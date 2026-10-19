/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.demo.SonDistant
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * « Mode distant »: the sound over a WebSocket (/api/son/ws), for a page
 * opened from afar — 20 ms frames at 8, 12 or 16 kHz, sent as soon as they
 * are made (no Nagle, a small socket buffer: a slow network drops old sound
 * here rather than piling it up). Receive only: a sound or a PTT going up
 * is refused and the socket closed.
 */
class SonDistantPc(private val diffusion: DiffusionSon, private val horloge: () -> Long = System::currentTimeMillis) {

    private class Bloc(val pcm: ShortArray, val n: Int, val rate: Int, val ms: Long)

    /** Serves one page, the opening already answered (101), until it goes. */
    fun sert(c: Socket, debut: SonDistant.Config) {
        runCatching { c.tcpNoDelay = true; c.sendBufferSize = 16 * 1024; c.soTimeout = 0 }
        val e = c.getInputStream()
        val s = c.getOutputStream()
        val emetteur = SonDistant.Emetteur(debut)
        val file = ArrayBlockingQueue<Bloc>(24)
        val perdus = AtomicLong()
        val fini = java.util.concurrent.atomic.AtomicBoolean()
        val verrou = Any()
        fun ecrit(b: ByteArray): Boolean = synchronized(verrou) { runCatching { s.write(b); s.flush() }.isSuccess }
        val abonne = DiffusionSon.Abonne(debut.source) { pcm, n, rate ->
            val b = Bloc(pcm.copyOf(n), n, rate, horloge())
            // The network behind: the oldest sound goes, never the source held back.
            if (!file.offer(b)) { file.poll(); perdus.incrementAndGet(); file.offer(b) }
        }
        if (!ecrit(WebSocketPc.texte(SonDistant.bonjour(debut)))) return
        diffusion.abonne(abonne)
        val envoi = thread(isDaemon = true, name = "satme-son-ws") {
            try {
                while (!fini.get()) {
                    val b = file.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    for (t in emetteur.pousse(b.pcm, b.n, b.rate, b.ms)) if (!ecrit(WebSocketPc.trame(WebSocketPc.BINAIRE, t))) {
                        // The page gone: the reader woken.
                        fini.set(true); runCatching { c.close() }; break
                    }
                }
            } catch (_: InterruptedException) {}
        }
        try {
            while (!fini.get()) {
                val t = WebSocketPc.litTrame(e) ?: break
                when (t.opcode) {
                    WebSocketPc.TEXTE -> if (t.fin) when (val m = SonDistant.message(String(t.donnees, Charsets.UTF_8))) {
                        is SonDistant.Message.Ping -> ecrit(WebSocketPc.texte(SonDistant.pong(m, horloge(), perdus.get())))
                        is SonDistant.Message.Reglage -> {
                            val c2 = SonDistant.Config.de(m.source, m.rate, m.codec, emetteur.config)
                            emetteur.config = c2; abonne.source = c2.source
                            ecrit(WebSocketPc.texte(SonDistant.bonjour(c2)))
                        }
                        is SonDistant.Message.Refuse -> { refuse(::ecrit, m.raison); break }
                        SonDistant.Message.Inconnu -> {}
                    }
                    // Binary from the page: a sound going up (kind 0x81, kept for later) — not now.
                    WebSocketPc.BINAIRE -> { refuse(::ecrit, "son montant refusé : réception seule"); break }
                    WebSocketPc.PING -> ecrit(WebSocketPc.trame(WebSocketPc.PONG, t.donnees))
                    WebSocketPc.FERMETURE -> { ecrit(WebSocketPc.trame(WebSocketPc.FERMETURE, t.donnees.copyOf(minOf(2, t.donnees.size)))); break }
                }
            }
        } finally {
            fini.set(true)
            diffusion.desabonne(abonne)
            envoi.interrupt()
            runCatching { envoi.join(1000) }
        }
    }

    private fun refuse(ecrit: (ByteArray) -> Boolean, raison: String) {
        ecrit(WebSocketPc.texte(SonDistant.refus(raison)))
        ecrit(WebSocketPc.fermeture(SonDistant.REFUS))
    }

    companion object {
        /**
         * A WebSocket opening from this page's own origin only: a page of
         * another site open in the same browser must not listen in (cookies
         * aside, this PC enters freely).
         */
        fun origineAdmise(origine: String?, hote: String?): Boolean {
            if (origine.isNullOrBlank()) return true
            if (hote.isNullOrBlank()) return false
            val o = origine.substringAfter("://", "").trimEnd('/')
            return o.equals(hote.trim(), ignoreCase = true)
        }

        fun refusHttp(s: OutputStream, statut: String, raison: String) {
            val corps = raison.toByteArray(Charsets.UTF_8)
            runCatching {
                s.write(("HTTP/1.1 $statut\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${corps.size}\r\nConnection: close\r\n\r\n").toByteArray())
                s.write(corps); s.flush()
            }
        }
    }
}
