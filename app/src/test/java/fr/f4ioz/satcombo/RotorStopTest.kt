/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.rotor.RotorMath
import fr.f4ioz.satcombo.rotor.RotorPos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * Les butées d'azimut, au banc, sans mât et sans câble.
 *
 * Un rotor n'est pas un plateau tournant. Il a un point mort — l'endroit où le
 * câble arrive en bout de course et où la couronne s'arrête —, et selon la
 * marque ce point mort est au nord ou au sud. Jusqu'en 18.9, SatMe l'ignorait :
 * il envoyait l'azimut vrai du satellite et laissait le contrôleur se
 * débrouiller. Cela se voyait sur les passages qui traversent la butée, et cela
 * se voyait au pire moment : un tour complet en plein milieu de la réception,
 * une demi-minute de mât qui tourne dans le vide pendant que le satellite est
 * haut et que le signal est le meilleur.
 *
 * Neuf essais, et ils tiennent tous à la même idée : **on décide avant, on ne
 * subit pas pendant**. [RotorMath.plan] choisit une fois pour toutes, à
 * l'acquisition, de quel côté de la butée on va suivre le passage ;
 * [RotorMath.follow] applique et ne rediscute rien. Commencer légèrement à côté
 * pendant vingt secondes coûte moins cher qu'un déroulage au zénith : une
 * antenne de satellite est large, un passage ne se rattrape pas.
 */
class RotorStopTest {

    /** Un mât d'un seul tour, point mort au sud : le cas d'Olivier. */
    private val sud360 = RotorMath.Limits(
        azMaxDeg = 360.0, elMaxDeg = 90.0, deadbandDeg = 2.0, azStopDeg = 180.0)

    /** Un mât à recouvrement, point mort au nord : le cas courant. */
    private val nord450 = RotorMath.Limits(
        azMaxDeg = 450.0, elMaxDeg = 90.0, deadbandDeg = 2.0, azStopDeg = 0.0)

    @Test
    fun la_butee_retient_le_mat_et_dit_de_combien() {
        // `clampAz` ne cherche pas une autre écriture du même point du ciel :
        // il constate qu'on n'ira pas plus loin. C'est toute sa différence avec
        // `unwrapNear`, et c'est pour cela que l'écart se lit en soustrayant.
        assertEquals(180.0, sud360.azMinReach, 1e-9)
        assertEquals(540.0, sud360.azMaxReach, 1e-9)
        assertEquals(540.0, RotorMath.clampAz(560.0, sud360), 1e-9)
        assertEquals(180.0, RotorMath.clampAz(100.0, sud360), 1e-9)
        assertEquals(300.0, RotorMath.clampAz(300.0, sud360), 1e-9)
        assertEquals(0.0, RotorMath.clampAz(-20.0, nord450), 1e-9)

        // Le dépliage borné, lui, tient compte de la butée : sur un mât à
        // point mort sud, un satellite plein nord se vise à 390, pas à 30.
        assertEquals(390.0, RotorMath.unwrapNear(30.0, 400.0, 360.0, 180.0)!!, 1e-9)
        // Et le dépliage libre ignore la course, exprès : c'est lui qui permet
        // de désigner un point hors d'atteinte, donc de mesurer l'écart.
        assertEquals(370.0, RotorMath.unwrapFree(10.0, 350.0), 1e-9)
        assertEquals(-20.0, RotorMath.unwrapFree(340.0, 0.0), 1e-9)
    }

