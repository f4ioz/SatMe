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
) {
    /** What to receive: sky frequency, symbol rate, the LNB's oscillator, the NIM's input, its LNB supply. */
    data class Reglage(
        val cielKhz: Long = BALISE_KHZ,
        val srKs: Int = 1500,
        val olKhz: Long = 9_750_000L,
        /** The NIM's bottom input rather than the top one. */
        val entreeB: Boolean = false,
        /** LNB supply: "" off, "v" 13 V, "h" 18 V. */
        val lnb: String = "",
        val son: Boolean = true,
    ) {
        val fiKhz: Long get() = cielKhz - olKhz
    }

    data class Etat(
        val actif: Boolean = false,
        /** LongMynd's state: 0 starting, 1 searching, 2 headers, 3 DVB-S, 4 DVB-S2. */
        val etat: Int = 0,
        val fiTrouveeKhz: Long = 0L,
        val srTrouveKs: Int = 0,
        val merDb: Double? = null,
        val modcod: String = "",
        val fournisseur: String = "",
        val service: String = "",
        val nulsPct: Int = 0,
        /** Elementary streams: PID and type (0x1B H.264, 0x24 H.265, 0x03/0x04 MPEG audio, 0x0F AAC…). */
        val flux: List<Pair<Int, Int>> = emptyList(),
        val agc1: Int = 0, val agc2: Int = 0,
        val berPct: Double? = null,
        val trames: Boolean? = null, val pilotes: Boolean? = null,
        val kbps: Int = 0,
        val images: Long = 0L,
        val enregistrement: String = "",
        val erreur: String = "",
        val depuis: Long = 0L,
    ) {
        val verrouille: Boolean get() = etat >= 3
        val libelle: String get() = when (etat) {
            0 -> "démarrage"; 1 -> "recherche"; 2 -> "en-têtes trouvés"; 3 -> "accroché DVB-S"; 4 -> "accroché DVB-S2"; else -> "?"
        }
    }

    @Volatile var etat = Etat(); private set
    @Volatile var reglage = lisReglage(); private set

    /** The last constellation points (I, Q), signed bytes. */
    private val points = ArrayDeque<Pair<Int, Int>>()
    fun constellation(): List<Pair<Int, Int>> = synchronized(points) { points.toList() }

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

    private fun lisReglage() = Reglage(
        cielKhz = stockage.getLong("datv_ciel_khz", BALISE_KHZ),
        srKs = stockage.getInt("datv_sr", 1500),
        olKhz = stockage.getLong("datv_ol_khz", 9_750_000L),
        entreeB = stockage.getBoolean("datv_entree_b", false),
        lnb = "",   // never kept: the supply is asked for each time
        son = stockage.getBoolean("datv_son", true))

    private fun garde(r: Reglage) {
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
    fun demarre(r: Reglage = reglage): String? {
        arrete()
        if (r.fiKhz !in FI_MIN_KHZ..FI_MAX_KHZ)
            return "fréquence intermédiaire hors de portée du MiniTiouner : %.3f MHz (ciel %.3f − OL %.3f), il faut 144 à 2450 MHz".format(
                java.util.Locale.FRANCE, r.fiKhz / 1000.0, r.cielKhz / 1000.0, r.olKhz / 1000.0)
        if (r.srKs !in 33..27_500) return "débit de symboles hors de portée : ${r.srKs} kS/s"
        val prog = programme() ?: return "LongMynd introuvable : le compiler (github.com/BritishAmateurTelevisionClub/longmynd) et donner son chemin"
        reglage = r; garde(r)
        val gen = ++generation
        val sEtat = DatagramSocket(0, InetAddress.getLoopbackAddress()).also { socketEtat = it }
        val sTs = DatagramSocket(0, InetAddress.getLoopbackAddress()).also { it.receiveBufferSize = 4 shl 20; socketTs = it }
        val cmd = commande(prog, r, sTs.localPort, sEtat.localPort)
        val p = runCatching {
            ProcessBuilder(cmd).directory(File(prog).parentFile).redirectErrorStream(true).redirectOutput(JOURNAL).start()
        }.getOrElse { arrete(); return "LongMynd ne démarre pas : " + (it.message ?: "") }
        longmynd = p
        synchronized(points) { points.clear() }
        etat = Etat(actif = true, depuis = horloge())
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

    @Synchronized
    fun arrete() {
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
        ffmpeg = null; ffplay = null; vlcs.clear()
        enregistre(false)
    }

    /** LongMynd's last words, made readable (the usual causes said plainly). */
    private fun pourquoiArret(): String {
        val l = runCatching { JOURNAL.readLines() }.getOrDefault(emptyList()).map { it.trim() }.filter { it.isNotEmpty() }
        val t = l.takeLast(6).joinToString(" ")
        return when {
            t.contains("LIBUSB_ERROR_ACCESS", true) || t.contains("permission", true) ->
                "pas d'accès au MiniTiouner : installer la règle udev de LongMynd (sudo cp minitiouner.rules /etc/udev/rules.d/), puis le débrancher et le rebrancher"
            t.contains("find", true) && t.contains("usb", true) || t.contains("not found", true) || t.contains("no minitiouner", true) ->
                "MiniTiouner introuvable : est-il branché ?"
            else -> "LongMynd s'est arrêté : " + (l.lastOrNull() ?: "sans un mot")
        }
    }

    // ------------------------------------------------------------ LongMynd's status

    private fun litEtat(s: DatagramSocket, gen: Int) {
        val b = ByteArray(2048)
        var iEnAttente: Int? = null
        val flux = ArrayList<Pair<Int, Int>>()
        var pidEnAttente: Int? = null
        while (generation == gen && !s.isClosed) {
            val d = DatagramPacket(b, b.size)
            if (runCatching { s.receive(d) }.isFailure) break
            for ((n, v) in lignes(String(b, 0, d.length, Charsets.ISO_8859_1))) {
                var e = etat
                when (n) {
                    1 -> { val st = v.toIntOrNull() ?: 0; if (st < 3 && e.etat >= 3) flux.clear(); e = e.copy(etat = st, erreur = "") }
                    6 -> e = e.copy(fiTrouveeKhz = v.toLongOrNull() ?: 0L)
                    7 -> iEnAttente = signe(v)
                    8 -> { val i = iEnAttente; val q = signe(v)
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
                        pidEnAttente = null }
                    18 -> e = e.copy(modcod = modcod(v.toIntOrNull() ?: -1, e.etat))
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
        val file = ArrayBlockingQueue<ByteArray>(512)
        var octets = 0L; var t0 = horloge()
        // One thread writes to the players: a slow one never holds the socket.
        thread(isDaemon = true, name = "satme-datv-lecteurs") {
            while (generation == gen) {
                val bloc = file.poll(500, TimeUnit.MILLISECONDS) ?: continue
                lecteurs().forEach { o -> runCatching { o.write(bloc); o.flush() } }
            }
        }
        while (generation == gen && !s.isClosed) {
            val d = DatagramPacket(b, b.size)
            if (runCatching { s.receive(d) }.isFailure) break
            val bloc = b.copyOf(d.length)
            octets += bloc.size
            enregistreur?.let { o -> runCatching { o.write(bloc) } }
            if (ffmpeg == null) demarreLecteurs(gen)
            if (!file.offer(bloc)) { file.poll(); file.offer(bloc) }
            val t = horloge()
            if (t - t0 >= 1000) { etat = etat.copy(kbps = (octets * 8 / (t - t0)).toInt()); octets = 0; t0 = t }
        }
    }

    private fun lecteurs(): List<OutputStream> {
        vlcs.removeAll { !it.isAlive }
        return listOfNotNull(ffmpeg?.outputStream, ffplay?.outputStream) + vlcs.map { it.outputStream }
    }

    /** ffmpeg for the page's pictures, ffplay for the sound: started with the first packets, stopped with LongMynd. */
    @Synchronized
    private fun demarreLecteurs(gen: Int) {
        if (generation != gen || ffmpeg != null) return
        val ff = RenduPc.ffmpeg() ?: run { etat = etat.copy(erreur = "ffmpeg introuvable : pas d'image (sudo apt install ffmpeg)"); return }
        val p = ProcessBuilder(ff, "-loglevel", "error", "-fflags", "nobuffer", "-flags", "low_delay", "-probesize", "200000", "-analyzeduration", "1000000",
            "-f", "mpegts", "-i", "pipe:0", "-map", "0:v:0", "-an", "-vf", "scale=-2:'min(720,ih)'", "-q:v", "5", "-r", "25",
            "-f", "image2pipe", "-c:v", "mjpeg", "pipe:1")
            .redirectError(File(System.getProperty("java.io.tmpdir"), "satme-datv-ffmpeg.log")).start()
        ffmpeg = p
        thread(isDaemon = true, name = "satme-datv-images") { litImages(p.inputStream, gen) }
        if (reglage.son) {
            val play = File(File(ff).parentFile, "ffplay").takeIf { it.canExecute() }?.path ?: "ffplay"
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
        val vlc = listOf("/usr/bin/vlc", "/snap/bin/vlc", "/usr/local/bin/vlc").firstOrNull { File(it).canExecute() } ?: return "VLC introuvable"
        val p = runCatching { ProcessBuilder(vlc, "--meta-title", "SatMe DATV", "fd://0").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start() }
            .getOrElse { return "VLC ne démarre pas" }
        vlcs += p
        return null
    }
    private val vlcs = java.util.concurrent.CopyOnWriteArrayList<Process>()

    companion object {
        /** QO-100's wide band beacon: 10491.5 MHz, 1.5 MS/s DVB-S2 QPSK. */
        const val BALISE_KHZ = 10_491_500L
        /** What the MiniTiouner's tuner covers. */
        const val FI_MIN_KHZ = 144_000L
        const val FI_MAX_KHZ = 2_450_000L
        /** The symbol rates of QO-100's wide band. */
        val DEBITS = listOf(125, 250, 333, 500, 1000, 1500, 2000)
        /** The narrow channels, every 500 kHz from 10492.75 to 10499.25 MHz. */
        val CANAUX_KHZ = (0..13).map { 10_492_750L + it * 500L }

        val LONGMYND = "LongMynd (BATC, GPL-3), program run apart"
        private val JOURNAL = File(System.getProperty("java.io.tmpdir"), "satme-longmynd.log")

        /** LongMynd's command line for [r]: TS and status to us on UDP, retried every 5 s without packets. */
        fun commande(prog: String, r: Reglage, portTs: Int, portEtat: Int): List<String> =
            listOf(prog, "-i", "127.0.0.1", portTs.toString(), "-I", "127.0.0.1", portEtat.toString(), "-r", "5000") +
                (if (r.entreeB) listOf("-w") else emptyList()) +
                (if (r.lnb == "v" || r.lnb == "h") listOf("-p", r.lnb) else emptyList()) +
                listOf(r.fiKhz.toString(), r.srKs.toString())

        /** "$n,m" lines (one or several per datagram, ended by CR). */
        fun lignes(t: String): List<Pair<Int, String>> =
            t.split('$').mapNotNull { m ->
                val l = m.trimEnd('\r', '\n', '\u0000')
                val i = l.indexOf(',')
                if (i <= 0) null else l.substring(0, i).toIntOrNull()?.let { it to l.substring(i + 1) }
            }

        /** A constellation point is a signed byte, sent as 0..255 or sign-extended to 32 bits: its low byte. */
        fun signe(v: String): Int? = v.trim().toLongOrNull()?.let { (it and 0xFF).toByte().toInt() }

        private val S2 = listOf("DummyPL", "QPSK 1/4", "QPSK 1/3", "QPSK 2/5", "QPSK 1/2", "QPSK 3/5", "QPSK 2/3", "QPSK 3/4", "QPSK 4/5",
            "QPSK 5/6", "QPSK 8/9", "QPSK 9/10", "8PSK 3/5", "8PSK 2/3", "8PSK 3/4", "8PSK 5/6", "8PSK 8/9", "8PSK 9/10",
            "16APSK 2/3", "16APSK 3/4", "16APSK 4/5", "16APSK 5/6", "16APSK 8/9", "16APSK 9/10",
            "32APSK 3/4", "32APSK 4/5", "32APSK 5/6", "32APSK 8/9", "32APSK 9/10")
        private val S1 = listOf("QPSK 1/2", "QPSK 2/3", "QPSK 3/4", "QPSK 5/6", "QPSK 6/7", "QPSK 7/8")

        /** The MODCOD in words, DVB-S2's table when locked on S2, DVB-S's otherwise. */
        fun modcod(n: Int, etat: Int): String = (if (etat == 3) S1 else S2).getOrNull(n).orEmpty()

        /** What an elementary stream type is. */
        fun typeFlux(t: Int): String = when (t) {
            0x01, 0x02 -> "MPEG-2"; 0x1B -> "H.264"; 0x24 -> "H.265"; 0x03, 0x04 -> "MPEG audio"; 0x0F -> "AAC"; 0x11 -> "AAC LATM"
            0x81 -> "AC-3"; 0x06 -> "données"; else -> "type 0x" + t.toString(16)
        }
    }
}
