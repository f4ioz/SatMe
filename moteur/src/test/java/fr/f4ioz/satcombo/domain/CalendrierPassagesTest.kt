/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import fr.f4ioz.satcombo.data.Observer
import fr.f4ioz.satcombo.data.SatPass
import fr.f4ioz.satcombo.data.TleEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendrierPassagesTest {

    private fun passe(aos: Long, los: Long, nom: String = "ISS", n: Int = 25544) = SatPass(
        satName = nom, catalogNumber = n, aosEpochMs = aos, losEpochMs = los, maxElevationDeg = 45.4,
        aosAzimuthDeg = 218.8, losAzimuthDeg = 71.2, sunlit = false, nightAtObserver = false)

    // 2026-10-15 18:30:00 UTC
    private val t0 = 1792089000000L

    @Test fun `structure et heures UTC`() {
        val s = CalendrierPassages.ics(listOf(passe(t0, t0 + 600_000L)), t0 - 3_600_000L)
        assertTrue(s.startsWith("BEGIN:VCALENDAR\r\nVERSION:2.0\r\n"))
        assertTrue(s.endsWith("END:VEVENT\r\nEND:VCALENDAR\r\n"))
        assertTrue(s.contains("\r\nDTSTART:20261015T183000Z\r\n"))
        assertTrue(s.contains("\r\nDTEND:20261015T184000Z\r\n"))
        assertTrue(s.contains("\r\nDTSTAMP:20261015T173000Z\r\n"))
        assertTrue(s.contains("SUMMARY:ISS · 45°"))
        assertTrue(s.contains("219°") && s.contains("71°"))
        assertEquals(1, Regex("BEGIN:VEVENT").findAll(s).count())
        // Every line ends in CRLF, none alone.
        assertFalse(s.replace("\r\n", "").contains('\n'))
        // Every line, folded, at most 75 octets.
        s.split("\r\n").forEach { assertTrue(it, it.toByteArray(Charsets.UTF_8).size <= 75) }
    }

    @Test fun `uid stable d'un calcul a l'autre`() {
        val iss = TleEntry("ISS (ZARYA)",
            "1 25544U 98067A   24001.50000000  .00016717  00000-0  30777-3 0  9990",
            "2 25544  51.6416 247.4627 0006703 130.5360 325.0288 15.49512407430303")
        val obs = Observer(48.80, 2.48, 50.0)
        val t = 1704110400000L
        val p = PassPredictor()
        val a = p.upcomingPasses(iss, obs, t, 24, 0.0)
        // Computed again later, from the middle of the first pass: same UIDs for the same passes.
        val b = p.upcomingPasses(iss, obs, (a[0].aosEpochMs + a[0].losEpochMs) / 2, 20, 0.0)
        assertEquals(CalendrierPassages.uid(a[0]), CalendrierPassages.uid(b[0]))
        assertEquals(a.drop(1).take(3).map { CalendrierPassages.uid(it) }, b.drop(1).take(3).map { CalendrierPassages.uid(it) })
        // Two passes, two UIDs.
        assertEquals(a.size, a.map { CalendrierPassages.uid(it) }.toSet().size)
        // The horizon mask does not change them.
        val m = p.upcomingPasses(iss, obs, t, 24, 20.0, MasqueHorizon(List(8) { 10.0 }))
        assertTrue(m.isNotEmpty())
        assertTrue(m.all { x -> a.any { CalendrierPassages.uid(it) == CalendrierPassages.uid(x) } })
    }

    @Test fun `echappement et pliage`() {
        assertEquals("a\\,b\\;c\\\\d\\ne", CalendrierPassages.texte("a,b;c\\d\ne"))
        val long = "DESCRIPTION:" + "é".repeat(80)
        val plie = CalendrierPassages.plie(long)
        val lignes = plie.split("\r\n")
        assertTrue(lignes.size > 1)
        lignes.forEach { assertTrue(it.toByteArray(Charsets.UTF_8).size <= 75) }
        lignes.drop(1).forEach { assertTrue(it.startsWith(" ")) }
        // Unfolded, it is the line again.
        assertEquals(long, plie.replace("\r\n ", ""))
        assertEquals("COURT", CalendrierPassages.plie("COURT"))
    }

    @Test fun `aucun passage, un calendrier vide mais valide`() {
        val s = CalendrierPassages.ics(emptyList(), t0)
        assertEquals("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//F4IOZ//SatMe//FR\r\nCALSCALE:GREGORIAN\r\nMETHOD:PUBLISH\r\nEND:VCALENDAR\r\n", s)
    }
}
