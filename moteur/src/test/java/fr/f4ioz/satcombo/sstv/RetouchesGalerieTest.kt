/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

import fr.f4ioz.satcombo.demo.ApiCorrectionSstv
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** The pictures' finish kept apart ([RetouchesGalerie]), the IA's tiles ([IaSstv]), and where it goes out ([ApiCorrectionSstv]). */
class RetouchesGalerieTest {

    private val d = File(System.getProperty("java.io.tmpdir"), "satme-retouches-" + System.nanoTime()).also { it.mkdirs() }
    @After fun nettoie() { d.deleteRecursively() }

    private fun charge(f: File): ClassementGalerie.Pixels? = runCatching {
        val b = ImageIO.read(f) ?: return null
        ClassementGalerie.Pixels(b.getRGB(0, 0, b.width, b.height, null, 0, b.width), b.width, b.height)
    }.getOrNull()
    private fun ecrit(f: File, px: IntArray, w: Int, h: Int) = runCatching {
        ImageIO.write(BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).also { it.setRGB(0, 0, w, h, px, 0, w) }, "png", f)
    }.getOrDefault(false)

    private var defauts = RetouchesGalerie.Defauts()
    private val r = RetouchesGalerie(::charge, ::ecrit, { defauts }) { 1_800_000_000_000L }

    /** A model that only counts its calls and gives the picture back (darker by one level, to be seen). */
    private class Modele : DebruiteurIa {
        override val nom = "essai"; override val id = "essai-1"
        val tailles = ArrayList<Pair<Int, Int>>()
        override fun tuile(rgb: FloatArray, w: Int, h: Int): FloatArray { tailles += w to h; return FloatArray(rgb.size) { (rgb[it] - 1f / 255f).coerceAtLeast(0f) } }
    }

    private fun mire(w: Int = 320, h: Int = 256): IntArray = IntArray(w * h) { i -> val x = i % w; val y = i / w
        when { y in 20..120 && x in 20..300 -> (0xFF shl 24) or ((80 + x / 4) shl 16) or ((60 + y) shl 8) or 150
               y in 150..170 && (x / 6) % 3 != 2 -> 0xFFF0F0F0.toInt()
               y in 190..230 -> { val v = 30 + x * 180 / w; (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
               else -> 0xFF101010.toInt() } }

    private val nom = "SatMe_SSTV_ISS_20261024_021207Z_Robot36.png"

    private fun recue(px: IntArray = CouleurSstv.simule(mire(), 320, 256, hz = 25.0)): File {
        val f = File(d, nom); ecrit(f, px, 320, 256)
        File(d, SstvMeta.sidecarName(nom)).writeText(SstvMeta.encode(SstvMeta.SstvShot(fileName = nom, satName = "ISS", mode = "Robot36", source = "live")))
        return f
    }

    @Test fun `switches kept picture by picture, over the defaults`() {
        recue()
        assertFalse(r.effet(d, nom).actif)
        assertTrue(r.change(d, nom, RetouchesGalerie.Reglage(couleur = true, saturation = true)))
        val e = r.effet(d, nom)
        assertTrue(e.couleur); assertTrue(e.options.saturation); assertTrue(e.options.dominante); assertFalse(e.ia)
        defauts = RetouchesGalerie.Defauts(couleur = true, options = CouleurSstv.Options(chroma = false))
        assertFalse(r.effet(d, nom).options.chroma)
        assertTrue(r.change(d, nom, RetouchesGalerie.Reglage()))
        assertFalse(File(CorrectionsGalerie.dossier(d), nom.removeSuffix(".png") + ".retouche").exists())
        assertTrue(r.effet(d, nom).couleur)
        assertEquals(RetouchesGalerie.Reglage(couleur = false, chroma = true, ia = true),
            RetouchesGalerie.decodeReglage(RetouchesGalerie.encodeReglage(RetouchesGalerie.Reglage(couleur = false, chroma = true, ia = true))))
    }

    @Test fun `the finished version is apart, the received one untouched, made again when its source changes`() {
        val f = recue()
        val avant = f.readBytes()
        val e = RetouchesGalerie.Effet(true, CouleurSstv.Options(), false)
        val fin = r.finie(d, f, e)
        assertNotNull(fin)
        assertEquals(CorrectionsGalerie.DOSSIER, fin!!.fichier.parentFile.name)
        assertEquals(nom.removeSuffix(".png") + "_couleur.png", fin.fichier.name)
        assertTrue(fin.fiche.plan.dominante)
        assertArrayEquals(avant, f.readBytes())
        assertTrue(CorrectionsGalerie.nomValide(fin.fichier.name))
        assertTrue(CorrectionsGalerie.estTraitee(fin.fichier))
        // The same asked again: the file as it is.
        assertNotNull(r.existante(d, f, e))
        // Other steps: made again.
        assertNull(r.existante(d, f, RetouchesGalerie.Effet(true, CouleurSstv.Options(niveaux = false), false)))
        // Its source changed (decoded again): made again.
        ecrit(f, mire(), 320, 256); f.setLastModified(f.lastModified() + 5_000)
        assertNull(r.existante(d, f, e))
        val fin2 = r.finie(d, f, e)!!
        assertFalse(fin2.fiche.plan.dominante)
        RetouchesGalerie.oublie(d, nom)
        assertFalse(fin2.fichier.exists())
    }

    @Test fun `the IA only with a model, after the colour, said in the sidecar`() {
        val f = recue()
        val e = RetouchesGalerie.Effet(true, CouleurSstv.Options(), true)
        // No model: the colour only.
        assertEquals(nom.removeSuffix(".png") + "_couleur.png", r.finie(d, f, e)!!.fichier.name)
        val m = Modele(); r.ia = m
        val fin = r.finie(d, f, e)!!
        assertEquals(nom.removeSuffix(".png") + "_couleur_ia.png", fin.fichier.name)
        assertEquals("essai-1", fin.fiche.ia)
        assertEquals(listOf(320 to 256), m.tailles)
        // Asked without the IA (a quick look): not made.
        assertNull(r.finie(d, f, RetouchesGalerie.Effet(false, CouleurSstv.Options(), true), avecIa = false))
    }

    @Test fun `tiles a multiple of 64, mirrored, put back whole`() {
        val m = Modele()
        val px = mire(320, 240)
        val out = IaSstv.applique(m, px, 320, 240)
        assertEquals(listOf(320 to 256), m.tailles)
        for (i in px.indices) assertEquals(((px[i] shr 8) and 0xFF) - 1, (out[i] shr 8) and 0xFF)
        // A large picture (PD 290, 800×616): several tiles, every pixel covered.
        val m2 = Modele()
        val grand = mire(800, 616)
        val o2 = IaSstv.applique(m2, grand, 800, 616)
        assertTrue(m2.tailles.size > 1)
        assertTrue(m2.tailles.all { (w, h) -> w % 64 == 0 && h % 64 == 0 })
        for (i in grand.indices) assertEquals(((grand[i] shr 8) and 0xFF) - 1, (o2[i] shr 8) and 0xFF)
    }

    @Test fun `a sheet and the site take the finished version, « IA » said`() {
        val f = recue()
        val api = ApiCorrectionSstv(CorrectionsGalerie(::charge, ::ecrit), { d }, { listOf(SstvMeta.decode(nom, File(d, SstvMeta.sidecarName(nom)).readText())) }, { emptyList() })
        val s = SstvMeta.decode(nom, null)
        // Without the finish: as it is.
        assertEquals(f, api.retouchee(nom, f to s).first)
        api.retouches = r
        assertEquals(f, api.retouchee(nom, f to s).first)
        r.change(d, nom, RetouchesGalerie.Reglage(couleur = true))
        val (fc, sc) = api.retouchee(nom, f to s)
        assertNotEquals(f, fc)
        assertFalse(sc.retoucheIa)
        assertTrue(sc.traitement.isNotEmpty())
        r.ia = Modele()
        r.change(d, nom, RetouchesGalerie.Reglage(couleur = true, ia = true))
        val (fi, si) = api.retouchee(nom, f to s)
        assertTrue(fi.name.endsWith("_couleur_ia.png"))
        assertTrue(si.retoucheIa)
        // The legend under a picture says « IA » even when the sheet does not mark processed pictures.
        assertTrue(Planche.legende(si, "F4IOZ", avecTraitement = false).endsWith(fr.f4ioz.satcombo.i18n.t("sstv_planche_ia")))
        // The viewer's analysis: what was found.
        val j = api.retouche(nom, "brute")
        assertTrue(j.getBoolean("ok"))
        assertTrue(j.getJSONObject("analyse").getJSONObject("dominante").getBoolean("sure"))
        assertEquals(25.0, j.getJSONObject("analyse").getJSONObject("dominante").getDouble("accordHz"), 6.0)
        assertTrue(j.getString("couleur").contains("_couleur.png"))
    }
}
