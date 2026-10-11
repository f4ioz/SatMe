/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.AccordFin
import fr.f4ioz.satcombo.domain.DopplerTuner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Fine tuning, judged in hertz per finger movement. 490 kHz of transponder
 * across a screen is 1.4 kHz per dp, while SSB needs 50 Hz: each of the three
 * aids must bring that within hand reach.
 */
class AccordFinTest {

    /** Typical phone density: 3× the reference density. */
    private val dpi = 480f

    // ------------------------------------------------------------------ magnifier

    /** The figure that motivated everything, and its remedy, side by side. */
    @Test
    fun la_loupe_ramene_le_doigt_dans_le_domaine_de_la_blu() {
        val largeurDp = 360f

        // The full scale: what we had.
        val reglette = AccordFin.hzParDp(490_000, largeurDp)
        assertTrue("la réglette entière donne $reglette Hz/dp", reglette > 1_000)

        // The 5 kHz magnifier: what we have now.
        val loupe = AccordFin.hzParDp(5_000, largeurDp)
        assertTrue("la loupe donne $loupe Hz/dp", loupe < 20)

        // A fingertip is about 10 dp. Under the magnifier it must cover less
        // than an SSB channel.
        assertTrue(loupe * 10 < 2_400)
    }

    @Test
    fun une_largeur_nulle_ne_fait_pas_exploser_le_rapport() {
        assertEquals(0.0, AccordFin.hzParDp(5_000, 0f), 1e-9)
    }

    // ---------------------------------------------------------------- vernier

    /**
     * The ratio is in Hz per centimetre, not per pixel: the only unit giving
     * the same gesture on screens of different density. Checked by sliding a
     * real centimetre on two very different devices.
     */
    @Test
    fun un_centimetre_de_doigt_vaut_le_meme_ecart_sur_deux_ecrans() {
        val telephone = AccordFin.hzParPixel(200, 480f)
        val tablette = AccordFin.hzParPixel(200, 160f)

        val cmTelephone = 480f / 2.54f      // pixels per centimetre
        val cmTablette = 160f / 2.54f

        assertEquals(200.0, telephone * cmTelephone, 0.5)
        assertEquals(200.0, tablette * cmTablette, 0.5)
    }

    /** You drag the dial, not the needle: leftwards tunes up. */
    @Test
    fun tirer_vers_la_gauche_fait_monter_en_frequence() {
        val hzParPx = AccordFin.hzParPixel(200, 480f)
        assertTrue(AccordFin.deltaHz(-50f, hzParPx) > 0)
        assertTrue(AccordFin.deltaHz(+50f, hzParPx) < 0)
    }

    /**
     * Key vernier point: at the finest ratio a pixel is under 1 Hz. Without
     * accumulating remainders every drag event rounds to zero and a slow drag
     * produces nothing — dead exactly where it matters.
     */
    @Test
    fun le_rapport_le_plus_fin_ne_s_arrondit_pas_a_zero() {
        val hzParPx = AccordFin.hzParPixel(AccordFin.RAPPORTS.first(), dpi)
        assertTrue("un pixel vaut $hzParPx Hz", hzParPx < 1.0)

        val aiguille = AccordFin.Aiguille()
        var total = 0L
        // 100 one-pixel events: a slow drag of about 5 mm.
        repeat(100) { total += aiguille.pousse(-1f, hzParPx) }

        assertTrue("un glissement lent doit produire quelque chose", total > 0)
        assertEquals(100 * hzParPx, total.toDouble(), 1.5)
    }

    @Test
    fun l_accumulateur_ne_derive_pas_sur_un_aller_retour() {
        val hzParPx = AccordFin.hzParPixel(20, dpi)
        val aiguille = AccordFin.Aiguille()
        var total = 0L
        repeat(200) { total += aiguille.pousse(-3f, hzParPx) }
        repeat(200) { total += aiguille.pousse(+3f, hzParPx) }
        assertTrue("retour au point de départ à un hertz près : $total", abs(total) <= 1L)
    }

    @Test
    fun un_nouveau_geste_oublie_le_reste_du_precedent() {
        val aiguille = AccordFin.Aiguille()
        aiguille.pousse(-1f, 0.4)
        aiguille.oublie()
        assertEquals(0L, aiguille.pousse(-1f, 0.4))
    }

    // ----------------------------------------------------------------- fling

    @Test
    fun le_lancer_s_amortit_et_finit_par_s_arreter() {
        val v0 = 3_000f
        assertTrue(AccordFin.vitesseApres(v0, 0.0) > AccordFin.vitesseApres(v0, 0.2))
        assertTrue(AccordFin.vitesseApres(v0, 2.0) < AccordFin.VITESSE_ARRET)
    }

