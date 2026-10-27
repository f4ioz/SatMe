/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.domain.Ft8Onde
import fr.f4ioz.satcombo.domain.Ft8Qso
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * FT8/FT4 transmit through the simulated IC-9700: the PTT on, the whole
 * message's audio to a fake way to the rig while keyed, the PTT off after;
 * nothing keyed when refused (the demonstration, the clock, the watchdog,
 * a halt), and no contact started by itself. No port, no card opened.
 */
class Ft8EmissionPcTest {

    private val dossier = File(System.getProperty("java.io.tmpdir"), "satme-ft8tx-" + System.nanoTime()).apply { mkdirs() }
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun fin() { portee.cancel(); dossier.deleteRecursively(); ModeDemo.desactive() }

    private fun attends(ms: Long = 5_000, quoi: () -> Boolean) {
        val fin = System.currentTimeMillis() + ms
        while (!quoi()) { check(System.currentTimeMillis() < fin) { "trop long" }; Thread.sleep(20) }
    }

    /** A fake way to the rig: the samples counted, and whether the rig was keyed for each block. */
    private inner class Fausse : EmissionPostePc.Sortie {
        val recus = AtomicLong(); @Volatile var horsPtt = 0
        lateinit var st: StationPc
        override val nom = "fausse"
        override fun pousse(pcm: ShortArray, n: Int, taux: Int): String? {
            recus.addAndGet(n.toLong()); if (!st.posteSimule!!.transmitting) horsPtt++
            assertEquals(48_000, taux); return null
        }
        override fun ferme() {}
    }

    /** An even FT8 slot's start. */
    private var t = 1_700_000_010_000L
    private val fausse = Fausse()

    private fun station(): StationPc {
        val st = StationPc(StockageMemoire(), Catalogue(dossier, enLigne = false), PortsSeriePc(), portee)
        st.settings.manualLocator = "JN18FS"
        st.connecteCat("SIM", 0, 0)
        attends { st.etat.value.catConnecte && st.posteSimule != null }
        fausse.st = st
        return st
    }

    private class Banc(val st: StationPc, val ft8: Ft8Pc, val tx: Ft8EmissionPc)

    private fun banc(simule: () -> Boolean = { false }): Banc {
        val st = station()
        val ft8 = Ft8Pc { t }
        ft8.demarre("FT8")
        val poste = EmissionPostePc(st, { null }, { t }, { _, _ -> fausse })
        val tx = Ft8EmissionPc(StockageMemoire(), ft8, poste, { "F4IOZ" }, { "JN18FS" }, horloge = { t }, simule = simule)
        return Banc(st, ft8, tx)
    }

    /** Runs the loop from now to [jusqua] ms later, 20 ms a step, the page saying it is there. */
    private fun avance(b: Banc, jusqua: Long) {
        val fin = t + jusqua
        while (t < fin) { t += 20; b.tx.vie(); b.tx.tic(t) }
    }

    @Test fun rien_ne_part_sans_geste_de_l_operateur() {
        val b = banc()
        // A station calling me: shown, never answered by itself.
        b.ft8.injecte(listOf(Ft8Pc.Entendu(t - 15_000, "F4IOZ G4ABC IO91", "G4ABC", "F4IOZ", "IO91", 1200, -5, 0.5, "FT8")))
        avance(b, 31_000)
        assertFalse(b.tx.arme)
        assertTrue(b.st.posteSimule!!.commandesPtt.isEmpty())
        assertEquals(0L, fausse.recus.get())
    }

    @Test fun un_cq_cle_envoie_le_message_entier_puis_relache() {
        val b = banc()
        assertNull(b.tx.appelleCq())
        assertTrue(b.tx.arme)
        avance(b, 14_500)
        assertEquals(listOf(true, false), b.st.posteSimule!!.commandesPtt)
        assertFalse(b.st.posteSimule!!.transmitting)
        // The whole transmission at 48 kHz, every block while keyed.
        val attendu = (Ft8Onde.dureeS(false) * 48_000).toLong()
        assertEquals(attendu, fausse.recus.get())
        assertEquals(0, fausse.horsPtt)
        assertEquals(1, b.tx.envois)
        assertEquals("CQ F4IOZ JN18", b.tx.dernier!!.first)
        // Still armed: the next even slot calls again.
        assertTrue(b.tx.arme)
        avance(b, 30_000)
        assertEquals(listOf(true, false, true, false), b.st.posteSimule!!.commandesPtt)
    }

