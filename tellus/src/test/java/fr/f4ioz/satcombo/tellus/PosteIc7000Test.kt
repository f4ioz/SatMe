/*
 * TellusMe — the SatMe ecosystem's terrestrial station
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.tellus

import fr.f4ioz.satcombo.cat.CatDecode
import fr.f4ioz.satcombo.cat.ModeleHf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PosteIc7000Test {

    private fun poste(sim: SimulateurIc7000, horloge: () -> Long = System::currentTimeMillis) =
        PosteIc7000({ sim }, pacingMs = 0L, horloge = horloge).also { assertTrue(it.ouvre()) }

    @Test fun lit_frequence_mode_et_smetre_a_l_adresse_70h() = runBlocking {
        val sim = SimulateurIc7000(frequence = 14_074_000L).apply { mode = 0x01; smetre = 120 }
        val p = poste(sim)
        p.sonde()
        assertEquals(14_074_000L, p.frequence)
        assertEquals("USB", p.mode)
        assertEquals(120, p.smetre)
        assertEquals("S9", PosteIc7000.texteS(p.smetre))
        // Every question went to 70h, from E0h.
        assertTrue(sim.recues.isNotEmpty())
        assertTrue(sim.recues.all { (it[2].toInt() and 0xFF) == 0x70 && (it[3].toInt() and 0xFF) == 0xE0 })
        assertEquals(0x70, ModeleHf.IC7000.adresse)
    }

    @Test fun accorde_et_change_de_mode() = runBlocking {
        val sim = SimulateurIc7000()
        val p = poste(sim)
        assertTrue(p.accorde(3_573_000L))
        assertEquals(3_573_000L, sim.frequence)
        assertTrue(p.regleMode("LSB"))
        assertEquals(0x00, sim.mode)
        assertTrue(p.regleMode("CW-R"))
        assertEquals(0x07, sim.mode)
        p.sonde()
        assertEquals("CW-R", p.mode)
        // Outside what the IC-7000 receives: not even sent.
        val avant = sim.recues.size
        assertFalse(p.accorde(300_000_000L))
        assertEquals(avant, sim.recues.size)
        assertFalse(p.regleMode("DV"))
    }

    @Test fun les_trames_transceive_du_bouton_ne_trompent_pas_la_lecture() = runBlocking {
        val sim = SimulateurIc7000(frequence = 7_100_000L)
        val p = poste(sim)
        p.sonde()
        // The knob turned twice, the rig broadcasting each step to 00h before our question.
        sim.tourneBouton(7_101_000L)
        sim.tourneBouton(7_102_500L)
        p.sonde()
        assertEquals(7_102_500L, p.frequence)
        sim.tourneBouton(7_103_000L)
        p.sonde()
        assertEquals(7_103_000L, p.frequence)
    }

    @Test fun la_garde_refuse_toute_commande_d_emission() {
        val sim = SimulateurIc7000()
        val l = LienReception(sim)
        fun f(vararg b: Int) = (listOf(0xFE, 0xFE, 0x70, 0xE0) + b.toList() + 0xFD).map { it.toByte() }.toByteArray()
        assertFalse(l.write(f(0x1C, 0x00, 0x01)))          // PTT on
        assertFalse(l.write(f(0x1C, 0x00)))                // even reading TX state
        assertFalse(l.write(f(0x17, 0x43, 0x51)))          // CW message: keys the rig
        assertFalse(l.write(f(0x14, 0x0A, 0x02, 0x55)))    // RF power
        assertFalse(l.write(f(0x1A, 0x05, 0x00, 0x01)))    // menu settings
        assertFalse(l.write(f(0x15, 0x11)))                // only the S-meter read
        assertEquals(6, l.refuses)
        assertTrue(sim.recues.isEmpty())
        assertTrue(l.write(f(0x03)))
        assertTrue(l.write(f(0x15, 0x02)))
        assertTrue(l.write(byteArrayOf(0xFE.toByte(), 0xFE.toByte(), 0x70, 0xE0.toByte(), 0x05) + CatDecode.freqToBcdLe(7_000_000) + byteArrayOf(0xFD.toByte())))
    }

    @Test fun aucune_trame_1C_pendant_une_session() = runBlocking {
        val sim = SimulateurIc7000()
        val p = poste(sim)
        repeat(5) { p.sonde() }
        p.accorde(21_074_000L); p.regleMode("USB"); p.sonde()
        assertTrue(sim.recues.none { (it[4].toInt() and 0xFF) == 0x1C || (it[4].toInt() and 0xFF) == 0x17 })
        assertEquals(0, p.refuses)
    }

    @Test fun se_reconnecte_apres_un_silence() = runBlocking {
        var t = 0L
        val sim = SimulateurIc7000(frequence = 10_116_000L)
        val p = poste(sim) { t }
        p.sonde()
        assertTrue(p.connecte)
        sim.debranche = true
        t = 1_000; p.sonde()
        assertTrue(p.connecte)
        t = 7_000; p.sonde()
        assertFalse(p.connecte)
        assertTrue(p.erreur.isNotEmpty())
        // Too soon to retry.
        t = 9_000; p.sonde(); assertFalse(p.connecte)
        sim.debranche = false
        t = 12_500; p.sonde()
        assertTrue(p.connecte)
        assertEquals(1, p.reconnexions)
        assertEquals(10_116_000L, p.frequence)
    }

    @Test fun echelle_s() {
        assertEquals("S0", PosteIc7000.texteS(0))
        assertEquals("S5", PosteIc7000.texteS(67))
        assertEquals("S9+60", PosteIc7000.texteS(241))
        assertEquals("S9+20", PosteIc7000.texteS(161))
        assertEquals("—", PosteIc7000.texteS(null))
        assertNull(PosteIc7000.NOMS_MODES[0x17])
    }
}
