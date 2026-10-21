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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class AfficheSstvTest {

    private val paris = TimeZone.getTimeZone("Europe/Paris")
    private fun date(s: String, tz: TimeZone = paris) = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = tz }.parse(s)!!.time
    private val maintenant = date("2026-10-21 10:00")

    /** What tesseract 5.3 read on the ARISS Series 34 flyer. */
    private val ARISS = """
        ARISS

        SSTV

        SERIES 34

        SSTV Series 34

        We're back with a second
        October event!

        This event will feature the
        theme: ARISS Hardware

        Start: Thursday, October 22
        Start time to be determined

        Frequency: 437.550 MHz

        +/- Doppler Shift
        SSTV Mode: Robot 36
        Schedule: 36 sec on / 2 min off.

        End: Sunday, October 25
        End time to be determined

        Submit SSTV images at:
        https://ariss-usa.org/ARISS_SSTV/
    """.trimIndent()

    @Test fun `l'affiche ARISS donne tout le formulaire`() {
        val p = AfficheSstv.lis(ARISS, maintenant, paris)
        assertEquals(437_550_000L, p.freqHz)
        assertEquals("Robot 36", p.modeSstv)
        assertEquals("ISS", p.satNom); assertEquals(25544, p.catnum)
        assertEquals(date("2026-10-22 00:00"), p.debutMs)
        assertEquals(date("2026-10-25 23:59"), p.finMs)
        assertTrue(p.aConfirmer)
        assertEquals("36 s on / 2 min off", p.horaire)
        assertEquals("https://ariss-usa.org/ARISS_SSTV/", p.lien)
        assertEquals("ARISS SSTV Series 34", p.nom)
    }

    @Test fun `l'annee sans etre ecrite est celle qui vient, le jour de la semaine d'accord`() {
        // Seen in December: the October to come is next year's.
        val p = AfficheSstv.lis("Start: October 22\nEnd: October 25\n145.800 MHz PD120", date("2026-12-20 10:00"), paris)
        assertEquals(date("2027-10-22 00:00"), p.debutMs)
        assertEquals("PD 120", p.modeSstv)
        assertEquals(145_800_000L, p.freqHz)
        // « Thursday, October 22 » seen in 2025: 2026 is when the 22nd is a Thursday.
        val q = AfficheSstv.lis("Start: Thursday, October 22", date("2025-09-01 10:00"), paris)
        assertEquals(date("2026-10-22 00:00"), q.debutMs)
    }

    @Test fun `une affiche en francais, avec ses heures en UTC`() {
        val t = "Événement SSTV de l'ISS\nDébut : jeudi 22 octobre 2026 à 09:20 UTC\nFin : dimanche 25 octobre 2026 à 18:45 UTC\nFréquence 145,800 MHz — mode PD 120"
        val p = AfficheSstv.lis(t, maintenant, paris)
        val utc = TimeZone.getTimeZone("UTC")
        assertEquals(date("2026-10-22 09:20", utc), p.debutMs)
        assertEquals(date("2026-10-25 18:45", utc), p.finMs)
        assertFalse(p.aConfirmer)
        assertEquals(145_800_000L, p.freqHz); assertEquals("PD 120", p.modeSstv); assertEquals(25544, p.catnum)
    }

    @Test fun `une plage d'un seul tenant, un satellite connu par son nom`() {
        val p = AfficheSstv.lis("SSTV event du 3 au 5 novembre, 435.400 MHz, Martin 1", maintenant, paris, listOf("UMKA-1" to 57172, "SO-50" to 27607))
        assertEquals(date("2026-11-03 00:00"), p.debutMs)
        assertEquals(date("2026-11-05 23:59"), p.finMs)
        assertEquals("Martin M1", p.modeSstv)
        assertEquals(0, p.catnum)
        val q = AfficheSstv.lis("UMKA-1 SSTV, Nov 3-5, 437.625 MHz", maintenant, paris, listOf("UMKA-1" to 57172))
        assertEquals(57172, q.catnum); assertEquals(date("2026-11-03 00:00"), q.debutMs)
    }

    @Test fun `un texte sans rien d'utile ne propose rien`() {
        val p = AfficheSstv.lis("Bonjour à tous", maintenant, paris)
        assertEquals(0L, p.freqHz); assertEquals(0L, p.debutMs); assertEquals(CampagneSstv.AUTO, p.modeSstv); assertEquals("", p.lien)
    }
}
