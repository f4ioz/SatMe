/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.datv.EtatDatv
import fr.f4ioz.satcombo.datv.ReglageDatv
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO

/** « Direct sur le site »: what is built, what goes (and only that), when it stops. */
class DirectSitePcTest {

    @get:Rule val tmp = TemporaryFolder()

    private class Envoi(val methode: String, val url: String, val entetes: Map<String, String>, val corps: ByteArray?)
    private val envois = ArrayList<Envoi>()
    private var reponse: (Envoi) -> Pair<Int, String> = { 200 to "{\"ok\":true}" }
    private var t = 1_800_000_000_000L

    /** The settings of the last [direct]. */
    private lateinit var reglages: StockageFichier

    private fun direct(): Pair<SitePc, DirectSitePc> {
        val st = StockageFichier(tmp.newFile("r-" + System.nanoTime() + ".properties"))
        reglages = st
        val site = SitePc(st, tmp.newFolder(), { false }, { t }, SitePc.Transport { m, u, e, c -> val x = Envoi(m, u, e, c?.octets()); envois += x; reponse(x) })
        // « Mon site web » switched on, the site set and tested.
        fr.f4ioz.satcombo.demo.ModulesStation.pose(st, emptySet())
        site.sert("/api/site/essai", "POST") { k -> when (k) { "url" -> "https://example.org/api/publication/{type}"; "jeton" -> "le-jeton"; else -> "" } }
        envois.clear()
        val d = DirectSitePc(st, site, { t })
        site.direct = d
        return site to d
    }

    /** What the auto-pilot's page carries, with things the site must never get. */
    private fun ap() = JSONObject().put("maitre", true).put("cartes", "hw:CARD=USB,DEV=0").put("profils", "icom-9700")
        .put("prochain", JSONObject().put("campagne", "ARISS").put("sat", "ISS").put("aos", t + 3_600_000L).put("los", t + 4_200_000L).put("el", 43)
            .put("freqHz", 145_800_000L).put("rotor", "192.168.0.9:4533"))
        .put("enCours", JSONObject().put("campagne", "ARISS").put("sat", "ISS").put("aos", t - 60_000L).put("los", t + 500_000L).put("el", 60).put("phase", "EN_COURS")
            .put("mode", "PD120").put("images", 2).put("enregistrement", "/home/olivier/.satme/son.wav")
            .put("image", JSONObject().put("phase", "ATTENTE").put("prochaine", t + 90_000L).put("progression", 0.5)))

