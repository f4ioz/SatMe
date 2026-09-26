/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.SuiviPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Position tracking on the move. Updates once only ran with the map open, so
 * the passes page kept the startup position: wrong azimuths that looked right.
 */
class SuiviPositionTest {

    private val jn18fs = 48.86 to 2.35      // Paris area
    private val t0 = 1_800_000_000_000L

    @Test
    fun la_distance_est_juste_sur_un_trajet_connu() {
        // Paris → Lyon, about 392 km great-circle.
        val d = SuiviPosition.distanceM(48.86, 2.35, 45.76, 4.84)
        assertEquals(392_000.0, d, 8_000.0)
    }

    @Test
    fun deux_points_confondus_sont_a_distance_nulle() {
        assertEquals(0.0, SuiviPosition.distanceM(48.86, 2.35, 48.86, 2.35), 1e-6)
    }

    /** No previous position: compute. The case of a freshly started app. */
    @Test
    fun le_premier_point_declenche_toujours_un_calcul() {
        assertTrue(SuiviPosition.doitRecalculer(
            null, null, jn18fs.first, jn18fs.second, 0L, t0))
        assertTrue(SuiviPosition.doitRecalculer(
            48.86, null, jn18fs.first, jn18fs.second, t0, t0))
    }

    /**
     * The core of it: GPS jitter of a few metres must not rerun a 48-hour SGP4
     * prediction for every tracked satellite — battery spent without changing
     * a single displayed second.
     */
    @Test
    fun un_fremissement_du_gps_ne_relance_pas_la_prediction() {
        // About 30 m, two hours later: time threshold passed, distance not.
        val proche = jn18fs.first + 0.0003
        assertFalse(SuiviPosition.doitRecalculer(
            jn18fs.first, jn18fs.second, proche, jn18fs.second,
            t0, t0 + 7_200_000L))
    }

    @Test
    fun un_vrai_deplacement_relance_la_prediction() {
        // About 10 km north.
        val colline = jn18fs.first + 0.1
        assertTrue(SuiviPosition.doitRecalculer(
            jn18fs.first, jn18fs.second, colline, jn18fs.second,
            t0, t0 + SuiviPosition.DELAI_MIN_MS))
    }

    /**
     * The distance threshold alone is not enough: on a motorway it is crossed
     * every 90 seconds, and we would spend the day predicting.
     */
    @Test
    fun le_plancher_de_temps_tient_meme_a_grande_distance() {
        val loin = jn18fs.first + 1.0     // about 100 km
        assertFalse(SuiviPosition.doitRecalculer(
            jn18fs.first, jn18fs.second, loin, jn18fs.second,
            t0, t0 + SuiviPosition.DELAI_MIN_MS - 1))
        assertTrue(SuiviPosition.doitRecalculer(
            jn18fs.first, jn18fs.second, loin, jn18fs.second,
            t0, t0 + SuiviPosition.DELAI_MIN_MS))
    }

    @Test
    fun le_seuil_tombe_ou_il_est_annonce() {
        // One degree of latitude ≈ 111 km: aim close to the threshold.
        val juste_sous = jn18fs.first + (SuiviPosition.SEUIL_M * 0.9) / 111_000.0
        val juste_au_dessus = jn18fs.first + (SuiviPosition.SEUIL_M * 1.1) / 111_000.0
        val plus_tard = t0 + SuiviPosition.DELAI_MIN_MS
        assertFalse(SuiviPosition.doitRecalculer(
            jn18fs.first, jn18fs.second, juste_sous, jn18fs.second, t0, plus_tard))
        assertTrue(SuiviPosition.doitRecalculer(
            jn18fs.first, jn18fs.second, juste_au_dessus, jn18fs.second, t0, plus_tard))
    }

    /**
     * 0,0 deserves its own test. A receiver with no fix returns it, and it
     * lies in the Gulf of Guinea — a perfectly valid place, which makes it
     * treacherous: accepted, it would move the QTH thousands of kilometres and
     * point the antenna at random.
     */
    @Test
    fun le_point_nul_du_recepteur_est_refuse() {
        assertFalse(SuiviPosition.vraisemblable(0.0, 0.0))
        assertTrue(SuiviPosition.vraisemblable(jn18fs.first, jn18fs.second))
    }

    @Test
    fun des_coordonnees_hors_du_globe_sont_refusees() {
        assertFalse(SuiviPosition.vraisemblable(91.0, 0.0))
        assertFalse(SuiviPosition.vraisemblable(-91.0, 0.0))
        assertFalse(SuiviPosition.vraisemblable(45.0, 181.0))
        assertTrue(SuiviPosition.vraisemblable(-33.9, 151.2))
    }

    // ------------------------------------------------------------- watchdog

    /**
     * A request sent to Google services before they are ready leaves a live
     * task that never delivers. Checking that the task **exists** is not
     * enough; only opening the map restarted it ("open a map to get an update").
     */
    @Test
    fun une_tache_vivante_mais_muette_est_relancee() {
        val demarre = t0
        val silence = t0 + SuiviPosition.SILENCE_MAX_MS + 1
        assertTrue(SuiviPosition.doitRelancer(
            auto = true, tacheActive = true,
            dernierPointMs = 0L, demarreDepuisMs = demarre, maintenantMs = silence))
    }

    /**
     * But give the provider time for a first fix: a GPS cold start can take a
     * minute, and restarting meanwhile would prevent the fix.
     */
    @Test
    fun on_laisse_au_gps_le_temps_du_premier_point() {
        assertFalse(SuiviPosition.doitRelancer(
            auto = true, tacheActive = true,
            dernierPointMs = 0L, demarreDepuisMs = t0,
            maintenantMs = t0 + SuiviPosition.SILENCE_MAX_MS - 1))
    }

    @Test
    fun un_suivi_qui_delivre_n_est_pas_derange() {
        val maintenant = t0 + 600_000L
        assertFalse(SuiviPosition.doitRelancer(
            auto = true, tacheActive = true,
            dernierPointMs = maintenant - 10_000L,
            demarreDepuisMs = t0, maintenantMs = maintenant))
    }

    @Test
    fun une_tache_morte_est_relancee_sans_attendre() {
        assertTrue(SuiviPosition.doitRelancer(
            auto = true, tacheActive = false,
            dernierPointMs = t0, demarreDepuisMs = t0, maintenantMs = t0 + 1))
    }

    /** In manual mode nothing restarts: the operator chose the QTH. */
    @Test
    fun le_mode_manuel_n_est_jamais_relance() {
        assertFalse(SuiviPosition.doitRelancer(
            auto = false, tacheActive = false,
            dernierPointMs = 0L, demarreDepuisMs = 0L,
            maintenantMs = t0 + 10 * SuiviPosition.SILENCE_MAX_MS))
    }

    /**
     * Tolerated silence must be well above the background rate, or the watchdog
     * would restart tracking between normal fixes and never let it settle.
     */
    @Test
    fun le_silence_tolere_laisse_passer_plusieurs_points_normaux() {
        assertTrue(SuiviPosition.SILENCE_MAX_MS >= 4 * SuiviPosition.CADENCE_FOND_MS)
    }

    /**
     * The background rate is much slower than the map's, on purpose: tracking
     * now runs permanently, so its rate is a battery budget item, not a
     * display detail.
     */
    @Test
    fun la_cadence_de_fond_menage_la_batterie() {
        assertTrue(SuiviPosition.CADENCE_FOND_MS >= 5 * SuiviPosition.CADENCE_CARTE_MS)
        // But fast enough to see a grid square change promptly.
        assertTrue(SuiviPosition.CADENCE_FOND_MS <= 60_000L)
    }
}
