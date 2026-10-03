/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * The sound of one picture, kept next to it: a WAV with the PNG's name. To
 * hear just that picture again, decode it again, or make a short video of
 * it — without the whole recording of the pass.
 */
object SstvSon {

    /** The picture's sound, saved or to be saved. */
    fun fichier(png: File): File = File(png.parentFile, png.name.removeSuffix(".png") + ".wav")

    /** Its video, once made. */
    /** Its animated GIF, once made. */
    fun gif(png: File): File = File(png.parentFile, png.name.removeSuffix(".png") + ".gif")

    fun video(png: File, hd: Boolean = false): File =
        File(png.parentFile, png.name.removeSuffix(".png") + (if (hd) "_HD" else "") + ".mp4")

    /**
     * The last few minutes of sound, kept at about 11 kHz (SSTV needs no more
     * than 2.3 kHz): enough for the longest mode, a few megabytes.
     */
    class Memoire(rateEntree: Int, secondes: Int = 330) {
        /** One sample kept out of [facteur] (averaged). */
        val facteur: Int = (rateEntree / 11_025.0).roundToInt().coerceAtLeast(1)
        val rate: Int = rateEntree / facteur
        private val tampon = ShortArray(rate * secondes)
        /** Samples kept since the start. */
        private var gardes = 0L
        private var somme = 0
        private var dansSomme = 0

        @Synchronized
        fun ajoute(pcm: ShortArray, n: Int) {
            for (i in 0 until n) {
                somme += pcm[i]; dansSomme++
                if (dansSomme == facteur) {
                    tampon[(gardes % tampon.size).toInt()] = (somme / facteur).toShort()
                    gardes++; somme = 0; dansSomme = 0
                }
            }
        }

        /**
         * The sound between two positions counted in samples *received*, as
         * far as it is still kept.
         */
        @Synchronized
        fun extrait(debutEntree: Long, finEntree: Long): ShortArray {
            val fin = (finEntree / facteur).coerceAtMost(gardes)
            val debut = (debutEntree / facteur).coerceAtLeast(gardes - tampon.size).coerceAtLeast(0L)
            if (fin <= debut) return ShortArray(0)
            return ShortArray((fin - debut).toInt()) { tampon[((debut + it) % tampon.size).toInt()] }
        }
    }

    /** A mono 16-bit WAV. */
    fun ecritWav(f: File, pcm: ShortArray, rate: Int) {
        val donnees = pcm.size * 2
        val b = ByteBuffer.allocate(44 + donnees).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt(36 + donnees); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(1)
        b.putInt(rate); b.putInt(rate * 2); b.putShort(2); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(donnees)
        for (s in pcm) b.putShort(s)
        f.writeBytes(b.array())
    }

    /** A mono 16-bit WAV as written by [ecritWav]: the samples and their rate, or null. */
    fun litWav(f: File): Pair<ShortArray, Int>? = runCatching {
        RandomAccessFile(f, "r").use { r ->
            val tete = ByteArray(44); r.readFully(tete)
            val b = ByteBuffer.wrap(tete).order(ByteOrder.LITTLE_ENDIAN)
            if (String(tete, 0, 4) != "RIFF" || String(tete, 8, 4) != "WAVE") return null
            if (b.getShort(22).toInt() != 1 || b.getShort(34).toInt() != 16) return null
            val rate = b.getInt(24)
            val n = ((r.length() - 44) / 2).toInt()
            val octets = ByteArray(n * 2); r.readFully(octets)
            val bb = ByteBuffer.wrap(octets).order(ByteOrder.LITTLE_ENDIAN)
            ShortArray(n) { bb.getShort() } to rate
        }
    }.getOrNull()

    /** Linear resampling, for an encoder that wants another rate. */
    fun reechantillonne(pcm: ShortArray, de: Int, vers: Int): ShortArray {
        if (de == vers || pcm.isEmpty()) return pcm
        val n = (pcm.size.toLong() * vers / de).toInt()
        return ShortArray(n) { i ->
            val x = i.toDouble() * de / vers
            val a = x.toInt().coerceAtMost(pcm.size - 1)
            val b = (a + 1).coerceAtMost(pcm.size - 1)
            val f = x - a
            (pcm[a] * (1 - f) + pcm[b] * f).roundToInt().toShort()
        }
    }
}
