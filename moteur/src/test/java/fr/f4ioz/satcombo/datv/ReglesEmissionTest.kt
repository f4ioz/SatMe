/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv

import fr.f4ioz.satcombo.datv.s2.Modcod
import fr.f4ioz.satcombo.datv.s2.Rolloff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The safety rules of a DATV transmission and the closed loop on the beacon. */
class ReglesEmissionTest {

    private fun d(khz: Long, sr: Int = 333, ro: Rolloff = Rolloff.A25, charge: Boolean = false, ind: String = "F4IOZ", gain: Double = -30.0, duree: Int = 600) =
        DemandeEmission(khz, sr, Modcod.de("QPSK 2/3")!!, ro, gain, duree, charge, ind)

    @Test
    fun plan_qo100_bande_et_balise() {
        assertEquals(2_402_000L, PlanQo100.BALISE_MONTEE_KHZ)
        // The band plan's channels, at their symbol rates, are all allowed (α 0.35 at most where it fits).
        for (c in PlanQo100.CANAUX) {
            val sr = Datv.DEBITS.filter { it <= c.srMaxKs }.max()
            val ro = if (c.srMaxKs >= 1000) Rolloff.A20 else Rolloff.A35
            assertNull("${c.descenteKhz} $sr", ReglesEmission.refus(d(c.monteeKhz, sr, ro), 0.0))
        }
        // The net channel 10499.25 (uplink 2409.75) at 333 kS/s.
        assertNull(ReglesEmission.refus(d(2_409_750L, 333, Rolloff.A35), 0.0))
        // Over the beacon, at its bottom edge, or off the top: refused.
        assertNotNull(ReglesEmission.refus(d(2_402_000L, 333), 0.0))
        assertTrue(ReglesEmission.refus(d(2_403_100L, 333, Rolloff.A35), 0.0)!!.contains("balise"))
        assertNotNull(ReglesEmission.refus(d(2_409_900L, 333), 0.0))
        assertNotNull(ReglesEmission.refus(d(2_409_750L, 500), 0.0))
        assertNotNull(ReglesEmission.refus(d(2_403_750L, 1500, Rolloff.A35), 0.0))
        assertNotNull(ReglesEmission.refus(d(435_000L, 333), 0.0))
        assertNotNull(ReglesEmission.refus(d(2_450_000L, 333), 0.0))
    }

    @Test
    fun charge_fictive_toute_frequence_gain_bride() {
        assertNull(ReglesEmission.refus(d(2_450_000L, charge = true), 0.0))
        assertNull(ReglesEmission.refus(d(1_255_000L, charge = true), 0.0))
        assertNotNull(ReglesEmission.refus(d(10_000L, charge = true), 0.0))
        assertEquals(-40.0, ReglesEmission.gain(d(2_450_000L, charge = true, gain = 0.0), 0.0), 0.0)
        assertEquals(-55.0, ReglesEmission.gain(d(2_450_000L, charge = true, gain = -55.0), 0.0), 0.0)
        assertEquals(-30.0, ReglesEmission.gain(d(2_405_250L, gain = -30.0), -10.0), 0.0)
        assertEquals(-10.0, ReglesEmission.gain(d(2_405_250L, gain = 0.0), -10.0), 0.0)
        assertEquals(0.0, ReglesEmission.gain(d(2_405_250L, gain = 7.0), 3.0), 0.0)
        assertEquals(-89.75, ReglesEmission.gain(d(2_405_250L, gain = -120.0), 0.0), 0.0)
    }

    @Test
    fun indicatif_obligatoire_duree_bornee() {
        assertTrue(ReglesEmission.refus(d(2_405_250L, ind = ""), 0.0)!!.contains("indicatif"))
        assertNotNull(ReglesEmission.refus(d(2_405_250L, ind = "SATME"), 0.0))
        assertNull(ReglesEmission.refus(d(2_405_250L, ind = "F4IOZ/P"), 0.0))
        assertNotNull(ReglesEmission.refus(d(2_405_250L, duree = 3600), 0.0))
        assertNull(ReglesEmission.refus(d(2_405_250L, duree = 1800), 0.0))
        assertEquals(1800, ReglesEmission.dureeS(5000))
        assertNotNull(ReglesEmission.refus(d(2_405_250L, sr = 400), 0.0))
    }