    @Test
    fun le_cas_d_olivier_choisit_le_tour_de_plus() {
        // Le passage qu'il décrivait : 200°, 180°, 150°, 90°, 30°, sur un mât
        // d'un seul tour à point mort sud. Sans décalage, le mât suit les deux
        // premiers points puis se retrouve devant sa butée et doit dérouler.
        //
        // Avec un tour de plus, la trajectoire devient 560, 540, 510, 450, 390 :
        // elle tient tout entière dans la course, au prix d'un seul écart de 20°
        // au tout début — le satellite est alors à l'horizon, dans les arbres et
        // dans le bruit, et le mât l'attend en butée. C'est exactement le
        // compromis demandé : commencer un peu à côté, puis tourner vers l'est
        // et le nord sans jamais dérouler.
        val passage = listOf(
            200.0 to 0.0, 180.0 to 15.0, 150.0 to 40.0, 90.0 to 15.0, 30.0 to 0.0)

        val p = RotorMath.plan(passage, sud360)!!
        assertEquals("le décalage retenu", 360.0, p.shiftDeg, 1e-9)
        assertEquals("l'azimut de départ", 560.0, p.startAzDeg, 1e-9)
        assertEquals("la couverture", 1.0, p.coverage, 1e-9)
        assertEquals("le pire écart", 20.0, p.worstErrorDeg, 1e-9)

        // Et le suivi tient la promesse du plan : le mât part en butée, y
        // attend le satellite, puis le suit sans jamais revenir en arrière.
        var az = RotorMath.clampAz(p.startAzDeg, sud360)
        assertEquals(540.0, az, 1e-9)
        val consignes = ArrayList<Double>()
        for ((azVrai, elVrai) in passage) {
            val a = RotorMath.follow(azVrai, elVrai, RotorPos(az, 0.0), sud360)
            consignes += a.azDeg
            az = a.azDeg
        }
        assertEquals(listOf(540.0, 540.0, 510.0, 450.0, 390.0), consignes)
    }

    @Test
    fun un_passage_entier_ne_saute_jamais_de_plus_de_trente_degres() {
        // Soixante et un échantillons, une seconde entre chacun : la cadence
        // réelle de la boucle de poursuite. Le passage traverse le point mort
        // sud de part en part, ce qui est le pire cas possible.
        //
        // Ce qu'on vérifie ici n'est pas la beauté du plan mais son absence de
        // surprise : une consigne qui saute de plus de trente degrés d'une
        // seconde à l'autre est le signe qu'on a changé de branche en cours de
        // route, et c'est précisément le déroulage qu'on cherche à supprimer.
        val passage = (0..60).map { i ->
            (200.0 - 3.0 * i) to 45.0 * sin(PI * i / 60.0)
        }

        val p = RotorMath.plan(passage, sud360)!!
        assertEquals(360.0, p.shiftDeg, 1e-9)
        assertTrue("couverture insuffisante : ${p.coverage}", p.coverage > 0.95)

        var az = RotorMath.clampAz(p.startAzDeg, sud360)
        var plusGrandSaut = 0.0
        var plusGrandEcart = 0.0
        for ((azVrai, elVrai) in passage) {
            val a = RotorMath.follow(azVrai, elVrai, RotorPos(az, 0.0), sud360)
            plusGrandSaut = max(plusGrandSaut, abs(a.azDeg - az))
            plusGrandEcart = max(plusGrandEcart, a.errorDeg)
            assertTrue("consigne hors course : ${a.azDeg}",
                a.azDeg >= sud360.azMinReach - 1e-9 && a.azDeg <= sud360.azMaxReach + 1e-9)
            az = a.azDeg
        }
        assertTrue("saut de $plusGrandSaut degrés en une seconde", plusGrandSaut <= 30.0)
        assertEquals("le pire écart annoncé n'est pas celui vécu",
            p.worstErrorDeg, plusGrandEcart, 1e-9)
        // Et l'on finit bien de l'autre côté, sans avoir déroulé.
        assertEquals(380.0, az, 1e-9)
    }

