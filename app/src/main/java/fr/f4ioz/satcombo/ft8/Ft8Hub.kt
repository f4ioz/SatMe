/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.ft8

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import fr.f4ioz.satcombo.domain.Ft8Decodeur
import fr.f4ioz.satcombo.domain.Ft8Signal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * FT8 and FT4 listening: microphone, time slots, decoding.
 *
 * **Slot timing is the whole problem.** FT8 transmits in 15 s windows aligned
 * on UTC, FT4 in 7.5 s windows. A transmission starts 0.5 s into its window
 * and lasts 12.64 s (FT8) or 5.04 s (FT4), so the receiver must know the time
 * to better than a second — which rests entirely on the phone clock.
 *
 * A single hub, not per-screen state: listening must survive a visit to the
 * log or settings, or a whole cycle is lost.
 */
object Ft8Hub {

    /** What the screen observes. */
    data class Etat(
        val enMarche: Boolean = false,
        val mode: String = "FT8",
        /** Decoded messages, most recent first. */
        val entendus: List<Entendu> = emptyList(),
        /** Progress through the current slot, for the progress bar. */
        val avancement: Float = 0f,
        /** Input level: is the microphone hearing anything? */
        val niveau: Float = 0f,
        /** Slots analysed since start. */
        val tranches: Int = 0,
        /**
         * The waterfall: one row per analysis step, most recent first, each
         * byte a level 0–255 across the band. Bytes, not floats: a few KB, and
         * the screen has no more shades to show anyway.
         */
        val cascade: List<ByteArray> = emptyList(),

        /**
         * The instant spectrum, one value per column, 0 to 1. The waterfall
         * shows history; this shows the present — what you watch while tuning
         * or checking the rig outputs anything.
         */
        val spectre: FloatArray = FloatArray(0),
        /** Displayed band edges, for the axis. */
        val basseHz: Int = 200,
        val hauteHz: Int = 3000,
        /** Empty when all is well. */
        val panne: String = "",
        /** Where the sound comes from: "MIC" (the phone's input) or "SDR" (the dongle's receiver). */
        val source: String = "MIC"
    )

    data class Entendu(
        val heure: String,
        val texte: String,
        val frequenceHz: Int,
        val rapportDb: Int,
        val decalageS: Double,
        val mode: String
    )

    private val _state = MutableStateFlow(Etat())
    val state = _state.asStateFlow()

    private var boucle: Job? = null
    private const val CADENCE = 12000

    /** The two modes and their slot length. */
    private fun modeDe(nom: String) =
        if (nom == "FT4") Ft8Signal.FT4 else Ft8Signal.FT8

    private fun trancheS(nom: String) = if (nom == "FT4") 7.5 else 15.0

    fun demarre(ctx: Context, nom: String) {
        arrete()
        val src = _state.value.source
        _state.value = Etat(enMarche = true, mode = nom, source = src)
        boucle = CoroutineScope(Dispatchers.Default).launch {
            if (src == "SDR") tourneSdr(nom, this) else tourne(nom, this)
        }
    }

    fun arrete() {
        ecouteSdr = false
        boucle?.cancel()
        boucle = null
        fileSdr.clear()
        _state.value = _state.value.copy(enMarche = false, avancement = 0f)
    }

    /** The microphone (the rig's audio into the phone) or the SDR dongle's receiver. */
    fun choisitSource(ctx: Context, src: String) {
        if (src == _state.value.source) return
        val tournait = _state.value.enMarche
        arrete()
        _state.value = _state.value.copy(source = src, panne = "")
        if (tournait) demarre(ctx, _state.value.mode)
    }

    // ------------------------------------------------------------ from the SDR dongle

    /** The dongle's reader hands its sound over only while true. */
    @Volatile var ecouteSdr = false
        private set
    /** The dongle's audio rate (44 100 Hz) brought to 11 025: FT8 lies under 3 kHz. */
    private const val CADENCE_SDR = 11_025
    private const val DECIMATION_SDR = 4
    private val fileSdr = java.util.concurrent.ArrayBlockingQueue<ShortArray>(256)
    private var resteSdr = 0; private var sommeSdr = 0

    /**
     * From the dongle's reading loop: its demodulated sound (44.1 kHz). Kept
     * short — the reading must never wait: averaged four by four into a
     * block, queued, decoded elsewhere; a block that finds the queue full is
     * dropped (the slot just decodes less).
     */
    fun feedLive(pcm: ShortArray, n: Int) {
        if (!ecouteSdr || n <= 0) return
        val out = ShortArray(n / DECIMATION_SDR + 1)
        var k = 0
        for (i in 0 until n) {
            sommeSdr += pcm[i]
            if (++resteSdr == DECIMATION_SDR) { out[k++] = (sommeSdr / DECIMATION_SDR).toShort(); sommeSdr = 0; resteSdr = 0 }
        }
        if (k > 0) fileSdr.offer(out.copyOf(k))
    }

