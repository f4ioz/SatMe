/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.station

import fr.f4ioz.satcombo.cat.CivController
import fr.f4ioz.satcombo.cat.Ic9700Sim
import fr.f4ioz.satcombo.cat.ModeleIcom
import fr.f4ioz.satcombo.cat.PosteCatCiv
import fr.f4ioz.satcombo.cat.icomreseau.FausseRadioIcom
import fr.f4ioz.satcombo.cat.icomreseau.PosteIcomReseau
import fr.f4ioz.satcombo.cat.icomreseau.CodageIdentifiants
import fr.f4ioz.satcombo.data.SatPosition
import fr.f4ioz.satcombo.data.SettingsStore
import fr.f4ioz.satcombo.data.Transmitter
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * USB-D for FT8/FT4 on a simulated IC-9700: the exact CI-V frames when the
 * data modes are switched on and put back, in satellite mode and out of it;
 * the Doppler loop keeping the data flag through its own mode writes; no
 * mode written while keyed; the operator's own USB-D left alone.
 */
class ModeDonneesTest {

    private val sim = Ic9700Sim()
    private val civ = CivController().apply { pacingMs = 0L; attach(sim) }
    private val poste = PosteCatCiv(civ)

    /** The frames received since [depuis], as hex without the FE FE A2 E0 head and the FD. */
    private fun trames(depuis: Int = 0): List<String> = sim.received.drop(depuis).map { f ->
        f.copyOfRange(4, f.size - 1).joinToString(" ") { "%02X".format(it) }
    }

    @Test
    fun en_mode_satellite_les_deux_bandes_passent_en_donnees_puis_reviennent() = runBlocking {
        runBlocking { civ.setSatelliteMode(true) }
        val b = BasculeDonnees()
        val n0 = sim.received.size
        b.tour(poste, voulu = true, emission = false)
        assertEquals(listOf("16 5A", "07 D1", "1A 06", "1A 06 01 01", "07 D0", "1A 06", "1A 06 01 01"), trames(n0))
        assertTrue(sim.mainData); assertTrue(sim.subData)
        assertFalse("ends on MAIN", sim.onSub)
        assertTrue(b.actif)
        // Asked again: nothing more sent.
        val n1 = sim.received.size
        b.tour(poste, voulu = true, emission = false)
        assertEquals(n1, sim.received.size)
        // Disarmed: the flags as they were (off), both bands.
        b.tour(poste, voulu = false, emission = false)
        assertEquals(listOf("16 5A", "07 D1", "1A 06 00 00", "07 D0", "1A 06 00 00"), trames(n1))
        assertFalse(sim.mainData); assertFalse(sim.subData)
        assertFalse(sim.onSub)
        // The modes themselves never written.
        assertTrue(trames().none { it.startsWith("06") || it.startsWith("26") })
        assertEquals(0, sim.refusals)
    }

    @Test
    fun hors_mode_satellite_seul_le_vfo_en_service() = runBlocking {
        val b = BasculeDonnees()
        b.tour(poste, voulu = true, emission = false)
        assertEquals(listOf("16 5A", "1A 06", "1A 06 01 01"), trames())
        assertTrue(sim.mainData); assertFalse(sim.subData)
        val n = sim.received.size
        b.tour(poste, voulu = false, emission = false)
        assertEquals(listOf("16 5A", "1A 06 00 00"), trames(n))
        assertFalse(sim.mainData)
        assertEquals(0, sim.refusals)
    }

    @Test
    fun le_usb_d_de_l_operateur_est_remis_tel_quel() = runBlocking {
        runBlocking { civ.setSatelliteMode(true) }
        sim.mainData = true   // the operator's own USB-D on MAIN, SUB plain
        val b = BasculeDonnees()
        b.tour(poste, voulu = true, emission = false)
        b.tour(poste, voulu = false, emission = false)
        assertTrue("MAIN back to USB-D", sim.mainData)
        assertFalse("SUB back to USB", sim.subData)
    }

