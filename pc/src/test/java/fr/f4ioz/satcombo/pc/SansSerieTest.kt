/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.plateforme.Lignes
import fr.f4ioz.satcombo.plateforme.PortSerie
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SansSerieTest {
    @After fun fin() { System.clearProperty("satme.sansSerie") }

    /** A trial instance never opens a port, whatever it is asked. */
    @Test fun aucun_port_ouvert() = runBlocking {
        System.setProperty("satme.sansSerie", "true")
        val o = PortsSeriePc().ouvre(PortSerie(index = 0, appareil = 0, port = 0, nbPorts = 1, libelle = "essai", cle = "x", vendorId = 0, produit = null), 19_200, Lignes.BASSES, false)
        assertNull(o.lien)
        assertTrue(o.erreur, o.erreur.contains("SATME_SANS_SERIE"))
    }
}
