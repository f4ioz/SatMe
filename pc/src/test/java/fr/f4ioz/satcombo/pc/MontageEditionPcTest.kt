/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.station.ActiviteMontage
import fr.f4ioz.satcombo.station.DetectionMontage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO

class MontageEditionPcTest {

    private fun montage(d: File = Files.createTempDirectory("montage").toFile()) = MontagePc(d, { ActiviteMontage.Lectures(
        etat = JSONObject("""{"cat":{"connecte":true,"modele":"IC9700"}}"""), boites = JSONObject("""{"wavelog":{"url":true,"cle":true}}""")) },
        systeme = { listOf(DetectionMontage.Usb("0456", "b673", "PlutoSDR")) to emptyList() })

    private fun MontagePc.appel(route: String, methode: String = "GET", corps: ByteArray? = null, vararg q: Pair<String, String>): JSONObject {
        val m = q.toMap()
        return JSONObject(String(sert(route, methode, { m[it].orEmpty() }) { corps }.corps, Charsets.UTF_8))
    }

    @Test fun lesGestesSEnregistrentEtSAnnulent() {
        val d = Files.createTempDirectory("montage").toFile()
        val m = montage(d)
        val a = m.appel("/edition", "POST", null, "op" to "ajoute", "m" to "dxp-gpsdo", "x" to "40", "y" to "40")
        assertTrue(a.toString(), a.getBoolean("ok"))
        val b = m.appel("/edition", "POST", null, "op" to "ajoute", "m" to "dxp-up-mk4", "x" to "300", "y" to "40")
        m.appel("/edition", "POST", null, "op" to "relie", "de" to a.getString("id") + ".s1", "vers" to b.getString("id") + ".ref")
        var e = m.appel("")
        assertEquals(1, e.getJSONObject("chaine").getJSONArray("liens").length())
        assertEquals("REFERENCE", e.getJSONObject("chaine").getJSONArray("liens").getJSONObject(0).getString("c"))
        assertTrue(e.getJSONObject("plan").getBoolean("libre"))
        assertTrue(e.getJSONObject("historique").getBoolean("annuler"))
        // Refused: the chain unchanged, nothing to undo added.
        assertFalse(m.appel("/edition", "POST", null, "op" to "relie", "de" to a.getString("id") + ".s2", "vers" to b.getString("id") + ".ref").getBoolean("ok"))
        assertFalse(m.appel("/edition", "GET", null, "op" to "auto").getBoolean("ok"))
        assertTrue(m.appel("/annule", "POST").getBoolean("ok"))
        e = m.appel("")
        assertEquals(0, e.getJSONObject("chaine").getJSONArray("liens").length())
        assertTrue(e.getJSONObject("historique").getBoolean("refaire"))
        assertTrue(m.appel("/refait", "POST").getBoolean("ok"))
        // Kept in the data folder: a new instance reads it back.
        assertEquals(1, montage(d).appel("").getJSONObject("chaine").getJSONArray("liens").length())
    }

    @Test fun laPropositionSeLitEtSeFusionne() {
        val m = montage()
        val p = m.appel("/detection")
        assertTrue(p.getBoolean("ok"))
        val modeles = (0 until p.getJSONObject("chaine").getJSONArray("elements").length()).map { p.getJSONObject("chaine").getJSONArray("elements").getJSONObject(it).getString("m") }
        assertTrue(modeles.toString(), "pluto" in modeles && "ic9700" in modeles && "boite-wavelog" in modeles)
        assertEquals("0456:b673", p.getJSONArray("usb").getJSONObject(0).getString("id"))
        val ids = JSONArray((0 until p.getJSONArray("pieces").length()).map { p.getJSONArray("pieces").getJSONObject(it).getString("id") })
        val corps = JSONObject().put("chaine", p.getJSONObject("chaine")).put("garde", ids).toString().toByteArray()
        val f = m.appel("/fusion", "POST", corps)
        assertTrue(f.toString(), f.getBoolean("ok"))
        assertEquals(modeles.size, f.getInt("ajoutes"))
        // Asked again: everything is already drawn.
        assertEquals(modeles.size, m.appel("/detection").getJSONArray("deja").length())
    }

    /** The owner's own photo of his IC-9700 (product picture on white), when it is on this PC: read only. */
    private val PHOTO = File(System.getProperty("user.home"), ".satme/montage-photos/e4-ic9700.jpg")

    @Test fun laPhotoDeLIc9700SeDetoure() {
        assumeTrue(PHOTO.isFile)
        val (r, png) = MontagePc.decoupe(PHOTO.readBytes(), null)!!
        assertTrue(r.uniforme)
        val img = ImageIO.read(png.inputStream())
        assertEquals(r.l, img.width)
        fun a(x: Int, y: Int) = img.getRGB(x, y) ushr 24
        // The corners clear.
        for ((x, y) in listOf(0 to 0, img.width - 1 to 0, 0 to img.height - 1, img.width - 1 to img.height - 1)) assertEquals("$x,$y", 0, a(x, y))
        // Most of the radio's body opaque: the middle band of the cut-out.
        var n = 0; var opaques = 0
        for (y in img.height * 3 / 10 until img.height * 7 / 10) for (x in img.width / 5 until img.width * 4 / 5) { n++; if (a(x, y) == 255) opaques++ }
        assertTrue("opaques : $opaques / $n", opaques > n * 0.9)
    }

    @Test fun unePhotoEnvoyeeEstDetoureeEtServie() {
        val d = Files.createTempDirectory("montage").toFile()
        val m = montage(d)
        val id = m.appel("/edition", "POST", null, "op" to "ajoute", "m" to "ic9700").getString("id")
        // A grey box on white, as a PNG.
        val img = java.awt.image.BufferedImage(120, 80, java.awt.image.BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 80) for (x in 0 until 120) img.setRGB(x, y, if (x in 30..90 && y in 20..60) 0x404850 else 0xFFFFFF)
        val o = java.io.ByteArrayOutputStream(); ImageIO.write(img, "png", o)
        val r = m.appel("/photo", "POST", o.toByteArray(), "id" to id)
        assertTrue(r.toString(), r.getBoolean("detoure"))
        val e = m.appel("")
        assertEquals(id, e.getJSONArray("detoures").getString(0))
        assertTrue(e.getJSONObject("detourage").getJSONObject(id).getBoolean("uniforme"))
        val png = m.sert("/photo", "GET", { mapOf("id" to id, "detoure" to "1")[it].orEmpty() }) { null }
        assertEquals("image/png", png.type)
        assertEquals(0, ImageIO.read(png.corps.inputStream()).getRGB(0, 0) ushr 24)
        // Done again with another tolerance; the photo in place of the drawing.
        assertEquals(50, m.appel("/photo", "POST", null, "id" to id, "detourage" to "1", "tolerance" to "50").getInt("tolerance"))
        m.appel("/edition", "POST", null, "op" to "photo", "id" to id, "on" to "1")
        assertTrue(m.appel("").getJSONObject("chaine").getJSONArray("elements").getJSONObject(0).getBoolean("ph"))
        // The photo removed: its cut-out too.
        m.appel("/photo", "POST", null, "id" to id, "retire" to "1")
        assertEquals(0, m.appel("").getJSONArray("detoures").length())
    }
}
