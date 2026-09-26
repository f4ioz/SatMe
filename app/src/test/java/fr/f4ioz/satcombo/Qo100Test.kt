/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.Qo100
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QO-100 : le plan de fréquences et le pointage de la parabole.
 *
 * Deux choses se jouent ici, et aucune des deux ne se vérifie à l'œil sur
 * l'écran du téléphone.
 *
 * D'abord le plan de fréquences. Le décalage montée/descente de 8 089,5 MHz
 * est une donnée du transpondeur, pas un réglage : s'il était faux d'un
 * mégahertz, l'application émettrait poliment à côté de la bande amateur, et
 * l'opérateur n'aurait aucun moyen de s'en apercevoir depuis chez lui. On
 * l'épingle donc sur les bords du transpondeur étroit, qui sont publiés.
 *
 * Ensuite le pointage. Une parabole de 60 cm en bande X a une ouverture à
 * mi-puissance de l'ordre de 3° : un degré d'erreur se paie en décibels, deux
 * degrés se paient en « je n'entends rien ». Les valeurs de référence
 * ci-dessous ont été calculées séparément, hors de ce code, et recoupées avec
 * les chiffres publiés par les stations françaises actives sur le satellite —
 * Paris à 150° / 30° est la valeur que tout le monde cite. Elles sont
 * volontairement épinglées au centième de degré : ce n'est pas une prétention
 * de précision, c'est un détecteur de changement. Si quelqu'un touche à la
 * géométrie un jour, l'essai le dira tout de suite.
 *
 * On y ajoute deux points hors de France — Le Cap et Doha — parce que la
 * formule fermée des manuels change de branche sans prévenir dans l'hémisphère
 * sud et à l'est du satellite, et que c'est exactement la raison pour laquelle
 * le calcul est fait par vecteurs.
 */
class Qo100Test {

    /** Un centième de degré : bien en dessous de ce qu'on sait pointer. */
    private val tol = 0.01

    private fun verifie(
        nom: String, lat: Double, lon: Double,
        az: Double, el: Double, skew: Double,
    ) {
        val p = Qo100.pointage(lat, lon)
        assertEquals("$nom azimut", az, p.azDeg, tol)
        assertEquals("$nom élévation", el, p.elDeg, tol)
        assertEquals("$nom skew", skew, p.skewDeg, tol)
        assertTrue("$nom devrait voir le satellite", p.visible)
    }

    // --- Le pointage sur des villes de référence -------------------------

    @Test
    fun paris_pointe_au_sud_sud_est_a_trente_degres() {
        verifie("Paris", 48.8566, 2.3522, az = 149.942, el = 29.533, skew = 19.242)
    }

    @Test
    fun les_quatre_coins_de_la_france_donnent_les_valeurs_publiees() {
        verifie("Brest", 48.39, -4.49, az = 141.890, el = 27.216, skew = 24.194)
        verifie("Nice", 43.70, 7.27, az = 153.990, el = 36.234, skew = 18.484)
        verifie("Marseille", 43.30, 5.37, az = 151.364, el = 35.929, skew = 20.413)
        verifie("Toulouse", 43.60, 1.44, az = 146.591, el = 34.036, skew = 23.500)
    }

    /**
     * Depuis l'hémisphère sud le satellite est au *nord*, et le skew change
     * de signe. C'est le cas qui fait mentir la formule fermée.
     */
    @Test
    fun depuis_le_cap_le_satellite_est_au_nord() {
        verifie("Le Cap", -33.92, 18.42, az = 13.240, el = 49.753, skew = -10.956)
    }

    /**
     * Doha est à l'est du satellite : la parabole se tourne vers le
     * sud-ouest, azimut au-delà de 180°. L'autre moitié du piège.
     */
    @Test
    fun depuis_doha_le_satellite_est_au_sud_ouest() {
        verifie("Doha", 25.29, 51.53, az = 228.317, el = 48.899, skew = -42.474)
    }

    /**
     * Pile sous le satellite, la parabole regarde le zénith. L'azimut n'a
     * alors plus de sens — toutes les directions se valent — donc on ne
     * l'épingle pas ; seule l'élévation est vérifiable.
     */
    @Test
    fun sous_le_satellite_la_parabole_regarde_en_haut() {
        val p = Qo100.pointage(0.0, Qo100.LONGITUDE_DEG)
        assertEquals(90.0, p.elDeg, tol)
        assertEquals(0.0, p.skewDeg, tol)
        assertTrue(p.visible)
    }

