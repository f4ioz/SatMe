/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.Qo100
import fr.f4ioz.satcombo.domain.SoleilQo100
import fr.f4ioz.satcombo.domain.SunCalc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date

/**
 * Aligning the dish using the Sun. These tests pin dates (e.g. 2 March 2026
 * 10:18 UTC from Paris, 0.02° separation): checkable predictions that must
 * move if the solar model is touched, before someone climbs onto a roof.
 * Transit windows must bracket the equinoxes; June or December would be wrong
 * however precise.
 */
class SoleilQo100Test {

    private val parisLat = 48.8566
    private val parisLon = 2.3522
    private val nyLat = 40.7128
    private val nyLon = -74.0060

    private fun ms(iso: String): Long = Instant.parse(iso).toEpochMilli()

    private fun jourTu(t: Long): String =
        Instant.ofEpochMilli(t).atZone(ZoneOffset.UTC).toLocalDate().toString()

    private fun minuteTu(t: Long): String =
        Instant.ofEpochMilli(t).atZone(ZoneOffset.UTC).toLocalTime()
            .withSecond(0).withNano(0).toString()

    // --- Angular separation ------------------------------------------------

    /**
     * The case that breaks naive implementations: two identical directions.
     * The dot product rounds to 1.000000000000002, and an unclamped acos
     * returns NaN — precisely on the value that matters, a perfect transit.
     */
    @Test
    fun deux_directions_identiques_donnent_un_ecart_nul_et_pas_un_nan() {
        val e = SoleilQo100.ecartDeg(149.942, 29.533, 149.942, 29.533)
        assertFalse("l'écart est NaN", e.isNaN())
        // 1e-4° tolerance: acos loses digits near zero (flat near 1, so dot
        // product rounding is hugely amplified). Negligible against a 0.5°
        // solar disk; it would need revisiting for astrometry.
        assertEquals(0.0, e, 1e-4)
    }

    @Test
    fun l_ecart_angulaire_suit_la_geometrie() {
        // Two horizon points 90° apart in azimuth.
        assertEquals(90.0, SoleilQo100.ecartDeg(0.0, 0.0, 90.0, 0.0), 1e-9)
        // Zenith is 90° from any horizon point.
        assertEquals(90.0, SoleilQo100.ecartDeg(0.0, 90.0, 217.0, 0.0), 1e-9)
        // Two opposite horizon points.
        assertEquals(180.0, SoleilQo100.ecartDeg(0.0, 0.0, 180.0, 0.0), 1e-9)
        // Azimuth matters less higher up: at 80° elevation, 10° of azimuth is
        // under 2° of separation.
        assertTrue(SoleilQo100.ecartDeg(100.0, 80.0, 110.0, 80.0) < 2.0)
    }

    // --- The Sun, azimuth included ---------------------------------------

    /**
     * Adding azimuth to the solar model must not change elevation:
     * [SunCalc.elevationDeg] is used by pass prediction to decide whether a
     * satellite is sunlit, and a shift would go unnoticed for a long time.
     */
    @Test
    fun l_elevation_du_soleil_est_inchangee_par_l_ajout_de_l_azimut() {
        val t = Date(ms("2026-07-21T10:00:00Z"))
        listOf(48.8566 to 2.3522, -33.92 to 18.42, 0.0 to 0.0, 70.0 to -50.0).forEach {
            val (la, lo) = it
            assertEquals(
                SunCalc.azElDeg(la, lo, t)[1],
                SunCalc.elevationDeg(la, lo, t),
                1e-12,
            )
        }
    }

    /**
     * Solar noon at Greenwich on the summer solstice: the Sun is due south at
     * 90° − latitude + 23.44°. Checks azimuth is not off by a quadrant — the
     * classic hour-angle sign error.
     */
    @Test
    fun au_midi_solaire_le_soleil_est_au_sud() {
        val p = SunCalc.azElDeg(48.8566, 0.0, Date(ms("2026-06-21T12:00:00Z")))
        // About 1° off south: the equation of time (a few minutes in June),
        // deliberately not corrected here.
        assertEquals(179.0, p[0], 1.5)
        assertEquals(90.0 - 48.8566 + 23.44, p[1], 0.2)
    }

