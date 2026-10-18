/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.annonces

/**
 * What a French voice reads well: numbers in words, callsigns spelled with
 * the ICAO alphabet (French spelling, so a French voice says them right),
 * frequencies digit by digit in groups. A speech engine left to « F4IOZ » or
 * « 10489.750 » says nonsense; written out, every engine says the same.
 */
object LectureAnnonce {

    /** The ICAO spelling alphabet, written as a French voice should say it. */
    val OACI = mapOf(
        'A' to "Alfa", 'B' to "Bravo", 'C' to "Charlie", 'D' to "Delta", 'E' to "Écho", 'F' to "Fox-trot",
        'G' to "Golf", 'H' to "Hôtel", 'I' to "India", 'J' to "Juliett", 'K' to "Kilo", 'L' to "Lima",
        'M' to "Mike", 'N' to "November", 'O' to "Oscar", 'P' to "Papa", 'Q' to "Québec", 'R' to "Roméo",
        'S' to "Sierra", 'T' to "Tango", 'U' to "Uniform", 'V' to "Victor", 'W' to "Whisky", 'X' to "X-ray",
        'Y' to "Yankee", 'Z' to "Zoulou",
    )

    private val CHIFFRES = arrayOf("zéro", "un", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf")
    private val UNITES = arrayOf("zéro", "un", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf",
        "dix", "onze", "douze", "treize", "quatorze", "quinze", "seize", "dix-sept", "dix-huit", "dix-neuf")
    private val DIZAINES = arrayOf("", "", "vingt", "trente", "quarante", "cinquante", "soixante")

    /** A digit's name. */
    fun chiffre(c: Char): String = CHIFFRES.getOrNull(c - '0') ?: c.toString()

    /** Each digit said alone: « 1 4 5 » → « un quatre cinq ». */
    fun chiffres(s: String): String = s.filter { it.isDigit() }.map { chiffre(it) }.joinToString(" ")

    /** A whole number in French words (traditional spelling): 71 « soixante et onze », 80 « quatre-vingts ». */
    fun nombre(n: Long): String {
        if (n < 0) return "moins " + nombre(-n)
        if (n < 1000) return moinsDeMille(n.toInt(), true)
        if (n < 1_000_000) {
            val m = (n / 1000).toInt(); val r = (n % 1000).toInt()
            val tete = if (m == 1) "mille" else moinsDeMille(m, false) + " mille"
            return if (r == 0) tete else tete + " " + moinsDeMille(r, true)
        }
        val mi = n / 1_000_000; val r = n % 1_000_000
        val tete = nombre(mi) + (if (mi > 1) " millions" else " million")
        return if (r == 0L) tete else tete + " " + nombre(r)
    }

    fun nombre(n: Int): String = nombre(n.toLong())

    /** 0..999; [fin]: the number ends here (« quatre-vingts », « deux cents » take their s only then). */
    private fun moinsDeMille(n: Int, fin: Boolean): String {
        if (n < 100) return moinsDeCent(n, fin)
        val c = n / 100; val r = n % 100
        val tete = if (c == 1) "cent" else UNITES[c] + " cent" + (if (r == 0 && fin) "s" else "")
        return if (r == 0) tete else tete + " " + moinsDeCent(r, true)
    }

    private fun moinsDeCent(n: Int, fin: Boolean): String {
        if (n < 20) return UNITES[n]
        val d = n / 10; val u = n % 10
        return when {
            d < 7 -> DIZAINES[d] + when (u) { 0 -> ""; 1 -> " et un"; else -> "-" + UNITES[u] }
            d == 7 -> "soixante" + (if (u == 1) " et onze" else "-" + UNITES[10 + u])
            d == 8 -> if (u == 0) "quatre-vingt" + (if (fin) "s" else "") else "quatre-vingt-" + UNITES[u]
            else -> "quatre-vingt-" + UNITES[10 + u]
        }
    }

    /**
     * A callsign spelled: letters by their ICAO word ([phonetique]) or alone,
     * digits by their name, « / » said « barre ». « F4IOZ » → « Fox-trot
     * quatre India Oscar Zoulou ».
     */
    fun indicatif(c: String, phonetique: Boolean = true): String =
        c.trim().uppercase().mapNotNull { x ->
            when {
                x in 'A'..'Z' -> if (phonetique) OACI[x] else x.toString()
                x.isDigit() -> chiffre(x)
                x == '/' -> "barre"
                x == '-' -> "tiret"
                else -> null
            }
        }.joinToString(" ")

    /**
     * A frequency in MHz, digit by digit in groups: the GHz said as a number,
     * the MHz digit by digit, then « virgule » and the decimals without their
     * trailing zeros. 10 489,750 MHz → « dix quatre huit neuf, virgule, sept
     * cinq »; 145,800 → « un quatre cinq, virgule, huit »; 435 → « quatre
     * trois cinq ».
     */
    fun frequence(hz: Long): String {
        val khz = Math.round(hz / 1000.0)
        val mhz = khz / 1000
        val dec = (khz % 1000).toString().padStart(3, '0').trimEnd('0')
        val ent = if (mhz >= 1000) nombre(mhz / 1000) + " " + chiffres((mhz % 1000).toString().padStart(3, '0')) else chiffres(mhz.toString())
        return if (dec.isEmpty()) ent else "$ent, virgule, " + chiffres(dec)
    }

    /** A step in hertz: « cent hertz », « un kilohertz ». */
    fun pasHz(hz: Long): String = when {
        hz >= 1000 && hz % 1000 == 0L -> nombre(hz / 1000) + " kilohertz"
        else -> nombre(hz) + " hertz"
    }

    /** « 1 minute », « 2 minutes », in words. */
    fun minutes(n: Int): String = (if (n == 1) "une" else nombre(n)) + if (n > 1) " minutes" else " minute"

    /** « 45 degrés » in words. */
    fun degres(n: Int): String = nombre(n) + if (n > 1 || n < -1) " degrés" else " degré"

    /**
     * A satellite's name made sayable: « AO-91 » → « AO 91 », « RS-44 » →
     * « RS 44 », its number in words; short capital groups spelled letter by
     * letter (« AO » → « A O »), « ISS » kept whole, as everyone says it.
     */
    fun satellite(nom: String): String = nom.replace('_', ' ').replace('-', ' ').split(' ').filter { it.isNotBlank() }.joinToString(" ") { m ->
        when {
            m.all { it.isDigit() } -> nombre(m.toLong())
            m == "ISS" -> m
            m.length <= 3 && m.all { it in 'A'..'Z' } -> m.toList().joinToString(" ")
            else -> m
        }
    }
}
