/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.cat.icomreseau

import fr.f4ioz.satcombo.cat.CatDecode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The TX guard, the credentials' coding, the audio put back in order, the scope. */
class PiecesIcomTest {

    private fun civ(vararg b: Int) = byteArrayOf(0xFE.toByte(), 0xFE.toByte(), 0xA2.toByte(), 0xE0.toByte()) +
        b.map { it.toByte() }.toByteArray() + byteArrayOf(0xFD.toByte())

    // ------------------------------------------------------------ TX guard

    @Test fun ptt_refuse() {
        assertNotNull(GardeEmission.refus(civ(0x1C, 0x00, 0x01)))
        assertNotNull(GardeEmission.refus(civ(0x1C, 0x00, 0x02)))
    }

    @Test fun lire_l_etat_tx_et_repasser_en_reception_passent() {
        assertNull(GardeEmission.refus(civ(0x1C, 0x00)))
        assertNull(GardeEmission.refus(civ(0x1C, 0x00, 0x00)))
        assertNull(GardeEmission.refus(civ(0x1C, 0x01)))
    }

    @Test fun boite_d_accord_cw_et_memoire_vocale_refusees() {
        assertNotNull(GardeEmission.refus(civ(0x1C, 0x01, 0x02)))
        assertNotNull(GardeEmission.refus(civ(0x17, 0x43, 0x51)))
        assertNotNull(GardeEmission.refus(civ(0x28, 0x00, 0x01)))
    }

    @Test fun ptt_derriere_le_prefixe_de_bande_refuse() {
        assertNotNull(GardeEmission.refus(civ(0x29, 0x01, 0x1C, 0x00, 0x01)))
        assertNull(GardeEmission.refus(civ(0x29, 0x01, 0x05, 0, 0, 0x80, 0x45, 0x01)))
    }

    @Test fun doppler_et_modes_passent() {
        assertNull(GardeEmission.refus(civ(0x05, *CatDecode.freqToBcdLe(435_795_000L).map { it.toInt() and 0xFF }.toIntArray())))
        assertNull(GardeEmission.refus(civ(0x06, 0x01, 0x01)))
        assertNull(GardeEmission.refus(civ(0x07, 0xD0)))
        assertNull(GardeEmission.refus(civ(0x16, 0x5A, 0x01)))
        assertNull(GardeEmission.refus(civ(0x03)))
    }

    @Test fun un_ptt_parmi_plusieurs_trames_bloque_l_envoi() {
        assertNotNull(GardeEmission.refusDans(civ(0x03) + civ(0x1C, 0x00, 0x01)))
        assertNull(GardeEmission.refusDans(civ(0x03) + civ(0x04)))
    }

    // ------------------------------------------------------------ credentials

    @Test fun codage_appris_puis_reutilise() {
        val c = CodageIdentifiants()
        // An invented substitution (the real one is learnt from the owner's traces).
        fun code(s: String) = ByteArray(16).also { b -> s.forEachIndexed { i, ch -> b[i] = ((ch.code * 7 + i * 13) and 0x7F).toByte() } }
        assertTrue(c.apprends("f4ioz", code("f4ioz")))
        assertTrue(c.apprends("radio", code("radio")))
        assertArrayEquals(code("f4ioz"), c.code("f4ioz"))
        // Characters known at those places: "fadio" codes without being taught.
        assertArrayEquals(code("fadio"), c.code("fadio"))
        assertNull(c.code("zzzzz"))
        assertEquals(listOf(0, 1, 2, 3), c.manquants("zzzzz"))
        // Kept as text and read back.
        val d = CodageIdentifiants().apply { lis(c.ecrit()) }
        assertArrayEquals(code("radio"), d.code("radio"))
        // A pair that contradicts what is known is refused.
        assertFalse(c.apprends("f", byteArrayOf(0x01)))
    }

    @Test fun hex_saisi() {
        assertEquals("74496a33", CodageIdentifiants.versHex(CodageIdentifiants.hex("74 49 6a 33")!!))
        assertEquals(16, CodageIdentifiants.hex("262222745336364d")!!.size)
        assertNull(CodageIdentifiants.hex("xyz"))
        assertNull(CodageIdentifiants.hex("00".repeat(17)))
    }

    // ------------------------------------------------------------ audio

    private class Horloge { var t = 0L }

