/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.Ft4
import fr.f4ioz.satcombo.domain.Ft8
import fr.f4ioz.satcombo.domain.Ft8Decodeur
import fr.f4ioz.satcombo.domain.Ft8Indicatifs
import fr.f4ioz.satcombo.domain.Ft8Onde
import fr.f4ioz.satcombo.domain.Ft8Pliage
import fr.f4ioz.satcombo.domain.Ft8Signal
import fr.f4ioz.satcombo.domain.Ldpc
import fr.f4ioz.satcombo.domain.LdpcTables
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * FT8/FT4 transmit: packing every message type SatMe sends, the CRC and the
 * LDPC parity of what is encoded, the waveform, and the whole way round —
 * text → tones → audio (with noise) → SatMe's own decoder → the same text.
 */
class Ft8EmissionTest {

    private fun connus(vararg c: String) = Ft8Indicatifs().apply { c.forEach { retiens(it) } }

    private fun allerRetour(texte: String, attendu: String = texte, vararg connus: String) {
        val m = Ft8Pliage.plie(texte)
        assertNotNull("non codé : $texte", m)
        val d = Ft8.deplie(m!!, connus(*connus))
        assertEquals(attendu, d?.brut)
    }

    // ------------------------------------------------------------ packing

    @Test fun les_messages_standard_reviennent_tels_quels() {
        allerRetour("CQ F4IOZ JN18")
        allerRetour("CQ F4IOZ")
        allerRetour("K1ABC F4IOZ JN18")
        allerRetour("K1ABC F4IOZ -12")
        allerRetour("K1ABC F4IOZ +05")
        allerRetour("K1ABC F4IOZ R-07")
        allerRetour("K1ABC F4IOZ R+12")
        allerRetour("F4IOZ K1ABC RRR")
        allerRetour("F4IOZ K1ABC RR73")
        allerRetour("F4IOZ K1ABC 73")
        allerRetour("F4IOZ K1ABC")
        allerRetour("QRZ F4IOZ JN18")
        allerRetour("DE F4IOZ JN18")
        allerRetour("CQ DX F4IOZ JN18")
        allerRetour("CQ POTA F4IOZ JN18")
        allerRetour("CQ 123 F4IOZ JN18")
        allerRetour("2E0XYZ G4ABC IO91")
        allerRetour("k1abc   f4ioz  -3", "K1ABC F4IOZ -03")
    }

    @Test fun le_suffixe_r_ou_p_passe_en_type_1_ou_2() {
        allerRetour("CQ F4IOZ/P JN18")
        allerRetour("K1ABC/R F4IOZ -10")
        val p = Ft8Pliage.plie("CQ F4IOZ/P JN18")!!
        assertEquals(2, (0 until 3).fold(0) { v, i -> v * 2 + (if (p[74 + i]) 1 else 0) })
        // Both suffixes cannot share a message.
        assertNull(Ft8Pliage.plie("K1ABC/R F4IOZ/P -10"))
    }

    @Test fun les_indicatifs_haches_et_composes() {
        // A compound callsign in clear (type 4), mine hashed: read back once my callsign is known.
        allerRetour("CQ PJ4/K1ABC")
        allerRetour("PJ4/K1ABC <F4IOZ> RR73", "PJ4/K1ABC <F4IOZ> RR73", "F4IOZ")
        allerRetour("<F4IOZ> PJ4/K1ABC 73", "<F4IOZ> PJ4/K1ABC 73", "F4IOZ")
        allerRetour("<F4IOZ> PJ4/K1ABC", "<F4IOZ> PJ4/K1ABC", "F4IOZ")
        // Type 1 with a 22-bit hash where the compound callsign cannot fit.
        allerRetour("<PJ4/K1ABC> F4IOZ R-07", "<PJ4/K1ABC> F4IOZ R-07", "PJ4/K1ABC")
        allerRetour("K1ABC <EA8/F4IOZ> -12", "K1ABC <EA8/F4IOZ> -12", "EA8/F4IOZ")
        // Unknown hash: shown as such, never a guessed callsign.
        allerRetour("<PJ4/K1ABC> F4IOZ R-07", "<...> F4IOZ R-07")
        assertEquals("composé", Ft8Pliage.genre("CQ PJ4/K1ABC"))
    }

