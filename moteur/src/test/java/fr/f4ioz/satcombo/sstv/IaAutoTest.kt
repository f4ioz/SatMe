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
import fr.f4ioz.satcombo.plateforme.StockageMemoire
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

/**
 * « Débruitage IA automatique » ([IaAuto]): its rule, its settings kept in
 * their ranges ([OptionsSstv], [IaSstv.Reglages]), the blend, and which
 * version goes to the web site ([ApiCorrectionSstv.pourSite]). A fake model:
 * nothing learnt is run here.
 */
class IaAutoTest {

    private val d = File(System.getProperty("java.io.tmpdir"), "satme-ia-auto-" + System.nanoTime()).also { it.mkdirs() }
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

    /** Gives every value back halfway to mid-grey (to be seen), counting its tiles. */
    private class Modele : DebruiteurIa {
        override val nom = "essai"; override val id = "essai-1"
        var tuiles = 0
        override fun tuile(rgb: FloatArray, w: Int, h: Int): FloatArray { tuiles++; return FloatArray(rgb.size) { (rgb[it] + 0.5f) / 2f } }
    }

    private fun mire(w: Int = 320, h: Int = 256): IntArray = IntArray(w * h) { i -> val x = i % w; val y = i / w
        when { y in 20..120 && x in 20..300 -> (0xFF shl 24) or ((80 + x / 4) shl 16) or ((60 + y) shl 8) or 150
               y in 190..230 -> { val v = 30 + x * 180 / w; (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
               else -> 0xFF303030.toInt() } }

    private fun bruitee(px: IntArray, sigma: Double): IntArray {
        val g = java.util.Random(3)
        return IntArray(px.size) { i -> val c = px[i]
            fun b(v: Int) = (v + Math.round(g.nextGaussian() * sigma).toInt()).coerceIn(0, 255)
            (0xFF shl 24) or (b((c shr 16) and 255) shl 16) or (b((c shr 8) and 255) shl 8) or b(c and 255) }
    }

    private fun recue(nom: String, px: IntArray): File {
        val f = File(d, nom); ecrit(f, px, 320, 256)
        File(d, SstvMeta.sidecarName(nom)).writeText(SstvMeta.encode(SstvMeta.SstvShot(fileName = nom, satName = "ISS", mode = "Robot36", source = "live")))
        return f
    }

    private val propre = "SatMe_SSTV_ISS_20261024_021207Z_Robot36.png"
    private val bruite = "SatMe_SSTV_ISS_20261024_021407Z_Robot36.png"

    @Test fun `the rule - off by default, the model needed, the noise threshold`() {
        val o = OptionsSstv(StockageMemoire())
        // Never on by itself.
        assertFalse(o.iaAuto)
        assertFalse(IaAuto.Regle().actif)
        assertEquals(IaAuto.COUPE, IaAuto.decide(o.regleIaAuto, true, 20.0))
        o.iaAuto = true
        assertEquals(IaAuto.SANS_MODELE, IaAuto.decide(o.regleIaAuto, false, 20.0))
        // The threshold on by default: a clean picture left alone.
        assertTrue(o.iaSeuilActif)
        assertEquals(IaAuto.SEUIL_DEFAUT, o.iaSeuil, 1e-9)
        assertEquals(IaAuto.PROPRE, IaAuto.decide(o.regleIaAuto, true, 2.0))
        assertEquals(IaAuto.FAITE, IaAuto.decide(o.regleIaAuto, true, IaAuto.SEUIL_DEFAUT))
        o.iaSeuilActif = false
        assertEquals(IaAuto.FAITE, IaAuto.decide(o.regleIaAuto, true, 0.0))
    }

    @Test fun `the settings kept in their ranges`() {
        val o = OptionsSstv(StockageMemoire())
        assertEquals(IaSstv.Reglages(), o.reglagesIa)
        o.iaForce = 140; assertEquals(100, o.iaForce)
        o.iaForce = -5; assertEquals(0, o.iaForce)
        o.iaTuile = 4000; assertEquals(IaSstv.TUILE_MAX, o.iaTuile)
        o.iaTuile = 10; assertEquals(IaSstv.TUILE_MIN, o.iaTuile)
        // A multiple of 64.
        o.iaTuile = 500; assertEquals(448, o.iaTuile)
        // The overlap at most a quarter of a tile, at least 8.
        o.iaRecouvrement = 500; assertEquals(112, o.iaRecouvrement)
        o.iaRecouvrement = 1; assertEquals(IaSstv.RECOUVREMENT_MIN, o.iaRecouvrement)
        o.iaSeuil = 99.0; assertEquals(IaAuto.SEUIL_MAX, o.iaSeuil, 1e-9)
        o.iaSeuil = -1.0; assertEquals(0.0, o.iaSeuil, 1e-9)
        o.iaSeuil = 4.25; assertEquals(4.3, o.iaSeuil, 1e-9)
        o.iaFils = 999; assertEquals(OptionsSstv.IA_FILS_MAX, o.iaFils)
        o.iaFils = -2; assertEquals(0, o.iaFils)
        assertEquals(IaSstv.Reglages(100, 1024, 256, false), IaSstv.Reglages(300, 9999, 9999).borne())
        assertEquals(IaSstv.Reglages(0, 128, 8, true), IaSstv.Reglages(-1, 1, 1, true).borne())
    }

    @Test fun `the blend - 0 % is the picture given, the model not run, 50 % halfway`() {
        val px = bruitee(mire(), 10.0)
        val m = Modele()
        assertArrayEquals(px, IaSstv.applique(m, px, 320, 256, IaSstv.Reglages(force = 0)))
        assertEquals(0, m.tuiles)
        val plein = IaSstv.applique(m, px, 320, 256, IaSstv.Reglages(force = 100))
        val moitie = IaSstv.applique(m, px, 320, 256, IaSstv.Reglages(force = 50))
        for (i in px.indices step 97) {
            val a = (px[i] shr 8) and 0xFF; val b = (plein[i] shr 8) and 0xFF; val c = (moitie[i] shr 8) and 0xFF
            assertTrue("$a $b $c", kotlin.math.abs(c - (a + b) / 2.0) <= 1.0)
        }
        // Smaller tiles: more of them, every pixel still covered.
        val m2 = Modele()
        val t = IaSstv.applique(m2, px, 320, 256, IaSstv.Reglages(tuile = 128, recouvrement = 16))
        assertTrue(m2.tuiles > 1)
        for (i in px.indices step 97) assertEquals((plein[i] shr 8) and 0xFF, (t[i] shr 8) and 0xFF)
    }

    @Test fun `automatic - a noisy picture denoised and marked, a clean one left, an owner's switch kept`() {
        recue(propre, mire())
        recue(bruite, bruitee(mire(), 14.0))
        var regle = IaAuto.Regle(actif = true)
        val faits = ArrayList<Pair<String, String>>()
        val a = IaAuto(r, { regle }, { it.run() }) { png, i -> faits += png to i }
        // No model: nothing queued.
        assertFalse(a.demande(d, bruite))
        r.ia = Modele()
        assertTrue(r.bruit(File(d, bruite))!! >= IaAuto.SEUIL_DEFAUT)
        assertTrue(r.bruit(File(d, propre))!! < IaAuto.SEUIL_DEFAUT)
        assertTrue(a.demande(d, propre))
        assertTrue(a.demande(d, bruite))
        assertEquals(listOf(propre to IaAuto.PROPRE, bruite to IaAuto.FAITE), faits)
        assertFalse(r.effet(d, propre).ia)
        val reg = r.reglage(d, bruite)
        assertTrue(reg.iaAuto); assertNull(reg.ia)
        assertTrue(r.effet(d, bruite).ia)
        assertNotNull(r.existante(d, File(d, bruite), r.effet(d, bruite)))
        // Unticked in the viewer: stays without, and the automatic IA leaves it.
        r.change(d, bruite, reg.copy(ia = false))
        assertFalse(r.effet(d, bruite).ia)
        assertEquals(IaAuto.CHOISIE, a.traite(d, bruite).first)
        // Kept as written: « ia=0 » and the automatic mark.
        assertEquals(RetouchesGalerie.Reglage(ia = false, iaAuto = true), RetouchesGalerie.decodeReglage(RetouchesGalerie.encodeReglage(RetouchesGalerie.Reglage(ia = false, iaAuto = true))))
        // Switched off: nothing queued.
        regle = IaAuto.Regle(actif = false)
        assertFalse(a.demande(d, propre))
    }

    @Test fun `the IA's settings in its sidecar - another strength or order makes it again`() {
        val f = recue(bruite, bruitee(mire(), 14.0))
        r.ia = Modele()
        val e = RetouchesGalerie.Effet(true, CouleurSstv.Options(), true, IaSstv.Reglages(force = 80))
        val fin = r.finie(d, f, e)!!
        assertEquals(IaSstv.Reglages(force = 80).code(), fin.fiche.iaCode)
        assertNotNull(r.existante(d, f, e))
        assertNull(r.existante(d, f, RetouchesGalerie.Effet(true, CouleurSstv.Options(), true, IaSstv.Reglages(force = 60))))
        assertNull(r.existante(d, f, RetouchesGalerie.Effet(true, CouleurSstv.Options(), true, IaSstv.Reglages(force = 80, avantCouleur = true))))
        val avant = r.finie(d, f, RetouchesGalerie.Effet(true, CouleurSstv.Options(), true, IaSstv.Reglages(avantCouleur = true)))!!
        assertTrue(avant.fiche.iaCode.endsWith("a"))
        // An older sidecar without settings: taken as made with the defaults.
        assertEquals(IaSstv.Reglages().code(), RetouchesGalerie.decode("source=x.png\nia=essai-1\n")!!.iaCode)
        // The trial: without and with, nothing written.
        val n = CorrectionsGalerie.dossier(d).listFiles()!!.size
        val ap = r.apercu(f, RetouchesGalerie.Effet(false, CouleurSstv.Options(), true), IaSstv.Reglages(force = 100))!!
        assertNotEquals(ap.sans.toList(), ap.avec.toList())
        assertArrayEquals(charge(f)!!.px, ap.sans)
        assertEquals(n, CorrectionsGalerie.dossier(d).listFiles()!!.size)
    }

    @Test fun `the version for the site - chosen when there is one, else as received`() {
        val f = recue(bruite, CouleurSstv.simule(bruitee(mire(), 14.0), 320, 256, hz = 25.0))
        val api = ApiCorrectionSstv(CorrectionsGalerie(::charge, ::ecrit), { d }, { listOf(SstvMeta.decode(bruite, File(d, SstvMeta.sidecarName(bruite)).readText())) }, { emptyList() })
        val s = SstvMeta.decode(bruite, null)
        // Without the finish: as received, whatever is chosen.
        ApiCorrectionSstv.VersionSite.values().forEach { assertEquals(f, api.pourSite(bruite, f to s, it).fichier) }
        api.retouches = r
        val recue = api.pourSite(bruite, f to s, ApiCorrectionSstv.VersionSite.RECUE)
        assertEquals(f, recue.fichier); assertEquals("", recue.retouche)
        // The colour: made now, said « couleurs ».
        val c = api.pourSite(bruite, f to s, ApiCorrectionSstv.VersionSite.COULEURS)
        assertTrue(c.fichier.name.endsWith("_couleur.png")); assertEquals("couleurs", c.retouche); assertFalse(c.shot.retoucheIa)
        // The IA chosen, no IA asked or made: as received (never computed for the site).
        r.ia = Modele()
        val sans = api.pourSite(bruite, f to s, ApiCorrectionSstv.VersionSite.IA)
        assertEquals(f, sans.fichier); assertEquals("", sans.retouche)
        assertFalse(File(CorrectionsGalerie.dossier(d), RetouchesGalerie.nomFinie(bruite, false, true)).exists())
        // Made by the automatic IA: that one, « ia », its model.
        IaAuto(r, { IaAuto.Regle(actif = true) }, { it.run() }).demande(d, bruite)
        val ia = api.pourSite(bruite, f to s, ApiCorrectionSstv.VersionSite.IA)
        assertTrue(ia.fichier.name.endsWith("_ia.png")); assertEquals("ia", ia.retouche); assertEquals("essai", ia.modeleIa); assertTrue(ia.shot.retoucheIa)
        // Its settings changed since: not up to date, as received until made again.
        defauts = RetouchesGalerie.Defauts(ia = IaSstv.Reglages(force = 50))
        assertEquals(f, api.pourSite(bruite, f to s, ApiCorrectionSstv.VersionSite.IA).fichier)
        assertEquals(ApiCorrectionSstv.VersionSite.RECUE, ApiCorrectionSstv.VersionSite.de("?"))
    }
}
