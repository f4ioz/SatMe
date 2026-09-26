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
 * Le tableau du Doppler d'un passage.
 *
 * Sept essais, dont six sur une trajectoire écrite à la main — c'est tout
 * l'intérêt d'avoir sorti l'échantillonneur du calcul : on peut poser un
 * passage dissymétrique, savoir où se trouve son sommet à la seconde près, et
 * exiger que le tableau le retrouve. Le septième prend une vraie éphéméride et
 * ne vérifie qu'une chose, mais la bonne : l'ordre de grandeur des excursions.
 * Une erreur de facteur mille sur la vitesse de la lumière, ou des kilomètres
 * par seconde pris pour des mètres par seconde, ne survit pas à ce garde-fou.
 */
class DopplerPassTest {

    private val rx435 = 435_500_000L
    private val tx145 = 145_900_000L

    /** Un instant d'un passage synthétique : élévation en cloche, glissement linéaire. */
    private fun at(t: Long, aosMs: Long, losMs: Long, peakMs: Long,
                   maxEl: Double, maxRate: Double): DopplerPass.Sample {
        val half = max(peakMs - aosMs, losMs - peakMs).toDouble()
        val x = ((t - peakMs) / half).coerceIn(-1.0, 1.0)
        return DopplerPass.Sample(
            timeMs = t,
            elevationDeg = maxEl * cos(PI / 2 * x),
            // Négatif tant que le satellite approche, nul au sommet, positif ensuite.
            rangeRateKmS = maxRate * sin(PI / 2 * x)
        )
    }

