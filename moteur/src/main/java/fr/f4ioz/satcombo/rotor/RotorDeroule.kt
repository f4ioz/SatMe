/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.rotor

import kotlin.math.abs
import kotlin.math.max

/**
 * When the mast goes round to the other side of its end stop during a pass.
 *
 * The plan ([RotorMath.plan]) picks the side before the pass, the dead zone
 * ("tolerated offset") included: a start a few degrees behind the stop waits
 * on the other side, the beam is wide. But a pass may still cross the stop
 * far beyond the dead zone; then the mast must go round — about a minute of
 * antenna turning through the whole sky. When to do it:
 *
 * - **as soon as** the satellite is beyond the stop by more than the dead
 *   zone, and the other side does better;
 * - **earlier**, when an SSTV picture is due while the satellite is beyond
 *   it and there is time to go round before it starts (the ISS sends its
 *   pictures at a steady pace: the next one is known from the last ones);
 * - **later**, when a picture is being received, or would start before the
 *   turn is over: once it has ended;
 * - **never while transmitting**: the operator is told.
 */
object RotorDeroule {

    /** Assumed until measured: a common azimuth rotator turns 5 to 6 degrees a second. */
    const val VITESSE_DEFAUT = 6.0

    /** Spare time on a turn: starting, stopping, the controller's slack. */
    const val MARGE_MS = 5_000L

    /** How long going round [degres] takes at [degParS]. */
    fun dureeMs(degres: Double, degParS: Double): Long =
        (abs(degres) / max(0.5, degParS) * 1000.0).toLong() + MARGE_MS

    /**
     * The pictures still to come, from those received ([recues]: start..end,
     * reception times): the same period (the median gap between starts, 30 s
     * to 10 min apart) and the same length as the last one. Empty with fewer
     * than two: no pace to go by.
     */
    fun imagesAVenir(recues: List<LongRange>, depuis: Long, jusqua: Long): List<LongRange> {
        if (recues.size < 2) return emptyList()
        val tri = recues.sortedBy { it.first }
        val ecarts = tri.zipWithNext { a, b -> b.first - a.first }.filter { it in 30_000L..600_000L }.sorted()
        if (ecarts.isEmpty()) return emptyList()
        val periode = ecarts[ecarts.size / 2]
        val duree = (tri.last().last - tri.last().first).coerceAtLeast(10_000L)
        val out = ArrayList<LongRange>()
        var d = tri.last().first + periode
        while (d <= jusqua && out.size < 50) {
            if (d + duree >= depuis) out += d..(d + duree)
            d += periode
        }
        return out
    }

    /** Why a turn waits. */
    enum class Raison { AUCUNE, EMISSION, IMAGE_EN_COURS, IMAGE_TROP_PROCHE }

    /** Go round now ([tourner]), or wait — why, and until when when known. */
    data class Decision(val tourner: Boolean, val raison: Raison = Raison.AUCUNE, val jusquaMs: Long? = null)

    /**
     * May a turn of [dureeMs] start at [maintenant]? Not while transmitting;
     * not while a picture is coming in, nor when one would start before the
     * turn is over ([images]: being received and to come) — then after it.
     */
    fun decide(maintenant: Long, dureeMs: Long, emission: Boolean, images: List<LongRange>): Decision {
        if (emission) return Decision(false, Raison.EMISSION)
        images.firstOrNull { maintenant in it }?.let { return Decision(false, Raison.IMAGE_EN_COURS, it.last) }
        images.filter { it.first > maintenant && it.first < maintenant + dureeMs }.minByOrNull { it.first }
            ?.let { return Decision(false, Raison.IMAGE_TROP_PROCHE, it.last) }
        return Decision(true)
    }

    /**
     * Needed now: on its side the mast misses the satellite by more than the
     * dead zone ([erreurIci] > [zoneMorte]), and the other side does better.
     */
    fun besoin(erreurIci: Double, erreurAutre: Double, zoneMorte: Double): Boolean =
        erreurIci > zoneMorte + 1e-9 && erreurAutre < erreurIci - 1e-9