    /**
     * Aux antipodes du satellite la Terre fait écran : l'élévation est
     * négative et [Qo100.Pointage.visible] doit le dire. Un écran qui
     * afficherait « azimut 42° » à quelqu'un qui ne peut pas le voir serait
     * pire que rien.
     */
    @Test
    fun aux_antipodes_le_satellite_est_sous_l_horizon() {
        val p = Qo100.pointage(0.0, Qo100.LONGITUDE_DEG - 180.0)
        assertTrue("élévation attendue négative, obtenue ${p.elDeg}", p.elDeg < 0.0)
        assertFalse(p.visible)
    }

    /**
     * Le long d'un même méridien, plus on monte vers le nord, plus le
     * satellite s'abaisse. Une propriété que la géométrie garantit et qu'un
     * signe inversé quelque part casserait immédiatement.
     */
    @Test
    fun l_elevation_baisse_quand_on_remonte_vers_le_nord() {
        val elevations = (0..70 step 10).map { Qo100.pointage(it.toDouble(), 25.9).elDeg }
        elevations.zipWithNext().forEach { (bas, haut) ->
            assertTrue("l'élévation devrait décroître : $bas puis $haut", haut < bas)
        }
    }

    // --- Le plan de fréquences ------------------------------------------

    /**
     * Le bord bas du transpondeur étroit concorde exactement avec le
     * décalage : c'est la meilleure preuve que la constante de 8 089,5 MHz
     * est juste.
     */
    @Test
    fun le_decalage_relie_exactement_le_bord_bas_du_transpondeur_etroit() {
        assertEquals(Qo100.NB.descenteBasHz, Qo100.descenteDepuisMontee(Qo100.NB.monteeBasHz))
        assertEquals(Qo100.NB.monteeBasHz, Qo100.monteeDepuisDescente(Qo100.NB.descenteBasHz))
    }

    /**
     * Le bord haut, lui, ne concorde pas : le tableau publié par AMSAT-DL
     * annonce une montée à 2 400,490 et une descente à 10 489,997, soit 7 kHz
     * d'écart avec le décalage.
     *
     * Longtemps cet essai s'appelait « le bord haut publié est incohérent ».
     * Il ne l'était pas : ces 7 kHz sont la balise multimédia, et l'essai
     * suivant le montre. Les deux bords sont donc justes, ils ne parlent
     * simplement pas de la même chose — l'un de la dernière fréquence sur
     * laquelle on émet, l'autre de la dernière sur laquelle on reçoit.
     *
     * On épingle quand même l'écart : pour que personne ne « corrige » un jour
     * l'un des deux bords en croyant réparer un bogue, et parce que sept
     * kilohertz suffisent à poser une porteuse sur une balise.
     */
    @Test
    fun les_sept_kilohertz_du_bord_haut_sont_la_balise_multimedia() {
        val ecart = Qo100.NB.descenteHautHz - Qo100.descenteDepuisMontee(Qo100.NB.monteeHautHz)
        assertEquals(7_000L, ecart)

        // Ces 7 kHz-là, exactement, sont une balise : même largeur, mêmes bornes.
        val multi = Qo100.SEGMENTS.first { it.cle == "balise_multimedia" }
        assertEquals(7_000L, multi.largeurHz)
        assertEquals(Qo100.descenteDepuisMontee(Qo100.NB.monteeHautHz), multi.basHz)
        assertEquals(Qo100.NB.descenteHautHz, multi.hautHz)
        assertEquals(Qo100.Usage.BALISE, multi.usage)

        // Et le bord de montée publié est bien la dernière fréquence émissible.
        assertEquals(Qo100.DERNIERE_DESCENTE_EMISSIBLE_HZ,
            Qo100.descenteDepuisMontee(Qo100.NB.monteeHautHz))

        // La montée calculée depuis le bord haut de la descente, elle, sort du
        // transpondeur : c'est le calcul qu'il ne faut jamais faire.
        val monteeCalculee = Qo100.monteeDepuisDescente(Qo100.NB.descenteHautHz)
        assertTrue("la montée déduite sort du transpondeur publié",
            monteeCalculee > Qo100.NB.monteeHautHz)
    }

    // --- Le plan de bande, segment par segment ---------------------------

