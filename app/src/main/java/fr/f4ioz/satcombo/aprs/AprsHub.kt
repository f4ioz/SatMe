/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.aprs

import android.content.Context
import android.util.Base64
import fr.f4ioz.satcombo.sstv.Mp3Pcm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Between the APRS decoder and the rest of the app, like SstvHub for SSTV:
 * fed by the pass recorder's capture thread (or the SDR dongle's audio),
 * read by the APRS page through a [StateFlow].
 *
 * Every frame is written to disk as it arrives (files/aprs/AAAA-MM-JJ.txt:
 * time, satellite, AX.25 bytes in base 64): what was heard during a pass
 * survives the app being killed. The page shows the last seven days.
 */
object AprsHub {

    data class Etat(
        /** Hooked to a running recording. */
        val ecoute: Boolean = false,
        /** Satellite of that recording. */
        val sat: String = "",
        /** Heard, newest first (last 7 days, 500 at most). */
        val paquets: List<Paquet> = emptyList(),
        /** Frames since listening started. */
        val nouveaux: Int = 0,
        /** Re-decoding a file: its name, 0..1 (-1 when idle), frames found. */
        val fichier: String? = null,
        val progression: Float = -1f,
        val trouves: Int = 0,
        /** Decoder failures while listening (shown, never silent). */
        val erreur: String? = null,
    )

    private val _etat = MutableStateFlow(Etat())
    val etat: StateFlow<Etat> = _etat.asStateFlow()

    private const val JOURS = 7
    /** In the file's satellite column: this line was sent, not heard. */
    private const val EMIS = "↑"
    private const val MAX = 500

    fun dossier(ctx: Context): File = File(ctx.getExternalFilesDir(null), "aprs").apply { mkdirs() }

    private val jourUtc = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    @Volatile private var charge = false

    /** Reads the last days from disk, once. */
    @Synchronized
    fun charge(ctx: Context) {
        if (charge) return
        charge = true
        val limite = System.currentTimeMillis() - JOURS * 86_400_000L
        val lus = ArrayList<Paquet>()
        dossier(ctx).listFiles { f -> f.name.endsWith(".txt") }?.forEach { f ->
            if (f.lastModified() < limite) { f.delete(); return@forEach }
            f.forEachLine { ligne -> ligneVersPaquet(ligne)?.takeIf { it.quand >= limite }?.let { lus += it } }
        }
        _etat.value = _etat.value.copy(paquets = (lus + _etat.value.paquets)
            .distinctBy { it.quand to it.trame }.sortedByDescending { it.quand }.take(MAX))
    }

    private fun ligneVersPaquet(ligne: String): Paquet? = runCatching {
        val (ms, sat, b64) = ligne.split('\t', limit = 3)
        val t = Ax25.decode(Base64.decode(b64, Base64.NO_WRAP)) ?: return null
        Aprs.lit(t, ms.toLong()).copy(emis = sat == EMIS)
    }.getOrNull()

    private fun ecrit(ctx: Context, p: Paquet, sat: String) {
        runCatching {
            File(dossier(ctx), jourUtc.format(Date(p.quand)) + ".txt").appendText(
                "${p.quand}\t${sat.replace('\t', ' ')}\t" +
                    Base64.encodeToString(Ax25.encode(p.trame), Base64.NO_WRAP) + "\n")
        }
    }

    /** Adds a frame unless the same one is already there within a few seconds. */
    @Synchronized
    private fun ajoute(ctx: Context, t: Trame, quand: Long, sat: String): Boolean {
        val deja = _etat.value.paquets.any { it.trame == t && kotlin.math.abs(it.quand - quand) < 5_000 }
        if (deja) return false
        val p = Aprs.lit(t, quand)
        ecrit(ctx, p, sat)
        _etat.value = _etat.value.copy(paquets = (listOf(p) + _etat.value.paquets)
            .sortedByDescending { it.quand }.take(MAX))
        return true
    }

    // ---------------------------------------------------------------- live

