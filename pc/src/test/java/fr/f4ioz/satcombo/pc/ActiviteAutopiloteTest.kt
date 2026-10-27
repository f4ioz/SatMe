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
import fr.f4ioz.satcombo.domain.ActivitesAVenir
import fr.f4ioz.satcombo.domain.CampagneSstv
import fr.f4ioz.satcombo.domain.PiloteSstv
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** « Activer l'auto-pilote » on an activity: the campaign made, kept, switched on, once; its period following the activity. */
class ActiviteAutopiloteTest {

    private val dossier = File(System.getProperty("java.io.tmpdir"), "satme-activite-ap-" + System.nanoTime()).apply { mkdirs() }
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun fin() { portee.cancel(); dossier.deleteRecursively() }

    private val m = 60_000L
    // 2026-10-23 20:00 UTC, during « Series 34 ».
    private val t0 = 1_792_785_600_000L
    private var heure = t0
    private val passe = SatPass("ISS", 25544, t0 + 60 * m, t0 + 70 * m, 50.0, 0.0, 0.0, false, false)
    private var fin = "20261026T174500Z"

    private fun ics() = """BEGIN:VCALENDAR
BEGIN:VEVENT
DTSTART:20261022T151000Z
DTEND:$fin
UID:serie34@google.com
DESCRIPTION:<p>An SSTV event on the ISS is scheduled.</p><p>The transmission frequency will be 437.550 MHz FM\, using SSTV mode Robot 36.</p>
SUMMARY:RSØISS - SSTV activity
END:VEVENT
END:VCALENDAR
""".replace("\n", "\r\n")

    private class Fausse : PiloteSstv.Station {
        override fun emission(): String? = null
        override fun conflit(c: CampagneSstv.Campagne): String? = null
        override fun capture() = emptyMap<String, String>()
        override fun prepare(c: CampagneSstv.Campagne, p: SatPass) = PiloteSstv.Preparation(emptyList())
        override fun enregistre(c: CampagneSstv.Campagne): String? = null
        override fun arreteEnregistrement(): String = ""
        override fun images(deMs: Long, aMs: Long) = emptyList<String>()
        override fun parque(): String? = null
        override fun restaure(avant: Map<String, String>): List<String> = emptyList()
        override fun annonce(texte: String) {}
    }

    private val catalogue = listOf("ISS (ZARYA)" to 25544, "UmKA 1 (RS40S)" to 57172)
    private val umka = ActivitesAVenir.Activite(id = "rss:umka", type = ActivitesAVenir.Type.SSTV, satNom = "UMKA-1", catnum = 0,
        debutMs = t0 + 86_400_000L, finMs = t0 + 3 * 86_400_000L, freqHz = 437_625_000L, titre = "UMKA-1 : Emission SSTV du 27 au 29 octobre 2026",
        sources = listOf(ActivitesAVenir.Source("AMSAT-F")))
    private val inconnu = ActivitesAVenir.Activite(id = "rss:x", type = ActivitesAVenir.Type.SSTV, satNom = "DEMOSAT-2", catnum = 0,
        debutMs = t0, finMs = t0 + 7 * 86_400_000L, precision = ActivitesAVenir.Precision.INCONNUE, freqHz = 145_800_000L,
        titre = "DEMOSAT-2 en SSTV", sources = listOf(ActivitesAVenir.Source("AMSAT-UK")))

    private fun monte(minEl: Int? = null, rotorMinEl: Int? = null): Pair<ActivitesPc, AutoPiloteSstvPc> {
        val st = StationPc(StockageMemoire(), Catalogue(File(dossier, "cache"), enLigne = false), PortsSeriePc(), portee, { heure })
        if (minEl != null) st.settings.minElevDeg = minEl
        if (rotorMinEl != null) st.settings.rotorMinEl = rotorMinEl
        val faux = ActivitesEnLigne.Telechargeur { url, _, _ ->
            if (url.contains("calendar.google.com")) ActivitesEnLigne.Reponse(200, ics()) else ActivitesEnLigne.Reponse(404, null)
        }
        val pas = { _: Int, _: Long, _: Long -> listOf(passe) }
        val ap = AutoPiloteSstvPc(st, dossier, { heure }, pas, stationPilote = Fausse())
        val a = ActivitesPc(st, dossier, { heure }, faux, pas, fictives = { listOf(umka, inconnu) }, catalogue = { catalogue })
        a.autopilote = ap
        return a to ap
    }

