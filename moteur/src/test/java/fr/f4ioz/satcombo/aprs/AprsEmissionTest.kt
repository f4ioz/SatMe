/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.aprs

import fr.f4ioz.satcombo.cat.CivController
import fr.f4ioz.satcombo.cat.Ic9700Sim
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Building frames, the checks before transmitting, and a key/unkey that always ends on receive. */
class AprsEmissionTest {

    @Test
    fun messages_de_l_aprs_thursday() {
        assertEquals(":ANSRVR   :CQ HOTG 73 de JN18{7", AprsEmission.message("ansrvr", "CQ HOTG 73 de JN18", "7"))
        assertEquals(":APRSPH   :HOTG Bonjour a tous", AprsEmission.message("APRSPH", "HOTG Bonjour à tous", null))
        // Reserved characters out, 67 characters at most.
        assertEquals(":F5RRO    :a b c", AprsEmission.message("F5RRO", "a|b~c{", null))
        assertEquals(67, AprsEmission.message("X", "y".repeat(100), null).substringAfter(":X        :").length)
    }

    @Test
    fun position_et_statut_relus_par_le_decodeur() {
        val info = AprsEmission.position(48.8765, 2.3183, "/-", "Paris")
        assertEquals("=4852.59N/00219.10E-Paris", info)
        val p = Aprs.lit(AprsEmission.trame("F4IOZ-6", listOf("ARISS"), info), 0)
        assertEquals(48.8765, p.lat!!, 1e-3); assertEquals(2.3183, p.lon!!, 1e-3)
        assertEquals("F4IOZ-6>APZSME,ARISS:=4852.59N/00219.10E-Paris", p.trame.tnc2())
        assertEquals("=3325.64S\\11207.74W>", AprsEmission.position(-33.4273, -112.129, "\\>", ""))
        assertEquals(">QRV sur l'ISS", AprsEmission.statut("QRV sur l'ISS"))
    }

    private fun refus(indicatif: String = "F4IOZ", derniere: Long = 0, hz: Long? = 145_825_000,
                      mode: Int? = 0x05, sat: Boolean? = false, tx: Boolean? = false) =
        AprsEmission.refus(indicatif, 100_000, derniere, hz, mode, sat, tx)

    @Test
    fun ce_qui_empeche_d_emettre() {
        assertNull(refus())
        assertNull(refus(hz = 145_828_300))          // with ISS Doppler
        assertNull(refus(hz = 144_800_000))          // terrestrial test
        assertEquals("indicatif", refus(indicatif = ""))
        assertEquals("indicatif", refus(indicatif = "N0CALL"))
        assertEquals("ecart", refus(derniere = 90_000))
        assertEquals("poste_muet", refus(hz = null))
        assertEquals("mode_satellite", refus(sat = true))
        assertEquals("mode", refus(mode = 0x01))
        assertEquals("frequence", refus(hz = 145_990_000))   // ISS voice uplink
        assertEquals("frequence", refus(hz = 437_800_000))
        assertEquals("deja_en_emission", refus(tx = true))
    }

    /** A rig that records what it is told and can be made to misbehave. */
    private class Faux(var accepte: Boolean = true, var colle: Boolean = false) : PosteAprs {
        val ordres = ArrayList<Boolean>()
        var tx = false
        override suspend fun frequence() = 145_825_000L
        override suspend fun mode() = 0x05
        override suspend fun modeSatellite() = false
        override suspend fun emission(on: Boolean): Boolean {
            ordres += on
            if (!accepte && on) return false
            if (!(colle && !on)) tx = on
            return true
        }
        override suspend fun enEmission() = tx
    }

    @Test
    fun emission_normale() = runBlocking {
        val p = Faux()
        var enTxPendantLeSon = false
        assertNull(AprsEmission.emet(p, 500) { enTxPendantLeSon = p.tx; true })
        assertTrue(enTxPendantLeSon)
        assertEquals(listOf(true, false), p.ordres)
        assertFalse(p.tx)
    }

    @Test
    fun son_rate_retour_en_reception_quand_meme() = runBlocking {
        val p = Faux()
        assertEquals("audio", AprsEmission.emet(p, 500) { false })
        assertFalse(p.tx)
        val q = Faux()
        runCatching { AprsEmission.emet(q, 500) { error("carte son débranchée") } }
        assertFalse(q.tx)
    }

    @Test
    fun annulation_pendant_le_son_retour_en_reception() = runBlocking {
        val p = Faux()
        val job = async { AprsEmission.emet(p, 60_000) { delay(60_000); true } }
        delay(300)
        job.cancel()
        runCatching { job.await() }.exceptionOrNull().let { assertTrue(it is CancellationException) }
        assertFalse(p.tx)
        assertEquals(false, p.ordres.last())
    }

    @Test
    fun son_bloque_coupe_au_bout_du_delai() = runBlocking {
        val p = Faux()
        assertEquals("audio", AprsEmission.emet(p, 100) { delay(60_000); true })
        assertFalse(p.tx)
    }

    @Test
    fun poste_refuse_ou_reste_en_emission() = runBlocking {
        assertEquals("ptt_refuse", AprsEmission.emet(Faux(accepte = false), 100) { true })
        val colle = Faux(colle = true)
        assertEquals("reste_en_emission", AprsEmission.emet(colle, 100) { true })
        assertEquals(listOf(true, false, false, false), colle.ordres)  // unkey tried three times
    }

    @Test
    fun commandes_ci_v_sur_le_simulateur_d_ic9700() = runBlocking {
        val sim = Ic9700Sim()
        val cat = CivController().also { it.attach(sim) }
        assertEquals(false, cat.readSatelliteMode())
        cat.setSatelliteMode(true)
        assertEquals(true, cat.readSatelliteMode())
        cat.setSatelliteMode(false)
        assertTrue(cat.setTransmit(true))
        assertEquals(true, cat.isTransmitting())
        assertTrue(cat.setTransmit(false))
        assertEquals(false, cat.isTransmitting())
        assertEquals(listOf(true, false), sim.commandesPtt)
        assertEquals(0, sim.refusals)
    }
}
