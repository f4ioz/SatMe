/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.Qo100
import fr.f4ioz.satcombo.domain.SoleilQo100
import fr.f4ioz.satcombo.domain.SunCalc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date

/**
 * L'alignement de la parabole par le Soleil.
 *
 * Ces essais ont une particularité : ils épinglent des dates. Le 2 mars 2026 à
 * 10 h 18 TU, depuis Paris, le Soleil passe à deux centièmes de degré du
 * satellite. Ce n'est pas une valeur choisie pour faire passer le code, c'est
 * une prédiction vérifiable — et si un jour le modèle solaire est retouché,
 * c'est exactement le genre de chose qu'on veut voir bouger avant que
 * quelqu'un ne monte sur un toit.
 *
 * Les deux fenêtres trouvées — fin février / début mars, et début octobre —
 * encadrent les équinoxes, ce qui est la signature d'un transit solaire sur un
 * géostationnaire. Un résultat qui tomberait en juin ou en décembre serait
 * faux quelle que soit la précision du calcul.
 */
class SoleilQo100Test {

    private val parisLat = 48.8566
    private val parisLon = 2.3522
    private val nyLat = 40.7128
    private val nyLon = -74.0060

    private fun ms(iso: String): Long = Instant.parse(iso).toEpochMilli()

    private fun jourTu(t: Long): String =
        Instant.ofEpochMilli(t).atZone(ZoneOffset.UTC).toLocalDate().toString()

    private fun minuteTu(t: Long): String =
        Instant.ofEpochMilli(t).atZone(ZoneOffset.UTC).toLocalTime()
            .withSecond(0).withNano(0).toString()

    // --- L'écart angulaire ------------------------------------------------

    /**
     * Le cas qui casse toutes les implémentations naïves : deux directions
     * identiques. Le produit scalaire vaut alors 1,000000000000002 par
     * arrondi, et un arc-cosinus non borné rend NaN — précisément sur la seule
     * valeur qui compte, puisque c'est celle du transit parfait.
     */
    @Test
    fun deux_directions_identiques_donnent_un_ecart_nul_et_pas_un_nan() {
        val e = SoleilQo100.ecartDeg(149.942, 29.533, 149.942, 29.533)
        assertFalse("l'écart est NaN", e.isNaN())
        // Un dix-millième de degré : l'arc-cosinus perd ses chiffres près de
        // zéro — son argument est plat au voisinage de 1, donc l'erreur
        // d'arrondi du produit scalaire y est amplifiée d'un facteur cent
        // millions. Un millionième de degré d'erreur sur un disque solaire qui
        // en fait un demi : la formule est parfaitement suffisante ici, et
        // elle serait à revoir pour de l'astrométrie.
        assertEquals(0.0, e, 1e-4)
    }

    @Test
    fun l_ecart_angulaire_suit_la_geometrie() {
        // Deux points à l'horizon, à 90° d'azimut l'un de l'autre.
        assertEquals(90.0, SoleilQo100.ecartDeg(0.0, 0.0, 90.0, 0.0), 1e-9)
        // Le zénith est à 90° de tout l'horizon, quel que soit l'azimut.
        assertEquals(90.0, SoleilQo100.ecartDeg(0.0, 90.0, 217.0, 0.0), 1e-9)
        // Deux points diamétralement opposés à l'horizon.
        assertEquals(180.0, SoleilQo100.ecartDeg(0.0, 0.0, 180.0, 0.0), 1e-9)
        // L'écart d'azimut compte d'autant moins qu'on monte : à 80°
        // d'élévation, dix degrés d'azimut ne font pas deux degrés d'écart.
        assertTrue(SoleilQo100.ecartDeg(100.0, 80.0, 110.0, 80.0) < 2.0)
    }

    // --- Le Soleil, azimut compris ---------------------------------------

    /**
     * Le modèle solaire n'avait jusqu'ici que l'élévation. On lui a ajouté
     * l'azimut, et la première chose à vérifier est que l'élévation n'a pas
     * bougé : [SunCalc.elevationDeg] est utilisé par la prédiction de passages
     * pour décider si un satellite est éclairé, et un décalage passerait
     * inaperçu longtemps.
     */
    @Test
    fun l_elevation_du_soleil_est_inchangee_par_l_ajout_de_l_azimut() {
        val t = Date(ms("2026-07-21T10:00:00Z"))
        listOf(48.8566 to 2.3522, -33.92 to 18.42, 0.0 to 0.0, 70.0 to -50.0).forEach {
            val (la, lo) = it
            assertEquals(
                SunCalc.azElDeg(la, lo, t)[1],
                SunCalc.elevationDeg(la, lo, t),
                1e-12,
            )
        }
    }

