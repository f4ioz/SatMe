/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sdr

import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * A HackRF One transmitting through `hackrf_transfer` (the `hackrf`
 * package's tool): the samples on its standard input (`-t -`), interleaved
 * signed 8-bit I/Q. **The only code that starts `hackrf_transfer`.**
 *
 * Low power only: the RF amplifier is never switched on (`-a 0` on every
 * command line), nor the antenna port's supply (`-p 0`); the TX VGA gain
 * never goes above [VGA_MAX]. [commande] builds the one command line and
 * [verifie] refuses any other.
 *
 * Nothing runs before [demarre]. **Stop** (asked, at the end of the time
 * allowed, on any error, when the tool dies or says the HackRF is missing,
 * when it stops taking samples, when the samples come too late too often,
 * or when [Reglage.garde] says so): its standard input closed first (the
 * tool ends at the end of its input), then the process terminated, then
 * killed. Every process started stays in [vivants] until it has ended, for
 * [arreteTout] when SatMe closes.
 */
class HackrfEmission(
    /** The `hackrf_transfer` program's path. */
    private val programme: String,
    /** Never above this TX VGA gain (dB), whatever is asked; [VGA_MAX] at most. */
    vgaMax: Int = VGA_MAX,
    private val horloge: () -> Long = System::currentTimeMillis,
    /** No buffer taken by the tool for this long: stopped. */
    private val chienMs: Long = 3_000L,
    /** More underruns than this within [fenetreManquesMs]: stopped. */
    private val manquesMax: Int = 20,
    private val fenetreManquesMs: Long = 10_000L,
    /** Each step of the stop: the input closed, then terminated, then killed. */
    private val attenteArretMs: Long = 1_500L,
    /** How long the start waits for the tool to open the HackRF or say why not. */
    private val attenteDemarrageMs: Long = 700L,
    private val lanceur: (List<String>) -> Process = { ProcessBuilder(it).start() },
) : EmetteurDatv {
    private val vgaMax = vgaMax.coerceIn(0, VGA_MAX)

    data class Reglage(
        val frequenceHz: Long,
        /** Samples per second (I/Q pairs), 2 to 20 MS/s. */
        val debit: Long,
        /** TX VGA gain (dB). */
        val vgaDb: Int,
        /** How long it may transmit (ms), then stops by itself. */
        val dureeMs: Long,
        /** The occupied band (Hz), for the baseband filter; the sample rate when 0. */
        val bandeHz: Long = 0L,
        /** A lower ceiling for this transmission (dB). */
        val plafondVgaDb: Int = VGA_MAX,
        /** I/Q pairs per buffer. */
        val paires: Int = 65_536,
        /** The HackRF's serial number (hackrf_info), or empty for the first one. */
        val serie: String = "",
        /** Asked every 200 ms: a reason to stop, or null. */
        val garde: () -> String? = { null },
        /** The I/Q correction for the board of this serial number ([serie]), or none. */
        val correction: (String) -> CorrectionIq? = { null },
    )

    /** What `hackrf_info` says of one board. */
    data class Carte(val serie: String, val carte: String, val firmware: String) {
        val libelle: String get() = listOf(carte.ifEmpty { "HackRF" }, serie.takeLast(8).let { if (it.isEmpty()) "" else "n° …$it" },
            if (firmware.isEmpty()) "" else "firmware $firmware").filter { it.isNotEmpty() }.joinToString(" · ")
    }

    @Volatile var etat = EtatEmission(); private set

    override val nom: String get() = "HackRF One"

    override fun etatCommun(): EtatEmission = etat

    @Volatile private var processus: Process? = null
    @Volatile private var generation = 0
    @Volatile private var actif = false
    @Volatile private var dernierEnvoi = 0L
    /** The tool's last lines (its standard error). */
    private val journal = ArrayDeque<String>()
    @Volatile private var fatal: String? = null
    private val datesManques = ArrayDeque<Long>()
    private val fils = ArrayList<Thread>()
    private var lecteur: Thread? = null
    /** Held for the whole of a stop: [demarre] waits on it for the stop's message. */
    private val verrouCoupe = Any()

    /** The TX VGA gain applied for [demande]: whole dB, under every ceiling, 0 at least. */
    fun vgaApplique(demande: Int, plafond: Int = VGA_MAX): Int = minOf(demande, vgaMax, plafond, VGA_MAX).coerceAtLeast(0)

    /**
     * Starts `hackrf_transfer` for [r], samples from [producteur] (it fills
     * the array given: [Reglage.paires] I/Q pairs, 16-bit); null once
     * transmitting, otherwise why not (no tool left running).
     */
    @Synchronized
    fun demarre(r: Reglage, producteur: (ShortArray) -> Unit): String? {
        if (actif) return "déjà en émission"
        require(r.paires in 1024..(1 shl 20))
        if (r.debit !in DEBIT_MIN..DEBIT_MAX) return "débit de ${r.debit} éch/s hors de la plage du HackRF (2 à 20 Méch/s)"
        if (r.frequenceHz !in FREQ_MIN_HZ..FREQ_MAX_HZ) return "fréquence hors de la plage du HackRF (1 à 6000 MHz)"
        if (r.serie.isNotEmpty() && !r.serie.matches(SERIE)) return "numéro de série du HackRF invalide"
        val vga = vgaApplique(r.vgaDb, r.plafondVgaDb)
        val duree = r.dureeMs.coerceIn(1L, DUREE_MAX_MS)
        // The correction after the producer's own mixing; the LO moved by the crystal's error.
        val etage = r.correction(r.serie)?.let { EtageIq(it, r.frequenceHz, r.debit) }
        if (etage != null && etage.loHz !in FREQ_MIN_HZ..FREQ_MAX_HZ) return "fréquence hors de la plage du HackRF (1 à 6000 MHz)"
        val cmd = commande(programme, if (etage == null) r else r.copy(frequenceHz = etage.loHz), vga)
        val gen = ++generation
        fatal = null
        synchronized(journal) { journal.clear() }
        val p = try {
            lanceur(cmd)
        } catch (x: IOException) {
            return "hackrf_transfer ne démarre pas : " + (x.message ?: "introuvable")
        } catch (x: SecurityException) {
            return "hackrf_transfer ne démarre pas : " + (x.message ?: "refusé")
        }
        vivants += p
        processus = p
        val maintenant = horloge()
        actif = true
        dernierEnvoi = maintenant
        synchronized(datesManques) { datesManques.clear() }
        etat = EtatEmission(emet = true, debutMs = maintenant, finMs = maintenant + duree, frequenceHz = r.frequenceHz, gainDb = vga.toDouble())
        lecteur = Thread({ litErreurs(p) }, "satme-hackrf-tx-journal").apply { isDaemon = true; start() }
        val file = ArrayBlockingQueue<ShortArray>(PROFONDEUR)
        fils.clear()
        fils += Thread({ produit(gen, r.paires, file, producteur, etage) }, "satme-hackrf-tx-mod").apply { isDaemon = true; priority = Thread.MAX_PRIORITY - 1 }
        fils += Thread({ ecrit(gen, p, file) }, "satme-hackrf-tx").apply { isDaemon = true; priority = Thread.MAX_PRIORITY }
        fils += Thread({ veille(gen, p, r) }, "satme-hackrf-tx-garde").apply { isDaemon = true }
        fils.forEach { it.start() }
        // The first moments: the tool opens the HackRF, or says why not.
        val mort = try { p.waitFor(attenteDemarrageMs, TimeUnit.MILLISECONDS) } catch (x: InterruptedException) { true }
        if (mort || fatal != null) {
            coupe(gen, "", if (mort) messageMort(p) else fatal!!)
            synchronized(verrouCoupe) { }
            return etat.erreur.ifEmpty { "hackrf_transfer s'est arrêté" }
        }
        return null
    }

    /** Stops transmitting (input closed, then the tool terminated); [raison] kept in the state. */
    override fun arrete(raison: String) = coupe(generation, raison, "")

    /** The tool takes its gain at the start only: kept for the next one. */
    override fun regleGain(db: Double): Double? = null

    // ------------------------------------------------------------ threads

    private fun produit(gen: Int, paires: Int, file: ArrayBlockingQueue<ShortArray>, producteur: (ShortArray) -> Unit, etage: EtageIq?) {
        try {
            while (actif && generation == gen) {
                val b = ShortArray(2 * paires)
                producteur(b)
                // The correction last, just before the transmitter.
                etage?.applique(b)
                while (actif && generation == gen && !file.offer(b, 200, TimeUnit.MILLISECONDS)) { /* the HackRF sets the pace */ }
            }
        } catch (x: InterruptedException) {
            // stopping
        } catch (x: Exception) {
            coupe(gen, "", "modulateur : " + (x.message ?: x.javaClass.simpleName))
        }
    }

    private fun ecrit(gen: Int, p: Process, file: ArrayBlockingQueue<ShortArray>) {
        val sortie: OutputStream = p.outputStream
        var octets = ByteArray(0)
        var envoyes = 0L
        try {
            while (actif && generation == gen) {
                var b = file.poll()
                if (b == null) {
                    // The tool ready, the samples not: an underrun (counted once streaming).
                    if (envoyes >= 2) manque()
                    b = file.poll(500, TimeUnit.MILLISECONDS) ?: continue
                }
                if (octets.size != b.size) octets = ByteArray(b.size)
                versInt8(b, octets, b.size)
                if (!actif || generation != gen) break
                // Blocks while the tool's pipe is full: the HackRF's clock sets the pace.
                sortie.write(octets)
                sortie.flush()
                envoyes++
                dernierEnvoi = horloge()
                etat = etat.copy(tampons = envoyes)
            }
        } catch (x: InterruptedException) {
            // stopping
        } catch (x: IOException) {
            if (actif && generation == gen) {
                // The pipe broken: the tool is dying; its last words say why.
                runCatching { p.waitFor(attenteArretMs, TimeUnit.MILLISECONDS) }
                coupe(gen, "", messageMort(p))
            }
        }
    }

    private fun litErreurs(p: Process) {
        runCatching {
            p.errorStream.bufferedReader().forEachLine { l ->
                val t = l.trim()
                if (t.isEmpty()) return@forEachLine
                synchronized(journal) { journal.addLast(t); while (journal.size > 40) journal.removeFirst() }
                if (fatal == null) traduis(t)?.let { fatal = it }
            }
        }
    }

    private fun manque() {
        val t = horloge()
        etat = etat.copy(manques = etat.manques + 1)
        synchronized(datesManques) {
            datesManques.addLast(t)
            while (datesManques.isNotEmpty() && t - datesManques.first() > fenetreManquesMs) datesManques.removeFirst()
        }
    }

    private fun veille(gen: Int, p: Process, r: Reglage) {
        while (actif && generation == gen) {
            try { Thread.sleep(PERIODE_MS) } catch (x: InterruptedException) { return }
            val t = horloge()
            val e = etat
            val f = fatal
            when {
                t >= e.finMs -> coupe(gen, "durée maximale atteinte", "")
                !p.isAlive -> coupe(gen, "", messageMort(p))
                f != null -> coupe(gen, "", f)
                t - dernierEnvoi > chienMs -> coupe(gen, "", "le HackRF ne prend plus les échantillons (débranché ? hackrf_transfer bloqué ?)")
                synchronized(datesManques) { datesManques.count { t - it <= fenetreManquesMs } } > manquesMax ->
                    coupe(gen, "", "échantillons trop souvent en retard (${e.manques} manques) : PC trop lent ?")
                else -> runCatching { r.garde() }.getOrElse { "garde : " + it.message }?.let { coupe(gen, it, "") }
            }
        }
    }

    /** Why the tool ended: its standard error read, in French. */
    private fun messageMort(p: Process): String {
        runCatching { lecteur?.join(500) }
        fatal?.let { return it }
        val l = synchronized(journal) { journal.toList() }
        l.firstNotNullOfOrNull { traduis(it) }?.let { return it }
        val code = runCatching { p.exitValue().toString() }.getOrDefault("?")
        return "hackrf_transfer s'est arrêté (code $code)" + (l.lastOrNull()?.let { " : " + it.take(160) } ?: "")
    }

    /** The end, whatever its cause: input closed, the tool terminated, then killed. */
    private fun coupe(gen: Int, raison: String, erreur: String) {
        synchronized(this) {
            if (!actif || gen != generation) return
            actif = false
            generation++
        }
        synchronized(verrouCoupe) {
            val p = processus
            processus = null
            val arrete = p == null || termine(p, attenteArretMs)
            fils.filter { it !== Thread.currentThread() }.forEach { it.interrupt() }
            etat = etat.copy(emet = false, raison = raison.ifEmpty { "erreur" },
                erreur = erreur + (if (!arrete) (if (erreur.isEmpty()) "" else " — ") + "hackrf_transfer NON arrêté : débrancher le HackRF" else ""))
        }
    }

    companion object {
        /** The ceiling of the TX VGA gain in SatMe (dB); the HackRF goes to 47. */
        const val VGA_MAX = 20
        const val DEBIT_MIN = 2_000_000L
        const val DEBIT_MAX = 20_000_000L
        const val FREQ_MIN_HZ = 1_000_000L
        const val FREQ_MAX_HZ = 6_000_000_000L
        const val DUREE_MAX_MS = 30 * 60_000L
        const val PROFONDEUR = 4
        const val PERIODE_MS = 200L
        private val SERIE = Regex("[0-9a-fA-F]{1,32}")

        /** The baseband filter's bandwidths (Hz) the HackRF has. */
        val FILTRES = listOf(1_750_000L, 2_500_000L, 3_500_000L, 5_000_000L, 5_500_000L, 6_000_000L, 7_000_000L, 8_000_000L,
            9_000_000L, 10_000_000L, 12_000_000L, 14_000_000L, 15_000_000L, 20_000_000L, 24_000_000L, 28_000_000L)

        /** The narrowest baseband filter that lets [bandeHz] through. */
        fun filtre(bandeHz: Long): Long = FILTRES.firstOrNull { it >= bandeHz } ?: FILTRES.last()

        /** The options the command line may hold, each with its value. */
        private val OPTIONS = setOf("-d", "-t", "-f", "-s", "-b", "-x", "-a", "-p")

        /**
         * `hackrf_transfer`'s command line: transmit from standard input,
         * frequency, sample rate, baseband filter, TX VGA [vga]; the RF
         * amplifier and the antenna port's supply off.
         */
        fun commande(programme: String, r: Reglage, vga: Int): List<String> {
            val l = arrayListOf(programme)
            if (r.serie.isNotEmpty()) l += listOf("-d", r.serie)
            l += listOf("-t", "-", "-f", r.frequenceHz.toString(), "-s", r.debit.toString(),
                "-b", filtre(if (r.bandeHz > 0) r.bandeHz else r.debit).toString(),
                "-x", vga.toString(), "-a", "0", "-p", "0")
            verifie(l)
            return l
        }

        /** Throws unless [l] is a command line [commande] may give: low power, from standard input, nothing else. */
        fun verifie(l: List<String>) {
            check(l.size % 2 == 1) { "ligne de commande hackrf_transfer inattendue" }
            val o = HashMap<String, String>()
            for (k in 1 until l.size step 2) {
                check(l[k] in OPTIONS && o.put(l[k], l[k + 1]) == null) { "option hackrf_transfer non prévue : " + l[k] }
            }
            check(o["-t"] == "-") { "hackrf_transfer : émission depuis l'entrée standard seulement" }
            check(o["-a"] == "0") { "hackrf_transfer : amplificateur RF interdit" }
            check(o["-p"] == "0") { "hackrf_transfer : alimentation du port d'antenne interdite" }
            check(o["-x"]?.toIntOrNull()?.let { it in 0..VGA_MAX } == true) { "hackrf_transfer : gain TX au-delà de $VGA_MAX dB" }
            check(o["-f"]?.toLongOrNull()?.let { it in FREQ_MIN_HZ..FREQ_MAX_HZ } == true) { "hackrf_transfer : fréquence" }
            check(o["-s"]?.toLongOrNull()?.let { it in DEBIT_MIN..DEBIT_MAX } == true) { "hackrf_transfer : débit" }
            check(o["-b"]?.toLongOrNull() in FILTRES) { "hackrf_transfer : filtre" }
        }

        /**
         * 16-bit I/Q (the modulator's, the RMS 10 dB under full scale) to
         * signed 8-bit: the top byte, rounded, held within ±127.
         */
        fun versInt8(src: ShortArray, dst: ByteArray, n: Int) {
            for (k in 0 until n) {
                val v = src[k].toInt()
                val r = (if (v >= 0) v + 128 else v - 128) / 256
                dst[k] = r.coerceIn(-127, 127).toByte()
            }
        }

        /** A line of the tool's standard error that means it cannot go on, in French; null otherwise. */
        fun traduis(l: String): String? {
            val m = l.lowercase()
            return when {
                "no hackrf boards found" in m || "hackrf_error_not_found" in m || "not found" in m && "hackrf" in m ->
                    "HackRF absent : le brancher (USB) et vérifier avec hackrf_info"
                "busy" in m -> "HackRF déjà pris par un autre programme"
                "access" in m || "permission" in m ->
                    "HackRF : accès USB refusé (règles udev du paquet hackrf ; débrancher et rebrancher)"
                "streaming_exit_called" in m -> null
                "hackrf_error" in m || "failed" in m || "couldn't transfer" in m || "error" in m ->
                    "hackrf_transfer : " + l.take(160)
                else -> null
            }
        }

        /** The boards in `hackrf_info`'s output (none when it found none). */
        fun lisInfo(texte: String): List<Carte> {
            val l = ArrayList<Carte>()
            var serie: String? = null
            var carte = ""
            var fw = ""
            fun fin() { serie?.let { l += Carte(it, carte, fw) }; serie = null; carte = ""; fw = "" }
            for (ligne in texte.lines().map { it.trim() }) {
                val v = ligne.substringAfter(':', "").trim()
                when {
                    ligne.startsWith("Found HackRF") || ligne.startsWith("Index:") -> if (serie != null) fin()
                    ligne.startsWith("Serial number:") -> serie = v.trimStart('0').ifEmpty { v }
                    ligne.startsWith("Board ID Number:") -> carte = v.substringAfter('(', "").substringBefore(')').ifEmpty { v }
                    ligne.startsWith("Firmware Version:") -> fw = v.substringBefore(" (").trim()
                }
            }
            fin()
            return l
        }

        /** Every `hackrf_transfer` started and not known to have ended. */
        private val vivants: MutableSet<Process> = ConcurrentHashMap.newKeySet()

        /** Ends [p]: input closed, waited; terminated, waited; killed. True once it has ended. */
        fun termine(p: Process, attenteMs: Long): Boolean {
            // Closed from a thread of its own: a writer blocked on a full pipe holds the stream.
            val fermeur = Thread({ runCatching { p.outputStream.close() } }, "satme-hackrf-tx-fin").apply { isDaemon = true; start() }
            runCatching { fermeur.join(minOf(500L, attenteMs)) }
            var fini = runCatching { p.waitFor(attenteMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
            if (!fini) {
                p.destroy()
                fini = runCatching { p.waitFor(attenteMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
            }
            if (!fini) {
                p.destroyForcibly()
                fini = runCatching { p.waitFor(attenteMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
            }
            if (fini) vivants -= p
            return fini
        }

        /** SatMe closing: every `hackrf_transfer` still running ended (quickly). */
        fun arreteTout() {
            for (p in vivants.toList()) termine(p, 500L)
        }

        /** How many are still running (the tests). */
        fun enCours(): Int = vivants.count { it.isAlive }
    }
}
