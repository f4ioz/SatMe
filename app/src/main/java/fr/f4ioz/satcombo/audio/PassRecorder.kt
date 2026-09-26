/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.naman14.androidlame.LameBuilder
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread

/**
 * Records the phone microphone (the RX audio coming out of the rig's speaker,
 * or later a Bluetooth audio link) straight to an MP3 file via the LAME encoder.
 * Mono 44.1 kHz — plenty for SSB/FM satellite audio, ~1 MB per minute at 128 kbps.
 *
 * Recording runs on its own thread; [stop] finalises the file (LAME flush) so the
 * MP3 is always valid, whether the user stops it or the pass auto-stop fires.
 */
class PassRecorder {

    @Volatile private var running = false
    private var worker: Thread? = null
    var currentFile: File? = null
        private set

    val isRecording: Boolean get() = running

    /** Open an AudioRecord, or null if that source/rate combination is refused.
     *  Kept separate so the caller can fall back to a plainer source. */
    @SuppressLint("MissingPermission")
    private fun openRecord(source: Int, sampleRate: Int, bufSize: Int): AudioRecord? {
        val r = try {
            AudioRecord(
                source, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize * 2)
        } catch (e: Exception) { return null }
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return null }
        return r
    }

    /** Start recording into [outFile]. Returns false if the mic can't be opened
     *  (permission missing, device busy…). RECORD_AUDIO must already be granted.
     *  [preferredDevice] routes capture to a specific input (the Bluetooth SCO
     *  link once the caller has brought it up, or a USB sound card); null =
     *  default mic. [source] is the MediaRecorder.AudioSource to open: the
     *  caller decides, because the right one depends on the route, not on
     *  whether a preferred device was given — a USB card wants MIC (or
     *  UNPROCESSED), an SCO link wants VOICE_COMMUNICATION. */
    fun start(
        outFile: File,
        satName: String,
        sampleRate: Int = 44_100,
        bitrateKbps: Int = 128,
        preferredDevice: android.media.AudioDeviceInfo? = null,
        source: Int = MediaRecorder.AudioSource.MIC,
        /** Raw capture tap, called on the recording thread before encoding.
         *  Used by the SSTV engine so the pictures are decoded from the
         *  untouched samples rather than from the re-read MP3. It must return
         *  quickly: anything slow here would starve the AudioRecord buffer and
         *  put a gap in the recording. */
        pcmSink: ((ShortArray, Int) -> Unit)? = null
    ): Boolean {
        if (running) return false
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return false
        val bufSize = maxOf(minBuf, 2048)

        // UNPROCESSED is advertised by the platform but still refused by some
        // devices/rates: never let that lose the recording, fall back to MIC.
        val recorder = openRecord(source, sampleRate, bufSize)
            ?: (if (source != MediaRecorder.AudioSource.MIC)
                    openRecord(MediaRecorder.AudioSource.MIC, sampleRate, bufSize) else null)
            ?: return false
        if (preferredDevice != null) {
            runCatching { recorder.preferredDevice = preferredDevice }
        }

        // Il n'y a qu'un encodeur MP3 dans le processus — voir [EncodeurMp3].
        // Si le SDR ou un export de mire le tient déjà, on ne démarre pas :
        // deux encodeurs à la fois ne produisent pas deux fichiers, ils tuent
        // le processus dans le code natif, sans trace côté Kotlin. Mieux vaut
        // un enregistrement refusé, qui se voit et se dit, qu'un enregistrement
        // qui emporte l'application au milieu du passage.
        if (!EncodeurMp3.prend(EncodeurMp3.ENREGISTREUR)) {
            recorder.release()
            return false
        }

        val lame = LameBuilder()
            .setInSampleRate(sampleRate)
            .setOutSampleRate(sampleRate)
            .setOutChannels(1)
            .setOutBitrate(bitrateKbps)
            .setQuality(5)
            .setId3tagTitle(satName)
            .setId3tagArtist("F4IOZ")
            .setId3tagComment("SatMe pass recording")
            .build()

        currentFile = outFile
        running = true
        worker = thread(name = "PassRecorder", isDaemon = true) {
            val pcm = ShortArray(bufSize)
            val mp3 = ByteArray((bufSize * 1.25).toInt() + 7200)
            val out = try { FileOutputStream(outFile) }
                      catch (e: Exception) {
                          running = false; recorder.release(); lame.close()
                          EncodeurMp3.rend(EncodeurMp3.ENREGISTREUR)
                          return@thread
                      }
            try {
                recorder.startRecording()
                while (running) {
                    val read = recorder.read(pcm, 0, pcm.size)
                    if (read > 0) {
                        if (pcmSink != null) runCatching { pcmSink(pcm, read) }
                        val enc = lame.encode(pcm, pcm, read, mp3)
                        if (enc > 0) out.write(mp3, 0, enc)
                    } else if (read < 0) {
                        break // read error
                    }
                }
                val flushed = lame.flush(mp3)
                if (flushed > 0) out.write(mp3, 0, flushed)
            } catch (e: Exception) {
                // best-effort: file is whatever was flushed so far
            } finally {
                try { recorder.stop() } catch (_: Exception) {}
                recorder.release()
                lame.close()
                // Rendu après la fermeture, jamais avant : tant que
                // `lame_close` n'a pas rendu la main, l'encodeur natif est
                // encore en train de se démonter, et un autre `build()` à cet
                // instant retomberait exactement sur la panne qu'on corrige.
                EncodeurMp3.rend(EncodeurMp3.ENREGISTREUR)
                try { out.flush(); out.close() } catch (_: Exception) {}
            }
        }
        return true
    }

    /** Stop and finalise the current recording (blocks briefly for the flush). */
    fun stop() {
        if (!running) return
        running = false
        try { worker?.join(2500) } catch (_: InterruptedException) {}
        worker = null
    }
}
