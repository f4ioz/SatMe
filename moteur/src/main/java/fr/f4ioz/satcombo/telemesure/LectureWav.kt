/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.telemesure

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A WAV recording read for a decoder: 16-bit PCM (or 8-bit, or 32-bit
 * float), mono or several channels (the first one kept), any rate, its
 * chunks in any order (a LIST before « data », as other programs write).
 */
object LectureWav {

    /** The samples (first channel) and their rate, or null when it is not a WAV this reads. */
    fun lis(o: ByteArray): Pair<ShortArray, Int>? {
        if (o.size < 44 || String(o, 0, 4, Charsets.ISO_8859_1) != "RIFF" || String(o, 8, 4, Charsets.ISO_8859_1) != "WAVE") return null
        val b = ByteBuffer.wrap(o).order(ByteOrder.LITTLE_ENDIAN)
        var i = 12
        var format = 0; var canaux = 0; var rate = 0; var bits = 0
        while (i + 8 <= o.size) {
            val id = String(o, i, 4, Charsets.ISO_8859_1)
            val n = b.getInt(i + 4).toLong().and(0xFFFFFFFFL).toInt().let { if (it < 0) o.size else it }
            val d = i + 8
            when (id) {
                "fmt " -> {
                    format = b.getShort(d).toInt() and 0xFFFF; canaux = b.getShort(d + 2).toInt()
                    rate = b.getInt(d + 4); bits = b.getShort(d + 14).toInt()
                    // WAVE_FORMAT_EXTENSIBLE: the real format in the sub-format's first two bytes.
                    if (format == 0xFFFE && n >= 26) format = b.getShort(d + 24).toInt() and 0xFFFF
                }
                "data" -> {
                    if (canaux <= 0 || rate <= 0) return null
                    val fin = minOf(o.size, d + n)
                    val pas = canaux * bits / 8
                    if (pas <= 0) return null
                    val m = (fin - d) / pas
                    val s = ShortArray(m)
                    for (k in 0 until m) {
                        val p = d + k * pas
                        s[k] = when {
                            format == 1 && bits == 16 -> b.getShort(p)
                            format == 1 && bits == 8 -> (((o[p].toInt() and 0xFF) - 128) shl 8).toShort()
                            format == 1 && bits == 24 -> (b.getShort(p + 1))
                            format == 1 && bits == 32 -> (b.getInt(p) shr 16).toShort()
                            format == 3 && bits == 32 -> (b.getFloat(p).coerceIn(-1f, 1f) * 32767).toInt().toShort()
                            else -> return null
                        }
                    }
                    return s to rate
                }
            }
            i = d + n + (n and 1)
        }
        return null
    }
}
