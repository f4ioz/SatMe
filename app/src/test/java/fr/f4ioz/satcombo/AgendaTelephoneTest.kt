/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.data.AgendaTelephone
import fr.f4ioz.satcombo.data.SatPass
import fr.f4ioz.satcombo.i18n.I18n
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The calendar event text: UTC times, azimuths, duration, QTH. */
class AgendaTelephoneTest {

    // 2026-09-27 22:48:28 UTC → 23:02:14 UTC, 826 s.
    private val pass = SatPass("SO-50", 27607, 1_790_549_308_000L, 1_790_550_134_000L,
        64.4, 318.7, 151.2, sunlit = true, nightAtObserver = true)

    @Test
    fun heures_en_utc_azimuts_et_duree() {
        try {
            I18n.apply("fr", "")
            assertEquals("Passage SO-50", AgendaTelephone.titre(pass))
            assertEquals(
                "AOS 22:48:28 UTC, azimut 318°\nÉlévation max 64°\nLOS 23:02:14 UTC, azimut 151°\n" +
                    "Durée 13 min 46 s\nQTH JN18FT",
                AgendaTelephone.description(pass, "JN18FT"))
        } finally { I18n.apply("fr", "") }
    }

    @Test
    fun sans_locator_pas_de_ligne_qth_vide() {
        try {
            I18n.apply("en", "")
            assertFalse(AgendaTelephone.description(pass, "").contains("QTH"))
        } finally { I18n.apply("fr", "") }
    }
}
