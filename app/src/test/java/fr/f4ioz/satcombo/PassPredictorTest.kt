/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.data.Observer
import fr.f4ioz.satcombo.data.OmmParser
import fr.f4ioz.satcombo.data.SkedSample
import fr.f4ioz.satcombo.data.SkedStationTrack
import fr.f4ioz.satcombo.data.TleEntry
import fr.f4ioz.satcombo.domain.PassPredictor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PassPredictorTest {

    // A real ISS TLE (epoch 2024-01-01-ish). SGP4 drifts far from epoch, so all
    // tests propagate near this date, NOT "now".
    private val iss = TleEntry(
        name = "ISS (ZARYA)",
        line1 = "1 25544U 98067A   24001.50000000  .00016717  00000-0  30777-3 0  9990",
        line2 = "2 25544  51.6416 247.4627 0006703 130.5360 325.0288 15.49512407430303"
    )
    private val epochMs = 1704110400000L // 2024-01-01 12:00:00 UTC
    private val paris = Observer(48.80, 2.48, 50.0, "JN18FS")

    @Test fun `ISS produces passes over Paris within 48h of epoch`() {
        val passes = PassPredictor().upcomingPasses(iss, paris, epochMs, 48, 0.0)
        // ISS at 51.6° inclination passes over 48.8°N several times a day.
        assertTrue("expected >=4 passes, got ${passes.size}", passes.size >= 4)
        for (p in passes) {
            assertTrue(p.losEpochMs > p.aosEpochMs)
            assertTrue("duration ${p.durationSec}s", p.durationSec in 60..900)
            assertTrue("maxEl ${p.maxElevationDeg}", p.maxElevationDeg in 0.0..90.0)
            assertTrue(p.aosAzimuthDeg in 0.0..360.0)
            assertTrue(p.losAzimuthDeg in 0.0..360.0)
        }
        // Chronological, non-overlapping.
        passes.zipWithNext().forEach { (a, b) -> assertTrue(a.losEpochMs <= b.aosEpochMs) }
    }

    /**
     * Le passage en cours se retrouve depuis un instant quelconque du passage,
     * et il coïncide avec celui qu'annonce la liste — c'est ce qui manquait
     * pour que « déjà appelé sur ce passage » veuille dire quelque chose.
     */
    @Test fun `currentPass retrouve le passage depuis son milieu`() {
        val pred = PassPredictor()
        val passe = pred.upcomingPasses(iss, paris, epochMs, 48, 0.0).first()
        val milieu = (passe.aosEpochMs + passe.losEpochMs) / 2
        val trouve = pred.currentPass(iss, paris, milieu)
        assertTrue("aucun passage trouvé au milieu du passage", trouve != null)
        // À la seconde près : les deux bords sont affinés par dichotomie.
        assertTrue("aos ${trouve!!.first} vs ${passe.aosEpochMs}",
            Math.abs(trouve.first - passe.aosEpochMs) < 2000)
        assertTrue("los ${trouve.second} vs ${passe.losEpochMs}",
            Math.abs(trouve.second - passe.losEpochMs) < 2000)
    }

    @Test fun `currentPass ne rend rien quand le satellite est couche`() {
        val pred = PassPredictor()
        val passe = pred.upcomingPasses(iss, paris, epochMs, 48, 0.0).first()
        // Dix minutes après la perte du signal : plus de passage en cours.
        assertEquals(null, pred.currentPass(iss, paris, passe.losEpochMs + 600_000L))
    }

    @Test fun `catalog number parsed from TLE line 1`() {
        assertEquals(25544, iss.catalogNumber)
    }

    @Test fun `positionAt returns sane geodetic values`() {
        val p = PassPredictor().positionAt(iss, paris, epochMs)
        assertTrue(p.latDeg in -52.0..52.0)     // bounded by inclination
        assertTrue(p.lonDeg in -180.0..180.0)
        assertTrue("altitude ${p.altKm}", p.altKm in 350.0..460.0)
    }

    @Test fun `sampleTrack spans the requested window with monotonic times`() {
        val from = epochMs; val to = epochMs + 600_000L
        val samples = PassPredictor().sampleTrack(iss, paris, from, to, 60)
        assertEquals(61, samples.size)
        assertEquals(from, samples.first().first)
        assertEquals(to, samples.last().first)
        samples.zipWithNext().forEach { (a, b) -> assertTrue(a.first < b.first) }
    }

    @Test fun `sked track interpolation crosses the 360 azimuth wrap smoothly`() {
        val track = SkedStationTrack(
            label = "JN18FS", locator = "JN18FS", aosMs = 0L, losMs = 20_000L,
            maxElDeg = 10.0,
            samples = listOf(
                SkedSample(0L, 350.0, 5.0),
                SkedSample(10_000L, 10.0, 8.0),   // wraps through north
                SkedSample(20_000L, 30.0, 3.0)
            )
        )
        val mid = track.sampleAt(5_000L)!!
        // Shortest-arc interpolation: 350° -> 10° passes through 0°, not 180°.
        assertEquals(0.0, mid.azDeg, 0.01)
        assertEquals(6.5, mid.elDeg, 0.01)
        // Outside the window clamps to the ends.
        assertEquals(350.0, track.sampleAt(-5L)!!.azDeg, 1e-9)
        assertEquals(30.0, track.sampleAt(99_999L)!!.azDeg, 1e-9)
    }

    @Test fun `normalizeDesignator collapses leading zeros`() {
        assertEquals("AO-7", normalizeDesignator("AO-07"))
        assertEquals("FO-29", normalizeDesignator("FO-029 (JAS-2)"))
        assertEquals("AO-73", normalizeDesignator("ao-73"))
    }

    /**
     * Malformed elements must be rejected, not crash the pass computation.
     *
     * Reproduces the production crash of 20.47: predict4java parses its numeric
     * fields with `Integer.parseInt`, and a truncated or corrupted line threw a
     * NumberFormatException from inside the coroutine computing favourite
     * passes — killing every satellite, not only the faulty one.
     */
    @Test
    fun des_elements_abimes_sont_ecartes_et_ne_plantent_pas() {
        val bon = TleEntry(
            name = "ISS (ZARYA)",
            line1 = "1 25544U 98067A   24001.50000000  .00016717  00000-0  10270-3 0  9003",
            line2 = "2 25544  51.6416 247.4627 0006703 130.5360 325.0288 15.49514637 12345")
        assertTrue(PassPredictor.elementsUtilisables(bon))

        // Un caractère non numérique là où la bibliothèque attend un entier.
        val abime = bon.copy(
            line1 = "1 25544U 98067A   24001.50000000  .00016717  00000-0  10270-3 0  9XX3")
        assertTrue(!PassPredictor.elementsUtilisables(abime))

        // Une ligne tronquée : le cas d'un téléchargement coupé.
        assertTrue(!PassPredictor.elementsUtilisables(bon.copy(line2 = "2 25544  51.64")))

        // Et surtout : le contrôle ne lève rien, quoi qu'on lui donne.
        assertTrue(!PassPredictor.elementsUtilisables(
            bon.copy(line1 = "", line2 = "")))
    }

    /**
     * Alpha-5 catalog numbers must stay usable.
     *
     * Catalog numbers above 99999 are written "A0057". predict4java reads that
     * field with `Integer.parseInt` and throws — which is what crashed 20.47
     * in production for anyone tracking SOYUZ-MS 29 (100057) or PROGRESS-MS 35
     * (100712), both in CelesTrak's "stations" group.
     *
     * They are rewritten for the library rather than dropped: every new
     * satellite now gets a six-digit number, amateur ones included.
     */
    @Test
    fun un_numero_alpha5_reste_utilisable() {
        val soyouz = TleEntry(
            name = "SOYUZ-MS 29",
            line1 = "1 A0057U 26162A   26267.85145930  .00010574  00000+0  19791-3 0  9993",
            line2 = "2 A0057  51.6317 166.8345 0004742 177.8061 182.2949 15.49273918587315",
            catalogNumber = 100057)
        assertTrue(PassPredictor.elementsUtilisables(soyouz))

        // Le champ vu par la bibliothèque est numérique, et la somme de
        // contrôle est refaite sur les colonnes modifiées.
        val vue = PassPredictor.lisible(soyouz.line1)
        assertTrue(vue.substring(2, 7).all { it.isDigit() })
        assertEquals(69, vue.length)

        // Un numéro ordinaire n'est pas touché : pas de réécriture inutile.
        val iss = soyouz.copy(
            line1 = "1 25544U 98067A   24001.50000000  .00016717  00000-0  10270-3 0  9003")
        assertEquals(iss.line1, PassPredictor.lisible(iss.line1))
    }

    /**
     * Catalog numbers the TLE format cannot carry at all.
     *
     * Alpha-5 stops at 339999 — it is a stopgap for legacy systems, and no
     * letter is left beyond Z. Nine-digit numbers already exist: CelesTrak
     * serves 18 SDS launch nominals in the 799xxxxxx range, and those only
     * appear in OMM formats.
     *
     * SatMe builds its own TLE lines from OMM, so such a number must degrade
     * to something the library can read rather than throw. The true number
     * stays in [TleEntry]; the field written in the line is only a label, and
     * the orbital maths never touch it.
     */
    @Test
    fun un_numero_hors_limite_ne_plante_pas() {
        assertEquals("A0000", OmmParser.alpha5(100000))
        assertEquals("Z9999", OmmParser.alpha5(339999))

        // Au-delà de la limite du format, on retombe sur des chiffres.
        listOf(340000, 799500001, 999999999).forEach { n ->
            val champ = OmmParser.alpha5(n)
            assertEquals(5, champ.length)
            assertTrue("« $champ » doit être numérique", champ.all { it.isDigit() })
        }

        // Et une ligne portant un tel champ reste utilisable de bout en bout.
        val e = TleEntry(
            name = "NOMINAL 799500001",
            line1 = "1 " + OmmParser.alpha5(799500001) +
                "U 26162A   26267.85145930  .00010574  00000+0  19791-3 0  9993",
            line2 = "2 " + OmmParser.alpha5(799500001) +
                "  51.6317 166.8345 0004742 177.8061 182.2949 15.49273918587315",
            catalogNumber = 799500001)
        assertEquals(69, e.line1.length)
        assertTrue(PassPredictor.elementsUtilisables(e))
    }
}
