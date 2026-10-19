/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceAudioTest {

    private val cartes = listOf("PCH [plughw:0,0]", "CODEC [plughw:1,0]", "IC-9700 (réseau)")

    @Test
    fun la_carte_a_prendre_jamais_au_hasard() {
        // The satellite's own first, then the last used, then the rig's.
        assertEquals("IC-9700 (réseau)", SourceAudio.carteAPrendre(cartes, "IC-9700 (réseau)", "PCH [plughw:0,0]", "CODEC [plughw:1,0]"))
        assertEquals("PCH [plughw:0,0]", SourceAudio.carteAPrendre(cartes, null, "PCH [plughw:0,0]", "CODEC [plughw:1,0]"))
        assertEquals("CODEC [plughw:1,0]", SourceAudio.carteAPrendre(cartes, "", "", "CODEC [plughw:1,0]"))
        // A card gone (unplugged) is passed over; the receiver is not a card.
        assertEquals("CODEC [plughw:1,0]", SourceAudio.carteAPrendre(cartes, SourceAudio.SDR, "USB [plughw:3,0]", "CODEC [plughw:1,0]"))
        // Nothing known: no guess among the others.
        assertNull(SourceAudio.carteAPrendre(cartes, null, "", null))
        assertNull(SourceAudio.carteAPrendre(emptyList(), "x", "y", "z"))
    }

    @Test
    fun la_liste_des_decodeurs_en_francais() {
        assertEquals("", SourceAudio.liste(emptyList()))
        assertEquals("SSTV", SourceAudio.liste(listOf("SSTV")))
        assertEquals("SSTV et FT8", SourceAudio.liste(listOf("SSTV", "FT8")))
        assertEquals("SSTV, FT8 et CW", SourceAudio.liste(listOf("SSTV", "FT8", "CW")))
    }

    @Test
    fun rien_n_arrive_le_bouton_qui_repare() {
        val c = SourceAudio.conseil(listOf("SSTV"), actif = false, niveau = 0.0, silenceMs = 0, carte = "CODEC [plughw:1,0]",
            libelle = "IC-9700 (USB Audio CODEC)", sdrPresent = true, source = "")!!
        assertEquals("SSTV n'entend rien : aucune source audio en marche.", c.texte)
        assertEquals("carte:CODEC [plughw:1,0]", c.action)
        assertEquals("▶ Démarrer IC-9700 (USB Audio CODEC)", c.bouton)
        // No card known: the receiver, when there is one.
        val s = SourceAudio.conseil(listOf("SSTV", "FT8"), false, 0.0, 0, null, "", true, "")!!
        assertEquals(SourceAudio.SDR, s.action)
        assertTrue(s.texte.startsWith("SSTV et FT8 n'entendent rien"))
        // Neither: said, no button.
        assertEquals("", SourceAudio.conseil(listOf("CW"), false, 0.0, 0, null, "", false, "")!!.action)
    }

    @Test
    fun silence_et_saturation_seulement_quand_ca_dure_ou_que_ca_coupe() {
        // No decoder on: nothing to say.
        assertNull(SourceAudio.conseil(emptyList(), false, 0.0, 0, "x", "x", true, ""))
        // Running and fine.
        assertNull(SourceAudio.conseil(listOf("FT8"), true, 0.2, 0, null, "", false, "IC-9700"))
        // Silent a moment (between two overs): nothing; silent long: said.
        assertNull(SourceAudio.conseil(listOf("FT8"), true, 0.0, 5_000, null, "", false, "IC-9700"))
        val m = SourceAudio.conseil(listOf("FT8"), true, 0.0, 25_000, null, "", false, "IC-9700")!!
        assertTrue(m.texte.contains("25 s"))
        assertEquals("", m.action)
        assertTrue(SourceAudio.conseil(listOf("FT8"), true, 0.99, 0, null, "", false, "IC-9700")!!.texte.contains("sature"))
    }

    @Test
    fun les_decodeurs_d_un_emetteur() {
        assertEquals(listOf("SSTV"), SourceAudio.decodeursPour("FM", "SSTV (ARISS)"))
        assertEquals(listOf("APRS"), SourceAudio.decodeursPour("FM", "APRS digipeater"))
        assertEquals(listOf("APRS"), SourceAudio.decodeursPour("AFSK", "Telemetry 1k2"))
        assertEquals(listOf("CW"), SourceAudio.decodeursPour("CW", "Mode U CW Beacon"))
        assertEquals(listOf("CW"), SourceAudio.decodeursPour("FM", "CW beacon"))
        assertEquals(listOf("FT8"), SourceAudio.decodeursPour("USB", "FT8 calling"))
        // Voice, a linear transponder: no decoder (the operator listens).
        assertEquals(emptyList<String>(), SourceAudio.decodeursPour("FM", "Mode V/U FM voice repeater"))
        assertEquals(emptyList<String>(), SourceAudio.decodeursPour("USB", "Mode V/U Linear Transponder"))
    }

    @Test
    fun le_passage_prepare_prend_la_bonne_source() {
        val poste = "CODEC [plughw:1,0]"
        // Remembered for this satellite: kept, the receiver too.
        assertEquals("IC-9700 (réseau)", SourceAudio.plan("FM", "SSTV", cartes, "IC-9700 (réseau)", null, poste, true, false).source)
        assertEquals(SourceAudio.SDR, SourceAudio.plan("FM", "SSTV", cartes, SourceAudio.SDR, null, poste, true, false).source)
        // The rig in CAT: its own sound.
        val p = SourceAudio.plan("FM", "SSTV", cartes, null, "PCH [plughw:0,0]", poste, true, false)
        assertEquals(poste, p.source)
        assertEquals(listOf("SSTV"), p.decodeurs)
        assertTrue(p.enregistrer)
        // The receiver running and no rig: it feeds already, nothing changed.
        assertNull(SourceAudio.plan("FM", "", cartes, null, "PCH [plughw:0,0]", poste, false, true).source)
        // Nothing running, no rig: the last card used.
        assertEquals("PCH [plughw:0,0]", SourceAudio.plan("FM", "", cartes, null, "PCH [plughw:0,0]", poste, false, false).source)
        assertNull(SourceAudio.plan("FM", "", emptyList(), null, "", null, false, false).source)
    }
}
