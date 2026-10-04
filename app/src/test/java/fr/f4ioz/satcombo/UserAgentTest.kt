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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** One User-Agent everywhere, as the SatMe GP server reads it, and no callsign in it. */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class UserAgentTest {

    @Test
    fun le_serveur_y_lit_la_version_et_android() {
        TleRepository.version = "20.77"
        val ua = TleRepository.USER_AGENT
        // The server's own patterns (satme_gp/garde.py).
        val m = Regex("^(SatMe|SatCombo)/(\\S+)").find(ua)!!
        assertEquals("20.77", m.groupValues[2])
        assertTrue(Regex("\\(Android ([0-9.]+)\\)").containsMatchIn(ua))
        assertTrue(ua.contains("+https://github.com/f4ioz/SatMe"))
    }

    @Test
    fun plus_aucun_user_agent_fige_dans_le_code() {
        val src = File("src/main/java").walkTopDown().filter { it.name.endsWith(".kt") }.toList()
        assertTrue(src.size > 50)
        val figes = src.flatMap { f ->
            f.readLines().filter { l -> l.contains("\"User-Agent\"") && !l.contains("USER_AGENT") }
                .map { f.name + ": " + it.trim() }
        }
        assertEquals(emptyList<String>(), figes)
        assertFalse(src.any { it.readText().contains("SatCombo/") && it.name != "TleRepository.kt" })
    }
}
