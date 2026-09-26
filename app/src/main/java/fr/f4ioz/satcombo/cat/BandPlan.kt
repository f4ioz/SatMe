/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.cat

/**
 * In which order to write the two frequencies of a satellite pair.
 *
 * IC-9700 rule: **MAIN and SUB can never be on the same band at once**. The rig
 * refuses silently, with a bare NAK.
 *
 * Going from a V/U bird (downlink 435, uplink 145) to a U/V one is a pure band
 * swap: whatever fixed order you pick, the first write lands one band where the
 * other still is, and the rig never changes band. No fixed order can work.
 *
 * Given what the rig shows now and the target, this returns a write sequence
 * where no intermediate step puts both on one band. Three cases:
 *
 * 1. Uplink first is harmless — the usual order, ending on RX so the dial is live.
 * 2. Downlink first, because the uplink's target band is held by the current downlink.
 * 3. Pure swap: both orders collide. Park the uplink on a **third** band first.
 *    The IC-9700 always has one (144, 430, 1200); it costs one extra frame per
 *    satellite change.
 *
 * (0x07 0xB0 swaps MAIN/SUB in one frame; deliberately not used — we stick to
 * bench-proven commands, the gain is half a second once per pass.)
 */
object BandPlan {

    /** The rig's bands, plus "elsewhere" for everything else. */
    enum class Band { V, U, L, AUTRE }

    /** 2 m, 70 cm, 23 cm — wide, because Doppler spills past the edges. */
    fun band(hz: Long): Band = when (hz) {
        in 143_000_000L..149_000_000L -> Band.V
        in 420_000_000L..460_000_000L -> Band.U
        in 1_200_000_000L..1_320_000_000L -> Band.L
        else -> Band.AUTRE
    }

    /** One write: which VFO (MAIN/SUB), and which frequency. */
    data class Step(val sub: Boolean, val hz: Long)

    /** Mid-band frequencies where an uplink in transit is parked. */
    private val garages = linkedMapOf(
        Band.V to 145_000_000L,
        Band.U to 435_000_000L,
        Band.L to 1_295_000_000L)

    /** A free band: none of the ones being targeted. */
    fun garageHz(vararg eviter: Band): Long =
        garages.entries.firstOrNull { it.key !in eviter }?.value ?: 145_000_000L

    /**
     * The writes to send, in order.
     *
     * [mainNow]/[subNow] are what the rig shows now, or null if unknown — then
     * they constrain nothing and we fall back to the usual order.
     */
    fun steps(mainNow: Long?, subNow: Long?, mainTarget: Long, subTarget: Long): List<Step> {
        val bm = band(mainTarget)
        val bs = band(subTarget)
        val descente = Step(sub = false, hz = mainTarget)
        val montee = Step(sub = true, hz = subTarget)

        // A target that itself puts both on one band can't be met. Write the
        // downlink (so we can hear) and leave the uplink alone.
        if (bm == bs) return listOf(descente)

        val monteeDabord = mainNow == null || band(mainNow) != bs
        val descenteDabord = subNow == null || band(subNow) != bm

        return when {
            monteeDabord -> listOf(montee, descente)
            descenteDabord -> listOf(descente, montee)
            // Pure swap: park the uplink, set the downlink in the freed band, then the uplink.
            else -> listOf(Step(true, garageHz(bm, bs)), descente, montee)
        }
    }
}
