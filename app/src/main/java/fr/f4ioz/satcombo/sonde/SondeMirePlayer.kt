/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
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
 * L'émission de la mire radiosonde, et sa démonstration hors antenne.
 *
 * Trois emplois, trois boutons, un seul signal derrière — celui que fabrique
 * [SondeMire], au format exact du constructeur.
 *
 * Le premier emploi est le haut-parleur : le téléphone émet, un autre appareil
 * écoute, et l'on éprouve toute la chaîne d'en face, micro compris. Le
 * deuxième est le fichier : un WAV ou un MP3 que l'on repasse dans une radio,
 * dans une carte son, ou que l'on envoie à un camarade qui n'a pas encore vu
 * de sonde passer. Le troisième est la démonstration : le son n'est joué nulle
 * part, il est versé directement dans le décodeur, et l'écran des radiosondes
 * se remplit d'un vol entier — montée, éclatement, descente, trace sur la
 * carte, journal exportable — sans clé, sans antenne et sans attendre le
 * lâcher de midi.
 *
 * La démonstration tourne plus vite que le temps réel : une mire de cinq
 * minutes se déroule en une dizaine de secondes. Le décodeur n'y voit rien,
 * puisqu'il ne travaille que sur des échantillons ; seul l'écran s'en aperçoit,
 * et c'est justement ce qu'on lui demande.
 */
object SondeMirePlayer {

    data class MireState(
        /** Vrai pendant l'émission par le haut-parleur. */
        val playing: Boolean = false,
        /** Vrai pendant la démonstration dans le décodeur. */
        val demo: Boolean = false,
        /** Modèle émis. */
        val model: String = "RS41",
        /** Avancement, de 0 à 1. */
        val progress: Float = 0f,
        /** Durée demandée, en secondes. */
        val seconds: Int = 60,
        /** Dernier fichier écrit. */
        val lastFile: String? = null
    )

    private val _state = MutableStateFlow(MireState())
    val state: StateFlow<MireState> = _state

    @Volatile private var thread: Thread? = null
    @Volatile private var stopping = false

    /** Vrai quand quelque chose tourne, émission ou démonstration. */
    val busy: Boolean get() = thread != null

    // ------------------------------------------------------------- émission

    /**
     * Émet la mire par la sortie audio du téléphone.
     *
     * Le signal est un carré à pleine amplitude : ce n'est pas de la musique,
     * et le haut-parleur d'un téléphone en rend assez pour qu'un micro à trente
     * centimètres décode. Au-delà, il faut un cordon.
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
                // Sortie audio refusée ou occupée : on rend la main.
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

    // --------------------------------------------------------- démonstration

    /**
     * Verse la mire directement dans le décodeur, sans passer par le son.
     *
     * C'est le mode qui sert le plus, et pas seulement pour montrer
     * l'application : quand rien ne se décode sur l'air, il répond en dix
     * secondes à la seule question qui compte — est-ce le décodeur, ou est-ce
     * la réception ? Si la mire passe et pas la sonde, le décodeur est hors de
     * cause et le défaut est devant, dans le cordon, l'accord ou l'antenne.
     *
     * Le journal n'est pas écrit : un vol de synthèse n'a rien à faire dans les
     * traces enregistrées, où l'on va chercher de vraies sondes.
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
                    // Une petite pause par quart de seconde de signal : le vol
                    // se déroule une trentaine de fois plus vite que sur l'air,
                    // ce qui laisse tout de même l'écran suivre la trace au lieu
                    // de la voir apparaître d'un coup.
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

    /** Coupe l'émission ou la démonstration en cours. */
    fun stop() {
        stopping = true
        val t = thread ?: return
        runCatching { t.join(2000) }
        thread = null
        _state.value = _state.value.copy(playing = false, demo = false, progress = 0f)
    }

    /** Arrête la démonstration et désarme le décodeur avec elle. */
    fun stopDemo() {
        stop()
        if (SondeHub.state.value.source == "DEMO") SondeHub.stop()
    }

    // ---------------------------------------------------------------- export

    /** Dossier des mires exportées, partagé avec la mire SSTV. */
    fun dir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null), "mires").apply { mkdirs() }

    /** Écrit la mire dans un WAV mono 44,1 kHz. */
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
     * La même mire en MP3.
     *
     * Prudence ici, et c'est écrit exprès : le MP3 est parfait pour la SSTV,
     * qui module entre 1500 et 2300 Hz, mais une sonde jette des fronts à
     * plusieurs kilohertz que le codage perceptuel arrondit. Le fichier
     * s'envoie et s'écoute, il sert à montrer ; pour éprouver vraiment une
     * chaîne de décodage, c'est le WAV qu'il faut repasser.
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
        le32(24, SondeMire.RATE); le32(28, SondeMire.RATE * 2); le16(32, 2); le16(34, 16)
        ascii(36, "data"); le32(40, dataLen)
        return h
    }
}
