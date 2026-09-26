/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
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
 * L'accord fin se juge en hertz sous le doigt, et cela se calcule.
 *
 * Le défaut d'origine tenait dans un rapport : 490 kHz de transpondeur étalés
 * sur la largeur d'un écran font 1,4 kHz par dp, quand une BLU se cale à 50 Hz
 * près. Les essais qui suivent vérifient d'abord ce chiffre, puis que chacune
 * des trois aides le ramène dans le domaine de la main.
 */
class AccordFinTest {

    /** Densité d'un téléphone courant : 3× la densité de référence. */
    private val dpi = 480f

    // ------------------------------------------------------------------ loupe

    /**
     * Le chiffre qui a motivé tout le reste, et son remède, côte à côte.
     */
    @Test
    fun la_loupe_ramene_le_doigt_dans_le_domaine_de_la_blu() {
        val largeurDp = 360f

        // La réglette entière : ce qu'on avait.
        val reglette = AccordFin.hzParDp(490_000, largeurDp)
        assertTrue("la réglette entière donne $reglette Hz/dp", reglette > 1_000)

        // La loupe de cinq kilohertz : ce qu'on a maintenant.
        val loupe = AccordFin.hzParDp(5_000, largeurDp)
        assertTrue("la loupe donne $loupe Hz/dp", loupe < 20)

        // Une pulpe de doigt fait une dizaine de dp. Sous la loupe, elle doit
        // couvrir moins que la largeur d'un canal BLU.
        assertTrue(loupe * 10 < 2_400)
    }

    @Test
    fun une_largeur_nulle_ne_fait_pas_exploser_le_rapport() {
        assertEquals(0.0, AccordFin.hzParDp(5_000, 0f), 1e-9)
    }

    // ---------------------------------------------------------------- vernier

    /**
     * Le rapport se dit en hertz par centimètre et non par pixel : c'est la
     * seule unité qui rende le même geste sur deux écrans de densités
     * différentes. On le vérifie en faisant glisser un centimètre réel sur
     * deux appareils que tout oppose.
     */
    @Test
    fun un_centimetre_de_doigt_vaut_le_meme_ecart_sur_deux_ecrans() {
        val telephone = AccordFin.hzParPixel(200, 480f)
        val tablette = AccordFin.hzParPixel(200, 160f)

        val cmTelephone = 480f / 2.54f      // pixels dans un centimètre
        val cmTablette = 160f / 2.54f

        assertEquals(200.0, telephone * cmTelephone, 0.5)
        assertEquals(200.0, tablette * cmTablette, 0.5)
    }

    /** On glisse le cadran, pas l'aiguille : vers la gauche fait monter. */
    @Test
    fun tirer_vers_la_gauche_fait_monter_en_frequence() {
        val hzParPx = AccordFin.hzParPixel(200, 480f)
        assertTrue(AccordFin.deltaHz(-50f, hzParPx) > 0)
        assertTrue(AccordFin.deltaHz(+50f, hzParPx) < 0)
    }

    /**
     * Le point le plus important du vernier.
     *
     * Au rapport le plus fin, un pixel vaut moins d'un hertz : 20 Hz/cm sur un
     * écran à 480 points par pouce font environ 0,1 Hz par pixel. Sans
     * accumulation des restes, chaque événement de glissement s'arrondirait à
     * zéro et le vernier serait mort exactement là où il sert le plus — un
     * glissement lent ne produirait rien du tout.
     */
    @Test
    fun le_rapport_le_plus_fin_ne_s_arrondit_pas_a_zero() {
        val hzParPx = AccordFin.hzParPixel(AccordFin.RAPPORTS.first(), dpi)
        assertTrue("un pixel vaut $hzParPx Hz", hzParPx < 1.0)

        val aiguille = AccordFin.Aiguille()
        var total = 0L
        // Cent événements d'un pixel : un glissement lent d'environ 2 mm.
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

    // ----------------------------------------------------------------- lancer

    @Test
    fun le_lancer_s_amortit_et_finit_par_s_arreter() {
        val v0 = 3_000f
        assertTrue(AccordFin.vitesseApres(v0, 0.0) > AccordFin.vitesseApres(v0, 0.2))
        assertTrue(AccordFin.vitesseApres(v0, 2.0) < AccordFin.VITESSE_ARRET)
    }

    /**
     * Le parcours total est fini, et c'est ce qui rend le lancer utilisable :
     * un geste vif traverse quelques kilohertz, pas la bande entière.
     */
    @Test
    fun un_lancer_parcourt_une_distance_finie() {
        val parcours = AccordFin.parcoursTotal(3_000f)
        assertEquals(3_000.0 * AccordFin.TAU, parcours, 1e-6)

        val hzParPx = AccordFin.hzParPixel(200, dpi)
        val hz = parcours * hzParPx
        assertTrue("un lancer vif parcourt $hz Hz", hz in 100.0..20_000.0)
    }

    // ------------------------------------------------------------ graduations

    @Test
    fun les_graduations_ne_se_serrent_jamais_au_dela_du_lisible() {
        listOf(0.05, 0.5, 5.0, 50.0).forEach { hzParPx ->
            val pas = AccordFin.pasGraduation(hzParPx)
            val ecart = pas / hzParPx
            assertTrue("à $hzParPx Hz/px l'écart vaut $ecart px", ecart >= 14.0)
        }
    }

    /** La suite 1-2-5 : jamais de pas en 3, 4 ou 7, qui ne se lisent pas. */
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
        // Le cadran est ordonné : les fréquences montent vers la droite.
        val hz = traits.map { it.hz }
        assertEquals(hz.sorted(), hz)
    }

