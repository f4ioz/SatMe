/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The receiver the automatic SSTV tuning may move: the SDR's fine offset, or
 * the rig through SatMe's own CAT writes.
 */
interface CibleAfc {
    /** Which way the tones move when the receiver goes up: +1 (USB), -1 (LSB); 0 they do not (FM, AM, unknown). */
    fun sens(): Int
    /** May be moved now; otherwise the correction is only advised. */
    fun disponible(): Boolean = true
    /**
     * Moved between two blocks of the very sound fed here (the SDR's own
     * loop): the decoder is told at which sample the move lands. False when
     * the sound comes back later (a rig, a sound card).
     */
    val couplee: Boolean
    /** Its fine tuning now (Hz); a change not made here is the operator's. */
    fun position(): Long
    /** Moves it by [hz] (receiver Hz, signed). */
    fun deplace(hz: Int)
}

/**
 * How far the SSTV tones sit from where they should, measured on the sound:
 * the header's 1900 Hz leaders (and its 1200 Hz start and stop bits around
 * its 1100/1300 Hz data), the 1200 Hz sync pulses the decoder follows,
 * checked against the picture's black and white edges (1500 and 2300 Hz).
 * Every value is in the sound as it arrives now: when the receiver moves,
 * [decale] moves what was measured before.
 */
class SstvEcart(private val rate: Int) {
    private val demod = Demodulator(rate)
    private var large = FloatArray(4096)
    private var etroit = FloatArray(4096)
    private val spms = rate / 1000.0

    /** Samples measured so far. */
    var echantillons = 0L; private set

    // Millisecond means of the wide reading, with prefix sums (value, square).
    private val cap = 2048
    private val ms = DoubleArray(cap)
    private val somme = DoubleArray(cap + 1)
    private val somme2 = DoubleArray(cap + 1)
    private var msLen = 0
    private var msBase = 0L
    private var msFaits = 0L
    private var accMs = 0.0
    private var accN = 0
    private var prochainTest = 0L

    // Sync pulses: their offset, when measured (samples).
    private val syncHz = DoubleArray(SYNCS)
    private val syncT = LongArray(SYNCS)
    private var syncN = 0

    // The picture's millisecond means, for its black and white edges.
    private val bord = FloatArray(BORDS)
    private var bordN = 0
    private var ignoreAvant = 0L

    /** The last header's offset (Hz), taken once by [prendVis]. */
    private var vis: Double? = null
    /** Where that header ended (samples measured). */
    var visFin = 0L; private set
    /** And its mode. */
    var visMode: SstvMode? = null; private set

    /** Feeds sound; [image]: a picture is being decoded (its edges count). */
    fun ajoute(pcm: ShortArray, n: Int, image: Boolean) {
        if (n <= 0) return
        if (large.size < n) { large = FloatArray(n); etroit = FloatArray(n) }
        demod.process(pcm, n, large, etroit)
        for (i in 0 until n) {
            accMs += large[i]; accN++
            echantillons++
            if ((msFaits + 1) * spms <= echantillons) {
                val v = accMs / accN
                accMs = 0.0; accN = 0
                pousseMs(v)
                if (image && echantillons >= ignoreAvant && v in 1250.0..2800.0) {
                    bord[bordN % BORDS] = v.toFloat(); bordN++
                }
            }
        }
        chercheVis()
    }

    private fun pousseMs(v: Double) {
        if (msLen >= cap) {
            val d = cap / 2
            System.arraycopy(ms, d, ms, 0, msLen - d)
            msLen -= d; msBase += d
            somme[0] = 0.0; somme2[0] = 0.0
            for (k in 0 until msLen) { somme[k + 1] = somme[k] + ms[k]; somme2[k + 1] = somme2[k] + ms[k] * ms[k] }
        }
        ms[msLen] = v
        somme[msLen + 1] = somme[msLen] + v
        somme2[msLen + 1] = somme2[msLen] + v * v
        msLen++; msFaits++
        if (prochainTest < msBase) prochainTest = msBase
    }

    private fun moy(a: Long, b: Long): Double {
        val i = (a - msBase).toInt(); val j = (b - msBase).toInt()
        if (i < 0 || j > msLen || j <= i) return 0.0
        return (somme[j] - somme[i]) / (j - i)
    }

    private fun ecartType(a: Long, b: Long): Double {
        val i = (a - msBase).toInt(); val j = (b - msBase).toInt()
        if (i < 0 || j > msLen || j <= i) return 1e9
        val m = (somme[j] - somme[i]) / (j - i)
        return sqrt(maxOf(0.0, (somme2[j] - somme2[i]) / (j - i) - m * m))
    }

