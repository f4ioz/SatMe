/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.BoussoleWit
import fr.f4ioz.satcombo.domain.GattWit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Bluetooth compass.
 *
 * This does not prove the module talks (only a real frame in the field will).
 * It proves that **if** bytes arrive as documented we get the right angle, and
 * above all that a split, merged or noisy stream does not make the needle lie.
 */
class BoussoleWitTest {

    /** Builds a 0x55 0x61 frame with the given three angles. */
    private fun trame(roulis: Float, tangage: Float, lacet: Float): ByteArray {
        val o = ByteArray(BoussoleWit.LONGUEUR)
        o[0] = 0x55; o[1] = 0x61.toByte()
        fun pose(i: Int, deg: Float) {
            val brut = Math.round(deg / 180f * 32768f)
            o[i] = (brut and 0xFF).toByte()
            o[i + 1] = ((brut shr 8) and 0xFF).toByte()
        }
        pose(14, roulis); pose(16, tangage); pose(18, lacet)
        return o
    }

    // ---- the frame ----

    @Test
    fun une_trame_conforme_rend_ses_trois_angles() {
        val a = BoussoleWit.litAttitude(trame(12f, -34f, 123f))
        assertNotNull(a)
        assertEquals(12f, a!!.roulis, 0.02f)
        assertEquals(-34f, a.tangage, 0.02f)
        assertEquals(123f, a.lacet, 0.02f)
    }

    @Test
    fun les_angles_negatifs_passent_par_le_complement_a_deux() {
        val a = BoussoleWit.litAttitude(trame(0f, 0f, -90f))!!
        assertEquals(-90f, a.lacet, 0.02f)
    }

    @Test
    fun une_trame_tronquee_ne_rend_rien() {
        val court = trame(0f, 0f, 45f).copyOf(19)
        assertNull(BoussoleWit.litAttitude(court))
    }

    @Test
    fun un_mauvais_drapeau_ne_rend_rien() {
        val t = trame(0f, 0f, 45f)
        t[1] = 0x71   // magnetic field: same length, other content
        assertNull(BoussoleWit.litAttitude(t))
    }

    // ---- reassembly ----

    @Test
    fun une_trame_coupee_en_deux_est_recollee() {
        val acc = BoussoleWit.Accumulateur()
        val t = trame(0f, 0f, 200f - 360f)   // −160°
        assertTrue(acc.verse(t.copyOfRange(0, 9)).isEmpty())
        val sorties = acc.verse(t.copyOfRange(9, 20))
        assertEquals(1, sorties.size)
        assertEquals(-160f, sorties[0].lacet, 0.02f)
    }

    @Test
    fun deux_trames_dans_une_seule_notification() {
        val acc = BoussoleWit.Accumulateur()
        val sorties = acc.verse(trame(0f, 0f, 10f) + trame(0f, 0f, 20f))
        assertEquals(2, sorties.size)
        assertEquals(10f, sorties[0].lacet, 0.02f)
        assertEquals(20f, sorties[1].lacet, 0.02f)
    }

    @Test
    fun du_bruit_en_tete_ne_desynchronise_pas() {
        val acc = BoussoleWit.Accumulateur()
        val sorties = acc.verse(byteArrayOf(0x12, 0x55, 0x00, 0x7F) + trame(0f, 0f, 77f))
        assertEquals(1, sorties.size)
        assertEquals(77f, sorties[0].lacet, 0.02f)
    }

    @Test
    fun une_trame_de_champ_magnetique_est_sautee_sans_perdre_la_suivante() {
        val acc = BoussoleWit.Accumulateur()
        val mag = ByteArray(BoussoleWit.LONGUEUR).also { it[0] = 0x55; it[1] = 0x71 }
        val sorties = acc.verse(mag + trame(0f, 0f, 33f))
        assertEquals(1, sorties.size)
        assertEquals(33f, sorties[0].lacet, 0.02f)
    }

    @Test
    fun le_tampon_ne_gonfle_pas_indefiniment() {
        val acc = BoussoleWit.Accumulateur(plafond = 64)
        repeat(50) { acc.verse(ByteArray(20) { 0x01 }) }
        assertTrue(acc.enAttente <= 64)
    }

