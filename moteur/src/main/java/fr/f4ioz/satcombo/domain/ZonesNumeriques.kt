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
 * A GENESIS burst (AMSAT-EA's 2-FSK telemetry, heard in USB) lights two
 * narrow lines at once, its two tones 1125 Hz apart at 200 bit/s (1600 Hz
 * at 800 bit/s): two bursts starting and ending together that far apart are
 * one GENESIS frame ([G200], [G800]), not two FT4 signals — its 4 to 5 s
 * frames would pass for FT4 by their length alone.
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
     * NUM (narrow, regular, neither), how many bursts; or of voices ([PHONIE],
     * its seconds that speak).
     */
    data class Zone(val debutMs: Long, val finMs: Long, val mode: String, val salves: Int, val basHz: Double, val hautHz: Double) {
        /** What the page writes on it. */
        val libelle: String get() = libelle(mode)
    }

    /** What the page writes on a zone of [mode]. */
    fun libelle(mode: String): String = when (mode) {
        NUM -> "numérique"; PHONIE -> "phonie"; G200 -> "GENESIS 200"; G800 -> "GENESIS 800"; GENESIS -> "GENESIS"; else -> mode
    }

    const val FT4 = "FT4"
    const val FT8 = "FT8"
    const val NUM = "NUM"
    /** Not digital: voices ([voix]), kept in the same file. */
    const val PHONIE = "PHONIE"
    /** GENESIS 2-FSK: 200 bit/s (tones 1125 Hz apart), 800 bit/s (1600 Hz), or its rate not known. */
    const val G200 = "GENESIS200"
    const val G800 = "GENESIS800"
    const val GENESIS = "GENESIS"
    /** Every mode a kept file may hold. */
    val MODES = setOf(FT4, FT8, NUM, PHONIE, G200, G800, GENESIS)

    fun estGenesis(mode: String) = mode == G200 || mode == G800 || mode == GENESIS

    /** Two bursts this close at their start (ms), and at their end, are sent together… */
    const val PAIRE_DEBUT_MS = 700L
    const val PAIRE_FIN_MS = 1_000L
    /** …and their centres this far apart (Hz, give or take [PAIRE_TOLERANCE_HZ]): GENESIS's two tones. */
    const val ECART_G200_HZ = 1_125.0
    const val ECART_G800_HZ = 1_600.0
    const val PAIRE_TOLERANCE_HZ = 180.0

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
        return paires(sortie.sortedBy { it.debutMs })
    }

    private val FT = setOf(FT4, FT8)

    /**
     * GENESIS's two tones found among [s] (in time order): two bursts starting within [PAIRE_DEBUT_MS] and ending
     * within [PAIRE_FIN_MS], their centres [ECART_G200_HZ] (or [ECART_G800_HZ]) apart, both take [G200] ([G800]) —
     * unless both are FT4 (FT8) by their length and slot: two stations sending in the same slot.
     */
    fun paires(s: List<Salve>): List<Salve> {
        val genre = s.map { it.genre }.toTypedArray()
        for (i in s.indices) for (j in i + 1 until s.size) {
            val a = s[i]; val b = s[j]
            if (b.debutMs - a.debutMs > PAIRE_DEBUT_MS) break
            if (Math.abs(a.finMs - b.finMs) > PAIRE_FIN_MS) continue
            // Both shaped and timed as FT4 (FT8): two stations in the same slot, not one GENESIS frame.
            if (a.genre in FT && b.genre in FT) continue
            val ecart = Math.abs((a.basHz + a.hautHz) / 2 - (b.basHz + b.hautHz) / 2)
            val g = when {
                Math.abs(ecart - ECART_G200_HZ) <= PAIRE_TOLERANCE_HZ -> G200
                Math.abs(ecart - ECART_G800_HZ) <= PAIRE_TOLERANCE_HZ -> G800
                else -> continue
            }
            genre[i] = g; genre[j] = g
        }
        return s.mapIndexed { i, x -> if (genre[i] == x.genre) x else x.copy(genre = genre[i]) }
    }

    /**
     * Where the recording holds **voices** (a [PHONIE] zone each): a frame
     * speaks when at least [VOIX_BANDES] bands over [VOIX_ETENDUE_HZ] stand
     * [VOIX_DB] above the floor, each a peak among its neighbours (FM hiss,
     * however loud, is smooth), and most of them were not lit a second before
     * nor are a second after — a voice's harmonics and formants move all the
     * time, where FT4, FT8, a carrier or a wideband modem stay put for
     * seconds (and SSTV, one tone at a time, is too narrow). A second speaks
     * with [VOIX_PAR_SECONDE] such frames; seconds closer than [VOIX_JOINT_S]
     * gathered, a zone when [VOIX_SECONDES_MIN] of them speak and they are
     * [VOIX_DENSITE] of its length. Its band: where its frames were lit,
     * a twentieth trimmed on each side; [Zone.salves], the seconds that speak.
     */
    fun voix(m: Mesure, origineMs: Long): List<Zone> {
        val nT = m.trames.size; val nB = m.bandes
        if (nT < 20) return emptyList()
        val rel = Array(nT) { t -> val l = m.trames[t]; val f = mediane(l); FloatArray(nB) { l[it] - f } }
        val usuel = FloatArray(nB) { b -> val v = FloatArray(nT) { rel[it][b] }; v.sort(); v[nT / 5] }
        val b0 = ((VOIX_BASSE_HZ - BASSE_HZ) / BANDE_HZ).toInt(); val b1 = ((VOIX_HAUTE_HZ - BASSE_HZ) / BANDE_HZ).toInt()
        // A band lit: above its usual level, and a peak among its neighbours (a harmonic, a formant): hiss, even
        // loud, is smooth across the bands.
        val voisins = FloatArray(7)
        val allume = Array(nT) { t -> val l = m.trames[t]; BooleanArray(nB) { b ->
            if (b !in b0 until b1 || rel[t][b] - usuel[b] < VOIX_DB) return@BooleanArray false
            for (k in -3..3) voisins[k + 3] = l[(b + k).coerceIn(0, nB - 1)]
            voisins.sort()
            l[b] - voisins[3] >= VOIX_CONTRASTE_DB
        } }
        val recul = maxOf(1, Math.round(1000.0 / m.trameMs).toInt())
        val parle = BooleanArray(nT) { t ->
            val l = allume[t]
            var n = 0; var bas = -1; var haut = -1; var reste = 0
            for (b in 0 until nB) if (l[b]) {
                n++; if (bas < 0) bas = b; haut = b
                if ((t >= recul && allume[t - recul][b]) || (t + recul < nT && allume[t + recul][b])) reste++
            }
            n >= VOIX_BANDES && (haut - bas + 1) * BANDE_HZ >= VOIX_ETENDUE_HZ && reste < n * VOIX_RESTE
        }
        val seconde = { t: Int -> (t * m.trameMs / 1000).toInt() }
        val parSeconde = IntArray(seconde(nT - 1) + 1)
        for (t in 0 until nT) if (parle[t]) parSeconde[seconde(t)]++
        val sortie = ArrayList<Zone>()
        var debut = -1; var fin = -1; var dites = 0
        fun ferme() {
            if (debut >= 0 && dites >= VOIX_SECONDES_MIN && dites >= (fin - debut + 1) * VOIX_DENSITE) {
                val compte = IntArray(nB)
                for (t in 0 until nT) if (parle[t] && seconde(t) in debut..fin) for (b in 0 until nB) if (allume[t][b]) compte[b]++
                val total = compte.sum(); var cumul = 0; var bas = b0; var haut = b1 - 1
                for (b in 0 until nB) { cumul += compte[b]; if (cumul * 20 >= total) { bas = b; break } }
                cumul = 0
                for (b in nB - 1 downTo 0) { cumul += compte[b]; if (cumul * 20 >= total) { haut = b; break } }
                sortie += Zone(origineMs + debut * 1000L, origineMs + (fin + 1) * 1000L, PHONIE, dites,
                    BASSE_HZ + bas * BANDE_HZ, BASSE_HZ + (haut + 1) * BANDE_HZ)
            }
            debut = -1; dites = 0
        }
        for (s in parSeconde.indices) if (parSeconde[s] >= VOIX_PAR_SECONDE) {
            if (debut >= 0 && s - fin > VOIX_JOINT_S) ferme()
            if (debut < 0) debut = s
            fin = s; dites++
        }
        ferme()
        return sortie
    }
    /** Where a voice is looked for (Hz): SSB's and FM's speech band. */
    const val VOIX_BASSE_HZ = 250.0
    const val VOIX_HAUTE_HZ = 2800.0
    /** A band speaks this far (dB) above its usual level… */
    const val VOIX_DB = 6.0f
    /** …and this far above the median of its seven neighbours (a peak: hiss is smooth). */
    const val VOIX_CONTRASTE_DB = 3.0f
    /** A frame speaks with this many such bands… */
    const val VOIX_BANDES = 5
    /** …spread over this much (Hz)… */
    const val VOIX_ETENDUE_HZ = 300.0
    /** …fewer than this share of them lit a second before or after. */
    const val VOIX_RESTE = 0.5f
    /** A second speaks with this many frames that speak. */
    const val VOIX_PAR_SECONDE = 2
    /** Seconds that speak closer than this (s) belong to the same voice… */
    const val VOIX_JOINT_S = 8
    /** …a zone with at least this many of them… */
    const val VOIX_SECONDES_MIN = 6
    /** …being this share of its length. */
    const val VOIX_DENSITE = 0.3
    /** A burst this close (ms) to a voice, in its band, is the voice's. */
    const val VOIX_MARGE_MS = 8_000L
    /** Two voices this close (ms): one QSO, the pause between them the voice's too. */
    const val VOIX_PAUSE_MS = 45_000L

    /**
     * Everything at once: the voices ([voix]) and the digital zones ([zones]),
     * in time order; a burst inside a voice and in its band left out (a
     * harmonic held a moment looks narrow), one beside it kept (FT4 next to a
     * QSO on a linear transponder).
     */
    fun toutes(m: Mesure, origineMs: Long): List<Zone> {
        val v = voix(m, origineMs)
        // The QSO goes on between two voices close together (the other station not heard, a pause): its gap too.
        val qso = ArrayList<Zone>()
        for (z in v) {
            val d = qso.lastOrNull()
            if (d != null && z.debutMs - d.finMs <= VOIX_PAUSE_MS) qso[qso.size - 1] = d.copy(finMs = z.finMs, basHz = minOf(d.basHz, z.basHz), hautHz = maxOf(d.hautHz, z.hautHz))
            else qso += z
        }
        val s = salves(m, origineMs).filter { x ->
            qso.none { z -> x.debutMs < z.finMs + VOIX_MARGE_MS && x.finMs > z.debutMs - VOIX_MARGE_MS && x.basHz < z.hautHz && x.hautHz > z.basHz }
        }
        return (v + zones(s)).sortedBy { it.debutMs }
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
     * The zones: bursts closer than [JOINT_MS] gathered; a zone is GENESIS
     * ([G200], [G800]) when two of its frames at least are pairs of tones
     * ([paires]) and their bursts are no fewer than the FT-shaped others (FT4
     * stations 1125 Hz apart in the same slot make a pair now and then, not
     * most of the zone); FT4 or FT8
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
            val g2 = g.count { it.genre == G200 }; val g8 = g.count { it.genre == G800 }
            val mode = when {
                // Each frame is two bursts.
                (g2 + g8) >= 4 && (g2 + g8) >= n4 + n8 -> if (g2 >= g8) G200 else G800
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
        toutes(Mesure(cadence).also { it.ajoute(audio) }, origineMs)

    // ------------------------------------------------------------ kept beside the recording

    /**
     * "<name>.numerique": one zone a line — start, end (ms from the file's start), mode, bursts (a voice: its
     * seconds that speak), low and high Hz. Since v2 the voices are there too ([PHONIE]); a v1 file is worked out again.
     */
    fun ecrit(l: List<Zone>, origineMs: Long): String = "$ENTETE\n" + l.joinToString("") {
        "${it.debutMs - origineMs}\t${it.finMs - origineMs}\t${it.mode}\t${it.salves}\t${it.basHz.toInt()}\t${it.hautHz.toInt()}\n"
    }

    const val ENTETE = "# SatMe zones numériques v3"

    /**
     * Whether a kept file is of this version, else it is worked out again: v1 had no voices, v2 took GENESIS's
     * pairs of tones for FT4 (its zones still read meanwhile, and named again by [genesis] when the pass is a GENESIS
     * satellite's).
     */
    fun aJour(t: String): Boolean = t.startsWith(ENTETE)

    /**
     * The zones of a pass of a GENESIS satellite (its transmitter says so, or frames of it were decoded): a
     * digital zone (FT4, FT8, NUM) is its GENESIS — at the rate its own bursts showed, else [debit] (« 200 »,
     * « 800 », the frames' when known), else the rate the pass's other zones showed, else [GENESIS].
     */
    fun genesis(l: List<Zone>, debit: String? = null): List<Zone> {
        val vu = l.firstOrNull { it.mode == G200 || it.mode == G800 }?.mode
        val mode = when (debit) { "200" -> G200; "800" -> G800; else -> vu ?: GENESIS }
        return l.map { z -> if (z.mode == FT4 || z.mode == FT8 || z.mode == NUM) z.copy(mode = mode) else z }
    }

    /** The same for the zones' modes alone (a pass's summary). */
    fun genesisModes(l: List<String>, debit: String? = null): List<String> {
        val vu = l.firstOrNull { it == G200 || it == G800 }
        val mode = when (debit) { "200" -> G200; "800" -> G800; else -> vu ?: GENESIS }
        return l.map { if (it == FT4 || it == FT8 || it == NUM) mode else it }
    }

    fun lit(t: String, origineMs: Long): List<Zone> = t.lines().filter { it.isNotBlank() && !it.startsWith("#") }.mapNotNull { l ->
        val c = l.split('\t'); if (c.size < 6) return@mapNotNull null
        runCatching {
            Zone(origineMs + c[0].toLong(), origineMs + c[1].toLong(), c[2].takeIf { it in MODES } ?: NUM,
                c[3].toInt(), c[4].toDouble(), c[5].toDouble())
        }.getOrNull()
    }
}
