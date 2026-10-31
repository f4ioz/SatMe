/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import kotlin.math.log10

/**
 * Where a recording holds **digital** traffic — FT4, FT8, other narrow
 * bursts — told apart from voices and hiss by what makes them what they are:
 *
 * - **narrow**: a burst lives in a few tens of hertz (FT4 83 Hz, FT8 50 Hz)
 *   where a voice spreads over the whole passband;
 * - **its length**: 5.04 s for FT4, 12.64 s for FT8;
 * - **its cadence**: it starts half a second into a UTC slot (7.5 s for FT4,
 *   15 s for FT8), give or take the station's clock.
 *
 * The recording is cut into frames of about a sixth of a second (the
 * spectrum in 50 Hz bands from [BASSE_HZ] to [HAUTE_HZ]); each band is
 * compared with the frame's median (the passband's floor, whatever the AGC
 * does) and with its own usual level over the recording (its lower fifth: a
 * birdie, the passband's shape, not a station sending most of the time). What stands above both by [SEUIL_DB] is lit; lit cells
 * that touch form one burst, a drifting one too (the Doppler slides it across
 * bands). Each burst is then judged by its length and where it starts in the
 * UTC slots; close bursts make a zone.
 *
 * What is found is a hint for the eye and a place to decode, not a decode:
 * the zone is read by the real decoders ([Ft8HorsLigne]) when asked.
 */
object ZonesNumeriques {

    const val BASSE_HZ = 200.0
    const val HAUTE_HZ = 3000.0
    const val BANDE_HZ = 50.0
    /** Above the frame's floor and the band's usual level by this much (dB): lit. */
    const val SEUIL_DB = 5.0
    /** A burst wider than this (Hz, its frames' mean) is a voice or a wideband signal. */
    const val LARGEUR_MAX_HZ = 260.0
    /** Its whole span (Hz), drift included: FT8 at 20 Hz/s slides 250 Hz. */
    const val ETENDUE_MAX_HZ = 600.0
    /** Shorter than this, a crackle. */
    const val DUREE_MIN_MS = 2_000L
    /** Bursts closer than this belong to the same zone. */
    const val JOINT_MS = 40_000L

    /** One burst found: its time (UTC ms), its band (Hz), what its length and start say. */
    data class Salve(val debutMs: Long, val finMs: Long, val basHz: Double, val hautHz: Double, val genre: String) {
        val dureeMs: Long get() = finMs - debutMs
    }

    /**
     * A zone of digital traffic: its time (UTC ms), its band, [mode] FT4, FT8 or
     * NUM (narrow, regular, neither), how many bursts.
     */
    data class Zone(val debutMs: Long, val finMs: Long, val mode: String, val salves: Int, val basHz: Double, val hautHz: Double) {
        /** What the page writes on it. */
        val libelle: String get() = if (mode == NUM) "numérique" else mode
    }

    const val FT4 = "FT4"
    const val FT8 = "FT8"
    const val NUM = "NUM"

    /** The spectrum measured as the samples come, any rate. */
    class Mesure(val cadence: Int) {
        /** About 170 ms at any usual rate (4096 at 24 kHz, 2048 at 12 kHz). */
        val taille: Int = Ft8Signal.puissanceDeDeux((cadence * 0.16).toInt().coerceAtLeast(64))
        val trameMs: Double get() = taille * 1000.0 / cadence
        private val binHz = cadence.toDouble() / taille
        val bandes: Int = ((HAUTE_HZ - BASSE_HZ) / BANDE_HZ).toInt()
        private val bin0 = IntArray(bandes) { b -> Math.round((BASSE_HZ + b * BANDE_HZ) / binHz).toInt() }
        private val bin1 = IntArray(bandes) { b -> maxOf(bin0[b] + 1, Math.round((BASSE_HZ + (b + 1) * BANDE_HZ) / binHz).toInt()) }
        private val fenetre = FloatArray(taille) { i -> (0.5 - 0.5 * kotlin.math.cos(2 * Math.PI * i / taille)).toFloat() }
        private val re = FloatArray(taille); private val im = FloatArray(taille)
        private var dans = 0
        /** Each frame's bands, dB. */
        val trames = ArrayList<FloatArray>()

