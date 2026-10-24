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
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * How the reception goes while a picture comes in: the sound's level (too
 * low, saturated), each line's sync and its noise turned into a signal to
 * noise ratio, and the mark the picture is heading for ([ClassementSstv]'s)
 * before it ends. Fed with the same sound as the decoder, and told of each
 * line by [SstvDecoder.mesureLigne]: read only, the picture is the same.
 *
 * The SNR: the noise measured inside the sync pulses (Hz) goes as
 * 350·10^(−SNR/20), SNR in 3 kHz, fitted on Robot 36 with white noise from
 * 40 down to 10 dB; below, the pulses are lost in the noise (« < 10 dB »).
 *
 * The mark: each line spoilt 0..1 — its sync lost, or noisier than 130 Hz:
 * 1; else (noise − 10) / 120 — and 100 less 110 × their share, less the
 * share of lines missing. Fitted on the owner's 49 ISS pictures of series
 * 33 and 34 with their own sound (October 2026): within 4 points of the
 * mark of the picture kept, typically.
 *
 * Cheap: a sum and a peak over each block of sound, a few numbers a line.
 */
class QualiteSstv(private val rate: Int, private val horloge: () -> Long = System::currentTimeMillis) {

    enum class Niveau { SILENCE, FAIBLE, BON, FORT, SATURE }

    /** What the screens show. */
    data class Etat(
        /** Sound level, RMS dBFS over the last half second; null before any sound. */
        val niveauDb: Double? = null,
        /** Its peak, dBFS. */
        val creteDb: Double? = null,
        /** Share of samples clipped over the last half second. */
        val ecretes: Double = 0.0,
        val niveau: Niveau = Niveau.SILENCE,
        /** A picture is under way. */
        val image: Boolean = false,
        val mode: String = "",
        /** Lines measured, of the mode's height. */
        val lignes: Int = 0,
        val hauteur: Int = 0,
        /** Lines whose sync was found, clean. */
        val trouvees: Int = 0,
        val nettes: Int = 0,
        /** SNR (dB in 3 kHz) of the last lines, smoothed; null not measured. */
        val snrDb: Double? = null,
        /** The pulses are lost in the noise: below what [snrDb] can tell. */
        val snrSousSeuil: Boolean = false,
        /** Each line's quality 0..100 (100 clean), the picture so far. */
        val qualites: List<Int> = emptyList(),
        /** The mark it heads for, from the lines so far; null before 8 lines. */
        val noteEstimee: Int? = null,
        /** The last picture's estimate, once it ended (until the next one starts). */
        val noteFinale: Int? = null,
    )

    @Volatile var etat: Etat = Etat()
        private set

    // ---- the sound: half a second's sums
    private var somme2 = 0.0
    private var crete = 0
    private var ecretes = 0
    private var n = 0
    private val fenetre = (rate / 2).coerceAtLeast(1)

    /** A block of the decoder's sound. */
    @Synchronized fun son(pcm: ShortArray, count: Int) {
        for (i in 0 until count) {
            val a = abs(pcm[i].toInt())
            somme2 += a.toDouble() * a
            if (a > crete) crete = a
            if (a >= ECRETE) ecretes++
            if (++n >= fenetre) publieSon()
        }
    }

    private fun publieSon() {
        val rms = sqrt(somme2 / n)
        val db = if (rms < 1.0) -96.0 else 20 * log10(rms / 32768.0)
        val cdb = if (crete < 1) -96.0 else 20 * log10(crete / 32768.0)
        val part = ecretes.toDouble() / n
        etat = etat.copy(niveauDb = db, creteDb = cdb, ecretes = part, niveau = niveau(db, part))
        somme2 = 0.0; crete = 0; ecretes = 0; n = 0
    }

    // ---- the picture
    private var spoilt = 0.0
    private val qualites = ArrayList<Int>()
    private var snrLisse = Double.NaN
    private var sous = 0
    private var mesures = 0

    /** A header heard (or a start by hand): a new picture of [mode]. */
    @Synchronized fun debut(mode: SstvMode) {
        spoilt = 0.0; qualites.clear(); snrLisse = Double.NaN; sous = 0; mesures = 0
        etat = etat.copy(image = true, mode = mode.name, lignes = 0, hauteur = mode.height, trouvees = 0, nettes = 0,
            snrDb = null, snrSousSeuil = false, qualites = emptyList(), noteEstimee = null, noteFinale = null)
    }

    /** One block decoded ([SstvDecoder.mesureLigne]). */
    @Synchronized fun ligne(lignes: Int, trouve: Boolean, nette: Boolean, bruitHz: Double) {
        val e = etat
        if (!e.image) return
        val g = gache(trouve, bruitHz)
        repeat(lignes.coerceAtLeast(1)) { spoilt += g; qualites += ((1 - g) * 100).toInt() }
        if (trouve && bruitHz >= 0) {
            mesures++
            if (bruitHz > PERDU_HZ) sous++
            else {
                val s = snrDb(bruitHz)
                snrLisse = if (snrLisse.isNaN()) s else snrLisse + (s - snrLisse) * 0.15
            }
        } else if (!trouve) { mesures++; sous++ }
        val vues = qualites.size
        // Lost in the noise: most recent pulses unreadable.
        val sousSeuil = mesures >= 4 && sous * 2 > mesures
        etat = e.copy(lignes = vues.coerceAtMost(e.hauteur.takeIf { it > 0 } ?: vues),
            trouvees = e.trouvees + if (trouve) 1 else 0, nettes = e.nettes + if (nette) 1 else 0,
            snrDb = snrLisse.takeIf { !it.isNaN() }, snrSousSeuil = sousSeuil,
            qualites = qualites.toList(), noteEstimee = if (vues >= 8) note(spoilt, vues, vues) else null)
        if (mesures >= 24) { sous = sous * 3 / 4; mesures = mesures * 3 / 4 }
    }

    /** The picture ended (kept or not): its lines received of the mode's [hauteur]. */
    @Synchronized fun fin() {
        val e = etat
        if (!e.image) return
        val vues = qualites.size
        val finale = if (vues >= 8) note(spoilt, vues, e.hauteur.coerceAtLeast(vues)) else null
        etat = e.copy(image = false, noteEstimee = finale, noteFinale = finale)
    }

    companion object {
        /** A sample this high is clipped. */
        const val ECRETE = 32_000
        /** Noise in the sync above this: the pulse is lost (the reading saturates). */
        const val PERDU_HZ = 110.0
        /** The fit's constant: noise (Hz) = K·10^(−SNR/20). */
        const val K_HZ = 350.0

        /** A line spoilt, 0 clean .. 1 lost. */
        fun gache(trouve: Boolean, bruitHz: Double): Double = when {
            !trouve || bruitHz < 0 || bruitHz > 130.0 -> 1.0
            else -> ((bruitHz - 10.0) / 120.0).coerceIn(0.0, 1.0)
        }

        /** The SNR (dB in 3 kHz) a sync noise of [bruitHz] says. */
        fun snrDb(bruitHz: Double): Double = 20 * log10(K_HZ / bruitHz.coerceAtLeast(1.0))

        /** The mark: [spoilt] lines' worth over [vues] lines, the picture [hauteur] high (the rest missing). */
        fun note(spoilt: Double, vues: Int, hauteur: Int): Int {
            if (vues <= 0 || hauteur <= 0) return 0
            val h = maxOf(hauteur, vues).toDouble()
            return Math.round(100.0 - 110.0 * spoilt / h - 100.0 * (h - vues) / h).toInt().coerceIn(0, 100)
        }

        /** The level's verdict: [db] RMS dBFS, [ecretes] the share clipped. */
        fun niveau(db: Double, ecretes: Double): Niveau = when {
            ecretes > 0.002 || db > -3.0 -> Niveau.SATURE
            db > -9.0 -> Niveau.FORT
            db < -70.0 -> Niveau.SILENCE
            db < -45.0 -> Niveau.FAIBLE
            else -> Niveau.BON
        }
    }
}