    /** L'échantillonneur que [DopplerPass.build] appellera, sans TLE ni SGP4. */
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
        // La demande de départ, mot pour mot : sur un passage 435, la fréquence
        // reçue part trop haute et finit trop basse.
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
        // Et le repos est traversé au point le plus haut, pas ailleurs.
        val maxRow = t.rows.first { it.mark == DopplerPass.MARK_MAX }
        assertTrue("au sommet, l'écart au repos devrait être infime : ${maxRow.rxHz - rx435}",
            abs(maxRow.rxHz - rx435) < 200)
    }

    @Test
    fun la_montee_fait_l_inverse_et_pour_la_meme_raison() {
        // Les deux VFO partent en sens contraires : c'est la signature d'un
        // suivi juste, et non un défaut de signe.
        val t = DopplerPass.build(aos, los, rx435, tx145, sample = sampler(aos, los, aos + 300_000L))
        t.rows.zipWithNext().forEach { (a, b) ->
            assertTrue("TX baisse : ${a.txHz} puis ${b.txHz}", b.txHz!! > a.txHz!!)
        }
        assertTrue(t.rows.first().txHz!! < tx145)
        assertTrue(t.rows.last().txHz!! > tx145)
        // Sans voie montante, la colonne reste vide plutôt que de mentir.
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
        // Les heures montent, et restent dans le passage.
        t.rows.zipWithNext().forEach { (a, b) -> assertTrue(a.timeMs < b.timeMs) }
        assertTrue(t.rows.first().timeMs >= aos && t.rows.last().timeMs <= los)
    }

    @Test
    fun le_sommet_dissymetrique_est_affine_a_deux_secondes() {
        // Sommet à 197 s : le balayage grossier ne peut proposer que 180 s ou
        // 210 s. Placer le maximum au milieu du temps, comme on est tenté de le
        // faire, serait faux de plus d'une minute ici.
        val peak = aos + 197_000L
        val t = DopplerPass.build(aos, los, rx435, tx145, sample = sampler(aos, los, peak))
        val maxRow = t.rows.first { it.mark == DopplerPass.MARK_MAX }
        assertTrue("sommet trouvé à ${(maxRow.timeMs - aos) / 1000.0} s au lieu de 197 s",
            abs(maxRow.timeMs - peak) <= DopplerPass.FINE_MS / 2)
        // Le milieu du passage, lui, est à 300 s : la naïveté aurait coûté 103 s.
        assertTrue(abs(maxRow.timeMs - (aos + los) / 2) > 90_000L)
    }

    @Test
    fun l_inversion_du_transpondeur_ne_change_pas_le_sens_du_glissement() {
        // Elle décide quelle voie de montée correspond à la descente choisie —
        // rien d'autre. Confondre les deux coûte un passage entier.
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
        // Et les deux repos diffèrent bien, sinon l'essai ne prouverait rien.
        assertTrue(Doppler.transponderUplinkRest(rx, dlLow, dlHigh, ulLow, ulHigh, false) !=
            Doppler.transponderUplinkRest(rx, dlLow, dlHigh, ulLow, ulHigh, true))
    }

    @Test
    fun un_passage_absurde_rend_un_tableau_vide_plutot_qu_une_exception() {
        // L'écran de détail appelle ce calcul à chaque recomposition, y compris
        // avant qu'un satellite ou une fréquence ne soient choisis.
        assertTrue(DopplerPass.build(los, aos, rx435, sample = sampler(aos, los, aos)).isEmpty)
        assertTrue(DopplerPass.build(aos, los, 0L, sample = sampler(aos, los, aos)).isEmpty)
        assertTrue(DopplerPass.build(aos, los, rx435) { _, _, _ -> emptyList() }.isEmpty)
        // Et l'affichage des kilohertz reste lisible, virgule ou point.
        assertEquals("12.3 kHz", DopplerPass.kHz(12_345L).replace(',', '.'))
    }

    @Test
    fun les_excursions_reelles_restent_dans_les_ordres_de_grandeur() {
        // Le garde-fou. Sur 435 MHz une station voit une dizaine de kilohertz
        // d'un bout à l'autre du passage, sur 145 MHz trois fois moins — et le
        // rapport des deux suit celui des fréquences. Une erreur de facteur
        // mille sur la vitesse de la lumière, ou des kilomètres par seconde pris
        // pour des mètres par seconde, ne passe pas ici.
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
        // La borne haute mérite un mot : 435,5 MHz × 2 × 7 km/s / c vaut
        // 20,3 kHz, et le tableau échantillonne d'un horizon à l'autre, là où
        // la vitesse radiale est la plus forte. Un passage au zénith frôle donc
        // le plafond — 20 018 Hz mesurés ici. La borne est à 22 kHz pour cette
        // raison, et non par prudence : au-delà, c'est la physique qui est en
        // cause, pas le passage.
        assertTrue("excursion RX hors bornes : ${t.rxExcursionHz} Hz",
            t.rxExcursionHz in 4_000L..22_000L)
        assertTrue("excursion TX hors bornes : ${t.txExcursionHz} Hz",
            t.txExcursionHz in 1_000L..7_000L)
        val ratio = t.rxExcursionHz.toDouble() / t.txExcursionHz
        val attendu = rx435.toDouble() / tx145
        assertEquals("rapport des excursions $ratio au lieu de $attendu",
            attendu, ratio, 0.15)
        // Et le sens, sur du vrai : la descente baisse.
        assertTrue(t.rows.last().rxHz < t.rows.first().rxHz)
    }

    @Test
    fun le_repos_de_reception_ne_survit_pas_au_changement_de_satellite() {
        // « Bug fréquence TX qui n'apparaît pas. » Le repos accordé à la main
        // restait en mémoire d'un satellite à l'autre : la colonne RX se
        // remplissait à partir de l'ancien repos, la colonne TX n'avait aucun
        // émetteur d'où partir, et l'on obtenait un tableau à moitié vide sous
        // le nom du nouveau satellite. Sans émetteur, il n'y a pas de tableau
        // du tout — c'est plus honnête qu'un tableau faux.
        assertNull(DopplerPass.rxRest(435_856_800L, null, null))

        // Avec un émetteur, le repos accordé à la main l'emporte : c'est le
        // choix de l'opérateur, et il ne doit pas être écrasé par le centre
        // du transpondeur à chaque tour.
        assertEquals(435_856_800L,
            DopplerPass.rxRest(435_856_800L, 435_850_000L, 435_840_000L))

        // Sans repos accordé, le centre du transpondeur ; à défaut, le bas de
        // la bande, qui est tout ce qu'un émetteur FM donne.
        assertEquals(435_850_000L, DopplerPass.rxRest(null, 435_850_000L, 435_840_000L))
        assertEquals(145_800_000L, DopplerPass.rxRest(null, null, 145_800_000L))
    }
}
