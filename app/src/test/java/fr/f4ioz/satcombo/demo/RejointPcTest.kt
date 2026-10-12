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
import org.junit.Assert.assertNull
import org.junit.Test

/** SatMe PC's QR code read on the phone, and anything else turned away. */
class RejointPcTest {
    @Test
    fun le_qr_code_de_satme_pc() {
        assertEquals("http://192.168.0.65:8091" to "abcdefghjkmnpqrstuvw", RejointPc.lit("http://192.168.0.65:8091/appairage?j=abcdefghjkmnpqrstuvw"))
        assertEquals("http://10.0.2.2:8091" to "abcdefghjkmnpqrstuvw", RejointPc.lit(" http://10.0.2.2:8091/appairage?j=abcdefghjkmnpqrstuvw\n"))
    }

    @Test
    fun les_autres_codes() {
        assertNull(RejointPc.lit("WIFI:T:WPA;S:maison;P:secret;;"))
        assertNull(RejointPc.lit("https://satme.example/appairage?j=abcdefghjkmnpqrstuvw"))
        assertNull(RejointPc.lit("http://192.168.0.65/appairage?j=abcdefghjkmnpqrstuvw"))
        assertNull(RejointPc.lit("http://192.168.0.65:8091/autre?j=abcdefghjkmnpqrstuvw"))
        assertNull(RejointPc.lit("http://192.168.0.65:8091/appairage?j=court"))
        assertNull(RejointPc.lit("pas une adresse"))
    }
}
