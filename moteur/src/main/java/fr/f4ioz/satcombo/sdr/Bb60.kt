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
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** The Signal Hound library could not be loaded (not installed, too old, Java too old): said in French, nothing else stops. */
class Bb60Absent(message: String) : IOException(message)

/** A BB60 API call answered an error ([statut] < 0), its message in French. */
class Bb60Erreur(val statut: Int, message: String) : IOException(message)

/**
 * The calls SatMe makes to Signal Hound's BB60 API (`bb_api.h`), one for one,
 * the statuses as the API returns them (`bbStatus`: < 0 an error, > 0 a
 * warning). The real one is the library loaded at run time (SatMe-PC's
 * `Bb60Ffm`); the tests give a fake. The calls that only read return their
 * value and throw [Bb60Erreur] on an error.
 *
 * Only what receiving I/Q needs, and the device's own diagnostics: nothing of
 * the tracking generator, the UART or the I/O ports.
 */
interface Bb60Natif {
    /** bbGetAPIVersion: "5.0.12". */
    fun versionApi(): String
    /** bbGetErrorString. */
    fun texteStatut(statut: Int): String
    /** bbGetSerialNumberList2: (serial number, device type) of each BB60 plugged in. */
    fun appareils(): List<Pair<Int, Int>>
    /** bbOpenDevice (serial 0) or bbOpenDeviceBySerialNumber: the handle (about 3 s). */
    fun ouvre(serie: Int): Int
    fun ferme(h: Int): Int
    fun typeAppareil(h: Int): Int
    fun serie(h: Int): Long
    fun firmware(h: Int): Int
    /** bbGetDeviceDiagnostics: temperature (°C), USB voltage (V), USB current (A). */
    fun diagnostics(h: Int): FloatArray
    fun niveauReference(h: Int, dBm: Double): Int
    /** bbConfigureGainAtten: −1 (automatic, from the reference level) or 0 to 3 each. */
    fun gainAttenuation(h: Int, gain: Int, attenuation: Int): Int
    fun centreIq(h: Int, hz: Double): Int
    fun configureIq(h: Int, decimation: Int, bandeHz: Double): Int
    /** bbConfigureIQDataType(bbDataType16sc): full-scale signed 16-bit pairs. */
    fun iq16(h: Int): Int
    /** bbInitiate(BB_STREAMING, BB_STREAM_IQ). */
    fun demarreIq(h: Int): Int
    /** bbAbort. */
    fun arrete(h: Int): Int
    /** bbQueryIQParameters: the rate (S/s) and bandwidth (Hz) obtained. */
    fun parametresIq(h: Int): DoubleArray
    /** bbGetIQUnpacked: [paires] pairs into [dest], contiguous with the previous call (no purge). */
    fun lisIq(h: Int, dest: ShortArray, paires: Int, lecture: Lecture): Int

    /** What bbGetIQUnpacked says besides the samples. */
    class Lecture {
        var restants = 0
        /** The API dropped samples (its buffer wrapped: the PC too slow). */
        var perte = false
        /** The first sample's time: seconds since the epoch, nanoseconds (the PC's clock, a GPS's on Windows). */
        var secondes = 0
        var nanos = 0
    }
}

/** What SatMe knows of the BB60 API: its codes, its rates, its messages. */
object Bb60 {
    const val PAS_D_ERREUR = 0
    const val PARAMETRE_AJUSTE = 1
    const val SATURATION_CAN = 2
    const val BORNE_HAUTE = 4
    const val BORNE_BASSE = 5
    const val NON_CALIBRE = 6
    const val RUPTURE = 7
    const val NON_OUVERT = -1
    const val ALLOCATION = -6
    const val INVALIDE = -9
    const val TRAMES = -13
    const val CONNEXION = -14
    const val DELAI_USB = -15
    const val LIBUSB = -18
    const val FREQUENCE = -103
    const val ATTENUATION = -104
    const val GAIN = -105
    const val BANDE = -106
    const val REFERENCE = -111

