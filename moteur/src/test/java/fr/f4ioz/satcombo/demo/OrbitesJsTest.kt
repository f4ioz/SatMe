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
    fun le_sked_se_rejoue_aussi_sur_le_globe() {
        // The Sked tab: a map / globe switch, the globe's settings (the Orbites tab's), its canvas beside the map.
        assertTrue(PagePc.HTML.contains("id=\"k-globe\""))
        assertTrue(PagePc.HTML.contains("onclick=\"vueSked('globe')\""))
        assertTrue(PagePc.HTML.contains("id=\"k-reglages\""))
        assertTrue(PagePc.HTML.contains("Orbites.sked(g('k-globe'), donneesGlobeSked())"))
        assertTrue(PagePc.HTML.contains("localStorage.setItem('sked-vue', q)"))
        // The globes' module: a 'sked' scene, driven by the page's clock, both stations.
        assertTrue(OrbitesJs.JS.contains("sked: async function (canvas, d)"))
        assertTrue(OrbitesJs.JS.contains("sc.mode === 'sked' ? sc.sk.donnees.horloge()"))
        assertTrue(OrbitesJs.JS.contains("sc.skedPrepare = () =>"))
    }

    @Test
    fun les_traces_plus_ou_moins_marquees() {
        // « Traces »: the tab's slider, the Sked's, one key; ribbons on the globe (a WebGL line is 1 px), the maps' strokes.
        assertTrue(PagePc.HTML.contains("id=\"ob-traces\" class=\"ob-traces-choix\""))
        assertTrue(PagePc.HTML.contains("box.appendChild(rangeTraces())"))
        assertTrue(PagePc.HTML.contains("Orbites.tracesActives() / 100"))
        assertTrue(OrbitesJs.JS.contains("lit('ob-traces', '60')"))
        assertTrue(OrbitesJs.JS.contains("o.sol = ruban(sc)"))
        assertTrue(OrbitesJs.JS.contains("o.orbite = ruban(sc, .6, true)"))
        assertTrue(OrbitesJs.JS.contains("#include <logdepthbuf_vertex>"))
        assertTrue(OrbitesJs.JS.contains("traces: n => prendTraces(n)"))
    }

    @Test
    fun la_terre_vue_du_satellite() {
        // A fourth camera: the tab's button, the Sked's choice, the Vitrine's turn; the globe on the satellite's vertical.
        assertTrue(PagePc.HTML.contains("data-ob-cam=\"satellite\""))
        assertTrue(PagePc.HTML.contains("['satellite', 'vue du satellite']"))
        assertTrue(PagePc.HTML.contains("vitrine: !!T.vitrine"))
        assertTrue(OrbitesJs.JS.contains("(passage && OB.cam === 'satellite') || vitSat"))
        assertTrue(OrbitesJs.JS.contains("obReculSat(r, a * .9, Math.min(1, k))"))
        assertTrue(OrbitesJs.JS.contains("function vueSatInfo("))
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
            val js = File(d, "orbites.js").apply { writeText(pur + "\nmodule.exports = { obEmpreinte, obNuit, obPassages, obSoleil, obLocator, obPisteA, obGmst, obLune, obLunePath, obHauteurSoleil, obSoleilJour, obVueSat, obReculSat };\n") }
            val essai = File(d, "essai.js").apply { writeText(OrbitesJsTest::class.java.getResource("/orbites-test.js")!!.readText()) }
            val p = ProcessBuilder(node, essai.path, js.path).redirectErrorStream(true).start()
            val sortie = p.inputStream.bufferedReader().readText()
            assertTrue("node trop long", p.waitFor(60, TimeUnit.SECONDS))
            assertEquals(sortie, 0, p.exitValue())
        } finally { d.deleteRecursively() }
    }
}
