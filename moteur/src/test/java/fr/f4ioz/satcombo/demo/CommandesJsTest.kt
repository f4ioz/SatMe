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
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** « Assistant et domotique » in SatMe-PC's page: its cards, the floating button, its category, its script valid. */
class CommandesJsTest {

    @Test fun les_cartes_et_le_bouton_sont_dans_la_page() {
        val h = PagePc.HTML
        listOf("id=\"asv-carte\"", "id=\"ha-carte\"", "id=\"cmds-carte\"", "id=\"asv-flottant\"", "function cmdCharge()", "id: 'commandes'").forEach {
            assertTrue(it, h.contains(it))
        }
        // The floating button outside the settings, before the script.
        assertTrue(h.indexOf("id=\"asv-flottant\"") < h.indexOf("<script>"))
        // The modules' selectors hide them.
        assertTrue(ModulesStation.module(ModulesStation.ASSISTANT)!!.masques.containsAll(listOf("#asv-carte", "#asv-flottant")))
        assertEquals(listOf("#ha-carte"), ModulesStation.module(ModulesStation.DOMOTIQUE)!!.masques)
        // Every card says it never transmits.
        assertEquals(3, Regex("Jamais d'émission").findAll(CommandesJs.CARTES).count())
        // The broker's password is never put back in its field.
        assertTrue(CommandesJs.CARTES.contains("id=\"ha-mdp\" type=\"password\""))
        assertTrue(!CommandesJs.JS.contains("ha-mdp').value = h."))
    }

    @Test fun le_script_de_la_page_est_valide_sous_node() {
        val node = listOf("/usr/bin/node", "/usr/local/bin/node").firstOrNull { File(it).canExecute() }
        assumeTrue("node absent", node != null)
        val h = PagePc.HTML
        val debut = h.indexOf("<script>") + "<script>".length
        val fin = h.indexOf("</script>", debut)
        val f = File.createTempFile("page-pc", ".js")
        try {
            f.writeText(h.substring(debut, fin))
            val p = ProcessBuilder(node, "--check", f.path).redirectErrorStream(true).start()
            val sortie = p.inputStream.bufferedReader().readText()
            assertTrue("node trop long", p.waitFor(60, TimeUnit.SECONDS))
            assertEquals(sortie, 0, p.exitValue())
        } finally { f.delete() }
    }
}
