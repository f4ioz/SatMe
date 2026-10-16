/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The transport stream's analysis on synthetic streams: drops, TEI, counter wraps, duplicates, sync, PCR. */
class AnalyseTsTest {

    /** A synthetic stream: PAT, PMT (0x1000: video 0x100 with the PCR, AAC 0x101), packets numbered. */
    class Fabrique(private val debit: Double = 429_800.0) {
        private val cc = HashMap<Int, Int>()
        var n = 0L; private set

        fun paquet(pid: Int, pcr: Boolean = false, tei: Boolean = false, charge: ByteArray? = null, pusi: Boolean = false, sansCharge: Boolean = false): ByteArray {
            val p = ByteArray(188) { 0xFF.toByte() }
            p[0] = 0x47
            p[1] = ((if (tei) 0x80 else 0) or (if (pusi) 0x40 else 0) or (pid shr 8)).toByte()
            p[2] = pid.toByte()
            val c = if (sansCharge || pid == 0x1FFF) cc[pid] ?: 0 else ((cc[pid] ?: -1) + 1) and 15
            if (pid != 0x1FFF && !sansCharge) cc[pid] = c
            var k = 4
            if (pcr || sansCharge) {
                p[3] = ((if (sansCharge) 0x20 else 0x30) or c).toByte()
                p[4] = (if (sansCharge) 183 else 7).toByte()
                p[5] = (if (pcr) 0x10 else 0).toByte()
                if (pcr) {
                    val v = (n * 1504 / debit * 27e6).toLong()
                    val base = v / 300; val ext = v % 300
                    p[6] = (base shr 25).toByte(); p[7] = (base shr 17).toByte(); p[8] = (base shr 9).toByte(); p[9] = (base shr 1).toByte()
                    p[10] = (((base and 1) shl 7) or 0x7E or (ext shr 8)).toInt().toByte(); p[11] = ext.toByte()
                }
                k = if (sansCharge) 188 else 12
            } else p[3] = (0x10 or c).toByte()
            charge?.copyInto(p, k, 0, minOf(charge.size, 188 - k))
            n++
            return p
        }

        fun pat() = paquet(0, pusi = true, charge = byteArrayOf(0, 0x00, 0xB0.toByte(), 13, 0, 1, 0xC1.toByte(), 0, 0, 0, 1, 0xF0.toByte(), 0x00, 0, 0, 0, 0))
        fun pmt() = paquet(0x1000, pusi = true, charge = byteArrayOf(0, 0x02, 0xB0.toByte(), 23, 0, 1, 0xC1.toByte(), 0, 0, 0xE1.toByte(), 0x00, 0xF0.toByte(), 0,
            0x1B, 0xE1.toByte(), 0x00, 0xF0.toByte(), 0, 0x0F, 0xE1.toByte(), 0x01, 0xF0.toByte(), 0, 0, 0, 0, 0))

        /** [k] packets of a realistic mix: tables now and then, video with a PCR every 10, some audio, some null. */
        fun suite(k: Int): List<ByteArray> = (0 until k).map {
            when {
                n % 100 == 0L -> pat()
                n % 100 == 1L -> pmt()
                n % 10 == 2L -> paquet(0x100, pcr = true)
                n % 7 == 3L -> paquet(0x101)
                n % 13 == 4L -> paquet(0x1FFF)
                else -> paquet(0x100)
            }
        }
    }

    private fun analyse(paquets: List<ByteArray>, bloc: Int = 510): AnalyseTs.Bilan {
        val a = AnalyseTs()
        val tout = paquets.fold(ByteArray(0)) { x, y -> x + y }
        var i = 0
        while (i < tout.size) { val n = minOf(bloc, tout.size - i); a.ajoute(tout.copyOfRange(i, i + n)); i += n }
        return a.bilan()
    }

    @Test
    fun un_flux_propre_coupe_n_importe_ou() {
        val f = Fabrique()
        val l = f.suite(1000)
        for (bloc in listOf(510, 188, 1, 1316, 7)) {
            val b = analyse(l, bloc)
            assertEquals(1000L, b.paquets)
            assertEquals(0L, b.erreursCc); assertEquals(0L, b.tei); assertEquals(0L, b.pertesSync); assertEquals(0L, b.doublons)
            assertTrue(b.pat && b.pmt == setOf(0x1000))
            assertEquals(mapOf(0x100 to 0x1B, 0x101 to 0x0F), b.types)
            assertEquals("H.264", AnalyseTs.role(0x100, b)); assertEquals("PMT", AnalyseTs.role(0x1000, b)); assertEquals("PAT", AnalyseTs.role(0, b))
            assertEquals(0x100, b.pcrPid)
        }
    }

