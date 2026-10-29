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
import fr.f4ioz.satcombo.data.LogEntry
import fr.f4ioz.satcombo.domain.ActivitesAVenir
import fr.f4ioz.satcombo.domain.Diplomes
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** « Agenda › Diplômes » on the PC: the list matched with the gallery and the log, the notices, the request prepared, the follow-up kept. */
class DiplomesPcTest {

    private val dossier = File(System.getProperty("java.io.tmpdir"), "satme-diplomes-pc-" + System.nanoTime()).apply { mkdirs() }
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun fin() { portee.cancel(); dossier.deleteRecursively() }

    private fun date(s: String) = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(s)!!.time
    private var heure = date("2026-10-27 10:00")

    private val pzk = """<html><title>ARISS SSTV Award</title><div id="news_en"><p>SSTV Expedition 75 Series 34 features “ARISS Hardware”
        <br>ARISS SSTV serie 34 is planned to be on 22 October 2026 and run until 25 October 2026. Frequency will be 437.550 MHz. SSTV mode will be Robot 36.</p></div>
        <div id="award_rules_en"></div><h3 class="page-header">Expdition 75-2 (22.10.2026 - 25.10.2026)</h3><p>ARISS SSTV Award "ARISS Hardware"</p></html>"""

    private val queretaro = """ARISS SSTV SERIES 34
        OCTOBER
        22 – 25, 2026
        PARTICIPATION AWARD
        RadioClub Querétaro will grant a beautiful AWARD in recognition for receiving an image from ARISS SSTV Series 34.
        REQUEST DEADLINE:
        OCTOBER 30, 2026
        Radio Club Querétaro A.C. XE1RCQ
        https: //bit.ly/4dRLRke""".trimIndent()

    private val images = listOf(
        Diplomes.Image("SatMe_SSTV_ISS_20261023_084038Z_Robot36.png", "ISS", date("2026-10-23 08:40"), note = 80, mode = "Robot36"),
        Diplomes.Image("SatMe_SSTV_ISS_20261026_084317Z_Robot36.png", "ISS", date("2026-10-26 08:43"), note = 92, mode = "Robot36"),
    )
    private val serie34 = ActivitesAVenir.Activite(id = "serie34", type = ActivitesAVenir.Type.SSTV, satNom = "ISS", catnum = 25544,
        debutMs = date("2026-10-22 15:10"), finMs = date("2026-10-26 17:45"), freqHz = 437_550_000L, mode = "Robot 36",
        titre = "ARISS SSTV Series 34", sources = listOf(ActivitesAVenir.Source("ARISS (agenda)")))
    private val log = listOf(
        LogEntry(timeMs = 1L, satName = "SO-50", catnum = 27607, azimuthDeg = 0.0, elevationDeg = 0.0, callsign = "EA4ABC", theirLocator = "IN80"),
        LogEntry(timeMs = 2L, satName = "RS-44", catnum = 44909, azimuthDeg = 0.0, elevationDeg = 0.0, callsign = "DL1XYZ", theirLocator = "JO62"))

    private fun monte(enLigne: Boolean = true): DiplomesPc {
        val st = StationPc(StockageMemoire(), Catalogue(File(dossier, "cache"), enLigne = false), PortsSeriePc(), portee, { heure })
        st.settings.callsign = "f4ioz"
        val faux = ActivitesEnLigne.Telechargeur { url, _, _ ->
            if (url.contains("pzk")) ActivitesEnLigne.Reponse(200, pzk) else ActivitesEnLigne.Reponse(404, null)
        }
        return DiplomesPc(st, dossier, { heure }, faux, images = { images }, contacts = { log }, activites = { listOf(serie34) },
            infosImage = { n -> JSONObject().put("nom", n).put("quand", images.first { it.nom == n }.quandMs).put("sat", "ISS").put("freqHz", 437_550_000L).put("mode", "Robot 36") },
            ocr = { null }, enLigneAutorise = enLigne)
    }

    private fun get(d: DiplomesPc, route: String = "", p: Map<String, String> = emptyMap()) =
        JSONObject(String(d.sert("/api/diplomes$route", "GET", { p[it].orEmpty() }, { null }).corps, Charsets.UTF_8))
    private fun post(d: DiplomesPc, route: String, p: Map<String, String> = emptyMap(), corps: ByteArray? = null) =
        d.sert("/api/diplomes$route", "POST", { p[it].orEmpty() }, { corps })
    private fun postJ(d: DiplomesPc, route: String, p: Map<String, String> = emptyMap(), corps: ByteArray? = null) =
        JSONObject(String(post(d, route, p, corps).corps, Charsets.UTF_8))
    private fun liste(j: JSONObject): List<JSONObject> = j.getJSONArray("diplomes").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