    const val BB60A = 1
    const val BB60C = 2
    const val BB60D = 3

    /** BB_MIN_FREQ; the BB60C/D's specified top (the API takes up to 6.4 GHz). */
    const val FREQ_MIN_HZ = 9_000L
    const val FREQ_MAX_HZ = 6_000_000_000L
    /** The undecimated I/Q rate (S/s). */
    const val DEBIT_MAX = 40_000_000.0
    const val REFERENCE_MAX_DBM = 20.0
    const val REFERENCE_MIN_DBM = -130.0
    /** Below this the readings may be out of specification (BB_MIN_USB_VOLTAGE). */
    const val TENSION_USB_MIN = 4.4f

    /** The widest filter for each decimation (the API's table « I/Q Filtering and Bandwidth Limitations »). */
    val BANDE_MAX: Map<Int, Double> = mapOf(1 to 27e6, 2 to 17.8e6, 4 to 8e6, 8 to 3.75e6, 16 to 2e6, 32 to 1e6, 64 to 0.5e6,
        128 to 0.25e6, 256 to 140e3, 512 to 65e3, 1024 to 30e3, 2048 to 15e3, 4096 to 8e3, 8192 to 4e3)

    /** BB60D gain and attenuation steps (dB), index 0 to 3. */
    val GAINS_BB60D = intArrayOf(0, 5, 15, 20)
    val ATTENUATIONS = intArrayOf(0, 10, 20, 30)

    fun modele(type: Int): String = when (type) { BB60A -> "BB60A"; BB60C -> "BB60C"; BB60D -> "BB60D"; else -> "BB60" }

    /**
     * How the BB60 gives [debit] S/s: its rate (40 MS/s ÷ a power of two), its
     * filter, and whether SatMe resamples. An exact rate as it comes; any other
     * from at least twice faster (so the cubic interpolation stays clean),
     * the filter kept within 90 % of the rate asked.
     */
    data class Plan(val decimation: Int, val natifHz: Double, val bandeHz: Double, val sortieHz: Double) {
        val reechantillonne: Boolean get() = natifHz != sortieHz
    }

    fun plan(debit: Int): Plan {
        val r = debit.toDouble().coerceIn(DEBIT_MAX / 8192, DEBIT_MAX)
        var d = 1
        while (d <= 8192) {
            if (DEBIT_MAX / d == r) return Plan(d, r, BANDE_MAX.getValue(d), r)
            d *= 2
        }
        val cible = if (2 * r <= DEBIT_MAX) 2 * r else r
        d = 1
        while (d < 8192 && DEBIT_MAX / (d * 2) >= cible) d *= 2
        return Plan(d, DEBIT_MAX / d, minOf(BANDE_MAX.getValue(d), 0.9 * r), r)
    }

    /** The API lost the device: to be closed and opened again. */
    fun perdu(s: Int): Boolean = s == CONNEXION || s == TRAMES || s == DELAI_USB || s == LIBUSB || s == NON_OUVERT || s == INVALIDE

    /** An error status in French ([texte]: the API's own words, kept after it). */
    fun message(s: Int, texte: String = "", ouverture: Boolean = false): String {
        val f = when (s) {
            NON_OUVERT -> if (ouverture) "aucun BB60 trouvé : le brancher sur un port USB 3 (LED verte), vérifier la règle udev du SDK (Linux), et qu'aucun autre programme (Spike) ne le tienne"
                else "BB60 non ouvert"
            ALLOCATION -> "BB60 : déjà ouvert, ou trop d'appareils ouverts"
            INVALIDE -> "BB60 : appareil invalide (débranché ?)"
            TRAMES -> "BB60 : trames USB corrompues (câble, port USB 3, alimentation)"
            CONNEXION -> "BB60 : liaison USB perdue (débranché ?)"
            DELAI_USB -> "BB60 : délai USB dépassé (port USB 3 ?)"
            LIBUSB -> "BB60 : erreur libusb (libusb-1.0 installée ? droits USB : règle udev du SDK)"
            FREQUENCE -> "BB60 : fréquence hors de sa gamme (9 kHz – 6 GHz)"
            REFERENCE -> "BB60 : niveau de référence refusé (au plus +20 dBm)"
            GAIN -> "BB60 : gain refusé"
            ATTENUATION -> "BB60 : atténuation refusée"
            BANDE -> "BB60 : bande I/Q refusée"
            else -> "BB60 : erreur $s"
        }
        return if (texte.isBlank() || s == NON_OUVERT && ouverture) f else "$f ($texte)"
    }

