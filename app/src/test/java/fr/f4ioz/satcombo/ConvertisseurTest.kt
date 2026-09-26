/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.Convertisseur
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The converter: one LO, one direction, one range. Numbers must match real
 * setups; the round trip must be exact (dial readback goes through the
 * inverse, and 1 Hz off looks like a gesture); and above all a converter left
 * enabled touches nothing outside its band, or every other satellite breaks.
 */
class ConvertisseurTest {

    private val lnb9750 = Convertisseur(
        actif = true, olHz = 9_750_000_000L,
        basHz = 10_400_000_000L, hautHz = 10_800_000_000L)

    private val lnb10000 = lnb9750.copy(olHz = 10_000_000_000L)

    private val tvtr432 = Convertisseur(
        actif = true, olHz = 1_968_000_000L,
        basHz = 2_390_000_000L, hautHz = 2_450_000_000L)

    private val tvtr144 = tvtr432.copy(olHz = 2_256_000_000L)

    // --- Real-world numbers ---------------------------------------------

    @Test
    fun le_lnb_9750_ramene_la_balise_mediane_a_739_750() {
        assertEquals(739_750_000L, lnb9750.versPoste(10_489_750_000L))
    }

    @Test
    fun le_lnb_10000_ramene_la_balise_mediane_a_489_750() {
        assertEquals(489_750_000L, lnb10000.versPoste(10_489_750_000L))
    }

    @Test
    fun le_transverter_432_emet_le_bas_du_transpondeur_etroit() {
        // NB uplink low edge: 2400.005 MHz, driven at 432.005.
        assertEquals(432_005_000L, tvtr432.versPoste(2_400_005_000L))
    }

    @Test
    fun le_transverter_144_emet_le_bas_du_transpondeur_etroit() {
        assertEquals(144_005_000L, tvtr144.versPoste(2_400_005_000L))
    }

    @Test
    fun les_bords_du_transpondeur_etroit_gardent_leur_ecart() {
        // 492 kHz wide on both sides: a subtractive conversion shifts
        // everything by the same amount, it compresses nothing.
        val bas = lnb9750.versPoste(10_489_505_000L)
        val haut = lnb9750.versPoste(10_489_997_000L)
        assertEquals(492_000L, haut - bas)
    }

    // --- Round trip ------------------------------------------------------

    @Test
    fun ce_qui_monte_redescend_a_l_identique() {
        val sat = 10_489_612_345L
        assertEquals(sat, lnb9750.versSatellite(lnb9750.versPoste(sat)))
    }

    @Test
    fun l_aller_retour_tient_aussi_a_l_emission() {
        val sat = 2_400_312_500L
        assertEquals(sat, tvtr432.versSatellite(tvtr432.versPoste(sat)))
    }

    // --- High-side injection --------------------------------------------

    @Test
    fun un_inverseur_retourne_la_bande() {
        val inv = Convertisseur(actif = true, olHz = 11_000_000_000L, inverseur = true,
            basHz = 10_400_000_000L, hautHz = 10_800_000_000L)
        // Higher in the sky, lower on the radio.
        val bas = inv.versPoste(10_489_500_000L)
        val haut = inv.versPoste(10_489_900_000L)
        assertTrue("le spectre ne s'est pas retourné", haut < bas)
        assertEquals(510_500_000L, bas)
    }

    @Test
    fun l_inverseur_est_sa_propre_reciproque() {
        val inv = Convertisseur(actif = true, olHz = 11_000_000_000L, inverseur = true,
            basHz = 10_400_000_000L, hautHz = 10_800_000_000L)
        val sat = 10_489_750_000L
        assertEquals(sat, inv.versSatellite(inv.versPoste(sat)))
    }

    // --- Range, i.e. safety -----------------------------------------------

    @Test
    fun hors_de_sa_bande_un_convertisseur_ne_touche_a_rien() {
        // LNB left enabled while working the ISS. If the subtraction applied,
        // a negative frequency would be written to the radio.
        assertEquals(145_800_000L, lnb9750.versPoste(145_800_000L))
        assertEquals(435_300_000L, lnb9750.versPoste(435_300_000L))
        assertFalse(lnb9750.couvre(145_800_000L))
    }

    @Test
    fun le_lnb_et_le_transverter_cohabitent_sans_se_marcher_dessus() {
        // Both stay enabled permanently; each sees only its own band.
        assertTrue(lnb9750.couvre(10_489_750_000L))
        assertFalse(lnb9750.couvre(2_400_150_000L))
        assertTrue(tvtr432.couvre(2_400_150_000L))
        assertFalse(tvtr432.couvre(10_489_750_000L))
    }

