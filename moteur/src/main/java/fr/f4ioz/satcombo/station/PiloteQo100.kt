/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.station

import fr.f4ioz.satcombo.data.SatConfigStore
import fr.f4ioz.satcombo.data.SettingsStore
import fr.f4ioz.satcombo.domain.ChaineQo100
import fr.f4ioz.satcombo.domain.MemoiresQo100
import fr.f4ioz.satcombo.domain.Qo100
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.i18n.tf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * QO-100 as the phone's screen drives it, for a station without its screen
 * (the PC): where to listen in the sky, the uplink that goes with it, the
 * memories, the station's conversion chain, the calibration on the beacon.
 *
 * **No loop.** A geostationary has no Doppler: the rig is written when the
 * operator moves something, never otherwise — the Doppler loop must hold
 * while [Etat.auPoste] is on (the station's `maintienDoppler`), or the two
 * would fight over the rig.
 *
 * The rules are the phone's, unchanged:
 * - the downlink stays in the chosen transponder (or the whole band plan
 *   when sweeping): beyond its edges the uplink lands on the neighbour;
 * - **sweeping is not transmitting**: where the band plan forbids it (the
 *   beacons), nothing is sent to the rig, not even the downlink — the uplink
 *   would sit one PTT press away from the band's reference;
 * - the calibration (the LNB's drift, stored as NORAD 43700's shift) moves
 *   the downlink only, the uplink follows the nominal one;
 * - the chain decides the converters, including when it wants none.
 */
class PiloteQo100(
    private val settings: SettingsStore,
    private val satConfig: SatConfigStore,
    private val materiel: Materiel,
) {
    /** What the station provides: its converters, its rig, and the writing. */
    interface Materiel {
        /** The rig's frequency for a sky frequency, converters applied. */
        fun posteRx(satHz: Long): Long
        fun posteTx(satHz: Long): Long
        /** Can the rig go there at all? */
        fun atteignable(posteHz: Long): Boolean
        /** Writes the pair (sky frequencies) to the rig, in satellite mode; asynchronous. */
        fun ecris(dlSat: Long, ulSat: Long)
        /** The converters changed: what the rig was armed with is void. */
        fun convertisseursChanges() {}
    }

    data class Etat(
        val transpondeur: String = Qo100.NB.cle,
        val sansBride: Boolean = false,
        /** Where we listen, in the sky (nominal: the calibration is not in it). */
        val descenteHz: Long = Qo100.BALISE_MEDIANE_HZ,
        val auPoste: Boolean = false,
        /**
         * Does the SDR dongle follow too? Independent of [auPoste], on purpose:
         * listening on the dongle while transmitting with the rig is a common
         * QO-100 station. The dongle reads [descenteHz] + [calageHz] itself.
         */
        val aLaCle: Boolean = false,
        /** What the rig should display, after converters: the check before hearing anything. */
        val posteRxHz: Long = 0L,
        val posteTxHz: Long = 0L,
        val posteAtteignable: Boolean = false,
        val calageHz: Long = 0L,
        val surBalise: Boolean = true,
        val memoires: List<MemoiresQo100.Memoire> = emptyList(),
        val chaines: List<ChaineQo100.Chaine> = emptyList(),
        val chaine: String = "Fixe",
        val statut: String = "",
    ) {
        val monteeHz: Long get() = Qo100.monteeDepuisDescente(descenteHz)
        val segment: Qo100.Segment? get() = Qo100.segment(descenteHz)
        val emissionPermise: Boolean get() = Qo100.emissionAutorisee(descenteHz)
        val tp: Qo100.Transpondeur get() = Qo100.TRANSPONDEURS.firstOrNull { it.cle == transpondeur } ?: Qo100.NB
        val chaineActive: ChaineQo100.Chaine get() = ChaineQo100.choisie(chaines, chaine)
    }

    private val _etat = MutableStateFlow(Etat())
    val etat: StateFlow<Etat> = _etat.asStateFlow()

    private fun change(f: Etat.() -> Etat) { _etat.value = _etat.value.f() }

    /** Reads what is stored (memories, chains, calibration); the frequency starts on the beacon. */
    fun ouvre() {
        change {
            copy(sansBride = settings.qo100SansBride, memoires = settings.qo100Memoires,
                chaines = settings.qo100Chaines, chaine = settings.qo100Chaine,
                calageHz = satConfig.load(Qo100.NORAD).calibShiftHz)
        }
        recalcule()
    }

    // ------------------------------------------------------------ frequency

    fun transpondeur(cle: String) {
        val tp = Qo100.TRANSPONDEURS.firstOrNull { it.cle == cle } ?: return
        change { copy(transpondeur = tp.cle, descenteHz = tp.brideDescente(descenteHz)) }
        recalcule(); pousse()
    }

    /** Tunes, clamped to the transponder (or the band plan when sweeping). */
    fun descente(hz: Long) {
        val e = _etat.value
        val borne = if (e.sansBride) hz.coerceIn(Qo100.REGLETTE_BAS_HZ, Qo100.REGLETTE_HAUT_HZ) else e.tp.brideDescente(hz)
        change { copy(descenteHz = borne) }
        recalcule(); pousse()
    }

    fun pas(deltaHz: Long) = descente(_etat.value.descenteHz + deltaHz)
    fun balise() = descente(Qo100.BALISE_MEDIANE_HZ)
    fun centre() = descente(_etat.value.tp.centreDescenteHz)

    fun sansBride(on: Boolean) {
        settings.qo100SansBride = on
        change { copy(sansBride = on) }
        if (!on) descente(_etat.value.descenteHz)
    }

    // ------------------------------------------------------------ memories

    /** The current frequency kept; no name needed (its kHz then). */
    fun poseMemoire(nom: String) {
        val l = MemoiresQo100.pose(_etat.value.memoires, _etat.value.descenteHz, nom)
        settings.qo100Memoires = l
        change { copy(memoires = l) }
    }

    fun retireMemoire(hz: Long) {
        val l = MemoiresQo100.retire(_etat.value.memoires, hz)
        settings.qo100Memoires = l
        change { copy(memoires = l) }
    }

    // ------------------------------------------------------------ the station's chain

    fun chaine(nom: String) {
        settings.qo100Chaine = nom
        change { copy(chaine = nom) }
        appliqueChaine()
    }

    fun ajouteChaine(nom: String) {
        val n = nom.trim(); if (n.isEmpty()) return
        val l = ChaineQo100.range(_etat.value.chaines, ChaineQo100.Chaine(nom = n))
        settings.qo100Chaines = l; settings.qo100Chaine = n
        change { copy(chaines = l, chaine = n) }
        appliqueChaine()
    }

    /**
     * An oscillator measured: the same signal on a reference ([cielHz]) and on
     * the rig ([posteHz]); the difference is the oscillator. What cannot be one
     * is refused (fields swapped, a misplaced comma), never a mere offset from
     * nominal — that is what is measured. Success and the message.
     */
    fun mesure(descente: Boolean, cielHz: Long, posteHz: Long): Pair<Boolean, String> {
        if (cielHz <= 0L || posteHz <= 0L) return false to t("qo100_mesure_vide")
        val ol = ChaineQo100.olMesure(cielHz, posteHz)
        val bon = if (descente) ChaineQo100.descenteCredible(ol) else ChaineQo100.monteeCredible(ol)
        if (!bon) return false to tf("qo100_mesure_refus", ol / 1_000_000.0)
        poseOl(descente, ol)
        val nominal = if (descente) 10_345_000_000L else 1_968_000_000L
        return true to tf("qo100_mesure_ok", ol / 1_000_000.0, (ol - nominal) / 1_000.0)
    }

    /** A nominal oscillator (a preset), or 0: no converter on that side. */
    fun poseOl(descente: Boolean, olHz: Long) {
        val a = _etat.value.chaineActive
        val l = ChaineQo100.range(_etat.value.chaines, if (descente) a.copy(descenteOlHz = olHz) else a.copy(monteeOlHz = olHz))
        settings.qo100Chaines = l
        change { copy(chaines = l) }
        appliqueChaine()
    }

    /**
     * The chain's oscillators into the converters: one source of truth, the
     * empty cases written too (choosing "direct" must remove the old one).
     * Bounds sized for QO-100, not the whole band: the narrower, the less a
     * foreign frequency looks like one of ours.
     */
    private fun appliqueChaine() {
        val c = _etat.value.chaineActive
        settings.convRxActif = c.descenteActive
        settings.convRxOlHz = c.descenteOlHz
        settings.convRxInverseur = false
        settings.convRxBasHz = 10_489_000_000L
        settings.convRxHautHz = 10_500_000_000L
        // Without a dongle the LNB feeds the rig.
        settings.convRxPoste = c.descenteActive
        settings.convTxActif = c.monteeActive
        settings.convTxOlHz = c.monteeOlHz
        settings.convTxInverseur = false
        settings.convTxBasHz = 2_390_000_000L
        settings.convTxHautHz = 2_450_000_000L
        materiel.convertisseursChanges()
        recalcule(); pousse()
    }

    // ------------------------------------------------------------ the rig, the calibration

    fun auPoste(on: Boolean) {
        change { copy(auPoste = on, statut = "") }
        if (on) pousse()
    }

    fun aLaCle(on: Boolean) = change { copy(aLaCle = on) }

    /** Where the middle beacon was really heard (sky): the gap from 10 489.750 is the LNB's drift. */
    fun caleSurLaBalise(entenduHz: Long) = calage(_etat.value.calageHz + entenduHz - Qo100.BALISE_MEDIANE_HZ)

    fun calage(hz: Long) {
        satConfig.saveShift(Qo100.NORAD, hz)
        change { copy(calageHz = hz) }
        recalcule(); pousse()
    }

    fun annuleCalage() = calage(0L)

    /** A CAT failure to show. */
    fun erreur(message: String) = change { copy(statut = message) }

    /** What the rig should read; display only (the writing converts on its own). */
    private fun recalcule() {
        val e = _etat.value
        val rx = materiel.posteRx(e.descenteHz + e.calageHz)
        val tx = materiel.posteTx(e.monteeHz)
        change {
            copy(posteRxHz = rx, posteTxHz = tx,
                posteAtteignable = materiel.atteignable(rx) && materiel.atteignable(tx),
                surBalise = abs(descenteHz - Qo100.BALISE_MEDIANE_HZ) < 100L)
        }
    }

    private fun pousse() {
        val e = _etat.value
        if (!e.auPoste) return
        if (!e.emissionPermise) { change { copy(statut = t("qo100_ecoute_seule")) }; return }
        change { copy(statut = "") }
        materiel.ecris(e.descenteHz + e.calageHz, e.monteeHz)
    }
}
