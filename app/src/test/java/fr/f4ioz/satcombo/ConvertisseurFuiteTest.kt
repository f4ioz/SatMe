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
 * The QO-100 converter leaking onto LEO satellites.
 *
 * On a LEO with CAT connected, the screen showed a GHz frequency as soon as
 * the VFO moved: the QO-100 downconverter was applied to the wrong chain.
 *
 * Asking whether the **result** of the conversion falls in band proves
 * nothing — it always does; turning 2 m into QO-100 is exactly what an LNB
 * does. The only meaningful question is which satellite we are listening to.
 */
class ConvertisseurFuiteTest {

    private val lnb = Convertisseur(
        actif = true, olHz = 10_344_973_000L, inverseur = false,
        basHz = 10_400_000_000L, hautHz = 10_800_000_000L)

    /** The trap, named so nobody sets it again. */
    @Test
    fun le_resultat_dans_la_bande_ne_prouve_rien() {
        // A dongle on 145.9 MHz: the result lands right in QO-100.
        val faussementCouvert = 145_900_000L + lnb.olHz
        assertTrue("le piège lui-même", lnb.couvre(faussementCouvert))
        // But the satellite being heard is on 2 m.
        assertFalse("la bonne question", lnb.couvre(145_900_000L))
    }

    @Test
    fun un_leo_en_deux_metres_n_est_pas_couvert() {
        listOf(145_900_000L, 145_960_000L, 435_800_000L, 437_800_000L)
            .forEach { assertFalse("$it", lnb.couvre(it)) }
    }

    @Test
    fun le_transpondeur_de_qo100_est_couvert() {
        listOf(10_489_550_000L, 10_489_750_000L, 10_489_990_000L)
            .forEach { assertTrue("$it", lnb.couvre(it)) }
    }

    /**
     * Out of band, the converter passes the frequency unchanged, so it can stay
     * enabled permanently without disturbing the ISS.
     */
    @Test
    fun hors_bande_la_frequence_ressort_intacte() {
        assertEquals(145_900_000L, lnb.versPoste(145_900_000L))
    }

    @Test
    fun dans_sa_bande_il_descend_vers_la_frequence_intermediaire() {
        assertEquals(144_777_000L, lnb.versPoste(10_489_750_000L))
    }

    /** Disabled, it is transparent at any frequency. */
    @Test
    fun decoche_il_ne_fait_rien() {
        val eteint = lnb.copy(actif = false)
        assertFalse(eteint.couvre(10_489_750_000L))
        assertEquals(10_489_750_000L, eteint.versPoste(10_489_750_000L))
    }
}

/**
 * Why the input guard cannot save F4IOZ's chain.
 *
 * `accepteEnEntree` was meant to close the LEO leak: a frequency is an IF only
 * if it falls in the window this converter actually produces. Correct — and
 * useless here.
 *
 * Ku bounds 10400–10800 MHz, measured LO 10344.973 MHz, so the IF window is
 * **55 to 455 MHz**: 400 MHz wide, swallowing all of 2 m and 70 cm and every
 * LEO satellite in existence.
 *
 * This test does not expect the guard to work: it **pins the proof that it
 * cannot**, so nobody wastes another evening tightening a frequency rule. The
 * only discriminator is the selected satellite.
 */
class ConvertisseurFenetreTest {

    private val chaineF4ioz = fr.f4ioz.satcombo.domain.Convertisseur(
        actif = true, olHz = 10_344_973_000L, inverseur = false,
        basHz = 10_400_000_000L, hautHz = 10_800_000_000L)

    @Test
    fun la_fenetre_intermediaire_avale_le_deux_metres() {
        // A LEO downlink (AO-91, SO-50, ISS) is inside.
        assertTrue(chaineF4ioz.accepteEnEntree(145_950_000L))
        assertTrue(chaineF4ioz.accepteEnEntree(145_800_000L))
        // And 70 cm too.
        assertTrue(chaineF4ioz.accepteEnEntree(435_500_000L))
    }

    @Test
    fun et_la_remontee_donne_donc_bien_des_gigahertz() {
        val sat = chaineF4ioz.versSatellite(145_950_000L)
        assertTrue("attendu des gigahertz, obtenu $sat", sat > 10_000_000_000L)
    }

    @Test
    fun aucune_borne_ne_referme_cela_sans_casser_qo100() {
        // Excluding 2 m would need an IF window above 146 MHz, hence a lower Ku
        // bound above 10490.973 — above the middle beacon itself. We would
        // lose QO-100 to save the LEOs.
        val basNecessaire = 10_344_973_000L + 146_000_000L
        assertTrue(basNecessaire > fr.f4ioz.satcombo.domain.Convertisseur.BALISE_MEDIANE_HZ)
    }
}
