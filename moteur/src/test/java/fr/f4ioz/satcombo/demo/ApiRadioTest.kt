/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.demo

import org.junit.Assert.assertEquals
import org.junit.Test

class ApiRadioTest {
    @Test fun `un CQ, avec ou sans direction`() {
        assertEquals(Triple("F4IOZ", null, "JN18"), ApiRadio.decoupe("CQ F4IOZ JN18"))
        assertEquals(Triple("F4IOZ", null, "JN18"), ApiRadio.decoupe("CQ DX F4IOZ JN18"))
        assertEquals(Triple("F4IOZ", null, "JN18"), ApiRadio.decoupe("CQ 145 F4IOZ JN18"))
        assertEquals(Triple("F4IOZ", null, null), ApiRadio.decoupe("CQ F4IOZ"))
    }

    @Test fun `un appel, un rapport, une fin`() {
        assertEquals(Triple("F4IOZ", "F4ABC", "JN18"), ApiRadio.decoupe("F4ABC F4IOZ JN18"))
        assertEquals(Triple("F4IOZ", "F4ABC", null), ApiRadio.decoupe("F4ABC F4IOZ -10"))
        assertEquals(Triple("F4IOZ", "F4ABC", null), ApiRadio.decoupe("F4ABC F4IOZ RR73"))
        assertEquals(Triple("PJ4/K1ABC", "F4ABC", null), ApiRadio.decoupe("F4ABC <PJ4/K1ABC> 73"))
    }
}
