/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sdr

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * `bladeRF-cli` (libbladeRF's tool) in interactive mode, driven line by
 * line on its standard input: what [BladerfSdr] and [BladerfEmission] use.
 * The samples go through a named pipe ([fifo]), in SC16 Q11 (signed 16-bit
 * little-endian I then Q, ±2048).
 *
 * Every line sent passes [verifie] first: a receiver's session cannot send
 * a TX command, nor a transmitter's anything it did not plan.
 */
class SessionBladerf(
    programme: String,
    serie: String,
    private val verifie: (String) -> Unit,
    lanceur: (List<String>) -> Process = { ProcessBuilder(it).redirectErrorStream(true).start() },
) {
    val processus: Process
    private val entree: OutputStream
    private val lignes = LinkedBlockingQueue<String>()
    private val journal = ArrayDeque<String>()
    /** The first line meaning the tool cannot go on, in French. */
    @Volatile var fatal: String? = null; private set
    /** The last such line, until taken ([prendErreur]). */
    @Volatile private var derniere: String? = null

    init {
        require(serie.isEmpty() || serie.matches(Bladerf.SERIE)) { "numéro de série du bladeRF invalide" }
        // Started through stdbuf, a missing program would only be its error line.
        if (programme.contains(java.io.File.separatorChar) && !java.io.File(programme).isFile)
            throw IOException("bladeRF-cli ne démarre pas : introuvable ($programme)")
        processus = try {
            lanceur(Bladerf.commande(programme, serie))
        } catch (x: IOException) {
            throw IOException("bladeRF-cli ne démarre pas : " + (x.message ?: "introuvable"))
        } catch (x: SecurityException) {
            throw IOException("bladeRF-cli ne démarre pas : " + (x.message ?: "refusé"))
        }
        Bladerf.vivants += processus
        entree = processus.outputStream
        Thread({ lit(processus.inputStream) }, "satme-bladerf-cli").apply { isDaemon = true; start() }
    }

    private fun lit(e: InputStream) {
        runCatching {
            e.bufferedReader().forEachLine { l ->
                val t = l.trim()
                if (t.isEmpty()) return@forEachLine
                synchronized(journal) { journal.addLast(t); while (journal.size > 60) journal.removeFirst() }
                Bladerf.traduis(t)?.let { if (fatal == null) fatal = it; derniere = it }
                // The device starved of samples: counted, shown with the transmission.
                if ("underrun" in t.lowercase()) sousCharges++
                lignes.offer(t)
            }
        }
    }

    val vivant: Boolean get() = processus.isAlive

    /** The tool's « underrun » lines: the bladeRF ran out of samples to send. */
    @Volatile var sousCharges = 0
        private set

    /** The last error line since the last call, or null. */
    fun prendErreur(): String? = derniere.also { derniere = null }

    /** One command line, checked, sent. */
    @Synchronized fun envoie(commande: String) {
        verifie(commande)
        entree.write((commande + "\n").toByteArray(Charsets.US_ASCII))
        entree.flush()
    }

    /** Lines already read and not waited for: forgotten. */
    fun oublie() { lignes.clear() }

    /**
     * The first line matching [motif] within [attenteMs]; an [IOException]
     * on an error line, when the tool ends, or when nothing came.
     */
    fun attend(motif: Regex, attenteMs: Long, quoi: String): MatchResult {
        val fin = System.currentTimeMillis() + attenteMs
        while (true) {
            val reste = fin - System.currentTimeMillis()
            if (reste <= 0) throw IOException("bladeRF-cli ne répond pas ($quoi)" + if (Bladerf.stdbuf == null) " : stdbuf (coreutils) manque" else "")
            val l = lignes.poll(minOf(reste, 100L), TimeUnit.MILLISECONDS)
            if (l == null) {
                if (!processus.isAlive && lignes.isEmpty()) throw IOException(fatal ?: messageMort())
                continue
            }
            Bladerf.traduis(l)?.let { throw IOException(it) }
            motif.find(l)?.let { return it }
        }
    }

    fun messageMort(): String {
        val l = synchronized(journal) { journal.toList() }
        l.firstNotNullOfOrNull { Bladerf.traduis(it) }?.let { return it }
        val code = runCatching { processus.exitValue().toString() }.getOrDefault("?")
        return "bladeRF-cli s'est arrêté (code $code)" + (l.lastOrNull { !it.startsWith("[INFO") }?.let { " : " + it.take(160) } ?: "")
    }

    /** `quit`, its input closed, then terminated, then killed. True once it has ended. */
    fun ferme(attenteMs: Long): Boolean {
        runCatching { envoie("quit") }
        return Bladerf.termine(processus, attenteMs)
    }
}

/** What the bladeRF's tool needs everywhere: its command line, its words, its pipes. */
object Bladerf {
    val SERIE = Regex("[0-9a-fA-F]{1,32}")
    /** The bladeRF 2.0's TX gain offset: libbladeRF's overall TX gain is the attenuation (≤ 0 dB) plus this. */
    const val DECALAGE_GAIN_TX = 66

    /**
     * coreutils' stdbuf: bladeRF-cli writes its answers through stdio, fully
     * buffered into a pipe; line by line through it, else they come only at its end.
     */
    val stdbuf: String? by lazy { listOf("/usr/bin/stdbuf", "/bin/stdbuf", "/usr/local/bin/stdbuf").firstOrNull { java.io.File(it).canExecute() } }

    /** `[stdbuf -oL -eL] bladeRF-cli [-d *:serial=…] -i`: interactive, its commands on standard input, its answers line by line. */
    fun commande(programme: String, serie: String): List<String> {
        val l = ArrayList<String>()
        stdbuf?.let { l += listOf(it, "-oL", "-eL") }
        l += programme
        if (serie.isNotEmpty()) { require(serie.matches(SERIE)); l += listOf("-d", "*:serial=$serie") }
        l += "-i"
        return l
    }

    /** A line of the tool's output that means it cannot go on (or a command was refused), in French; null otherwise. */
    fun traduis(l: String): String? {
        val m = l.lowercase()
        if (l.startsWith("[INFO") || l.startsWith("[DEBUG") || l.startsWith("[VERBOSE") || l.startsWith("[WARNING")) return null
        return when {
            "no devices available" in m || "no device" in m && "found" in m ->
                "bladeRF absent : le brancher (USB 3) et vérifier avec bladeRF-cli -p"
            "busy" in m -> "bladeRF déjà pris par un autre programme"
            "permission" in m || "access denied" in m || "libusb_error_access" in m ->
                "bladeRF : accès USB refusé (règle udev du paquet bladerf, groupe plugdev ; débrancher et rebrancher)"
            "fpga" in m && ("not loaded" in m || "failed" in m || "not configured" in m) ->
                "bladeRF : FPGA non chargé (hostedxA4.rbf dans ~/.config/Nuand/bladeRF/ ou /usr/share/Nuand/bladeRF/)"
            l.startsWith("[ERROR") || l.startsWith("[CRITICAL") || m.startsWith("error") || m.startsWith("failed") ||
                "invalid" in m || "unrecognized command" in m || "failed to" in m -> "bladeRF-cli : " + l.take(160)
            else -> null
        }
    }

    /** What `bladeRF-cli -e info -e version` says of the board: model, serial, FPGA, firmware. */
    data class Carte(val modele: String, val serie: String, val fpga: String, val firmware: String, val vitesseUsb: String = "") {
        val libelle: String get() = listOf(modele.ifEmpty { "bladeRF" }, serie.takeLast(8).let { if (it.isEmpty()) "" else "n° …$it" },
            if (fpga.isEmpty()) "" else "FPGA $fpga", if (firmware.isEmpty()) "" else "firmware $firmware",
            vitesseUsb).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    fun lisInfo(texte: String): Carte? {
        var modele = ""; var serie = ""; var fpga = ""; var fw = ""; var usb = ""; var taille = ""
        for (ligne in texte.lines().map { it.trim() }) {
            val v = ligne.substringAfter(':', "").trim()
            when {
                ligne.startsWith("Board:") -> modele = v.substringBefore(" (").removePrefix("Nuand ").trim()
                ligne.startsWith("Serial #:") -> serie = v
                ligne.startsWith("FPGA size:") -> taille = v
                ligne.startsWith("FPGA version:") -> fpga = v.substringBefore(" (").trim()
                ligne.startsWith("Firmware version:") -> fw = v.substringBefore("-git").trim()
                ligne.startsWith("USB speed:") -> usb = v
            }
        }
        if (serie.isEmpty()) return null
        if (fpga.isEmpty() && taille.isNotEmpty()) fpga = taille
        return Carte(modele, serie, fpga, fw, if (usb.isEmpty() || usb == "SuperSpeed") "" else "USB $usb (USB 3 conseillé)")
    }

    /** A named pipe in a folder of its own, or an [IOException] (Windows has none bladeRF-cli could write to). */
    fun creeFifo(nom: String, dossier: File? = null): File {
        if (System.getProperty("os.name").orEmpty().startsWith("Windows"))
            throw IOException("bladeRF : sous Windows, bladeRF-cli.exe ne peut pas écrire dans un tube nommé — Linux seulement pour l'instant")
        val d = dossier ?: Files.createTempDirectory("satme-bladerf").toFile()
        d.mkdirs()
        val f = File(d, nom)
        f.delete()
        val p = try { ProcessBuilder("mkfifo", "-m", "600", f.path).redirectErrorStream(true).start() } catch (x: IOException) {
            throw IOException("mkfifo introuvable : " + (x.message ?: ""))
        }
        if (!p.waitFor(5, TimeUnit.SECONDS) || p.exitValue() != 0) { p.destroyForcibly(); throw IOException("mkfifo a échoué (" + f.path + ")") }
        return f
    }

    /** A pipe's other side opened and closed at once: whoever waits in its open goes on (and then sees its end). */
    fun debloque(fifo: File, lecteurEnAttente: Boolean) {
        Thread({
            runCatching { if (lecteurEnAttente) FileOutputStream(fifo).close() else FileInputStream(fifo).close() }
        }, "satme-bladerf-fifo").apply { isDaemon = true; start() }
    }

    /** Every `bladeRF-cli` started and not known to have ended. */
    internal val vivants: MutableSet<Process> = ConcurrentHashMap.newKeySet()

    /** Ends [p]: input closed, waited; terminated, waited; killed. True once it has ended. */
    fun termine(p: Process, attenteMs: Long): Boolean {
        val fermeur = Thread({ runCatching { p.outputStream.close() } }, "satme-bladerf-fin").apply { isDaemon = true; start() }
        runCatching { fermeur.join(minOf(500L, attenteMs)) }
        var fini = runCatching { p.waitFor(attenteMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        if (!fini) {
            runCatching { p.descendants().forEach { it.destroy() } }
            p.destroy()
            fini = runCatching { p.waitFor(attenteMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        }
        if (!fini) {
            runCatching { p.descendants().forEach { it.destroyForcibly() } }
            p.destroyForcibly()
            fini = runCatching { p.waitFor(attenteMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        }
        if (fini) vivants -= p
        return fini
    }

    /** SatMe closing: every `bladeRF-cli` still running ended (quickly). */
    fun arreteTout() {
        for (p in vivants.toList()) termine(p, 500L)
    }

    /** How many are still running (the tests). */
    fun enCours(): Int = vivants.count { it.isAlive }

    /**
     * Runs `bladeRF-cli [-d] -e info -e version` (read-only: it opens the
     * board, prints what it is, closes it): the board, or why not.
     */
    fun detecte(programme: String, serie: String = "", attenteS: Long = 10): Pair<Carte?, String> = runCatching {
        val l = arrayListOf(programme)
        if (serie.isNotEmpty() && serie.matches(SERIE)) l += listOf("-d", "*:serial=$serie")
        l += listOf("-e", "info", "-e", "version")
        val pr = ProcessBuilder(l).redirectErrorStream(true).start()
        runCatching { pr.outputStream.close() }
        val lu = StringBuilder()
        val t = Thread({ runCatching { val s = pr.inputStream.bufferedReader().readText(); synchronized(lu) { lu.append(s) } } }, "satme-bladerf-info")
            .apply { isDaemon = true; start() }
        if (!pr.waitFor(attenteS, TimeUnit.SECONDS)) { pr.destroyForcibly(); return null to "bladeRF-cli ne répond pas" }
        t.join(1000)
        val texte = synchronized(lu) { lu.toString() }
        val c = lisInfo(texte)
        if (c != null) c to "" else null to (texte.lines().map { it.trim() }.firstNotNullOfOrNull { traduis(it) } ?: "aucun bladeRF trouvé (le brancher)")
    }.getOrElse { null to "bladeRF-cli : " + (it.message ?: "erreur") }
}

/**
 * A Nuand bladeRF 2.0 (micro xA4…) on USB, as a receiver, through
 * `bladeRF-cli`: its RX1 side only.
 *
 * **Receive only.** The session's lines are checked ([ECRITURES]): RX1's
 * frequency, sample rate, band, gain and AGC, `rx config`, `rx start`,
 * `rx stop`, `print frequency rx1`, `quit`. Nothing on the TX side.
 *
 * **The rate.** As the Pluto: the AD9361 at twice the dongle's rate
 * (2.1168 MS/s), brought down here to exactly [Dsp.RTL_RATE], 12-bit
 * samples scaled to 16 bits ([read16]).
 *
 * **The stream.** `rx config file=<pipe> format=bin n=0 channel=1`, then
 * `rx start`: bladeRF-cli writes SC16 Q11 into the pipe, a thread of its
 * own reads it into a queue (the oldest block dropped when the processing
 * is late). Retuning while streaming is one more line on its input.
 */
class BladerfSdr private constructor(
    private val session: SessionBladerf,
    private val fifo: File,
    val carte: String,
    private val horloge: () -> Long,
    private val silenceMs: Long,
    private val demarrageMs: Long,
) : SourceIq {

    val description: String get() = carte

    private val file = ArrayBlockingQueue<ShortArray>(PROFONDEUR)
    @Volatile var ouvert = true; private set
    @Volatile override var perdus = 0L; private set
    @Volatile override var octets = 0L; private set
    @Volatile var echantillons = 0L; private set
    @Volatile var erreur: String? = null; private set
    @Volatile var avertissement: String = ""; private set

    @Volatile private var facteur = 0
    @Volatile private var decimateur: DecimateurIq? = null
    private var lecteur: Thread? = null
    @Volatile private var ouvertureEnCours = false
    @Volatile private var flux: InputStream? = null
    @Volatile private var departMs = 0L
    @Volatile private var dernierMs = 0L
    @Volatile private var ppm = 0
    @Volatile private var demandeHz = 0L

    override val iq16: Boolean get() = true

    private fun envoie(c: String) {
        if (!ouvert) return
        try { session.envoie(c) } catch (x: IOException) { arreteSur("bladeRF-cli : liaison perdue") }
    }

    @Synchronized override fun setSampleRate(rate: Int): Double {
        val d = facteurPour(rate)
        envoie("set samplerate rx1 " + rate.toLong() * d)
        envoie("set bandwidth rx1 " + rate.toLong().coerceIn(BANDE_MIN, BANDE_MAX))
        if (d != facteur) { facteur = d; decimateur = DecimateurIq(d, 16.0) }
        return rate.toDouble()
    }

    /** The crystal's error moves the LO asked, as for the Pluto. */
    override fun setFreqCorrection(ppm: Int) {
        this.ppm = ppm
        if (demandeHz > 0) setCenterFreq(demandeHz)
    }

    override fun setCenterFreq(hz: Long): Long {
        demandeHz = hz
        val lo = Math.round(hz / (1.0 + ppm * 1e-6))
        if (lo !in FREQ_MIN_HZ..FREQ_MAX_HZ) {
            avertissement = "fréquence hors de la plage du bladeRF (70 MHz – 6 GHz)"
            return hz
        }
        avertissement = ""
        envoie("set frequency rx1 $lo")
        return hz
    }

    /** Tenths of dB (0 to 60 dB), or null: the AD9361's AGC. */
    override fun setGain(tenthDb: Int?) {
        if (tenthDb == null) envoie("set agc rx1 on")
        else { envoie("set agc rx1 off"); envoie("set gain rx1 " + (tenthDb / 10).coerceIn(GAIN_MIN_DB, GAIN_MAX_DB)) }
    }

    override fun setAgc(on: Boolean) {}

    override fun read16(buf: ShortArray, timeoutMs: Int): Int {
        demarre()
        val b = file.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS) ?: return if (ouvert || file.isNotEmpty()) 0 else -1
        val n = minOf(b.size, buf.size) and 1.inv()
        System.arraycopy(b, 0, buf, 0, n)
        return n
    }

    override fun read(buf: ByteArray, timeoutMs: Int): Int {
        demarre()
        val b = file.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS) ?: return if (ouvert || file.isNotEmpty()) 0 else -1
        val n = minOf(b.size, buf.size) and 1.inv()
        for (k in 0 until n) buf[k] = ((b[k] + 32768) shr 8).toByte()
        return n
    }

    @Synchronized private fun demarre() {
        if (lecteur != null || !ouvert) return
        if (facteur == 0) setSampleRate(Dsp.RTL_RATE)
        departMs = horloge()
        ouvertureEnCours = true
        lecteur = Thread({ boucle() }, "satme-bladerf-rx").apply { isDaemon = true; priority = Thread.NORM_PRIORITY + 1; start() }
        envoie("rx config file=" + fifo.path + " format=bin n=0 channel=1 samples=$TAMPON buffers=32 xfers=16 timeout=2000")
        envoie("rx start")
        Thread({ veille() }, "satme-bladerf-rx-garde").apply { isDaemon = true; start() }
    }

    private fun boucle() {
        try {
            val e = FileInputStream(fifo)
            ouvertureEnCours = false
            flux = e
            if (!ouvert) { e.close(); return }
            val brut = ByteArray(TAMPON * 4 + 4)
            var reste = 0
            var sortieCourante = ShortArray(BLOC)
            var pos = 0
            var dec = ShortArray(TAMPON * 2 + 4)
            while (ouvert) {
                val lu = e.read(brut, reste, brut.size - reste)
                if (lu < 0) break
                val n = reste + lu
                val m4 = n and 3.inv()
                octets += lu
                echantillons = octets / 4
                dernierMs = horloge()
                val d = decimateur
                if (d != null && m4 > 0) {
                    if (dec.size < m4 / 2 + 4) dec = ShortArray(m4 / 2 + 4)
                    val m = d.traite(brut, m4, dec)
                    var k = 0
                    while (k < m) {
                        val c = minOf(m - k, BLOC - pos)
                        System.arraycopy(dec, k, sortieCourante, pos, c)
                        pos += c; k += c
                        if (pos == BLOC) {
                            if (!file.offer(sortieCourante)) { file.poll(); perdus++; file.offer(sortieCourante) }
                            sortieCourante = ShortArray(BLOC); pos = 0
                        }
                    }
                }
                // A pair cut at the end of a read waits for the next one.
                reste = n - m4
                if (reste > 0) System.arraycopy(brut, m4, brut, 0, reste)
            }
            if (ouvert) arreteSur(session.fatal ?: if (session.vivant) "bladeRF-cli a fermé le flux" else session.messageMort())
        } catch (x: IOException) {
            if (ouvert) arreteSur("bladeRF : " + (x.message ?: "flux perdu"))
        } finally {
            ouvertureEnCours = false
            runCatching { flux?.close() }
        }
    }

    /** The tool gone, or silent too long: the reception stops with the reason. */
    private fun veille() {
        while (ouvert) {
            try { Thread.sleep(200) } catch (x: InterruptedException) { return }
            val t = horloge()
            when {
                !session.vivant -> arreteSur(session.fatal ?: session.messageMort())
                octets == 0L && t - departMs > demarrageMs -> arreteSur(session.fatal ?: "le bladeRF ne donne pas d'échantillons (bladeRF-cli bloqué ?)")
                octets > 0L && t - dernierMs > silenceMs -> arreteSur("le bladeRF ne donne plus d'échantillons (débranché ?)")
                else -> session.prendErreur()?.let { avertissement = it }
            }
        }
    }

    private fun arreteSur(pourquoi: String) {
        if (!ouvert) return
        erreur = pourquoi
        ouvert = false
    }

    override fun close() {
        val etaitOuvert = ouvert
        ouvert = false
        if (etaitOuvert || session.vivant) runCatching { session.envoie("rx stop") }
        if (ouvertureEnCours) Bladerf.debloque(fifo, lecteurEnAttente = true)
        val t = lecteur
        runCatching { session.ferme(ATTENTE_ARRET_MS) }
        runCatching { flux?.close() }
        if (t != null && t !== Thread.currentThread()) runCatching { t.join(1000) }
        file.clear()
        fifo.delete()
        fifo.parentFile?.takeIf { it.name.startsWith("satme-bladerf") }?.delete()
    }

    companion object {
        const val FREQ_MIN_HZ = 70_000_000L
        const val FREQ_MAX_HZ = 6_000_000_000L
        const val DEBIT_MIN = 2_000_000L
        const val BANDE_MIN = 200_000L
        const val BANDE_MAX = 56_000_000L
        const val GAIN_MIN_DB = 0
        const val GAIN_MAX_DB = 60
        /** Samples per USB buffer: 7.7 ms at 2.1168 MS/s. */
        const val TAMPON = 16_384
        const val BLOC = 16_384
        const val PROFONDEUR = 256
        const val ATTENTE_ARRET_MS = 1_500L

        fun facteurPour(rate: Int): Int { var d = 1; while (rate.toLong() * d < DEBIT_MIN) d++; return d }

        private val RX_SET = Regex("set (frequency|samplerate|bandwidth) rx1 \\d{1,10}")
        private val RX_GAIN = Regex("set gain rx1 -?\\d{1,2}")
        private val RX_AGC = Regex("set agc rx1 (on|off)")
        private val RX_CONFIG = Regex("rx config file=\\S+ format=bin n=0 channel=1( (samples|buffers|xfers|timeout)=\\d+)*")
        private val RX_SEUL = setOf("rx start", "rx stop", "print frequency rx1", "quit")

        /** Throws unless [c] is one of the receiver's lines: RX1 only. */
        fun verifie(c: String) {
            check(c in RX_SEUL || RX_SET.matches(c) || RX_GAIN.matches(c) || RX_AGC.matches(c) || RX_CONFIG.matches(c)) {
                "commande refusée pour le récepteur bladeRF : $c"
            }
        }

        /**
         * Starts `bladeRF-cli` on the board ([serie], or the first one) and
         * checks it answers; an [IOException] in French says why not. Only
         * RX lines are ever sent.
         */
        fun ouvre(programme: String, serie: String = "", carte: String = "bladeRF 2.0",
                  lanceur: (List<String>) -> Process = { ProcessBuilder(it).redirectErrorStream(true).start() },
                  horloge: () -> Long = System::currentTimeMillis, silenceMs: Long = 3_000L, demarrageMs: Long = 12_000L,
                  dossier: File? = null): BladerfSdr {
            val fifo = Bladerf.creeFifo("rx.iq", dossier)
            val s = try { SessionBladerf(programme, serie, ::verifie, lanceur) } catch (x: Exception) {
                fifo.delete(); throw if (x is IOException) x else IOException(x.message)
            }
            try {
                s.envoie("print frequency rx1")
                s.attend(Regex("RX1 Frequency:"), 10_000L, "ouverture")
            } catch (x: IOException) {
                s.ferme(500L)
                fifo.delete()
                throw IOException(s.fatal ?: x.message)
            }
            return BladerfSdr(s, fifo, carte, horloge, silenceMs, demarrageMs)
        }
    }
}
