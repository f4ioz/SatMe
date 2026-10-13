/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.meteor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeteorAutoTest {
    @Test fun `ne prend pas un satellite en cours de passage`() {
        assertFalse(MeteorAuto.peutPrendre(1000L, emptySet(), suiviEstMeteor = false, suiviElDeg = 35.0))
        assertTrue(MeteorAuto.peutPrendre(1000L, emptySet(), suiviEstMeteor = false, suiviElDeg = -10.0))
        assertTrue(MeteorAuto.peutPrendre(1000L, emptySet(), suiviEstMeteor = false, suiviElDeg = null))
        assertTrue(MeteorAuto.peutPrendre(1000L, emptySet(), suiviEstMeteor = true, suiviElDeg = 35.0))
    }

    @Test fun `un seul essai par passage`() {
        assertFalse(MeteorAuto.peutPrendre(1000L, setOf(1000L), suiviEstMeteor = true, suiviElDeg = null))
        assertTrue(MeteorAuto.peutPrendre(2000L, setOf(1000L), suiviEstMeteor = true, suiviElDeg = null))
    }
}
