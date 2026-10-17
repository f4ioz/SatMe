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
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A bladeRF 2.0 transmitting through `bladeRF-cli` in interactive mode:
 * the samples (SC16 Q11) into a named pipe it reads with `tx config
 * file=<pipe> format=bin repeat=1`. **The only code that sends a TX line
 * to bladeRF-cli** ([verifie] refuses any other).
 *
 * **The gain** is the Pluto's: an attenuation, 0 dB at most, whole dB,
 * under [gainMaxDb] and the transmission's ceiling (−40 dB indoors and into
 * a load); libbladeRF's overall TX gain is it plus 66 dB ([Bladerf.DECALAGE_GAIN_TX]),
 * which the tool's answer must confirm (its range up to 66 dB).
 *
 * **Start**, one line at a time, each answer checked before the next:
 * the gain first (TX still muted: libbladeRF keeps it for the start), the
 * TX LO, the sample rate, the band, the stream's settings; then `tx start`;
 * once samples flow, the gain again and read back (never above the one
 * asked). An error line, a missing answer: stopped before `tx start`.
 *
 * **Stop** (asked, at the end of the time allowed, on any error line, when
 * the tool dies, when it stops taking samples, when the samples come too
 * late too often, or when [Reglage.garde] says so): `tx stop` (the stream
 * ends, libbladeRF disables TX), the pipe closed, `quit` (the board
 * closed), then terminated, then killed.
 */
