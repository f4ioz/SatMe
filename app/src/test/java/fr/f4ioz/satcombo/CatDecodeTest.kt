/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.cat.CatDecode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Les deux dialectes CAT, éprouvés avec des octets écrits à la main.
 *
 * Huit essais, et aucun n'a besoin d'une radio. C'est tout le changement :
 * jusqu'ici, ce fichier n'existait pas parce qu'il ne pouvait pas exister — le
 * découpage des trames vivait à l'intérieur des appels USB. La seule
 * vérification possible était de brancher un poste et de regarder sa face
 * avant, ce qui ne distingue pas une commande comprise d'une commande
 * poliment accusée puis jetée.
 */
class CatDecodeTest {

    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private val radio = 0xA2
    private val ctrl = 0xE0

    @Test
    fun deux_trames_collees_se_separent_et_le_bruit_se_jette() {
        val buf = b(
            0x11, 0x22,                                        // bruit devant
            0xFE, 0xFE, 0xE0, 0xA2, 0xFB, 0xFD,                // accusé
            0xFE, 0xFE, 0xE0, 0xA2, 0x03, 0x00, 0x50, 0x35, 0x35, 0x04, 0xFD
        )
        val f = CatDecode.splitCiv(buf)
        assertEquals(2, f.size)
        assertEquals(CatDecode.ACK, CatDecode.command(f[0]))
        assertEquals(0x03, CatDecode.command(f[1]))
        // Et un préambule de trois 0xFE, que certains postes émettent, ne
        // décale rien.
        assertEquals(1, CatDecode.splitCiv(b(0xFE, 0xFE, 0xFE, 0xA2, 0xE0, 0x03, 0xFD)).size)
    }

    @Test
    fun une_trame_tronquee_n_est_pas_rendue() {
        // Le tampon s'arrête au milieu : mieux vaut ne rien rendre que rendre
        // une fréquence incomplète, qui aurait l'air d'une fréquence.
        val f = CatDecode.splitCiv(b(0xFE, 0xFE, 0xE0, 0xA2, 0x03, 0x00, 0x50))
        assertTrue(f.isEmpty())
        assertTrue(CatDecode.splitCiv(ByteArray(0)).isEmpty())
        assertEquals(-1, CatDecode.command(b(0xFE, 0xFE)))
    }

    @Test
    fun l_echo_du_bus_se_distingue_de_la_reponse_du_poste() {
        // Le CI-V est un bus à un fil : ce que l'on écrit revient dans sa propre
        // oreille. Les deux adresses sont simplement inversées, et c'est la
        // seule chose qui permette de trier.
        val question = b(0xFE, 0xFE, 0xA2, 0xE0, 0x03, 0xFD)
        val reponse = b(0xFE, 0xFE, 0xE0, 0xA2, 0x03, 0x00, 0x50, 0x35, 0x35, 0x04, 0xFD)
        assertTrue(CatDecode.isEcho(question, radio, ctrl))
        assertTrue(!CatDecode.isFromRadio(question, radio, ctrl))
        assertTrue(CatDecode.isFromRadio(reponse, radio, ctrl))
        assertTrue(!CatDecode.isEcho(reponse, radio, ctrl))
        // Une trame destinée à un autre poste du bus ne nous concerne pas.
        assertTrue(!CatDecode.isFromRadio(b(0xFE, 0xFE, 0xE0, 0x94, 0x03, 0xFD), radio, ctrl))
    }

    @Test
    fun la_charge_utile_ignore_l_echo_et_les_accuses() {
        val frames = CatDecode.splitCiv(b(
            0xFE, 0xFE, 0xA2, 0xE0, 0x03, 0xFD,                              // notre écho
            0xFE, 0xFE, 0xE0, 0xA2, 0xFB, 0xFD,                              // accusé
            0xFE, 0xFE, 0xE0, 0xA2, 0x03, 0x00, 0x50, 0x35, 0x35, 0x04, 0xFD // la réponse
        ))
        assertEquals(3, frames.size)
        val p = CatDecode.payload(frames, radio, ctrl, 0x03)
        assertArrayEquals(b(0x00, 0x50, 0x35, 0x35, 0x04), p)
        assertTrue(CatDecode.isAck(frames, radio, ctrl))
        assertTrue(!CatDecode.isNak(frames, radio, ctrl))
        // Une sous-commande qui ne correspond pas fait rendre null, et non la
        // trame suivante « à peu près ».
        val vfo = CatDecode.splitCiv(b(
            0xFE, 0xFE, 0xE0, 0xA2, 0x25, 0x01, 0x00, 0x90, 0x59, 0x45, 0x01, 0xFD))
        assertNull(CatDecode.payload(vfo, radio, ctrl, 0x25, 0x00))
        assertEquals(5, CatDecode.payload(vfo, radio, ctrl, 0x25, 0x01)!!.size)
    }

