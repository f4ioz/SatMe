/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class JournalDopplerTest {

    private val dossier = File(System.getProperty("java.io.tmpdir"), "satme-doppler-" + System.nanoTime())

    @After fun range() { dossier.deleteRecursively() }

    private val t0 = 1792089000000L

    /** A pass: elevation up to 40° at 60 s then down, below the horizon from 120 s. */
    private fun e(s: Int) = JournalDoppler.Echantillon(
        tMs = t0 + s * 1000L, azDeg = 180.0 + s, elDeg = 40.0 - kotlin.math.abs(s - 60) * (40.0 / 59.0),
        distanceKm = 800.0, vitesseKmS = (s - 60) / 10.0,
        descenteReposHz = 435_000_000L, monteeReposHz = 145_900_000L,
        descenteEcriteHz = 435_001_000L, monteeEcriteHz = null, descenteLueHz = 435_000_990L)

    @Test fun `un passage, ses phases, une ligne toutes les 5 s`() {
        val j = JournalDoppler(dossier)
        var ferme: File? = null
        for (s in 1..125) { j.tic(true, "AO-91", 43017, e(s))?.let { ferme = it } }
        assertNotNull(ferme)
        assertNull(j.enCours)
        val lignes = ferme!!.readLines()
        assertEquals(JournalDoppler.ENTETE, lignes[0])
        val phases = lignes.drop(1).map { it.split(",")[2] }
        assertEquals("AOS", phases.first())
        assertEquals("LOS", phases.last())
        assertEquals(1, phases.count { it == "TCA" })
        // Rising before the peak, falling after it.
        val i = phases.indexOf("TCA")
        assertTrue(phases.subList(1, i).all { it == "montée" })
        assertTrue(phases.subList(i + 1, phases.size - 1).all { it == "descente" })
        // The peak row is the highest one.
        val el = lignes.drop(1).map { it.split(",")[4].toDouble() }
        assertEquals(el.dropLast(1).max(), el[i], 1e-9)
        // Every five seconds, plus the LOS row.
        val t = lignes.drop(1).map { it.split(",")[1].toLong() }
        assertTrue(t.zipWithNext().dropLast(1).all { (a, b) -> b - a == 5_000L })
        assertTrue(ferme!!.name.startsWith("doppler-AO-91-20261015-"))
    }

    @Test fun `le doppler prevu, ce qui est ecrit et lu`() {
        val l = JournalDoppler.ligne(e(30), "montée").split(",")
        assertEquals(16, l.size)
        assertEquals(16, JournalDoppler.ENTETE.split(",").size)
        val rr = (30 - 60) / 10.0
        val dl = Doppler.downlink(435_000_000L, rr)
        assertEquals((dl - 435_000_000L).toString(), l[8])
        assertEquals(dl.toString(), l[9])
        assertTrue(l[8].toLong() > 0) // coming closer: heard higher
        assertTrue(Doppler.uplink(145_900_000L, rr) < 145_900_000L)
        assertEquals("435001000", l[13])
        assertEquals("", l[14])
        assertEquals("435000990", l[15])
    }

    @Test fun `arret, autre satellite, rien sous l'horizon`() {
        val j = JournalDoppler(dossier)
        assertNull(j.tic(true, "AO-91", 43017, e(0).copy(elDeg = -3.0)))
        assertNull(j.enCours)
        j.tic(true, "AO-91", 43017, e(10))
        assertNotNull(j.enCours)
        // Another satellite: the first log closes, a second opens.
        val f1 = j.tic(true, "SO-50", 27607, e(20).copy(tMs = t0 + 1_000_000L))
        assertNotNull(f1)
        assertNotNull(j.enCours)
        // The log switched off: closed, and nothing more.
        val f2 = j.tic(false, "SO-50", 27607, e(25).copy(tMs = t0 + 1_005_000L))
        assertNotNull(f2)
        assertNull(j.enCours)
        assertEquals(2, JournalDoppler.liste(dossier).size)
        assertTrue(f1!!.readLines().last().split(",")[2] != "LOS")
    }

    @Test fun `les journaux d'un passage, et rien hors du dossier`() {
        val j = JournalDoppler(dossier)
        for (s in 1..125) j.tic(true, "ISS (ZARYA)", 25544, e(s))
        val f = JournalDoppler.liste(dossier).single()
        assertEquals(t0 + 1000L, JournalDoppler.debut(f.name))
        assertEquals(listOf(f), JournalDoppler.duPassage(dossier, "ISS (ZARYA)", t0 + 60_000L, t0 + 600_000L))
        assertTrue(JournalDoppler.duPassage(dossier, "ISS", t0, t0 + 600_000L).isEmpty())
        assertTrue(JournalDoppler.duPassage(dossier, "ISS (ZARYA)", t0 + 3_600_000L, t0 + 4_000_000L).isEmpty())
        assertEquals(f, JournalDoppler.fichier(dossier, f.name))
        assertNull(JournalDoppler.fichier(dossier, "../" + f.name))
        assertNull(JournalDoppler.fichier(dossier, "autre.csv"))
    }

    @Test fun `repos de la montee d'un transpondeur inverse`() {
        val t = fr.f4ioz.satcombo.data.Transmitter(description = "lin", alive = true, type = "Transponder",
            uplinkLowHz = 145_900_000L, uplinkHighHz = 145_950_000L, downlinkLowHz = 435_800_000L, downlinkHighHz = 435_850_000L,
            mode = "USB", invert = true)
        assertEquals(145_950_000L, JournalDoppler.reposMontee(t, 435_800_000L))
        // FM: one channel each way, the input as it is.
        assertEquals(145_900_000L, JournalDoppler.reposMontee(t.copy(downlinkHighHz = null), 435_800_000L))
        assertNull(JournalDoppler.reposMontee(null, 435_800_000L))
    }
}
