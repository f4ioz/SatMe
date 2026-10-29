/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.domain.Diplomes
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
import javax.imageio.ImageIO

/** « Publication web › Diplômes »: only the awards' own words and the owner's progress go; a picture only on a click. */
class DiplomesSitePcTest {

    @get:Rule val tmp = TemporaryFolder()

    private val t0 = 1_800_000_000_000L

    private fun bilans(): List<Diplomes.Bilan> {
        val vucc = Diplomes.CATALOGUE.first { it.id == "cat:vucc" }
        val dxcc = Diplomes.CATALOGUE.first { it.id == "cat:dxcc-sat" }
        val scc = Diplomes.CATALOGUE.first { it.id == "cat:amsat-scc" }
        val stations = Diplomes.CATALOGUE.first { it.id == "cat:samsat" }
        val ariss = Diplomes.Diplome("pzk:75-2", "ARISS SSTV série 25", "ARISS (géré par le PZK)", Diplomes.Genre.IMAGE, 1, satNom = "ISS", catnum = 25544,
            debutMs = t0 + 86_400_000L, finMs = t0 + 3 * 86_400_000L, limiteMs = t0 + 30 * 86_400_000L, lienReglement = "https://ariss.pzk.org.pl/sstv",
            lienFormulaire = "https://forms.example/secret", courrielDemande = "awards@example.org", frais = "gratuit", mode = "PD120",
            notes = "note privée", conditions = "longues conditions")
        val vieux = ariss.copy(id = "pzk:70", debutMs = t0 - 90 * 86_400_000L, finMs = t0 - 88 * 86_400_000L)
        val img = Diplomes.Image("iss-2026.png", "ISS", t0, true, 80, "PD120")
        return listOf(
            Diplomes.Bilan(vucc, Diplomes.Statut.EN_COURS, 37, elements = listOf("JN18", "IO91", "FN31"), libelle = "En cours : 37/100 locators",
                remarque = "3 contacts sans locator"),
            Diplomes.Bilan(dxcc, Diplomes.Statut.DEMANDE, 104, elements = listOf("France", "Japon"), libelle = "Demandé le 02/09",
                suivi = Diplomes.Suivi(Diplomes.EtatSuivi.DEMANDE, t0 - 1000L, note = "dossier envoyé à Olivier Gouyen")),
            Diplomes.Bilan(scc, Diplomes.Statut.RECU, 1, libelle = "Reçu le 01/09",
                suivi = Diplomes.Suivi(Diplomes.EtatSuivi.RECU, t0 - 2000L, certificat = "cat_amsat-scc.png", note = "dans le classeur")),
            Diplomes.Bilan(stations, Diplomes.Statut.MASQUE, 3, elements = listOf("F4ABC", "G0XYZ", "EA4ZZZ"), suivi = Diplomes.Suivi(Diplomes.EtatSuivi.MASQUE)),
            Diplomes.Bilan(ariss, Diplomes.Statut.A_VENIR, 0, images = listOf(img), libelle = "À venir : du 16/01 au 18/01"),
            Diplomes.Bilan(vieux, Diplomes.Statut.MANQUE, 0, libelle = "Aucune image reçue pendant l'événement"),
        )
    }

    @Test fun `the payload holds the awards' own words and the owner's progress, nothing else`() {
        val o = DiplomesSitePc.json(bilans(), setOf("cat:amsat-scc"))
        assertEquals(setOf("diplomes"), o.keySet())
        val a = o.getJSONArray("diplomes")
        val l = (0 until a.length()).map { a.getJSONObject(it) }
        // Hidden and missed ones stay here.
        assertEquals(listOf("cat:vucc", "cat:dxcc-sat", "cat:amsat-scc", "pzk:75-2"), l.map { it.getString("id") })
        l.forEach { d ->
            assertTrue(d.keySet().toString(), DiplomesSitePc.CLES.containsAll(d.keySet()))
            d.optJSONObject("progression")?.let { assertTrue(DiplomesSitePc.CLES_PROGRESSION.containsAll(it.keySet())) }
        }
        val vucc = l[0]
        assertEquals("permanent", vucc.getString("sorte")); assertEquals("en_cours", vucc.getString("statut"))
        assertEquals(37, vucc.getJSONObject("progression").getInt("actuel")); assertEquals(100, vucc.getJSONObject("progression").getInt("objectif"))
        assertEquals("locators", vucc.getJSONObject("progression").getString("unite"))
        assertEquals("https://www.arrl.org/vucc", vucc.getString("reglement"))
        assertEquals("demande", l[1].getString("statut")); assertEquals(t0 - 1000L, l[1].getLong("demande"))
        assertEquals("recu", l[2].getString("statut")); assertEquals(t0 - 2000L, l[2].getLong("recu")); assertTrue(l[2].getBoolean("image"))
        assertFalse(l[0].has("image"))
        val ev = l[3]
        assertEquals("evenement", ev.getString("sorte")); assertEquals("a_venir", ev.getString("statut")); assertEquals("ISS", ev.getString("satellite"))
        assertEquals(t0 + 86_400_000L, ev.getLong("debut")); assertEquals(t0 + 30 * 86_400_000L, ev.getLong("limite"))
        assertFalse(ev.has("progression"))
        // What was counted, the pictures, the notes, the forms, the e-mails: never.
        val texte = o.toString()
        listOf("JN18", "IO91", "France", "F4ABC", "G0XYZ", "iss-2026", "Olivier", "classeur", "secret", "awards@example.org", "note privée",
            "longues conditions", "sans locator", "kk5do", "cat_amsat-scc.png").forEach { assertFalse(it, texte.contains(it)) }
    }

