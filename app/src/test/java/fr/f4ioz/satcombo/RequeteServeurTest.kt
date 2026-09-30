/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.data.TleRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a request carries: the app, its version and Android — nothing that follows a phone. */
class RequeteServeurTest {

    @Test
    fun seulement_des_marqueurs_publics() {
        TleRepository.version = "20.73"
        val r = TleRepository.requete("https://gp.f4ioz.fr/gp/amsat.json")
        assertTrue(r.header("User-Agent")!!.startsWith("SatMe/20.73 (Android "))
        assertEquals(setOf("User-Agent"), r.headers.names())
    }
}
