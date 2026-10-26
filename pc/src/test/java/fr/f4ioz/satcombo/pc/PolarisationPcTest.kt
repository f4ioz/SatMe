package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.demo.ModulesStation
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import fr.f4ioz.satcombo.polarisation.Bande
import fr.f4ioz.satcombo.polarisation.Equipement
import fr.f4ioz.satcombo.polarisation.Esp32Virtuel
import fr.f4ioz.satcombo.polarisation.Pol
import fr.f4ioz.satcombo.polarisation.Selecteur
import fr.f4ioz.satcombo.polarisation.StatsPolarisation
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

class PolarisationPcTest {
    private val dossier = File(System.getProperty("java.io.tmpdir"), "satme-polar-" + System.nanoTime()).apply { mkdirs() }
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun fin() { portee.cancel(); dossier.deleteRecursively() }

    /** A clock that moves a little at each look, so no wait ever hangs. */
    private var t = 1_000_000L
    private val horloge = { t += 5; t }
    private val esp = Esp32Virtuel()

    private fun polar(): Pair<PolarisationPc, StationPc> {
        val st = StationPc(StockageMemoire(), Catalogue(dossier, enLigne = false), PortsSeriePc(), portee, horloge)
        val p = PolarisationPc(st, dossier, horloge) { esp }
        return p to st
    }

    /** Turns until the box answered. */
    private fun connecte(p: PolarisationPc) {
        p.port = PolarisationPc.PORT_VIRTUEL
        repeat(200) { if (p.connexion == "connecte" && p.etat != null) return; p.pas(t); t += 50 }
        error("pas connecté : ${p.message}")
    }

    @Test fun rien_n_est_ouvert_sans_port() {
        val (p, _) = polar()
        repeat(10) { p.pas(t); t += 1000 }
        assertEquals("coupe", p.connexion)
        assertNull(p.etat)
        assertNotNull(p.commande(Bande.U, Pol.H))
    }

    @Test fun avec_l_esp32_virtuel_il_commande_et_lit_l_etat_reel() {
        val (p, _) = polar()
        connecte(p)
        assertNull(p.commande(Bande.U, Pol.D))
        assertEquals(listOf(true, true, false), esp.relais(Bande.U))
        assertEquals(Pol.D, p.etat!!.u)
        // VHF not equipped: refused without asking the box.
        assertNotNull(p.commande(Bande.V, Pol.H))
        p.vhf = Equipement.HV
        assertNotNull(p.commande(Bande.V, Pol.D))
        assertNull(p.commande(Bande.V, Pol.H))
        assertEquals(listOf(true, false, false), esp.relais(Bande.V))
        assertTrue(p.teste().optBoolean("ok"))
    }

    @Test fun droite_et_gauche_inversees_selon_le_cablage() {
        val (p, _) = polar()
        connecte(p)
        p.inverse = true
        assertNull(p.commande(Bande.U, Pol.D))
        assertEquals(Pol.G, esp.etat().u)
        assertEquals("D", p.json().getJSONObject("etat").getString("U"))
    }

    @Test fun le_selecteur_garde_le_dernier_mot() {
        val (p, _) = polar()
        connecte(p)
        esp.tourne(Selecteur.H)
        repeat(5) { p.pas(t); t += 50 }
        assertEquals(Selecteur.H, p.etat!!.selecteur)
        val r = p.commande(Bande.U, Pol.D)
        assertTrue(r!!, r.contains("manuel"))
        val j = p.json().getJSONObject("etat")
        assertTrue(j.getBoolean("manuel")); assertEquals("H", j.getString("U"))
    }

    @Test fun jamais_de_commande_vers_l_antenne_qui_emet() {
        val (p, _) = polar()
        connecte(p)
        p.vhf = Equipement.WIMO
        // SatMe transmits on 435 MHz: UHF held, VHF free.
        p.emissions = { listOf(435_100_000L) }
        assertTrue(p.commande(Bande.U, Pol.H)!!.contains("émet"))
        assertEquals(listOf(false, false, false), esp.relais(Bande.U))
        assertNull(p.commande(Bande.V, Pol.H))
        // A transmission of unknown frequency: both held.
        p.emissions = { listOf(null) }
        assertNotNull(p.commande(Bande.V, Pol.V))
        p.emissions = { emptyList() }
        // The rig's SEND line: the box refuses, SatMe does not even ask.
        esp.emission(true)
        repeat(5) { p.pas(t); t += 50 }
        assertTrue(p.commande(Bande.U, Pol.H)!!.contains("SEND"))
        assertEquals(listOf(false, false, false), esp.relais(Bande.U))
    }

    @Test fun le_module_coupe_ferme_tout() {
        val (p, st) = polar()
        connecte(p)
        ModulesStation.pose(st.stockage, setOf(PolarisationPc.MODULE))
        p.pas(t)
        assertEquals("coupe", p.connexion)
        assertNotNull(p.commande(Bande.U, Pol.H))
    }

    @Test fun le_profil_par_satellite_et_la_prediction() {
        val (p, _) = polar()
        assertEquals(PolarisationPc.PREDITE, p.profil(25544, Bande.U))
        p.poseProfil(25544, Bande.U, "D")
        p.poseProfil(25544, Bande.V, "n'importe quoi")
        assertEquals("D", p.profil(25544, Bande.U))
        assertEquals(PolarisationPc.PREDITE, p.profil(25544, Bande.V))
        val c = PolarisationPc.Contexte(25544, "ISS", 5.0, 100.0, 0L, 600_000L, Bande.U, "FM", false)
        assertEquals(Pol.D, p.cible(c, Bande.U))
        // VHF not equipped: nothing.
        assertNull(p.cible(c, Bande.V))
        p.poseProfil(25544, Bande.U, PolarisationPc.PREDITE)
        assertNull(p.cible(c, Bande.U))
    }

    @Test fun les_sondages_sont_gardes_dans_le_dossier() {
        val (p, _) = polar()
        connecte(p)
        p.vhf = Equipement.HV
        p.sondeOn = true
        p.simPassage = true
        // A made-up pass, a made-up level: probes made and kept, with where the satellite was.
        repeat(4_000) { p.pas(t); t += 40 }
        val l = p.mesures()
        assertTrue("${l.size} sondages", l.isNotEmpty())
        assertTrue(l.all { it.source == PolarisationPc.SIMULEE && it.elDeg >= 0.0 && it.phase.isNotBlank() })
        assertTrue(p.fichier.isFile)
        assertEquals(l.size, p.fichier.readLines().size)
        // Read back by another start (levels kept to a tenth of a dB).
        val (q, _) = polar()
        assertEquals(l.map { it.tMs to it.gagnante }, q.mesures().map { it.tMs to it.gagnante })
        assertEquals(l.first().hDb, q.mesures().first().hDb, 0.051)
        q.efface(seulementSimulees = true)
        assertTrue(q.mesures().isEmpty())
        assertFalse(StatsPolarisation.litFichier(q.fichier.readLines().asSequence()).isNotEmpty())
    }
}