    /**
     * Worth doing before a picture: during it the satellite would be beyond
     * the dead zone on this side ([erreurIciPendantImage]) but within it on
     * the other ([erreurAutrePendantImage]); the other side is fine now too
     * ([erreurAutreMaintenant]); and the turn ends before the picture starts.
     */
    fun anticiper(
        erreurIciPendantImage: Double, erreurAutrePendantImage: Double, erreurAutreMaintenant: Double,
        zoneMorte: Double, avantImageMs: Long, dureeMs: Long
    ): Boolean =
        erreurIciPendantImage > zoneMorte + 1e-9 && erreurAutrePendantImage <= zoneMorte + 1e-9 &&
            erreurAutreMaintenant <= zoneMorte + 1e-9 && avantImageMs >= dureeMs

    /** What [vise] decided, for the operator. */
    enum class Etat { SUIT, TOUR, TOUR_ANTICIPE, ATTEND_EMISSION, ATTEND_IMAGE, ATTEND_IMAGE_PROCHE }

    /**
     * The aim of this second, and why: following on the side the mast is on,
     * or the same sky point one turn away when going round is worth it and
     * allowed. [cmd] is the last command (it carries the side). [prevue] gives
     * where the satellite will be at a time (null outside the pass).
     */
    data class Vise(val aim: RotorMath.Aim, val etat: Etat, val dureeS: Int = 0, val jusquaMs: Long? = null,
                    val imageMs: Long? = null)

    fun vise(
        azDeg: Double, elDeg: Double, cmd: RotorPos, limits: RotorMath.Limits, wasFlipped: Boolean,
        zoneMorte: Double, maintenant: Long, images: List<LongRange>, emission: Boolean, degParS: Double,
        prevue: (Long) -> Pair<Double, Double>?
    ): Vise {
        val ici = RotorMath.follow(azDeg, elDeg, cmd, limits, wasFlipped)
        if (limits.azMaxDeg < 360.0 - 1e-9) return Vise(ici, Etat.SUIT)
        val autre = listOf(360.0, -360.0).map { s ->
            RotorMath.follow(azDeg, elDeg, RotorPos(cmd.azDeg + s, cmd.elDeg), limits, wasFlipped)
        }.filter { abs(it.azDeg - ici.azDeg) > 180.0 }.minByOrNull { it.errorDeg } ?: return Vise(ici, Etat.SUIT)
        val duree = dureeMs(autre.azDeg - ici.azDeg, degParS)
        var veut = besoin(ici.errorDeg, autre.errorDeg, zoneMorte)
        var image: LongRange? = null
        if (!veut) {
            val prochaine = images.filter { it.first > maintenant }.minByOrNull { it.first }
            if (prochaine != null) {
                val pts = listOf(prochaine.first, (prochaine.first + prochaine.last) / 2, prochaine.last).mapNotNull(prevue)
                if (pts.isNotEmpty()) {
                    val eIci = pts.maxOf { (az, el) -> RotorMath.follow(az, el, RotorPos(ici.azDeg, el), limits).errorDeg }
                    val eAutre = pts.maxOf { (az, el) -> RotorMath.follow(az, el, RotorPos(autre.azDeg, el), limits).errorDeg }
                    if (anticiper(eIci, eAutre, autre.errorDeg, zoneMorte, prochaine.first - maintenant, duree)) {
                        veut = true; image = prochaine
                    }
                }
            }
        }
        if (!veut) return Vise(ici, Etat.SUIT)
        val s = ((duree - MARGE_MS) / 1000).toInt().coerceAtLeast(1)
        val d = decide(maintenant, duree, emission, images)
        return when {
            d.tourner -> Vise(autre, if (image != null) Etat.TOUR_ANTICIPE else Etat.TOUR, s, imageMs = image?.first)
            d.raison == Raison.EMISSION -> Vise(ici, Etat.ATTEND_EMISSION, s)
            d.raison == Raison.IMAGE_EN_COURS -> Vise(ici, Etat.ATTEND_IMAGE, s, d.jusquaMs)
            else -> Vise(ici, Etat.ATTEND_IMAGE_PROCHE, s, d.jusquaMs)
        }
    }

    /**
     * The rotator's speed, measured between two readings while it turns (more
     * than half a degree a second), smoothed: one reading drifts, ten do not.
     * Null when the two readings say nothing of its speed.
     */
    fun mesureVitesse(az1: Double, t1: Long, az2: Double, t2: Long, avant: Double?): Double? {
        val dt = (t2 - t1) / 1000.0
        if (dt !in 0.5..3.0) return null
        val v = abs(az2 - az1) / dt
        if (v < 0.5 || v > 30.0) return null
        return if (avant == null) v else avant * 0.8 + v * 0.2
    }
}
