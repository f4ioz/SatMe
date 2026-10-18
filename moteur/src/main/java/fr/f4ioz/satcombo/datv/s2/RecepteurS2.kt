/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv.s2

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs

/** What the software receiver says, at one moment. */
data class EtatS2(
    /** 0 searching, 1 headers seen, 2 locked on DVB-S2 frames. */
    val etape: Int = 0,
    val merDb: Double? = null,
    /** The PLS MODCOD (table 12), −1 before any; the TYPE (short frames, pilots). */
    val modcod: Int = -1,
    val courtes: Boolean? = null,
    val pilotes: Boolean? = null,
    /** The carrier found, from the frequency tuned (Hz). */
    val decalageHz: Double = 0.0,
    /** The symbol rate found (symbols/s). */
    val rsMesure: Double = 0.0,
    val tramesDonnees: Long = 0,
    val tramesVides: Long = 0,
    val tramesAutres: Long = 0,
    val ldpcEchecs: Long = 0,
    val bitsCorriges: Long = 0,
    val bchCorriges: Long = 0,
    val tramesSautees: Long = 0,
    val paquets: Long = 0,
    val paquetsFaux: Long = 0,
    val iterationsMoyennes: Double = 0.0,
    val rolloff: Double? = null,
    /** How far the spectrum's search found the band above the rest (standard deviations). */
    val contraste: Double = 0.0,
    val verrouillages: Long = 0,
)

/**
 * A DVB-S2 receiver in software (ETSI EN 302 307-1, receive side), from an
 * SDR's samples to the transport stream:
 *
 * [FrontalS2] (carrier search, matched filter, symbol clock) and
 * [TrameurS2] (frames, carrier, demapping) on the caller's thread, which
 * feeds [echantillons]; the FEC ([DecodeurFec]: LDPC, BCH) on a pool of
 * threads, frames decoded side by side; [FluxTsS2] puts the stream back in
 * order and gives it to [sortieTs].
 *
 * QPSK and 8PSK, every code rate, normal and short frames, pilots or not;
 * dummy frames skipped; 16APSK/32APSK frames recognised and skipped.
 * When the decoders fall behind, the oldest work is kept and new frames are
 * dropped (counted), so the picture stalls rather than drifts.
 */