    private fun get(a: ActivitesPc, route: String = "", p: Map<String, String> = emptyMap()) =
        JSONObject(String(a.sert("/api/activites$route", "GET") { p[it].orEmpty() }.corps, Charsets.UTF_8))
    private fun post(a: ActivitesPc, route: String, p: Map<String, String>) =
        JSONObject(String(a.sert("/api/activites$route", "POST") { p[it].orEmpty() }.corps, Charsets.UTF_8))
    private fun activite(a: ActivitesPc, id: String) = get(a).getJSONArray("activites").let { l -> (0 until l.length()).map { l.getJSONObject(it) }.first { it.getString("id") == id } }

    @Test fun `un clic fait la campagne, l'active, allume l'interrupteur, une seule fois`() {
        val (a, ap) = monte()
        a.tic()
        val id = "ariss:serie34@google.com"
        val s = activite(a, id)
        assertTrue(s.getBoolean("autopilote"))
        assertTrue(s.isNull("lienAp"))
        // What would be done, shown first: nothing kept yet.
        val v = get(a, "/autopilote", mapOf("id" to id))
        assertTrue(v.toString(), v.getBoolean("ok"))
        assertEquals(25544, v.getInt("catnum"))
        assertFalse(v.getBoolean("maitre"))
        assertEquals(437_550_000L, v.getJSONObject("campagne").getLong("freqHz"))
        assertEquals(1, v.getJSONArray("passages").length())
        assertTrue(ap.campagnes.isEmpty())

        val r = post(a, "/autopilote", mapOf("id" to id))
        assertTrue(r.toString(), r.getBoolean("ok"))
        assertTrue(r.getBoolean("nouvelle"))
        assertTrue(r.getBoolean("maitreAllume"))
        assertTrue(ap.maitre)
        val c = ap.campagnes.single()
        assertEquals(id, c.activite)
        assertTrue(c.active && c.activiteSuit)
        assertEquals(25544, c.catnum)
        assertEquals("Robot 36", c.modeSstv)
        assertEquals(437_550_000L, c.freqHz)
        assertEquals(CampagneSstv.Chaine(), c.chaine)
        assertEquals(1_792_681_800_000L, c.debutMs)
        // The card: active, its next pass.
        val l = activite(a, id).getJSONObject("lienAp")
        assertTrue(l.getBoolean("active") && l.getBoolean("maitre"))
        assertEquals(passe.aosEpochMs, l.getLong("prochain"))

        // Again: the same campaign, the switch left as it is.
        val r2 = post(a, "/autopilote", mapOf("id" to id))
        assertFalse(r2.getBoolean("nouvelle")); assertFalse(r2.getBoolean("maitreAllume"))
        assertEquals(1, ap.campagnes.size)
        // Suspended, then again: switched on again, still one.
        ap.active(c.id, false)
        assertFalse(activite(a, id).getJSONObject("lienAp").getBoolean("active"))
        post(a, "/autopilote", mapOf("id" to id))
        assertEquals(1, ap.campagnes.size)
        assertTrue(ap.campagnes.single().active)
        // Kept on disk with its link (read back by a new start).
        assertEquals(id, CampagneSstv.lit(File(dossier, "sstv-campagnes.json").readText()).campagnes.single().activite)
    }

    @Test fun `la periode suit l'activite tant qu'elle n'est pas changee a la main`() {
        val (a, ap) = monte()
        a.tic()
        val id = "ariss:serie34@google.com"
        post(a, "/autopilote", mapOf("id" to id))
        // The calendar says one more day.
        fin = "20261027T174500Z"
        heure += 6 * m
        a.demandeRafraichissement(); a.tic()
        assertEquals(1_793_123_100_000L, ap.campagnes.single().finMs)
        // Changed by hand in Agenda › SSTV: no longer followed.
        val c = ap.campagnes.single()
        val form = mapOf("id" to c.id, "nom" to c.nom, "catnum" to "25544", "freq" to "437.550", "mode" to c.modeSstv,
            "de" to c.debutMs.toString(), "a" to (c.finMs + 3_600_000L).toString())
        val rep = JSONObject(String(ap.sert("/api/autopilote/campagne", "POST", { form[it].orEmpty() }) { null }.corps, Charsets.UTF_8))
        assertTrue(rep.toString(), rep.getBoolean("ok"))
        val k = ap.campagnes.single()
        assertEquals(id, k.activite)
        assertFalse(k.activiteSuit)
        fin = "20261028T174500Z"
        heure += 6 * m
        a.demandeRafraichissement(); a.tic()
        assertEquals(k.finMs, ap.campagnes.single().finMs)
    }