    @Test fun le_texte_libre_et_ses_limites() {
        allerRetour("TNX 73 GL")
        allerRetour("TU QO100 GL")
        allerRetour("HELLO/WORLD?")
        assertEquals("texte libre", Ft8Pliage.genre("TNX 73 GL"))
        assertNull("trop long", Ft8Pliage.plie("ABCDEFGHIJKLMN"))
        assertNull("caractère hors alphabet", Ft8Pliage.plie("HÉ HO"))
        assertNull(Ft8Pliage.plie("   "))
    }

    @Test fun un_rapport_hors_bornes_ou_un_cq_avec_rapport_est_refuse() {
        // -31 cannot be sent in type 1, and is too long for free text.
        assertNull(Ft8Pliage.plie("K1ABC F4IOZ -31"))
        assertEquals("texte libre", Ft8Pliage.genre("K1AB F4IO -31"))
        assertNull(Ft8Pliage.plie("CQ F4IOZ -10 EXTRA WORDS HERE"))
        assertEquals("texte libre", Ft8Pliage.genre("CQ F4IOZ -10"))
    }

    // ------------------------------------------------------------ CRC, LDPC, tones

    @Test fun le_mot_code_satisfait_toutes_les_parites() {
        for (t in listOf("CQ F4IOZ JN18", "K1ABC F4IOZ R-07", "TNX 73 GL", "CQ PJ4/K1ABC")) {
            val m = Ft8Pliage.plie(t)!!
            for (ft4 in listOf(false, true)) {
                val utiles = if (ft4) Ft4.avecControle(m) else Ft8.avecControle(m)
                assertTrue("CRC $t", Ft8.controleJuste(utiles))
                val mot = Ldpc.encode(utiles)
                assertEquals("parité $t", 0, Ldpc.controlesViolés(mot))
                assertEquals(LdpcTables.N, mot.size)
                // The tones read back to the same codeword.
                val tons = Ft8Onde.tons(m, ft4)
                val relus = if (ft4) Ft4.symbolesVersBits(tons) else Ft8.symbolesVersBits(tons)
                assertEquals(mot.toList(), relus.toList())
                assertEquals(if (ft4) Ft4.SYMBOLES else Ft8.SYMBOLES, tons.size)
                assertEquals(if (ft4) 16 else 21, if (ft4) Ft4.scoreCostas(tons) else Ft8.scoreCostas(tons))
                assertTrue(tons.all { it in 0 until (if (ft4) 4 else 8) })
            }
        }
    }

    @Test fun le_crc_change_quand_un_bit_change() {
        val m = Ft8Pliage.plie("CQ F4IOZ JN18")!!
        val c = Ft8.crc14(m)
        for (i in 0 until 77) { val n = m.copyOf(); n[i] = !n[i]; assertTrue(Ft8.crc14(n) != c) }
    }

    // ------------------------------------------------------------ the waveform

    @Test fun la_forme_d_onde_a_la_bonne_duree_et_les_bons_tons() {
        for (ft4 in listOf(false, true)) {
            val mode = if (ft4) Ft8Signal.FT4 else Ft8Signal.FT8
            val tons = Ft8Onde.tons("CQ F4IOZ JN18", ft4)!!
            val o = Ft8Onde.onde(tons, mode, 1000.0, 12_000.0, 0.5)
            assertEquals(if (ft4) 105 * 576 else 79 * 1920, o.size)
            assertEquals(Ft8Onde.dureeS(ft4), o.size / 12_000.0, 1e-9)
            // 48 kHz: four times as many samples, the same tones.
            assertEquals(o.size * 4, Ft8Onde.onde(tons, mode, 1000.0, 48_000.0).size)
            assertTrue(o.all { abs(it) <= 0.5f + 1e-6f })
            // The ramps: silent at both ends, full in the middle.
            assertTrue(abs(o[0]) < 1e-3f && abs(o[o.size - 1]) < 0.01f)
            // Each symbol's strongest tone (correlation over the symbol) is the one sent.
            val sps = if (ft4) 576 else 1920
            var justes = 0
            for (s in tons.indices) {
                var meilleur = -1; var max = -1.0
                for (k in 0 until mode.tons) {
                    val f = 1000.0 + k * mode.ecartHz
                    var c = 0.0; var d = 0.0
                    for (i in s * sps until (s + 1) * sps) { val w = 2 * PI * f * i / 12_000.0; c += o[i] * kotlin.math.cos(w); d += o[i] * kotlin.math.sin(w) }
                    if (c * c + d * d > max) { max = c * c + d * d; meilleur = k }
                }
                if (meilleur == tons[s]) justes++
            }
            assertEquals("tons mal placés", tons.size, justes)
        }
    }

