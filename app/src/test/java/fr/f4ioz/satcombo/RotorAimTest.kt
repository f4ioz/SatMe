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

/**
 * Ce que le mât montre à la boussole, et ce que la tolérance lui fait accepter.
 *
 * Deux choses sont vérifiées ici, et elles n'ont en commun que d'être invisibles
 * tant qu'on ne les a pas vues échouer sur un vrai passage.
 *
 * La première : quand un rotor tient l'antenne, c'est lui qui sait où elle
 * pointe, et la boussole doit le montrer à la place du téléphone. Encore
 * faut-il traduire ce qu'il annonce — un mât à recouvrement dit 380° là où le
 * ciel n'a que 20, et un rotor d'élévation retourné dit 100° alors qu'il vise
 * 80° dans la direction opposée. Afficher la lecture brute mettrait l'aiguille
 * à l'exact opposé de l'antenne.
 *
 * La seconde : l'écart toléré ne sert pas qu'à colorer un bandeau, il entre
 * dans le choix du plan. Un passage qui commence trois degrés derrière la butée
 * ne vaut pas un tour complet de mât pour aller le chercher — c'est la demande
 * d'Olivier, mot pour mot : « on peut démarrer seulement après la butée et
 * laisser les quelques degrés définis sans être pointé précisément ».
 */
class RotorAimTest {

    /** Le cas d'Olivier : un seul tour, point mort au sud. */
    private val sud360 = RotorMath.Limits(
        azMaxDeg = 360.0, elMaxDeg = 90.0, deadbandDeg = 2.0, azStopDeg = 180.0)

    @Test
    fun la_boussole_montre_ou_l_antenne_pointe_et_non_ce_que_le_mat_affiche() {
        // Le recouvrement d'abord : 380° de couronne, c'est 20° de ciel.
        assertEquals(RotorPos(20.0, 30.0), RotorMath.antennaAim(RotorPos(380.0, 30.0)))
        assertEquals(RotorPos(0.0, 0.0), RotorMath.antennaAim(RotorPos(720.0, 0.0)))
        // Un azimut déjà dans le tour ne bouge pas d'un iota.
        assertEquals(RotorPos(215.0, 45.0), RotorMath.antennaAim(RotorPos(215.0, 45.0)))

        // Le retournement ensuite, et c'est le piège : à 100° d'élévation le
        // mât regarde par-dessus sa tête, donc de l'autre côté.
        assertEquals(RotorPos(180.0, 80.0), RotorMath.antennaAim(RotorPos(0.0, 100.0)))
        assertEquals(RotorPos(30.0, 10.0), RotorMath.antennaAim(RotorPos(210.0, 170.0)))
        // La frontière appartient au cas droit : 90°, c'est le zénith, et le
        // faire basculer ferait sauter l'aiguille d'un demi-tour pour rien.
        assertEquals(RotorPos(100.0, 90.0), RotorMath.antennaAim(RotorPos(100.0, 90.0)))

        // Et l'antenne pointe toujours quelque part de nommable : azimut dans
        // le tour, élévation au-dessus de l'horizon.
        var el = 0.0
        while (el <= 180.0 + 1e-9) {
            var az = -720.0
            while (az <= 720.0 + 1e-9) {
                val v = RotorMath.antennaAim(RotorPos(az, el))
                assertTrue("azimut hors du tour : ${v.azDeg}", v.azDeg >= -1e-9 && v.azDeg < 360.0)
                assertTrue("élévation impossible : ${v.elDeg}", v.elDeg >= -1e-9 && v.elDeg <= 90.0 + 1e-9)
                az += 37.5
            }
            el += 7.5
        }
    }

    /**
     * Un passage qui commence trente degrés derrière la butée sud, et qui
     * remonte ensuite dans la course.
     *
     * Sans tolérance, le seul moyen de tout couvrir est d'ajouter un tour — le
     * mât part alors à 510° au lieu de 180, et il aura passé une bonne partie du
     * passage à y aller.
     */
    private fun passageDerriereLaButee(): List<Pair<Double, Double>> =
        (0..35).map { i -> (150.0 + i.toDouble()) to 45.0 }

    @Test
    fun l_ecart_tolere_evite_le_tour_complet() {
        val track = passageDerriereLaButee()

        // Sans rien tolérer, le plan va chercher le tour de plus : c'est le
        // seul décalage qui couvre le début du passage au degré près.
        val strict = RotorMath.plan(track, sud360, toleranceDeg = 0.0)!!
        assertEquals(360.0, strict.shiftDeg, 1e-9)

        // Avec trente-cinq degrés tolérés, le début manqué ne coûte plus rien :
        // on démarre à la butée, on laisse passer les quelques degrés, et le
        // mât ne déroule pas.
        val souple = RotorMath.plan(track, sud360, toleranceDeg = 35.0)!!
        assertEquals(0.0, souple.shiftDeg, 1e-9)
        assertEquals(1.0, souple.coverage, 1e-9)

        // Mais on ne se ment pas sur ce qu'on a manqué : le pire écart reste
        // le vrai, celui que le bandeau annonce à l'opérateur.
        assertEquals(30.0, souple.worstErrorDeg, 1e-9)

        // Et la tolérance ne déplace jamais le mât plus loin qu'il n'irait sans
        // elle : elle autorise à renoncer, pas à en faire davantage.
        for (tol in listOf(0.0, 5.0, 10.0, 15.0, 20.0, 30.0, 35.0, 60.0)) {
            val p = RotorMath.plan(track, sud360, toleranceDeg = tol)!!
            assertTrue("décalage inattendu à $tol° : ${p.shiftDeg}",
                p.shiftDeg == 0.0 || p.shiftDeg == 360.0)
        }
    }

    @Test
    fun un_passage_bien_dans_la_course_ne_change_pas_avec_la_tolerance() {
        // Le garde-fou de la 18.16 : là où il n'y a pas de butée à franchir, la
        // tolérance n'a rien à décider, et le plan doit être exactement celui
        // d'avant — même décalage, même couverture, même écart nul.
        val track = (0..40).map { i -> (200.0 + i.toDouble() * 3.0) to (10.0 + i.toDouble()) }
        val sans = RotorMath.plan(track, sud360, toleranceDeg = 0.0)!!
        for (tol in listOf(5.0, 15.0, 30.0)) {
            val avec = RotorMath.plan(track, sud360, toleranceDeg = tol)!!
            assertEquals(sans.shiftDeg, avec.shiftDeg, 1e-9)
            assertEquals(sans.coverage, avec.coverage, 1e-9)
            assertEquals(sans.worstErrorDeg, avec.worstErrorDeg, 1e-9)
        }
        assertEquals(1.0, sans.coverage, 1e-9)
        assertEquals(0.0, sans.worstErrorDeg, 1e-9)
    }
}
