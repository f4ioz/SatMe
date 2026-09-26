/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.data.Adif
import fr.f4ioz.satcombo.domain.Indicatifs
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * L'indicatif arrive-t-il entier au bout de la chaîne ?
 *
 * Olivier a eu deux indicatifs tronqués au carnet. La cause trouvée était dans
 * l'écran (champ vidé quand la file avançait), mais la chaîne elle-même
 * n'avait jamais été vérifiée. Elle l'est ici, maillon par maillon.
 */
class IndicatifIntegriteTest {

    private val cas = listOf(
        "F5RRO", "F5RRO/P", "M0NKC", "F/DL2GRC/P", "LA/DF2ET/P",
        "EA6/DF2ET", "OK1UFC", "F4IOZ/M", "9A/S51CD/P", "VK3YY")

    /** La séparation base/suffixe ne perd aucun caractère. */
    @Test
    fun separe_puis_recolle_rend_l_original() {
        for (c in cas) {
            val (b, suf) = Indicatifs.separe(c)
            assertEquals(c, b + suf)
        }
    }

    /** La clé de mémoire conserve l'indicatif complet. */
    @Test
    fun la_cle_conserve_tout() {
        for (c in cas) assertEquals(c, Indicatifs.cle(c))
    }

    /**
     * La frappe caractère par caractère, comme au clavier : l'insertion se
     * fait avant le suffixe, et le résultat doit être exact.
     */
    @Test
    fun la_frappe_caractere_par_caractere_reconstruit_l_indicatif() {
        for (c in cas) {
            var saisie = ""
            for (ch in c) {
                if (ch == '/') {
                    // Le clavier pose la barre en fin de chaîne.
                    saisie += "/"
                } else {
                    val (b, suf) = Indicatifs.separe(saisie)
                    saisie = b + ch + suf
                }
            }
            assertEquals(c, saisie)
        }
    }

    /** Le champ ADIF déclare la bonne longueur et le bon contenu. */
    @Test
    fun le_champ_adif_porte_l_indicatif_entier() {
        for (c in cas) {
            val f = Adif.field("CALL", c)
            assertEquals("<CALL:${c.toByteArray().size}>$c", f)
        }
    }
}