    @Test
    fun des_paquets_perdus() {
        val l = Fabrique().suite(500).toMutableList()
        // Two video packets in a row lost, then one audio packet.
        val v = l.indices.filter { (l[it][1].toInt() and 0x1F) == 1 && l[it][2].toInt() == 0 && l[it][3].toInt() and 0x20 == 0 }
        l.removeAt(v[40]); l.removeAt(v[40])
        val audio = l.indices.filter { (l[it][1].toInt() and 0x1F) == 1 && l[it][2].toInt() == 1 }
        l.removeAt(audio[audio.size - 3])
        val b = analyse(l)
        assertEquals(2L, b.erreursCc)
        assertEquals(3L, b.manquants)
        assertEquals(1L, b.pids.first { it.pid == 0x100 }.erreursCc); assertEquals(2L, b.pids.first { it.pid == 0x100 }.manquants)
        assertEquals(1L, b.pids.first { it.pid == 0x101 }.manquants)
    }

    @Test
    fun le_compteur_qui_reboucle_les_doublons_et_les_paquets_sans_charge() {
        val f = Fabrique()
        val l = ArrayList<ByteArray>()
        repeat(40) { l += f.paquet(0x200) }            // 0..15, 0..15, 0..7: wraps twice
        l += l.last().copyOf()                          // a duplicate: allowed
        l += f.paquet(0x200, sansCharge = true)         // adaptation only: the counter stays
        l += f.paquet(0x200)
        val b = analyse(l)
        assertEquals(0L, b.erreursCc)
        assertEquals(1L, b.doublons)
        assertEquals(43L, b.paquets)
    }

    @Test
    fun les_erreurs_de_transport_et_la_synchro() {
        val f = Fabrique()
        val l = ArrayList<ByteArray>()
        repeat(10) { l += f.paquet(0x300) }
        l += f.paquet(0x300, tei = true)
        repeat(10) { l += f.paquet(0x300) }
        val avec = l.subList(0, 15).fold(ByteArray(0)) { x, y -> x + y } + byteArrayOf(1, 2, 0x47, 3, 4) + l.subList(15, l.size).fold(ByteArray(0)) { x, y -> x + y }
        val a = AnalyseTs(); a.ajoute(avec)
        val b = a.bilan()
        assertEquals(1L, b.tei)
        assertEquals(1L, b.pertesSync)
        assertEquals(21L, b.paquets)
        // The packet with TEI is not followed: one gap seen on its PID.
        assertEquals(1L, b.erreursCc)
    }

    @Test
    fun le_pcr_dit_ce_qui_manque() {
        val f = Fabrique()
        val l = f.suite(3000).toMutableList()
        val propre = analyse(l)
        assertNotNull(propre.debitPcr)
        assertEquals(429_800.0, propre.debitPcr!!, 500.0)
        assertEquals(1.0, propre.ratioPcr(429_800.0)!!, 0.01)
        // Whole frames lost (QPSK 2/3: 28.56 packets each): 29, 57, 28, 86 packets in a row, here and there.
        for ((debut, k) in listOf(300 to 29, 900 to 57, 1500 to 28, 2200 to 86)) repeat(k) { l.removeAt(debut) }
        val b = analyse(l)
        assertEquals(3000L - 200, b.paquets)
        assertEquals((3000.0 - 200) / 3000, b.ratioPcr(429_800.0)!!, 0.02)
        val manques = b.manquesParTrou(429_800.0)
        assertEquals(4, manques.size)
        assertEquals(29.0, manques[0], 0.5); assertEquals(86.0, manques[3], 0.5)
        val ppt = QualiteDatv.paquetsParTrame("QPSK 2/3", false)
        assertTrue(QualiteDatv.tramesEntieres(manques, ppt))
        assertFalse(QualiteDatv.tramesEntieres(listOf(3.0, 7.0, 12.0, 45.0), ppt))
    }
}
