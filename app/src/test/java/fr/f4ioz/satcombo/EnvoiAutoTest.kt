/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.EnvoiAuto
import fr.f4ioz.satcombo.domain.EnvoiAuto.Etat
import org.junit.Assert.assertEquals
import org.junit.Test

/** Each contact sent on after a minute, unless held back. */
class EnvoiAutoTest {

    private val actif = 1_000_000L
    private val qso = actif + 10_000L

    private fun etat(
        maintenant: Long, envoye: Long = 0, retenu: Boolean = false,
        depuis: Long? = actif, modifie: Long? = null, call: String = "F4ABC", t: Long = qso,
    ) = EnvoiAuto.etat(t, call, envoye, retenu, depuis, modifie, maintenant)

    @Test
    fun une_minute_d_attente_puis_pret() {
        assertEquals(Etat.ATTENTE, etat(qso + 59_000))
        assertEquals(1L, EnvoiAuto.resteS(qso, null, qso + 59_000))
        assertEquals(Etat.PRET, etat(qso + 60_000))
    }

    @Test
    fun en_pause_il_ne_part_pas() {
        assertEquals(Etat.PAUSE, etat(qso + 10 * 60_000, retenu = true))
    }

    @Test
    fun une_modification_relance_la_minute() {
        val modif = qso + 50_000
        assertEquals(Etat.ATTENTE, etat(qso + 70_000, modifie = modif))
        assertEquals(Etat.PRET, etat(modif + 60_000, modifie = modif))
    }

    @Test
    fun option_coupee_ou_contact_plus_ancien_ne_partent_pas() {
        // An older log may already be in Wavelog by an ADIF import.
        assertEquals(Etat.HORS, etat(qso + 120_000, depuis = null))
        assertEquals(Etat.HORS, etat(actif + 120_000, t = actif - 1))
        assertEquals(Etat.HORS, etat(qso + 120_000, call = ""))
    }

    @Test
    fun deja_envoye_reste_envoye_meme_en_pause() {
        assertEquals(Etat.ENVOYE, etat(qso + 120_000, envoye = qso + 61_000, retenu = true))
    }
}
