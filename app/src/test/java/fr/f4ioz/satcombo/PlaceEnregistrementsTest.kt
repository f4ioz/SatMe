/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo


import fr.f4ioz.satcombo.domain.PlaceEnregistrements
import fr.f4ioz.satcombo.domain.PlaceEnregistrements.Fichier
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaceEnregistrementsTest {
    private val maintenant = 1_800_000_000_000L
    private val jour = 24 * 3_600_000L

    @Test
    fun seuls_les_vieux_enregistrements_inutiles_sont_proposes() {
        val sons = listOf(
            Fichier("vieux.mp3", 5_000_000, maintenant - 40 * jour), Fichier("vieux.info", 25, maintenant - 40 * jour),
            Fichier("vieux_du_journal.mp3", 3_000_000, maintenant - 40 * jour),
            Fichier("recent.mp3", 2_000_000, maintenant - 2 * jour),
            Fichier("en_cours.mp3", 1_000_000, maintenant - 40 * jour))
        val images = listOf(Fichier("a.png", 400_000, 0L), Fichier("a.meta", 200, 0L))
        val b = PlaceEnregistrements.bilan(sons, images, setOf("vieux_du_journal.mp3"), "en_cours.mp3", maintenant)
        assertEquals(4, b.enregistrements); assertEquals(11_000_025L, b.octetsEnregistrements)
        assertEquals(1, b.images); assertEquals(400_200L, b.octetsImages)
        assertEquals(listOf("vieux.mp3"), b.vieux)
        assertEquals(5_000_025L, b.octetsVieux)
        assertEquals("4.8 MB", PlaceEnregistrements.taille(5_000_025L).replace(',', '.'))
        assertEquals("1 kB", PlaceEnregistrements.taille(25))
    }
}
