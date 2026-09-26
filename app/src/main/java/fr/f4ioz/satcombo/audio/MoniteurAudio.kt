/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** What the screen shows of the audio being recorded. */
data class EtatMoniteur(
    val actif: Boolean = false,
    val bandes: List<Float> = emptyList(),
    val crete: Float = 0f,
    /** True when audio is actually going to the speaker. */
    val hautParleur: Boolean = false
)

/**
 * Monitoring, by ear and by eye, of what is being recorded.
 *
 * Two features from the same tap: the spectrum of the captured audio, and
 * playback of that audio on the phone speaker. Both hook into the tap
 * [PassRecorder] already provides for the SSTV decoder (raw samples, before
 * the MP3 encoder).
 *
 * **The capture thread must return at once**, or the AudioRecord buffer
 * overflows and the recording gets gaps. So nothing is computed or written on
 * it: [alimenter] copies the block into a small queue and returns. Our own
 * thread does the FFT and writes to the AudioTrack, which may block.
 *
 * When the queue is full, blocks are dropped: a stuttering monitor is barely
 * noticed, a recording with gaps is lost for good.
 *
 * The speaker only opens for an external source (rig USB sound card or
 * Bluetooth). Playing the phone mic through the same phone's speaker would
 * only cause feedback, and the sound is already in the room anyway.
 */
object MoniteurAudio {

    private val _etat = MutableStateFlow(EtatMoniteur())
    val etat: StateFlow<EtatMoniteur> = _etat

    /** Compute and publish the spectrum. Can change mid-recording. */
    @Volatile var spectre = false
    /** Play audio on the speaker. Can change mid-recording. */
    @Volatile var hautParleur = false

    /** True when capture does not come from the phone mic. */
    @Volatile private var sourceExterne = false
    @Volatile private var enMarche = false
    private var fil: Thread? = null
    /** Sample rate of the current capture. */
    @Volatile var cadence: Int = 44_100
        private set

    private var rate = 44_100

    /** Eight blocks, about 0.1 s of lead, no more: beyond that the monitor
     *  would lag behind the rig's own audio. */
    private val file = ArrayBlockingQueue<ShortArray>(8)

    /** Whether the speaker can be used with the current source. */
    fun hautParleurPossible(): Boolean = sourceExterne

    /**
     * Opens the monitor for one recording. Called even with both options off:
     * the thread then sleeps at no cost, and the operator can turn either on
     * mid-pass.
     */
    fun demarrer(rate: Int, sourceExterne: Boolean, spectre: Boolean, hautParleur: Boolean) {
        cadence = rate
        arreter()
        this.rate = rate
        this.sourceExterne = sourceExterne
        this.spectre = spectre
        this.hautParleur = hautParleur
        file.clear()
        enMarche = true
        _etat.value = EtatMoniteur(actif = true)
        fil = thread(name = "MoniteurAudio", isDaemon = true) { boucle() }
    }

    /** Copies [n] samples into the queue. Runs on the capture thread: must
     *  never block or compute anything. */
    fun alimenter(pcm: ShortArray, n: Int) {
        // The demo server taps first, before any other condition: it needs the
        // audio even with spectrum and speaker off. It encodes on its side
        // without blocking here.
        fr.f4ioz.satcombo.demo.ServeurDemo.verseAudio(pcm, n, cadence)
        if (!enMarche) return
        if (!spectre && !(hautParleur && sourceExterne)) return
        if (n <= 0) return
        file.offer(pcm.copyOf(n))
    }

    fun arreter() {
        if (!enMarche && fil == null) { _etat.value = EtatMoniteur(); return }
        enMarche = false
        runCatching { fil?.join(1_500) }
        fil = null
        file.clear()
        _etat.value = EtatMoniteur()
    }

    private fun boucle() {
        val analyseur = AnalyseurSpectre(rate = rate)
        var piste: AudioTrack? = null
        var dernierePublication = 0L
        try {
            while (enMarche) {
                val bloc = file.poll(200, TimeUnit.MILLISECONDS)
                val veutHp = hautParleur && sourceExterne
                if (!veutHp && piste != null) { fermerPiste(piste); piste = null }
                if (bloc == null) continue

                if (spectre && analyseur.pousser(bloc, bloc.size)) {
                    // A 1024-point window at 44.1 kHz arrives every 23 ms;
                    // publishing each would redraw ~40 times a second for
                    // no visible gain.
                    val t = System.currentTimeMillis()
                    if (t - dernierePublication >= 60) {
                        dernierePublication = t
                        _etat.value = EtatMoniteur(
                            actif = true,
                            bandes = analyseur.bandes.toList(),
                            crete = analyseur.crete,
                            hautParleur = veutHp)
                    }
                }

                if (veutHp) {
                    if (piste == null) piste = runCatching { ouvrirPiste() }.getOrNull()
                    // write() blocks when the buffer is full: it paces the
                    // thread, as intended.
                    piste?.let { runCatching { it.write(bloc, 0, bloc.size) } }
                }
            }
        } finally {
            piste?.let { fermerPiste(it) }
        }
    }

    private fun ouvrirPiste(): AudioTrack {
        val min = AudioTrack.getMinBufferSize(
            rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // Half a second of margin: the thread shares the CPU with the MP3
        // encoder and possibly the SSTV decoder.
        val taille = maxOf(min * 2, rate)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
            .setBufferSizeInBytes(taille)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        runCatching { t.setVolume(AudioTrack.getMaxVolume()) }
        // **Force the phone's built-in speaker.**
        //
        // With a USB sound card plugged in, Android routes media output to it,
        // and its output is often wired to nothing: the spectrum showed but the
        // phone stayed silent. Here we want the **opposite** of the default
        // routing: source from the card, playback on the phone.
        forceHautParleur(t)
        t.play()
        return t
    }

    /** Set at startup: needed to reach audio routing. */
    @Volatile var contexte: android.content.Context? = null

    private fun forceHautParleur(t: AudioTrack) {
        val ctx = contexte ?: return
        runCatching {
            val am = ctx.getSystemService(android.content.Context.AUDIO_SERVICE)
                as android.media.AudioManager
            val hp = am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull {
                    it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                }
            // Failure is not fatal: without a USB card the default routing
            // was already right.
            if (hp != null) t.preferredDevice = hp
        }
    }

    private fun fermerPiste(t: AudioTrack) {
        runCatching { t.pause(); t.flush(); t.stop() }
        runCatching { t.release() }
    }
}
