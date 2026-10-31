/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.cat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What each rig may be sent: a static table, a clear refusal outside it. */
class BandesPosteTest {

    @Test
    fun l_ic9700_couvre_2m_70cm_23cm_et_rien_d_autre() {
        for (hz in listOf(145_800_000L, 435_660_000L, 1_268_500_000L)) assertTrue(hz.toString(), BandesPoste.couvre("IC9700", hz))
        for (hz in listOf(29_400_000L, 50_313_000L, 223_000_000L, 739_550_000L, 10_489_550_000L)) assertFalse(hz.toString(), BandesPoste.couvre("IC9700", hz))
        val r = BandesPoste.refus("IC9700", 29_400_000L)!!
        assertTrue(r, r.startsWith("IC-9700 : 29,400 MHz hors de ses bandes (2 m, 70 cm, 23 cm)"))
        assertTrue(r.endsWith("commande non envoyée"))
        assertNull(BandesPoste.refus("IC9700", 145_800_000L))
    }

    @Test
    fun le_ts2000_couvre_la_hf_mais_pas_en_mode_satellite() {
        assertTrue(BandesPoste.couvre("TS2000", 29_400_000L, satellite = false))
        assertFalse(BandesPoste.couvre("TS2000", 29_400_000L))
        assertTrue(BandesPoste.refus("TS2000", 29_400_000L)!!.contains("hors de son mode satellite"))
        assertTrue(BandesPoste.couvre("TS2000", 1_268_000_000L))
        assertEquals("2 m, 70 cm, 23 cm (option)", BandesPoste.resume("TS2000"))
        // The simulated rig is its model.
        assertFalse(BandesPoste.couvre("TS2000SIM", 2_400_000_000L))
        assertFalse(BandesPoste.couvre("SIM", 50_000_000L))
    }

    @Test
    fun un_modele_inconnu_n_est_jamais_refuse_sur_une_supposition() {
        assertTrue(BandesPoste.couvre("PAIRE", 10_000_000_000L))
        assertNull(BandesPoste.refus("XYZ", 1L))
    }

    @Test
    fun les_refus_du_ts2000_ne_sont_pas_des_succes() {
        for (r in listOf("?;", "E;", "O;", " ?; ")) assertTrue(r, Ts2000.refuse(r))
        for (r in listOf("", null, "FA00145800000;")) assertFalse(r.toString(), Ts2000.refuse(r))
    }
}
