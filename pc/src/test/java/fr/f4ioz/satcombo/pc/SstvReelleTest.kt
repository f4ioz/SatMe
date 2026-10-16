/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.plateforme.StockageMemoire
import fr.f4ioz.satcombo.sdr.Dsp
import fr.f4ioz.satcombo.sstv.SstvDecoder
import fr.f4ioz.satcombo.sstv.SstvMode
import fr.f4ioz.satcombo.sstv.SstvSon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem

/**
 * By hand only: real recordings through the PC's SSTV path as the SDR
 * dongle feeds it (resampled to [Dsp.AUDIO_RATE], through the dongle's hook).
 * Skipped unless SATME_SSTV_WAV names recordings with SSTV (":"-separated);
 * SATME_SSTV_VOIX: recordings without SSTV, which must give no picture.
 *
 * Each SSTV recording is also played with its headers blanked: the pictures
 * must still come, started on their sync pulses (continuous decoding).
 */
class SstvReelleTest {

    private val dossier = File(System.getProperty("java.io.tmpdir"), "satme-sstv-reel-" + System.nanoTime())

    private fun lit(f: File): ShortArray {
        val a0 = AudioSystem.getAudioInputStream(f)
        val voulu = AudioFormat(a0.format.sampleRate, 16, 1, true, false)
        val a = if (a0.format.matches(voulu)) a0 else AudioSystem.getAudioInputStream(voulu, a0)
        val o = a.use { it.readAllBytes() }
        val pcm = ShortArray(o.size / 2) { ((o[2 * it].toInt() and 0xFF) or (o[2 * it + 1].toInt() shl 8)).toShort() }
        return SstvSon.reechantillonne(pcm, voulu.sampleRate.toInt(), Dsp.AUDIO_RATE)
    }

    /** Through SstvPc's dongle hook, in the dongle's blocks; the pictures kept. */
    private fun parLaCle(pcm: ShortArray, nom: String, continu: Boolean = true): List<String> {
        var t = 1_800_000_000_000L
        val d = File(dossier, nom + "-" + System.nanoTime()).apply { mkdirs() }
        val s = SstvPc(StockageMemoire(), d, { SstvPc.Contexte("TEST") }) { t }
        s.continu = continu
        val b = ShortArray(4410)
        var i = 0
        while (i < pcm.size) {
            val n = minOf(b.size, pcm.size - i)
            System.arraycopy(pcm, i, b, 0, n)
            s.ecouteSdr(b, n, Dsp.AUDIO_RATE)
            i += n; t += n * 1000L / Dsp.AUDIO_RATE
        }
        s.abandonne()
        return s.images().map { it.mode + (if (it.complete) "" else " (partielle)") }
    }

    /** Where each header ends (samples), found by a decoder that waits for headers. */
    private fun entetes(pcm: ShortArray): List<Long> {
        val fins = ArrayList<Long>()
        lateinit var d: SstvDecoder
        d = SstvDecoder(Dsp.AUDIO_RATE, object : SstvDecoder.Listener {
            override fun onVis(mode: SstvMode) { fins += d.debutTrame + (1.3 * Dsp.AUDIO_RATE).toLong() }
        })
        val b = ShortArray(4410)
        var i = 0
        while (i < pcm.size) { val n = minOf(b.size, pcm.size - i); System.arraycopy(pcm, i, b, 0, n); d.feed(b, n); i += n }
        return fins
    }

    @Test fun `de vrais enregistrements par la cle SDR`() {
        val wavs = System.getenv("SATME_SSTV_WAV")?.split(':')?.filter { it.isNotBlank() }.orEmpty()
        val voix = System.getenv("SATME_SSTV_VOIX")?.split(':')?.filter { it.isNotBlank() }.orEmpty()
        assumeTrue(wavs.isNotEmpty() || voix.isNotEmpty())
        try {
            for (w in wavs) {
                val pcm = lit(File(w))
                val t0 = System.currentTimeMillis()
                val avec = parLaCle(pcm, "avec")
                println("$w : ${pcm.size / Dsp.AUDIO_RATE} s, ${avec.size} image(s) ${avec} en ${System.currentTimeMillis() - t0} ms")
                assertTrue("aucune image dans $w", avec.isNotEmpty())
                val enAttente = parLaCle(pcm, "attente", continu = false)
                println("  en attente d'en-tête seulement (sans décodage continu) : ${enAttente.size} image(s) $enAttente")
                // The headers blanked (a second of hiss in their place).
                val fins = entetes(pcm)
                val r = java.util.Random(7)
                val sans = pcm.copyOf()
                for (f in fins) {
                    val a = (f - Dsp.AUDIO_RATE).coerceAtLeast(0).toInt(); val z = minOf(sans.size, (f + Dsp.AUDIO_RATE / 50).toInt())
                    for (k in a until z) sans[k] = (r.nextGaussian() * 300).toInt().toShort()
                }
                val continu = parLaCle(sans, "sans-vis", continu = true)
                val attente = parLaCle(sans, "sans-vis-attente", continu = false)
                println("  sans en-tête (${fins.size} effacés) : décodage continu ${continu.size} image(s) $continu ; en attente d'en-tête ${attente.size}")
                assertTrue("sans en-tête, rien dans $w", continu.isNotEmpty())
            }
            for (v in voix) {
                val pcm = lit(File(v))
                val l = parLaCle(pcm, "voix")
                println("$v (sans SSTV) : ${pcm.size / Dsp.AUDIO_RATE} s, ${l.size} image(s) $l")
                assertEquals("fausse image dans $v", 0, l.size)
            }
            // Ten minutes of hiss.
            val r = java.util.Random(3)
            val bruit = ShortArray(600 * Dsp.AUDIO_RATE) { (r.nextGaussian() * 4000).toInt().coerceIn(-32767, 32767).toShort() }
            val l = parLaCle(bruit, "bruit")
            println("bruit 600 s : ${l.size} image(s) $l")
            assertEquals(0, l.size)
        } finally { dossier.deleteRecursively() }
    }
}