    @Test
    fun la_couverture_pese_le_zenith_plus_que_l_horizon() {
        // Deux passages qui ne diffèrent que par l'endroit où sont les fortes
        // élévations : mêmes azimuts, mêmes points manqués, même somme de
        // poids, même écart maximal. Seule change la valeur de ce qu'on perd.
        //
        // Sans la pondération, les deux annonceraient « quatre points sur six »
        // et l'on choisirait au hasard. Avec elle, le plan sait faire la
        // différence entre trente secondes ratées dans les arbres et trente
        // secondes ratées au plus haut du passage.
        val azimuts = listOf(60.0, 90.0, 120.0, 150.0, 200.0, 250.0)
        val bride = RotorMath.Limits(
            azMaxDeg = 180.0, elMaxDeg = 90.0, deadbandDeg = 2.0, azStopDeg = 0.0)

        val basseFin = azimuts.zip(listOf(30.0, 50.0, 50.0, 30.0, 3.0, 3.0))
        val hauteFin = azimuts.zip(listOf(3.0, 3.0, 30.0, 50.0, 50.0, 30.0))

        val a = RotorMath.plan(basseFin, bride)!!
        val b = RotorMath.plan(hauteFin, bride)!!

        // Rigoureusement la même géométrie : même décalage, même pire écart.
        assertEquals(0.0, a.shiftDeg, 1e-9)
        assertEquals(0.0, b.shiftDeg, 1e-9)
        assertEquals(70.0, a.worstErrorDeg, 1e-9)
        assertEquals(70.0, b.worstErrorDeg, 1e-9)

        assertTrue("perdre l'horizon devrait à peine compter : ${a.coverage}",
            a.coverage > 0.95)
        assertTrue("perdre le zénith devrait coûter cher : ${b.coverage}",
            b.coverage < 0.55)
    }

    @Test
    fun a_couverture_egale_le_plus_petit_ecart_l_emporte() {
        // Un point du ciel qu'aucun décalage n'atteint : le mât ne fait qu'un
        // demi-tour, et le satellite est derrière. Les deux plans couvrent
        // autant, c'est-à-dire rien — mais l'un fait attendre le mât à vingt
        // degrés du satellite, l'autre à cent soixante. Ce n'est pas la même
        // chose sur un lobe d'antenne, et ce n'est pas la même chose non plus
        // au moment où le satellite reviendra dans la course.
        val bride = RotorMath.Limits(
            azMaxDeg = 180.0, elMaxDeg = 90.0, deadbandDeg = 2.0, azStopDeg = 0.0)
        val p = RotorMath.plan(listOf(200.0 to 45.0), bride)!!
        assertEquals(0.0, p.coverage, 1e-9)
        assertEquals(360.0, p.shiftDeg, 1e-9)
        assertEquals(200.0, p.startAzDeg, 1e-9)
        assertEquals(20.0, p.worstErrorDeg, 1e-9)
    }

    @Test
    fun derriere_la_butee_le_mat_attend_au_lieu_de_derouler() {
        // Le mât est en butée haute, à 540, c'est-à-dire plein sud. Le
        // satellite est à 200° : vingt degrés plus loin, de l'autre côté du
        // point mort. Y aller demanderait de dérouler le tour entier.
        //
        // Le pilote reste donc où il est et **dit** de combien il manque. C'est
        // le point le plus important du fichier : un écart annoncé se voit sur
        // le bandeau et s'explique ; un mât qui déroule ne s'explique pas.
        var a = RotorMath.follow(200.0, 10.0, RotorPos(540.0, 10.0), sud360)
        assertEquals(540.0, a.azDeg, 1e-9)
        assertEquals(20.0, a.errorDeg, 1e-9)

        a = RotorMath.follow(190.0, 20.0, RotorPos(540.0, 10.0), sud360)
        assertEquals(540.0, a.azDeg, 1e-9)
        assertEquals(10.0, a.errorDeg, 1e-9)

        // Et dès que le satellite repasse devant la butée, le mât repart.
        a = RotorMath.follow(175.0, 30.0, RotorPos(540.0, 20.0), sud360)
        assertEquals(535.0, a.azDeg, 1e-9)
        assertEquals(0.0, a.errorDeg, 1e-9)
    }

