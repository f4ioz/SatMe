/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A picture decoded again from its own sound, the whole of it at once.
 *
 * The live decoder ([SstvDecoder]) has to decide each line as it comes: it
 * places a line on its sync pulse and on the few pulses before it. Here the
 * whole sound is there, so every line is placed knowing every pulse:
 *  - the line period and its start are searched over the whole picture
 *    (all sync pulses summed, like a comb): the sound card's clock error and
 *    the transmitter's (the slant) come out of that, ppm;
 *  - each line is then nudged by what its neighbours' pulses say (a running
 *    median, then the mean of those that agree): a noisy pulse no longer
 *    moves its line, a jump in the sound (samples lost by a sound card)
 *    still moves every line after it; a jump beyond the ±25 ms each pulse is
 *    looked for in leaves a run of lines without one: that run is searched
 *    again over almost a line for the offset its lines agree on;
 *  - the tuning error is read on the clean sync pulses only (1200 Hz; a
 *    noisy one reads nearer the band's middle) and followed slowly;
 *  - Robot 36's tone scale is set again on the separators (1500 and 2300 Hz
 *    before each chroma): in a weak signal the noise pulls every reading
 *    towards the middle and below, a grey and green picture; read the same
 *    way as the pixels, the separators say by how much;
 *  - Robot 36's chroma type (R-Y or B-Y, told by each line's separator) is
 *    voted over the whole picture, not trusted line by line: a noisy
 *    separator no longer paints a line purple or green;
 *  - each pixel is the median of the frequency over its span (the clicks of
 *    a weak FM signal are a sample or two far off: a median ignores them,
 *    where a mean is pulled by them — the speckle);
 *  - Robot 36's missing chroma on a line is the mean of the lines above and
 *    below, not a copy of one.
 * A picture begun late (no header) starts on its first clean pulse, as live.
 *
 * Measured on the owner's 40 ISS pictures with their sound (series 34, Oct.
 * 2026; [CorrectionSstvReelTest]): the gallery's mark 91.6 → 95.4 on average,
 * three pictures the live decoder had garbled (sync slips) 24 → 89, 49 → 79,
 * 91 → 99, the weakest ones 17–37 → 33–56.
 *
 * Pure Kotlin, offline: give it the samples of one picture's sound (the
 * `.wav` kept beside the PNG, or an extract of a pass recording).
 */
object RedecodageSstv {

    /** How a pixel's value is taken from the frequencies over its span. */
    enum class Lecture { MOYENNE, MEDIANE }

    class Options(
        /**
         * Low-pass after mixing to 1900 Hz, Hz (the scan band is 1500–2300: ±400, and the sync 1200).
         * 700 measured best on the owner's ISS pictures (series 34: mark and gap to the other copies,
         * weak and clean ones alike), 1000 a little sharper on a clean signal and noisier.
         */
        val filtreHz: Double = 700.0,
        val lecture: Lecture = Lecture.MEDIANE,
        /** A pixel spans at least this, ms (0: its own length; negative: from the noise measured in the sync pulses, as live). */
        val etendueMs: Double = -1.0,
        /** Follow the tuning error along the picture (else one value for the whole). */
        val suitAccord: Boolean = true,
        /** Robot 36: the other chroma from the lines above and below (else from the pair). */
        val chromaInterpolee: Boolean = true,
        /** Robot 36: the tone scale set again on each line's separator (1500 or 2300 Hz), see [decode]. */
        val calibre: Boolean = true,
        /** Runs of lines without a pulse searched again over almost a line (a jump of the sound beyond ±25 ms), see [decode]. */
        val recaleSegments: Boolean = true,
    )

