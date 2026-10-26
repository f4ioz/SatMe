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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The reception's guards: a frozen picture, LongMynd gone by itself. */
class GardeDatvTest {

    @Test fun `image figee pendant que le flux passe`() {
        val g = GardeImage(figeMs = 6000, pauseMs = 20_000)
        // Pictures coming: nothing to do.
        assertFalse(g.tic(10_000, true, 1200, 9_800, 0))
        // Frozen 6 s while locked and the stream flows: started again, once.
        assertTrue(g.tic(16_000, true, 1200, 9_800, 0))
        assertFalse(g.tic(17_000, true, 1200, 9_800, 0))
        // Players just started again: given their time to show a picture.
        assertFalse(g.tic(21_000, true, 1200, 9_800, 16_500))
        // Still frozen after the pause: again.
        assertTrue(g.tic(37_000, true, 1200, 9_800, 16_500))
        assertEquals(2, g.relances)
        // Not locked, no stream, no players: not a decoder's fault.
        val h = GardeImage()
        assertFalse(h.tic(60_000, false, 1200, 0, 0))
        assertFalse(h.tic(60_000, true, 10, 0, 0))
        assertFalse(h.tic(60_000, true, 1200, 0, null))
    }

    @Test fun `LongMynd relance apres un arret inattendu`() {
        val r = RelanceLongMynd(listOf(3000, 10_000, 30_000), tenuMs = 15_000)
        // Unplugged, no access: stays stopped.
        assertNull(r.apres(60_000, "MiniTiouner introuvable : est-il branché ?"))
        assertNull(r.apres(60_000, "pas d'accès au MiniTiouner : il faut une règle udev"))
        // Gone by itself: a growing pause, three tries.
        assertEquals(3000L, r.apres(60_000, "LongMynd s'est arrêté : sans un mot"))
        assertEquals(10_000L, r.apres(2_000, "LongMynd s'est arrêté"))
        assertEquals(30_000L, r.apres(2_000, "LongMynd s'est arrêté"))
        assertNull(r.apres(2_000, "LongMynd s'est arrêté"))
        // A long run since: counted again from the start.
        assertEquals(3000L, r.apres(20_000, "LongMynd s'est arrêté"))
        r.raz()
        assertEquals(3000L, r.apres(1_000, "LongMynd s'est arrêté"))
    }
}
