/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import java.text.Normalizer
import java.util.Locale

/**
 * A satellite named in a post (« UMKA-1 », « ERMINAZ-2U devient AO-129 »)
 * found in the station's catalogue (« UmKA 1 (RS40S) », « AO-129 »): the
 * names compared without case, spaces, dashes or leading zeros (« AO-07 » is
 * « AO-7 »), each part of a catalogue name on its own (« ISS (ZARYA) » is
 * also « ZARYA »), a few known other names, then a name with one more
 * letter or digit (« ERMINAZ-2 » → « ERMINAZ-2U »), then a catalogue name
 * said in the title. Only a single best answer is taken; the owner picks
 * among [candidats] otherwise.
 */
object NomsSatellites {

    /** Names the same satellite goes by: its launch name, its OSCAR number, its AMSAT status name. */
    val ALIAS: List<Set<String>> = listOf(
        setOf("UMKA1", "RS40S"),
        setOf("ERMINAZ2U", "AO129"),
        setOf("ISS", "ZARYA", "RS0ISS", "NA1SS"),
    ).map { l -> l.map(::cle).toSet() }

    /** [s] compared: upper case, accents and punctuation gone, leading zeros dropped (« Ø » read as 0). */
    fun cle(s: String): String {
        val t = Normalizer.normalize(s.replace('Ø', '0').replace('ø', '0'), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return t.uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9]+"), " ").trim()
            .replace(Regex("(?<![0-9])0+(?=[0-9])"), "").replace(" ", "")
    }

    /** A name's parts: the whole, what is before the brackets, each name in them (split on « / » and « , »). */
    fun parties(nom: String): Set<String> {
        val l = LinkedHashSet<String>()
        l += cle(nom)
        l += cle(nom.substringBefore('('))
        Regex("\\(([^)]*)\\)").findAll(nom).forEach { m -> m.groupValues[1].split('/', ',').forEach { l += cle(it) } }
        nom.substringBefore('(').split('/').forEach { l += cle(it) }
        return l.filter { it.length >= 2 }.toSet()
    }

    private fun alias(k: String): Set<String> = ALIAS.filter { k in it }.flatten().toSet()

    /** The words of a title as keys, each alone and with the next one (« UmKA 1 » → « UMKA1 »). */
    private fun mots(titre: String): Set<String> {
        val w = titre.split(Regex("[\\s,;:.!?()«»\"'/]+")).map(::cle).filter { it.isNotEmpty() }
        return (w + w.zipWithNext { a, b -> a + b }).toSet()
    }

    /** How surely [catalogue] is the satellite named [nom] (in a post titled [titre]): 0 not at all, 100 by its name. */
    fun score(nom: String, titre: String, catalogue: String): Int {
        val a = if (nom.isBlank()) emptySet() else parties(nom)
        val c = parties(catalogue)
        if (a.any { it in c }) return 100
        if (a.any { k -> alias(k).any { it in c } }) return 90
        // One more letter or digit (ERMINAZ-2 → ERMINAZ-2U), never across a number (AO-12 is not AO-129).
        if (a.any { k -> k.length >= 4 && c.any { x -> x.length > k.length && x.length - k.length <= 1 && x.startsWith(k) && !(k.last().isDigit() && x[k.length].isDigit()) } }) return 70
        if (titre.isNotBlank()) {
            val m = mots(titre)
            if (c.any { it.length >= 4 && it in m }) return 60
            if (c.any { k -> alias(k).any { it.length >= 4 && it in m } }) return 60
        }
        // The same stem (UMKA-2 for UMKA-1): only to put it first in the owner's list.
        val stem = { k: String -> k.takeWhile { it.isLetter() } }
        if (a.any { k -> stem(k).length >= 4 && c.any { stem(it) == stem(k) } }) return 30
        return 0
    }

    /** The catalogue's satellites (name, NORAD number) most likely named [nom] / [titre], best first; [n] at most. */
    fun candidats(nom: String, titre: String, catalogue: List<Pair<String, Int>>, n: Int = 8): List<Pair<String, Int>> =
        catalogue.asSequence().map { it to score(nom, titre, it.first) }.filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<Pair<String, Int>, Int>> { it.second }.thenBy { it.first.first.length })
            .map { it.first }.distinctBy { it.second }.take(n).toList()

    /** The NORAD number of the satellite named [nom] / [titre] when one stands out (60 or more, alone at its best), else 0. */
    fun identifie(nom: String, titre: String, catalogue: List<Pair<String, Int>>): Int {
        val l = catalogue.map { it to score(nom, titre, it.first) }.filter { it.second >= 60 }
        val best = l.maxOfOrNull { it.second } ?: return 0
        val tops = l.filter { it.second == best }.map { it.first.second }.distinct()
        return tops.singleOrNull() ?: 0
    }
}
