/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Sound handed out in blocks: fills [buf] and says how many samples; 0 at the end. */
fun interface SourcePcm {
    fun lis(buf: ShortArray): Int

    companion object {
        /** A sound already in memory. */
        fun de(pcm: ShortArray): SourcePcm {
            var pos = 0
            return SourcePcm { buf ->
                val n = minOf(buf.size, pcm.size - pos)
                if (n > 0) System.arraycopy(pcm, pos, buf, 0, n)
                pos += maxOf(n, 0)
                maxOf(n, 0)
            }
        }
    }
}

/**
 * A pass's sound, decoded into a temporary file rather than into memory.
 *
 * **Why.** A video of the pass needs its sound: once at ~22 kHz it is 26 MB
 * for ten minutes, and it was held in memory three or four times over
 * (each recording, the pass, the accelerated version, the opening silence
 * added): ≈ +95 MB measured on a 10-minute pass, more for a long one —
 * enough to run an older phone short. Here the recordings are decoded block
 * by block into a file of 16-bit samples, then read back block by block
 * while encoding: memory no longer depends on the pass's length.
 *
 * The rules are those of the in-memory version, sample for sample: the
 * recordings in order, each one's spoken header left as silence, averaged
 * down to ~22 kHz, one shorter than a second ignored, a later recording's
 * non-silent samples winning, the rate of the first one used throughout.
 */
class SonPassage private constructor(private val fichier: File, val rate: Int, val echantillons: Long) : Closeable {

    private val raf = RandomAccessFile(fichier, "rw")
    private val octets = ByteArray(BLOC * 2)

    /** One recording of the pass: its file, when it starts, its spoken header, its length (ms). */
    class Morceau(val f: File, val origine: Long, val annonce: Long, val dureeMs: Long)

    /** Decodes a recording, block by block, until [sortie] says stop (false). */
    fun interface Decodeur {
        fun decode(f: File, sortie: (pcm: ShortArray, count: Int, rate: Int) -> Boolean)
    }

