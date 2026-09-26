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
            get() = if (ok) "Séquence complète, aucun refus du poste simulé."
            else "$refusals refus du poste simulé — voir le journal des trames."
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

        cat.enterSatelliteMode(); steps += "mode satellite"
        cat.setModes("FM", "FM"); steps += "modes FM / FM"
        cat.setPair(downlinkHz, uplinkHz); steps += "descente et montée"
        val back = cat.readDownlink()
        steps += "relecture : " + (back?.let { "%.5f MHz".format(it / 1e6) } ?: "aucune réponse")
        cat.setCtcss(toneTenthHz); steps += "ton d'accès %.1f Hz".format(toneTenthHz / 10.0)

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
        pair.setPair(downlinkHz, uplinkHz); steps += "descente et montée"
        val back = pair.readDownlink()
        steps += "relecture : " + (back?.let { "%.5f MHz".format(it / 1e6) } ?: "aucune réponse")
        pair.setCtcss(toneTenthHz); steps += "ton d'accès %.1f Hz".format(toneTenthHz / 10.0)

        val refusals = rxSim.refusals + txSim.refusals
        val ok = refusals == 0 && back == downlinkHz &&
            txSim.hz == uplinkHz && txSim.toneTenthHz == toneTenthHz
        return Report(steps, refusals, ok)
    }
}
