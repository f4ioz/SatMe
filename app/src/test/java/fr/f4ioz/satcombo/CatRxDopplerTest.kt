/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.cat.RxArbiter
import fr.f4ioz.satcombo.domain.Doppler
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Le Doppler en réception, et la question qui l'a fait naître : « la fréquence
 * poste ne bouge pas ».
 *
 * Deux choses sont vérifiées ici. D'abord l'arbitrage — savoir à chaque
 * instant qui, de l'opérateur ou du logiciel, tient le VFO de réception, et
 * surtout ne pas prendre sa propre consigne pour un geste de l'opérateur.
 * Ensuite l'arithmétique du repos figé, qui est la vraie correction : tant que
 * le repos était relu du poste à chaque tour, un VFO immobile faisait dériver
 * le repos de tout le Doppler, et l'émission partait dans le décor pendant que
 * la réception ne bougeait pas d'un hertz.
 *
 * La cadence de la boucle CAT est de cent millisecondes ; c'est le pas employé
 * ci-dessous, pour que les deux secondes de silence exigées soient les mêmes
 * ici et sur le fil.
 */
class CatRxDopplerTest {

    private val pasMs = 100L

    @Test
    fun la_molette_qui_tourne_garde_la_main() {
        val a = RxArbiter()
        var t = 0L
        var hz = 435_856_800L
        // La toute première lecture ne prouve rien : personne n'a encore rien
        // touché depuis qu'on s'est accroché au poste, et c'est nous qui menons
        // — voir `on_mene_avant_d_avoir_jamais_suivi`.
        a.observe(hz, t)
        // Puis un accord à la main : cinquante hertz par pas, pendant cinq
        // secondes. Dès le premier mouvement, la main passe à l'opérateur.
        repeat(50) {
            hz += 50L
            t += pasMs
            val geste = a.observe(hz, t)
            assertTrue("le geste n'a pas été vu à $t ms", geste)
            assertFalse("le logiciel a pris la main pendant l'accord", a.driven)
        }
    }

    /**
     * Le défaut trouvé par Olivier au premier essai avec les FT-817 : « quand
     * je sélectionne le satellite la fréquence n'est pas bien prise en compte ».
     *
     * À la connexion, le poste traîne où on l'avait laissé. L'arbitre rendait
     * alors la main dès la première lecture, et l'application **adoptait cette
     * fréquence-là comme canal** au lieu d'imposer le milieu de la bande
     * passante qu'elle venait de calculer. Elle suivait avant d'avoir jamais
     * mené — et le satellite choisi n'y changeait rien.
     */
    @Test
    fun on_mene_avant_d_avoir_jamais_suivi() {
        val a = RxArbiter()
        assertTrue("l'arbitre doit mener au départ", a.driven)
        // Une première lecture, quelle qu'elle soit, ne nous fait pas lâcher.
        assertFalse(a.observe(435_000_000L, 0L))
        assertTrue("une simple lecture n'est pas un geste", a.driven)
    }

    @Test
    fun un_changement_de_satellite_nous_rend_la_main() {
        val a = RxArbiter()
        var t = 0L
        var hz = 435_856_800L
        a.observe(hz, t)
        repeat(5) { hz += 100L; t += pasMs; a.observe(hz, t) }
        assertFalse("l'opérateur avait la main", a.driven)
        // Nouveau satellite : c'est nous qui savons où il faut être.
        a.reset()
        assertTrue("après un changement de satellite, nous menons", a.driven)
    }

    @Test
    fun deux_secondes_de_silence_et_le_logiciel_prend_le_relais() {
        val a = RxArbiter()
        var hz = 435_856_800L
        var t = 0L
        a.observe(hz, t)
        // Il faut d'abord un vrai geste pour que la main change de côté :
        // depuis le correctif, l'arbitre mène tant que rien n'a bougé.
        hz += 200L
        t += pasMs
        assertTrue(a.observe(hz, t))
        assertFalse("le geste doit rendre la main à l'opérateur", a.driven)
        val tGeste = t
        // À une seconde neuf après le geste, on attend encore : c'est ce
        // qu'Olivier a demandé, et une reprise plus tôt volerait la molette en
        // pleine recherche.
        while (t - tGeste < 1_900L) {
            t += pasMs
            a.observe(hz, t)
            assertFalse("reprise trop tôt, à ${t - tGeste} ms après le geste", a.driven)
        }
        // Passé les deux secondes, le logiciel reprend le relais.
        while (t - tGeste < 2_100L) {
            t += pasMs
            a.observe(hz, t)
        }
        assertTrue("le logiciel n'a jamais pris la main", a.driven)
    }

