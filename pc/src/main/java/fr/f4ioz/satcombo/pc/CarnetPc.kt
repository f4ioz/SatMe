/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.data.Adif
import fr.f4ioz.satcombo.data.CarnetEnLigne
import fr.f4ioz.satcombo.data.LogEntry
import fr.f4ioz.satcombo.data.LogStore
import fr.f4ioz.satcombo.data.Qrz
import fr.f4ioz.satcombo.domain.Doppler
import fr.f4ioz.satcombo.domain.QrzReponse
import fr.f4ioz.satcombo.location.Maidenhead
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The contact log on the PC: the phone's file and format (`qso_log.json`,
 * ADIF), the phone's rules.
 *
 * **A contact is timestamped when saved**, on the satellite followed, with
 * where it was (az, el), my square, the transmitter's mode and both
 * frequencies **at rest** (Doppler taken off: what the online log and LoTW
 * want). Name and town come from the log itself, else from QRZ.
 */
class CarnetPc(private val station: StationPc, val store: LogStore, private val horloge: () -> Long = System::currentTimeMillis) {

    private val qrz = Qrz()

    fun contacts(): List<LogEntry> = store.load().sortedByDescending { it.timeMs }

    // ------------------------------------------------------------ the callsigns known

    /** What the log knows of each station (the phone's memory), kept a few seconds: asked at every key. */
    @Volatile private var memoire: Pair<Long, List<fr.f4ioz.satcombo.domain.Indicatifs.Connu>>? = null

    fun memoire(): List<fr.f4ioz.satcombo.domain.Indicatifs.Connu> {
        memoire?.takeIf { horloge() - it.first < 5_000L }?.let { return it.second }
        val m = fr.f4ioz.satcombo.domain.Indicatifs.memoire(store.load().filter { it.callsign.isNotBlank() }.map {
            fr.f4ioz.satcombo.domain.Indicatifs.Contact(indicatif = it.callsign, locator = it.theirLocator, quandMs = it.timeMs,
                satellite = it.satName, nom = it.nom)
        })
        memoire = horloge() to m
        return m
    }

    /**
     * For a callsign being typed: whether it was worked (or looks plausible,
     * or unusual), and the stations it may be, as the phone ranks them —
     * contacts, how recent, the same satellite first during a pass.
     */
    fun propositions(saisie: String): Pair<fr.f4ioz.satcombo.domain.Indicatifs.Etat, List<fr.f4ioz.satcombo.domain.Indicatifs.Connu>> {
        val m = memoire()
        return fr.f4ioz.satcombo.domain.Indicatifs.etat(saisie, m) to
            fr.f4ioz.satcombo.domain.Indicatifs.suggestions(saisie, m, horloge(), station.etat.value.suivi?.name.orEmpty(), max = 4)
    }

    private fun oublieMemoire() { memoire = null }

    /** A new contact, now; null without a callsign or a satellite. */
    fun ajoute(indicatif: String, locator: String, rstEnvoye: String, rstRecu: String, note: String = "",
               fiche: QrzReponse.Fiche? = null): LogEntry? {
        val call = indicatif.trim().uppercase()
        if (call.isEmpty()) return null
        val e = station.etat.value
        val sat = e.suivi ?: return null
        val pos = e.position
        val t = station.emetteurChoisi()
        val maintenant = horloge()
        // Who it is, as already known: a past contact, else what QRZ said while it was typed.
        val base = call.substringBefore('/').let { if (it.length < 3) call else it }
        val passes = store.load().filter { it.callsign.substringBefore('/') == base }.sortedByDescending { it.timeMs }
        val (dl, ul) = frequencesAuRepos()
        val c = LogEntry(
            timeMs = maintenant, satName = sat.name, catnum = sat.catalogNumber,
            azimuthDeg = pos?.azimuthDeg ?: 0.0, elevationDeg = pos?.elevationDeg ?: 0.0,
            myLocator = e.locator, callsign = call, theirLocator = locator.trim().uppercase(),
            rstSent = rstEnvoye.trim(), rstRcvd = rstRecu.trim(), note = note.trim(),
            locatorOrigine = if (locator.isBlank()) "INCONNU" else if (fiche != null && locator.trim().equals(fiche.carre, true)) "QRZ" else "SAISI",
            mode = mode(t),
            downlinkMhz = dl?.let { it / 1e6 } ?: 0.0, uplinkMhz = ul?.let { it / 1e6 } ?: 0.0,
            nom = passes.firstOrNull { it.nom.isNotBlank() }?.nom ?: fiche?.nom.orEmpty(),
            qth = passes.firstOrNull { it.qth.isNotBlank() }?.qth ?: fiche?.qth.orEmpty(),
            courriel = fiche?.courriel.orEmpty())
        store.add(c); oublieMemoire()
        return c
    }

