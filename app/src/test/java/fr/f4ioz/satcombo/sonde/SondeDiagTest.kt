/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sonde

import org.junit.Assume
import org.junit.Test

/**
 * Diagnostics that take the hub apart.
 *
 * When RS41 and M20 yield only one frame in six, both with and without the
 * radio chain, the receiver is not to blame. The question is where the rest
 * are lost: never found by the decoder, or found then rejected by flight
 * tracking. So this redoes by hand what [SondeHub] does, counting each step.
 */
class SondeDiagTest {

    private fun enabled(): Boolean = (System.getProperty("satme.bench") ?: "").isNotEmpty()

    @Test
    fun ou_se_perdent_les_trames_rs41() {
        Assume.assumeTrue("banc désactivé", enabled())
        val src = SondeMire.Source("RS41", 48.2, -4.5, 6, stepSec = SondeBench.STEP_SEC)
        val d = SondeDemod(SondeMire.RATE.toDouble(), Rs41.BAUD, 2048)
        val chunk = ShortArray(341)
        var scans = 0
        var hits = 0
        val seen = LinkedHashSet<Int>()
        while (true) {
            val n = src.read(chunk)
            if (n <= 0) break
            d.feedBits(chunk, n, null)
            if (d.bitsAvailable >= Rs41.LEN_STD * 8 + 64) {
                scans++
                for (off in 0 until 8) {
                    val cnt = d.packBytes(off)
                    val hit = Rs41.scan(d.bytes, 0, cnt, 404_000_000L, 0L) ?: continue
                    hits++
                    seen += hit.frame.frameNo
                    break
                }
                d.trimTo(Rs41.LEN_STD * 8)
            }
        }
        println("RS41 : $scans recherches, $hits trouvailles, numéros $seen")
    }

    @Test
    fun ou_se_perdent_les_trames_m20() {
        Assume.assumeTrue("banc désactivé", enabled())
        val src = SondeMire.Source("M20", 48.2, -4.5, 6, stepSec = SondeBench.STEP_SEC)
        val d = SondeDemod(SondeMire.RATE.toDouble(), Meteomodem.M20_BAUD, 2048)
        val chunk = ShortArray(341)
        var scans = 0
        var hits = 0
        val seen = LinkedHashSet<Int>()
        while (true) {
            val n = src.read(chunk)
            if (n <= 0) break
            d.feedBits(chunk, n, null)
            if (d.bitsAvailable >= Meteomodem.M20_LEN * 8 + 128) {
                scans++
                val chips = d.chipsCopy()
                for (off in 0 until 8) {
                    val cnt = d.packBytesMsb(chips, chips.size, off)
                    val hit = Meteomodem.scanM20(d.bytes, 0, cnt, 404_000_000L, 0L) ?: continue
                    hits++
                    seen += hit.frame.frameNo
                    break
                }
                d.trimTo(Meteomodem.M20_LEN * 8)
            }
        }
        println("M20 : $scans recherches, $hits trouvailles, numéros $seen")
    }

    @Test
    fun le_suivi_de_vol_accepte_t_il_tout() {
        Assume.assumeTrue("banc désactivé", enabled())
        // Six RS41 frames built and parsed without the radio: if flight
        // tracking rejects any, the fault is there and nowhere else.
        val flight = SondeMire.flight(48.2, -4.5, 6, stepSec = SondeBench.STEP_SEC)
        val fl = SondeFlight("MIRE", "RS41")
        for (s in 0 until 6) {
            // The test pattern returns the frame as sent over the air, i.e.
            // scrambled: descramble it first, as the decoder does (the
            // operation is its own inverse).
            val bytes = SondeMire.frameFor("RS41", flight[s], s + 1)
            Rs41.descramble(bytes)
            val f = Rs41.parse(bytes, 404_000_000L, 1_000L * (s + 1))
            if (f == null) { println("trame ${s + 1} : illisible"); continue }
            val ok = fl.add(f)
            println("trame ${s + 1} : n°${f.frameNo} sats=${f.sats} " +
                "alt=${"%.0f".format(f.altM)} plausible=${f.plausible} retenue=$ok")
        }
        println("vol : ${fl.count} points")
    }
}
