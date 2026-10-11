/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

/**
 * Doppler for a whole pass, not just the present instant.
 *
 * The range rate used to reach the display only once the satellite was above
 * the horizon, so opening an **upcoming** pass showed nothing — yet before the
 * pass is exactly when you want to know where to set the VFO. This calculation
 * knows no horizon: give it a sampler and two rest frequencies, it returns the
 * pass table.
 *
 * **Direction of the shift, once and for all**, because it always surprises:
 * on the downlink, the satellite approaches, range rate is negative, received
 * frequency is too high; it then only falls, crosses the rest frequency at
 * maximum elevation, and ends too low. The uplink does the opposite for the
 * same reason (the satellite is the receiver). So the two VFOs move in
 * opposite directions: that is the signature of correct tracking, not a bug.
 *
 * **Transponder inversion does not change that direction.** It only decides
 * which uplink channel matches the chosen downlink, handled upstream by
 * [Doppler.transponderUplinkRest]. Confusing the two is the most common
 * mistake on inverting linear transponders, and costs a whole pass looking for
 * your own echo.
 */
object DopplerPass {

    /** One instant of the pass, as returned by the sampler. */
    data class Sample(
        val timeMs: Long,
        val elevationDeg: Double,
        /** Range rate in km/s, positive when receding. */
        val rangeRateKmS: Double
    )

    /** One table row: mark and time, elevation, RX ↓, TX ↑. */
    data class Row(
        val mark: String,
        val timeMs: Long,
        val elevationDeg: Double,
        val rxHz: Long,
        val txHz: Long?
    )

    /** The full table, with total excursion of both links. */
    data class Table(
        val rows: List<Row> = emptyList(),
        val rxRestHz: Long = 0L,
        val txRestHz: Long? = null,
        /** Highest minus lowest received frequency. */
        val rxExcursionHz: Long = 0L,
        /** Same for the uplink; zero when there is none. */
        val txExcursionHz: Long = 0L
    ) {
        val isEmpty: Boolean get() = rows.isEmpty()
    }

    const val MARK_AOS = "AOS"
    const val MARK_MAX = "MAX"
    const val MARK_LOS = "LOS"

    /** Coarse sweep step: half a minute is enough to find the peak. */
    const val COARSE_MS = 30_000L

    /** Refinement step around the peak. */
    const val FINE_MS = 2_000L

    /**
     * Receive rest frequency to use, or null.
     *
     * Manual tuning beats the catalogue, band centre beats band bottom. **But
     * with no transmitter selected there is no rest frequency at all**, whatever
     * was tuned before. Otherwise, when switching satellite, the transmitter
     * list emptied during reload while the manual tuning from the previous
     * satellite stayed: the table showed a full RX column inherited from another
     * satellite next to an empty TX column. The worst kind of error — the kind
     * that looks like it works.
     */
    fun rxRest(reposAccorde: Long?, centreEmetteur: Long?, basEmetteur: Long?): Long? {
        if (centreEmetteur == null && basEmetteur == null) return null
        return reposAccorde ?: centreEmetteur ?: basEmetteur
    }

    /**
     * Builds the table for a pass.
     *
     * [sample] takes (start, end, step) and returns the instants: no TLE or
     * SGP4 here, so it can be tested on a hand-written trajectory.
     *
     * Maximum elevation is searched every 30 s then refined to 2 s. Placing it
     * at mid-time is wrong as soon as the pass is asymmetric, which is almost
     * always.
     */
    fun build(
        aosMs: Long,
        losMs: Long,
        rxRestHz: Long,
        txRestHz: Long? = null,
        rows: Int = 7,
        sample: (Long, Long, Long) -> List<Sample>
    ): Table {
        if (losMs <= aosMs || rxRestHz <= 0L) return Table(rxRestHz = rxRestHz, txRestHz = txRestHz)
        val coarse = sample(aosMs, losMs, COARSE_MS).sortedBy { it.timeMs }
        if (coarse.isEmpty()) return Table(rxRestHz = rxRestHz, txRestHz = txRestHz)

        val top = coarse.maxByOrNull { it.elevationDeg }!!
        val fine = sample(
            (top.timeMs - COARSE_MS).coerceAtLeast(aosMs),
            (top.timeMs + COARSE_MS).coerceAtMost(losMs),
            FINE_MS
        )
        val best = (fine + top).maxByOrNull { it.elevationDeg }!!

        // Excursion over every sample, not only displayed rows: otherwise the
        // figure would depend on how many rows fit on screen.
        val all = (coarse + fine).sortedBy { it.timeMs }
        val rx = all.map { Doppler.downlink(rxRestHz, it.rangeRateKmS) }
        val rxExc = (rx.max() - rx.min())
        val txExc = if (txRestHz != null && txRestHz > 0L) {
            val tx = all.map { Doppler.uplink(txRestHz, it.rangeRateKmS) }
            tx.max() - tx.min()
        } else 0L

        val picked = LinkedHashMap<Long, Sample>()
        picked[coarse.first().timeMs] = coarse.first()
        val inner = (rows - 3).coerceAtLeast(0)
        for (k in 1..inner) {
            val t = aosMs + (losMs - aosMs) * k / (inner + 1)
            val s = coarse.minByOrNull { kotlin.math.abs(it.timeMs - t) } ?: continue
            picked[s.timeMs] = s
        }
        picked[best.timeMs] = best
        picked[coarse.last().timeMs] = coarse.last()

        val ordered = picked.values.sortedBy { it.timeMs }
        val out = ordered.mapIndexed { i, s ->
            val mark = when {
                i == 0 -> MARK_AOS
                i == ordered.size - 1 -> MARK_LOS
                s.timeMs == best.timeMs -> MARK_MAX
                else -> ""
            }
            Row(
                mark = mark,
                timeMs = s.timeMs,
                elevationDeg = s.elevationDeg,
                rxHz = Doppler.downlink(rxRestHz, s.rangeRateKmS),
                txHz = if (txRestHz != null && txRestHz > 0L)
                    Doppler.uplink(txRestHz, s.rangeRateKmS) else null
            )
        }
        return Table(out, rxRestHz, txRestHz, rxExc, txExc)
    }

    /** Excursion in kHz, one decimal, for display. */
    fun kHz(hz: Long): String = "%.1f kHz".format(hz / 1000.0)
}
