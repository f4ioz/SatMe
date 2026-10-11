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
 * Two rigs, one per leg (an FT-817 or IC-705 each side, a TS-2000 or a
 * TH-D72 driven as two): nothing to juggle, each leg on its own rig.
 */
class PosteCatPaire(private val p: Ft817Pair) : PosteCat {
    override val paire = true

    override suspend fun arme(disposition: DispositionSat, descente: ModePoste, montee: ModePoste, ctcssDixiemes: Int) {
        // Two dedicated rigs: no band/split gymnastics — just modes + tone.
        p.setModes(downlink = descente.nom, uplink = montee.nom)
        p.setCtcss(ctcssDixiemes)
    }

    override suspend fun lisDescente(memeBande: Boolean): Long? = p.readDownlink()

    override suspend fun lisMontee(): Long? = p.readUplink()

    override suspend fun ecrisDescente(hz: Long) { p.rx.setFrequency(hz) }

    override suspend fun ecrisCouple(descente: Long, montee: Long, memeBande: Boolean) = p.setPair(descente, montee)

    override suspend fun ecrisMontee(montee: Long, memeBande: Boolean) = p.setUplink(montee)
}
