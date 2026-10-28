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
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** The SSTV tab's inset (the satellite followed): in the page, wired to the station's state, its pure part right (under node). */
class EncartSstvJsTest {

    @Test
    fun l_encart_est_dans_la_page() {
        val h = PagePc.HTML
        assertTrue(h.contains("<section class=\"carte es\" id=\"es\""))
        // Between the live picture and the sub-tabs, its switch in SSTV › Réglages.
        assertTrue(h.indexOf("class=\"carte sx-direct\"") < h.indexOf("id=\"es\"") && h.indexOf("id=\"es\"") < h.indexOf("data-st=\"sstv\""))
        assertTrue(h.indexOf("id=\"sx-p-reg\"") < h.indexOf("id=\"es-montre\""))
        // Drawn with the station's state and when the tab opens; the Station tab's sky reused, not a second one.
        assertTrue(h.contains("dessineCarteStation();\n  esRend();"))
        assertTrue(h.contains("if (n === 'sstv') { ticMire(true); xsCharge(); esRend(true); }"))
        assertTrue(EncartSstvJs.JS.contains("cielSvg() + esCielPlus(et)"))
        // The flat globe: no WebGL taken from Orbites, the coastline and the track already served.
        assertFalse(EncartSstvJs.JS.contains("Orbites."))
        assertFalse(EncartSstvJs.JS.contains("webgl"))
        assertTrue(EncartSstvJs.JS.contains("fetch('api/carte/traits')"))
        assertTrue(EncartSstvJs.JS.contains("chargeTraceSol()"))
        // Folded and hidden per browser, never failing where storage is refused.
        assertTrue(EncartSstvJs.JS.contains("try { localStorage.setItem('sstv-encart-plie'"))
        assertTrue(EncartSstvJs.JS.contains("try { localStorage.setItem('sstv-encart-cache'"))
        assertTrue(EncartSstvJs.HTML.contains("Aucun satellite suivi"))
        assertFalse("pas de gabarit Kotlin dans le JS", Regex("\\$[{A-Za-z]").containsMatchIn(EncartSstvJs.JS))
        assertFalse("pas de gabarit JS", EncartSstvJs.JS.contains("`"))
        assertTrue(EncartSstvJs.JS.toByteArray().size < 60_000)
    }

    @Test
    fun la_partie_pure_sous_node() {
        val node = node()
        assumeTrue("node absent", node != null)
        val d = File(System.getProperty("java.io.tmpdir"), "satme-encart-" + System.nanoTime()).apply { mkdirs() }
        try {
            val js = File(d, "encart.js").apply { writeText(EncartSstvJs.PUR + "\nmodule.exports = { esOrtho, esCentre, esTca, esEtat, esDescente, esHeures };\n") }
            val essai = File(d, "essai.js").apply { writeText(EncartSstvJsTest::class.java.getResource("/encart-sstv-test.js")!!.readText()) }
            val pb = ProcessBuilder(node, essai.path, js.path).redirectErrorStream(true)
            pb.environment()["TZ"] = "Europe/Paris"
            val p = pb.start()
            val sortie = p.inputStream.bufferedReader().readText()
            assertTrue("node trop long", p.waitFor(60, TimeUnit.SECONDS))
            assertEquals(sortie, 0, p.exitValue())
        } finally { d.deleteRecursively() }
    }

    @Test
    fun le_script_se_lit() {
        val node = node()
        assumeTrue("node absent", node != null)
        val d = File(System.getProperty("java.io.tmpdir"), "satme-encart-" + System.nanoTime()).apply { mkdirs() }
        try {
            val js = File(d, "encart.js").apply { writeText(EncartSstvJs.JS) }
            val p = ProcessBuilder(node, "--check", js.path).redirectErrorStream(true).start()
            val sortie = p.inputStream.bufferedReader().readText()
            assertTrue("node trop long", p.waitFor(60, TimeUnit.SECONDS))
            assertEquals(sortie, 0, p.exitValue())
        } finally { d.deleteRecursively() }
    }

    private fun node(): String? = listOf("/usr/bin/node", "/usr/local/bin/node").firstOrNull { File(it).canExecute() }
        ?: runCatching { ProcessBuilder("which", "node").start().inputStream.bufferedReader().readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
}
