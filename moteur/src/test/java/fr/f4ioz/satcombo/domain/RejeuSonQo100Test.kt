/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import org.junit.Test

/**
 * A sound heard on the air, played to the classifier: the receiver's USB
 * sound as SatMe-PC streams it (`/api/son/flux?source=sdr`: the rate on 4
 * bytes, then 16-bit PCM), put back in IQ over a noise as its own floor.
 * Runs only when QO100_FLUX names the file; the verdicts go to
 * build/qo100-reel.txt.
 */
class RejeuSonQo100Test {
    @Test fun rejoue() {
        val chemin = System.getenv("QO100_FLUX") ?: return
        val octets = java.io.File(chemin).readBytes()
        val bb = java.nio.ByteBuffer.wrap(octets).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val rate = bb.int
        val n = (octets.size - 4) / 2
        val pcm = FloatArray(n) { bb.short / 32768f }
        val lp = fr.f4ioz.satcombo.sdr.Dsp.lowPass(63, 4000.0, rate.toDouble())
        val fac = rate / ScenesQo100.AUDIO
        val audio = FloatArray(n / fac) { k -> var s = 0.0; for (j in lp.indices) { val x = k * fac - j; if (x >= 0) s += lp[j] * pcm[x] }; s.toFloat() }
        val duree = audio.size.toDouble() / ScenesQo100.AUDIO
        val sc = ScenesQo100(duree, 1, t0Ms = (System.getenv("QO100_T0") ?: "1792000000000").toLong())
        // The scene's noise as the sound's own floor (its quietest tenth of 50 ms frames, over ~2.4 kHz).
        val l = 550
        val p = (0 until audio.size / l).map { f -> var s = 0.0; for (j in 0 until l) s += audio[f * l + j].toDouble() * audio[f * l + j]; s / l }.sorted()
        val sigma2 = p[p.size / 10] / 4 * ScenesQo100.CADENCE / 2400.0
        val k0 = Math.sqrt(sigma2) / sc.sigma
        for (k in 0 until sc.n) { sc.i[k] *= k0.toFloat(); sc.q[k] *= k0.toFloat() }
        sc.ajouteAudioBlu(0.0, audio, 1.0)
        val a = AnalyseurQo100(ScenesQo100.CADENCE, ScenesQo100.TAILLE)
        sc.analyse(a)
        val sb = StringBuilder("rate=$rate durée=${"%.1f".format(duree)} s\n")
        for (s in a.signaux(sc.finMs, 0.0)) sb.append("$s\n")
        for (pst in a.pistes()) a.mesures(pst)?.let { m -> sb.append("#${pst.id} $m\n   ${ClassifieurQo100.scores(m).entries.sortedByDescending { it.value }.take(4).joinToString { "${it.key}=${"%.2f".format(it.value)}" }}\n") }
        java.io.File("build/qo100-reel.txt").writeText(sb.toString())
    }
}
