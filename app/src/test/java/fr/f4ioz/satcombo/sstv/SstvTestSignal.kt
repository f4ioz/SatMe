/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

/**
 * The encoder used by the tests.
 *
 * It now lives in the app sources (SSTV test patterns need to make sound);
 * this name stays so the tests keep saying what they check: the mode table
 * read back through a path independent of the decoder.
 */
object SstvTestSignal {

    fun encode(
        mode: SstvMode,
        image: IntArray,
        sampleRate: Int,
        blocks: Int = mode.blocks,
        leadMs: Double = 120.0,
        trailMs: Double = 60.0
    ): ShortArray = SstvEncoder.encode(mode, image, sampleRate, blocks, leadMs, trailMs)
}