    @Test
    fun le_retournement_garde_son_hysteresis_meme_avec_une_butee() {
        // La branche retournée de la 18.9 survit telle quelle, avec les mêmes
        // quatre-vingt-dix degrés d'hystérésis — sinon un satellite qui frôle
        // le zénith ferait basculer le choix d'une seconde à l'autre, et le mât
        // passerait le meilleur moment du passage à se retourner.
        val gros = RotorMath.Limits(
            azMaxDeg = 450.0, elMaxDeg = 180.0, deadbandDeg = 2.0, azStopDeg = 0.0)

        // Cent soixante-dix degrés d'azimut économisés : cela vaut la peine.
        val a = RotorMath.follow(30.0, 85.0, RotorPos(200.0, 95.0), gros)
        assertTrue("il aurait dû se retourner", a.flipped)
        assertEquals(210.0, a.azDeg, 1e-9)
        assertEquals(95.0, a.elDeg, 1e-9)

        // Vingt degrés seulement : cela ne la vaut pas.
        val b = RotorMath.follow(30.0, 85.0, RotorPos(130.0, 90.0), gros)
        assertTrue("il s'est retourné pour vingt degrés", !b.flipped)
        assertEquals(30.0, b.azDeg, 1e-9)

        // Et sur un rotor qui ne monte qu'à 90°, la branche n'existe pas.
        val c = RotorMath.follow(30.0, 85.0, RotorPos(200.0, 45.0), nord450)
        assertTrue(!c.flipped)
    }

    @Test
    fun un_mat_a_recouvrement_ne_deroule_pas_sur_un_passage_au_nord() {
        // Le passage au nord de la 18.9, revu avec une butée : quarante et un
        // points de 340° à 380°. Le plan doit choisir de le suivre à 340 plutôt
        // qu'à moins vingt — les deux visent le même ciel, mais l'un est dans
        // la course et l'autre derrière le point mort.
        val passage = (0..40).map { (340.0 + it) to 30.0 }
        val p = RotorMath.plan(passage, nord450)!!
        assertEquals(360.0, p.shiftDeg, 1e-9)
        assertEquals(340.0, p.startAzDeg, 1e-9)
        assertEquals(1.0, p.coverage, 1e-9)
        assertEquals(0.0, p.worstErrorDeg, 1e-9)

        var az = RotorMath.clampAz(p.startAzDeg, nord450)
        var parcouru = 0.0
        for ((azVrai, elVrai) in passage) {
            val a = RotorMath.follow(azVrai, elVrai, RotorPos(az, 30.0), nord450)
            parcouru += RotorMath.travel(az, a.azDeg)
            az = a.azDeg
        }
        // Exactement les quarante degrés de la trajectoire, et rien de plus.
        assertEquals(40.0, parcouru, 1e-6)
        assertEquals(380.0, az, 1e-9)
    }

    @Test
    fun les_azimuts_ne_changent_d_origine_qu_au_dernier_moment() {
        // Dans toute l'application, un azimut est compté depuis le nord vrai :
        // c'est ce que dit le prédicteur, ce que montre la boussole, ce que lit
        // l'opérateur. Certains contrôleurs comptent depuis leur butée. La
        // traduction se fait donc au moment d'écrire la trame, et se défait dès
        // que la position revient — un seul réglage, et rien d'autre à changer.
        assertEquals(20.0, RotorMath.commandAz(200.0, sud360, fromStop = true), 1e-9)
        assertEquals(200.0, RotorMath.commandAz(200.0, sud360, fromStop = false), 1e-9)
        assertEquals(200.0, RotorMath.trueAz(20.0, sud360, fromStop = true), 1e-9)
        assertEquals(20.0, RotorMath.trueAz(20.0, sud360, fromStop = false), 1e-9)

        // L'aller-retour ne perd rien, sur toute la course et dans les deux
        // conventions : c'est la seule chose qui compte vraiment ici.
        for (fromStop in listOf(false, true)) {
            var az = sud360.azMinReach
            while (az <= sud360.azMaxReach + 1e-9) {
                val cmd = RotorMath.commandAz(az, sud360, fromStop)
                assertEquals(az, RotorMath.trueAz(cmd, sud360, fromStop), 1e-9)
                az += 7.5
            }
        }
        // Et sur un mât à butée nord, il n'y a rien à traduire du tout.
        assertEquals(123.0, RotorMath.commandAz(123.0, nord450, fromStop = true), 1e-9)
    }
}
