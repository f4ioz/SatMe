/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.cw

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/**
 * Morse heard in the rig's audio, as text.
 *
 * The tone is found by itself (the strongest steady note between 300 and
 * 1 200 Hz, looked for again twice a second) or given. Its level is measured
 * every 5 ms (Goertzel); the key is down when it stands well above the noise
 * (thresholds that follow the noise floor and the signal's peaks). The dot's
 * length follows the sender: a short mark is a dot, a long one a dash, and
 * each one corrects the estimate. A silence of more than two dots ends a
 * letter, of more than five a word.
 *
 * Nothing Android here: the phone and SatMe-PC feed it the same way.
 */
class DecodeurCw(
    private val rate: Int,
    /** A letter (or a space between words) as it comes. */
    private val sortie: (String) -> Unit,
    /** The tone to listen to (Hz); 0 = found by itself. */
    tonFixe: Int = 0,
) {
    /** The tone listened to now (Hz). */
    @Volatile var tonHz: Int = if (tonFixe in TON_MIN..TON_MAX) tonFixe else 700
        private set
    @Volatile var tonAuto: Boolean = tonFixe == 0
    /** Speed estimated from the dots (words per minute). */
    val wpm: Int get() = (1200.0 / unitMs).toInt()
    /** The key down right now (a signal). */
    @Volatile var marque: Boolean = false
        private set
    /** How far the signal stands above the noise (dB). */
    @Volatile var ecartDb: Double = 0.0
        private set

    fun fixeTon(hz: Int) { if (hz in TON_MIN..TON_MAX) { tonHz = hz; tonAuto = false; tonPrecis = hz.toDouble() } else tonAuto = true }

    /**
     * The note moved by [hz] (the satellite's Doppler, when the rig does not
     * follow it): the decoder moves with it, before it even hears it.
     */
    fun decaleTon(hz: Double) {
        tonPrecis = (tonPrecis + hz).coerceIn(TON_MIN.toDouble(), TON_MAX.toDouble())
        tonHz = Math.round(tonPrecis).toInt()
    }

    /** The note, to a hertz, as followed (the Doppler slides it a little all the time). */
    @Volatile private var tonPrecis = tonHz.toDouble()

    // --------------------------------------------------------------- levels

    private val bloc = max(16, rate / 200)            // 5 ms
    private val msParBloc = bloc * 1000.0 / rate
    private var accu = DoubleArray(bloc)
    private var nAccu = 0

    private var bruit = -60.0
    private var crete = -60.0
    /** The level smoothed over about 10 ms: hiss alone flickers by ten decibels from one 5 ms block to the next. */
    private var lisse = 0.0

    // ----------------------------------------------------------- the tone

    /** A quarter of a second kept to look for the tone. */
    private val recherche = DoubleArray(rate / 4)
    private var nRecherche = 0

    // ------------------------------------------------- following the note

    /** The last 40 ms, to measure where the note is while the key is down. */
    private val fenetre = DoubleArray(max(64, rate / 25))
    private var posFenetre = 0
    private var blocsSuivi = 0
    /** Time since a key down was last heard (blocks): long enough, the note is looked for again in the whole band. */
    private var blocsSansSignal = 1_000_000

    /**
     * While the key is down, the note's power just below and above it:
     * the balance says which way it slid (parabola through three points),
     * and the note follows by a part of it — the Doppler of a pass slides it
     * by tens of hertz a minute.
     */
    private fun suitTon() {
        val n = fenetre.size
        val x = DoubleArray(n) { fenetre[(posFenetre + it) % n] }
        val pas = 25.0
        val a = goertzel(x, n, tonPrecis - pas); val b = goertzel(x, n, tonPrecis); val c = goertzel(x, n, tonPrecis + pas)
        val den = a - 2 * b + c
        if (b <= a || b <= c || den >= 0) {
            // The note left the middle: towards the stronger side.
            if (a > b || c > b) decaleTon(if (c > a) pas / 2 else -pas / 2)
            return
        }
        val decalage = 0.5 * (a - c) / den * pas
        decaleTon((decalage * 0.5).coerceIn(-8.0, 8.0))
    }

    // --------------------------------------------------------------- timing

    private var unitMs = 60.0                          // 20 WPM to start with
    private var blocsMarque = 0
    private var hautMarque = 0.0
    private var blocsSilence = 0
    private val symboles = StringBuilder()
    private var motFini = true

    /** A block of the sound. */
    fun ecoute(pcm: ShortArray, n: Int) {
        for (i in 0 until n) {
            val x = pcm[i] / 32768.0
            accu[nAccu++] = x
            fenetre[posFenetre] = x; posFenetre = (posFenetre + 1) % fenetre.size
            if (nAccu == bloc) { traiteBloc(); nAccu = 0 }
            // The whole band searched only when nothing has been heard for a while: in a QSO the note is followed, not hunted.
            if (tonAuto && blocsSansSignal * msParBloc > 1500) {
                recherche[nRecherche++] = x
                if (nRecherche == recherche.size) { chercheTon(); nRecherche = 0 }
            }
        }
    }

    private fun goertzel(x: DoubleArray, n: Int, hz: Double): Double {
        val k = 2 * cos(2 * PI * hz / rate)
        var s1 = 0.0; var s2 = 0.0
        for (i in 0 until n) { val s0 = x[i] + k * s1 - s2; s2 = s1; s1 = s0 }
        return (s1 * s1 + s2 * s2 - k * s1 * s2) / (n.toDouble() * n)
    }

    /** The strongest steady note of the last quarter second, if it stands out. */
    private fun chercheTon() {
        var meilleur = 0.0; var hzMeilleur = 0
        val puissances = ArrayList<Double>()
        var hz = TON_MIN
        while (hz <= TON_MAX) {
            val p = goertzel(recherche, recherche.size, hz.toDouble())
            puissances += p
            if (p > meilleur) { meilleur = p; hzMeilleur = hz }
            hz += 10
        }
        val mediane = puissances.sorted()[puissances.size / 2]
        // 12 dB above the band's median: a carrier, not hiss; a small move is not worth changing.
        if (mediane > 0 && meilleur / mediane > 16 && kotlin.math.abs(hzMeilleur - tonHz) > 15) { tonHz = hzMeilleur; tonPrecis = hzMeilleur.toDouble() }
    }

    private fun traiteBloc() {
        val p = goertzel(accu, bloc, tonPrecis)
        // The last two blocks averaged: steadier than one, back to the noise 10 ms after the key is up.
        val db = 10 * log10((p + lisse) / 2 + 1e-12)
        lisse = p
        // The noise floor follows the lows quickly and rises slowly; the peak follows the highs and decays.
        bruit = if (db < bruit) bruit + (db - bruit) * 0.3 else bruit + (db - bruit) * 0.004
        crete = if (db > crete) db else crete - 0.015 * msParBloc
        if (crete < bruit) crete = bruit
        ecartDb = crete - bruit
        val seuil = bruit + (crete - bruit) * 0.5
        val haut = if (ecartDb >= ECART_MIN_DB) db > seuil + 1.0 else false
        val bas = db < seuil - 1.0 || ecartDb < ECART_MIN_DB
        val avant = marque
        marque = if (marque) !bas else haut
        if (marque) {
            if (!avant) { finSilence(); blocsSilence = 0; hautMarque = 0.0 }
            blocsMarque++
            blocsSansSignal = 0
            // Every 40 ms of a key down (after its first 20 ms): where is the note now?
            if (blocsMarque * msParBloc >= 20 && ++blocsSuivi * msParBloc >= 40) { blocsSuivi = 0; suitTon() }
            hautMarque = max(hautMarque, db - bruit)
        } else {
            // A real key down stands far above the hiss; the hiss's own flickers do not.
            if (avant) { if (hautMarque >= MARQUE_MIN_DB) finMarque(blocsMarque * msParBloc); blocsMarque = 0 }
            blocsSilence++
            blocsSansSignal++
            silenceEnCours(blocsSilence * msParBloc)
        }
    }

    private fun finMarque(ms: Double) {
        if (ms < unitMs * 0.4 || ms < 18.0) return              // a crackle
        // Each mark corrects the dot's length, by a little at a time: one odd mark does not throw the speed.
        val mesure = if (ms < unitMs * 2.0) { symboles.append('.'); ms } else { symboles.append('-'); ms / 3.0 }
        unitMs = unitMs * 0.85 + mesure.coerceIn(unitMs * 0.6, unitMs * 1.6) * 0.15
        unitMs = unitMs.coerceIn(UNIT_MIN_MS, UNIT_MAX_MS)
        if (symboles.length > 8) symboles.setLength(0)          // not Morse: forgotten
    }

    private fun finSilence() {}

    private fun silenceEnCours(ms: Double) {
        if (symboles.isNotEmpty() && ms >= unitMs * 2.0) {
            sortie(MORSE[symboles.toString()] ?: "*")
            symboles.setLength(0)
            motFini = false
        }
        if (!motFini && ms >= unitMs * 5.0) { sortie(" "); motFini = true }
    }

    companion object {
        const val TON_MIN = 300
        const val TON_MAX = 1200
        const val UNIT_MIN_MS = 30.0     // 40 WPM
        const val UNIT_MAX_MS = 240.0    // 5 WPM
        /** How far above the noise a key down must stand. */
        const val ECART_MIN_DB = 10.0
        /** How far above the noise a mark's top must stand to count. */
        const val MARQUE_MIN_DB = 16.0

        val MORSE: Map<String, String> = mapOf(
            ".-" to "A", "-..." to "B", "-.-." to "C", "-.." to "D", "." to "E", "..-." to "F", "--." to "G", "...." to "H",
            ".." to "I", ".---" to "J", "-.-" to "K", ".-.." to "L", "--" to "M", "-." to "N", "---" to "O", ".--." to "P",
            "--.-" to "Q", ".-." to "R", "..." to "S", "-" to "T", "..-" to "U", "...-" to "V", ".--" to "W", "-..-" to "X",
            "-.--" to "Y", "--.." to "Z",
            "-----" to "0", ".----" to "1", "..---" to "2", "...--" to "3", "....-" to "4", "....." to "5", "-...." to "6",
            "--..." to "7", "---.." to "8", "----." to "9",
            ".-.-.-" to ".", "--..--" to ",", "..--.." to "?", "-..-." to "/", "-...-" to "=", "-....-" to "-",
            ".-.-." to "+", "-.--." to "(", "-.--.-" to ")", ".----." to "'", "---..." to ":", "...-.-" to "<SK>", ".-..." to "<AS>",
        )
        private val VERS_MORSE = MORSE.entries.associate { (k, v) -> v to k }

        /** Morse for [texte] at [wpm], as keying: (key down?, ms) — for tests and a test card. */
        fun manipulation(texte: String, wpm: Int): List<Pair<Boolean, Double>> {
            val u = 1200.0 / wpm
            val l = ArrayList<Pair<Boolean, Double>>()
            texte.uppercase().split(' ').filter { it.isNotEmpty() }.forEachIndexed { iMot, mot ->
                if (iMot > 0) l += false to 7 * u
                mot.forEachIndexed { iLettre, c ->
                    if (iLettre > 0) l += false to 3 * u
                    val code = VERS_MORSE[c.toString()] ?: return@forEachIndexed
                    code.forEachIndexed { i, s ->
                        if (i > 0) l += false to u
                        l += true to (if (s == '.') u else 3 * u)
                    }
                }
            }
            return l
        }
    }
}
