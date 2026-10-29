/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.domain.ClasseQo100
import fr.f4ioz.satcombo.domain.MonteeQo100
import fr.f4ioz.satcombo.domain.ProfilMontee
import fr.f4ioz.satcombo.domain.Qo100
import fr.f4ioz.satcombo.domain.SignalQo100
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import fr.f4ioz.satcombo.sdr.EmetteurDatv
import fr.f4ioz.satcombo.sdr.EtatEmission
import fr.f4ioz.satcombo.sdr.PlutoEmission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * The narrow band's opt-in helpers, offline: the simulation (no device),
 * the level guard, listen before talk, the profile's cap, the CW and voice
 * memories, the journal. A fake "Pluto" counts every start: in the
 * simulation it must never be asked.
 */
class EmissionBandeEtroiteOptionsPcTest {
    @get:Rule val tmp = TemporaryFolder()

    private class FauxTx(val r: PlutoEmission.Reglage, val max: Double, val producteur: (ShortArray) -> Unit) : EmetteurDatv {
        @Volatile var emet = true
        @Volatile var gain = minOf(r.gainDb, max, r.plafondDb, 0.0)
        override val nom = "faux"
        override fun etatCommun() = EtatEmission(emet, 0, 0, r.frequenceHz, gain, 0, 0, "", "")
        override fun arrete(raison: String) { emet = false }
        override fun regleGain(db: Double): Double? { gain = minOf(db, max, r.plafondDb, 0.0); return gain }
        fun tire(paires: Int): ShortArray = ShortArray(2 * paires).also { producteur(it) }
    }

    private inner class Banc(var demo: Boolean = false) {
        var t = 5_000_000L
        var signaux: List<SignalQo100>? = null
        val stockage = StockageMemoire()
        val plutos = ArrayList<FauxTx>()
        val simules = ArrayList<EmetteurSimulePc>()
        val b = EmissionBandeEtroitePc(stockage, { "F4IOZ" }, gainMaxDb = { -10.0 }, descenteCockpit = { 10_489_800_000L },
            signauxQo100 = { signaux }, horloge = { t }, sonde = { false }, locator = { "JN18FS" }, dossierVoix = tmp.newFolder(),
            simulationSeule = { demo },
            fabrique = { r, max, p -> FauxTx(r, max, p).also { plutos += it } to null },
            fabriqueSimulation = { r, max, p -> EmetteurSimulePc(r, max, p, { t }, boucle = false).also { simules += it } })
        fun air() { b.charge = false }
    }

    private fun sig(id: Int, hz: Long, snr: Double, classe: ClasseQo100 = ClasseQo100.BLU) = SignalQo100(id, hz, 2_400, classe, 0.9, 0, 100, snr, "")

    @Test fun `la simulation n ouvre aucun appareil`() {
        val k = Banc()
        k.b.reglage(mapOf("simulation" to "1"), local = true)
        k.air()
        assertEquals("simulation", k.b.destination)
        // Not « on the air »: no confirmation needed, and the Pluto never asked.
        assertNull(k.b.appuie("page", false, "", confirme = false))
        assertTrue(k.plutos.isEmpty())
        assertEquals(1, k.simules.size)
        assertEquals("simulation", k.b.json("page").getString("destinationEnCours"))
        // The mode does not change while keyed.
        assertFalse(k.b.reglage(mapOf("simulation" to "0"), local = true).getBoolean("ok"))
    }

    @Test fun `la simulation mesure ce qu elle aurait emis et tient les gardes`() {
        val k = Banc()
        k.b.simulation = true
        k.b.reglage(mapOf("mode" to "cw", "lie" to "0"), local = true)
        k.b.cadranManuelHz = MonteeQo100.cadranParDefaut(MonteeQo100.Mode.CW)
        assertNull(k.b.appuie("page", false, "", false))
        k.b.cw("page", mapOf("touche" to "1"))
        val s = k.simules.last()
        repeat(4) { s.tire(ShortArray(2 * 4_096)) }
        assertTrue(s.rmsDbfs!! > -20.0)
        assertTrue(k.b.json("page").getJSONObject("simule").getDouble("rms") > -20.0)
        // The watchdog as on the air.
        k.t += 2_500; k.b.tic()
        assertFalse(k.b.emet)
        assertFalse(s.etatCommun().emet)
        // Journal: one over, in simulation.
        assertEquals("simulation", k.b.journal.liste().first().destination)
        assertEquals("cw", k.b.journal.liste().first().mode)
    }

    @Test fun `la demonstration ne fait que simuler`() {
        val k = Banc(demo = true)
        assertTrue(k.b.simulation)
        assertFalse(k.b.reglage(mapOf("simulation" to "0"), local = true).getBoolean("ok"))
        k.air()
        assertNull(k.b.appuie("page", false, "", true))
        assertTrue(k.plutos.isEmpty())
        assertEquals(1, k.simules.size)
    }

