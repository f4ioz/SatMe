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

/** « En direct de l'ISS » in the Orbites tab: in the page, wired to the globe, its links worked out right (under node). */
class IssDirectJsTest {

    @Test
    fun la_fenetre_est_dans_la_page() {
        assertTrue(PagePc.HTML.contains("const IssDirect = (function ()"))
        assertTrue(PagePc.HTML.contains(".iss-f.iss-tuile"))
        assertTrue(PagePc.HTML.contains("IssDirect.menu(titre, coche)"))
        // The default source, NASA's public stream (found by SatMe-PC, else the channel's live), from the no-cookie player, muted; the credit.
        assertTrue(IssDirectJs.JS.contains("const ISS_CHAINE = 'UCLA_DiR1FfKNvjuUpBHmylQ'"))
        assertTrue(IssDirectJs.JS.contains("'api/iss-direct/source'"))
        assertTrue(IssDirectJs.JS.contains("https://www.youtube-nocookie.com/embed/"))
        assertTrue(IssDirectJs.JS.contains("autoplay=1&mute=1&playsinline=1"))
        assertTrue(IssDirectJs.JS.contains("Images : NASA (domaine public), via YouTube"))
        // The globe's side: where the ISS is, chosen and seen from above.
        assertTrue(OrbitesJs.JS.contains("sousSat: n =>"))
        assertTrue(OrbitesJs.JS.contains("choisir: n =>"))
        assertTrue(IssDirectJs.JS.contains("Orbites.camera('satellite')"))
        assertFalse("pas de gabarit Kotlin dans le JS", Regex("\\$[{A-Za-z]").containsMatchIn(IssDirectJs.JS))
    }

    @Test
    fun les_liens_sous_node() {
        val node = listOf("/usr/bin/node", "/usr/local/bin/node").firstOrNull { File(it).canExecute() }
            ?: runCatching { ProcessBuilder("which", "node").start().inputStream.bufferedReader().readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
        assumeTrue("node absent", node != null)
        val d = File(System.getProperty("java.io.tmpdir"), "satme-iss-" + System.nanoTime()).apply { mkdirs() }
        try {
            val js = File(d, "iss.js").apply { writeText(IssDirectJs.PUR + "\nmodule.exports = { issSource, issMer, issKm, issAutos, ISS_CHAINE, ISS_DERNIER };\n") }
            val essai = File(d, "essai.js").apply { writeText(IssDirectJsTest::class.java.getResource("/iss-direct-test.js")!!.readText()) }
            val p = ProcessBuilder(node, essai.path, js.path).redirectErrorStream(true).start()
            val sortie = p.inputStream.bufferedReader().readText()
            assertTrue("node trop long", p.waitFor(60, TimeUnit.SECONDS))
            assertEquals(sortie, 0, p.exitValue())
        } finally { d.deleteRecursively() }
    }
}
