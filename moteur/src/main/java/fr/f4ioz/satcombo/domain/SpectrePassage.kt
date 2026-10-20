/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * « Journal détaillé »: a pass's spectrum around the downlink, one averaged
 * line every couple of seconds, for the pass report's waterfall.
 *
 * The receiver's spectra are taken as they come ([Enregistreur.ajoute]); the
 * window around the listened frequency (which follows the Doppler) is
 * resampled to [Enregistreur.bins] columns and averaged over
 * [Enregistreur.periodeMs]. The lines are held in a bounded ring: full, it
 * keeps every other moment (pairs averaged, the period doubled), so a long
 * pass stays whole in a fixed memory. Kept as a small file per pass.
 */
object SpectrePassage {

    /** One line: its time (ms, the mean of the moments it averages) and dB per column, low frequency first. */
    class Ligne(val tMs: Long, val db: FloatArray)

    /** A pass's lines: [largeurHz] wide around the listened frequency; [source] "spectres" or "iq". */
    class Bande(val catnum: Int, val satellite: String, val largeurHz: Double, val lignes: List<Ligne>, val source: String = SOURCE_SPECTRES) {
        val debutMs get() = lignes.firstOrNull()?.tMs ?: 0L
        val finMs get() = lignes.lastOrNull()?.tMs ?: 0L
    }

    const val SOURCE_SPECTRES = "spectres"
    const val SOURCE_IQ = "iq"
    const val EXT = ".spectre"
    private const val MAGIC = 0x534D5350 // "SMSP"

    /**
     * The averaging and the ring. Not thread-safe by itself: one caller (the
     * logger's loop) at a time.
     */
    class Enregistreur(val bins: Int = 128, periodeMs: Long = 2_000L, val capacite: Int = 900) {
        init { require(bins >= 8 && capacite >= 4 && periodeMs > 0) }
        var periodeMs = periodeMs; private set
        private val lignes = ArrayList<Ligne>()
        private val somme = DoubleArray(bins)
        private var n = 0
        private var sommeT = 0L
        private var debutLigne = -1L

        /** Lines kept so far. */
        val taille get() = lignes.size
        fun lignes(): List<Ligne> = lignes.toList()

        /**
         * One spectrum [sp] (dB per bin, low first): its bins between
         * [centreBin] − [demiBins] and [centreBin] + [demiBins] (fractional
         * bins), resampled. False when the window falls outside it.
         */
        fun ajoute(tMs: Long, sp: FloatArray, centreBin: Double, demiBins: Double): Boolean {
            val col = fenetre(sp, centreBin, demiBins, bins) ?: return false
            if (debutLigne >= 0 && tMs - debutLigne >= periodeMs) ferme()
            if (debutLigne < 0) debutLigne = tMs
            for (i in 0 until bins) somme[i] += col[i]
            n++; sommeT += tMs - debutLigne
            return true
        }

        /** The line under way closed (the pass over). */
        fun termine() { if (n > 0) ferme() }

        private fun ferme() {
            if (n > 0) {
                val l = FloatArray(bins) { (somme[it] / n).toFloat() }
                lignes += Ligne(debutLigne + sommeT / n, l)
                if (lignes.size >= capacite) decime()
            }
            somme.fill(0.0); n = 0; sommeT = 0L; debutLigne = -1L
        }

        /** Every pair of lines merged into one: half the lines, twice the period. */
        private fun decime() {
            val m = ArrayList<Ligne>(lignes.size / 2 + 1)
            var i = 0
            while (i + 1 < lignes.size) {
                val a = lignes[i]; val b = lignes[i + 1]
                m += Ligne((a.tMs + b.tMs) / 2, FloatArray(bins) { (a.db[it] + b.db[it]) / 2f })
                i += 2
            }
            if (i < lignes.size) m += lignes[i]
            lignes.clear(); lignes.addAll(m)
            periodeMs *= 2
        }
    }

