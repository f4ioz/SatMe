/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv

import fr.f4ioz.satcombo.plateforme.StockageMemoire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** QO-100's wide band spectrum: the frame's values, the signals found in it, the names, the « veille ». */
class BandeLargeTest {

    private val n = 922

    /** A flat noise with signals: centre (kHz), symbol rate (kS/s), height (units). */
    private fun spectre(vararg s: Triple<Long, Int, Int>): IntArray {
        val r = java.util.Random(7)
        val v = IntArray(n) { BandeLarge.BRUIT + r.nextInt(600) - 300 }
        for ((c, sr, h) in s) for (i in 0 until n) {
            val f = BandeLarge.khz(i.toDouble(), n)
            // A raised cosine (roll-off 0.35), in dB, as the spectrum is.
            val x = Math.abs(f - c) / sr
            val a = when { x <= 0.325 -> 1.0; x >= 0.675 -> 0.0; else -> 0.5 * (1 + Math.cos(Math.PI / 0.35 * (x - 0.325))) }
            if (a > 0) v[i] = maxOf(v[i], (BandeLarge.BRUIT + h + 10 * Math.log10(a) * BandeLarge.UNITES_PAR_DB).toInt())
        }
        return v
    }

    @Test
    fun debits_standard() {
        assertEquals(1500, BandeLarge.debit(1440.0))
        assertEquals(500, BandeLarge.debit(450.0))
        assertEquals(333, BandeLarge.debit(320.0))
        assertEquals(35, BandeLarge.debit(30.0))
        assertEquals(66, BandeLarge.debit(70.0))
        assertEquals(2000, BandeLarge.debit(2400.0))
    }

    @Test
    fun valeurs_petit_boutiste() {
        val v = BandeLarge.valeurs(byteArrayOf(0x34, 0x12, 0xFF.toByte(), 0xFF.toByte()))
        assertEquals(listOf(0x1234, 0xFFFF), v.toList())
    }

    @Test
    fun balise_et_signaux_synthetiques() {
        val v = spectre(Triple(Datv.BALISE_KHZ, 1500, 27_000), Triple(10_494_750L, 2000, 26_000), Triple(10_497_750L, 333, 20_000),
            Triple(10_498_500L, 125, 15_000), Triple(10_499_000L, 66, 14_000))
        val l = BandeLarge.detecte(v)
        assertEquals(l.toString(), 5, l.size)
        val b = l[0]
        assertTrue(b.balise)
        assertEquals(1500, b.srKs)
        assertTrue(Math.abs(b.centreKhz - Datv.BALISE_KHZ) < 20)
        assertEquals(0.0, b.parRapportBaliseDb!!, 0.01)
        assertEquals(listOf(1500, 2000, 333, 125, 66), l.map { it.srKs })
        // 2 MS at 1000 units under the beacon: −0.3 dB, over the beacon's line (−0.75 dB).
        assertEquals(-0.3, l[1].parRapportBaliseDb!!, 0.15)
        assertTrue(l[1].auDessusBalise)
        // Narrow ones are never said over the beacon.
        assertFalse(l[2].auDessusBalise)
        assertTrue(Math.abs(l[2].centreKhz - 10_497_750L) < 20)
    }

    @Test
    fun signal_large_sous_la_balise() {
        val l = BandeLarge.detecte(spectre(Triple(Datv.BALISE_KHZ, 1500, 27_000), Triple(10_496_250L, 1000, 22_000)))
        assertEquals(2, l.size)
        assertEquals(1000, l[1].srKs)
        assertFalse(l[1].auDessusBalise)
        assertTrue(l[1].parRapportBaliseDb!! < -1.0)
    }

    @Test
    fun deux_signaux_voisins_separes() {
        // Two 333 kS/s 400 kHz apart: their skirts touch over the threshold.
        val l = BandeLarge.detecte(spectre(Triple(10_497_750L, 333, 20_000), Triple(10_498_150L, 333, 16_000)))
        assertEquals(l.toString(), 2, l.size)
        assertEquals(listOf(333, 333), l.map { it.srKs })
        assertNull(l[0].parRapportBaliseDb)
    }

    @Test
    fun bruit_seul() {
        assertTrue(BandeLarge.detecte(spectre()).isEmpty())
    }

    @Test
    fun trames_reelles() {
        val b = javaClass.getResourceAsStream("/datv/bande-large-qo100.bin")!!.readBytes()
        assertEquals(0, b.size % 1844)
        val trames = (0 until b.size / 1844).map { BandeLarge.valeurs(b.copyOfRange(it * 1844, (it + 1) * 1844)) }
        assertEquals(922, trames[0].size)
        for (t in trames + listOf(BandeLarge.moyenne(trames))) {
            val l = BandeLarge.detecte(t)
            val b0 = l.firstOrNull { it.balise }
            assertNotNull(l.toString(), b0)
            assertEquals(1500, b0!!.srKs)
            assertTrue(l.toString(), Math.abs(b0.centreKhz - Datv.BALISE_KHZ) < 60)
            // Every signal within the band, none twice.
            assertTrue(l.all { it.centreKhz in 10_490_500L..10_499_500L })
            assertEquals(l.size, l.map { it.centreKhz / 100 }.toSet().size)
        }
        val l = BandeLarge.detecte(BandeLarge.moyenne(trames))
        assertTrue(l.toString(), l.size >= 3)
    }

