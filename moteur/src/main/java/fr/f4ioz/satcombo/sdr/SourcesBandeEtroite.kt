/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sdr

import fr.f4ioz.satcombo.cw.DecodeurCw
import fr.f4ioz.satcombo.phonie.Reechantillonneur
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Live audio for a transmitter that must never wait: a microphone's frames
 * (a browser's, by the network) or a sound card's blocks come in bursts; the
 * modulator takes samples at its own steady pace ([lis]).
 *
 * - Short of samples: silence fills the gap (the transmitter never starves,
 *   [manques] counts the gaps).
 * - Too many (the sender ahead of the transmitter): the oldest dropped down
 *   to [cibleMs] — the voice never lags behind more than that.
 *
 * Any input rate is brought to [debit] here.
 */
class TamponAudio(
    val debit: Int = 48_000,
    /** Kept at most (ms) before the oldest is dropped. */
    private val maxMs: Int = 400,
    /** Brought back to this (ms) when over [maxMs]. */
    private val cibleMs: Int = 120,
) : (ShortArray) -> Int {
    private val anneau = ShortArray(debit * 2)
    private var lecture = 0
    private var ecriture = 0
    private var n = 0
    private var reech: Reechantillonneur? = null

    @Volatile var manques = 0L; private set
    @Volatile var jetes = 0L; private set
    @Volatile var recus = 0L; private set

    /** In the buffer now (ms). */
    val retardMs: Int get() = synchronized(this) { n * 1000 / debit }

    /** [count] samples of [pcm] at [taux] in. */
    fun pousse(pcm: ShortArray, count: Int = pcm.size, taux: Int = debit) {
        val src: ShortArray
        val k: Int
        if (taux == debit) { src = pcm; k = count } else {
            val r = reech?.takeIf { it.entree == taux } ?: Reechantillonneur(taux, debit).also { reech = it }
            val y = r.pousse(pcm, count)
            src = ShortArray(y.size) { (y[it].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }; k = y.size
        }
        synchronized(this) {
            for (i in 0 until k) {
                anneau[ecriture] = src[i]
                ecriture = (ecriture + 1) % anneau.size
                if (n == anneau.size) lecture = (lecture + 1) % anneau.size else n++
            }
            recus += k
            if (n > debit * maxMs / 1000) {
                val garde = debit * cibleMs / 1000
                val jete = n - garde
                lecture = (lecture + jete) % anneau.size; n = garde; jetes += jete
            }
        }
    }

    /** Fills [dst] entirely: the samples waiting, silence for the rest. */
    override fun invoke(dst: ShortArray): Int = lis(dst)

    fun lis(dst: ShortArray): Int {
        synchronized(this) {
            val k = minOf(n, dst.size)
            for (i in 0 until k) { dst[i] = anneau[lecture]; lecture = (lecture + 1) % anneau.size }
            n -= k
            if (k < dst.size) { dst.fill(0, k, dst.size); if (recus > 0) manques++ }
        }
        return dst.size
    }

    fun vide() = synchronized(this) { lecture = 0; ecriture = 0; n = 0 }
}

/**
 * CW as audio: a tone of [tonHz], keyed with raised-cosine edges of
 * [rampeMs] (no clicks: the spectrum stays within ±100 Hz), by text queued
 * ([envoie], at [wpm]) or by a straight key ([touche]). Silence between.
 * Never ends by itself: the transmitter's own guards stop it.
 */
class ManipulateurCw(
    val debit: Int = 48_000,
    @Volatile var wpm: Int = 20,
    val tonHz: Double = 700.0,
    private val niveau: Double = 0.9,
    private val rampeMs: Double = 5.0,
) : (ShortArray) -> Int {
    private val elements = ArrayDeque<Pair<Boolean, Int>>()
    private var restant = 0
    private var bas = false
    @Volatile private var cle = false
    private var enveloppe = 0.0
    private val pasEnv = 1.0 / (rampeMs * debit / 1000.0)
    private var phase = 0.0

    /** Text still to send (characters queued, roughly). */
    @Volatile var enAttente = 0; private set

    /** Queues [texte] at the current speed; a word gap before it when something is already queued. */
    @Synchronized
    fun envoie(texte: String) {
        val m = DecodeurCw.manipulation(texte, wpm.coerceIn(5, 50))
        if (m.isEmpty()) return
        if (elements.isNotEmpty() || restant > 0) elements.addLast(false to (7 * 1200.0 / wpm * debit / 1000).roundToInt())
        for ((b, ms) in m) elements.addLast(b to (ms * debit / 1000).roundToInt())
        elements.addLast(false to (3 * 1200.0 / wpm * debit / 1000).roundToInt())
        enAttente += texte.length
    }

    /** The straight key: down while [on]. */
    fun touche(on: Boolean) { cle = on }

    /** Everything queued dropped (the key released too). */
    @Synchronized
    fun annule() { elements.clear(); restant = 0; bas = false; cle = false; enAttente = 0 }

    /** True while text is being sent. */
    val occupe: Boolean @Synchronized get() = restant > 0 || elements.isNotEmpty()

    @Synchronized
    override fun invoke(dst: ShortArray): Int {
        val w = 2 * PI * tonHz / debit
        for (i in dst.indices) {
            if (restant <= 0) {
                val e = elements.removeFirstOrNull()
                if (e != null) { bas = e.first; restant = e.second } else { bas = false; enAttente = 0 }
            }
            if (restant > 0) restant--
            val veut = bas || cle
            enveloppe = if (veut) minOf(1.0, enveloppe + pasEnv) else maxOf(0.0, enveloppe - pasEnv)
            // Raised cosine of the linear ramp: the edges' spectrum falls fast.
            val g = 0.5 - 0.5 * cos(PI * enveloppe)
            phase += w; if (phase > 2 * PI) phase -= 2 * PI
            dst[i] = (sin(phase) * g * niveau * 32767).roundToInt().toShort()
        }
        return dst.size
    }
}

/**
 * Moves I/Q samples by a frequency that may change at any time ([ncoHz],
 * read once per buffer, the phase carried on): the uplink retuned while
 * transmitting without touching the LO.
 */
class MelangeurNco(private val debit: Long, @Volatile var ncoHz: Double) {
    private var c = 1.0
    private var s = 0.0

    /** [n] I/Q pairs of [b] moved in place. */
    fun applique(b: ShortArray, n: Int = b.size / 2) {
        val w = 2 * PI * ncoHz / debit
        val cw = cos(w); val sw = sin(w)
        for (k in 0 until n) {
            val i = b[2 * k].toDouble(); val q = b[2 * k + 1].toDouble()
            b[2 * k] = sature(i * c - q * s)
            b[2 * k + 1] = sature(i * s + q * c)
            val nc = c * cw - s * sw
            s = c * sw + s * cw
            c = nc
        }
        val m = hypot(c, s)
        c /= m; s /= m
    }

    private fun sature(v: Double): Short = v.roundToInt().coerceIn(-32767, 32767).toShort()
}
