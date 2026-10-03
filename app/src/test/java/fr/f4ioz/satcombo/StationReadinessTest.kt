/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.StationReadiness
import fr.f4ioz.satcombo.domain.StationReadiness.Action
import fr.f4ioz.satcombo.domain.StationReadiness.Boussole
import fr.f4ioz.satcombo.domain.StationReadiness.Domaine
import fr.f4ioz.satcombo.domain.StationReadiness.Instantane
import fr.f4ioz.satcombo.domain.StationReadiness.Niveau
import fr.f4ioz.satcombo.domain.StationReadiness.Pointage
import fr.f4ioz.satcombo.domain.StationReadiness.Profil
import fr.f4ioz.satcombo.domain.StationReadiness.SanteCat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Station readiness: one light per domain, severity from the station's profile. */
class StationReadinessTest {

    private val maintenant = 1_000_000_000_000L
    private val jour = 86_400_000L

    /** A ready station: IC-9700 on CAT, rotor tracking, recording on the USB card, Wavelog in sync. */
    private val pret = Instantane(
        satNom = "RS-44", epochMs = maintenant - jour / 2, prochainAosMs = maintenant + 600_000,
        prochainLosMs = maintenant + 1_400_000, transpondeurs = 3,
        observateur = true, manuel = true, indicatif = "F4IOZ", locator = "JN18FS",
        catActive = true, catConnecte = true, catSante = SanteCat.OK, relectureRx = true,
        rotorDisponible = true, rotorConnecte = true, rotorSuivi = true, rotorRelu = true,
        source = "USB", permissionMicro = true, carteUsbPresente = true,
        carnetConfigure = true, carnetAuto = true, carnetProfil = true,
    )
    private val complet = Profil(catRequis = true, pointage = Pointage.ROTOR, enregistrementRequis = true, synchroRequise = true)

    private fun voyant(i: Instantane, p: Profil, d: Domaine) =
        StationReadiness.evalue(i, p, maintenant).single { it.domaine == d }

    @Test
    fun une_station_prete_est_toute_verte() {
        val l = StationReadiness.evalue(pret, complet, maintenant)
        assertEquals(9, l.size)
        assertTrue(l.joinToString { "${it.domaine}=${it.niveau}" }, l.all { it.niveau == Niveau.OK })
        assertEquals(Niveau.OK, StationReadiness.global(l))
    }

    // ------------------------------------------------------------ satellite

    @Test
    fun elements_vieux_hors_ligne_ou_absents() {
        val vieux = pret.copy(epochMs = maintenant - 5 * jour)
        voyant(vieux, complet, Domaine.SAT).let {
            assertEquals(Niveau.WARNING, it.niveau); assertEquals(Action.RAFRAICHIR_ELEMENTS, it.action); assertEquals(listOf("5"), it.args)
        }
        assertEquals(Niveau.WARNING, voyant(pret.copy(cacheHorsLigne = true), complet, Domaine.SAT).niveau)
        assertEquals(Niveau.ERROR, voyant(pret.copy(epochMs = null), complet, Domaine.SAT).niveau)
        assertEquals(Niveau.ERROR, voyant(pret.copy(prochainAosMs = null), complet, Domaine.SAT).niveau)
        assertEquals(Niveau.ERROR, voyant(pret.copy(satNom = null), complet, Domaine.SAT).niveau)
    }

    @Test
    fun transpondeurs_et_statut() {
        assertEquals("rd_sat_sans_transpondeur", voyant(pret.copy(transpondeurs = 0), complet, Domaine.SAT).raison)
        // Still loading: not a fault yet.
        assertEquals(Niveau.OK, voyant(pret.copy(transpondeurs = 0, transpondeursEnCours = true), complet, Domaine.SAT).niveau)
        assertEquals(Niveau.WARNING, voyant(pret.copy(satMuet = true), complet, Domaine.SAT).niveau)
        assertEquals(Niveau.WARNING, voyant(pret.copy(amsatPasEntendu = true), complet, Domaine.SAT).niveau)
    }

    // ------------------------------------------------------------- position

    @Test
    fun position_manuelle_et_gps() {
        assertEquals(Niveau.ERROR, voyant(pret.copy(observateur = false), complet, Domaine.POSITION).niveau)
        assertEquals(Niveau.ERROR, voyant(pret.copy(locatorInvalide = true), complet, Domaine.POSITION).niveau)
        val gps = pret.copy(manuel = false, gpsPermission = true, gpsPoints = 4)
        assertEquals(Niveau.OK, voyant(gps, complet, Domaine.POSITION).niveau)
        assertEquals(Niveau.ERROR, voyant(gps.copy(gpsPermission = false), complet, Domaine.POSITION).niveau)
        assertEquals(Niveau.WARNING, voyant(gps.copy(gpsPoints = 0), complet, Domaine.POSITION).niveau)
        // The built-in default place, shown as "GPS": a warning, not a silent OK.
        assertEquals("rd_pos_par_defaut", voyant(gps.copy(positionParDefaut = true), complet, Domaine.POSITION).raison)
    }

