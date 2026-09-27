/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.audio

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The spoken header of a pass recording: satellite, date and UTC time,
 * locator, read by the phone's speech synthesis before the pass audio.
 *
 * A file found months later on a card or sent to a friend says what it is
 * from the first seconds, like a station announcing itself — without its
 * name or a sidecar file.
 *
 * Letters are spelt: "SO-50" as "S O 50", the locator in the phonetic
 * alphabet ("Juliett November 1 8…"), as on the air. Read as words, a
 * synthesiser says "so fifty" and turns a locator into mush.
 */
object AnnonceVocale {

    private val PHONETIQUE = mapOf(
        'A' to "Alfa", 'B' to "Bravo", 'C' to "Charlie", 'D' to "Delta", 'E' to "Echo",
        'F' to "Foxtrot", 'G' to "Golf", 'H' to "Hotel", 'I' to "India", 'J' to "Juliett",
        'K' to "Kilo", 'L' to "Lima", 'M' to "Mike", 'N' to "November", 'O' to "Oscar",
        'P' to "Papa", 'Q' to "Quebec", 'R' to "Romeo", 'S' to "Sierra", 'T' to "Tango",
        'U' to "Uniform", 'V' to "Victor", 'W' to "Whiskey", 'X' to "X-ray",
        'Y' to "Yankee", 'Z' to "Zulu")

    /** "JN18FT" → "Juliett November 1 8 Foxtrot Tango". */
    fun locatorEpele(loc: String): String =
        loc.trim().uppercase().mapNotNull { c ->
            when {
                c.isLetter() -> PHONETIQUE[c]
                c.isDigit() -> c.toString()
                else -> null
            }
        }.joinToString(" ")

    /**
     * "SO-50" → "S O 50", "ISS" → "I S S", "CAS-2T" → "C A S 2 T". Short
     * all-capital groups are designators, spelt; digits stay numbers; longer
     * words ("FUNCUBE") are left to the synthesiser.
     */
    fun nomEpele(nom: String): String =
        nom.split(Regex("[-_ ()]+")).filter { it.isNotBlank() }.joinToString(" ") { mot ->
            Regex("[A-Za-z]+|\\d+").findAll(mot).joinToString(" ") { g ->
                val s = g.value
                if (s[0].isLetter() && s.length <= 4 && s == s.uppercase()) s.toList().joinToString(" ")
                else s
            }
        }

    /** The whole sentence, in the app's language. */
    fun texte(sat: String, debutMs: Long, locator: String, fr: Boolean): String {
        val cal = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = debutMs }
        val jour = cal.get(java.util.Calendar.DAY_OF_MONTH)
        val mois = java.text.SimpleDateFormat("MMMM", if (fr) Locale.FRENCH else Locale.ENGLISH)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(java.util.Date(debutMs))
        val an = cal.get(java.util.Calendar.YEAR)
        val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val m = cal.get(java.util.Calendar.MINUTE)
        val loc = locatorEpele(locator)
        return if (fr) buildString {
            append("Enregistrement SatMe. Satellite ${nomEpele(sat)}. ")
            append("Le $jour $mois $an, $h heures $m UTC.")
            if (loc.isNotEmpty()) append(" Locator $loc.")
        } else buildString {
            append("SatMe recording. Satellite ${nomEpele(sat)}. ")
            append("$mois $jour, $an, ${"%02d".format(h)} ${"%02d".format(m)} UTC.")
            if (loc.isNotEmpty()) append(" Locator $loc.")
        }
    }

    /**
     * 16-bit PCM from a WAV file, mixed to mono, with its sample rate; null
     * for anything else. The synthesiser writes WAV at its own rate (16, 22.05
     * or 24 kHz depending on the engine).
     */
    fun wavVersPcm(b: ByteArray): Pair<ShortArray, Int>? {
        if (b.size < 44 || String(b, 0, 4, Charsets.US_ASCII) != "RIFF" ||
            String(b, 8, 4, Charsets.US_ASCII) != "WAVE") return null
        fun le16(i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
        fun le32(i: Int) = le16(i) or (le16(i + 2) shl 16)
        var i = 12
        var canaux = 0; var taux = 0; var bits = 0; var format = 0
        while (i + 8 <= b.size) {
            val id = String(b, i, 4, Charsets.US_ASCII)
            val lg = le32(i + 4)
            val corps = i + 8
            if (id == "fmt " && corps + 16 <= b.size) {
                format = le16(corps); canaux = le16(corps + 2); taux = le32(corps + 4); bits = le16(corps + 14)
            } else if (id == "data") {
                if (format != 1 || bits != 16 || canaux < 1 || taux <= 0) return null
                // Some engines leave the data length at 0 or too large while
                // streaming: take what the file really holds.
                val fin = if (lg <= 0 || corps + lg > b.size) b.size else corps + lg
                val trames = (fin - corps) / (2 * canaux)
                val out = ShortArray(trames)
                for (t in 0 until trames) {
                    var somme = 0
                    for (c in 0 until canaux) {
                        val k = corps + 2 * (t * canaux + c)
                        somme += le16(k).toShort().toInt()
                    }
                    out[t] = (somme / canaux).toShort()
                }
                return out to taux
            }
            i = corps + lg + (lg and 1)
        }
        return null
    }

    /** Linear resampling: enough for a voice, and no filter bank to maintain. */
    fun reechantillonne(pcm: ShortArray, de: Int, vers: Int): ShortArray {
        if (de == vers || pcm.isEmpty()) return pcm
        val n = (pcm.size.toLong() * vers / de).toInt()
        return ShortArray(n) { k ->
            val x = k.toDouble() * de / vers
            val i = x.toInt().coerceAtMost(pcm.size - 1)
            val j = (i + 1).coerceAtMost(pcm.size - 1)
            val f = x - i
            (pcm[i] * (1 - f) + pcm[j] * f).toInt().toShort()
        }
    }

    /**
     * Speaks [texte] into PCM at [taux], followed by half a second of
     * silence. Blocking: call it off the main thread. Null when the phone has
     * no speech engine, lacks the language, or takes too long — the recording
     * then simply starts without its header.
     */
    fun synthetise(ctx: Context, texte: String, locale: Locale, taux: Int): ShortArray? {
        val pret = CountDownLatch(1)
        var ok = false
        var tts: TextToSpeech? = null
        tts = TextToSpeech(ctx.applicationContext) { st -> ok = st == TextToSpeech.SUCCESS; pret.countDown() }
        try {
            if (!pret.await(5, TimeUnit.SECONDS) || !ok) return null
            val moteur = tts
            val langue = moteur.setLanguage(locale)
            if (langue == TextToSpeech.LANG_MISSING_DATA || langue == TextToSpeech.LANG_NOT_SUPPORTED) return null
            val fichier = File(ctx.cacheDir, "annonce_${System.nanoTime()}.wav")
            val fini = CountDownLatch(1)
            var reussi = false
            moteur.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { reussi = true; fini.countDown() }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) { fini.countDown() }
                override fun onError(id: String?, code: Int) { fini.countDown() }
            })
            if (moteur.synthesizeToFile(texte, Bundle(), fichier, "annonce") != TextToSpeech.SUCCESS) return null
            if (!fini.await(15, TimeUnit.SECONDS) || !reussi) { fichier.delete(); return null }
            val lu = runCatching { fichier.readBytes() }.getOrNull()
            fichier.delete()
            val (pcm, source) = lu?.let { wavVersPcm(it) } ?: return null
            if (pcm.isEmpty()) return null
            return reechantillonne(pcm, source, taux) + ShortArray(taux / 2)
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { tts.shutdown() }
        }
    }
}
