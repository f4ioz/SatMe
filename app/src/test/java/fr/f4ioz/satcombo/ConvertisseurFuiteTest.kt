/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.Convertisseur
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La fuite du convertisseur QO-100 vers les satellites à défilement.
 *
 * Terrain d'Olivier : sur un LEO, avec le CAT branché, l'écran annonçait une
 * fréquence en gigahertz dès qu'on touchait au VFO. Le convertisseur de
 * descente de QO-100 s'appliquait à une chaîne qui n'était pas la sienne.
 *
 * La cause tient en une ligne : demander si le **résultat** de la conversion
 * tombe dans la bande ne prouve rien, puisque c'est toujours vrai. Un LNB
 * transforme précisément du 2 m en QO-100 — c'est son métier. La seule
 * question qui ait un sens est celle du satellite qu'on écoute.
 */
class ConvertisseurFuiteTest {

    private val lnb = Convertisseur(
        actif = true, olHz = 10_344_973_000L, inverseur = false,
        basHz = 10_400_000_000L, hautHz = 10_800_000_000L)

    /** Le piège, nommé pour qu'on ne le repose pas. */
    @Test
    fun le_resultat_dans_la_bande_ne_prouve_rien() {
        // Une clé sur 145,9 MHz : le résultat tombe pile dans QO-100.
        val faussementCouvert = 145_900_000L + lnb.olHz
        assertTrue("le piège lui-même", lnb.couvre(faussementCouvert))
        // Mais le satellite écouté, lui, est bien en 2 m.
        assertFalse("la bonne question", lnb.couvre(145_900_000L))
    }

    @Test
    fun un_leo_en_deux_metres_n_est_pas_couvert() {
        listOf(145_900_000L, 145_960_000L, 435_800_000L, 437_800_000L)
            .forEach { assertFalse("$it", lnb.couvre(it)) }
    }

    @Test
    fun le_transpondeur_de_qo100_est_couvert() {
        listOf(10_489_550_000L, 10_489_750_000L, 10_489_990_000L)
            .forEach { assertTrue("$it", lnb.couvre(it)) }
    }

    /**
     * Hors de sa bande, le convertisseur laisse passer la fréquence intacte.
     * C'est ce qui permet de le laisser coché en permanence sans qu'il gêne
     * l'ISS le mardi.
     */
    @Test
    fun hors_bande_la_frequence_ressort_intacte() {
        assertEquals(145_900_000L, lnb.versPoste(145_900_000L))
    }

    @Test
    fun dans_sa_bande_il_descend_vers_la_frequence_intermediaire() {
        assertEquals(144_777_000L, lnb.versPoste(10_489_750_000L))
    }

    /** Décoché, il est transparent quelle que soit la fréquence. */
    @Test
    fun decoche_il_ne_fait_rien() {
        val eteint = lnb.copy(actif = false)
        assertFalse(eteint.couvre(10_489_750_000L))
        assertEquals(10_489_750_000L, eteint.versPoste(10_489_750_000L))
    }
}

/**
 * Pourquoi la garde d'entrée ne peut pas sauver la chaîne de F4IOZ.
 *
 * `accepteEnEntree` avait été posée pour fermer la fuite du convertisseur sur
 * les satellites à défilement : une fréquence n'est une intermédiaire que si
 * elle tombe dans la fenêtre que ce convertisseur produit réellement. C'est
 * juste — et ici, parfaitement inopérant.
 *
 * Le calcul, qui tient en deux lignes : bornes Ku 10 400–10 800 MHz, oscillateur
 * mesuré 10 344,973 MHz, donc fenêtre intermédiaire **55 à 455 MHz**. Quatre
 * cents mégahertz de large, qui avalent le 2 m entier, le 70 cm entier, et de
 * quoi loger tous les satellites à défilement qui existent.
 *
 * Ce banc n'attend donc pas que la garde marche : il **fige la preuve qu'elle
 * ne peut pas**, pour qu'on ne perde pas une quatrième fois une soirée à
 * resserrer une règle de fréquence. Le seul discriminant est le satellite
 * sélectionné.
 */
class ConvertisseurFenetreTest {

    private val chaineF4ioz = fr.f4ioz.satcombo.domain.Convertisseur(
        actif = true, olHz = 10_344_973_000L, inverseur = false,
        basHz = 10_400_000_000L, hautHz = 10_800_000_000L)

    @Test
    fun la_fenetre_intermediaire_avale_le_deux_metres() {
        // La descente d'un LEO — AO-91, SO-50, l'ISS — est ici dedans.
        assertTrue(chaineF4ioz.accepteEnEntree(145_950_000L))
        assertTrue(chaineF4ioz.accepteEnEntree(145_800_000L))
        // Et le 70 cm avec, tant qu'on y est.
        assertTrue(chaineF4ioz.accepteEnEntree(435_500_000L))
    }

    @Test
    fun et_la_remontee_donne_donc_bien_des_gigahertz() {
        val sat = chaineF4ioz.versSatellite(145_950_000L)
        assertTrue("attendu des gigahertz, obtenu $sat", sat > 10_000_000_000L)
    }

    @Test
    fun aucune_borne_ne_referme_cela_sans_casser_qo100() {
        // Resserrer les bornes Ku pour exclure le 2 m reviendrait à exiger une
        // fenêtre intermédiaire au-dessus de 146 MHz, donc une borne basse Ku
        // au-dessus de 10 490,973 — soit au-dessus de la balise médiane
        // elle-même. On perdrait QO-100 pour sauver les LEO.
        val basNecessaire = 10_344_973_000L + 146_000_000L
        assertTrue(basNecessaire > fr.f4ioz.satcombo.domain.Convertisseur.BALISE_MEDIANE_HZ)
    }
}
