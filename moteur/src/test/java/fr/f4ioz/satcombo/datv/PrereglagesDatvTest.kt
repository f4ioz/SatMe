/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The DATV presets: every built-in one valid in the band plan, never a gain, the operator's own kept. */
class PrereglagesDatvTest {
    @Test fun `les preregages d origine sont valides et sans gain`() {
        for (p in PrereglagesDatv.INTEGRES) {
            assertNull(p.nom, PrereglagesDatv.refus(p))
            assertFalse("gain" in p.parametres())
            assertTrue(p.integre)
        }
        // The net preset on 10 499.25 (uplink 2 409.75).
        assertEquals("2409.75", PrereglagesDatv.INTEGRES.first().parametres()["mhz"])
    }

    @Test fun `les siens se gardent se lisent et se refusent`() {
        val p = PrereglagesDatv.Prereglage("Mon 250", srKs = 250, modcod = "QPSK 1/2", monteeKhz = 2_408_750L)
        val l = PrereglagesDatv.range(emptyList(), p)
        val relu = PrereglagesDatv.lisListe(PrereglagesDatv.ecrisListe(l))
        assertEquals(listOf(p), relu)
        assertNotNull(PrereglagesDatv.de(relu, "mon 250"))
        // Not a symbol rate, not a MODCOD, too near the beacon: refused (and dropped when read back).
        assertNotNull(PrereglagesDatv.refus(p.copy(srKs = 77)))
        assertNotNull(PrereglagesDatv.refus(p.copy(modcod = "QPSK 9/7")))
        assertNotNull(PrereglagesDatv.refus(p.copy(monteeKhz = 2_402_000L)))
        assertTrue(PrereglagesDatv.lisListe(PrereglagesDatv.ecrisListe(listOf(p.copy(srKs = 77)))).isEmpty())
        // The built-ins first, a homonym of a built-in hidden.
        val t = PrereglagesDatv.tous(listOf(p, p.copy(nom = PrereglagesDatv.INTEGRES[1].nom)))
        assertEquals(PrereglagesDatv.INTEGRES.size + 1, t.size)
    }
}
