/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.MoletteTx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La molette d'émission tenue pour un bouton de décalage.
 *
 * Ces essais existent parce que le calcul qu'ils couvrent a été faux deux fois
 * de suite et livré deux fois. Il vivait au milieu de la boucle CAT, mêlé à des
 * lectures série et à des écritures, donc hors d'atteinte du banc — et c'est
 * l'opérateur, devant son poste, qui a dû le démasquer.
 */
class MoletteTxTest {

    private val consigne = 435_100_000L

    @Test
    fun un_vrai_geste_est_absorbe_une_fois() {
        val d = MoletteTx.decide(
            shiftHz = 100L, referenceHz = consigne, lueHz = consigne + 300L,
            gesteVu = true, moletteTranquille = true)
        assertTrue(d.absorbe)
        assertEquals(400L, d.shiftHz)
        assertTrue(d.gesteConsomme)
    }

    @Test
    fun un_geste_vers_le_bas_soustrait() {
        val d = MoletteTx.decide(50L, consigne, consigne - 250L, true, true)
        assertEquals(-200L, d.shiftHz)
    }

    /**
     * **La régression.**
     *
     * On rejoue exactement la panne observée : le Doppler dérive, la boucle
     * tourne, et personne ne touche à la molette. La lecture du poste s'écarte
     * donc de la montée recalculée à l'instant — mais pas de la dernière
     * consigne écrite, qui est la seule référence honnête. Le décalage ne doit
     * pas bouger d'un hertz.
     *
     * L'ancien calcul comparait à la montée fraîche et absorbait la dérive :
     * le décalage oscillait sans fin entre deux valeurs, et la fonction était
     * inutilisable.
     */
    @Test
    fun la_derive_doppler_sans_geste_ne_bouge_pas_le_decalage() {
        var shift = 480L
        var reference = consigne
        // Deux minutes de boucle à un tour par seconde, avec un Doppler qui
        // court : le poste répond toujours ce qu'on lui a écrit.
        repeat(120) {
            val d = MoletteTx.decide(
                shiftHz = shift, referenceHz = reference, lueHz = reference,
                gesteVu = false, moletteTranquille = true)
            assertFalse(d.absorbe)
            shift = d.shiftHz
            reference = d.referenceHz
        }
        assertEquals(480L, shift)
    }

    /**
     * Même chose, mais avec l'arrondi du poste : le FT-817 quantifie, et sa
     * réponse n'est jamais exactement ce qu'on lui a demandé. Sans geste, cela
     * ne doit rien déclencher non plus.
     */
    @Test
    fun l_arrondi_du_poste_sans_geste_ne_declenche_rien() {
        var shift = 0L
        repeat(50) { i ->
            val d = MoletteTx.decide(
                shiftHz = shift, referenceHz = consigne,
                lueHz = consigne + (if (i % 2 == 0) 10L else -10L),
                gesteVu = false, moletteTranquille = true)
            shift = d.shiftHz
        }
        assertEquals(0L, shift)
    }

    /**
     * Le point qui fermait la boucle infinie : après absorption, la référence
     * suit le poste. Sans cela, le même écart serait réabsorbé au tour suivant
     * et le décalage doublerait à chaque passage.
     */
    @Test
    fun la_reference_suit_le_poste_apres_absorption() {
        val premier = MoletteTx.decide(0L, consigne, consigne + 300L, true, true)
        assertEquals(300L, premier.shiftHz)
        assertEquals(consigne + 300L, premier.referenceHz)

        // Tour suivant : plus de geste, la lecture est identique. Rien ne bouge.
        val second = MoletteTx.decide(
            premier.shiftHz, premier.referenceHz, consigne + 300L, false, true)
        assertFalse(second.absorbe)
        assertEquals(300L, second.shiftHz)
    }

    /** Molette encore en mouvement : une position de passage n'est pas une intention. */
    @Test
    fun rien_ne_s_absorbe_tant_que_la_molette_tourne() {
        val d = MoletteTx.decide(0L, consigne, consigne + 5_000L,
            gesteVu = true, moletteTranquille = false)
        assertFalse(d.absorbe)
        assertFalse(d.gesteConsomme)
    }

    /**
     * Un geste trop petit est consommé sans être absorbé. Le laisser en attente
     * le ferait absorber plus tard, avec un écart qui aurait entre-temps changé
     * de sens.
     */
    @Test
    fun un_geste_sous_le_seuil_est_consomme_sans_etre_absorbe() {
        val d = MoletteTx.decide(0L, consigne, consigne + 10L, true, true)
        assertFalse(d.absorbe)
        assertTrue(d.gesteConsomme)
        assertEquals(0L, d.shiftHz)
    }

    /** Rien n'a encore été écrit : aucune référence, donc aucune mesure. */
    @Test
    fun sans_consigne_ecrite_on_ne_mesure_rien() {
        val d = MoletteTx.decide(0L, 0L, consigne, true, true)
        assertFalse(d.absorbe)
        assertTrue(d.gesteConsomme)
    }

    /**
     * Deux gestes successifs s'additionnent, comme deux appuis sur les boutons.
     */
    @Test
    fun deux_gestes_successifs_s_additionnent() {
        val a = MoletteTx.decide(0L, consigne, consigne + 200L, true, true)
        val b = MoletteTx.decide(a.shiftHz, a.referenceHz, a.referenceHz + 150L, true, true)
        assertEquals(350L, b.shiftHz)
    }

    /**
     * Le seuil d'absorption est celui de l'écriture, et ce n'est pas une
     * coïncidence : un écart qu'on ne juge pas digne d'être écrit ne peut pas
     * être digne d'être absorbé.
     */
    @Test
    fun le_seuil_est_celui_de_l_ecriture() {
        assertEquals(20L, MoletteTx.SEUIL_HZ)
    }
}