    /** A warning status in French, or null. */
    fun avertissement(s: Int): String? = when (s) {
        PARAMETRE_AJUSTE -> "BB60 : réglage ajusté par l'API"
        SATURATION_CAN -> "BB60 : saturation du CAN — relever le niveau de référence"
        BORNE_HAUTE, BORNE_BASSE -> "BB60 : réglage ramené dans ses bornes"
        NON_CALIBRE -> "BB60 : appareil non calibré"
        RUPTURE -> "BB60 : rupture du flux I/Q"
        else -> null
    }
}

/**
 * A Signal Hound BB60 (BB60C, BB60D) as the reception's receiver, through
 * its API ([Bb60Natif]): I/Q streaming at a centre frequency, 16-bit
 * pairs, full scale.
 *
 * - **The rate.** The BB60 gives 40 MS/s divided by a power of two, filtered
 *   by the API (on the PC); any other rate asked ([Dsp.RTL_RATE]) is made here
 *   from one at least twice faster ([Bb60.plan], [ReechantillonneurIq]).
 * - **Tuning.** A new centre is a new bbInitiate (the API takes its settings
 *   only then): asked from any thread, done by the reading thread between two
 *   reads, the samples of the old centre dropped. The Doppler's software
 *   share ([fr.f4ioz.satcombo.domain.DopplerTuner]) keeps retunes rare.
 * - **Level.** The reference level (dBm) sets the gain and attenuation
 *   (automatic), or both are forced (0 to 3 each).
 * - **Loss.** The device unplugged (a USB error), it is closed and opened
 *   again, after 1 s, then 2, 4… up to [repriseMaxMs]; meanwhile the reception
 *   gets nothing (no error) and [avertissement] says why.
 *
 * One thread only calls the API (it is not thread-safe). Receive only: the
 * BB60 is a receiver, and nothing here drives its outputs.
 */