    private fun di(khz: Long, sr: Int = 333, ro: Rolloff = Rolloff.A35, qui: Emetteur = Emetteur.PLUTO, duree: Int = 300, gain: Double = 0.0) =
        DemandeEmission(khz, sr, Modcod.de("QPSK 2/3")!!, ro, gain, duree, false, "F4IOZ", interieur = true, emetteur = qui)

    @Test
    fun essai_en_interieur_bandes_amateur_puissance_minimale_duree_courte() {
        for (qui in Emetteur.entries) {
            // 23 cm and 13 cm, the whole signal inside.
            assertNull(ReglesEmission.refus(di(PlanInterieur.DEFAUT_KHZ, qui = qui), 0.0))
            assertNull(ReglesEmission.refus(di(1_240_300L, qui = qui), 0.0))
            assertNull(ReglesEmission.refus(di(2_449_700L, qui = qui), 0.0))
            assertNull(ReglesEmission.refus(di(2_350_000L, 2000, qui = qui), 0.0))
            // An edge outside, between the bands, above 2450 MHz, below 1240 MHz: refused.
            assertTrue(ReglesEmission.refus(di(1_240_100L, qui = qui), 0.0)!!.contains("1240–1300"))
            assertNotNull(ReglesEmission.refus(di(1_299_900L, qui = qui), 0.0))
            assertNotNull(ReglesEmission.refus(di(1_800_000L, qui = qui), 0.0))
            assertNotNull(ReglesEmission.refus(di(2_450_000L, qui = qui), 0.0))
            assertNotNull(ReglesEmission.refus(di(435_000L, qui = qui), 0.0))
            // 10 min at most.
            assertNull(ReglesEmission.refus(di(1_255_000L, qui = qui, duree = 600), 0.0))
            assertTrue(ReglesEmission.refus(di(1_255_000L, qui = qui, duree = 900), 0.0)!!.contains("intérieur"))
            assertTrue(ReglesEmission.refus(di(1_255_000L, qui = qui).copy(indicatif = ""), 0.0)!!.contains("indicatif"))
            // Both modes at once: no.
            assertNotNull(ReglesEmission.refus(di(1_255_000L, qui = qui).copy(chargeFictive = true), 0.0))
        }
        assertEquals(300, PlanInterieur.dureeS(300))
        assertEquals(600, PlanInterieur.dureeS(3600))
        // The Pluto at −40 dB at most indoors.
        assertEquals(-40.0, ReglesEmission.gain(di(1_255_000L, gain = 0.0), 0.0), 0.0)
        assertEquals(-60.0, ReglesEmission.gain(di(1_255_000L, gain = -60.0), 0.0), 0.0)
        // The shared parts said; 1255 MHz quiet.
        assertNull(PlanInterieur.avertissement(1_255_000L, 333, 0.35))
        assertTrue(PlanInterieur.avertissement(1_270_000L, 333, 0.35)!!.contains("radionavigation"))
        assertTrue(PlanInterieur.avertissement(2_420_000L, 333, 0.35)!!.contains("Wi-Fi"))
        assertTrue(PlanInterieur.avertissement(2_399_900L, 333, 0.35)!!.contains("Wi-Fi"))
        assertNull(PlanInterieur.avertissement(2_320_000L, 333, 0.35))
    }

