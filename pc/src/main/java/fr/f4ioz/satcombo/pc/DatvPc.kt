/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.plateforme.Stockage
import fr.f4ioz.satcombo.datv.ClientNoeud
import fr.f4ioz.satcombo.datv.Datv
import fr.f4ioz.satcombo.datv.EtatDatv
import fr.f4ioz.satcombo.datv.ReglageDatv
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * DATV reception (QO-100's wide band transponder) with a MiniTiouner: the
 * receiver's tuner and DVB-S/S2 demodulator on USB, driven by **LongMynd**
 * (BATC's free program for it, run apart like rtl_tcp; see
 * [LONGMYND]), the pictures shown on the page.
 *
 * - **LongMynd** tunes the MiniTiouner to the intermediate frequency (the
 *   sky's minus the LNB's oscillator) at a symbol rate, searches, locks, and
 *   sends on UDP here its status ("$n,m" lines: state, MER, carrier,
 *   constellation points…) and the MPEG transport stream. It cannot be
 *   retuned while it runs: a new frequency or rate starts it again.
 * - **The transport stream** goes to ffmpeg, which makes JPEG pictures for
 *   the page, to ffplay for the sound on this PC, and to a file when
 *   recording (.ts, as received).
 *
 * The LNB supply from the MiniTiouner (13 V or 18 V, on boards that have it)
 * stays off unless asked: an LNB powered by something else, or a splitter
 * passing DC, must not get a second supply.
 */
class DatvPc(
    private val stockage: Stockage,
    private val dossierEnregistrements: File,
    private val horloge: () -> Long = System::currentTimeMillis,
    /** False on a SatMe-Node: no picture nor sound here, the stream goes to the network's clients ([abonne]). */
    private val lecteursLocaux: Boolean = true,
) {
    @Volatile var etat = EtatDatv(); private set
    @Volatile var reglage = lisReglage(); private set

    /** The last constellation points (I, Q), signed bytes. */
    private val points = ArrayDeque<Pair<Int, Int>>()
    fun constellation(): List<Pair<Int, Int>> = synchronized(points) { points.toList() }

    /** The station card and chat lines found in the stream (forgotten at each start). */
    val carte = fr.f4ioz.satcombo.datv.LecteurCarte(horloge)

    /** The last picture made (JPEG) and its number. */
    @Volatile var image: ByteArray? = null; private set

    private var longmynd: Process? = null
    private var ffmpeg: Process? = null
    private var ffplay: Process? = null
    private var socketEtat: DatagramSocket? = null
    private var socketTs: DatagramSocket? = null
    private var enregistreur: OutputStream? = null
    @Volatile private var generation = 0

    // ------------------------------------------------------------ settings

    private fun lisReglage() = ReglageDatv(
        cielKhz = stockage.getLong("datv_ciel_khz", Datv.BALISE_KHZ),
        srKs = stockage.getInt("datv_sr", 1500),
        olKhz = stockage.getLong("datv_ol_khz", 9_750_000L),
        entreeB = stockage.getBoolean("datv_entree_b", false),
        lnb = "",   // never kept: the supply is asked for each time
        son = stockage.getBoolean("datv_son", true))

    private fun garde(r: ReglageDatv) {
        stockage.edit().putLong("datv_ciel_khz", r.cielKhz).putInt("datv_sr", r.srKs).putLong("datv_ol_khz", r.olKhz)
            .putBoolean("datv_entree_b", r.entreeB).putBoolean("datv_son", r.son).apply()
    }

    /** Where LongMynd is: the path chosen, else the usual places. */
    var chemin: String
        get() = stockage.getString("datv_longmynd", "") ?: ""
        set(v) { stockage.edit().putString("datv_longmynd", v.trim()).apply() }

    fun programme(): String? {
        val home = System.getProperty("user.home")
        val l = listOf(chemin) + (System.getenv("PATH").orEmpty().split(File.pathSeparatorChar).map { "$it/longmynd" }) +
            listOf("$home/longmynd/longmynd", "$home/SatMe-atelier/longmynd/longmynd", "/usr/local/bin/longmynd", "/opt/longmynd/longmynd")
        return l.filter { it.isNotBlank() }.firstOrNull { File(it).let { f -> f.isFile && f.canExecute() } }
    }

    // ------------------------------------------------------------ start, stop

    /** Starts receiving with [r] (LongMynd started again when it ran); null when started, else why not. */
    @Synchronized
    fun demarre(r: ReglageDatv = reglage): String? {
        arreteIci()
        r.refus()?.let { return it }
        if (distant) return demarreDistant(r)
        // LongMynd is Linux's: on Windows the MiniTiouner is driven through a SatMe-Node.
        if (OutilsPc.windows) return "sous Windows, le MiniTiouner se pilote par un SatMe-Node (Raspberry Pi) : donner son adresse et son code ci-dessous"
        val prog = programme() ?: return "LongMynd introuvable : le compiler (github.com/BritishAmateurTelevisionClub/longmynd) et donner son chemin"
        reglage = r; garde(r)
        val gen = ++generation
        val sEtat = DatagramSocket(0, InetAddress.getLoopbackAddress()).also { socketEtat = it }
        val sTs = DatagramSocket(0, InetAddress.getLoopbackAddress()).also { it.receiveBufferSize = 4 shl 20; socketTs = it }
        val cmd = commande(prog, r, sTs.localPort, sEtat.localPort)
        val p = runCatching {
            ProcessBuilder(cmd).directory(File(prog).parentFile).redirectErrorStream(true).redirectOutput(JOURNAL).start()
        }.getOrElse { arreteIci(); return "LongMynd ne démarre pas : " + (it.message ?: "") }
        longmynd = p
        synchronized(points) { points.clear() }
        carte.oublie()
        etat = EtatDatv(actif = true, depuis = horloge())
        thread(isDaemon = true, name = "satme-datv-etat") { litEtat(sEtat, gen) }
        thread(isDaemon = true, name = "satme-datv-ts") { litTs(sTs, gen) }
        thread(isDaemon = true, name = "satme-datv-garde") {
            // LongMynd gone by itself (no MiniTiouner, no access to it): say why, from its own words.
            p.waitFor()
            if (generation == gen) {
                etat = etat.copy(actif = false, erreur = pourquoiArret())
                arreteFlux()
            }
        }
        return null
    }

    /** Stops receiving, here and on the node receiving for us. */
    @Synchronized
    fun arrete() {
        val etaitActif = etat.actif
        arreteIci()
        if (distant && etaitActif) thread(isDaemon = true) { client.arrete() }
    }

    @Synchronized
    private fun arreteIci() {
        generation++
        longmynd?.let { p -> p.destroy(); if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly() }
        longmynd = null
        arreteFlux()
        runCatching { socketEtat?.close() }; runCatching { socketTs?.close() }
        socketEtat = null; socketTs = null
        etat = etat.copy(actif = false, kbps = 0)
    }

    private fun arreteFlux() {
        (listOf(ffmpeg, ffplay) + vlcs).forEach { p -> p?.let { runCatching { it.outputStream.close() }; it.destroy() } }
        ffmpeg = null; ffplay = null; vlcs.clear(); typesLecteurs = null
        enregistre(false)
    }

    /** LongMynd's last words, made readable (the usual causes said plainly). */
    private fun pourquoiArret(): String = pourquoi(runCatching { JOURNAL.readLines() }.getOrDefault(emptyList()))

    // ------------------------------------------------------------ LongMynd's status

    private fun litEtat(s: DatagramSocket, gen: Int) {
        val b = ByteArray(2048)
        var iEnAttente: Int? = null
        val flux = ArrayList<Pair<Int, Int>>()
        var pidEnAttente: Int? = null
        while (generation == gen && !s.isClosed) {
            val d = DatagramPacket(b, b.size)
            if (runCatching { s.receive(d) }.isFailure) break
            for ((n, v) in Datv.lignes(String(b, 0, d.length, Charsets.ISO_8859_1))) {
                var e = etat
                when (n) {
                    1 -> { val st = v.toIntOrNull() ?: 0; if (st < 3 && e.etat >= 3) flux.clear(); e = e.copy(etat = st, erreur = "") }
                    6 -> e = e.copy(fiTrouveeKhz = v.toLongOrNull() ?: 0L)
                    7 -> iEnAttente = Datv.signe(v)
                    8 -> { val i = iEnAttente; val q = Datv.signe(v)
                        if (i != null && q != null) synchronized(points) { points.addLast(i to q); while (points.size > 400) points.removeFirst() }
                        iEnAttente = null }
                    9 -> e = e.copy(srTrouveKs = ((v.toLongOrNull() ?: 0L) / 1000L).toInt())
                    11 -> e = e.copy(berPct = v.toIntOrNull()?.let { it / 100.0 })
                    12 -> e = e.copy(merDb = v.toIntOrNull()?.let { it / 10.0 })
                    13 -> e = e.copy(fournisseur = v.trim())
                    14 -> e = e.copy(service = v.trim())
                    15 -> e = e.copy(nulsPct = v.toIntOrNull() ?: 0)
                    16 -> pidEnAttente = v.toIntOrNull()
                    17 -> { val pid = pidEnAttente; val t = v.toIntOrNull()
                        if (pid != null && t != null) { flux.removeAll { it.first == pid }; flux += pid to t; e = e.copy(flux = flux.toList()) }
                        pidEnAttente = null
                        relanceSiAutreCodage(flux.map { it.second }.toSet()) }
                    18 -> e = e.copy(modcod = Datv.modcod(v.toIntOrNull() ?: -1, e.etat))
                    19 -> e = e.copy(trames = v.trim() == "1")
                    20 -> e = e.copy(pilotes = v.trim() == "1")
                    26 -> e = e.copy(agc1 = v.toIntOrNull() ?: 0)
                    27 -> e = e.copy(agc2 = v.toIntOrNull() ?: 0)
                }
                etat = e
            }
        }
    }

    // ------------------------------------------------------------ the transport stream

    private fun litTs(s: DatagramSocket, gen: Int) {
        val b = ByteArray(65_536)
        val r = Reception(gen)
        while (generation == gen && !s.isClosed) {
            val d = DatagramPacket(b, b.size)
            if (runCatching { s.receive(d) }.isFailure) break
            r.bloc(b.copyOf(d.length))
        }
    }

    /**
     * Where each block of the stream goes, whatever its source (LongMynd
     * here, or a node): the recording, the players here, the node's clients.
     */
    private inner class Reception(private val gen: Int) {
        private val file = ArrayBlockingQueue<ByteArray>(512)
        private var octets = 0L
        private var t0 = horloge()

        init {
            // One thread writes to the players: a slow one never holds the source.
            if (lecteursLocaux) thread(isDaemon = true, name = "satme-datv-lecteurs") {
                while (generation == gen) {
                    val bloc = file.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    lecteurs().forEach { o -> runCatching { o.write(bloc); o.flush() } }
                }
            }
        }

        fun bloc(bloc: ByteArray) {
            octets += bloc.size
            runCatching { carte.bloc(bloc) }
            enregistreur?.let { o -> runCatching { o.write(bloc) } }
            if (lecteursLocaux) {
                if (ffmpeg == null) demarreLecteurs(gen)
                if (!file.offer(bloc)) { file.poll(); file.offer(bloc) }
            }
            for (q in abonnes) if (!q.offer(bloc)) { q.poll(); q.offer(bloc) }
            val t = horloge()
            if (t - t0 >= 1000) { etat = etat.copy(kbps = (octets * 8 / (t - t0)).toInt()); octets = 0; t0 = t }
        }
    }

    // ------------------------------------------------------------ a SatMe-Node's clients

    /** Each network client its own queue: a slow one loses blocks, never holds the others. */
    private val abonnes = java.util.concurrent.CopyOnWriteArrayList<ArrayBlockingQueue<ByteArray>>()

    fun abonne(): ArrayBlockingQueue<ByteArray> = ArrayBlockingQueue<ByteArray>(1024).also { abonnes += it }

    fun desabonne(q: ArrayBlockingQueue<ByteArray>) { abonnes -= q }

    // ------------------------------------------------------------ a SatMe-Node receiving for us

    /** The SatMe-Node receiving for this PC, when one is joined (address, key, name kept). */
    val client = ClientNoeud(stockage)
    val noeud: String get() = client.adresse
    val nomNoeud: String get() = client.nom
    val distant: Boolean get() = client.associe

    /** Joins a SatMe-Node with its code (an empty address: the MiniTiouner on this PC again); null when done. */
    fun rejoins(adresse: String, code: String): String? { arrete(); return client.rejoins(adresse, code) }

    /** Receiving through the node: it starts LongMynd there; its status and stream come here. */
    private fun demarreDistant(r: ReglageDatv): String? {
        reglage = r; garde(r)
        client.demarre(r)?.let { return it }
        val gen = ++generation
        synchronized(points) { points.clear() }
        carte.oublie()
        etat = EtatDatv(actif = true, depuis = horloge())
        thread(isDaemon = true, name = "satme-datv-noeud-etat") { suisNoeud(gen) }
        thread(isDaemon = true, name = "satme-datv-noeud-ts") { litTsNoeud(gen) }
        return null
    }

    /** The node's status, twice a second: its words become ours (the stream's rate and the recording stay ours). */
    private fun suisNoeud(gen: Int) {
        var echecs = 0
        while (generation == gen) {
            runCatching { client.etat() }.onSuccess { j ->
                echecs = 0
                synchronized(points) { points.clear(); Datv.points(j).forEach { points.addLast(it) } }
                etat = Datv.etatDe(j, etat)
                if (!j.optBoolean("actif") && j.optString("erreur").isNotBlank()) {
                    // LongMynd stopped there: said here, the stream closed.
                    synchronized(this) { if (generation == gen) { arreteIci(); etat = etat.copy(erreur = "sur le node : " + j.optString("erreur")) } }
                    return
                }
            }.onFailure { if (++echecs >= 10) etat = etat.copy(erreur = "node silencieux : " + (it.message ?: "")) }
            Thread.sleep(400)
        }
    }

    /** The node's stream over HTTP, taken up again when it breaks while receiving. */
    private fun litTsNoeud(gen: Int) {
        val r = Reception(gen)
        val b = ByteArray(1316 * 8)
        while (generation == gen) {
            runCatching {
                client.httpFlux.newCall(okhttp3.Request.Builder().url(client.urlFlux()).build()).execute().use { rep ->
                    val e = rep.body?.byteStream() ?: return@use
                    while (generation == gen) {
                        val n = e.read(b)
                        if (n < 0) break
                        if (n > 0) r.bloc(b.copyOf(n))
                    }
                }
            }
            if (generation == gen) Thread.sleep(1000)
        }
    }

    private fun lecteurs(): List<OutputStream> {
        vlcs.removeAll { !it.isAlive }
        return listOfNotNull(ffmpeg?.outputStream, ffplay?.outputStream) + vlcs.map { it.outputStream }
    }

    /** The streams' types the players were started on. */
    @Volatile private var typesLecteurs: Set<Int>? = null

    /**
     * The station changed its coding (H.264 → H.265, another sound): the
     * players, started on the old one, would decode nothing more. Stopped,
     * they start again with the next packets.
     */
    @Synchronized
    private fun relanceSiAutreCodage(types: Set<Int>) {
        val avant = typesLecteurs
        if (ffmpeg == null || types.isEmpty()) return
        if (avant == null) { typesLecteurs = types; return }
        // A type not seen when they started (the list rebuilt piece by piece after a lost lock is no change).
        if (avant.containsAll(types)) return
        typesLecteurs = types
        listOfNotNull(ffmpeg, ffplay).forEach { p -> runCatching { p.outputStream.close() }; p.destroy() }
        ffmpeg = null; ffplay = null
    }

    /** ffmpeg for the page's pictures, ffplay for the sound: started with the first packets, stopped with LongMynd. */
    @Synchronized
    private fun demarreLecteurs(gen: Int) {
        if (generation != gen || ffmpeg != null) return
        val ff = RenduPc.ffmpeg() ?: run { etat = etat.copy(erreur = "ffmpeg introuvable : pas d'image (" + OutilsPc.conseil("ffmpeg") + ")"); return }
        val p = ProcessBuilder(ff, "-loglevel", "error", "-fflags", "nobuffer", "-flags", "low_delay", "-probesize", "200000", "-analyzeduration", "1000000",
            "-f", "mpegts", "-i", "pipe:0", "-map", "0:v:0", "-an", "-vf", "scale=-2:'min(720,ih)'", "-q:v", "5", "-r", "25",
            "-f", "image2pipe", "-c:v", "mjpeg", "pipe:1")
            .redirectError(File(System.getProperty("java.io.tmpdir"), "satme-datv-ffmpeg.log")).start()
        ffmpeg = p
        thread(isDaemon = true, name = "satme-datv-images") { litImages(p.inputStream, gen) }
        if (reglage.son) {
            val play = OutilsPc.trouve("ffplay") ?: File(File(ff).parentFile, "ffplay").takeIf { it.canExecute() }?.path ?: "ffplay"
            ffplay = runCatching {
                ProcessBuilder(play, "-loglevel", "error", "-nodisp", "-fflags", "nobuffer", "-flags", "low_delay", "-probesize", "200000", "-analyzeduration", "1000000", "-f", "mpegts", "-i", "pipe:0")
                    .redirectError(File(System.getProperty("java.io.tmpdir"), "satme-datv-ffplay.log")).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            }.getOrNull()
        }
    }

    /** JPEG pictures one after the other on ffmpeg's output: each kept whole, from its start to its end marker. */
    private fun litImages(e: InputStream, gen: Int) {
        val d = Decoupe()
        val b = ByteArray(65_536)
        while (generation == gen) {
            val n = runCatching { e.read(b) }.getOrDefault(-1)
            if (n < 0) break
            d.ajoute(b, n) { img -> image = img; etat = etat.copy(images = etat.images + 1) }
        }
    }

    /** Cuts a byte stream of JPEGs back into pictures (start FFD8, end FFD9). */
    class Decoupe {
        private val tampon = java.io.ByteArrayOutputStream()
        private var precedent = -1
        private var dedans = false
        fun ajoute(b: ByteArray, n: Int, image: (ByteArray) -> Unit) {
            for (i in 0 until n) {
                val c = b[i].toInt() and 0xFF
                if (!dedans) {
                    if (precedent == 0xFF && c == 0xD8) { dedans = true; tampon.reset(); tampon.write(0xFF); tampon.write(0xD8) }
                } else {
                    tampon.write(c)
                    if (precedent == 0xFF && c == 0xD9) { dedans = false; image(tampon.toByteArray()); tampon.reset() }
                    else if (tampon.size() > 8 shl 20) { dedans = false; tampon.reset() }
                }
                precedent = c
            }
        }
    }

    // ------------------------------------------------------------ recording

    /** The transport stream as received, to a .ts file (any player reads it); the file's name, or "" once stopped. */
    @Synchronized
    fun enregistre(on: Boolean): String {
        enregistreur?.let { runCatching { it.close() } }
        enregistreur = null
        if (!on || !etat.actif) { etat = etat.copy(enregistrement = ""); return "" }
        dossierEnregistrements.mkdirs()
        val nom = "SatMe_DATV_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(horloge())) + "_" + (reglage.cielKhz / 1000.0).toString().replace('.', '-') + ".ts"
        enregistreur = File(dossierEnregistrements, nom).outputStream().buffered(1 shl 20)
        etat = etat.copy(enregistrement = nom)
        return nom
    }

    /** The sound on this PC, or not (from the next start). */
    fun regleSon(on: Boolean) { reglage = reglage.copy(son = on); garde(reglage) }

    /** The stream in VLC too (full quality, its own window), fed as the others. */
    @Synchronized
    fun ouvreVlc(): String? {
        if (!etat.actif) return "réception arrêtée"
        val vlc = OutilsPc.trouve("vlc") ?: return "VLC introuvable : " + (if (OutilsPc.windows) "l'installer (videolan.org)" else OutilsPc.conseil("vlc"))
        val p = runCatching { ProcessBuilder(vlc, "--meta-title", "SatMe DATV", "fd://0").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start() }
            .getOrElse { return "VLC ne démarre pas" }
        vlcs += p
        return null
    }
    private val vlcs = java.util.concurrent.CopyOnWriteArrayList<Process>()

    companion object {
        /**
         * Why LongMynd stopped, from its log: its "ERROR:" lines (its last
         * line only says it exited cleanly), the usual causes said plainly.
         */
        fun pourquoi(journal: List<String>): String {
            val l = journal.map { it.trim() }.filter { it.isNotEmpty() }
            val erreurs = l.filter { it.startsWith("ERROR", true) || it.contains("LIBUSB_ERROR", true) }
            val t = (erreurs.ifEmpty { l.takeLast(6) }).joinToString(" ")
            // libusb says it before LongMynd's own words, which then wrongly ask whether it is plugged in.
            val acces = l.any { it.contains("errno=13") || it.contains("requires write access", true) }
            return when {
                acces || t.contains("LIBUSB_ERROR_ACCESS", true) || t.contains("permission", true) ->
                    "pas d'accès au MiniTiouner : il faut une règle udev pour lui (voir la page SatMe-PC, DATV), puis le débrancher et le rebrancher"
                t.contains("Unable to open device", true) || t.contains("plugged", true) || t.contains("not found", true) ->
                    "MiniTiouner introuvable : est-il branché ?"
                erreurs.isNotEmpty() -> "LongMynd : " + erreurs.joinToString(" · ") { it.removePrefix("ERROR:").trim() }
                else -> "LongMynd s'est arrêté : " + (l.lastOrNull() ?: "sans un mot")
            }
        }


        val LONGMYND = "LongMynd (BATC, GPL-3), program run apart"
        private val JOURNAL = File(System.getProperty("java.io.tmpdir"), "satme-longmynd.log")

        /** LongMynd's command line for [r]: TS and status to us on UDP, retried every 5 s without packets. */
        fun commande(prog: String, r: ReglageDatv, portTs: Int, portEtat: Int): List<String> =
            listOf(prog, "-i", "127.0.0.1", portTs.toString(), "-I", "127.0.0.1", portEtat.toString(), "-r", "5000") +
                (if (r.entreeB) listOf("-w") else emptyList()) +
                (if (r.lnb == "v" || r.lnb == "h") listOf("-p", r.lnb) else emptyList()) +
                listOf(r.fiKhz.toString(), r.srKs.toString())
    }
}
