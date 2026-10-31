/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.audio

import fr.f4ioz.satcombo.sstv.SstvMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

/** « Son perdu »: the holes found in a capture, their words, a recording's verdict. */
class SonPerduTest {

    @Test
    fun les_trous_sont_comptes_et_dits() {
        val p = SonPerdu()
        p.note(SonPerdu.Perte(1_000L, 420L, SonPerdu.Cause.CARTE, true))
        p.note(SonPerdu.Perte(5_000L, 3_100L, SonPerdu.Cause.CARTE, false))
        p.note(SonPerdu.Perte(9_000L, 80L, SonPerdu.Cause.FILE, true))
        assertEquals(SonPerdu.Bilan(3, 3_600L, 9_000L, 1), p.total())
        assertEquals(SonPerdu.Bilan(2, 3_180L, 9_000L, 1), p.entre(4_000L))
        assertEquals("son perdu : 3,2 s pendant ce passage (2 trous, dont 1 trop longs pour être comblés) — PC surchargé (charge 34 pour 12 cœurs)",
            SonPerdu.texte(p.entre(4_000L), "pendant ce passage", 34.2, 12))
        assertEquals("son perdu pendant l'image : 1,2 s", SonPerdu.pendantImage(1_200))
        assertEquals("", SonPerdu.pendantImage(0))
        assertEquals("850 ms", SonPerdu.duree(850)); assertEquals("2 min 05 s", SonPerdu.duree(125_000))
        assertEquals(24.75, SonPerdu.charge("24.75 25.92 31.29 28/3256 159999\n")!!, 1e-9)
        assertNull(SonPerdu.charge(null)); assertNull(SonPerdu.charge("rien"))
    }

    @Test
    fun la_derive_n_est_pas_un_trou_un_saut_l_est() {
        val rate = 48_000
        val t = TrousCapture(rate)
        var ns = 0L
        var lus = 0L
        var trous = 0
        // 400 ppm slow, read every 2048 samples, the buffer level given; then 250 ms thrown.
        for (k in 0 until 20_000) {
            val produits = ((k + 1) * 2048L)
            ns = (produits / (rate * (1 - 400e-6)) * 1e9).toLong() + (k % 7) * 300_000L
            if (k >= 15_000) ns += 250_000_000L
            val v = t.bloc(2048, ns, 0)
            lus += 2048
            if (!v.rien) { trous++; assertTrue("$k ${v.manquants}", Math.abs(v.manquants - rate / 4) < rate * 0.01) }
        }
        assertEquals(1, trous)
        assertTrue("${t.ppm}", Math.abs(t.ppm + 400) < 40)
    }

    @Test
    fun un_trou_trop_long_est_une_coupure() {
        val rate = 8_000
        val t = TrousCapture(rate)
        var ns = 0L
        for (k in 0 until 200) { ns += 128_000_000L; assertTrue(t.bloc(1024, ns, 0).rien) }
        val v = t.bloc(1024, ns + 128_000_000L + 5_000_000_000L, 0)
        assertEquals(0, v.manquants)
        assertEquals(5_000L, v.coupureMs)
        // Set right again: the next block is on time.
        assertTrue(t.bloc(1024, ns + 2 * 128_000_000L + 5_000_000_000L, 0).rien)
    }

    private fun wav(f: File, rate: Int, echantillons: Int) {
        RandomAccessFile(f, "rw").use { r ->
            val b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray()).putInt(36 + 2 * echantillons).put("WAVE".toByteArray())
            b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
            b.put("data".toByteArray()).putInt(2 * echantillons)
            r.write(b.array()); r.setLength(44L + 2 * echantillons)
        }
    }

    @Test
    fun un_enregistrement_trop_court_pour_le_temps_qu_il_a_dure_est_signale() {
        val d = File(System.getProperty("java.io.tmpdir"), "satme-bilan-" + System.nanoTime()).apply { mkdirs() }
        try {
            // Without a sidecar: the name's start and the last write — 547,8 s of sound for 566 s.
            val f = File(d, "SatMe_SAKHACUBE-CHOLBON_20261031_211623Z.wav")
            wav(f, 24_000, (547.8 * 24_000).toInt())
            val debut = SstvMeta.debutEnregistrement(f.name)
            f.setLastModified(debut + 566_000L)
            val b = BilanEnregistrement.bilan(f, debut)!!
            assertFalse(b.parTemps)
            assertTrue(b.perte)
            assertEquals("547,8 s de son pour 566,0 s : son perdu 18,2 s manquent (3,2 %)", b.texte())
            // A whole one: a second's rounding is no loss.
            val g = File(d, "SatMe_ISS_20261031_103655Z.wav")
            wav(g, 24_000, 707 * 24_000)
            g.setLastModified(SstvMeta.debutEnregistrement(g.name) + 708_600L)
            assertFalse(BilanEnregistrement.bilan(g, SstvMeta.debutEnregistrement(g.name))!!.perte)
            // With the recorder's sidecar: the silence put in the holes is the loss.
            val h = File(d, "SatMe_ISS_20261031_121054Z.wav")
            wav(h, 24_000, 600 * 24_000)
            BilanEnregistrement.fichierTemps(h).writeText(BilanEnregistrement.Temps(1_000L, 601_000L, 600L * 24_000, 24_000, 3, 12_000L, 0L).encode())
            val c = BilanEnregistrement.bilan(h, 0L)!!
            assertTrue(c.parTemps && c.perte)
            assertEquals("600,0 s de son pour 600,0 s : son perdu 12,0 s comblés de silence (3 trous) (2,0 %)", c.texte())
            assertNull(BilanEnregistrement.bilan(File(d, "absent.wav"), 0L))
        } finally { d.deleteRecursively() }
    }

    @Test
    fun la_fiche_d_une_image_garde_le_son_perdu() {
        val s = SstvMeta.SstvShot(fileName = "SatMe_SSTV_ISS_20261031_211800Z_Robot36.png", satName = "ISS", sonPerduMs = 1_200L)
        val t = SstvMeta.encode(s)
        assertTrue(t, t.contains("sonPerdu=1200"))
        assertEquals(1_200L, SstvMeta.decode(s.fileName, t).sonPerduMs)
        assertEquals(0L, SstvMeta.decode(s.fileName, SstvMeta.encode(s.copy(sonPerduMs = 0L))).sonPerduMs)
    }
}
