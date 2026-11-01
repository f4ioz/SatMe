/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

import fr.f4ioz.satcombo.data.SatPass
import fr.f4ioz.satcombo.demo.ApiPlancheEvenement
import fr.f4ioz.satcombo.demo.PagePlanche
import fr.f4ioz.satcombo.sstv.ClassementSstv.Origine
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.util.Random
import javax.imageio.ImageIO

/** « Note minimale »: the fills leave out the pictures marked below it, the hand's choice stays, the file keeps it. */
class PlancheSeuilTest {

    private val T0 = 1_792_681_800_000L

    private fun shot(nom: String, t: Long, complete: Boolean = true) =
        SstvMeta.SstvShot(fileName = nom, satName = "ISS", timeMs = t, mode = "Robot36", complete = complete)

    private fun groupe(id: String, diapo: Int, o: Origine, vararg im: Pair<SstvMeta.SstvShot, Int>) =
        ClassementSstv.Groupe(id, im.sortedByDescending { it.second }.map { it.first }, diapo, 12, o, im.associate { it.first.fileName to it.second })

    /** A signature of its own for each picture (never two copies of one), its group and mark. */
    private fun sig(k: Int, groupe: String, note: Int) = Planche.Signature(IntArray(192) { (it * 37 + k * 101) % 256 }, 0f, groupe, note)

    // ------------------------------------------------------------ the file

    @Test fun la_note_minimale_va_dans_le_fichier_et_une_vieille_planche_se_lit_sans() {
        val m = Planche.genereGrille("p1", "Série").copy(noteMin = 50)
        val t = Planche.ecrit(m)
        assertTrue(t.contains("notemin=50\n"))
        assertEquals(50, Planche.lit("p1", t)!!.noteMin)
        // None: nothing written, as an older sheet; read as 0.
        val sans = Planche.ecrit(m.copy(noteMin = 0))
        assertFalse(sans.contains("notemin"))
        assertEquals(0, Planche.lit("p1", sans)!!.noteMin)
        // A file from elsewhere: kept within 0..100, nonsense read as none.
        assertEquals(100, Planche.lit("p1", sans + "notemin=250\n")!!.noteMin)
        assertEquals(0, Planche.lit("p1", sans + "notemin=abc\n")!!.noteMin)
        // An older SatMe reads every line it knows the same (the key it does not know is left aside).
        assertEquals(Planche.lit("p1", sans)!!.copy(noteMin = 50), Planche.lit("p1", t))
    }

    @Test fun le_modele_exporte_garde_sa_note_minimale_et_reste_lisible_en_version_1() {
        val dir = Files.createTempDirectory("seuil-fichier").toFile()
        try {
            val r = Planche.Rangement(dir)
            val m = Planche.genereGrille("p1", "Série").copy(noteMin = 45)
            val octets = PlancheFichier.exporte(m, r, { null })
            val lu = PlancheFichier.lit(octets)
            assertEquals(45, lu.modele.noteMin)
            assertEquals(1, lu.version)
            assertEquals(45, PlancheFichier.importe(octets, r).noteMin)
        } finally { dir.deleteRecursively() }
    }

    // ------------------------------------------------------------ « Remplir »

    @Test fun remplir_laisse_de_cote_les_images_sous_le_seuil_mais_pas_celles_non_notees() {
        val a = shot("a.png", T0 + 1000); val b = shot("b.png", T0 + 2000); val c = shot("c.png", T0 + 3000); val d = shot("d.png", T0 + 4000)
        val sigs = mapOf("a.png" to sig(1, "ga", 80), "b.png" to sig(2, "gb", 30), "c.png" to sig(3, "gc", -1), "d.png" to sig(4, "gd", 55))
        val m = Planche.genereGrille("p", "x")
        assertEquals(listOf("a.png", "b.png", "c.png", "d.png"), Planche.choisies(Planche.remplit(m, listOf(a, b, c, d), sigs)))
        val s = Planche.remplit(m.copy(noteMin = 50), listOf(a, b, c, d), sigs)
        assertEquals(listOf("a.png", "c.png", "d.png"), Planche.choisies(s))
        assertEquals(listOf("a.png", "c.png"), Planche.choisies(Planche.remplit(m.copy(noteMin = 60), listOf(a, b, c, d), sigs)))
        // The sheet taking processed versions: b's slide fused at 75 shows above the threshold, so b comes back.
        val auto = { g: String -> if (g == "gb") 75 else -1 }
        assertEquals(listOf("a.png", "b.png", "c.png", "d.png"), Planche.choisies(Planche.remplit(m.copy(noteMin = 50), listOf(a, b, c, d), sigs, auto)))
        // The sheet fitted to its pictures: as many as are left.
        val ajustee = Planche.avecNombre(m.copy(noteMin = 50), 4, listOf(a, b, c, d), sigs)
        assertEquals(listOf("a.png", "c.png", "d.png"), Planche.choisies(ajustee))
        assertEquals(3, ajustee.cases.size)
        // A picture already on it stays (put there by hand): only the others are judged.
        val main = Planche.ajuste(m.copy(noteMin = 50), listOf("b.png"))
        assertEquals(listOf("b.png", "a.png"), Planche.choisies(Planche.avecNombre(main, 2, listOf(a, b, c, d), sigs)))
    }

