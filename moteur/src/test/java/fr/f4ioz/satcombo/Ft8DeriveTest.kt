/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.Ft8Decodeur
import fr.f4ioz.satcombo.domain.Ft8Signal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * A receiver whose oscillator moves under the slot (QO-100, 21 October: a
 * new LNB warming walked 8–12 Hz/s, and the beacon calibration retuned by
 * tens to hundreds of hertz a second): the whole band slides, or jumps, at
 * once.
 */
class Ft8DeriveTest {

    private val profonde = Ft8Decodeur.Profondeur.PROFONDE

    private fun lus(s: Ft8Banc.Scenario, cherche: Boolean = true, p: Ft8Decodeur.Profondeur? = profonde): Set<String> =
        Ft8Decodeur.decode(Ft8Banc.audio(s), s.mode.cadenceHz, s.mode, profondeur = p, chercheDerive = cherche)
            .map { it.message.brut }.toSet()

    private fun bande(graine: Long, pente: Double = 0.0, saut: Double = 0.0, sautS: Double = 0.0) = Ft8Banc.Scenario(
        "derive", (0 until 10).map { i ->
            Ft8Banc.station("CQ", listOf("F4IOZ", "G0ABC", "DL1XYZ", "EA4AB", "ON4KST", "PA3GAN", "I2ABC", "OK1DEF", "SP9XYZ", "HB9ABC")[i],
                "JN18", 400.0 + i * 230.0, -10.0 + i % 3 * 4.0, dt = (i % 4) * 0.2)
        }, penteCommuneHzS = pente, graine = graine, sautHz = saut, sautS = sautS)

    @Test
    fun la_derive_commune_du_creneau_est_retrouvee() {
        for (p in listOf(-12.0, -6.0, 8.0, 14.0)) {
            val s = bande(300L + p.toLong(), pente = p)
            val e = Ft8Signal.penteCommune(Ft8Signal.spectrogramme(Ft8Banc.audio(s), s.mode))
            assertTrue("$p Hz/s lu $e", abs(e - p) <= 0.75)
        }
        // A steady band: none.
        val droit = bande(310L)
        assertEquals(0.0, Ft8Signal.penteCommune(Ft8Signal.spectrogramme(Ft8Banc.audio(droit), droit.mode)), 0.0)
    }

    @Test
    fun le_bruit_seul_ne_donne_pas_de_derive() {
        for (g in 1..6) {
            val s = Ft8Banc.Scenario("bruit", emptyList(), graine = 900L + g)
            assertEquals(0.0, Ft8Signal.penteCommune(Ft8Signal.spectrogramme(Ft8Banc.audio(s), s.mode)), 0.0)
        }
    }

    @Test
    fun un_lnb_qui_derive_vite_est_decode_quand_meme() {
        val s = bande(320L, pente = 10.0)
        val attendus = s.attendus
        // Each candidate's own search (±4 Hz/s) does not reach it: nothing, or almost.
        assertTrue(lus(s, cherche = false).size <= 2)
        val r = lus(s)
        assertTrue("${r.size} : $r", r.size >= 9 && attendus.containsAll(r))
        // The light budget (the phone's) does not look: unchanged, no cost.
        assertTrue(lus(s, p = Ft8Decodeur.Profondeur.LEGERE).size <= 2)
    }

    @Test
    fun une_bande_immobile_ne_coute_pas_une_deuxieme_lecture() {
        val s = bande(330L)
        assertEquals(lus(s, cherche = false), lus(s))
        assertEquals(10, lus(s).size)
    }

    @Test
    fun un_saut_de_calage_pendant_les_emissions_les_perd_entre_elles_ne_coute_rien() {
        // 60 Hz, ten tones, at once: what a calibration write does to every station.
        val pendant = lus(bande(340L, saut = 60.0, sautS = 7.0))
        assertTrue("$pendant", pendant.size <= 2)
        // The same step in the slot's last second, after the transmissions: all of them.
        val apres = lus(bande(340L, saut = 60.0, sautS = 14.3))
        assertEquals(10, apres.size)
    }
}
