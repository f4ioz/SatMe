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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The test pattern through the whole decoder, as the dongle's audio would bring it. */
class DecodeurSondesTest {

    private val dossier = File(System.getProperty("java.io.tmpdir"), "sondes-" + System.nanoTime())
    @After fun fin() { dossier.deleteRecursively() }

    private fun ecoute(d: DecodeurSondes, modele: String, secondes: Int = 6) {
        val src = SondeMire.Source(modele, 48.2, -4.5, secondes)
        val bloc = ShortArray(SondeMire.RATE / 4)
        while (true) { val n = src.read(bloc); if (n <= 0) break; d.ecoute(bloc, n) }
    }

    @Test
    fun les_trois_modeles_traversent_le_decodeur() {
        for (m in listOf("RS41", "M20", "M10")) {
            val d = DecodeurSondes(null)
            d.demarre(SondeMire.RATE, SondeMire.DEMO_FREQ_HZ, m)
            ecoute(d, m)
            assertTrue("aucune trame $m", d.etat.trames > 0)
            assertEquals(m, d.etat.type)
            assertEquals(SondeMire.DEMO_FREQ_HZ, d.etat.derniere!!.freqHz)
        }
    }

    @Test
    fun le_mode_automatique_trouve_la_sonde_et_le_vol_s_exporte() {
        val d = DecodeurSondes(dossier)
        d.demarre(SondeMire.RATE, SondeMire.DEMO_FREQ_HZ, SondeModel.AUTO)
        ecoute(d, "RS41", 8)
        assertEquals("RS41", d.etat.type)
        val v = d.vol!!
        assertTrue(v.count > 0)
        assertEquals(48.2, d.etat.derniere!!.lat, 1.0)   // the pattern drifts with the wind
        // The log: a header and a line per frame kept.
        val j = d.journaux().first { it.name.endsWith(".csv") }
        assertEquals(d.etat.trames + 1, j.readLines().size)
        val gpx = d.exporte(kml = false)!!
        assertTrue(gpx.readText().contains("<trkpt"))
        // Forgotten: decoding goes on, from nothing.
        d.oublieVol()
        assertEquals(0, d.etat.trames)
        assertEquals(null, d.vol)
    }
}