    private suspend fun tourneSdr(nom: String, portee: CoroutineScope) {
        fileSdr.clear(); resteSdr = 0; sommeSdr = 0
        ecouteSdr = true
        var courant: ShortArray? = null; var pos = 0
        try {
            ecoute(nom, portee, CADENCE_SDR) { bloc ->
                // Blocks of the reader's size, taken from the queue as they come.
                var n = 0
                while (n < bloc.size) {
                    val c = courant ?: kotlinx.coroutines.withContext(Dispatchers.IO) { fileSdr.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS) }
                        ?: break
                    courant = c
                    val k = minOf(bloc.size - n, c.size - pos)
                    System.arraycopy(c, pos, bloc, n, k)
                    n += k; pos += k
                    if (pos == c.size) { courant = null; pos = 0 }
                }
                n
            }
        } finally { ecouteSdr = false }
    }

    /**
     * Changes mode. Restarts listening if running: slot lengths differ.
     */
    fun choisitMode(ctx: Context, nom: String) {
        if (nom == _state.value.mode) return
        val tournait = _state.value.enMarche
        arrete()
        _state.value = _state.value.copy(mode = nom, panne = "", avancement = 0f)
        if (tournait) demarre(ctx, nom)
    }

    fun vide() {
        _state.value = _state.value.copy(
            entendus = emptyList(), tranches = 0, cascade = emptyList())
    }

    private fun panne(quoi: String) {
        // Listening that decodes nothing looks like listening that never
        // started: permission refused, mic held by another app, unsupported
        // rate. Each gets its own word.
        _state.value = _state.value.copy(enMarche = false, panne = quoi)
    }

    @android.annotation.SuppressLint("MissingPermission")
    private suspend fun tourne(nom: String, portee: CoroutineScope) {

        val min = AudioRecord.getMinBufferSize(
            CADENCE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) { panne("cadence"); return }

        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, CADENCE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 8)
        } catch (e: SecurityException) { panne("permission"); return }
        catch (e: Exception) { panne("micro"); return }

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release(); panne("micro"); return
        }
        try { rec.startRecording() } catch (e: Exception) {
            rec.release(); panne("occupe"); return
        }
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            rec.release(); panne("occupe"); return
        }

        try {
            ecoute(nom, portee, CADENCE) { bloc -> rec.read(bloc, 0, bloc.size) }
        } catch (e: Exception) {
            panne("lecture")
        } finally {
            try { rec.stop() } catch (_: Exception) { }
            try { rec.release() } catch (_: Exception) { }
        }
    }

    /**
     * Listening, whatever the source: blocks read by [lis] at [cadence], cut
     * into UTC slots, each one decoded; the waterfall fed as it comes.
     */
    private suspend fun ecoute(nom: String, portee: CoroutineScope, cadence: Int, lis: suspend (ShortArray) -> Int) {
        val mode = modeDe(nom)
        val tranche = trancheS(nom)
        val parTranche = (tranche * cadence).toInt()
        // Short blocks: at 4096 samples (a third of a second) the waterfall
        // would jump three rows at a time.
        val bloc = ShortArray(1024)
        val analyseur = fr.f4ioz.satcombo.domain.Ft8Cascade(BASSE_HZ, HAUTE_HZ, COLONNES, cadence)
        var tampon = FloatArray(parTranche)
        var ecrit = 0
        // Drop the first slot: it starts mid-transmission.
        var premiere = true
        var trancheCourante = numeroTranche(tranche)

        while (portee.isActive) {
            val lus = lis(bloc)
            if (lus <= 0) { delay(20); continue }

            var pic = 0f
            for (i in 0 until lus) {
                val v = bloc[i] / 32768f
                if (ecrit < parTranche) tampon[ecrit++] = v
                val a = kotlin.math.abs(v)
                if (a > pic) pic = a
            }

            val n = numeroTranche(tranche)
            if (n != trancheCourante) {
                val pleine = tampon
                val combien = ecrit
                trancheCourante = n
                tampon = FloatArray(parTranche)
                ecrit = 0
                if (premiere) {
                    premiere = false
                } else if (combien > mode.parSymbole * mode.symboles / 4) {
                    decodeTranche(pleine.copyOf(combien), mode, nom, cadence)
                }
            }

            val nouvelleLigne = analyseur.verse(bloc, lus)
            _state.value = _state.value.copy(
                niveau = pic,
                avancement = (ecrit.toFloat() / parTranche).coerceIn(0f, 1f),
                cascade = if (nouvelleLigne) analyseur.lignes.toList()
                          else _state.value.cascade,
                spectre = if (nouvelleLigne) analyseur.dernierSpectre
                          else _state.value.spectre)
        }
    }

    /** The clock the slots are cut by (a test follows the samples it feeds). */
    @Volatile internal var horloge: () -> Long = System::currentTimeMillis

    /** Current slot number since the UTC epoch. */
    private fun numeroTranche(dureeS: Double): Long =
        (horloge() / (dureeS * 1000).toLong())

    private const val COLONNES = 256
    /** The analysed band, in Hz. */
    private const val BASSE_HZ = 200
    private const val HAUTE_HZ = 3000

    /**
     * Live analyser.
     *
     * **Separate from decoding.** Built from the decoder's spectrogram, the
     * waterfall only moved every slot, and not at all when a slot failed —
     * yet tuning, before anything decodes, is when you most need to see the
     * band. So it runs on the input stream and never waits for the decoder.
     *
     * 2048 points at 12 kHz: 171 ms, 5.9 Hz per bin. An FT8 signal spans
     * eight bins — clear enough, without resolution that costs CPU per frame.
     */
    // The per-slot waterfall was removed: the live analyser answers the same
    // question better, and two versions would contradict each other on screen.

    private fun decodeTranche(audio: FloatArray, mode: Ft8Signal.Mode, nom: String, cadence: Int = CADENCE) {
        val entendus = try {
            Ft8Decodeur.decode(audio, cadence.toDouble(), mode)
        } catch (e: Exception) {
            emptyList()
        }
        val heure = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(java.util.Date(horloge()))

        val nouveaux = entendus.map {
            Entendu(
                heure = heure,
                texte = it.message.brut,
                frequenceHz = Math.round(it.frequenceHz).toInt(),
                rapportDb = it.rapportDb,
                decalageS = it.instantS,
                mode = nom
            )
        }
        val avant = _state.value
        _state.value = avant.copy(
            // Newest first, capped: an evening of listening would otherwise
            // hold thousands of lines.
            entendus = (nouveaux + avant.entendus).take(300),
            tranches = avant.tranches + 1)
    }
}
