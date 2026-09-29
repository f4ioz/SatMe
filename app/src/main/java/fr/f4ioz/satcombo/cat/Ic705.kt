/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.cat

import android.content.Context

/**
 * Icom IC-705 as one side of a two-rig station, through its own USB port
 * (CI-V over the built-in Silicon Labs bridge).
 *
 * **One receiver, no satellite mode.** Unlike the IC-9700 there is no MAIN /
 * SUB and no 0x16 0x5A: the IC-705 holds one frequency, which is exactly what
 * a pair needs from it. Only generic CI-V is sent — 0x05 frequency, 0x03 read,
 * 0x06 mode, 0x16 0x42 and 0x1B tone, 0x1C TX state — through [CivController],
 * so framing, echo handling and the journal are the IC-9700's, tested.
 *
 * Default CI-V address 0xA4. The rig's "CI-V USB Echo Back" may stay on:
 * the controller already drops its own echo.
 */
class Ic705Cat(context: Context? = null) : PosteSimple {

    private val civ = CivController(context).apply { radioAddr = ADRESSE }

    override val isOpen: Boolean get() = civ.isOpen

    override var pacingMs: Long
        get() = civ.pacingMs
        set(v) { civ.pacingMs = v }

    override fun attach(l: SerialLink) = civ.attach(l)

    /**
     * [baud] must match the rig's "CI-V USB Baud Rate". On Auto the rig
     * follows any speed; set to a fixed rate, it hears only that one — hence
     * a setting of its own, apart from the FT-817's.
     */
    override suspend fun open(cle: String?, baud: Int): Boolean {
        // Like the IC-9700, the IC-705 shows more than one serial port over
        // USB; only one carries CI-V. Keep the first that answers a frequency
        // read, rather than assume which one it is.
        val n = civ.nombrePorts(cle).coerceAtLeast(1)
        for (port in 0 until n) {
            if (!civ.openParCle(cle, baud, port)) continue
            if (n == 1 || civ.readFrequency() != null) return true
            civ.close()
        }
        return false
    }

    override fun close() = civ.close()

    override suspend fun setFrequency(hz: Long): Boolean = civ.setFrequency(hz)

    override suspend fun readFrequency(): Long? = civ.readFrequency()

    override suspend fun setMode(mode: String): Boolean = civ.setMode(modeCiv(mode))

    override suspend fun setCtcss(tenthHz: Int): Boolean =
        if (tenthHz > 0) civ.setToneOn(true) && civ.setToneFreq(tenthHz)
        else civ.setToneOn(false)

    override suspend fun isTransmitting(): Boolean? = civ.isTransmitting()

    /** Why the last opening failed, for the diagnostic screen. */
    val derniereErreur: String get() = civ.lastError

    companion object {
        /** IC-705 factory CI-V address. */
        const val ADRESSE = 0xA4
        /** Default speed: fine with the rig's CI-V USB Baud Rate on Auto. */
        const val VITESSE = 115_200
    }
}
