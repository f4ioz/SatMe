/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.phonie

import kotlin.math.abs
import kotlin.math.ln

/**
 * « Traduction parlée »: what is said, in which voice, in what order. The
 * synthetic voice is for the listener only: it is never transmitted.
 */
object ParoleTraduite {

    /** "neutre": the voice as it is; "proche": [IntonationProche]; "clonee": its timbre turned into the speaker's, then « proche ». */
    val INTONATIONS = linkedMapOf("neutre" to "neutre", "proche" to "proche", "clonee" to "voix clonée")

    /** A synthetic voice offered for download (its data's licence checked: CC0, public domain, CC-BY, CC-BY-SA). */
    data class Voix(val id: String, val langue: String, val nom: String, val masculine: Boolean, val mo: Int,
                    val licence: String, val locuteur: String = "", val fichier: String = id) {
        /** Hz: what the voice speaks around, to pick the one nearest a speaker. */
        val f0Hz: Double get() = if (masculine) 115.0 else 210.0
    }

    /** Piper's voices (rhasspy/piper-voices v1.0.0), per language; « fichier »: the model shared by two speakers. */
    val VOIX = listOf(
        Voix("fr_FR-siwis-medium", "fr", "Siwis (femme)", false, 63, "CC-BY 4.0"),
        Voix("fr_FR-gilles-low", "fr", "Gilles (homme)", true, 63, "CC0"),
        Voix("fr_FR-upmc-medium:pierre", "fr", "Pierre (homme)", true, 76, "CC-BY-SA 4.0", "pierre", "fr_FR-upmc-medium"),
        Voix("fr_FR-upmc-medium:jessica", "fr", "Jessica (femme)", false, 76, "CC-BY-SA 4.0", "jessica", "fr_FR-upmc-medium"),
        Voix("en_US-john-medium", "en", "John (homme)", true, 63, "domaine public"),
        Voix("en_US-ljspeech-medium", "en", "LJ (femme)", false, 63, "domaine public"),
        Voix("de_DE-thorsten-medium", "de", "Thorsten (homme)", true, 63, "CC0"),
        Voix("de_DE-kerstin-low", "de", "Kerstin (femme)", false, 63, "CC0"),
        Voix("es_ES-davefx-medium", "es", "Davefx (homme)", true, 63, "CC0"),
        Voix("es_ES-sharvard-medium:F", "es", "Sharvard (femme)", false, 76, "CC-BY 3.0", "F", "es_ES-sharvard-medium"),
        Voix("nl_NL-ronnie-medium", "nl", "Ronnie (homme)", true, 62, "CC0"),
        Voix("nl_BE-nathalie-medium", "nl", "Nathalie (femme)", false, 63, "CC0"),
        Voix("pl_PL-darkman-medium", "pl", "Darkman (homme)", true, 63, "CC0"),
        Voix("pl_PL-gosia-medium", "pl", "Gosia (femme)", false, 63, "CC0"),
        Voix("pt_PT-tugão-medium", "pt", "Tugão (homme)", true, 63, "CC0"),
    )

    fun voix(id: String): Voix? = VOIX.firstOrNull { it.id == id }

    /** The voices for [langue] whose model is there ([fichiers]: the models present, by file name). */
    fun disponibles(langue: String, fichiers: Collection<String>): List<Voix> = VOIX.filter { it.langue == langue && it.fichier in fichiers }

    /**
     * The voice to say it: the one chosen when it is there for this language,
     * else (« auto ») the one whose usual pitch is nearest the speaker's.
     */
    fun choisit(choix: String, langue: String, fichiers: Collection<String>, f0Hz: Double): Voix? {
        val l = disponibles(langue, fichiers)
        l.firstOrNull { it.id == choix }?.let { return it }
        if (l.isEmpty()) return null
        if (f0Hz <= 0) return l.first()
        return l.minByOrNull { abs(ln(it.f0Hz / f0Hz)) }
    }

    /** Whether an over is said: something to say, and (when asked) only a language other than the one spoken here. */
    fun aDire(langueEntendue: String, cible: String, traduction: String, texte: String, seulementEtrangere: Boolean): String? {
        val etrangere = langueEntendue.isNotBlank() && langueEntendue != cible
        return when {
            traduction.isNotBlank() && etrangere -> traduction
            seulementEtrangere -> null
            texte.isNotBlank() && !etrangere -> texte
            else -> null
        }
    }

