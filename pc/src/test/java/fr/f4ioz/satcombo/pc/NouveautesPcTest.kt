/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** « Quoi de neuf »: the notes cut per version and per language, those since the last version seen. */
class NouveautesPcTest {

    private val NOTES = """
# Nouveautés

<!-- règles : ## Prochaine version renommée avant l'étiquette ; ## 2026.10.1 pas lue ici -->

## Prochaine version
### fr
- à venir
### en
- coming

## 2026.10.27
### fr
- vingt-sept
### en
- twenty-seven

## 2026.10.13.1
- correctif (les deux langues)

## 2026.10.9 (première)
### fr
- neuf

## Notes diverses
- pas une version
""".trimIndent()

    private val n = NouveautesPc(NOTES)

    @Test
    fun les_sections_sont_lues_par_version_et_par_langue() {
        assertEquals(listOf("Prochaine", "2026.10.27", "2026.10.13.1", "2026.10.9"), n.notes.map { it.version })
        val v27 = n.notes[1]
        assertEquals("- vingt-sept", v27.fr)
        assertEquals("- twenty-seven", v27.en)
        assertEquals("- correctif (les deux langues)", n.notes[2].texte(true))
        // English missing: the French shown rather than nothing.
        assertEquals("- neuf", n.notes[3].texte(true))
    }

    @Test
    fun depuis_la_derniere_version_vue_jusqua_celle_ci() {
        assertEquals(listOf("2026.10.27", "2026.10.13.1"), n.depuis("2026.10.9", "2026.10.27").map { it.version })
        assertEquals(listOf("2026.10.13.1"), n.depuis("2026.10.13", "2026.10.13.1").map { it.version })
        // 2026.10.27 after 2026.10.9: compared as numbers, not text.
        assertEquals(listOf("2026.10.27"), n.depuis("2026.10.13.1", "2026.10.27").map { it.version })
        assertTrue(n.depuis("2026.10.27", "2026.10.27").isEmpty())
        assertTrue("revenu à une version plus ancienne", n.depuis("2026.10.27", "2026.10.9").isEmpty())
        // A station older than the notes: this version's only.
        assertEquals(listOf("2026.10.27"), n.depuis(null, "2026.10.27").map { it.version })
        // A build by hand: never shown by itself.
        assertTrue(n.depuis("2026.10.9", "dev").isEmpty())
    }

    @Test
    fun la_prochaine_version_tient_lieu_d_une_version_sans_section() {
        val l = n.depuis("2026.10.27", "2026.10.28")
        assertEquals(listOf("2026.10.28"), l.map { it.version })
        assertEquals("- à venir", l[0].fr)
        assertEquals("2026.10.28", n.toutes("2026.10.28").first().version)
        assertEquals("Prochaine", n.toutes("dev").first().version)
        assertEquals("2026.10.27", n.toutes("2026.10.27").first().version)
    }

    @Test
    fun les_notes_sont_dans_le_jar() {
        val j = NouveautesPc.duJar()
        assertTrue("pc/NOUVEAUTES.md copié dans les ressources à la compilation", j.notes.isNotEmpty())
    }

    @Test
    fun le_fichier_du_depot_se_lit() {
        val f = listOf("NOUVEAUTES.md", "pc/NOUVEAUTES.md").map(::File).firstOrNull { it.isFile } ?: return
        val notes = NouveautesPc(f.readText()).notes
        assertTrue(notes.size >= 3)
        notes.forEach { assertTrue("${it.version} sans texte français", it.fr.isNotBlank()); assertTrue("${it.version} sans texte anglais", it.en.isNotBlank()) }
        // Newest first, each version once.
        val datees = notes.filter { VersionPc.publiee(it.version) }.map { it.version }
        assertEquals(datees.sortedWith { a, b -> VersionPc.compare(b, a)!! }, datees)
        assertEquals(datees.distinct(), datees)
    }
}
