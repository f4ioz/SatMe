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
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** The synthetic voice, as [ParolePc] sees it: phrases in, one sound each out. */
interface SyntheseVoix {
    /** [phrases] said by [voix] (a model's file name; [locuteur] for a model of several), slower when [longueur] > 1. */
    fun dit(voix: String, locuteur: String, phrases: List<String>, longueur: Double): Pair<List<FloatArray>, Int>
    fun arrete()
}

/** The tone colour converter: [src] made to sound like [ref]'s speaker. */
interface ConvertisseurVoix {
    fun convertit(ref: FloatArray, refRate: Int, src: FloatArray, srcRate: Int): Pair<FloatArray, Int>
    fun arrete()
}

/**
 * A helper as a child process speaking JSON lines (satme_voix.py): its
 * first line says it is ready, then one answer per question.
 */
class ProcessusLignes(private val commande: List<String>, private val environnement: Map<String, String>, private val delaiPretMs: Long = 120_000L) {
    @Volatile private var processus: Process? = null
    private var entree: OutputStream? = null
    private val lignes = LinkedBlockingQueue<String>()
    private val erreurs = ArrayDeque<String>()
    private var numero = 0L
    /** The ready line. */
    var pret: JSONObject? = null
        private set

    @Synchronized fun demarre(): JSONObject {
        pret?.let { if (processus?.isAlive == true) return it }
        val pb = ProcessBuilder(commande)
        pb.environment().putAll(environnement)
        val p = runCatching { pb.start() }.getOrElse { throw IllegalStateException("ne démarre pas : ${commande.firstOrNull()} (${it.message})") }
        processus = p; entree = p.outputStream; lignes.clear()
        thread(isDaemon = true, name = "satme-voix-sortie") {
            runCatching { BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8)).forEachLine { lignes.put(it) } }
            lignes.put(FIN)
        }
        thread(isDaemon = true, name = "satme-voix-erreurs") {
            runCatching { BufferedReader(InputStreamReader(p.errorStream, Charsets.UTF_8)).forEachLine { l ->
                synchronized(erreurs) { erreurs.addLast(l.take(300)); while (erreurs.size > 30) erreurs.removeFirst() } } }
        }
        val j = attend(delaiPretMs) { it.optBoolean("pret") || it.optBoolean("fatal") }
        if (!j.optBoolean("pret")) { arrete(); throw IllegalStateException(j.optString("erreur", "le programme ne démarre pas")) }
        pret = j
        return j
    }

    /** One question, its answer; an exception when the helper died or took longer than [delaiMs]. */
    @Synchronized fun demande(q: JSONObject, delaiMs: Long): JSONObject {
        demarre()
        val id = ++numero
        val o = entree ?: throw IllegalStateException("arrêté")
        runCatching { o.write((q.put("id", id).toString() + "\n").toByteArray(Charsets.UTF_8)); o.flush() }.onFailure { arrete(); throw IllegalStateException(raison()) }
        val r = attend(delaiMs) { it.has("id") && it.optLong("id", -1) == id }
        if (r.has("erreur")) throw IllegalStateException(r.optString("erreur"))
        return r
    }

    private fun attend(ms: Long, voulu: (JSONObject) -> Boolean): JSONObject {
        val fin = System.currentTimeMillis() + ms
        while (true) {
            val reste = fin - System.currentTimeMillis()
            if (reste <= 0) { arrete(); throw IllegalStateException("pas de réponse en ${ms / 1000} s") }
            val l = lignes.poll(reste, TimeUnit.MILLISECONDS) ?: continue
            if (l == FIN) { val r = raison(); arrete(); throw IllegalStateException(r) }
            val j = runCatching { JSONObject(l) }.getOrNull() ?: continue
            if (voulu(j)) return j
        }
    }

    private fun raison(): String = "le programme s'est arrêté" +
        (synchronized(erreurs) { erreurs.lastOrNull { it.isNotBlank() && !it.startsWith(" ") } }?.let { " : $it" } ?: "")

    @Synchronized fun arrete() {
        val p = processus ?: return
        processus = null; pret = null
        runCatching { entree?.close() }
        entree = null
        if (!p.waitFor(1500, TimeUnit.MILLISECONDS)) p.destroyForcibly()
    }

    companion object {
        private const val FIN = "\u0000fin"

        fun pcm(b64: String): FloatArray {
            if (b64.isEmpty()) return FloatArray(0)
            val b = Base64.getDecoder().decode(b64)
            return FloatArray(b.size / 2) { i -> ((b[2 * i].toInt() and 0xFF) or (b[2 * i + 1].toInt() shl 8)).toShort() / 32768f }
        }

        fun b64(x: FloatArray): String {
            val b = ByteArray(x.size * 2)
            for (i in x.indices) { val s = (x[i] * 32767f).toInt().coerceIn(-32768, 32767); b[2 * i] = s.toByte(); b[2 * i + 1] = (s shr 8).toByte() }
            return Base64.getEncoder().encodeToString(b)
        }
    }
}

/** Piper through satme_voix.py --parle. */
class SynthesePiper(commande: List<String>, env: Map<String, String>) : SyntheseVoix {
    private val p = ProcessusLignes(commande, env)
    val version: String get() = p.pret?.optString("piper").orEmpty()
    fun demarre() = p.demarre()
    override fun dit(voix: String, locuteur: String, phrases: List<String>, longueur: Double): Pair<List<FloatArray>, Int> {
        val r = p.demande(JSONObject().put("voix", voix).put("locuteur", locuteur).put("phrases", JSONArray(phrases)).put("longueur", longueur),
            30_000L + phrases.sumOf { it.length } * 50L)
        val a = r.optJSONArray("phrases") ?: JSONArray()
        return (0 until a.length()).map { ProcessusLignes.pcm(a.optString(it)) } to r.optInt("rate", 22_050)
    }
    override fun arrete() = p.arrete()
}

/** The tone colour converter through satme_voix.py --convertit (its own environment). */
class ConvertisseurProcessus(commande: List<String>, env: Map<String, String>) : ConvertisseurVoix {
    private val p = ProcessusLignes(commande, env, 180_000L)
    fun demarre() = p.demarre()
    override fun convertit(ref: FloatArray, refRate: Int, src: FloatArray, srcRate: Int): Pair<FloatArray, Int> {
        val r = p.demande(JSONObject().put("ref", ProcessusLignes.b64(ref)).put("ref_rate", refRate).put("src", ProcessusLignes.b64(src))
            .put("src_rate", srcRate).put("tau", 0.3), 60_000L + src.size / 10L)
        return ProcessusLignes.pcm(r.optString("pcm")) to r.optInt("rate", 22_050)
    }
    override fun arrete() = p.arrete()
}
