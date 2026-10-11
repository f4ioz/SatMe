/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.Pays
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Country outlines for the QRV photo.
 *
 * Built squares instead of real outlines: a test depending on a 460 KB file
 * stops saying what it checks. Crude shapes, exact questions.
 */
class PaysTest {

    /** A square, flattened as lat, lon, lat, lon… like catalogue rings. */
    private fun carre(sud: Double, ouest: Double, nord: Double, est: Double) =
        doubleArrayOf(sud, ouest, sud, est, nord, est, nord, ouest)

    // A "mainland" and a separate "island".
    private val metropole = carre(42.0, -5.0, 51.0, 8.0)
    private val ile = carre(41.3, 8.5, 43.0, 9.6)
    // A distant territory across the Atlantic.
    private val lointain = carre(2.0, -54.0, 6.0, -52.0)
    private val bleu = Pays.Contour("FR", "France", listOf(metropole, ile, lointain))
    private val voisin = Pays.Contour("ES", "Espagne", listOf(carre(36.0, -9.0, 43.5, 3.0)))

    // ------------------------------------------------------------- membership

    @Test
    fun un_point_interieur_est_reconnu() {
        assertTrue(Pays.dansAnneau(metropole, 48.86, 2.35))
        assertTrue(Pays.dansPays(bleu, 48.86, 2.35))
    }

    @Test
    fun un_point_exterieur_ne_l_est_pas() {
        assertFalse(Pays.dansAnneau(metropole, 51.5, -0.12))
        assertFalse(Pays.dansAnneau(metropole, 60.0, 20.0))
    }

    @Test
    fun un_point_sur_l_ile_appartient_au_meme_pays() {
        assertTrue(Pays.dansPays(bleu, 42.0, 9.0))
        assertFalse(Pays.dansAnneau(metropole, 42.0, 9.0))
    }

    @Test
    fun le_bon_pays_est_trouve_parmi_plusieurs() {
        assertEquals("FR", Pays.trouve(listOf(bleu, voisin), 48.86, 2.35)?.code)
        assertEquals("ES", Pays.trouve(listOf(bleu, voisin), 40.4, -3.7)?.code)
    }

    /**
     * A portable operator is often on a beach, pier or headland — outside the
     * simplified outline. Returning "no country" would leave them without a
     * map in their own country.
     */
    @Test
    fun un_point_juste_en_mer_retombe_sur_le_pays_le_plus_proche() {
        val c = Pays.trouve(listOf(bleu, voisin), 48.5, -6.2)
        assertNotNull(c)
        assertEquals("FR", c?.code)
    }

    /** But mid-Atlantic belongs to nobody. */
    @Test
    fun le_large_n_appartient_a_aucun_pays() {
        assertNull(Pays.trouve(listOf(bleu, voisin), 45.0, -40.0))
    }

    // ------------------------------------------------------- pieces to draw

    /**
     * The core of it, which no territory list would do cleanly: from the
     * mainland we want the mainland **and** the island, but not the overseas
     * territory, which would make the map unreadable.
     */
    @Test
    fun depuis_la_metropole_on_dessine_la_metropole_et_son_ile() {
        val m = Pays.morceauxAutour(bleu, 48.86, 2.35)
        assertEquals(2, m.size)
        assertTrue(m.contains(metropole))
        assertTrue(m.contains(ile))
        assertFalse(m.contains(lointain))
    }

    /** From overseas, draw only the overseas piece. */
    @Test
    fun depuis_l_outre_mer_on_ne_dessine_que_lui() {
        val m = Pays.morceauxAutour(bleu, 4.0, -53.0)
        assertEquals(1, m.size)
        assertTrue(m.contains(lointain))
    }

    @Test
    fun un_pays_sans_anneau_ne_produit_rien() {
        val vide = Pays.Contour("XX", "Nulle part", emptyList())
        assertTrue(Pays.morceauxAutour(vide, 0.0, 0.0).isEmpty())
    }

