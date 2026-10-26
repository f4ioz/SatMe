/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.datv.NiveauxSon
import java.io.OutputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

/**
 * The DATV sound decoded here rather than by an outside player: ffmpeg
 * gives it as 48 kHz stereo PCM ([SonDatvPc]); its levels are measured
 * ([niveaux]), it is played on this PC's output at [volume] while [joue]
 * says so, and handed to the browsers listening ([diffusion], "datv").
 * The volume changes at once: nothing restarts. While the spoken
 * translation speaks here, the sound played here glides down to [baisse]
 * (the browsers lower theirs themselves, as they play the voice).
 */
class SonLocalDatvPc(
    ffmpeg: String,
    private val niveaux: NiveauxSon,
    private val volume: () -> Double,
    private val joue: () -> Boolean,
    private val diffusion: DiffusionSon?,
    private val horloge: () -> Long = System::currentTimeMillis,
    private val baisse: () -> Double = { 1.0 },
) {
    private val ducking = fr.f4ioz.satcombo.phonie.ParoleTraduite.Baisse()
    private val decodeur = SonDatvPc(ffmpeg, RATE, 2, 50, "satme-datv-son-pc.log") { pcm, n, _ -> bloc(pcm, n) }
    private var ligne: SourceDataLine? = null
    private var ligneEssaiMs = 0L
    @Volatile var erreur = ""; private set

    val entree: OutputStream? get() = decodeur.entree
    val enMarche: Boolean get() = decodeur.enMarche

    fun demarre(): Boolean = decodeur.demarre()

    fun arrete() {
        decodeur.arrete()
        synchronized(this) { ligne?.let { runCatching { it.stop(); it.close() } }; ligne = null }
    }

    /** A block of [n] samples (stereo interleaved): measured, scaled, played, passed on. */
    internal fun bloc(pcm: ShortArray, n: Int) {
        val t = horloge()
        niveaux.mesure(pcm, n / 2, 2, t)
        if (diffusion?.ecoute("datv") == true) {
            val m = NiveauxSon.mono(pcm, n / 2)
            NiveauxSon.applique(m, m.size, volume())
            diffusion.ajoute("datv", m, m.size, RATE)
        }
        if (!joue()) { synchronized(this) { ligne?.let { runCatching { it.flush() } } }; return }
        NiveauxSon.applique(pcm, n, volume())
        ducking.applique(pcm, n, 2, RATE, runCatching(baisse).getOrDefault(1.0))
        val l = ligne(t) ?: return
        val b = ByteArray(n * 2)
        for (i in 0 until n) { val v = pcm[i].toInt(); b[2 * i] = v.toByte(); b[2 * i + 1] = (v shr 8).toByte() }
        runCatching { l.write(b, 0, b.size) }.onFailure { erreur = "sortie son : " + (it.message ?: ""); synchronized(this) { ligne = null } }
    }

    @Synchronized
    private fun ligne(t: Long): SourceDataLine? {
        ligne?.let { return it }
        if (ModeDemo.actif) return null
        // No output (no sound card, busy): tried again every 5 s, the levels go on.
        if (t - ligneEssaiMs < 5_000L) return null
        ligneEssaiMs = t
        val f = AudioFormat(RATE.toFloat(), 16, 2, true, false)
        return runCatching {
            (AudioSystem.getLine(DataLine.Info(SourceDataLine::class.java, f)) as SourceDataLine).also {
                it.open(f, RATE / 5 * 4)   // 200 ms
                it.start()
            }
        }.onSuccess { erreur = ""; ligne = it }.onFailure { erreur = "pas de sortie son sur ce PC : " + (it.message ?: it.javaClass.simpleName) }.getOrNull()
    }

    companion object {
        const val RATE = 48_000
    }
}
