/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
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
 * Les contours de pays de la photo QRV.
 *
 * On travaille ici sur des carrés fabriqués plutôt que sur les vrais contours :
 * un essai qui dépend d'un fichier de 460 Ko cesse de dire ce qu'il vérifie. Les
 * formes sont grossières, les questions sont exactes.
 */
class PaysTest {

    /** Un carré, aplati en lat, lon, lat, lon… comme les anneaux du catalogue. */
    private fun carre(sud: Double, ouest: Double, nord: Double, est: Double) =
        doubleArrayOf(sud, ouest, sud, est, nord, est, nord, ouest)

    // Une « métropole » et son « île », séparées de trois degrés.
    private val metropole = carre(42.0, -5.0, 51.0, 8.0)
    private val ile = carre(41.3, 8.5, 43.0, 9.6)
    // Un territoire lointain, de l'autre côté de l'Atlantique.
    private val lointain = carre(2.0, -54.0, 6.0, -52.0)
    private val bleu = Pays.Contour("FR", "France", listOf(metropole, ile, lointain))
    private val voisin = Pays.Contour("ES", "Espagne", listOf(carre(36.0, -9.0, 43.5, 3.0)))

    // ------------------------------------------------------------- appartenance

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
     * Un opérateur en portable est souvent sur une plage, une jetée ou une
     * pointe — c'est-à-dire hors du contour une fois celui-ci simplifié. Rendre
     * « aucun pays » le laisserait sans carte alors qu'il est chez lui.
     */
    @Test
    fun un_point_juste_en_mer_retombe_sur_le_pays_le_plus_proche() {
        val c = Pays.trouve(listOf(bleu, voisin), 48.5, -6.2)
        assertNotNull(c)
        assertEquals("FR", c?.code)
    }

    /** Mais le milieu de l'Atlantique n'appartient à personne. */
    @Test
    fun le_large_n_appartient_a_aucun_pays() {
        assertNull(Pays.trouve(listOf(bleu, voisin), 45.0, -40.0))
    }

    // ------------------------------------------------------- morceaux à dessiner

    /**
     * Le cœur de l'affaire, et ce qu'aucune liste de territoires n'aurait fait
     * proprement : depuis la métropole on veut la métropole **et** l'île, mais
     * pas le territoire d'outre-mer, qui rendrait la carte illisible.
     */
    @Test
    fun depuis_la_metropole_on_dessine_la_metropole_et_son_ile() {
        val m = Pays.morceauxAutour(bleu, 48.86, 2.35)
        assertEquals(2, m.size)
        assertTrue(m.contains(metropole))
        assertTrue(m.contains(ile))
        assertFalse(m.contains(lointain))
    }

    /** Et depuis l'outre-mer, on ne dessine que l'outre-mer. */
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

    // ------------------------------------------------------------------- boîte

    @Test
    fun la_boite_englobe_tous_les_morceaux() {
        val b = Pays.boite(listOf(metropole, ile))
        assertEquals(41.3, b.sud, 1e-9)
        assertEquals(51.0, b.nord, 1e-9)
        assertEquals(-5.0, b.ouest, 1e-9)
        assertEquals(9.6, b.est, 1e-9)
    }

    // -------------------------------------------------------------- placement

    /**
     * La longitude doit être comprimée par le cosinus de la latitude. Sans
     * cela, la France apparaît environ un tiers trop large — c'est l'erreur
     * classique quand on projette des degrés directement en pixels.
     */
    @Test
    fun la_longitude_est_comprimee_selon_la_latitude() {
        val b = Pays.boite(listOf(metropole))
        val p = Pays.place(b, 0.0, 0.0, 1000.0, 1000.0)
        // À 46,5° de latitude moyenne, le facteur vaut environ 0,69.
        assertEquals(0.69, p.compression, 0.02)
    }

    /**
     * L'échelle est la même dans les deux sens : un pays doit garder sa forme,
     * pas être étiré au cadre.
     */
    @Test
    fun le_pays_garde_sa_forme_dans_un_cadre_allonge() {
        val b = Pays.boite(listOf(metropole))
        val p = Pays.place(b, 0.0, 0.0, 2000.0, 500.0)
        val largeurRendue = (b.largeur * p.compression) * p.echelle
        val hauteurRendue = b.hauteur * p.echelle
        val rapportReel = (b.largeur * p.compression) / b.hauteur
        assertEquals(rapportReel, largeurRendue / hauteurRendue, 1e-6)
        // Et rien ne déborde du cadre.
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
     * Le point du QTH doit tomber à l'intérieur du contour dessiné : c'est tout
     * l'intérêt de la carte, et une erreur de projection s'y verrait au premier
     * coup d'œil.
     */
    @Test
    fun le_point_du_qth_tombe_dans_le_cadre_et_au_bon_endroit() {
        val b = Pays.boite(listOf(metropole))
        val p = Pays.place(b, 0.0, 0.0, 1000.0, 1000.0)
        val x = p.x(2.35); val y = p.y(48.86)
        assertTrue(x in 0.0..1000.0)
        assertTrue(y in 0.0..1000.0)
        // Paris est au nord-est du centre du carré : à droite et au-dessus.
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

    // ---------------------------------------------------------------- divers

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
