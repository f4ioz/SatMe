/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A satellite named in a post found in the catalogue (the names seen in October 2026). */
class NomsSatellitesTest {

    private val catalogue = listOf("ISS (ZARYA)" to 25544, "UmKA 1 (RS40S)" to 57172, "AO-07" to 7530, "AO-129" to 66001,
        "SONATE-2" to 59112, "RS-44" to 44909, "METEOR-M2 3" to 57166)

    @Test fun `les cles`() {
        assertEquals("UMKA1", NomsSatellites.cle("UmKA 1"))
        assertEquals("AO7", NomsSatellites.cle("AO-07"))
        assertEquals("RS0ISS", NomsSatellites.cle("RSØISS"))
        assertEquals(setOf("UMKA1RS40S", "UMKA1", "RS40S"), NomsSatellites.parties("UmKA 1 (RS40S)"))
    }

    @Test fun `le nom d'un article trouve dans le catalogue`() {
        assertEquals(57172, NomsSatellites.identifie("UMKA-1", "UMKA-1 : Emission SSTV du 27 au 29 octobre 2026", catalogue))
        assertEquals(57172, NomsSatellites.identifie("RS40S", "", catalogue))
        assertEquals(7530, NomsSatellites.identifie("AO-7", "", catalogue))
        assertEquals(25544, NomsSatellites.identifie("ISS", "", catalogue))
        assertEquals(59112, NomsSatellites.identifie("Sonate 2", "", catalogue))
        // « ERMINAZ-2U devient AO-129 »: the OSCAR number said in the title, or the other name.
        assertEquals(66001, NomsSatellites.identifie("ERMINAZ-2", "ERMINAZ-2 : Deux nouveaux OSCAR : ERMINAZ-2U devient AO-129.", catalogue))
        assertEquals(66001, NomsSatellites.identifie("ERMINAZ-2U", "", catalogue))
        assertEquals(42, NomsSatellites.identifie("ERMINAZ-2", "", listOf("ERMINAZ-2U" to 42, "ERMINAZ-1" to 41)))
    }

    @Test fun `rien de sur, rien de pris`() {
        assertEquals(0, NomsSatellites.identifie("DEMOSAT-2", "SSTV d'un satellite fictif", catalogue))
        assertEquals(0, NomsSatellites.identifie("AO-12", "", catalogue))
        assertEquals(0, NomsSatellites.identifie("", "", catalogue))
        // Two satellites as likely: the owner picks.
        assertEquals(0, NomsSatellites.identifie("ERMINAZ-2", "", listOf("ERMINAZ-2U" to 42, "ERMINAZ-2A" to 43)))
    }

    @Test fun `les candidats, le plus probable d'abord`() {
        val l = NomsSatellites.candidats("UMKA-2", "", catalogue + ("UmKA 2" to 70000))
        assertEquals(70000, l.first().second)
        assertTrue(l.any { it.second == 57172 })
        assertTrue(NomsSatellites.candidats("DEMOSAT-2", "", catalogue).isEmpty())
    }
}