    @Test
    fun noms_retenus_et_oublies() {
        val m = NomsSignaux()
        assertTrue(m.note(10_497_750L, 333, "F4IOZ", 0L))
        assertFalse(m.note(10_497_760L, 333, "F4IOZ", 1000L))
        assertEquals("F4IOZ", m.nom(10_497_700L, 333, 2000L))
        assertNull(m.nom(10_497_750L, 500, 2000L))
        assertNull(m.nom(10_498_250L, 333, 2000L))
        // Persisted, taken back, forgotten after 24 h.
        val s = StockageMemoire()
        s.edit().putString("k", m.texte()).apply()
        val m2 = NomsSignaux(); m2.reprends(s.getString("k", null), 10_000L)
        assertEquals("F4IOZ", m2.nom(10_497_750L, 333, 10_000L))
        assertNull(m2.nom(10_497_750L, 333, 25 * 3600_000L))
        val m3 = NomsSignaux(); m3.reprends(m.texte(), 25 * 3600_000L)
        assertTrue(m3.tous().isEmpty())
    }

    private fun sg(khz: Long, sr: Int, balise: Boolean = false) = SignalLarge(khz - sr / 2, khz + sr / 2, khz, sr.toLong(), sr, 20_000, 0.0, balise, false)

    @Test
    fun veille_tourne_et_reste_sur_l_accroche() {
        val v = VeilleDatv(attenteMs = 12_000L)
        val l = listOf(sg(Datv.BALISE_KHZ, 1500, true), sg(10_494_750L, 2000), sg(10_497_750L, 333), sg(10_498_250L, 333))
        assertNull(v.tic(0L, l, false))   // not started
        v.demarre()
        assertEquals(VeilleDatv.Cible(10_494_750L, 2000), v.tic(0L, l, false))
        assertNull(v.tic(5_000L, l, false))
        assertEquals(VeilleDatv.Cible(10_497_750L, 333), v.tic(12_000L, l, false))
        // Locked: held as long as it lasts, then 12 s more.
        assertNull(v.tic(15_000L, l, true))
        assertNull(v.tic(60_000L, l, true))
        assertNull(v.tic(71_000L, l, false))
        assertEquals(VeilleDatv.Cible(10_498_250L, 333), v.tic(72_000L, l, false))
        // Around to the lowest, the beacon left out.
        assertEquals(VeilleDatv.Cible(10_494_750L, 2000), v.tic(84_000L, l, false))
        v.arrete("réglé à la main")
        assertFalse(v.active)
        assertNull(v.tic(200_000L, l, false))
    }

    @Test
    fun veille_avec_la_balise_et_sans_signal() {
        val v = VeilleDatv(avecBalise = true); v.demarre()
        assertEquals(VeilleDatv.Cible(Datv.BALISE_KHZ, 1500), v.tic(0L, listOf(sg(Datv.BALISE_KHZ, 1500, true)), false))
        val w = VeilleDatv(); w.demarre()
        assertNull(w.tic(0L, listOf(sg(Datv.BALISE_KHZ, 1500, true)), false))
        assertEquals("aucun signal occupé", w.etat)
    }

    @Test
    fun le_reglage_du_node_repris_avec_son_alimentation_lnb() {
        val node = ReglageDatv(cielKhz = 10_494_750L, srKs = 333, olKhz = 9_749_766L, entreeB = true, lnb = "h")
        val j = Datv.json(EtatDatv(actif = true), node, emptyList())
        assertEquals(node.copy(son = false), Datv.reglageDe(j, ReglageDatv(son = false)))
        // An older node says no supply: the one known here is kept.
        j.remove("lnb")
        assertEquals("v", Datv.reglageDe(j, ReglageDatv(lnb = "v")).lnb)
        // The rates scanned go to the node and come back; an older node says none: kept.
        val b = node.copy(balayage = listOf(500, 250))
        assertEquals(listOf(500, 250), Datv.reglageDe(Datv.json(EtatDatv(), b, emptyList()), ReglageDatv()).balayage)
        j.remove("balayage")
        assertEquals(listOf(500), Datv.reglageDe(j, ReglageDatv(balayage = listOf(500))).balayage)
        assertEquals(listOf(333, 66, 500), Datv.balayageDe("333, 66;x,500,2,333,1000"))
    }
}
