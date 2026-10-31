/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.audio

import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

/**
 * Did a recording keep all its sound? Its audio's length against the time it
 * lasted on the wall clock: equal, nothing was lost; shorter, the capture
 * lost blocks (an overloaded PC) and what came after them is early.
 *
 * The wall clock's time comes from the recorder's own sidecar ("<name>.temps":
 * its start and stop, the samples written, the silence put in the holes)
 * when there is one; else from the start in the file's name and its last
 * write (a second or two too long: the header is rewritten every second).
 */
object BilanEnregistrement {

    /** What the recorder writes next to the WAV, key=value per line. */
    data class Temps(
        /** Start and stop (UTC ms; fin 0: under way or cut off by a crash). */
        val debutMs: Long, val finMs: Long,
        /** Samples written (at the file's rate), silence included. */
        val echantillons: Long, val rate: Int,
        /** Holes found and the silence put in them (ms); cuts too long for the decoders, filled in the file all the same (ms). */
        val trous: Int = 0, val combleMs: Long = 0L, val coupeMs: Long = 0L,
    ) {
        fun encode(): String = "debut=$debutMs\nfin=$finMs\nechantillons=$echantillons\nrate=$rate\ntrous=$trous\ncomble=$combleMs\ncoupe=$coupeMs\n"

        companion object {
            fun decode(s: String): Temps? {
                val m = s.lines().mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it).trim() to l.substring(it + 1).trim() } }.toMap()
                fun l(k: String) = m[k]?.toLongOrNull()
                val debut = l("debut") ?: return null
                return Temps(debut, l("fin") ?: 0L, l("echantillons") ?: 0L, (l("rate") ?: 0L).toInt(),
                    (l("trous") ?: 0L).toInt(), l("comble") ?: 0L, l("coupe") ?: 0L)
            }
        }
    }

    /** The sidecar of [son] ("SatMe_X_….wav" → "SatMe_X_….temps"). */
    fun fichierTemps(son: File): File = File(son.parentFile, son.name.substringBeforeLast('.') + ".temps")

    /**
     * The verdict: [sonMs] of audio for [murMs] of wall time; [perduMs] missing
     * from the file (>0: shorter than it lasted), [combleMs] lost but filled
     * with silence by the recorder; [parTemps]: measured by the recorder's
     * sidecar (else from the name and the last write).
     */
    data class Bilan(val sonMs: Long, val murMs: Long, val perduMs: Long, val combleMs: Long, val trous: Int, val parTemps: Boolean) {
        /** Lost (missing or filled) against the time it lasted, %. */
        val pourcent: Double get() = if (murMs <= 0) 0.0 else 100.0 * (perduMs.coerceAtLeast(0L) + combleMs) / murMs
        /** More than the usual: a second-rounding (no sidecar) never counts. */
        val perte: Boolean get() = (perduMs.coerceAtLeast(0L) + combleMs) > (if (parTemps) 200L else 2_500L) && pourcent >= SEUIL_POURCENT

        /** « 547,8 s de son pour 566,5 s : 18,7 s perdus (3,3 %) », « complet (708,6 s) ». */
        fun texte(): String {
            val fr = Locale.FRANCE
            fun s(ms: Long) = String.format(fr, "%.1f s", ms / 1000.0)
            if (!perte) return "complet (" + s(sonMs) + (if (parTemps) "" else " ; pour " + s(murMs)) + ")"
            val manque = perduMs.coerceAtLeast(0L)
            val parts = buildList {
                if (manque > 0) add(s(manque) + " manquent")
                if (combleMs > 0) add(s(combleMs) + " comblés de silence" + if (trous > 0) " ($trous trou" + (if (trous > 1) "s" else "") + ")" else "")
            }
            return s(sonMs) + " de son pour " + s(murMs) + " : son perdu " + parts.joinToString(", ") + String.format(fr, " (%.1f %%)", pourcent)
        }
    }

    /** Under this share of the time, a recording is whole (the capture's own start and stop take a little). */
    const val SEUIL_POURCENT = 1.0

    /** A WAV's rate and its audio's length (ms) from its header and size; null when not a WAV this recorder writes. */
    fun dureeWav(f: File): Pair<Int, Long>? = runCatching {
        RandomAccessFile(f, "r").use { r ->
            if (r.length() < 44) return null
            val h = ByteArray(44); r.readFully(h)
            if (String(h, 0, 4, Charsets.US_ASCII) != "RIFF" || String(h, 8, 4, Charsets.US_ASCII) != "WAVE") return null
            val b = java.nio.ByteBuffer.wrap(h).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val canaux = b.getShort(22).toInt().coerceAtLeast(1)
            val rate = b.getInt(24)
            val bits = b.getShort(34).toInt().coerceAtLeast(8)
            if (rate <= 0) return null
            // The size on disk, not the header's: a recording under way rewrites it only every second.
            val echantillons = (r.length() - 44) / (canaux * bits / 8)
            rate to echantillons * 1000L / rate
        }
    }.getOrNull()

    /**
     * [son]'s verdict; null when it cannot be told (not a WAV, no time to
     * compare with). [debutNomMs]: the start in its name (0: none).
     */
    fun bilan(son: File, debutNomMs: Long): Bilan? {
        val (_, sonMs) = dureeWav(son) ?: return null
        val t = runCatching { fichierTemps(son).takeIf { it.isFile }?.readText()?.let { Temps.decode(it) } }.getOrNull()
        if (t != null && t.finMs > t.debutMs) {
            val mur = t.finMs - t.debutMs
            // The cuts too are silence in the file (the recorder keeps its time).
            return Bilan(sonMs, mur, mur - sonMs, t.combleMs + t.coupeMs, t.trous, true)
        }
        val debut = t?.debutMs ?: debutNomMs
        if (debut <= 0L) return null
        val mur = son.lastModified() - debut
        if (mur <= 0L) return null
        return Bilan(sonMs, mur, mur - sonMs, t?.combleMs ?: 0L, t?.trous ?: 0, false)
    }
}
