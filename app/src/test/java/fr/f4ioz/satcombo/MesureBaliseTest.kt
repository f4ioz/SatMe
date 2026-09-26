/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.MesureBalise
import fr.f4ioz.satcombo.domain.Qo100
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Beacon measurement, on a synthetic spectrum whose truth is known to the
 * hertz — the only way to judge a measurement that feeds a calibration.
 */
class MesureBaliseTest {

    private val n = 16384
    private val etendue = 1_058_400.0
    private val centre = Qo100.BALISE_MEDIANE_HZ.toDouble()

    /** Width of one panorama bin: about 65 Hz. */
    private val hzParRaie = etendue / n

    /**
     * −90 dB noise with a Gaussian line at [baliseHz], spread like a Hann
     * window would; a perfectly sharp line would make the test worthless.
     */
    private fun spectre(
        baliseHz: Double,
        hauteurDb: Float = 40f,
        etendueSignee: Double = etendue,
    ): FloatArray {
        val largeurRaies = 1.2
        return FloatArray(n) { i ->
            val f = centre + (i - n / 2.0) * (etendueSignee / n)
            val d = (f - baliseHz) / (largeurRaies * abs(etendueSignee / n))
            // Reproducible noise: a sawtooth, not random.
            val bruit = -90f + (i % 7) * 0.3f
            bruit + hauteurDb * exp(-0.5 * d * d).toFloat()
        }
    }

    @Test
    fun une_balise_pile_a_sa_place_donne_un_ecart_nul() {
        val m = MesureBalise.mesurer(spectre(centre), centre, etendue)
        assertNotNull(m)
        assertEquals(0.0, m!!.ecartHz, 5.0)
    }

    @Test
    fun l_ecart_mesure_est_celui_qu_on_a_mis() {
        // E.g. a cold LNB: 12 kHz off.
        for (vrai in listOf(-12_000.0, -3_500.0, -70.0, 0.0, 70.0, 3_500.0, 12_000.0)) {
            val m = MesureBalise.mesurer(spectre(centre + vrai), centre, etendue)
            assertNotNull("balise perdue à $vrai Hz", m)
            assertEquals("écart faux à $vrai Hz", vrai, m!!.ecartHz, 10.0)
        }
    }

    /**
     * Parabolic interpolation is useful: without it the result would be a
     * multiple of the bin width (~65 Hz) and a 30 Hz offset would read zero.
     */
    @Test
    fun un_ecart_plus_petit_qu_une_raie_est_quand_meme_vu() {
        val vrai = hzParRaie * 0.4
        assertTrue("l'essai n'aurait pas de sens", vrai < hzParRaie)
        val m = MesureBalise.mesurer(spectre(centre + vrai), centre, etendue)
        assertNotNull(m)
        assertTrue("l'écart a été arrondi à zéro : l'interpolation ne sert à rien",
            abs(m!!.ecartHz) > hzParRaie * 0.15)
        assertEquals(vrai, m.ecartHz, 8.0)
    }

    /**
     * Behind high-side injection the spectrum is inverted and the array runs
     * backwards. The offset must stay the sky's, not its opposite — the point
     * of the signed span.
     */
    @Test
    fun un_montage_inverseur_ne_change_pas_le_signe_de_l_ecart() {
        val vrai = 4_200.0
        val m = MesureBalise.mesurer(
            spectre(centre + vrai, etendueSignee = -etendue), centre, -etendue)
        assertNotNull(m)
        assertEquals(vrai, m!!.ecartHz, 10.0)
    }

    @Test
    fun le_rapport_au_plancher_suit_la_hauteur_de_la_raie() {
        val faible = MesureBalise.mesurer(spectre(centre, hauteurDb = 10f), centre, etendue)
        val fort = MesureBalise.mesurer(spectre(centre, hauteurDb = 40f), centre, etendue)
        assertNotNull(faible); assertNotNull(fort)
        assertTrue("le rapport ne monte pas avec le signal",
            fort!!.rapportDb > faible!!.rapportDb + 20f)
        // The floor is what we set, give or take the sawtooth.
        assertEquals(-90f, fort.plancherDb, 2.5f)
    }

    @Test
    fun sans_balise_il_n_y_a_pas_de_mesure() {
        val plat = FloatArray(n) { -90f + (it % 7) * 0.3f }
        assertNull(MesureBalise.mesurer(plat, centre, etendue))
    }

    /**
     * A line barely above noise must not pass for a beacon: it would end up
     * in the calibration, which persists beyond the session.
     */
    @Test
    fun une_raie_sous_le_seuil_est_refusee() {
        val s = spectre(centre, hauteurDb = 3f)
        assertNull(MesureBalise.mesurer(s, centre, etendue, seuilDb = 6f))
        assertNotNull(MesureBalise.mesurer(s, centre, etendue, seuilDb = 1f))
    }

    /**
     * Outside the window the beacon does not exist. No measurement beats one
     * taken on whatever station happens to be there.
     */
    @Test
    fun une_balise_hors_fenetre_n_est_pas_attrapee() {
        val m = MesureBalise.mesurer(
            spectre(centre + 40_000.0), centre, etendue, fenetreHz = 20_000.0)
        // Either nothing, or not the beacon: either way the reported offset
        // must not be that of a found beacon.
        if (m != null) assertTrue("on a attrapé quelque chose à 40 kHz",
            abs(m.ecartHz) < 20_000.0)
    }

    @Test
    fun une_fenetre_hors_du_tableau_ne_rend_rien() {
        // Centre 10 MHz from the target: the window falls far outside the array.
        assertNull(MesureBalise.mesurer(
            spectre(centre), centre + 10_000_000.0, etendue))
    }

    @Test
    fun un_tableau_vide_ou_une_etendue_nulle_ne_font_pas_de_degat() {
        assertNull(MesureBalise.mesurer(FloatArray(0), centre, etendue))
        assertNull(MesureBalise.mesurer(FloatArray(4) { -50f }, centre, etendue))
        assertNull(MesureBalise.mesurer(spectre(centre), centre, 0.0))
        assertNull(MesureBalise.mesurer(spectre(centre), centre, etendue, fenetreHz = 0.0))
    }

    @Test
    fun l_ecart_arrondi_est_celui_qu_on_ecrit_dans_l_etalonnage() {
        val m = MesureBalise.mesurer(spectre(centre + 8_123.0), centre, etendue)
        assertNotNull(m)
        assertEquals(m!!.ecartHz.roundToInt().toLong(), m.ecartArrondiHz)
        assertTrue("écart arrondi aberrant : ${m.ecartArrondiHz}",
            abs(m.ecartArrondiHz - 8_123L) < 15L)
    }

    @Test
    fun le_calage_se_declare_suffisant_au_bon_endroit() {
        assertFalse("une absence de mesure n'est pas un bon calage",
            MesureBalise.calageSuffisant(null))
        val juste = MesureBalise.mesurer(spectre(centre + 30.0), centre, etendue)
        val faux = MesureBalise.mesurer(spectre(centre + 4_000.0), centre, etendue)
        assertTrue(MesureBalise.calageSuffisant(juste))
        assertFalse(MesureBalise.calageSuffisant(faux))
    }
}