    /**
     * Le midi solaire au méridien de Greenwich, au solstice d'été : le Soleil
     * est au sud, et à 90° − latitude + 23,44° de hauteur. C'est la
     * vérification que l'azimut n'est pas décalé d'un quadrant — l'erreur
     * classique quand on se trompe de signe sur l'angle horaire.
     */
    @Test
    fun au_midi_solaire_le_soleil_est_au_sud() {
        val p = SunCalc.azElDeg(48.8566, 0.0, Date(ms("2026-06-21T12:00:00Z")))
        // Un degré d'écart au sud : l'équation du temps, qui vaut une poignée
        // de minutes en juin et qu'on ne cherche pas à corriger.
        assertEquals(179.0, p[0], 1.5)
        assertEquals(90.0 - 48.8566 + 23.44, p[1], 0.2)
    }

    // --- Le passage en azimut, tous les jours ----------------------------

    /**
     * Le geste de tous les jours : à cette minute-là, l'ombre d'un piquet
     * vertical est dans l'axe de la parabole, à 180° près.
     *
     * On épingle la minute, et surtout on vérifie la propriété qui fait le
     * service — l'azimut du Soleil est bien celui du satellite, à un
     * centième de degré, ce qui est cent fois mieux qu'une boussole de
     * téléphone.
     */
    @Test
    fun le_passage_en_azimut_du_21_juillet_2026_depuis_paris() {
        val t = SoleilQo100.prochainPassageEnAzimut(
            parisLat, parisLon, ms("2026-07-21T00:00:00Z"))
        assertNotNull(t)
        assertEquals("2026-07-21", jourTu(t!!))
        assertEquals("10:52", minuteTu(t))

        val sat = Qo100.pointage(parisLat, parisLon)
        val soleil = SunCalc.azElDeg(parisLat, parisLon, Date(t))
        assertEquals(sat.azDeg, soleil[0], 0.01)
        assertTrue("le Soleil doit être levé", soleil[1] > 0.0)
        assertTrue(SoleilQo100.memeAzimut(soleil[0], sat.azDeg))

        // En juillet le Soleil est bien plus haut que le satellite : c'est
        // pourquoi ce passage-là règle l'azimut et rien d'autre.
        assertTrue(soleil[1] > sat.elDeg + 20.0)
    }

    /**
     * Il y en a un chaque jour de l'année, et c'est tout l'intérêt : on n'a
     * pas à attendre l'équinoxe pour régler son azimut.
     */
    @Test
    fun il_y_a_un_passage_en_azimut_tous_les_jours_de_l_annee() {
        val sat = Qo100.pointage(parisLat, parisLon)
        listOf("2026-01-15", "2026-04-15", "2026-06-21", "2026-09-15", "2026-12-21").forEach { j ->
            val t = SoleilQo100.prochainPassageEnAzimut(
                parisLat, parisLon, ms(j + "T00:00:00Z"), jours = 1)
            assertNotNull("aucun passage le $j", t)
            assertEquals("le passage n'est pas le jour demandé", j, jourTu(t!!))
            val soleil = SunCalc.azElDeg(parisLat, parisLon, Date(t))
            assertEquals(sat.azDeg, soleil[0], 0.01)
            assertTrue(soleil[1] > 0.0)
        }
    }

    /**
     * Depuis New York le satellite est seize degrés sous l'horizon — cent
     * degrés de longitude d'écart, c'est vingt de trop. Aucune ombre ne pointe
     * vers quoi que ce soit, et la fonction doit le dire plutôt que de rendre
     * une heure qui n'a pas de sens.
     */
    @Test
    fun sans_satellite_visible_il_n_y_a_ni_passage_ni_transit() {
        assertTrue(Qo100.pointage(nyLat, nyLon).elDeg < 0.0)
        assertNull(SoleilQo100.prochainPassageEnAzimut(nyLat, nyLon, ms("2026-03-01T00:00:00Z")))
        assertTrue(SoleilQo100.prochainsTransits(
            nyLat, nyLon, ms("2026-01-01T00:00:00Z")).isEmpty())
    }

    // --- Les transits, deux fois l'an ------------------------------------

    /**
     * Les dix jours de 2026 où, depuis Paris, le Soleil passe à moins d'un
     * degré du satellite. Cinq autour du 2 mars, cinq autour du 11 octobre :
     * les deux équinoxes, comme il se doit pour un géostationnaire.
     *
     * Le meilleur jour du printemps passe à 0,02°, celui de l'automne à
     * 0,04° — soit un vingtième de la largeur du disque solaire. Ce jour-là,
     * l'ombre de la source se centre au fond de la parabole quand le pointage
     * est juste, et c'est le réglage le plus fin qu'on puisse faire sans
     * mesurer de signal.
     */
    @Test
    fun les_transits_solaires_de_2026_depuis_paris() {
        val transits = SoleilQo100.prochainsTransits(
            parisLat, parisLon, ms("2026-01-01T00:00:00Z"), jours = 400, maximum = 20)

        assertEquals(10, transits.size)
        assertEquals(
            listOf("2026-02-28", "2026-03-01", "2026-03-02", "2026-03-03", "2026-03-04",
                "2026-10-09", "2026-10-10", "2026-10-11", "2026-10-12", "2026-10-13"),
            transits.map { jourTu(it.instantMs) },
        )

        val printemps = transits[2]
        assertEquals("2026-03-02", jourTu(printemps.instantMs))
        assertEquals("10:18", minuteTu(printemps.instantMs))
        assertEquals(0.019, printemps.ecartDeg, 0.01)

        val automne = transits[7]
        assertEquals("2026-10-11", jourTu(automne.instantMs))
        assertEquals("09:53", minuteTu(automne.instantMs))
        assertEquals(0.042, automne.ecartDeg, 0.01)

        // Le meilleur des deux est celui de mars : deux centièmes de degré,
        // soit un vingt-cinquième du diamètre du disque solaire.
        assertEquals(printemps.ecartDeg, transits.minOf { it.ecartDeg }, 1e-12)
    }

