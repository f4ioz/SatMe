/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The SSTV chain has no hardware to test against here, so every mode is
 * verified by re-synthesising its own signal ([SstvTestSignal], written from
 * the published specification) and decoding it back. A wrong line time, a
 * swapped colour order or a mis-anchored Scottie block all fail loudly instead
 * of shipping as a tilted picture nobody can explain on the air.
 */
class SstvDecoderTest {

    private val fs = 44_100

    // ------------------------------------------------------------- test images

    /** Eight vertical colour bars, constant down each column. */
    private fun bars(m: SstvMode): IntArray {
        val cols = intArrayOf(
            0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt(),
            0xFF0000FF.toInt(), 0xFFFFFF00.toInt(), 0xFF00FFFF.toInt(), 0xFF808080.toInt())
        val img = IntArray(m.width * m.height)
        for (y in 0 until m.height) for (x in 0 until m.width) {
            img[y * m.width + x] = cols[(x * 8 / m.width).coerceIn(0, 7)]
        }
        return img
    }

    /** Black on top, white below — catches vertical misalignment. */
    private fun split(m: SstvMode): IntArray {
        val img = IntArray(m.width * m.height)
        val cut = m.height / 2
        for (y in 0 until m.height) {
            val c = if (y < cut) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            for (x in 0 until m.width) img[y * m.width + x] = c
        }
        return img
    }

    // ------------------------------------------------------------------ driver

    private class Result(var mode: SstvMode?, var pixels: IntArray?, var complete: Boolean)

    private fun decode(m: SstvMode, img: IntArray, blocks: Int = m.blocks): Result {
        val pcm = SstvTestSignal.encode(m, img, fs, blocks)
        val res = Result(null, null, false)
        val dec = SstvDecoder(fs, object : SstvDecoder.Listener {
            override fun onVis(mode: SstvMode) { if (res.mode == null) res.mode = mode }
            override fun onImage(
                mode: SstvMode, pixels: IntArray, linesDone: Int, complete: Boolean
            ) {
                if (res.pixels == null) { res.pixels = pixels.copyOf(); res.complete = complete }
            }
        })
        val chunk = ShortArray(4096)
        var i = 0
        while (i < pcm.size) {
            val n = minOf(chunk.size, pcm.size - i)
            System.arraycopy(pcm, i, chunk, 0, n)
            dec.feed(chunk, n)
            i += n
        }
        dec.finish()
        return res
    }

    private fun px(p: IntArray, m: SstvMode, x: Int, y: Int) = p[y * m.width + x]

    private fun assertColour(
        got: Int, want: Int, tol: Int, where: String
    ) {
        val dr = abs(((got shr 16) and 0xFF) - ((want shr 16) and 0xFF))
        val dg = abs(((got shr 8) and 0xFF) - ((want shr 8) and 0xFF))
        val db = abs((got and 0xFF) - (want and 0xFF))
        assertTrue(
            "$where attendu #%06X obtenu #%06X".format(want and 0xFFFFFF, got and 0xFFFFFF),
            dr <= tol && dg <= tol && db <= tol)
    }

    // ---------------------------------------------------------------- VIS only

    /**
     * Every published mode must be recognised from its header alone. Only a few
     * blocks are synthesised — decoding a full PD 290 frame would be five
     * minutes of audio.
     */
    @Test
    fun everyModeIsRecognisedFromItsHeader() {
        for (m in SstvMode.ALL) {
            val img = bars(m)
            val r = decode(m, img, blocks = 3)
            assertNotNull("aucun VIS détecté pour ${m.name}", r.mode)
            assertEquals("mauvais mode pour ${m.name}", m.name, r.mode!!.name)
        }
    }

    /** A header carrying a VIS nobody implements must not start a picture. */
    @Test
    fun unknownVisIsIgnored() {
        // 0x2D = 45, an unassigned code with the same shape as Martin M1.
        val fake = SstvMode(45, "Fake", 320, 8, 446.446, 1, 0.0, 4.862,
            listOf(Segment(5.434, 146.432, Role.GREEN),
                   Segment(152.438, 146.432, Role.BLUE),
                   Segment(299.442, 146.432, Role.RED)), Family.RGB)
        val r = decode(fake, IntArray(320 * 8), blocks = 3)
        assertEquals(null, r.mode)
    }

    /** Parity is what keeps noise from firing the decoder at random. */
    @Test
    fun visParityIsEven() {
        for (m in SstvMode.ALL) {
            var ones = 0
            for (b in 0 until 7) if ((m.vis shr b) and 1 == 1) ones++
            // The generator appends the parity bit; the count of all eight must
            // come out even, which is exactly what the decoder checks.
            val parity = if (ones % 2 == 1) 1 else 0
            assertEquals("parité ${m.name}", 0, (ones + parity) % 2)
        }
    }

