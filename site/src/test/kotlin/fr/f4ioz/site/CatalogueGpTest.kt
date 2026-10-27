/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site

import fr.f4ioz.site.sat.CatalogueSite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The orbital elements come from the SatMe GP server only; a refusal pauses it; what is on disk stays. Nothing leaves the test. */
class CatalogueGpTest {

    private val iss = "ISS (ZARYA)\n1 25544U 98067A   24001.50000000  .00016717  00000-0  30777-3 0  9990\n2 25544  51.6416 247.4627 0006703 130.5360 325.0288 15.49512407430303\n"

    private fun dossier(): File = Files.createTempDirectory("gp").toFile()

    @Test fun `only the GP server is asked, one address per group`() {
        val c = CatalogueSite(dossier(), "https://gp.f4ioz.fr", false, groupes = listOf("amsat", "amateur", "satnogs", "inconnu"))
        val a = c.adresses().map { it.second }
        assertEquals(listOf("https://gp.f4ioz.fr/gp/amsat.json", "https://gp.f4ioz.fr/gp/amateur.json", "https://gp.f4ioz.fr/gp/satnogs.json"), a)
        assertTrue(a.none { it.contains("celestrak", true) || it.contains("amsat.org") || it.contains("satnogs.org") })
        val img = CatalogueSite(dossier(), "gp.exemple.org/", false, groupes = listOf("images")).adresses().map { it.second }
        assertTrue(img.isNotEmpty()); assertTrue(img.all { it.startsWith("https://gp.exemple.org/gp/catnr/") })
        assertEquals("gp.f4ioz.fr", c.serveurNom)
    }

    @Test fun `the elements fetched are kept on disk and read at the next start`() {
        val d = dossier()
        val demandes = ArrayList<String>()
        val c = CatalogueSite(d, "https://gp.f4ioz.fr", false, groupes = listOf("amsat")) { u -> demandes += u; 200 to iss }
        c.charge()
        assertEquals("1 satellites", c.rafraichis())
        assertEquals(1, c.satellites.size)
        assertTrue(demandes.all { it == "https://gp.f4ioz.fr/gp/amsat.json" })
        val relu = CatalogueSite(d, "https://gp.f4ioz.fr", true, groupes = listOf("amsat"))
        relu.charge()
        assertEquals(25544, relu.satellites.single().catalogNumber)
        assertEquals("hors ligne : éléments du disque", relu.rafraichis())
    }

    @Test fun `a 403 or a 429 pauses the server, doubling, and the elements on disk stay`() {
        val d = dossier()
        var t = 1_000_000_000L
        var code = 200
        val demandes = ArrayList<String>()
        val c = CatalogueSite(d, "https://gp.f4ioz.fr", false, { t }, listOf("amsat", "amateur")) { u -> demandes += u; code to (if (code == 200) iss else "{\"erreur\":\"adresse bannie\"}") }
        c.charge(); c.rafraichis()
        assertEquals(1, c.satellites.size)
        code = 403; demandes.clear()
        t += 7 * 3_600_000L
        assertTrue(c.rafraichis().contains("403"))
        assertEquals(1, demandes.size)              // the first refusal stops the round
        assertEquals(t + CatalogueSite.PAUSE_MIN_MS, c.pauseJusqua)
        assertEquals(1, c.satellites.size)          // kept
        t += 30 * 60_000L
        assertTrue(c.rafraichis().startsWith("serveur GP en pause"))
        assertEquals(1, demandes.size)              // not asked during the pause
        // A restart remembers the pause.
        val apres = CatalogueSite(d, "https://gp.f4ioz.fr", false, { t }, listOf("amsat")) { u -> demandes += u; 200 to iss }
        apres.charge()
        assertTrue(apres.rafraichis().startsWith("serveur GP en pause"))
        assertEquals(1, demandes.size)
        code = 429
        t += 31 * 60_000L
        c.rafraichis()
        assertEquals(t + 2 * CatalogueSite.PAUSE_MIN_MS, c.pauseJusqua)
        code = 200
        t += 2 * CatalogueSite.PAUSE_MIN_MS + 1
        assertFalse(c.rafraichis().contains("refusé"))
        assertEquals(0L, c.pauseJusqua)
    }

    @Test fun `no server, nothing asked`() {
        val demandes = ArrayList<String>()
        val c = CatalogueSite(dossier(), "", false) { u -> demandes += u; 200 to "" }
        assertEquals("pas de serveur GP", c.rafraichis())
        assertTrue(demandes.isEmpty())
    }
}
