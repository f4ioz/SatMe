/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.cat

/**
 * Le banc d'essai : la séquence complète d'un début de passage, jouée contre un
 * poste qui n'existe pas.
 *
 * Ce n'est pas une démonstration. C'est la seule façon, pour l'instant,
 * d'affirmer quelque chose de vérifiable sur le pilotage CAT — et l'affirmation
 * est simple : une séquence saine ne doit produire **aucun refus**. Un poste
 * simulé qui compte ses refus transforme « ça n'a pas planté » en « le poste a
 * tout compris », et ces deux phrases n'ont rien à voir.
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
     * Un début de passage FM transbande sur un IC-9700 : mode satellite, modes
     * des deux voies, couple de fréquences, relecture de la descente, ton
     * d'accès. Rien d'exotique — c'est exactement ce que l'application émet.
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

    /**
     * La même chose sur un couple de FT-817 : un poste sur la descente, un sur
     * la montée, chacun sur son câble.
     */
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
