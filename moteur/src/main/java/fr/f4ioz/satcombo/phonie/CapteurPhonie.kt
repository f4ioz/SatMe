/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.phonie

/**
 * The sound heard, at whatever rate, to pieces of voice at 16 kHz with the
 * time they began: the resampler and the cutter together (started again
 * when the rate changes).
 */
class CapteurPhonie(private val creeDecoupe: () -> DecoupeVoix = { DecoupeVoix() }) {

    /** A piece of voice and when it began (epoch ms). */
    class Piece(val segment: DecoupeVoix.Segment, val debutMs: Long)

    private var rate = 0
    private var reech: Reechantillonneur? = null
    private var decoupe = creeDecoupe()
    /** The wall clock at the stream's first sample (16 kHz count 0). */
    private var origineMs = 0L

    val enVoix: Boolean get() = decoupe.enVoix
    val bruitDb: Double get() = decoupe.bruit

    /** Feeds a block of [n] samples at [rate] Hz, received at [maintenantMs] (its end). */
    @Synchronized
    fun pousse(pcm: ShortArray, n: Int, rate: Int, maintenantMs: Long, squelch: Boolean? = null): List<Piece> {
        if (n <= 0 || rate <= 0) return emptyList()
        if (rate != this.rate || reech == null) {
            this.rate = rate
            reech = Reechantillonneur(rate, ProtocolePhonie.RATE)
            decoupe = creeDecoupe()
            origineMs = maintenantMs - n * 1000L / rate
        }
        val x = reech!!.pousse(pcm, n)
        return decoupe.pousse(x, x.size, squelch).map { Piece(it, origineMs + it.debut * 1000L / ProtocolePhonie.RATE) }
    }

    /** The piece in progress closed (the source stops or changes). */
    @Synchronized
    fun vide(): List<Piece> {
        val s = decoupe.vide() ?: return emptyList()
        return listOf(Piece(s, origineMs + s.debut * 1000L / ProtocolePhonie.RATE))
    }

    @Synchronized
    fun reinitialise() { rate = 0; reech = null; decoupe = creeDecoupe() }
}

/**
 * The pieces waiting for the engine: at most [max]; when it lags, the oldest
 * goes (what is said now matters more), and is counted.
 */
class FilePhonie<T>(private val max: Int) {
    private val file = ArrayDeque<T>()
    /** Pieces dropped because the engine lagged. */
    var perdus = 0L
        private set

    @Synchronized fun ajoute(x: T) {
        file.addLast(x)
        while (file.size > max) { file.removeFirst(); perdus++ }
        (this as Object).notifyAll()
    }

    /** The next piece, waiting at most [ms]; null if none came. */
    @Synchronized fun prend(ms: Long): T? {
        val fin = System.currentTimeMillis() + ms
        while (file.isEmpty()) {
            val reste = fin - System.currentTimeMillis()
            if (reste <= 0) return null
            (this as Object).wait(reste)
        }
        return file.removeFirst()
    }

    @Synchronized fun taille(): Int = file.size
    @Synchronized fun tous(): List<T> = file.toList()
    @Synchronized fun vide() { file.clear() }
}
