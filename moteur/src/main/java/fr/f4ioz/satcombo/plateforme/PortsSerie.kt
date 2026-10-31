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
    val produit: String?,
    /** The adapter's serial number, when it has one and may be read (the phone: once allowed). */
    val serie: String? = null,
    /** Where the system shows it: `/dev/bus/usb/…` on the phone, `/dev/ttyUSB0` or `COM3` on a PC. */
    val chemin: String = ""
)

/**
 * RTS and DTR when the port opens.
 *
 * **Low unless asked.** On a rig interface they key the transmitter (a
 * TS-2000 went to transmit that way, 07/10/2026); on an Arduino, raising DTR
 * reboots the board. The drivers that need them up — Icom CDC bridges and
 * GS-232 sketches stay silent otherwise — say so.
 */
enum class Lignes {
    BASSES, LEVEES,
    /**
     * Left as the platform leaves them: the FT-817's cables were always
     * opened that way on the phone, and work. On a PC, where opening raises
     * them, this means low.
     */
    TELLES
}

/**
 * A RigExpert's second port: PTT on RTS, CW on DTR. SatMe never needs it,
 * and opening it with its lines up keys the transmitter — an IC-9700 search
 * did just that on a TS-2000 (12/10/2026). Never opened, whoever asks.
 */
fun PortSerie.portPttRigExpert(): Boolean = port > 0 && produit?.contains("RigExpert", ignoreCase = true) == true

/** Parity bit of a serial frame. */
enum class Parite { AUCUNE, PAIRE, IMPAIRE, MARQUE, ESPACE }

/**
 * The shape of each character on the line: data bits (5 to 8), parity, one
 * or two stop bits. [HUIT_N_UN] (8N1) is what every rig and rotator driver
 * uses; some old TNCs and instruments want 7E1.
 */
data class FormatSerie(val bitsDonnees: Int = 8, val parite: Parite = Parite.AUCUNE, val deuxArrets: Boolean = false) {
    init { require(bitsDonnees in 5..8) }
    val huitSansParite: Boolean get() = bitsDonnees == 8 && parite == Parite.AUCUNE

    /** "8N1", "7E1", "8N2"… */
    override fun toString(): String = "$bitsDonnees" + when (parite) {
        Parite.AUCUNE -> "N"; Parite.PAIRE -> "E"; Parite.IMPAIRE -> "O"; Parite.MARQUE -> "M"; Parite.ESPACE -> "S"
    } + if (deuxArrets) "2" else "1"

    companion object {
        val HUIT_N_UN = FormatSerie()
        val SEPT_E_UN = FormatSerie(7, Parite.PAIRE)

        /** "7E1", "8n2"… → the format, or null. */
        fun de(texte: String): FormatSerie? {
            val t = texte.trim().uppercase()
            if (t.length != 3) return null
            val bits = t[0].digitToIntOrNull()?.takeIf { it in 5..8 } ?: return null
            val p = when (t[1]) { 'N' -> Parite.AUCUNE; 'E' -> Parite.PAIRE; 'O' -> Parite.IMPAIRE; 'M' -> Parite.MARQUE; 'S' -> Parite.ESPACE; else -> return null }
            val arrets = when (t[2]) { '1' -> false; '2' -> true; else -> return null }
            return FormatSerie(bits, p, arrets)
        }
    }
}

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

    /**
     * Opens [p] at [baud] baud, 8 bits, no parity, one stop bit — two when
     * [deuxArrets] (Yaesu CAT, a Kenwood at 4800 or less) — with RTS/DTR as
     * [lignes] says. Low lines are set low before the speed too: a RigExpert
     * may carry PTT on them.
     */
    suspend fun ouvre(p: PortSerie, baud: Int, lignes: Lignes = Lignes.BASSES, deuxArrets: Boolean = false): Ouverture

    /**
     * Opens [p] with another character [format] (7E1…). A platform that only
     * knows 8 bits without parity opens those, and refuses the others —
     * saying so — rather than open the line wrong.
     */
    suspend fun ouvre(p: PortSerie, baud: Int, lignes: Lignes, format: FormatSerie): Ouverture =
        if (format.huitSansParite) ouvre(p, baud, lignes, format.deuxArrets)
        else Ouverture(null, "format $format non pris en charge sur cette plateforme")
}

/**
 * The ports of the adapter with key [cle] (see `IdentiteUsb`), among those
 * that may be opened without asking, in port order; empty when none answers
 * to it.
 */
fun PortsSerie.portsDe(cle: String?): List<PortSerie> {
    val permis = liste().filter { autorise(it) }
    val choisie = fr.f4ioz.satcombo.cat.IdentiteUsb.resout(cle, permis.map { it.cle }.distinct()) ?: return emptyList()
    return permis.filter { it.cle == choisie }.sortedBy { it.port }
}
