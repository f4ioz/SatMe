/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Fine tuning by finger.
 *
 * The QO-100 slider spreads the 490 kHz narrowband transponder across the
 * screen, and the finger points at an **absolute** position: about 1.4 kHz
 * per dp, ±12 kHz under a fingertip. SSB needs about 50 Hz before voices turn
 * into ducks — two orders of magnitude off. That is a direction, not tuning.
 *
 * Three answers, which do not replace one another:
 *
 *  - the **magnifier** shows: a second spectrum view a few kHz wide, where
 *    both edges of the other station's sideband are visible. No new
 *    computation: the analyser already yields 4096 bins over 176 400 Hz
 *    (43 Hz per bin); the display was throwing that resolution away.
 *  - the **vernier** moves: an unrolled knob. The finger pushes the
 *    frequency at so many Hz per cm of drag instead of pointing at it, so it
 *    no longer hides the target signal, and the ratio is selectable.
 *  - **voice alignment** decides: in SSB the real question is "does the voice
 *    sound right"; placing the spectral centroid at the right audio offset
 *    answers better than any finger.
 *
 * Only the arithmetic lives here: it can be tested on the bench, a gesture
 * cannot.
 */
object AccordFin {

    // ------------------------------------------------------------ magnifier

    /**
     * Magnifier widths offered, Hz.
     *
     * The narrowest is 3 kHz, not 2: an SSB channel takes 2.4 kHz, and a
     * narrower view cuts off the very edges you came to look at. 3 kHz shows
     * the whole channel plus a margin, enough to see which way you are off.
     */
    val LOUPES: List<Int> = listOf(3_000, 5_000, 10_000, 20_000)

    /**
     * Actual magnifier resolution, Hz per dp.
     *
     * Answers "is it enough for SSB": on 360 dp, a 5 kHz view gives 14 Hz/dp,
     * so a fingertip covers under 150 Hz, versus 12 kHz on the full slider.
     */
    fun hzParDp(fenetreHz: Int, largeurDp: Float): Double =
        if (largeurDp <= 0f) 0.0 else fenetreHz / largeurDp.toDouble()

    // -------------------------------------------------------------- vernier

    /**
     * Vernier ratios, Hz per cm of drag.
     *
     * Centimetres, not dp or pixels: the only unit that gives the same gesture
     * on a 10-inch tablet and a phone. In pixels, the vernier would run twice
     * as fast on a screen twice as dense.
     *
     * Three values for the three real gestures: landing on a carrier to the
     * hertz, reaching a QSO heard next door, crossing a chunk of band.
     */
    val RAPPORTS: List<Int> = listOf(20, 200, 2_000)

    /** Converts a ratio to Hz per pixel, given the screen density. */
    fun hzParPixel(hzParCm: Int, dpi: Float): Double =
        if (dpi <= 0f) 0.0 else hzParCm * 2.54 / dpi

    /**
     * Gesture direction: you drag the **dial**, not the needle.
     *
     * Dragging left raises the frequency, like any scrolled list or an SDR
     * waterfall. The sign is fixed here once, rather than in each gesture
     * handler where it ends up differing between widgets.
     */
    fun deltaHz(dxPixels: Float, hzParPixel: Double): Double = -dxPixels * hzParPixel

    /**
     * Remainder accumulator.
     *
     * At the finest ratio a pixel is worth less than a hertz. Rounding each
     * drag event would give zero every time, and the vernier would be dead
     * exactly where it matters: a slow drag would do nothing. The fraction is
     * carried to the next event.
     *
     * The 10 Hz deadband of [DopplerTuner] filters setpoints downstream; here
     * we would be filtering intent.
     */
    class Aiguille {
        private var reste = 0.0

        /** Returns the whole hertz to apply, keeps the fraction. */
        fun pousse(dxPixels: Float, hzParPixel: Double): Long {
            reste += deltaHz(dxPixels, hzParPixel)
            val entier = if (reste >= 0) floor(reste).toLong() else -floor(-reste).toLong()
            reste -= entier
            return entier
        }

        fun oublie() { reste = 0.0 }
    }

    /**
     * Remaining fling speed, [t] seconds after release.
     *
     * Exponential decay with time constant [TAU]. Below the stop speed it
     * halts at once; otherwise the vernier creeps for seconds and the operator
     * loses track of where he is.
     */
    fun vitesseApres(v0: Float, t: Double, tau: Double = TAU): Float =
        if (t < 0) v0 else (v0 * exp(-t / tau)).toFloat()

    /** Total distance of a free fling, pixels. */
    fun parcoursTotal(v0: Float, tau: Double = TAU): Double = v0 * tau

    const val TAU: Double = 0.32
    const val VITESSE_ARRET: Float = 24f

