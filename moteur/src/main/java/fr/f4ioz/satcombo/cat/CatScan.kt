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
 * A serial port address: which device, and which port on that device.
 *
 * The IC-9700 exposes one USB device with two serial ports (A = CI-V, B = data)
 * plus a sound card. Opening only port 0 of the first device found meant that
 * with CI-V on B, or an SDR dongle enumerated first, we opened a real port that
 * never answered.
 */
data class PortRef(val deviceIndex: Int, val portIndex: Int, val label: String)

/** Port ordering, kept free of Android so it can be unit-tested. */
object CatScan {

    /**
     * Try order: the chosen port first (the operator picked it), then its
     * siblings on the same device, then the rest.
     *
     * Siblings are by far the most likely: right rig, wrong port. Other devices
     * come last because opening an SDR dongle for nothing costs a second of timeout.
     */
    fun ordre(refs: List<PortRef>, choisi: Int): List<Int> {
        if (refs.isEmpty()) return emptyList()
        val c = choisi.coerceIn(0, refs.size - 1)
        val dev = refs[c].deviceIndex
        val freres = refs.indices.filter { it != c && refs[it].deviceIndex == dev }
        val autres = refs.indices.filter { it != c && refs[it].deviceIndex != dev }
        return listOf(c) + freres + autres
    }

    /** Arduino boards (Arduino SA, Arduino.org): a rotator controller here, never a rig. */
    val VENDEURS_ARDUINO = setOf(0x2341, 0x2A03)

    /**
     * [ordre] without the rotor's port, nor any Arduino board.
     *
     * Trying a port opens it with DTR raised: on an Arduino that **reboots the
     * board** — a GS-232 controller's relays click — and takes the port away
     * from the rotor that holds it. The automatic CAT search tried every port
     * every 45 s around an armed pass. [identite]: a port's USB key and vendor
     * id, null when unknown (kept); [cleRotor]: the rotor's key, if known.
     */
    fun sansRotor(ordre: List<Int>, identite: (Int) -> Pair<String, Int>?, cleRotor: String?): List<Int> =
        ordre.filter { i ->
            val id = identite(i) ?: return@filter true
            id.second !in VENDEURS_ARDUINO && (cleRotor.isNullOrEmpty() || id.first != cleRotor)
        }

    /**
     * Label shown to the operator: "IC-9700 · port A".
     *
     * Product name if any, else the kernel path. The port letter appears only
     * when the device really exposes several ports.
     */
    fun etiquette(produit: String?, chemin: String, portIndex: Int, nbPorts: Int): String {
        val nom = produit?.trim().orEmpty().ifEmpty { chemin }
        return if (nbPorts <= 1) nom else nom + " \u00b7 port " + ('A' + portIndex)
    }
}
