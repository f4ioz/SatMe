/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import fr.f4ioz.satcombo.data.SatPass
import fr.f4ioz.satcombo.domain.CampagneSstv.Campagne
import fr.f4ioz.satcombo.domain.CampagneSstv.Statut
import fr.f4ioz.satcombo.domain.CampagneSstv.Trace

/**
 * The SSTV auto-pilot, receive only: for each pass a campaign keeps, a few
 * minutes before AOS the chain is set up (the satellite followed, the rig and
 * the mast with it, the sound, the decoder held on its mode), the sound
 * recorded from AOS, stopped after LOS; then the pictures noted, the mast
 * parked if asked, the receive state put back as it was.
 *
 * A transmission on, or another function holding what the chain needs: the
 * pass is set aside, its reason kept — never a transmission interrupted.
 * The station's gestures go through [Station] (the PC's, or the tests' fake).
 */
class PiloteSstv(private val station: Station, private val horloge: () -> Long = System::currentTimeMillis,
                 /** Each pass done or set aside, to keep (the history). */
                 private val consigne: (Trace) -> Unit = {}) {

    /** What the auto-pilot asks of the station. */
    interface Station {
        /** The transmission on the air now, said; null when none. */
        fun emission(): String?
        /** Why [c]'s chain cannot be set up now (a receiver held by DATV, the automatic mode on another satellite…); null when free. */
        fun conflit(c: Campagne): String?
        /** The receive state now, to put back afterwards. */
        fun capture(): Map<String, String>
        /** [c]'s chain set up for pass [p]: what was done; an error stops it (the state then put back). */
        fun prepare(c: Campagne, p: SatPass): Preparation
        /** The recording started; null when it runs, else why not. */
        fun enregistre(c: Campagne): String?
        /** The recording stopped: its file's name ("" none). */
        fun arreteEnregistrement(): String
        /** The pictures received between [deMs] and [aMs] (their names). */
        fun images(deMs: Long, aMs: Long): List<String>
        /**
         * The partial pictures received between [deMs] and [aMs] that are one
         * picture cut in two, put together (« Réunir »): the names made.
         */
        fun reunitPartielles(deMs: Long, aMs: Long): List<String> = emptyList()
        /** The mast parked; null when done, else why not. */
        fun parque(): String?
        /** The receive state [avant] put back: what was done. */
        fun restaure(avant: Map<String, String>): List<String>
        fun annonce(texte: String)
    }

    data class Preparation(val faits: List<String>, val erreur: String? = null)

    enum class Phase { PRET, EN_COURS }

    data class EnCours(val campagne: Campagne, val passage: SatPass, val phase: Phase, val avant: Map<String, String>,
                       val faits: List<String>, val enregistrement: String = "", val repris: Boolean = false)

    @Volatile var enCours: EnCours? = null; private set
    @Volatile private var arretDemande: String? = null

    /** The pass under way kept on disk before a restart: taken up again if it still is (its state to put back with it). */
    fun reprend(c: Campagne, p: SatPass, avant: Map<String, String>) {
        if (enCours != null) return
        val t = horloge()
        if (t >= p.losEpochMs + CampagneSstv.APRES_LOS_MS) return
        val r = station.prepare(c, p)
        enCours = EnCours(c, p, if (t >= p.aosEpochMs) Phase.EN_COURS else Phase.PRET, avant, r.faits + "repris après un redémarrage", repris = true)
        if (t >= p.aosEpochMs && c.chaine.enregistrer) station.enregistre(c)
    }

    /** « STOP »: the pass under way ended now (recording stopped, state put back). */
    fun stop(raison: String = "arrêté à la main") { arretDemande = raison }

    /**
     * Once a second: the pass under way moved on (AOS: the recording; LOS:
     * the end), else the next pass of [plan] taken up when its time has come.
     * [maitre] off: nothing starts, a pass under way ends.
     */
    fun tic(plan: List<CampagneSstv.Prevu>, maitre: Boolean) {
        val t = horloge()
        val ec = enCours
        if (ec != null) {
            arretDemande?.let { arretDemande = null; termine(ec, Statut.ARRETE, it); return }
            if (!maitre) { termine(ec, Statut.ARRETE, "auto-pilote coupé"); return }
            val p = ec.passage
            if (ec.phase == Phase.PRET) {
                // A transmission began meanwhile: the pass is left, nothing touched while it lasts.
                station.emission()?.let { termine(ec, Statut.INTERROMPU, "émission en cours : $it"); return }
                if (t >= p.aosEpochMs) {
                    val err = if (ec.campagne.chaine.enregistrer) station.enregistre(ec.campagne) else null
                    enCours = ec.copy(phase = Phase.EN_COURS, faits = ec.faits + (err?.let { "pas d'enregistrement : $it" } ?: if (ec.campagne.chaine.enregistrer) "enregistrement à l'AOS" else "AOS"))
                    if (ec.campagne.chaine.annonce) station.annonce("Auto-pilote : ${ec.campagne.nom}, le passage commence.")
                }
            }
            if (t >= p.losEpochMs + CampagneSstv.APRES_LOS_MS) termine(enCours ?: ec, Statut.FAIT, "")
            return
        }
        arretDemande = null
        if (!maitre) return
        // A pass lost to another (both at once): said once when its time comes.
        plan.firstOrNull { it.statut == Statut.CHEVAUCHE && t >= it.passage.aosEpochMs - it.campagne.avantMs && t < it.passage.losEpochMs }?.let {
            ecarte(it.campagne, it.passage, it.raison.ifBlank { "un autre passage en même temps" })
        }
        val pr = plan.firstOrNull { it.statut == Statut.PREVU && t >= it.passage.aosEpochMs - it.campagne.avantMs && t < it.passage.losEpochMs } ?: return
        val c = pr.campagne; val p = pr.passage
        station.emission()?.let { ecarte(c, p, "émission en cours : $it"); return }
        station.conflit(c)?.let { ecarte(c, p, it); return }
        val avant = station.capture()
        val r = runCatching { station.prepare(c, p) }.getOrElse { Preparation(emptyList(), it.message ?: "erreur") }
        if (r.erreur != null) {
            val remis = runCatching { station.restaure(avant) }.getOrDefault(emptyList())
            ecarte(c, p, r.erreur, r.faits + remis)
            return
        }
        val leve = t >= p.aosEpochMs
        var faits = r.faits
        if (leve && c.chaine.enregistrer) faits = faits + (station.enregistre(c)?.let { "pas d'enregistrement : $it" } ?: "enregistrement en cours")
        enCours = EnCours(c, p, if (leve) Phase.EN_COURS else Phase.PRET, avant, faits)
        if (c.chaine.annonce) station.annonce("Auto-pilote : ${c.nom}, ${c.satNom} " + if (leve) "en cours." else "dans ${((p.aosEpochMs - t + 59_999) / 60_000).coerceAtLeast(1)} minutes.")
    }

    /** The passes already set aside this session: each said once, even if the plan is not read again in time. */
    private val ecartes = HashSet<String>()

    private fun ecarte(c: Campagne, p: SatPass, raison: String, faits: List<String> = emptyList()) {
        if (!ecartes.add(c.id + ":" + p.catalogNumber + ":" + p.aosEpochMs)) return
        consigne(Trace(c.id, c.nom, p.catalogNumber, c.satNom, p.aosEpochMs, p.losEpochMs, p.maxElevationDeg, Statut.ECARTE, raison, horloge(), faits = faits))
    }

    private fun termine(ec: EnCours, statut: Statut, raison: String) {
        enCours = null
        val c = ec.campagne; val p = ec.passage
        val faits = ArrayList(ec.faits)
        val fichier = if (c.chaine.enregistrer) runCatching { station.arreteEnregistrement() }.getOrDefault("") else ""
        // One picture cut in two by a fade: put back together before the pictures are counted.
        val reunies = runCatching { station.reunitPartielles(p.aosEpochMs - 60_000L, horloge()) }.getOrDefault(emptyList())
        if (reunies.isNotEmpty()) faits += reunies.size.toString() + " image" + (if (reunies.size > 1) "s" else "") + " réunie" + (if (reunies.size > 1) "s" else "") + " de deux partielles"
        val images = runCatching { station.images(p.aosEpochMs - 60_000L, horloge()) }.getOrDefault(emptyList())
        val tx = station.emission()
        if (tx != null) faits += "état laissé tel quel : émission en cours"
        else {
            if (c.chaine.parquer && c.chaine.rotor) faits += (station.parque()?.let { "rotor non parqué : $it" } ?: "rotor parqué")
            faits += runCatching { station.restaure(ec.avant) }.getOrDefault(emptyList())
        }
        if (c.chaine.annonce && statut == Statut.FAIT) station.annonce("Auto-pilote : ${c.nom}, fin du passage" + if (images.isNotEmpty()) ", ${images.size} image" + (if (images.size > 1) "s." else ".") else ".")
        consigne(Trace(c.id, c.nom, p.catalogNumber, c.satNom, p.aosEpochMs, p.losEpochMs, p.maxElevationDeg, statut, raison, horloge(), fichier, images, faits))
    }
}