    @Test
    fun rien_pendant_le_ptt_ni_pour_basculer_ni_pour_remettre() = runBlocking {
        val b = BasculeDonnees()
        val n = sim.received.size
        b.tour(poste, voulu = true, emission = true)
        assertEquals(n, sim.received.size)
        assertFalse(b.pret(poste, voulu = true))
        b.tour(poste, voulu = true, emission = false)
        assertTrue(b.pret(poste, voulu = true))
        val m = sim.received.size
        b.tour(poste, voulu = false, emission = true)
        assertEquals("not put back while keyed", m, sim.received.size)
        assertTrue(sim.mainData)
        b.tour(poste, voulu = false, emission = false)
        assertFalse(sim.mainData)
    }

    @Test
    fun les_autres_icom_ne_sont_pas_commutes() = runBlocking {
        val s910 = Ic9700Sim(radioAddr = 0x60, modele = ModeleIcom.IC910)
        val c910 = CivController().apply { pacingMs = 0L; modele = ModeleIcom.IC910; radioAddr = 0x60; attach(s910) }
        val p = PosteCatCiv(c910)
        assertFalse(p.donneesPossibles)
        val b = BasculeDonnees()
        b.tour(p, voulu = true, emission = false)
        assertTrue(s910.received.isEmpty())
        assertTrue("FT8 not held back for a rig SatMe cannot switch", b.pret(p, voulu = true))
        // The loop's mode writes stay plain 06 there.
        c910.setModeData(0x01, true)
        assertEquals(1, s910.received.size)
        assertEquals(0, s910.refusals)
    }

    // ------------------------------------------------------------------ the Doppler loop

    /** RS-44 like: linear, inverting, V up, U down. */
    private val lineaire = Transmitter("Linear transponder", "USB", 145_935_000L, 145_995_000L,
        435_610_000L, 435_670_000L, invert = true, alive = true, type = "Transponder")

    private class Monde(val poste: PosteCatCiv, val t: Transmitter) : MondeCat {
        var rr = 0.0
        var donnees = false
        var emission = false
        var repos: Long? = 435_640_000L
        override fun connecte() = true
        override fun poste() = poste
        override fun emetSeul() = false
        override fun aprsEnEmission() = false
        override fun satellite() = SatPosition(180.0, 30.0, 1000.0, rr, 800.0, 0.0, 0.0, true)
        override fun catnum() = 44909
        override fun transpondeur() = t
        override fun sousHorizonAussi() = false
        override fun calibrationHz() = 0L
        override fun decalageTxHz() = 0L
        override fun poseDecalageTx(hz: Long) {}
        override fun decalageRxHz() = 0L
        override fun modeCw() = false
        override fun maintienDoppler() = false
        override fun dopplerRx() = true
        override fun moletteTxDecalage() = false
        override fun txSuitVite() = false
        override fun modelePoste() = "IC9700"
        override fun inversionForcee(): Boolean? = null
        override fun ctcssChoisi() = 0
        override fun ctcssAuto() = true
        override fun repos() = repos
        override fun poseRepos(hz: Long) { repos = hz }
        override fun posteRx(satHz: Long) = satHz
        override fun satDepuisPoste(posteHz: Long) = posteHz
        override fun posteTx(satHz: Long) = satHz
        override fun satDepuisPosteTx(posteHz: Long) = posteHz
        override fun donnees() = donnees
        override fun emissionEnCours() = emission
    }

    private var t = 1_000_000L

    @Test
    fun la_boucle_arme_en_usb_d_et_le_garde_au_fil_du_doppler() = runBlocking {
        val m = Monde(poste, lineaire).apply { donnees = true }
        val st = StationCat(m, horloge = { t })
        st.tour()
        // Armed: LSB-D up (SUB, inverting), USB-D down (MAIN), each 06 then 1A 06 01 01.
        val armement = trames()
        assertTrue(armement.containsAll(listOf("06 00 01", "06 01 01", "1A 06 01 01")))
        assertTrue(armement.indexOf("06 00 01") < armement.indexOf("1A 06 01 01"))
        assertEquals(0x00, sim.subMode); assertTrue(sim.subData)
        assertEquals(0x01, sim.mainMode); assertTrue(sim.mainData)
        // A pass of Doppler: frequencies move, the data flags stay, never switched off.
        for (i in 0 until 60) { m.rr = -6.0 + i * 0.2; st.tour(); t += 100 }
        assertTrue(sim.mainData); assertTrue(sim.subData)
        assertTrue(trames().none { it == "1A 06 00 00" })
        assertEquals("USB-D", st.etat.value.mode)
        assertFalse(st.etat.value.modeEcart)
        // The rig reverts MAIN to plain USB (its band memory, a hand): the loop's look puts USB-D back.
        civ.setMode(0x01)
        assertFalse(sim.mainData)
        repeat(20) { st.tour(); t += 100 }
        assertTrue(sim.mainData)
        assertEquals(0, sim.refusals)
    }

