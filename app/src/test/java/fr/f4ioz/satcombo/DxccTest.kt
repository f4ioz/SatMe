/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.Dxcc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Le pays d'un indicatif, d'après son préfixe. */
class DxccTest {

    @Test
    fun les_prefixes_courants_rendent_leur_pays() {
        assertEquals("France", Dxcc.entite("F4IOZ")?.nom)
        assertEquals("FR", Dxcc.entite("F4IOZ")?.drapeau)
        assertEquals("Allemagne", Dxcc.entite("DL2MF")?.nom)
        assertEquals("Espagne", Dxcc.entite("EA3DIX")?.nom)
        assertEquals("Angleterre", Dxcc.entite("G0ABI")?.nom)
        assertEquals("Japon", Dxcc.entite("JF9SOM")?.nom)
        assertEquals("États-Unis", Dxcc.entite("K5XYZ")?.nom)
        assertEquals("États-Unis", Dxcc.entite("W1AW")?.nom)
    }

    /** Le préfixe le plus long gagne : EA8 est les Canaries, pas l'Espagne. */
    @Test
    fun le_prefixe_le_plus_long_gagne() {
        assertEquals("Canaries", Dxcc.entite("EA8ABC")?.nom)
        assertEquals("Sardaigne", Dxcc.entite("IS0XYZ")?.nom)
        assertEquals("Crète", Dxcc.entite("SV9ABC")?.nom)
        assertEquals("Corse", Dxcc.entite("TK5EP")?.nom)
    }

    /**
     * EA5/F4IOZ est en Espagne : le préfixe pays prime sur l'indicatif
     * d'origine — c'est tout son sens.
     */
    @Test
    fun le_prefixe_pays_prime() {
        assertEquals("Espagne", Dxcc.entite("EA5/F4IOZ")?.nom)
        assertEquals("Suisse", Dxcc.entite("HB9/DL2MF")?.nom)
    }

    /** /P et /M ne changent pas le pays. */
    @Test
    fun le_suffixe_d_exploitation_ne_change_pas_le_pays() {
        assertEquals("France", Dxcc.entite("F4IOZ/P")?.nom)
        assertEquals("Angleterre", Dxcc.entite("G0ABI/M")?.nom)
    }

    @Test
    fun l_ecosse_et_le_pays_de_galles_se_distinguent_de_l_angleterre() {
        assertEquals("Écosse", Dxcc.entite("MM0XYZ")?.nom)
        assertEquals("Pays de Galles", Dxcc.entite("GW4ABC")?.nom)
    }

    @Test
    fun un_prefixe_inconnu_rend_nul_plutot_qu_un_pays_faux() {
        assertNull(Dxcc.entite("XX9XX"))
        assertNull(Dxcc.entite(""))
    }
}
