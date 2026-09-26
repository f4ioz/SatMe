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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le calcul de visée d'un rotor, au banc, sans mât et sans câble.
 *
 * Onze essais, et tous portent sur la même idée : un pilote de rotor est la
 * seule partie de SatMe qui déplace physiquement une antenne. Une erreur de
 * signe n'y donne pas un affichage bizarre, elle donne trois mètres d'aluminium
 * qui partent dans le mauvais sens pendant une minute entière — c'est le temps
 * qu'il faut à un G-5500 pour faire un demi-tour.
 *
 * Le plus parlant est celui du passage au nord : la même trajectoire, le même
 * satellite, les mêmes six cent une positions, et un mât qui parcourt quarante
 * degrés ou quatre cents selon la seule façon dont on écrit un azimut.
 */
class RotorMathTest {

    private val limits90 = RotorMath.Limits(azMaxDeg = 450.0, elMaxDeg = 90.0, deadbandDeg = 2.0)
    private val limits180 = RotorMath.Limits(azMaxDeg = 450.0, elMaxDeg = 180.0, deadbandDeg = 2.0)

    @Test
    fun norm360_ramene_tout_dans_le_tour() {
        assertEquals(350.0, RotorMath.norm360(-10.0), 1e-9)
        assertEquals(10.0, RotorMath.norm360(370.0), 1e-9)
        assertEquals(0.0, RotorMath.norm360(360.0), 1e-9)
        assertEquals(0.0, RotorMath.norm360(0.0), 1e-9)
        assertEquals(180.0, RotorMath.norm360(-180.0), 1e-9)
    }

    @Test
    fun le_recouvrement_choisit_la_representation_la_plus_proche() {
        // Un azimut de 10° s'écrit 10 ou 370 sur un mât qui tourne sur 450 : les
        // deux visent le même point du ciel, mais pas depuis le même endroit.
        assertEquals(370.0, RotorMath.unwrapNear(10.0, 350.0, 450.0)!!, 1e-9)
        assertEquals(10.0, RotorMath.unwrapNear(10.0, 20.0, 450.0)!!, 1e-9)
        // Sur un mât qui ne fait qu'un tour, il n'y a pas de choix à faire.
        assertEquals(10.0, RotorMath.unwrapNear(10.0, 350.0, 360.0)!!, 1e-9)
        assertEquals(350.0, RotorMath.unwrapNear(350.0, 400.0, 450.0)!!, 1e-9)
    }

    @Test
    fun un_azimut_hors_course_ne_rend_rien() {
        // 270° n'existe pas sur un mât bridé à 180 : aucune écriture ne le
        // rattrape, et il faut le dire plutôt que de rendre le plus proche.
        assertNull(RotorMath.unwrapNear(270.0, 0.0, 180.0))
        assertNotNull(RotorMath.unwrapNear(170.0, 0.0, 180.0))
    }

    @Test
    fun un_passage_au_nord_coute_dix_fois_moins_cher_avec_le_recouvrement() {
        // Un passage qui traverse le nord : l'azimut va de 340° à 380°, soit
        // quarante degrés de mât si on le laisse continuer tout droit. Six cent
        // une positions, c'est une seconde d'écart entre deux — la cadence
        // réelle de la boucle de poursuite.
        val n = 601
        var avecRecouvrement = 0.0
        var sansRecouvrement = 0.0
        var courantLarge = RotorPos(340.0, 30.0)
        var courantEtroit = RotorPos(340.0, 30.0)
        val large = RotorMath.Limits(azMaxDeg = 450.0, elMaxDeg = 90.0, deadbandDeg = 0.0)
        val etroit = RotorMath.Limits(azMaxDeg = 360.0, elMaxDeg = 90.0, deadbandDeg = 0.0)

        for (i in 0 until n) {
            val az = 340.0 + i * (40.0 / (n - 1))
            val a = RotorMath.aim(az, 30.0, courantLarge, large)!!
            avecRecouvrement += RotorMath.travel(courantLarge.azDeg, a.azDeg)
            courantLarge = RotorPos(a.azDeg, a.elDeg)

            val b = RotorMath.aim(az, 30.0, courantEtroit, etroit)!!
            sansRecouvrement += RotorMath.travel(courantEtroit.azDeg, b.azDeg)
            courantEtroit = RotorPos(b.azDeg, b.elDeg)
        }

        // Tout droit : exactement les quarante degrés de la trajectoire.
        assertEquals(40.0, avecRecouvrement, 1e-6)
        // Et sans recouvrement, un demi-tour complet en plein milieu du passage,
        // au moment précis où le satellite est le plus haut.
        assertTrue("le mât bridé n'a pas fait son demi-tour : $sansRecouvrement",
            sansRecouvrement > 350.0)
        assertTrue("rapport insuffisant : $sansRecouvrement contre $avecRecouvrement",
            sansRecouvrement / avecRecouvrement > 9.0)
        assertEquals(380.0, courantLarge.azDeg, 1e-6)
    }

    @Test
    fun un_rotor_a_quatre_vingt_dix_degres_ne_se_retourne_pas() {
        // La branche retournée demande un rotor d'élévation qui monte à 180°.
        // Sur les autres, elle n'existe tout simplement pas — et l'on ne doit
        // surtout pas la proposer « au cas où ».
        val a = RotorMath.aim(30.0, 85.0, RotorPos(200.0, 45.0), limits90)!!
        assertTrue(!a.flipped)
        // Même en venant d'un état retourné, il n'y a rien à tenir.
        val b = RotorMath.aim(30.0, 85.0, RotorPos(200.0, 45.0), limits90, wasFlipped = true)!!
        assertTrue(!b.flipped)
    }