    // ------------------------------------------------------------ CAT, Doppler

    @Test
    fun cat_configure_mais_non_connecte_n_est_qu_un_avertissement() {
        val l = StationReadiness.evalue(pret.copy(catConnecte = false), complet, maintenant)
        val cat = l.single { it.domaine == Domaine.CAT }
        assertEquals(Niveau.WARNING, cat.niveau)
        assertEquals(Action.CONNECTER_CAT, cat.action)
        assertEquals("rd_dop_sans_cat", l.single { it.domaine == Domaine.DOPPLER }.raison)
        // The operator may still work by hand: the station is "to check", not blocked.
        assertEquals(Niveau.WARNING, StationReadiness.global(l))
    }

    @Test
    fun cat_muet_simule_ou_paire_incomplete() {
        assertEquals(Niveau.ERROR, voyant(pret.copy(catSante = SanteCat.MUETTE), complet, Domaine.CAT).niveau)
        assertEquals(Niveau.WARNING, voyant(pret.copy(catSimule = true), complet, Domaine.CAT).niveau)
        assertEquals(Niveau.WARNING, voyant(pret.copy(catPaireIncomplete = true), complet, Domaine.CAT).niveau)
        assertEquals(Niveau.WARNING, voyant(pret.copy(catModeDifferent = true), complet, Domaine.CAT).niveau)
    }

    @Test
    fun sans_cat_requis_cat_et_doppler_ne_comptent_pas() {
        val p = complet.copy(catRequis = false)
        val l = StationReadiness.evalue(pret.copy(catActive = false, catConnecte = false), p, maintenant)
        assertEquals(Niveau.NOT_REQUIRED, l.single { it.domaine == Domaine.CAT }.niveau)
        assertEquals(Niveau.NOT_REQUIRED, l.single { it.domaine == Domaine.DOPPLER }.niveau)
        assertEquals(Niveau.OK, StationReadiness.global(l))
    }

    @Test
    fun doppler_en_pause() {
        voyant(pret.copy(dopplerEnPause = true), complet, Domaine.DOPPLER).let {
            assertEquals(Niveau.WARNING, it.niveau); assertEquals(Action.REPRENDRE_DOPPLER, it.action)
        }
    }

    // ------------------------------------------------------------- pointing

    @Test
    fun antenne_manuelle_jamais_de_rotor_rouge() {
        val p = complet.copy(pointage = Pointage.MANUEL)
        // No rotor at all, extension locked: still not red.
        val sansRotor = pret.copy(rotorDisponible = false, rotorConnecte = false, rotorSuivi = false, rotorRelu = false)
        val v = voyant(sansRotor, p, Domaine.POINTAGE)
        assertEquals(Niveau.OK, v.niveau); assertEquals("rd_pt_manuel", v.raison)
        assertEquals(Niveau.WARNING, voyant(sansRotor.copy(boussole = Boussole.ECHEC), p, Domaine.POINTAGE).niveau)
        assertEquals(Niveau.OK, voyant(sansRotor.copy(boussole = Boussole.CONNECTEE), p, Domaine.POINTAGE).niveau)
    }

    @Test
    fun rotor_attendu() {
        assertEquals(Niveau.ERROR, voyant(pret.copy(rotorConnecte = false), complet, Domaine.POINTAGE).niveau)
        // Connected but silent for more than 4 s: the mast is lost.
        assertEquals("rd_pt_rotor_muet", voyant(pret.copy(rotorRelu = false), complet, Domaine.POINTAGE).raison)
        assertEquals(Niveau.WARNING, voyant(pret.copy(rotorSimule = true, rotorRelu = false), complet, Domaine.POINTAGE).niveau)
        assertEquals(Niveau.WARNING, voyant(pret.copy(rotorSuivi = false), complet, Domaine.POINTAGE).niveau)
        assertEquals(Niveau.WARNING, voyant(pret.copy(rotorHorsCourse = true), complet, Domaine.POINTAGE).niveau)
    }

    // ------------------------------------------------------ audio, recording