    /**
     * La propriété qui porte toute la réglette : les douze segments se
     * recollent bout à bout, de 10 489,500 à 10 490,000, sans un hertz de trou
     * ni un hertz de recouvrement.
     *
     * Un trou, et le curseur traverserait une zone sans nom ni couleur, où
     * [Qo100.emissionAutorisee] répondrait « non » sans raison affichable. Un
     * recouvrement, et l'ordre de la liste déciderait silencieusement du
     * libellé. Aucun des deux ne se verrait à l'œil sur l'écran.
     */
    @Test
    fun les_douze_segments_couvrent_la_reglette_sans_trou_ni_recouvrement() {
        assertEquals(12, Qo100.SEGMENTS.size)
        assertEquals(Qo100.BALISE_BASSE_HZ, Qo100.REGLETTE_BAS_HZ)
        assertEquals(Qo100.BALISE_HAUTE_HZ, Qo100.REGLETTE_HAUT_HZ)
        assertEquals(500_000L, Qo100.REGLETTE_HAUT_HZ - Qo100.REGLETTE_BAS_HZ)

        Qo100.SEGMENTS.zipWithNext().forEach { (a, b) ->
            assertEquals("« ${a.cle} » et « ${b.cle} » ne se touchent pas", a.hautHz, b.basHz)
        }
        Qo100.SEGMENTS.forEach {
            assertTrue("le segment « ${it.cle} » est vide ou à l'envers", it.largeurHz > 0)
        }
        // Les clés servent à composer une clé de traduction : elles doivent
        // être uniques, sinon deux segments partagent un libellé.
        assertEquals(12, Qo100.SEGMENTS.map { it.cle }.toSet().size)
    }

    /**
     * Le balayage au kilohertz : chaque fréquence de la réglette appartient à
     * exactement un segment, et [Qo100.segment] rend celui-là.
     */
    @Test
    fun chaque_frequence_de_la_reglette_appartient_a_un_seul_segment() {
        var hz = Qo100.REGLETTE_BAS_HZ
        while (hz <= Qo100.REGLETTE_HAUT_HZ) {
            val trouves = Qo100.SEGMENTS.filter { hz in it }
            // La toute dernière fréquence est la borne haute fermée à la main.
            val attendu = if (hz == Qo100.REGLETTE_HAUT_HZ) 0 else 1
            assertEquals("$hz appartient à ${trouves.size} segments", attendu, trouves.size)
            assertTrue("aucun segment rendu pour $hz", Qo100.segment(hz) != null)
            hz += 500L
        }
    }

    @Test
    fun hors_de_la_reglette_il_n_y_a_pas_de_segment() {
        assertNull(Qo100.segment(Qo100.REGLETTE_BAS_HZ - 1))
        assertNull(Qo100.segment(Qo100.REGLETTE_HAUT_HZ + 1))
        assertNull(Qo100.segment(145_800_000L))
        // Et donc pas d'émission non plus : la réponse par défaut est « non ».
        assertFalse(Qo100.emissionAutorisee(Qo100.REGLETTE_BAS_HZ - 1))
        assertFalse(Qo100.emissionAutorisee(2_400_100_000L))
    }

    /**
     * Les quatre balises, et le fait qu'on n'émet sur aucune des quatre.
     *
     * C'est la raison d'être de tout ce tableau : sans lui, l'application
     * laisserait poser une porteuse sur la balise médiane, qui est justement
     * celle que tout le monde utilise pour se caler.
     */
    @Test
    fun on_n_emet_sur_aucune_des_quatre_balises() {
        val balises = Qo100.SEGMENTS.filter { it.usage == Qo100.Usage.BALISE }
        assertEquals(4, balises.size)
        assertEquals(listOf("balise_basse", "balise_mediane", "balise_multimedia", "balise_haute"),
            balises.map { it.cle })
        balises.forEach {
            assertFalse("« ${it.cle} » ne devrait pas permettre l'émission", it.emissionPermise)
        }
        listOf(Qo100.BALISE_BASSE_HZ, Qo100.BALISE_MEDIANE_HZ, Qo100.BALISE_HAUTE_HZ).forEach {
            assertFalse("émission autorisée sur la balise $it", Qo100.emissionAutorisee(it))
        }
        // Et la multimédia, qui n'a pas de constante à elle.
        assertFalse(Qo100.emissionAutorisee(10_489_993_500L))
    }