    @Test
    fun la_frequence_fait_l_aller_retour_en_bcd_petit_boutien() {
        // 435,530 000 MHz, chiffres pris par paires et à l'envers.
        assertArrayEquals(b(0x00, 0x00, 0x53, 0x35, 0x04), CatDecode.freqToBcdLe(435_530_000L))
        for (hz in listOf(145_800_000L, 435_500_000L, 1_296_100_000L, 29_600_000L)) {
            assertEquals(hz, CatDecode.bcdLeToFreq(CatDecode.freqToBcdLe(hz)))
        }
    }

    @Test
    fun une_lecture_invraisemblable_rend_null_plutot_qu_un_nombre() {
        // Des demi-octets qui ne sont pas des chiffres : ce n'est pas du BCD, et
        // le lire comme tel donnerait une fréquence parfaitement plausible.
        assertNull(CatDecode.bcdLeToFreq(b(0xFF, 0x00, 0x53, 0x35, 0x04)))
        // Trop court.
        assertNull(CatDecode.bcdLeToFreq(b(0x00, 0x00, 0x53)))
        // Et zéro hertz, que produisait l'ancien code quand il lisait son propre
        // écho suivi de rien.
        assertNull(CatDecode.bcdLeToFreq(b(0x00, 0x00, 0x00, 0x00, 0x00)))
    }

    @Test
    fun le_ton_d_acces_est_gros_boutien_et_l_ancien_encodage_valait_885_hz() {
        // 88,5 Hz : trois octets, et le premier est nul.
        assertArrayEquals(b(0x00, 0x08, 0x85), CatDecode.toneToBcdBe(885))
        assertArrayEquals(b(0x00, 0x06, 0x70), CatDecode.toneToBcdBe(670))
        assertEquals(885, CatDecode.bcdBeToTone(b(0x00, 0x08, 0x85)))

        // Et voici la faute, relue : l'ancien encodeur traitait le ton comme une
        // fréquence, donc à l'envers, et sortait 00 88 50. Le poste y lit
        // 885,0 Hz — hors plage, ignoré, mais accusé réception.
        val ancien = b(0x00, 0x88, 0x50)
        assertEquals(8850, CatDecode.bcdBeToTone(ancien))
        assertTrue("885 Hz aurait dû être hors plage",
            !CatDecode.toneInRange(CatDecode.bcdBeToTone(ancien)!!))
        assertTrue(CatDecode.toneInRange(885))
        assertTrue(CatDecode.toneInRange(CatDecode.TONE_MIN_TENTH))
        assertTrue(CatDecode.toneInRange(CatDecode.TONE_MAX_TENTH))
        assertTrue(!CatDecode.toneInRange(CatDecode.TONE_MAX_TENTH + 1))
        assertNull(CatDecode.bcdBeToTone(b(0x00, 0x0F, 0x85)))
    }

    @Test
    fun le_dialecte_yaesu_compte_par_dix_hertz_et_se_traduit() {
        // Huit chiffres BCD gros-boutiens, unité de dix hertz.
        assertArrayEquals(b(0x14, 0x58, 0x00, 0x00), CatDecode.yaesuFreq(145_800_000L))
        assertEquals(145_800_000L, CatDecode.yaesuFreqOf(b(0x14, 0x58, 0x00, 0x00)))
        // Le pas de dix hertz est réel : les unités disparaissent.
        assertEquals(435_500_000L, CatDecode.yaesuFreqOf(CatDecode.yaesuFreq(435_500_007L)))
        assertNull(CatDecode.yaesuFreqOf(b(0x1A, 0x58, 0x00, 0x00)))

        // Les traductions du journal : lisibles sans manuel ouvert à côté.
        assertTrue(CatDecode.describeCiv(
            b(0xFE, 0xFE, 0xA2, 0xE0, 0x07, 0xD1, 0xFD)).contains("secondaire"))
        assertTrue(CatDecode.describeCiv(
            b(0xFE, 0xFE, 0xE0, 0xA2, 0xFA, 0xFD)).contains("efus"))
        assertTrue(CatDecode.describeCiv(
            b(0xFE, 0xFE, 0xA2, 0xE0, 0x16, 0x5A, 0x01, 0xFD)).contains("satellite"))
        assertTrue(CatDecode.describeYaesu(
            b(0x00, 0x00, 0x00, 0x00, 0xF7), fromRig = false).contains("émission"))
        assertTrue(CatDecode.describeYaesu(
            b(0x80), fromRig = true, lastOp = 0xF7).contains("reçoit"))
    }
}