    private var live: AfskDemodulateur? = null
    private var liveCtx: Context? = null
    @Volatile private var pannes = 0

    @Synchronized
    fun startLive(ctx: Context, frequence: Int, sat: String) {
        val app = ctx.applicationContext
        liveCtx = app
        charge(app)
        pannes = 0
        live = AfskDemodulateur(frequence) { t ->
            if (ajoute(app, t, System.currentTimeMillis(), sat)) {
                _etat.value = _etat.value.copy(nouveaux = _etat.value.nouveaux + 1)
            }
        }
        _etat.value = _etat.value.copy(ecoute = true, sat = sat, nouveaux = 0, erreur = null)
    }

    /** From the capture thread. A decoder failure must not stop the recording, nor go unseen. */
    fun feedLive(pcm: ShortArray, n: Int) {
        val d = live ?: return
        runCatching { d.traite(pcm, n) }.onFailure { e ->
            val k = ++pannes
            _etat.value = _etat.value.copy(erreur = e.javaClass.simpleName + if (k > 1) " ×$k" else "")
        }
    }

    @Synchronized
    fun stopLive() {
        live = null
        _etat.value = _etat.value.copy(ecoute = false)
    }

    // ----------------------------------------------------------- from file

    @Volatile private var annule = false
    fun annuleFichier() { annule = true }

    /**
     * Frames in a recording (SatMe MP3, or an imported WAV/MP3). Blocking: call
     * off the main thread. Timed from the recording's start when its name says
     * it ("SatMe_ISS_20260804_172011Z"), else from the file's date.
     */
    fun decodeFichier(ctx: Context, f: File): Int {
        val app = ctx.applicationContext
        charge(app)
        annule = false
        val sat = f.name.removePrefix("SatMe_").substringBefore("_").ifBlank { "SAT" }
        val debut = runCatching {
            SimpleDateFormat("yyyyMMdd_HHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                .parse(Regex("\\d{8}_\\d{6}Z").find(f.name)!!.value)!!.time
        }.getOrDefault(f.lastModified())
        var trouves = 0
        var demod: AfskDemodulateur? = null
        var frequence = 0
        _etat.value = _etat.value.copy(fichier = f.name, progression = 0f, trouves = 0)
        runCatching {
            Mp3Pcm.decode(f) { pcm, n, rate, fraction ->
                if (demod == null) {
                    frequence = rate
                    demod = AfskDemodulateur(rate) { t ->
                        val quand = debut + (demod?.position ?: 0L) * 1000 / frequence
                        if (ajoute(app, t, quand, sat)) trouves++
                    }
                }
                demod?.traite(pcm, n)
                _etat.value = _etat.value.copy(progression = fraction, trouves = trouves)
                !annule
            }
        }
        _etat.value = _etat.value.copy(progression = -1f, trouves = trouves)
        return trouves
    }

    /** A frame a KISS radio (TH-D72…) received and passed on, already decoded. */
    fun recuDuTnc(ctx: Context, t: Trame) = recuDuPoste(ctx, t, "TNC")

    /** A station a radio decoded itself (KISS, or an FT3D's waypoint), labelled [etiquette]. */
    fun recuDuPoste(ctx: Context, t: Trame, etiquette: String) {
        val app = ctx.applicationContext
        charge(app)
        ajoute(app, t, System.currentTimeMillis(), etiquette)
    }

    /** A frame this phone has just sent: shown and kept with the others. */
    @Synchronized
    fun ajouteEmis(ctx: Context, t: Trame) {
        charge(ctx)
        val p = Aprs.lit(t, System.currentTimeMillis()).copy(emis = true)
        ecrit(ctx, p, EMIS)
        _etat.value = _etat.value.copy(paquets = (listOf(p) + _etat.value.paquets).take(MAX))
    }

    /** Clears the history (disk and screen). */
    @Synchronized
    fun efface(ctx: Context) {
        dossier(ctx).listFiles()?.forEach { it.delete() }
        _etat.value = _etat.value.copy(paquets = emptyList(), nouveaux = 0)
    }
}
