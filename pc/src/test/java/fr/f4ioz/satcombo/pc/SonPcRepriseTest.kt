/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SonPcRepriseTest {

    /** A card that is not one (the IC-9700 by the network), so no sound hardware is needed. */
    private fun sonAvecPoste(): Pair<SonPc, IntArray> {
        val s = SonPc()
        val ouvertures = IntArray(1)
        s.virtuelles += SonPc.SourceVirtuelle("IC-9700 (réseau)", "essai", { true }, { ouvertures[0]++; null }, {})
        assertTrue(s.demarre("IC-9700 (réseau)"))
        return s to ouvertures
    }

    @Test fun un_enregistrement_garde_sa_carte_quand_le_sdr_demarre() {
        val (s, _) = sonAvecPoste()
        s.gardeCarte = { true }
        s.prendPour(SdrPc.NOM_SOURCE, 44_100)
        assertEquals("IC-9700 (réseau)", s.etat.value.appareil)
        assertTrue(s.etat.value.actif)
        s.rendPar(SdrPc.NOM_SOURCE)
        assertEquals("IC-9700 (réseau)", s.etat.value.appareil)
        assertTrue(s.etat.value.actif)
    }

    @Test fun le_sdr_rend_la_carte_qu_il_avait_prise() {
        val (s, ouvertures) = sonAvecPoste()
        s.prendPour(SdrPc.NOM_SOURCE, 44_100)
        assertEquals(SdrPc.NOM_SOURCE, s.etat.value.appareil)
        s.rendPar(SdrPc.NOM_SOURCE)
        assertEquals("IC-9700 (réseau)", s.etat.value.appareil)
        assertTrue(s.etat.value.actif)
        assertEquals(2, ouvertures[0])
    }
}