    /**
     * [sp]'s bins from [centreBin] − [demiBins] to [centreBin] + [demiBins]
     * as [cols] columns, linear interpolation between bins; null when that
     * window is not wholly inside.
     */
    fun fenetre(sp: FloatArray, centreBin: Double, demiBins: Double, cols: Int): FloatArray? {
        val a = centreBin - demiBins; val b = centreBin + demiBins
        if (a < 0 || b > sp.size - 1 || demiBins <= 0) return null
        return FloatArray(cols) { i ->
            val x = a + (b - a) * (i + 0.5) / cols
            val j = floor(x).toInt().coerceIn(0, sp.size - 2)
            val f = (x - j).toFloat()
            sp[j] * (1 - f) + sp[j + 1] * f
        }
    }

    // ------------------------------------------------------------ the file

    /** A pass's file name: its satellite number and first moment. */
    fun nom(catnum: Int, debutMs: Long) = "${catnum}_$debutMs$EXT"

    /** The files of [dossier] covering [deMs]..[aMs] (by their name's time and their satellite). */
    fun duPassage(dossier: File, catnum: Int, deMs: Long, aMs: Long): List<File> =
        (dossier.listFiles { f -> f.name.endsWith(EXT) } ?: emptyArray()).filter { f ->
            val p = f.name.removeSuffix(EXT).split('_')
            p.size == 2 && p[0].toIntOrNull() == catnum && (p[1].toLongOrNull() ?: Long.MAX_VALUE) <= aMs && f.lastModified() >= deMs - 60_000L
        }.sortedBy { it.name }

    /** The lines, 0.5 dB a step from each line's lowest (a 127 dB span). */
    fun ecrit(b: Bande, o: OutputStream) {
        val d = DataOutputStream(o)
        d.writeInt(MAGIC); d.writeByte(1)
        d.writeInt(b.catnum); d.writeUTF(b.satellite.take(60)); d.writeDouble(b.largeurHz); d.writeUTF(b.source)
        val bins = b.lignes.firstOrNull()?.db?.size ?: 0
        d.writeInt(bins); d.writeInt(b.lignes.size)
        for (l in b.lignes) {
            d.writeLong(l.tMs)
            val bas = l.db.filter { it.isFinite() }.minOrNull() ?: 0f
            d.writeFloat(bas)
            for (v in l.db) d.writeByte(if (v.isFinite()) ((v - bas) * 2).roundToInt().coerceIn(0, 255) else 0)
        }
        d.flush()
    }

    fun lit(i: InputStream): Bande {
        val d = DataInputStream(i)
        require(d.readInt() == MAGIC) { "pas un spectre de passage" }
        require(d.readUnsignedByte() == 1) { "version inconnue" }
        val catnum = d.readInt(); val sat = d.readUTF(); val larg = d.readDouble(); val src = d.readUTF()
        val bins = d.readInt(); val n = d.readInt()
        require(bins in 0..8192 && n in 0..1_000_000)
        val l = ArrayList<Ligne>(n)
        repeat(n) {
            val t = d.readLong(); val bas = d.readFloat()
            l += Ligne(t, FloatArray(bins) { bas + d.readUnsignedByte() / 2f })
        }
        return Bande(catnum, sat, larg, l, src)
    }

    // ------------------------------------------------------------ what the lines say

    /** Per line: the signal (the strongest of the middle [partMilieu] of the columns) above the line's noise (its median), dB. */
    fun rapports(b: Bande, partMilieu: Double = 0.5): List<Pair<Long, Double>> = b.lignes.map { l ->
        val n = l.db.size
        val a = ((1 - partMilieu) / 2 * n).toInt(); val z = max(a + 1, n - a)
        val pic = (a until z).maxOf { l.db[it].toDouble() }
        val med = l.db.sorted().let { it[it.size / 2].toDouble() }
        l.tMs to (pic - med)
    }

