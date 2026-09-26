/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.MaterielRx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le banc des écarts par appareil.
 *
 * Il tient surtout une promesse : **rien ne bouge tant qu'on n'a pas mesuré**.
 * C'était la condition posée — pas d'effet de bord sur les satellites à
 * défilement — et elle se vérifie ici plutôt qu'au terrain.
 */
class MaterielRxTest {

    @Test
    fun sans_mesure_rien_ne_bouge() {
        MaterielRx.parDefaut().forEach { m ->
            assertEquals(0.0, m.ppm, 0.0)
            assertEquals(145_950_000L, MaterielRx.corrige(145_950_000L, m.ppm))
        }
    }

    @Test
    fun le_premier_poste_est_la_reference() {
        val ref = MaterielRx.parDefaut().filter { it.reference }
        assertEquals(1, ref.size)
        assertEquals("FT-817 A", ref.first().nom)
    }

    /**
     * L'erreur est une proportion : trois fois plus haut, trois fois plus
     * d'écart. C'est ce qui interdit de ranger des hertz.
     */
    @Test
    fun l_ecart_suit_la_frequence() {
        val a = MaterielRx.corrige(144_000_000L, 2.0) - 144_000_000L
        val b = MaterielRx.corrige(432_000_000L, 2.0) - 432_000_000L
        assertEquals(288L, a)
        assertEquals(864L, b)
        assertEquals(3.0, b.toDouble() / a, 0.01)
    }

    @Test
    fun corriger_puis_redresser_revient_au_point_de_depart() {
        listOf(-40.0, -2.5, 0.0, 1.7, 25.0).forEach { ppm ->
            val f = 144_790_000L
            val revenu = MaterielRx.redresse(MaterielRx.corrige(f, ppm), ppm)
            assertTrue("ppm=$ppm : $revenu", kotlin.math.abs(revenu - f) <= 1L)
        }
    }

    @Test
    fun une_mesure_donne_l_ecart() {
        // 144,790 MHz attendus, 144,793 lus : environ +20,7 ppm.
        val ppm = MaterielRx.ppmDepuisMesure(144_790_000L, 144_793_000L)
        assertTrue(ppm != null && kotlin.math.abs(ppm - 20.72) < 0.05)
    }

    /**
     * Cent ppm valent quatorze kilohertz à 144 MHz : au-delà ce n'est plus un
     * quartz, c'est une faute de frappe, et la ranger déplacerait tout.
     */
    @Test
    fun une_mesure_absurde_est_refusee() {
        assertNull(MaterielRx.ppmDepuisMesure(144_790_000L, 154_790_000L))
        assertNull(MaterielRx.ppmDepuisMesure(0L, 144_790_000L))
        assertNull(MaterielRx.ppmDepuisMesure(144_790_000L, 0L))
    }

    @Test
    fun un_ecart_incroyable_ne_corrige_rien() {
        assertEquals(144_000_000L, MaterielRx.corrige(144_000_000L, 5_000.0))
        assertEquals(144_000_000L, MaterielRx.corrige(144_000_000L, Double.NaN))
    }

    /** La référence reste à zéro : c'est ce qui donne un point fixe. */
    @Test
    fun la_reference_ne_se_regle_pas() {
        val apres = MaterielRx.range(MaterielRx.parDefaut(), "FT-817 A", 12.0)
        assertEquals(0.0, MaterielRx.choisi(apres, "FT-817 A").ppm, 0.0)
    }

    @Test
    fun un_autre_appareil_se_regle() {
        val apres = MaterielRx.range(MaterielRx.parDefaut(), "Clé SDR 1", 25.0)
        assertEquals(25.0, MaterielRx.choisi(apres, "Clé SDR 1").ppm, 0.0)
        // Et les autres n'ont pas bougé.
        assertEquals(0.0, MaterielRx.choisi(apres, "Clé SDR 2").ppm, 0.0)
    }

    @Test
    fun un_nom_inconnu_retombe_sur_la_reference() {
        assertTrue(MaterielRx.choisi(MaterielRx.parDefaut(), "inexistant").reference)
    }

    @Test
    fun la_liste_se_range_et_se_relit() {
        val liste = MaterielRx.range(MaterielRx.parDefaut(), "Clé SDR 1", -18.5)
        val relue = MaterielRx.lit(MaterielRx.ecrit(liste))
        assertEquals(liste, relue)
    }

    /** Sans liste, on rend celle par défaut : jamais aucun appareil. */
    @Test
    fun une_liste_illisible_rend_celle_par_defaut() {
        assertEquals(MaterielRx.parDefaut(), MaterielRx.lit("n'importe quoi"))
        assertEquals(MaterielRx.parDefaut(), MaterielRx.lit("[]"))
    }

    /** Une valeur abîmée dans le fichier ne doit pas déplacer les fréquences. */
    @Test
    fun une_valeur_rangee_absurde_est_ramenee_a_zero() {
        val relue = MaterielRx.lit("Clé SDR 1|99999.0|false")
        assertEquals(0.0, relue.first().ppm, 0.0)
    }
}
