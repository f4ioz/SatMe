/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.cat.CatJournal
import fr.f4ioz.satcombo.cat.CivController
import fr.f4ioz.satcombo.cat.Ft817Cat
import fr.f4ioz.satcombo.cat.Ft817Sim
import fr.f4ioz.satcombo.cat.Ic9700Sim
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Le journal des trames : ce qui manquait le plus.
 *
 * Quand un passage se passait mal, il n'y avait rien à regarder. Le poste ne
 * répondait pas comme prévu, l'application affichait un message poli, et l'on
 * en était réduit à deviner. Cinq essais pour que le journal soit fiable — car
 * un journal qui perd des lignes, ou qui en garde trop, est pire qu'aucun
 * journal : il fait chercher au mauvais endroit.
 */
class CatJournalTest {

    @Before
    fun avant() { CatJournal.clear(); CatJournal.enabled = false }

    @After
    fun apres() { CatJournal.enabled = false; CatJournal.clear() }

    @Test
    fun le_journal_est_muet_tant_qu_on_ne_le_demande_pas() {
        // Il tourne au cœur de la boucle Doppler, plusieurs fois par seconde,
        // pendant tout un passage. Éteint, il ne doit rien coûter et rien
        // retenir.
        CatJournal.log(true, byteArrayOf(0xFE.toByte(), 0xFE.toByte()), "quelque chose")
        assertTrue(CatJournal.entries.value.isEmpty())
        CatJournal.enabled = true
        CatJournal.log(true, byteArrayOf(0xFE.toByte(), 0xFE.toByte()), "quelque chose")
        assertEquals(1, CatJournal.entries.value.size)
    }

    @Test
    fun le_journal_ne_grossit_pas_indefiniment() {
        // Deux cents lignes, pas une de plus : c'est de quoi couvrir largement
        // le début d'un passage, et cela tient dans une mémoire de téléphone
        // sans qu'on ait à y penser.
        CatJournal.enabled = true
        repeat(CatJournal.DEPTH + 25) { i ->
            CatJournal.log(i % 2 == 0, byteArrayOf(i.toByte()), "trame $i")
        }
        val e = CatJournal.entries.value
        assertEquals(CatJournal.DEPTH, e.size)
        // Et ce sont les plus anciennes qui partent, pas les plus récentes.
        assertEquals("trame 25", e.first().text)
        assertEquals("trame ${CatJournal.DEPTH + 24}", e.last().text)
    }

    @Test
    fun l_ordre_d_arrivee_est_conserve_et_l_horodatage_avec() {
        // Un journal dans le désordre ne sert à rien : ce que l'on cherche,
        // c'est ce qui a précédé le refus.
        CatJournal.enabled = true
        CatJournal.log(true, byteArrayOf(0x01), "question", tMs = 1_000L)
        CatJournal.log(false, byteArrayOf(0x02), "réponse", tMs = 1_040L)
        CatJournal.log(true, byteArrayOf(0x03), "suite", tMs = 1_080L)
        val e = CatJournal.entries.value
        assertEquals(listOf("question", "réponse", "suite"), e.map { it.text })
        assertEquals(listOf(true, false, true), e.map { it.out })
        assertEquals(listOf(1_000L, 1_040L, 1_080L), e.map { it.tMs })
        assertEquals("01", e.first().hex)
    }

    @Test
    fun on_peut_vider_le_journal_sans_l_eteindre() {
        CatJournal.enabled = true
        CatJournal.log(true, byteArrayOf(0x01), "avant")
        assertEquals(1, CatJournal.entries.value.size)
        CatJournal.clear()
        assertTrue(CatJournal.entries.value.isEmpty())
        // Il reste ouvert : vider n'est pas éteindre.
        CatJournal.log(true, byteArrayOf(0x02), "après")
        assertEquals(1, CatJournal.entries.value.size)
    }

    @Test
    fun les_deux_dialectes_tiennent_dans_le_meme_journal() = runBlocking {
        // C'est le point : un opérateur qui a deux postes de marques
        // différentes lit une seule liste, en français, sans manuel ouvert à
        // côté. L'hexadécimal reste là-dessous pour qui veut compter les
        // octets.
        CatJournal.enabled = true
        val icom = Ic9700Sim()
        val civ = CivController()
        civ.pacingMs = 0L
        civ.attach(icom)
        civ.setSatelliteMode(true)

        val yaesu = Ft817Sim()
        val ft = Ft817Cat()
        ft.pacingMs = 0L
        ft.attach(yaesu)
        ft.setMode("FM")

        val e = CatJournal.entries.value
        assertTrue("le CI-V n'a rien laissé", e.any { it.text.contains("mode satellite activé") })
        assertTrue("l'accusé n'est pas journalisé", e.any { !it.out && it.text.contains("accusé") })
        assertTrue("le Yaesu n'a rien laissé", e.any { it.text.contains("mode ← FM") })
        assertTrue(e.any { it.hex.startsWith("FE FE A2 E0 16") })
        assertTrue(e.any { it.hex == "08 00 00 00 07" })
    }
}