    /**
     * La frontière du haut, à un kilohertz près. C'est le seul endroit du
     * fichier où une erreur d'un kilohertz est un brouillage.
     */
    @Test
    fun la_derniere_frequence_emissible_est_dix_mille_quatre_cent_quatre_vingt_neuf_neuf_cent_quatre_vingt_dix() {
        assertEquals(10_489_990_000L, Qo100.DERNIERE_DESCENTE_EMISSIBLE_HZ)
        assertTrue(Qo100.emissionAutorisee(Qo100.DERNIERE_DESCENTE_EMISSIBLE_HZ - 1))
        assertFalse(Qo100.emissionAutorisee(Qo100.DERNIERE_DESCENTE_EMISSIBLE_HZ))
        assertFalse(Qo100.emissionAutorisee(Qo100.NB.descenteHautHz))
        // La montée correspondante est exactement le bord haut publié.
        assertEquals(Qo100.NB.monteeHautHz,
            Qo100.monteeDepuisDescente(Qo100.DERNIERE_DESCENTE_EMISSIBLE_HZ))
    }

    /**
     * Le bas de la bande passante utile est le premier segment où l'on émet.
     * Autrement dit : [Qo100.NB] et [Qo100.SEGMENTS] racontent la même bande.
     */
    @Test
    fun les_bornes_du_transpondeur_etroit_concordent_avec_les_segments() {
        val premierEmissible = Qo100.SEGMENTS.first { it.emissionPermise }
        assertEquals(Qo100.NB.descenteBasHz, premierEmissible.basHz)
        assertEquals("cw", premierEmissible.cle)
        val dernierEmissible = Qo100.SEGMENTS.last { it.emissionPermise }
        assertEquals(Qo100.DERNIERE_DESCENTE_EMISSIBLE_HZ, dernierEmissible.hautHz)
    }

    /**
     * Les largeurs maximales publiées. Le segment numérique étroit à 500 Hz
     * est le seul du plan à ne pas être à 2,7 kHz, et c'est aussi celui où une
     * BLU posée par erreur écraserait le plus de monde.
     */
    @Test
    fun les_largeurs_maximales_suivent_le_plan_publie() {
        fun seg(cle: String) = Qo100.SEGMENTS.first { it.cle == cle }
        assertEquals(500, seg("num_etroit").largeurMaxHz)
        assertEquals(2_700, seg("num_large").largeurMaxHz)
        assertEquals(2_700, seg("ssb_bas").largeurMaxHz)
        assertEquals(2_700, seg("ssb_haut").largeurMaxHz)
        assertEquals(2_700, seg("mixte").largeurMaxHz)
        // La CW n'a pas de largeur au plan : l'usage tient lieu de règle.
        assertEquals(0, seg("cw").largeurMaxHz)
    }

    /**
     * Diffusion et urgence : deux tranches de 7,5 kHz autour de deux
     * fréquences qui se retiennent, 10 489,855 et 10 489,860. La frontière
     * tombe donc sur un demi-kilohertz, ce qui a tout l'air d'une coquille et
     * n'en est pas une.
     */
    @Test
    fun la_diffusion_et_l_urgence_font_sept_kilohertz_et_demi_chacune() {
        val diff = Qo100.SEGMENTS.first { it.cle == "diffusion" }
        val urg = Qo100.SEGMENTS.first { it.cle == "urgence" }
        assertEquals(7_500L, diff.largeurHz)
        assertEquals(7_500L, urg.largeurHz)
        assertEquals(10_489_855_000L, diff.repereHz!!)
        assertEquals(10_489_860_000L, urg.repereHz!!)
        assertEquals(10_489_857_500L, diff.hautHz)
        // On y émet : ce sont des usages réservés, pas des interdictions.
        assertTrue(diff.emissionPermise)
        assertTrue(urg.emissionPermise)
    }

    /**
     * Le repère d'un segment, quand il existe, tombe dans ce segment. Sinon
     * l'écran dessinerait un trait de balise à côté de sa balise.
     */
    @Test
    fun les_reperes_tombent_dans_leur_propre_segment() {
        Qo100.SEGMENTS.forEach { s ->
            val r = s.repereHz ?: return@forEach
            assertTrue("le repère $r sort du segment « ${s.cle} »",
                r in s || r == s.hautHz)
        }
        // Six segments portent un repère : les quatre balises, plus la
        // diffusion et l'urgence. Le compte est là pour qu'on s'en aperçoive
        // si l'un d'eux disparaît.
        assertEquals(6, Qo100.SEGMENTS.count { it.repereHz != null })
    }

