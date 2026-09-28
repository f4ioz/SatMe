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
 * One rig holding one frequency: one side of a two-rig station, RX or TX.
 *
 * The pair ([Ft817Pair]) drives two of these without knowing which protocol
 * each speaks: an FT-817 (Yaesu CAT) or an IC-705 (Icom CI-V). The ViewModel
 * reasons in "downlink" and "uplink", never in brands.
 */
interface PosteSimple {
    val isOpen: Boolean
    /** Gap between frames: what the real rig needs, zero on the bench. */
    var pacingMs: Long
    /** Plugs in any serial line — a real cable or a simulated rig. */
    fun attach(l: SerialLink)
    /** Opens the USB adapter with key [cle] (see [IdentiteUsb]). */
    suspend fun open(cle: String?, baud: Int): Boolean
    fun close()
    suspend fun setFrequency(hz: Long): Boolean
    suspend fun readFrequency(): Long?
    /** LSB, USB, CW, AM, FM… */
    suspend fun setMode(mode: String): Boolean
    /** TX access tone in tenths of Hz (670 = 67.0 Hz), zero to turn it off. */
    suspend fun setCtcss(tenthHz: Int): Boolean
    /** True while transmitting, false while receiving, null if unknown. */
    suspend fun isTransmitting(): Boolean?
    /** Raw TX-status byte for the diagnostic line, when the protocol has one. */
    val dernierEtatTx: Int? get() = null
}
