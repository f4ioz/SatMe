package fr.f4ioz.satcombo.polarisation

import fr.f4ioz.satcombo.polarisation.ProtocolePolarisation.Reponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PolarisationTest {

    // ------------------------------------------------------------ the protocol

    @Test fun les_lignes_d_etat_se_lisent() {
        val r = ProtocolePolarisation.lit("ETAT SEL=AUTO U=D V=H TX=1 FW=1.0") as Reponse.Etat
        assertEquals(EtatBoitier(Selecteur.AUTO, Pol.D, Pol.H, true, "1.0"), r.etat)
        assertEquals(Selecteur.INCONNU, (ProtocolePolarisation.lit("etat sel=? u=v") as Reponse.Etat).etat.selecteur)
        assertEquals(Selecteur.G, ProtocolePolarisation.litEtat("SEL=G U=G V=V TX=0")!!.selecteur)
        // Something that does not read is never taken for a state.
        assertNull(ProtocolePolarisation.litEtat("SEL=AUTO U=X"))
        assertNull(ProtocolePolarisation.litEtat("U=V V=V"))
        assertNull(ProtocolePolarisation.litEtat("SEL=AUTO TX=2"))
        assertTrue(ProtocolePolarisation.lit("ETAT n'importe quoi") is Reponse.Autre)
        assertNull(ProtocolePolarisation.lit("   "))
    }

    @Test fun les_reponses_et_les_commandes() {
        assertEquals(Reponse.Ok("POL U H"), ProtocolePolarisation.lit("OK POL U H"))
        assertEquals(Reponse.Err("MANUEL", "sélecteur sur H"), ProtocolePolarisation.lit("ERR manuel sélecteur sur H"))
        assertEquals(Reponse.Err("TX", ""), ProtocolePolarisation.lit("ERR TX"))
        assertEquals(Reponse.Id("SATME-POL ESP32 FW=1.0"), ProtocolePolarisation.lit("ID SATME-POL ESP32 FW=1.0"))
        assertEquals("POL V G", ProtocolePolarisation.commande(Bande.V, Pol.G))
        assertEquals(Bande.U to Pol.D, ProtocolePolarisation.litCommande(" pol u d "))
        assertNull(ProtocolePolarisation.litCommande("POL X H"))
        assertNull(ProtocolePolarisation.litCommande("POL U"))
        val e = EtatBoitier(Selecteur.AUTO, Pol.H, Pol.V, false, "1.0")
        assertEquals(e, (ProtocolePolarisation.lit(ProtocolePolarisation.etat(e)) as Reponse.Etat).etat)
    }

    @Test fun droite_et_gauche_s_inversent_seules() {
        assertEquals(Pol.G, Pol.D.inverse(true)); assertEquals(Pol.D, Pol.G.inverse(true))
        assertEquals(Pol.H, Pol.H.inverse(true)); assertEquals(Pol.D, Pol.D.inverse(false))
    }

    @Test fun les_bandes_par_la_frequence() {
        assertEquals(Bande.V, Bande.deFrequence(145_825_000L))
        assertEquals(Bande.U, Bande.deFrequence(435_300_000L))
        assertNull(Bande.deFrequence(2_400_500_000L))
        assertNull(Bande.deFrequence(null))
        assertFalse(Equipement.HV.permet(Pol.D)); assertTrue(Equipement.HV.permet(Pol.H)); assertFalse(Equipement.AUCUN.permet(Pol.V))
    }

    // ------------------------------------------------------------ the relay tree

    @Test fun l_arbre_ne_donne_jamais_qu_une_broche() {
        assertNull(ArbreRelais.broche(ArbreRelais.relais(Pol.V)))
        assertEquals(5, ArbreRelais.broche(ArbreRelais.relais(Pol.H)))
        assertEquals(1, ArbreRelais.broche(ArbreRelais.relais(Pol.D)))
        assertEquals(2, ArbreRelais.broche(ArbreRelais.relais(Pol.G)))
        Pol.entries.forEach { assertEquals(it, ArbreRelais.pol(ArbreRelais.relais(it))) }
    }

    @Test fun un_changement_passe_par_aucune_tension() {
        for (de in Pol.entries) for (vers in Pol.entries) {
            val s = ArbreRelais.sequence(ArbreRelais.relais(de), vers)
            if (de == vers) { assertTrue(s.isEmpty()); continue }
            assertEquals(vers, ArbreRelais.pol(s.last()))
            // Between two powered positions, a state with no voltage at all.
            if (de != Pol.V && vers != Pol.V) assertTrue("$de → $vers", s.any { ArbreRelais.broche(it) == null })
            // Never another pin than the start's or the end's on the way.
            s.forEach { k -> val b = ArbreRelais.broche(k); assertTrue("$de → $vers", b == null || b == ArbreRelais.broche(ArbreRelais.relais(vers))) }
        }
    }

    // ------------------------------------------------------------ the virtual ESP32 (the firmware's rules)

    @Test fun au_demarrage_tout_est_relache() {
        val e = Esp32Virtuel()
        assertEquals(listOf(false, false, false), e.relais(Bande.U))
        assertEquals(listOf(false, false, false), e.relais(Bande.V))
        val b = ByteArray(256)
        val lu = String(b, 0, e.read(b, 100))
        assertTrue(lu, lu.startsWith("ID SATME-POL"))
    }

    @Test fun en_auto_il_obeit_et_dit_son_etat() {
        val e = Esp32Virtuel()
        assertEquals(listOf("OK POL U D", "ETAT SEL=AUTO U=D V=V TX=0 FW=1.0-virtuel"), e.traite("POL U D"))
        assertEquals(listOf(true, true, false), e.relais(Bande.U))
        assertEquals(listOf("OK POL U D"), e.traite("POL U D"))
        assertEquals(Pol.D, e.etat().u)
        assertTrue(e.traite("POL W H").single().startsWith("ERR SYNTAXE"))
        assertTrue(e.traite("FOO").single().startsWith("ERR SYNTAXE"))
        assertTrue(e.traite("ID?").single().startsWith("ID SATME-POL"))
    }

    @Test fun hors_auto_tout_relache_et_les_ordres_refuses() {
        val e = Esp32Virtuel()
        e.traite("POL U G"); e.traite("POL V H")
        e.tourne(Selecteur.H)
        assertEquals(listOf(false, false, false), e.relais(Bande.U))
        assertEquals(listOf(false, false, false), e.relais(Bande.V))
        assertTrue(e.traite("POL U D").single().startsWith("ERR MANUEL"))
        assertEquals(listOf(false, false, false), e.relais(Bande.U))
        // The real state is the selector's.
        assertEquals(Pol.H, e.etat().u)
        e.tourne(Selecteur.INCONNU)
        assertTrue(e.traite("POL U D").single().startsWith("ERR MANUEL"))
        e.tourne(Selecteur.AUTO)
        assertEquals(Pol.V, e.etat().u)
        assertTrue(e.traite("POL U D").first().startsWith("OK"))
    }

    @Test fun en_emission_aucune_commutation() {
        val e = Esp32Virtuel()
        e.traite("POL U H")
        e.emission(true)
        assertTrue(e.traite("POL U V").single().startsWith("ERR TX"))
        assertTrue(e.traite("POL V H").single().startsWith("ERR TX"))
        assertEquals(listOf(true, false, false), e.relais(Bande.U))
        assertTrue(e.etat().tx)
        e.emission(false)
        assertTrue(e.traite("POL U V").first().startsWith("OK"))
    }

    @Test fun jamais_deux_broches_quoi_qu_on_lui_demande() {
        val e = Esp32Virtuel()
        val r = java.util.Random(7)
        repeat(300) {
            when (r.nextInt(10)) {
                0 -> e.tourne(Selecteur.entries[r.nextInt(Selecteur.entries.size)])
                1 -> e.emission(r.nextBoolean())
                else -> e.traite("POL " + Bande.entries[r.nextInt(2)].name + " " + Pol.entries[r.nextInt(4)].name)
            }
        }
        for (b in Bande.entries) {
            val h = e.historique.getValue(b)
            assertTrue(h.size > 10)
            // Each state energises at most one pin (the tree), and two powered positions are separated by a state without voltage.
            h.zipWithNext().forEach { (a, c) ->
                val pa = ArbreRelais.broche(a); val pc = ArbreRelais.broche(c)
                assertTrue("$a → $c", pa == null || pc == null || pa == pc)
            }
        }
    }

    @Test fun il_parle_comme_une_ligne_serie() {
        val e = Esp32Virtuel()
        val b = ByteArray(512)
        e.read(b, 50)
        assertTrue(e.write("ETAT?\nPOL U".toByteArray()))
        val l1 = String(b, 0, e.read(b, 100))
        assertTrue(l1, l1.startsWith("ETAT SEL=AUTO U=V"))
        e.write(" H\r\n".toByteArray())
        val l2 = String(b, 0, e.read(b, 100))
        assertTrue(l2, l2.startsWith("OK POL U H"))
        e.close()
        assertFalse(e.write("ETAT?\n".toByteArray()))
    }

    // ------------------------------------------------------------ the transmission lock

    @Test fun le_verrou_d_emission() {
        assertEquals(emptySet<Bande>(), VerrouEmission.bandes(emptyList()))
        assertEquals(setOf(Bande.V), VerrouEmission.bandes(listOf(145_990_000L)))
        assertEquals(setOf(Bande.U), VerrouEmission.bandes(listOf(435_000_000L, 2_400_100_000L)))
        // A transmission whose frequency is unknown holds both antennas.
        assertEquals(Bande.entries.toSet(), VerrouEmission.bandes(listOf(null)))
    }

    // ------------------------------------------------------------ the probe

    /** Drives a probe with levels [niveau] per polarisation, every 250 ms, the commands obeyed; returns the outcome. */
    private fun sonde(s: SondeHv, depart: Pol, t0: Long = 100_000L, niveau: (Pol) -> Double): Pair<SondeHv.Resultat?, List<Pol>> {
        var p = depart
        val commandes = mutableListOf<Pol>()
        var t = t0
        repeat(80) {
            val a = s.tic(t, true, true, p, SondeHv.Mesure(niveau(p), t))
            a.commande?.let { p = it; commandes += it }
            a.resultat?.let { return it to commandes }
            t += 250L
        }
        return null to commandes
    }

    @Test fun la_sonde_garde_la_meilleure() {
        val s = SondeHv()
        val (r, c) = sonde(s, Pol.D) { if (it == Pol.H) -80.0 else -86.0 }
        assertNotNull(r)
        assertEquals(Pol.H, r!!.gagnante)
        assertEquals(6.0, r.ecartDb, 0.01)
        assertEquals(Pol.D, r.avant)
        assertEquals(listOf(Pol.H, Pol.V, Pol.H), c)
    }

    @Test fun la_sonde_commence_par_celle_en_place_et_tient_l_hysteresis() {
        val s = SondeHv(hystDb = 2.5)
        // V in place, H better by 1.5 dB only: V kept, and only one switch there and back.
        val (r, c) = sonde(s, Pol.V) { if (it == Pol.H) -80.0 else -81.5 }
        assertEquals(Pol.V, r!!.gagnante)
        assertEquals(listOf(Pol.H, Pol.V), c)
        assertEquals(Pol.H, SondeHv.decide(-80.0, -83.0, Pol.V, 2.5))
        assertEquals(Pol.V, SondeHv.decide(-82.0, -80.0, null, 2.5))
    }

    @Test fun la_sonde_attend_sa_periode_ou_une_chute() {
        val s = SondeHv(periodeMs = 30_000L)
        val (r, _) = sonde(s, Pol.H) { if (it == Pol.H) -80.0 else -90.0 }
        val t = r!!.tMs
        assertNull(s.tic(t + 5_000L, true, true, Pol.H, SondeHv.Mesure(-80.0, t + 5_000L)).commande)
        assertEquals(SondeHv.Phase.REPOS, s.phase)
        // The signal falls 6 dB under its reference: probed again at once.
        s.tic(t + 6_000L, true, true, Pol.H, SondeHv.Mesure(-87.0, t + 6_000L))
        assertEquals(SondeHv.Phase.ATTENTE1, s.phase)
    }

    @Test fun une_mesure_d_avant_la_bascule_ne_compte_pas() {
        val s = SondeHv(stabMs = 1_500L, mesureMs = 2_000L)
        var t = 0L
        val vieille = SondeHv.Mesure(-50.0, -1L)
        val a = s.tic(t, true, true, Pol.V, SondeHv.Mesure(-90.0, t))
        assertEquals(SondeHv.Phase.ATTENTE1, s.phase); assertNull(a.commande)
        // Only an old reading: nothing to average → abandoned, V given back.
        while (s.phase != SondeHv.Phase.REPOS && t < 10_000L) { t += 250L; val x = s.tic(t, true, true, Pol.V, vieille); if (x.abandon != null) assertNull(x.resultat) }
        assertNull(s.dernier)
    }

    @Test fun interrompue_par_une_emission_elle_rend_la_polarisation_ensuite() {
        val s = SondeHv()
        var t = 0L
        val a = s.tic(t, true, true, Pol.D, SondeHv.Mesure(-80.0, t))
        assertEquals(Pol.H, a.commande)
        t += 500L
        // Transmitting: abandoned, nothing commanded.
        val b = s.tic(t, true, false, Pol.H, null)
        assertNotNull(b.abandon); assertNull(b.commande)
        assertNull(s.tic(t + 500L, true, false, Pol.H, null).commande)
        // Allowed again: D given back first.
        assertEquals(Pol.D, s.tic(t + 1_000L, true, true, Pol.H, SondeHv.Mesure(-80.0, t + 1_000L)).commande)
    }

    @Test fun le_s_metre_en_db() {
        assertEquals(0.0, SondeHv.smetreDb(0), 0.01)
        assertEquals(54.0, SondeHv.smetreDb(120), 0.01)
        assertEquals(114.0, SondeHv.smetreDb(241), 0.01)
    }

    // ------------------------------------------------------------ statistics and prediction

    private fun m(cat: Int, el: Double, h: Double, v: Double, bande: Bande = Bande.U) =
        StatsPolarisation.Mesure(1000L, cat, "SAT-$cat", "FM", bande, el, 120.0, "montee", h, v, if (h >= v) Pol.H else Pol.V, "smetre")

    @Test fun les_tranches_et_les_phases() {
        assertEquals(0, StatsPolarisation.tranche(-1.0)); assertEquals(0, StatsPolarisation.tranche(9.9))
        assertEquals(2, StatsPolarisation.tranche(20.0)); assertEquals(4, StatsPolarisation.tranche(85.0))
        assertEquals("60–90°", StatsPolarisation.nomTranche(4))
        assertEquals("montee", StatsPolarisation.phase(10, 0, 100)); assertEquals("culmination", StatsPolarisation.phase(50, 0, 100))
        assertEquals("descente", StatsPolarisation.phase(90, 0, 100))
    }

    @Test fun la_prediction_par_tranche_d_elevation() {
        val l = listOf(m(1, 5.0, -80.0, -84.0), m(1, 7.0, -81.0, -85.0), m(1, 8.0, -79.0, -83.0),
            m(1, 40.0, -85.0, -80.0), m(1, 45.0, -86.0, -80.0), m(1, 50.0, -84.0, -81.0), m(1, 55.0, -84.0, -80.0))
        val bas = StatsPolarisation.prediction(l, 1, Bande.U, 3.0)!!
        assertEquals(Pol.H, bas.pol); assertEquals(0, bas.tranche); assertEquals(3, bas.bilan.n)
        val haut = StatsPolarisation.prediction(l, 1, Bande.U, 47.0)!!
        assertEquals(Pol.V, haut.pol); assertEquals(0.8, haut.bilan.confiance, 0.001)
        // Another band, another satellite: nothing known.
        assertNull(StatsPolarisation.prediction(l, 1, Bande.V, 47.0))
        assertNull(StatsPolarisation.prediction(l, 2, Bande.U, 47.0))
        // Too few in that band: the satellite's whole record, here split evenly → nothing clear.
        assertNull(StatsPolarisation.prediction(l, 1, Bande.U, 25.0))
    }

    @Test fun pas_de_prediction_quand_c_est_partage_ou_trop_maigre() {
        assertNull(StatsPolarisation.prediction(listOf(m(1, 5.0, -80.0, -84.0), m(1, 6.0, -80.0, -84.0)), 1, Bande.U, 5.0))
        val partage = listOf(m(1, 5.0, -80.0, -84.0), m(1, 6.0, -84.0, -80.0), m(1, 7.0, -80.0, -80.5), m(1, 8.0, -84.0, -80.0), m(1, 9.0, -80.0, -84.0))
        assertNull(StatsPolarisation.prediction(partage, 1, Bande.U, 5.0))
        // Under 1 dB: half a win each.
        assertEquals(StatsPolarisation.Bilan(1, 0.5, 0.5, -0.5), StatsPolarisation.bilan(listOf(m(1, 5.0, -80.5, -80.0))))
    }

    @Test fun le_fichier_aller_retour_et_le_tableau() {
        val l = listOf(m(1, 5.0, -80.0, -84.0), m(2, 40.0, -85.0, -80.0, Bande.V))
        val relu = StatsPolarisation.litFichier((l.map { StatsPolarisation.ligneFichier(it) } + "pas du json" + "{\"cassé\":1}").asSequence())
        assertEquals(l, relu)
        val t = StatsPolarisation.tableau(relu)
        assertEquals(2, t.size)
        assertEquals(Bande.V, t.first { it.catnum == 2 }.bande)
    }
}
