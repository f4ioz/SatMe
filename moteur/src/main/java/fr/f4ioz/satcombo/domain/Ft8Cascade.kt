/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

/**
 * The FT8 waterfall: one row of levels across the band about every 1/8 s,
 * the last [LIGNES] kept, and the instant spectrum. Fed samples at [cadence]
 * (12 kHz, the decoder's rate) by the phone's microphone or the PC's sound card.
 */
class Ft8Cascade(val basseHz: Int, val hauteHz: Int, val colonnes: Int, private val cadence: Int = 12_000,
                 /** Rows kept (the phone's screen: [LIGNES]; SatMe-PC keeps three slots). */
                 val lignesMax: Int = LIGNES) {

    /** Rows made since the start: the page asks for those after the last it has. */
    @Volatile var total = 0L
        private set

    /** Time between two rows, ms. */
    val pasMs: Double get() = PAS_ANALYSE * 1000.0 / cadence

    /** The rows after the [n]th, oldest first (all kept if [n] is too old). */
    @Synchronized fun depuis(n: Long): List<ByteArray> {
        val k = (total - n).coerceIn(0L, lignes.size.toLong()).toInt()
        return lignes.take(k).reversed()
    }

    companion object {
        const val FENETRE = 2048
        /** About 1/8 s between rows: enough for the eye. */
        const val PAS_ANALYSE = 1536
        /** Rows kept. */
        const val LIGNES = 60
    }

    private val anneau = FloatArray(FENETRE)
    private var ecrit = 0
    private var depuisDerniere = 0
    private val re = FloatArray(FENETRE)
    private val im = FloatArray(FENETRE)
    /** Hann window, computed once. */
    private val fenetre = FloatArray(FENETRE) {
        (0.5 - 0.5 * kotlin.math.cos(2.0 * Math.PI * it / (FENETRE - 1))).toFloat()
    }
    /** Smoothed noise floor — otherwise the image flickers. */
    private var plancher = -1.0

    val lignes = ArrayDeque<ByteArray>()
    var dernierSpectre = FloatArray(colonnes)
        private set

    /** Feeds samples; true when a new row is ready. */
    @Synchronized fun verse(bloc: ShortArray, combien: Int): Boolean {
        var pret = false
        for (i in 0 until combien) {
            anneau[ecrit] = bloc[i] / 32768f
            ecrit = (ecrit + 1) % FENETRE
            if (++depuisDerniere >= PAS_ANALYSE) { depuisDerniere = 0; pret = analyse() }
        }
        return pret
    }

    private fun analyse(): Boolean {
        // Unroll the ring oldest first, windowed: without a window each
        // tone leaks into its neighbours.
        for (k in 0 until FENETRE) {
            re[k] = anneau[(ecrit + k) % FENETRE] * fenetre[k]
            im[k] = 0f
        }
        Ft8Signal.fft(re, im)

        val binHz = cadence.toDouble() / FENETRE
        val r0 = (basseHz / binHz).toInt().coerceAtLeast(1)
        val r1 = (hauteHz / binHz).toInt().coerceAtMost(FENETRE / 2 - 1)
        if (r1 <= r0) return false

        var somme = 0.0
        val ligne = ByteArray(colonnes)
        val courbe = FloatArray(colonnes)
        for (c in 0 until colonnes) {
            val a = r0 + c * (r1 - r0) / colonnes
            val b = (r0 + (c + 1) * (r1 - r0) / colonnes).coerceAtLeast(a + 1)
            // Max, not mean: a narrow strong station would drown in an
            // average with the surrounding noise.
            var pic = 0f
            for (r in a until minOf(b, r1)) {
                val v = re[r] * re[r] + im[r] * im[r]
                if (v > pic) pic = v
                somme += v
            }
            courbe[c] = pic
        }
        val moyenne = somme / (r1 - r0).coerceAtLeast(1)
        plancher = if (plancher < 0) moyenne else plancher * 0.9 + moyenne * 0.1
        val ref = if (plancher <= 0.0) 1e-12 else plancher

        for (c in 0 until colonnes) {
            val db = 10.0 * kotlin.math.log10((courbe[c] + 1e-12) / ref)
            val v = ((db / 30.0).coerceIn(0.0, 1.0) * 255).toInt()
            ligne[c] = v.toByte()
            courbe[c] = (db / 30.0).coerceIn(0.0, 1.0).toFloat()
        }
        dernierSpectre = courbe
        lignes.addFirst(ligne)
        while (lignes.size > lignesMax) lignes.removeLast()
        total++
        return true
    }
}
