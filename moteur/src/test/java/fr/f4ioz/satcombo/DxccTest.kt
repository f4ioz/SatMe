/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.Dxcc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/** The country of a callsign, from its prefix. */
class DxccTest {

    @Test
    fun les_prefixes_courants_rendent_leur_pays() {
        assertEquals("France", Dxcc.entite("F4IOZ")?.nom)
        assertEquals("FR", Dxcc.entite("F4IOZ")?.drapeau)
        assertEquals("Allemagne", Dxcc.entite("DL2MF")?.nom)
        assertEquals("Espagne", Dxcc.entite("EA3DIX")?.nom)
        assertEquals("Angleterre", Dxcc.entite("G0ABI")?.nom)
        assertEquals("Japon", Dxcc.entite("JF9SOM")?.nom)
        assertEquals("États-Unis", Dxcc.entite("K5XYZ")?.nom)
        assertEquals("États-Unis", Dxcc.entite("W1AW")?.nom)
    }

    /** Longest prefix wins: EA8 is the Canaries, not Spain. */
    @Test
    fun le_prefixe_le_plus_long_gagne() {
        assertEquals("Canaries", Dxcc.entite("EA8ABC")?.nom)
        assertEquals("Sardaigne", Dxcc.entite("IS0XYZ")?.nom)
        assertEquals("Crète", Dxcc.entite("SV9ABC")?.nom)
        assertEquals("Corse", Dxcc.entite("TK5EP")?.nom)
    }

    /** EA5/F4IOZ is in Spain: the country prefix overrides the home callsign. */
    @Test
    fun le_prefixe_pays_prime() {
        assertEquals("Espagne", Dxcc.entite("EA5/F4IOZ")?.nom)
        assertEquals("Suisse", Dxcc.entite("HB9/DL2MF")?.nom)
    }

    /** /P and /M do not change the country. */
    @Test
    fun le_suffixe_d_exploitation_ne_change_pas_le_pays() {
        assertEquals("France", Dxcc.entite("F4IOZ/P")?.nom)
        assertEquals("Angleterre", Dxcc.entite("G0ABI/M")?.nom)
    }

    @Test
    fun l_ecosse_et_le_pays_de_galles_se_distinguent_de_l_angleterre() {
        assertEquals("Écosse", Dxcc.entite("MM0XYZ")?.nom)
        assertEquals("Pays de Galles", Dxcc.entite("GW4ABC")?.nom)
    }

    @Test
    fun un_prefixe_inconnu_rend_nul_plutot_qu_un_pays_faux() {
        assertNull(Dxcc.entite("QZ9XX"))
        assertNull(Dxcc.entite(""))
    }

    private fun n(indicatif: String) = Dxcc.entite(indicatif)?.adif

    /** The whole current list: 340 entities, each number once, each with its continent and zones. */
    @Test
    fun la_liste_compte_toutes_les_entites_actuelles() {
        val l = Dxcc.actuelles
        assertEquals(340, l.size)
        assertEquals(l.size, l.map { it.adif }.toSet().size)
        assertEquals(l.size, l.map { it.nomAdif }.toSet().size)
        l.forEach { e ->
            assertTrue("$e : continent", e.continent in setOf("EU", "AS", "AF", "NA", "SA", "OC", "AN"))
            assertTrue("$e : zones CQ", e.zonesCq.isNotEmpty() && e.zonesCq.all { it in 1..40 })
            assertTrue("$e : zones UIT", e.zonesItu.isNotEmpty() && e.zonesItu.all { it in 1..90 })
            assertTrue("$e : centre", e.lat != null && e.lon != null && e.lat!! in -90.0..90.0 && e.lon!! in -180.0..180.0)
            assertFalse(e.supprimee)
        }
        // Every prefix of the table leads back to its own entity (no key taken twice).
        l.forEach { e -> e.prefixes.forEach { p -> assertEquals("$p → $e", e.adif, Dxcc.entite(p, simple = true)?.adif) } }
    }

    @Test
    fun les_entites_supprimees_sont_connues_mais_aucun_prefixe_n_y_mene() {
        val rda = Dxcc.parAdif(229)!!
        assertTrue(rda.supprimee)
        assertEquals("GERMAN DEMOCRATIC REPUBLIC", rda.nomAdif)
        assertTrue(rda.prefixes.isEmpty())
        assertTrue(Dxcc.supprimees.size > 40)
        assertTrue(Dxcc.supprimees.none { e -> Dxcc.actuelles.any { it.adif == e.adif } })
        assertEquals("France", Dxcc.parAdif(227)?.nom)
        assertNull(Dxcc.parAdif(9999))
    }