    @Test
    fun sans_ft8_la_boucle_envoie_les_memes_trames_qu_avant() = runBlocking {
        val m = Monde(poste, lineaire)
        val st = StationCat(m, horloge = { t })
        repeat(25) { st.tour(); t += 100 }
        // No data-mode write at all; reading it to show the mode is all.
        assertTrue(trames().none { it.startsWith("1A 06 0") })
        assertEquals("USB", st.etat.value.mode)
        // The operator's own USB-D is not taken away by the loop's look.
        sim.mainData = true
        repeat(20) { st.tour(); t += 100 }
        assertTrue(sim.mainData)
        assertEquals("USB-D", st.etat.value.mode)
    }

    @Test
    fun aucun_mode_ecrit_pendant_le_ptt() = runBlocking {
        val m = Monde(poste, lineaire).apply { donnees = true }
        val st = StationCat(m, horloge = { t })
        st.tour()
        // Keyed: the rig's MAIN changed under it (plain USB); the loop only reads.
        civ.setMode(0x01)
        m.emission = true
        sim.transmitting = true
        repeat(25) { m.rr = -3.0 + it * 0.3; st.tour(); t += 100 }
        assertTrue("frequencies still follow while keyed", sim.pendantEmission.any { (it[4].toInt() and 0xFF) == 0x05 })
        assertTrue(sim.modesPendantEmission.isEmpty())
        assertTrue(st.etat.value.modeEcart)
        // Released: put back at the next look.
        sim.transmitting = false
        m.emission = false
        repeat(20) { st.tour(); t += 100 }
        assertTrue(sim.mainData)
    }

    // ------------------------------------------------------------------ over the network

    @Test
    fun par_le_reseau_les_memes_trames() {
        val u = CodageIdentifiants.hex("74496a33")!!
        val mdp = CodageIdentifiants.hex("262222745336364d")!!
        val r = FausseRadioIcom(u, mdp)
        try {
            r.satellite = true
            val reglages = SettingsStore(StockageMemoire()).apply {
                icomHote = "127.0.0.1"; icomPortControle = r.portControle; icomPortCiv = r.portCiv; icomPortAudio = r.portAudio
                icomUtilisateur = "x"; icomUtilisateurCode = "74496a33"; icomMdp = "y"; icomMdpCode = "262222745336364d"
            }
            val reseau = PosteIcomReseau(reglages)
            assertNull(reseau.prends(PosteIcomReseau.Usage.CAT))
            val cat = CivController().apply { pacingMs = 20L; attach(reseau.lienCiv()) }
            val p = PosteCatCiv(cat)
            val b = BasculeDonnees()
            runBlocking { b.tour(p, voulu = true, emission = false) }
            assertTrue(b.actif)
            assertTrue(r.donneesMain); assertTrue(r.donneesSub)
            runBlocking { b.tour(p, voulu = false, emission = false) }
            assertFalse(r.donneesMain); assertFalse(r.donneesSub)
            val vues = r.civRecues.map { f -> f.copyOfRange(4, f.size - 1).joinToString(" ") { "%02X".format(it) } }
                .filter { it.startsWith("1A 06") || it.startsWith("07 D") }
            assertEquals(listOf("07 D1", "1A 06", "1A 06 01 01", "07 D0", "1A 06", "1A 06 01 01",
                "07 D1", "1A 06 00 00", "07 D0", "1A 06 00 00"), vues)
            cat.close()
        } finally { r.arrete() }
    }
}
