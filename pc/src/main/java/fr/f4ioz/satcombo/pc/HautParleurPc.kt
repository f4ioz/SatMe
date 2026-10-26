/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

/**
 * The dongle's sound to this PC's speaker.
 *
 * The receiver hands its blocks in bursts and its clock is not the sound
 * card's: writing a block only when the line had room dropped one block in
 * four once the line was full — a dip every 31 ms, the sound « chevrotant ».
 * Here the blocks go to a ring ([pousse] never waits); a thread of its own
 * writes 10 ms at a time, the card setting the pace, and holds the ring near
 * [CIBLE_S] by reading one to three samples more or fewer per 10 ms
 * (≤ 0.7 %, unheard), linearly interpolated. Run dry, it plays silence until
 * the cushion is back; far behind (a stall then a burst), it skips to it.
 */
class HautParleurPc(private val rate: Int,
                    /** The cushion held (s): 0.15 for listening; less for the microphone to the rig. */
                    cibleS: Double = CIBLE_S,
                    private val ouvre: () -> SourceDataLine? = { ligne(rate) }) {

    private val anneau = ShortArray(Integer.highestOneBit(rate * 4))
    private val masque = anneau.size - 1
    private var ecrit = 0L
    private var lu = 0.0
    private var amorce = true
    private val bloc = rate / 100
    private val cible = (rate * cibleS).toLong()
    @Volatile private var enMarche = true
    /** The level asked (1 as received): lowered while SatMe speaks; reached over about 30 ms, no click. */
    @Volatile var gain = 1f
    private var gainVu = 1f
    private val fil = Thread({ boucle() }, "satme-haut-parleur").apply { isDaemon = true; priority = Thread.MAX_PRIORITY; start() }

    /** A block from the receiver: kept, never waited for. */
    @Synchronized fun pousse(pcm: ShortArray, n: Int) {
        for (i in 0 until n) anneau[((ecrit + i) and masque.toLong()).toInt()] = pcm[i]
        ecrit += n
        // Far behind: back to the cushion.
        if (ecrit - lu > rate * MAX_S) lu = (ecrit - cible).toDouble()
    }

    /** The next 10 ms for the card. */
    @Synchronized internal fun prochain(sortie: ShortArray) {
        val enFile = ecrit - lu
        if (amorce) {
            if (enFile < cible) { sortie.fill(0); return }
            amorce = false
        }
        // One sample more or fewer per 10 ms for each tenth of the cushion away from it, three at most.
        val ecart = ((enFile - cible) * 10 / cible).toLong().coerceIn(-3, 3)
        val pas = (bloc + ecart).toDouble() / bloc
        for (i in sortie.indices) {
            if (ecrit - lu < 2) { sortie.fill(0, i, sortie.size); amorce = true; return }
            val k = lu.toLong()
            val x = lu - k
            val a = anneau[(k and masque.toLong()).toInt()].toInt()
            val b = anneau[((k + 1) and masque.toLong()).toInt()].toInt()
            sortie[i] = (a + (b - a) * x).toInt().toShort()
            lu += pas
        }
        val voulu = gain.coerceIn(0f, 1f)
        if (voulu != 1f || gainVu != 1f) for (i in sortie.indices) {
            gainVu += (voulu - gainVu) * 0.002f
            if (kotlin.math.abs(voulu - gainVu) < 1e-4f) gainVu = voulu
            sortie[i] = (sortie[i] * gainVu).toInt().toShort()
        }
    }

    fun ferme() { enMarche = false; fil.interrupt() }

    private fun boucle() {
        val l = runCatching { ouvre() }.getOrNull() ?: return
        val s = ShortArray(bloc)
        val o = ByteArray(bloc * 2)
        try {
            while (enMarche) {
                prochain(s)
                for (i in s.indices) { val v = s[i].toInt(); o[2 * i] = v.toByte(); o[2 * i + 1] = (v shr 8).toByte() }
                l.write(o, 0, o.size)   // the card's pace
            }
        } catch (_: Exception) {
        } finally { runCatching { l.stop(); l.close() } }
    }

    companion object {
        const val CIBLE_S = 0.15
        const val MAX_S = 0.6

        private fun ligne(rate: Int): SourceDataLine? = ligne(rate, null)

        /** A playback line at [rate] on the card [mixer] (Java's name; null: the default one), 100 ms of buffer. */
        fun ligne(rate: Int, mixer: String?): SourceDataLine? {
            // The demonstration plays nothing on this computer: the browsers listening get the sound.
            if (ModeDemo.actif) return null
            val f = AudioFormat(rate.toFloat(), 16, 1, true, false)
            val di = DataLine.Info(SourceDataLine::class.java, f)
            val l = if (mixer == null) AudioSystem.getLine(di)
                else AudioSystem.getMixer(AudioSystem.getMixerInfo().firstOrNull { it.name == mixer } ?: return null).getLine(di)
            return (l as SourceDataLine).also {
                it.open(f, rate / 10 * 2)   // 100 ms
                it.start()
            }
        }
    }
}

/**
 * The speaker's level asked by several voices at once (the announcements,
 * the spoken translation): the lowest wins, 1 again only when all are done.
 */
class BaisseSon(private val applique: (Float) -> Unit) {
    private val niveaux = HashMap<String, Float>()

    @Synchronized fun regle(qui: String, niveau: Float) {
        if (niveau >= 1f) niveaux.remove(qui) else niveaux[qui] = niveau.coerceAtLeast(0f)
        applique(niveaux.values.minOrNull() ?: 1f)
    }
}
