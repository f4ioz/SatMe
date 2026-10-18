/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.ais

/**
 * AIS messages as NMEA 0183 sentences (« !AIVDM »): the bits armoured six
 * at a time into printable characters (value + 48, plus 8 more above 39),
 * the sentence split in fragments of at most [MAX_CAR] characters, the
 * checksum the XOR of everything between « ! » and « * ».
 */
object Aivdm {
    const val MAX_CAR = 60

    /** The armoured characters of [bits] and the fill bits added to reach a multiple of six. */
    fun arme(bits: BooleanArray): Pair<String, Int> {
        val n = (bits.size + 5) / 6
        val sb = StringBuilder(n)
        for (k in 0 until n) {
            var v = 0
            for (j in 0 until 6) { val i = 6 * k + j; v = (v shl 1) or (if (i < bits.size && bits[i]) 1 else 0) }
            sb.append((v + if (v < 40) 48 else 56).toChar())
        }
        return sb.toString() to (6 * n - bits.size)
    }

    /** The bits of armoured [texte], [remplissage] fill bits dropped at the end; null on a character outside the armour. */
    fun desarme(texte: String, remplissage: Int = 0): BooleanArray? {
        val out = BooleanArray((texte.length * 6 - remplissage).coerceAtLeast(0))
        for ((k, c) in texte.withIndex()) {
            var v = c.code - 48
            if (v < 0 || v > 71 || v in 40..47) return null
            if (v > 40) v -= 8
            for (j in 0 until 6) { val i = 6 * k + j; if (i < out.size) out[i] = (v shr (5 - j)) and 1 == 1 }
        }
        return out
    }

    fun somme(corps: String): Int = corps.fold(0) { a, c -> a xor c.code }

    /** The sentences carrying [bits] heard on [canal] ('A' or 'B'); [sequence] (0–9) numbers a message of several fragments. */
    fun phrases(bits: BooleanArray, canal: Char, sequence: Int = 0, entete: String = "!AIVDM"): List<String> {
        val (t, fill) = arme(bits)
        val morceaux = t.chunked(MAX_CAR).ifEmpty { listOf("") }
        return morceaux.mapIndexed { i, m ->
            val corps = entete.drop(1) + "," + morceaux.size + "," + (i + 1) + "," + (if (morceaux.size > 1) (sequence % 10).toString() else "") +
                "," + canal + "," + m + "," + (if (i == morceaux.lastIndex) fill else 0)
            entete.take(1) + corps + "*" + "%02X".format(somme(corps))
        }
    }

    /**
     * Gathers sentences into messages: [lit] gives the bits and the channel
     * when a message is whole, null otherwise (a fragment kept, a bad
     * checksum, a sentence not AIS).
     */
    class Assembleur {
        private var attente: Triple<String, Int, StringBuilder>? = null
        private var vus = 0
        var rejetees = 0; private set

        fun lit(ligne: String): Pair<BooleanArray, Char>? {
            val l = ligne.trim()
            val etoile = l.lastIndexOf('*')
            if (l.length < 10 || (l[0] != '!' && l[0] != '$') || etoile < 0 || etoile + 3 > l.length) { rejetees++; return null }
            val corps = l.substring(1, etoile)
            if (l.substring(etoile + 1, etoile + 3).toIntOrNull(16) != somme(corps)) { rejetees++; return null }
            val c = corps.split(',')
            if (c.size < 7 || !(c[0].endsWith("VDM") || c[0].endsWith("VDO"))) { rejetees++; return null }
            val total = c[1].toIntOrNull() ?: return null
            val num = c[2].toIntOrNull() ?: return null
            val canal = c[4].firstOrNull() ?: 'A'
            val fill = c[6].toIntOrNull() ?: 0
            if (total == 1) { attente = null; return desarme(c[5], fill)?.let { it to canal } }
            if (num == 1) { attente = Triple(c[3], total, StringBuilder(c[5])); vus = 1; return null }
            val a = attente ?: return null
            if (a.first != c[3] || a.second != total || num != vus + 1) { attente = null; return null }
            a.third.append(c[5]); vus = num
            if (num < total) return null
            attente = null
            return desarme(a.third.toString(), fill)?.let { it to canal }
        }
    }
}
