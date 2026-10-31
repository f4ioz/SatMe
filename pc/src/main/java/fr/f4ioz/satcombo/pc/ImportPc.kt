/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.data.AdifImport
import fr.f4ioz.satcombo.data.CarnetEnLigne
import fr.f4ioz.satcombo.data.LogEntry
import fr.f4ioz.satcombo.data.Observer
import fr.f4ioz.satcombo.domain.FiltreMoisson
import fr.f4ioz.satcombo.domain.JournalPassage
import fr.f4ioz.satcombo.domain.PassPredictor
import fr.f4ioz.satcombo.domain.ProfilsStation
import fr.f4ioz.satcombo.location.Maidenhead

/**
 * Satellite contacts brought into the PC as the phone brings them: from the
 * online log (Wavelog / Cloudlog, a read key is enough) or from an ADIF file.
 * Each one not in the log yet is added with where the satellite was then
 * (from the station's square of the time), and the passes they belong to
 * are rebuilt into the journal.
 */
class ImportPc(private val station: StationPc, private val carnet: CarnetPc, private val journal: JournalPc,
               private val horloge: () -> Long = System::currentTimeMillis) {

    private val predicteur = PassPredictor()

    /** [inconnus]: on a satellite the AMSAT bulletin does not know (logged without its position, no pass rebuilt). */
    data class Bilan(val lus: Int, val ajoutes: Int, val passages: Int, val inconnus: Int, val message: String = "")

    /** The station locations declared in the online log, each with its square. */
    suspend fun profils(): Pair<List<ProfilsStation.Profil>, String?> {
        val s = station.settings
        if (s.carnetUrl.isBlank() || s.carnetCle.isBlank()) return emptyList<ProfilsStation.Profil>() to "renseigne l'adresse et la clé du carnet en ligne"
        return runCatching { CarnetEnLigne.profils(s.carnetUrl, s.carnetCle) to null }
            .getOrElse { emptyList<ProfilsStation.Profil>() to (it.message ?: it.javaClass.simpleName) }
    }

    /** Every satellite contact of the online log for these locations (all when empty), brought in. */
    suspend fun depuisEnLigne(ids: List<String>): Bilan {
        val (tous, erreur) = profils()
        if (erreur != null) return Bilan(0, 0, 0, 0, erreur)
        val choisis = tous.filter { ids.isEmpty() || it.id in ids }
        if (choisis.isEmpty()) return Bilan(0, 0, 0, 0, "aucun profil de station")
        val s = station.settings
        val l = ArrayList<AdifImport.ContactEnLigne>()
        var message = ""
        for (p in choisis) {
            val r = runCatching { CarnetEnLigne.moissonne(s.carnetUrl, s.carnetCle, listOf(p.id), 0L, FiltreMoisson.SAT) }
            r.exceptionOrNull()?.let { message = it.message ?: it.javaClass.simpleName }
            val m = r.getOrNull() ?: continue
            if (m.adif.isBlank()) { if (m.message.isNotBlank()) message = m.message; continue }
            // A contact without its own square takes its location's.
            l += AdifImport.contactsEnLigne(m.adif).map { q -> AdifImport.avecLieu(q, p.carre) }
        }
        if (l.isEmpty()) return Bilan(0, 0, 0, 0, message.ifBlank { "rien dans le carnet en ligne" })
        return importe(l, dejaEnLigne = true)
    }

    /** An ADIF file's satellite contacts brought in; [dejaEnLigne]: they are in the online log already (never sent again). */
    fun depuisAdif(texte: String, dejaEnLigne: Boolean): Bilan {
        val l = AdifImport.contactsEnLigne(texte)
        if (l.isEmpty()) return Bilan(0, 0, 0, 0, "aucun contact satellite dans ce fichier (SAT_NAME ou PROP_MODE=SAT)")
        return importe(l, dejaEnLigne)
    }

    /** The contacts not in the log yet, added; their passes rebuilt into the journal. */
    fun importe(contacts: List<AdifImport.ContactEnLigne>, dejaEnLigne: Boolean): Bilan {
        val J = JournalPassage
        val maintenant = horloge()
        var ajoutes = 0; var inconnus = 0
        val locaux = carnet.contacts().toMutableList()
        val evts = ArrayList<JournalPassage.Evenement>()
        for (q in contacts) {
            // A satellite the bulletin no longer carries (silent, re-entered): the contact is kept all
            // the same — a log loses nothing — without its position, and no pass rebuilt for it.
            val sat = journal.satImport(0, q.satellite)
            if (sat == null) inconnus++
            else evts += JournalPassage.Evenement(JournalPassage.Source.CARNET, q.quandMs, sat.catalogNumber, sat.name, q.monLocator)
            val nomSat = sat?.name ?: q.satellite
            val deja = locaux.any { J.indicatifDeBase(it.callsign) == J.indicatifDeBase(q.indicatif) &&
                kotlin.math.abs(it.timeMs - q.quandMs) <= 120_000L && ((sat != null && it.catnum == sat.catalogNumber) || J.memeSatellite(it.satName, nomSat)) }
            if (deja) continue
            val obs = Maidenhead.centre(q.monLocator.ifBlank { q.monGrilles.substringBefore(",") })?.let { (la, lo) -> Observer(la, lo) } ?: station.etat.value.observateur
            val pos = if (sat != null && obs != null) runCatching { predicteur.positionAt(sat, obs, q.quandMs) }.getOrNull() else null
            val e = LogEntry(timeMs = q.quandMs, satName = nomSat, catnum = sat?.catalogNumber ?: 0,
                azimuthDeg = pos?.azimuthDeg ?: 0.0, elevationDeg = pos?.elevationDeg ?: 0.0,
                myLocator = q.monLocator, myGrids = q.monGrilles, callsign = q.indicatif, theirLocator = q.locator,
                nom = q.nom, qth = q.qth, mode = q.mode, rstSent = q.rstEnvoye, rstRcvd = q.rstRecu,
                downlinkMhz = q.freqRxMhz, uplinkMhz = q.freqMhz,
                envoyeMs = if (dejaEnLigne) maintenant else 0L)
            carnet.store.add(e); locaux += e; ajoutes++
        }
        val passages = journal.importe(journal.candidats(evts))
        return Bilan(contacts.size, ajoutes, passages, inconnus)
    }
}