    class Resultat(
        val mode: SstvMode,
        /** ARGB, mode.width × mode.height; rows not received black. */
        val pixels: IntArray,
        /** Rows decoded from the top. */
        val lignes: Int,
        val complete: Boolean,
        /** The line period's error found, ppm (sound card and transmitter together). */
        val derivePpm: Double,
        /** Mean tuning error, Hz. */
        val ecartHz: Double,
        /** The header was found (else the picture began late, on its first pulse). */
        val entete: Boolean,
        /** Share of lines whose sync pulse was found where expected. */
        val syncs: Float,
        /** Frequency spread inside the sync pulses, Hz: the noise (0 clean, 40 at 20 dB SNR, 100 at 10). */
        val bruitHz: Double = 0.0,
        /** The span a pixel was read over, ms. */
        val etendueMs: Double = 0.0,
        /** The low-pass used, Hz. */
        val filtreHz: Double = 0.0,
        /** Robot 36: the tone scale's shrink found on the separators (1: none, or not set again). */
        val gain: Double = 1.0,
        /** Where each line of the picture starts in the sound, samples. */
        val debuts: DoubleArray = DoubleArray(0),
    )

    /** Decodes [pcm] at [rate] as [mode]; null when no picture is found in it. */
    fun decode(pcm: ShortArray, rate: Int, mode: SstvMode, o: Options = Options()): Resultat? {
        if (pcm.size < rate) return null
        val filtreHz = o.filtreHz
        val f = frequences(pcm, rate, filtreHz)
        val spms = rate / 1000.0
        val n = f.size
        val t0 = mode.blockMs * spms

        // Sync-likeness of each sample (1200 ± 150 Hz), prefix-summed.
        val q = IntArray(n + 1)
        for (i in 0 until n) q[i + 1] = q[i] + (if (f[i] > 1050f && f[i] < 1350f) 1 else 0)
        fun dans(a: Int, b: Int): Int { val aa = a.coerceIn(0, n); val bb = b.coerceIn(0, n); return if (bb > aa) q[bb] - q[aa] else 0 }
        val lSync = (mode.syncMs * spms).roundToInt().coerceAtLeast(2)
        val lApres = (minOf(3.0, mode.syncMs / 3) * spms).roundToInt().coerceAtLeast(1)
        val lAvant = lApres
        // A pulse opening at s: 1200 over the pulse, not before nor after; normalised 1 for a clean one.
        val score = FloatArray(n) { s ->
            (dans(s, s + lSync) - (dans(s + lSync, s + lSync + lApres) + dans(s - lAvant, s)) * lSync / (2f * lApres)) / lSync
        }
        val syncAt = mode.syncAtMs * spms

        // 1. The comb: period and phase that gather the most pulses over the whole sound.
        val nbDerives = 161
        val pasPpm = 50.0
        var meilleur = Float.NEGATIVE_INFINITY; var pMeilleur = 0; var dMeilleur = 0.0
        val pasPhase = if (t0 > 3000) 2 else 1
        for (di in 0 until nbDerives) {
            val d = (di - nbDerives / 2) * pasPpm
            val t = t0 * (1 + d * 1e-6)
            var p = 0
            while (p < t0) {
                var s = 0f; var x = p.toDouble()
                while (x < n) { s += score[x.toInt()]; x += t }
                if (s > meilleur) { meilleur = s; pMeilleur = p; dMeilleur = d }
                p += pasPhase
            }
        }
        val periode = t0 * (1 + dMeilleur * 1e-6)
        // Lines k = 0.. with their sync near pMeilleur + k·periode.
        val nbK = floor((n - pMeilleur) / periode).toInt() + 1
        if (nbK < 3) return null

        // 2. Each pulse found near where the comb puts it (±25 ms: a jump in the sound too), then placed by its trailing edge.
        val rayon = (25 * spms).toInt()
        val lisse = moyenneGlissante(f, (0.5 * spms).roundToInt().coerceAtLeast(1))
        val residu = DoubleArray(nbK) { Double.NaN }
        /** Line [k]'s pulse looked for within [r] of the comb shifted by [decal], placed by its trailing edge: its gap to the comb, or NaN. */
        fun place(k: Int, decal: Int, r: Int): Double {
            val c = (pMeilleur + k * periode).roundToInt() + decal
            var bs = Float.NEGATIVE_INFINITY; var bp = c
            for (s in maxOf(0, c - r)..minOf(n - 1, c + r)) if (score[s] > bs) { bs = score[s]; bp = s }
            if (bs < 0.5f) return Double.NaN
            // The trailing edge (1200 → 1500 Hz, through 1350) does not move with the picture.
            val fin = bp + lSync
            var bord = Double.NaN
            for (s in maxOf(1, fin - lSync / 2)..minOf(n - 1, fin + lSync / 2)) {
                if (lisse[s - 1] < 1350f && lisse[s] >= 1350f) {
                    val e = s - 1 + (1350f - lisse[s - 1]) / (lisse[s] - lisse[s - 1]).toDouble()
                    if (bord.isNaN() || abs(e - fin) < abs(bord - fin)) bord = e
                }
            }
            return (if (bord.isNaN()) bp.toDouble() else bord - lSync) - (pMeilleur + k * periode)
        }
        for (k in 0 until nbK) residu[k] = place(k, 0, rayon)
        // **Beyond ±25 ms.** A bigger jump of the sound (33 ms lost by a sound card, 20261024_034726) leaves the
        // lines on one side of it with no pulse where the comb (set by the longer side) looks: filled from their
        // neighbours, they were drawn shifted (a green band). Each run of lines without a pulse is searched again
        // over almost a whole line, for one offset that most of its lines agree on — as the live decoder adopts a
        // step only once two pulses in a row say it. Several jumps: each run its own.
        if (o.recaleSegments) recaleSegments(residu, nbK, t0, spms) { k, c, r -> place(k, c, r) }
        // A running median (keeps a jump), then the mean of the pulses that agree with it (removes the noise).
        val mediane = DoubleArray(nbK) { k ->
            val v = (maxOf(0, k - 4)..minOf(nbK - 1, k + 4)).map { residu[it] }.filter { !it.isNaN() }.sorted()
            if (v.isEmpty()) Double.NaN else v[v.size / 2]
        }
        remplitTrous(mediane)
        val tol = 0.8 * spms
        val corr = DoubleArray(nbK) { k ->
            var s = 0.0; var m = 0
            for (j in maxOf(0, k - 6)..minOf(nbK - 1, k + 6)) {
                val r = residu[j]
                if (!r.isNaN() && !mediane[k].isNaN() && abs(r - mediane[k]) < tol) { s += r; m++ }
            }
            if (m > 0) s / m else mediane[k]
        }
        remplitTrous(corr)
        if (corr.all { it.isNaN() }) return null
        val debutLigne = DoubleArray(nbK) { k -> pMeilleur + k * periode + (corr[k].takeIf { !it.isNaN() } ?: 0.0) - syncAt }

        // How sync-like each line is where it is finally placed (±1 ms): a pulse of noise found somewhere in ±25 ms is not one.
        val ici = (1 * spms).roundToInt()
        val nette = FloatArray(nbK) { k ->
            val c = (debutLigne[k] + syncAt).roundToInt()
            var m = Float.NEGATIVE_INFINITY
            for (s in maxOf(0, c - ici)..minOf(n - 1, c + ici)) if (score[s] > m) m = score[s]
            m
        }

        // 3. Where the picture starts: after its header, else on its first clean pulses.
        var k0 = -1; var entete = false
        for (k in 0 until nbK) {
            val finVis = (debutLigne[k] - mode.firstBlockOffsetMs * spms).roundToInt()
            val a = finVis - (300 * spms).roundToInt()
            if (a < 0) continue
            var bande = 0; var meneur = 0
            for (i in a until finVis) if (f[i] > 1000f && f[i] < 1400f) bande++
            val b = a - (250 * spms).roundToInt()
            if (b < 0) continue
            for (i in b until a - (20 * spms).roundToInt()) if (f[i] > 1750f && f[i] < 2050f) meneur++
            if (bande > 0.7 * (finVis - a) && meneur > 0.5 * (a - b - 20 * spms)) { k0 = k; entete = true; break }
        }
        if (k0 < 0) k0 = (0 until nbK - 2).firstOrNull { k -> nette[k] >= 0.6f && (1..3).count { nette.getOrElse(k + it) { 0f } >= 0.6f } >= 2 } ?: return null
        // Where it ends: the last clean pulse, when only noise follows (20 lines) or the sound ends.
        val dispo = (k0 until nbK).filter { debutLigne[it] + periode <= n + 2 * spms }
        var kFin = minOf(k0 + mode.blocks, (dispo.lastOrNull() ?: (k0 - 1)) + 1)
        var dernier = -1
        for (k in k0 until kFin) if (nette[k] >= 0.5f) dernier = k
        if (dernier < 0) return null
        val apres = kFin - dernier - 1
        if (apres > 0 && (apres >= FIN_LIGNES || kFin < k0 + mode.blocks)) kFin = dernier + 1
        val blocs = kFin - k0
        if (blocs < 2) return null

        // 4. Tuning error, from the clean pulses only (1200 Hz; in noise a pulse reads nearer 1900, a false error), followed slowly.
        // The noise: how far the frequency strays inside the pulses (mean absolute gap to their median).
        val ecartLigne = DoubleArray(blocs) { Double.NaN }
        val ecarts = ArrayList<Double>()
        for (j in 0 until blocs) {
            if (nette[k0 + j] < 0.5f) continue
            val s = (debutLigne[k0 + j] + syncAt).roundToInt()
            val v = ((s + lSync / 5)..(s + lSync * 4 / 5)).filter { it in 0 until n }.map { f[it].toDouble() }
            if (v.size < 3) continue
            val m = v.sorted()[v.size / 2]
            val dispersion = v.sumOf { abs(it - m) } / v.size
            ecarts += dispersion
            if (dispersion < PULSE_NETTE_HZ) ecartLigne[j] = m - SstvTone.SYNC
        }
        val bruitHz = if (ecarts.isEmpty()) 0.0 else ecarts.sorted()[ecarts.size / 2] * 1.25
        val etendue = if (o.etendueMs >= 0) o.etendueMs else ((bruitHz - 20.0) / 60.0).coerceIn(0.0, 1.4)
        val valides = ecartLigne.filter { !it.isNaN() }.sorted()
        val ecartGlobal = if (valides.size < 10) 0.0 else valides[valides.size / 2].coerceIn(-MAX_ECART_HZ, MAX_ECART_HZ)
        val ecart = DoubleArray(blocs) { j ->
            if (!o.suitAccord) ecartGlobal else {
                val v = (maxOf(0, j - 10)..minOf(blocs - 1, j + 10)).map { ecartLigne[it] }.filter { !it.isNaN() }.sorted()
                if (v.size < 5) ecartGlobal else v[v.size / 2].coerceIn(-MAX_ECART_HZ, MAX_ECART_HZ)
            }
        }

        // 5. Robot 36: which chroma each line carries, voted over the picture.
        val chromaCb = BooleanArray(blocs)
        if (mode.family == Family.ROBOT36) {
            val seg = mode.segments.first { it.role == Role.C_ALT }
            var pour = 0; var contre = 0
            for (j in 0 until blocs) {
                val a = (debutLigne[k0 + j] + (seg.startMs - 5.5) * spms).roundToInt()
                val b = (debutLigne[k0 + j] + (seg.startMs - 2.0) * spms).roundToInt()
                var s = 0.0; var m = 0
                for (i in a until b) if (i in 0 until n) { s += f[i]; m++ }
                if (m == 0) continue
                val cb = s / m - ecart[j] > SstvTone.CARRIER
                if (cb == (j % 2 == 1)) pour++ else contre++
            }
            for (j in 0 until blocs) chromaCb[j] = (j % 2 == 1) == (pour >= contre)
        }

        // 6. The pixels.
        val w = mode.width; val h = mode.height
        val px = IntArray(w * h) { 0xFF000000.toInt() }
        val echelle = periode / t0
        val tampon = FloatArray(256)
        /** The frequency over samples [i0, i1), less [e], as the options read it. */
        fun valeur(i0: Int, i1: Int, e: Float): Float {
            var m = 0
            for (i in i0 until i1) { if (m < tampon.size) tampon[m++] = (f[i] - e).coerceIn(1200f, 2600f) }
            if (m == 0) return SstvTone.CARRIER.toFloat()
            return if (o.lecture == Lecture.MEDIANE && m >= 3) { java.util.Arrays.sort(tampon, 0, m); if (m % 2 == 1) tampon[m / 2] else (tampon[m / 2 - 1] + tampon[m / 2]) / 2 }
                   else { var s = 0f; for (i in 0 until m) s += tampon[i]; s / m }
        }
        // **The tone scale, set again on the separators.** In a weak signal the noise pulls every reading
        // towards the middle of the band and below it: the picture comes out grey and green. Robot 36 sends
        // a known tone before each chroma, 2300 Hz (B-Y) or 1500 Hz (R-Y): read the same way as the pixels,
        // over 20 lines around, they give how the scale shrank and shifted; the pixels are read back on it.
        // A clean signal reads them where they are: nothing changes.
        val gain = DoubleArray(blocs) { 1.0 }
        val decal = DoubleArray(blocs) { ecart[it] }
        val cal = BooleanArray(blocs)
        if (mode.family == Family.ROBOT36 && o.calibre) {
            val seg = mode.segments.first { it.role == Role.C_ALT }
            val sep = DoubleArray(blocs) { j ->
                if (nette[k0 + j] < 0.5f) Double.NaN else {
                    val a = (debutLigne[k0 + j] + (seg.startMs - 5.4) * spms).roundToInt().coerceIn(0, n - 1)
                    val b = (debutLigne[k0 + j] + (seg.startMs - 2.1) * spms).roundToInt().coerceIn(a + 1, n)
                    valeur(a, b, 0f).toDouble()
                }
            }
            for (j in 0 until blocs) {
                val r = maxOf(0, j - 20)..minOf(blocs - 1, j + 20)
                val hauts = r.filter { chromaCb[it] && !sep[it].isNaN() }.map { sep[it] }.sorted()
                val bas = r.filter { !chromaCb[it] && !sep[it].isNaN() }.map { sep[it] }.sorted()
                if (hauts.size < 5 || bas.size < 5) continue
                val hi = hauts[hauts.size / 2]; val lo = bas[bas.size / 2]
                val g = (hi - lo) / (SstvTone.WHITE - SstvTone.BLACK)
                if (g < 0.55 || g > 1.15) continue
                gain[j] = g; decal[j] = lo - g * SstvTone.BLACK; cal[j] = true
            }
        }
        fun lit(j: Int, seg: Segment): IntArray {
            val out = IntArray(w)
            val debut = debutLigne[k0 + j] + seg.startMs * spms * echelle
            val pas = seg.durMs * spms * echelle / w
            val demi = maxOf(pas, etendue * spms) / 2
            // The filter blends a segment's ends with the tones around it: the pixels there take the nearest clean span.
            val garde = GARDE_MS * spms
            val lo = debut + garde; val hi = debut + seg.durMs * spms * echelle - garde
            val g = gain[j]; val e = decal[j]
            val brut = !cal[j]
            for (x in 0 until w) {
                val c = debut + (x + 0.5) * pas
                var a = c - demi; var b = c + demi
                if (a < lo) { a = lo; b = maxOf(b, a + 1) }
                if (b > hi) { b = hi; a = minOf(a, b - 1) }
                var i0 = Math.round(a).toInt(); var i1 = Math.round(b).toInt()
                if (i1 <= i0) i1 = i0 + 1
                i0 = i0.coerceIn(0, n - 1); i1 = i1.coerceIn(i0 + 1, n)
                val v = if (brut) valeur(i0, i1, e.toFloat()).toDouble() else (valeur(i0, i1, 0f) - e) / g
                out[x] = SstvTone.level(v)
            }
            return out
        }
        val lignesMax = minOf(h, blocs * mode.linesPerBlock)
        when (mode.family) {
            Family.ROBOT36 -> {
                val segY = mode.segments.first { it.role == Role.Y1 }
                val segC = mode.segments.first { it.role == Role.C_ALT }
                val ys = Array(blocs) { lit(it, segY) }
                val cs = Array(blocs) { lit(it, segC) }
                for (j in 0 until minOf(blocs, h)) {
                    val propre = cs[j]
                    val autre: IntArray? = if (o.chromaInterpolee) {
                        val a = cs.getOrNull(j - 1); val b = cs.getOrNull(j + 1)
                        when { a != null && b != null -> IntArray(w) { (a[it] + b[it] + 1) / 2 }; else -> a ?: b }
                    } else cs.getOrNull(if (j % 2 == 0) j + 1 else j - 1)
                    val cr = if (chromaCb[j]) autre else propre
                    val cb = if (chromaCb[j]) propre else autre
                    for (x in 0 until w) px[j * w + x] = SstvTone.rgb(ys[j][x], cr?.get(x) ?: 128, cb?.get(x) ?: 128)
                }
            }
            Family.YUV, Family.PD -> for (j in 0 until blocs) {
                val parRole = mode.segments.associate { it.role to lit(j, it) }
                val y1 = parRole[Role.Y1] ?: continue
                val r0 = j * mode.linesPerBlock
                if (r0 < h) for (x in 0 until w) px[r0 * w + x] = SstvTone.rgb(y1[x], parRole[Role.CR]?.get(x) ?: 128, parRole[Role.CB]?.get(x) ?: 128)
                val y2 = parRole[Role.Y2]
                if (y2 != null && r0 + 1 < h) for (x in 0 until w) px[(r0 + 1) * w + x] = SstvTone.rgb(y2[x], parRole[Role.CR]?.get(x) ?: 128, parRole[Role.CB]?.get(x) ?: 128)
            }
            Family.RGB -> for (j in 0 until minOf(blocs, h)) {
                val parRole = mode.segments.associate { it.role to lit(j, it) }
                val r = parRole[Role.RED] ?: continue; val g = parRole[Role.GREEN] ?: continue; val b = parRole[Role.BLUE] ?: continue
                for (x in 0 until w) px[j * w + x] = (0xFF shl 24) or (r[x] shl 16) or (g[x] shl 8) or b[x]
            }
        }
        val trouves = (k0 until kFin).count { nette[it] >= 0.5f }
        return Resultat(mode, px, lignesMax, blocs >= mode.blocks, dMeilleur, ecartGlobal, entete, trouves / blocs.toFloat(), bruitHz, etendue, filtreHz, (0 until blocs).filter { cal[it] }.map { gain[it] }.sorted().let { if (it.isEmpty()) 1.0 else it[it.size / 2] }, DoubleArray(blocs) { debutLigne[k0 + it] })
    }