    // --- Daily azimuth crossing -------------------------------------------

    /**
     * The everyday method: at that minute a vertical stake's shadow lies along
     * the dish axis (180° off). Sun azimuth equals the satellite's to 0.01°,
     * far better than a phone compass.
     */
    @Test
    fun le_passage_en_azimut_du_21_juillet_2026_depuis_paris() {
        val t = SoleilQo100.prochainPassageEnAzimut(
            parisLat, parisLon, ms("2026-07-21T00:00:00Z"))
        assertNotNull(t)
        assertEquals("2026-07-21", jourTu(t!!))
        assertEquals("10:52", minuteTu(t))

        val sat = Qo100.pointage(parisLat, parisLon)
        val soleil = SunCalc.azElDeg(parisLat, parisLon, Date(t))
        assertEquals(sat.azDeg, soleil[0], 0.01)
        assertTrue("le Soleil doit être levé", soleil[1] > 0.0)
        assertTrue(SoleilQo100.memeAzimut(soleil[0], sat.azDeg))

        // In July the Sun is far higher than the satellite: this crossing sets
        // azimuth only.
        assertTrue(soleil[1] > sat.elDeg + 20.0)
    }

    /** There is one every day of the year: no need to wait for the equinox. */
    @Test
    fun il_y_a_un_passage_en_azimut_tous_les_jours_de_l_annee() {
        val sat = Qo100.pointage(parisLat, parisLon)
        listOf("2026-01-15", "2026-04-15", "2026-06-21", "2026-09-15", "2026-12-21").forEach { j ->
            val t = SoleilQo100.prochainPassageEnAzimut(
                parisLat, parisLon, ms(j + "T00:00:00Z"), jours = 1)
            assertNotNull("aucun passage le $j", t)
            assertEquals("le passage n'est pas le jour demandé", j, jourTu(t!!))
            val soleil = SunCalc.azElDeg(parisLat, parisLon, Date(t))
            assertEquals(sat.azDeg, soleil[0], 0.01)
            assertTrue(soleil[1] > 0.0)
        }
    }

    /**
     * From New York the satellite is well below the horizon. No shadow points
     * anywhere useful, and the function must say so rather than return a
     * meaningless time.
     */
    @Test
    fun sans_satellite_visible_il_n_y_a_ni_passage_ni_transit() {
        assertTrue(Qo100.pointage(nyLat, nyLon).elDeg < 0.0)
        assertNull(SoleilQo100.prochainPassageEnAzimut(nyLat, nyLon, ms("2026-03-01T00:00:00Z")))
        assertTrue(SoleilQo100.prochainsTransits(
            nyLat, nyLon, ms("2026-01-01T00:00:00Z")).isEmpty())
    }

    // --- Transits, twice a year -------------------------------------------

    /**
     * The ten 2026 days when the Sun passes within 1° of the satellite from
     * Paris, around the equinoxes. On the best day the feed's shadow centres
     * in the dish when pointing is right: the finest adjustment possible
     * without measuring signal.
     */
    @Test
    fun les_transits_solaires_de_2026_depuis_paris() {
        val transits = SoleilQo100.prochainsTransits(
            parisLat, parisLon, ms("2026-01-01T00:00:00Z"), jours = 400, maximum = 20)

        assertEquals(10, transits.size)
        assertEquals(
            listOf("2026-02-28", "2026-03-01", "2026-03-02", "2026-03-03", "2026-03-04",
                "2026-10-09", "2026-10-10", "2026-10-11", "2026-10-12", "2026-10-13"),
            transits.map { jourTu(it.instantMs) },
        )

        val printemps = transits[2]
        assertEquals("2026-03-02", jourTu(printemps.instantMs))
        assertEquals("10:18", minuteTu(printemps.instantMs))
        assertEquals(0.019, printemps.ecartDeg, 0.01)

        val automne = transits[7]
        assertEquals("2026-10-11", jourTu(automne.instantMs))
        assertEquals("09:53", minuteTu(automne.instantMs))
        assertEquals(0.042, automne.ecartDeg, 0.01)

        // March is the better one: 0.02°, about 1/25 of the solar diameter.
        assertEquals(printemps.ecartDeg, transits.minOf { it.ecartDeg }, 1e-12)
    }