    /**
     * Total travel is finite, which makes the fling usable: a quick flick
     * crosses a few kHz, not the whole band.
     */
    @Test
    fun un_lancer_parcourt_une_distance_finie() {
        val parcours = AccordFin.parcoursTotal(3_000f)
        assertEquals(3_000.0 * AccordFin.TAU, parcours, 1e-6)

        val hzParPx = AccordFin.hzParPixel(200, dpi)
        val hz = parcours * hzParPx
        assertTrue("un lancer vif parcourt $hz Hz", hz in 100.0..20_000.0)
    }

    // ------------------------------------------------------------ tick marks

    @Test
    fun les_graduations_ne_se_serrent_jamais_au_dela_du_lisible() {
        listOf(0.05, 0.5, 5.0, 50.0).forEach { hzParPx ->
            val pas = AccordFin.pasGraduation(hzParPx)
            val ecart = pas / hzParPx
            assertTrue("à $hzParPx Hz/px l'écart vaut $ecart px", ecart >= 14.0)
        }
    }

    /** 1-2-5 sequence: never steps of 3, 4 or 7, which do not read well. */
    @Test
    fun le_pas_suit_la_suite_un_deux_cinq() {
        listOf(0.01, 0.1, 1.0, 10.0, 100.0).forEach { hzParPx ->
            val pas = AccordFin.pasGraduation(hzParPx)
            var m = pas
            while (m % 10L == 0L && m > 1L) m /= 10L
            assertTrue("pas $pas hors de la suite", m == 1L || m == 2L || m == 5L)
        }
    }

    @Test
    fun les_traits_encadrent_le_centre_et_restent_dans_la_vue() {
        val hzParPx = AccordFin.hzParPixel(200, dpi)
        val traits = AccordFin.traits(10_489_750_000L, hzParPx, 1080f)
        assertTrue(traits.isNotEmpty())
        assertTrue(traits.all { it.xPixels >= -1f && it.xPixels <= 1081f })
        assertTrue("il faut des traits majeurs", traits.any { it.majeur })
        // The dial is ordered: frequencies increase to the right.
        val hz = traits.map { it.hz }
        assertEquals(hz.sorted(), hz)
    }

    @Test
    fun un_cadran_de_largeur_nulle_ne_rend_aucun_trait() {
        assertTrue(AccordFin.traits(1_000L, 1.0, 0f).isEmpty())
        assertTrue(AccordFin.traits(1_000L, 0.0, 500f).isEmpty())
    }

    /**
     * A hard limit, not polish: at the coarsest ratio on a wide screen a bad
     * step would produce thousands of ticks, felt by the draw loop every frame.
     */
    @Test
    fun le_nombre_de_traits_reste_borne() {
        val traits = AccordFin.traits(0L, 0.0001, 4000f, pas = 1L)
        assertTrue(traits.size <= 400)
    }

    // ----------------------------------------------------------------- voice centring

    /**
     * The existing recentring puts the centroid at zero — fine for a carrier,
     * but an SSB voice there straddles the tuning point and you hear half of
     * each syllable. It must sit near ±1500 Hz depending on the sideband.
     */
    @Test
    fun la_cible_depend_de_la_bande_laterale() {
        assertEquals(1_500, AccordFin.cibleVoixHz("USB"))
        assertEquals(-1_500, AccordFin.cibleVoixHz("LSB"))
        assertEquals(0, AccordFin.cibleVoixHz("NFM"))
        assertEquals(0, AccordFin.cibleVoixHz("AM"))
        assertEquals(1_500, AccordFin.cibleVoixHz("usb"))
    }

    @Test
    fun le_calage_ne_se_propose_qu_en_bande_laterale() {
        assertTrue(AccordFin.calageUtile("USB"))
        assertTrue(AccordFin.calageUtile("LSB"))
        assertFalse(AccordFin.calageUtile("NFM"))
        assertFalse(AccordFin.calageUtile("AM"))
    }

    /**
     * A voice measured 800 Hz above the tuning point sounds dull at 800 Hz:
     * tune down 700 Hz to bring it up to 1500.
     */
    @Test
    fun l_accord_vise_place_la_voix_au_bon_endroit() {
        assertEquals(-700L, AccordFin.accordVise(800.0, 1_500))
        assertEquals(500L, AccordFin.accordVise(2_000.0, 1_500))
        // On LSB the spectrum is inverted and the sign follows.
        assertEquals(700L, AccordFin.accordVise(-800.0, -1_500))
        // With no target, this is exactly the old recentring.
        assertEquals(800L, AccordFin.accordVise(800.0, 0))
    }

