/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sdr

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import kotlin.math.abs

/**
 * The frequencies along an I/Q stream, as SigMF captures: a new one when the
 * PLL moves, or when the software share of the Doppler has moved by a hertz
 * or more (at most four a second) — so a pass is heard back exactly as it
 * was heard.
 */
class PisteIq(private val debit: Double) {
    private val l = ArrayList<SigMF.Capture>()

    @Synchronized fun note(echantillon: Long, cielHz: Long, pllHz: Long, fineHz: Double) {
        val d = l.lastOrNull()
        val c = SigMF.Capture(echantillon, cielHz, pllHz, fineHz)
        if (d != null && d.pllHz == pllHz && d.frequenceHz == cielHz) {
            if (abs(d.fineHz - fineHz) < 1.0 || echantillon - d.echantillon < debit / 4) return
        }
        if (d != null && d.echantillon == echantillon) l[l.size - 1] = c else l += c
    }

    /** Forgets what is before [echantillon], the capture under way there kept. */
    @Synchronized fun oublieAvant(echantillon: Long) {
        val i = l.indexOfLast { it.echantillon <= echantillon }
        if (i > 0) l.subList(0, i).clear()
    }

    @Synchronized fun liste(): List<SigMF.Capture> = l.toList()
    @Synchronized fun vide() = l.clear()
}

/**
 * « Remonter le temps »: the last minutes of raw I/Q, always, in a file of a
 * fixed size on the disk (a ring: the oldest overwritten) — never in memory,
 * where ten minutes would be gigabytes. Saved on demand as a SigMF
 * recording ([exporte]) while the ring keeps running.
 *
 * Bytes as SigMF stores them: [SigMF.Format.CU8] from a dongle,
 * [SigMF.Format.CI16] from the 16-bit receivers.
 */
class AnneauIq(
    val fichier: File,
    val format: SigMF.Format,
    val capacitePaires: Long,
    val debit: Double = Dsp.RTL_RATE.toDouble(),
    private val horloge: () -> Long = System::currentTimeMillis,
) : Closeable {
    init { require(capacitePaires > 0 && (format == SigMF.Format.CU8 || format == SigMF.Format.CI16)) }

    private val bpp = format.octetsParPaire
    private val capOctets = capacitePaires * bpp
    private val raf = RandomAccessFile(fichier.apply { parentFile?.mkdirs() }, "rw").apply { setLength(0) }
    private var tampon = ByteArray(0)
    private val piste = PisteIq(debit)

    /** Pairs written since the start (the ring holds the last [capacitePaires] of them). */
    @Volatile var ecrites = 0L; private set
    /** The wall clock at the last block: the samples' times are counted back from it. */
    @Volatile private var dernierMs = 0L
    @Volatile var ferme = false; private set

    /** Pairs held now. */
    val tenues: Long get() = minOf(ecrites, capacitePaires)

    /** [n] bytes (I, Q unsigned) from a dongle. */
    @Synchronized fun ecrit8(b: ByteArray, n: Int, cielHz: Long, pllHz: Long, fineHz: Double) {
        if (ferme || format != SigMF.Format.CU8) return
        piste.note(ecrites, cielHz, pllHz, fineHz)
        ecritOctets(b, n - n % 2)
    }

    /** [n] shorts (I, Q signed) from a 16-bit receiver. */
    @Synchronized fun ecrit16(s: ShortArray, n: Int, cielHz: Long, pllHz: Long, fineHz: Double) {
        if (ferme || format != SigMF.Format.CI16) return
        val m = n - n % 2
        if (tampon.size < 2 * m) tampon = ByteArray(2 * m)
        OctetsIq.de16(s, m, tampon)
        piste.note(ecrites, cielHz, pllHz, fineHz)
        ecritOctets(tampon, 2 * m)
    }

    private fun ecritOctets(b: ByteArray, len: Int) {
        var off = 0
        while (off < len) {
            val pos = (ecrites * bpp + off) % capOctets
            val k = minOf((len - off).toLong(), capOctets - pos).toInt()
            raf.seek(pos); raf.write(b, off, k)
            off += k
        }
        ecrites += len / bpp
        dernierMs = horloge()
        piste.oublieAvant(ecrites - capacitePaires)
    }

    /** What [exporte] wrote: from which pair of the stream, how many, the captures (counted from 0), the first sample's time. */
    data class Extrait(val debutPaire: Long, val paires: Long, val captures: List<SigMF.Capture>, val debutMs: Long)

    /**
     * The last [paires] pairs (all held, at most) into [sortie], while the
     * ring keeps being written: copied oldest first, by pieces, each piece
     * checked still there under the lock — the copy outruns the writing,
     * [margePaires] of the oldest left out give it its start.
     */
    fun exporte(sortie: OutputStream, paires: Long = Long.MAX_VALUE, margePaires: Long = (2 * debit).toLong(), progres: (Float) -> Unit = {}): Extrait {
        val (debut, n, caps, t0) = synchronized(this) {
            val fin = ecrites
            val n = minOf(paires, tenues - margePaires.coerceIn(0L, tenues / 2))
            if (n <= 0) throw IOException("rien à garder pour l'instant")
            val debut = fin - n
            Extrait(debut, n, SigMF.capturesDans(piste.liste(), debut, n), dernierMs - Math.round((fin - debut) * 1000.0 / debit))
        }
        val morceau = (1 shl 20) / bpp
        val buf = ByteArray(morceau * bpp)
        var p = debut
        while (p < debut + n) {
            val k = minOf(morceau.toLong(), debut + n - p).toInt()
            synchronized(this) {
                if (ferme) throw IOException("le récepteur s'est arrêté pendant la copie")
                if (p < ecrites - capacitePaires) throw IOException("la copie a été rattrapée par l'écriture (disque trop lent)")
                var o = 0
                while (o < k * bpp) {
                    val abs = p * bpp + o
                    val pos = abs % capOctets
                    val l = minOf((k * bpp - o).toLong(), capOctets - pos).toInt()
                    raf.seek(pos); raf.readFully(buf, o, l)
                    o += l
                }
            }
            sortie.write(buf, 0, k * bpp)
            p += k
            progres(((p - debut).toDouble() / n).toFloat())
        }
        return Extrait(debut, n, caps, t0)
    }

    @Synchronized override fun close() {
        if (ferme) return
        ferme = true
        runCatching { raf.close() }
        fichier.delete()
    }

    companion object {
        /** The ring's size on disk for [minutes] of [format] at the receiver's rate. */
        fun octets(minutes: Int, format: SigMF.Format, debit: Double = Dsp.RTL_RATE.toDouble()): Long =
            (minutes * 60.0 * debit).toLong() * format.octetsParPaire
    }
}

