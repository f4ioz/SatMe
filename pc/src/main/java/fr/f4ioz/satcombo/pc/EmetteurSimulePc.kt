/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.sdr.EmetteurDatv
import fr.f4ioz.satcombo.sdr.EtatEmission
import fr.f4ioz.satcombo.sdr.PlutoEmission
import kotlin.concurrent.thread
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * A transmitter that radiates nothing: the narrow band's « simulation ».
 * It takes the samples at the Pluto's pace, as the board would, keeps the
 * same time-out and asks the same guard every 200 ms — so the PTT, the
 * watchdog, the CW keyer, the memories and the level guard all behave as
 * on the air — and measures what it would have sent (peak and RMS, dBFS
 * of the I/Q). **No device is ever opened.**
 */
class EmetteurSimulePc(
    private val r: PlutoEmission.Reglage,
    private val gainMaxDb: Double,
    private val producteur: (ShortArray) -> Unit,
    private val horloge: () -> Long = System::currentTimeMillis,
    /** False in the tests: the samples pulled by hand ([tire]). */
    boucle: Boolean = true,
) : EmetteurDatv {
    override val nom = "simulation (aucune émission)"
    @Volatile private var emet = true
    @Volatile private var raison = ""
    @Volatile private var gain = borne(r.gainDb)
    @Volatile private var tampons = 0L
    private val debutMs = horloge()
    @Volatile private var finMs = 0L

    /** The last buffer's peak and RMS (dBFS of the I/Q), null before the first. */
    @Volatile var creteDbfs: Double? = null; private set
    @Volatile var rmsDbfs: Double? = null; private set

    private fun borne(db: Double) = minOf(db, gainMaxDb, r.plafondDb, 0.0).coerceAtLeast(PlutoEmission.GAIN_MIN_DB)

    init {
        if (boucle) thread(isDaemon = true, name = "satme-tx-simule") {
            val paires = r.paires.coerceIn(1_024, 65_536)
            val b = ShortArray(2 * paires)
            val parTamponNs = paires * 1_000_000_000L / r.debit
            var prochain = System.nanoTime()
            var gardeMs = 0L
            while (emet) {
                tire(b)
                val t = horloge()
                if (t - debutMs >= r.dureeMs) { arrete("minuterie de l'émetteur"); break }
                if (t - gardeMs >= 200) {
                    gardeMs = t
                    val non = r.garde()
                    if (non != null) { arrete(non); break }
                }
                prochain += parTamponNs
                val attente = (prochain - System.nanoTime()) / 1_000_000L
                if (attente > 0) Thread.sleep(attente) else prochain = System.nanoTime()
            }
        }
    }

    /** One buffer taken and measured. */
    fun tire(b: ShortArray) {
        producteur(b)
        var crete = 0.0; var somme = 0.0
        val n = b.size / 2
        for (i in 0 until n) {
            val re = b[2 * i] / 32768.0; val im = b[2 * i + 1] / 32768.0
            val p = re * re + im * im
            somme += p; if (p > crete) crete = p
        }
        tampons++
        creteDbfs = if (crete > 0) 10 * log10(crete) else -120.0
        rmsDbfs = if (somme > 0) 10 * log10(somme / n) else -120.0
    }

    /** RMS amplitude of the last buffer (0..1), for the tests. */
    val rms: Double get() = rmsDbfs?.let { sqrt(Math.pow(10.0, it / 10)) } ?: 0.0

    override fun etatCommun() = EtatEmission(emet, debutMs, finMs, r.frequenceHz, gain, tampons, 0, raison, "")

    override fun arrete(raison: String) {
        if (!emet) return
        emet = false; this.raison = raison; finMs = horloge()
    }

    override fun regleGain(db: Double): Double? { gain = borne(db); return gain }
}
