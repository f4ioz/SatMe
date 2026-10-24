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
import fr.f4ioz.satcombo.sstv.ClassementSstv.Origine
import fr.f4ioz.satcombo.sstv.Planche.ChoixCase
import fr.f4ioz.satcombo.sstv.Planche.Version
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** A box's version chosen by hand (Auto, received, corrected, fused): kept, carried, drawn, gone back to Auto when its file is gone. */
class PlancheVersionsTest {

    private val d = File(System.getProperty("java.io.tmpdir"), "satme-versions-" + System.nanoTime()).also { it.mkdirs() }
    @After fun nettoie() { d.deleteRecursively() }

    private fun shot(nom: String, t: Long = 1_792_681_800_000L) = SstvMeta.SstvShot(fileName = nom, satName = "ISS", timeMs = t, mode = "Robot36")
    private fun groupe(id: String, diapo: Int, vararg im: Pair<SstvMeta.SstvShot, Int>) =
        ClassementSstv.Groupe(id, im.sortedByDescending { it.second }.map { it.first }, diapo, 12, Origine.LU, im.associate { it.first.fileName to it.second })

    @Test fun gardee_case_par_case_et_une_ancienne_planche_est_en_auto() {
        val m = Planche.genereGrille("p", "P").copy(images = mapOf(0 to "a.png", 2 to "c.png"),
            versions = mapOf(0 to ChoixCase(Version.FUSION), 2 to ChoixCase(Version.RECUE, copieMain = true), 5 to ChoixCase(Version.CORRIGEE)))
        val lu = Planche.lit("p", Planche.ecrit(m))!!
        assertEquals(m.versions, lu.versions)
        assertEquals(Version.FUSION, Planche.version(lu, 0)); assertEquals(Version.AUTO, Planche.version(lu, 1))
        // An older sheet: no line for it, every box Auto; Auto itself never written.
        val ancienne = Planche.ecrit(m.copy(versions = emptyMap()))
        assertFalse(ancienne.contains("versioncase"))
        assertTrue(Planche.lit("p", ancienne)!!.versions.isEmpty())
        assertFalse(Planche.ecrit(m.copy(versions = mapOf(1 to ChoixCase()))).contains("versioncase"))
        // A line it cannot read, or for a box not there, left aside.
        assertTrue(Planche.lit("p", ancienne + "versioncase=3;nimporte;0\nversioncase=99;fusion;0\n")!!.versions.isEmpty())
        // Back to Auto by hand: the box's entry gone.
        assertTrue(Planche.avecVersion(Planche.avecVersion(m.copy(versions = emptyMap()), 4, Version.FUSION), 4, Version.AUTO).versions.isEmpty())
    }

    @Test fun le_modele_exporte_garde_les_versions_pas_les_images() {
        val m = Planche.genereGrille("p", "P").copy(images = mapOf(0 to "a.png"),
            versions = mapOf(0 to ChoixCase(Version.FUSION, copieMain = true), 1 to ChoixCase(Version.RECUE, copieMain = true)))
        val x = PlancheFichier.sansPersonnel(m)
        assertTrue(x.images.isEmpty())
        assertEquals(mapOf(0 to ChoixCase(Version.FUSION), 1 to ChoixCase(Version.RECUE)), x.versions)
        val r = Planche.Rangement(File(d, "planches"))
        val lu = PlancheFichier.lit(PlancheFichier.exporte(m, r, { null }))
        assertTrue(lu.modele.images.isEmpty()); assertTrue(lu.images.isEmpty())
        assertEquals(x.versions, lu.modele.versions)
    }

    @Test fun remplir_par_diapo_garde_les_choix_a_la_main() {
        val a1 = shot("a1.png"); val a2 = shot("a2.png"); val b = shot("b.png"); val c = shot("c.png")
        val gs = listOf(groupe("g1", 1, a1 to 90, a2 to 70), groupe("g2", 2, b to 80), groupe("g3", 3, c to 85))
        val m0 = Planche.remplitParDiapo(Planche.genereGrille("p", "P"), gs).modele
        assertEquals(mapOf(0 to "a1.png", 1 to "b.png", 2 to "c.png"), m0.images)
        // Box 1: copy a2 chosen by hand, fused; box 3: received; box 7 (empty): corrected, awaiting its slide.
        val m1 = Planche.avecVersion(Planche.avecVersion(Planche.avecVersion(Planche.avecCopie(m0, 0, "a2.png"), 0, Version.FUSION), 2, Version.RECUE), 6, Version.CORRIGEE)
        val r = Planche.remplitParDiapo(m1, gs)
        assertEquals("a2.png", r.modele.images[0])
        assertEquals(ChoixCase(Version.FUSION, copieMain = true), r.modele.versions[0])
        assertEquals(Version.RECUE, Planche.version(r.modele, 2))
        assertEquals(Version.CORRIGEE, Planche.version(r.modele, 6))
        assertEquals(3, r.gardes)
        // Slide 3 renumbered 4: its choice goes with it to box 4; the copy chosen by hand no longer of slide 1
        // (no number now): the best comes in, the version kept.
        val gs2 = listOf(groupe("g1", 1, a1 to 90), ClassementSstv.Groupe("gx", listOf(a2), 0, 12, Origine.AUCUNE, mapOf("a2.png" to 70)),
            groupe("g2", 2, b to 80), groupe("g3", 4, c to 85))
        val r2 = Planche.remplitParDiapo(m1, gs2)
        assertEquals("a1.png", r2.modele.images[0])
        assertEquals(ChoixCase(Version.FUSION), r2.modele.versions[0])
        assertEquals("c.png", r2.modele.images[3]); assertEquals(Version.RECUE, Planche.version(r2.modele, 3))
        assertEquals(Version.AUTO, Planche.version(r2.modele, 2))
        // « Tout réinitialiser »: everything back to Auto, the best copies.
        val r3 = Planche.remplitParDiapo(m1, gs, reinitialise = true)
        assertTrue(r3.modele.versions.isEmpty()); assertEquals(0, r3.gardes)
        assertEquals("a1.png", r3.modele.images[0])
        // Swapped boxes: the choices go with their pictures.
        val e = Planche.echange(m1, 0, 1)
        assertEquals(Version.FUSION, Planche.version(e, 1)); assertEquals(Version.AUTO, Planche.version(e, 0))
    }

