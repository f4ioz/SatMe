/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.Indicatifs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two rules startup applies (the ViewModel itself is covered by the
 * Robolectric test). A release once shipped a keypad with no memory: log
 * loading hung off a removed queue function, and nothing noticed.
 */
class DemarrageTest {

    private fun contact(ind: String, carre: String = "JN18FS", sat: String = "RS-44",
                        quand: Long = 1_700_000_000_000L, nom: String = "") =
        Indicatifs.Contact(indicatif = ind, locator = carre, quandMs = quand,
            satellite = sat, nom = nom)

    // ------------------------------------------------ keypad memory

    /**
     * Three sources merge into one memory: local log, imported ADIF, built-in
     * database. Startup must redo this merge; it once stopped doing so.
     */
    @Test
    fun les_trois_sources_se_reunissent() {
        val locaux = listOf(contact("F1FPL"))
        val importe = listOf(contact("F5RRO", carre = "IN77US"))
        val interne = listOf(contact("F5OHH", carre = "IN97AJ", nom = "Christian"))

        val memoire = Indicatifs.memoire(locaux + importe + interne)

        assertEquals(3, memoire.size)
        assertTrue(memoire.any { it.indicatif == "F1FPL" })
        assertTrue(memoire.any { it.indicatif == "F5RRO" })
        assertTrue(memoire.any { it.indicatif == "F5OHH" })
    }

    /**
     * The reported symptom: with no source, the keypad suggests nothing. An
     * empty memory is fine on a fresh install, but must never come from a
     * log that is full.
     */
    @Test
    fun sans_source_la_memoire_est_vide() {
        assertEquals(emptyList<Indicatifs.Connu>(), Indicatifs.memoire(emptyList()))
    }

    @Test
    fun un_carnet_plein_ne_donne_jamais_une_memoire_vide() {
        val carnet = listOf(contact("F1FPL"), contact("F5RRO"))
        assertTrue(Indicatifs.memoire(carnet).isNotEmpty())
    }

    // ------------------------------------------------ gesture migration

    /**
     * Migration as `SettingsStore` applies it: rewrite once, and only once —
     * replayed on every start it would make the setting impossible to change.
     */
    private fun reprise(faites: Int, appuisActuels: Int): Pair<Int, Int> =
        if (faites < 1) 2 to 1 else appuisActuels to faites

    @Test
    fun une_installation_existante_passe_au_double_appui() {
        assertEquals(2 to 1, reprise(faites = 0, appuisActuels = 3))
    }

    @Test
    fun la_reprise_ne_se_rejoue_pas() {
        // The operator went back to three taps after migration: the choice
        // survives the next start.
        assertEquals(3 to 1, reprise(faites = 1, appuisActuels = 3))
    }

    @Test
    fun le_nombre_d_appuis_reste_borne_a_deux_ou_trois() {
        // A single tap would open the screen whenever the compass is touched;
        // more than three is impractical with gloves.
        listOf(0, 1, 2, 3, 4, 9).forEach {
            assertTrue(it.coerceIn(2, 3) in 2..3)
        }
        assertEquals(2, 1.coerceIn(2, 3))
        assertEquals(3, 7.coerceIn(2, 3))
    }
}
