/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sdr

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** The node's stream: its preamble, its frames, the gaps counted; the half-band steps down and up. */
class FluxNoeudTest {

    @Test
    fun preambule_et_trames_relus_tels_quels() {
        val o = ByteArrayOutputStream()
        FluxNoeud.ecritPreambule(o, JSONObject().put("debit", 1_058_400).put("centre", 739_750_000L).put("format", FluxNoeud.FORMAT))
        val e = FluxNoeud.Ecrivain(o) { 1234L }
        val v = shortArrayOf(1, -1, 32767, -32768, 0, 300)
        e.iq(v, v.size, 739_750_000L, 1_058_400)
        e.texte("centre 739800000")
        e.vie()
        val i = ByteArrayInputStream(o.toByteArray())
        val p = FluxNoeud.litPreambule(i)
        assertEquals(1_058_400, p.getInt("debit")); assertEquals("cs16le", p.getString("format"))
        val l = FluxNoeud.Lecteur(i)
        val a = l.lit()
        assertEquals(FluxNoeud.IQ, a.type); assertEquals(0L, a.seq); assertEquals(1234L, a.tMs)
        assertEquals(739_750_000L, a.centreHz); assertEquals(1_058_400, a.debit)
        assertArrayEquals(v, a.iq)
        val b = l.lit()
        assertEquals(FluxNoeud.TEXTE, b.type); assertEquals("centre 739800000", b.texte)
        assertEquals(FluxNoeud.VIE, l.lit().type)
        assertEquals(0L, l.perdues)
        try { l.lit(); fail() } catch (_: EOFException) {}
    }

    @Test
    fun une_trame_sautee_est_comptee() {
        val o = ByteArrayOutputStream()
        val e = FluxNoeud.Ecrivain(o)
        val v = ShortArray(8)
        e.iq(v, 8, 1L, 1); e.saute(); e.saute(); e.iq(v, 8, 1L, 1); e.saute(); e.iq(v, 8, 1L, 1)
        val l = FluxNoeud.Lecteur(ByteArrayInputStream(o.toByteArray()))
        assertEquals(0L, l.lit().seq); assertEquals(3L, l.lit().seq); assertEquals(5L, l.lit().seq)
        assertEquals(3L, l.perdues)
    }

    @Test
    fun un_flux_corrompu_est_refuse() {
        try { FluxNoeud.litPreambule(ByteArrayInputStream("HTTP/1.1 403".toByteArray() + ByteArray(20))); fail() } catch (_: IOException) {}
        val t = ByteArray(32); "SMFI".toByteArray().copyInto(t); t[28] = 0xFF.toByte(); t[29] = 0xFF.toByte(); t[30] = 0xFF.toByte(); t[31] = 0x7F
        try { FluxNoeud.Lecteur(ByteArrayInputStream(t)).lit(); fail() } catch (x: IOException) { assertTrue(x.message!!.contains("taille")) }
    }

    @Test
    fun commandes_bornees() {
        assertEquals(FluxNoeud.Commande("centre", 739_800_000L), FluxNoeud.litCommande("centre 739800000"))
        assertEquals(FluxNoeud.Commande("gain", null), FluxNoeud.litCommande("gain auto"))
        assertEquals(FluxNoeud.Commande("gain", 300L), FluxNoeud.litCommande("gain 300"))
        assertNull(FluxNoeud.litCommande("gain 5000"))
        assertNull(FluxNoeud.litCommande("tx on"))
        assertNull(FluxNoeud.litCommande("centre"))
        assertNull(FluxNoeud.litCommande("ppm 999"))
    }

    // ------------------------------------------------------------ the half-band steps

    private fun ton(hz: Double, debit: Double, paires: Int, a: Double = 8000.0): ShortArray = ShortArray(2 * paires).also { s ->
        for (k in 0 until paires) { val ph = 2 * PI * hz * k / debit; s[2 * k] = (a * cos(ph)).roundToInt().toShort(); s[2 * k + 1] = (a * sin(ph)).roundToInt().toShort() }
    }

