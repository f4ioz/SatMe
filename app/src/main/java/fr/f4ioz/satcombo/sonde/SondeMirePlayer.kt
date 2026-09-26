/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sonde

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.naman14.androidlame.LameBuilder
import fr.f4ioz.satcombo.audio.EncodeurMp3
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Plays the [SondeMire] test signal three ways: through the speaker (tests
 * another device's whole chain, microphone included), as a WAV/MP3 file, or
 * as an off-air demo fed straight into the decoder, filling the radiosonde
 * screen with a full flight with no dongle or antenna. The demo runs ~30x
 * real time; the decoder only sees samples and can't tell.
 */
object SondeMirePlayer {

    data class MireState(
        /** True while playing through the speaker. */
        val playing: Boolean = false,
        /** True while the demo feeds the decoder. */
        val demo: Boolean = false,
        val model: String = "RS41",
        /** Progress, 0 to 1. */
        val progress: Float = 0f,
        /** Requested duration, seconds. */
        val seconds: Int = 60,
        /** Last file written. */
        val lastFile: String? = null
    )

    private val _state = MutableStateFlow(MireState())
    val state: StateFlow<MireState> = _state

    @Volatile private var thread: Thread? = null
    @Volatile private var stopping = false

    /** True while playing or demoing. */
    val busy: Boolean get() = thread != null

    // ------------------------------------------------------------- playback

    /**
     * Plays the test signal through the phone's audio output. Full-scale
     * square wave: a phone speaker is enough for a microphone 30 cm away to
     * decode. Further than that, use a cable.
     */
    fun play(
        ctx: Context, model: String, lat: Double, lon: Double, seconds: Int,
        ambience: Boolean = false
    ) {
        stop()
        _state.value = _state.value.copy(
            playing = true, demo = false, model = model, progress = 0f, seconds = seconds)
        stopping = false
        val t = Thread {
            var track: AudioTrack? = null
            try {
                val src = SondeMire.Source(model, lat, lon, seconds, ambience = ambience)
                val min = AudioTrack.getMinBufferSize(
                    SondeMire.RATE, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT)
                val bufBytes = maxOf(min, SondeMire.RATE / 2 * 2)
                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SondeMire.RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(bufBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                track.play()
                val chunk = ShortArray(4096)
                while (!stopping) {
                    val n = src.read(chunk)
                    if (n <= 0) break
                    var off = 0
                    while (off < n && !stopping) {
                        val w = track.write(chunk, off, n - off)
                        if (w <= 0) break
                        off += w
                    }
                    _state.value = _state.value.copy(progress = src.progress)
                }
                if (!stopping) runCatching { Thread.sleep(300) }
            } catch (_: Throwable) {
                // Audio output refused or busy: give up.
            } finally {
                runCatching { track?.stop() }
                runCatching { track?.release() }
                thread = null
                _state.value = _state.value.copy(playing = false, progress = 0f)
            }
        }
        thread = t
        t.isDaemon = true
        t.start()
    }

    // --------------------------------------------------------- demo

    /**
     * Feeds the test signal straight into the decoder.
     *
     * The most useful mode: when nothing decodes on air, it answers in ten
     * seconds whether it's the decoder or the reception. If the test signal
     * decodes and the sonde doesn't, look at the cable, tuning or antenna.
     *
     * No log: a synthetic flight has no place among real sonde tracks.
     */
    fun demo(
        ctx: Context, model: String, lat: Double, lon: Double, seconds: Int,
        ambience: Boolean = false
    ) {
        stop()
        val app = ctx.applicationContext
        _state.value = _state.value.copy(
            playing = false, demo = true, model = model, progress = 0f, seconds = seconds)
        stopping = false
        val t = Thread {
            try {
                SondeHub.start(app, SondeMire.RATE, SondeMire.DEMO_FREQ_HZ,
                    source = "DEMO", model = model, log = false)
                val src = SondeMire.Source(model, lat, lon, seconds, ambience = ambience)
                val chunk = ShortArray(SondeMire.RATE / 4)
                while (!stopping) {
                    val n = src.read(chunk)
                    if (n <= 0) break
                    SondeHub.feedLive(chunk, n)
                    _state.value = _state.value.copy(progress = src.progress)
                    // Short pause per quarter second of signal: ~30x real time,
                    // slow enough for the screen to follow the track instead of
                    // showing it all at once.
                    runCatching { Thread.sleep(8) }
                }
            } catch (_: Throwable) {
            } finally {
                thread = null
                _state.value = _state.value.copy(demo = false, progress = 0f)
            }
        }
        thread = t
        t.isDaemon = true
        t.start()
    }

    /** Stops playback or demo. */
    fun stop() {
        stopping = true
        val t = thread ?: return
        runCatching { t.join(2000) }
        thread = null
        _state.value = _state.value.copy(playing = false, demo = false, progress = 0f)
    }

    /** Stops the demo and disarms the decoder with it. */
    fun stopDemo() {
        stop()
        if (SondeHub.state.value.source == "DEMO") SondeHub.stop()
    }

    // ---------------------------------------------------------------- export

    /** Export directory, shared with the SSTV test signal. */
    fun dir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null), "mires").apply { mkdirs() }

    /** Writes the test signal as a 44.1 kHz mono WAV. */
    fun exportWav(
        ctx: Context, model: String, lat: Double, lon: Double, seconds: Int,
        ambience: Boolean = false
    ): File? = runCatching {
        val src = SondeMire.Source(model, lat, lon, seconds, ambience = ambience)
        val f = File(dir(ctx), "SatMe_SONDE_${model}_${stamp()}.wav")
        FileOutputStream(f).use { out ->
            out.write(wavHeader(src.totalSamples))
            val chunk = ShortArray(8192)
            val bytes = ByteArray(chunk.size * 2)
            while (true) {
                val n = src.read(chunk)
                if (n <= 0) break
                for (i in 0 until n) {
                    val v = chunk[i].toInt()
                    bytes[2 * i] = (v and 0xFF).toByte()
                    bytes[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
                }
                out.write(bytes, 0, n * 2)
            }
        }
        _state.value = _state.value.copy(lastFile = f.name)
        f
    }.getOrNull()

    /**
     * Same signal as MP3.
     *
     * Caution: MP3 is fine for SSTV (1500 to 2300 Hz), but sonde edges reach
     * several kHz and perceptual coding rounds them. Good for showing; to
     * really test a decoding chain, replay the WAV.
     */
    fun exportMp3(
        ctx: Context, model: String, lat: Double, lon: Double, seconds: Int,
        ambience: Boolean = false
    ): File? = EncodeurMp3.avec(EncodeurMp3.MIRE_SONDE) { runCatching {
        val src = SondeMire.Source(model, lat, lon, seconds, ambience = ambience)
        val f = File(dir(ctx), "SatMe_SONDE_${model}_${stamp()}.mp3")
        val chunkSize = 8192
        val lame = LameBuilder()
            .setInSampleRate(SondeMire.RATE)
            .setOutSampleRate(SondeMire.RATE)
            .setOutChannels(1)
            .setOutBitrate(128)
            .setQuality(2)
            .setId3tagTitle("SatMe " + model)
            .setId3tagArtist("SatMe")
            .setId3tagComment("SatMe radiosonde test signal")
            .build()
        try {
            FileOutputStream(f).use { out ->
                val chunk = ShortArray(chunkSize)
                val mp3 = ByteArray((chunkSize * 1.25).toInt() + 7200)
                while (true) {
                    val n = src.read(chunk)
                    if (n <= 0) break
                    val enc = lame.encode(chunk, chunk, n, mp3)
                    if (enc > 0) out.write(mp3, 0, enc)
                }
                val flushed = lame.flush(mp3)
                if (flushed > 0) out.write(mp3, 0, flushed)
            }
        } finally {
            runCatching { lame.close() }
        }
        _state.value = _state.value.copy(lastFile = f.name)
        f
    }.getOrNull() }

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd'_'HHmmss", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())

    /** Canonical 44-byte WAV header, mono 16-bit PCM. */
    private fun wavHeader(samples: Int): ByteArray {
        val dataLen = samples * 2
        val h = ByteArray(44)
        fun ascii(off: Int, s: String) {
            for (i in s.indices) h[off + i] = s[i].code.toByte()
        }
        fun le32(off: Int, v: Int) {
            h[off] = (v and 0xFF).toByte()
            h[off + 1] = ((v shr 8) and 0xFF).toByte()
            h[off + 2] = ((v shr 16) and 0xFF).toByte()
            h[off + 3] = ((v shr 24) and 0xFF).toByte()
        }
        fun le16(off: Int, v: Int) {
            h[off] = (v and 0xFF).toByte()
            h[off + 1] = ((v shr 8) and 0xFF).toByte()
        }
        ascii(0, "RIFF"); le32(4, 36 + dataLen); ascii(8, "WAVE")
        ascii(12, "fmt "); le32(16, 16); le16(20, 1); le16(22, 1)
        le32(24, SondeMire.RATE); le32(28, SondeMire.RATE * 2); le16(32, 2); le16(34, 16)
        ascii(36, "data"); le32(40, dataLen)
        return h
    }
}
