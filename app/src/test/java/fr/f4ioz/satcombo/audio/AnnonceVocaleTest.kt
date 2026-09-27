/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The spoken header: what is said, and the WAV the synthesiser returns. */
class AnnonceVocaleTest {

    @Test
    fun le_locator_se_dit_en_alphabet_phonetique() {
        assertEquals("Juliett November 1 8 Foxtrot Tango", AnnonceVocale.locatorEpele("jn18ft"))
    }

    @Test
    fun les_indicatifs_de_satellite_s_epellent() {
        assertEquals("S O 50", AnnonceVocale.nomEpele("SO-50"))
        assertEquals("I S S", AnnonceVocale.nomEpele("ISS"))
        assertEquals("C A S 2 T", AnnonceVocale.nomEpele("CAS-2T"))
        // A real word is left to the synthesiser.
        assertEquals("FUNCUBE 1", AnnonceVocale.nomEpele("FUNCUBE-1"))
    }

    @Test
    fun la_phrase_en_francais_et_en_anglais() {
        val t = 1_790_549_308_000L   // 2026-09-27 22:48:28 UTC
        assertEquals("Enregistrement SatMe. Satellite S O 50. Le 27 septembre 2026, 22 heures 48 UTC. " +
            "Locator Juliett November 1 8 Foxtrot Tango.",
            AnnonceVocale.texte("SO-50", t, "JN18FT", fr = true))
        assertEquals("SatMe recording. Satellite S O 50. September 27, 2026, 22 48 UTC.",
            AnnonceVocale.texte("SO-50", t, "", fr = false))
    }

    private fun wav(canaux: Int, taux: Int, echantillons: ShortArray, lgData: Int? = null): ByteArray {
        val data = echantillons.size * 2
        val b = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(canaux.toShort())
            .putInt(taux).putInt(taux * 2 * canaux).putShort((2 * canaux).toShort()).putShort(16)
        b.put("data".toByteArray()).putInt(lgData ?: data)
        echantillons.forEach { b.putShort(it) }
        return b.array()
    }

    @Test
    fun wav_mono_lu_tel_quel() {
        val s = shortArrayOf(1, -2, 300, -400)
        val (pcm, taux) = AnnonceVocale.wavVersPcm(wav(1, 22050, s))!!
        assertEquals(22050, taux)
        assertArrayEquals(s, pcm)
    }

    @Test
    fun wav_stereo_ramene_en_mono() {
        val (pcm, _) = AnnonceVocale.wavVersPcm(wav(2, 24000, shortArrayOf(100, 300, -50, -150)))!!
        assertArrayEquals(shortArrayOf(200, -100), pcm)
    }

    @Test
    fun longueur_de_donnees_nulle_on_lit_ce_que_le_fichier_contient() {
        // Engines that stream leave the data length at 0.
        val (pcm, _) = AnnonceVocale.wavVersPcm(wav(1, 16000, shortArrayOf(5, 6, 7), lgData = 0))!!
        assertArrayEquals(shortArrayOf(5, 6, 7), pcm)
    }

    @Test
    fun ce_qui_n_est_pas_un_wav_pcm_16_bits_est_refuse() {
        assertNull(AnnonceVocale.wavVersPcm(ByteArray(10)))
        val flottant = wav(1, 22050, shortArrayOf(1, 2)).also { it[20] = 3 }   // format 3 = float
        assertNull(AnnonceVocale.wavVersPcm(flottant))
    }

    @Test
    fun reechantillonnage_garde_la_duree() {
        val un = ShortArray(22050) { (it % 100).toShort() }
        assertEquals(44100, AnnonceVocale.reechantillonne(un, 22050, 44100).size)
        assertEquals(16000, AnnonceVocale.reechantillonne(un, 22050, 16000).size)
    }
}