    @Test
    fun la_relecture_hors_plage_ne_fabrique_pas_un_saut_de_dix_gigahertz() {
        // The radio shows 435.300: a satellite on 435.300, not 10185. Without
        // this guard every readback would look like a gesture.
        assertEquals(435_300_000L, lnb9750.versSatellite(435_300_000L))
    }

    @Test
    fun decoche_il_est_transparent() {
        val off = lnb9750.copy(actif = false)
        assertEquals(10_489_750_000L, off.versPoste(10_489_750_000L))
        assertEquals(739_750_000L, off.versSatellite(739_750_000L))
        assertFalse(off.configure)
    }

    @Test
    fun un_oscillateur_a_zero_vaut_pas_de_convertisseur() {
        val vide = Convertisseur(actif = true, olHz = 0L)
        assertFalse(vide.configure)
        assertEquals(10_489_750_000L, vide.versPoste(10_489_750_000L))
    }

    @Test
    fun sans_bornes_le_convertisseur_s_applique_partout_ou_le_resultat_tient() {
        val libre = Convertisseur(actif = true, olHz = 9_750_000_000L)
        assertEquals(739_750_000L, libre.versPoste(10_489_750_000L))
        // …but never to the point of producing a negative frequency.
        assertEquals(145_800_000L, libre.versPoste(145_800_000L))
    }

    // --- Presets ---------------------------------------------------------

    @Test
    fun les_preregleges_donnent_bien_les_fi_annoncees() {
        val attendu = mapOf(
            "lnb9750" to 739_750_000L,
            "lnb10000" to 489_750_000L,
            "lnb10057" to 432_250_000L,
            // The measured LO puts the middle beacon at 144.777, not 145.750
            // (nominal 10 344.000 MHz assumed here; StationsQo100Test uses
            // 10 345.000, which one is right is still open).
            "down145" to 144_777_000L)
        Convertisseur.PRESETS.filter { it.descente }.forEach { p ->
            assertEquals("préréglage ${p.cle}",
                attendu[p.cle], p.vers().versPoste(Convertisseur.BALISE_MEDIANE_HZ))
        }
    }

    @Test
    fun les_preregleges_de_montee_couvrent_le_transpondeur_etroit() {
        Convertisseur.PRESETS.filter { !it.descente }.forEach { p ->
            val c = p.vers()
            assertTrue("préréglage ${p.cle} ne couvre pas la montée NB",
                c.couvre(2_400_005_000L) && c.couvre(2_400_490_000L))
        }
    }

    // --- F4IOZ setup: downlink on 145, uplink on 432 ---------------------

    /**
     * The `down145` preset must put the *whole* narrowband transponder in 2 m,
     * not just the beacon. If one edge fell outside, half the transponder would
     * be unreachable, noticed only when trying to call someone.
     */
    @Test
    fun le_downconverter_145_range_tout_le_transpondeur_etroit_dans_les_2_m() {
        val c = Convertisseur.PRESETS.first { it.cle == "down145" }.vers()
        val bas = c.versPoste(10_489_505_000L)
        val haut = c.versPoste(10_489_997_000L)
        assertEquals(144_532_000L, bas)
        assertEquals(145_024_000L, haut)
        // IC-9700 2 m band: 144–148 MHz, with margin.
        assertTrue("le bas sort des 2 m", bas in 144_000_000L..148_000_000L)
        assertTrue("le haut sort des 2 m", haut in 144_000_000L..148_000_000L)
    }

    @Test
    fun le_downconverter_145_pose_les_trois_balises_ou_on_les_attend() {
        val c = Convertisseur.PRESETS.first { it.cle == "down145" }.vers()
        // Frequencies actually observed on the FT-817, not round values: the
        // LNB oscillator carries its 27 kHz offset.
        assertEquals(144_527_000L, c.versPoste(10_489_500_000L))
        assertEquals(144_777_000L, c.versPoste(10_489_750_000L))
        assertEquals(145_027_000L, c.versPoste(10_490_000_000L))
    }

    /**
     * The crossed pair: receive on 145, transmit on 432. Both converters must
     * coexist without interfering.
     */
    @Test
    fun la_paire_145_rx_432_tx_tient_avec_les_deux_convertisseurs() {
        val descente = Convertisseur.PRESETS.first { it.cle == "down145" }.vers()
        val montee = Convertisseur.PRESETS.first { it.cle == "tvtr432" }.vers()
        assertEquals(144_777_000L, descente.versPoste(10_489_750_000L))
        assertEquals(432_250_000L, montee.versPoste(2_400_250_000L))
        // Each ignores the other's band.
        assertFalse(descente.couvre(2_400_250_000L))
        assertFalse(montee.couvre(10_489_750_000L))
        // Neither touches the other's IF.
        assertEquals(144_777_000L, montee.versPoste(144_777_000L))
        assertEquals(432_250_000L, descente.versPoste(432_250_000L))
    }

