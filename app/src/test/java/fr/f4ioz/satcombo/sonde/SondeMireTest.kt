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
import kotlin.math.abs

/**
 * End-to-end test of the radiosonde chain.
 *
 * This one matters most. The M20 once never decoded although every piece
 * passed its own tests: the wiring was wrong — the M20 was fed to the M10
 * demodulator (two symbols per bit) and the half-bit decoding discarded
 * everything. No unit test crossed the whole chain, so none could see it.
 *
 * Here the test pattern generates the signal, it is fed to [SondeHub] exactly
 * as the dongle does, and frames are required at the output. Miswired
 * demodulators fail this before shipping.
 */
class SondeMireTest {

    @After
    fun tearDown() {
        SondeHub.stop()
    }

    /** Feeds the test pattern into the hub as the SDR dongle would. */
    private fun run(model: String, seconds: Int = 6): SondeHub.SondeState {
        SondeHub.stop()
        SondeHub.start(null, SondeMire.RATE, SondeMire.DEMO_FREQ_HZ,
            source = "DEMO", model = model, log = false)
        val src = SondeMire.Source(model, 48.2, -4.5, seconds)
        val chunk = ShortArray(SondeMire.RATE / 4)
        while (true) {
            val n = src.read(chunk)
            if (n <= 0) break
            SondeHub.feedLive(chunk, n)
        }
        val st = SondeHub.state.value
        SondeHub.stop()
        return st
    }

    @Test
    fun la_mire_rs41_traverse_toute_la_chaine() {
        val st = run("RS41")
        assertTrue("aucune trame RS41 décodée", st.frames > 0)
        assertEquals("RS41", st.type)
    }

    @Test
    fun la_mire_m20_traverse_toute_la_chaine() {
        // The test that should have existed from the start.
        val st = run("M20")
        assertTrue("aucune trame M20 décodée", st.frames > 0)
        assertEquals("M20", st.type)
    }

    @Test
    fun la_mire_m10_traverse_toute_la_chaine() {
        val st = run("M10")
        assertTrue("aucune trame M10 décodée", st.frames > 0)
        assertEquals("M10", st.type)
    }

    @Test
    fun le_mode_automatique_trouve_la_sonde_sans_qu_on_la_nomme() {
        // The default setting: all three decoders in parallel.
        SondeHub.stop()
        SondeHub.start(null, SondeMire.RATE, SondeMire.DEMO_FREQ_HZ,
            source = "DEMO", model = SondeModel.AUTO, log = false)
        val src = SondeMire.Source("M20", 45.0, 5.0, 6)
        val chunk = ShortArray(SondeMire.RATE / 4)
        while (true) {
            val n = src.read(chunk)
            if (n <= 0) break
            SondeHub.feedLive(chunk, n)
        }
        val st = SondeHub.state.value
        SondeHub.stop()
        assertTrue("le mode automatique n'a rien vu", st.frames > 0)
        assertEquals("M20", st.type)
    }

    @Test
    fun la_position_decodee_est_celle_qui_a_ete_emise() {
        // Decoding can produce frames with wrong coordinates. We cannot require
        // the last point near the launch (the test flight is compressed and
        // drifts several degrees in eight frames, on purpose). Instead the
        // decoded position must be one the pattern actually sent — a tighter
        // check, independent of the flight profile.
        val emitted = SondeMire.flight(48.2, -4.5, 8)
        val st = run("RS41", 8)
        val f = st.last
        assertTrue("pas de trame", f != null)
        val near = emitted.any {
            abs(it.lat - f!!.lat) < 0.001 && abs(it.lon - f.lon) < 0.001 &&
                abs(it.altM - f.altM) < 50.0
        }
        assertTrue("position hors du vol émis : ${f!!.lat}, ${f.lon}, ${f.altM}", near)
    }

    @Test
    fun le_vol_monte_eclate_et_redescend() {
        val pts = SondeMire.flight(48.0, -4.0, 300)
        val top = pts.maxByOrNull { it.altM }!!
        assertTrue("le ballon n'a pas éclaté haut : ${top.altM}", top.altM > 25_000.0)
        assertTrue("la descente manque",
            pts.last().altM < top.altM - 10_000.0)
        // Drift must be visible: a stationary balloon is useless for testing
        // the track display.
        val drift = abs(pts.last().lon - pts.first().lon)
        assertTrue("aucune dérive : $drift", drift > 0.1)
    }

    @Test
    fun le_signal_a_la_bonne_longueur_et_la_bonne_amplitude() {
        val pcm = SondeMire.render("M20", 48.0, -4.0, 2)
        assertEquals(2 * SondeMire.RATE, pcm.size)
        val peak = pcm.maxOf { abs(it.toInt()) }
        // Synthesis goes through a filter that rounds edges, so the peak stays
        // just under the target, as intended. Check it stays below (or the WAV
        // clips) and has not collapsed.
        assertTrue("crete $peak", peak <= SondeMire.AMPLITUDE)
        assertTrue("crete $peak", peak > SondeMire.AMPLITUDE * 0.75)
    }

    @Test
    fun la_mire_reste_decodable_avec_l_ambiance() {
        // Hiss and fading are for the demo, not to sink the decoder: a noisy
        // pattern must still produce frames, or the demo shows nothing.
        SondeHub.stop()
        SondeHub.start(null, SondeMire.RATE, SondeMire.DEMO_FREQ_HZ,
            source = "DEMO", model = "RS41", log = false)
        val src = SondeMire.Source("RS41", 48.2, -4.5, 6, ambience = true)
        val chunk = ShortArray(SondeMire.RATE / 4)
        while (true) {
            val n = src.read(chunk)
            if (n <= 0) break
            SondeHub.feedLive(chunk, n)
        }
        val st = SondeHub.state.value
        SondeHub.stop()
        assertTrue("trames ${st.frames}", st.frames > 0)
    }

    @Test
    fun les_profils_disent_la_bonne_largeur_de_filtre() {
        // RS41 in 15 kHz, Meteomodem in 22 kHz: why naming the model helps.
        assertEquals(Rs41.BANDWIDTH_HZ, SondeModel.bandwidthFor("RS41"))
        assertEquals(Meteomodem.BANDWIDTH_HZ, SondeModel.bandwidthFor("M20"))
        assertEquals(Meteomodem.BANDWIDTH_HZ, SondeModel.bandwidthFor(SondeModel.AUTO))
        assertTrue(SondeModel.wantsRs41(SondeModel.AUTO))
        assertTrue(SondeModel.wantsM20(SondeModel.AUTO))
        assertTrue(SondeModel.wantsM10(SondeModel.AUTO))
        assertTrue(SondeModel.wantsRs41("RS41"))
        assertTrue(!SondeModel.wantsM10("RS41"))
    }

    @Test
    fun la_m10_reste_jouable_a_quarante_quatre_kilohertz() {
        // 44100 / 9616 = 4.59 samples per chip. This test once expected 2.29,
        // treating 9616 as a bit rate instead of a chip rate, and so protected
        // the bug.
        val m10 = SondeModel.byId("M10")
        val s = m10.samplesPerChip(SondeMire.RATE)
        assertTrue("échantillons par symbole : $s", s > 4.5 && s < 4.7)
        assertTrue("la M10 ne devrait pas être signalée comme limite",
            !m10.marginal(SondeMire.RATE))
        // At 8 kHz, however, it no longer works.
        assertTrue(m10.marginal(8_000))
    }
}