    /**
     * The runs of [residu] without a pulse (NaN), [MIN_TROU] lines or more:
     * for each, the offset within ±0.45 line ([t0] samples) where most of its
     * lines find a clean pulse ([place] around it, ±1.5 ms); kept when at
     * least [MIN_ACCORD] lines in a row and half of the run agree. A run that
     * does not agree as a whole is tried again in two halves (a jump inside it).
     */
    private fun recaleSegments(residu: DoubleArray, nbK: Int, t0: Double, spms: Double, place: (Int, Int, Int) -> Double) {
        val large = (0.45 * t0).roundToInt()
        val pres = (1.5 * spms).roundToInt().coerceAtLeast(2)
        val origine = residu.copyOf()
        fun essaie(a: Int, b: Int, profondeur: Int) {
            val l = b - a
            if (l < MIN_TROU) return
            // Each line of the run looked at over the wide window; the offset most of them agree on, to ±1.5 ms.
            val vus = DoubleArray(l) { place(a + it, 0, large) }
            val offs = vus.filter { !it.isNaN() }.sorted()
            var choix = Double.NaN; var accord = 0
            for (x in offs) { val m = offs.count { abs(it - x) <= pres }; if (m > accord) { accord = m; choix = x } }
            if (!choix.isNaN() && accord >= MIN_ACCORD && accord * 2 >= l) {
                // Placed again near that offset; adopted when enough of them in a row confirm it (a step, not noise).
                val d = choix.roundToInt()
                val ici = DoubleArray(l) { place(a + it, d, pres).takeIf { r -> !r.isNaN() && abs(r - choix) <= pres } ?: Double.NaN }
                var suite = 0; var plusLongue = 0
                for (r in ici) if (r.isNaN()) suite = 0 else { suite++; plusLongue = maxOf(plusLongue, suite) }
                if (plusLongue >= MIN_ACCORD) { for (j in 0 until l) if (!ici[j].isNaN()) residu[a + j] = ici[j]; return }
            }
            if (profondeur < 3 && l >= 2 * MIN_TROU) { essaie(a, a + l / 2, profondeur + 1); essaie(a + l / 2, b, profondeur + 1) }
        }
        var k = 0
        while (k < nbK) {
            if (!origine[k].isNaN()) { k++; continue }
            var e = k
            while (e < nbK && origine[e].isNaN()) e++
            essaie(k, e, 0)
            k = e
        }
    }

