/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.clavier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * PSK31 and RTTY: the codes, then synthetic signals with noise decoded end
 * to end, off-centre (the AFC), at a modest S/N.
 */
class ClavierTest {

    // ------------------------------------------------------------ codes

    @Test
    fun varicode_complet_sans_double_zero_et_sans_doublon() {
        assertEquals(128, Varicode.CODES.size)
        assertEquals(128, Varicode.CODES.toSet().size)
        for (c in Varicode.CODES) {
            assertTrue(c, c.startsWith("1") && c.endsWith("1") && !c.contains("00") && c.length <= 10)
        }
        assertEquals("1", Varicode.CODES[' '.code])
        assertEquals("11", Varicode.CODES['e'.code])
        assertEquals("101", Varicode.CODES['t'.code])
        assertEquals("1011", Varicode.CODES['a'.code])
        assertEquals("11101", Varicode.CODES['\n'.code])
        // Shorter codes for the commoner letters: e, t, o, a … never longer than a rare capital.
        assertTrue(Varicode.CODES['e'.code].length < Varicode.CODES['Q'.code].length)
    }

    @Test
    fun varicode_aller_retour() {
        val texte = "CQ CQ de F4IOZ F4IOZ pse k 73! {x|y} ~@#"
        val sb = StringBuilder()
        val l = Varicode.Lecteur { sb.append(it) }
        ("0000" + Varicode.encode(texte)).forEach { l.bit(it == '1') }
        assertEquals(texte, sb.toString())
    }

    @Test
    fun baudot_lettres_chiffres_et_retour_sur_espace() {
        val codes = Baudot.encode("RYRY 599 TU 73 DE F4IOZ")
        assertEquals(Baudot.LTRS, codes[0])
        assertTrue(Baudot.FIGS in codes.toList())
        val sb = StringBuilder()
        val l = Baudot.Lecteur(true) { sb.append(it) }
        codes.forEach { l.code(it) }
        assertEquals("RYRY 599 TU 73 DE F4IOZ", sb.toString())
        // Without unshift on space, the figures page stays after a space.
        val sb2 = StringBuilder()
        val l2 = Baudot.Lecteur(false) { sb2.append(it) }
        intArrayOf(Baudot.FIGS, 0x01, Baudot.ESPACE, 0x01, Baudot.LTRS, 0x01).forEach { l2.code(it) }
        assertEquals("3 3E", sb2.toString())
        val sb3 = StringBuilder()
        val l3 = Baudot.Lecteur(true) { sb3.append(it) }
        intArrayOf(Baudot.FIGS, 0x01, Baudot.ESPACE, 0x01).forEach { l3.code(it) }
        assertEquals("3 E", sb3.toString())
        // Encoding without unshift on space puts FIGS again only when needed.
        val s = StringBuilder(); val l4 = Baudot.Lecteur(false) { s.append(it) }
        Baudot.encode("1 2 A", usos = false).forEach { l4.code(it) }
        assertEquals("1 2 A", s.toString())
    }

    // ------------------------------------------------------------ signals with noise

    /** Audio at [fs] with white noise; S/N in 3 kHz of noise bandwidth equivalent, as SSB reports quote it. */
    private fun bruite(x: FloatArray, fs: Int, snr3kDb: Double, crete: Double, graine: Long = 7): FloatArray {
        // A sine of peak c has power c²/2; noise power in 3 kHz = σ² · 3000 / (fs/2).
        val p = crete * crete / 2
        val sigma = sqrt(p / 10.0.pow(snr3kDb / 10) * (fs / 2.0) / 3_000.0)
        val r = Random(graine)
        return FloatArray(x.size) { (x[it] + r.nextGaussian() * sigma).toFloat() }
    }

    private fun psk(texte: String, fs: Int, hz: Double, centre: Double, snr: Double, secondes: Double): Pair<String, DecodeurPsk31> {
        val s = SignauxEssai.Psk31(texte, pauseS = 0.5)
        val a = bruite(SignauxEssai.audioPsk31(s, fs, hz, secondes, 0.3), fs, snr, 0.3)
        val sb = StringBuilder()
        val d = DecodeurPsk31(fs, { sb.append(it) }, centre)
        var k = 0
        while (k < a.size) { val n = minOf(4_096, a.size - k); d.ecoute(a.copyOfRange(k, k + n), n); k += n }
        return sb.toString() to d
    }

    @Test
    fun psk31_decode_avec_bruit() {
        val msg = "CQ CQ de F4IOZ F4IOZ JN18 pse k"
        val (t, d) = psk(msg, 44_100, 1_000.0, 1_000.0, 0.0, 22.0)
        assertTrue("lu : « $t »", t.contains("de F4IOZ F4IOZ JN18"))
        assertTrue("S/B ${d.snrDb}", d.snrDb > 5)
        assertTrue("IMD ${d.imdDb}", d.imdDb.isNaN() || d.imdDb < -20)
    }

