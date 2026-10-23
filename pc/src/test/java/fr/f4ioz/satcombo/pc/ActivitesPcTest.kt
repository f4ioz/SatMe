/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.data.ActivitesEnLigne
import fr.f4ioz.satcombo.data.SatPass
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** « Agenda › Activités à venir » on the PC: the list, its passes, the notices, the campaign's draft (never saved). */
class ActivitesPcTest {

    private val dossier = File(System.getProperty("java.io.tmpdir"), "satme-activites-pc-" + System.nanoTime()).apply { mkdirs() }
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun fin() { portee.cancel(); dossier.deleteRecursively() }

    private val m = 60_000L
    // 2026-10-23 20:00 UTC, during « Series 34 ».
    private val t0 = 1_792_785_600_000L
    private var heure = t0
    private val passe = SatPass("ISS", 25544, t0 + 60 * m, t0 + 70 * m, 50.0, 0.0, 0.0, false, false)
    private val basse = SatPass("ISS", 25544, t0 + 160 * m, t0 + 168 * m, 3.0, 0.0, 0.0, false, false)

    private val ics = """BEGIN:VCALENDAR
BEGIN:VEVENT
DTSTART:20261022T151000Z
DTEND:20261026T174500Z
UID:serie34@google.com
DESCRIPTION:<p>An SSTV event on the ISS is scheduled.</p><p>The ARISS Series 34 images are about ARISS Hardware.<br><br>The transmission
  frequency will be 437.550 MHz FM\, (+/- 10 kHz Doppler shift) using SSTV mode Robot 36\, 36 seconds on with 2 minutes off gap.
  Gallery: <a href="https://ariss-usa.org/ARISS_SSTV/">here</a></p>
SUMMARY:RSØISS - SSTV activity
END:VEVENT
END:VCALENDAR
""".replace("\n", "\r\n")

    private fun monte(): ActivitesPc {
        val st = StationPc(StockageMemoire(), Catalogue(File(dossier, "cache"), enLigne = false), PortsSeriePc(), portee, { heure })
        val faux = ActivitesEnLigne.Telechargeur { url, _, _ ->
            when {
                url.contains("calendar.google.com") -> ActivitesEnLigne.Reponse(200, ics)
                else -> ActivitesEnLigne.Reponse(404, null)
            }
        }
        return ActivitesPc(st, dossier, { heure }, faux, { _, _, _ -> listOf(passe, basse) })
    }

    private fun get(a: ActivitesPc, route: String = "", p: Map<String, String> = emptyMap()) =
        JSONObject(String(a.sert("/api/activites$route", "GET") { p[it].orEmpty() }.corps, Charsets.UTF_8))
    private fun post(a: ActivitesPc, route: String, p: Map<String, String>) =
        JSONObject(String(a.sert("/api/activites$route", "POST") { p[it].orEmpty() }.corps, Charsets.UTF_8))

    @Test fun `la liste, ses passages, les annonces une fois`() {
        val a = monte()
        a.tic()
        val j = get(a)
        val s = j.getJSONArray("activites").getJSONObject(0)
        assertEquals("SSTV", s.getString("type"))
        assertEquals(437_550_000L, s.getLong("freqHz"))
        assertTrue(s.getBoolean("campagne"))
        // The low pass (3°) is not offered.
        assertEquals(1, s.getJSONArray("passages").length())
        val an = j.getJSONArray("annonces")
        assertEquals(1, an.length())
        assertTrue(an.getJSONObject(0).getString("texte").startsWith("Nouvelle activité : ISS SSTV"))
        // Again: nothing new.
        a.tic()
        assertEquals(1, get(a).getJSONArray("annonces").length())
        // Ten minutes before AOS: the pass is said, once.
        heure = passe.aosEpochMs - 10 * m
        a.tic(); a.tic()
        val l = get(a).getJSONArray("annonces")
        assertEquals(2, l.length())
        assertTrue(l.getJSONObject(1).getString("texte"), l.getJSONObject(1).getString("texte").startsWith("ISS dans 10 min (50° max)"))
        // Every source's state: the ones not answering said, quietly.
        assertTrue(get(a).getJSONArray("sources").let { s2 -> (0 until s2.length()).any { s2.getJSONObject(it).getString("erreur") == "HTTP 404" } })
    }

    @Test fun `preparer la campagne donne un brouillon, rien n'est enregistre`() {
        val a = monte()
        a.tic()
        val id = get(a).getJSONArray("activites").getJSONObject(0).getString("id")
        val p = get(a, "/campagne", mapOf("id" to id))
        assertTrue(p.toString(), p.getBoolean("ok"))
        val q = p.getJSONObject("proposition")
        assertEquals(25544, q.getInt("catnum"))
        assertEquals(437_550_000L, q.getLong("freqHz"))
        assertEquals("Robot 36", q.getString("mode"))
        assertEquals("ARISS SSTV Series 34", q.getString("nom"))
        assertEquals("https://ariss-usa.org/ARISS_SSTV/", q.getString("lien"))
        assertFalse(File(dossier, "sstv-campagnes.json").exists())
        assertFalse(get(a, "/campagne", mapOf("id" to "inconnue")).getBoolean("ok"))
    }

    @Test fun `coupe, plus rien n'est demande ni montre`() {
        val a = monte()
        assertTrue(post(a, "/reglage", mapOf("k" to "actif", "on" to "0")).getBoolean("ok"))
        a.tic()
        val j = get(a)
        assertEquals(0, j.getJSONArray("activites").length())
        assertFalse(j.getJSONObject("reglages").getBoolean("actif"))
        assertFalse(post(a, "/reglage", mapOf("k" to "nimporte", "on" to "1")).getBoolean("ok"))
    }
}
