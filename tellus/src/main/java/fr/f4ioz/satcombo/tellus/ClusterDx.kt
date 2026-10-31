/*
 * TellusMe — the SatMe ecosystem's terrestrial station
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.tellus

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * A DX cluster over telnet, **read only**: TellusMe sends the callsign once
 * when the cluster asks for it (« login: », « call: »…) and nothing else,
 * ever — no spot, no talk, no command. It keeps the last [MAX] spots.
 * Reconnects [relanceMs] after the line dropped, while [actif].
 */
class ClusterDx(
    private val horloge: () -> Long = System::currentTimeMillis,
    private val relanceMs: Long = 60_000L,
) {
    data class Spot(val tMs: Long, val deQui: String, val khz: Double, val dx: String, val commentaire: String, val heure: String)

    @Volatile var hote = ""; private set
    @Volatile var port = 0; private set
    @Volatile var indicatif = ""; private set
    @Volatile var connecte = false; private set
    @Volatile var etat = "arrêté"; private set
    @Volatile private var actif = false
    @Volatile private var prise: Socket? = null
    @Volatile private var fil: Thread? = null
    private val spots = ArrayDeque<Spot>()
    /** What TellusMe wrote to the cluster (the tests read it: the callsign, once). */
    val envoye = StringBuffer()

    @Synchronized fun demarre(hote: String, port: Int, indicatif: String) {
        arrete()
        if (hote.isBlank() || port !in 1..65535 || indicatif.isBlank()) { etat = "réglez le cluster et l'indicatif"; return }
        this.hote = hote.trim(); this.port = port; this.indicatif = indicatif.trim().uppercase()
        actif = true
        fil = Thread({ boucle() }, "tellus-cluster").apply { isDaemon = true; start() }
    }

    @Synchronized fun arrete() {
        actif = false
        runCatching { prise?.close() }
        fil = null
        connecte = false
        etat = "arrêté"
    }

    private fun boucle() {
        val moi = Thread.currentThread()
        while (actif && fil === moi) {
            try {
                etat = "connexion à $hote:$port…"
                Socket().use { s ->
                    prise = s
                    s.connect(InetSocketAddress(hote, port), 10_000)
                    s.soTimeout = 0
                    connecte = true
                    etat = "connecté à $hote:$port"
                    lis(s.getInputStream()) { ligne -> traite(ligne, s) }
                }
            } catch (e: Exception) {
                if (actif) etat = "coupé : " + (e.message ?: e.javaClass.simpleName).take(80)
            }
            connecte = false
            val fin = horloge() + relanceMs
            while (actif && fil === moi && horloge() < fin) Thread.sleep(200)
        }
    }

    private var identifie = false

    private fun traite(ligne: String, s: Socket) {
        val l = ligne.trim()
        if (!identifie && DEMANDE.containsMatchIn(l)) {
            identifie = true
            val m = indicatif + "\r\n"
            envoye.append(m)
            s.getOutputStream().apply { write(m.toByteArray(Charsets.US_ASCII)); flush() }
            return
        }
        lisSpot(l, horloge())?.let { sp -> synchronized(spots) { spots.addFirst(sp); while (spots.size > MAX) spots.removeLast() } }
    }

    /** Lines, and the prompt that ends without a newline (« login: »). */
    private fun lis(e: InputStream, ligne: (String) -> Unit) {
        val sb = StringBuilder()
        while (true) {
            val b = e.read()
            if (b < 0) break
            if (b == 0xFF) { e.read(); e.read(); continue } // telnet negotiation: ignored, never answered
            when (b) {
                '\n'.code -> { ligne(sb.toString()); sb.setLength(0) }
                '\r'.code -> {}
                else -> {
                    sb.append(b.toChar())
                    if (!identifie && sb.length < 80 && sb.trimEnd().endsWith(":") && DEMANDE.containsMatchIn(sb)) { ligne(sb.toString()); sb.setLength(0) }
                }
            }
        }
    }

    fun spots(): List<Spot> = synchronized(spots) { spots.toList() }

    /** For the page: the spots in [basHz]..[hautHz] (all when null), newest first. */
    fun json(basHz: Long? = null, hautHz: Long? = null): JSONObject {
        val l = spots().filter { basHz == null || hautHz == null || (it.khz * 1000).toLong() in basHz..hautHz }.take(60)
        return JSONObject().put("connecte", connecte).put("etat", etat).put("hote", hote).put("total", spots().size)
            .put("spots", JSONArray(l.map {
                JSONObject().put("t", it.tMs).put("de", it.deQui).put("khz", it.khz).put("dx", it.dx).put("c", it.commentaire).put("h", it.heure)
            }))
    }

    companion object {
        const val MAX = 300
        private val DEMANDE = Regex("(login|call|callsign|indicatif)\\s*:?\\s*$", RegexOption.IGNORE_CASE)
        private val SPOT = Regex("^DX de\\s+([^:\\s]+):?\\s+(\\d+(?:\\.\\d+)?)\\s+(\\S+)\\s*(.*?)\\s*(\\d{4})Z", RegexOption.IGNORE_CASE)

        /** « DX de F4XYZ:  14025.0  K1ABC  CW 599  1234Z » → a spot, null for anything else. */
        fun lisSpot(l: String, tMs: Long): Spot? {
            val m = SPOT.find(l.trim()) ?: return null
            val (de, khz, dx, c, h) = m.destructured
            val f = khz.toDoubleOrNull() ?: return null
            return Spot(tMs, de.uppercase(), f, dx.uppercase(), c.trim(), h)
        }
    }
}
