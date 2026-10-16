/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.domain.AnalyseurQo100
import fr.f4ioz.satcombo.domain.Qo100
import fr.f4ioz.satcombo.domain.SignalQo100
import fr.f4ioz.satcombo.domain.SuiviBaliseQo100
import fr.f4ioz.satcombo.sdr.Dsp
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * What each signal on the narrow transponder is ([AnalyseurQo100]), from the
 * receiver's raw IQ while it is on QO-100.
 *
 * Nothing runs unless a page asked ([demande], the route polled) within the
 * last 3 s: the receiver's loop only hands over a copy of its block, the
 * analysis (a 65 536-point FFT every 62 ms) runs on its own thread, which
 * stops 10 s after the last question — and the pistes are forgotten.
 */
class SignauxQo100Pc(private val sdr: SdrPc, private val suivi: SuiviBaliseQo100, private val horloge: () -> Long = System::currentTimeMillis) {

    private class Bloc(val b8: ByteArray?, val b16: ShortArray?, val n: Int, val pll: Long, val t: Long, val apresPerte: Boolean)

    @Volatile private var demandeMs = 0L
    @Volatile private var fil: Thread? = null
    private val file = ArrayBlockingQueue<Bloc>(FILE)
    @Volatile private var perdu = false
    @Volatile private var liste: List<SignalQo100> = emptyList()
    @Volatile private var listeMs = 0L
    @Volatile private var chargeCpu = 0.0
    @Volatile private var correctionHz = 0.0
    @Volatile private var blocsPerdus = 0L

    init { sdr.iqQo100 = { b8, b16, n, pll -> recoit(b8, b16, n, pll) } }

    /** Asked by a page now. */
    val actif: Boolean get() = horloge() - demandeMs < ACTIF_MS

    /** A page wants the list: the analysis started (or kept) for a few seconds more. */
    fun demande() {
        demandeMs = horloge()
        if (fil?.isAlive != true) synchronized(this) {
            if (fil?.isAlive != true) fil = Thread({ boucle() }, "satme-qo100-signaux").apply { isDaemon = true; start() }
        }
    }

    /** From the receiver's loop: a copy of the block, never waiting. */
    private fun recoit(b8: ByteArray?, b16: ShortArray?, n: Int, pll: Long) {
        if (!actif || n <= 0) return
        val bloc = Bloc(b8?.copyOf(n), b16?.copyOf(n), n, pll, horloge(), perdu)
        if (file.offer(bloc)) perdu = false else { perdu = true; blocsPerdus++ }
    }

    private fun boucle() {
        var a: AnalyseurQo100? = null
        var pll = 0L
        var axe: Pair<Double, Double>? = null
        var dernierListe = 0L
        var dernierBloc = 0L
        var ns = 0L; var debut = horloge()
        try {
            while (horloge() - demandeMs < ARRET_MS) {
                val b = file.poll(500, TimeUnit.MILLISECONDS) ?: continue
                val t0 = System.nanoTime()
                val an = a ?: AnalyseurQo100(Dsp.RTL_RATE.toDouble()).also { a = it }
                if (b.pll != pll || axe == null) {
                    // Another PLL: the stream's frequencies moved, the pistes with them.
                    pll = b.pll
                    val ax = sdr.axeQo100(pll)
                    axe = ax
                    val hz = an.hzParCase
                    val sens = if (ax.second < 0) -1.0 else 1.0
                    val k0 = an.taille / 2 + ((Qo100.REGLETTE_BAS_HZ - MARGE_HZ - ax.first) / hz * sens).toInt()
                    val k1 = an.taille / 2 + ((Qo100.REGLETTE_HAUT_HZ + MARGE_HZ - ax.first) / hz * sens).toInt()
                    an.bande(minOf(k0, k1), maxOf(k0, k1))
                    an.reinitialise()
                }
                // Blocks dropped, or none for a while (no page asking): the frame starts again.
                if (b.apresPerte || b.t - dernierBloc > 300L) an.discontinuite()
                dernierBloc = b.t
                val miroir = axe!!.second < 0
                if (b.b16 != null) an.pousse16(b.b16, b.n, b.t, miroir) else if (b.b8 != null) an.pousse8(b.b8, b.n, b.t, miroir)
                val t = horloge()
                if (t - dernierListe >= 1_000L) {
                    dernierListe = t
                    // The axis as the calibration stands now, corrected by the beacon when it was measured lately.
                    val ax = sdr.axeQo100(pll)
                    axe = ax
                    val corr = correction(t)
                    correctionHz = corr
                    liste = an.signaux(t, ax.first - corr)
                    listeMs = t
                }
                ns += System.nanoTime() - t0
                if (t - debut >= 5_000L) { chargeCpu = ns / 1e6 / (t - debut); ns = 0; debut = t }
            }
        } finally {
            file.clear()
            liste = emptyList()
            chargeCpu = 0.0
            fil = null
        }
    }

    /**
     * How far the axis is off, as the middle beacon tells (Hz): the LNB's
     * recent error minus the calibration already applied. Zero when the
     * beacon was not measured lately.
     */
    private fun correction(t: Long): Double {
        val calage = sdr.station.qo100.etat.value.calageHz
        val d = suivi.dernier ?: return 0.0
        if (t - d.t > 60_000L) return 0.0
        val erreur = suivi.erreurRecente() ?: d.erreurHz
        return erreur - calage
    }

    fun json(): JSONObject {
        val t = horloge()
        val o = JSONObject().put("actif", fil != null && sdr.etat.value.actif && sdr.station.qo100.etat.value.aLaCle)
        o.put("quand", listeMs).put("cpu", Math.round(chargeCpu * 1000) / 10.0).put("correction", Math.round(correctionHz))
            .put("perdus", blocsPerdus)
        o.put("signaux", JSONArray(liste.filter { t - listeMs < 5_000L }.map { s ->
            JSONObject().put("id", s.id).put("hz", s.hz).put("largeurHz", s.largeurHz).put("classe", s.classe.cle)
                .put("libelle", s.classe.libelle).put("confiance", s.confiance).put("depuisMs", s.depuisMs)
                .put("vuIlYaMs", s.vuIlYaMs).put("snrDb", s.snrDb).put("details", s.details)
                .put("segment", Qo100.segment(s.hz)?.cle ?: "")
        }))
        return o
    }

    companion object {
        /** A page asking within this keeps the analysis on. */
        const val ACTIF_MS = 3_000L
        /** The thread stops this long after the last question. */
        const val ARRET_MS = 10_000L
        /** About 2 s of blocks waiting at most. */
        const val FILE = 256
        /** Around the band plan: the LNB may be some kilohertz off before the beacon corrects it. */
        const val MARGE_HZ = 25_000L
    }
}