    @Test fun la_regle_du_seuil() {
        assertTrue(Planche.retenue(10, 0))          // no threshold
        assertTrue(Planche.retenue(-1, 90))         // not marked yet: never left out
        assertTrue(Planche.retenue(50, 50))         // at the threshold: kept
        assertFalse(Planche.retenue(49, 50))
        assertEquals(70, Planche.noteVue(40, 70))
        assertEquals(40, Planche.noteVue(40, -1))
    }

    // ------------------------------------------------------------ « Remplir par diapo »

    @Test fun remplir_par_diapo_prend_la_meilleure_au_dessus_et_une_diapo_sans_image_retenue_manque() {
        val x = shot("x.png", T0 + 1000); val y = shot("y.png", T0 + 2000); val z = shot("z.png", T0 + 3000)
        val w = shot("w.png", T0 + 4000); val v = shot("v.png", T0 + 5000)
        val gs = listOf(
            groupe("g1", 1, Origine.LU, x to 40, y to 70),    // slide 1: y, the only one above 50
            groupe("g2", 2, Origine.LU, z to 30),             // slide 2: none above → missing
            groupe("g3", 3, Origine.LU, w to -1),             // slide 3: not marked yet → kept
            groupe("g4", 4, Origine.LU, v to 49))             // slide 4: just below → missing
        val m = Planche.genereGrille("p", "x").copy(noteMin = 50)
        val r = Planche.remplitParDiapo(m, gs, 12)
        assertEquals(mapOf(0 to "y.png", 2 to "w.png"), r.modele.images)
        assertNull(r.modele.images[1])
        assertEquals(listOf(2, 4), r.ecartees)
        assertTrue(r.manquantes.containsAll(listOf(2, 4)))
        // No threshold: as before.
        val sans = Planche.remplitParDiapo(m.copy(noteMin = 0), gs, 12)
        assertEquals(mapOf(0 to "y.png", 1 to "z.png", 2 to "w.png", 3 to "v.png"), sans.modele.images)
        assertEquals(emptyList<Int>(), sans.ecartees)
        // A surer group all below: a less sure one of the same slide at the threshold takes its place.
        val estime = gs + groupe("e2", 2, Origine.ESTIME, shot("e.png", T0 + 6000) to 90)
        assertEquals("e.png", Planche.remplitParDiapo(m, estime, 12).modele.images[1])
        // Auto showing slide 2's fusion at 80: its picture goes in, the box will show the fusion.
        val auto = Planche.remplitParDiapo(m, gs, 12, noteAuto = { if (it == "g2") 80 else -1 })
        assertEquals("z.png", auto.modele.images[1])
        assertEquals(listOf(4), auto.ecartees)
    }

    @Test fun une_copie_mise_a_la_main_sous_le_seuil_reste() {
        val x = shot("x.png", T0 + 1000); val y = shot("y.png", T0 + 2000); val z = shot("z.png", T0 + 3000)
        val gs = listOf(groupe("g1", 1, Origine.LU, x to 40, y to 70), groupe("g2", 2, Origine.LU, z to 30))
        val base = Planche.genereGrille("p", "x").copy(noteMin = 50)
        // Box 1: x chosen by hand (below), box 2: z chosen by hand (its slide has none above).
        val m = Planche.avecCopie(Planche.avecCopie(base, 0, "x.png"), 1, "z.png")
        val r = Planche.remplitParDiapo(m, gs, 12)
        assertEquals("x.png", r.modele.images[0])
        assertEquals("z.png", r.modele.images[1])
        assertEquals(emptyList<Int>(), r.ecartees)
        assertFalse(2 in r.manquantes)
        // « Tout réinitialiser »: the hand's choices forgotten, the threshold applies.
        val ri = Planche.remplitParDiapo(m, gs, 12, reinitialise = true)
        assertEquals(mapOf(0 to "y.png"), ri.modele.images)
        assertEquals(listOf(2), ri.ecartees)
    }

