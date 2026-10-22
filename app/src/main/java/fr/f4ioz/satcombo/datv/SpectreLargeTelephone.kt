/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

/**
 * QO-100's wide band as the ground station sees it (the BATC's public
 * spectrum, read only), on the phone's DATV screen: the signals found in it,
 * so that one is chosen with a touch. The same as SatMe-PC's ([BandeLarge]).
 *
 * Connected only while [ouvre] holds (the option on, the screen shown);
 * nothing is ever sent there but the opening and the close.
 */
class SpectreLargeTelephone(private val portee: CoroutineScope) {

    data class Ui(
        val etat: String = "",
        val signaux: List<SignalLarge> = emptyList(),
        /** The last second's average, thinned for drawing ([BandeLarge] units). */
        val spectre: IntArray = IntArray(0),
        val frais: Boolean = false,
    )

    private val _ui = MutableStateFlow(Ui())
    val ui = _ui.asStateFlow()

    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS).build()
    private val recentes = ArrayDeque<IntArray>()
    @Volatile private var trameMs = 0L
    private var ws: WebSocket? = null
    private var boucle: Job? = null

    /** Connected (and kept so, reconnecting after a cut) until [ferme]. */
    fun ouvre() {
        if (boucle?.isActive == true) return
        boucle = portee.launch {
            var delai = DELAI_MIN_MS
            while (isActive) {
                val fini = kotlinx.coroutines.CompletableDeferred<String>()
                var recues = 0
                _ui.update { it.copy(etat = "connexion…") }
                ws = client.newWebSocket(Request.Builder().url(URL).header("Sec-WebSocket-Protocol", PROTOCOLE).header("User-Agent", AGENT).build(),
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) { _ui.update { it.copy(etat = "connecté") } }
                        override fun onMessage(webSocket: WebSocket, bytes: ByteString) { if (trame(bytes.toByteArray())) recues++ }
                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { fini.complete("connexion fermée") }
                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            fini.complete(t.message ?: t.javaClass.simpleName)
                        }
                    })
                val pourquoi = try { fini.await() } finally { ws?.cancel(); ws = null }
                if (recues > 0) delai = DELAI_MIN_MS
                _ui.update { it.copy(etat = "indisponible ($pourquoi), nouvel essai dans ${delai / 1000} s", frais = false) }
                delay(delai)
                delai = minOf(delai * 2, DELAI_MAX_MS)
            }
        }
    }

    fun ferme() {
        boucle?.cancel(); boucle = null
        ws?.close(1000, null); ws = null
        _ui.update { Ui() }
        synchronized(recentes) { recentes.clear() }
    }

    /** A frame received: its signals found on the last second's average. */
    fun trame(b: ByteArray): Boolean {
        val v = BandeLarge.valeurs(b)
        if (v.size < 100) return false
        val m = synchronized(recentes) {
            recentes.addLast(v); while (recentes.size > 4) recentes.removeFirst()
            BandeLarge.moyenne(recentes.toList())
        }
        trameMs = System.currentTimeMillis()
        _ui.update { it.copy(signaux = BandeLarge.detecte(m), spectre = amincit(m, POINTS), frais = true) }
        return true
    }

    /** Fresh frames (the last within 5 s). */
    fun frais(): Boolean = trameMs > 0 && System.currentTimeMillis() - trameMs < 5_000L

    companion object {
        const val URL = "wss://eshail.batc.org.uk/wb/fft"
        const val PROTOCOLE = "fft"
        const val AGENT = "SatMe-Android"
        const val DELAI_MIN_MS = 2_000L
        const val DELAI_MAX_MS = 60_000L
        /** Points drawn across the band. */
        const val POINTS = 360

        /** [v] brought to [n] points, each the highest of its share (a narrow signal stays seen). */
        fun amincit(v: IntArray, n: Int): IntArray {
            if (v.size <= n) return v
            return IntArray(n) { i ->
                val a = i * v.size / n; val b = maxOf(a + 1, (i + 1) * v.size / n)
                var m = 0; for (k in a until b) if (v[k] > m) m = v[k]; m
            }
        }
    }
}
