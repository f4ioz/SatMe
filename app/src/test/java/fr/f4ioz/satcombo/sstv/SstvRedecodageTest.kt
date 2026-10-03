/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** A picture decoded again keeps when it was received, and says when it was decoded again. */
class SstvRedecodageTest {

    private val recu = 1_759_383_005_000L   // 2025-10-02 05:30:05 UTC

    @Test
    fun le_debut_d_un_enregistrement_est_dans_son_nom() {
        assertEquals(1_759_383_005_000L, SstvMeta.debutEnregistrement("SatMe_ISS_20251002_053005Z.mp3"))
        assertEquals(0L, SstvMeta.debutEnregistrement("import.wav"))
    }

    @Test
    fun un_second_decodage_ne_remplace_pas_le_premier() {
        val n = SstvMeta.fileName("ISS", recu, "PD120", false, variante = 2)
        assertEquals("SatMe_SSTV_ISS_20251002_053005Z_PD120_partiel_r2.png", n)
        val s = SstvMeta.parseName(n)
        assertEquals("ISS", s.satName); assertEquals(recu, s.timeMs)
        assertEquals("PD120", s.mode); assertFalse(s.complete)
    }

    @Test
    fun la_date_de_redecodage_est_gardee_a_cote() {
        val s = SstvMeta.SstvShot(fileName = "x.png", satName = "ISS", timeMs = recu, redecodeMs = recu + 86_400_000L)
        val lu = SstvMeta.decode("x.png", SstvMeta.encode(s))
        assertEquals(recu, lu.timeMs)
        assertEquals(recu + 86_400_000L, lu.redecodeMs)
    }

    @Test
    fun un_enregistrement_garde_son_lieu_et_son_annonce() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        val mp3 = java.io.File(dir, "SatMe_ISS_20251002_053005Z.mp3")
        fr.f4ioz.satcombo.audio.InfoEnregistrement.ecrit(mp3, fr.f4ioz.satcombo.audio.InfoEnregistrement.Info("JN18FS", 9_800L))
        val i = fr.f4ioz.satcombo.audio.InfoEnregistrement.lit(mp3)!!
        assertEquals("JN18FS", i.locator); assertEquals(9_800L, i.annonceMs)
        assertEquals(null, fr.f4ioz.satcombo.audio.InfoEnregistrement.lit(java.io.File(dir, "autre.mp3")))
        dir.deleteRecursively()
    }
}
