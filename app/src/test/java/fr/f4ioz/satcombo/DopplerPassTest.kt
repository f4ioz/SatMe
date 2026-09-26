/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.data.Observer
import fr.f4ioz.satcombo.data.TleEntry
import fr.f4ioz.satcombo.domain.Doppler
import fr.f4ioz.satcombo.domain.DopplerPass
import fr.f4ioz.satcombo.domain.PassPredictor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * The Doppler table for a pass. Most tests use a hand-written asymmetric
 * track with a known peak; one uses a real ephemeris to check the order of
 * magnitude (catches km/s vs m/s or a wrong c).
 */
class DopplerPassTest {

    private val rx435 = 435_500_000L
    private val tx145 = 145_900_000L

    /** One instant of a synthetic pass: bell-shaped elevation, smooth range rate. */
    private fun at(t: Long, aosMs: Long, losMs: Long, peakMs: Long,
                   maxEl: Double, maxRate: Double): DopplerPass.Sample {
        val half = max(peakMs - aosMs, losMs - peakMs).toDouble()
        val x = ((t - peakMs) / half).coerceIn(-1.0, 1.0)
        return DopplerPass.Sample(
            timeMs = t,
            elevationDeg = maxEl * cos(PI / 2 * x),
            // Negative while approaching, zero at the peak, positive after.
            rangeRateKmS = maxRate * sin(PI / 2 * x)
        )
    }

    /** The sampler [DopplerPass.build] will call, with no TLE or SGP4. */
    private fun sampler(aosMs: Long, losMs: Long, peakMs: Long,
                        maxEl: Double = 60.0, maxRate: Double = 6.0):
        (Long, Long, Long) -> List<DopplerPass.Sample> = { from, to, step ->
        val out = ArrayList<DopplerPass.Sample>()
        var t = from
        while (t <= to) { out += at(t, aosMs, losMs, peakMs, maxEl, maxRate); t += step }
        if (out.isEmpty() || out.last().timeMs != to)
            out += at(to, aosMs, losMs, peakMs, maxEl, maxRate)
        out
    }

    private val aos = 1_700_000_000_000L
    private val los = aos + 600_000L

    @Test
    fun la_descente_ne_fait_que_baisser_du_debut_a_la_fin() {
        // The original requirement: on a 435 pass, the received frequency
        // starts high and ends low.
        val t = DopplerPass.build(aos, los, rx435, sample = sampler(aos, los, aos + 300_000L))
        assertEquals(7, t.rows.size)
        assertTrue("le tableau est vide", !t.isEmpty)
        t.rows.zipWithNext().forEach { (a, b) ->
            assertTrue("RX remonte : ${a.rxHz} puis ${b.rxHz}", b.rxHz < a.rxHz)
        }
        assertTrue("la première ligne devrait être au-dessus du repos",
            t.rows.first().rxHz > rx435)
        assertTrue("la dernière ligne devrait être en dessous du repos",
            t.rows.last().rxHz < rx435)
        // The rest frequency is crossed at the peak, nowhere else.
        val maxRow = t.rows.first { it.mark == DopplerPass.MARK_MAX }
        assertTrue("au sommet, l'écart au repos devrait être infime : ${maxRow.rxHz - rx435}",
            abs(maxRow.rxHz - rx435) < 200)
    }

    @Test
    fun la_montee_fait_l_inverse_et_pour_la_meme_raison() {
        // The two VFOs move in opposite directions: the signature of correct
        // tracking, not a sign error.
        val t = DopplerPass.build(aos, los, rx435, tx145, sample = sampler(aos, los, aos + 300_000L))
        t.rows.zipWithNext().forEach { (a, b) ->
            assertTrue("TX baisse : ${a.txHz} puis ${b.txHz}", b.txHz!! > a.txHz!!)
        }
        assertTrue(t.rows.first().txHz!! < tx145)
        assertTrue(t.rows.last().txHz!! > tx145)
        // Without an uplink the column stays empty rather than lie.
        val sans = DopplerPass.build(aos, los, rx435, sample = sampler(aos, los, aos + 300_000L))
        assertTrue(sans.rows.all { it.txHz == null })
        assertEquals(0L, sans.txExcursionHz)
    }

    @Test
    fun les_reperes_encadrent_le_passage_et_marquent_le_sommet() {
        val t = DopplerPass.build(aos, los, rx435, tx145, sample = sampler(aos, los, aos + 200_000L))
        assertEquals(DopplerPass.MARK_AOS, t.rows.first().mark)
        assertEquals(DopplerPass.MARK_LOS, t.rows.last().mark)
        assertEquals(1, t.rows.count { it.mark == DopplerPass.MARK_MAX })
        val maxRow = t.rows.first { it.mark == DopplerPass.MARK_MAX }
        assertEquals(t.rows.maxOf { it.elevationDeg }, maxRow.elevationDeg, 1e-9)
        // Times increase and stay within the pass.
        t.rows.zipWithNext().forEach { (a, b) -> assertTrue(a.timeMs < b.timeMs) }
        assertTrue(t.rows.first().timeMs >= aos && t.rows.last().timeMs <= los)
    }

    @Test
    fun le_sommet_dissymetrique_est_affine_a_deux_secondes() {
        // Peak at 197 s: the coarse sweep can only offer 180 or 210 s. Putting
        // the maximum at mid-pass, as is tempting, would be over a minute off.
        val peak = aos + 197_000L
        val t = DopplerPass.build(aos, los, rx435, tx145, sample = sampler(aos, los, peak))
        val maxRow = t.rows.first { it.mark == DopplerPass.MARK_MAX }
        assertTrue("sommet trouvé à ${(maxRow.timeMs - aos) / 1000.0} s au lieu de 197 s",
            abs(maxRow.timeMs - peak) <= DopplerPass.FINE_MS / 2)
        // Mid-pass is 300 s: the naive guess would be 103 s off.
        assertTrue(abs(maxRow.timeMs - (aos + los) / 2) > 90_000L)
    }

