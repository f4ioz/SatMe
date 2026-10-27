/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

import kotlin.math.abs
import kotlin.math.ceil

/**
 * « Prochaine image dans 1:24 »: when the next SSTV picture of a pass should
 * begin, for the countdown beside the live picture.
 *
 * A satellite sends its pictures on a rhythm: announced (ARISS « Robot 36 ·
 * 36 s on / 2 min off », a campaign's timetable) or not. The period is the
 * announced one when there is one, else estimated from the starts of the
 * pictures received in this pass: the period most of the gaps between them
 * are a whole number of (one picture missed is a gap of two), refined on
 * them, the latest gap breaking a tie (a satellite that changes its rhythm
 * keeps the new one). Either way the phase is the last start received: every
 * new picture puts the countdown right again. While a picture arrives, its
 * progress; once the expected start has passed by a fifth of a period,
 * « en retard »; after two periods, « en attente ». At the satellite's LOS,
 * « fin du passage ». An estimate, said as one.
 *
 * Pure Kotlin, no clock of its own: [etat] at a given moment.
 */
object ProchaineImageSstv {

    enum class Phase {
        /** Not one picture's start known yet, nor a period. */
        INCONNU,
        /** A picture is arriving. */
        EN_COURS,
        /** Counting down to the next. */
        ATTENTE,
        /** The next start passed by more than [RETARD] of a period. */
        EN_RETARD,
        /** No picture for [ATTENTE_PERIODES] periods: the rhythm is no longer known. */
        EN_ATTENTE,
        /** The satellite has set. */
        FIN_PASSAGE,
    }

    enum class Source { HORAIRE, ESTIME }

    /** A picture under way: when it began, its rows decoded, its mode. */
    class EnCours(val debutMs: Long, val lignes: Int, val mode: SstvMode)

    data class Etat(
        val phase: Phase,
        /** When the next picture should begin, UTC ms (0: unknown). */
        val prochaineMs: Long = 0L,
        /** The period used, ms (0: unknown). */
        val periodeMs: Long = 0L,
        val source: Source? = null,
        /** Picture starts it rests on. */
        val images: Int = 0,
        /** 0..1: how sure (the announced timetable, or many starts that agree, near 1). */
        val confiance: Double = 0.0,
        /** The picture under way: 0..1, its rows, its total, ms left. */
        val progression: Double = 0.0,
        val lignes: Int = 0,
        val total: Int = 0,
        val resteImageMs: Long = 0L,
        /** The next start falls after LOS. */
        val apresLos: Boolean = false,
        /** Late: the slot after (a picture missed), UTC ms; 0 otherwise. */
        val suivanteMs: Long = 0L,
        val mode: String = "",
    ) {
        /** ms left until the next start (negative: late). */
        fun resteMs(maintenant: Long) = if (prochaineMs > 0L) prochaineMs - maintenant else 0L
    }

    /** A late start stops being « imminent » after this share of the period. */
    const val RETARD = 0.2
    /** Periods without a picture before the rhythm is given up. */
    const val ATTENTE_PERIODES = 2.0
    /** Gaps fit a period to this share of it. */
    private const val TOLERANCE = 0.08
    /** Pictures in a row a gap may have missed, plus one. */
    private const val K_MAX = 4L
    /** Shortest period believed, ms (a picture is at least this long). */
    private const val PERIODE_MIN_MS = 20_000L

    /**
     * The period of [debuts] (UTC ms, any order), ms, and how many gaps fit
     * it; null with fewer than two starts. See the class.
     */
    fun periode(debuts: List<Long>, minMs: Long = PERIODE_MIN_MS): Pair<Long, Int>? {
        val t = debuts.sorted().distinct()
        if (t.size < 2) return null
        val gaps = (1 until t.size).map { (t[it] - t[it - 1]).toDouble() }.filter { it >= minMs }
        if (gaps.isEmpty()) return null
        // Each candidate scored: the gaps it fits, less half a point per picture it says was missed — any short
        // enough period fits every gap (40 s fits 121 and 198), with pictures missed everywhere.
        var best = 0.0; var bestScore = Double.NEGATIVE_INFINITY; var bestRecent = -1
        val candidats = gaps.flatMap { g -> (1..3).map { g / it } }.filter { it >= minMs }
        for (p in candidats) {
            var n = 0; var manquees = 0; var recent = -1
            for ((i, g) in gaps.withIndex()) {
                val k = Math.round(g / p)
                if (k in 1..K_MAX && abs(g - k * p) <= TOLERANCE * p) { n++; manquees += (k - 1).toInt(); recent = i }
            }
            val score = n - 0.5 * manquees
            // The best score; then the one the latest gap fits (a new rhythm); then the longest period.
            if (score > bestScore || (score == bestScore && recent > bestRecent) || (score == bestScore && recent == bestRecent && p > best)) {
                best = p; bestScore = score; bestRecent = recent
            }
        }
        // Refined on the gaps that fit it.
        val fits = gaps.mapNotNull { g -> val k = Math.round(g / best); if (k in 1..K_MAX && abs(g - k * best) <= TOLERANCE * best) g / k else null }
        val p = if (fits.isEmpty()) best else fits.sorted()[fits.size / 2]
        return p.toLong() to fits.size
    }

