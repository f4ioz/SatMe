/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.phonie

import fr.f4ioz.satcombo.phonie.AnalysePhonie.Genre
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysePhonieTest {

    private fun valeurs(t: String, langue: String = "", g: Genre? = null) =
        AnalysePhonie.analyse(t, langue).filter { g == null || it.genre == g }.map { it.valeur }

    @Test fun `alphabet international en anglais`() {
        assertEquals(listOf("SP5ABC"), valeurs("This is Sierra Papa five Alfa Bravo Charlie, over.", "en", Genre.INDICATIF))
        assertEquals(listOf("JN18"), valeurs("My locator is Juliett November one eight.", "en", Genre.CARRE))
        assertEquals(listOf("59"), valeurs("You are five by nine here.", "en", Genre.REPORT))
    }

    @Test fun `en francais avec les variantes`() {
        val t = "CQ CQ ici Fox quatre Italie Oscar Zoulou, carré Juliette Novembre un huit, rapport cinq neuf"
        val l = AnalysePhonie.analyse(t, "fr")
        assertEquals(listOf("F4IOZ"), l.filter { it.genre == Genre.INDICATIF }.map { it.valeur })
        assertEquals(listOf("JN18"), l.filter { it.genre == Genre.CARRE }.map { it.valeur })
        assertEquals(listOf("59"), l.filter { it.genre == Genre.REPORT }.map { it.valeur })
        // Where it is in the text, for the highlight.
        val f = l.first { it.genre == Genre.INDICATIF }
        assertEquals("Fox quatre Italie Oscar Zoulou", t.substring(f.debut, f.fin))
        // After "ici": surer.
        assertTrue(f.score >= 2)
    }

    @Test fun `ecrit tel quel`() {
        assertEquals(listOf("F4IOZ", "JN18", "59"), valeurs("F4IOZ JN18 59"))
        assertEquals(listOf("EA5/F4IOZ"), valeurs("de EA5/F4IOZ", g = Genre.INDICATIF))
        assertEquals(listOf("SP5ABC"), valeurs("SP 5 ABC", g = Genre.INDICATIF))
        assertEquals(listOf("SP5ABC"), valeurs("S P 5 A B C", g = Genre.INDICATIF))
        assertEquals(listOf("F4IOZ/P"), valeurs("Fox four India Oscar Zulu portable", "en", Genre.INDICATIF))
    }

    @Test fun `indicatif suivi du carre epele d'un trait`() {
        assertEquals(listOf("F4IOZ", "JN18"), valeurs("Foxtrot four India Oscar Zulu Juliett November one eight", "en"))
    }

    @Test fun `allemand espagnol italien polonais`() {
        assertEquals(listOf("DL1ABC"), valeurs("Hier ist Delta Lima eins Anton Berta Cäsar", "de", Genre.INDICATIF))
        assertEquals(listOf("EA4XY"), valeurs("Aquí Echo Alfa cuatro X-ray Yankee", "es", Genre.INDICATIF))
        assertEquals(listOf("IK2ABC"), valeurs("Qui India Kilo due Ancona Bologna Como", "it", Genre.INDICATIF))
        assertEquals(listOf("SP5ABC"), valeurs("Tutaj Sierra Papa pięć Adam Barbara Celina", "pl", Genre.INDICATIF))
        assertEquals(listOf("59"), valeurs("raport pięć dziewięć", "pl", Genre.REPORT))
        assertEquals(listOf("59"), valeurs("Rapporto cinque nove", "it", Genre.REPORT))
    }

    @Test fun `otto selon la langue`() {
        // Italian: eight; German: O.
        assertEquals(listOf("I8ABC"), valeurs("India otto Alfa Bravo Charlie", "it", Genre.INDICATIF))
        assertEquals(listOf("DO1AB"), valeurs("Dora Otto eins Anton Berta", "de", Genre.INDICATIF))
    }

    @Test fun `pas d'indicatif dans une conversation ordinaire`() {
        assertTrue(valeurs("Bonjour Adam, merci pour le contact, à la prochaine fois sur le satellite", "fr").isEmpty())
        assertTrue(valeurs("I have two antennas and one radio at home", "en").isEmpty())
        assertTrue(valeurs("Dzień dobry, mam na imię Adam i mieszkam w Warszawie", "pl").isEmpty())
        // Four five minutes: no report without "report" or a usual one.
        assertTrue(valeurs("dans quatre cinq minutes", "fr", Genre.REPORT).isEmpty())
        // A country's prefix needed.
        assertTrue(valeurs("QO 100 QSO", g = Genre.INDICATIF).isEmpty())
    }

    @Test fun `connu et repete`() {
        val l = AnalysePhonie.analyse("F4IOZ de SP5ABC, SP5ABC", "en", connus = setOf("F4IOZ"))
        val f = l.first { it.valeur == "F4IOZ" }
        assertTrue(f.connu)
        val s = l.filter { it.valeur == "SP5ABC" }
        assertEquals(2, s.size)
        assertTrue(s[0].score >= 3)
        assertEquals(listOf("SP5ABC", "F4IOZ"), AnalysePhonie.indicatifs(l).map { it.valeur })
    }
}