        fun ajoute(pcm: ShortArray, n: Int = pcm.size) {
            for (i in 0 until n) {
                re[dans] = pcm[i] / 32768f * fenetre[dans]
                if (++dans == taille) trame()
            }
        }

        fun ajoute(audio: FloatArray) {
            for (v in audio) {
                re[dans] = v * fenetre[dans]
                if (++dans == taille) trame()
            }
        }

        private fun trame() {
            java.util.Arrays.fill(im, 0f)
            Ft8Signal.fft(re, im)
            val l = FloatArray(bandes)
            for (b in 0 until bandes) {
                var s = 0.0
                for (k in bin0[b] until minOf(bin1[b], taille / 2)) s += re[k] * re[k] + im[k] * im[k]
                l[b] = (10 * log10(s / (bin1[b] - bin0[b]) + 1e-20)).toFloat()
            }
            trames += l
            dans = 0
        }
    }

    private fun mediane(v: FloatArray): Float { val s = v.sortedArray(); return s[s.size / 2] }

    /** The bursts of a measured recording that started at [origineMs] (UTC). */
    fun salves(m: Mesure, origineMs: Long): List<Salve> {
        val nT = m.trames.size; val nB = m.bandes
        if (nT < 20) return emptyList()
        // Above the frame's floor…
        val rel = Array(nT) { t -> val l = m.trames[t]; val f = mediane(l); FloatArray(nB) { l[it] - f } }
        // …and above the band's usual level: low in its spread over the recording (a fifth up), so a band busy
        // most of the time (a station calling CQ all pass long) still has its floor.
        val usuel = FloatArray(nB) { b -> val v = FloatArray(nT) { rel[it][b] }; v.sort(); v[nT / 5] }
        // Lit, smoothed over three frames (a symbol's fade does not split a burst).
        val allume = Array(nT) { t -> BooleanArray(nB) { b ->
            var s = 0f; var k = 0
            for (u in maxOf(0, t - 1)..minOf(nT - 1, t + 1)) { s += rel[u][b] - usuel[b]; k++ }
            s / k >= SEUIL_DB
        } }
        // Cells that touch (time and band, diagonals too): one burst.
        val vu = Array(nT) { BooleanArray(nB) }
        val sortie = ArrayList<Salve>()
        val pile = IntArray(nT * nB * 2)
        for (t0 in 0 until nT) for (b0 in 0 until nB) {
            if (!allume[t0][b0] || vu[t0][b0]) continue
            var haut = 0
            pile[haut++] = t0; pile[haut++] = b0; vu[t0][b0] = true
            var tMin = t0; var tMax = t0; var bMin = b0; var bMax = b0; var cellules = 0
            while (haut > 0) {
                val b = pile[--haut]; val t = pile[--haut]
                cellules++
                if (t < tMin) tMin = t; if (t > tMax) tMax = t; if (b < bMin) bMin = b; if (b > bMax) bMax = b
                for (dt in -1..1) for (db in -1..1) {
                    val u = t + dt; val c = b + db
                    if (u !in 0 until nT || c !in 0 until nB || vu[u][c] || !allume[u][c]) continue
                    vu[u][c] = true; pile[haut++] = u; pile[haut++] = c
                }
            }
            val debut = origineMs + Math.round(tMin * m.trameMs)
            val fin = origineMs + Math.round((tMax + 1) * m.trameMs)
            if (fin - debut < DUREE_MIN_MS) continue
            val largeur = cellules.toDouble() / (tMax - tMin + 1) * BANDE_HZ
            val etendue = (bMax - bMin + 1) * BANDE_HZ
            if (largeur > LARGEUR_MAX_HZ || etendue > ETENDUE_MAX_HZ) continue
            sortie += Salve(debut, fin, BASSE_HZ + bMin * BANDE_HZ, BASSE_HZ + (bMax + 1) * BANDE_HZ, genre(debut, fin))
        }
        return sortie.sortedBy { it.debutMs }
    }

