/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.meteor

/**
 * From soft symbols to checked frames (VCDU, 892 bytes).
 *
 * 1. **Which way up**: the demodulator leaves the phase ambiguous (the eight
 *    symmetries of the square) and does not know which of I or Q starts a
 *    pair. Each of the sixteen readings is tried on a short stretch: decoded,
 *    coded again, compared; the right one agrees almost everywhere.
 * 2. **Viterbi**, then **NRZ-M**: a 1 is a change of level, which also takes
 *    away the last 180° doubt.
 * 3. **Sync word** 1ACFFC1D, then 1020 bytes; the pseudo-random sequence
 *    taken off; **Reed-Solomon** on four interleaved codewords.
 *
 * Losing the thread (a fade, a 90° slip of the carrier loop) is noticed by the
 * same check — decoded bits no longer coding back into what came in — and
 * the search starts again.
 */
class Trames(private val surTrame: (ByteArray) -> Unit) {

    var diagnostic: ((String) -> Unit)? = null

    // --- statistics
    var verrouille = false; private set
    var trames = 0; private set
    var tramesRatees = 0; private set
    var octetsCorriges = 0L; private set
    /** Share of coded bits that disagree with the decoded ones, last block. */
    var tauxErreurs = 1.0; private set

    // --- search
    private val recherche = ByteArray(2 * PAIRES_RECHERCHE + 2)
    private var nRecherche = 0
    private var lecture = 0          // which of the 16 readings
    private var bonsBlocs = 0
    private var mauvaisBlocs = 0

    // --- running decode
    private val viterbi = Convolutif.Decodeur()
    private var demiPaire = 0        // a soft symbol waiting for its partner
    private var enAttente = false
    private val bits = IntArray(1 shl 15)
    private var encodeur = 0         // register re-coding the decoded bits
    private val durs = IntArray(1 shl 15)   // hard symbol pairs, by step
    private var pas = 0L
    private var pasSortis = 0L
    private var erreursBloc = 0
    private var bitsBloc = 0
    private var dernierBit = 0

    // --- frame assembly
    private var registre = 0
    private var dansTrame = false
    private val trame = ByteArray(TRAME)
    private var nBits = 0
    private var manques = 0

    fun traite(soft: ByteArray, count: Int) {
        var i = 0
        while (i < count) {
            if (!verrouille) {
                val libre = recherche.size - nRecherche
                val k = minOf(libre, count - i)
                System.arraycopy(soft, i, recherche, nRecherche, k)
                nRecherche += k; i += k
                if (nRecherche == recherche.size) cherche()
            } else {
                entre(soft[i].toInt())
                i++
            }
        }
        sors(false)
    }

    /** Applies reading [l] to a symbol pair (a, b) — the eight symmetries of the square. */
    private fun lis(l: Int, a: Int, b: Int, out: IntArray) {
        var x = a; var y = b
        when (l and 3) {
            1 -> { val t = x; x = -y; y = t }
            2 -> { x = -x; y = -y }
            3 -> { val t = x; x = y; y = -t }
        }
        if (l and 4 != 0) { val t = x; x = y; y = t }
        out[0] = x; out[1] = y
    }

    private fun cherche() {
        var meilleur = -1
        var taux = 1.0
        val paire = IntArray(2)
        val sortie = IntArray(PAIRES_RECHERCHE)
        for (l in 0 until 16) {
            val decale = l shr 3
            val v = Convolutif.Decodeur()
            val dur = IntArray(2 * PAIRES_RECHERCHE)
            for (p in 0 until PAIRES_RECHERCHE) {
                lis(l, recherche[decale + 2 * p].toInt(), -recherche[decale + 2 * p + 1].toInt(), paire)
                v.pas(paire[0], paire[1])
                dur[2 * p] = if (paire[0] > 0) 1 else 0
                dur[2 * p + 1] = if (paire[1] > 0) 1 else 0
            }
            val n = v.lis(sortie, 0, final = true)
            val code = IntArray(2 * n)
            Convolutif.code(sortie.copyOf(n), code)
            var e = 0; var tot = 0
            for (k in 2 * 40 until 2 * (n - 40)) { if (code[k] != dur[k]) e++; tot++ }
            val t = e.toDouble() / tot
            if (t < taux) { taux = t; meilleur = l }
        }
        tauxErreurs = taux
        if (meilleur >= 0 && taux < SEUIL) {
            lecture = meilleur
            verrouille = true
            viterbi.reinitialise()
            pas = 0; pasSortis = 0; encodeur = 0; enAttente = false
            erreursBloc = 0; bitsBloc = 0; mauvaisBlocs = 0
            val start = lecture shr 3
            val garde = recherche.copyOf(nRecherche)
            nRecherche = 0
            for (k in start until garde.size) entre(garde[k].toInt())
        } else {
            // Slide by a quarter, keep trying.
            val garde = recherche.size / 4
            System.arraycopy(recherche, garde, recherche, 0, recherche.size - garde)
            nRecherche = recherche.size - garde
        }
    }