    /**
     * Graduation step for a given ratio: the smallest 1-2-5 step leaving at
     * least [ecartMinPx] pixels between ticks. Tighter is unreadable and
     * flickers while scrolling.
     */
    fun pasGraduation(hzParPixel: Double, ecartMinPx: Double = 14.0): Long {
        if (hzParPixel <= 0.0) return 1L
        val minimumHz = ecartMinPx * hzParPixel
        var decade = 1L
        while (decade <= 100_000_000L) {
            for (m in longArrayOf(1L, 2L, 5L)) {
                val pas = decade * m
                if (pas >= minimumHz) return pas
            }
            decade *= 10L
        }
        return decade
    }

    /** A dial tick: position and value. */
    class Trait(val xPixels: Float, val hz: Long, val majeur: Boolean)

    /**
     * Visible ticks of a dial centred on [centreHz].
     *
     * One tick in five is major and labelled. The count is capped: a badly
     * chosen step on a wide screen would produce thousands and stall drawing.
     */
    fun traits(
        centreHz: Long,
        hzParPixel: Double,
        largeurPixels: Float,
        pas: Long = pasGraduation(hzParPixel),
        maxTraits: Int = 400
    ): List<Trait> {
        if (hzParPixel <= 0.0 || largeurPixels <= 0f || pas <= 0L) return emptyList()
        val demi = largeurPixels / 2.0
        val etendue = demi * hzParPixel
        val bas = centreHz - etendue
        val haut = centreHz + etendue
        val premier = floor(bas / pas).toLong() * pas
        val out = ArrayList<Trait>()
        var f = premier
        while (f <= haut && out.size < maxTraits) {
            if (f >= bas) {
                val x = (demi + (f - centreHz) / hzParPixel).toFloat()
                out.add(Trait(x, f, Math.floorMod(f / pas, 5L) == 0L))
            }
            f += pas
        }
        return out
    }

    // ------------------------------------------------------ voice alignment

    /**
     * Where to put the received voice centroid, audio Hz.
     *
     * Voice spans 300–2700 Hz, centroid around 1500. In USB the tuning sits
     * 1500 Hz **below** the centroid; in LSB above, the spectrum being
     * inverted.
     *
     * FM/AM: carrier in the middle, target zero. Returning zero rather than
     * refusing spares callers from checking the mode.
     */
    fun cibleVoixHz(mode: String): Int = when (mode.uppercase()) {
        "USB" -> 1_500
        "LSB" -> -1_500
        else -> 0
    }

    /** Does voice alignment make sense in this mode? */
    fun calageUtile(mode: String): Boolean = cibleVoixHz(mode) != 0

    /**
     * Half-width of the centroid search, Hz.
     *
     * Narrow on purpose. The radiosonde auto-centre sweeps ±25 kHz because it
     * searches an empty band; here we align on a station we **already** hear,
     * and a wide search would jump to a stronger neighbour at the first pause.
     * 3 kHz is one SSB channel and nothing more.
     */
    const val RECHERCHE_VOIX_HZ: Double = 3_000.0

    /**
     * Target tuning, Hz relative to the dongle's tuning.
     *
     * [centreGraviteHz] is the measured centroid, also relative; we sit
     * [cibleHz] below it so the voice lands at the right audio offset.
     */
    fun accordVise(centreGraviteHz: Double, cibleHz: Int): Long =
        (centreGraviteHz - cibleHz).roundToLong()

    // ------------------------------------------------ what the vernier drives

    /**
     * What the vernier moves, depending on the current satellite.
     *
     * The key distinction. On a transponder, what matters is the **channel**
     * (the listening point in the passband), because it is mirrored on the
     * uplink: on an inverting transponder, +2 kHz on receive is −2 kHz on
     * transmit, and you stay on your contact. Moving a mere receive offset
     * would shift receive without moving transmit: you would lose the QSO
     * while thinking you were following it.
     *
     * On a fixed channel (FM, no band to scan) only the dongle tuning moves.
     */
    enum class Cible { CANAL, CLE }

    fun cibleDuVernier(estTranspondeur: Boolean, basHz: Long?, hautHz: Long?): Cible =
        if (estTranspondeur && basHz != null && hautHz != null && basHz != hautHz) Cible.CANAL
        else Cible.CLE

    /**
     * The moved channel, clamped to the passband.
     *
     * Clamp, never wrap: wrapping would jump across the transponder in the
     * middle of a contact, and a fling covering several kHz would make that
     * common.
     */
    fun nouveauCanal(actuelHz: Long, deltaHz: Long, basHz: Long, hautHz: Long): Long =
        (actuelHz + deltaHz).coerceIn(minOf(basHz, hautHz), maxOf(basHz, hautHz))

    /**
     * Is a move worth sending to the dongle?
     *
     * Below the Doppler scheduler's deadband it would be ignored downstream
     * anyway. Above it, it costs only a complex multiply while it stays within
     * the software offset range.
     */

    fun vautLaPeine(deltaHz: Long): Boolean = abs(deltaHz) >= DopplerTuner.DEADBAND_HZ
}