    @Test fun ajustee_les_choix_suivent_leurs_images() {
        val m = Planche.ajuste(Planche.genereGrille("p", "P"), listOf("a.png", "b.png", "c.png"))
        val v = Planche.avecVersion(m, 1, Version.FUSION)
        val n = Planche.bascule(v, "a.png")
        assertEquals("b.png", n.images[0]); assertEquals(Version.FUSION, Planche.version(n, 0))
        assertEquals(1, n.versions.size)
    }

    // ------------------------------------------------------------ drawing

    /** A processed version as [CorrectionsGalerie] keeps it: its PNG (any bytes do here) and its sidecar. */
    private fun traitee(f: CorrectionsGalerie.Fiche) {
        val dir = CorrectionsGalerie.dossier(d).also { it.mkdirs() }
        File(dir, f.fichier).writeBytes(byteArrayOf(1))
        File(dir, f.fichier.removeSuffix(".png") + ".meta").writeText(CorrectionsGalerie.encode(f))
    }

    @Test fun le_dessin_prend_la_version_choisie_sinon_auto_en_le_disant() {
        val a = shot("a.png"); val b = shot("b.png")
        val g = groupe("g1", 1, a to 70, b to 60)
        val corr = CorrectionsGalerie.Fiche("a.png", CorrectionsGalerie.nomCorrigee("a.png"), CorrectionsGalerie.Methode.SON, 70, 80, 1L)
        val fus = CorrectionsGalerie.Fiche("g1", CorrectionsGalerie.nomFusion("g1"), CorrectionsGalerie.Methode.FUSION, 70, 92, 1L, listOf("a.png", "b.png"))
        traitee(corr); traitee(fus)
        val api = ApiCorrectionSstv(CorrectionsGalerie({ null }, { _, _, _, _ -> false }), { d }, { listOf(a, b) }, { listOf(g) })
        val parNom = listOf(a, b).associate { it.fileName to (File(d, it.fileName) to it) }
        val m = Planche.genereGrille("p", "P").copy(images = mapOf(0 to "a.png", 1 to "b.png"))
        fun montre(x: Planche.Modele, i: Int) = api.pourCases(x, parNom).places[i]!!.first.name
        // Auto, the sheet not taking processed versions: as received; taking them: the best marked (the fusion).
        assertEquals("a.png", montre(m, 0))
        assertEquals(fus.fichier, montre(m.copy(corrigees = true), 0))
        // Chosen: the corrected one, received (even with the option on), the fusion (even with it off).
        assertEquals(corr.fichier, montre(Planche.avecVersion(m, 0, Version.CORRIGEE), 0))
        assertEquals("a.png", montre(Planche.avecVersion(m.copy(corrigees = true), 0, Version.RECUE), 0))
        val f = Planche.avecVersion(m, 1, Version.FUSION)
        assertEquals(fus.fichier, montre(f, 1))
        assertTrue(api.pourCases(f, parNom).places[1]!!.second.traitement.isNotEmpty())
        assertTrue(api.pourCases(f, parNom).replis.isEmpty())
        // The corrected version of b chosen, there is none: Auto, said so.
        val sans = api.pourCases(Planche.avecVersion(m, 1, Version.CORRIGEE), parNom)
        assertEquals("b.png", sans.places[1]!!.first.name); assertEquals(listOf(1), sans.replis)
        // The fusion deleted: Auto (the corrected a, best left with the option on), said so.
        api.corrections.oublieFusion(d, "g1")
        val parti = api.pourCases(Planche.avecVersion(m.copy(corrigees = true), 0, Version.FUSION), parNom)
        assertEquals(corr.fichier, parti.places[0]!!.first.name); assertEquals(listOf(0), parti.replis)
        // The box's panel: what there is, what Auto shows, a fusion to make.
        val o = api.pourCase(Planche.avecVersion(m, 0, Version.FUSION), 0)
        assertEquals("fusion", o.getString("choix")); assertTrue(o.getBoolean("repli")); assertTrue(o.getBoolean("fusionPossible"))
        assertEquals(listOf("recue", "corrigee"), (0 until o.getJSONArray("versions").length()).map { o.getJSONArray("versions").getJSONObject(it).getString("v") })
        assertNull(api.pourCase(m, 5).opt("note"))
    }
}