    @Test
    fun psk31_afc_sur_un_signal_decale() {
        for (ecart in listOf(-42.0, 37.0)) {
            val (t, d) = psk("CQ CQ de F4IOZ F4IOZ pse k", 8_000, 1_500.0 + ecart, 1_500.0, 10.0, 20.0)
            assertTrue("écart $ecart lu : « $t »", t.contains("de F4IOZ F4IOZ"))
            assertTrue("suivi ${d.frequenceHz}", abs(d.frequenceHz - (1_500.0 + ecart)) < 2.0)
        }
    }

    @Test
    fun psk31_rien_dans_le_bruit_seul() {
        val (t, d) = psk("x", 44_100, 1_000.0, 1_000.0, -60.0, 20.0)
        assertTrue("silencieux, lu « $t »", t.length < 3)
        assertFalse(d.ouvert)
    }

    private fun rtty(texte: String, r: DecodeurRtty.Reglages, fs: Int, hz: Double, centre: Double, snr: Double, secondes: Double, rd: DecodeurRtty.Reglages = r): Pair<String, DecodeurRtty> {
        val s = SignauxEssai.Rtty(texte, r, pauseS = 0.5)
        val a = bruite(SignauxEssai.audioRtty(s, fs, hz, secondes, 0.3), fs, snr, 0.3)
        val sb = StringBuilder()
        val d = DecodeurRtty(fs, { sb.append(it) }, centre, rd)
        var k = 0
        while (k < a.size) { val n = minOf(4_096, a.size - k); d.ecoute(a.copyOfRange(k, k + n), n); k += n }
        return sb.toString() to d
    }

    @Test
    fun rtty_45_bauds_170_hz_avec_bruit() {
        val (t, d) = rtty("RYRYRY CQ CQ DE F4IOZ F4IOZ JN18 599 73 K", DecodeurRtty.Reglages(), 44_100, 1_500.0, 1_500.0, 3.0, 16.0)
        assertTrue("lu : « $t »", t.contains("DE F4IOZ F4IOZ JN18 599 73"))
        assertTrue(d.present)
    }

    @Test
    fun rtty_autres_vitesses_et_shifts_et_inverse() {
        for (r in listOf(DecodeurRtty.Reglages(50.0, 425.0), DecodeurRtty.Reglages(75.0, 850.0), DecodeurRtty.Reglages(45.45, 170.0, inverse = true))) {
            val (t, _) = rtty("RYRY CQ DE F4IOZ 599 K", r, 48_000, 1_700.0, 1_700.0, 10.0, 12.0)
            assertTrue("$r lu : « $t »", t.contains("CQ DE F4IOZ 599"))
        }
        // The wrong sense reads nothing sensible.
        val (faux, _) = rtty("RYRY CQ DE F4IOZ 599 K", DecodeurRtty.Reglages(inverse = true), 48_000, 1_700.0, 1_700.0, 10.0, 12.0, DecodeurRtty.Reglages())
        assertFalse(faux, faux.contains("F4IOZ"))
    }

    @Test
    fun rtty_afc_sur_un_signal_decale() {
        for (ecart in listOf(-45.0, 33.0)) {
            val (t, d) = rtty("RYRYRY CQ CQ DE F4IOZ F4IOZ K", DecodeurRtty.Reglages(), 8_000, 1_200.0 + ecart, 1_200.0, 10.0, 14.0)
            assertTrue("écart $ecart lu : « $t »", t.contains("DE F4IOZ F4IOZ"))
            assertTrue("suivi ${d.frequenceHz}", abs(d.frequenceHz - (1_200.0 + ecart)) <= 6.0)
        }
    }

    @Test
    fun rtty_rien_dans_le_bruit_seul() {
        val (t, d) = rtty("x", DecodeurRtty.Reglages(), 44_100, 1_500.0, 1_500.0, -60.0, 15.0)
        assertTrue("silencieux, lu « $t »", t.length < 4)
        assertFalse(d.present)
    }

    // ------------------------------------------------------------ the text

    @Test
    fun journal_horodate_et_indicatifs() {
        var t = 1_700_000_000_000L
        val j = JournalClavier { t }
        j.ajoute("CQ CQ DE F4IOZ\n")
        t += 1_000
        j.ajoute("F4IOZ de dl1abc/p jn18 599 73")
        val l = j.lignes()
        assertEquals(2, l.size)
        assertEquals(1_700_000_000_000L, l[0].t)
        val ind = JournalClavier.indicatifs(l[1].texte, "F4IOZ")
        assertEquals(listOf("F4IOZ" to true, "dl1abc/p" to false), ind.map { l[1].texte.substring(it.debut, it.fin) to it.moi })
        assertTrue(j.texte().startsWith("2023-11-14 22:13:20Z  CQ CQ DE F4IOZ\n"))
        // A long silence begins a new line.
        t += 10_000
        j.ajoute("QRZ")
        assertEquals(3, j.lignes().size)
    }
}