    @Test
    fun l_inversion_du_transpondeur_ne_change_pas_le_sens_du_glissement() {
        // Inversion decides which uplink matches the chosen downlink — nothing
        // else. Confusing the two costs a whole pass.
        val dlLow = 435_490_000L; val dlHigh = 435_530_000L
        val ulLow = 145_920_000L; val ulHigh = 145_960_000L
        val rx = 435_500_000L
        for (invert in listOf(false, true)) {
            val txRest = Doppler.transponderUplinkRest(rx, dlLow, dlHigh, ulLow, ulHigh, invert)
            val t = DopplerPass.build(aos, los, rx, txRest, sample = sampler(aos, los, aos + 300_000L))
            t.rows.zipWithNext().forEach { (a, b) ->
                assertTrue("inversion=$invert : RX devrait baisser", b.rxHz < a.rxHz)
                assertTrue("inversion=$invert : TX devrait monter", b.txHz!! > a.txHz!!)
            }
        }
        // The two rest frequencies differ, or the test would prove nothing.
        assertTrue(Doppler.transponderUplinkRest(rx, dlLow, dlHigh, ulLow, ulHigh, false) !=
            Doppler.transponderUplinkRest(rx, dlLow, dlHigh, ulLow, ulHigh, true))
    }

    @Test
    fun un_passage_absurde_rend_un_tableau_vide_plutot_qu_une_exception() {
        // The detail screen calls this on every recomposition, including before
        // a satellite or frequency is chosen.
        assertTrue(DopplerPass.build(los, aos, rx435, sample = sampler(aos, los, aos)).isEmpty)
        assertTrue(DopplerPass.build(aos, los, 0L, sample = sampler(aos, los, aos)).isEmpty)
        assertTrue(DopplerPass.build(aos, los, rx435) { _, _, _ -> emptyList() }.isEmpty)
        // kHz display stays readable, comma or point.
        assertEquals("12.3 kHz", DopplerPass.kHz(12_345L).replace(',', '.'))
    }

    @Test
    fun les_excursions_reelles_restent_dans_les_ordres_de_grandeur() {
        // The safety net. On 435 MHz a station sees on the order of 10–20 kHz
        // over a pass, on 145 MHz three times less, and the ratio follows the
        // frequencies. A factor-1000 error on c, or km/s taken for m/s, fails.
        val iss = TleEntry(
            name = "ISS (ZARYA)",
            line1 = "1 25544U 98067A   24001.50000000  .00016717  00000-0  30777-3 0  9990",
            line2 = "2 25544  51.6416 247.4627 0006703 130.5360 325.0288 15.49512407430303"
        )
        val paris = Observer(48.80, 2.48, 50.0, "JN18FS")
        val epochMs = 1704110400000L
        val predictor = PassPredictor()
        val pass = predictor.upcomingPasses(iss, paris, epochMs, 48, 20.0)
            .maxByOrNull { it.maxElevationDeg }
        assertTrue("aucun passage exploitable sur 48 h", pass != null)

        val t = DopplerPass.build(pass!!.aosEpochMs, pass.losEpochMs, rx435, tx145) { from, to, step ->
            predictor.samplePositions(iss, paris, from, to, step)
        }
        assertTrue("tableau vide sur un vrai passage", !t.isEmpty)
        // Upper bound: 435.5 MHz × 2 × 7 km/s / c = 20.3 kHz, and the table
        // samples horizon to horizon where range rate peaks, so an overhead
        // pass nears the ceiling (20018 Hz here). Hence 22 kHz, not caution:
        // beyond it, physics is broken, not the pass.
        assertTrue("excursion RX hors bornes : ${t.rxExcursionHz} Hz",
            t.rxExcursionHz in 4_000L..22_000L)
        assertTrue("excursion TX hors bornes : ${t.txExcursionHz} Hz",
            t.txExcursionHz in 1_000L..7_000L)
        val ratio = t.rxExcursionHz.toDouble() / t.txExcursionHz
        val attendu = rx435.toDouble() / tx145
        assertEquals("rapport des excursions $ratio au lieu de $attendu",
            attendu, ratio, 0.15)
        // Direction on real data: the downlink falls.
        assertTrue(t.rows.last().rxHz < t.rows.first().rxHz)
    }

    @Test
    fun le_repos_de_reception_ne_survit_pas_au_changement_de_satellite() {
        // Bug "TX frequency missing": the hand-tuned rest frequency survived a
        // satellite change, so RX was filled from the old rest, TX had no
        // transmitter to start from, and a half-empty table appeared under the
        // new satellite's name. No transmitter, no table — more honest than a
        // wrong one.
        assertNull(DopplerPass.rxRest(435_856_800L, null, null))

        // With a transmitter, the hand-tuned rest wins: the operator's choice
        // must not be overwritten by the transponder centre every cycle.
        assertEquals(435_856_800L,
            DopplerPass.rxRest(435_856_800L, 435_850_000L, 435_840_000L))

        // No tuned rest: the transponder centre; failing that, the band low
        // edge, which is all an FM transmitter provides.
        assertEquals(435_850_000L, DopplerPass.rxRest(null, 435_850_000L, 435_840_000L))
        assertEquals(145_800_000L, DopplerPass.rxRest(null, null, 145_800_000L))
    }
}
