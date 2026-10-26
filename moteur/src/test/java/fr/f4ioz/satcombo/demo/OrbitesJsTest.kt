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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** The Orbites tab and the Suivi globe: in the page, their libraries bundled, their pure part right (under node). */
class OrbitesJsTest {

    @Test
    fun la_page_porte_l_onglet_et_le_globe_du_suivi() {
        assertTrue(PagePc.TOUS.contains("orbites"))
        assertTrue(PagePc.HTML.contains("id=\"v-orbites\""))
        assertTrue(PagePc.HTML.contains("id=\"st-globe\""))
        assertTrue(PagePc.HTML.contains("const Orbites = (function ()"))
        assertFalse("pas de gabarit Kotlin dans le JS", OrbitesJs.JS.contains("$"))
        assertTrue("l'icône de l'onglet", IconesPc.svg("orbit").isNotEmpty())
    }

    @Test
    fun les_bibliotheques_sont_dans_le_jar() {
        val three = PagePc.bibliotheque("three.js")
        assertNotNull(three)
        assertTrue(String(three!!, 0, 200).contains("SPDX-License-Identifier: MIT"))
        assertTrue(String(PagePc.bibliotheque("satellite.js")!!, 0, 200).contains("License: MIT"))
        assertNull(PagePc.bibliotheque("../reglages.properties"))
    }

    @Test
    fun la_partie_pure_sous_node() {
        val node = listOf("/usr/bin/node", "/usr/local/bin/node").firstOrNull { File(it).canExecute() }
            ?: runCatching { ProcessBuilder("which", "node").start().inputStream.bufferedReader().readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
        assumeTrue("node absent", node != null)
        val d = File(System.getProperty("java.io.tmpdir"), "satme-orbites-" + System.nanoTime()).apply { mkdirs() }
        try {
            // The pure functions only: the globes' module needs a page.
            val pur = OrbitesJs.JS.substringBefore("/* ------------------------------------------------------------ the globes */")
            val js = File(d, "orbites.js").apply { writeText(pur + "\nmodule.exports = { obEmpreinte, obNuit, obPassages, obSoleil, obLocator, obPisteA, obGmst, obLune, obLunePath, obHauteurSoleil, obSoleilJour };\n") }
            val essai = File(d, "essai.js").apply { writeText(OrbitesJsTest::class.java.getResource("/orbites-test.js")!!.readText()) }
            val p = ProcessBuilder(node, essai.path, js.path).redirectErrorStream(true).start()
            val sortie = p.inputStream.bufferedReader().readText()
            assertTrue("node trop long", p.waitFor(60, TimeUnit.SECONDS))
            assertEquals(sortie, 0, p.exitValue())
        } finally { d.deleteRecursively() }
    }
}