    // --------------------------------------------------------- full RGB frames

    @Test
    fun martinM1RoundTrip() = colourBarTest("Martin M1", 22)

    @Test
    fun martinM2RoundTrip() = colourBarTest("Martin M2", 22)

    @Test
    fun scottieS1RoundTrip() = colourBarTest("Scottie S1", 22)

    @Test
    fun scottieS2RoundTrip() = colourBarTest("Scottie S2", 22)

    @Test
    fun robot36RoundTrip() = colourBarTest("Robot 36", 30)

    @Test
    fun robot72RoundTrip() = colourBarTest("Robot 72", 26)

    @Test
    fun pd90RoundTrip() = colourBarTest("PD 90", 26)

    @Test
    fun pd120RoundTrip() = colourBarTest("PD 120", 30)

    private fun colourBarTest(name: String, tol: Int) {
        val m = SstvMode.byName(name)!!
        val img = bars(m)
        val r = decode(m, img)
        assertEquals(m.name, r.mode?.name)
        assertTrue("image ${m.name} incomplète", r.complete)
        val p = r.pixels!!
        // Sample the middle of each bar, skipping the first and last lines
        // (filter settling and the trailing edge of the transmission).
        for (y in 4 until m.height - 2 step 7) {
            for (bar in 0 until 8) {
                val x = (bar * m.width / 8) + (m.width / 16)
                assertColour(px(p, m, x, y), img[y * m.width + x], tol, "${m.name} ($x,$y)")
            }
        }
    }

    // ------------------------------------------------------ vertical alignment

    /**
     * The black/white step must land within a couple of lines of the middle.
     * Scottie is the interesting one: its sync pulse sits between blue and red,
     * so a block anchored on the pulse would mix two picture lines.
     */
    @Test
    fun verticalAlignmentIsHeldAcrossTheFrame() {
        for (name in listOf("Martin M1", "Scottie S1", "PD 120", "Robot 72")) {
            val m = SstvMode.byName(name)!!
            val r = decode(m, split(m))
            assertTrue("image $name incomplète", r.complete)
            val p = r.pixels!!
            val x = m.width / 2
            // First row that reads bright.
            var edge = -1
            for (y in 0 until m.height) {
                if (((px(p, m, x, y) shr 8) and 0xFF) > 128) { edge = y; break }
            }
            assertTrue("$name : pas de transition détectée", edge > 0)
            assertTrue("$name : transition à la ligne $edge au lieu de ${m.height / 2}",
                abs(edge - m.height / 2) <= 2)
            // And the halves must stay clean end to end — that is the slant test.
            assertColour(px(p, m, x, 4), 0xFF000000.toInt(), 26, "$name haut")
            assertColour(px(p, m, x, m.height - 4), 0xFFFFFFFF.toInt(), 26, "$name bas")
        }
    }

    // -------------------------------------------------------- partial pictures

    /** Audio cut short mid-frame still yields the lines already received. */
    @Test
    fun truncatedTransmissionYieldsAPartialImage() {
        val m = SstvMode.byName("Martin M1")!!
        val r = decode(m, bars(m), blocks = 40)
        assertEquals(m.name, r.mode?.name)
        assertNotNull(r.pixels)
        assertTrue("une image tronquée ne doit pas être annoncée complète", !r.complete)
        val p = r.pixels!!
        assertColour(px(p, m, m.width / 16, 10), 0xFF000000.toInt(), 22, "partielle")
        assertColour(px(p, m, m.width / 16 + m.width / 8, 10), 0xFFFFFFFF.toInt(), 22, "partielle")
    }

    // ------------------------------------------------------------- mode table

    /** The published block times, recomputed from the segment layout. */
    @Test
    fun blockTimesMatchThePublishedSpecification() {
        val expected = mapOf(
            "Robot 36" to 150.0, "Robot 72" to 300.0,
            "Martin M1" to 446.446, "Martin M2" to 226.798,
            "Scottie S1" to 428.22, "Scottie S2" to 277.692, "Scottie DX" to 1050.3,
            "PD 50" to 388.16, "PD 90" to 703.04, "PD 120" to 508.48,
            "PD 160" to 804.416, "PD 180" to 754.24, "PD 240" to 1000.0,
            "PD 290" to 937.28)
        for (m in SstvMode.ALL) {
            val want = expected[m.name] ?: error("mode non couvert : ${m.name}")
            assertEquals(m.name, want, m.blockMs, 0.001)
            // Nothing may stick out of its own block.
            for (s in m.segments) {
                assertTrue("${m.name}/${s.role} déborde du bloc",
                    s.startMs + s.durMs <= m.blockMs + 1e-6)
            }
            assertTrue("${m.name} : sync hors bloc",
                m.syncAtMs + m.syncMs <= m.blockMs + 1e-6)
        }
    }
}