    /**
     * Ce qu'un transit doit garantir pour être utilisable : le Soleil est bien
     * là où est le satellite, la fenêtre encadre l'instant, et elle dure assez
     * pour qu'on ait le temps de tourner une parabole sans durer si longtemps
     * qu'elle ne voudrait plus rien dire.
     */
    @Test
    fun chaque_transit_est_coherent_avec_lui_meme() {
        val sat = Qo100.pointage(parisLat, parisLon)
        val transits = SoleilQo100.prochainsTransits(
            parisLat, parisLon, ms("2026-01-01T00:00:00Z"), jours = 400, maximum = 20)
        assertTrue(transits.isNotEmpty())

        transits.forEach { tr ->
            assertTrue("écart au-dessus du seuil", tr.ecartDeg <= 1.0)
            assertEquals(sat.azDeg, tr.azSoleilDeg, 1.2)
            assertEquals(sat.elDeg, tr.elSoleilDeg, 1.2)
            // L'écart annoncé est bien celui qu'on recalcule à cet instant.
            assertEquals(
                tr.ecartDeg,
                SoleilQo100.ecartDeg(tr.azSoleilDeg, tr.elSoleilDeg, sat.azDeg, sat.elDeg),
                1e-9,
            )
            assertTrue("la fenêtre n'encadre pas l'instant",
                tr.debutMs <= tr.instantMs && tr.instantMs <= tr.finMs)
            assertTrue("fenêtre vide", tr.dureeS > 0)
            // Le Soleil parcourt un quart de degré par minute : une fenêtre à
            // un degré ne peut pas durer une demi-heure.
            assertTrue("fenêtre de ${tr.dureeS} s, invraisemblable", tr.dureeS < 1800)
        }
    }

    /**
     * Le nombre demandé est une borne, pas une suggestion : l'écran n'affiche
     * que les prochaines dates et n'a aucune raison de balayer l'année pour
     * les jeter ensuite.
     */
    @Test
    fun le_nombre_de_transits_rendus_est_borne() {
        val trois = SoleilQo100.prochainsTransits(
            parisLat, parisLon, ms("2026-01-01T00:00:00Z"), jours = 400, maximum = 3)
        assertEquals(3, trois.size)
        assertEquals("2026-02-28", jourTu(trois.first().instantMs))
        // Et ils sont rendus dans l'ordre chronologique.
        trois.zipWithNext().forEach { (a, b) ->
            assertTrue(a.instantMs < b.instantMs)
        }
    }

    /**
     * Un seuil plus serré donne moins de jours, jamais plus. La propriété a
     * l'air évidente ; elle ne l'est pas si le bord de fenêtre et le minimum
     * ne sont pas calculés avec le même seuil.
     */
    @Test
    fun un_seuil_plus_serre_ne_donne_jamais_plus_de_jours() {
        val large = SoleilQo100.prochainsTransits(
            parisLat, parisLon, ms("2026-01-01T00:00:00Z"),
            jours = 120, seuilDeg = 1.0, maximum = 20)
        val serre = SoleilQo100.prochainsTransits(
            parisLat, parisLon, ms("2026-01-01T00:00:00Z"),
            jours = 120, seuilDeg = 0.3, maximum = 20)
        assertTrue(serre.size <= large.size)
        assertTrue(serre.isNotEmpty())
        assertEquals("2026-03-02", jourTu(serre.first().instantMs))
        // Et une fenêtre plus serrée est une fenêtre plus courte.
        assertTrue(serre.first().dureeS < large[2].dureeS)
    }

    /**
     * L'ombre part à l'opposé du satellite. C'est la seule ligne du dispositif
     * où une erreur de signe retourne la parabole à 180°, et rien à l'écran ne
     * le dirait.
     */
    @Test
    fun l_ombre_est_a_l_oppose_du_satellite() {
        assertEquals(329.942, SoleilQo100.azimutDeLOmbre(149.942), 1e-9)
        assertEquals(10.0, SoleilQo100.azimutDeLOmbre(190.0), 1e-9)
        assertEquals(0.0, SoleilQo100.azimutDeLOmbre(180.0), 1e-9)
        // Et l'opération est bien une involution.
        listOf(0.0, 45.0, 149.942, 200.0, 359.9).forEach {
            assertEquals(it, SoleilQo100.azimutDeLOmbre(SoleilQo100.azimutDeLOmbre(it)), 1e-9)
        }
    }
}
