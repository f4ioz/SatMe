/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.i18n

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Guard against duplicate keys, the typical result of a file rebuild.
 *
 * `mapOf` silently accepts a repeated key and keeps the last one. Nothing
 * shows at runtime, but two competing labels coexist and the winner depends
 * on line order. After one rebuild `Strings.kt` had 71 duplicate keys per
 * table, about twenty with different texts. The compiled tables have already
 * picked a winner, so only the source can reveal this.
 */
class StringsSourceTest {

    private fun source(): File? =
        listOf("src/main/java", "app/src/main/java", "../app/src/main/java")
            .map { File(it, "fr/f4ioz/satcombo/i18n/Strings.kt") }
            .firstOrNull { it.isFile }

    /** Keys of the table starting at `marque`, in file order. */
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
        val lignes = f!!.readLines() + File(f.parentFile, "StringsEn.kt").readLines()
        assertEquals("clés françaises en double", emptyList<String>(),
            doublons(cles(lignes, "val FR")))
        assertEquals("clés anglaises en double", emptyList<String>(),
            doublons(cles(lignes, "val EN")))
    }

    /**
     * The two tables are read separately (a key may, and should, exist in
     * both). This mainly checks that the split above found two non-empty
     * blocks; otherwise the previous test would pass while checking nothing.
     */
    @Test
    fun les_deux_tables_sont_bien_reperees() {
        val f = source()
        assumeTrue("Strings.kt introuvable", f != null)
        val lignes = f!!.readLines() + File(f.parentFile, "StringsEn.kt").readLines()
        val fr = cles(lignes, "val FR")
        val en = cles(lignes, "val EN")
        assertEquals("la table FR de la source doit valoir la table compilée",
            FR.size, fr.size)
        assertEquals("la table EN de la source doit valoir la table compilée",
            EN.size, en.size)
    }
}