/**
 * « Enregistrer »: the raw I/Q written straight to a SigMF data file, up to
 * [limitePaires] (then [ecrit8] / [ecrit16] say false: the caller closes).
 */
class EnregistrementIq(
    val data: File,
    val format: SigMF.Format,
    val limitePaires: Long,
    val debit: Double = Dsp.RTL_RATE.toDouble(),
    horloge: () -> Long = System::currentTimeMillis,
) : Closeable {
    private val sortie = BufferedOutputStream(FileOutputStream(data.apply { parentFile?.mkdirs() }), 1 shl 20)
    private val piste = PisteIq(debit)
    private var tampon = ByteArray(0)
    val debutMs: Long = horloge()
    @Volatile var paires = 0L; private set
    @Volatile var erreur: String? = null; private set
    @Volatile var ferme = false; private set

    val captures: List<SigMF.Capture> get() = piste.liste()

    @Synchronized fun ecrit8(b: ByteArray, n: Int, cielHz: Long, pllHz: Long, fineHz: Double): Boolean {
        if (ferme || format != SigMF.Format.CU8) return false
        val k = minOf((n / 2).toLong(), limitePaires - paires).toInt()
        if (k <= 0) return false
        piste.note(paires, cielHz, pllHz, fineHz)
        return ecrit(b, 2 * k)
    }

    @Synchronized fun ecrit16(s: ShortArray, n: Int, cielHz: Long, pllHz: Long, fineHz: Double): Boolean {
        if (ferme || format != SigMF.Format.CI16) return false
        val k = minOf((n / 2).toLong(), limitePaires - paires).toInt()
        if (k <= 0) return false
        if (tampon.size < 4 * k) tampon = ByteArray(4 * k)
        OctetsIq.de16(s, 2 * k, tampon)
        piste.note(paires, cielHz, pllHz, fineHz)
        return ecrit(tampon, 4 * k)
    }

    private fun ecrit(b: ByteArray, len: Int): Boolean = try {
        sortie.write(b, 0, len)
        paires += len / format.octetsParPaire
        paires < limitePaires
    } catch (e: IOException) { erreur = e.message ?: "écriture impossible"; false }

    @Synchronized override fun close() {
        if (ferme) return
        ferme = true
        runCatching { sortie.close() }
    }
}