class BladerfEmission(
    private val programme: String,
    /** Never above this TX gain (dB, ≤ 0), whatever is asked. */
    private val gainMaxDb: Double = -10.0,
    private val horloge: () -> Long = System::currentTimeMillis,
    /** No buffer taken for this long (once flowing): stopped. */
    private val chienMs: Long = 3_000L,
    private val manquesMax: Int = 20,
    private val fenetreManquesMs: Long = 10_000L,
    private val attenteArretMs: Long = 1_500L,
    /** Each answer of the tool, at most. */
    private val attenteReponseMs: Long = 6_000L,
    /** From `tx start` to the first buffer taken, at most. */
    private val attenteFluxMs: Long = 12_000L,
    private val dossierFifo: File? = null,
    private val lanceur: (List<String>) -> Process = { ProcessBuilder(it).redirectErrorStream(true).start() },
) : EmetteurDatv {

    data class Reglage(
        val frequenceHz: Long,
        /** Samples per second (I/Q pairs). */
        val debit: Long,
        /** The attenuation asked (dB, ≤ 0), as the Pluto's. */
        val gainDb: Double,
        val dureeMs: Long,
        /** The analog TX band (Hz); the sample rate when 0. */
        val bandeHz: Long = 0L,
        /** A lower ceiling for this transmission (dB). */
        val plafondDb: Double = 0.0,
        /** I/Q pairs per buffer (a multiple of 1024). */
        val paires: Int = 65_536,
        /** The board's serial number, or empty for the first one. */
        val serie: String = "",
        val garde: () -> String? = { null },
    )

    @Volatile var etat = EtatEmission(); private set

    override val nom: String get() = "bladeRF 2.0"

    override fun etatCommun(): EtatEmission = etat

    @Volatile private var session: SessionBladerf? = null
    @Volatile private var pairesParTampon = 65_536

    /** How often the device ran out of samples to send (the tool's « underrun » lines). */
    val sousCharges: Int get() = session?.sousCharges ?: 0
    @Volatile private var fifo: File? = null
    @Volatile private var sortie: OutputStream? = null
    @Volatile private var ouvertureEnCours = false
    @Volatile private var generation = 0
    @Volatile private var actif = false
    @Volatile private var dernierEnvoi = 0L
    @Volatile private var envoyes = 0L
    @Volatile private var plafond = 0.0
    private val datesManques = ArrayDeque<Long>()
    private val fils = ArrayList<Thread>()
    private val verrouCoupe = Any()
    /** Guards [actif] and [generation] at a stop (not the start's lock: a stop never waits for a start). */
    private val verrouEtat = Any()
    /** One command and its answer at a time. */
    private val verrouCommande = Any()

    /** The attenuation applied for [demande]: whole dB, under every ceiling, −89 dB at least. */
    fun gainApplique(demande: Double, plafondDb: Double = 0.0): Double {
        if (demande.isNaN()) return GAIN_MIN_DB.toDouble()
        val g = minOf(demande, gainMaxDb, plafondDb, 0.0)
        return Math.floor(g).coerceAtLeast(GAIN_MIN_DB.toDouble())
    }

    /** The tool's overall TX gain for an attenuation [g]. */
    fun gainCli(g: Double): Int = Bladerf.DECALAGE_GAIN_TX + g.toInt()

    /**
     * Configures and starts transmitting [r], samples from [producteur]
     * (it fills [Reglage.paires] I/Q pairs, 16-bit); null once
     * transmitting, otherwise why not (no tool left running).
     */
    @Synchronized
    fun demarre(r: Reglage, producteur: (ShortArray) -> Unit): String? {
        if (actif) return "déjà en émission"
        require(r.paires in 1024..(1 shl 20) && r.paires % 1024 == 0)
        pairesParTampon = r.paires
        if (r.debit !in DEBIT_MIN..DEBIT_MAX) return "débit de ${r.debit} éch/s hors de la plage du bladeRF (0,52 à 61,44 Méch/s)"
        if (r.frequenceHz !in FREQ_MIN_HZ..FREQ_MAX_HZ) return "fréquence hors de la plage du bladeRF (47 à 6000 MHz)"
        if (r.serie.isNotEmpty() && !r.serie.matches(Bladerf.SERIE)) return "numéro de série du bladeRF invalide"
        plafond = minOf(r.plafondDb, 0.0)
        val g = gainApplique(r.gainDb, plafond)
        val cli = gainCli(g)
        val bande = (if (r.bandeHz > 0) r.bandeHz else r.debit).coerceIn(BANDE_MIN, BANDE_MAX)
        val duree = r.dureeMs.coerceIn(1L, DUREE_MAX_MS)
        val gen = synchronized(verrouEtat) { ++generation }
        val f = try { Bladerf.creeFifo("tx.iq", dossierFifo) } catch (x: IOException) { return x.message }
        fifo = f
        val s = try { SessionBladerf(programme, r.serie, ::verifie, lanceur) } catch (x: IOException) {
            f.delete(); fifo = null; return x.message
        }
        session = s
        // Set up, each answer checked; TX still off.
        try {
            synchronized(verrouCommande) {
                s.oublie()
                s.envoie("set gain tx1 $cli")
                val dit = s.attend(Regex("Setting TX1 overall gain to (-?\\d+) dB"), attenteReponseMs, "gain").groupValues[1].toInt()
                if (dit != cli) throw IOException("bladeRF : gain $dit dB au lieu de $cli")
                val gamme = s.attend(Regex("Gain TX1\\s+overall:\\s*-?\\d+ dB \\(Range: \\[([-\\d.]+), ([-\\d.]+)\\]\\)"), attenteReponseMs, "gain")
                if (gamme.groupValues[2].toDoubleOrNull() != Bladerf.DECALAGE_GAIN_TX.toDouble())
                    throw IOException("bladeRF : gamme de gain TX inattendue (" + gamme.groupValues[1] + " à " + gamme.groupValues[2] + " dB) : émission refusée")
                s.envoie("set frequency tx1 " + r.frequenceHz)
                val fq = s.attend(Regex("TX1 Frequency:\\s*(\\d+) Hz"), attenteReponseMs, "fréquence").groupValues[1].toLong()
                if (kotlin.math.abs(fq - r.frequenceHz) > 10) throw IOException("bladeRF : fréquence $fq Hz au lieu de ${r.frequenceHz}")
                s.envoie("set samplerate tx1 " + r.debit)
                val de = s.attend(Regex("Setting TX1 sample rate - req:\\s*\\d+.*actual:\\s*(\\d+)"), attenteReponseMs, "débit").groupValues[1].toLong()
                if (kotlin.math.abs(de - r.debit) > r.debit / 10_000 + 1) throw IOException("bladeRF : débit $de éch/s au lieu de ${r.debit}")
                s.envoie("set bandwidth tx1 $bande")
                s.attend(Regex("TX1 Bandwidth:\\s*\\d+ Hz"), attenteReponseMs, "bande")
                s.envoie("tx config file=" + f.path + " format=bin repeat=1 delay=0 channel=1 samples=$ECHANTILLONS_CLI buffers=$TAMPONS_CLI xfers=$TRANSFERTS_CLI timeout=2000")
                s.envoie("tx")
                val fichier = s.attend(Regex("File:\\s*(.+)$"), attenteReponseMs, "réglage du flux").groupValues[1].trim()
                if (fichier != f.path) throw IOException("bladeRF : flux sur « $fichier » au lieu du tube")
            }
        } catch (x: Exception) {
            nettoie(s, f)
            return x.message ?: "bladeRF-cli : réglage impossible"
        }
        val maintenant = horloge()
        actif = true
        envoyes = 0L
        dernierEnvoi = maintenant
        synchronized(datesManques) { datesManques.clear() }
        etat = EtatEmission(emet = true, debutMs = maintenant, finMs = maintenant + duree, frequenceHz = r.frequenceHz, gainDb = g)
        val file = ArrayBlockingQueue<ShortArray>(PROFONDEUR)
        fils.clear()
        fils += Thread({ produit(gen, r.paires, file, producteur) }, "satme-bladerf-tx-mod").apply { isDaemon = true; priority = Thread.MAX_PRIORITY - 1 }
        fils += Thread({ ecrit(gen, f, file) }, "satme-bladerf-tx").apply { isDaemon = true; priority = Thread.MAX_PRIORITY }
        fils.forEach { it.start() }
        runCatching { s.envoie("tx start") }.onFailure { coupe(gen, "", "bladeRF-cli : " + (it.message ?: "liaison perdue")) }
        // The first buffer taken: it flows; else stopped.
        val fin = horloge() + attenteFluxMs
        while (actif && generation == gen && envoyes == 0L && horloge() < fin && s.vivant && s.fatal == null) Thread.sleep(20)
        val pourquoi = when {
            !actif || generation != gen -> etat.erreur.ifEmpty { "émission arrêtée au démarrage" }
            s.fatal != null -> s.fatal
            !s.vivant -> s.messageMort()
            envoyes == 0L -> "le bladeRF ne prend pas les échantillons (bladeRF-cli bloqué ?)"
            else -> null
        }
        if (pourquoi != null) {
            coupe(gen, "", pourquoi)
            synchronized(verrouCoupe) { }
            return etat.erreur.ifEmpty { pourquoi }
        }
        // Flowing: the gain again, read back, never above the one asked.
        val relu = appliqueGain(s, g)
        if (relu != null) {
            coupe(gen, "", relu)
            synchronized(verrouCoupe) { }
            return etat.erreur.ifEmpty { relu }
        }
        fils += Thread({ veille(gen, s, r) }, "satme-bladerf-tx-garde").apply { isDaemon = true; start() }
        return null
    }

    /** `set gain tx1`, its answer checked; null when applied, else why not. */
    private fun appliqueGain(s: SessionBladerf, g: Double): String? = try {
        synchronized(verrouCommande) {
            val cli = gainCli(g)
            s.oublie()
            s.envoie("set gain tx1 $cli")
            val dit = s.attend(Regex("Setting TX1 overall gain to (-?\\d+) dB"), attenteReponseMs, "gain").groupValues[1].toInt()
            val lu = s.attend(Regex("Gain TX1\\s+overall:\\s*(-?\\d+) dB"), attenteReponseMs, "gain").groupValues[1].toInt()
            when {
                dit != cli -> "bladeRF : gain $dit dB au lieu de $cli"
                lu > cli -> "bladeRF : gain relu $lu dB, plus que les $cli demandés : arrêt"
                else -> { etat = etat.copy(gainDb = g); null }
            }
        }
    } catch (x: IOException) {
        x.message ?: "bladeRF-cli : gain"
    }

    override fun arrete(raison: String) = coupe(generation, raison, "")

    /** A new attenuation while transmitting (clamped as at the start); the one applied, or null. */
    override fun regleGain(db: Double): Double? {
        val s = session
        if (!actif || s == null) return null
        val gen = generation
        val g = gainApplique(db, plafond)
        val e = appliqueGain(s, g)
        if (e != null) { coupe(gen, "", e); return null }
        return g
    }

    // ------------------------------------------------------------ threads

    private fun produit(gen: Int, paires: Int, file: ArrayBlockingQueue<ShortArray>, producteur: (ShortArray) -> Unit) {
        try {
            while (actif && generation == gen) {
                val b = ShortArray(2 * paires)
                producteur(b)
                while (actif && generation == gen && !file.offer(b, 200, TimeUnit.MILLISECONDS)) { /* the bladeRF sets the pace */ }
            }
        } catch (x: InterruptedException) {
        } catch (x: Exception) {
            coupe(gen, "", "modulateur : " + (x.message ?: x.javaClass.simpleName))
        }
    }

    private fun ecrit(gen: Int, f: File, file: ArrayBlockingQueue<ShortArray>) {
        var octets = ByteArray(0)
        try {
            ouvertureEnCours = true
            // Waits until bladeRF-cli opens the pipe (at `tx start`).
            val o = FileOutputStream(f)
            ouvertureEnCours = false
            sortie = o
            while (actif && generation == gen) {
                var b = file.poll()
                if (b == null) {
                    // At the start the tool fills its own buffers at once and empties the queue: not a lateness.
                    if (envoyes.toLong() * pairesParTampon >= (TAMPONS_CLI + 8L) * ECHANTILLONS_CLI) manque()
                    b = file.poll(500, TimeUnit.MILLISECONDS) ?: continue
                }
                if (octets.size != b.size * 2) octets = ByteArray(b.size * 2)
                versSc16q11(b, octets, b.size)
                if (!actif || generation != gen) break
                o.write(octets)
                envoyes++
                dernierEnvoi = horloge()
                etat = etat.copy(tampons = envoyes)
            }
        } catch (x: InterruptedException) {
        } catch (x: IOException) {
            if (actif && generation == gen) {
                val s = session
                runCatching { s?.processus?.waitFor(attenteArretMs, TimeUnit.MILLISECONDS) }
                coupe(gen, "", s?.fatal ?: s?.takeIf { !it.vivant }?.messageMort() ?: "bladeRF-cli a fermé le flux")
            }
        } finally {
            ouvertureEnCours = false
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

    private fun veille(gen: Int, s: SessionBladerf, r: Reglage) {
        while (actif && generation == gen) {
            try { Thread.sleep(PERIODE_MS) } catch (x: InterruptedException) { return }
            val t = horloge()
            val e = etat
            val f = s.fatal
            when {
                t >= e.finMs -> coupe(gen, "durée maximale atteinte", "")
                !s.vivant -> coupe(gen, "", s.messageMort())
                f != null -> coupe(gen, "", f)
                t - dernierEnvoi > chienMs -> coupe(gen, "", "le bladeRF ne prend plus les échantillons (débranché ? bladeRF-cli bloqué ?)")
                synchronized(datesManques) { datesManques.count { t - it <= fenetreManquesMs } } > manquesMax ->
                    coupe(gen, "", "échantillons trop souvent en retard (${e.manques} manques) : PC trop lent ?")
                else -> runCatching { r.garde() }.getOrElse { "garde : " + it.message }?.let { coupe(gen, it, "") }
            }
        }
    }

    /** The end, whatever its cause: `tx stop`, the pipe closed, `quit`, terminated, killed. */
    private fun coupe(gen: Int, raison: String, erreur: String) {
        synchronized(verrouEtat) {
            if (!actif || gen != generation) return
            actif = false
            generation++
        }
        synchronized(verrouCoupe) {
            val s = session
            val f = fifo
            val arrete = if (s == null) true else nettoie(s, f)
            fils.filter { it !== Thread.currentThread() }.forEach { it.interrupt() }
            etat = etat.copy(emet = false, raison = raison.ifEmpty { "erreur" },
                erreur = erreur + (if (!arrete) (if (erreur.isEmpty()) "" else " — ") + "bladeRF-cli NON arrêté proprement : débrancher le bladeRF" else ""))
        }
    }

    /** `tx stop`, the pipe closed (or unblocked), `quit` and the tool ended; true once it has. */
    private fun nettoie(s: SessionBladerf, f: File?): Boolean {
        runCatching { s.envoie("tx stop") }
        // A writer waiting in the pipe's open, the tool never there: let it through.
        if (ouvertureEnCours && f != null) Bladerf.debloque(f, lecteurEnAttente = false)
        val o = sortie
        sortie = null
        if (o != null) Thread({ runCatching { o.close() } }, "satme-bladerf-tx-fin").apply { isDaemon = true; start() }
        val fini = s.ferme(attenteArretMs)
        session = null
        f?.delete()
        f?.parentFile?.takeIf { it.name.startsWith("satme-bladerf") }?.delete()
        fifo = null
        return fini
    }

    companion object {
        /**
         * The tool's USB buffers: small and many (32 768 samples each, 16 in
         * flight: 2 MB), as the device likes them. Buffers of 50 ms went beyond
         * the kernel's 16 MB of USB memory at 6 MS/s (LIBUSB_ERROR_NO_MEM).
         */
        const val ECHANTILLONS_CLI = 32_768
        const val TAMPONS_CLI = 64
        const val TRANSFERTS_CLI = 16
        /** The AD9361's TX attenuation range, whole dB. */
        const val GAIN_MIN_DB = -89
        const val DEBIT_MIN = 520_834L
        const val DEBIT_MAX = 61_440_000L
        const val FREQ_MIN_HZ = 47_000_000L
        const val FREQ_MAX_HZ = 6_000_000_000L
        const val BANDE_MIN = 200_000L
        const val BANDE_MAX = 56_000_000L
        const val DUREE_MAX_MS = 30 * 60_000L
        const val PROFONDEUR = 4
        const val PERIODE_MS = 200L

        private val TX_GAIN = Regex("set gain tx1 (-?\\d{1,2})")
        private val TX_SET = Regex("set (frequency|samplerate|bandwidth) tx1 (\\d{1,11})")
        private val TX_CONFIG = Regex("tx config file=(\\S+) format=bin repeat=1 delay=0 channel=1 samples=\\d+ buffers=\\d+ xfers=\\d+ timeout=\\d+")
        private val TX_SEUL = setOf("tx", "tx start", "tx stop", "quit")

        /**
         * Throws unless [c] is a line this transmitter may send: TX1's gain
         * within 66 − 89 … 66 dB (attenuation 0 at most), its LO, rate and
         * band in range, the stream from a file played once, start, stop,
         * quit. Nothing else: no loopback, no bias tee, no RX, no repeat.
         */
        fun verifie(c: String) {
            if (c in TX_SEUL) return
            TX_GAIN.matchEntire(c)?.let { m ->
                val g = m.groupValues[1].toInt()
                check(g in (Bladerf.DECALAGE_GAIN_TX + GAIN_MIN_DB)..Bladerf.DECALAGE_GAIN_TX) { "bladeRF : gain TX hors bornes : $c" }
                return
            }
            TX_SET.matchEntire(c)?.let { m ->
                val v = m.groupValues[2].toLong()
                val ok = when (m.groupValues[1]) {
                    "frequency" -> v in FREQ_MIN_HZ..FREQ_MAX_HZ
                    "samplerate" -> v in DEBIT_MIN..DEBIT_MAX
                    else -> v in BANDE_MIN..BANDE_MAX
                }
                check(ok) { "bladeRF : valeur hors bornes : $c" }
                return
            }
            if (TX_CONFIG.matches(c)) return
            error("commande refusée pour l'émetteur bladeRF : $c")
        }

        /**
         * 16-bit I/Q (the modulator's, the RMS 10 dB under full scale) to
         * SC16 Q11 little-endian: divided by 16, rounded, held within ±2047.
         */
        fun versSc16q11(src: ShortArray, dst: ByteArray, n: Int) {
            for (k in 0 until n) {
                val v = src[k].toInt()
                val r = ((if (v >= 0) v + 8 else v - 8) / 16).coerceIn(-2047, 2047)
                dst[2 * k] = r.toByte()
                dst[2 * k + 1] = (r shr 8).toByte()
            }
        }
    }
}