    @Test fun `only https rules links`() {
        val d = Diplomes.Diplome("x", "X", "Y", Diplomes.Genre.CONTACTS, 10, lienReglement = "javascript:alert(1)", sourceLien = "http://example.org")
        val o = DiplomesSitePc.json(listOf(Diplomes.Bilan(d, Diplomes.Statut.EN_COURS, 2))).getJSONArray("diplomes").getJSONObject(0)
        assertFalse(o.has("reglement"))
    }

    private fun png(w: Int, h: Int, bruit: Boolean = false): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val r = java.util.Random(4)
        for (y in 0 until h) for (x in 0 until w) img.setRGB(x, y, if (bruit) r.nextInt(0xFFFFFF) else (x * 255 / w) shl 8)
        return ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
    }

    @Test fun `a picture as kept, a large one made smaller, a PDF as it is, anything else refused`() {
        val petit = png(400, 300)
        DiplomesSitePc.prepare(petit)!!.let { assertEquals("image/png", it.mime); assertEquals("png", it.ext); assertTrue(it.octets.contentEquals(petit)) }
        val gros = png(3400, 2400, bruit = true)
        assertTrue(gros.size > DiplomesSitePc.MAX_OCTETS)
        DiplomesSitePc.prepare(gros)!!.let { d ->
            assertEquals("image/jpeg", d.mime); assertTrue(d.octets.size <= DiplomesSitePc.MAX_OCTETS)
            val i = ImageIO.read(d.octets.inputStream()); assertTrue(maxOf(i.width, i.height) <= DiplomesSitePc.COTE_MAX)
        }
        val pdf = "%PDF-1.4\n%fin".toByteArray()
        assertEquals("pdf", DiplomesSitePc.prepare(pdf)!!.ext)
        assertNull(DiplomesSitePc.prepare("<html>".toByteArray()))
    }

    private class Envoi(val url: String, val entetes: Map<String, String>, val corps: ByteArray?)

    private fun site(envois: MutableList<Envoi>, reponse: () -> Pair<Int, String> = { 201 to "{\"ok\":true}" }): Pair<SitePc, StockageFichier> {
        val st = StockageFichier(tmp.newFile("r-" + System.nanoTime() + ".properties"))
        val site = SitePc(st, tmp.newFolder(), { false }, { t0 }, SitePc.Transport { _, u, e, c -> envois += Envoi(u, e, c?.octets()); reponse() })
        fr.f4ioz.satcombo.demo.ModulesStation.pose(st, emptySet())
        site.sert("/api/site/essai", "POST") { k -> when (k) { "url" -> "https://example.org/api/publication/{type}"; "jeton" -> "le-jeton"; else -> "" } }
        envois.clear()
        return site to st
    }

    @Test fun `the picture of an award received goes only on the click, as diplome-image`() {
        val envois = ArrayList<Envoi>()
        val (site, _) = site(envois)
        val ds = DiplomesSitePc(tmp.newFile("diplomes-site.json").also { it.delete() }, site, { t0 })
        val f = tmp.newFile("cert.png").also { it.writeBytes(png(200, 150)) }
        assertNull(ds.envoie("cat:amsat-scc", f))
        assertEquals(1, envois.size)
        assertEquals("https://example.org/api/publication/diplome-image", envois[0].url)
        assertEquals("diplome-image", envois[0].entetes["X-SatMe-Type"])
        val corps = String(envois[0].corps!!, Charsets.ISO_8859_1)
        assertTrue(corps.contains("\"diplome\":\"cat:amsat-scc\""))
        assertTrue(corps.contains("Content-Type: image/png"))
        assertEquals(t0, ds.envoyes()["cat:amsat-scc"])
        // Kept across a restart.
        assertEquals(t0, DiplomesSitePc(java.io.File(tmp.root, "diplomes-site.json"), site, { t0 }).envoyes()["cat:amsat-scc"])
        // No picture: nothing sent.
        assertNotNull(ds.envoie("cat:vucc", null))
        assertEquals(1, envois.size)
    }

    @Test fun `a site that does not know diplome-image says so`() {
        val envois = ArrayList<Envoi>()
        var code = 201
        val (site, _) = site(envois) { if (code == 201) 201 to "{\"ok\":true}" else code to "{\"ok\":false,\"erreur\":\"type inconnu\"}" }
        code = 404
        val ds = DiplomesSitePc(java.io.File(tmp.root, "d.json"), site, { t0 })
        val r = ds.envoie("x", tmp.newFile("c.png").also { it.writeBytes(png(20, 20)) })
        assertTrue(r!!.contains("non pris en charge"))
        assertTrue(ds.envoyes().isEmpty())
    }

    @Test fun `the awards go only when chosen, on change, else once a day`() {
        val envois = ArrayList<Envoi>()
        var t = t0
        val st = StockageFichier(tmp.newFile("r-direct.properties"))
        val site = SitePc(st, tmp.newFolder(), { false }, { t }, SitePc.Transport { _, u, e, c -> envois += Envoi(u, e, c?.octets()); 200 to "{\"ok\":true}" })
        fr.f4ioz.satcombo.demo.ModulesStation.pose(st, emptySet())
        site.sert("/api/site/essai", "POST") { k -> when (k) { "url" -> "https://example.org/api/publication/{type}"; "jeton" -> "le-jeton"; else -> "" } }
        envois.clear()
        val d = DirectSitePc(st, site, { t })
        site.direct = d
        var l = bilans()
        d.diplomes = { DiplomesSitePc.json(l) }
        d.tic()
        assertTrue(envois.isEmpty())
        site.sert("/api/site/direct", "POST") { k -> if (k == "on_diplomes") "1" else "" }
        d.tic()
        assertEquals(listOf("https://example.org/api/publication/diplomes"), envois.map { it.url })
        val o = JSONObject(String(envois[0].corps!!))
        assertEquals("diplomes", o.getString("type")); assertEquals(1, o.getInt("version")); assertEquals(t, o.getLong("t"))
        assertEquals(4, o.getJSONArray("diplomes").length())
        // Unchanged: not again the same day.
        t += 3_600_000L; d.tic()
        assertEquals(1, envois.size)
        // Changed: within the minute.
        l = l.take(2)
        t += 60_000L; d.tic()
        assertEquals(2, envois.size)
        t += DirectSitePc.PERIODE_DIPLOMES_MS; d.tic()
        assertEquals(3, envois.size)
        val c = d.json().getJSONArray("canaux").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.first { it.getString("nom") == "diplomes" }
        assertTrue(c.getBoolean("on")); assertTrue(c.getString("cadence").contains("jour"))
        // Off here (« Agenda › Diplômes » not active): nothing.
        d.diplomes = { null }
        t += DirectSitePc.PERIODE_DIPLOMES_MS * 2; d.tic()
        assertEquals(3, envois.size)
    }

    @Test fun `to a folder the awards are diplomes_json and diplomes slash`() {
        val st = StockageFichier(tmp.newFile("r-dossier.properties"))
        val dos = tmp.newFolder("site-dossier")
        val site = SitePc(st, tmp.newFolder(), { false }, { t0 }, SitePc.Transport { _, _, _, _ -> throw AssertionError("pas de web") })
        fr.f4ioz.satcombo.demo.ModulesStation.pose(st, emptySet())
        site.sert("/api/site/essai", "POST") { k -> when (k) { "cible" -> "dossier"; "dossier" -> dos.absolutePath; else -> "" } }
        val d = DirectSitePc(st, site, { t0 })
        site.direct = d
        d.diplomes = { DiplomesSitePc.json(bilans()) }
        site.sert("/api/site/direct", "POST") { k -> if (k == "on_diplomes") "1" else "" }
        d.tic()
        val o = JSONObject(java.io.File(dos, "diplomes.json").readText())
        assertEquals("diplomes", o.getString("type"))
        assertEquals(4, o.getJSONArray("diplomes").length())
        val ds = DiplomesSitePc(java.io.File(tmp.root, "e.json"), site, { t0 })
        assertNull(ds.envoie("cat:amsat-scc", tmp.newFile("c.png").also { it.writeBytes(png(20, 20)) }))
        assertTrue(java.io.File(dos, "diplomes/cat-amsat-scc.png").isFile)
    }
}