    @Test fun audio_remis_dans_l_ordre() {
        val h = Horloge()
        val sortie = ArrayList<Int>()
        val tp = TamponAudio(80, { h.t }) { sortie += it[0].toInt() }
        tp.recois(10, byteArrayOf(10, 0)); tp.recois(12, byteArrayOf(12, 0)); tp.recois(11, byteArrayOf(11, 0))
        assertEquals(listOf(10, 11, 12), sortie)
        assertEquals(0L, tp.perdus)
    }

    @Test fun trou_rempli_de_silence_apres_la_fenetre() {
        val h = Horloge()
        val sortie = ArrayList<ByteArray>()
        val tp = TamponAudio(80, { h.t }) { sortie += it }
        tp.recois(0, ByteArray(4) { 1 })
        tp.recois(2, ByteArray(4) { 3 })
        assertEquals(1, sortie.size)
        h.t = 40; tp.vide()
        assertEquals(listOf(1), tp.manquants())
        assertEquals(1, sortie.size)
        h.t = 100; tp.vide()
        assertEquals(3, sortie.size)
        assertArrayEquals(ByteArray(4), sortie[1])
        assertEquals(1L, tp.perdus)
        // Arriving now, too late: dropped.
        tp.recois(1, ByteArray(4) { 2 })
        assertEquals(3, sortie.size); assertEquals(1L, tp.enRetard)
    }

    @Test fun double_ignore_et_compteur_qui_repart() {
        val h = Horloge()
        val sortie = ArrayList<Int>()
        val tp = TamponAudio(80, { h.t }) { sortie += it[0].toInt() }
        tp.recois(65535, byteArrayOf(1)); tp.recois(0, byteArrayOf(2)); tp.recois(0, byteArrayOf(2))
        assertEquals(listOf(1, 2), sortie)
        tp.recois(30_000, byteArrayOf(3))
        assertEquals(listOf(1, 2, 3), sortie)
    }

    @Test fun ulaw_et_surechantillonnage() {
        assertEquals(0, DecodeAudio.ulaw(0xFF).toInt())
        assertEquals(-32124, DecodeAudio.ulaw(0x00).toInt())
        assertEquals(32124, DecodeAudio.ulaw(0x80).toInt())
        val s = Surechantillonneur(16_000)
        assertEquals(3, s.facteur)
        val out = s.traite(ShortArray(160) { 1000 })
        assertEquals(480, out.size)
        assertEquals(1000, out.last().toInt())
    }

    @Test fun codec_24k_absent() {
        // 16-bit mono at 24 kHz carries nothing on the radio (48 000 bytes a second).
        assertTrue(CodecReseau.entries.none { it.octetsParSeconde == 48_000 })
        assertEquals(CodecReseau.PCM16_16K, CodecReseau.de("inconnu"))
    }

    // ------------------------------------------------------------ sequences

    @Test fun trous_et_doubles_du_controle() {
        val s = SuiviRecus()
        assertTrue(s.recois(0, 0)); assertTrue(s.recois(1, 0)); assertTrue(s.recois(4, 0))
        assertEquals(listOf(2, 3), s.aRedemander(10))
        assertTrue(s.recois(2, 20))
        assertFalse(s.recois(1, 20))
        assertEquals(listOf(3), s.aRedemander(30))
        assertEquals(emptyList<Int>(), s.aRedemander(5_000))
    }

    // ------------------------------------------------------------ scope

    @Test fun spectre_en_une_partie() {
        val sp = SpectrePoste()
        val tete = intArrayOf(0x27, 0x00, 0x00, 0x01, 0x01, 0x00) +
            CatDecode.freqToBcdLe(145_900_000L).map { it.toInt() and 0xFF } + CatDecode.freqToBcdLe(50_000L).map { it.toInt() and 0xFF } + listOf(0x00)
        val f = byteArrayOf(0xFE.toByte(), 0xFE.toByte(), 0xE0.toByte(), 0xA2.toByte()) +
            (tete.toList() + List(475) { (it % 161) }).map { it.toByte() }.toByteArray() + byteArrayOf(0xFD.toByte())
        assertTrue(sp.recois(f, 5))
        val b = sp.dernier!!
        assertEquals(145_850_000L, b.basHz); assertEquals(145_950_000L, b.hautHz)
        assertEquals(475, b.points.size)
        assertEquals(1f, b.points[160], 1e-6f)
        assertFalse(sp.recois(civ(0x03), 6))
    }
}
