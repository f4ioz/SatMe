/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.StationsQo100
import fr.f4ioz.satcombo.domain.StationsQo100.Etalonnage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QO-100 calibration.
 *
 * Reference values are real measurements by F4IOZ (3 Sep 2026) against the
 * GPSDO-locked IS0GRB WebSDR, confirmed two hours later after a full power-off.
 */
class StationsQo100Test {

    private val ciel = 10_489_805_600L   // WebSDR IS0GRB
    private val poste = 144_832_650L     // FT-817, USB
    private val olAttendu = 10_344_972_950L

    @Test
    fun l_oscillateur_se_deduit_de_deux_frequences_lues() {
        val r = StationsQo100.etalonne(ciel, poste)
        assertTrue(r is Etalonnage.Trouve)
        assertEquals(olAttendu, (r as Etalonnage.Trouve).olHz)
    }

    /**
     * The natural mistake: reading your own radio first, then the WebSDR. A
     * negative difference gives it away.
     */
    @Test
    fun les_champs_intervertis_sont_refuses() {
        val r = StationsQo100.etalonne(poste, ciel)
        assertTrue(r is Etalonnage.Refuse)
        assertEquals("qo100_cal_inverse", (r as Etalonnage.Refuse).motif)
    }

    @Test
    fun un_champ_vide_est_refuse() {
        assertTrue(StationsQo100.etalonne(0L, poste) is Etalonnage.Refuse)
        assertTrue(StationsQo100.etalonne(ciel, 0L) is Etalonnage.Refuse)
    }

    /** Two close frequencies do not give a credible LO. */
    @Test
    fun un_ecart_absurde_est_refuse() {
        val r = StationsQo100.etalonne(10_489_805_600L, 10_489_805_000L)
        assertTrue(r is Etalonnage.Refuse)
        assertEquals("qo100_cal_absurde", (r as Etalonnage.Refuse).motif)
    }

    // ------------------------------------------------ the offset

    /**
     * −27 kHz from nominal (**−2.6 ppm**): the LNB TCXO, not a fault, and not
     * fixable by a GPSDO. Nominal is 10345000 kHz (lower beacon at 144.500);
     * the other reading of the DX Patrol manual gives 50 kHz lower. Note:
     * ConvertisseurTest assumes 10 344.000 MHz instead (middle beacon at
     * 145.750); the true nominal is still to be checked by the author.
     * Irrelevant once the absolute LO is known.
     */
    @Test
    fun l_ecart_au_nominal_se_mesure() {
        val nominal = 10_345_000_000L
        assertEquals(-27_050L, StationsQo100.ecartAuNominal(olAttendu, nominal))
        val ppm = StationsQo100.ecartPpm(olAttendu, nominal)
        assertTrue("ppm = $ppm", ppm in -2.7..-2.5)
    }

    /** The other nominal, 50 kHz lower, gives +23 kHz. */
    @Test
    fun l_autre_nominal_donne_l_ecart_symetrique() {
        assertEquals(22_950L,
            StationsQo100.ecartAuNominal(olAttendu, 10_344_950_000L))
    }

    @Test
    fun un_nominal_absent_ne_divise_pas_par_zero() {
        assertEquals(0.0, StationsQo100.ecartPpm(olAttendu, 0L), 0.0)
    }

    // ------------------------------------------------------- stations

    @Test
    fun une_station_reglee_convertit_dans_les_deux_sens() {
        val s = StationsQo100.Station("fixe", descenteOlHz = olAttendu,
            monteeOlHz = 1_968_000_000L)
        assertEquals(poste, s.posteRx(ciel))
        assertEquals(2_400_250_000L - 1_968_000_000L, s.posteTx(2_400_250_000L))
    }

    /** Without a converter the frequency passes unchanged. */
    @Test
    fun une_station_sans_etage_laisse_passer() {
        val s = StationsQo100.Station("directe")
        assertEquals(ciel, s.posteRx(ciel))
        assertEquals(2_400_250_000L, s.posteTx(2_400_250_000L))
    }

    /**
     * An uncalibrated station must say so, rather than offer a nominal value
     * that looks right without being right.
     */
    @Test
    fun les_stations_par_defaut_ne_sont_pas_etalonnees() {
        val d = StationsQo100.parDefaut()
        assertEquals(2, d.size)
        d.forEach {
            assertTrue(!it.descenteReglee)
            assertTrue(!it.monteeReglee)
        }
    }

    @Test
    fun remplacer_ne_reordonne_pas_la_liste() {
        val liste = StationsQo100.parDefaut()
        val neuf = StationsQo100.remplace(liste, 1,
            liste[1].copy(descenteOlHz = olAttendu))
        assertEquals(listOf("fixe", "portable"), neuf.map { it.nom })
        assertEquals(olAttendu, neuf[1].descenteOlHz)
        assertEquals(0L, neuf[0].descenteOlHz)
    }

    @Test
    fun un_index_hors_liste_ne_casse_rien() {
        val liste = StationsQo100.parDefaut()
        assertEquals(liste, StationsQo100.remplace(liste, 7, liste[0]))
        assertEquals(liste, StationsQo100.remplace(liste, -1, liste[0]))
    }

    /**
     * Home and portable setups have different LNBs, hence different LOs. That
     * is the whole point of this list.
     */
    @Test
    fun deux_stations_gardent_chacune_son_etalonnage() {
        var liste = StationsQo100.parDefaut()
        liste = StationsQo100.remplace(liste, 0,
            liste[0].copy(descenteOlHz = olAttendu))
        liste = StationsQo100.remplace(liste, 1,
            liste[1].copy(descenteOlHz = 9_750_123_000L))
        assertEquals(poste, liste[0].posteRx(ciel))
        assertEquals(ciel - 9_750_123_000L, liste[1].posteRx(ciel))
    }
}