    @Test
    fun hackrf_jamais_vers_qo100_gain_bride() {
        val h = d(2_409_750L, 333, Rolloff.A35).copy(emetteur = Emetteur.HACKRF, gainDb = 0.0)
        assertEquals(ReglesEmission.HACKRF_PAS_QO100, ReglesEmission.refus(h, 0.0))
        assertNull(ReglesEmission.refus(h.copy(chargeFictive = true), 0.0))
        assertNull(ReglesEmission.refus(h.copy(chargeFictive = true, frequenceKhz = 50_000L), 0.0))
        assertNotNull(ReglesEmission.refus(h.copy(chargeFictive = true, frequenceKhz = 7_000_000L), 0.0))
        // The Pluto as before.
        assertNull(ReglesEmission.refus(d(2_409_750L, 333, Rolloff.A35), 0.0))
        assertNotNull(ReglesEmission.refus(d(50_000L, charge = true), 0.0))
        assertEquals(10, ReglesEmission.vgaHackrf(47.0))
        assertEquals(0, ReglesEmission.vgaHackrf(-3.0))
        assertEquals(6, ReglesEmission.vgaHackrf(6.9))
        assertEquals(0, ReglesEmission.vgaHackrf(Double.NaN))
    }

    @Test
    fun bladerf_comme_le_pluto() {
        val b = d(2_409_750L, 333, Rolloff.A35).copy(emetteur = Emetteur.BLADERF)
        assertNull(ReglesEmission.refus(b, 0.0))
        assertNotNull(ReglesEmission.refus(b.copy(frequenceKhz = 2_402_000L), 0.0))
        // Into a load: its own range, 47 MHz to 6 GHz.
        assertNull(ReglesEmission.refus(b.copy(chargeFictive = true, frequenceKhz = 50_000L), 0.0))
        assertTrue(ReglesEmission.refus(b.copy(chargeFictive = true, frequenceKhz = 40_000L), 0.0)!!.contains("bladeRF"))
        assertEquals(Emetteur.BLADERF, Emetteur.de("bladerf"))
        assertEquals(-40.0, ReglesEmission.gain(0.0, 0.0, charge = false, interieur = true), 0.0)
    }

    @Test
    fun echantillons_par_symbole_pour_le_pluto() {
        val attendu = mapOf(125 to 17, 250 to 9, 333 to 7, 500 to 5, 1000 to 3, 1500 to 2, 2000 to 2)
        for ((sr, k) in attendu) {
            assertEquals(sr.toString(), k, ReglesEmission.sps(sr))
            assertTrue(sr * 1000L * k >= ReglesEmission.DEBIT_MIN)
        }
    }

    @Test
    fun boucle_fermee_baisse_le_gain_quand_plus_fort_que_la_balise() {
        val b = BoucleNiveau()
        var gain = -20.0
        var t = 0L
        // Nothing heard: nothing done.
        assertNull(b.mesure(null, 12.0, 0, gain, t))
        assertTrue(b.etat.message.contains("ne reçoit pas"))
        // Beacon unknown.
        assertNull(b.mesure(10.0, null, 0, gain, t))
        // 3 dB under the beacon: fine.
        assertNull(b.mesure(9.0, 12.0, 0, gain, t))
        assertEquals(-3.0, b.etat.ecartDb!!, 1e-9)
        // Simulated: our MER follows the gain (+1 dB of gain, +1 dB of MER), starting 2 dB above the beacon.
        fun mer(g: Double) = 14.0 + (g + 20.0)
        var baisses = 0
        repeat(40) {
            t += 1000
            val n = b.mesure(mer(gain), 12.0, 200, gain, t)
            if (n != null) { assertEquals(gain - 1.0, n, 1e-9); gain = n; baisses++ }
        }
        // Down until 1 dB under the beacon, one step every 5 s, never more.
        assertEquals(-23.0, gain, 1e-9)
        assertEquals(3, baisses)
        assertTrue(!b.etat.tropFort)
        // A stale measure is not used.
        assertNull(b.mesure(30.0, 12.0, 10_000, gain, t + 10_000))
        assertTrue(b.mesure(30.0, 12.0, 100, gain, t + 20_000) != null)
        assertTrue(b.etat.message.startsWith("TROP FORT"))
    }
}
