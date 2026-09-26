/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.ui.CoinsAim
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Les deux coins chiffrés de la boussole.
 *
 * « Quand le rotor est connecté, la boussole est dirigée par les éléments
 * élévation azimut du rotor. Même quand le satellite n'est pas à vue. »
 *
 * Le piège tient dans le libellé plus que dans le chiffre : « 155° » sous
 * `AZ` et « 155° » sous `AZ MÂT` ne disent pas la même chose, et rien à
 * l'écran ne permet de les distinguer après coup. On essaie donc les deux à
 * chaque fois.
 */
class CoinsAimTest {

    @Test
    fun sous_l_horizon_le_mat_remplace_les_tirets() {
        // Le cas du 2 août à 19 h : ISS sous l'horizon, mât branché à 155°.
        val c = CoinsAim.coin(mat = 155.0, sat = null, satVisible = false,
            libelleMat = "AZ MÂT", libelleSat = "AZ")
        assertEquals("AZ MÂT", c.libelle)
        assertEquals("155°", c.valeur)
    }

    @Test
    fun sans_rotor_ni_satellite_il_reste_le_tiret() {
        val c = CoinsAim.coin(null, null, false, "AZ MÂT", "AZ")
        assertEquals("AZ", c.libelle)
        assertEquals(CoinsAim.RIEN, c.valeur)
    }

    @Test
    fun sans_rotor_le_satellite_visible_est_affiche_comme_avant() {
        val c = CoinsAim.coin(null, 212.7, true, "ÉL MÂT", "AZ")
        assertEquals("AZ", c.libelle)
        // Le satellite garde la troncature d'origine : on ne change pas
        // l'affichage existant en passant.
        assertEquals("212°", c.valeur)
    }

    @Test
    fun la_position_lue_passe_devant_le_satellite_visible() {
        // Les deux existent : c'est le mât qui décrit le monde réel.
        val c = CoinsAim.coin(mat = 155.0, sat = 212.7, satVisible = true,
            libelleMat = "AZ MÂT", libelleSat = "AZ")
        assertEquals("AZ MÂT", c.libelle)
        assertEquals("155°", c.valeur)
    }

    @Test
    fun un_mat_muet_rend_la_main_au_satellite_et_le_libelle_le_dit() {
        // readPosition() muet : rotorAimAz repasse à null. Le libellé qui
        // redevient « AZ » est le seul signe visible que l'écran a changé de
        // source — sans lui, on croirait le mât immobile.
        val c = CoinsAim.coin(null, 212.0, true, "AZ MÂT", "AZ")
        assertEquals("AZ", c.libelle)
        assertEquals("212°", c.valeur)
    }

    @Test
    fun le_mat_est_arrondi_et_non_tronque() {
        // 155,6° affiché « 155° » ferait croire à un demi-degré d'erreur de
        // pointage qui n'existe pas.
        assertEquals("156°", CoinsAim.coin(155.6, null, false, "M", "S").valeur)
        assertEquals("16°", CoinsAim.coin(15.5, null, false, "M", "S").valeur)
        assertEquals("0°", CoinsAim.coin(0.0, null, false, "M", "S").valeur)
    }

    @Test
    fun une_valeur_impossible_ne_s_affiche_pas() {
        // Un NaN venu d'un calcul raté vaut mieux affiché en tiret qu'en
        // « NaN° » au milieu du cadran.
        val c = CoinsAim.coin(Double.NaN, null, false, "AZ MÂT", "AZ")
        assertEquals("AZ", c.libelle)
        assertEquals(CoinsAim.RIEN, c.valeur)
    }

    @Test
    fun un_rotor_d_azimut_seul_laisse_l_elevation_au_satellite() {
        // rotorAimEl est null sur un mât d'azimut seul : le coin d'élévation
        // ne doit pas inventer un zéro.
        val c = CoinsAim.coin(null, 45.0, true, "ÉL MÂT", "ÉL")
        assertEquals("ÉL", c.libelle)
        assertEquals("45°", c.valeur)
    }
}