    /**
     * The header, as the decoder reads it but over ±[VIS_MAX_HZ]: both
     * leaders steady and agreeing, a break, start and stop bits at 1200 Hz,
     * eight data bits clear of it with even parity, a mode SatMe knows.
     */
    private fun chercheVis() {
        val fin = msBase + msLen
        while (prochainTest + SstvDecoder.VIS_MS <= fin) {
            val s = prochainTest
            prochainTest++
            val l1 = moy(s + 20, s + 290); val l2 = moy(s + 330, s + 600)
            if (abs(l1 - l2) > 40.0) continue
            val off = (l1 + l2) / 2 - 1900.0
            if (abs(off) > VIS_MAX_HZ) continue
            // Noise: a weak header is not a reference.
            if (ecartType(s + 20, s + 290) > 45.0 || ecartType(s + 330, s + 600) > 45.0) continue
            if (moy(s + 301, s + 309) - off > 1600.0) continue
            val debut = moy(s + 611, s + 639) - off; val arret = moy(s + 881, s + 909) - off
            if (abs(debut - 1200.0) > 110.0 || abs(arret - 1200.0) > 110.0) continue
            var code = 0; var uns = 0; var ok = true
            for (b in 0 until 8) {
                val f = moy(s + 641 + 30L * b, s + 669 + 30L * b) - off
                if (abs(f - 1200.0) < 40.0) { ok = false; break }
                if (f < 1200.0) { uns++; if (b < 7) code = code or (1 shl b) }
            }
            if (!ok || uns % 2 != 0 || SstvMode.byVis(code) == null) continue
            // The leaders alone: 600 ms of a steady tone; the 30 ms bits lean towards their neighbours.
            vis = off
            visFin = ((s + SstvDecoder.VIS_MS) * spms).toLong()
            visMode = SstvMode.byVis(code)
            prochainTest = s + SstvDecoder.VIS_MS + 60
        }
    }

    /** A sync pulse the decoder followed: its offset and its spread (Hz). */
    fun sync(ecartHz: Double, bruitHz: Double) {
        if (echantillons < ignoreAvant) return
        if (bruitHz > BRUIT_MAX_HZ || abs(ecartHz) > 450.0) return
        val k = syncN % SYNCS
        syncHz[k] = ecartHz; syncT[k] = echantillons; syncN++
    }

    /** The header's offset (Hz) when one was just heard; null otherwise. Taken once. */
    fun prendVis(): Double? = vis.also { vis = null }

    /**
     * The offset now (Hz): the median of the newest sync pulses of the last
     * [FENETRE_S] s when at least [SYNCS_MIN] agree (median deviation under
     * [MAD_MAX_HZ]); null otherwise. The picture's black and white edges,
     * when both show (800 Hz apart, give or take), must agree with it: alone
     * they are no reference — in noise they read 170 Hz off on pictures in
     * tune.
     */
    fun estimation(): Double? {
        val depuis = echantillons - (FENETRE_S * rate).toLong()
        val v = ArrayList<Double>(SYNCS)
        // The newest [SYNCS_UTILES]: a slow mode sends one pulse a second or more (PD 90: 1.3 s), a fast one ten.
        val recents = (0 until minOf(syncN, SYNCS)).filter { syncT[it] >= depuis && syncT[it] >= ignoreAvant }.sortedByDescending { syncT[it] }.take(SYNCS_UTILES)
        for (k in recents) v += syncHz[k]
        if (v.size < SYNCS_MIN) return null
        v.sort()
        val med = v[v.size / 2]
        val mad = v.map { abs(it - med) }.sorted()[v.size / 2]
        if (mad > MAD_MAX_HZ) return null
        val b = bords()
        if (b != null && abs(b - med) > BORDS_ACCORD_HZ) return null
        return med
    }

    /** The picture's edges: the middle of its 2nd and 98th percentiles, less 1900 Hz, when 740–860 Hz apart. */
    fun bords(): Double? {
        val n = minOf(bordN, BORDS)
        if (n < BORDS_MIN) return null
        val t = bord.copyOf(n); t.sort()
        val bas = t[(n * 0.02).toInt()]; val haut = t[(n * 0.98).toInt().coerceAtMost(n - 1)]
        val ecart = haut - bas
        if (ecart < 740f || ecart > 860f) return null
        return (bas + haut) / 2.0 - 1900.0
    }