    /** The amplitude of [hz] in [s] (a single-bin correlation over the second half: the filters settled). */
    private fun niveau(s: ShortArray, n: Int, hz: Double, debit: Double): Double {
        val paires = n / 2
        var re = 0.0; var im = 0.0
        for (k in paires / 2 until paires) {
            val ph = -2 * PI * hz * k / debit
            val i = s[2 * k].toDouble(); val q = s[2 * k + 1].toDouble()
            re += i * cos(ph) - q * sin(ph); im += i * sin(ph) + q * cos(ph)
        }
        return hypot(re, im) / (paires - paires / 2)
    }

    @Test
    fun decimation_garde_la_bande_et_coupe_le_reste() {
        val fs = Dsp.RTL_RATE.toDouble()
        val d = DecimateurDemiBande(1)
        val e = ton(100_000.0, fs, 40_000)
        val s = ShortArray(e.size)
        val n = d.traite(e, e.size, s)
        assertEquals(e.size / 2, n)
        assertEquals(8000.0, niveau(s, n, 100_000.0, fs / 2), 80.0)
        // 400 kHz would fold to −129.2 kHz: far below.
        val d2 = DecimateurDemiBande(1)
        val e2 = ton(400_000.0, fs, 40_000)
        val n2 = d2.traite(e2, e2.size, s)
        val alias = niveau(s, n2, 400_000.0 - fs / 2, fs / 2)
        assertTrue("repliement à ${20 * Math.log10(alias / 8000)} dB", alias < 8000 * 1e-3)
    }

    @Test
    fun decimation_par_blocs_comme_d_un_coup() {
        val fs = Dsp.RTL_RATE.toDouble()
        val e = ton(37_000.0, fs, 10_000)
        val a = ShortArray(e.size); val na = DecimateurDemiBande(2).traite(e, e.size, a)
        val d = DecimateurDemiBande(2)
        val b = ShortArray(e.size); var nb = 0
        var k = 0
        var tour = 0
        // Odd cuts: blocks of 7 pairs, then 13, then 7…
        while (k < e.size) {
            val m = minOf(e.size - k, if (tour++ % 2 == 0) 14 else 26)
            val sortie = ShortArray(m + 2)
            val r = d.traite(e.copyOfRange(k, k + m), m, sortie)
            System.arraycopy(sortie, 0, b, nb, r); nb += r
            k += m
        }
        assertEquals(na, nb); assertArrayEquals(a.copyOf(na), b.copyOf(nb))
    }

    @Test
    fun interpolation_rend_le_ton_sans_image() {
        val fs = Dsp.RTL_RATE / 2.0
        val u = InterpolateurDemiBande(1)
        val e = ton(100_000.0, fs, 20_000)
        val s = ShortArray(2 * e.size)
        val n = u.traite(e, e.size, s)
        assertEquals(2 * e.size, n)
        assertEquals(8000.0, niveau(s, n, 100_000.0, 2 * fs), 80.0)
        val image = niveau(s, n, 100_000.0 - fs, 2 * fs)
        assertTrue("image à ${20 * Math.log10(image / 8000)} dB", image < 8000 * 1e-3)
        // The phase goes on smoothly: no half-sample jump between the two branches.
        val k = n / 2 - 2
        val d1 = atan2(s[k + 1].toDouble(), s[k].toDouble()); val d2 = atan2(s[k + 3].toDouble(), s[k + 2].toDouble())
        var dp = d2 - d1; while (dp > PI) dp -= 2 * PI; while (dp < -PI) dp += 2 * PI
        assertEquals(2 * PI * 100_000.0 / (2 * fs), dp, 0.01)
    }

    @Test
    fun aller_retour_quatre_fois() {
        val fs = Dsp.RTL_RATE.toDouble()
        val e = ton(-60_000.0, fs, 40_000)
        val d = ShortArray(e.size); val nd = DecimateurDemiBande(2).traite(e, e.size, d)
        assertEquals(e.size / 4, nd)
        val u = ShortArray(4 * nd); val nu = InterpolateurDemiBande(2).traite(d, nd, u)
        assertEquals(e.size, nu)
        assertEquals(8000.0, niveau(u, nu, -60_000.0, fs), 120.0)
    }
}
