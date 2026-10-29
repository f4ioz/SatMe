/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

/**
 * QO-100's first rule, enforced by SatMe while transmitting on the narrow
 * band: **never stronger than the beacon**. Our own signal is measured on
 * the downlink against the middle beacon ([fr.f4ioz.satcombo.sstv.NiveauSstvQo100]);
 * this guard turns that measurement into action:
 *
 * - above [seuilDb] (the beacon's level by default, or a few dB under it)
 *   for [confirmeMs]: the gain goes **down** by the excess plus 1 dB, then
 *   waits [pauseMs] for the downlink to show the effect;
 * - [coupureDb] or more above the beacon, or no more gain to take off:
 *   the transmission is **cut**.
 *
 * It only ever lowers or cuts — it never raises the gain, never keys
 * anything. No measurement (SatMe not hearing the downlink, a dummy load):
 * nothing done, the excess forgotten.
 */
class GardeNiveauQo100(
    /** Our level minus the beacon's (dB) not to exceed: 0 the beacon itself, −3 three dB under it. */
    val seuilDb: Double = 0.0,
    /** This far above the beacon (dB): cut at once. */
    val coupureDb: Double = COUPURE_DB,
    /** The excess must last this long (ms) before the gain is touched: one measurement is not enough. */
    val confirmeMs: Long = 1_000L,
    /** Between two steps down (ms): the downlink's measurement follows with a delay. */
    val pauseMs: Long = 1_500L,
) {
    /** What to do now: a lower gain, or a cut, with a word for the page. */
    data class Decision(val gainDb: Double?, val coupe: String?, val message: String)

    private var tropDepuisMs: Long? = null
    private var dernierPasMs = Long.MIN_VALUE / 2

    /** Steps taken down since [raz]. */
    var pas = 0
        private set

    /** A new transmission: nothing remembered. */
    fun raz() { tropDepuisMs = null; dernierPasMs = Long.MIN_VALUE / 2; pas = 0 }

    /**
     * One look at the measurement [ecartDb] (null: not measured) with the
     * gain [gainDb] in force, [gainMinDb] the lowest the transmitter takes.
     * Null: nothing to do.
     */
    fun tic(ecartDb: Double?, gainDb: Double, gainMinDb: Double, t: Long): Decision? {
        if (ecartDb == null || ecartDb.isNaN()) { tropDepuisMs = null; return null }
        if (ecartDb >= coupureDb)
            return Decision(null, String.format(Locale.FRANCE, "notre signal %+.1f dB au-dessus de la balise : émission coupée", ecartDb), "")
        if (ecartDb <= seuilDb) { tropDepuisMs = null; return null }
        val depuis = tropDepuisMs ?: t.also { tropDepuisMs = it }
        if (t - depuis < confirmeMs || t - dernierPasMs < pauseMs) return null
        val baisse = max(1.0, ceil(ecartDb - seuilDb + 1.0))
        val nouveau = gainDb - baisse
        if (nouveau < gainMinDb)
            return Decision(null, String.format(Locale.FRANCE, "toujours %+.1f dB par rapport à la balise au gain le plus bas : émission coupée", ecartDb), "")
        dernierPasMs = t
        pas++
        return Decision(nouveau, null, String.format(Locale.FRANCE, "%+.1f dB par rapport à la balise : gain baissé de %.0f dB (%.0f dB)", ecartDb, baisse, nouveau))
    }

    companion object {
        /** Cut at once this far above the beacon (dB). */
        const val COUPURE_DB = 6.0
        /** The thresholds offered on the page (dB relative to the beacon). */
        val SEUILS = listOf(0.0, -3.0, -6.0, -10.0)
    }
}

/**
 * Listen before transmitting: is somebody already where we are about to
 * send? The narrow band's signals (the cockpit's receiver, beacon
 * calibration applied) overlapping our downlink band, our own excluded by
 * the caller (only asked while not transmitting).
 */
object OccupationQo100 {
    data class Occupant(val hz: Long, val snrDb: Double, val classe: ClasseQo100)

    /** Within this much (Hz) of our band counts as overlapping (skirts, drift). */
    const val GARDE_HZ = 150L
    /** Weaker than this over the noise (dB): not counted. */
    const val SNR_MIN_DB = 6.0
    /** Not seen for this long (ms): gone. */
    const val AGE_MAX_MS = 5_000L

    /** The strongest signal overlapping [mode] on the uplink dial [cadranHz], or null when the frequency looks free. */
    fun occupant(signaux: List<SignalQo100>, mode: MonteeQo100.Mode, cadranHz: Long): Occupant? {
        val o = MonteeQo100.occupe(mode, cadranHz)
        val bas = Qo100.descenteDepuisMontee(o.first) - GARDE_HZ
        val haut = Qo100.descenteDepuisMontee(o.last) + GARDE_HZ
        return signaux.asSequence()
            .filter { it.classe != ClasseQo100.BALISE && it.vuIlYaMs <= AGE_MAX_MS && it.snrDb >= SNR_MIN_DB }
            .filter { s -> val d = s.largeurHz.coerceAtLeast(50) / 2; s.hz + d >= bas && s.hz - d <= haut }
            .maxByOrNull { it.snrDb }
            ?.let { Occupant(it.hz, it.snrDb, it.classe) }
    }

    /** The occupant in a few words for the page. */
    fun texte(o: Occupant): String = String.format(Locale.FRANCE, "fréquence occupée : un signal « %s » sur %s MHz (%.0f dB sur le bruit)",
        o.classe.libelle, MonteeQo100.mhz(o.hz), o.snrDb)
}