    private val paire = IntArray(2)

    private fun entre(s: Int) {
        if (!enAttente) { demiPaire = s; enAttente = true; return }
        enAttente = false
        // The second code symbol is sent inverted.
        lis(lecture, demiPaire, -s, paire)
        viterbi.pas(paire[0], paire[1])
        durs[(pas and (durs.size - 1).toLong()).toInt()] =
            (if (paire[0] > 0) 2 else 0) or (if (paire[1] > 0) 1 else 0)
        pas++
        if (viterbi.enAttente > 4096) sors(false)
    }

    private fun sors(final: Boolean) {
        if (!verrouille) return
        val n = viterbi.lis(bits, 0, final)
        val out = IntArray(2)
        for (k in 0 until n) {
            val b = bits[k]
            // Does it code back into what came in?
            encodeur = ((encodeur shl 1) or b) and 0x7F
            val attendu = (parite(encodeur and Convolutif.G1) shl 1) or parite(encodeur and Convolutif.G2)
            val recu = durs[(pasSortis and (durs.size - 1).toLong()).toInt()]
            erreursBloc += Integer.bitCount(attendu xor recu)
            bitsBloc += 2
            pasSortis++
            // NRZ-M: a 1 is a change.
            val d = b xor dernierBit
            dernierBit = b
            bit(d)
            if (bitsBloc >= 2048) {
                tauxErreurs = erreursBloc.toDouble() / bitsBloc
                if (tauxErreurs > SEUIL_PERTE) mauvaisBlocs++ else mauvaisBlocs = 0
                erreursBloc = 0; bitsBloc = 0
                if (mauvaisBlocs >= 3) { decroche(); return }
            }
        }
        out[0] = 0
    }

    private fun decroche() {
        verrouille = false
        dansTrame = false
        nRecherche = 0
    }

    private fun parite(x: Int) = Integer.bitCount(x) and 1

    private fun bit(b: Int) {
        if (!dansTrame) {
            registre = (registre shl 1) or b
            if (Integer.bitCount(registre xor ASM) <= 3) { dansTrame = true; nBits = 0; trame.fill(0) }
            return
        }
        if (nBits < TRAME * 8) {
            if (b != 0) trame[nBits ushr 3] = (trame[nBits ushr 3].toInt() or (0x80 ushr (nBits and 7))).toByte()
            nBits++
            if (nBits == TRAME * 8) { finit(); registre = 0; nBits++ }
            return
        }
        // The next sync word, 32 bits; a few wrong ones are forgiven while the frames keep coming.
        registre = (registre shl 1) or b
        nBits++
        if (nBits == TRAME * 8 + 1 + 32) {
            if (Integer.bitCount(registre xor ASM) <= 8) { nBits = 0; trame.fill(0); manques = 0 }
            else { dansTrame = false; manques++ }
        }
    }

    private fun finit() {
        Derandomise.applique(trame, 0, TRAME)
        val cw = IntArray(ReedSolomon.N)
        var corriges = 0
        for (j in 0 until 4) {
            for (i in 0 until ReedSolomon.N) cw[i] = trame[i * 4 + j].toInt() and 0xFF
            val r = ReedSolomon.corrige(cw)
            if (r < 0) { tramesRatees++; return }
            if (r > 0) diagnostic?.invoke("cw $j : " + ReedSolomon.dernieresPositions)
            corriges += r
            for (i in 0 until ReedSolomon.N) trame[i * 4 + j] = cw[i].toByte()
        }
        octetsCorriges += corriges
        trames++
        surTrame(trame.copyOf(VCDU))
    }

    companion object {
        const val ASM = 0x1ACFFC1D
        const val TRAME = 1020
        const val VCDU = 892
        const val PAIRES_RECHERCHE = 1024
        const val SEUIL = 0.07
        const val SEUIL_PERTE = 0.09
    }
}
