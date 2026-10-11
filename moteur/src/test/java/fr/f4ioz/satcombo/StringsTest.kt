/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.i18n.EN
import fr.f4ioz.satcombo.i18n.FR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Translation tables. A key once defined twice with two meanings: `mapOf`
 * keeps the second **silently**, so a calibration step showed the wrong
 * message, raw `%s` included. Only reading the source spots duplicates.
 */
class StringsTest {

    private fun source(): String {
        // Read the source as written: the compiled table has already lost the
        // duplicates — that is the whole problem.
        val chemins = listOf(
            "src/main/java/fr/f4ioz/satcombo/i18n/Strings.kt",
            "app/src/main/java/fr/f4ioz/satcombo/i18n/Strings.kt"
        )
        val f = chemins.map { File(it) }.firstOrNull { it.exists() }
        assertTrue("Strings.kt introuvable depuis ${File(".").absolutePath}", f != null)
        // English has its own file (one table per file: the JVM's 64 KB method limit).
        return f!!.readText() + "\n" + File(f.parentFile, "StringsEn.kt").readText()
    }

    private val cle = Regex("""^\s+"([a-z0-9_]+)" to """, RegexOption.MULTILINE)

    @Test
    fun aucune_cle_nest_ecrite_deux_fois() {
        val toutes = cle.findAll(source()).map { it.groupValues[1] }.toList()
        // Two tables in the file, so each key appears at most twice: once in
        // French, once in English.
        val enTrop = toutes.groupingBy { it }.eachCount().filter { it.value > 2 }
        assertEquals("clés écrites plusieurs fois dans une même table : $enTrop",
            emptyMap<String, Int>(), enTrop)
    }

    @Test
    fun les_deux_tables_couvrent_les_memes_cles() {
        val manquantEn = FR.keys - EN.keys
        val manquantFr = EN.keys - FR.keys
        // English falls back to French when a key is missing, so nothing
        // breaks — but an English speaker sees French, and nobody notices
        // without this check.
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
        // A "%s" on one side only gives either a raw placeholder on screen or
        // a lost argument.
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
