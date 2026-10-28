/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.demo.ModulesStation
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.ServerSocket
import java.net.Socket

/**
 * The receivers given as examples in docs/exemples/publication-web, run against SatMe-PC's own sender
 * (skipped where python3 or php is not installed).
 */
class ExemplesPublicationWebTest {

    @get:Rule val tmp = TemporaryFolder()

    private val exemples = File("../docs/exemples/publication-web")
    private var processus: Process? = null

    @After fun arrete() { processus?.destroy() }

    private fun present(cmd: String): Boolean = runCatching { ProcessBuilder(cmd, "--version").redirectErrorStream(true).start().waitFor() == 0 }.getOrDefault(false)

    @Test fun `the Python receiver takes what SatMe-PC sends`() {
        assumeTrue(present("python3"))
        val donnees = tmp.newFolder("recu")
        val port = ServerSocket(0).use { it.localPort }
        processus = ProcessBuilder("python3", "-I", File(exemples, "recevoir.py").absolutePath, "--dossier", donnees.absolutePath, "--port", port.toString())
            .redirectErrorStream(true).redirectOutput(File(tmp.root, "python.log")).apply { environment()["SATME_JETON"] = "le-jeton" }.start()
        val fin = System.currentTimeMillis() + 10_000L
        while (runCatching { Socket("127.0.0.1", port).close() }.isFailure) { assertTrue("le récepteur ne démarre pas", System.currentTimeMillis() < fin); Thread.sleep(100) }

        val st = StockageFichier(tmp.newFile("r.properties"))
        ModulesStation.pose(st, emptySet())
        val s = SitePc(st, tmp.newFolder("pc"), { false })
        s.sources = { g, ref -> SitePc.Contenu(("contenu de " + ref).toByteArray(), null, if (g == SitePc.Genre.QSO) "text/plain" else "image/png",
            JSONObject().put("satellite", "ISS").put("titre", "image $ref").put("utc", "2026-10-03T13:51:19Z").put("indicatif", "EA4ABC")) }
        // A wrong token, then the right one, with {type}.
        val faux = JSONObject(String(s.sert("/api/site/essai", "POST") { k -> when (k) { "url" -> "http://127.0.0.1:$port/satme/{type}"; "jeton" -> "faux"; else -> "" } }.corps))
        assertEquals("jeton refusé par le site", faux.getString("message"))
        val bon = JSONObject(String(s.sert("/api/site/essai", "POST") { k -> if (k == "jeton") "le-jeton" else "" }.corps))
        assertTrue(bon.toString(), bon.getBoolean("ok"))
        s.publie(SitePc.Genre.SSTV, "a.png", true)
        s.publie(SitePc.Genre.QSO, "42", true)
        assertEquals(2, s.envoieDus())
        val index = JSONObject(File(donnees, "index.json").readText()).getJSONArray("elements")
        assertEquals(2, index.length())
        val qso = index.getJSONObject(0)
        assertEquals("qso:42", qso.getString("cle")); assertEquals("EA4ABC", qso.getString("indicatif"))
        val img = index.getJSONObject(1)
        assertEquals("contenu de a.png", File(donnees, img.getString("chemin")).readText())
        assertTrue(img.getString("chemin").startsWith("sstv-image/2026/"))
        // The live state, then the one address for everything (the type in X-SatMe-Type).
        assertEquals(200, s.envoieDirect("direct", JSONObject().put("t", 1L).put("pilote", JSONObject().put("maitre", true)))!!.first)
        assertTrue(JSONObject(File(donnees, "direct/direct.json").readText()).getJSONObject("pilote").getBoolean("maitre"))
        s.sert("/api/site/essai", "POST") { k -> if (k == "url") "http://127.0.0.1:$port/satme" else "" }
        assertTrue(s.json().getBoolean("configure"))
        assertEquals(403, s.envoieDirect("direct-spectre", JSONObject())!!.first)
        assertTrue(s.retire(SitePc.Genre.SSTV, "a.png") == null)
        assertEquals(1, JSONObject(File(donnees, "index.json").readText()).getJSONArray("elements").length())
        assertFalse(File(donnees, img.getString("chemin")).exists())
    }

    @Test fun `the PHP receiver is valid PHP`() {
        assumeTrue(present("php"))
        val p = ProcessBuilder("php", "-l", File(exemples, "recevoir.php").absolutePath).redirectErrorStream(true).start()
        val sortie = p.inputStream.bufferedReader().readText()
        assertEquals(sortie, 0, p.waitFor())
    }
}
