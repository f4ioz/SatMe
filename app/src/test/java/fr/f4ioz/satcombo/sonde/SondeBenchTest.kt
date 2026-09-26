/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sonde

import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * The measurement bench, plus its quick safety check.
 *
 * Full sweeps take minutes: they only run with `-Dsatme.bench=1` and print a
 * table. The only test run on every build is the first one: in a few seconds
 * it checks that a clean sonde gets through the whole radio chain — dongle-
 * format IQ through decimation, channel filter and discriminator, not the
 * test pattern fed straight into the decoder.
 */
class SondeBenchTest {

    @After
    fun tearDown() {
        SondeHub.stop()
    }

    private fun benchEnabled(): Boolean =
        (System.getProperty("satme.bench") ?: "").isNotEmpty()

    @Test
    fun la_chaine_radio_complete_decode_une_rs41_propre() {
        val p = SondeBench.measure("RS41", ebn0Db = 25.0, seconds = 4)
        println("garde-fou : $p")
        assertTrue("aucune trame à travers la chaîne radio : $p", p.frames > 0)
    }

    @Test
    fun temoin_sans_radio() {
        Assume.assumeTrue("banc désactivé (passer -Dsatme.bench=1)", benchEnabled())
        println("=== témoin : la mire versée directement dans le décodeur ===")
        for (model in listOf("RS41", "M20", "M10")) {
            println(SondeBench.measureAudio(model))
        }
    }

    @Test
    fun balayage_du_bruit() {
        Assume.assumeTrue("banc désactivé (passer -Dsatme.bench=1)", benchEnabled())
        println("=== seuil de bruit, accord parfait ===")
        for (model in listOf("RS41", "M20", "M10")) {
            for (db in listOf(30.0, 20.0, 16.0, 14.0, 12.0, 10.0, 8.0, 6.0, 4.0)) {
                println(SondeBench.measure(model, ebn0Db = db, seconds = 6))
            }
        }
    }

    @Test
    fun balayage_du_desaccord() {
        Assume.assumeTrue("banc désactivé (passer -Dsatme.bench=1)", benchEnabled())
        println("=== coût du désaccord, à bruit confortable ===")
        for (model in listOf("RS41", "M20")) {
            for (off in listOf(0.0, 1_000.0, 2_000.0, 3_000.0, 5_000.0, 8_000.0)) {
                println(SondeBench.measure(model, ebn0Db = 16.0, seconds = 6, offsetHz = off))
            }
        }
    }

    @Test
    fun l_accord_automatique_rattrape_le_desaccord() {
        Assume.assumeTrue("banc désactivé (passer -Dsatme.bench=1)", benchEnabled())
        println("=== accord automatique par centre de gravité du spectre ===")
        for (off in listOf(0.0, 2_000.0, 5_000.0, 8_000.0)) {
            println("sans : " + SondeBench.measure("RS41", 14.0, 6, offsetHz = off))
            println("avec : " + SondeBench.measure("RS41", 14.0, 6, offsetHz = off, autoTune = true))
        }
    }
}
