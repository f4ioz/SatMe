/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.cat.Ft817Pair
import fr.f4ioz.satcombo.cat.Thd72
import fr.f4ioz.satcombo.cat.Thd72Bande
import fr.f4ioz.satcombo.cat.Thd72Lien
import fr.f4ioz.satcombo.cat.Thd72Sim
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The TH-D72 as a full-duplex satellite rig: its two bands, on the simulator that answers like the real one. */
class Thd72Test {

    private val reelA = "FO 0,0144800000,0,0,0,1,0,0,0,00,08,000,0,00600000,0"

    @Test
    fun protocole() {
        val c = Thd72.champs(reelA, 0)!!
        assertEquals(144_800_000L, Thd72.frequence(c))
        assertEquals(5_000L, Thd72.pas(c))
        assertNull(Thd72.champs(reelA, 1))       // band B asked, band A answered
        assertNull(Thd72.champs("N", 0))
        assertEquals(436_795_000L, Thd72.arrondi(436_796_900L, 5_000))
        assertEquals(436_800_000L, Thd72.arrondi(436_797_600L, 5_000))
        assertEquals("FO 0,0145850000,0,0,0,1,0,0,0,00,08,000,0,00600000,0",
            Thd72.commande(Thd72.avecFrequence(c, 145_850_000L)))
        val ton = Thd72.avecTon(Thd72.champs("FO 1,0435850000,0,0,0,0,0,0,0,09,04,000,0,01600000,0", 1)!!, 670)
        assertEquals("1", ton[5]); assertEquals("00", ton[9])
        assertEquals("0", Thd72.avecTon(c, 0)[5])
    }

    private fun banc(): Pair<Thd72Lien, Thd72Sim> {
        val sim = Thd72Sim()
        val lien = Thd72Lien().also { it.attach(sim); it.pacingMs = 0 }
        return lien to sim
    }

    @Test
    fun une_bande_arrondie_au_pas_et_sans_ecriture_inutile() = runBlocking {
        val (lien, sim) = banc()
        val b = Thd72Bande(lien, 1)
        assertTrue(lien.estUnThd72())
        assertEquals(435_850_000L, b.readFrequency())
        assertTrue(b.setFrequency(435_852_700L))       // → 435.855
        assertEquals(435_855_000L, b.readFrequency())
        val n = sim.ecritures.size
        assertTrue(b.setFrequency(435_856_100L))       // same 5 kHz channel: nothing sent
        assertEquals(n, sim.ecritures.size)
        assertTrue(b.setMode("FM")); assertFalse(b.setMode("USB"))
        assertTrue(b.setCtcss(670))
        assertEquals("1", sim.bandes[1][5]); assertEquals("00", sim.bandes[1][9])
        assertNull(b.isTransmitting())
    }

    @Test
    fun bande_ptt_et_puissance() = runBlocking {
        val (lien, sim) = banc()
        assertTrue(lien.choisitBande(1)); assertEquals(1, sim.bandeCourante); assertEquals(1, lien.bandeCourante())
        assertTrue(lien.reglePuissance(0, 2)); assertEquals(2, lien.puissance(0))
    }

    @Test
    fun paire_so50_montee_bande_a_descente_bande_b() = runBlocking {
        val sim = Thd72Sim()
        val paire = Ft817Pair()
        paire.configureThd72(bandeTx = 0)
        paire.rx.attach(sim); paire.pacingMs = 0
        assertTrue(paire.rx.isOpen && paire.tx.isOpen)   // one line, both sides
        // SO-50 at AOS: downlink 436.795 MHz + 9.4 kHz of Doppler, uplink 145.850 MHz − 3.1 kHz.
        paire.setPair(436_804_400L, 145_846_900L)
        assertEquals(436_805_000L, Thd72.frequence(sim.bandes[1]))   // band B receives
        assertEquals(145_845_000L, Thd72.frequence(sim.bandes[0]))   // band A transmits
        assertEquals(436_805_000L, paire.readDownlink())
        // Mid-pass, Doppler through zero: both come back to the nominal channels.
        paire.setPair(436_795_600L, 145_850_200L)
        assertEquals(436_795_000L, Thd72.frequence(sim.bandes[1]))
        assertEquals(145_850_000L, Thd72.frequence(sim.bandes[0]))
        // Swapping roles (transmit on B) is a new configuration, closed first.
        paire.close()
        paire.configureThd72(bandeTx = 1)
        assertEquals(1, (paire.tx as Thd72Bande).bande); assertEquals(0, (paire.rx as Thd72Bande).bande)
        // Back to an FT-817 pair: the TH-D72 bands are replaced.
        paire.configure(rxIc705 = false, txIc705 = false)
        assertFalse(paire.rx is Thd72Bande || paire.tx is Thd72Bande)
    }
}
