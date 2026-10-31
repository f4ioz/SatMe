/*
 * TellusMe — the SatMe ecosystem's terrestrial station
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.tellus

import fr.f4ioz.satcombo.sdr.hpsdr.HpsdrDecouverte
import fr.f4ioz.satcombo.sdr.hpsdr.HpsdrProtocole
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.SocketTimeoutException
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A Brick2 in memory, on the loopback only (127.0.0.1, a free port): the
 * tests' radio and `tellusme --simulation`'s. Answers the discovery, streams
 * EP6 frames at the asked rate once started, follows the frequency of the
 * EP2 frames, and records every datagram it was sent, so a test can check
 * that no MOX bit and no TX sample ever came. The band holds a few made-up
 * signals (a keyed CW carrier, an SSB-like voice, FT8-like tones, an AM
 * broadcaster) over noise; sent conjugated, as the real radio's are.
 */
class SimulateurBrick(private val bruit: Double = 40.0) {

    data class Signal(val hz: Long, val niveau: Double, val genre: String)

    val signaux = listOf(
        Signal(7_010_500, 6000.0, "cw"), Signal(7_074_600, 2500.0, "ft8"), Signal(7_075_400, 1800.0, "ft8"),
        Signal(7_076_200, 3000.0, "ft8"), Signal(7_090_000, 9000.0, "blu"), Signal(7_200_000, 30000.0, "am"),
        Signal(14_074_800, 2000.0, "ft8"), Signal(14_025_000, 5000.0, "cw"), Signal(14_210_000, 7000.0, "blu"),
    )

    private val prise = DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)); soTimeout = 200 }
    val port: Int get() = prise.localPort

    @Volatile var freq = 0L; private set
    @Volatile var debit = 48_000; private set
    @Volatile var enFlux = false; private set
    @Volatile private var client: SocketAddress? = null
    @Volatile private var actif = true
    val recus = java.util.Collections.synchronizedList(ArrayList<ByteArray>())
    @Volatile var trameEnvoyees = 0L; private set
    private val alea = java.util.Random(7)
    /** Each signal's phase, carried from sample to sample: a frequency that moves must not jump in phase. */
    private val phases = DoubleArray(signaux.size)

    private val filEcoute = Thread({ ecoute() }, "brick-simule").apply { isDaemon = true; start() }
    private val filFlux = Thread({ flux() }, "brick-simule-iq").apply { isDaemon = true; start() }

    private fun ecoute() {
        val b = ByteArray(2048)
        while (actif) {
            val p = DatagramPacket(b, b.size)
            try { prise.receive(p) } catch (e: SocketTimeoutException) { continue } catch (e: Exception) { break }
            val d = p.data.copyOf(p.length)
            recus += d
            if (d.size < 4 || (d[0].toInt() and 0xFF) != 0xEF || (d[1].toInt() and 0xFF) != 0xFE) continue
            when (d[2].toInt() and 0xFF) {
                0x02 -> { val r = HpsdrDecouverte.reponseRadio(byteArrayOf(0x00, 0x1C, 0xC0.toByte(), 0xA2.toByte(), 0x13, 0x07), 33, 0x01, enFlux)
                    runCatching { prise.send(DatagramPacket(r, r.size, p.socketAddress)) } }
                0x04 -> { enFlux = d[3].toInt() == 0x01; client = if (enFlux) p.socketAddress else null }
                0x01 -> {
                    HpsdrProtocole.frequenceEp2(d)?.let { freq = it }
                    HpsdrProtocole.debitEp2(d)?.let { debit = it }
                }
            }
        }
    }

    private fun flux() {
        var seq = 0L
        var t = 0L
        var dette = 0.0
        var dernier = System.nanoTime()
        val iq = FloatArray(2 * HpsdrProtocole.ECHANTILLONS_TRAME)
        while (actif) {
            Thread.sleep(5)
            val c = client
            if (!enFlux || c == null) { dernier = System.nanoTime(); continue }
            val maintenant = System.nanoTime()
            dette += (maintenant - dernier) / 1e9 * debit / HpsdrProtocole.ECHANTILLONS_TRAME
            dernier = maintenant
            if (dette > 40) dette = 40.0
            while (dette >= 1.0) {
                dette -= 1.0
                for (k in 0 until HpsdrProtocole.ECHANTILLONS_TRAME) { echantillon(t++, iq, k) }
                val f = HpsdrProtocole.trameEp6(seq++, iq)
                runCatching { prise.send(DatagramPacket(f, f.size, c)); trameEnvoyees++ }
            }
        }
    }

    private fun echantillon(t: Long, iq: FloatArray, k: Int) {
        val s = t.toDouble() / debit
        var re = alea.nextGaussian() * bruit
        var im = alea.nextGaussian() * bruit
        for ((i, sg) in signaux.withIndex()) {
            val off = (sg.hz - freq).toDouble()
            if (kotlin.math.abs(off) > debit / 2.0) continue
            val (a, df) = when (sg.genre) {
                "cw" -> (if (((s * 6).toLong() % 3) != 0L) sg.niveau else 0.0) to 0.0
                "ft8" -> sg.niveau to (((s / 0.16).toLong() * 7919 % 8) * 6.25)
                "blu" -> sg.niveau * (0.5 + 0.5 * sin(2 * PI * 3.1 * s)) to (300.0 + 900.0 * (0.5 + 0.5 * sin(2 * PI * 0.7 * s)))
                else -> sg.niveau * (1.0 + 0.6 * sin(2 * PI * 400.0 * s)) to 0.0
            }
            // Conjugated, like the real radio: the session conjugates back.
            phases[i] = (phases[i] - 2 * PI * (off + df) / debit) % (2 * PI)
            if (a == 0.0) continue
            re += a * cos(phases[i]); im += a * sin(phases[i])
        }
        iq[2 * k] = re.toFloat(); iq[2 * k + 1] = im.toFloat()
    }

    fun ferme() { actif = false; runCatching { prise.close() } }
}
