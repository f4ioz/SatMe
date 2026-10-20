/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sdr

import fr.f4ioz.satcombo.domain.SpectrePassage
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/**
 * A pass's waterfall from its I/Q recording (SigMF): at most [lignesMax]
 * moments spread over the file, each the average of a few windowed FFTs,
 * the [largeurHz] around the listened frequency kept — the recording's own
 * software share at that moment (its captures follow the Doppler), plus
 * [decalageHz] when given (a Doppler worked out afterwards for a recording
 * made at a fixed frequency). Read only; seeks, never loads the file.
 */
object CascadeIq {

    fun calcule(data: File, meta: SigMF.Meta, largeurHz: Double = 25_000.0, bins: Int = 128, lignesMax: Int = 600,
                moyennes: Int = 4, decalageHz: ((Long) -> Double)? = null): SpectrePassage.Bande {
        val bpp = meta.format.octetsParPaire
        val paires = data.length() / bpp
        // A bin no wider than a column, within reason.
        var n = 256
        while (n < 16384 && meta.debit / n > largeurHz / bins) n *= 2
        val parBin = meta.debit / n
        val fen = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / (n - 1)) }
        val lignes = ArrayList<SpectrePassage.Ligne>()
        if (paires < n.toLong()) return SpectrePassage.Bande(meta.norad, meta.satellite, largeurHz, lignes, SpectrePassage.SOURCE_IQ)
        val nl = min(lignesMax.toLong(), paires / n).toInt().coerceAtLeast(1)
        val re = DoubleArray(n); val im = DoubleArray(n); val acc = DoubleArray(n)
        val buf = ByteArray(n * bpp)
        val t0 = meta.debutMs ?: 0L
        RandomAccessFile(data, "r").use { raf ->
            for (i in 0 until nl) {
                val debut = (paires - n.toLong() * moyennes).coerceAtLeast(0L) * i / max(1, nl - 1)
                acc.fill(0.0)
                var faites = 0
                for (k in 0 until moyennes) {
                    val p = debut + k.toLong() * n
                    if (p + n > paires) break
                    raf.seek(p * bpp); raf.readFully(buf)
                    lis(buf, meta.format, re, im)
                    for (j in 0 until n) { re[j] *= fen[j]; im[j] *= fen[j] }
                    Fft.transform(re, im)
                    for (j in 0 until n) acc[j] += re[j] * re[j] + im[j] * im[j]
                    faites++
                }
                if (faites == 0) continue
                // Shifted: the middle is the recording's centre.
                val sp = FloatArray(n) { j -> val s = (j + n / 2) % n; (10 * log10(acc[s] / faites / n / n + 1e-20)).toFloat() }
                val milieu = debut + n.toLong() * faites / 2
                val tMs = t0 + Math.round(milieu * 1000.0 / meta.debit)
                val fine = meta.captureA(milieu)?.fineHz ?: 0.0
                val centre = fine + (decalageHz?.invoke(tMs) ?: 0.0)
                val col = SpectrePassage.fenetre(sp, centre / parBin + n / 2.0, largeurHz / 2 / parBin, bins) ?: continue
                lignes += SpectrePassage.Ligne(tMs, col)
            }
        }
        return SpectrePassage.Bande(meta.norad, meta.satellite, largeurHz, lignes, SpectrePassage.SOURCE_IQ)
    }

    /** One block of pairs as complex samples, full scale ±1. */
    private fun lis(b: ByteArray, f: SigMF.Format, re: DoubleArray, im: DoubleArray) {
        val n = re.size
        when (f) {
            SigMF.Format.CU8 -> for (j in 0 until n) { re[j] = ((b[2 * j].toInt() and 255) - 127.5) / 127.5; im[j] = ((b[2 * j + 1].toInt() and 255) - 127.5) / 127.5 }
            SigMF.Format.CI8 -> for (j in 0 until n) { re[j] = b[2 * j] / 128.0; im[j] = b[2 * j + 1] / 128.0 }
            SigMF.Format.CI16 -> { val s = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer(); for (j in 0 until n) { re[j] = s.get(2 * j) / 32768.0; im[j] = s.get(2 * j + 1) / 32768.0 } }
            SigMF.Format.CF32 -> { val s = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer(); for (j in 0 until n) { re[j] = s.get(2 * j).toDouble(); im[j] = s.get(2 * j + 1).toDouble() } }
        }
    }
}