    // ------------------------------------------------------------------- bounding box

    @Test
    fun la_boite_englobe_tous_les_morceaux() {
        val b = Pays.boite(listOf(metropole, ile))
        assertEquals(41.3, b.sud, 1e-9)
        assertEquals(51.0, b.nord, 1e-9)
        assertEquals(-5.0, b.ouest, 1e-9)
        assertEquals(9.6, b.est, 1e-9)
    }

    // -------------------------------------------------------------- layout

    /**
     * Longitude must be scaled by cos(latitude). Without it France looks
     * about a third too wide — the classic mistake of mapping degrees straight
     * to pixels.
     */
    @Test
    fun la_longitude_est_comprimee_selon_la_latitude() {
        val b = Pays.boite(listOf(metropole))
        val p = Pays.place(b, 0.0, 0.0, 1000.0, 1000.0)
        // At 46.5° mean latitude the factor is about 0.69.
        assertEquals(0.69, p.compression, 0.02)
    }

    /**
     * Same scale both ways: a country keeps its shape, not stretched to the frame.
     */
    @Test
    fun le_pays_garde_sa_forme_dans_un_cadre_allonge() {
        val b = Pays.boite(listOf(metropole))
        val p = Pays.place(b, 0.0, 0.0, 2000.0, 500.0)
        val largeurRendue = (b.largeur * p.compression) * p.echelle
        val hauteurRendue = b.hauteur * p.echelle
        val rapportReel = (b.largeur * p.compression) / b.hauteur
        assertEquals(rapportReel, largeurRendue / hauteurRendue, 1e-6)
        // Nothing overflows the frame.
        assertTrue(largeurRendue <= 2000.0 + 1e-6)
        assertTrue(hauteurRendue <= 500.0 + 1e-6)
    }

    @Test
    fun le_contour_est_centre_dans_le_cadre() {
        val b = Pays.boite(listOf(metropole))
        val p = Pays.place(b, 100.0, 200.0, 800.0, 800.0)
        val gauche = p.x(b.ouest)
        val droite = p.x(b.est)
        val margeG = gauche - 100.0
        val margeD = (100.0 + 800.0) - droite
        assertEquals(margeG, margeD, 1e-6)
    }

    /**
     * The QTH dot must fall inside the drawn outline: the whole point of the
     * map, and a projection error would show at a glance.
     */
    @Test
    fun le_point_du_qth_tombe_dans_le_cadre_et_au_bon_endroit() {
        val b = Pays.boite(listOf(metropole))
        val p = Pays.place(b, 0.0, 0.0, 1000.0, 1000.0)
        val x = p.x(2.35); val y = p.y(48.86)
        assertTrue(x in 0.0..1000.0)
        assertTrue(y in 0.0..1000.0)
        // Paris is north-east of the square's centre: right and above.
        val xc = p.x((b.ouest + b.est) / 2)
        val yc = p.y(b.latMoyenne)
        assertTrue("Paris doit être à droite du centre", x > xc)
        assertTrue("Paris doit être au-dessus du centre", y < yc)
    }

    @Test
    fun le_nord_est_en_haut() {
        val b = Pays.boite(listOf(metropole))
        val p = Pays.place(b, 0.0, 0.0, 1000.0, 1000.0)
        assertTrue(p.y(b.nord) < p.y(b.sud))
    }

    // ---------------------------------------------------------------- misc

    @Test
    fun l_aire_d_un_carre_est_celle_qu_on_attend() {
        val a = Pays.aire(carre(0.0, 0.0, 2.0, 3.0))
        assertTrue(abs(a - 6.0) < 1e-6)
    }

    @Test
    fun l_europe_se_reconnait_a_sa_boite() {
        assertTrue(Pays.enEurope(Pays.boite(listOf(metropole))))
        assertFalse(Pays.enEurope(Pays.boite(listOf(carre(-40.0, 140.0, -10.0, 155.0)))))
    }
}
