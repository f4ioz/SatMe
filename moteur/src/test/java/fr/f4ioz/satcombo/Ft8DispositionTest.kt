/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.Ft8
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Layout of the 77 message bits.
 *
 * The previous tests **could not see** this bug: the grid field was read at
 * bit 58 instead of 59, and the tests wrote to the same wrong place. The
 * round trip agreed on a shared mistake.
 *
 * It only showed when decoding real stations whose answer is known
 * independently: a Hungarian station cannot be in EP93. Hence these tests set
 * **absolute field positions** and check grids known from outside.
 */
class Ft8DispositionTest {

    /** Writes a type 1 message at the positions given by the specification. */
    private fun typeUn(
        appele: String, appelant: String, g15: Int,
        roger: Boolean = false, r1a: Boolean = false, r1b: Boolean = false
    ): BooleanArray {
        val m = BooleanArray(Ft8.BITS_MESSAGE)
        // The first three codes are tokens, not callsigns: 0 = DE, 1 = QRZ, 2 = CQ.
        val code1 = when (appele) {
            "DE" -> 0L; "QRZ" -> 1L; "CQ" -> 2L
            else -> Ft8.indicatifVers28(appele)!!
        }
        Ft8.ecritEntier(m, 0, 28, code1)
        m[28] = r1a
        Ft8.ecritEntier(m, 29, 28, Ft8.indicatifVers28(appelant)!!)
        m[57] = r1b
        m[58] = roger
        Ft8.ecritEntier(m, 59, 15, g15.toLong())
        Ft8.ecritEntier(m, 74, 3, 1L)
        return m
    }

    /** Value of a four-character grid, in mixed radix. */
    private fun valeur(carre: String): Int =
        (carre[0] - 'A') * 18 * 100 + (carre[1] - 'A') * 100 +
            (carre[2] - '0') * 10 + (carre[3] - '0')

    // ---- the field bug ----

    @Test
    fun un_carre_ne_vaut_pas_la_moitie_du_vrai() {
        // Cases heard on air, with each station's real grid. Before the fix,
        // each came out halved.
        val cas = listOf(
            "HA1ZW" to "JN87",     // came out as EP93
            "SP5UFE" to "KO02",    // came out as FH01
            "OH2ZZ" to "KP20",     // came out as FH60
            "IZ2DPX" to "JN45"     // came out as EP72
        )
        for ((indicatif, carre) in cas) {
            val m = typeUn("CQ", indicatif, valeur(carre))
            val lu = Ft8.deplie(m)
            assertNotNull("$indicatif non déplié", lu)
            assertEquals("carré de $indicatif", carre, lu!!.locator)
        }
    }

    @Test
    fun le_bit_roger_ne_deborde_pas_dans_le_carre() {
        // This is the bit the grid field swallowed. Set or clear, the grid
        // must not change at all.
        val g = valeur("JN18")
        val sans = Ft8.deplie(typeUn("CQ", "F4IOZ", g, roger = false))!!
        val avec = Ft8.deplie(typeUn("CQ", "F4IOZ", g, roger = true))!!
        assertEquals("JN18", sans.locator)
        assertEquals("JN18", avec.locator)
    }

    @Test
    fun les_bits_de_suffixe_ne_debordent_pas_non_plus() {
        val g = valeur("IN77")
        val lu = Ft8.deplie(typeUn("CQ", "F4IOZ", g, r1a = true, r1b = true))!!
        assertEquals("IN77", lu.locator)
        assertEquals("F4IOZ", lu.appelant)
    }

    // ---- field bounds ----

    @Test
    fun les_deux_bouts_de_la_grille_tiennent() {
        assertEquals("AA00", Ft8.deplie(typeUn("CQ", "F4IOZ", 0))!!.locator)
        assertEquals("RR99", Ft8.deplie(typeUn("CQ", "F4IOZ", 32_399))!!.locator)
    }

    @Test
    fun au_dela_de_la_grille_ce_ne_sont_plus_des_carres() {
        for (code in listOf(1, 2, 3, 4, 5, 40)) {
            val lu = Ft8.deplie(typeUn("CQ", "F4IOZ", 32_400 + code))!!
            assertNull("le code $code ne doit pas donner de carré", lu.locator)
        }
    }

    // ---- acks and reports ----

    @Test
    fun les_accuses_de_reception_se_lisent() {
        assertEquals("RRR", Ft8.deplie(typeUn("W9XYZ", "F4IOZ", 32_402))!!.brut.split(" ").last())
        assertEquals("RR73", Ft8.deplie(typeUn("W9XYZ", "F4IOZ", 32_403))!!.brut.split(" ").last())
        assertEquals("73", Ft8.deplie(typeUn("W9XYZ", "F4IOZ", 32_404))!!.brut.split(" ").last())
    }

    @Test
    fun un_rapport_se_compte_a_partir_de_trente_deux_mille_quatre_cents() {
        // code = g15 − 32400, then report = code − 35. Applying "− 35" alone
        // displayed +32367.
        val zero = Ft8.deplie(typeUn("W9XYZ", "F4IOZ", 32_400 + 35))!!
        assertEquals(0, zero.rapportDb)
        val moinsDix = Ft8.deplie(typeUn("W9XYZ", "F4IOZ", 32_400 + 25))!!
        assertEquals(-10, moinsDix.rapportDb)
        val plusDix = Ft8.deplie(typeUn("W9XYZ", "F4IOZ", 32_400 + 45))!!
        assertEquals(10, plusDix.rapportDb)
    }

    @Test
    fun un_rapport_reste_dans_les_bornes_du_plausible() {
        // No FT8 report gets near 100 dB: such a number means the field was
        // misread.
        for (code in 5..84) {
            val r = Ft8.deplie(typeUn("W9XYZ", "F4IOZ", 32_400 + code))!!.rapportDb
            if (r != null) {
                assert(r in -40..60) { "rapport aberrant : $r pour le code $code" }
            }
        }
    }

    // ---- whole message ----

    @Test
    fun un_appel_complet_se_relit_mot_pour_mot() {
        val lu = Ft8.deplie(typeUn("CQ", "F4IOZ", valeur("IN77")))!!
        assertEquals("CQ F4IOZ IN77", lu.brut)
        assertEquals("F4IOZ", lu.appelant)
        assertNull("un CQ n'a pas d'appelé", lu.appele)
    }

    @Test
    fun une_reponse_nommee_se_relit_aussi() {
        val lu = Ft8.deplie(typeUn("W9XYZ", "F4IOZ", valeur("JN18")))!!
        assertEquals("W9XYZ F4IOZ JN18", lu.brut)
        assertEquals("F4IOZ", lu.appelant)
        assertEquals("W9XYZ", lu.appele)
    }
}
