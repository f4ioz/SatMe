/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site

import fr.f4ioz.site.direct.EtatDirect
import fr.f4ioz.site.publication.TypePublication
import fr.f4ioz.site.reglages.ReglageModule
import fr.f4ioz.site.reglages.ReglagePublication
import fr.f4ioz.site.reglages.Reglages
import fr.f4ioz.site.reglages.ReglagesSite
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO

/** « Admin › Paramètres »'s file, and the live state SatMe-PC pushes. */
class ReglagesTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun `no file is the site as it was`() {
        val r = ReglagesSite(tmp.root.resolve("reglages-site.json")).also { it.charge() }.courant
        assertTrue(r.module("/grid").visible)
        assertTrue(TypePublication.values().all { r.publication(it).accepte && r.publication(it).publique })
        assertFalse(r.bandeau.actif)
        assertFalse(r.direct.actif)
        assertEquals("auto", r.direct.source)
        assertTrue(r.accueil.passages && r.accueil.qso && r.accueil.image && r.accueil.article && r.accueil.satme && r.accueil.outils)
        assertNull(r.satellitesAccueil)
    }

    @Test fun `written whole, read back, nothing left beside it`() {
        val f = tmp.root.resolve("reglages-site.json")
        val st = ReglagesSite(f).also { it.charge() }
        st.change { it.copy(modules = mapOf("/grid" to ReglageModule(false, "Locator", 5)),
            publications = mapOf(TypePublication.VIDEO to ReglagePublication(accepte = false, publique = true)),
            satellitesAccueil = listOf("ISS", "SO-50")) }
        assertTrue(f.isFile)
        assertEquals(listOf("reglages-site.json"), tmp.root.list()!!.toList())
        val r = ReglagesSite(f).also { it.charge() }.courant
        assertEquals(ReglageModule(false, "Locator", 5), r.module("/grid"))
        assertFalse(r.publication(TypePublication.VIDEO).accepte)
        assertTrue(r.publication(TypePublication.SSTV).accepte)
        assertEquals(listOf("ISS", "SO-50"), r.satellitesAccueil)
        // « Rétablir »: the file gone, the defaults back.
        st.retablis()
        assertFalse(f.exists())
        assertTrue(st.courant.module("/grid").visible)
    }

    @Test fun `unknown keys are kept, wrong values are the defaults`() {
        val f = tmp.root.resolve("reglages-site.json")
        f.writeText(JSONObject().put("demain", JSONObject().put("x", 1)).put("bandeau", JSONObject().put("actif", "oui").put("derniereImage", false))
            .put("direct", JSONObject().put("actif", true).put("source", "ailleurs"))
            .put("modules", JSONObject().put("../etc", JSONObject().put("visible", false)).put("/dx", JSONObject().put("visible", false).put("libelle", "<b>DX</b>\n")))
            .put("publications", JSONObject().put("inconnu", JSONObject().put("accepte", false))).toString())
        val st = ReglagesSite(f).also { it.charge() }
        val r = st.courant
        assertFalse(r.bandeau.actif)
        assertFalse(r.bandeau.derniereImage)
        assertTrue(r.direct.actif)
        assertEquals("auto", r.direct.source)
        assertEquals(setOf("/dx"), r.modules.keys)
        assertEquals("b DX /b", r.module("/dx").libelle)
        assertTrue(r.publications.isEmpty())
        st.change { it.copy(bandeau = it.bandeau.copy(actif = true)) }
        val o = JSONObject(f.readText())
        assertEquals(1, o.getJSONObject("demain").getInt("x"))
        assertTrue(o.getJSONObject("bandeau").getBoolean("actif"))
    }

    @Test fun `an unreadable file is set aside`() {
        val f = tmp.root.resolve("reglages-site.json")
        f.writeText("{pas du json")
        val st = ReglagesSite(f).also { it.charge() }
        assertEquals(Reglages().json().toString(), st.courant.json().toString())
        assertTrue(tmp.root.resolve("reglages-site.json.illisible").isFile)
    }

    // ------------------------------------------------------------------ the live state

    private fun jpeg(w: Int, h: Int): ByteArray = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "jpg", it) }.toByteArray()

    @Test fun `the auto-pilot's words are read field by field, then forgotten`() {
        var t = 1_800_000_000_000L
        val e = EtatDirect { t }
        val p = JSONObject().put("maitre", true).put("secret", "non").put("cartes", "hw:1")
            .put("prochain", JSONObject().put("campagne", "ARISS\u0007 octobre").put("sat", "ISS").put("aos", t + 3_600_000L).put("los", t + 4_200_000L).put("el", 43.4).put("freqHz", 145_800_000L))
            .put("enCours", JSONObject().put("sat", "ISS").put("aos", t - 60_000L).put("los", t + 500_000L).put("phase", "EN_COURS").put("enregistrement", "/home/x/son.wav")
                .put("image", JSONObject().put("phase", "ATTENTE").put("prochaine", t + 90_000L).put("progression", 3.0)))
        e.recoit(JSONObject().put("t", t).put("pilote", p), pilotePermis = true, datvPermis = false)
        val r = e.pilote()!!
        assertEquals(setOf("maitre", "prochain", "enCours"), r.keySet())
        assertEquals("ARISS  octobre", r.getJSONObject("prochain").getString("campagne"))
        assertEquals(43L, r.getJSONObject("prochain").getLong("el"))
        assertFalse(r.getJSONObject("prochain").has("freqHz"))
        assertFalse(r.getJSONObject("enCours").has("enregistrement"))
        assertFalse(r.getJSONObject("enCours").getJSONObject("image").has("progression"))
        t += EtatDirect.PILOTE_MS + 1
        assertNull(e.pilote())
    }

    @Test fun `a PC whose clock is off has its times put right`() {
        val t = 1_800_000_000_000L
        val e = EtatDirect { t }
        e.recoit(JSONObject().put("t", t - 60_000L).put("pilote", JSONObject().put("prochain", JSONObject().put("sat", "ISS").put("aos", t).put("los", t + 1000))),
            pilotePermis = true, datvPermis = false)
        assertEquals(t + 60_000L, e.pilote()!!.getJSONObject("prochain").getLong("aos"))
    }

    @Test fun `refused when the site does not show it`() {
        val e = EtatDirect()
        try { e.recoit(JSONObject().put("pilote", JSONObject()), pilotePermis = false, datvPermis = false); fail() } catch (x: EtatDirect.Refus) { assertEquals(403, x.statut) }
        // Clearing is always allowed.
        e.recoit(JSONObject().put("pilote", JSONObject.NULL).put("datv", JSONObject.NULL), pilotePermis = false, datvPermis = false)
    }

    @Test fun `the DATV picture is a small JPEG only, stale after 30 s`() {
        var t = 1_800_000_000_000L
        val e = EtatDirect { t }
        try { e.recoitImage(PublicationsTest.png(64, 64)); fail() } catch (x: EtatDirect.Refus) { assertEquals(415, x.statut) }
        try { e.recoitImage(jpeg(2000, 1200)); fail() } catch (x: EtatDirect.Refus) { assertEquals(415, x.statut) }
        val gros = ByteArray(EtatDirect.IMAGE_MAX + 10).also { it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte(); it[2] = 0xFF.toByte() }
        try { e.recoitImage(gros); fail() } catch (x: EtatDirect.Refus) { assertEquals(413, x.statut) }
        e.recoitImage(jpeg(640, 360))
        e.recoit(JSONObject().put("datv", JSONObject().put("verrouille", true).put("service", "F4XYZ").put("mer", 7.4).put("noeud", "192.168.0.9")), true, true)
        val d = e.datv()!!
        assertTrue(d.getBoolean("enLigne"))
        assertEquals("F4XYZ", d.getString("service"))
        assertFalse(d.has("noeud"))
        assertEquals(1L, d.getJSONObject("image").getLong("numero"))
        t += EtatDirect.DATV_MS + 1
        assertFalse(e.datv()!!.getBoolean("enLigne"))
        assertNotNull(e.image())
        t += EtatDirect.OUBLI_MS
        assertNull(e.datv()); assertNull(e.image())
    }

    @Test fun `the spectrum keeps a short waterfall, starts again when the axis moves`() {
        var t = 1_800_000_000_000L
        val e = EtatDirect { t }
        fun ligne(centre: Long, n: Int = 1024) = JSONObject().put("centreHz", centre).put("largeurHz", 1_058_400L).put("minDb", -100.0).put("maxDb", -40.0)
            .put("bins", Base64.getEncoder().encodeToString(ByteArray(n) { (it % 200).toByte() }))
        repeat(EtatDirect.HISTOIRE + 30) { e.recoitSpectre(ligne(10_489_750_000L)); t += 1000 }
        assertTrue(e.spectreActif())
        assertEquals(EtatDirect.HISTOIRE, e.spectre(0)!!.getJSONArray("lignes").length())
        assertEquals(1, e.spectre(e.seq - 1)!!.getJSONArray("lignes").length())
        e.recoitSpectre(ligne(10_490_500_000L))
        assertEquals(1, e.spectre(0)!!.getJSONArray("lignes").length())
        for (mauvais in listOf(ligne(10_489_750_000L, 10), ligne(0L), ligne(10_489_750_000L).put("bins", "@@@"), ligne(10_489_750_000L, 4096))) {
            try { e.recoitSpectre(mauvais); fail(mauvais.toString().take(80)) } catch (x: EtatDirect.Refus) { assertTrue(x.statut in setOf(400, 413)) }
        }
        t += EtatDirect.SPECTRE_MS + 1
        assertFalse(e.spectreActif())
    }

    // ------------------------------------------------------------------ which view the live block shows

    @Test fun `QO-100 by default, the SSTV pass from AOS to LOS, then back after the hold`() {
        val aos = 1_800_000_000_000L; val los = aos + 600_000L; val maintien = 60_000L
        val p = JSONObject().put("phase", "EN_COURS").put("aos", aos).put("los", los)
        fun vue(t: Long, image: Long = 0L, frais: Boolean = true, bascule: Boolean = true, passage: JSONObject? = p) =
            fr.f4ioz.site.direct.VueDirect.choisis(passage, image, frais, t, bascule, maintien).nom
        assertEquals("qo100", vue(aos - 1000, passage = null))
        assertEquals("qo100", vue(aos - 1000))
        assertEquals("sstv", vue(aos))
        assertEquals("sstv", vue(los))
        // The station silent during the pass: not shown.
        assertEquals("qo100", vue(aos + 1000, frais = false))
        assertEquals("qo100", vue(aos + 1000, bascule = false))
        // After LOS: the hold, from the later of LOS and the last picture.
        assertEquals("sstv", vue(los + maintien))
        assertEquals("qo100", vue(los + maintien + 1))
        assertEquals("sstv", vue(los + 100_000L, image = los + 50_000L))
        assertEquals("qo100", vue(los + 111_000L, image = los + 50_000L))
        assertEquals("qo100", vue(aos, passage = JSONObject().put("aos", aos).put("los", aos - 1)))
    }

    @Test fun `the pass is kept for its hold after the auto-pilot stops telling it`() {
        var t = 1_800_000_000_000L
        val e = EtatDirect { t }
        val en = JSONObject().put("sat", "ISS").put("phase", "EN_COURS").put("aos", t - 60_000L).put("los", t + 60_000L).put("elMaintenant", 31.6).put("azMaintenant", 412)
        e.recoit(JSONObject().put("t", t).put("pilote", JSONObject().put("enCours", en)), true, false)
        e.recoitImageSstv(jpeg(320, 256))
        assertEquals("sstv", e.vue(true, 60_000L).nom)
        val ps = e.passageSstv()!!
        assertEquals(32L, ps.getLong("elMaintenant"))
        assertFalse(ps.has("azMaintenant"))
        assertEquals(1L, ps.getJSONObject("image").getLong("numero"))
        // LOS passed, the auto-pilot done: the pass stays a minute, then QO-100.
        t += 90_000L
        e.recoit(JSONObject().put("t", t).put("pilote", JSONObject()), true, false)
        assertEquals("sstv", e.vue(true, 60_000L).nom)
        t += 61_000L
        assertEquals("qo100", e.vue(true, 60_000L).nom)
        // No pass known: its picture refused.
        val e2 = EtatDirect()
        try { e2.recoitImageSstv(jpeg(320, 256)); fail() } catch (x: EtatDirect.Refus) { assertEquals(409, x.statut) }
    }

    @Test fun `the home page fits one screen whatever the cards`() {
        val p = fr.f4ioz.site.pages.Accueil.places(listOf("direct", "passages", "qso", "image", "article", "outils"))
        assertEquals("--gc:1/8;--gr:1/4", p["direct"])
        assertEquals("--gc:10/13;--gr:3/4", p["article"])
        assertEquals("--gc:1/13;--gr:4/5", p["outils"])
        assertEquals("--gc:1/13;--gr:1/4", fr.f4ioz.site.pages.Accueil.places(listOf("direct"))["direct"])
        val sans = fr.f4ioz.site.pages.Accueil.places(listOf("passages", "qso", "article"))
        assertEquals(listOf("--gc:1/5;--gr:1/4", "--gc:5/9;--gr:1/4", "--gc:9/13;--gr:1/4"), sans.values.toList())
    }
}