    private fun jpeg(w: Int, h: Int): ByteArray {
        val i = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) for (x in 0 until w) i.setRGB(x, y, (x * 31 + y * 17) * 2654435761L.toInt())
        return ByteArrayOutputStream().also { ImageIO.write(i, "jpg", it) }.toByteArray()
    }

    @Test fun `the auto-pilot's words are only names, times and counts`() {
        val o = DirectSitePc.pilote(ap(), Triple("ISS", t - 600_000L, "PD120"), t)
        val texte = o.toString()
        for (secret in listOf("hw:CARD", "icom", "192.168", "4533", ".satme", "son.wav", "145800000", "freqHz", "rotor", "enregistrement", "cartes", "profils"))
            assertFalse(secret, texte.contains(secret))
        assertEquals(setOf("maitre", "prochain", "enCours", "derniere"), o.keySet())
        assertEquals(setOf("campagne", "sat", "aos", "los", "el"), o.getJSONObject("prochain").keySet())
        assertEquals(t + 90_000L, o.getJSONObject("enCours").getJSONObject("image").getLong("prochaine"))
    }

    @Test fun `the receiver's figures, never its node`() {
        val e = EtatDatv(actif = true, etat = 4, merDb = 7.43, modcod = "QPSK 4/5", service = "F4XYZ", fournisseur = "Paris", srTrouveKs = 333)
        val o = DirectSitePc.datv(e, ReglageDatv(cielKhz = 10_494_750L, srKs = 333), 2.1)
        assertEquals(setOf("verrouille", "cielKhz", "srKs", "service", "fournisseur", "modcod", "mer", "marge"), o.keySet())
        assertEquals(7.4, o.getDouble("mer"), 1e-9)
        val hors = DirectSitePc.datv(EtatDatv(actif = true, etat = 1), ReglageDatv(), null)
        assertFalse(hors.getBoolean("verrouille")); assertFalse(hors.has("service"))
    }

    @Test fun `a spectrum line is small, scaled and the right way round`() {
        val db = FloatArray(16384) { -100f }.also { it[100] = -40f }
        val l = DirectSitePc.ligneSpectre(db, 10_489_750_000.0, -1_058_400.0)!!
        val b = Base64.getDecoder().decode(l.getString("bins"))
        assertEquals(1024, b.size)
        assertEquals(1_058_400L, l.getLong("largeurHz"))
        // Turned over: the peak near the start of the panorama ends near the end of the line.
        assertTrue((b[1023 - 100 / 16].toInt() and 0xFF) > 240)
        assertTrue((b[0].toInt() and 0xFF) < 40)
        assertTrue(l.toString().length < 1600)
        assertNull(DirectSitePc.ligneSpectre(FloatArray(10), 10_489_750_000.0, 1e6))
    }

    @Test fun `a DATV picture is made small`() {
        val j = DirectSitePc.jpegReduit(jpeg(1920, 1080))!!
        assertTrue(j.size <= 190 * 1024)
        assertEquals(640, ImageIO.read(j.inputStream()).width)
        assertNull(DirectSitePc.jpegReduit(ByteArray(100)))
    }

    @Test fun `nothing goes unless chosen, then each part to its route with the token`() {
        val (site, d) = direct()
        val a = ap()
        d.pilote = { a }
        d.panorama = { DirectSitePc.Panorama(FloatArray(16384) { -90f }, 10_489_750_000.0, 1_058_400.0) }
        var demande = 0
        d.demandePanorama = { demande++ }
        d.tic()
        assertTrue(envois.isEmpty())
        assertEquals(0, demande)
        site.sert("/api/site/direct", "POST") { k -> when (k) { "on_pilote", "on_spectre" -> "1"; else -> "" } }
        d.tic()
        assertEquals(listOf("https://example.org/api/publication/direct", "https://example.org/api/publication/direct-spectre"), envois.map { it.url })
        assertTrue(envois.all { it.entetes["Authorization"] == "Bearer le-jeton" && it.methode == "POST" && it.entetes["X-SatMe-Version"] == "1" })
        assertEquals(listOf("direct", "direct-spectre"), envois.map { it.entetes["X-SatMe-Type"] })
        assertTrue(demande > 0)
        val corps = String(envois[0].corps!!)
        assertFalse(corps.contains("le-jeton")); assertFalse(corps.contains("hw:CARD"))
        // The same auto-pilot state again within a minute: not sent; the spectrum every second.
        envois.clear(); t += 10_000L; d.tic()
        assertEquals(listOf("https://example.org/api/publication/direct-spectre"), envois.map { it.url })
        // The token is never shown on SatMe-PC's page either.
        assertFalse(site.json().toString().contains("le-jeton"))
        assertTrue(site.json().getJSONObject("direct").getJSONArray("canaux").getJSONObject(0).getBoolean("on"))
    }

    @Test fun `nothing at all while Mon site web is switched off, even the parts chosen`() {
        val (site, d) = direct()
        d.pilote = { ap() }
        d.panorama = { DirectSitePc.Panorama(FloatArray(16384) { -90f }, 10_489_750_000.0, 1_058_400.0) }
        var demande = 0
        d.demandePanorama = { demande++ }
        site.sert("/api/site/direct", "POST") { k -> when (k) { "on_pilote", "on_spectre", "on_datv" -> "1"; else -> "" } }
        fr.f4ioz.satcombo.demo.ModulesStation.pose(reglages, setOf(fr.f4ioz.satcombo.demo.ModulesStation.SITE))
        repeat(5) { t += 10_000L; d.tic() }
        assertTrue(envois.isEmpty())
        assertEquals(0, demande)
        assertNull(site.envoieDirect("direct", JSONObject()))
        // The page cannot switch a part on or wipe the site's live block either.
        assertFalse(site.sert("/api/site/direct", "POST") { k -> if (k == "efface") "1" else "" }.corps.toString(Charsets.UTF_8).contains("\"ok\":true"))
        assertTrue(envois.isEmpty())
    }

    @Test fun `a refusal waits two minutes and says why, a silent site backs off`() {
        val (site, d) = direct()
        d.panorama = { DirectSitePc.Panorama(FloatArray(4096) { -90f }, 10_489_750_000.0, 1_058_400.0) }
        site.sert("/api/site/direct", "POST") { k -> if (k == "on_spectre") "1" else "" }
        reponse = { 403 to "{\"ok\":false,\"refuse\":true,\"erreur\":\"le site n'affiche pas le spectre\"}" }
        d.tic()
        assertEquals(1, envois.size)
        assertTrue(d.json().toString().contains("refusé par le site : le site n'affiche pas le spectre"))
        t += 60_000L; d.tic()
        assertEquals(1, envois.size)
        t += 61_000L; d.tic()
        assertEquals(2, envois.size)
        // No answer: 30 s, then a minute.
        reponse = { throw java.io.IOException("connexion refusée") }
        t += 121_000L; d.tic()
        assertEquals(3, envois.size)
        assertTrue(d.json().toString().contains("pas de réponse : connexion refusée"))
        t += 10_000L; d.tic()
        assertEquals(3, envois.size)
        t += 21_000L; d.tic()
        assertEquals(4, envois.size)
        t += 31_000L; d.tic()
        assertEquals(4, envois.size)
    }

    @Test fun `the DATV picture goes only while locked, and once`() {
        val (site, d) = direct()
        val img = jpeg(1280, 720)
        var e = EtatDatv(actif = true, etat = 1)
        d.datv = { DirectSitePc.Datv(e, ReglageDatv(), null, img) }
        site.sert("/api/site/direct", "POST") { k -> if (k == "on_datv") "1" else "" }
        d.tic()
        assertEquals(listOf("https://example.org/api/publication/direct"), envois.map { it.url })
        e = EtatDatv(actif = true, etat = 4, merDb = 6.0, service = "F4XYZ")
        t += 5_000L; d.tic()
        assertEquals("https://example.org/api/publication/direct-datv-image", envois.last().url)
        assertTrue(envois.last().entetes["Content-Type"]!!.startsWith("multipart/form-data"))
        assertNotNull(ImageIO.read(SitePcTest.parties(envois.last().entetes["Content-Type"]!!, envois.last().corps!!).getValue("fichier").second.inputStream()))
        val n = envois.size
        t += 5_000L; d.tic()
        assertEquals(n + 1, envois.size)
        // The receiver stopped: told once, then nothing.
        e = EtatDatv(actif = false)
        t += 5_000L; d.tic(); t += 5_000L; d.tic()
        assertEquals(n + 2, envois.size)
        assertTrue(String(envois.last().corps!!).contains("\"datv\":null"))
    }

    @Test fun `during a pass the picture goes every few seconds, behind Auto-pilote, and only when it changed`() {
        val (site, d) = direct()
        val ap = JSONObject().put("enCours", JSONObject().put("sat", "ISS").put("phase", "EN_COURS").put("aos", t - 60_000L).put("los", t + 300_000L)
            .put("elMaintenant", 31).put("azMaintenant", 120))
        d.pilote = { ap }
        var image = jpeg(320, 256)
        d.imageSstv = { image }
        d.tic()
        assertTrue(envois.isEmpty())
        site.sert("/api/site/direct", "POST") { k -> if (k == "on_pilote") "1" else "" }
        d.tic()
        assertEquals(listOf("https://example.org/api/publication/direct", "https://example.org/api/publication/direct-sstv-image"), envois.map { it.url })
        assertTrue(String(envois[0].corps!!).contains("\"elMaintenant\":31"))
        t += DirectSitePc.PERIODE_SSTV_MS; d.tic()
        assertEquals(2, envois.size)
        image = jpeg(320, 240)
        t += DirectSitePc.PERIODE_SSTV_MS; d.tic()
        assertEquals(3, envois.size)
        // Well after LOS: nothing more.
        image = jpeg(300, 200)
        t += 400_000L; d.tic()
        assertTrue(envois.none { it.url.endsWith("sstv-image") && envois.indexOf(it) > 2 })
    }
}