    @Test
    fun une_trame_complete_ne_reste_pas_dans_le_tampon() {
        val acc = BoussoleWit.Accumulateur()
        acc.verse(trame(0f, 0f, 5f))
        assertEquals(0, acc.enAttente)
    }

    // ---- heading ----

    @Test
    fun le_lacet_negatif_devient_un_azimut_de_boussole() {
        assertEquals(270f, BoussoleWit.azimutDepuisLacet(-90f), 0.01f)
        assertEquals(0f, BoussoleWit.azimutDepuisLacet(0f), 0.01f)
        assertEquals(180f, BoussoleWit.azimutDepuisLacet(180f), 0.01f)
    }

    @Test
    fun le_calage_deplace_le_zero() {
        assertEquals(100f, BoussoleWit.azimutDepuisLacet(90f, offsetDeg = 10f), 0.01f)
        // And wraps through zero cleanly.
        assertEquals(5f, BoussoleWit.azimutDepuisLacet(355f, offsetDeg = 10f), 0.01f)
    }

    @Test
    fun le_sens_inverse_retourne_la_rotation() {
        assertEquals(270f, BoussoleWit.azimutDepuisLacet(90f, inverse = true), 0.01f)
        assertEquals(90f, BoussoleWit.azimutDepuisLacet(-90f, inverse = true), 0.01f)
    }

    @Test
    fun le_calage_deduit_dun_releve_ramene_bien_sur_lazimut_vise() {
        val lacet = 37f
        val vise = 145f
        val cal = BoussoleWit.calageDepuisReleve(lacet, vise)
        assertEquals(vise, BoussoleWit.azimutDepuisLacet(lacet, cal), 0.01f)
    }

    @Test
    fun le_calage_deduit_reste_lisible_pour_un_petit_ecart() {
        // Module reads 3° high: we want to see −3, not 357.
        val cal = BoussoleWit.calageDepuisReleve(3f, 0f)
        assertEquals(-3f, cal, 0.01f)
        assertTrue(cal > -180f && cal <= 180f)
    }

    @Test
    fun le_calage_deduit_tient_compte_du_sens_inverse() {
        val cal = BoussoleWit.calageDepuisReleve(37f, 145f, inverse = true)
        assertEquals(145f, BoussoleWit.azimutDepuisLacet(37f, cal, inverse = true), 0.01f)
    }

    // ---- smoothing ----

    @Test
    fun le_lissage_part_de_la_premiere_valeur() {
        assertEquals(42f, BoussoleWit.lisse(Float.NaN, 42f), 0.001f)
    }

    @Test
    fun le_lissage_passe_par_le_plus_court_chemin() {
        // 359 → 1: two degrees to cross, not 358.
        val r = BoussoleWit.lisse(359f, 1f, k = 0.5f)
        assertEquals(0f, r, 0.01f)
    }

    @Test
    fun le_lissage_reste_dans_le_tour() {
        var v = 350f
        repeat(20) { v = BoussoleWit.lisse(v, 10f) }
        assertTrue(v >= 0f && v < 360f)
    }

    // ---- identifiers ----

    @Test
    fun la_base_des_uuid_est_celle_de_witmotion_pas_la_normalisee() {
        // The trap: 9a, not 9b. The standard base finds nothing.
        assertTrue(GattWit.SERVICE.endsWith("00805f9a34fb"))
        assertTrue(GattWit.NOTIFICATION.endsWith("00805f9a34fb"))
        assertTrue(GattWit.ECRITURE.endsWith("00805f9a34fb"))
        // The subscription descriptor, though, is standard: 9b.
        assertTrue(GattWit.CCCD.endsWith("00805f9b34fb"))
    }

    @Test
    fun les_noms_de_la_famille_sont_reconnus() {
        assertTrue(GattWit.nomPlausible("WT901BLE68"))
        assertTrue(GattWit.nomPlausible("WT9011DCL"))
        assertTrue(GattWit.nomPlausible("HWT905"))
        assertFalse(GattWit.nomPlausible("FT-817"))
        assertFalse(GattWit.nomPlausible(null))
    }
}