    // ------------------------------------------------------------ the gallery and the page

    @Test fun la_galerie_dit_les_images_sous_le_seuil() {
        val dir = Files.createTempDirectory("seuil-galerie").toFile()
        try {
            // A clean picture and one spoilt by noise: two marks apart.
            val noms = listOf("propre.png", "sale.png")
            for ((i, n) in noms.withIndex()) {
                val r = Random(i.toLong())
                val b = BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB)
                for (yy in 0 until 240) for (xx in 0 until 320) {
                    val base = if ((xx / 40 + yy / 40) % 2 == 0) 200 else 60
                    val k = if (i == 0) base else (base + r.nextGaussian() * 90).toInt().coerceIn(0, 255)
                    val g = if (i == 1 && r.nextInt(5) == 0) 255 else k
                    b.setRGB(xx, yy, (k shl 16) or (g shl 8) or (if (i == 0) base else (base + r.nextGaussian() * 90).toInt().coerceIn(0, 255)))
                }
                ImageIO.write(b, "png", File(dir, n))
                SstvMeta.change(File(dir, n)) { it.copy(satName = "ISS", timeMs = T0 + i * 3_600_000L, mode = "Robot36") }
            }
            fun shots() = noms.map { n -> SstvMeta.decode(n, File(dir, SstvMeta.sidecarName(n)).readText()) }
            val galerie = ClassementGalerie { f -> ImageIO.read(f).let { ClassementGalerie.Pixels(it.getRGB(0, 0, it.width, it.height, null, 0, it.width), it.width, it.height) } }
            val src = object : ApiPlancheEvenement.Source {
                override val dossier = dir
                override val classement = galerie
                override fun images() = shots()
                override fun evenements() = emptyList<EvenementSstv.Evenement>()
                override fun catnumDe(sat: String) = 25544
                override fun passes(catnum: Int, deMs: Long, aMs: Long): List<SatPass> = emptyList()
                override fun positions(catnum: Int, ts: List<Long>): List<Pair<Double, Double>>? = null
                override fun epoqueElements(catnum: Int): Long? = null
                override fun elMin() = 10.0
                override fun station() = RapportEvenement.Station("F4IOZ", "JN18FS")
                override fun chaine(plan: String): Pair<String, List<List<RapportEvenement.Etape>>>? = null
                override fun planche(m: Planche.Modele): ByteArray? = null
                override fun vignette(nom: String): ByteArray? = null
            }
            galerie.etat(dir, shots(), attendre = true)
            fun notes(m: Planche.Modele?): Map<String, Pair<Int, Boolean>> = ApiPlancheEvenement.galerie(m, src).let { a ->
                (0 until a.length()).map { a.getJSONObject(it) }.associate { it.getString("f") to (it.getInt("ne") to it.getBoolean("sous")) } }
            val sans = notes(Planche.Modele("p", "x"))
            assertTrue(sans.values.none { it.second })
            val propre = sans.getValue("propre.png").first; val sale = sans.getValue("sale.png").first
            assertTrue("$propre > $sale", propre > sale && sale >= 0)
            // The threshold between the two: only the spoilt one is under it.
            val avec = notes(Planche.Modele("p", "x", noteMin = sale + 1))
            assertTrue(avec.getValue("sale.png").second)
            assertFalse(avec.getValue("propre.png").second)
            // No sheet: nothing under any threshold.
            assertTrue(notes(null).values.none { it.second })
        } finally { dir.deleteRecursively() }
    }

    @Test fun la_page_a_le_reglage_et_le_badge() {
        val h = PagePlanche.html()
        assertTrue(h.contains("notemin="))
        assertTrue(h.contains("Note minimale"))
        assertTrue(h.contains("type: 'range', min: '0', max: '100', step: '5'"))
        assertTrue(h.contains("sous le seuil"))
        assertTrue(h.contains("image(s) écartée(s)"))
        assertTrue(h.contains("pas encore notée"))
        assertFalse(h.contains("\"note_min\":\"pw_note_min\""))
    }
}