    @Test fun `la garde de niveau est coupee d origine puis baisse le gain`() {
        val k = Banc()
        k.air()
        k.b.gainDb = -20.0
        val d = Qo100.descenteDepuisMontee(k.b.cadranHz)
        val balise = sig(9, Qo100.BALISE_MEDIANE_HZ, 15.0, ClasseQo100.BALISE)
        k.signaux = listOf(balise, sig(1, d + 1_500, 18.0))
        assertNull(k.b.appuie("page", false, "", confirme = true))
        // Off by default: measured, said, nothing touched.
        repeat(20) { k.t += 200; k.b.vie("page"); k.b.tic() }
        assertEquals(-20.0, k.plutos.last().gain, 0.0)
        assertTrue(k.b.json("page").getJSONObject("niveau").getBoolean("tropFort"))
        k.b.relache("fin")
        assertEquals(3.0, k.b.journal.liste().first().ecartMaxDb!!, 1e-9)
        // On: 3 dB over → 4 dB down after a second.
        k.b.reglage(mapOf("garde" to "1"), local = true)
        assertNull(k.b.appuie("page", false, "", confirme = true))
        repeat(8) { k.t += 200; k.b.vie("page"); k.b.tic() }
        assertEquals(-24.0, k.plutos.last().gain, 0.0)
        assertEquals(-24.0, k.b.gainDb, 0.0)
        assertTrue(k.b.emet)
        // 6 dB over: cut.
        k.signaux = listOf(balise, sig(1, d + 1_500, 22.0))
        k.t += 200; k.b.vie("page"); k.b.tic()
        assertFalse(k.b.emet)
        assertTrue(k.b.json("page").getString("raison").contains("garde de niveau"))
    }

    @Test fun `ecouter avant d emettre et plafond du profil`() {
        val k = Banc()
        val d = Qo100.descenteDepuisMontee(k.b.cadranHz)
        k.signaux = listOf(sig(1, d + 1_200, 12.0))
        assertTrue(k.b.json("page").getString("occupe").contains("occupée"))
        k.signaux = listOf(sig(1, d + 8_000, 12.0))
        assertEquals("", k.b.json("page").getString("occupe"))
        // The profile's own cap, the lowest of all.
        k.b.profils = ProfilMontee.PAR_DEFAUT.map { if (it.nom == "Fixe") it.copy(plafondPlutoDb = -30.0) else it }
        k.b.gainDb = -5.0
        k.air()
        assertNull(k.b.appuie("page", false, "", confirme = true))
        assertEquals(-30.0, k.plutos.last().max, 0.0)
        assertEquals(-30.0, k.b.json("page").getDouble("gainEffectif"), 0.0)
    }

    @Test fun `memoires CW`() {
        val k = Banc()
        k.b.mode = MonteeQo100.Mode.CW
        assertTrue(k.b.cw("page", mapOf("memoire" to "1")).getBoolean("ok"))
        // {LUI} empty: refused.
        assertFalse(k.b.cw("page", mapOf("memoire" to "2")).getBoolean("ok"))
        assertTrue(k.b.cw("page", mapOf("memoire" to "2", "lui" to "dl1abc")).getBoolean("ok"))
        assertEquals("DL1ABC", k.b.lui)
        // Changed and stored; not from another machine.
        assertTrue(k.b.reglage(mapOf("cwMem" to "6", "cwNom" to "Test", "cwTexte" to "test de {moi}"), true).getBoolean("ok"))
        assertEquals("TEST DE {MOI}", k.b.memoiresCw[5].texte)
        assertFalse(k.b.reglage(mapOf("simulation" to "1"), local = false).getBoolean("ok"))
        // The keyboard keyer: one key at a time.
        k.b.cw("page", mapOf("annule" to "1"))
        assertEquals(1, k.b.cw("page", mapOf("texte" to "e", "frappe" to "1")).getInt("enAttente"))
    }

    @Test fun `memoire vocale jouee sous le PTT puis relachee`() {
        val k = Banc()
        k.air()
        // One second of 800 Hz at 44.1 kHz, recorded from the page.
        val n = 44_100
        val o = ByteBuffer.allocate(2 * n).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) o.putShort((sin(2 * PI * 800 * i / 44_100.0) * 12_000).toInt().toShort())
        assertNull(k.b.voix.enregistre(1, "CQ", o.array(), 44_100))
        assertEquals(1_000L, k.b.voix.liste().single().dureeMs)
        // In CW: refused.
        k.b.mode = MonteeQo100.Mode.CW
        assertNotNull(k.b.appuie("page", false, "", true, voixN = 1))
        k.b.mode = MonteeQo100.Mode.BLU
        assertNotNull(k.b.appuie("page", false, "", true, voixN = 2))
        assertNull(k.b.appuie("page", false, "", true, voixN = 1))
        // Pulled at the Pluto's pace: 1.2 s of samples, then released by itself.
        repeat(12) { k.plutos.last().tire((PlanPlutoDebit / 10).toInt()) }
        k.t += 200; k.b.vie("page"); k.b.tic()
        assertTrue(k.b.emet)
        k.t += 500; k.b.vie("page"); k.b.tic()
        assertFalse(k.b.emet)
        assertTrue(k.b.json("page").getString("raison").contains("mémoire vocale"))
        assertEquals("mémoire vocale « CQ »", k.b.journal.liste().first().source)
    }

    private val PlanPlutoDebit = MonteeQo100.PlanPluto.DEBIT
}
