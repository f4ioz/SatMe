/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
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
 * Le banc de la mesure de balise.
 *
 * On ne branche pas de clé : on fabrique un spectre dont on connaît la vérité
 * au hertz près, et on demande à la pièce de la retrouver. C'est le seul moyen
 * de juger une mesure dont la sortie sert à corriger un étalonnage — sur le
 * ciel, on n'a jamais la réponse.
 */
class MesureBaliseTest {

    private val n = 16384
    private val etendue = 1_058_400.0
    private val centre = Qo100.BALISE_MEDIANE_HZ.toDouble()

    /** La largeur d'une raie du panorama : soixante-cinq hertz environ. */
    private val hzParRaie = etendue / n

    /**
     * Un spectre de bruit à −90 dB avec une raie gaussienne à [baliseHz].
     *
     * La raie est étalée sur quelques dixièmes de raie de FFT, comme le fait
     * une vraie fenêtre de Hann : une raie parfaitement ponctuelle rendrait
     * l'interpolation parabolique triviale et l'essai sans valeur.
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
            // Un bruit reproductible : une dent de scie, pas un tirage au sort.
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
        // Un LNB froid : douze kilohertz trop haut.
        for (vrai in listOf(-12_000.0, -3_500.0, -70.0, 0.0, 70.0, 3_500.0, 12_000.0)) {
            val m = MesureBalise.mesurer(spectre(centre + vrai), centre, etendue)
            assertNotNull("balise perdue à $vrai Hz", m)
            assertEquals("écart faux à $vrai Hz", vrai, m!!.ecartHz, 10.0)
        }
    }

    /**
     * L'interpolation parabolique sert à quelque chose.
     *
     * Sans elle, la mesure serait un multiple de la largeur de raie, soit
     * soixante-cinq hertz : un écart de trente hertz ressortirait à zéro. On
     * vérifie donc qu'un décalage plus petit qu'une raie est bien vu.
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
     * Derrière une injection haute, le spectre est retourné et le tableau se
     * parcourt à l'envers. L'écart mesuré doit rester celui du ciel, pas son
     * opposé — c'est tout l'intérêt de l'étendue signée.
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
        // Le plancher est celui qu'on a mis, à la dent de scie près.
        assertEquals(-90f, fort.plancherDb, 2.5f)
    }

    @Test
    fun sans_balise_il_n_y_a_pas_de_mesure() {
        val plat = FloatArray(n) { -90f + (it % 7) * 0.3f }
        assertNull(MesureBalise.mesurer(plat, centre, etendue))
    }

    /**
     * Une raie qui dépasse à peine ne doit pas passer pour une balise. Ce qui
     * se ramasse au bruit finit dans l'étalonnage, et l'étalonnage est
     * persistant : une mauvaise mesure survit à la session.
     */
    @Test
    fun une_raie_sous_le_seuil_est_refusee() {
        val s = spectre(centre, hauteurDb = 3f)
        assertNull(MesureBalise.mesurer(s, centre, etendue, seuilDb = 6f))
        assertNotNull(MesureBalise.mesurer(s, centre, etendue, seuilDb = 1f))
    }

    /**
     * Hors de la fenêtre, la balise n'existe pas. On préfère une absence de
     * mesure à une mesure prise sur le premier correspondant venu.
     */
    @Test
    fun une_balise_hors_fenetre_n_est_pas_attrapee() {
        val m = MesureBalise.mesurer(
            spectre(centre + 40_000.0), centre, etendue, fenetreHz = 20_000.0)
        // Ou bien rien du tout, ou bien pas la balise : dans les deux cas
        // l'écart annoncé ne doit pas être celui d'une balise trouvée.
        if (m != null) assertTrue("on a attrapé quelque chose à 40 kHz",
            abs(m.ecartHz) < 20_000.0)
    }

    @Test
    fun une_fenetre_hors_du_tableau_ne_rend_rien() {
        // Le centre est à dix mégahertz de la cible : la fenêtre tombe très
        // au-delà du tableau.
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
