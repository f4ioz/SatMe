/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
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
 * Plays the test card through the phone speaker.
 *
 * Streamed from [SstvEncoder.Source], never rendered at once (a PD 290 is
 * 4 min 50 s, ~25 MB of PCM). Runs on its own thread and can be stopped any
 * time: nobody should wait four minutes to find they picked the wrong mode.
 */
object SstvPlayer {

    data class PlayState(
        /** True while transmitting. */
        val playing: Boolean = false,
        /** Mode being transmitted. */
        val modeName: String? = null,
        /** Progress, 0..1. */
        val progress: Float = 0f,
        /** Total length, seconds. */
        val seconds: Int = 0,
        /** Last exported file. */
        val lastFile: String? = null
    )

    private val _state = MutableStateFlow(PlayState())
    val state: StateFlow<PlayState> = _state

    /** Output sample rate — the sound card's. */
    const val RATE = 44_100

    @Volatile private var thread: Thread? = null
    @Volatile private var stopping = false

    /** True while a transmission runs. */
    val playing: Boolean get() = thread != null

    /** Plays the [mode] test card. Stops any running one first: two overlaid SSTV signals decode to nothing. */
    fun play(
        ctx: Context, mode: SstvMode, callsign: String, locator: String,
        cond: SstvConditions = SstvConditions.CLEAN
    ) {
        stop()
        val app = ctx.applicationContext
        val total = SstvEncoder.seconds(mode).toInt()
        _state.value = _state.value.copy(
            playing = true, modeName = mode.name, progress = 0f, seconds = total)
        stopping = false
        val t = Thread {
            var track: AudioTrack? = null
            try {
                val pixels = SstvPattern.pixels(
                    SstvPattern.render(app, mode, callsign, locator))
                val src = SstvEncoder.Source(mode, pixels, RATE)
                val sim = ReceptionSim(cond, RATE, src.totalSamples)
                val min = AudioTrack.getMinBufferSize(
                    RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val bufBytes = maxOf(min, RATE / 2 * 2)      // at least half a second
                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(RATE)
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
                    sim.apply(chunk, n)
                    var off = 0
                    while (off < n && !stopping) {
                        val w = track.write(chunk, off, n - off)
                        if (w <= 0) break
                        off += w
                    }
                    _state.value = _state.value.copy(progress = src.progress)
                }
                // Let the buffer drain, or the last line is lost at the far end.
                if (!stopping) runCatching { Thread.sleep(300) }
            } catch (_: Throwable) {
                // Audio output refused or busy: just give up, the screen goes idle.
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

    /** Stops the running transmission. */
    fun stop() {
        stopping = true
        val t = thread ?: return
        runCatching { t.join(1500) }
        thread = null
        _state.value = _state.value.copy(playing = false, progress = 0f)
    }

    // ---------------------------------------------------------------- export

    /**
     * Export name. A degraded card says so ("_sim"): opened later, a noisy
     * file must not pass for a faulty chain.
     */
    private fun fileName(mode: SstvMode, cond: SstvConditions, ext: String): String {
        val safe = mode.name.replace(Regex("[^A-Za-z0-9]"), "")
        return "SatMe_MIRE_${safe}${if (cond.active) "_sim" else ""}_${stamp()}.$ext"
    }

    /** Folder for exported test cards. */
    fun dir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null), "mires").apply { mkdirs() }

    /**
     * Writes the test card as a mono 44.1 kHz WAV, to play from another device
     * or over a radio. Streamed to disk, never held in memory.
     */
    fun exportWav(
        ctx: Context, mode: SstvMode, callsign: String, locator: String,
        cond: SstvConditions = SstvConditions.CLEAN
    ): File? = runCatching {
        val pixels = SstvPattern.pixels(SstvPattern.render(ctx, mode, callsign, locator))
        val src = SstvEncoder.Source(mode, pixels, RATE)
        val sim = ReceptionSim(cond, RATE, src.totalSamples)
        val f = File(dir(ctx), fileName(mode, cond, "wav"))
        FileOutputStream(f).use { out ->
            out.write(wavHeader(src.totalSamples))
            val chunk = ShortArray(8192)
            val bytes = ByteArray(chunk.size * 2)
            while (true) {
                val n = src.read(chunk)
                if (n <= 0) break
                sim.apply(chunk, n)
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
     * Same test card as MP3: a PD 290 is 25 MB as WAV, under 5 MB at 128 kbit/s.
     * Perceptual coding is harmless here: SSTV is FM between 1500 and 2300 Hz,
     * right where the codec is most faithful, and pass recordings already use
     * MP3. Uses the recorder's embedded LAME, streamed chunk by chunk.
     */
    fun exportMp3(
        ctx: Context, mode: SstvMode, callsign: String, locator: String,
        cond: SstvConditions = SstvConditions.CLEAN
    ): File? = EncodeurMp3.avec(EncodeurMp3.MIRE_SSTV) { runCatching {
        val pixels = SstvPattern.pixels(SstvPattern.render(ctx, mode, callsign, locator))
        val src = SstvEncoder.Source(mode, pixels, RATE)
        val sim = ReceptionSim(cond, RATE, src.totalSamples)
        val f = File(dir(ctx), fileName(mode, cond, "mp3"))
        val chunkSize = 8192
        val lame = LameBuilder()
            .setInSampleRate(RATE)
            .setOutSampleRate(RATE)
            .setOutChannels(1)
            .setOutBitrate(128)
            .setQuality(2)
            .setId3tagTitle("SatMe " + mode.name)
            .setId3tagArtist(callsign.ifBlank { "SatMe" })
            .setId3tagComment("SatMe SSTV test pattern")
            .build()
        try {
            FileOutputStream(f).use { out ->
                val chunk = ShortArray(chunkSize)
                val mp3 = ByteArray((chunkSize * 1.25).toInt() + 7200)
                while (true) {
                    val n = src.read(chunk)
                    if (n <= 0) break
                    sim.apply(chunk, n)
                    // LAME wants both channels even in mono: pass the same buffer twice.
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
        le32(24, RATE); le32(28, RATE * 2); le16(32, 2); le16(34, 16)
        ascii(36, "data"); le32(40, dataLen)
        return h
    }

    /** Media volume to show before playing — informational only. */
    fun volumeHint(ctx: Context): Pair<Int, Int> {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return 0 to 0
        return am.getStreamVolume(AudioManager.STREAM_MUSIC) to
            am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    }
}
