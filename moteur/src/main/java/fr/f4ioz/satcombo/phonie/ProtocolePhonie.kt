/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.phonie

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * What SatMe and the speech-to-text helper (satme_phonie.py, a long-lived
 * child process) say to each other.
 *
 * To it, on its standard input, one frame per piece of voice:
 * `[u32 BE: header length][header, JSON UTF-8][u32 BE: PCM length][PCM s16le, 16 kHz mono]`,
 * the header `{"id":7,"langue":"fr","tache":"transcribe","traduire":false,"rate":16000,"format":"s16le"}`
 * (an empty language: found by the engine).
 *
 * From it, one JSON object per line on its standard output: first
 * `{"pret":true,"modele":"large-v3","calcul":"cuda","type":"float16","charge_s":3.2}`
 * (or `{"erreur":"…","fatal":true}`), then one answer per frame:
 * `{"id":7,"langue":"pl","proba":0.98,"calcul_s":0.4,"segments":[{"debut":0.0,"fin":3.1,"texte":"…",
 * "avg_logprob":-0.3,"no_speech_prob":0.02,"compression":1.2}],"traduction":"…"}`, or `{"id":7,"erreur":"…"}`.
 */
object ProtocolePhonie {

    const val RATE = 16_000

    /** The frame for one piece: [pcm] at 16 kHz, −1…1. */
    fun trame(id: Long, pcm: FloatArray, langue: String = "", traduire: Boolean = false): ByteArray {
        val entete = JSONObject().put("id", id).put("langue", langue).put("tache", "transcribe")
            .put("traduire", traduire).put("rate", RATE).put("format", "s16le").toString().toByteArray(Charsets.UTF_8)
        val o = ByteArrayOutputStream(entete.size + pcm.size * 2 + 8)
        val d = DataOutputStream(o)
        d.writeInt(entete.size); d.write(entete)
        d.writeInt(pcm.size * 2)
        val b = ByteArray(pcm.size * 2)
        for (i in pcm.indices) {
            val s = (pcm[i] * 32767f).toInt().coerceIn(-32768, 32767)
            b[2 * i] = s.toByte(); b[2 * i + 1] = (s shr 8).toByte()
        }
        d.write(b)
        return o.toByteArray()
    }

    /** One line read back: its header, its PCM (for the fake engines of the tests). */
    fun litTrame(entree: java.io.DataInputStream): Pair<JSONObject, FloatArray>? {
        val h = runCatching { entree.readInt() }.getOrNull() ?: return null
        if (h !in 1..65_536) return null
        val e = ByteArray(h); entree.readFully(e)
        val p = entree.readInt()
        if (p < 0 || p > 64 * 1024 * 1024) return null
        val b = ByteArray(p); entree.readFully(b)
        val pcm = FloatArray(p / 2) { i -> ((b[2 * i].toInt() and 0xFF) or (b[2 * i + 1].toInt() shl 8)).toShort() / 32768f }
        return JSONObject(String(e, Charsets.UTF_8)) to pcm
    }

    /** A segment of an answer, as the engine heard it. */
    data class Morceau(val debutS: Double, val finS: Double, val texte: String,
                       val avgLogprob: Double, val noSpeechProb: Double, val compression: Double = 1.0)

    sealed class Message {
        data class Pret(val modele: String, val calcul: String, val type: String, val chargeS: Double, val repli: String) : Message()
        data class Reponse(val id: Long, val langue: String, val proba: Double, val calculS: Double,
                           val morceaux: List<Morceau>, val traduction: String) : Message() {
            val texte: String get() = morceaux.joinToString(" ") { it.texte.trim() }.trim()
        }
        data class Erreur(val id: Long?, val texte: String, val fatale: Boolean) : Message()
        data class Progres(val texte: String, val mo: Double) : Message()
        object Autre : Message()
    }

    /** One line from the engine; anything not understood is [Message.Autre]. */
    fun lit(ligne: String): Message {
        val j = runCatching { JSONObject(ligne.trim()) }.getOrNull() ?: return Message.Autre
        return when {
            j.has("erreur") -> Message.Erreur(if (j.has("id")) j.optLong("id") else null, j.optString("erreur"), j.optBoolean("fatal", false))
            j.optBoolean("pret", false) -> Message.Pret(j.optString("modele"), j.optString("calcul"), j.optString("type"),
                j.optDouble("charge_s", 0.0), j.optString("repli"))
            j.has("id") && j.has("segments") -> {
                val s = j.optJSONArray("segments") ?: JSONArray()
                Message.Reponse(j.optLong("id"), j.optString("langue"), j.optDouble("proba", 0.0), j.optDouble("calcul_s", 0.0),
                    (0 until s.length()).mapNotNull { s.optJSONObject(it) }.map {
                        Morceau(it.optDouble("debut", 0.0), it.optDouble("fin", 0.0), it.optString("texte"),
                            it.optDouble("avg_logprob", 0.0), it.optDouble("no_speech_prob", 0.0), it.optDouble("compression", 1.0))
                    }, j.optString("traduction"))
            }
            j.has("progres") || j.has("mo") -> Message.Progres(j.optString("progres"), j.optDouble("mo", 0.0))
            else -> Message.Autre
        }
    }
}

/**
 * A piece of voice understood, as the page shows it and the day's file
 * keeps it (one JSON line each).
 */
data class BullePhonie(
    val id: Long,
    /** When the voice began (epoch ms). */
    val t: Long,
    val dureeS: Double,
    /** "carte" (the sound card) or "sdr" (the dongle). */
    val source: String,
    /** The sky frequency heard, when known (QO-100's downlink, the dongle, the rig by CAT). */
    val freqHz: Long?,
    val satellite: String,
    val langue: String,
    val probaLangue: Double,
    val texte: String,
    val traduction: String,
    /** 0…1, from the engine's mean log-probability. */
    val confiance: Double,
    val calculS: Double,
    val elements: List<AnalysePhonie.Element>,
) {
    fun json(): JSONObject = JSONObject().put("id", id).put("t", t).put("duree", Math.round(dureeS * 10) / 10.0)
        .put("src", source).put("freq", freqHz ?: JSONObject.NULL).put("sat", satellite).put("langue", langue)
        .put("proba", Math.round(probaLangue * 100) / 100.0).put("texte", texte).put("traduction", traduction)
        .put("conf", Math.round(confiance * 100) / 100.0).put("calcul", Math.round(calculS * 100) / 100.0)
        .put("elements", JSONArray(elements.map { it.json() }))

    companion object {
        fun depuis(j: JSONObject): BullePhonie = BullePhonie(j.optLong("id"), j.optLong("t"), j.optDouble("duree", 0.0),
            j.optString("src"), if (j.isNull("freq") || !j.has("freq")) null else j.optLong("freq"), j.optString("sat"),
            j.optString("langue"), j.optDouble("proba", 0.0), j.optString("texte"), j.optString("traduction"),
            j.optDouble("conf", 0.0), j.optDouble("calcul", 0.0),
            (j.optJSONArray("elements") ?: JSONArray()).let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { AnalysePhonie.Element.depuis(it) } })

        /** The engine's mean log-probability as 0…1 (exp, weighted by each segment's length). */
        fun confiance(m: List<ProtocolePhonie.Morceau>): Double {
            val total = m.sumOf { (it.finS - it.debutS).coerceAtLeast(0.1) }
            if (m.isEmpty() || total <= 0) return 0.0
            return m.sumOf { Math.exp(it.avgLogprob.coerceAtMost(0.0)) * (it.finS - it.debutS).coerceAtLeast(0.1) } / total
        }
    }
}
