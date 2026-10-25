/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The styled sheets drawn by the phone (the real Android drawing, under
 * Robolectric's native graphics): the fonts load from the bundled files,
 * the effects and frames draw. The sheets go to `build/planches-stylees-android/`
 * to be put beside the PC's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlancheRenduStyleTest {

    private val sortie = File("build/planches-stylees-android").apply { mkdirs() }

    private fun images(n: Int): Map<Int, Pair<Bitmap, SstvMeta.SstvShot>> {
        val dossier = listOf("../moteur/src/test/resources/sstv-reel", "moteur/src/test/resources/sstv-reel").map { File(it) }.first { it.isDirectory }
        val f = dossier.listFiles { x -> x.name.endsWith(".png") }!!.sortedBy { it.name }
        return (0 until n).associateWith { i ->
            val x = f[i % f.size]
            BitmapFactory.decodeFile(x.absolutePath) to SstvMeta.SstvShot(x.name, "ISS", 1_792_000_000_000L + i * 600_000L, mode = "Robot36", locator = "JN18du", callsign = "F4IOZ")
        }
    }

    private fun rendu(m: Planche.Modele, nom: String): Bitmap {
        val decor = PlancheDecor.integre(m.decor)?.takeIf { it.ressource != null }?.let { d -> PlancheDecor.ouvre(d)!!.use { BitmapFactory.decodeStream(it) } }
        val b = PlancheRendu.dessine(m, null, 2400, images(m.cases.size),
            PlancheRendu.Valeurs("F4IOZ", "Olivier", "JN18DU", "2026-10-23 – 2026-10-24", "ISS · SSTV"), null, decor)
        File(sortie, nom).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return b
    }

    private val infos = PlancheTitres.Infos("34", "ARISS SSTV Series 34", "SERIES")

    @Test
    fun les_planches_stylees_se_dessinent_sur_le_telephone() {
        PlanchePolices.LISTE.forEach { assertNotNull(it.id, PlancheRendu.police(it.id)) }
        val a = rendu(PlancheTitres.genereAffiche("a", "Affiche", infos), "affiche-iss.png")
        rendu(PlancheTitres.applique(Planche.genereTour("t", "t").copy(decor = "nebuleuse", cadre = PlancheStyle.Cadre.ARRONDI), "neon", infos), "neon-retro.png")
        rendu(PlancheTitres.applique(Planche.genereGrille("g", "g").copy(decor = "orbite", cadre = PlancheStyle.Cadre.FIN), "ecusson", infos), "ecusson-dore.png")
        // The chrome title lights the top of the poster.
        var clairs = 0
        for (y in (a.height * 0.05f).toInt() until (a.height * 0.2f).toInt() step 4)
            for (x in 0 until a.width step 4) if ((a.getPixel(x, y) and 0xFF) > 200) clairs++
        assertTrue("clairs $clairs", clairs > 1000)
    }
}
