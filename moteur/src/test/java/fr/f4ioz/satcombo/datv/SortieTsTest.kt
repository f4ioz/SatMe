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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The stream passed on by UDP (where to, the datagrams), the stations heard. */
class SortieTsTest {

    @Test
    fun destinations_locales_seulement() {
        assertEquals(SortieUdpTs.Destination("127.0.0.1", 9080), SortieUdpTs.destination("127.0.0.1").first)
        assertEquals(SortieUdpTs.Destination("192.168.1.20", 1234), SortieUdpTs.destination(" 192.168.1.20:1234 ").first)
        assertNotNull(SortieUdpTs.destination("10.0.0.5:5000").first)
        assertNotNull(SortieUdpTs.destination("172.20.1.1:5000").first)
        assertNotNull(SortieUdpTs.destination("239.1.2.3:5000").first)
        // The internet, names, nonsense: refused, with a reason.
        for (t in listOf("8.8.8.8:53", "172.32.0.1", "example.org:9080", "1.2.3", "127.0.0.1:0", "127.0.0.1:70000", "", "300.1.1.1")) {
            val (d, pourquoi) = SortieUdpTs.destination(t)
            assertNull(t, d); assertNotNull(t, pourquoi)
        }
    }

    private fun paquet(k: Int) = ByteArray(188) { if (it == 0) 0x47 else (k + it).toByte() }

    @Test
    fun datagrammes_de_paquets_entiers() {
        val flux = java.io.ByteArrayOutputStream()
        flux.write(byteArrayOf(1, 2, 3))   // a broken start
        for (k in 0 until 15) flux.write(paquet(k))
        val tout = flux.toByteArray()
        val d = DecoupeTs()
        val recus = ArrayList<ByteArray>()
        // Blocks cut anywhere.
        var i = 0
        val tailles = intArrayOf(100, 7, 500, 1, 1316, 90)
        var k = 0
        while (i < tout.size) {
            val n = minOf(tailles[k++ % tailles.size], tout.size - i)
            d.ajoute(tout.copyOfRange(i, i + n)) { b, l -> recus += b.copyOf(l) }
            i += n
        }
        assertEquals(2, recus.size)
        recus.forEach { b -> assertEquals(1316, b.size); for (p in 0 until 7) assertEquals(0x47.toByte(), b[p * 188]) }
        assertEquals(paquet(0).toList(), recus[0].copyOfRange(0, 188).toList())
        assertEquals(paquet(7).toList(), recus[1].copyOfRange(0, 188).toList())
        val reste = ArrayList<Int>()
        d.vide { _, l -> reste += l }
        assertEquals(listOf(188), reste)
    }

    @Test
    fun synchro_perdue_reprise() {
        val d = DecoupeTs(parDatagramme = 2)
        val recus = ArrayList<ByteArray>()
        val faux = paquet(1).copyOf(100)   // cut short: the next packet's sync is not where expected
        d.ajoute(paquet(0) + faux + paquet(2) + paquet(3)) { b, l -> recus += b.copyOf(l) }
        assertEquals(1, recus.size)
        assertEquals(376, recus[0].size)
    }

    @Test
    fun stations_entendues() {
        val s = StationsEntendues(dureeMs = 1000_000L, max = 3)
        assertTrue(s.note("F4ABC", 10_494_750, 1500, 8.0, 1000))
        assertFalse(s.note("f4abc", 10_494_750, 1500, 9.5, 2000))
        assertTrue(s.note("F4ABC", 10_496_250, 1500, 7.0, 3000))   // moved: noted again
        assertFalse(s.note("  ", 0, 0, null, 3000))
        s.note("G1XYZ", 10_497_750, 333, null, 4000)
        val l = s.toutes()
        assertEquals(listOf("G1XYZ", "F4ABC"), l.map { it.nom })
        val f = l[1]
        assertEquals(1000L, f.premierMs); assertEquals(3000L, f.dernierMs); assertEquals(9.5, f.merMaxDb!!, 0.0)
        assertEquals(10_496_250L, f.khz)
        // Kept and taken back; the old ones forgotten, at most max.
        val t = s.texte()
        val r = StationsEntendues(dureeMs = 1000_000L, max = 3); r.reprends(t, 5000)
        assertEquals(l, r.toutes())
        r.reprends(t, 1_003_500L)
        assertEquals(listOf("G1XYZ"), r.toutes().map { it.nom })
        s.note("A", 1, 1, null, 5000); s.note("B", 1, 1, null, 6000)
        assertEquals(listOf("B", "A", "G1XYZ"), s.toutes().map { it.nom })
    }
}
