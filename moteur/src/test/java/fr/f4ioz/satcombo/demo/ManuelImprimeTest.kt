/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ManuelImprimeTest {

    /** The repository's docs/ (the tests run in moteur/). */
    private val docs = listOf(File("../docs"), File("docs")).first { File(it, "station-pc.fr.md").isFile }

    @Test
    fun les_images_du_guide_deviennent_des_figures() {
        val src = "# T\n\nintro\n\n<p align=\"center\"><img src=\"images/a.webp\" width=\"70%\" alt=\"L'onglet « A »\"></p>\n\n## Un\n\ntexte\n\n" +
            "<p align=\"center\">\n<img src=\"images/b.webp\" width=\"49%\" alt=\"B\">\n<img src=\"images/c.png\" width=\"49%\" alt=\"C &amp; D\">\n</p>\n\nfin"
        val (md, figs) = ManuelImprime.figures(src)
        assertEquals(listOf(listOf(ManuelImprime.Image("images/a.webp", 70, "L'onglet « A »")),
            listOf(ManuelImprime.Image("images/b.webp", 49, "B"), ManuelImprime.Image("images/c.png", 49, "C & D"))), figs)
        assertTrue(md.contains("@@FIG0@@") && md.contains("@@FIG1@@") && !md.contains("<img"))
        val h = ManuelImprime.html(src, "fr", "2026.10.34", "2026-10-29")
        assertTrue(h.contains("<img src=\"images/b.webp\" alt=\"B\">") && h.contains("<figcaption>C &amp; D</figcaption>"))
        assertFalse(h.contains("@@FIG"))
        assertTrue(h.contains("Version 2026.10.34 · 29 octobre 2026"))
        assertTrue(ManuelImprime.html(src, "en", "dev", "2026-10-29").contains("development build · 29 October 2026"))
    }

    @Test
    fun les_numeros_de_page_vont_dans_le_sommaire() {
        val src = "# SatMe-PC — la station\n\n## Sommaire\n\n1. [Un](#un)\n\n## Un\n\n### Deux\n\nx"
        val sans = ManuelImprime.html(src, "fr", "1", "2026-10-29")
        assertTrue(sans.contains("<a href=\"#un\"><span class=\"n\">1</span><span class=\"t\">Un</span><span class=\"p\"></span></a>"))
        val avec = ManuelImprime.html(src, "fr", "1", "2026-10-29", mapOf("un" to 4, "deux" to 5))
        assertTrue(avec.contains("<span class=\"t\">Un</span><span class=\"p\">4</span>"))
        assertTrue(avec.contains("<span class=\"t\">Deux</span><span class=\"p\">5</span>"))
        // The guide's own contents are not a chapter; each chapter its running header.
        assertFalse(avec.contains("id=\"sommaire\""))
        assertTrue(avec.contains("@page c1 { @top-right { content: \"1. Un\"; } }"))
        assertTrue(avec.contains("<h2 id=\"un\"><span class=\"num\">1</span>Un</h2>"))
    }

    /** Every `##` title of [src] but the table of contents. */
    private fun titres(src: String): List<String> {
        var code = false
        return src.lines().filter { l -> if (l.trimStart().startsWith("```")) code = !code; !code && l.startsWith("## ") }
            .map { Markdown.ancre(it.removePrefix("## ").trim()) }.filter { it != "sommaire" && it != "contents" }
    }

    @Test
    fun les_deux_guides_donnent_tous_leurs_chapitres() {
        val tailles = mutableListOf<Pair<Int, Int>>()
        for ((langue, nom) in listOf("fr" to "station-pc.fr.md", "en" to "station-pc.md")) {
            val src = File(docs, nom).readText()
            val g = ManuelImprime.guide(src)
            val h = ManuelImprime.html(src, langue, "2026.10.34", "2026-10-29")
            val chapitres = titres(src)
            assertEquals("$nom : les chapitres", chapitres, g.chapitres.map { it.id })
            assertTrue("$nom : plus de vingt chapitres", chapitres.size > 20)
            val ids = Regex("\\bid=\"([^\"]+)\"").findAll(h).map { it.groupValues[1] }.toSet()
            for (a in ManuelImprime.ancres(g)) {
                assertTrue("$nom : le titre #$a", a in ids)
                assertTrue("$nom : #$a au sommaire", h.contains("<a href=\"#$a\">"))
            }
            // Every internal link leads somewhere; every picture is there; no marker left.
            Regex("href=\"#([^\"]+)\"").findAll(h).forEach { assertTrue("$nom : lien #${it.groupValues[1]}", it.groupValues[1] in ids) }
            ManuelImprime.images(src).forEach { assertTrue("$nom : $it", File(docs, it).isFile) }
            assertTrue(ManuelImprime.images(src).size > 20)
            assertFalse(h.contains("@@FIG"))
            assertTrue(h.contains("<section class=\"couv\">") && h.contains("<section class=\"toc\">") && h.contains("<section class=\"intro\">"))
            assertEquals(chapitres.size, Regex("<section class=\"ch c\\d+\"").findAll(h).count())
            tailles.add(g.chapitres.size to g.chapitres.sumOf { it.sections.size })
        }
        // The French and English guides kept in step: the same chapters and sections.
        assertEquals(tailles[0], tailles[1])
    }

    @Test
    fun la_date_en_toutes_lettres() {
        assertEquals("1 mars 2026", ManuelImprime.date("2026-03-01", "fr"))
        assertEquals("29 October 2026", ManuelImprime.date("2026-10-29", "en"))
        assertEquals("demain", ManuelImprime.date("demain", "fr"))
    }
}