    /**
     * The waterfall as an RGB picture, time left to right (one column per
     * line, stretched to [largeur] when given), frequency bottom to top;
     * the colours from the noise (10th percentile) to 3 dB under the peak.
     */
    fun image(b: Bande, largeur: Int = b.lignes.size): Png.Rgb {
        val l = b.lignes
        val h = l.firstOrNull()?.db?.size ?: 1
        val w = max(1, largeur)
        val tous = l.flatMap { x -> x.db.filter { it.isFinite() } }.sorted()
        val bas = tous.getOrNull(tous.size / 10)?.toDouble() ?: -100.0
        val haut = max(bas + 15.0, (tous.getOrNull((tous.size * 0.999).toInt().coerceAtMost(tous.size - 1)) ?: 0f) - 3.0)
        val img = Png.Rgb(w, h)
        if (l.isEmpty()) return img
        val t0 = l.first().tMs; val t1 = max(t0 + 1, l.last().tMs)
        for (x in 0 until w) {
            // The line nearest this column's time (a column per line when not stretched).
            val k = if (w == l.size) x else {
                val t = t0 + (t1 - t0) * x / max(1, w - 1)
                var lo = 0; var hi = l.size - 1
                while (lo < hi) { val m = (lo + hi) / 2; if (l[m].tMs < t) lo = m + 1 else hi = m }
                if (lo > 0 && t - l[lo - 1].tMs < l[lo].tMs - t) lo - 1 else lo
            }
            val col = l[k].db
            for (y in 0 until h) img.pose(x, h - 1 - y, palette(((col[y] - bas) / (haut - bas)).toFloat()))
        }
        return img
    }

    /** Dark blue → cyan → yellow → white. */
    fun palette(v0: Float): Int {
        val v = min(1f, max(0f, v0))
        val r: Float; val g: Float; val bl: Float
        when {
            v < 0.35f -> { val f = v / 0.35f; r = 0.02f; g = 0.05f + 0.45f * f; bl = 0.18f + 0.6f * f }
            v < 0.7f -> { val f = (v - 0.35f) / 0.35f; r = 0.02f + 0.95f * f; g = 0.5f + 0.4f * f; bl = 0.78f - 0.65f * f }
            else -> { val f = (v - 0.7f) / 0.3f; r = 0.97f + 0.03f * f; g = 0.9f + 0.1f * f; bl = 0.13f + 0.87f * f }
        }
        return ((r * 255).roundToInt() shl 16) or ((g * 255).roundToInt() shl 8) or (bl * 255).roundToInt()
    }
}

/** A plain PNG writer (8-bit RGB, no filter): enough for the reports' small pictures, and usable on the phone. */
object Png {
    class Rgb(val largeur: Int, val hauteur: Int) {
        val px = IntArray(largeur * hauteur)
        fun pose(x: Int, y: Int, rgb: Int) { if (x in 0 until largeur && y in 0 until hauteur) px[y * largeur + x] = rgb }
    }

    fun encode(img: Rgb): ByteArray {
        val brut = java.io.ByteArrayOutputStream(img.px.size * 3 + img.hauteur)
        for (y in 0 until img.hauteur) {
            brut.write(0)
            for (x in 0 until img.largeur) { val c = img.px[y * img.largeur + x]; brut.write(c shr 16 and 255); brut.write(c shr 8 and 255); brut.write(c and 255) }
        }
        val z = java.io.ByteArrayOutputStream()
        java.util.zip.DeflaterOutputStream(z, java.util.zip.Deflater(6)).use { it.write(brut.toByteArray()) }
        val o = java.io.ByteArrayOutputStream()
        o.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        val ihdr = java.nio.ByteBuffer.allocate(13).putInt(img.largeur).putInt(img.hauteur).put(8).put(2).put(0).put(0).put(0).array()
        morceau(o, "IHDR", ihdr); morceau(o, "IDAT", z.toByteArray()); morceau(o, "IEND", ByteArray(0))
        return o.toByteArray()
    }

    private fun morceau(o: java.io.ByteArrayOutputStream, type: String, d: ByteArray) {
        val t = type.toByteArray(Charsets.US_ASCII)
        o.write(java.nio.ByteBuffer.allocate(4).putInt(d.size).array())
        o.write(t); o.write(d)
        val c = java.util.zip.CRC32(); c.update(t); c.update(d)
        o.write(java.nio.ByteBuffer.allocate(4).putInt(c.value.toInt()).array())
    }
}