    @Test fun `un satellite nomme sans numero, trouve ou choisi une fois`() {
        val (a, ap) = monte()
        a.tic()
        // « UMKA-1 » is « UmKA 1 (RS40S) » in the catalogue.
        assertEquals(57172, activite(a, "rss:umka").getInt("catnum"))
        // Not found: the button all the same, the satellite to pick.
        val s = activite(a, "rss:x")
        assertEquals(0, s.getInt("catnum"))
        assertTrue(s.getBoolean("autopilote"))
        val v = get(a, "/autopilote", mapOf("id" to "rss:x"))
        assertTrue(v.isNull("campagne"))
        assertTrue(v.getBoolean("datesInconnues"))
        assertFalse(post(a, "/autopilote", mapOf("id" to "rss:x")).getBoolean("ok"))
        assertFalse(post(a, "/autopilote", mapOf("id" to "rss:x", "catnum" to "99999")).getBoolean("ok"))
        assertTrue(ap.campagnes.isEmpty())
        val v2 = get(a, "/autopilote", mapOf("id" to "rss:x", "catnum" to "57172"))
        assertNotNull(v2.optJSONObject("campagne"))
        val r = post(a, "/autopilote", mapOf("id" to "rss:x", "catnum" to "57172"))
        assertTrue(r.toString(), r.getBoolean("ok"))
        assertEquals(57172, ap.campagnes.single().catnum)
        // Remembered by name: found by itself next time, also after a restart.
        assertEquals(57172, activite(a, "rss:x").getInt("catnum"))
        val (a2, _) = monte()
        assertEquals(57172, a2.liste().first { it.id == "rss:x" }.catnum)
    }

    @Test fun `l'elevation de la campagne celle du rotor ou de la station, ou celle donnee dans la fenetre`() {
        // A station that lists every pass from 0° with a rotor going no lower than 10°: the rotor's.
        val (a, ap) = monte(minEl = 0, rotorMinEl = 10)
        a.tic()
        val id = "ariss:serie34@google.com"
        val v = get(a, "/autopilote", mapOf("id" to id))
        assertEquals(10.0, v.getDouble("elMin"), 0.0)
        assertEquals(10.0, v.getJSONObject("campagne").getDouble("elMin"), 0.0)
        assertEquals(1, v.getJSONArray("passages").length())
        // Typed in the dialog: the passes listed follow it (the one known culminates at 50°).
        assertEquals(0, get(a, "/autopilote", mapOf("id" to id, "el" to "60")).getJSONArray("passages").length())
        assertEquals(1, get(a, "/autopilote", mapOf("id" to id, "el" to "45")).getJSONArray("passages").length())
        assertEquals(10.0, get(a, "/autopilote", mapOf("id" to id, "el" to "abc")).getDouble("elMin"), 0.0)
        assertEquals(90.0, get(a, "/autopilote", mapOf("id" to id, "el" to "120")).getDouble("elMin"), 0.0)
        // Without it, the default; with it, the one typed; clicked again, the existing campaign takes the new one.
        post(a, "/autopilote", mapOf("id" to id))
        assertEquals(10.0, ap.campagnes.single().elMin, 0.0)
        post(a, "/autopilote", mapOf("id" to id, "el" to "20"))
        assertEquals(20.0, ap.campagnes.single().elMin, 0.0)
        assertEquals(id, ap.campagnes.single().activite)
        // A station asking more than its rotor: its minimum.
        val (a2, _) = monte(minEl = 15)
        a2.tic()
        assertEquals(15.0, get(a2, "/autopilote", mapOf("id" to id)).getDouble("elMin"), 0.0)
        assertEquals(0.0, CampagneSstv.elMinActivite(0.0), 0.0)
        assertEquals(25.0, CampagneSstv.elMinActivite(25.0), 0.0)
        assertEquals(12.0, CampagneSstv.elMinActivite(5.0, 12.0), 0.0)
    }
}
