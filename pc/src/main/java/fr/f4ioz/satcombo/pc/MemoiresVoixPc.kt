/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The voice memories of the narrow band (a CQ call, one's callsign and
 * locator): a few short recordings made from the page's microphone, kept
 * as 16-bit mono at 48 kHz in [dossier]. Only sound files: playing one
 * goes through the PTT like the microphone, on the operator's click, with
 * every guard of the transmitter.
 */
class MemoiresVoixPc(private val dossier: File?) {
    data class Memoire(val n: Int, val nom: String, val dureeMs: Long)

    private fun fichier(n: Int) = dossier?.let { File(it, "voix-$n.pcm") }
    private fun fichierNom(n: Int) = dossier?.let { File(it, "voix-$n.txt") }

    /** The memories recorded, by number. */
    fun liste(): List<Memoire> = (1..NOMBRE).mapNotNull { n ->
        val f = fichier(n)?.takeIf { it.isFile && it.length() > 0 } ?: return@mapNotNull null
        val nom = runCatching { fichierNom(n)?.readText()?.trim() }.getOrNull()?.ifBlank { null } ?: "Voix $n"
        Memoire(n, nom, f.length() / 2 * 1000 / DEBIT)
    }

    fun json(): JSONArray = JSONArray(liste().map { JSONObject().put("n", it.n).put("nom", it.nom).put("duree", it.dureeMs) })

    /**
     * Keeps [octets] (16-bit little-endian mono at [taux]) as memory [n]
     * named [nom]; null when kept, else why not.
     */
    fun enregistre(n: Int, nom: String, octets: ByteArray, taux: Int): String? {
        val d = dossier ?: return "pas de dossier pour les mémoires vocales"
        if (n !in 1..NOMBRE) return "mémoire 1 à $NOMBRE"
        if (taux !in 8_000..96_000) return "cadence du son inattendue ($taux Hz)"
        val brut = ShortArray(octets.size / 2).also { ByteBuffer.wrap(octets).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it) }
        if (brut.size < taux / 4) return "enregistrement trop court"
        val pcm = normalise(reechantillonne(brut, taux, DEBIT))
        if (pcm.size > DEBIT * DUREE_MAX_S) return "trop long : $DUREE_MAX_S s au plus"
        d.mkdirs()
        val b = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        b.asShortBuffer().put(pcm)
        val f = fichier(n)!!
        val tmp = File(d, f.name + ".tmp")
        tmp.writeBytes(b.array())
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        fichierNom(n)!!.writeText(nom.trim().take(24).ifBlank { "Voix $n" })
        return null
    }

    fun efface(n: Int) { fichier(n)?.delete(); fichierNom(n)?.delete() }

    /** Memory [n]'s samples at 48 kHz, or null. */
    fun lis(n: Int): ShortArray? {
        val f = fichier(n)?.takeIf { it.isFile } ?: return null
        val o = runCatching { f.readBytes() }.getOrNull() ?: return null
        return ShortArray(o.size / 2).also { ByteBuffer.wrap(o).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it) }
    }

    companion object {
        const val NOMBRE = 4
        const val DEBIT = 48_000
        const val DUREE_MAX_S = 30

        /** Linear interpolation to [vers] (a voice, not hi-fi). */
        fun reechantillonne(x: ShortArray, de: Int, vers: Int): ShortArray {
            if (de == vers || x.isEmpty()) return x
            val n = (x.size.toLong() * vers / de).toInt()
            return ShortArray(n) { i ->
                val p = i.toDouble() * de / vers
                val k = p.toInt().coerceAtMost(x.size - 1)
                val f = p - k
                val b = x[minOf(k + 1, x.size - 1)]
                (x[k] * (1 - f) + b * f).toInt().toShort()
            }
        }

        /** Brought to a peak of about −3 dBFS, never raised more than 20 dB (a recording of silence stays silent). */
        fun normalise(x: ShortArray): ShortArray {
            var c = 1
            for (v in x) { val a = kotlin.math.abs(v.toInt()); if (a > c) c = a }
            val g = minOf(0.7 * 32767 / c, 10.0)
            return ShortArray(x.size) { (x[it] * g).toInt().coerceIn(-32767, 32767).toShort() }
        }
    }
}

/** A voice memory as a transmitter's audio source: its samples once, then silence; [fini] after the last. */
class LectureVoix(private val pcm: ShortArray) : (ShortArray) -> Int {
    @Volatile private var i = 0
    val fini: Boolean get() = i >= pcm.size
    /** Played so far (0 to 1). */
    val avance: Double get() = if (pcm.isEmpty()) 1.0 else i.toDouble() / pcm.size

    override fun invoke(dst: ShortArray): Int {
        val k = minOf(dst.size, pcm.size - i).coerceAtLeast(0)
        if (k > 0) System.arraycopy(pcm, i, dst, 0, k)
        if (k < dst.size) dst.fill(0, k, dst.size)
        i += k
        return dst.size
    }
}