    /**
     * Where the countdown stands at [maintenant]: [debuts] the starts of the
     * pictures received in this pass (a picture with its header; one
     * continued after a lost sync counts once), [enCours] the picture
     * arriving, [periodeAnnonceeS] the announced timetable's period
     * (null: none), [losMs] the satellite's LOS (null: unknown).
     */
    fun etat(maintenant: Long, debuts: List<Long>, enCours: EnCours? = null, periodeAnnonceeS: Double? = null,
             losMs: Long? = null, mode: SstvMode? = null): Etat {
        if (losMs != null && maintenant >= losMs) return Etat(Phase.FIN_PASSAGE, mode = mode?.name ?: "")
        val t = (debuts + listOfNotNull(enCours?.debutMs)).filter { it in 1..maintenant }.sorted().distinct()
        val estime = periode(t)
        val (p, source, confiance) = when {
            periodeAnnonceeS != null -> Triple((periodeAnnonceeS * 1000).toLong(), Source.HORAIRE,
                // Announced, and the starts received agree with it: sure.
                if (estime != null && abs(estime.first - periodeAnnonceeS * 1000) <= TOLERANCE * periodeAnnonceeS * 1000) 0.95 else 0.8)
            estime != null -> Triple(estime.first, Source.ESTIME, (0.3 + 0.2 * estime.second).coerceAtMost(0.9))
            else -> Triple(0L, null, 0.0)
        }
        val dernier = t.lastOrNull()
        val m = enCours?.mode ?: mode
        val nom = m?.name ?: ""
        fun apres(x: Long) = losMs != null && x >= losMs
        if (enCours != null) {
            val dureeMs = (enCours.mode.frameSeconds * 1000).toLong()
            val total = enCours.mode.height
            val prochaine = if (p > 0) enCours.debutMs + p else 0L
            return Etat(Phase.EN_COURS, prochaine, p, source, t.size, confiance,
                (enCours.lignes.toDouble() / total).coerceIn(0.0, 1.0), enCours.lignes, total,
                (enCours.debutMs + dureeMs - maintenant).coerceAtLeast(0L), prochaine > 0 && apres(prochaine), mode = nom)
        }
        if (dernier == null || p <= 0) return Etat(Phase.INCONNU, periodeMs = p, source = source, images = t.size, confiance = confiance, mode = nom)
        val prochaine = dernier + p
        val retard = maintenant - prochaine
        val phase = when {
            retard <= RETARD * p -> Phase.ATTENTE
            retard <= (ATTENTE_PERIODES - 1) * p -> Phase.EN_RETARD
            else -> Phase.EN_ATTENTE
        }
        // Late: the slot after (a picture missed) is still said, as « peut-être dans … ».
        val vise = if (phase == Phase.EN_RETARD) dernier + ceil((maintenant - dernier).toDouble() / p).toLong() * p else prochaine
        return Etat(phase, if (phase == Phase.EN_ATTENTE) 0L else prochaine, p, source, t.size,
            if (phase == Phase.ATTENTE) confiance else confiance / 2, apresLos = apres(vise), mode = nom,
            suivanteMs = if (phase == Phase.EN_RETARD) vise else 0L)
    }

    /** The countdown as JSON for the pages (one shape everywhere). */
    fun json(e: Etat, maintenant: Long): org.json.JSONObject = org.json.JSONObject()
        .put("phase", e.phase.name).put("prochaine", e.prochaineMs).put("reste", e.resteMs(maintenant))
        .put("periode", e.periodeMs).put("source", e.source?.name ?: "").put("images", e.images)
        .put("confiance", Math.round(e.confiance * 100) / 100.0).put("progression", Math.round(e.progression * 1000) / 1000.0)
        .put("lignes", e.lignes).put("total", e.total).put("resteImage", e.resteImageMs).put("apresLos", e.apresLos)
        .put("suivante", if (e.suivanteMs > 0L) e.suivanteMs - maintenant else 0L)
        .put("mode", e.mode).put("maintenant", maintenant)
}