    @Test fun l_arret_coupe_le_ptt_au_milieu_du_message() {
        val b = banc()
        b.tx.appelleCq()
        avance(b, 5_000)
        assertTrue(b.st.posteSimule!!.transmitting)
        b.tx.arrete()
        assertFalse(b.st.posteSimule!!.transmitting)
        assertEquals(listOf(true, false), b.st.posteSimule!!.commandesPtt)
        val avant = fausse.recus.get()
        avance(b, 20_000)
        assertEquals(avant, fausse.recus.get())
        assertEquals(listOf(true, false), b.st.posteSimule!!.commandesPtt)
    }

    @Test fun la_demonstration_ne_cle_jamais() {
        ModeDemo.active()
        val b = banc { ModeDemo.actif }
        assertNull(b.tx.appelleCq())
        avance(b, 14_500)
        assertEquals(1, b.tx.envois)              // simulated
        assertTrue(b.st.posteSimule!!.commandesPtt.isEmpty())
        assertEquals(0L, fausse.recus.get())
        assertTrue(b.tx.json().getBoolean("simule"))
    }

    @Test fun une_horloge_decalee_refuse_l_emission() {
        val b = banc()
        // Every station 1.6 s late: our clock is 1.1 s ahead (FT8 allows 1 s).
        b.ft8.injecte((1..6).map { Ft8Pc.Entendu(t - it * 15_000L, "CQ G${it}ABC IO91", "G${it}ABC", null, "IO91", 600 + it * 100, -5, 1.6, "FT8") })
        val r = b.tx.appelleCq()
        assertNotNull(r)
        assertTrue(r!!, r.contains("horloge"))
        avance(b, 15_000)
        assertTrue(b.st.posteSimule!!.commandesPtt.isEmpty())
    }

    @Test fun le_chien_de_garde_arrete_le_cq() {
        val b = banc()
        b.tx.chienMin = 1
        b.tx.appelleCq()
        avance(b, 75_000)
        assertFalse(b.tx.arme)
        assertTrue(b.tx.raison, b.tx.raison.contains("chien de garde"))
        val n = b.st.posteSimule!!.commandesPtt.size
        // Two CQs in the minute (0 s and 30 s), then nothing.
        assertEquals(listOf(true, false, true, false), b.st.posteSimule!!.commandesPtt)
        avance(b, 30_000)
        assertEquals(n, b.st.posteSimule!!.commandesPtt.size)
    }

    @Test fun la_page_fermee_arrete_l_emission() {
        val b = banc()
        b.tx.appelleCq()
        val fin = t + 20_000
        while (t < fin) { t += 20; b.tx.tic(t) }     // no vie(): the page went away
        assertFalse(b.tx.arme)
        assertTrue(b.tx.raison.contains("page"))
        assertFalse(b.st.posteSimule!!.transmitting)
    }

    @Test fun repondre_a_une_station_dans_le_creneau_oppose_et_ailleurs_qu_elle() {
        val b = banc()
        // K1ABC calls CQ at 1500 Hz in the odd slot just before.
        val e = Ft8Pc.Entendu(t - 15_000, "CQ K1ABC FN42", "K1ABC", null, "FN42", 1500, -9, 0.5, "FT8")
        b.ft8.injecte(listOf(e))
        assertNull(b.tx.repond(Ft8Qso.Recu(e.texte, e.appelant, null, e.locator, e.rapportDb, e.frequenceHz, e.tranche, false)))
        assertTrue(b.tx.qso.pair)
        // Not on its frequency: moved to a clear one.
        assertTrue(kotlin.math.abs(b.tx.frequenceHz - 1500) >= 60)
        avance(b, 14_500)
        assertEquals("K1ABC F4IOZ JN18", b.tx.dernier!!.first)
        // Its report comes in the odd slot: R + report next.
        b.ft8.injecte(listOf(Ft8Pc.Entendu(t - t % 15_000 + 15_000, "F4IOZ K1ABC -11", "K1ABC", "F4IOZ", null, 1500, -9, 0.5, "FT8")))
        avance(b, 30_000)
        assertEquals("K1ABC F4IOZ R-09", b.tx.dernier!!.first)
    }
}
