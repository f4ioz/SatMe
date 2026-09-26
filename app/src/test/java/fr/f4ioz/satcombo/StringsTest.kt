/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.i18n.EN
import fr.f4ioz.satcombo.i18n.FR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Le banc des traductions.
 *
 * **Le défaut qu'il empêche.** `bouss_nord_ok` avait été écrite deux fois dans
 * la table française, avec deux sens différents : « Nord relevé — pointe
 * maintenant à l'ouest » pour le calage à deux visées, et « Calé sur le %s »
 * pour celui de la flèche. Dans une `mapOf`, la seconde gagne **en silence**.
 * Le premier calage affichait donc le message du second, gabarit `%s` non
 * rempli compris — au moment précis où l'opérateur attend qu'on lui dise quoi
 * faire ensuite.
 *
 * Rien ne l'aurait signalé : ni le compilateur, ni l'exécution. Seule une
 * relecture du fichier source peut voir deux clés identiques, et c'est ce que
 * fait cet essai.
 */
class StringsTest {

    private fun source(): String {
        // Le fichier est lu tel qu'il est écrit : la table compilée, elle, a
        // déjà perdu les doublons — c'est tout le problème.
        val chemins = listOf(
            "src/main/java/fr/f4ioz/satcombo/i18n/Strings.kt",
            "app/src/main/java/fr/f4ioz/satcombo/i18n/Strings.kt"
        )
        val f = chemins.map { File(it) }.firstOrNull { it.exists() }
        assertTrue("Strings.kt introuvable depuis ${File(".").absolutePath}", f != null)
        return f!!.readText()
    }

    private val cle = Regex("""^\s+"([a-z0-9_]+)" to """, RegexOption.MULTILINE)

    @Test
    fun aucune_cle_nest_ecrite_deux_fois() {
        val toutes = cle.findAll(source()).map { it.groupValues[1] }.toList()
        // Deux tables dans le fichier, donc chaque clé doit apparaître au plus
        // deux fois : une en français, une en anglais.
        val enTrop = toutes.groupingBy { it }.eachCount().filter { it.value > 2 }
        assertEquals("clés écrites plusieurs fois dans une même table : $enTrop",
            emptyMap<String, Int>(), enTrop)
    }

    @Test
    fun les_deux_tables_couvrent_les_memes_cles() {
        val manquantEn = FR.keys - EN.keys
        val manquantFr = EN.keys - FR.keys
        // L'anglais retombe sur le français quand une clé manque, donc une
        // absence ne casse rien — mais elle laisse du français à un anglophone,
        // et personne ne s'en aperçoit sans ce compte.
        assertTrue("clés absentes de l'anglais : ${manquantEn.sorted().take(20)}",
            manquantEn.isEmpty())
        assertTrue("clés absentes du français : ${manquantFr.sorted().take(20)}",
            manquantFr.isEmpty())
    }

    @Test
    fun aucune_traduction_nest_vide() {
        val videsFr = FR.filterValues { it.isBlank() }.keys
        val videsEn = EN.filterValues { it.isBlank() }.keys
        assertTrue("traductions vides en français : $videsFr", videsFr.isEmpty())
        assertTrue("traductions vides en anglais : $videsEn", videsEn.isEmpty())
    }

    @Test
    fun les_gabarits_sont_les_memes_dans_les_deux_langues() {
        // Un « %s » présent d'un côté et absent de l'autre donne, selon le sens,
        // un gabarit affiché brut ou un argument perdu.
        val gabarit = Regex("""%[sdf]""")
        val ecarts = FR.keys.filter { k ->
            val a = gabarit.findAll(FR[k] ?: "").count()
            val b = gabarit.findAll(EN[k] ?: "").count()
            a != b
        }
        assertEquals("gabarits différents entre les deux langues : $ecarts",
            emptyList<String>(), ecarts)
    }
}
