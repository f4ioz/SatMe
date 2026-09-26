/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.cat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Port probing order.
 *
 * An IC-9700 alone exposes two serial ports behind one plug. The operator
 * picks one with no way of knowing which carries CI-V; if it is the wrong
 * one, its sibling must be tried next, before bothering other devices.
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
        // Choice = Icom port A. Port B is on the same device: it comes before
        // the SDR dongle, which will never answer CI-V.
        assertEquals(listOf(0, 1, 2), CatScan.ordre(ic9700EtCleSdr(), 0))
    }

    @Test
    fun un_choix_hors_bornes_ne_fait_pas_tomber_la_connexion() {
        // The index persists across sessions: it may point to a port that is
        // gone because a cable was unplugged meanwhile.
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
        // Many serial bridges declare no product name; the kernel path is ugly
        // but at least tells two plugs apart.
        assertEquals("/dev/bus/usb/001/004",
            CatScan.etiquette(null, "/dev/bus/usb/001/004", 0, 1))
        assertEquals("/dev/bus/usb/001/004",
            CatScan.etiquette("   ", "/dev/bus/usb/001/004", 0, 1))
    }
}
