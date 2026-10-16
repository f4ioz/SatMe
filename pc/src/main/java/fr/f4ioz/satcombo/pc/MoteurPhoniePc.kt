/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.phonie.ProtocolePhonie
import fr.f4ioz.satcombo.phonie.ProtocolePhonie.Message
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** The speech recogniser, as [PhoniePc] sees it: started, asked, stopped. */
interface MoteurPhonie {
    /** Loads the model; the engine's [Message.Pret], or an exception saying why not. */
    fun demarre(): Message.Pret
    /** One piece of voice: the answer ([Message.Reponse] or [Message.Erreur]); an exception when the engine died. */
    fun transcrit(id: Long, pcm: FloatArray, langue: String, traduire: Boolean): Message
    /** The same, the translation into [cible] (a language code; "" or "whisper": Whisper's English). */
    fun transcrit(id: Long, pcm: FloatArray, langue: String, traduire: Boolean, cible: String): Message = transcrit(id, pcm, langue, traduire)
    fun arrete()
}

/**
 * The helper as a child process: frames on its stdin, JSON lines on its
 * stdout ([ProtocolePhonie]); what it says on stderr kept (its last lines
 * explain a crash).
 */
class MoteurProcessus(
    private val commande: List<String>,
    private val environnement: Map<String, String> = emptyMap(),
    private val delaiChargementMs: Long = 300_000L,
) : MoteurPhonie {

    @Volatile private var processus: Process? = null
    private var entree: OutputStream? = null
    private val lignes = LinkedBlockingQueue<String>()
    private val erreurs = ArrayDeque<String>()

    /** The last lines on its stderr. */
    fun dernieresErreurs(): List<String> = synchronized(erreurs) { erreurs.toList() }

    override fun demarre(): Message.Pret {
        val pb = ProcessBuilder(commande)
        pb.environment().putAll(environnement)
        val p = runCatching { pb.start() }.getOrElse { throw IllegalStateException("Python introuvable ou ne démarre pas : ${commande.firstOrNull()} (${it.message})") }
        processus = p
        entree = p.outputStream
        thread(isDaemon = true, name = "satme-phonie-sortie") {
            runCatching { BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8)).forEachLine { lignes.put(it) } }
            lignes.put(FIN)
        }
        thread(isDaemon = true, name = "satme-phonie-erreurs") {
            runCatching {
                BufferedReader(InputStreamReader(p.errorStream, Charsets.UTF_8)).forEachLine { l ->
                    synchronized(erreurs) { erreurs.addLast(l.take(400)); while (erreurs.size > 40) erreurs.removeFirst() }
                }
            }
        }
        val fin = System.currentTimeMillis() + delaiChargementMs
        while (true) {
            val reste = fin - System.currentTimeMillis()
            if (reste <= 0) { arrete(); throw IllegalStateException("le modèle ne se charge pas (plus de ${delaiChargementMs / 1000} s)") }
            val l = lignes.poll(reste, TimeUnit.MILLISECONDS) ?: continue
            if (l == FIN) { p.waitFor(2, TimeUnit.SECONDS); throw IllegalStateException(raisonArret()) }
            when (val m = ProtocolePhonie.lit(l)) {
                is Message.Pret -> return m
                is Message.Erreur -> { arrete(); throw IllegalStateException(m.texte) }
                else -> {}
            }
        }
    }

    override fun transcrit(id: Long, pcm: FloatArray, langue: String, traduire: Boolean): Message = transcrit(id, pcm, langue, traduire, "")

    override fun transcrit(id: Long, pcm: FloatArray, langue: String, traduire: Boolean, cible: String): Message {
        val o = entree ?: throw IllegalStateException("moteur arrêté")
        runCatching { o.write(ProtocolePhonie.trame(id, pcm, langue, traduire, cible)); o.flush() }
            .onFailure { throw IllegalStateException(raisonArret()) }
        // Translating doubles the work; a slow processor takes a few times the piece's length.
        val delai = 60_000L + pcm.size / 16L * (if (traduire) 20 else 10)
        val fin = System.currentTimeMillis() + delai
        while (true) {
            val reste = fin - System.currentTimeMillis()
            if (reste <= 0) { arrete(); throw IllegalStateException("le moteur ne répond plus (${delai / 1000} s)") }
            val l = lignes.poll(reste, TimeUnit.MILLISECONDS) ?: continue
            if (l == FIN) throw IllegalStateException(raisonArret())
            when (val m = ProtocolePhonie.lit(l)) {
                is Message.Reponse -> if (m.id == id) return m
                is Message.Erreur -> if (m.id == id || m.id == null) return m
                else -> {}
            }
        }
    }

    override fun arrete() {
        val p = processus ?: return
        processus = null
        runCatching { entree?.close() }
        entree = null
        if (!p.waitFor(1500, TimeUnit.MILLISECONDS)) { p.destroy(); if (!p.waitFor(1500, TimeUnit.MILLISECONDS)) p.destroyForcibly() }
    }

    /** Why it stopped: its exit code and the last line it wrote on stderr. */
    private fun raisonArret(): String {
        val p = processus
        val code = p?.let { runCatching { if (it.waitFor(2, TimeUnit.SECONDS)) it.exitValue() else null }.getOrNull() }
        val derniere = dernieresErreurs().lastOrNull { it.isNotBlank() && !it.startsWith(" ") }?.trim().orEmpty()
        return "le moteur s'est arrêté" + (code?.let { " (code $it)" } ?: "") + (if (derniere.isNotEmpty()) " : $derniere" else "")
    }

    private companion object { const val FIN = "\u0000fin" }
}