    /**
     * The search is deliberately narrow: centre on the station already being
     * heard. Sweeping the ±25 kHz of auto-recentring would jump to a stronger
     * neighbour at the first pause.
     */
    @Test
    fun la_recherche_du_calage_tient_dans_un_canal() {
        assertTrue(AccordFin.RECHERCHE_VOIX_HZ <= 4_000.0)
        assertTrue(AccordFin.RECHERCHE_VOIX_HZ >= 2_400.0)
    }

    // ------------------------------------------------------------ downstream

    /**
     * Below the Doppler planner's threshold the command would be ignored
     * downstream anyway: sending it only adds traffic to the hot loop. So the
     * vernier filters at the same threshold, not another one.
     */
    @Test
    fun un_deplacement_sous_le_seuil_ne_part_pas() {
        assertFalse(AccordFin.vautLaPeine(0L))
        assertFalse(AccordFin.vautLaPeine(DopplerTuner.DEADBAND_HZ - 1))
        assertTrue(AccordFin.vautLaPeine(DopplerTuner.DEADBAND_HZ))
        assertTrue(AccordFin.vautLaPeine(-DopplerTuner.DEADBAND_HZ))
    }

    /** The offered ratios must cover the three real gestures. */
    @Test
    fun les_rapports_proposes_vont_du_hertz_au_kilohertz() {
        assertEquals(3, AccordFin.RAPPORTS.size)
        assertEquals(AccordFin.RAPPORTS.sorted(), AccordFin.RAPPORTS)
        // The finest must allow hertz-level placement within a centimetre of
        // finger travel; the coarsest must cross a chunk of band.
        assertTrue(AccordFin.RAPPORTS.first() <= 50)
        assertTrue(AccordFin.RAPPORTS.last() >= 1_000)
    }

    // ------------------------------------------------ what the vernier drives

    /**
     * The core of the fix. Two tunings ignored each other: the transponder
     * channel (which produces the RX and TX lines sent to the radio) and the
     * SDR dongle tuning. The vernier only moved the latter.
     */
    @Test
    fun sur_un_transpondeur_le_vernier_deplace_le_canal() {
        assertEquals(AccordFin.Cible.CANAL,
            AccordFin.cibleDuVernier(true, 145_855_000L, 145_875_000L))
    }

    /**
     * On a fixed channel (FM) there is no band to travel: only the dongle
     * tuning moves.
     */
    @Test
    fun sur_un_canal_fixe_le_vernier_deplace_la_cle() {
        assertEquals(AccordFin.Cible.CLE,
            AccordFin.cibleDuVernier(false, 145_800_000L, 145_800_000L))
        // A transponder with no bounds, or zero width, is not one as far as
        // the gesture is concerned.
        assertEquals(AccordFin.Cible.CLE, AccordFin.cibleDuVernier(true, null, null))
        assertEquals(AccordFin.Cible.CLE,
            AccordFin.cibleDuVernier(true, 145_855_000L, 145_855_000L))
    }

    @Test
    fun le_canal_se_deplace_du_pas_demande() {
        assertEquals(145_867_000L,
            AccordFin.nouveauCanal(145_866_000L, 1_000L, 145_855_000L, 145_875_000L))
        assertEquals(145_865_000L,
            AccordFin.nouveauCanal(145_866_000L, -1_000L, 145_855_000L, 145_875_000L))
    }

    /**
     * Clamp, do not wrap. Wrapping past the edge would jump across the whole
     * transponder mid-contact — and the fling, covering several kHz per flick,
     * would make that frequent.
     */
    @Test
    fun le_canal_s_arrete_aux_bords_de_la_bande() {
        assertEquals(145_875_000L,
            AccordFin.nouveauCanal(145_874_000L, 50_000L, 145_855_000L, 145_875_000L))
        assertEquals(145_855_000L,
            AccordFin.nouveauCanal(145_856_000L, -50_000L, 145_855_000L, 145_875_000L))
    }

    /** Reversed bounds must not jam the vernier. */
    @Test
    fun des_bornes_inversees_sont_remises_a_l_endroit() {
        assertEquals(145_867_000L,
            AccordFin.nouveauCanal(145_866_000L, 1_000L, 145_875_000L, 145_855_000L))
    }

    @Test
    fun les_largeurs_de_loupe_encadrent_le_canal_blu() {
        assertEquals(AccordFin.LOUPES.sorted(), AccordFin.LOUPES)
        assertTrue("la plus étroite doit montrer un canal entier",
            AccordFin.LOUPES.first() >= 2_400)
    }
}
