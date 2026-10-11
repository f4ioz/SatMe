/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.ui.heureVersMs
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The contact editor's time field shows HH:mm. Read back, it set the seconds
 * to zero: every save moved the contact to the start of its minute, and the
 * fields saved after under the old time were lost (11/10).
 */
class HeureContactTest {

    private val contact = 1_791_745_237_412L   // 2026-10-11 19:00:37.412 UTC

    @Test
    fun la_meme_heure_relue_ne_bouge_pas_le_contact() {
        assertEquals(contact, heureVersMs("19:00", contact, useUtc = true))
    }

    @Test
    fun une_minute_changee_garde_les_secondes() {
        assertEquals(contact + 5 * 60_000L, heureVersMs("19:05", contact, useUtc = true))
    }
}
