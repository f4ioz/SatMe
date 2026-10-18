/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.datv.CommandeAccord
import fr.f4ioz.satcombo.datv.LimiteDebit
import fr.f4ioz.satcombo.datv.ReglageDatv
import fr.f4ioz.satcombo.plateforme.Stockage
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.concurrent.thread

/**
 * The DATV receiver retuned by UDP (another program on this PC, or on the
 * local network when SatMe-PC runs with --reseau and it is chosen): a tune
 * request in text ([CommandeAccord]) per datagram. Off unless chosen.
 * Receive only: nothing is ever answered nor sent, the LNB supply never
 * touched, requests come at most one every 2 s.
 */
class AccordUdpPc(
    private val stockage: Stockage,
    /** SatMe-PC opened to the network (--reseau): only then may the local network send. */
    private val reseauPermis: Boolean,
    private val actuel: () -> ReglageDatv,
    /** Starts receiving with these settings; null when started, else why not. */
    private val accorde: (ReglageDatv) -> String?,
    /** Receiving now: the same settings sent again change nothing. */
    private val actif: () -> Boolean = { true },
    private val horloge: () -> Long = System::currentTimeMillis,
) {
    val on: Boolean get() = stockage.getBoolean("datv_accord_udp", false)
    val port: Int get() = stockage.getInt("datv_accord_udp_port", PORT_DEFAUT).takeIf { it in 1024..65535 } ?: PORT_DEFAUT
    /** From the local network too (only with --reseau). */
    val reseau: Boolean get() = reseauPermis && stockage.getBoolean("datv_accord_udp_reseau", false)

    private val limite = LimiteDebit(2_000L, 10)
    @Volatile private var socket: DatagramSocket? = null
    @Volatile var erreur = ""; private set
    @Volatile var recus = 0; private set
    @Volatile var refuses = 0; private set
    @Volatile var dernier = ""; private set
    @Volatile var dernierMs = 0L; private set
    @Volatile var dernierResultat = ""; private set

    /** Listening as set (at the start, and after each change). */
    @Synchronized
    fun relance() {
        socket?.close(); socket = null; erreur = ""
        if (!on) return
        val adresse = if (reseau) InetSocketAddress(port) else InetSocketAddress(InetAddress.getLoopbackAddress(), port)
        val s = runCatching { DatagramSocket(null).apply { reuseAddress = false; bind(adresse) } }
            .getOrElse { erreur = "port UDP " + port + " indisponible : " + (it.message ?: ""); return }
        socket = s
        thread(isDaemon = true, name = "satme-datv-accord-udp") { ecoute(s) }
    }

    /** On, off, another port, the network or not; null when done, else why not. */
    fun regle(on: Boolean?, port: Int?, reseau: Boolean?): String? {
        if (port != null && port !in 1024..65535) return "port : 1024 à 65535"
        if (reseau == true && !reseauPermis) return "le réseau local seulement quand SatMe-PC est lancé avec --reseau"
        val e = stockage.edit()
        on?.let { e.putBoolean("datv_accord_udp", it) }
        port?.let { e.putInt("datv_accord_udp_port", it) }
        reseau?.let { e.putBoolean("datv_accord_udp_reseau", it) }
        e.apply()
        relance()
        return erreur.takeIf { it.isNotEmpty() }
    }

    private fun ecoute(s: DatagramSocket) {
        val b = ByteArray(1024)
        while (!s.isClosed) {
            val d = DatagramPacket(b, b.size)
            if (runCatching { s.receive(d) }.isFailure) break
            if (!permis(d.address)) { refuses++; continue }
            recoit(String(b, 0, d.length, Charsets.UTF_8))
        }
    }

    /** This PC always; the local network only when chosen. */
    fun permis(a: InetAddress): Boolean = a.isLoopbackAddress || (reseau && (a.isSiteLocalAddress || a.isLinkLocalAddress))

    /** One request: read, limited, applied. */
    fun recoit(texte: String): String {
        val t = horloge()
        recus++
        dernier = texte.trim().take(160); dernierMs = t
        val r = CommandeAccord.lis(texte, actuel())
        val g = r.reglage
        val res = when {
            g == null -> "refusée : " + r.erreur
            g == actuel() && actif() -> "déjà sur ce réglage"
            !limite.accepte(t) -> "ignorée : trop rapprochée (une toutes les 2 s au plus)"
            else -> accorde(g)?.let { "refusée : $it" } ?: ("accordé sur " + g.cielKhz + " kHz, " + g.srKs + " kS/s")
        }
        dernierResultat = res
        return res
    }

    fun json(): JSONObject = JSONObject().put("on", on).put("port", port).put("reseau", reseau).put("reseauPermis", reseauPermis)
        .put("ecoute", socket?.isClosed == false).put("erreur", erreur).put("recus", recus).put("refuses", refuses)
        .put("dernier", dernier).put("dernierMs", dernierMs).put("resultat", dernierResultat)

    fun ferme() { socket?.close(); socket = null }

    companion object {
        const val PORT_DEFAUT = 9997
    }
}
