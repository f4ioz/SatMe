/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.StationsQo100
import fr.f4ioz.satcombo.domain.StationsQo100.Etalonnage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le banc de l'étalonnage QO-100.
 *
 * Les valeurs de référence sont celles mesurées par F4IOZ le 3 septembre 2026
 * contre le WebSDR IS0GRB, lui-même sur GPSDO. Elles ne sont pas inventées
 * pour l'occasion : c'est un relevé réel, confirmé deux heures plus tard avec
 * extinction complète entre les deux essais.
 */
class StationsQo100Test {

    private val ciel = 10_489_805_600L   // WebSDR IS0GRB
    private val poste = 144_832_650L     // FT-817, USB
    private val olAttendu = 10_344_972_950L

    @Test
    fun l_oscillateur_se_deduit_de_deux_frequences_lues() {
        val r = StationsQo100.etalonne(ciel, poste)
        assertTrue(r is Etalonnage.Trouve)
        assertEquals(olAttendu, (r as Etalonnage.Trouve).olHz)
    }

    /**
     * L'erreur naturelle : on lit d'abord son poste, qui est devant soi, puis
     * le WebSDR. Une différence négative la trahit immédiatement.
     */
    @Test
    fun les_champs_intervertis_sont_refuses() {
        val r = StationsQo100.etalonne(poste, ciel)
        assertTrue(r is Etalonnage.Refuse)
        assertEquals("qo100_cal_inverse", (r as Etalonnage.Refuse).motif)
    }

    @Test
    fun un_champ_vide_est_refuse() {
        assertTrue(StationsQo100.etalonne(0L, poste) is Etalonnage.Refuse)
        assertTrue(StationsQo100.etalonne(ciel, 0L) is Etalonnage.Refuse)
    }

    /** Deux fréquences voisines ne donnent pas un oscillateur crédible. */
    @Test
    fun un_ecart_absurde_est_refuse() {
        val r = StationsQo100.etalonne(10_489_805_600L, 10_489_805_000L)
        assertTrue(r is Etalonnage.Refuse)
        assertEquals("qo100_cal_absurde", (r as Etalonnage.Refuse).motif)
    }

    // ------------------------------------------------ ce que vaut l'écart

    /**
     * −27 kHz d'écart au nominal, soit **−2,6 ppm** : c'est le TCXO du LNB,
     * pas un défaut, et c'est conforme à sa spécification de 2 ppm.
     *
     * Le nominal est 10 345 000 kHz — celui qui range la balise CW basse à
     * 144,500. L'autre lecture du manuel DX Patrol, qui la range à 144,550,
     * donne un nominal 50 kHz plus bas et un écart de +23 kHz. Les deux
     * lectures restent possibles ; aucune mesure relative ne les départage, et
     * cela n'a plus d'importance dès lors que l'oscillateur absolu est connu.
     *
     * Un GPSDO ne corrigera pas cet écart : il ne touche pas au LNB, dont
     * l'oscillateur est interne.
     */
    @Test
    fun l_ecart_au_nominal_se_mesure() {
        val nominal = 10_345_000_000L
        assertEquals(-27_050L, StationsQo100.ecartAuNominal(olAttendu, nominal))
        val ppm = StationsQo100.ecartPpm(olAttendu, nominal)
        assertTrue("ppm = $ppm", ppm in -2.7..-2.5)
    }

    /** L'autre lecture du nominal, 50 kHz plus bas, donne +23 kHz. */
    @Test
    fun l_autre_nominal_donne_l_ecart_symetrique() {
        assertEquals(22_950L,
            StationsQo100.ecartAuNominal(olAttendu, 10_344_950_000L))
    }

    @Test
    fun un_nominal_absent_ne_divise_pas_par_zero() {
        assertEquals(0.0, StationsQo100.ecartPpm(olAttendu, 0L), 0.0)
    }

    // ------------------------------------------------------- les stations

    @Test
    fun une_station_reglee_convertit_dans_les_deux_sens() {
        val s = StationsQo100.Station("fixe", descenteOlHz = olAttendu,
            monteeOlHz = 1_968_000_000L)
        assertEquals(poste, s.posteRx(ciel))
        assertEquals(2_400_250_000L - 1_968_000_000L, s.posteTx(2_400_250_000L))
    }

    /** Sans convertisseur, la fréquence traverse sans changer. */
    @Test
    fun une_station_sans_etage_laisse_passer() {
        val s = StationsQo100.Station("directe")
        assertEquals(ciel, s.posteRx(ciel))
        assertEquals(2_400_250_000L, s.posteTx(2_400_250_000L))
    }

    /**
     * Une station non étalonnée doit se dire telle, plutôt que de proposer une
     * valeur nominale qui aurait l'air juste sans l'être.
     */
    @Test
    fun les_stations_par_defaut_ne_sont_pas_etalonnees() {
        val d = StationsQo100.parDefaut()
        assertEquals(2, d.size)
        d.forEach {
            assertTrue(!it.descenteReglee)
            assertTrue(!it.monteeReglee)
        }
    }

    @Test
    fun remplacer_ne_reordonne_pas_la_liste() {
        val liste = StationsQo100.parDefaut()
        val neuf = StationsQo100.remplace(liste, 1,
            liste[1].copy(descenteOlHz = olAttendu))
        assertEquals(listOf("fixe", "portable"), neuf.map { it.nom })
        assertEquals(olAttendu, neuf[1].descenteOlHz)
        assertEquals(0L, neuf[0].descenteOlHz)
    }

    @Test
    fun un_index_hors_liste_ne_casse_rien() {
        val liste = StationsQo100.parDefaut()
        assertEquals(liste, StationsQo100.remplace(liste, 7, liste[0]))
        assertEquals(liste, StationsQo100.remplace(liste, -1, liste[0]))
    }

    /**
     * Le montage fixe et le montage portable n'ont pas le même LNB, donc pas
     * le même oscillateur. C'est toute la raison d'être de cette liste.
     */
    @Test
    fun deux_stations_gardent_chacune_son_etalonnage() {
        var liste = StationsQo100.parDefaut()
        liste = StationsQo100.remplace(liste, 0,
            liste[0].copy(descenteOlHz = olAttendu))
        liste = StationsQo100.remplace(liste, 1,
            liste[1].copy(descenteOlHz = 9_750_123_000L))
        assertEquals(poste, liste[0].posteRx(ciel))
        assertEquals(ciel - 9_750_123_000L, liste[1].posteRx(ciel))
    }
}