    /** Lines without a pulse in a row searched again over almost a line, from this many; lines in a row that must agree. */
    private const val MIN_TROU = 3
    private const val MIN_ACCORD = 2

    /** Lines without a pulse after the last one: the picture ended there. */
    private const val FIN_LIGNES = 20
    /** A pulse this steady (mean gap to its median, Hz) is a tuning reference. */
    private const val PULSE_NETTE_HZ = 35.0
    /** The largest tuning error believed, Hz (FM: the tones arrive as sent, give or take the sound card). */
    private const val MAX_ECART_HZ = 150.0

    /** Each end of a segment where the filter blends it with the tone beside, ms. */
    private const val GARDE_MS = 0.45

    /** Instantaneous frequency of each sample: mixed to 1900 Hz, low-passed (centred, no delay), phase step. */
    fun frequences(pcm: ShortArray, rate: Int, filtreHz: Double): FloatArray {
        val n = pcm.size
        val taps = Demodulator.lowPass((rate / 600).let { if (it % 2 == 0) it + 1 else it }.coerceAtLeast(15), filtreHz, rate)
        val mi = DoubleArray(n); val mq = DoubleArray(n)
        val dp = 2 * PI * SstvTone.CARRIER / rate
        for (i in 0 until n) { val s = pcm[i] / 32768.0; mi[i] = s * cos(dp * i); mq[i] = -s * sin(dp * i) }
        val c = taps.size / 2
        val fi = DoubleArray(n); val fq = DoubleArray(n)
        for (i in 0 until n) {
            var a = 0.0; var b = 0.0
            for (t in taps.indices) { val j = i + t - c; if (j in 0 until n) { a += taps[t] * mi[j]; b += taps[t] * mq[j] } }
            fi[i] = a; fq[i] = b
        }
        val k = rate / (2 * PI)
        val f = FloatArray(n)
        f[0] = SstvTone.CARRIER.toFloat()
        for (i in 1 until n) {
            val re = fi[i] * fi[i - 1] + fq[i] * fq[i - 1]
            val im = fq[i] * fi[i - 1] - fi[i] * fq[i - 1]
            f[i] = (SstvTone.CARRIER + (if (re == 0.0 && im == 0.0) 0.0 else atan2(im, re)) * k).toFloat()
        }
        return f
    }

    private fun moyenneGlissante(f: FloatArray, l: Int): FloatArray {
        val n = f.size; val p = DoubleArray(n + 1)
        for (i in 0 until n) p[i + 1] = p[i] + f[i]
        return FloatArray(n) { i -> val a = maxOf(0, i - l / 2); val b = minOf(n, a + l); ((p[b] - p[a]) / (b - a)).toFloat() }
    }

    /** NaN holes filled with the nearest value. */
    private fun remplitTrous(v: DoubleArray) {
        val premier = v.indexOfFirst { !it.isNaN() }
        if (premier < 0) return
        var dernier = v[premier]
        for (i in v.indices) if (v[i].isNaN()) v[i] = dernier else dernier = v[i]
    }
}
