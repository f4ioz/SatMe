/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.cat

import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.i18n.tf

/**
 * Test bench: the full start-of-pass sequence, played against a simulated rig.
 *
 * The claim it checks: a sane sequence must produce **zero refusals**. A
 * simulator that counts refusals turns "it didn't crash" into "the rig
 * understood everything" — two very different statements.
 */
object CatBench {

    data class Report(
        val steps: List<String>,
        val refusals: Int,
        val ok: Boolean
    ) {
        val summary: String
            get() = if (ok) t("catb_ok")
            else tf("catb_refusals", refusals)
    }

    /**
     * Start of a cross-band FM pass on an IC-9700: satellite mode, modes,
     * frequency pair, downlink read-back, access tone. Exactly what the app sends.
     */
    suspend fun runIc9700(
        sim: Ic9700Sim = Ic9700Sim(),
        downlinkHz: Long = 435_500_000L,
        uplinkHz: Long = 145_900_000L,
        toneTenthHz: Int = 670
    ): Report {
        val cat = CivController()
        cat.pacingMs = 0L
        cat.attach(sim)
        val steps = ArrayList<String>()

        cat.enterSatelliteMode(); steps += t("catb_sat_mode")
        cat.setModes("FM", "FM"); steps += "modes FM / FM"
        cat.setPair(downlinkHz, uplinkHz); steps += t("catb_pair")
        val back = cat.readDownlink()
        steps += tf("catb_readback", back?.let { "%.5f MHz".format(java.util.Locale.US, it / 1e6) } ?: t("catb_no_reply"))
        cat.setCtcss(toneTenthHz); steps += tf("catb_tone", "%.1f".format(java.util.Locale.US, toneTenthHz / 10.0))

        val ok = sim.refusals == 0 && back == downlinkHz &&
            sim.subHz == uplinkHz && sim.toneTenthHz == toneTenthHz
        return Report(steps, sim.refusals, ok)
    }

    /** Same on a pair of FT-817s: one on the downlink, one on the uplink, each on its own cable. */
    suspend fun runFt817Pair(
        rxSim: Ft817Sim = Ft817Sim(),
        txSim: Ft817Sim = Ft817Sim(),
        downlinkHz: Long = 145_800_000L,
        uplinkHz: Long = 437_800_000L,
        toneTenthHz: Int = 670
    ): Report {
        val pair = Ft817Pair()
        pair.attach(rxSim, txSim)
        pair.pacingMs = 0L
        val steps = ArrayList<String>()

        pair.setModes("FM", "FM"); steps += "modes FM / FM"
        pair.setPair(downlinkHz, uplinkHz); steps += t("catb_pair")
        val back = pair.readDownlink()
        steps += tf("catb_readback", back?.let { "%.5f MHz".format(java.util.Locale.US, it / 1e6) } ?: t("catb_no_reply"))
        pair.setCtcss(toneTenthHz); steps += tf("catb_tone", "%.1f".format(java.util.Locale.US, toneTenthHz / 10.0))

        val refusals = rxSim.refusals + txSim.refusals
        val ok = refusals == 0 && back == downlinkHz &&
            txSim.hz == uplinkHz && txSim.toneTenthHz == toneTenthHz
        return Report(steps, refusals, ok)
    }

    /**
     * FT-817 + IC-705, each speaking its own protocol on its own cable: the
     * IC-705 on the downlink, or on the uplink when [ic705Emet]. Passes only
     * with no refusal on either side: a single IC-9700 command slipped to the
     * IC-705 (satellite mode, MAIN/SUB) would show here.
     */
    suspend fun runFt817Ic705(
        icom: Ic705Sim = Ic705Sim(),
        yaesu: Ft817Sim = Ft817Sim(),
        ic705Emet: Boolean = false,
        downlinkHz: Long = 145_800_000L,
        uplinkHz: Long = 437_800_000L,
        toneTenthHz: Int = 670
    ): Report {
        val pair = Ft817Pair()
        pair.configure(rxIc705 = !ic705Emet, txIc705 = ic705Emet)
        if (ic705Emet) pair.attach(yaesu, icom) else pair.attach(icom, yaesu)
        pair.pacingMs = 0L
        val steps = ArrayList<String>()

        pair.setModes("FM", "FM"); steps += "modes FM / FM"
        pair.setPair(downlinkHz, uplinkHz); steps += t("catb_pair")
        val back = pair.readDownlink()
        steps += tf("catb_readback", back?.let { "%.5f MHz".format(java.util.Locale.US, it / 1e6) } ?: t("catb_no_reply"))
        pair.setCtcss(toneTenthHz); steps += tf("catb_tone", "%.1f".format(java.util.Locale.US, toneTenthHz / 10.0))

        val refusals = icom.refusals + yaesu.refusals
        val (hzMontee, tonMontee) =
            if (ic705Emet) icom.hz to icom.toneTenthHz else yaesu.hz to yaesu.toneTenthHz
        val ok = refusals == 0 && back == downlinkHz &&
            hzMontee == uplinkHz && tonMontee == toneTenthHz
        return Report(steps, refusals, ok)
    }
}
