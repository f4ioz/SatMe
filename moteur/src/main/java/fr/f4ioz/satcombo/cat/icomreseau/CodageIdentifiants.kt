/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.cat.icomreseau

/**
 * The radio's network user and password as the login packet carries them:
 * not in clear, each character replaced by another byte, the same text
 * always giving the same bytes (the captures show one user's field
 * identical from one session to the next, and from one program to another).
 *
 * No public description gives the substitution itself, so nothing here
 * guesses it. Two ways in, both from what the owner sees on his own link:
 *
 * - the coded bytes themselves (hex), read once from a capture of a login
 *   that worked — the radio compares bytes, so they are all it needs;
 * - a table of what each character becomes at each place, filled from such
 *   pairs (a text and its coded bytes), kept in a small text file, so a
 *   password changed later is coded from what is already known.
 *
 * Both fields are at most 16 bytes.
 */
class CodageIdentifiants {

    /** (place, character) → coded byte. */
    private val table = HashMap<Long, Int>()

    private fun cle(place: Int, c: Int) = (place.toLong() shl 16) or c.toLong()

    val taille: Int get() = table.size

    /** Learns from a text and the bytes a working login carried for it. False when they disagree with what is known. */
    fun apprends(clair: String, code: ByteArray): Boolean {
        val t = clair.toByteArray(Charsets.ISO_8859_1)
        if (t.size > Paquets.CHAMP || code.size < t.size) return false
        for (i in t.indices) {
            val k = cle(i, t[i].toInt() and 0xFF)
            val v = code[i].toInt() and 0xFF
            val avant = table[k]
            if (avant != null && avant != v) return false
        }
        for (i in t.indices) table[cle(i, t[i].toInt() and 0xFF)] = code[i].toInt() and 0xFF
        return true
    }

    /** [clair] coded on 16 bytes, or null when a character at a place is not known yet. */
    fun code(clair: String): ByteArray? {
        val t = clair.toByteArray(Charsets.ISO_8859_1)
        if (t.size > Paquets.CHAMP) return null
        val r = ByteArray(Paquets.CHAMP)
        for (i in t.indices) r[i] = (table[cle(i, t[i].toInt() and 0xFF)] ?: return null).toByte()
        return r
    }

    /** What is missing to code [clair]: the places whose character is not known. */
    fun manquants(clair: String): List<Int> {
        val t = clair.toByteArray(Charsets.ISO_8859_1)
        return t.indices.filter { table[cle(it, t[it].toInt() and 0xFF)] == null }
    }

    /** The table as text: one line per entry, `place code-du-caractère octet` (hex). */
    fun ecrit(): String = table.entries.sortedBy { it.key }.joinToString("\n") { (k, v) ->
        "%d %02x %02x".format((k shr 16).toInt(), (k and 0xFFFF).toInt(), v)
    }

    fun lis(texte: String) {
        for (l in texte.lineSequence()) {
            val p = l.trim().split(Regex("\\s+"))
            if (p.size != 3 || l.trimStart().startsWith("#")) continue
            val place = p[0].toIntOrNull() ?: continue
            val c = p[1].toIntOrNull(16) ?: continue
            val v = p[2].toIntOrNull(16) ?: continue
            if (place in 0 until Paquets.CHAMP && c in 0..255 && v in 0..255) table[cle(place, c)] = v
        }
    }

    companion object {
        /** Hex typed by hand ("74 49 6a 33", "74496a33…"), padded to 16 bytes; null when not hex or too long. */
        fun hex(s: String): ByteArray? {
            val t = s.filter { !it.isWhitespace() && it != ':' && it != '-' }
            if (t.isEmpty() || t.length % 2 != 0 || t.length > 2 * Paquets.CHAMP) return null
            val b = ByteArray(Paquets.CHAMP)
            for (i in 0 until t.length / 2) b[i] = (t.substring(2 * i, 2 * i + 2).toIntOrNull(16) ?: return null).toByte()
            return b
        }

        fun versHex(b: ByteArray): String {
            val n = b.indexOfLast { it.toInt() != 0 } + 1
            return (0 until n).joinToString("") { "%02x".format(b[it]) }
        }
    }
}
