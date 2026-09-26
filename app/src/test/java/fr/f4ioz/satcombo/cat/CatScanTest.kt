/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.cat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * L'ordre d'essai des ports, vérifié au banc.
 *
 * Le cas qui a motivé tout ceci : un IC-9700 branché seul expose deux ports
 * série derrière une seule prise. L'opérateur en désigne un — il n'a aucun
 * moyen de savoir lequel porte le CI-V —, et si ce n'est pas le bon il faut que
 * le voisin soit essayé aussitôt après, avant d'aller déranger les autres
 * appareils.
 */
class CatScanTest {

    private fun ic9700EtCleSdr() = listOf(
        PortRef(0, 0, "IC-9700 · port A"),
        PortRef(0, 1, "IC-9700 · port B"),
        PortRef(1, 0, "RTL2838"))

    @Test
    fun le_port_choisi_passe_en_premier() {
        assertEquals(listOf(1, 0, 2), CatScan.ordre(ic9700EtCleSdr(), 1))
    }

    @Test
    fun le_frere_du_meme_appareil_vient_avant_les_autres() {
        // Choix = port A de l'Icom. Le port B est du même appareil : il passe
        // avant la clé SDR, qui n'a aucune chance de répondre en CI-V.
        assertEquals(listOf(0, 1, 2), CatScan.ordre(ic9700EtCleSdr(), 0))
    }

    @Test
    fun un_choix_hors_bornes_ne_fait_pas_tomber_la_connexion() {
        // L'index est retenu d'une session à l'autre : il peut désigner un port
        // qui n'existe plus parce qu'un câble a été débranché entre-temps.
        assertEquals(listOf(2, 0, 1), CatScan.ordre(ic9700EtCleSdr(), 9))
        assertEquals(listOf(0, 1, 2), CatScan.ordre(ic9700EtCleSdr(), -3))
    }

    @Test
    fun sans_aucun_port_il_ny_a_rien_a_essayer() {
        assertEquals(emptyList<Int>(), CatScan.ordre(emptyList(), 0))
    }

    @Test
    fun tous_les_ports_sont_essayes_une_fois_et_une_seule() {
        val refs = ic9700EtCleSdr()
        for (choix in refs.indices) {
            val o = CatScan.ordre(refs, choix)
            assertEquals("aucun port oublié", refs.size, o.size)
            assertEquals("aucun port en double", refs.size, o.toSet().size)
        }
    }

    @Test
    fun letiquette_ne_numerote_que_les_appareils_a_plusieurs_ports() {
        assertEquals("IC-9700", CatScan.etiquette("IC-9700", "/dev/bus/usb/001/004", 0, 1))
        assertEquals("IC-9700 · port A", CatScan.etiquette("IC-9700", "/dev/x", 0, 2))
        assertEquals("IC-9700 · port B", CatScan.etiquette("IC-9700", "/dev/x", 1, 2))
    }

    @Test
    fun sans_nom_de_produit_on_montre_au_moins_le_chemin() {
        // Beaucoup de ponts série ne déclarent aucun nom de produit ; le chemin
        // du noyau est laid, mais il permet au moins de distinguer deux prises.
        assertEquals("/dev/bus/usb/001/004",
            CatScan.etiquette(null, "/dev/bus/usb/001/004", 0, 1))
        assertEquals("/dev/bus/usb/001/004",
            CatScan.etiquette("   ", "/dev/bus/usb/001/004", 0, 1))
    }
}