    /** [nb] samples from sample [depuis] into [buf]; silence beyond the end. */
    fun lis(depuis: Long, buf: ShortArray, nb: Int) {
        var fait = 0
        while (fait < nb) {
            val pos = depuis + fait
            val n = minOf(BLOC, nb - fait)
            val dispo = (echantillons - pos).coerceIn(0, n.toLong()).toInt()
            if (dispo > 0) {
                raf.seek(pos * 2)
                raf.readFully(octets, 0, dispo * 2)
                ByteBuffer.wrap(octets, 0, dispo * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(buf, fait, dispo)
            }
            for (i in fait + dispo until fait + n) buf[i] = 0
            fait += n
        }
    }

    private fun ecris(depuis: Long, buf: ShortArray, nb: Int) {
        val b = ByteBuffer.allocate(nb * 2).order(ByteOrder.LITTLE_ENDIAN)
        b.asShortBuffer().put(buf, 0, nb)
        raf.seek(depuis * 2)
        raf.write(b.array(), 0, nb * 2)
    }

    /**
     * The sound as the video plays it: [titre] samples of silence (the opening
     * page), then the pass from [debutMs] — whole, or as [segments] say
     * (normal speed: its sound; faster: silence).
     */
    fun source(debutMs: Long, segments: List<JournalPassage.Segment>?, titre: Int): SourcePcm {
        // The stretches to play, in samples: (where in the pass, how long, silent?).
        val morceaux = ArrayList<Triple<Long, Long, Boolean>>()
        if (titre > 0) morceaux += Triple(0L, titre.toLong(), true)
        if (segments == null) morceaux += Triple(0L, echantillons, false)
        else {
            // Never longer than the accelerated replay plus a second (as the in-memory version).
            var reste = JournalPassage.dureeLecture(segments) * rate / 1000 + rate
            for (s in segments) {
                val longueur = minOf(((s.aMs - s.deMs) / s.vitesse * rate / 1000), reste)
                if (longueur <= 0) continue
                val de = (s.deMs - debutMs) * rate / 1000
                morceaux += Triple(de, longueur, s.vitesse != 1)
                reste -= longueur
            }
        }
        var i = 0; var dans = 0L
        return SourcePcm { buf ->
            var n = 0
            while (n < buf.size && i < morceaux.size) {
                val (de, longueur, muet) = morceaux[i]
                val k = minOf((longueur - dans).toInt().toLong(), (buf.size - n).toLong()).toInt()
                if (muet) java.util.Arrays.fill(buf, n, n + k, 0)
                else {
                    // Before the pass's first sample (a stretch starting earlier): silence.
                    val pos = de + dans
                    val avant = if (pos < 0) minOf(-pos, k.toLong()).toInt() else 0
                    java.util.Arrays.fill(buf, n, n + avant, 0)
                    if (k > avant) {
                        val tmp = ShortArray(k - avant)
                        lis(maxOf(pos, 0L), tmp, k - avant)
                        System.arraycopy(tmp, 0, buf, n + avant, k - avant)
                    }
                }
                n += k; dans += k
                if (dans >= longueur) { i++; dans = 0 }
            }
            n
        }
    }

    override fun close() {
        runCatching { raf.close() }
        fichier.delete()
    }

    companion object {
        /** Samples handled at a time: a few kilobytes, whatever the pass's length. */
        const val BLOC = 8192

        /**
         * The sound of [deMs]..[aMs] from every recording covering it, into a
         * file in [dossier]; null when none brings any.
         */
        fun fabrique(morceaux: List<Morceau>, deMs: Long, aMs: Long, dossier: File, decodeur: Decodeur): SonPassage? {
            dossier.mkdirs()
            val f = File(dossier, "son-" + System.nanoTime() + ".pcm")
            var raf: RandomAccessFile? = null
            var rate = 0
            var n = 0L
            fun ouvre(r: Int) {
                rate = r
                n = (aMs - deMs) * r / 1000
                raf = RandomAccessFile(f, "rw").also { it.setLength(n * 2) }
            }
            try {
                for (m in morceaux.sortedBy { it.origine }) {
                    if (m.origine + m.annonce >= aMs || m.origine + m.dureeMs <= deMs) continue
                    val de0 = deMs - m.origine
                    val aRel = minOf(aMs, m.origine + m.dureeMs) - m.origine
                    val deRel = maxOf(de0, m.annonce)
                    val silenceMs = deRel - de0
                    ajoute(m.f, deRel, aRel, silenceMs, decodeur, { r -> if (raf == null) ouvre(r); rate }, { raf!! }, { n })
                }
            } finally {
                runCatching { raf?.close() }
            }
            if (raf == null) { f.delete(); return null }
            return SonPassage(f, rate, n)
        }

        /**
         * One recording, decoded from [deRel] to [aRel] (ms in its file), averaged down to
         * ~22 kHz, after [silenceMs] of silence, merged into the pass's file.
         */
        private fun ajoute(f: File, deRel: Long, aRel: Long, silenceMs: Long, decodeur: Decodeur,
                           rateDuPassage: (Int) -> Int, fichier: () -> RandomAccessFile, longueur: () -> Long) {
            var facteur = 1; var rS = 0
            var debut = 0L; var fin = 0L; var lus = 0L
            var somme = 0; var dansSomme = 0
            var j = 0L          // this recording's next sample (its own rate), the silence first
            var produits = 0L   // samples decoded (silence apart)
            var limite = 0L
            var rate = 0
            // Held until a second is decoded: a shorter recording is ignored.
            val attente = ArrayList<ShortArray>()
            var enAttente = true
            val bloc = ShortArray(BLOC); var nb = 0
            var jBloc = 0L

            fun verse(buf: ShortArray, compte: Int, jDebut: Long) {
                // Pass samples i whose recording sample floor(i·rS/rate) falls in [jDebut, jDebut+compte).
                val raf = fichier()
                val total = longueur()
                val i0 = ceilDiv(jDebut * rate, rS.toLong()).coerceAtLeast(0)
                val i1 = ceilDiv((jDebut + compte) * rate, rS.toLong()).coerceAtMost(total)
                if (i1 <= i0) return
                val m = (i1 - i0).toInt()
                val tmp = ShortArray(m)
                val oct = ByteArray(m * 2)
                raf.seek(i0 * 2); raf.readFully(oct)
                ByteBuffer.wrap(oct).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(tmp)
                var change = false
                for (x in 0 until m) {
                    val k = ((i0 + x) * rS / rate - jDebut).toInt()
                    if (k in 0 until compte) {
                        val v = buf[k]
                        if (v.toInt() != 0) { tmp[x] = v; change = true }
                    }
                }
                if (!change) return
                val out = ByteBuffer.allocate(m * 2).order(ByteOrder.LITTLE_ENDIAN)
                out.asShortBuffer().put(tmp)
                raf.seek(i0 * 2); raf.write(out.array())
            }
            fun pousse() {
                if (nb == 0) return
                val copie = bloc.copyOf(nb)
                if (enAttente) attente += copie else verse(copie, nb, jBloc)
                jBloc += nb; nb = 0
            }

            decodeur.decode(f) { pcm, count, r ->
                if (rS == 0) {
                    facteur = Math.round(r / 22050.0).toInt().coerceAtLeast(1)
                    rS = r / facteur
                    debut = deRel.coerceAtLeast(0) * r / 1000; fin = aRel * r / 1000
                    limite = ((fin - debut) / facteur + 1).coerceIn(1, 40_000_000)
                    j = silenceMs * rS / 1000
                    jBloc = j
                }
                for (i in 0 until count) {
                    val k = lus + i
                    if (k < debut || k >= fin) continue
                    somme += pcm[i]; dansSomme++
                    if (dansSomme == facteur) {
                        if (produits < limite) {
                            bloc[nb++] = (somme / facteur).toShort(); produits++
                            if (nb == BLOC) pousse()
                        }
                        somme = 0; dansSomme = 0
                    }
                }
                lus += count
                // A second decoded: this recording counts; the pass's rate is set by the first one.
                if (enAttente && produits >= rS) {
                    rate = rateDuPassage(rS)
                    enAttente = false
                    var jj = j
                    for (a in attente) { verse(a, a.size, jj); jj += a.size }
                    attente.clear()
                }
                lus < fin
            }
            if (enAttente) return   // under a second (or nothing): ignored
            pousse()
        }

        private fun ceilDiv(a: Long, b: Long): Long = -Math.floorDiv(-a, b)
    }
}