    @Test fun le_spectre_reste_etroit() {
        // Energy beyond ±150 Hz of the 50 Hz signal: under 1 % (no clicks).
        val tons = Ft8Onde.tons("K1ABC F4IOZ R-07", false)!!
        val o = Ft8Onde.onde(tons, Ft8Signal.FT8, 1500.0, 12_000.0)
        val n = 1 shl 17
        val re = FloatArray(n); val im = FloatArray(n)
        for (i in 0 until minOf(n, o.size)) re[i] = o[i]
        Ft8Signal.fft(re, im)
        var dans = 0.0; var hors = 0.0
        for (k in 0 until n / 2) {
            val f = k * 12_000.0 / n
            val p = re[k] * re[k].toDouble() + im[k] * im[k].toDouble()
            if (f in 1_350.0..1_700.0) dans += p else hors += p
        }
        assertTrue("hors bande ${hors / (dans + hors)}", hors / (dans + hors) < 0.01)
    }

    // ------------------------------------------------------------ the round trip by air

    /** [texte] sent at [hz] with [snr] dB (2500 Hz), noise added, through SatMe's decoder. */
    private fun parLAir(texte: String, ft4: Boolean, hz: Double, snr: Double, graine: Long = 7): List<String> {
        val mode = if (ft4) Ft8Signal.FT4 else Ft8Signal.FT8
        val fs = 12_000.0
        val tranche = if (ft4) 7.5 else 15.0
        val onde = Ft8Onde.onde(Ft8Onde.tons(texte, ft4)!!, mode, hz, fs, 1.0)
        val audio = FloatArray((tranche * fs).toInt())
        val sigma = 1.0
        // A unit sine has power 1/2; the noise's power in 2500 Hz is σ²·2500/(fs/2).
        val a = sqrt(2.0 * sigma * sigma * 2500.0 / (fs / 2) * 10.0.pow(snr / 10.0))
        val alea = java.util.Random(graine)
        val debut = (0.5 * fs).toInt()
        for (i in audio.indices) audio[i] = (sigma * alea.nextGaussian()).toFloat()
        for (i in onde.indices) if (debut + i < audio.size) audio[debut + i] += (a * onde[i]).toFloat()
        return Ft8Decodeur.decode(audio, fs, mode, 200.0, 3000.0, profondeur = Ft8Decodeur.Profondeur.NORMALE).map { it.message.brut }
    }

    @Test fun ft8_aller_retour_par_le_decodeur() {
        for ((t, hz, snr) in listOf(Triple("CQ F4IOZ JN18", 1500.0, 0.0), Triple("K1ABC F4IOZ R-07", 600.0, -12.0),
                Triple("F4IOZ K1ABC RR73", 2400.0, -18.0), Triple("TNX 73 GL", 1000.0, -10.0))) {
            val l = parLAir(t, false, hz, snr)
            assertTrue("FT8 « $t » à $hz Hz, $snr dB : $l", t in l)
        }
    }

    @Test fun ft4_aller_retour_par_le_decodeur() {
        for ((t, hz, snr) in listOf(Triple("CQ F4IOZ JN18", 1500.0, 0.0), Triple("K1ABC F4IOZ R+05", 800.0, -8.0),
                Triple("F4IOZ K1ABC 73", 2200.0, -12.0))) {
            val l = parLAir(t, true, hz, snr)
            assertTrue("FT4 « $t » à $hz Hz, $snr dB : $l", t in l)
        }
    }

    @Test fun un_signal_tres_faible_ne_donne_pas_un_autre_message() {
        // Below the threshold: nothing, or the message itself — never a different one.
        val l = parLAir("K1ABC F4IOZ R-07", false, 1200.0, -28.0, 3)
        assertTrue(l.all { it == "K1ABC F4IOZ R-07" })
    }

    @Test fun l_erf_est_juste() {
        assertEquals(0.0, Ft8Onde.erf(0.0), 1e-7)
        assertEquals(0.8427007929, Ft8Onde.erf(1.0), 2e-7)
        assertEquals(-0.9953222650, Ft8Onde.erf(-2.0), 2e-7)
        // The pulse integrates to one symbol: a constant tone stays exactly that tone.
        val k = PI * sqrt(2.0 / kotlin.math.ln(2.0))
        var s = 0.0
        for (j in -5..5) s += Ft8Onde.impulsion(0.3 - j, k)
        assertEquals(1.0, s, 1e-6)
        assertEquals(0.0, atan2(0.0, 1.0), 0.0)
    }
}