    @Test
    fun numero_adif_continent_et_zones() {
        val f = Dxcc.entite("F4IOZ")!!
        assertEquals(227, f.adif); assertEquals("FRANCE", f.nomAdif); assertEquals(14, f.cq); assertEquals(27, f.itu)
        val us = Dxcc.entite("W1AW")!!
        assertEquals(291, us.adif); assertEquals(listOf(3, 4, 5), us.zonesCq); assertNull(us.cq)
        assertEquals("AN", Dxcc.entite("KC4AAA")?.continent)
    }

    /** The French Southern Lands and Scattered Islands: FT, a digit, the island's letter. */
    @Test
    fun les_terres_australes_par_leur_lettre() {
        assertEquals(41, n("FT4WA"))   // Crozet
        assertEquals("Crozet", Dxcc.entite("FT5WQ")?.nom)
        assertEquals(131, n("FT5XO"))  // Kerguelen
        assertEquals(10, n("FT5ZM"))   // Amsterdam & St Paul
        assertEquals(99, n("FT4GL"))   // Glorioso
        assertEquals(124, n("FT4JA"))  // Juan de Nova
        assertEquals(276, n("FT4TA"))  // Tromelin
        assertEquals(13, n("FT0YA"))   // Adélie Land: Antarctica
        assertNull(Dxcc.entite("FT5ABC")) // FT without a known letter: no guess
    }

    @Test
    fun le_pacifique_et_les_caraibes_americains() {
        assertEquals(166, n("KH0AA")); assertEquals(20, n("KH1/K1ABC")); assertEquals(103, n("KH2L")); assertEquals(123, n("KH3AA"))
        assertEquals(174, n("KH4AB")); assertEquals(197, n("KH5AA")); assertEquals(110, n("KH6ABC")); assertEquals(110, n("NH7A"))
        assertEquals(138, n("KH7KY")); assertEquals(9, n("WH8ABC")); assertEquals(515, n("KH8SI")); assertEquals(297, n("KH9AA"))
        assertEquals(6, n("KL7ABC")); assertEquals(6, n("AL7X"))
        assertEquals(182, n("KP1/W1ABC")); assertEquals(285, n("KP2A")); assertEquals(202, n("KP4ABC")); assertEquals(202, n("NP3A"))
        assertEquals(43, n("KP5/NP3VI"))
        // KG4 with two letters is Guantánamo; KG4ABC an ordinary US call.
        assertEquals(105, n("KG4AW")); assertEquals(291, n("KG4ABC")); assertEquals(105, n("KG4/W1ABC"))
    }

    @Test
    fun les_prefixes_partages_des_antilles_et_d_ailleurs() {
        assertEquals(12, n("VP2EA")); assertEquals(96, n("VP2MXX")); assertEquals(65, n("VP2VI"))
        assertNull(Dxcc.entite("VP2ABC"))
        assertEquals(24, n("3Y0J")); assertEquals(199, n("3Y0X"))
        assertEquals(217, n("CE0X/W1ABC")); assertEquals(47, n("CE0YHF")); assertEquals(125, n("CE0ZIS")); assertEquals(112, n("CE3ABC"))
        assertEquals(56, n("PY0FF")); assertEquals(253, n("PY0SA")); assertEquals(273, n("PY0TA"))
        assertEquals(147, n("VK9LA")); assertEquals(189, n("VK9NS")); assertEquals(35, n("VK9XA")); assertEquals(38, n("VK9CA"))
        assertNull(Dxcc.entite("VK9ABC"))
        assertEquals(111, n("VK0EK")); assertEquals(153, n("VK0MQ")); assertEquals(13, n("VK0AI"))
        assertEquals(192, n("JD1ABC")); assertEquals(177, n("JD1BMM"))
        assertEquals(216, n("HK0ABC")); assertEquals(161, n("HK0NA"))
        assertEquals(301, n("T30A")); assertEquals(31, n("T31A")); assertEquals(48, n("T32A")); assertEquals(490, n("T33A"))
        assertEquals(117, n("4U1ITU")); assertEquals(289, n("4U1UN")); assertEquals(206, n("4U1VIC"))
        assertNull(Dxcc.entite("4U9ABC"))
    }

    /** Russia by call area: 2 Kaliningrad, 8/9/0 Asia, except 9F/9G/9X west of the Urals. */
    @Test
    fun la_russie_selon_la_zone_d_appel() {
        assertEquals(54, n("RA3ABC")); assertEquals(126, n("UA2FZ")); assertEquals(126, n("R2FA"))
        assertEquals(15, n("UA9ABC")); assertEquals(15, n("RA0FF")); assertEquals(15, n("R8TA"))
        assertEquals(54, n("UA9FAR")); assertEquals(54, n("RA9XA"))
        assertEquals(61, n("R1FJA"))
    }

    @Test
    fun drapeau_iso_pour_les_nouvelles_entites() {
        assertEquals("TF", Dxcc.entite("FT5WQ")?.iso)
        assertEquals("FR", Dxcc.entite("FT5WQ")?.drapeau)
        assertEquals("🇭🇲", Dxcc.emoji(Dxcc.entite("VK0EK")))
    }
}