    @Test
    fun le_decalage_relie_aussi_les_bords_du_transpondeur_large() {
        assertEquals(Qo100.WB.descenteBasHz, Qo100.descenteDepuisMontee(Qo100.WB.monteeBasHz))
        assertEquals(Qo100.WB.descenteHautHz, Qo100.descenteDepuisMontee(Qo100.WB.monteeHautHz))
    }

    /**
     * Sans inversion, monter d'un kilohertz descend d'un kilohertz. C'est ce
     * qui distingue QO-100 de la plupart des transpondeurs linéaires, et
     * l'oublier retournerait la bande.
     */
    @Test
    fun le_transpondeur_n_inverse_pas_le_spectre() {
        val a = Qo100.descenteDepuisMontee(2_400_100_000L)
        val b = Qo100.descenteDepuisMontee(2_400_101_000L)
        assertEquals(1_000L, b - a)
    }

    @Test
    fun l_aller_retour_montee_descente_ne_perd_rien() {
        listOf(2_400_005_000L, 2_400_250_000L, 2_400_490_000L).forEach { m ->
            assertEquals(m, Qo100.monteeDepuisDescente(Qo100.descenteDepuisMontee(m)))
        }
    }

    /** Les trois balises sont dans le transpondeur étroit, ou juste à son bord. */
    @Test
    fun les_balises_encadrent_le_transpondeur_etroit() {
        assertTrue(Qo100.BALISE_BASSE_HZ < Qo100.NB.descenteBasHz)
        assertTrue(Qo100.NB.contientDescente(Qo100.BALISE_MEDIANE_HZ))
        assertTrue(Qo100.BALISE_HAUTE_HZ > Qo100.NB.descenteHautHz)
        // 250 kHz entre chacune : le repère qu'on cherche à l'oreille.
        assertEquals(250_000L, Qo100.BALISE_MEDIANE_HZ - Qo100.BALISE_BASSE_HZ)
        assertEquals(250_000L, Qo100.BALISE_HAUTE_HZ - Qo100.BALISE_MEDIANE_HZ)
    }

    /**
     * La balise médiane tombe pile au milieu du transpondeur étroit, à un
     * kilohertz près. C'est ce qui en fait un point de départ raisonnable
     * quand on ouvre l'écran sans savoir où aller.
     */
    @Test
    fun le_centre_du_transpondeur_etroit_est_sur_la_balise_mediane() {
        assertEquals(Qo100.BALISE_MEDIANE_HZ.toDouble(),
            Qo100.NB.centreDescenteHz.toDouble(), 1_000.0)
    }

    @Test
    fun le_transpondeur_etroit_fait_bien_492_kilohertz() {
        assertEquals(492_000L, Qo100.NB.largeurHz)
        assertEquals(8_000_000L, Qo100.WB.largeurHz)
    }

    @Test
    fun la_bride_ne_laisse_jamais_sortir_du_transpondeur() {
        assertEquals(Qo100.NB.descenteBasHz, Qo100.NB.brideDescente(10_000_000_000L))
        assertEquals(Qo100.NB.descenteHautHz, Qo100.NB.brideDescente(11_000_000_000L))
        assertEquals(10_489_750_000L, Qo100.NB.brideDescente(10_489_750_000L))
    }

    @Test
    fun les_deux_transpondeurs_ne_se_chevauchent_pas() {
        assertFalse(Qo100.NB.contientDescente(Qo100.WB.descenteBasHz))
        assertFalse(Qo100.WB.contientDescente(Qo100.NB.descenteHautHz))
        assertEquals(2, Qo100.TRANSPONDEURS.size)
        assertEquals(setOf("nb", "wb"), Qo100.TRANSPONDEURS.map { it.cle }.toSet())
    }

    /**
     * Le NORAD est la clé sous laquelle se range le décalage d'étalonnage :
     * s'il changeait, le calage fait sur la balise se perdrait sans bruit.
     */
    @Test
    fun le_numero_de_catalogue_est_celui_d_es_hail_2() {
        assertEquals(43700, Qo100.NORAD)
        assertEquals(25.9, Qo100.LONGITUDE_DEG, 1e-9)
        assertEquals(8_089_500_000L, Qo100.DECALAGE_HZ)
    }
}
