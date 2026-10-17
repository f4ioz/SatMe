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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The automatic SSTV tuning, on synthetic pictures mistuned by -300 to +300 Hz, with noise and drift. */
class SstvAfcTest {

    private val fs = 11_025

    /** Colour bars over a grey ramp: black, white and colours on every line. */
    private fun mire(m: SstvMode): IntArray {
        val cols = intArrayOf(0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0x00FFFF, 0x808080)
        return IntArray(m.width * m.height) { p ->
            val x = p % m.width; val y = p / m.width
            if (y < m.height / 2) cols[x * 8 / m.width] or 0xFF000000.toInt()
            else { val g = x * 255 / (m.width - 1); (0xFF shl 24) or (g shl 16) or (g shl 8) or g }
        }
    }

    private val robot = SstvMode.byName("Robot 36")!!
    private val son by lazy { SstvEncoder.encode(robot, mire(robot), fs, leadMs = 600.0, trailMs = 400.0) }
    private val reference by lazy { AfcBanc.decode(son, fs, { 0.0 }, afc = false).images.first() }

    @Test fun `the header's offset is measured over plus or minus 300 Hz, noise or not`() {
        for (off in listOf(-300.0, -170.0, 0.0, 90.0, 300.0)) {
            val e = SstvEcart(fs)
            val d = AfcBanc.Decaleur(fs)
            val n = (2.5 * fs).toInt()
            val rnd = java.util.Random(7)
            val x = ShortArray(n) { (son[it] + rnd.nextGaussian() * 4000).toInt().coerceIn(-32767, 32767).toShort() }
            val y = ShortArray(n)
            d.traite(x, n, y, off)
            var vu: Double? = null
            var i = 0
            while (i < n) { val k = minOf(1000, n - i); e.ajoute(y.copyOfRange(i, i + k), k, false); e.prendVis()?.let { vu = it }; i += k }
            assertNotNull("header at $off Hz", vu)
            assertEquals("header at $off Hz", off, vu!!, 6.0)
        }
    }

    @Test fun `noise alone measures nothing`() {
        val e = SstvEcart(fs)
        val rnd = java.util.Random(3)
        val x = ShortArray(fs) { (rnd.nextGaussian() * 8000).toInt().toShort() }
        repeat(20) { e.ajoute(x, x.size, true); assertNull(e.prendVis()); e.sync(rnd.nextGaussian() * 200, 80.0) }
        assertNull(e.estimation())
    }

    @Test fun `mistuned pictures come out as the tuned one, with the tuning on`() {
        // The same noise in tune: the best to expect.
        val base = AfcBanc.ecart(AfcBanc.decode(son, fs, { 0.0 }, afc = false, bruit = 2500.0, ssb = true).images.first(), reference)
        val fautes = ArrayList<String>()
        for (off in listOf(-300.0, -250.0, -200.0, -100.0, 100.0, 200.0, 250.0, 300.0)) {
            val avec = AfcBanc.decode(son, fs, { off }, afc = true, bruit = 2500.0, ssb = true)
            val sans = AfcBanc.decode(son, fs, { off }, afc = false, bruit = 2500.0, ssb = true)
            val ea = avec.images.firstOrNull()?.let { AfcBanc.ecart(it, reference) } ?: 255.0
            val es = sans.images.firstOrNull()?.let { AfcBanc.ecart(it, reference) } ?: 255.0
            println("AFC synthétique : décalage $off Hz → avec ${"%.1f".format(ea)} (corrigé ${avec.totalHz} Hz, reste ${"%.0f".format(avec.resteFinHz)}), sans ${"%.1f".format(es)}, accordé ${"%.1f".format(base)}")
            if (abs(avec.totalHz - off) > 20) fautes += "$off Hz: corrected ${avec.totalHz}"
            if (ea > base + 5.0) fautes += "$off Hz: picture with ($ea, in tune $base)"
            if (ea > maxOf(es, base) + 1.0) fautes += "$off Hz: worse than without ($ea vs $es)"
        }
        assertTrue(fautes.joinToString("; "), fautes.isEmpty())
    }

    @Test fun `in tune, the dead band keeps it there`() {
        // Clean: nothing moves at all.
        assertEquals(0, AfcBanc.decode(son, fs, { 8.0 }, afc = true).corrections)
        // In noise the pulses read a few Hz apart: at most a step either way, never a walk.
        val r = AfcBanc.decode(son, fs, { 8.0 }, afc = true, bruit = 2500.0)
        println("AFC accordé (8 Hz, bruit) : ${r.corrections} pas, total ${r.totalHz} Hz")
        assertTrue(abs(r.totalHz) <= SstvAfc.PAS_MAX_HZ)
    }

