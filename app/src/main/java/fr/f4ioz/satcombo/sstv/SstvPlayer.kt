/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
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
 * L'émission de la mire par le haut-parleur du téléphone.
 *
 * Le signal est produit au fil de la lecture, jamais d'un bloc : un PD 290
 * dure quatre minutes cinquante, ce qui ferait vingt-cinq mégaoctets de PCM
 * à porter en mémoire pour un son qui sort de toute façon paquet par paquet.
 * [SstvEncoder.Source] rend les échantillons à la demande et garde la phase
 * entre deux paquets — une discontinuité à chaque jointure produirait des
 * claquements qu'un décodeur prend pour des synchros.
 *
 * L'émission tourne sur son propre fil, arrêtable à tout moment : personne
 * n'attend quatre minutes pour se rendre compte qu'il s'est trompé de mode.
 */
object SstvPlayer {

    data class PlayState(
        /** Vrai pendant l'émission. */
        val playing: Boolean = false,
        /** Mode en cours d'émission. */
        val modeName: String? = null,
        /** Avancement de l'émission, 0 à 1. */
        val progress: Float = 0f,
        /** Durée totale de l'émission en secondes. */
        val seconds: Int = 0,
        /** Dernier fichier WAV exporté. */
        val lastFile: String? = null
    )

    private val _state = MutableStateFlow(PlayState())
    val state: StateFlow<PlayState> = _state

    /** Fréquence d'échantillonnage de l'émission — celle de la carte son. */
    const val RATE = 44_100

    @Volatile private var thread: Thread? = null
    @Volatile private var stopping = false

    /** Vrai quand une émission est en cours. */
    val playing: Boolean get() = thread != null

    /**
     * Émet la mire de [mode] par la sortie audio.
     *
     * Une émission déjà en cours est interrompue : on ne superpose pas deux
     * signaux SSTV, le décodeur d'en face n'en tirerait rien.
     */
    fun play(ctx: Context, mode: SstvMode, callsign: String, locator: String) {
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
                val min = AudioTrack.getMinBufferSize(
                    RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val bufBytes = maxOf(min, RATE / 2 * 2)      // une demi-seconde au moins
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
                    var off = 0
                    while (off < n && !stopping) {
                        val w = track.write(chunk, off, n - off)
                        if (w <= 0) break
                        off += w
                    }
                    _state.value = _state.value.copy(progress = src.progress)
                }
                // Laisse sortir ce qui reste dans le tampon, sinon la fin de
                // l'image est coupée net et la dernière ligne manque en face.
                if (!stopping) runCatching { Thread.sleep(300) }
            } catch (_: Throwable) {
                // Sortie audio refusée ou occupée : rien à faire de plus que
                // rendre la main, l'écran repasse au repos.
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

    /** Coupe l'émission en cours. */
    fun stop() {
        stopping = true
        val t = thread ?: return
        runCatching { t.join(1500) }
        thread = null
        _state.value = _state.value.copy(playing = false, progress = 0f)
    }

    // ---------------------------------------------------------------- export

    /** Dossier des mires exportées. */
    fun dir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null), "mires").apply { mkdirs() }

    /**
     * Écrit la mire dans un WAV mono 44,1 kHz, à jouer depuis un autre appareil
     * ou à envoyer sur l'air par une radio.
     *
     * Le fichier est écrit au fil de la synthèse : même raison qu'à la lecture,
     * on ne garde jamais toute l'émission en mémoire.
     */
    fun exportWav(
        ctx: Context, mode: SstvMode, callsign: String, locator: String
    ): File? = runCatching {
        val pixels = SstvPattern.pixels(SstvPattern.render(ctx, mode, callsign, locator))
        val src = SstvEncoder.Source(mode, pixels, RATE)
        val safe = mode.name.replace(Regex("[^A-Za-z0-9]"), "")
        val f = File(dir(ctx), "SatMe_MIRE_${safe}_${stamp()}.wav")
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
     * La même mire, mais en MP3.
     *
     * Un PD 290 en WAV pèse vingt-cinq mégaoctets ; le même en MP3 à 128 kbit/s
     * en fait moins de cinq, ce qui passe par messagerie et se met sur un
     * baladeur sans y penser. Le codage perceptuel n'inquiète pas ici : la SSTV
     * est une modulation de fréquence entre 1500 et 2300 Hz, en plein milieu de
     * la bande que le codeur conserve le mieux, et c'est de toute façon déjà le
     * format dans lequel l'application enregistre les passages qu'elle sait
     * redécoder ensuite.
     *
     * L'encodeur est celui du magnétophone — LAME, embarqué — et le fichier est
     * écrit au fil de la synthèse, paquet par paquet.
     */
    fun exportMp3(
        ctx: Context, mode: SstvMode, callsign: String, locator: String
    ): File? = EncodeurMp3.avec(EncodeurMp3.MIRE_SSTV) { runCatching {
        val pixels = SstvPattern.pixels(SstvPattern.render(ctx, mode, callsign, locator))
        val src = SstvEncoder.Source(mode, pixels, RATE)
        val safe = mode.name.replace(Regex("[^A-Za-z0-9]"), "")
        val f = File(dir(ctx), "SatMe_MIRE_${safe}_${stamp()}.mp3")
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
                    // LAME veut les deux canaux même en mono : on lui donne
                    // deux fois le même tampon, comme le magnétophone.
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

    /** En-tête WAV canonique, 44 octets, mono PCM 16 bits. */
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

    /** Volume média conseillé avant émission — purement indicatif. */
    fun volumeHint(ctx: Context): Pair<Int, Int> {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return 0 to 0
        return am.getStreamVolume(AudioManager.STREAM_MUSIC) to
            am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    }
}
