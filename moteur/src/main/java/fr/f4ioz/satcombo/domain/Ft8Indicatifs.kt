/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import fr.f4ioz.satcombo.plateforme.Stockage

/**
 * Callsigns heard in clear, to put a name on the hashed ones.
 *
 * An FT8/FT4 message has room for one compound callsign (`F/G4ABC`,
 * `PJ4/K1ABC`, `F4IOZ/P`) at most; the other station travels as a hash of
 * 22, 12 or 10 bits. A hash cannot be reversed: it is matched against the
 * callsigns already heard in clear. Hence this table, fed by every unpacked
 * message, seeded with the log, and always holding our own callsign.
 *
 * Kept: the [capacite] most recent callsigns heard (saved between two
 * starts), the log's (seeded at start, not saved) and ours. On a hash several
 * callsigns share, the most recent heard wins; the log only answers when a
 * single one of its callsigns matches.
 */
class Ft8Indicatifs(private val capacite: Int = 300) {

    /** Heard in clear, oldest first: callsign → its 22-bit hash. */
    private val recents = LinkedHashMap<String, Int>()
    /** The log's callsigns: callsign → its 22-bit hash. */
    private val carnet = HashMap<String, Int>()
    private var stockage: Stockage? = null
    private var change = false

    /** Our own callsign (station settings), read when needed: always resolved. */
    @Volatile var moi: () -> String = { "" }

    /** A callsign heard in clear: it moves to the newest end. */
    @Synchronized fun retiens(indicatif: String) {
        val s = propre(indicatif) ?: return
        val h = hache(s, 22) ?: return
        if (recents.keys.lastOrNull() == s) return
        recents.remove(s)
        recents[s] = h
        while (recents.size > capacite) recents.remove(recents.keys.first())
        change = true
    }

    /** The log's callsigns: replaces the previous seeding. */
    @Synchronized fun amorce(indicatifs: Collection<String>) {
        carnet.clear()
        for (i in indicatifs) {
            val s = propre(i) ?: continue
            carnet[s] = hache(s, 22) ?: continue
        }
    }

    /** The callsign behind a hash of [bits] bits (22, 12 or 10), or `null`. */
    fun trouve(valeur: Int, bits: Int): String? {
        require(bits in 1..22) { "hachage de 1 à 22 bits" }
        propre(moi())?.let { if (hache(it, bits) == valeur) return it }
        synchronized(this) {
            for ((s, h) in recents.entries.reversed()) if (h ushr (22 - bits) == valeur) return s
            val l = carnet.entries.filter { it.value ushr (22 - bits) == valeur }
            return l.singleOrNull()?.key
        }
    }

    /** The callsigns heard, newest first. */
    @Synchronized fun indicatifs(): List<String> = recents.keys.reversed()

    /** Reads back what [stockage] holds, then saves there with [sauve]. */
    @Synchronized fun branche(stockage: Stockage) {
        this.stockage = stockage
        stockage.getString(CLE, null)?.split(' ')?.forEach { retiens(it) }
        change = false
    }

    /** Writes the table if it changed since the last time. */
    fun sauve() {
        val (st, texte) = synchronized(this) {
            val st = stockage ?: return
            if (!change) return
            change = false
            st to recents.keys.joinToString(" ")
        }
        runCatching { st.edit().putString(CLE, texte).apply() }
    }

    companion object {
        /** The table the decoders fill and read. */
        val commun = Ft8Indicatifs()

        private const val CLE = "ft8_indicatifs"

        /** Alphabet of the hash (and of 58-bit callsigns): 38 characters. */
        internal const val A38 = " 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ/"

        /**
         * The [bits]-bit hash of a callsign.
         *
         * The callsign, padded with spaces to 11 characters, read as a base-38
         * number; multiplied by 47 055 833 459 modulo 2^64; the top [bits]
         * bits kept. `null` for what is not a callsign.
         */
        fun hache(indicatif: String, bits: Int): Int? {
            val s = indicatif.trim().uppercase().removePrefix("<").removeSuffix(">")
            if (s.isEmpty() || s.length > 11 || bits !in 1..32) return null
            var n = 0L
            for (i in 0 until 11) {
                val k = A38.indexOf(if (i < s.length) s[i] else ' ')
                if (k < 0) return null
                n = n * 38 + k
            }
            return ((n * 47_055_833_459L) ushr (64 - bits)).toInt()
        }

        /** A callsign proper: prefix, area digits, one to four letters ("F4IOZ", "3DA0ABC", "TM100XYZ"). */
        private val BASE = Regex("(?:[A-Z]{1,2}|[A-Z][0-9]|[0-9][A-Z]{1,2})[0-9]{1,4}[A-Z]{1,4}")
        /** Before it, a country prefix ("F", "PJ4", "3DA0"). */
        private val AVANT = Regex("[A-Z0-9]{0,3}[A-Z][A-Z0-9]{0,3}")
        /** After it, the usual suffixes ("P", "MM", "QRP", "7"). */
        private val APRES = Regex("[A-Z0-9]|MM|AM|QRP|LH")

        /**
         * What looks like a callsign: up to 11 characters, a callsign proper
         * with at most a prefix before and a suffix after. Garbage that passed
         * the CRC, words ("RR73", "DX") and stray slashes stay out.
         */
        fun propre(indicatif: String?): String? {
            val s = indicatif?.trim()?.uppercase()?.removePrefix("<")?.removeSuffix(">") ?: return null
            if (s.length !in 3..11) return null
            val parts = s.split('/')
            val i = parts.indexOfFirst { BASE.matches(it) }
            if (i < 0 || parts.size > 3) return null
            for ((k, p) in parts.withIndex()) {
                if (k < i && (k != i - 1 || !AVANT.matches(p))) return null
                if (k > i && (k != i + 1 || !APRES.matches(p))) return null
            }
            return s
        }
    }
}
