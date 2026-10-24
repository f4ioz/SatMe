/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QualiteSstvTest {

    private val fs = 48_000
    private val r36 = SstvMode.byName("Robot 36")!!

    private fun image(): IntArray = IntArray(r36.width * r36.height) { i ->
        val x = i % r36.width; val y = i / r36.width
        ((x * 255 / r36.width) shl 16) or ((y * 255 / r36.height) shl 8) or (if ((x / 40 + y / 40) % 2 == 0) 0xC0 else 0x30)
    }

    /** The signal at [snrDb] in 3 kHz (white noise over 24 kHz), [gain] applied, clipped at 16 bits. */
    private fun bruite(sig: ShortArray, snrDb: Double, gain: Double, seed: Long = 1): ShortArray {
        val ps = sig.sumOf { it.toDouble() * it } / sig.size
        val pn = ps / Math.pow(10.0, snrDb / 10) * 8.0
        val r = java.util.Random(seed)
        return ShortArray(sig.size) { ((sig[it] + r.nextGaussian() * Math.sqrt(pn)) * gain).coerceIn(-32768.0, 32767.0).toInt().toShort() }
    }

    private class Sortie(val q: QualiteSstv, val pixels: IntArray?)

    private fun decode(pcm: ShortArray, mesurer: Boolean): Sortie {
        val q = QualiteSstv(fs)
        var px: IntArray? = null
        val d = SstvDecoder(fs, object : SstvDecoder.Listener {
            override fun onVis(mode: SstvMode) { if (mesurer) q.debut(mode) }
            override fun onImage(mode: SstvMode, pixels: IntArray, linesDone: Int, complete: Boolean) { px = pixels.copyOf(); if (mesurer) q.fin() }
        })
        if (mesurer) d.mesureLigne = { _, l, t, n, b -> q.ligne(l, t, n, b) }
        var i = 0
        while (i < pcm.size) { val n = minOf(960, pcm.size - i); val b = pcm.copyOfRange(i, i + n); if (mesurer) q.son(b, n); d.feed(b, n); i += n }
        d.finish()
        return Sortie(q, px)
    }

    @Test fun la_mesure_ne_change_pas_l_image() {
        val pcm = bruite(SstvTestSignal.encode(r36, image(), fs), 15.0, 0.3)
        val a = decode(pcm, true); val b = decode(pcm, false)
        assertNotNull(a.pixels)
        assertArrayEquals(b.pixels, a.pixels)
    }

    @Test fun le_rapport_signal_bruit_suit_le_bruit() {
        val sig = SstvTestSignal.encode(r36, image(), fs)
        val fort = decode(bruite(sig, 30.0, 0.3), true).q.etat
        val moyen = decode(bruite(sig, 16.0, 0.3), true).q.etat
        assertEquals(30.0, fort.snrDb!!, 4.0)
        assertEquals(16.0, moyen.snrDb!!, 4.0)
        assertTrue(fort.noteFinale!! >= 90)
        assertTrue(fort.noteFinale!! > moyen.noteFinale!!)
        assertEquals(r36.height, fort.qualites.size)
        assertTrue(fort.trouvees > 230)
    }

    @Test fun le_niveau_dit_saturation_et_son_trop_faible() {
        val sig = SstvTestSignal.encode(r36, image(), fs)
        assertEquals(QualiteSstv.Niveau.SATURE, decode(bruite(sig, 30.0, 3.0), true).q.etat.niveau)
        assertEquals(QualiteSstv.Niveau.FAIBLE, decode(bruite(sig, 30.0, 0.002), true).q.etat.niveau)
        assertEquals(QualiteSstv.Niveau.BON, decode(bruite(sig, 30.0, 0.2), true).q.etat.niveau)
        val q = QualiteSstv(fs); q.son(ShortArray(fs), fs)
        assertEquals(QualiteSstv.Niveau.SILENCE, q.etat.niveau)
    }

    @Test fun la_note_estimee_compte_les_lignes_perdues_et_manquantes() {
        assertEquals(100, QualiteSstv.note(0.0, 240, 240))
        assertEquals(45, QualiteSstv.note(120.0, 240, 240))
        assertEquals(50, QualiteSstv.note(0.0, 120, 240))
        assertEquals(1.0, QualiteSstv.gache(false, 5.0), 0.0)
        assertEquals(0.0, QualiteSstv.gache(true, 8.0), 0.0)
        assertEquals(30.0, QualiteSstv.snrDb(350.0 / Math.pow(10.0, 1.5)), 1e-6)
    }
}