    @Test
    fun nos_propres_consignes_ne_passent_pas_pour_un_geste() {
        val a = RxArbiter()
        var t = 0L
        var hz = 435_856_800L
        a.observe(hz, t)
        repeat(25) { t += pasMs; a.observe(hz, t) }
        assertTrue(a.driven)

        // À partir d'ici, c'est nous qui écrivons : trente hertz par pas, soit
        // bien au-delà du seuil de détection. Sans la mémoire des consignes,
        // chacun de nos propres pas nous ferait rendre la main.
        repeat(40) {
            hz -= 30L
            a.commanded(hz)
            t += pasMs
            val geste = a.observe(hz, t)
            assertFalse("notre propre consigne a été prise pour un geste", geste)
            assertTrue("nous avons lâché la molette tout seuls", a.driven)
        }
    }

    @Test
    fun un_geste_pendant_le_pilotage_rend_la_main_aussitot() {
        val a = RxArbiter()
        var t = 0L
        val hz = 435_856_800L
        a.observe(hz, t)
        repeat(25) { t += pasMs; a.observe(hz, t) }
        a.commanded(hz - 30L)
        t += pasMs
        a.observe(hz - 30L, t)
        assertTrue(a.driven)

        // L'opérateur cherche un correspondant plus haut dans le transpondeur.
        t += pasMs
        assertTrue("le geste n'a pas été vu", a.observe(hz + 1_200L, t))
        assertFalse("le logiciel n'a pas lâché la molette", a.driven)

        // Et il la reprend seulement après le silence exigé, pas avant.
        val reprise = t + 2_000L
        while (t < reprise) {
            t += pasMs
            a.observe(hz + 1_200L, t)
            if (t < reprise) assertFalse("reprise trop tôt à $t ms", a.driven)
        }
        assertTrue("le logiciel n'a pas repris la main", a.driven)
    }

    @Test
    fun le_repos_relu_derive_alors_que_le_repos_fige_tient() {
        // Le passage d'Olivier, en chiffres : réception à 435,8568 MHz, poste
        // immobile, et le satellite qui s'éloigne de plus en plus vite.
        val posteImmobile = 435_856_800L
        val repos = Doppler.restFromDownlink(posteImmobile, 0.0)

        var pireDerive = 0L
        var pireEcritureAttendue = 0L
        for (dixiemes in 0..600) {
            // La vitesse radiale passe de zéro à six kilomètres par seconde,
            // ce qui est l'ordre de grandeur d'un passage sur 435 MHz.
            val rr = 6.0 * dixiemes / 600.0

            // L'ancienne façon : relire le repos d'un VFO qui ne bouge pas.
            val reposRelu = Doppler.restFromDownlink(posteImmobile, rr)
            pireDerive = maxOf(pireDerive, abs(reposRelu - repos))

            // La nouvelle : figer le repos et en déduire ce que le poste doit
            // afficher. C'est cette consigne-là qui manquait.
            val aEcrire = Doppler.downlink(repos, rr)
            pireEcritureAttendue = maxOf(pireEcritureAttendue, abs(aEcrire - posteImmobile))
        }

        // Le repos relu dérivait de plusieurs kilohertz : le correspondant
        // sortait du filtre, et l'émission le suivait dans son erreur.
        assertTrue("la dérive du repos relu n'est pas celle attendue : $pireDerive Hz",
            pireDerive > 8_000L)
        // Et la consigne de réception qui manquait est du même ordre : c'est
        // exactement ce que le poste aurait dû bouger et ne bougeait pas.
        assertTrue("l'écriture attendue est trop faible : $pireEcritureAttendue Hz",
            pireEcritureAttendue > 8_000L)

        // Le repos figé, lui, ne bouge par construction jamais — au hertz de
        // l'arrondi près, qui est tout ce que l'aller-retour peut coûter.
        val retour = Doppler.restFromDownlink(Doppler.downlink(repos, 4.2), 4.2)
        assertTrue("l'aller-retour a perdu le repos : $retour", abs(retour - repos) <= 1L)
    }