    /** The receiver moved: every tone now arrives [hz] lower. [attenteMs]: its sound comes back that much later. */
    fun decale(hz: Double, attenteMs: Int = 0) {
        for (k in 0 until minOf(syncN, SYNCS)) syncHz[k] -= hz
        for (k in 0 until minOf(bordN, BORDS)) bord[k] -= hz.toFloat()
        vis = vis?.let { it - hz }
        if (attenteMs > 0) {
            ignoreAvant = echantillons + attenteMs * rate / 1000L
            // The sound still in flight was tuned the old way: its edges are forgotten.
            bordN = 0
        }
    }

    /** Forgets what was measured (the operator retuned). */
    fun oublie() { syncN = 0; bordN = 0; vis = null }

    companion object {
        /** Two crystals (a Pluto, a bladeRF, an LNB) easily differ by 600 Hz and more at 23 cm: ±1 kHz. */
        const val VIS_MAX_HZ = 1000.0
        const val BRUIT_MAX_HZ = 60.0
        const val FENETRE_S = 20.0
        const val SYNCS_UTILES = 9
        const val SYNCS = 32
        const val SYNCS_MIN = 5
        const val MAD_MAX_HZ = 20.0
        private const val BORDS = 8000
        private const val BORDS_MIN = 3000
        private const val BORDS_ACCORD_HZ = 40.0
    }
}

/**
 * Automatic SSTV tuning: measures the tones' offset ([SstvEcart]) and moves
 * the receiver gently to bring them home.
 *
 * - At most once a second, by at most [PAS_MAX_HZ], outside a dead band of
 *   ±[ZONE_MORTE_HZ]; never more than ±[LIMITE_HZ] from where the operator
 *   tuned.
 * - At a header, a receiver moved in step with the decoder ([CibleAfc.couplee])
 *   takes the whole offset at once: the picture has not started, and the
 *   decoder is told exactly where the move lands, so no line sees a jump. A
 *   header beyond the decoder's own reach is handed to it.
 * - During a picture the small steps go on: coupled, the decoder's correction
 *   follows at the very sample; otherwise its own tracking absorbs 20 Hz.
 *
 * [nourrit] takes the decoder's place in feeding it. Off, none of this runs:
 * the decoder is untouched.
 */
class SstvAfc(private val rate: Int, /** Small steps during a picture too (else only between pictures). */ private val pendantImage: Boolean = true) {

    class Etat(
        /** The offset measured now (Hz, in the sound as it arrives), null when not known. */
        val mesureHz: Int? = null,
        /** The correction applied so far (Hz of sound, the tones moved down by it). */
        val totalHz: Int = 0,
        /** Moves made. */
        val corrections: Int = 0,
        /** The receiver can be moved now (else [mesureHz] is only advice). */
        val applique: Boolean = false,
        /** How the tones follow the receiver (see [CibleAfc.sens]); 0 unknown or not at all. */
        val sens: Int = 0,
        /** The last header's offset (Hz), null before any. */
        val visHz: Int? = null,
    )

    val ecart = SstvEcart(rate)
    @Volatile var etat = Etat(); private set

    private var total = 0
    private var corrections = 0
    private var aRecaler = 0.0
    private var aPoser: Double? = null
    private var dernierPas = Long.MIN_VALUE / 2
    private var positionAttendue: Long? = null
    private var dernierVis: Int? = null
    private var branche: SstvDecoder? = null

