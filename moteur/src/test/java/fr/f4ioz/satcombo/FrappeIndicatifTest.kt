/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.Indicatifs
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Typing order.
 *
 * `DL/PA3GAN`, typed letter by letter, came out as `DLA3GAN/P`: once the text
 * held `DL/P` it read as "DL portable" and every following letter went before
 * the slash.
 *
 * The text alone cannot decide: the `/P /M` key and the `/` key produce the
 * same slash. Only the keystroke knows, so that is what we ask.
 */
class FrappeIndicatifTest {

    /** Types a sequence of letters, with the suffix flag as given. */
    private fun tape(debut: String, lettres: String, suffixePose: Boolean = false): String {
        var s = debut
        lettres.forEach { s = Indicatifs.ajoute(s, it, suffixePose) }
        return s
    }

    // ------------------------------------------------ country prefix

    /** The bug reported on FO-29. */
    @Test
    fun un_prefixe_de_pays_s_ecrit_dans_l_ordre() {
        assertEquals("DL/PA3GAN", tape("DL/", "PA3GAN"))
    }

    /** The letter right after the slash is what triggered it. */
    @Test
    fun la_lettre_juste_apres_la_barre_reste_apres() {
        assertEquals("DL/P", tape("DL/", "P"))
    }

    @Test
    fun les_autres_prefixes_suivent_la_meme_regle() {
        assertEquals("F/DF2ET", tape("F/", "DF2ET"))
        assertEquals("EA6/DF2ET", tape("EA6/", "DF2ET"))
        // Nor does an M after the slash make it mobile.
        assertEquals("LA/M0NKC", tape("LA/", "M0NKC"))
    }

    @Test
    fun un_indicatif_sans_barre_s_ecrit_dans_l_ordre() {
        assertEquals("F1FPL", tape("", "F1FPL"))
    }

    // ------------------------------------------------ suffix set

    /**
     * The other half of the rule, and why insertion exists: set /P as soon as
     * you hear it, then finish the callsign in front of it.
     */
    @Test
    fun un_suffixe_pose_reste_au_bout() {
        assertEquals("F4IOZX/P", Indicatifs.ajoute("F4IOZ/P", 'X', suffixePose = true))
    }

    /**
     * /P set **before** the callsign, on an empty field — the operator heard
     * "portable" first. It used to give `/PF4IOZ`.
     */
    @Test
    fun un_indicatif_se_complete_entierement_devant_le_suffixe() {
        assertEquals("F4IOZ/P", tape("/P", "F4IOZ", suffixePose = true))
    }

    /** Country prefix **and** operating suffix: each in its place. */
    @Test
    fun le_suffixe_tient_meme_avec_un_prefixe_devant() {
        assertEquals("DL/PA3GANX/P",
            Indicatifs.ajoute("DL/PA3GAN/P", 'X', suffixePose = true))
    }

    // ------------------------------------------------ parsing the text

    /** What `separe` must keep returning, since everything depends on it. */
    @Test
    fun un_prefixe_n_est_pas_un_suffixe() {
        assertEquals("DL/PA3GAN" to "", Indicatifs.separe("DL/PA3GAN"))
        assertEquals("DL/PA3GAN" to "/P", Indicatifs.separe("DL/PA3GAN/P"))
        assertEquals("F4IOZ" to "/P", Indicatifs.separe("F4IOZ/P"))
    }

    /**
     * `DL/PA3GAN` and `PA3GAN` are distinct: the log must not suggest one's
     * grid square for the other.
     */
    @Test
    fun le_prefixe_fait_une_entree_a_part() {
        assertEquals("DL/PA3GAN", Indicatifs.cle("DL/PA3GAN"))
        assertEquals("PA3GAN", Indicatifs.cle("PA3GAN"))
    }
}
