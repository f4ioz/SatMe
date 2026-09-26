/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.i18n

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Garde-fou contre la maladie de la reconstruction : les clés en double.
 *
 * `mapOf` accepte sans broncher deux fois la même clé et garde la dernière.
 * Rien ne se voit donc à l'exécution — mais le fichier enfle, deux libellés
 * concurrents cohabitent, et la version qui gagne dépend de l'ordre des lignes.
 * Après l'incident du 2 août, `Strings.kt` portait soixante et onze clés en
 * double par table, dont une vingtaine avec des textes différents : l'ancienne
 * formulation de la 18.16 dormait sous la nouvelle. On ne peut pas repérer ça
 * depuis les tables compilées, puisqu'elles ont déjà tranché ; il faut relire
 * la source.
 */
class StringsSourceTest {

    private fun source(): File? =
        listOf("src/main/java", "app/src/main/java", "../app/src/main/java")
            .map { File(it, "fr/f4ioz/satcombo/i18n/Strings.kt") }
            .firstOrNull { it.isFile }

    /** Les clés de la table qui commence à `marque`, dans l'ordre du fichier. */
    private fun cles(lignes: List<String>, marque: String): List<String> {
        val debut = lignes.indexOfFirst { it.startsWith(marque) }
        if (debut < 0) return emptyList()
        val fin = lignes.drop(debut + 1).indexOfFirst { it.startsWith("val ") }
        val bloc = if (fin < 0) lignes.drop(debut + 1) else lignes.subList(debut + 1, debut + 1 + fin)
        val re = Regex("^\\s*\"([a-z0-9_]+)\" to ")
        return bloc.mapNotNull { re.find(it)?.groupValues?.get(1) }
    }

    private fun doublons(cles: List<String>): List<String> =
        cles.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.sorted()

    @Test
    fun aucune_cle_nest_declaree_deux_fois() {
        val f = source()
        assumeTrue("Strings.kt introuvable depuis " + File(".").absolutePath, f != null)
        val lignes = f!!.readLines()
        assertEquals("clés françaises en double", emptyList<String>(),
            doublons(cles(lignes, "val FR")))
        assertEquals("clés anglaises en double", emptyList<String>(),
            doublons(cles(lignes, "val EN")))
    }

    /**
     * Les deux tables sont lues séparément : une clé peut — et doit — exister
     * des deux côtés. Cet essai vérifie surtout que le découpage ci-dessus a
     * bien trouvé deux blocs non vides, sans quoi l'essai précédent passerait
     * en ne regardant rien du tout.
     */
    @Test
    fun les_deux_tables_sont_bien_reperees() {
        val f = source()
        assumeTrue("Strings.kt introuvable", f != null)
        val lignes = f!!.readLines()
        val fr = cles(lignes, "val FR")
        val en = cles(lignes, "val EN")
        assertEquals("la table FR de la source doit valoir la table compilée",
            FR.size, fr.size)
        assertEquals("la table EN de la source doit valoir la table compilée",
            EN.size, en.size)
    }
}