    @Test fun `la session ARISS, le diplôme du club ajouté, la demande préparée, le suivi gardé`() {
        val d = monte()
        d.tic()
        val ariss = liste(get(d)).first { it.getString("id") == "pzk:75-2" }
        // Its page says the 25th, the calendar the 26th 17:45: the picture of the 26th counts.
        assertEquals("OBTENABLE", ariss.getString("statut"))
        assertEquals(2, ariss.getInt("nImages"))
        assertEquals("SatMe_SSTV_ISS_20261026_084317Z_Robot36.png", ariss.getJSONArray("images").getJSONObject(0).getString("nom"))
        // The calendar's own session is the same one: kept once.
        assertEquals(1, liste(get(d)).count { Diplomes.estAriss(Diplomes.lis(it)!!) })
        // The log's: 2 grids of 100.
        assertEquals("En cours : 2/100 locators", liste(get(d)).first { it.getString("id") == "cat:vucc" }.getString("libelle"))

        // A flyer's text read, then added.
        val prop = postJ(d, "/texte", corps = queretaro.toByteArray()).getJSONObject("proposition")
        assertEquals("https://bit.ly/4dRLRke", prop.getString("formulaire"))
        val id = postJ(d, "/ajoute", corps = prop.toString().toByteArray()).getString("id")
        val club = liste(get(d)).first { it.getString("id") == id }
        assertEquals("OBTENABLE", club.getString("statut"))
        assertTrue(club.getString("libelle"), club.getString("libelle").startsWith("Obtenable — à demander avant le 30/10"))

        // Two days before its deadline: told once, obtainable and the deadline.
        heure = date("2026-10-28 12:00")
        d.tic(); d.tic()
        val textes = get(d).getJSONArray("annonces").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("texte") } }
        assertEquals(1, textes.count { it.startsWith("Plus que") && it.contains("Participation") })
        assertEquals(1, textes.count { it.startsWith("Diplôme obtenable") && it.contains("Participation") })

        // The request: the station's callsign, the e-mail said missing (never made up), the pictures with their UTC time.
        val dem = get(d, "/demande", mapOf("id" to id))
        val champs = dem.getJSONArray("champs").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        assertEquals("F4IOZ", champs.first { it.getString("k") == "indicatif" }.getString("valeur"))
        assertTrue(champs.first { it.getString("k") == "courriel" }.getBoolean("manque"))
        val img = dem.getJSONArray("images").getJSONObject(0)
        assertEquals("2026-10-26", img.getString("date"))
        assertEquals("08:43:00 UTC", img.getString("heure"))
        assertEquals("437,550 MHz", img.getString("frequence"))
        assertTrue(dem.getString("pack").startsWith("api/sstv-options/ariss.zip?noms="))
        assertTrue(dem.getString("texte").contains("Courriel : (à remplir)"))
        // Given by the owner, then in the request.
        assertTrue(postJ(d, "/identite", mapOf("nom" to "Olivier", "courriel" to "f4ioz@example.org")).getBoolean("ok"))
        assertFalse(postJ(d, "/identite", mapOf("courriel" to "pas une adresse")).getBoolean("ok"))
        assertTrue(get(d, "/demande", mapOf("id" to id)).getString("texte").contains("f4ioz@example.org"))

        // Asked, then received with its picture.
        postJ(d, "/suivi", mapOf("id" to id, "etat" to "DEMANDE"))
        assertEquals("DEMANDE", liste(get(d)).first { it.getString("id") == id }.getString("statut"))
        val png = ByteArrayOutputStream().also { javax.imageio.ImageIO.write(java.awt.image.BufferedImage(4, 3, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
        assertTrue(postJ(d, "/certificat", mapOf("id" to id), png).getBoolean("ok"))
        val recu = liste(get(d)).first { it.getString("id") == id }
        assertEquals("RECU", recu.getString("statut"))
        assertTrue(recu.getJSONObject("suivi").getBoolean("certificat"))
        assertEquals("image/png", d.sert("/api/diplomes/certificat", "GET", { if (it == "id") id else "" }, { null }).type)

        // Kept: a new start finds the award added and its follow-up; offline, the ARISS session from the cache.
        val e = DiplomesPc(StationPc(StockageMemoire(), Catalogue(File(dossier, "cache2"), enLigne = false), PortsSeriePc(), portee, { heure }), dossier, { heure },
            { _, _, _ -> throw java.io.IOException("hors ligne") }, images = { images })
        e.tic()
        val l = liste(get(e))
        assertEquals("RECU", l.first { it.getString("id") == id }.getString("statut"))
        assertTrue(l.any { it.getString("id") == "pzk:75-2" })
        // Only what was added by hand can be taken away.
        assertFalse(postJ(e, "/retire", mapOf("id" to "pzk:75-2")).getBoolean("ok"))
        assertTrue(postJ(e, "/retire", mapOf("id" to id)).getBoolean("ok"))
        assertTrue(liste(get(e)).none { it.getString("id") == id })
    }

    @Test fun `la démonstration ne lit rien en ligne`() {
        val d = monte(enLigne = false)
        d.tic()
        val j = get(d)
        assertTrue(j.getBoolean("demo"))
        assertEquals(0, j.getJSONArray("sources").length())
        // Without the award's page, the calendar's ARISS session still has its award.
        assertTrue(liste(j).any { it.getString("id").startsWith("ariss:") && it.getString("statut") == "OBTENABLE" })
        assertTrue(JSONArray(j.getJSONArray("diplomes").toString()).length() >= Diplomes.CATALOGUE.size)
    }
}