    @Test
    fun le_downconverter_145_fait_l_aller_retour_sans_perdre_un_hertz() {
        val c = Convertisseur.PRESETS.first { it.cle == "down145" }.vers()
        listOf(10_489_505_000L, 10_489_750_000L, 10_489_997_000L).forEach { sat ->
            assertEquals(sat, c.versSatellite(c.versPoste(sat)))
        }
    }

    @Test
    fun aucun_prereglage_ne_deborde_sur_la_bande_d_un_autre() {
        Convertisseur.PRESETS.forEach { p ->
            val c = p.vers()
            if (p.descente) assertFalse("${p.cle} déborde sur le 13 cm",
                c.couvre(2_400_150_000L))
            else assertFalse("${p.cle} déborde sur le 10 GHz",
                c.couvre(10_489_750_000L))
        }
    }

    // ------------------------------- F4IOZ's measured chain

    /**
     * The `down145` preset holds a **measured** LO: one QSO read at 10489.80559
     * MHz on the GPSDO-locked IS0GRB WebSDR and 144.83265 MHz on the FT-817
     * (3 Sep 2026) gives 10344.97294 MHz. The offset is the LNB TCXO, stable
     * across power cycles.
     */
    @Test
    fun le_preset_down145_porte_la_valeur_mesuree() {
        val p = Convertisseur.PRESETS.first { it.cle == "down145" }
        assertEquals(10_344_973_000L, p.olHz)
        assertTrue(p.descente)
    }

    @Test
    fun le_qso_mesure_retombe_sur_l_affichage_du_poste() {
        val c = Convertisseur.PRESETS.first { it.cle == "down145" }.vers()
        // The FT-817 read 144.83265; the LO rounded to 1 kHz leaves ~60 Hz,
        // well below what the ear notices on SSB.
        val fi = c.versPoste(10_489_805_590L)
        assertTrue("écart de ${144_832_650L - fi} Hz",
            kotlin.math.abs(144_832_650L - fi) < 200L)
    }

    @Test
    fun les_trois_balises_tombent_dans_les_deux_metres() {
        val c = Convertisseur.PRESETS.first { it.cle == "down145" }.vers()
        assertEquals(144_527_000L, c.versPoste(Convertisseur.BALISE_MEDIANE_HZ - 250_000L))
        assertEquals(144_777_000L, c.versPoste(Convertisseur.BALISE_MEDIANE_HZ))
        assertEquals(145_027_000L, c.versPoste(Convertisseur.BALISE_HAUTE_HZ))
    }

    /**
     * The round trip must be exact: it guarantees a logged contact carries the
     * sky frequency, not the IF.
     */
    @Test
    fun l_aller_retour_de_la_chaine_mesuree_est_exact() {
        val c = Convertisseur.PRESETS.first { it.cle == "down145" }.vers()
        listOf(10_489_500_000L, 10_489_750_000L, 10_489_805_590L, 10_490_000_000L)
            .forEach { assertEquals(it, c.versSatellite(c.versPoste(it))) }
    }

    /** The upper beacon is a second calibration point, 500 kHz from the first. */
    @Test
    fun les_deux_balises_sont_distantes_de_cinq_cents_kilohertz() {
        assertEquals(500_000L,
            Convertisseur.BALISE_HAUTE_HZ - (Convertisseur.BALISE_MEDIANE_HZ - 250_000L))
    }

    /**
     * The trap that showed a QO-100 frequency on a LEO: bounds apply to the
     * sky-side **result**, and 145.9 MHz + LNB LO = 10490.9, inside Ku. So
     * [Convertisseur]'s guard is not enough; the current satellite must decide
     * whether the converter is in the chain.
     */
    @Test
    fun une_frequence_de_leo_tombe_par_accident_dans_la_fenetre_ku() {
        val lnb = Convertisseur(actif = true, olHz = 10_344_973_000L,
            basHz = 10_400_000_000L, hautHz = 10_800_000_000L)
        // 145.9 MHz mapped up gives 10490.9: within bounds, so converted.
        val remonte = lnb.versSatellite(145_900_000L)
        assertEquals(10_490_873_000L, remonte)
        assertTrue("la garde de bande ne peut pas voir l'erreur", lnb.couvre(remonte))
    }

    /** The satellite downlink does not lie: 435 is not in Ku. */
    @Test
    fun la_descente_du_satellite_tranche_sans_ambiguite() {
        val lnb = Convertisseur(actif = true, olHz = 10_344_973_000L,
            basHz = 10_400_000_000L, hautHz = 10_800_000_000L)
        assertTrue(lnb.couvre(10_489_750_000L))
        assertTrue(!lnb.couvre(435_350_000L))
        assertTrue(!lnb.couvre(145_950_000L))
    }
}
