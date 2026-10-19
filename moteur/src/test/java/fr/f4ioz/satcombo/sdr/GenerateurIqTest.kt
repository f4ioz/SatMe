/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sdr

import fr.f4ioz.satcombo.clavier.DecodeurPsk31
import fr.f4ioz.satcombo.clavier.DecodeurRtty
import fr.f4ioz.satcombo.cw.DecodeurCw
import fr.f4ioz.satcombo.domain.Ft8Decodeur
import fr.f4ioz.satcombo.domain.Ft8Signal
import fr.f4ioz.satcombo.sstv.SstvDecoder
import fr.f4ioz.satcombo.sstv.SstvMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The test generator, end to end: its I/Q through SatMe's receive chain
 * ([RxChain], tuned as the page's « Écouter » sets it), the sound to the
 * decoders — CW, SSTV, FT8 — and what they read compared with what was sent.
 */
class GenerateurIqTest {

    private val rien = GenerateurIq.Signal(false, 0, 0.0)
    private val seul = GenerateurIq.Reglages(porteuse = rien, cw = rien, blu = rien, fm = rien, ft8 = rien, sstv = rien, psk31 = rien, rtty = rien)

    /** [secondes] of the generator heard as [cle] is listened to; each audio block to [son]. */
    private fun ecoute(r: GenerateurIq.Reglages, cle: String, secondes: Double, debutMs: Long = 0L, son: (ShortArray, Int) -> Unit) {
        val g = GenerateurIq(r, cadence = false, debutMs = debutMs)
        val e = g.ecoutes().first { it.cle == cle }
        val chaine = RxChain().apply { mode = RxMode.valueOf(e.mode); offsetHz = e.decalageHz.toDouble(); if (e.largeurHz > 0) bandwidthHz = e.largeurHz.toDouble() }
        val iq = ShortArray(2 * 16_384)
        val pcm = ShortArray(chaine.maxAudio(iq.size))
        var paires = 0L
        while (paires < secondes * Dsp.RTL_RATE) {
            val n = g.read16(iq)
            val k = chaine.process16(iq, n, pcm)
            if (k > 0) son(pcm, k)
            paires += n / 2
        }
    }

    @Test
    fun le_cw_du_generateur_est_lu_par_le_decodeur_cw() {
        val r = seul.copy(cw = GenerateurIq.Signal(true, 12_000, 25.0), cwTexte = "VVV DE SATME TEST", cwWpm = 20)
        val lu = StringBuilder()
        val d = DecodeurCw(Dsp.AUDIO_RATE, { lu.append(it) })
        ecoute(r, "cw", 16.0) { p, n -> d.ecoute(p, n) }
        println("CW lu : « $lu », ton ${d.tonHz} Hz, ${d.wpm} mots/min")
        assertTrue(lu.toString(), lu.contains("SATME"))
        assertTrue(lu.toString(), lu.contains("TEST"))
        assertTrue("ton ${d.tonHz}", abs(d.tonHz - GenerateurIq.NOTE_CW_HZ) <= 30)
    }

    @Test
    fun limage_sstv_du_generateur_est_decodee() {
        val m = SstvMode.byName("Robot 36")!!
        val r = seul.copy(sstv = GenerateurIq.Signal(true, -45_000, 30.0), sstvMode = m.name)
        var recu: IntArray? = null
        var mode: SstvMode? = null
        var lignes = 0
        val dec = SstvDecoder(Dsp.AUDIO_RATE, object : SstvDecoder.Listener {
            override fun onImage(mode0: SstvMode, pixels: IntArray, linesDone: Int, complete: Boolean) {
                if (recu == null) { recu = pixels.copyOf(); mode = mode0; lignes = linesDone }
            }
        })
        ecoute(r, "sstv", SstvEncoderSecondes(m) + 1.0) { p, n -> dec.feed(p, n) }
        dec.finish()
        assertEquals(m, mode)
        assertTrue("$lignes lignes", lignes >= m.height - 2)
        val src = GenerateurIq.mire(m)
        var s = 0L; var n = 0
        for (y in 2 until m.height - 2) for (x in 0 until m.width) {
            val a = src[y * m.width + x]; val b = recu!![y * m.width + x]
            for (sh in intArrayOf(16, 8, 0)) { s += abs(((a shr sh) and 255) - ((b shr sh) and 255)); n++ }
        }
        val ecart = s.toDouble() / n
        println("SSTV du générateur : écart moyen %.1f / 255".format(ecart))
        assertTrue("écart $ecart", ecart < 15.0)
    }

    private fun SstvEncoderSecondes(m: SstvMode) = fr.f4ioz.satcombo.sstv.SstvEncoder.seconds(m)

    @Test
    fun lappel_ft8_du_generateur_est_decode() {
        val r = seul.copy(ft8 = GenerateurIq.Signal(true, 50_000, 15.0), ft8Message = "CQ Q0SAT JN18")
        // An even slot from the first sample: the call 0.5 s in.
        val audio = FloatArray((14.0 * Dsp.AUDIO_RATE).toInt())
        var k = 0
        ecoute(r, "ft8", 14.0, debutMs = 1_800_000_000_000L - 1_800_000_000_000L % 30_000L) { p, n ->
            for (i in 0 until n) if (k < audio.size) audio[k++] = p[i] / 32_768f
        }
        val l = Ft8Decodeur.decode(audio, Dsp.AUDIO_RATE.toDouble(), Ft8Signal.FT8, 1_300.0, 1_800.0)
        assertEquals(l.joinToString { it.message.brut }, 1, l.size)
        assertEquals("CQ Q0SAT JN18", l[0].message.brut)
        assertEquals(1_500.0, l[0].frequenceHz, 5.0)
    }

    @Test
    fun le_generateur_suit_le_temps_reel() {
        var t = 5_000_000L
        val g = GenerateurIq(seul, horloge = { t }, cadence = true, debutMs = t, dort = { t += it })
        val b = ShortArray(2 * 10_584)
        repeat(200) { g.read16(b) }
        assertEquals(2_000L, t - 5_000_000L)
    }

    @Test
    fun les_reglages_se_bornent_et_les_messages_ft8_se_verifient() {
        assertNotNull(GenerateurIq.tonsFt8("CQ Q0SAT JN18"))
        assertEquals(79, GenerateurIq.tonsFt8("F4IOZ Q0SAT JN18")!!.size)
        assertEquals(null, GenerateurIq.messageFt8("BONJOUR A TOUS"))
        val r = GenerateurIq.Reglages().avec(mapOf("cwHz" to "999999", "ft8Message" to "PAS UN MESSAGE", "sstvMode" to "Martin M1", "fmOn" to "0")::get)
        assertEquals(85_000, r.cw.decalageHz)
        assertEquals("CQ Q0SAT JN18", r.ft8Message)
        assertEquals("Martin M1", r.sstvMode)
        assertEquals(false, r.fm.actif)
        assertTrue(GenerateurIq.ecoutes(r).none { it.cle == "fm" })
    }

    @Test
    fun le_psk31_et_le_rtty_du_generateur_sont_lus() {
        val r = seul.copy(psk31 = GenerateurIq.Signal(true, 20_000, 12.0), rtty = GenerateurIq.Signal(true, -8_000, 12.0))
        val lp = StringBuilder()
        val p = DecodeurPsk31(Dsp.AUDIO_RATE, { lp.append(it) }, GenerateurIq.AUDIO_PSK31_HZ.toDouble())
        ecoute(r, "psk31", 14.0) { b, n -> p.ecoute(b, n) }
        println("PSK31 lu : « $lp », ${p.frequenceHz} Hz")
        assertTrue(lp.toString(), lp.contains("de Q0SAT Q0SAT JN18"))
        val lr = StringBuilder()
        val d = DecodeurRtty(Dsp.AUDIO_RATE, { lr.append(it) }, GenerateurIq.AUDIO_RTTY_HZ.toDouble())
        ecoute(r, "rtty", 12.0) { b, n -> d.ecoute(b, n) }
        println("RTTY lu : « $lr », ${d.frequenceHz} Hz")
        assertTrue(lr.toString(), lr.contains("CQ DE Q0SAT Q0SAT JN18"))
    }

    @Test
    fun un_canal_secondaire_psk31_lit_le_generateur() {
        // The receiver tuned elsewhere; the channel 20 kHz away takes PSK31 from the IQ.
        val r = seul.copy(psk31 = GenerateurIq.Signal(true, 20_000, 12.0))
        val g = GenerateurIq(r, cadence = false)
        val chaine = RxChain().apply { mode = RxMode.USB; offsetHz = 0.0 }
        val canaux = CanauxSecondaires()
        val lu = StringBuilder()
        val p = DecodeurPsk31(Dsp.AUDIO_RATE, { lu.append(it) }, GenerateurIq.AUDIO_PSK31_HZ.toDouble())
        val cibles = listOf(CanauxSecondaires.Cible(1, 20_000.0 - GenerateurIq.AUDIO_PSK31_HZ, ModeCanal.USB))
        chaine.canaux = { aI, aQ, n, bI, bQ, n1 -> canaux.traite(aI, aQ, n, bI, bQ, n1, cibles) { _, pcm, k -> p.ecoute(pcm, k) } }
        val iq = ShortArray(2 * 16_384)
        val pcm = ShortArray(chaine.maxAudio(iq.size))
        var paires = 0L
        while (paires < 14.0 * Dsp.RTL_RATE) { val n = g.read16(iq); chaine.process16(iq, n, pcm); paires += n / 2 }
        assertTrue(lu.toString(), lu.contains("de Q0SAT Q0SAT JN18"))
    }
}
