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
import fr.f4ioz.satcombo.domain.ProfilsStation
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

    /** The memory of callsigns made again at the next key (contacts added from elsewhere). */
    fun oublieMemoire() { memoire = null }

    /** Every contact with this station (whatever its suffix: /P, /M…), the newest first. */
    fun historique(indicatif: String): List<LogEntry> {
        return fr.f4ioz.satcombo.demo.ApiStation.historique(store.load(), indicatif)
    }

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
            myLocator = e.locator, myGrids = mesCarres(), callsign = call, theirLocator = locator.trim().uppercase(),
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
        // A contact changed while it waits: its delay starts again.
        modifies[timeMs] = horloge()
        store.update(timeMs) {
            it.copy(callsign = m.callsign.trim().uppercase(), theirLocator = m.theirLocator.trim().uppercase(),
                note = m.note.trim(), mode = m.mode.trim(), rstSent = m.rstSent.trim(), rstRcvd = m.rstRcvd.trim(),
                nom = m.nom.trim(), qth = m.qth.trim(), courriel = m.courriel.trim(),
                locatorOrigine = if (m.theirLocator.trim().uppercase() == it.theirLocator) it.locatorOrigine else m.locatorOrigine)
        }
    }

    fun supprime(timeMs: Long) { store.delete(timeMs); oublieMemoire() }

    /** Several contacts (a selection on the page); how many went. */
    fun supprime(times: Set<Long>): Int {
        val avant = store.load().size
        val n = avant - store.delete(times).size
        oublieMemoire()
        return n
    }

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
        if (s.carnetUrl.isBlank() || s.carnetCle.isBlank())
            return "renseigne l'adresse et une clé en écriture du carnet en ligne"
        val e = store.load().firstOrNull { it.timeMs == timeMs } ?: return "contact introuvable"
        if (profils.isEmpty()) releveProfils()
        val profil = profilPour(e) ?: return "aucun profil Wavelog pour " + ProfilsStation.nomEmplacement(lieuDe(e)) + " : le choisir ou le créer dans Réglages"
        val adif = Adif.enregistrement(e, stationDuProfil(profil), s.callsign)
        if (adif.isBlank()) return "contact incomplet"
        val r = CarnetEnLigne.depose(s.carnetUrl, s.carnetCle, profil, adif)
        val pris = CarnetEnLigne.accepte(r) || CarnetEnLigne.doublon(r)
        if (pris) store.marqueEnvoye(timeMs, horloge())
        return if (pris) "${e.callsign} déposé au carnet en ligne" else CarnetEnLigne.raison(r)
    }

    // ------------------------------------------- Wavelog's station locations, as on the phone

    /** The station locations the online log declares (read by [releveProfils]). */
    @Volatile var profils: List<ProfilsStation.Profil> = emptyList(); private set
    @Volatile private var profilsEssai = 0L

    /**
     * The big squares the station sits in now: its own, then those within
     * the distance chosen (a site on a grid line) — the phone's rule. Empty
     * when it is inside one square only.
     */
    fun mesCarres(): String {
        val s = station.settings
        val mien = station.etat.value.locator.take(4).uppercase()
        val obs = station.etat.value.observateur
        val voisins = if (s.nearGridMeters <= 0 || obs == null) emptyList()
            else runCatching { Maidenhead.neighbourFields(obs.latDeg, obs.lonDeg, s.nearGridMeters.toDouble()).map { it.first } }.getOrDefault(emptyList())
        val l = (listOf(mien) + voisins.take(3)).filter { it.length == 4 }.distinct()
        return if (l.size <= 1) "" else l.joinToString(",")
    }

    /** Where the station is now, as a location (one square, or the squares of a grid line). */
    fun ici(): Set<String> = ProfilsStation.emplacement(station.etat.value.locator, mesCarres())

    fun lieuDe(e: LogEntry): Set<String> = ProfilsStation.emplacement(e.myLocator, e.myGrids)

    /** Reads the profiles from the online log; null when read, else why not. */
    suspend fun releveProfils(): String? {
        val s = station.settings
        if (s.carnetUrl.isBlank() || s.carnetCle.isBlank()) return "renseigne l'adresse et la clé, puis Enregistrer"
        return runCatching { profils = CarnetEnLigne.profils(s.carnetUrl, s.carnetCle); null }
            .getOrElse { "pas de réponse : " + (it.message ?: it.javaClass.simpleName) }
    }

    /**
     * The profile a contact goes to: the one covering **exactly** where it
     * was made from; for here, the one chosen in the settings when none
     * matches; null when there is none.
     */
    fun profilPour(e: LogEntry): String? = profilPourLieu(lieuDe(e))

    fun profilPourLieu(lieu: Set<String>): String? {
        val trouve = ProfilsStation.apparieEmplacements(listOf(lieu), profils, station.settings.callsign)[lieu]
        if (trouve != null) return trouve
        // Here, or no list read: the profile chosen by hand.
        val choisi = station.settings.carnetProfil.ifBlank { null }
        return if (profils.isEmpty() || lieu == ici()) choisi else null
    }

    /** Does a profile cover exactly [lieu] (rather than being only the one chosen by hand)? */
    fun couvre(id: String?, lieu: Set<String>): Boolean =
        id != null && ProfilsStation.apparieEmplacements(listOf(lieu), profils, station.settings.callsign)[lieu] == id

    /** The station callsign of a profile ("F4IOZ/P"), else the settings' (Wavelog refuses a different one). */
    fun stationDuProfil(id: String): String =
        profils.firstOrNull { it.id == id }?.indicatif?.trim()?.uppercase()?.ifBlank { null } ?: station.settings.callsign

    /** The log's locations, with how many contacts each, and the profile matching it. */
    fun lieuxDuCarnet(): List<Triple<Set<String>, Int, String?>> =
        store.load().filter { it.callsign.isNotBlank() }.groupingBy { lieuDe(it) }.eachCount()
            .entries.sortedBy { ProfilsStation.nomEmplacement(it.key) }
            .map { (lieu, n) -> Triple(lieu, n, profilPourLieu(lieu)) }

    /**
     * Creates in the online log the profiles for [lieux] ("CALL @ JN18");
     * Wavelog needs the DXCC, the CQ and ITU zones. Then reads them again.
     */
    suspend fun creeProfils(lieux: List<Set<String>>, dxcc: String, pays: String, cq: String, itu: String): String {
        val s = station.settings
        if (itu.isBlank()) return "zone ITU obligatoire : Wavelog refuse ensuite chaque contact d'un profil sans elle"
        var faits = 0; var dernier = ""
        for (lieu in lieux) {
            val carre = ProfilsStation.nomEmplacement(lieu)
            if (carre == "—") continue
            val r = CarnetEnLigne.creeProfil(s.carnetUrl, s.carnetCle, s.callsign.trim().uppercase() + " @ " + carre, carre, s.callsign, dxcc, pays, cq, itu)
            if (CarnetEnLigne.profilEnPlace(r)) faits++ else dernier = CarnetEnLigne.raison(r)
        }
        releveProfils()
        return "$faits profil(s) créé(s) sur ${lieux.size}" + (if (dernier.isBlank()) "" else " · $dernier")
    }

    // ------------------------------------------- each contact on its own, as on the phone

    /** Last change of each contact, which restarts its delay. In memory only. */
    private val modifies = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    /** Why the server refused a contact (it is then held back). */
    private val refus = java.util.concurrent.ConcurrentHashMap<Long, String>()
    /** No reply: not before this time (the request may have gone through). */
    private val reessai = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    @Volatile private var envoiEnCours = false
    /** The last automatic upload, or why it stopped, in words. */
    @Volatile var autoEtat = ""; private set

    fun etatEnvoi(e: LogEntry, maintenant: Long = horloge()): fr.f4ioz.satcombo.domain.EnvoiAuto.Etat {
        val s = station.settings
        return fr.f4ioz.satcombo.domain.EnvoiAuto.etat(e.timeMs, e.callsign, e.envoyeMs, e.retenu,
            if (s.carnetAuto) s.carnetAutoDepuisMs else null, modifies[e.timeMs], maintenant, s.carnetAutoDelaiS * 1000L)
    }

    fun resteEnvoiS(e: LogEntry, maintenant: Long = horloge()): Long =
        fr.f4ioz.satcombo.domain.EnvoiAuto.resteS(e.timeMs, modifies[e.timeMs], maintenant, station.settings.carnetAutoDelaiS * 1000L)

    fun refusEnvoi(timeMs: Long): String = refus[timeMs].orEmpty()

    /** On or off; only what is logged from now on leaves (an older log may already be online through an ADIF). */
    fun setAuto(on: Boolean) {
        val s = station.settings
        if (on && !s.carnetAuto) s.carnetAutoDepuisMs = horloge()
        s.carnetAuto = on
        autoEtat = ""
    }

    /** Holds a contact back, or releases it (a refusal is then forgotten). */
    fun retiens(timeMs: Long, retenu: Boolean) {
        if (!retenu) { refus.remove(timeMs); reessai.remove(timeMs) }
        store.retiens(timeMs, retenu)
    }

    /**
     * Every second: the oldest contact whose delay is over goes to the online
     * log, one at a time, marked only once the server took it.
     */
    fun ticEnvoi() {
        val s = station.settings
        if (!s.carnetAuto || envoiEnCours) return
        if (s.carnetUrl.isBlank() || s.carnetCle.isBlank()) return
        val maintenant = horloge()
        val e = store.load().filter {
            etatEnvoi(it, maintenant) == fr.f4ioz.satcombo.domain.EnvoiAuto.Etat.PRET && (reessai[it.timeMs] ?: 0L) <= maintenant
        }.minByOrNull { it.timeMs } ?: return
        envoiEnCours = true
        kotlin.concurrent.thread(isDaemon = true, name = "satme-envoi") {
            try {
                // The profiles once, before the first upload: each contact to its own location's.
                if (profils.isEmpty() && maintenant - profilsEssai > 600_000L) { profilsEssai = maintenant; kotlinx.coroutines.runBlocking { releveProfils() } }
                val profil = profilPour(e)
                if (profil == null) {
                    store.retiens(e.timeMs, true)
                    refus[e.timeMs] = "aucun profil Wavelog pour " + ProfilsStation.nomEmplacement(lieuDe(e))
                    autoEtat = "${e.callsign} en pause : " + refus[e.timeMs] + " (Réglages)"
                    return@thread
                }
                val adif = Adif.enregistrement(e, stationDuProfil(profil), s.callsign)
                if (adif.isBlank()) { store.retiens(e.timeMs, true); refus[e.timeMs] = "contact incomplet"; return@thread }
                val r = kotlinx.coroutines.runBlocking { CarnetEnLigne.depose(s.carnetUrl, s.carnetCle, profil, adif) }
                val issue = CarnetEnLigne.issue(r)
                autoEtat = when {
                    CarnetEnLigne.doublon(r) -> { store.marqueEnvoye(e.timeMs, horloge()); "${e.callsign} déjà dans le carnet en ligne" }
                    issue == CarnetEnLigne.Issue.PRIS -> { store.marqueEnvoye(e.timeMs, horloge()); "${e.callsign} envoyé au carnet en ligne" }
                    issue == CarnetEnLigne.Issue.REFUS -> {
                        // Held back: sent again, it would be refused again.
                        val pourquoi = CarnetEnLigne.raison(r)
                        refus[e.timeMs] = pourquoi; store.retiens(e.timeMs, true)
                        "${e.callsign} refusé : $pourquoi (mis en pause)"
                    }
                    else -> {
                        reessai[e.timeMs] = horloge() + fr.f4ioz.satcombo.domain.EnvoiAuto.DELAI_MS
                        "${e.callsign} : pas de réponse claire, nouvel essai dans une minute"
                    }
                }
            } catch (x: Exception) {
                reessai[e.timeMs] = horloge() + fr.f4ioz.satcombo.domain.EnvoiAuto.DELAI_MS
                autoEtat = "${e.callsign} : " + (x.message ?: "échec")
            } finally {
                envoiEnCours = false
            }
        }
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