    /**
     * What a usable transit guarantees: the Sun is where the satellite is, the
     * window brackets the instant, and it lasts long enough to turn a dish but
     * not so long it becomes meaningless.
     */
    @Test
    fun chaque_transit_est_coherent_avec_lui_meme() {
        val sat = Qo100.pointage(parisLat, parisLon)
        val transits = SoleilQo100.prochainsTransits(
            parisLat, parisLon, ms("2026-01-01T00:00:00Z"), jours = 400, maximum = 20)
        assertTrue(transits.isNotEmpty())

        transits.forEach { tr ->
            assertTrue("écart au-dessus du seuil", tr.ecartDeg <= 1.0)
            assertEquals(sat.azDeg, tr.azSoleilDeg, 1.2)
            assertEquals(sat.elDeg, tr.elSoleilDeg, 1.2)
            // The reported separation matches a recomputation at that instant.
            assertEquals(
                tr.ecartDeg,
                SoleilQo100.ecartDeg(tr.azSoleilDeg, tr.elSoleilDeg, sat.azDeg, sat.elDeg),
                1e-9,
            )
            assertTrue("la fenêtre n'encadre pas l'instant",
                tr.debutMs <= tr.instantMs && tr.instantMs <= tr.finMs)
            assertTrue("fenêtre vide", tr.dureeS > 0)
            // The Sun moves 0.25° per minute: a 1° window cannot last half an hour.
            assertTrue("fenêtre de ${tr.dureeS} s, invraisemblable", tr.dureeS < 1800)
        }
    }

    /**
     * The requested count is a hard limit: the screen only shows the next
     * dates and has no reason to scan the year only to discard them.
     */
    @Test
    fun le_nombre_de_transits_rendus_est_borne() {
        val trois = SoleilQo100.prochainsTransits(
            parisLat, parisLon, ms("2026-01-01T00:00:00Z"), jours = 400, maximum = 3)
        assertEquals(3, trois.size)
        assertEquals("2026-02-28", jourTu(trois.first().instantMs))
        // In chronological order.
        trois.zipWithNext().forEach { (a, b) ->
            assertTrue(a.instantMs < b.instantMs)
        }
    }

    /**
     * A tighter threshold gives fewer days, never more. Obvious — unless the
     * window edge and the minimum use different thresholds.
     */
    @Test
    fun un_seuil_plus_serre_ne_donne_jamais_plus_de_jours() {
        val large = SoleilQo100.prochainsTransits(
            parisLat, parisLon, ms("2026-01-01T00:00:00Z"),
            jours = 120, seuilDeg = 1.0, maximum = 20)
        val serre = SoleilQo100.prochainsTransits(
            parisLat, parisLon, ms("2026-01-01T00:00:00Z"),
            jours = 120, seuilDeg = 0.3, maximum = 20)
        assertTrue(serre.size <= large.size)
        assertTrue(serre.isNotEmpty())
        assertEquals("2026-03-02", jourTu(serre.first().instantMs))
        // A tighter window is a shorter window.
        assertTrue(serre.first().dureeS < large[2].dureeS)
    }

    /**
     * The shadow points away from the satellite. The one place where a sign
     * error turns the dish 180°, and nothing on screen would say so.
     */
    @Test
    fun l_ombre_est_a_l_oppose_du_satellite() {
        assertEquals(329.942, SoleilQo100.azimutDeLOmbre(149.942), 1e-9)
        assertEquals(10.0, SoleilQo100.azimutDeLOmbre(190.0), 1e-9)
        assertEquals(0.0, SoleilQo100.azimutDeLOmbre(180.0), 1e-9)
        // The operation is an involution.
        listOf(0.0, 45.0, 149.942, 200.0, 359.9).forEach {
            assertEquals(it, SoleilQo100.azimutDeLOmbre(SoleilQo100.azimutDeLOmbre(it)), 1e-9)
        }
    }
}