    @Test
    fun la_reception_et_l_emission_partent_du_meme_repos() {
        // Sur un transpondeur inversé, les deux consignes doivent venir du même
        // repos figé : c'est ce qui garantit qu'on reste en face de son
        // correspondant des deux côtés du satellite.
        val dlLow = 435_840_000L; val dlHigh = 435_860_000L
        val ulLow = 145_930_000L; val ulHigh = 145_950_000L
        val repos = 435_856_800L

        val ulRepos = Doppler.transponderUplinkRest(
            repos, dlLow, dlHigh, ulLow, ulHigh, invert = true)
        var precedentDl = Doppler.downlink(repos, 0.0)
        var precedentUl = Doppler.uplink(ulRepos, 0.0)
        for (i in 1..100) {
            val rr = 6.0 * i / 100.0
            val dl = Doppler.downlink(repos, rr)
            val ul = Doppler.uplink(ulRepos, rr)
            // En s'éloignant, on descend en réception et l'on monte en émission.
            assertTrue("la réception ne descend pas : $dl", dl < precedentDl)
            assertTrue("l'émission ne monte pas : $ul", ul > precedentUl)
            precedentDl = dl; precedentUl = ul
        }
        // Et le repos d'émission reste dans la bande du transpondeur.
        assertTrue("repos d'émission hors bande : $ulRepos",
            ulRepos in ulLow..ulHigh)
    }

    @Test
    fun en_fm_l_arrondi_du_poste_ne_passe_pas_pour_un_geste() {
        // « Pouvoir suivre en FM le VFO manuel du poste, quelquefois j'ajuste
        // via le VFO, puis reprendre le Doppler. »
        //
        // En FM, l'arbitre ne peut pas avoir le même seuil qu'en linéaire. Le
        // poste n'accorde pas au hertz : il arrondit à son pas d'accord — un
        // kilohertz sur un IC-9700 en FM — et relit donc une fréquence qui
        // n'est pas exactement celle qu'on lui a écrite. Avec les vingt hertz
        // du linéaire, chacun de nos propres ordres reviendrait déguisé en
        // geste d'opérateur, et le logiciel lâcherait la molette pour de bon.
        val a = RxArbiter(moveHz = 1_500L)
        var t = 0L
        val consigne = 145_959_400L
        val arrondi = 145_960_000L   // ce que le poste affiche vraiment

        a.commanded(consigne)
        a.observe(arrondi, t)
        repeat(25) {
            t += pasMs
            val geste = a.observe(arrondi, t)
            assertFalse("l'arrondi du poste a été pris pour un geste à $t ms", geste)
        }
        assertTrue("le logiciel n'a pas pris la main", a.driven)

        // Et il la garde pendant que le Doppler défile, arrondi compris.
        repeat(40) {
            val suivant = consigne - 100L
            a.commanded(suivant)
            t += pasMs
            assertFalse("notre propre consigne arrondie a été prise pour un geste",
                a.observe(arrondi, t))
            assertTrue("nous avons lâché la molette tout seuls", a.driven)
        }
    }

    @Test
    fun en_fm_un_changement_de_canal_rend_la_main_et_devient_le_nouveau_repos() {
        // L'autre moitié de la demande : quand Olivier change vraiment de
        // canal — cinq kilohertz au moins, jamais moins en FM — le logiciel
        // doit lâcher aussitôt, puis repartir du canal choisi et non de
        // l'ancien. C'est ce dernier point qui compte : reprendre le Doppler
        // sur l'ancienne fréquence annulerait le geste deux secondes plus tard.
        val a = RxArbiter(moveHz = 1_500L)
        var t = 0L
        val canal = 145_960_000L
        a.commanded(canal)
        a.observe(canal, t)
        repeat(25) { t += pasMs; a.observe(canal, t) }
        assertTrue(a.driven)

        val nouveau = canal + 5_000L
        t += pasMs
        assertTrue("le changement de canal n'a pas été vu", a.observe(nouveau, t))
        assertFalse("le logiciel n'a pas lâché la molette", a.driven)

        // Le repos qu'on adopte est celui du nouveau canal, corrigé du Doppler
        // du moment : c'est exactement ce que fait la boucle CAT à la lecture.
        val rr = 3.5
        val repos = Doppler.restFromDownlink(nouveau, rr)
        assertTrue("le nouveau repos est resté sur l'ancien canal",
            abs(repos - canal) > 4_000L)
        assertTrue("l'aller-retour a perdu le canal",
            abs(Doppler.downlink(repos, rr) - nouveau) <= 1L)

        // Puis le silence rend la main au logiciel, comme en linéaire.
        val reprise = t + 2_000L
        while (t < reprise) {
            t += pasMs
            a.observe(nouveau, t)
        }
        assertTrue("le Doppler n'a pas repris après le geste", a.driven)
    }
}
