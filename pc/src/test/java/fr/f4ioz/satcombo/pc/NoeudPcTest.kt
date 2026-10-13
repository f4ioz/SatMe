/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.plateforme.StockageMemoire
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** SatMe-node: what it says to anyone, its code, its key, and what it refuses without one. */
class NoeudPcTest {

    private val stockage = StockageMemoire()
    private val datv = DatvPc(stockage, File(System.getProperty("java.io.tmpdir")), lecteursLocaux = false)
    private val noeud = NoeudPc(datv, stockage, 0, nom = "essai").also { it.demarre() }
    @After fun fin() { noeud.arrete() }

    private fun lit(chemin: String, post: Boolean = false): Pair<Int, String> {
        val c = URL("http://127.0.0.1:${noeud.portReel}$chemin").openConnection() as HttpURLConnection
        if (post) { c.requestMethod = "POST"; c.doOutput = true; c.outputStream.close() }
        val code = c.responseCode
        return code to (if (code < 400) c.inputStream else c.errorStream)?.readBytes()?.toString(Charsets.UTF_8).orEmpty()
    }

    @Test
    fun un_code_puis_une_cle_gardee() {
        val api = JSONObject(lit("/noeud/api").second)
        assertTrue(api.getBoolean("noeud")); assertEquals("essai", api.getString("nom"))
        assertEquals(403, lit("/noeud/datv").first)
        assertEquals(403, lit("/noeud/datv?cle=nimporte").first)
        assertFalse(JSONObject(lit("/noeud/entrer?code=000000x").second).getBoolean("ok"))
        val cle = JSONObject(lit("/noeud/entrer?code=" + noeud.code).second).getString("cle")
        assertEquals(200, lit("/noeud/datv?cle=$cle").first)
        // A start asked by GET is refused: what changes the node is a POST.
        assertEquals(405, lit("/noeud/datv/demarre?cle=$cle").first)
        // The same code and key after a restart (they are kept).
        val apres = NoeudPc(datv, stockage, 0)
        assertEquals(noeud.code, apres.code)
        assertTrue(noeud.code.matches(Regex("\\d{6}")))
        // The page says what the node is, never its code.
        assertFalse(lit("/").second.contains(noeud.code))
    }

    @Test
    fun hors_de_portee_le_node_le_dit() {
        val cle = JSONObject(lit("/noeud/entrer?code=" + noeud.code).second).getString("cle")
        val r = JSONObject(lit("/noeud/datv/demarre?cle=$cle&ciel=10491.5&ol=12000", post = true).second)
        assertFalse(r.getBoolean("ok")); assertTrue(r.getString("erreur").contains("hors de portée"))
    }
}
