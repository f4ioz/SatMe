/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sdr

import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The SDR of a SatMe-Node (its bladeRF or its Pluto) as a receiver here:
 * one TCP connection to the node's `/noeud/sdr/flux`, the I/Q coming in
 * frames ([FluxNoeud]), the tuning, gain and correction going back as
 * command lines on the same connection.
 *
 * **The rate.** The reception runs at [Dsp.RTL_RATE]; the node sends that,
 * or 2^[etages] times less (a lighter stream for a slow network), brought
 * back up here ([InterpolateurDemiBande]): the outer part of the band is
 * then empty.
 *
 * **Network loss.** The first connection is made by [connecte] (its
 * failure said at once); a connection lost afterwards is taken up again
 * by itself, every second or so, the settings asked again — meanwhile the
 * reception gets nothing (no error) and [avertissement] says why.
 *
 * Receive only: nothing on this connection can make the node transmit.
 */
class SourceNoeudIq private constructor(
    private val hote: String,
    private val port: Int,
    /** The request's path with the key (`/noeud/sdr/flux?cle=…`), the settings added to it. */
    private val chemin: String,
    val etages: Int,
    private val horloge: () -> Long,
    private val attenteMs: Int,
    private val reprisesMs: Long,
) : SourceIq {

    /** What the node said of itself at the last connection (device, its board). */
    @Volatile var preambule: JSONObject = JSONObject(); private set
    val description: String get() = preambule.optString("libelle").ifBlank { "SDR du SatMe-Node" }
    /** The node's device ("bladerf", "pluto"). */
    val appareil: String get() = preambule.optString("appareil")
    @Volatile var erreur: String? = null; private set
    /** Why nothing comes now (the connection lost), else what the node says of its device; empty: all well. */
    val avertissement: String get() = avertIci.ifEmpty { avertissementNoeud }
    @Volatile private var avertIci = ""
    /** Connections taken up again after a loss. */
    @Volatile var reprises = 0; private set
    @Volatile var connectee = false; private set

    @Volatile private var ouvert = true
    @Volatile private var socket: Socket? = null
    @Volatile private var ecrivain: FluxNoeud.Ecrivain? = null
    @Volatile private var lecteur: FluxNoeud.Lecteur? = null
    private val file = ArrayBlockingQueue<ShortArray>(PROFONDEUR)
    @Volatile private var pertesIci = 0L
    @Volatile private var pertesAvant = 0L
    @Volatile private var octetsAvant = 0L
    /** Frames dropped on our side while the tuning moved (still at the old centre): not losses. */
    @Volatile var enTransit = 0L; private set

    @Volatile private var centreHz = 0L
    @Volatile private var centreMs = 0L
    @Volatile private var gainDixiemes: Int? = null
    @Volatile private var ppm = 0
    @Volatile private var avertissementNoeud = ""

    override val iq16: Boolean get() = true
    override val octets: Long get() = octetsAvant + (lecteur?.lus ?: 0L)
    override val perdus: Long get() = pertesAvant + (lecteur?.perdues ?: 0L) + pertesIci

    override fun setSampleRate(rate: Int): Double = Dsp.RTL_RATE.toDouble()

    override fun setFreqCorrection(ppm: Int) { this.ppm = ppm; commande("ppm $ppm") }

    override fun setCenterFreq(hz: Long): Long {
        if (hz != centreHz) { centreHz = hz; centreMs = horloge() }
        commande("centre $hz")
        return hz
    }

    override fun setGain(tenthDb: Int?) { gainDixiemes = tenthDb; commande("gain " + (tenthDb?.toString() ?: "auto")) }

    override fun setAgc(on: Boolean) {}

    override fun read(buf: ByteArray, timeoutMs: Int): Int {
        val b = file.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS) ?: return if (ouvert) 0 else -1
        val n = minOf(b.size, buf.size) and 1.inv()
        for (k in 0 until n) buf[k] = ((b[k] + 32768) shr 8).toByte()
        return n
    }

    override fun read16(buf: ShortArray, timeoutMs: Int): Int {
        val b = file.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS) ?: return if (ouvert || file.isNotEmpty()) 0 else -1
        val n = minOf(b.size, buf.size) and 1.inv()
        System.arraycopy(b, 0, buf, 0, n)
        return n
    }

    private fun commande(c: String) {
        val e = ecrivain ?: return
        runCatching { e.texte(c) }
    }

    /** The request with the settings now: a connection taken up again asks the same. */
    private fun requete(): String = chemin + "&etages=" + etages + (if (centreHz > 0) "&centre=$centreHz" else "") +
        "&gain=" + (gainDixiemes?.toString() ?: "auto") + "&ppm=" + ppm

    /** One connection: the request, the answer, the preamble. Throws with the node's reason. */
    private fun ouvreLiaison() {
        val s = Socket()
        try {
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(hote, port), attenteMs)
            s.soTimeout = maxOf(attenteMs, 5_000)
            s.getOutputStream().write(("GET " + requete() + " HTTP/1.1\r\nHost: $hote\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
            s.getOutputStream().flush()
            val i = s.getInputStream()
            val statut = ligne(i)
            val code = statut.split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("le node ne répond pas en HTTP")
            var longueur = -1
            while (true) {
                val l = ligne(i)
                if (l.isEmpty()) break
                if (l.lowercase().startsWith("content-length:")) longueur = l.substringAfter(':').trim().toIntOrNull() ?: -1
            }
            if (code == 403) throw IOException("le node refuse la clé : l'associer à nouveau avec son code")
            if (code == 404) throw IOException("ce SatMe-Node ne sait pas prêter son SDR : le mettre à jour")
            if (code != 200) {
                val corps = if (longueur in 1..65_536) ByteArray(longueur).also { FluxNoeud.litTout(i, it, longueur) } else ByteArray(0)
                val e = runCatching { JSONObject(String(corps, Charsets.UTF_8)).optString("erreur") }.getOrNull()
                throw IOException(e?.ifBlank { null } ?: "le node répond $code")
            }
            val p = FluxNoeud.litPreambule(i)
            preambule = p
            lecteur?.let { l -> octetsAvant += l.lus; pertesAvant += l.perdues }
            lecteur = FluxNoeud.Lecteur(i)
            ecrivain = FluxNoeud.Ecrivain(s.getOutputStream(), horloge)
            socket = s
            connectee = true
        } catch (x: Exception) {
            runCatching { s.close() }
            throw if (x is IOException) x else IOException(x.message ?: "node injoignable")
        }
    }

    private fun ligne(i: InputStream): String {
        val b = StringBuilder()
        while (b.length < 4096) {
            val c = i.read()
            if (c < 0) throw IOException("le node a fermé la connexion")
            if (c == '\n'.code) break
            if (c != '\r'.code) b.append(c.toChar())
        }
        return b.toString()
    }

    private fun demarreFils() {
        Thread({ boucle() }, "satme-noeud-sdr-rx").apply { isDaemon = true; start() }
        Thread({ vie() }, "satme-noeud-sdr-vie").apply { isDaemon = true; start() }
    }

    /** Frames read, brought up to the reception's rate; the connection taken up again when lost. */
    private fun boucle() {
        val u = InterpolateurDemiBande(etages)
        var sortie = ShortArray(BLOC * (1 shl etages))
        var attente = 0L
        while (ouvert) {
            val l = lecteur
            if (connectee && l != null) {
                try {
                    val t = l.lit()
                    when (t.type) {
                        FluxNoeud.IQ -> {
                            // Still the old centre a moment after a retune: the samples are not where the reception thinks.
                            if (t.centreHz != centreHz && centreHz > 0 && horloge() - centreMs < TRANSIT_MS) { enTransit++; continue }
                            if (sortie.size < t.n * (1 shl etages)) sortie = ShortArray(t.n * (1 shl etages))
                            val n = u.traite(t.iq!!, t.n, sortie)
                            var k = 0
                            while (k < n) {
                                val m = minOf(BLOC, n - k)
                                val b = sortie.copyOfRange(k, k + m)
                                if (!file.offer(b)) { file.poll(); pertesIci++; file.offer(b) }
                                k += m
                            }
                        }
                        FluxNoeud.TEXTE -> runCatching { JSONObject(t.texte!!) }.getOrNull()?.let { j ->
                            avertissementNoeud = j.optString("avertissement")
                            j.optString("erreur").takeIf { it.isNotBlank() }?.let { e -> erreurNoeud(e) }
                        }
                    }
                    attente = 0L
                } catch (x: IOException) {
                    if (!ouvert) break
                    connectee = false
                    runCatching { socket?.close() }
                    ecrivain = null
                    majAvertissement("liaison avec le SatMe-Node perdue (" + (x.message ?: "coupée") + ") : reconnexion…")
                }
                continue
            }
            // Taken up again: one try a second, then every few seconds.
            try { Thread.sleep(attente.coerceIn(200L, reprisesMs)) } catch (_: InterruptedException) { break }
            if (!ouvert) break
            try {
                ouvreLiaison()
                reprises++
                majAvertissement("")
            } catch (x: IOException) {
                majAvertissement("SatMe-Node injoignable : " + (x.message ?: "") + " — nouvel essai…")
                attente = if (attente == 0L) 1_000L else minOf(attente * 2, reprisesMs)
            }
        }
    }

    private fun majAvertissement(ici: String) { avertIci = ici }

    /** The node stopped its device (unplugged, taken): said here, the reception ends. */
    private fun erreurNoeud(e: String) {
        erreur = "sur le node : $e"
        ouvert = false
        runCatching { socket?.close() }
    }

    /** « Still there » every second: the node lets its device go when the PC stays silent. */
    private fun vie() {
        while (ouvert) {
            try { Thread.sleep(VIE_MS) } catch (_: InterruptedException) { return }
            if (connectee) runCatching { ecrivain?.vie() }
        }
    }

    override fun close() {
        ouvert = false
        connectee = false
        runCatching { socket?.close() }
        file.clear()
    }

    companion object {
        const val BLOC = 16_384
        const val PROFONDEUR = 128
        const val VIE_MS = 1_000L
        /** After a retune, the frames still at the old centre are dropped this long. */
        const val TRANSIT_MS = 1_500L

        /**
         * Connects to the node's SDR at [hote]:[port] ([chemin]: the path
         * with its key); throws with the node's reason (no device chosen,
         * device held by DATV…). The stream 2^[etages] times lighter than
         * the reception's rate.
         */
        fun connecte(hote: String, port: Int, chemin: String, etages: Int = 0, centreHz: Long = 0L, gainDixiemes: Int? = null, ppm: Int = 0,
                     horloge: () -> Long = System::currentTimeMillis, attenteMs: Int = 3_000, reprisesMs: Long = 5_000L): SourceNoeudIq {
            require(etages in 0..3)
            val s = SourceNoeudIq(hote, port, chemin, etages, horloge, attenteMs, reprisesMs)
            s.centreHz = centreHz; s.gainDixiemes = gainDixiemes; s.ppm = ppm
            s.ouvreLiaison()
            s.demarreFils()
            return s
        }
    }
}
