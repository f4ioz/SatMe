/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.cat.icomreseau

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire format against packets published in a capture write-up (an
 * IC-7610's: the same server as the IC-9700's) and against itself.
 */
class PaquetsIcomTest {

    private fun hex(s: String): ByteArray {
        val t = s.filter { !it.isWhitespace() }
        return ByteArray(t.length / 2) { t.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }

    /** "ConnInfo to Radio", as captured: a host asking for 16-bit mono 48 kHz streams. */
    private val infoHoteCapture = hex(
        "90000000 0000 0400 0c7fc351 58ac1d65 000000 8001 0300 0300 00 80c6 7a586739" +
        "000000000000001080000090c7098d6d 00000000000000000000000000000000" +
        "49432d37363130000000000000000000 00000000000000000000000000000000" +
        "74496a33000000000000000000000000 01 01 04 04 0000bb80 0000bb80 0000c352 0000c353 000000c8 01 00000000000000")

    /** "Status", as captured: the ports the radio grants. */
    private val statutCapture = hex(
        "50000000 0000 4b00 cdd54a26 87a17866 0000 0040 0203 0002 0000 4edd 025ecdc9" +
        "0000 0000 0000 0010 8000 0090c7098d6d 00000000 000000000000000000000000 0000c352 0000c353 0000000000000000")

    /** "Capabilities", as captured: one radio, its name, CI-V address, rate bitmaps. */
    private val capacitesCapture = hex(
        "a8000000 0000 0200 49c16632 74e70000 00000098 0202 0001 0000 67c7 6abbfe9f" +
        "0000000000000000000000000000000000000000000000000000000000000000" +
        "0001 0000000000000010 8000 0090c7098d6d" +
        "49432d3736313000000000000000000000000000000000000000000000000000" +
        "49434f4d5f56415544494f0000000000 00000000000000000000000000000000" +
        "3f07 98 018b 018b 01 01 01 00004b00 0150 00 9001 000000")

    @Test fun les_captures_ont_la_taille_annoncee() {
        assertEquals(0x90, infoHoteCapture.size)
        assertEquals(0x50, statutCapture.size)
        assertEquals(0xA8, capacitesCapture.size)
    }

    @Test fun info_hote_lue_aux_bons_endroits() {
        val j = Paquets.jeton(infoHoteCapture)!!
        assertEquals(Paquets.SENS_DEMANDE, j.sens)
        assertEquals(Paquets.G_INFO, j.genre)
        assertEquals(3, j.seqInterne)
        assertEquals(0x7a586739, Integer.reverseBytes(j.jeton))
        assertEquals(4, infoHoteCapture[Paquets.I_RX_CODEC].toInt())
        assertEquals(48_000, Paquets.u32be(infoHoteCapture, Paquets.I_RX_TAUX))
        assertEquals(50_002, Paquets.u32be(infoHoteCapture, Paquets.I_PORT_CIV))
        assertEquals(50_003, Paquets.u32be(infoHoteCapture, Paquets.I_PORT_AUDIO))
        assertEquals(200, Paquets.u32be(infoHoteCapture, Paquets.I_TAMPON_TX))
        assertEquals("IC-7610", Paquets.texte(infoHoteCapture, Paquets.I_NOM, 32))
    }

    @Test fun info_hote_refaite_comme_la_capture() {
        // Same fields in, same bytes out (TX on, as in the capture, to compare: SatMe itself never asks for it).
        val c = infoHoteCapture
        val e = Paquets.entete(c)!!
        val j = Paquets.jeton(c)!!
        val radio = Paquets.Radio(c.copyOfRange(0x20, 0x30), "IC-7610", "", 0, 0x98, 0, 0, 0)
        val b = Paquets.infoHote(e.seq, e.emetteur, e.destinataire, j.seqInterne, j.demande, j.jeton, radio,
            c.copyOfRange(0x60, 0x70), Paquets.Flux(4, 48_000, 50_002, 50_003, txActif = true, tamponTxMs = 200))
        assertArrayEquals(c, b)
    }

    @Test fun statut_donne_les_ports() {
        val s = Paquets.statut(statutCapture)!!
        assertEquals(50_002, s.portCiv)
        assertEquals(50_003, s.portAudio)
        assertEquals(0, s.erreur)
        assertEquals(Paquets.SENS_REPONSE, Paquets.jeton(statutCapture)!!.sens)
    }

    @Test fun capacites_lues() {
        val r = Paquets.capacites(capacitesCapture).single()
        assertEquals("IC-7610", r.nom)
        assertEquals("ICOM_VAUDIO", r.audio)
        assertEquals(0x98, r.civ)
        assertEquals(0x8B01, r.tauxRx)
        assertEquals(19_200, r.vitesse)
        // The identity block, echoed in the connection info: MAC mode, MAC at 0x0A.
        assertEquals(0x8010, Paquets.u16le(r.identite, 7))
        assertEquals("0090c7098d6d", Paquets.hex(r.identite, 10, 16))
        assertEquals(listOf(8_000, 12_000, 16_000, 24_000, 48_000), Paquets.tauxOfferts(r.tauxRx))
    }

    @Test fun controle_et_ping() {
        // "Are you there?" as captured: length 0x10, type 3, our id first.
        val b = Paquets.controle(Paquets.T_ES_TU_LA, 0, Integer.reverseBytes(0x87a17866.toInt()), 0)
        assertEquals("100000000300000087a1786600000000", Paquets.hex(b, 0, b.size))
        val p = Paquets.ping(2, 1, 2, false, 0x02138c5e)
        assertEquals(0x15, p.size)
        assertFalse(Paquets.estReponsePing(p))
        assertEquals("5e8c1302", Paquets.hex(p, 0x11, 0x15))
        val r = Paquets.ping(2, 2, 1, true, Paquets.valeurPing(p))
        assertTrue(Paquets.estReponsePing(r))
        assertEquals(Paquets.valeurPing(p), Paquets.valeurPing(r))
    }

    @Test fun ouverture_civ_comme_capturee() {
        // "16000000 0000 0100 <ids> c0 0100 0000 05" (open).
        val b = Paquets.ouvreFerme(1, 0, 0, 0, true)
        assertEquals("c001000000" + "05", Paquets.hex(b, 0x10, 0x16))
        assertTrue(Paquets.estOuvreFerme(b))
        assertEquals(0x00, Paquets.ouvreFerme(2, 0, 0, 1, false)[0x15].toInt())
    }

    @Test fun trame_civ_aller_retour() {
        // As captured: "1e000000 0000 7800 … c1 0900 0100 fefe98e01a050116fd".
        val t = hex("fefe98e01a050116fd")
        val b = Paquets.civ(0x78, 1, 2, 1, t)
        assertEquals(0x1e, b.size)
        assertEquals("c109000100", Paquets.hex(b, 0x10, 0x15))
        assertArrayEquals(t, Paquets.trameCiv(b))
        assertNull(Paquets.trameCiv(Paquets.ouvreFerme(1, 0, 0, 0, true)))
    }

    @Test fun audio_aller_retour() {
        val d = ByteArray(556) { it.toByte() }
        val b = Paquets.nouvelAudio(7, 1, 2, 3, d)
        // As captured: a 0x18-byte header, the length (0x022c) at 0x16, big-endian.
        assertEquals(0x18 + 556, b.size)
        assertEquals("022c", Paquets.hex(b, 0x16, 0x18))
        val a = Paquets.audio(b)!!
        assertEquals(7, a.seq); assertEquals(3, a.seqAudio)
        assertArrayEquals(d, a.donnees)
    }

    @Test fun connexion_porte_les_champs() {
        val u = ByteArray(16).also { it[0] = 0x74 }
        val m = ByteArray(16).also { it[0] = 0x26 }
        val b = Paquets.connexion(1, 5, 6, 0, 0x4edd, u, m, "SATME-PC")
        assertEquals(0x80, b.size)
        val j = Paquets.jeton(b)!!
        assertEquals(Paquets.G_CONNEXION, j.genre); assertEquals(Paquets.SENS_DEMANDE, j.sens)
        assertEquals(0x4edd, j.demande)
        // The payload size as captured: "0000 0070".
        assertEquals("00000070", Paquets.hex(b, 0x10, 0x14))
        assertEquals("SATME-PC", Paquets.texte(b, Paquets.C_NOM, 16))
        assertEquals(0x74, b[Paquets.C_UTILISATEUR].toInt()); assertEquals(0x26, b[Paquets.C_MOT_DE_PASSE].toInt())
    }

    @Test fun reprises_une_ou_plusieurs() {
        assertEquals(listOf(9), Paquets.reprises(Paquets.reprise(9, 1, 2)))
        val b = Paquets.nouveau(0x14, Paquets.T_REPRISE, 0, 1, 2)
        Paquets.p16le(b, 0x10, 4); Paquets.p16le(b, 0x12, 5)
        assertEquals(listOf(4, 5), Paquets.reprises(b))
    }

    @Test fun le_resume_ne_montre_pas_les_identifiants() {
        val u = ByteArray(16) { 0x55 }
        val b = Paquets.connexion(1, 5, 6, 0, 1, u, u, "X")
        val r = Paquets.resume(true, "contrôle", b)
        assertNotNull(r)
        assertFalse(r.contains("5555"))
    }
}
