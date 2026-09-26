/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.data.TleEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reconnaître un satellite qui ne bouge pas, d'après son TLE.
 *
 * On ne se fie pas à une liste de numéros : elle serait juste pour QO-100 et
 * fausse pour le prochain géostationnaire venu. Le nombre de tours par jour
 * est dans les éléments orbitaux, et il vaut pour tous.
 */
class GeostationnaireTest {

    // QO-100 / Es'hail-2 : 1,00 tour par jour.
    private val qo100 = TleEntry(
        name = "QO-100",
        line1 = "1 43700U 18090A   26246.50000000  .00000100  00000-0  00000-0 0  9990",
        line2 = "2 43700   0.0200  95.0000 0002000 250.0000 110.0000  1.00271000 24000")

    // FO-29 : un peu plus de 12 tours par jour.
    private val fo29 = TleEntry(
        name = "FO-29",
        line1 = "1 24278U 96046B   26246.50000000  .00000010  00000-0  00000-0 0  9990",
        line2 = "2 24278  98.5000 200.0000 0350000  30.0000 330.0000 13.53000000 24000")

    @Test
    fun le_nombre_de_tours_se_lit() {
        assertEquals(1.00271, qo100.toursParJour!!, 1e-5)
        assertEquals(13.53, fo29.toursParJour!!, 1e-5)
    }

    @Test
    fun un_geostationnaire_est_immobile() {
        assertTrue(qo100.estImmobile)
    }

    @Test
    fun un_satellite_defilant_ne_l_est_pas() {
        assertFalse(fo29.estImmobile)
    }

    /**
     * Sans ligne 2 lisible, on répond « non ».
     *
     * Mieux vaut une boussole inutile qu'une boussole cachée sur un satellite
     * qui passe : la première encombre, la seconde fait manquer le passage.
     */
    @Test
    fun un_tle_illisible_ne_passe_pas_pour_immobile() {
        val casse = TleEntry(name = "x", line1 = "1 00000U", line2 = "2 00000")
        assertNull(casse.toursParJour)
        assertFalse(casse.estImmobile)
    }

    /** Un géosynchrone incliné décrit un huit, mais la parabole ne bouge pas. */
    @Test
    fun un_geosynchrone_incline_compte_aussi() {
        val incline = TleEntry(
            name = "incliné",
            line1 = qo100.line1,
            line2 = "2 43700  12.0000  95.0000 0002000 250.0000 110.0000  1.00280000 24000")
        assertTrue(incline.estImmobile)
    }
}
