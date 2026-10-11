/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.data.Inactifs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which satellites are silent, from the SatNOGS transmitter list. */
class InactifsTest {

    @Test
    fun muet_seulement_si_tous_ses_emetteurs_sont_morts() {
        val tx = sequenceOf(
            40967 to false, 40967 to false,          // AO-85: all dead
            40908 to false, 40908 to true,           // LilacSat-2: one beacon left
            27607 to true)                           // SO-50
        assertEquals(setOf(40967), Inactifs.calcule(tx))
    }

    @Test
    fun le_cache_se_relit_a_l_identique() {
        val ids = setOf(40967, 43137, 7530)
        assertEquals(1_700_000_000_000L to ids,
            Inactifs.depuisTexte(Inactifs.versTexte(1_700_000_000_000L, ids)))
    }

    @Test
    fun un_cache_abime_est_refuse_plutot_que_lu_a_moitie() {
        assertNull(Inactifs.depuisTexte(""))
        assertNull(Inactifs.depuisTexte("pas une date\n40967\n"))
        assertNull(Inactifs.depuisTexte("1700000000000\n40967\nAO-85\n"))
    }
}
