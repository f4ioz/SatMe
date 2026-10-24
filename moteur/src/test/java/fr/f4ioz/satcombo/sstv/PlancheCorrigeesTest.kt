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
import org.junit.Assert.assertTrue
import org.junit.Test

/** A sheet taking the processed versions: kept in its template, said on the picture. */
class PlancheCorrigeesTest {

    @Test fun gardee_dans_le_modele() {
        val m = Planche.Modele(id = "a", nom = "A", corrigees = true)
        assertTrue(Planche.lit("a", Planche.ecrit(m))!!.corrigees)
        assertFalse(Planche.lit("a", Planche.ecrit(m.copy(corrigees = false)))!!.corrigees)
        assertFalse(Planche.ecrit(m.copy(corrigees = false)).contains("corrigees"))
    }

    @Test fun la_legende_le_dit() {
        val s = SstvMeta.SstvShot(fileName = "x.png", mode = "Robot 36", timeMs = 0L)
        assertEquals("Robot 36 | F4IOZ", Planche.legende(s, "F4IOZ"))
        assertEquals("Robot 36 | F4IOZ | corrigée", Planche.legende(s.copy(traitement = "corrigée"), "F4IOZ"))
        // Never written to the sidecar.
        assertFalse(SstvMeta.encode(s.copy(traitement = "corrigée")).contains("corrig"))
    }
}