    @Test fun `a slow drift is followed, by small steps`() {
        val pd = SstvMode.byName("PD 90")!!
        val s = SstvEncoder.encode(pd, mire(pd), fs, leadMs = 500.0, trailMs = 300.0)
        val ref = AfcBanc.decode(s, fs, { 0.0 }, afc = false).images.first()
        // Doppler-like: from +120 Hz, 1 Hz a second.
        for (pente in listOf(1.0, -1.0)) {
            val avec = AfcBanc.decode(s, fs, { 120.0 + pente * it }, afc = true, bruit = 2000.0)
            val sans = AfcBanc.decode(s, fs, { 120.0 + pente * it }, afc = false, bruit = 2000.0)
            val ea = AfcBanc.ecart(avec.images.first(), ref)
            val es = sans.images.firstOrNull()?.let { AfcBanc.ecart(it, ref) } ?: 255.0
            println("AFC dérive ${pente} Hz/s : avec ${"%.1f".format(ea)} (reste ${"%.0f".format(avec.resteFinHz)} Hz, ${avec.corrections} pas), sans ${"%.1f".format(es)}")
            assertTrue("residual ${avec.resteFinHz}", abs(avec.resteFinHz) < 25.0)
            // After the header's jump, only small steps.
            assertTrue(avec.pas.drop(1).all { abs(it) <= SstvAfc.PAS_MAX_HZ })
            assertTrue("$ea vs $es", ea <= es + 0.5)
        }
    }

    @Test fun `a rig's late sound, small steps only, still converging`() {
        val r = AfcBanc.decode(son, fs, { 150.0 }, afc = true, couplee = false, latenceMs = 600, bruit = 2500.0)
        assertTrue(r.pas.all { abs(it) <= SstvAfc.PAS_MAX_HZ })
        val e = r.images.firstOrNull()?.let { AfcBanc.ecart(it, reference) } ?: 255.0
        println("AFC poste (600 ms de retard) : 150 Hz → reste ${"%.0f".format(r.resteFinHz)} Hz, image ${"%.1f".format(e)}")
        assertTrue("residual ${r.resteFinHz}", abs(r.resteFinHz) < 60.0)
        assertTrue(e < 15.0)
    }

    @Test fun `LSB moves the receiver the other way`() {
        val r = AfcBanc.decode(son, fs, { 200.0 }, afc = true, sens = -1, bruit = 2500.0)
        assertEquals(200.0, -r.pas.sum().toDouble(), 20.0)
        assertTrue(abs(r.resteFinHz) < 20.0)
    }

    @Test fun `never further than the limit, nothing moved in FM`() {
        val a = SstvAfc(fs)
        val dec = SstvDecoder(fs)
        var pos = 0L
        val c = object : CibleAfc {
            override fun sens() = 1
            override val couplee = false
            override fun position() = pos
            override fun deplace(hz: Int) { pos += hz }
        }
        // A constant 400 Hz error reported for 200 s.
        val x = ShortArray(fs / 4)
        repeat(800) { a.nourrit(dec, x, x.size, c); repeat(6) { a.ecart.sync(400.0, 10.0) } }
        assertEquals(SstvAfc.LIMITE_HZ.toLong(), pos)
        val fm = SstvAfc(fs)
        var moved = false
        val cfm = object : CibleAfc {
            override fun sens() = 0
            override val couplee = true
            override fun position() = 0L
            override fun deplace(hz: Int) { moved = true }
        }
        repeat(40) { fm.nourrit(dec, x, x.size, cfm); repeat(6) { fm.ecart.sync(200.0, 10.0) } }
        assertTrue(!moved)
        assertEquals(200, fm.etat.mesureHz)
    }

    @Test fun `the operator retuning is the new reference, reset goes back`() {
        val a = SstvAfc(fs)
        val dec = SstvDecoder(fs)
        var pos = 0L
        val c = object : CibleAfc {
            override fun sens() = 1
            override val couplee = false
            override fun position() = pos
            override fun deplace(hz: Int) { pos += hz }
        }
        val x = ShortArray(fs / 4)
        repeat(40) { a.nourrit(dec, x, x.size, c); repeat(6) { a.ecart.sync(100.0, 10.0) } }
        assertTrue(a.totalHz > 0)
        pos += 5000
        val accorde = pos
        a.nourrit(dec, x, x.size, c)
        assertEquals(0, a.totalHz)
        repeat(12) { a.nourrit(dec, x, x.size, c); repeat(6) { a.ecart.sync(60.0, 10.0) } }
        val t = a.totalHz
        assertTrue(t > 0)
        a.remiseAZero(c)
        assertEquals(accorde, pos)
        assertEquals(0, a.totalHz)
    }

    @Test fun `the decoder's sync hook alone changes nothing`() {
        val s = SstvEncoder.encode(robot, mire(robot), fs)
        fun decode(hook: Boolean): IntArray {
            var px = IntArray(0)
            val d = SstvDecoder(fs, object : SstvDecoder.Listener {
                override fun onImage(mode: SstvMode, pixels: IntArray, linesDone: Int, complete: Boolean) { px = pixels.copyOf() }
            })
            if (hook) d.mesureSync = { _, _ -> }
            var i = 0
            while (i < s.size) { val n = minOf(2048, s.size - i); d.feed(s.copyOfRange(i, i + n), n); i += n }
            d.finish()
            return px
        }
        assertTrue(decode(false).contentEquals(decode(true)))
    }
}
