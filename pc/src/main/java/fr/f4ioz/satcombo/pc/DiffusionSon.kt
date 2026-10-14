/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * The sound heard here, sent to the browsers that listen — the page open on
 * another PC of the network hears the dongle or the rig as this one does.
 * 16-bit mono at half the source's rate (22 050 Hz from the dongle: a voice
 * channel needs no more), little-endian, after a 4-byte header giving the rate.
 * A listener too slow loses the oldest blocks, never holds the source back.
 */
class DiffusionSon {

    private class Auditeur(val source: String) {
        val file = ArrayBlockingQueue<ByteArray>(64)
        @Volatile var rate = 0
    }

    private val auditeurs = CopyOnWriteArrayList<Auditeur>()

    /** Someone listens to [source] ("sdr", "carte"): worth producing. */
    fun ecoute(source: String): Boolean = auditeurs.any { it.source == source }

    /** A block from [source], at [rate]: halved (two samples averaged) and handed to its listeners. */
    fun ajoute(source: String, pcm: ShortArray, n: Int, rate: Int) {
        if (auditeurs.none { it.source == source }) return
        val m = n / 2
        val o = ByteArray(m * 2)
        for (i in 0 until m) {
            val v = (pcm[2 * i].toInt() + pcm[2 * i + 1].toInt()) / 2
            o[2 * i] = v.toByte(); o[2 * i + 1] = (v shr 8).toByte()
        }
        for (a in auditeurs) if (a.source == source) {
            a.rate = rate / 2
            if (!a.file.offer(o)) { a.file.poll(); a.file.offer(o) }
        }
    }

    /** Streams [source] to [sortie] until the browser goes. */
    fun sert(source: String, sortie: OutputStream) {
        val a = Auditeur(source)
        auditeurs += a
        try {
            sortie.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
            sortie.flush()
            var entete = false
            var calme = 0
            while (true) {
                val b = a.file.poll(500, TimeUnit.MILLISECONDS)
                if (b == null) {
                    // Nothing heard for a while: a byte-less wait would hide a browser gone, so a short silence goes.
                    if (++calme % 4 == 0 && entete) { sortie.write(ByteArray(maxOf(2, a.rate / 20 * 2))); sortie.flush() }
                    continue
                }
                calme = 0
                if (!entete) {
                    val r = a.rate
                    sortie.write(byteArrayOf(r.toByte(), (r shr 8).toByte(), (r shr 16).toByte(), (r shr 24).toByte()))
                    entete = true
                }
                sortie.write(b); sortie.flush()
            }
        } catch (_: Exception) {
        } finally { auditeurs -= a }
    }
}
