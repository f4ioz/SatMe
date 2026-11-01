/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.aprs.Agwpe
import fr.f4ioz.satcombo.aprs.Ax25
import fr.f4ioz.satcombo.aprs.Kiss
import fr.f4ioz.satcombo.aprs.KissDecodeur
import fr.f4ioz.satcombo.plateforme.Stockage
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The AX.25 frames SatMe decodes, offered to other programs — Xastir, YAAC,
 * APRSIS32, a packet client — on two TCP servers: **KISS** (port 8001 by
 * default) and **AGWPE** (port 8000, monitoring: raw 'K' frames and 'U'
 * frames, after the client's 'k' or 'm').
 *
 * **Listening only.** This class holds no way to a transmitter: it is given
 * the frames heard ([diffuse]) and nothing else. A frame a client sends to
 * be transmitted (a KISS data frame; AGWPE K, M, V, C, D…) is refused and
 * counted, never passed on.
 *
 * Both are **off by default** and listen on **127.0.0.1** only; opening them
 * to the local network is an option, and even then only private, link-local
 * and loopback addresses are let in.
 */
class PartageTramesPc(
    private val stockage: Stockage,
    private val horloge: () -> Long = System::currentTimeMillis,
) {
    // ------------------------------------------------------------ settings

    var kissActif: Boolean
        get() = stockage.getBoolean("pc_partage_kiss", false)
        set(v) { stockage.edit().putBoolean("pc_partage_kiss", v).apply(); kiss.applique() }
    var kissPort: Int
        get() = stockage.getInt("pc_partage_kiss_port", PORT_KISS)
        set(v) { stockage.edit().putInt("pc_partage_kiss_port", v).apply(); kiss.applique() }
    var agwActif: Boolean
        get() = stockage.getBoolean("pc_partage_agw", false)
        set(v) { stockage.edit().putBoolean("pc_partage_agw", v).apply(); agw.applique() }
    var agwPort: Int
        get() = stockage.getInt("pc_partage_agw_port", PORT_AGW)
        set(v) { stockage.edit().putInt("pc_partage_agw_port", v).apply(); agw.applique() }
    /** Open to the local network (private addresses only); off: this PC only. */
    var reseau: Boolean
        get() = stockage.getBoolean("pc_partage_reseau", false)
        set(v) { stockage.edit().putBoolean("pc_partage_reseau", v).apply(); kiss.applique(true); agw.applique(true) }

    /** Frames handed to the clients since the start (each frame once, whatever the number of clients). */
    val partagees = AtomicInteger()

    // ------------------------------------------------------------ the frames heard

    private val recents = LinkedHashMap<String, Long>()

    /**
     * A frame heard on the air (AX.25 without FCS: from the sound card's
     * decoder, a KISS radio listening, the packet decoder). The same bytes
     * twice within 2 s (two receivers) go once.
     */
    fun diffuse(ax25: ByteArray) {
        if (!kissActif && !agwActif) return
        if (!Ax25.adressesValides(ax25)) return
        val maintenant = horloge()
        val cle = ax25.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        synchronized(recents) {
            val it = recents.entries.iterator()
            while (it.hasNext()) { if (maintenant - it.next().value > 2_000L) it.remove() else break }
            if (recents.containsKey(cle)) return
            recents[cle] = maintenant
        }
        partagees.incrementAndGet()
        if (kissActif) kiss.envoieATous { Kiss.trame(ax25) }
        if (agwActif) agw.envoieATous { c ->
            val a = c as ClientAgw
            val l = ArrayList<ByteArray>(2)
            if (a.brut) l += Agwpe.brute(ax25).octets()
            if (a.moniteur) Agwpe.moniteur(ax25, maintenant)?.let { l += it.octets() }
            if (l.isEmpty()) null else l.reduce { x, y -> x + y }
        }
    }

    // ------------------------------------------------------------ the servers

    /** One client: what goes out waits in its queue, written by its own thread (a slow client does not slow the decoders). */
    abstract inner class Client(val s: Socket) {
        val file = LinkedBlockingQueue<ByteArray>(500)
        @Volatile var ouvert = true
        fun ferme() { ouvert = false; runCatching { s.close() } }
        abstract fun lit(b: ByteArray, n: Int)
    }

    inner class ClientKiss(s: Socket, private val serveur: Serveur) : Client(s) {
        // Every data frame a client sends is a frame to transmit: refused, counted, dropped.
        private val decodeur = KissDecodeur { _ -> serveur.refusees.incrementAndGet() }
        override fun lit(b: ByteArray, n: Int) = decodeur.octets(b, n)
    }

    inner class ClientAgw(s: Socket, private val serveur: Serveur) : Client(s) {
        @Volatile var brut = false
        @Volatile var moniteur = false
        private val lecteur = Agwpe.Lecteur()
        override fun lit(b: ByteArray, n: Int) {
            for (t in lecteur.octets(b, n)) when (Agwpe.genre(t.genre)) {
                Agwpe.Genre.EMISSION -> serveur.refusees.incrementAndGet()
                Agwpe.Genre.BASCULE -> if (t.genre == 'k') brut = !brut else moniteur = !moniteur
                Agwpe.Genre.REPONSE -> Agwpe.reponse(t, NOM_PORT)?.let { file.offer(it.octets()) }
                Agwpe.Genre.IGNORE -> serveur.ignorees.incrementAndGet()
            }
            if (lecteur.faux) ferme()
        }
    }

    inner class Serveur(val nom: String, private val actif: () -> Boolean, private val port: () -> Int, private val fabrique: (Socket, Serveur) -> Client) {
        @Volatile private var ss: ServerSocket? = null
        val clients = CopyOnWriteArrayList<Client>()
        val refusees = AtomicInteger()
        val ignorees = AtomicInteger()
        val envoyees = AtomicInteger()
        @Volatile var erreur = ""; private set
        @Volatile var ecoute = ""; private set
        @Volatile var portOuvert = 0; private set

        @Synchronized fun applique(rouvrir: Boolean = false) {
            val voulu = actif()
            val ouvert = ss
            if (ouvert != null && (!voulu || rouvrir || (port() != 0 && ouvert.localPort != port()))) ferme()
            if (!voulu || ss != null) return
            erreur = ""
            val p = port()
            if (p !in 0..65535) { erreur = "port $p hors de 0–65535"; return }
            val adresse = if (reseau) InetAddress.getByName("0.0.0.0") else InetAddress.getLoopbackAddress()
            val n = try { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(adresse, p), 8) } } catch (e: Exception) {
                erreur = "port $p indisponible (" + e.javaClass.simpleName + ")"; return
            }
            ss = n; portOuvert = n.localPort
            ecoute = (if (reseau) "réseau local" else "127.0.0.1") + ":" + n.localPort
            Thread({ accepte(n) }, "satme-partage-$nom").apply { isDaemon = true; start() }
        }

        @Synchronized fun ferme() {
            runCatching { ss?.close() }
            ss = null; ecoute = ""; portOuvert = 0
            clients.forEach { it.ferme() }; clients.clear()
        }

        private fun accepte(n: ServerSocket) {
            while (!n.isClosed) {
                val s = runCatching { n.accept() }.getOrNull() ?: break
                if (!admis(s.inetAddress) || clients.size >= MAX_CLIENTS) { runCatching { s.close() }; continue }
                val c = fabrique(s, this)
                clients += c
                Thread({ ecrit(c) }, "satme-partage-$nom-e").apply { isDaemon = true; start() }
                Thread({ litClient(c) }, "satme-partage-$nom-l").apply { isDaemon = true; start() }
            }
        }

        private fun litClient(c: Client) {
            val buf = ByteArray(4096)
            runCatching {
                val e = c.s.getInputStream()
                while (c.ouvert) {
                    val n = e.read(buf)
                    if (n < 0) break
                    c.lit(buf, n)
                }
            }
            c.ferme(); clients.remove(c)
        }

        private fun ecrit(c: Client) {
            runCatching {
                val o = c.s.getOutputStream()
                while (c.ouvert) {
                    val b = c.file.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    o.write(b); o.flush()
                }
            }
            c.ferme(); clients.remove(c)
        }

        fun envoieATous(quoi: (Client) -> ByteArray?) {
            for (c in clients) {
                val b = quoi(c) ?: continue
                if (c.file.offer(b)) envoyees.incrementAndGet()
            }
        }

        fun json(): JSONObject = JSONObject().put("actif", actif()).put("port", port()).put("ecoute", ecoute)
            .put("clients", clients.size).put("envoyees", envoyees.get()).put("refusees", refusees.get())
            .put("ignorees", ignorees.get()).put("erreur", erreur)
    }

    /** This PC, or (open to the network) an address of the local network only. */
    private fun admis(a: InetAddress): Boolean = a.isLoopbackAddress || (reseau && (a.isSiteLocalAddress || a.isLinkLocalAddress))

    val kiss = Serveur("kiss", { kissActif }, { kissPort }) { s, sv -> ClientKiss(s, sv) }
    val agw = Serveur("agw", { agwActif }, { agwPort }) { s, sv -> ClientAgw(s, sv) }

    /** At start: the servers ticked opened. */
    fun demarre() { kiss.applique(); agw.applique() }

    fun arrete() { kiss.ferme(); agw.ferme() }

    fun json(): JSONObject = JSONObject().put("present", true).put("ecouteSeule", true).put("reseau", reseau)
        .put("partagees", partagees.get()).put("kiss", kiss.json()).put("agw", agw.json())
        .put("defauts", JSONObject().put("kiss", PORT_KISS).put("agw", PORT_AGW))

    /** The page's settings: kiss, kissPort, agw, agwPort, reseau (1/0). */
    fun action(q: Map<String, String>): JSONObject {
        fun port(v: String): Int? = v.trim().toIntOrNull()?.takeIf { it in 1024..65535 }
        q["kissPort"]?.let { v -> kissPort = port(v) ?: return JSONObject().put("ok", false).put("erreur", "port KISS : 1024 à 65535") }
        q["agwPort"]?.let { v -> agwPort = port(v) ?: return JSONObject().put("ok", false).put("erreur", "port AGWPE : 1024 à 65535") }
        if (q["kissPort"] != null && q["agwPort"] != null && kissPort == agwPort) return JSONObject().put("ok", false).put("erreur", "deux ports différents")
        q["reseau"]?.let { reseau = it == "1" }
        q["kiss"]?.let { kissActif = it == "1" }
        q["agw"]?.let { agwActif = it == "1" }
        return json().put("ok", true)
    }

    companion object {
        const val PORT_KISS = 8001
        const val PORT_AGW = 8000
        const val MAX_CLIENTS = 8
        /** What the AGWPE port is called to a client (ASCII). */
        const val NOM_PORT = "SatMe (reception seule)"
    }
}
