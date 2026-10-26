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
import fr.f4ioz.satcombo.domain.ChoixCarnet
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

    /** Told of each contact the operator logs (the satellite link). */
    val ecouteurs = java.util.concurrent.CopyOnWriteArrayList<(LogEntry) -> Unit>()
    private fun annonce(c: LogEntry) = ecouteurs.forEach { runCatching { it(c) } }

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
            fr.f4ioz.satcombo.domain.Indicatifs.suggestions(saisie, m, horloge(),
                choix().satellite, max = 4)
    }

    /** The memory of callsigns made again at the next key (contacts added from elsewhere). */
    fun oublieMemoire() { memoire = null }

    /** Every contact with this station (whatever its suffix: /P, /M…), the newest first. */
    fun historique(indicatif: String): List<LogEntry> {
        return fr.f4ioz.satcombo.demo.ApiStation.historique(store.load(), indicatif)
    }

    /**
     * The satellite a contact goes on (set by SatMe-PC, [ChoixCarnet]): the
     * one chosen in the form, else the rig's, QO-100 from the cockpit's form.
     * Unset: the satellite followed.
     */
    @Volatile var choix: ((ChoixCarnet.Demande, Boolean) -> ChoixCarnet.Choix)? = null

    /** QO-100's dial and log mode now, for a contact on it. */
    @Volatile var qo100: (() -> fr.f4ioz.satcombo.domain.TraficQo100.Contexte)? = null

    fun choix(demande: ChoixCarnet.Demande = ChoixCarnet.Demande.AUTO, depuisCockpit: Boolean = false): ChoixCarnet.Choix =
        choix?.invoke(demande, depuisCockpit) ?: station.etat.value.suivi.let {
            ChoixCarnet.choix(demande, depuisCockpit, false, false, station.etat.value.catConnecte, it?.name, it?.catalogNumber ?: 0)
        }

    /**
     * A new contact, now; null without a callsign or a satellite. On
     * [demande]'s satellite, else the rig's ([ChoixCarnet]);
     * [depuisCockpit]: typed in the QO-100 cockpit.
     */
    fun ajoute(indicatif: String, locator: String, rstEnvoye: String, rstRecu: String, note: String = "",
               fiche: QrzReponse.Fiche? = null, demande: ChoixCarnet.Demande = ChoixCarnet.Demande.AUTO,
               depuisCockpit: Boolean = false): LogEntry? {
        val call = indicatif.trim().uppercase()
        if (call.isEmpty()) return null
        val e = station.etat.value
        val ch = choix(demande, depuisCockpit)
        // QO-100 followed as a satellite keeps its transmitter's frequencies; chosen otherwise, the dial's.
        val qo = if (ch.qo100 && e.suivi?.catalogNumber != ch.catnum) (qo100?.invoke() ?: fr.f4ioz.satcombo.domain.TraficQo100.surQo100(station.qo100.etat.value.descenteHz,
            station.settings.qo100ModeCarnet, fr.f4ioz.satcombo.domain.TraficQo100.Par.AUCUN)) else null
        val sat = e.suivi
        if (qo == null && sat == null) return null
        val t = station.emetteurChoisi()
        val maintenant = horloge()
        // Who it is, as already known: a past contact, else what QRZ said while it was typed.
        val base = call.substringBefore('/').let { if (it.length < 3) call else it }
        val passes = store.load().filter { it.callsign.substringBefore('/') == base }.sortedByDescending { it.timeMs }
        // On QO-100: its dish's aim, its sky frequencies, the mode of the signal tuned.
        val ciel = if (qo != null) e.observateur?.let { fr.f4ioz.satcombo.domain.Qo100.pointage(it.latDeg, it.lonDeg) } else null
        val (dl, ul) = if (qo != null) qo.descenteHz to qo.monteeHz else frequencesAuRepos()
        val pos = e.position
        val c = LogEntry(
            timeMs = maintenant, satName = qo?.satellite ?: sat!!.name, catnum = qo?.catnum ?: sat!!.catalogNumber,
            azimuthDeg = if (qo != null) ciel?.azDeg ?: 0.0 else pos?.azimuthDeg ?: 0.0,
            elevationDeg = if (qo != null) ciel?.elDeg ?: 0.0 else pos?.elevationDeg ?: 0.0,
            myLocator = e.locator, myGrids = mesCarres(), callsign = call, theirLocator = locator.trim().uppercase(),
            rstSent = rstEnvoye.trim(), rstRcvd = rstRecu.trim(), note = note.trim(),
            locatorOrigine = if (locator.isBlank()) "INCONNU" else if (fiche != null && locator.trim().equals(fiche.carre, true)) "QRZ" else "SAISI",
            mode = qo?.modeCarnet ?: mode(t),
            downlinkMhz = dl?.let { it / 1e6 } ?: 0.0, uplinkMhz = ul?.let { it / 1e6 } ?: 0.0,
            nom = passes.firstOrNull { it.nom.isNotBlank() }?.nom ?: fiche?.nom.orEmpty(),
            qth = passes.firstOrNull { it.qth.isNotBlank() }?.qth ?: fiche?.qth.orEmpty(),
            courriel = fiche?.courriel.orEmpty())
        store.add(c); oublieMemoire()
        annonce(c)
        return c
    }

    /**
     * A DATV contact, now, in mode DATV: on QO-100 when [cielKhz] (the
     * picture received) is in its wide band transponder (the dish's aim, both
     * frequencies), else terrestrial (no satellite, the frequency received).
     */
    fun ajouteDatv(indicatif: String, locator: String, rstEnvoye: String, rstRecu: String, note: String, cielKhz: Long,
                   fiche: QrzReponse.Fiche? = null): LogEntry? {
        val call = indicatif.trim().uppercase()
        if (call.isEmpty() || cielKhz <= 0) return null
        val e = station.etat.value
        val qo = fr.f4ioz.satcombo.datv.IndicatifsImage.qo100(cielKhz)
        val ciel = if (qo) e.observateur?.let { fr.f4ioz.satcombo.domain.Qo100.pointage(it.latDeg, it.lonDeg) } else null
        val passes = historique(call)
        val c = LogEntry(
            timeMs = horloge(), satName = if (qo) fr.f4ioz.satcombo.domain.TraficQo100.NOM else "",
            catnum = if (qo) fr.f4ioz.satcombo.domain.Qo100.NORAD else 0,
            azimuthDeg = ciel?.azDeg ?: 0.0, elevationDeg = ciel?.elDeg ?: 0.0,
            myLocator = e.locator, myGrids = mesCarres(), callsign = call, theirLocator = locator.trim().uppercase(),
            rstSent = rstEnvoye.trim(), rstRcvd = rstRecu.trim(), note = note.trim(),
            locatorOrigine = if (locator.isBlank()) "INCONNU" else if (fiche != null && locator.trim().equals(fiche.carre, true)) "QRZ" else "SAISI",
            mode = "DATV",
            downlinkMhz = cielKhz / 1000.0, uplinkMhz = if (qo) fr.f4ioz.satcombo.datv.IndicatifsImage.monteeKhz(cielKhz) / 1000.0 else 0.0,
            nom = passes.firstOrNull { it.nom.isNotBlank() }?.nom ?: fiche?.nom.orEmpty(),
            qth = passes.firstOrNull { it.qth.isNotBlank() }?.qth ?: fiche?.qth.orEmpty(),
            courriel = fiche?.courriel.orEmpty())
        store.add(c); oublieMemoire()
        annonce(c)
        return c
    }

    /**
     * A contact typed afterwards: on [catnum], at [quandMs] (a past pass),
     * its az/el where the satellite was then, the mode given; who it is as
     * already known, else as QRZ says. Null without a callsign, or on an
     * unknown satellite.
     */
    fun ajouteApres(catnum: Int, quandMs: Long, indicatif: String, locator: String, rstEnvoye: String, rstRecu: String,
                    note: String, mode: String, fiche: QrzReponse.Fiche? = null,
                    /** The rig's frequencies then (sky), when known: the journal's, read on CAT during the pass. */
                    dlHz: Long? = null, ulHz: Long? = null, nom: String = "", qth: String = ""): LogEntry? {
        val call = indicatif.trim().uppercase()
        if (call.isEmpty()) return null
        val c0 = station.contactApres(catnum, quandMs, call, locator, rstEnvoye, rstRecu, note, mode) ?: return null
        val passes = historique(call)
        val c = c0.copy(myGrids = mesCarres(),
            locatorOrigine = if (fiche != null && locator.trim().equals(fiche.carre, true)) "QRZ" else c0.locatorOrigine,
            nom = nom.trim().ifBlank { passes.firstOrNull { it.nom.isNotBlank() }?.nom ?: fiche?.nom.orEmpty() },
            qth = qth.trim().ifBlank { passes.firstOrNull { it.qth.isNotBlank() }?.qth ?: fiche?.qth.orEmpty() },
            courriel = fiche?.courriel.orEmpty(),
            downlinkMhz = dlHz?.let { it / 1e6 } ?: c0.downlinkMhz, uplinkMhz = ulHz?.let { it / 1e6 } ?: c0.uplinkMhz)
        store.add(c); oublieMemoire()
        annonce(c)
        return c
    }

    /**
     * A DATV contact, now, from the station card received: on QO-100 (its
     * wide band: [khz] the downlink received) or terrestrial; mode DATV, the
     * MER we measure on its signal as the report given ([rapport]). Null
     * without a callsign.
     */
    fun ajouteDatv(indicatif: String, locator: String, rapport: String, note: String, qo100: Boolean, khz: Long,
                   nom: String = "", qth: String = ""): LogEntry? {
        val call = indicatif.trim().uppercase()
        if (call.isEmpty()) return null
        val e = station.etat.value
        val ciel = if (qo100) e.observateur?.let { fr.f4ioz.satcombo.domain.Qo100.pointage(it.latDeg, it.lonDeg) } else null
        val passes = historique(call)
        val mhz = khz / 1000.0
        val c = LogEntry(
            timeMs = horloge(), satName = if (qo100) fr.f4ioz.satcombo.domain.TraficQo100.NOM else fr.f4ioz.satcombo.data.Adif.DATV_TERRESTRE,
            catnum = if (qo100) fr.f4ioz.satcombo.domain.Qo100.NORAD else 0,
            azimuthDeg = ciel?.azDeg ?: 0.0, elevationDeg = ciel?.elDeg ?: 0.0,
            myLocator = e.locator, myGrids = mesCarres(), callsign = call, theirLocator = locator.trim().uppercase(),
            rstSent = rapport.trim(), rstRcvd = "", note = note.trim(),
            locatorOrigine = if (locator.isBlank()) "INCONNU" else "SAISI", mode = "DATV",
            // QO-100: the downlink received, the uplink 8089.5 MHz below; terrestrial: the one frequency.
            downlinkMhz = mhz, uplinkMhz = if (qo100 && mhz > 0) mhz - fr.f4ioz.satcombo.domain.Qo100.DECALAGE_HZ / 1e6 else mhz,
            nom = nom.trim().ifBlank { passes.firstOrNull { it.nom.isNotBlank() }?.nom.orEmpty() },
            qth = qth.trim().ifBlank { passes.firstOrNull { it.qth.isNotBlank() }?.qth.orEmpty() })
        store.add(c); oublieMemoire()
        annonce(c)
        return c
    }

    /** The mode of the transmitter followed now, when it is [catnum]'s (a contact typed afterwards on it). */
    fun modeSuivi(catnum: Int): String? =
        if (station.etat.value.suivi?.catalogNumber == catnum) station.emetteurChoisi()?.let { mode(it) } else null

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

    /**
     * A contact moved to another satellite ([ChangeSatellite]): [nom] a
     * satellite known (its az/el then, its transmitter's frequencies), QO-100
     * (the dish's aim), any other name, or blank / « terrestre ». Null when
     * there is no such contact.
     */
    fun changeSatellite(timeMs: Long, nom: String, catnum: Int): LogEntry? {
        val avant = store.load().firstOrNull { it.timeMs == timeMs } ?: return null
        val e = station.etat.value
        val n = nom.trim()
        val qo = catnum == fr.f4ioz.satcombo.domain.Qo100.NORAD || n.equals(fr.f4ioz.satcombo.domain.TraficQo100.NOM, true)
        val sat = if (qo) null else e.satellites.firstOrNull { catnum > 0 && it.catalogNumber == catnum }
            ?: e.satellites.firstOrNull { n.isNotEmpty() && it.name.equals(n, true) }
        val azEl = if (qo) e.observateur?.let { fr.f4ioz.satcombo.domain.Qo100.pointage(it.latDeg, it.lonDeg) }?.let { it.azDeg to it.elDeg }
            else sat?.let { station.contactApres(it.catalogNumber, timeMs, "-", "", "", "", "", "") }?.let { it.azimuthDeg to it.elevationDeg }
        val f = sat?.let { fr.f4ioz.satcombo.domain.ChangeSatellite.frequencesNominales(station.emetteurDe(it.catalogNumber)) }
        val apres = fr.f4ioz.satcombo.domain.ChangeSatellite.applique(avant, sat?.name ?: n, sat?.catalogNumber ?: if (qo) fr.f4ioz.satcombo.domain.Qo100.NORAD else 0, azEl, f)
        if (apres == avant) return avant
        // As any edit: a contact waiting for the automatic upload gets its delay again.
        modifies[timeMs] = horloge()
        store.update(timeMs) { apres }
        oublieMemoire()
        return apres
    }

    /**
     * A contact given another time ([fr.f4ioz.satcombo.domain.RedateContact]):
     * az/el where its satellite was then, the log in order again. Null when
     * there is no such contact.
     */
    fun redate(timeMs: Long, nouveauMs: Long): fr.f4ioz.satcombo.domain.RedateContact.Resultat? {
        val e = station.etat.value
        val r = fr.f4ioz.satcombo.domain.RedateContact.redate(store.load(), timeMs, nouveauMs) { c, t ->
            val sat = e.satellites.firstOrNull { c.catnum > 0 && it.catalogNumber == c.catnum }
                ?: e.satellites.firstOrNull { c.satName.isNotBlank() && it.name.equals(c.satName, true) }
            sat?.let { station.contactApres(it.catalogNumber, t, "-", "", "", "", "", "") }?.let { it.azimuthDeg to it.elevationDeg }
        } ?: return null
        if (r.contact.timeMs == timeMs) return r
        store.save(r.log)
        // As any edit, under its new key: a contact waiting for the automatic upload gets its delay again.
        modifies.remove(timeMs); modifies[r.contact.timeMs] = horloge()
        oublieMemoire()
        return r
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

    /** The contacts chosen (their times), as ADIF; all when none is given. */
    fun adif(choisis: Set<Long>): String = if (choisis.isEmpty()) adif()
        else fr.f4ioz.satcombo.data.LogStore.toAdif(contacts().filter { it.timeMs in choisis && it.callsign.isNotBlank() }, station.settings.callsign)

    /** The contacts chosen, as the phone's batch for another SatMe (Settings › Backup › merge a batch). */
    fun lot(choisis: Set<Long>): String =
        fr.f4ioz.satcombo.data.LotContacts.ecrit(contacts().filter { it.timeMs in choisis && it.callsign.isNotBlank() }.sortedBy { it.timeMs },
            station.settings.callsign)

    /** The worked squares, for the page (a count of distinct 4-character squares). */
    fun carres(): Int = store.load().mapNotNull { it.theirLocator.takeIf { l -> l.length >= 4 }?.substring(0, 4)?.uppercase() }.distinct().size

    /** The mode the log records: what CAT sets (USB down, LSB up when inverting) on a linear transponder. */
    private fun mode(t: fr.f4ioz.satcombo.data.Transmitter?): String = fr.f4ioz.satcombo.domain.NouveauPassage.modeEmetteur(t)

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
        ModeDemo.refus("l'envoi au carnet en ligne")?.let { return it }
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
        ModeDemo.refus("la création de profils en ligne")?.let { return it }
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
    /** The AMSAT push ([fr.f4ioz.satcombo.domain.PushAmsat]): a pass over with a contact, reported "Heard". */
    fun ticAmsat() {
        // The demonstration's made-up callsign never reaches AMSAT, Wavelog, QRZ…
        if (ModeDemo.actif) return
        val e = station.etat.value
        station.pushAmsat.tic(store.load(), e.indicatif, e.locator) { station.statutAmsat(it)?.name ?: it }
    }

    fun ticEnvoi() {
        if (ModeDemo.actif) return
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
        ModeDemo.refus("l'envoi au carnet en ligne")?.let { return it }
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
