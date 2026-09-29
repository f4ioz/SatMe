/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import androidx.test.core.app.ApplicationProvider
import fr.f4ioz.satcombo.data.ServeurGp
import fr.f4ioz.satcombo.data.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The author's server by default; a field the user emptied stays empty (sources directly). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServeurGpDefautTest {

    @Test
    fun serveur_par_defaut_puis_vide_si_choisi() {
        val s = SettingsStore(ApplicationProvider.getApplicationContext())
        assertEquals("https://gp.f4ioz.fr", s.serveurGp)
        assertEquals("https://gp.f4ioz.fr/gp/amsat.json", ServeurGp.adresses(s.serveurGp,
            fr.f4ioz.satcombo.data.TleSource("amsat_gp", "AMSAT", "https://x/a.json"))[0])
        s.serveurGp = ""
        assertEquals("", SettingsStore(ApplicationProvider.getApplicationContext()).serveurGp)
    }
}
