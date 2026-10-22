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

/** The map labels' layout: grouping, no overlap with many contacts close in time and place (under node). */
class EtiquettesCarteJsTest {

    @Test
    fun la_page_porte_le_placement() {
        assertTrue(PagePc.HTML.contains("function placeEtiquettes("))
        assertTrue(PagePc.HTML.contains("function groupesCarte("))
        assertFalse("pas de gabarit Kotlin dans le JS", EtiquettesCarteJs.JS.contains("$"))
    }

    @Test
    fun le_placement_sous_node() {
        val node = listOf("/usr/bin/node", "/usr/local/bin/node").firstOrNull { File(it).canExecute() }
            ?: runCatching { ProcessBuilder("which", "node").start().inputStream.bufferedReader().readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
        assumeTrue("node absent", node != null)
        val d = File(System.getProperty("java.io.tmpdir"), "satme-etiquettes-" + System.nanoTime()).apply { mkdirs() }
        try {
            val js = File(d, "etiquettes.js").apply { writeText(EtiquettesCarteJs.JS + "\nmodule.exports = { groupesCarte, placeEtiquettes, colonnesEtiquette };\n") }
            val essai = File(d, "essai.js").apply { writeText(EtiquettesCarteJsTest::class.java.getResource("/etiquettes-carte-test.js")!!.readText()) }
            val p = ProcessBuilder(node, essai.path, js.path).redirectErrorStream(true).start()
            val sortie = p.inputStream.bufferedReader().readText()
            assertTrue("node trop long", p.waitFor(60, TimeUnit.SECONDS))
            assertEquals(sortie, 0, p.exitValue())
        } finally { d.deleteRecursively() }
    }
}
