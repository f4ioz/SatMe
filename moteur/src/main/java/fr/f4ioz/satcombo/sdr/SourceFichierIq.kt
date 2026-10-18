/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sdr

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.floor

/**
 * « Fichier I/Q »: a recording played back as if a receiver gave it — at
 * its own pace (or two, four times faster), paused, looped, sought — so the
 * whole reception (spectrum, demodulation, every decoder) works on it.
 *
 * Gives 16-bit pairs at [Dsp.RTL_RATE] whatever the file: another rate is
 * brought to it by linear interpolation. Tuning asks nothing: the frequency
 * is the file's ([capture]). A file only: nothing is ever transmitted.
 */
class SourceFichierIq(
    val donnees: File,
    val meta: SigMF.Meta,
    private val horloge: () -> Long = System::currentTimeMillis,
    /** At the file's pace (false: as fast as asked, for tests). */
    private val cadence: Boolean = true,
    private val dort: (Long) -> Unit = { Thread.sleep(it) },
) : SourceIq {

    private val format = meta.format
    private val bpp = format.octetsParPaire
    private val raf = RandomAccessFile(donnees, "r")
    val paires: Long = raf.length() / bpp
    val debit: Double = meta.debit
    val dureeS: Double get() = paires / debit
    private val pas = debit / Dsp.RTL_RATE

    override val iq16: Boolean get() = true
    @Volatile override var octets = 0L; private set

    /** ×1, ×2, ×4… */
    @Volatile var vitesse = 1.0
        set(v) { field = v.coerceIn(0.25, 16.0); recaleVoulu = true }
    @Volatile var enPause = false
        set(v) { field = v; recaleVoulu = true }
    @Volatile var boucle = false
    /** Started again so that the file's second within 15 s is the clock's (FT8 slots), at ×1. */
    @Volatile var caleTranches = false
    @Volatile var fini = false; private set
    @Volatile private var ferme = false

    /** Where in the file (input pairs, fractional). */
    @Volatile private var pos = 0.0
    val positionS: Double get() = pos / debit
    @Volatile private var saut = -1.0
    @Volatile private var recaleVoulu = true
    /** Waiting for the slots to line up: until then (ms), nothing given. */
    @Volatile var attenteJusqua = 0L; private set

    /** Goes to [secondes] from the start. */
    fun cherche(secondes: Double) { saut = secondes.coerceIn(0.0, dureeS) }

    /** The capture under way (sky frequency, PLL, the Doppler's software share). */
    fun capture(): SigMF.Capture = meta.captureA(floor(pos).toLong()) ?: SigMF.Capture(0, 0)

    /** UTC of the sample being played (ms), when the file says when it was recorded. */
    fun momentMs(): Long? = meta.debutMs?.let { it + Math.round(positionS * 1000) }

    // Pacing: [livrees] pairs given since [t0].
    private var t0 = 0L
    private var livrees = 0L

    private fun recale() {
        t0 = horloge(); livrees = 0; recaleVoulu = false
        val d = meta.debutMs
        attenteJusqua = if (caleTranches && cadence && vitesse == 1.0 && d != null) {
            val fichierMs = d + Math.round(positionS * 1000)
            t0 + Math.floorMod(fichierMs - t0, TRANCHE_MS)
        } else 0L
    }

    // A window of the file, in its own type.
    private val fen = ByteArray(FENETRE * bpp)
    private var fenDebut = -1L
    private var fenN = 0

    private fun charge(i: Long) {
        fenDebut = i
        val n = minOf(FENETRE.toLong(), paires - i).toInt()
        raf.seek(i * bpp)
        raf.readFully(fen, 0, n * bpp)
        fenN = n
        octets += n.toLong() * bpp
    }

    private fun i(k: Long): Float = valeur(k, 0)
    private fun q(k: Long): Float = valeur(k, 1)

    private fun valeur(k: Long, c: Int): Float {
        if (k < fenDebut || k >= fenDebut + fenN) charge(k)
        val o = ((k - fenDebut) * bpp).toInt()
        return when (format) {
            SigMF.Format.CU8 -> ((fen[o + c].toInt() and 0xFF) - 127.5f) * 256f
            SigMF.Format.CI8 -> fen[o + c] * 256f
            SigMF.Format.CI16 -> ((fen[o + 2 * c].toInt() and 0xFF) or (fen[o + 2 * c + 1].toInt() shl 8)).toShort().toFloat()
            SigMF.Format.CF32 -> {
                val b = o + 4 * c
                val bits = (fen[b].toInt() and 0xFF) or ((fen[b + 1].toInt() and 0xFF) shl 8) or
                    ((fen[b + 2].toInt() and 0xFF) shl 16) or (fen[b + 3].toInt() shl 24)
                Float.fromBits(bits) * 32767f
            }
        }
    }

    override fun read16(buf: ShortArray, timeoutMs: Int): Int {
        if (ferme) return -1
        if (saut >= 0) { pos = saut * debit; saut = -1.0; fini = false; recaleVoulu = true }
        if (fini) {
            if (!boucle) return -1
            pos = 0.0; fini = false; recaleVoulu = true
        }
        if (recaleVoulu) recale()
        if (enPause) { if (cadence) dort(50); recaleVoulu = true; return 0 }
        val maintenant = horloge()
        if (attenteJusqua > maintenant) { dort(minOf(100L, attenteJusqua - maintenant)); return 0 }
        if (attenteJusqua != 0L) { attenteJusqua = 0L; t0 = maintenant; livrees = 0 }
        val n = buf.size / 2
        if (cadence) {
            // A receiver hands a block once it is in: at [t0] + its end's time.
            val du = t0 + Math.round((livrees + n) * 1000.0 / (Dsp.RTL_RATE * vitesse))
            val d = du - horloge()
            if (d > 1000) { /* clock gone back */ recale() }
            else if (d > 0) dort(d)
            else if (d < -2000) { t0 = horloge(); livrees = 0 }
        }
        var o = 0
        var k = 0
        while (k < n) {
            val ip = floor(pos).toLong()
            if (ip >= paires) { fini = true; break }
            val fr = (pos - ip).toFloat()
            var vi = i(ip); var vq = q(ip)
            if (fr > 0f && ip + 1 < paires) { vi += (i(ip + 1) - vi) * fr; vq += (q(ip + 1) - vq) * fr }
            buf[o++] = vi.toInt().coerceIn(-32767, 32767).toShort()
            buf[o++] = vq.toInt().coerceIn(-32767, 32767).toShort()
            pos += pas
            k++
        }
        livrees += k
        if (k == 0) return if (fini && !boucle) -1 else 0
        return o
    }

    override fun read(buf: ByteArray, timeoutMs: Int): Int = throw UnsupportedOperationException()
    override fun setSampleRate(rate: Int): Double = Dsp.RTL_RATE.toDouble()
    override fun setFreqCorrection(ppm: Int) {}
    override fun setCenterFreq(hz: Long): Long = hz
    override fun setGain(tenthDb: Int?) {}
    override fun setAgc(on: Boolean) {}

    override fun close() {
        ferme = true
        synchronized(this) { runCatching { raf.close() } }
    }

    companion object {
        const val FENETRE = 65_536
        const val TRANCHE_MS = 15_000L

        /**
         * A recording opened: a SigMF file (its data or its meta named), or a
         * raw one (`.cu8`, `.cs8`, `.cs16`, `.cf32`) with [debitBrut] and
         * [frequenceBrute] given. Null with why not.
         */
        fun meta(f: File, debitBrut: Double = 0.0, frequenceBrute: Long = 0L): Pair<Pair<File, SigMF.Meta>?, String> {
            val nom = f.name
            if (nom.endsWith(SigMF.EXT_META) || nom.endsWith(SigMF.EXT_DATA)) {
                val m = if (nom.endsWith(SigMF.EXT_META)) f else SigMF.meta(f)
                val d = SigMF.data(m)
                if (!m.isFile) return null to "le fichier .sigmf-meta manque à côté des données"
                if (!d.isFile) return null to "le fichier .sigmf-data manque"
                val meta = SigMF.litMeta(m.readText()) ?: return null to "sigmf-meta illisible, ou un type d'échantillons inconnu"
                return (d to meta) to ""
            }
            val fmt = SigMF.Format.deExtension(nom) ?: return null to "type de fichier inconnu (.sigmf-data, .cu8, .cs8, .cs16, .cf32)"
            if (debitBrut <= 0) return null to "un fichier brut : donner son débit (échantillons par seconde)"
            return (f to SigMF.Meta(fmt, debitBrut, null, listOf(SigMF.Capture(0, frequenceBrute)))) to ""
        }
    }
}