class RecepteurS2(
    val fs: Double,
    val rs: Double,
    sortieTs: (ByteArray, Int) -> Unit,
    val fils: Int = filsParDefaut(),
    alpha: Double = 0.35,
    plageHz: Double = fs / 2,
    /** The LDPC's iterations at most (fewer when the decoders are late). */
    val iterationsMax: Int = 50,
    /** Live (an SDR): late decoders skip frames or iterate less. Off (a file, the tests): every frame waits its turn, fully decoded. */
    val tempsReel: Boolean = true,
) {
    val frontal = FrontalS2(fs, rs, alpha, plageHz)
    private val flux = FluxTsS2(sortieTs)
    private val pool = Executors.newFixedThreadPool(fils) { r -> Thread(r, "satme-s2-fec").apply { isDaemon = true } }
    private val decodeurs = ThreadLocal.withInitial { DecodeurFec() }
    private val enCours = ArrayBlockingQueue<Future<BbFrame>>(4 * fils + 2)
    @Volatile private var ouvert = true
    private var sauter = false

    @Volatile private var ldpcEchecs = 0L
    @Volatile private var bitsCorriges = 0L
    @Volatile private var bchCorriges = 0L
    @Volatile private var tramesSautees = 0L
    @Volatile private var iterations = 0.0
    /** Frames failed in a row: a lock on noise, or a signal gone; the lock is given up after [ECHECS_DECROCHE]. */
    @Volatile private var echecsSuite = 0
    @Volatile private var lacher = false
    @Volatile private var soumis = 0L
    @Volatile private var traites = 0L

    val trameur = TrameurS2(frontal) { t -> soumets(t) }

    private val ordonne = Thread({ boucleSortie() }, "satme-s2-ts").apply { isDaemon = true; start() }

    init { frontal.acquiers() }

    private fun soumets(t: TrameS2) {
        if (!ouvert) return
        if (tempsReel && enCours.remainingCapacity() == 0) { tramesSautees++; sauter = true; return }
        val tr = if (sauter) TrameS2(t.numero, t.modcod, t.llr, true) else t
        sauter = false
        // Late: fewer iterations rather than frames dropped.
        val n = if (tempsReel && enCours.size > enCours.remainingCapacity()) iterationsMax / 3 else iterationsMax
        soumis++
        enCours.put(pool.submit<BbFrame> { decodeurs.get().decode(tr, n) })
    }

    private fun boucleSortie() {
        while (ouvert) {
            val f = enCours.poll(200, TimeUnit.MILLISECONDS) ?: continue
            val b = runCatching { f.get() }.getOrNull()
            if (b != null) {
                if (b.octets == null) { if (++echecsSuite >= ECHECS_DECROCHE) lacher = true } else echecsSuite = 0
                if (b.octets == null) ldpcEchecs++ else { bitsCorriges += b.bitsCorriges; bchCorriges += b.bchCorriges.coerceAtLeast(0) }
                trameur.trace?.invoke("FEC trame ${b.numero} ${b.modcod.libelle} : " + (if (b.octets == null) "ÉCHEC" else "ok") + " en ${b.iterations} itérations, BCH ${b.bchCorriges}")
                iterations += 0.05 * (b.iterations - iterations)
                runCatching { flux.trame(b) }
            }
            traites++
        }
    }

    private var tampon = FloatArray(0)

    /** [n] shorts of I, Q pairs (any full scale). */
    fun echantillons(iq: ShortArray, n: Int) {
        if (tampon.size < n) tampon = FloatArray(n)
        for (k in 0 until n) tampon[k] = iq[k] * (1f / 32768f)
        echantillons(tampon, n)
    }

    /** [n] floats of I, Q pairs. */
    fun echantillons(iq: FloatArray, n: Int) {
        if (!ouvert) return
        flux.rolloff?.let { if (abs(it - frontal.alpha) > 1e-6) frontal.regleAlpha(it) }
        if (lacher) { lacher = false; echecsSuite = 0; if (trameur.etape == 2) trameur.lache() }
        frontal.traite(iq, n / 2) { i, q -> trameur.symbole(i, q) }
        // The carrier gone far from the filter's centre (a drifting LNB): searched again.
        if (trameur.etape == 2 && abs(trameur.omegaBoucle) > 0.3 && !frontal.enAcquisition) { trameur.lache(); frontal.acquiers() }
    }

    fun etat(): EtatS2 {
        val t = trameur
        val ty = t.typeTrame
        return EtatS2(
            etape = t.etape, merDb = t.merDb, modcod = t.modcodCode,
            courtes = if (t.modcodCode >= 0) ty and 2 != 0 else null, pilotes = if (t.modcodCode >= 0) ty and 1 != 0 else null,
            decalageHz = frontal.ncoHz + (if (t.etape == 2) t.omegaBoucle * rs / (2 * PI) else 0.0),
            rsMesure = fs / frontal.periode,
            tramesDonnees = t.tramesDonnees, tramesVides = t.tramesVides, tramesAutres = t.tramesAutres,
            ldpcEchecs = ldpcEchecs, bitsCorriges = bitsCorriges, bchCorriges = bchCorriges, tramesSautees = tramesSautees,
            paquets = flux.paquets, paquetsFaux = flux.paquetsFaux, iterationsMoyennes = iterations,
            rolloff = flux.rolloff, contraste = frontal.acqContraste, verrouillages = t.verrouillages)
    }

    /** The last data symbols after the carrier (I, Q interleaved). */
    fun points(): FloatArray = synchronized(trameur.points) { trameur.points.copyOf(2 * trameur.nPoints) }

    /** Waits until every frame handed to the decoders went through (the tests' end). */
    fun vide(attenteMs: Long = 30_000) {
        val fin = System.currentTimeMillis() + attenteMs
        while (traites < soumis && System.currentTimeMillis() < fin) Thread.sleep(5)
    }

    fun ferme() {
        ouvert = false
        pool.shutdownNow()
        runCatching { ordonne.join(500) }
    }

    companion object {
        const val ECHECS_DECROCHE = 8

        fun filsParDefaut(): Int = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(1, 4)

        /**
         * The SDR's rate for [rs]: two and a half samples a symbol at least, and room
         * for the carrier to be off by [ecartHz] either way.
         */
        fun debitPour(rs: Int, ecartHz: Int = 100_000): Int {
            val utile = (1.35 * rs + 2.0 * ecartHz)
            val d = maxOf(2.5 * rs, utile)
            // A round number of kHz.
            return (Math.ceil(d / 1000.0) * 1000).toInt()
        }
    }
}
