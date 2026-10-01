/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.cat.CatBench
import fr.f4ioz.satcombo.cat.Ic705Sim
import fr.f4ioz.satcombo.cat.Postes
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** FT-817 or IC-705 on each side, or the SDR dongle receiving. */
class PostesTest {

    @Test
    fun chaque_combinaison_garde_son_identifiant_d_avant() {
        // Ids saved by earlier versions must still mean the same rigs.
        assertEquals("FT817x2", Postes.modele(Postes.FT817, Postes.FT817))
        assertEquals("FT817_IC705", Postes.modele(Postes.IC705, Postes.FT817))
        assertEquals("IC705_FT817", Postes.modele(Postes.FT817, Postes.IC705))
        assertEquals("FT817TX", Postes.modele(Postes.SDR, Postes.FT817))
        assertEquals(FT817_IC705, Postes.FT817_IC705)
        assertEquals(IC705_FT817, Postes.IC705_FT817)
    }

    @Test
    fun les_nouvelles_combinaisons_ic705() {
        assertEquals("IC705x2", Postes.modele(Postes.IC705, Postes.IC705))
        assertEquals("IC705TX", Postes.modele(Postes.SDR, Postes.IC705))
        assertEquals(6, Postes.MODELES.size)
    }

    @Test
    fun aller_retour_entre_cotes_et_modele() {
        for (m in Postes.MODELES) assertEquals(m, Postes.modele(Postes.rx(m)!!, Postes.tx(m)!!))
        assertNull(Postes.rx("IC9700"))
        assertNull(Postes.tx(THD72))
    }

    @Test
    fun ce_que_chaque_modele_implique() {
        assertTrue(Postes.rxIc705("IC705x2") && Postes.txIc705("IC705x2"))
        assertFalse(Postes.mixte("IC705x2"))      // same make: told apart by band
        assertTrue(Postes.emetSeul("IC705TX") && Postes.txIc705("IC705TX"))
        assertFalse(Postes.rxIc705("IC705TX") || Postes.avecFt817("IC705TX"))
        assertTrue(Postes.emetSeul("FT817TX") && !Postes.avecIc705("FT817TX"))
        assertTrue(Postes.mixte(FT817_IC705) && Postes.mixte(IC705_FT817))
        assertFalse(Postes.emetSeul("IC9700"))
    }

    @Test
    fun le_banc_deux_ic705() = runBlocking {
        val rx = Ic705Sim(); val tx = Ic705Sim()
        val rep = CatBench.runIc705Pair(rx, tx, downlinkHz = 435_300_000L, uplinkHz = 145_850_000L)
        assertTrue(rep.steps.joinToString(" / "), rep.ok)
        assertEquals(0, rep.refusals)
        assertEquals(435_300_000L, rx.hz)
        assertEquals(145_850_000L, tx.hz)
        assertEquals(670, tx.toneTenthHz)
    }
}
