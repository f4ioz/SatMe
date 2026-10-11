/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.plateforme

import fr.f4ioz.satcombo.cat.SerialLink

/**
 * A serial port the platform offers: one port of a USB adapter on the phone,
 * `/dev/ttyUSB0` or `COM3` on a PC.
 *
 * [index] counts ports across all adapters, in the order shown to the
 * operator; [appareil] and [port] say which adapter and which of its ports (an
 * IC-9700 shows two). [cle] names the adapter for good (see `IdentiteUsb`).
 */
data class PortSerie(
    val index: Int,
    val appareil: Int,
    val port: Int,
    val nbPorts: Int,
    val libelle: String,
    val cle: String,
    val vendorId: Int,
    val produit: String?
)

/**
 * RTS and DTR when the port opens.
 *
 * **Low unless asked.** On a rig interface they key the transmitter (a
 * TS-2000 went to transmit that way, 07/10/2026); on an Arduino, raising DTR
 * reboots the board. The drivers that need them up — Icom CDC bridges and
 * GS-232 sketches stay silent otherwise — say so.
 */
enum class Lignes { BASSES, LEVEES }

/** What opening gave: the line, or why not, in words for the operator. */
class Ouverture(val lien: SerialLink?, val erreur: String)

/** The serial ports of the platform, for the rig and rotator drivers. */
interface PortsSerie {
    /** Every port visible now. */
    fun liste(): List<PortSerie>

    /** May this port be opened without asking? */
    fun autorise(p: PortSerie): Boolean

    /** Asks the operator, without waiting for the answer. */
    fun demande(p: PortSerie)

    /** Asks the operator and waits for the answer (true: allowed). */
    suspend fun demandeEtAttends(p: PortSerie): Boolean

    /** Opens [p] at [baud] baud, 8N1, with RTS/DTR as [lignes] says. */
    suspend fun ouvre(p: PortSerie, baud: Int, lignes: Lignes = Lignes.BASSES): Ouverture
}
