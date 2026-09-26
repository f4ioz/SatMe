/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.rotor

import fr.f4ioz.satcombo.cat.CatJournal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.Writer
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale

/**
 * Le dialecte de `rotctld`, le démon de rotor de Hamlib.
 *
 * C'est l'autre chemin vers le mât, et il ne ressemble en rien au premier :
 * pas de câble série ici, mais une prise TCP vers un petit serveur — sur un
 * Raspberry Pi au pied de l'antenne, le plus souvent — qui parle, lui, au
 * contrôleur. L'intérêt est considérable pour l'opérateur : Hamlib connaît
 * plus de rotors que nous n'en verrons jamais, et le téléphone n'a plus besoin
 * d'être relié physiquement à quoi que ce soit.
 *
 * Le protocole est du texte, une commande par ligne. `P 180.00 45.00` pour
 * viser, `p` pour lire, `S` pour arrêter, et le serveur rend `RPRT 0` quand
 * tout va bien. Trois pièges s'y cachent, et les trois ont été rencontrés :
 *
 * **Le point décimal.** Hamlib lit ses nombres en C, avec la virgule flottante
 * anglo-saxonne. Un `String.format` sans [Locale] écrit `180,00` sur un
 * téléphone réglé en français, et le serveur répond `RPRT -1` sans autre
 * explication. Tout ce fichier formate en [Locale.US], sans exception.
 *
 * **Le `RPRT -1` rendu à la place des deux lignes de position.** Quand le
 * contrôleur ne répond pas, `p` ne rend pas deux nombres : il rend un code
 * d'erreur, sur une seule ligne. Un lecteur qui attend aveuglément deux lignes
 * consomme alors la réponse de la commande **suivante**, et à partir de là tout
 * est décalé d'un cran — indéfiniment.
 *
 * **L'écho du mode étendu.** Un `rotctld` lancé avec les réponses étendues fait
 * précéder chaque réponse de l'écho de la commande (`get_pos:`) et nomme ses
 * champs (`Azimuth: 180.000000`), puis termine par `RPRT 0`. Le `RPRT 0` final
 * n'existe pas en mode simple ; l'oublier laisse une ligne en trop dans le
 * tuyau, et l'on retombe sur le décalage précédent.
 */
object RotctldCodec {

    /** Demande de position. */
    const val QUERY = "p\n"

    /** Arrêt immédiat. */
    const val STOP = "S\n"

    /** La consigne, toujours avec un point décimal, quelle que soit la langue. */
    fun moveCommand(azDeg: Double, elDeg: Double): String? {
        if (azDeg.isNaN() || elDeg.isNaN() || azDeg.isInfinite() || elDeg.isInfinite()) return null
        return String.format(Locale.US, "P %.2f %.2f\n", azDeg, elDeg)
    }
}

/**
 * Le client `rotctld`, sur une prise TCP.
 *
 * Rien ici ne connaît Android : le client s'éprouve au banc contre un faux
 * serveur Hamlib de trente lignes, sur un port que le système choisit
 * lui-même.
 */
class RotctldRotor : RotorDriver {

    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: Writer? = null

    override val isOpen: Boolean get() = socket?.isConnected == true && socket?.isClosed == false

    /** Ouvre la prise vers [host]:[port]. */
    suspend fun open(host: String, port: Int, timeoutMs: Int = 2000): Boolean =
        withContext(Dispatchers.IO) {
            close()
            runCatching {
                val s = Socket()
                s.connect(InetSocketAddress(host, port), timeoutMs)
                s.soTimeout = 1500
                socket = s
                reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.US_ASCII))
                writer = OutputStreamWriter(s.getOutputStream(), Charsets.US_ASCII)
                true
            }.getOrDefault(false)
        }

    override fun close() {
        runCatching { writer?.close() }
        runCatching { reader?.close() }
        runCatching { socket?.close() }
        writer = null; reader = null; socket = null
    }

    /**
     * Jette ce qui traîne encore avant de poser une question.
     *
     * C'est le filet de sécurité contre le décalage : si une réponse
     * précédente a laissé une ligne — un `RPRT 0` de mode étendu, un message
     * d'erreur inattendu —, elle part ici, et non dans la lecture suivante.
     */
    private fun drain(r: BufferedReader) {
        var guard = 0
        runCatching { while (r.ready() && guard++ < 32) { if (r.readLine() == null) break } }
    }

    private fun send(s: String): Boolean {
        val w = writer ?: return false
        val r = reader ?: return false
        drain(r)
        return runCatching {
            w.write(s); w.flush()
            CatJournal.log(true, s.toByteArray(Charsets.US_ASCII), "rotctld → " + s.trim())
            true
        }.getOrDefault(false)
    }

    /** Attend le compte rendu d'une commande d'écriture. Vrai sur `RPRT 0`. */
    private fun readRprt(): Boolean {
        val r = reader ?: return false
        var guard = 0
        while (guard++ < 8) {
            val line = runCatching { r.readLine() }.getOrNull() ?: return false
            val t = line.trim()
            if (t.isEmpty()) continue
            CatJournal.log(false, t.toByteArray(Charsets.US_ASCII), "rotctld ← $t")
            if (t.startsWith("RPRT", ignoreCase = true))
                return t.substring(4).trim().toIntOrNull() == 0
        }
        return false
    }

    /**
     * Relit une position, en mode simple comme en mode étendu.
     *
     * Le mode se reconnaît tout seul : une ligne qui se termine par deux points
     * sans rien derrière est l'écho de la commande, donc on est en étendu, donc
     * il y aura un `RPRT` final à consommer. En mode simple, deux nombres
     * suffisent et il n'y a rien derrière.
     */
    private fun readPositionReply(): RotorPos? {
        val r = reader ?: return null
        val vals = ArrayList<Double>()
        var extended = false
        var guard = 0
        while (guard++ < 10) {
            val line = runCatching { r.readLine() }.getOrNull() ?: return null
            val t = line.trim()
            if (t.isEmpty()) continue
            CatJournal.log(false, t.toByteArray(Charsets.US_ASCII), "rotctld ← $t")
            if (t.startsWith("RPRT", ignoreCase = true)) {
                val code = t.substring(4).trim().toIntOrNull()
                return if (code == 0 && vals.size >= 2) RotorPos(vals[0], vals[1]) else null
            }
            val body = if (t.contains(':')) t.substringAfter(':').trim() else t
            if (body.isEmpty()) { extended = true; continue }
            val d = body.toDoubleOrNull() ?: continue
            vals += d
            if (vals.size == 2 && !extended) return RotorPos(vals[0], vals[1])
        }
        return null
    }

    override suspend fun moveTo(azDeg: Double, elDeg: Double): Boolean =
        withContext(Dispatchers.IO) {
            if (!isOpen) return@withContext false
            val cmd = RotctldCodec.moveCommand(azDeg, elDeg) ?: return@withContext false
            if (!send(cmd)) return@withContext false
            readRprt()
        }

    override suspend fun readPosition(): RotorPos? = withContext(Dispatchers.IO) {
        if (!isOpen) return@withContext null
        if (!send(RotctldCodec.QUERY)) return@withContext null
        readPositionReply()
    }

    override suspend fun stop(): Boolean = withContext(Dispatchers.IO) {
        if (!isOpen) return@withContext false
        if (!send(RotctldCodec.STOP)) return@withContext false
        readRprt()
    }
}