    fun modifie(timeMs: Long, m: LogEntry) {
        oublieMemoire()
        store.update(timeMs) {
            it.copy(callsign = m.callsign.trim().uppercase(), theirLocator = m.theirLocator.trim().uppercase(),
                note = m.note.trim(), mode = m.mode.trim(), rstSent = m.rstSent.trim(), rstRcvd = m.rstRcvd.trim(),
                nom = m.nom.trim(), qth = m.qth.trim(), courriel = m.courriel.trim(),
                locatorOrigine = if (m.theirLocator.trim().uppercase() == it.theirLocator) it.locatorOrigine else m.locatorOrigine)
        }
    }

    fun supprime(timeMs: Long) { store.delete(timeMs); oublieMemoire() }

    fun adif(): String = store.toAdif(station.settings.callsign)

    /** The worked squares, for the page (a count of distinct 4-character squares). */
    fun carres(): Int = store.load().mapNotNull { it.theirLocator.takeIf { l -> l.length >= 4 }?.substring(0, 4)?.uppercase() }.distinct().size

    /** The mode the log records: what CAT sets (USB down, LSB up when inverting) on a linear transponder. */
    private fun mode(t: fr.f4ioz.satcombo.data.Transmitter?): String {
        val m = t?.mode.orEmpty().uppercase()
        return when {
            m.contains("FM") -> "FM"
            m.contains("CW") -> "CW"
            t != null && t.isTransponder -> if (t.invert) "LSB" else "USB"
            m.contains("LSB") -> "LSB"
            m.contains("USB") -> "USB"
            else -> m.ifBlank { "FM" }
        }
    }

    /** Both frequencies at rest (sky, no Doppler), as the phone logs them. */
    fun frequencesAuRepos(): Pair<Long?, Long?> {
        val t = station.emetteurChoisi() ?: return null to null
        val lo = t.downlinkLowHz; val hi = t.downlinkHighHz ?: lo
        val dl = station.etat.value.repos ?: lo?.let { (it + (hi ?: it)) / 2 }
        val ul = if (t.isTransponder && dl != null && lo != null && t.uplinkLowHz != null)
            Doppler.transponderUplinkRest(dl, lo, hi ?: lo, t.uplinkLowHz!!, t.uplinkHighHz ?: t.uplinkLowHz!!, t.invert)
        else t.uplinkLowHz
        return dl to ul
    }

    // ------------------------------------------------------------------ QRZ

    /**
     * A callsign's QRZ record, the session opened when needed (QRZ counts
     * logins: only then). Null without credentials or network.
     */
    suspend fun ficheQrz(indicatif: String): QrzReponse.Fiche? = withContext(Dispatchers.IO) {
        val s = station.settings
        if (s.qrzUser.isBlank() || s.qrzMdp.isBlank()) return@withContext null
        val ind = indicatif.trim().uppercase()
        runCatching {
            synchronized(qrz) {
                var f = qrz.cherche(ind)
                if (f.erreur.contains("connect", true) || f.erreur.contains("session", true)) {
                    if (qrz.connecte(s.qrzUser, s.qrzMdp).isBlank()) f = qrz.cherche(ind)
                }
                f
            }
        }.getOrNull()
    }

    // ------------------------------------------------------- the online log

    /**
     * Uploads one contact to Wavelog / Cloudlog (the settings' address, write
     * key and station profile); marked as sent only once the server took it
     * (or already had it). The server's answer, in words.
     */
    suspend fun depose(timeMs: Long): String {
        val s = station.settings
        if (s.carnetUrl.isBlank() || s.carnetCle.isBlank() || s.carnetProfil.isBlank())
            return "renseigne l'adresse, une clé en écriture et l'ID du profil du carnet en ligne"
        val e = store.load().firstOrNull { it.timeMs == timeMs } ?: return "contact introuvable"
        val adif = Adif.enregistrement(e, s.callsign)
        if (adif.isBlank()) return "contact incomplet"
        val r = CarnetEnLigne.depose(s.carnetUrl, s.carnetCle, s.carnetProfil, adif)
        val pris = CarnetEnLigne.accepte(r) || CarnetEnLigne.doublon(r)
        if (pris) store.marqueEnvoye(timeMs, horloge())
        return if (pris) "${e.callsign} déposé au carnet en ligne" else CarnetEnLigne.raison(r)
    }

    /** Every contact not yet uploaded, oldest first, stopping at the first refusal. */
    suspend fun deposeTout(): String {
        val a = store.load().filter { it.envoyeMs == 0L && it.callsign.isNotBlank() && !it.retenu }.sortedBy { it.timeMs }
        if (a.isEmpty()) return "tout est déjà déposé"
        var n = 0
        for (e in a) {
            val r = depose(e.timeMs)
            if (!r.endsWith("déposé au carnet en ligne")) return "$n déposé(s) · arrêt : $r"
            n++
        }
        return "$n déposé(s)"
    }

    companion object {
        /** A square that names a place (JN18 or JN18FS), for the page's check. */
        fun carreValide(l: String) = l.isBlank() || Maidenhead.centre(l.trim()) != null
    }
}