    @Test
    fun audio_et_enregistrement() {
        assertEquals(Niveau.ERROR, voyant(pret.copy(permissionMicro = false), complet, Domaine.AUDIO).niveau)
        // USB card missing: the recording goes on the phone mic, a warning only.
        assertEquals("rd_audio_usb_absente", voyant(pret.copy(carteUsbPresente = false), complet, Domaine.AUDIO).raison)
        assertEquals(Niveau.ERROR, voyant(pret.copy(source = "BT", permissionBluetooth = false), complet, Domaine.AUDIO).niveau)
        assertEquals(Niveau.WARNING, voyant(pret.copy(enregistreurActif = false), complet, Domaine.ENREGISTREMENT).niveau)
        assertEquals("rd_rec_arme", voyant(pret.copy(enregistrementArme = true), complet, Domaine.ENREGISTREMENT).raison)
        val p = complet.copy(enregistrementRequis = false)
        assertEquals(Niveau.NOT_REQUIRED, voyant(pret.copy(permissionMicro = false), p, Domaine.AUDIO).niveau)
        assertEquals(Niveau.NOT_REQUIRED, voyant(pret, p, Domaine.ENREGISTREMENT).niveau)
        // Armed automatic SSTV needs the microphone even when recording is "not required".
        assertEquals(Niveau.ERROR, voyant(pret.copy(permissionMicro = false, enregistrementArme = true), p, Domaine.AUDIO).niveau)
    }

    // --------------------------------------------------------------- log, sync

    @Test
    fun carnet_et_synchro() {
        assertEquals(Niveau.ERROR, voyant(pret.copy(indicatif = ""), complet, Domaine.CARNET).niveau)
        assertEquals(Niveau.WARNING, voyant(pret.copy(locator = ""), complet, Domaine.CARNET).niveau)
        assertEquals(Niveau.ERROR, voyant(pret.copy(carnetProfil = false), complet, Domaine.SYNCHRO).niveau)
        assertEquals(Niveau.ERROR, voyant(pret.copy(contactsRefuses = 1), complet, Domaine.SYNCHRO).niveau)
        voyant(pret.copy(contactsEnAttente = 3), complet, Domaine.SYNCHRO).let {
            assertEquals(Niveau.WARNING, it.niveau); assertEquals(Action.DEPOSER_CARNET, it.action); assertEquals(listOf("3"), it.args)
        }
        // Not configured: a bonus, never a prerequisite unless the profile asks.
        val sans = pret.copy(carnetConfigure = false)
        assertEquals(Niveau.NOT_REQUIRED, voyant(sans, complet.copy(synchroRequise = false), Domaine.SYNCHRO).niveau)
        assertEquals(Niveau.WARNING, voyant(sans, complet, Domaine.SYNCHRO).niveau)
    }

    @Test
    fun le_global_ignore_ce_qui_n_est_pas_requis() {
        val l = StationReadiness.evalue(pret.copy(catConnecte = false, carnetConfigure = false),
            complet.copy(catRequis = false, synchroRequise = false), maintenant)
        assertEquals(Niveau.OK, StationReadiness.global(l))
        assertEquals(Niveau.ERROR, StationReadiness.global(StationReadiness.evalue(pret.copy(indicatif = ""), complet, maintenant)))
    }

    // ------------------------------------------------------------- profiles

    @Test
    fun profils_fixe_et_portable_relus_a_l_identique() {
        val l = StationReadiness.profilsDeDepart("Fixe", "Portable", complet) +
            StationReadiness.ProfilNomme("p3", "SOTA\tsommet", Profil(false, Pointage.MANUEL, false, false))
        val relus = StationReadiness.litProfils(StationReadiness.ecritProfils(l))
        assertEquals(3, relus.size)
        assertEquals(l[0], relus[0]); assertEquals(l[1], relus[1])
        assertEquals("SOTA sommet", relus[2].nom)   // a tab in a name cannot break the line
        assertEquals(false, relus[1].profil.catRequis)
        assertEquals("p4", StationReadiness.nouvelId(relus))
        // A damaged line is dropped, the others kept.
        assertEquals(1, StationReadiness.litProfils("x\tY\n" + StationReadiness.ecritProfils(l.take(1))).size)
    }

    @Test
    fun le_profil_portable_ne_reclame_ni_cat_ni_rotor() {
        val portable = StationReadiness.profilsDeDepart("F", "P")[1].profil
        val sans = pret.copy(catActive = false, catConnecte = false, rotorDisponible = false, rotorConnecte = false,
            rotorSuivi = false, rotorRelu = false, carnetConfigure = false)
        assertEquals(Niveau.OK, StationReadiness.global(StationReadiness.evalue(sans, portable, maintenant)))
    }

    @Test
    fun le_poste_la_boussole_et_l_audio_sont_gardes() {
        val p = StationReadiness.ProfilNomme("p3", "Portable 817", Profil(true, Pointage.MANUEL, true, false),
            poste = "FT817x2", boussole = "BLE", audio = "USB")
        assertEquals(p, StationReadiness.litProfils(StationReadiness.ecritProfils(listOf(p))).single())
        // A line of the first format (six fields) still reads, with nothing to apply.
        val ancien = StationReadiness.litProfils("fixe\tFixe\ttrue\tROTOR\ttrue\tfalse").single()
        assertEquals(null, ancien.poste); assertEquals(Pointage.ROTOR, ancien.profil.pointage)
    }
}
