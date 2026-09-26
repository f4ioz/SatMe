/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * L'analyseur de spectre du moniteur audio, au banc.
 *
 * Ce qu'on demande à cet objet est modeste et vérifiable sans téléphone : qu'un
 * sifflement à 1 500 Hz allume la barre qui est à 1 500 Hz, que le silence
 * laisse les barres à zéro, et que la crête dise vraiment ce qui entre. Les
 * trois répondent à la même question de l'opérateur — « est-ce que le son
 * arrive, et à quel niveau » — et une erreur d'échelle ou de fenêtre les fait
 * toutes tomber.
 */
class SpectreTest {

    private fun sinus(a: AnalyseurSpectre, freq: Double, amplitude: Double,
                      trames: Int = 4) {
        val n = a.taille * trames
        val pcm = ShortArray(n) {
            (amplitude * 32767.0 * sin(2.0 * PI * freq * it / a.rate)).toInt().toShort()
        }
        a.pousser(pcm, n)
    }

    @Test
    fun une_tonalite_allume_la_bande_qui_lui_correspond() {
        val a = AnalyseurSpectre()
        sinus(a, 1_500.0, 0.5)
        val b = a.bandeLaPlusForte()
        assertTrue("aucune bande allumée", b >= 0)
        val centre = a.centreHz(b)
        assertTrue("bande la plus forte à $centre Hz au lieu de 1500",
            kotlin.math.abs(centre - 1_500.0) < 150.0)
    }

    @Test
    fun deux_tonalites_eloignees_ne_se_confondent_pas() {
        val basse = AnalyseurSpectre().also { sinus(it, 800.0, 0.5) }
        val haute = AnalyseurSpectre().also { sinus(it, 2_300.0, 0.5) }
        assertTrue("les deux tonalités tombent dans la même bande",
            basse.bandeLaPlusForte() < haute.bandeLaPlusForte())
    }

    @Test
    fun le_silence_laisse_toutes_les_bandes_a_zero() {
        val a = AnalyseurSpectre()
        a.pousser(ShortArray(a.taille * 2), a.taille * 2)
        assertTrue("une bande s'allume sur du silence : ${a.bandes.toList()}",
            a.bandes.all { it == 0f })
        assertEquals(0f, a.crete, 1e-6f)
    }

    @Test
    fun la_crete_suit_le_niveau_qui_entre() {
        val fort = AnalyseurSpectre().also { sinus(it, 1_000.0, 0.9) }
        val faible = AnalyseurSpectre().also { sinus(it, 1_000.0, 0.1) }
        assertTrue("crête forte ${fort.crete}", fort.crete > 0.8f)
        assertTrue("crête faible ${faible.crete}", faible.crete < 0.2f)
        assertTrue("la crête ne dépasse jamais un", fort.crete <= 1f)
    }

    @Test
    fun une_saturation_se_voit_a_la_crete() {
        // Un signal collé aux butées : c'est exactement ce que le moniteur doit
        // montrer en magenta pour dire de baisser le volume du poste.
        val a = AnalyseurSpectre()
        val n = a.taille * 2
        val pcm = ShortArray(n) { if ((it / 8) % 2 == 0) 32_767 else -32_768 }
        a.pousser(pcm, n)
        assertTrue("saturation non détectée : ${a.crete}", a.crete >= 0.95f)
    }

    @Test
    fun les_trames_se_comptent_et_le_reste_est_garde() {
        // Le service livre des blocs qui ne tombent pas juste sur la taille de
        // la transformée : le reste doit être conservé d'un bloc à l'autre,
        // sinon un morceau de son sur trois n'est jamais analysé.
        val a = AnalyseurSpectre()
        val bloc = ShortArray(300)
        repeat(10) { a.pousser(bloc, bloc.size) }
        // 3 000 échantillons : deux transformées de 1 024, et 952 échantillons
        // gardés sous le coude pour le bloc suivant.
        assertEquals(2L, a.trames)
        assertTrue(a.trames * a.taille <= 3_000)
    }

    @Test
    fun le_reset_remet_tout_a_plat() {
        val a = AnalyseurSpectre()
        sinus(a, 1_500.0, 0.7)
        a.reset()
        assertEquals(0L, a.trames)
        assertEquals(0f, a.crete, 1e-6f)
        assertTrue(a.bandes.all { it == 0f })
        assertEquals(-1, a.bandeLaPlusForte())
    }

    @Test
    fun les_bandes_restent_ordonnees_a_seize_kilohertz() {
        // La liaison Bluetooth capture à 16 kHz : les bornes de bandes doivent
        // rester strictement croissantes, sinon une bande vide plante le calcul.
        val a = AnalyseurSpectre(rate = 16_000)
        var precedent = -1.0
        for (i in 0 until a.nbBandes) {
            val c = a.centreHz(i)
            assertTrue("bande $i à $c Hz après $precedent", c > precedent)
            precedent = c
        }
        assertTrue("dernière bande à $precedent Hz", precedent <= 3_600.0)
        sinus(a, 1_200.0, 0.5)
        assertTrue(kotlin.math.abs(a.centreHz(a.bandeLaPlusForte()) - 1_200.0) < 200.0)
    }

    @Test
    fun une_taille_qui_nest_pas_une_puissance_de_deux_est_refusee() {
        var leve = false
        try { AnalyseurSpectre(taille = 1000) } catch (e: IllegalArgumentException) { leve = true }
        assertTrue("taille 1000 acceptée", leve)
    }
}