    @Test
    fun le_retournement_vise_le_meme_point_du_ciel() {
        // Azimut 180 / élévation 80, c'est exactement azimut 0 / élévation 100.
        // Le mât est déjà au second : il n'a rigoureusement rien à faire.
        val a = RotorMath.aim(180.0, 80.0, RotorPos(0.0, 100.0), limits180, wasFlipped = true)!!
        assertTrue(a.flipped)
        assertEquals(0.0, a.azDeg, 1e-9)
        assertEquals(100.0, a.elDeg, 1e-9)
        assertEquals(0.0, RotorMath.cost(a, RotorPos(0.0, 100.0)), 1e-9)
    }

    @Test
    fun on_ne_se_retourne_pas_pour_economiser_vingt_degres() {
        // Sans hystérésis, un satellite qui frôle le zénith fait basculer le
        // choix d'une seconde à l'autre : le mât passe le meilleur moment du
        // passage à faire des demi-tours. Vingt degrés d'économie ne valent pas
        // cela.
        val courant = RotorPos(130.0, 90.0)
        val a = RotorMath.aim(30.0, 85.0, courant, limits180, wasFlipped = false)!!
        assertTrue("il s'est retourné pour vingt degrés", !a.flipped)
        assertEquals(30.0, a.azDeg, 1e-9)
    }

    @Test
    fun on_se_retourne_quand_l_economie_depasse_l_hysteresis() {
        // Cent soixante-dix degrés d'azimut économisés, soit une demi-minute de
        // mât : là, cela vaut la peine.
        val courant = RotorPos(200.0, 95.0)
        val a = RotorMath.aim(30.0, 85.0, courant, limits180, wasFlipped = false)!!
        assertTrue("il aurait dû se retourner", a.flipped)
        assertEquals(210.0, a.azDeg, 1e-9)
        assertEquals(95.0, a.elDeg, 1e-9)
        assertTrue(RotorMath.cost(a, courant) < RotorMath.FLIP_HYSTERESIS_DEG)
    }

    @Test
    fun la_zone_morte_laisse_le_mat_tranquille() {
        // Le lobe d'une antenne de satellite se compte en dizaines de degrés :
        // un degré d'écart ne se voit sur aucun récepteur, mais chaque départ
        // use un relais.
        val courant = RotorPos(100.0, 45.0)
        assertTrue(!RotorMath.needsMove(RotorMath.Aim(101.0, 45.0, false), courant, 2.0))
        assertTrue(!RotorMath.needsMove(RotorMath.Aim(100.0, 46.5, false), courant, 2.0))
        assertTrue(RotorMath.needsMove(RotorMath.Aim(103.0, 45.0, false), courant, 2.0))
        assertTrue(RotorMath.needsMove(RotorMath.Aim(100.0, 48.0, false), courant, 2.0))
    }

    @Test
    fun une_consigne_hors_course_rend_null_plutot_que_d_etre_bornee() {
        // Le point le plus important du fichier. Borner silencieusement une
        // consigne impossible ferait tourner le mât vers un endroit où le
        // satellite n'est pas — ce qui est pire que ne rien faire, parce que
        // rien ne le dirait.
        assertNull(RotorMath.aim(30.0, 95.0, RotorPos(0.0, 0.0), limits90))
        val bride = RotorMath.Limits(azMaxDeg = 180.0, elMaxDeg = 90.0, deadbandDeg = 2.0)
        assertNull(RotorMath.aim(270.0, 20.0, RotorPos(0.0, 0.0), bride))
        // Et ce qui tient, tient.
        assertNotNull(RotorMath.aim(170.0, 20.0, RotorPos(0.0, 0.0), bride))
    }

    @Test
    fun le_mat_part_attendre_le_satellite_juste_avant_le_lever() {
        // « Il faut qu'il soit positionné avant le début du passage, x minutes
        // en paramètre. » La règle n'est vraie que dans la fenêtre : avant, on
        // laisse le mât au garage ; après le lever, c'est le suivi normal qui
        // prend la main, et le pré-pointage n'a plus rien à dire.
        val maintenant = 1_700_000_000_000L
        val lever = maintenant + 3 * 60_000L

        // Dans la fenêtre de trois minutes : oui, y compris à la seconde près.
        assertTrue(RotorMath.prePositionDue(maintenant, lever, 3))
        assertTrue(RotorMath.prePositionDue(maintenant, maintenant + 1_000L, 3))
        assertTrue(RotorMath.prePositionDue(maintenant, maintenant + 179_000L, 3))

        // Quatre minutes avant : trop tôt, le mât reste au garage.
        assertFalse(RotorMath.prePositionDue(maintenant, maintenant + 4 * 60_000L, 3))

        // Le lever est passé : ce n'est plus du pré-pointage.
        assertFalse(RotorMath.prePositionDue(maintenant, maintenant - 1_000L, 3))
        assertFalse(RotorMath.prePositionDue(maintenant, maintenant, 3))

        // Zéro minute désactive, et aucun passage connu ne déclenche rien.
        assertFalse(RotorMath.prePositionDue(maintenant, lever, 0))
        assertFalse(RotorMath.prePositionDue(maintenant, null, 3))
    }

    @Test
    fun le_garage_refuse_une_position_impossible() {
        assertNotNull(RotorMath.park(0.0, 0.0, limits90))
        assertNotNull(RotorMath.park(450.0, 90.0, limits90))
        assertNull(RotorMath.park(500.0, 0.0, limits90))
        assertNull(RotorMath.park(0.0, -5.0, limits90))
        assertEquals(180.0, RotorMath.park(180.0, 0.0, limits90)!!.azDeg, 1e-9)
    }
}