    @Test
    fun un_cadran_de_largeur_nulle_ne_rend_aucun_trait() {
        assertTrue(AccordFin.traits(1_000L, 1.0, 0f).isEmpty())
        assertTrue(AccordFin.traits(1_000L, 0.0, 500f).isEmpty())
    }

    /**
     * Une borne, pas une élégance : au rapport le plus grossier sur un écran
     * large, un pas mal choisi produirait des milliers de traits et la boucle
     * de dessin s'en apercevrait à chaque trame.
     */
    @Test
    fun le_nombre_de_traits_reste_borne() {
        val traits = AccordFin.traits(0L, 0.0001, 4000f, pas = 1L)
        assertTrue(traits.size <= 400)
    }

    // ----------------------------------------------------------------- calage

    /**
     * Le cœur du calage, et la raison pour laquelle le recentrage existant ne
     * pouvait pas servir tel quel.
     *
     * Ce dernier pose le centre de gravité sur zéro, ce qui convient à une
     * porteuse. Posée sur zéro, une voix en bande latérale se retrouve à cheval
     * sur la fréquence d'accord : on n'entend qu'une moitié de chaque syllabe.
     * Il faut la poser vers 1 500 hertz, et du bon côté selon la latérale.
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
     * Un correspondant dont la voix est mesurée 800 Hz au-dessus de l'accord
     * est écouté trop bas : sa voix sort à 800 Hz, sourde. Il faut descendre
     * l'accord de 700 Hz pour qu'elle remonte à 1 500.
     */
    @Test
    fun l_accord_vise_place_la_voix_au_bon_endroit() {
        assertEquals(-700L, AccordFin.accordVise(800.0, 1_500))
        assertEquals(500L, AccordFin.accordVise(2_000.0, 1_500))
        // En latérale inférieure, le spectre est retourné et le signe suit.
        assertEquals(700L, AccordFin.accordVise(-800.0, -1_500))
        // Sans cible, on retombe exactement sur l'ancien recentrage.
        assertEquals(800L, AccordFin.accordVise(800.0, 0))
    }

    /**
     * La recherche est étroite, et c'est délibéré : on cale sur le
     * correspondant qu'on écoute déjà. Ratisser les ±25 kHz du recentrage
     * automatique ferait sauter sur la station voisine plus forte au premier
     * silence.
     */
    @Test
    fun la_recherche_du_calage_tient_dans_un_canal() {
        assertTrue(AccordFin.RECHERCHE_VOIX_HZ <= 4_000.0)
        assertTrue(AccordFin.RECHERCHE_VOIX_HZ >= 2_400.0)
    }

    // ------------------------------------------------------------ envoi en aval

    /**
     * Sous le seuil du planificateur Doppler, la consigne serait ignorée plus
     * bas de toute façon : l'envoyer ne ferait que du trafic dans la boucle
     * chaude. Le vernier doit donc filtrer au même seuil, et pas à un autre.
     */
    @Test
    fun un_deplacement_sous_le_seuil_ne_part_pas() {
        assertFalse(AccordFin.vautLaPeine(0L))
        assertFalse(AccordFin.vautLaPeine(DopplerTuner.DEADBAND_HZ - 1))
        assertTrue(AccordFin.vautLaPeine(DopplerTuner.DEADBAND_HZ))
        assertTrue(AccordFin.vautLaPeine(-DopplerTuner.DEADBAND_HZ))
    }

    /** Les rapports proposés doivent couvrir les trois gestes réels. */
    @Test
    fun les_rapports_proposes_vont_du_hertz_au_kilohertz() {
        assertEquals(3, AccordFin.RAPPORTS.size)
        assertEquals(AccordFin.RAPPORTS.sorted(), AccordFin.RAPPORTS)
        // Le plus fin doit permettre de se poser au hertz près sur un
        // centimètre de doigt ; le plus grossier de traverser un bout de bande.
        assertTrue(AccordFin.RAPPORTS.first() <= 50)
        assertTrue(AccordFin.RAPPORTS.last() >= 1_000)
    }

    // ------------------------------------------------ ce que le vernier pilote

    /**
     * Le cœur du correctif. Deux accords s'ignoraient : le canal du
     * transpondeur — celui qui produit les lignes RX et TX et qu'on reporte au
     * poste — et l'accord de la clé SDR. Le vernier ne touchait que le second.
     */
    @Test
    fun sur_un_transpondeur_le_vernier_deplace_le_canal() {
        assertEquals(AccordFin.Cible.CANAL,
            AccordFin.cibleDuVernier(true, 145_855_000L, 145_875_000L))
    }

    /**
     * Sur un canal fixe — de la FM — il n'y a pas de bande à parcourir : rien
     * d'autre à déplacer que l'accord de la clé.
     */
    @Test
    fun sur_un_canal_fixe_le_vernier_deplace_la_cle() {
        assertEquals(AccordFin.Cible.CLE,
            AccordFin.cibleDuVernier(false, 145_800_000L, 145_800_000L))
        // Un transpondeur annoncé sans bornes, ou de largeur nulle, n'en est
        // pas un du point de vue du geste.
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
     * On borne, on ne boucle pas. Un vernier qui repasserait de l'autre côté
     * après le bord ferait sauter d'un bout à l'autre du transpondeur au milieu
     * d'un contact — et le lancer, qui parcourt plusieurs kilohertz d'un geste,
     * rendrait l'accident fréquent.
     */
    @Test
    fun le_canal_s_arrete_aux_bords_de_la_bande() {
        assertEquals(145_875_000L,
            AccordFin.nouveauCanal(145_874_000L, 50_000L, 145_855_000L, 145_875_000L))
        assertEquals(145_855_000L,
            AccordFin.nouveauCanal(145_856_000L, -50_000L, 145_855_000L, 145_875_000L))
    }

    /** Des bornes données à l'envers ne doivent pas coincer le vernier. */
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