    /**
     * What a burst's length and start say: FT4 (about 5 s, starting near the
     * start of a 7.5 s slot), FT8 (about 12.6 s, near the start of a 15 s
     * slot), else NUM. The start is allowed from a second early to two late
     * (the station's clock, the recording's start known to the second, the
     * frame's length); the length from 1.4 s short (a fade at an edge) to
     * one long (the smoothing).
     */
    fun genre(debutMs: Long, finMs: Long): String {
        val d = finMs - debutMs
        fun cale(duree: Long): Boolean { val p = Math.floorMod(debutMs - 500L, duree); return p <= 2_000L || p >= duree - 1_000L }
        return when {
            d in 3_600L..6_200L && cale(7_500L) -> FT4
            d in 10_500L..14_000L && cale(15_000L) -> FT8
            else -> NUM
        }
    }

    /**
     * The zones: bursts closer than [JOINT_MS] gathered; a zone is FT4 or FT8
     * when at least two of its bursts say so and they are the most; NUM when
     * none does but at least three narrow bursts start on the 7.5 s grid
     * (most gaps between starts within 0.8 s of a multiple of 7.5 s); else it
     * is not shown (a carrier, a CW call, a birdie).
     */
    fun zones(s: List<Salve>): List<Zone> {
        val groupes = ArrayList<MutableList<Salve>>()
        for (x in s.sortedBy { it.debutMs }) {
            val g = groupes.lastOrNull()
            if (g != null && x.debutMs - g.maxOf { it.finMs } <= JOINT_MS) g += x else groupes += mutableListOf(x)
        }
        return groupes.mapNotNull { g ->
            val n4 = g.count { it.genre == FT4 }; val n8 = g.count { it.genre == FT8 }
            val mode = when {
                n4 >= 2 && n4 >= n8 -> FT4
                n8 >= 2 -> FT8
                n4 + n8 >= 1 && g.size >= 3 && cadence(g) -> if (n4 >= n8) FT4 else FT8
                g.size >= 3 && cadence(g) -> NUM
                else -> null
            } ?: return@mapNotNull null
            Zone(g.minOf { it.debutMs }, g.maxOf { it.finMs }, mode, g.size, g.minOf { it.basHz }, g.maxOf { it.hautHz })
        }
    }

    private fun cadence(g: List<Salve>): Boolean {
        val debuts = g.map { it.debutMs }.distinct().sorted()
        if (debuts.size < 3) return false
        val ecarts = debuts.zipWithNext { a, b -> b - a }
        val bons = ecarts.count { e -> val r = Math.floorMod(e, 7_500L); r <= 800L || r >= 6_700L }
        return bons * 3 >= ecarts.size * 2
    }

    /** All at once: a recording's samples, its start (UTC ms), its zones. */
    fun cherche(audio: FloatArray, cadence: Int, origineMs: Long): List<Zone> =
        zones(salves(Mesure(cadence).also { it.ajoute(audio) }, origineMs))

    // ------------------------------------------------------------ kept beside the recording

    /** "<name>.numerique": one zone a line — start, end (ms from the file's start), mode, bursts, low and high Hz. */
    fun ecrit(l: List<Zone>, origineMs: Long): String = "# SatMe zones numériques v1\n" + l.joinToString("") {
        "${it.debutMs - origineMs}\t${it.finMs - origineMs}\t${it.mode}\t${it.salves}\t${it.basHz.toInt()}\t${it.hautHz.toInt()}\n"
    }

    fun lit(t: String, origineMs: Long): List<Zone> = t.lines().filter { it.isNotBlank() && !it.startsWith("#") }.mapNotNull { l ->
        val c = l.split('\t'); if (c.size < 6) return@mapNotNull null
        runCatching {
            Zone(origineMs + c[0].toLong(), origineMs + c[1].toLong(), c[2].takeIf { it in setOf(FT4, FT8, NUM) } ?: NUM,
                c[3].toInt(), c[4].toDouble(), c[5].toDouble())
        }.getOrNull()
    }
}