    /**
     * The overs waiting to be said: [max] at most (the oldest goes), and one
     * heard more than [perimeMs] ago is not said any more. One added
     * [seule] (the DATV's: only the latest is worth saying) puts away those
     * of its [genre] still waiting; each may have its own limit of age.
     */
    class File<T>(private val max: Int = 2, private val perimeMs: Long = 30_000L, private val horloge: () -> Long = System::currentTimeMillis) {
        private class Element<T>(val x: T, val entenduMs: Long, val perimeMs: Long, val genre: String)
        private val file = ArrayDeque<Element<T>>()
        /** Dropped because the queue was full (or a later one of the same kind came). */
        @Volatile var sautees = 0L; private set
        /** Dropped because too old. */
        @Volatile var perimees = 0L; private set

        @Synchronized fun ajoute(x: T, entenduMs: Long, seule: Boolean = false, perime: Long = perimeMs, genre: String = "") {
            if (seule) { val avant = file.size; file.removeAll { it.genre == genre }; sautees += avant - file.size }
            file.addLast(Element(x, entenduMs, perime, genre))
            while (file.size > max) { file.removeFirst(); sautees++ }
            (this as Object).notifyAll()
        }

        /** The next one still fresh, null when none. */
        @Synchronized fun prochaine(): T? {
            while (file.isNotEmpty()) {
                val e = file.removeFirst()
                if (horloge() - e.entenduMs > e.perimeMs) { perimees++; continue }
                return e.x
            }
            return null
        }

        /** [prochaine], waiting at most [ms] for one. */
        @Synchronized fun attend(ms: Long): T? {
            val fin = System.currentTimeMillis() + ms
            while (file.isEmpty()) {
                val reste = fin - System.currentTimeMillis()
                if (reste <= 0) return null
                (this as Object).wait(reste)
            }
            return prochaine()
        }

        /** Whether one heard at [entenduMs] is still worth saying (within [perime]). */
        fun frais(entenduMs: Long, perime: Long = perimeMs): Boolean = horloge() - entenduMs <= perime

        @Synchronized fun taille(): Int = file.size
        @Synchronized fun vide() { file.clear() }
    }

    /**
     * The received sound lowered while the voice speaks (« ducking »): its
     * gain glides to the level asked ([cible], 0…1) at most [rampeMs] from
     * full to nothing, so it neither clicks nor jumps; [applique] scales a
     * block of interleaved frames.
     */
    class Baisse(private val rampeMs: Double = 150.0) {
        /** The gain reached at the end of the last block. */
        @Volatile var gain = 1.0; private set

        fun applique(pcm: ShortArray, n: Int, canaux: Int, rate: Int, cible: Double) {
            val c = cible.coerceIn(0.0, 1.0)
            if (gain == 1.0 && c == 1.0) return
            val pas = 1.0 / (rampeMs / 1000.0 * rate).coerceAtLeast(1.0)
            var g = gain
            val trames = n / canaux.coerceAtLeast(1)
            for (i in 0 until trames) {
                g = if (g < c) minOf(c, g + pas) else maxOf(c, g - pas)
                for (k in 0 until canaux) { val j = i * canaux + k; pcm[j] = (pcm[j] * g).toInt().coerceIn(-32768, 32767).toShort() }
            }
            gain = g
        }
    }

    /** « baisser le son d'origine de [pct] % » as a gain (0 % → 1, 100 % → 0). */
    fun gainBaisse(pct: Int): Double = 1.0 - pct.coerceIn(0, 100) / 100.0

    /** A sound as a WAV file (16-bit mono). */
    fun wav(pcm: ShortArray, rate: Int): ByteArray {
        val n = pcm.size * 2
        val b = java.nio.ByteBuffer.allocate(44 + n).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + n).put("WAVE".toByteArray()).put("fmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16).put("data".toByteArray()).putInt(n)
        for (s in pcm) b.putShort(s)
        return b.array()
    }

    /** [x] (−1…1) to 16 bits, its peak at [crete] of full scale. */
    fun enShorts(x: FloatArray, crete: Float = 0.85f): ShortArray {
        val m = x.maxOfOrNull { abs(it) } ?: 0f
        val g = if (m > 1e-6f) crete / m else 1f
        return ShortArray(x.size) { (x[it] * g * 32767f).toInt().coerceIn(-32768, 32767).toShort() }
    }
}
