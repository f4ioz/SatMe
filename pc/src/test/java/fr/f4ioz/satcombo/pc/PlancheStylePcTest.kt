/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.sstv.Planche
import fr.f4ioz.satcombo.sstv.PlancheDecor
import fr.f4ioz.satcombo.sstv.PlanchePolices
import fr.f4ioz.satcombo.sstv.PlancheStyle
import fr.f4ioz.satcombo.sstv.PlancheTitres
import fr.f4ioz.satcombo.sstv.SstvMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * The styled sheets drawn on the PC: the fonts load, every effect and frame
 * draws something, a plain sheet is drawn as before. The sheets are written
 * to `build/planches-stylees/` to be looked at.
 */
class PlancheStylePcTest {

    private val sortie = File("build/planches-stylees").apply { mkdirs() }

    /** The real pictures of the tests (ISS, series 34), twice over: twelve boxes. */
    private fun images(n: Int): Map<Int, Pair<BufferedImage, SstvMeta.SstvShot>> {
        val dossier = listOf("../moteur/src/test/resources/sstv-reel", "moteur/src/test/resources/sstv-reel").map { File(it) }.first { it.isDirectory }
        val f = dossier.listFiles { x -> x.name.endsWith(".png") }!!.sortedBy { it.name }
        return (0 until n).associateWith { i ->
            val x = f[i % f.size]
            ImageIO.read(x) to SstvMeta.SstvShot(x.name, "ISS", 1_792_000_000_000L + i * 600_000L, mode = "Robot36", locator = "JN18du", callsign = "F4IOZ")
        }
    }

    private val valeurs = PlancheRenduPc.Valeurs("F4IOZ", "Olivier", "JN18DU", "2026-10-23 – 2026-10-24", "ISS · SSTV")

    private fun rendu(m: Planche.Modele, largeur: Int = 2400): BufferedImage {
        val decor = PlancheDecor.integre(m.decor)?.takeIf { it.ressource != null }?.let { d -> PlancheDecor.ouvre(d)!!.use { ImageIO.read(it) } }
        return PlancheRenduPc.dessine(m, null, largeur, images(m.cases.size), valeurs, logo = null, decor = decor)
    }

    private fun ecrit(nom: String, b: BufferedImage) { ImageIO.write(b, "png", File(sortie, nom)) }

    private val infos = PlancheTitres.Infos("34", "ARISS SSTV Series 34", "SERIES")

    @Test
    fun les_polices_se_chargent() {
        PlanchePolices.LISTE.forEach { p ->
            val f = PlancheRenduPc.police(p.id)
            assertNotNull(p.id, f)
            assertTrue(p.id, f!!.canDisplay('S'))
            assertNotNull(PlanchePolices::class.java.getResourceAsStream("polices/" + p.licence))
        }
    }

    @Test
    fun les_planches_stylees_se_dessinent() {
        val affiche = PlancheTitres.genereAffiche("a", "Affiche", infos)
        ecrit("affiche-iss.png", rendu(affiche))
        val neon = PlancheTitres.applique(Planche.genereTour("t", "t").copy(decor = "nebuleuse", cadre = PlancheStyle.Cadre.ARRONDI), "neon", infos)
        ecrit("neon-retro.png", rendu(neon))
        val ecusson = PlancheTitres.applique(Planche.genereGrille("g", "g").copy(decor = "orbite", cadre = PlancheStyle.Cadre.FIN), "ecusson", infos)
        ecrit("ecusson-dore.png", rendu(ecusson))
        val ariss = PlancheTitres.applique(Planche.genereTour("t", "t").copy(decor = "nasa_nuit", cadre = PlancheStyle.Cadre.NEON, cadreCouleur = 0xFFFFC21A.toInt()), "ariss", infos)
        ecrit("serie-ariss.png", rendu(ariss))
        // Each effect on one line, small and large: readable at both sizes.
        val styles = PlancheStyle.Style.values().toList()
        val essai = Planche.Modele("s", "s", decor = "abysse", textes = styles.mapIndexed { i, s ->
            Planche.Texte(Planche.Champ.LIBRE, Planche.Zone(0.02f, 0.02f + i * 0.135f, 0.62f, 0.12f), PlancheStyle.LUEURS[i % 4], 0,
                "SSTV 34 " + s.code.ifEmpty { "simple" }, PlanchePolices.LISTE[i % PlanchePolices.LISTE.size].id, s, italique = i % 2 == 1, filets = s == PlancheStyle.Style.NEON)
        } + styles.mapIndexed { i, s ->
            Planche.Texte(Planche.Champ.LIBRE, Planche.Zone(0.68f, 0.02f + i * 0.04f, 0.3f, 0.035f), Planche.BLANC, 0, "Série 34 · F4IOZ", "", s)
        })
        val b = rendu(essai)
        ecrit("effets.png", b)
        // Something drawn in each line (not the plain background).
        styles.indices.forEach { i ->
            val y0 = ((0.02f + i * 0.135f + 0.03f) * b.height).toInt()
            val n = (y0 until y0 + (0.06f * b.height).toInt() step 3).sumOf { y ->
                val fond = lum(b.getRGB((b.width * 0.66).toInt(), y))
                (0 until (b.width * 0.6).toInt()).count { x -> kotlin.math.abs(lum(b.getRGB(x, y)) - fond) > 40 }
            }
            assertTrue("ligne $i: $n", n > 200)
        }
    }

    private fun lum(c: Int) = ((c shr 16) and 0xFF) * 3 / 10 + ((c shr 8) and 0xFF) * 6 / 10 + (c and 0xFF) / 10

    /** A sheet with nothing of the new kind is drawn exactly as the texts were drawn before (the plain path). */
    @Test
    fun une_planche_simple_ne_change_pas() {
        val m = Planche.genereGrille("g", "g")
        assertTrue(m.textes.all { PlancheStyle.estSimple(it) })
        assertEquals(PlancheStyle.Cadre.AUCUN, m.cadre)
        val b = rendu(m, 1200)
        ecrit("simple.png", b)
        // The box's white line of old, where it always was.
        val z = m.cases[0]
        assertEquals(0xFFFFFF, b.getRGB((z.x * 1200 + z.w * 600).toInt(), (z.y * b.height).toInt()) and 0xFFFFFF)
    }
}
