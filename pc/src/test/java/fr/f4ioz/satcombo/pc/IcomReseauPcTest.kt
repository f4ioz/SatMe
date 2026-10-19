/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.cat.icomreseau.CodageIdentifiants
import fr.f4ioz.satcombo.cat.icomreseau.FausseRadioIcom
import fr.f4ioz.satcombo.cat.icomreseau.PosteIcomReseau
import fr.f4ioz.satcombo.data.Observer
import fr.f4ioz.satcombo.domain.PassPredictor
import fr.f4ioz.satcombo.plateforme.Lignes
import fr.f4ioz.satcombo.plateforme.Ouverture
import fr.f4ioz.satcombo.plateforme.PortSerie
import fr.f4ioz.satcombo.plateforme.PortsSerie
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The CAT switched between the USB cable and the network: with « Réseau »
 * the same Doppler loop drives the rig through the LAN session, and no
 * serial port is ever opened.
 */
class IcomReseauPcTest {

    private val dossier = File(System.getProperty("java.io.tmpdir"), "satme-icom-" + System.nanoTime()).apply {
        File(this, "emetteurs").mkdirs()
        File(this, "amsat-tle.txt").writeText("""ISS
1 25544U 98067A   26284.17638745  .00009430  00000-0  18014-3 0  9996
2 25544  51.6312  86.0933 0006713 248.1316 111.8959 15.48821882589734
""")
        File(this, "emetteurs/25544.json").writeText("""[
          {"description":"FM voice repeater (V/U)","mode":"FM","uplink_low":145990000,"downlink_low":437800000,"alive":true,"invert":false,"type":"Transceiver"}]""")
    }
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val radio = FausseRadioIcom(CodageIdentifiants.hex("74496a33")!!, CodageIdentifiants.hex("262222745336364d")!!)

    /** Serial ports that must never be opened. */
    private class Ports : PortsSerie {
        @Volatile var ouvertures = 0
        override fun liste() = listOf(PortSerie(0, 0, 0, 1, "/dev/ttyUSB0 — IC-9700", "X", 0x10C4, "CP2102"))
        override fun autorise(p: PortSerie) = true
        override fun demande(p: PortSerie) {}
        override suspend fun demandeEtAttends(p: PortSerie) = true
        override suspend fun ouvre(p: PortSerie, baud: Int, lignes: Lignes, deuxArrets: Boolean): Ouverture { ouvertures++; return Ouverture(null, "refusé par le test") }
    }

    private val milieu: Long by lazy {
        val iss = Catalogue.lisTle(File(dossier, "amsat-tle.txt").readText()).first()
        val p = PassPredictor().upcomingPasses(iss, Observer(48.77, 2.46), iss.epochMs!!, 48, 10.0).first()
        (p.aosEpochMs + p.losEpochMs) / 2
    }

    @After fun fin() { portee.cancel(); radio.arrete(); dossier.deleteRecursively() }

    private fun attends(ms: Long = 8_000, quoi: () -> Boolean) {
        val fin = System.currentTimeMillis() + ms
        while (!quoi()) { check(System.currentTimeMillis() < fin) { "trop long" }; Thread.sleep(50) }
    }

    private fun station(ports: Ports): StationPc {
        val st = StationPc(StockageMemoire(), Catalogue(dossier, enLigne = false), ports, portee, horloge = { milieu })
        st.settings.manualLocator = "JN18FS"
        st.settings.apply {
            icomHote = "127.0.0.1"; icomPortControle = radio.portControle; icomPortCiv = radio.portCiv; icomPortAudio = radio.portAudio
            icomUtilisateurCode = "74496a33"; icomMdpCode = "262222745336364d"
        }
        st.demarre()
        return st
    }

    @Test fun le_doppler_passe_par_le_reseau_sans_ouvrir_de_port() {
        val ports = Ports()
        val st = station(ports)
        st.settings.icomLiaison = "RESEAU"
        assertTrue(st.suis(25544))
        attends { st.etat.value.position != null && st.etat.value.emetteurs.isNotEmpty() }
        st.connecteCat("IC9700", 0, 0)
        attends { st.etat.value.catConnecte }
        assertTrue(st.etat.value.catStatut, st.etat.value.catStatut.contains("réseau"))
        // The loop's writes reach the rig: its dial leaves 145.800 for the ISS downlink with Doppler.
        attends { Math.abs(radio.frequenceHz - 437_800_000L) < 20_000L }
        assertEquals(0, ports.ouvertures)
        assertTrue(radio.civRecues.none { it.size >= 7 && (it[4].toInt() and 0xFF) == 0x1C && it[6].toInt() != 0 })
        st.deconnecteCat()
        attends { radio.jetonRendu }
        attends { !st.etat.value.catConnecte }
    }

    @Test fun usb_ne_touche_pas_au_reseau_et_ic9100_refuse_en_reseau() {
        val ports = Ports()
        val st = station(ports)
        st.settings.icomLiaison = "RESEAU"
        st.connecteCat("IC9100", 0, 0)
        attends { st.etat.value.catStatut.startsWith("échec") }
        assertTrue(st.etat.value.catStatut.contains("IC-9700 seulement"))
        assertEquals(0, radio.connexions)
        // Back on USB: the cable is tried (here refused), the network left alone.
        st.settings.icomLiaison = "USB"
        st.connecteCat("IC9700", 0, 0)
        attends { st.etat.value.catStatut.startsWith("échec") }
        assertEquals(1, ports.ouvertures)
        assertEquals(0, radio.connexions)
    }

    @Test fun la_source_son_reseau_se_liste_et_demarre() {
        val st = station(Ports())
        val son = SonPc()
        val ir = IcomReseauPc(st, son, dossier)
        assertTrue(son.appareils().any { it.nom == PosteIcomReseau.NOM_SOURCE })
        var n = 0L
        son.auditeurs += { _, k, rate -> assertEquals(48_000, rate); n += k }
        assertTrue(son.demarre(PosteIcomReseau.NOM_SOURCE))
        attends { n >= 48_000 }
        assertEquals(PosteIcomReseau.NOM_SOURCE, son.etat.value.appareil)
        assertTrue(ir.json().getString("etat") == "CONNECTE")
        son.arrete()
        attends { radio.jetonRendu }
        // No address: not listed.
        st.settings.icomHote = ""
        assertFalse(son.appareils().any { it.nom == PosteIcomReseau.NOM_SOURCE })
    }

    @Test fun reglages_apprennent_le_codage() {
        val st = station(Ports())
        val ir = IcomReseauPc(st, SonPc(), dossier)
        val r = ir.action("/api/icomreseau/reglage", mapOf("liaison" to "RESEAU", "utilisateur" to "f4ioz", "utilisateurCode" to "0102030405",
            "mdp" to "abc", "mdpCode" to "111213", "codec" to "pcm16-8k", "latence" to "500"))
        assertTrue(r.getBoolean("ok"))
        assertEquals("RESEAU", st.settings.icomLiaison)
        assertEquals(300, st.settings.icomLatenceMs)
        assertEquals("pcm16-8k", st.settings.icomCodec)
        // The password itself never goes back to the page.
        assertFalse(r.toString().contains("abc"))
        // Learnt: the coded forms dropped, a login is still possible from the table.
        ir.action("/api/icomreseau/reglage", mapOf("utilisateurCode" to "", "mdpCode" to "-"))
        assertTrue(ir.json().getBoolean("pret"))
        assertFalse(ir.action("/api/icomreseau/reglage", mapOf("hote" to "a b;c")).getBoolean("ok"))
    }
}
