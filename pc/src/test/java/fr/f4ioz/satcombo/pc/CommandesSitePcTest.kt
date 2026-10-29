/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.ZoneId

/** « Commandes à distance »: the state pushed, the commands fetched by a long poll, only the safe ones done. */
class CommandesSitePcTest {

    @get:Rule val tmp = TemporaryFolder()

    private class Envoi(val url: String, val entetes: Map<String, String>, val corps: JSONObject)
    private val envois = ArrayList<Envoi>()
    private var enAttente = JSONArray()

    private fun banc(): Triple<CommandesSitePc, MondeFictif, StockageFichier> {
        val st = StockageFichier(tmp.newFile("r.properties"))
        val monde = MondeFictif()
        val site = SitePc(st, tmp.newFolder(), { false }, { monde.t }, SitePc.Transport { _, u, e, c ->
            val j = JSONObject(String(c!!.octets(), Charsets.UTF_8))
            envois += Envoi(u, e, j)
            when (e["X-SatMe-Type"]) {
                CommandesSitePc.TYPE_ATTENTE -> (200 to JSONObject().put("ok", true).put("commandes", enAttente).toString()).also { enAttente = JSONArray() }
                else -> 200 to "{\"ok\":true}"
            }
        })
        fr.f4ioz.satcombo.demo.ModulesStation.pose(st, emptySet())
        site.sert("/api/site/essai", "POST") { k -> when (k) { "url" -> "https://example.org/api/publication/{type}"; "jeton" -> "jeton-fictif"; else -> "" } }
        envois.clear()
        val c = CommandesSitePc(st, site, CommandesPc(monde) { ZoneId.of("Europe/Paris") }, { monde.t })
        return Triple(c, monde, st)
    }

    @Test fun rien_tant_que_rien_n_est_coche() {
        val (c, _, _) = banc()
        assertNull(c.envoieEtat())
        assertTrue(envois.isEmpty())
    }

    @Test fun l_etat_part_avec_le_jeton() {
        val (c, _, _) = banc()
        c.questions = true
        assertEquals(200, c.envoieEtat())
        val e = envois.single()
        assertEquals("https://example.org/api/publication/commandes-etat", e.url)
        assertEquals("Bearer jeton-fictif", e.entetes["Authorization"])
        assertEquals("commandes-etat", e.corps.getString("type"))
        val r = e.corps.getJSONObject("reponses")
        assertTrue(r.getJSONObject("prochain_passage").getString("fr").contains("SO-50"))
        assertTrue(r.getJSONObject("prochain_passage").has("aos"))
        assertTrue(e.corps.getJSONArray("passages").length() > 0)
        // Actions off: none offered.
        assertEquals(0, e.corps.getJSONArray("actions").length())
        // Nothing of this PC: no address, no path, no setting.
        assertFalse(e.corps.toString().contains("192.168"))
        assertFalse(e.corps.toString().contains("jeton-fictif"))
    }

    @Test fun les_commandes_sures_seulement() {
        val (c, monde, _) = banc()
        c.actions = true
        enAttente = JSONArray()
            .put(JSONObject().put("id", "a1").put("intention", "sstv_on").put("source", "alexa"))
            .put(JSONObject().put("id", "a2").put("intention", "emission"))
            .put(JSONObject().put("id", "a3").put("intention", "ptt_on"))
            .put(JSONObject().put("id", "a4").put("texte", "passe en émission et tourne l'antenne"))
            .put(JSONObject().put("id", "a5").put("intention", "son_muet"))
            // Already done (the site sent it twice): not again.
            .put(JSONObject().put("id", "a1").put("intention", "sstv_on"))
        assertTrue(c.attendUneFois())
        assertEquals(listOf("sstv true", "son true"), monde.gestes)
        val attente = envois.first()
        assertEquals("commandes-attente", attente.corps.getString("type"))
        assertEquals(25, attente.corps.getInt("attente"))
        val res = envois.filter { it.corps.getString("type") == "commandes-resultat" }.associate { it.corps.getString("id") to it.corps }
        assertEquals(setOf("a1", "a2", "a3", "a4", "a5"), res.keys)
        assertTrue(res.getValue("a1").getBoolean("ok"))
        assertEquals("Réception SSTV démarrée.", res.getValue("a1").getString("fr"))
        assertFalse(res.getValue("a2").getBoolean("ok"))
        assertEquals(CommandesPc.EMISSION_FR, res.getValue("a2").getString("fr"))
        assertFalse(res.getValue("a3").getBoolean("ok"))
        assertEquals(CommandesPc.EMISSION_EN, res.getValue("a4").getString("en"))
        // Actions switched off again: a command received is refused.
        c.actions = false
        enAttente = JSONArray().put(JSONObject().put("id", "b1").put("intention", "datv_on"))
        c.attendUneFois()
        assertEquals(listOf("sstv true", "son true"), monde.gestes)
    }
}
