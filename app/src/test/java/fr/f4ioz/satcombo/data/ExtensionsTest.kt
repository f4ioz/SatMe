/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le trousseau des fonctions optionnelles.
 *
 * Depuis que la SSTV, la clé SDR et les images NOAA sont ouvertes à tous, ces
 * tests couvrent deux choses : que ce qui est ouvert le reste quoi qu'on tape
 * dans le champ « Extensions » — un champ vide, un mot inconnu, une apostrophe
 * ne doivent jamais refermer une porte —, et que le mécanisme de clé lui-même
 * fonctionne toujours, puisque la prochaine fonction non éprouvée s'en servira.
 */
class ExtensionsTest {

    @Test
    fun ce_qui_est_ouvert_lest_sans_rien_taper() {
        assertEquals(Extensions.OPEN, Extensions.unlocked("", ""))
        assertEquals(Extensions.OPEN, Extensions.unlocked("F1ABC", ""))
    }

    @Test
    fun lindicatif_de_lauteur_nouvre_plus_rien_de_particulier() {
        // L'auteur voyait autrefois tout sans rien taper : il etait donc le
        // seul a ne jamais voir l'application telle que les autres la voient,
        // ce qui est la meilleure facon de laisser passer un defaut. Son
        // indicatif est desormais un indicatif comme un autre.
        assertEquals(Extensions.OPEN, Extensions.unlocked("F4IOZ", ""))
        assertEquals(Extensions.OPEN, Extensions.unlocked("f4ioz", ""))
        assertEquals(Extensions.OPEN, Extensions.unlocked("F4IOZ/P", ""))
        assertEquals(Extensions.ALL.toSet(), Extensions.unlocked("F4IOZ", "tout"))
    }

    /**
     * Depuis la publication, **tout est ouvert sans rien taper**.
     *
     * Cet essai disait auparavant l'inverse : que les drapeaux bretons
     * restaient fermés tant qu'on n'avait pas tapé le mot. Il a mordu au moment
     * du changement de règle, et c'est exactement ce qu'on attend de lui — une
     * politique d'ouverture ne doit pas pouvoir changer par distraction.
     *
     * Ce qui protège désormais l'opérateur d'une fonction non éprouvée, ce n'est
     * plus une clé mais le bandeau d'avertissement en tête de l'écran concerné :
     * il se lit au moment de s'en servir, ce qu'une clé non documentée ne fait
     * pas.
     */
    @Test
    fun tout_est_ouvert_sans_rien_taper_sauf_la_base_adif() {
        // Tout le trousseau est ouvert, à une exception : la base d'indicatifs
        // embarquée est le carnet personnel d'Olivier — noms et carrés de ses
        // correspondants — et l'application est publique. Elle ne se sert
        // qu'à qui écrit « adif » dans le champ Extensions.
        val rien = Extensions.unlocked("F1ABC", "")
        (Extensions.ALL - Extensions.APT).forEach {
            assertTrue("« $it » doit être ouvert", it in rien)
        }
        // Les images NOAA sont la seule fonction fermée, et « noaa » l'ouvre.
        assertTrue(Extensions.APT !in rien)
        assertTrue(Extensions.APT in Extensions.unlocked("F1ABC", "noaa"))
        assertTrue(Extensions.APT in Extensions.unlocked("F1ABC", "NOAA"))
        // L'ancien nom du format n'ouvre rien : le mot-clé est celui que
        // cherche l'opérateur, pas celui de l'ingénieur.
        assertTrue(Extensions.APT !in Extensions.unlocked("F1ABC", "apt"))
    }

    /**
     * Le mécanisme de clé reste en état de marche : la prochaine fonction non
     * éprouvée pourra s'en servir sans avoir à le réécrire.
     */
    @Test
    fun le_mecanisme_de_cle_fonctionne_toujours() {
        assertEquals(Extensions.ALL.toSet(), Extensions.unlocked("F1ABC", "tout"))
        assertEquals(Extensions.ALL.toSet(), Extensions.unlocked("F1ABC", "bêta"))
        assertTrue(Extensions.BZH in Extensions.unlocked("F1ABC", "drapeau bzh"))
    }

    @Test
    fun un_indicatif_voisin_garde_au_moins_ce_qui_est_ouvert() {
        for (call in listOf("F4IOX", "F4I", "F4IO")) {
            assertTrue("indicatif « $call »",
                Extensions.unlocked(call, "").containsAll(Extensions.OPEN))
        }
    }

    @Test
    fun un_mot_cle_najoute_rien_a_ce_qui_est_deja_ouvert() {
        // Le mot reste accepté — il ne doit simplement plus rien changer tant
        // que la fonction qu'il désigne est ouverte à tous.
        assertEquals(Extensions.OPEN, Extensions.unlocked("F1ABC", "sstv"))
        assertEquals(Extensions.OPEN, Extensions.unlocked("F1ABC", "sdr"))
    }

    @Test
    fun la_ponctuation_ne_ferme_pas_la_porte() {
        for (code in listOf("sstv sdr", "sstv,sdr", "sstv, sdr", "sstv;sdr",
                            "sstv+sdr", "sstv/sdr", "  SSTV   SDR  ", "SsTv,SdR")) {
            assertTrue("code « $code »",
                Extensions.unlocked("F1ABC", code).containsAll(Extensions.OPEN))
        }
    }


    @Test
    fun un_mot_inconnu_est_ignore_sans_rien_casser() {
        assertEquals(Extensions.OPEN, Extensions.unlocked("F1ABC", "cat wefax"))
        assertEquals(Extensions.OPEN, Extensions.unlocked("F1ABC", "n'importe quoi"))
    }

    @Test
    fun le_raccourci_de_lecture_dit_la_meme_chose() {
        assertTrue(Extensions.isUnlocked(Extensions.SDR, "F4IOZ", ""))
        assertTrue(Extensions.isUnlocked(Extensions.FLAG, "F1ABC", "drapeau"))
        assertTrue(Extensions.isUnlocked(Extensions.SSTV, "F1ABC", ""))
        // Les images NOAA sont fermées depuis la 20.47 : le raccourci doit le dire
        // aussi, et s'ouvrir avec le mot-clé.
        assertFalse(Extensions.isUnlocked(Extensions.APT, "", ""))
        assertTrue(Extensions.isUnlocked(Extensions.APT, "F1ABC", "noaa"))
    }

    @Test
    fun la_liste_des_extensions_est_sans_doublon_et_en_minuscules() {
        assertEquals(Extensions.ALL.size, Extensions.ALL.toSet().size)
        Extensions.ALL.forEach { assertEquals(it, it.lowercase()) }
    }

    @Test
    fun tout_ce_qui_est_ouvert_est_une_extension_connue() {
        assertTrue(Extensions.ALL.containsAll(Extensions.OPEN))
    }
    /**
     * Choix assumé : un mot maître ouvre tout, images NOAA comprises.
     *
     * Cet essai visait la base ADIF, retirée à la 20.42. Il garde la même
     * intention sur la seule fonction encore fermée.
     */
    @Test
    fun le_mot_maitre_ouvre_aussi_les_images_noaa() {
        assertTrue(Extensions.APT in Extensions.unlocked("F1ABC", "beta"))
    }
}