class Bb60Sdr private constructor(
    private val natif: Bb60Natif,
    private val serieVoulue: Int,
    @Volatile private var h: Int,
    reglages: Reglages,
    private val horloge: () -> Long,
    private val repriseMaxMs: Long,
) : SourceIq {

    data class Reglages(
        /** dBm, −130 to +20: about 5 dB above the strongest signal expected. */
        val referenceDbm: Double = -30.0,
        /** −1: automatic (from the reference level); 0 to 3 each, both forced together. */
        val gain: Int = -1,
        val attenuation: Int = -1,
    ) {
        val automatique: Boolean get() = gain < 0 || attenuation < 0
    }

    /** What the device is: its model, serial number, firmware, and the API's version. */
    data class Appareil(val type: Int, val serie: Long, val firmware: Int, val versionApi: String) {
        val modele: String get() = Bb60.modele(type)
        val libelle: String get() = "Signal Hound $modele n° $serie · firmware $firmware · API $versionApi"
    }

    /** The device's own measurements, read every few seconds while it streams. */
    data class Diagnostic(val temperatureC: Float, val tensionV: Float, val courantA: Float, val lueMs: Long)

    @Volatile var appareil: Appareil = lisAppareil(h); private set
    @Volatile var diagnostic: Diagnostic? = null; private set
    /** The diagnostics read now (from any thread: the library's calls are serialized by it, [Bb60Natif]). */
    fun diagnosticImmediat(): Diagnostic? = runCatching { natif.diagnostics(h).let { Diagnostic(it[0], it[1], it[2], horloge()) } }.getOrNull()
    val description: String get() = "Signal Hound " + appareil.modele + (plan?.let { " · " + fmtDebit(it.natifHz) + (if (it.reechantillonne) " → " + fmtDebit(it.sortieHz) else "") } ?: "")

    @Volatile var reglages: Reglages = reglages
        set(v) { field = v; demande++ }
    @Volatile var erreur: String? = null; private set
    /** Why nothing comes now (the device lost), or the last warning; empty: all well. */
    @Volatile var avertissement: String = ""; private set
    @Volatile var ouvert = true; private set
    @Volatile var connecte = true; private set
    @Volatile var reprises = 0; private set
    /** Blocks the API dropped (its buffer wrapped), and those dropped here (the processing late). */
    @Volatile var pertesApi = 0L; private set
    @Volatile var pertesIci = 0L; private set
    @Volatile var saturations = 0L; private set
    /** The last block's first sample: nanoseconds since the epoch (0: none yet). */
    @Volatile var horodatageNs = 0L; private set
    /** The plan in use (null until a rate is asked) and the rate the API reports. */
    @Volatile var plan: Bb60.Plan? = null; private set
    @Volatile var debitApi = 0.0; private set
    @Volatile var bandeApi = 0.0; private set

    override val perdus: Long get() = pertesApi + pertesIci
    @Volatile override var octets = 0L; private set
    override val iq16: Boolean get() = true

    @Volatile private var debitVoulu = 0
    @Volatile private var centreVoulu = 0L
    @Volatile private var ppm = 0
    @Volatile private var demande = 0
    private val file = ArrayBlockingQueue<ShortArray>(PROFONDEUR)
    /** The block being handed out (the reading side only). */
    private var reste: ShortArray? = null
    private var resteDe = 0
    private lateinit var lecteur: Thread

    private fun demarreLecteur() {
        lecteur = Thread({ boucle() }, "satme-bb60").apply { isDaemon = true; start() }
    }

    override fun setSampleRate(rate: Int): Double {
        debitVoulu = rate
        plan = Bb60.plan(rate)
        demande++
        return plan!!.sortieHz
    }

    /** No correction in the BB60: the centre asked moved by the error, as for the bladeRF. */
    override fun setFreqCorrection(ppm: Int) { if (ppm != this.ppm) { this.ppm = ppm; demande++ } }

    override fun setCenterFreq(hz: Long): Long {
        if (hz == centreVoulu) return hz
        centreVoulu = hz
        demande++
        return hz
    }

    /** The SDR tab's gain is not the BB60's: its reference level and steps are its own settings ([reglages]). */
    override fun setGain(tenthDb: Int?) {}
    override fun setAgc(on: Boolean) {}

    override fun read16(buf: ShortArray, timeoutMs: Int): Int {
        var b = reste
        var de = resteDe
        if (b == null) {
            b = file.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS) ?: return if (ouvert || file.isNotEmpty()) 0 else -1
            de = 0
        }
        val n = minOf(b.size - de, buf.size) and 1.inv()
        System.arraycopy(b, de, buf, 0, n)
        if (de + n < b.size) { reste = b; resteDe = de + n } else { reste = null; resteDe = 0 }
        return n
    }

    /** As bytes for a reader without 16 bits (unsigned, centred on 128): the top byte of each value. */
    override fun read(buf: ByteArray, timeoutMs: Int): Int {
        val s = ShortArray(buf.size)
        val n = read16(s, timeoutMs)
        for (k in 0 until maxOf(n, 0)) buf[k] = ((s[k].toInt() shr 8) + 128).toByte()
        return n
    }

    override fun close() {
        if (!ouvert) return
        ouvert = false
        file.clear()
        if (::lecteur.isInitialized) { lecteur.interrupt(); runCatching { lecteur.join(3_000) } }
    }

    // ------------------------------------------------------------ the reading thread: the only one calling the API

    private var applique = -1
    private var enFlux = false
    private var reech: ReechantillonneurIq? = null
    private var natifBuf = ShortArray(0)
    private var sortieBuf = ShortArray(0)
    private var paires = 0
    private val lecture = Bb60Natif.Lecture()
    private var dernierDiag = 0L

    private fun boucle() {
        var attente = 0L
        try {
            while (ouvert) {
                if (!connecte) {
                    try { Thread.sleep(attente) } catch (_: InterruptedException) { if (!ouvert) break }
                    if (!ouvert) break
                    attente = if (attente == 0L) 1_000L else minOf(attente * 2, repriseMaxMs)
                    try {
                        h = natif.ouvre(serieVoulue)
                        connecte = true
                        appareil = lisAppareil(h)
                        reprises++
                        applique = -1
                        avertissement = ""
                    } catch (x: IOException) {
                        avertissement = "BB60 perdu (" + (x.message ?: "?") + ") : nouvel essai dans " + (attente / 1000) + " s…"
                    }
                    continue
                }
                val v = demande
                if (v != applique) {
                    applique = v
                    if (!configure()) continue
                }
                if (!enFlux) { try { Thread.sleep(20) } catch (_: InterruptedException) {}; continue }
                val s = natif.lisIq(h, natifBuf, paires, lecture)
                if (s < 0) { echec(s, "lecture"); continue }
                if (s == Bb60.SATURATION_CAN) saturations++
                if (lecture.perte || s == Bb60.RUPTURE) pertesApi++
                horodatageNs = lecture.secondes.toLong() * 1_000_000_000L + lecture.nanos
                octets += paires * 4L
                // A retune asked meanwhile: these samples are of the old centre.
                if (demande != applique) continue
                pousse()
                val t = horloge()
                if (t - dernierDiag >= DIAGNOSTIC_MS) {
                    dernierDiag = t
                    runCatching { natif.diagnostics(h) }.getOrNull()?.let { d ->
                        diagnostic = Diagnostic(d[0], d[1], d[2], t)
                        if (d[1] in 0.1f..Bb60.TENSION_USB_MIN && avertissement.isEmpty())
                            avertissement = "BB60 : tension USB basse (" + String.format(java.util.Locale.FRANCE, "%.2f", d[1]) + " V) — câble, port USB 3"
                    }
                }
            }
        } catch (x: Exception) {
            erreur = "BB60 : " + (x.message ?: x.javaClass.simpleName)
        } finally {
            ouvert = false
            if (connecte) { runCatching { natif.arrete(h) }; runCatching { natif.ferme(h) } }
            connecte = false
        }
    }

    /** The settings given to the API, then bbInitiate; false when the stream did not start. */
    private fun configure(): Boolean {
        val p = plan
        val centre = centreVoulu
        if (p == null || centre <= 0) { enFlux = false; return true }
        val lo = Math.round(centre / (1.0 + ppm * 1e-6))
        if (lo !in Bb60.FREQ_MIN_HZ..Bb60.FREQ_MAX_HZ) {
            avertissement = "fréquence hors de la gamme du BB60 (9 kHz – 6 GHz)"
            return true
        }
        val r = reglages
        runCatching { natif.arrete(h) }
        enFlux = false
        file.clear()
        val etapes = listOf<() -> Int>(
            { natif.iq16(h) },
            { natif.niveauReference(h, r.referenceDbm.coerceIn(Bb60.REFERENCE_MIN_DBM, Bb60.REFERENCE_MAX_DBM)) },
            { if (r.automatique) natif.gainAttenuation(h, -1, -1) else natif.gainAttenuation(h, r.gain.coerceIn(0, 3), r.attenuation.coerceIn(0, 3)) },
            { natif.centreIq(h, lo.toDouble()) },
            { natif.configureIq(h, p.decimation, p.bandeHz) },
            { natif.demarreIq(h) },
        )
        var averti: String? = null
        for (e in etapes) {
            val s = e()
            if (s < 0) { echec(s, "réglage"); return false }
            Bb60.avertissement(s)?.let { averti = it }
        }
        val q = runCatching { natif.parametresIq(h) }.getOrNull()
        debitApi = q?.get(0) ?: p.natifHz
        bandeApi = q?.get(1) ?: p.bandeHz
        val natifHz = if (debitApi > 0) debitApi else p.natifHz
        reech = if (natifHz != p.sortieHz) ReechantillonneurIq(natifHz, p.sortieHz) else null
        // About 20 ms a read (at least 1024 pairs, at most 256k).
        paires = (natifHz / 50).toInt().coerceIn(1024, 262_144)
        if (natifBuf.size < 2 * paires) natifBuf = ShortArray(2 * paires)
        val maxSortie = reech?.maxSortie(2 * paires) ?: (2 * paires)
        if (sortieBuf.size < maxSortie) sortieBuf = ShortArray(maxSortie)
        avertissement = averti ?: ""
        enFlux = true
        return true
    }

    /** An error from the API: the device lost (opened again later), or the reception ends with it. */
    private fun echec(s: Int, quoi: String) {
        enFlux = false
        val texte = runCatching { natif.texteStatut(s) }.getOrDefault("")
        if (Bb60.perdu(s)) {
            runCatching { natif.ferme(h) }
            connecte = false
            file.clear()
            avertissement = Bb60.message(s, texte) + " : reconnexion…"
        } else {
            erreur = Bb60.message(s, texte) + " ($quoi)"
            ouvert = false
        }
    }

    /** The block read, resampled when needed, cut to the reception's blocks and queued (the oldest dropped when late). */
    private fun pousse() {
        val r = reech
        val (src, n) = if (r == null) natifBuf to 2 * paires else sortieBuf to r.traite(natifBuf, 2 * paires, sortieBuf)
        var k = 0
        while (k < n) {
            val m = minOf(BLOC, n - k)
            val b = src.copyOfRange(k, k + m)
            if (!file.offer(b)) { file.poll(); pertesIci++; file.offer(b) }
            k += m
        }
    }

    private fun lisAppareil(h: Int): Appareil = Appareil(
        runCatching { natif.typeAppareil(h) }.getOrDefault(0), runCatching { natif.serie(h) }.getOrDefault(0L),
        runCatching { natif.firmware(h) }.getOrDefault(0), runCatching { natif.versionApi() }.getOrDefault("?"))

    companion object {
        /** Values (shorts) per block queued: the reception's own block. */
        const val BLOC = 16_384
        /** About 1.5 s at the reception's rate. */
        const val PROFONDEUR = 96
        const val DIAGNOSTIC_MS = 2_000L

        /**
         * Opens the BB60 with [serie] (0: the first found), about 3 s; an
         * [IOException] in French when none answers. The stream starts once a
         * rate and a centre are asked.
         */
        fun ouvre(natif: Bb60Natif, serie: Int = 0, reglages: Reglages = Reglages(),
                  horloge: () -> Long = System::currentTimeMillis, repriseMaxMs: Long = 10_000L): Bb60Sdr {
            val h = try { natif.ouvre(serie) } catch (x: Bb60Erreur) {
                throw IOException(Bb60.message(x.statut, runCatching { natif.texteStatut(x.statut) }.getOrDefault(""), ouverture = true))
            }
            return Bb60Sdr(natif, serie, h, reglages, horloge, repriseMaxMs).also { it.demarreLecteur() }
        }

        fun fmtDebit(hz: Double): String = if (hz >= 1e6) String.format(java.util.Locale.FRANCE, "%.4g MS/s", hz / 1e6)
            else String.format(java.util.Locale.FRANCE, "%.4g kS/s", hz / 1e3)
    }
}
