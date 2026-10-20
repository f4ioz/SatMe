/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sdr

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The pass waterfall from a small synthetic SigMF recording: a drifting carrier brought back to the middle. */
class CascadeIqTest {

    private val dossier = File(System.getProperty("java.io.tmpdir"), "satme-cascade-" + System.nanoTime()).apply { mkdirs() }
    @After fun fin() { dossier.deleteRecursively() }

    private val debit = 96_000.0
    private val secondes = 6

    /** A carrier at [f] (Hz from the centre, drifting with time in s), written as ci16. */
    private fun ecrit(nom: String, f: (Double) -> Double): File {
        val n = (debit * secondes).toInt()
        val b = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN)
        var phase = 0.0
        val bruit = java.util.Random(1)
        for (i in 0 until n) {
            phase += 2 * PI * f(i / debit) / debit
            b.putShort((8000 * cos(phase) + 300 * bruit.nextGaussian()).toInt().toShort())
            b.putShort((8000 * sin(phase) + 300 * bruit.nextGaussian()).toInt().toShort())
        }
        return File(dossier, nom + SigMF.EXT_DATA).apply { writeBytes(b.array()) }
    }

    /** The column holding each line's strongest bin. */
    private fun pics(b: fr.f4ioz.satcombo.domain.SpectrePassage.Bande) = b.lignes.map { l -> l.db.indices.maxBy { l.db[it] } }

    @Test
    fun la_porteuse_suivie_par_les_captures_reste_au_milieu() {
        // The carrier drifts from +2 kHz to +8 kHz; the recording's captures followed it (its software share).
        val drift = { t: Double -> 2_000.0 + 1_000.0 * t }
        val data = ecrit("suivie", drift)
        val caps = (0 until secondes * 8).map { k -> SigMF.Capture((k * debit / 8).toLong(), 145_800_000L, 145_800_000L, drift(k / 8.0 + 0.0625)) }
        val meta = SigMF.Meta(SigMF.Format.CI16, debit, 1_000_000L, caps, satellite = "SO-50", norad = 27607)
        File(dossier, "suivie" + SigMF.EXT_META).writeText(SigMF.ecritMeta(meta))
        val relue = SigMF.litMeta(File(dossier, "suivie" + SigMF.EXT_META).readText())!!
        val b = CascadeIq.calcule(data, relue, largeurHz = 6_000.0, bins = 64, lignesMax = 20)
        assertEquals(20, b.lignes.size)
        assertEquals(27607, b.catnum)
        // Times from the meta's start, through the file.
        assertTrue(b.lignes.first().tMs in 1_000_000L..1_000_300L && b.lignes.last().tMs in 1_005_600L..1_006_000L)
        // Always within a few columns of the middle (a capture lasts an eighth of a second).
        pics(b).forEach { assertTrue("pic en $it", it in 26..38) }
        // Well above the noise.
        val r = fr.f4ioz.satcombo.domain.SpectrePassage.rapports(b)
        assertTrue(r.all { it.second > 20 })
    }

    @Test
    fun sans_captures_la_porteuse_derive_et_le_decalage_la_ramene() {
        val drift = { t: Double -> 1_000.0 * t - 2_000.0 }
        val data = ecrit("fixe", drift)
        val meta = SigMF.Meta(SigMF.Format.CI16, debit, 0L, listOf(SigMF.Capture(0, 145_800_000L)))
        val brut = pics(CascadeIq.calcule(data, meta, largeurHz = 12_000.0, bins = 64, lignesMax = 12))
        // Uncorrected: the carrier crosses the window.
        assertTrue("$brut", brut.last() - brut.first() > 20)
        // The Doppler worked out afterwards (here, the drift itself) brings it back.
        val corrige = pics(CascadeIq.calcule(data, meta, largeurHz = 12_000.0, bins = 64, lignesMax = 12) { t -> drift(t / 1000.0) })
        corrige.forEach { assertTrue("pic en $it : $corrige", it in 28..36) }
    }

    @Test
    fun un_fichier_cu8_trop_court_ne_donne_rien() {
        val f = File(dossier, "court" + SigMF.EXT_DATA).apply { writeBytes(ByteArray(100) { 127 }) }
        val b = CascadeIq.calcule(f, SigMF.Meta(SigMF.Format.CU8, 1_058_400.0, 0L))
        assertTrue(b.lignes.isEmpty())
    }
}
