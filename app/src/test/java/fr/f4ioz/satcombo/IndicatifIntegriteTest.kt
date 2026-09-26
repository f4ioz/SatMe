/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.data.Adif
import fr.f4ioz.satcombo.domain.Indicatifs
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Does the callsign reach the end of the chain intact?
 *
 * Two callsigns once reached the log truncated. The cause was in the screen
 * (field cleared when the queue advanced), but the chain itself had never been
 * checked. It is checked here, link by link.
 */
class IndicatifIntegriteTest {

    private val cas = listOf(
        "F5RRO", "F5RRO/P", "M0NKC", "F/DL2GRC/P", "LA/DF2ET/P",
        "EA6/DF2ET", "OK1UFC", "F4IOZ/M", "9A/S51CD/P", "VK3YY")

    /** Splitting base/suffix loses no character. */
    @Test
    fun separe_puis_recolle_rend_l_original() {
        for (c in cas) {
            val (b, suf) = Indicatifs.separe(c)
            assertEquals(c, b + suf)
        }
    }

    /** The memory key keeps the full callsign. */
    @Test
    fun la_cle_conserve_tout() {
        for (c in cas) assertEquals(c, Indicatifs.cle(c))
    }

    /**
     * Typing one character at a time, as on the keypad: insertion happens
     * before the suffix, and the result must be exact.
     */
    @Test
    fun la_frappe_caractere_par_caractere_reconstruit_l_indicatif() {
        for (c in cas) {
            var saisie = ""
            for (ch in c) {
                if (ch == '/') {
                    // The keypad appends the slash at the end.
                    saisie += "/"
                } else {
                    val (b, suf) = Indicatifs.separe(saisie)
                    saisie = b + ch + suf
                }
            }
            assertEquals(c, saisie)
        }
    }

    /** The ADIF field declares the right length and content. */
    @Test
    fun le_champ_adif_porte_l_indicatif_entier() {
        for (c in cas) {
            val f = Adif.field("CALL", c)
            assertEquals("<CALL:${c.toByteArray().size}>$c", f)
        }
    }
}
