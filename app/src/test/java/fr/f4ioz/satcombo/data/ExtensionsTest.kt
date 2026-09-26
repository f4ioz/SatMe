/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Optional features and their unlock keywords.
 *
 * Two things are covered: what is open stays open whatever is typed in the
 * "Extensions" field (empty field, unknown word, apostrophe must never close
 * a door), and the key mechanism itself still works — NOAA images still use
 * it, and the next untested feature will too.
 */
class ExtensionsTest {

    @Test
    fun ce_qui_est_ouvert_lest_sans_rien_taper() {
        assertEquals(Extensions.OPEN, Extensions.unlocked("", ""))
        assertEquals(Extensions.OPEN, Extensions.unlocked("F1ABC", ""))
    }

    @Test
    fun lindicatif_de_lauteur_nouvre_plus_rien_de_particulier() {
        // The author's callsign once unlocked everything, so he never saw the
        // app as others did — the best way to miss a bug. It is now an
        // ordinary callsign.
        assertEquals(Extensions.OPEN, Extensions.unlocked("F4IOZ", ""))
        assertEquals(Extensions.OPEN, Extensions.unlocked("f4ioz", ""))
        assertEquals(Extensions.OPEN, Extensions.unlocked("F4IOZ/P", ""))
        assertEquals(Extensions.ALL.toSet(), Extensions.unlocked("F4IOZ", "tout"))
    }

    /**
     * **Everything is open without typing anything**, except NOAA images.
     *
     * This test used to assert the opposite for the Breton flags and failed
     * when the rule changed — as intended: an access policy must not change by
     * accident.
     *
     * Untested features are now guarded by a warning banner on their screen,
     * read at the moment of use, rather than by an undocumented key.
     */
    @Test
    fun tout_est_ouvert_sans_rien_taper_sauf_la_base_adif() {
        // Everything is open except NOAA images. (The test name still refers
        // to the built-in ADIF callsign base, since removed.)
        val rien = Extensions.unlocked("F1ABC", "")
        (Extensions.ALL - Extensions.APT).forEach {
            assertTrue("« $it » doit être ouvert", it in rien)
        }
        // NOAA images are the only closed feature, and "noaa" opens it.
        assertTrue(Extensions.APT !in rien)
        assertTrue(Extensions.APT in Extensions.unlocked("F1ABC", "noaa"))
        assertTrue(Extensions.APT in Extensions.unlocked("F1ABC", "NOAA"))
        // The format name does not unlock it: the keyword is the operator's
        // word, not the engineer's.
        assertTrue(Extensions.APT !in Extensions.unlocked("F1ABC", "apt"))
    }

    /**
     * The key mechanism stays working, so the next untested feature can use
     * it without rewriting it.
     */
    @Test
    fun le_mecanisme_de_cle_fonctionne_toujours() {
        assertEquals(Extensions.ALL.toSet(), Extensions.unlocked("F1ABC", "tout"))
        assertEquals(Extensions.ALL.toSet(), Extensions.unlocked("F1ABC", "bêta"))
        assertTrue(Extensions.BZH in Extensions.unlocked("F1ABC", "drapeau bzh"))
    }

    @Test
    fun un_indicatif_voisin_garde_au_moins_ce_qui_est_ouvert() {
        for (call in listOf("F4IOX", "F4I", "F4IO")) {
            assertTrue("indicatif « $call »",
                Extensions.unlocked(call, "").containsAll(Extensions.OPEN))
        }
    }

    @Test
    fun un_mot_cle_najoute_rien_a_ce_qui_est_deja_ouvert() {
        // The word is still accepted; it just changes nothing while its
        // feature is open to all.
        assertEquals(Extensions.OPEN, Extensions.unlocked("F1ABC", "sstv"))
        assertEquals(Extensions.OPEN, Extensions.unlocked("F1ABC", "sdr"))
    }

    @Test
    fun la_ponctuation_ne_ferme_pas_la_porte() {
        for (code in listOf("sstv sdr", "sstv,sdr", "sstv, sdr", "sstv;sdr",
                            "sstv+sdr", "sstv/sdr", "  SSTV   SDR  ", "SsTv,SdR")) {
            assertTrue("code « $code »",
                Extensions.unlocked("F1ABC", code).containsAll(Extensions.OPEN))
        }
    }


    @Test
    fun un_mot_inconnu_est_ignore_sans_rien_casser() {
        assertEquals(Extensions.OPEN, Extensions.unlocked("F1ABC", "cat wefax"))
        assertEquals(Extensions.OPEN, Extensions.unlocked("F1ABC", "n'importe quoi"))
    }

    @Test
    fun le_raccourci_de_lecture_dit_la_meme_chose() {
        assertTrue(Extensions.isUnlocked(Extensions.SDR, "F4IOZ", ""))
        assertTrue(Extensions.isUnlocked(Extensions.FLAG, "F1ABC", "drapeau"))
        assertTrue(Extensions.isUnlocked(Extensions.SSTV, "F1ABC", ""))
        // NOAA images are closed: the shortcut must say so too, and open with
        // the keyword.
        assertFalse(Extensions.isUnlocked(Extensions.APT, "", ""))
        assertTrue(Extensions.isUnlocked(Extensions.APT, "F1ABC", "noaa"))
    }

    @Test
    fun la_liste_des_extensions_est_sans_doublon_et_en_minuscules() {
        assertEquals(Extensions.ALL.size, Extensions.ALL.toSet().size)
        Extensions.ALL.forEach { assertEquals(it, it.lowercase()) }
    }

    @Test
    fun tout_ce_qui_est_ouvert_est_une_extension_connue() {
        assertTrue(Extensions.ALL.containsAll(Extensions.OPEN))
    }
    /**
     * Deliberate choice: a master word opens everything, NOAA images included.
     * (This test once targeted the ADIF base, since removed; same intent on the
     * only feature still closed.)
     */
    @Test
    fun le_mot_maitre_ouvre_aussi_les_images_noaa() {
        assertTrue(Extensions.APT in Extensions.unlocked("F1ABC", "beta"))
    }
}