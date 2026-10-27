/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site

import fr.f4ioz.site.carnet.Qso
import fr.f4ioz.site.publication.Publications
import fr.f4ioz.site.publication.TypePublication
import fr.f4ioz.site.publication.Vignettes
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
import javax.imageio.ImageIO

class PublicationsTest {

    @get:Rule val tmp = TemporaryFolder()

    companion object {
        fun png(w: Int = 320, h: Int = 256, couleur: Int = 0x3FE0C8): ByteArray {
            val i = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until h) for (x in 0 until w) i.setRGB(x, y, if ((x / 16 + y / 16) % 2 == 0) couleur else 0x0A0F14)
            return ByteArrayOutputStream().also { ImageIO.write(i, "png", it) }.toByteArray()
        }
    }

    @Test fun `a picture is published once, with its thumbnail`() {
        val p = Publications(tmp.newFolder("p"))
        val b = png()
        val r = p.publie(TypePublication.SSTV, b, JSONObject().put("satellite", "ISS").put("utc", "2026-10-03T13:51:19Z").put("mode", "Robot36"))
        assertTrue(r.nouvelle)
        assertEquals(16, r.p.id.length)
        assertEquals("fichier.png", r.p.fichier)
        assertEquals(320, r.p.largeur)
        assertNotNull(p.vignetteDe(r.p))
        assertEquals("ISS", r.p.groupe)
        assertEquals(1791035479000L, r.p.quandMs)
        // The same picture again: the same publication, nothing written twice.
        val r2 = p.publie(TypePublication.SSTV, b, JSONObject().put("satellite", "autre"))
        assertFalse(r2.nouvelle)
        assertEquals(r.p.id, r2.p.id)
        assertEquals(1, p.toutes().size)
        // Read back from the disk.
        val p2 = Publications(tmp.root.resolve("p")); p2.charge()
        assertEquals("ISS", p2.parId(r.p.id)!!.satellite)
    }

    @Test fun `a new version of the same key replaces the old`() {
        val p = Publications(tmp.newFolder("p"))
        val a = p.publie(TypePublication.PLANCHE, png(couleur = 0xFF0000), JSONObject().put("cle", "planche:pmvl8cltc"))
        val b = p.publie(TypePublication.PLANCHE, png(couleur = 0x00FF00), JSONObject().put("cle", "planche:pmvl8cltc"))
        assertEquals(a.p.id, b.remplacee)
        assertNull(p.parId(a.p.id))
        assertEquals(listOf(b.p.id), p.toutes().map { it.id })
        assertFalse(tmp.root.resolve("p/" + a.p.id).exists())
    }

    @Test fun `what is not of its type, or too large, is refused`() {
        val p = Publications(tmp.newFolder("p"))
        fun refus(statut: Int, f: () -> Unit) { try { f(); fail("accepté") } catch (e: Publications.Refus) { assertEquals(statut, e.statut) } }
        refus(415) { p.publie(TypePublication.SSTV, "<html>pas une image</html>".toByteArray(), JSONObject()) }
        refus(415) { p.publie(TypePublication.SSTV, "%PDF-1.7 rapport".toByteArray(), JSONObject()) }
        refus(400) { p.publie(TypePublication.SSTV, ByteArray(0), JSONObject()) }
        refus(413) { p.publie(TypePublication.QSO, ByteArray((1 shl 20) + 1) { 'a'.code.toByte() }, JSONObject()) }
        refus(415) { p.publie(TypePublication.QSO, "bonjour".toByteArray(), JSONObject()) }
        assertTrue(p.toutes().isEmpty())
    }

    @Test fun `a picture whose header announces too many pixels is refused before it is decoded`() {
        // A PNG header saying 30000 × 30000, nothing behind it.
        val b = png(8, 8)
        val ihdr = 16
        fun put(o: Int, v: Int) { b[o] = (v ushr 24).toByte(); b[o + 1] = (v ushr 16).toByte(); b[o + 2] = (v ushr 8).toByte(); b[o + 3] = v.toByte() }
        put(ihdr, 30_000); put(ihdr + 4, 30_000)
        assertEquals(30_000 to 30_000, Vignettes.dimensions(b))
        assertFalse(Vignettes.imageAcceptable(b))
        assertNull(Vignettes.vignette(b))
    }

    @Test fun `the kinds are told by their first bytes`() {
        assertEquals("png", Vignettes.genre(png()))
        assertEquals("pdf", Vignettes.genre("%PDF-1.4\n".toByteArray()))
        assertEquals("mp4", Vignettes.genre(byteArrayOf(0, 0, 0, 0x18) + "ftypisom".toByteArray()))
        assertNull(Vignettes.genre("GIF8".toByteArray()))
        val v = Vignettes.vignette(png(1600, 1200))!!
        val i = ImageIO.read(v.inputStream())
        assertEquals(480, i.width); assertEquals(360, i.height)
    }

    @Test fun `a report in HTML, a contact in ADIF or JSON`() {
        val p = Publications(tmp.newFolder("p"))
        assertEquals("fichier.html", p.publie(TypePublication.RAPPORT, "<!doctype html><title>R</title>".toByteArray(), JSONObject()).p.fichier)
        val adif = "<CALL:5>F4XYZ<QSO_DATE:8>20261003<TIME_ON:6>135119<SAT_NAME:3>ISS<MODE:2>FM<GRIDSQUARE:6>JN18AB<BAND:3>2m<EOR>"
        val q = p.publie(TypePublication.QSO, adif.toByteArray(), JSONObject())
        assertEquals("fichier.adif", q.p.fichier)
        val l = Qso.adif(adif, "satme")
        assertEquals("F4XYZ", l.single().indicatif)
        assertEquals("JN18", l.single().carre)
        assertEquals("ISS", l.single().satellite)
        val j = Qso.json("""{"indicatif":"ea4abc","utc":"2026-10-03T13:51:00Z","satellite":"SO-50","mode":"FM","locator":"in80dd"}""")
        assertEquals("EA4ABC", j.single().indicatif); assertEquals("IN80", j.single().carre)
    }

    @Test fun `the sender's words are kept short and flat`() {
        val p = Publications(tmp.newFolder("p"))
        val m = JSONObject().put("satellite", "ISS").put("long", "x".repeat(2000)).put("objet", JSONObject().put("a", 1)).put("../cle", "x").put("n", 3)
        val r = p.publie(TypePublication.SSTV, png(), m)
        assertEquals(500, r.p.meta.getString("long").length)
        assertFalse(r.p.meta.has("objet"))
        assertFalse(r.p.meta.has("../cle"))
        assertEquals(3, r.p.meta.getInt("n"))
    }
}