    /**
     * One block: measured, given to [d], then [cible] moved when it is time
     * (the move lands with the next block). [attenteMs]: how late the sound
     * of an uncoupled receiver comes back after a move.
     */
    fun nourrit(d: SstvDecoder, pcm: ShortArray, n: Int, cible: CibleAfc?, attenteMs: Int = ATTENTE_MS) {
        if (n <= 0) return
        if (branche !== d) { branche = d; d.mesureSync = { e, b -> ecart.sync(e, b) } }
        // A move made after the last block lands with this one.
        val r = aRecaler; val p = aPoser
        if (r != 0.0 || p != null) { aRecaler = 0.0; aPoser = null; d.recale(r, p) }
        // The decoder's samples against the estimator's (started apart when the tuning was switched on later).
        val avance = d.echantillons - ecart.echantillons
        ecart.ajoute(pcm, n, d.decoding)
        val vis = ecart.prendVis()
        val sens = cible?.let { runCatching { it.sens() }.getOrDefault(0) } ?: 0
        val dispo = cible != null && sens != 0 && runCatching { cible.disponible() }.getOrDefault(false)
        val couplee = cible?.couplee == true
        // The operator retuned: that is the new reference.
        if (cible != null) {
            val pos = runCatching { cible.position() }.getOrNull()
            val attendue = positionAttendue
            if (pos != null && attendue != null && pos != attendue) { total = 0; ecart.oublie(); aRecaler = 0.0; aPoser = null }
            positionAttendue = pos
        }
        // A header beyond the decoder's reach: handed to it, read in this very block.
        val visMode = ecart.visMode
        val aLire = vis != null && dispo && couplee && abs(vis) > ZONE_MORTE_HZ && abs(vis) > LECTURE_DECODEUR_HZ && visMode != null
        if (aLire) d.attendsEntete(visMode!!, ecart.visFin + avance, vis!!)
        val debutAvant = d.debutTrame
        d.feed(pcm, n)
        if (vis != null) dernierVis = vis.roundToInt()
        val mesure = ecart.estimation()
        val t = ecart.echantillons
        var pas = 0
        if (dispo && cible != null) {
            if (couplee && vis != null && abs(vis) > ZONE_MORTE_HZ) {
                // The whole offset at once. The decoder read the header (its frame starts there): its
                // correction moves with the receiver. It did not: it is told the offset left.
                pas = (total + vis.roundToInt()).coerceIn(-LIMITE_HZ, LIMITE_HZ) - total
                val lu = d.decoding && d.debutTrame != debutAvant &&
                    abs(d.debutTrame + (1_300L * rate / 1000) - (ecart.visFin + avance)) < rate / 10
                if (pas != 0 && bouge(cible, sens, pas, true, attenteMs, t)) {
                    if (lu) aRecaler += pas else { aRecaler = 0.0; aPoser = vis - pas }
                }
            } else {
                if (vis != null && abs(vis) > ZONE_MORTE_HZ) pas = vis.roundToInt().coerceIn(-PAS_MAX_HZ, PAS_MAX_HZ)
                else if (mesure != null && abs(mesure) > ZONE_MORTE_HZ && t - dernierPas >= rate && (pendantImage || !d.decoding))
                    pas = mesure.roundToInt().coerceIn(-PAS_MAX_HZ, PAS_MAX_HZ)
                pas = (total + pas).coerceIn(-LIMITE_HZ, LIMITE_HZ) - total
                if (pas != 0 && bouge(cible, sens, pas, couplee, attenteMs, t) && couplee) aRecaler += pas
            }
        }
        val reste = (if (pas != 0) mesure?.minus(pas) else mesure)?.roundToInt()
        etat = Etat(reste, total, corrections, dispo, sens, dernierVis)
    }

    private fun bouge(cible: CibleAfc, sens: Int, pas: Int, couplee: Boolean, attenteMs: Int, t: Long): Boolean {
        if (runCatching { cible.deplace(sens * pas) }.isFailure) return false
        total += pas; corrections++; dernierPas = t
        ecart.decale(pas.toDouble(), if (couplee) 0 else attenteMs)
        positionAttendue = runCatching { cible.position() }.getOrNull()
        return true
    }

    /**
     * Back to where the operator tuned: the receiver moved back by the total
     * (when it can be), the measurements forgotten.
     */
    fun remiseAZero(cible: CibleAfc?) {
        val sens = cible?.let { runCatching { it.sens() }.getOrDefault(0) } ?: 0
        if (cible != null && sens != 0 && total != 0) {
            if (runCatching { cible.deplace(-sens * total) }.isSuccess) {
                if (cible.couplee) { aRecaler -= total; aPoser = null }
                positionAttendue = runCatching { cible.position() }.getOrNull()
            }
        }
        total = 0; corrections = 0; dernierVis = null
        ecart.oublie()
        etat = Etat(sens = sens)
    }

    /** The correction applied so far (Hz of sound). */
    val totalHz: Int get() = total

    companion object {
        const val ZONE_MORTE_HZ = 15
        const val PAS_MAX_HZ = 20
        const val LIMITE_HZ = 2000
        /** A rig's sound comes back about that late after a frequency write (CAT round, rig, sound card). */
        const val ATTENTE_MS = 600
        /** Headers the decoder reads itself (its own reach, less a margin). */
        const val LECTURE_DECODEUR_HZ = 235.0

        /** +1 for USB, -1 for LSB, 0 otherwise (FM, AM, CW, unknown): how the tones follow a receiver in that mode. */
        fun sensDuMode(mode: String?): Int {
            val m = mode.orEmpty().uppercase()
            return when {
                m.contains("USB") -> 1
                m.contains("LSB") -> -1
                else -> 0
            }
        }
    }
}
