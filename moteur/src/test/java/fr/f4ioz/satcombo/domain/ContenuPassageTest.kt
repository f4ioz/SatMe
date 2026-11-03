/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import fr.f4ioz.satcombo.domain.ContenuPassage as C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a journal pass holds, counted by kind: the list's badges and its filter. */
class ContenuPassageTest {

    @Test
    fun rien_ne_donne_aucune_pastille() {
        assertEquals(emptyMap<String, Int>(), C.resume(C.Elements()))
        assertEquals("{}", C.json(emptyMap()))
    }

    @Test
    fun chaque_genre_compte_et_dans_l_ordre_de_la_liste() {
        val r = C.resume(C.Elements(imagesSstv = 3, imagesSsdv = 1, messagesFt4 = 153, tramesAprs = 4, tramesTelemesure = 12,
            paroles = 2, modesQso = listOf("FM", "fm", "CW", "SSTV"), enregistrementsDatv = 1))
        assertEquals(listOf(C.SSTV, C.SSDV, C.FT4, C.APRS, C.TLM, C.PHONIE, C.CW, C.DATV), r.keys.toList())
        assertEquals(153, r[C.FT4]); assertEquals(4, r[C.PHONIE]); assertEquals(1, r[C.CW]); assertEquals(3, r[C.SSTV])
        assertEquals("""{"SSTV":3,"SSDV":1,"FT4":153,"APRS":4,"TLM":12,"PHONIE":4,"CW":1,"DATV":1}""", C.json(r))
    }

    @Test
    fun une_zone_numerique_expliquee_par_des_messages_n_est_plus_violette() {
        val zones = listOf(ZonesNumeriques.FT4, ZonesNumeriques.FT4, ZonesNumeriques.FT8, ZonesNumeriques.NUM)
        // Nothing read: every zone is digital not identified.
        assertEquals(mapOf(C.NUM to 4), C.resume(C.Elements(zones = zones)))
        // FT4 read: its zones are FT4's, the others stay.
        assertEquals(mapOf(C.FT4 to 20, C.NUM to 2), C.resume(C.Elements(zones = zones, messagesFt4 = 20)))
        // An FT8 contact in the log explains the FT8 zone too.
        assertEquals(mapOf(C.FT8 to 1, C.FT4 to 20, C.NUM to 1), C.resume(C.Elements(zones = zones, messagesFt4 = 20, modesQso = listOf("FT8"))))
    }

    @Test
    fun une_voix_entendue_est_de_la_phonie_pas_du_numerique() {
        val zones = listOf(ZonesNumeriques.PHONIE, ZonesNumeriques.FT4)
        assertEquals(mapOf(C.FT4 to 153, C.PHONIE to 1), C.resume(C.Elements(zones = zones, messagesFt4 = 153)))
        assertEquals(mapOf(C.NUM to 1, C.PHONIE to 2), C.resume(C.Elements(zones = zones, modesQso = listOf("SSB"))))
    }

    @Test
    fun un_message_vocal_genesis_a_sa_pastille_apres_la_phonie() {
        val r = C.resume(C.Elements(paroles = 1, messagesVoix = 2, modesQso = listOf("CW")))
        assertEquals(listOf(C.PHONIE, C.VOIX, C.CW), r.keys.toList())
        assertEquals(2, r[C.VOIX]); assertEquals(1, r[C.PHONIE])
        assertEquals("""{"PHONIE":1,"VOIX":2,"CW":1}""", C.json(r))
        assertTrue(C.retenu(r, setOf(C.VOIX)))
        assertFalse(C.retenu(C.resume(C.Elements(paroles = 1)), setOf(C.VOIX)))
    }

    @Test
    fun le_mode_d_un_contact() {
        listOf("FM", "USB", "LSB", "SSB", "AM", "C4FM", "DIGITALVOICE").forEach { assertEquals(it, C.PHONIE, C.genreQso(it)) }
        assertEquals(C.CW, C.genreQso("cw"))
        assertEquals(C.FT8, C.genreQso("MFSK/FT8")); assertEquals(C.FT4, C.genreQso("FT4"))
        assertEquals(C.DATV, C.genreQso("DATV")); assertEquals(C.DATV, C.genreQso("DVB-S2"))
        assertEquals(C.APRS, C.genreQso("PKT")); assertEquals(C.SSTV, C.genreQso("SSTV"))
        assertNull(C.genreQso("")); assertNull(C.genreQso("RTTY"))
    }

    @Test
    fun le_filtre_garde_l_un_ou_l_autre() {
        val r = mapOf(C.SSTV to 2, C.APRS to 1)
        assertTrue(C.retenu(r, emptySet()))
        assertTrue(C.retenu(emptyMap(), emptySet()))
        assertTrue(C.retenu(r, setOf(C.FT4, C.SSTV)))
        assertFalse(C.retenu(r, setOf(C.FT4, C.PHONIE)))
        assertFalse(C.retenu(mapOf(C.FT4 to 0), setOf(C.FT4)))
    }

    @Test
    fun les_salves_genesis_lues_ne_sont_ni_ft4_ni_numerique_non_lu() {
        // HADES-SA, 03/11/2026: two zones of its bursts, its frames read: TLM, not « NUM 2 ».
        val x = C.Elements(zones = listOf("GENESIS200", "GENESIS200"), tramesTelemesure = 9)
        assertEquals(mapOf(C.TLM to 9), C.resume(x))
        // Nothing read of them yet: digital not read.
        assertEquals(mapOf(C.NUM to 2), C.resume(C.Elements(zones = listOf("GENESIS200", "GENESIS"))))
    }
}
