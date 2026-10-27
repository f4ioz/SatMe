/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site.station

import fr.f4ioz.satcombo.sdr.Fft
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * The panadapter's session with the radio — receive only (see
 * [HpsdrProtocole]). One for the whole site: every private client sees and
 * tunes the same one, the last order wins. The protocol has one client: while
 * it runs, the radio is taken from Thetis or piHPSDR (the page says so). It
 * ends 12 s after the last client went, after 15 minutes in any case, and
 * then tells the radio to stop.
 */
class HpsdrSession(
    /** The radio's address, or "" to find it ([trouve]). */
    private val hote: String,
    private val port: Int = 1024,
    private val trouve: () -> String? = { null },
    private val horloge: () -> Long = System::currentTimeMillis,
    private val maxSessionMs: Long = 15 * 60_000L,
    private val clientMs: Long = 12_000L,
) {
    @Volatile var enMarche = false; private set
    @Volatile var erreur: String? = null; private set
    @Volatile var freq: Long = 7_100_000L; private set
    @Volatile var debit: Int = 48_000; private set
    @Volatile var audio = false; private set
    @Volatile var smeter = -130.0; private set
    @Volatile private var spectre: FloatArray? = null
    @Volatile private var dernierClient = 0L
    @Volatile private var fil: Thread? = null
    /** The datagrams sent to the radio (tests read them: the MOX bit, the payloads). */
    @Volatile var envoyes = 0L; private set
    val demod = HpsdrDemod(debit)
    private val tamponAudio = ByteArrayOutputStream()

    fun touche() { dernierClient = horloge() }

    /** Tunes ([hz] within 100 kHz…60 MHz) and/or sets the span ([nouveauDebit] one of 48/96/192/384 kHz). */
    fun regle(hz: Long? = null, nouveauDebit: Int? = null) {
        if (hz != null && hz in 100_000L..60_000_000L) freq = hz
        if (nouveauDebit != null && nouveauDebit in HpsdrProtocole.CODES_DEBIT) { debit = nouveauDebit; demod.regle(debit = nouveauDebit) }
    }

    fun regleAudio(on: Boolean? = null, mode: String? = null, volume: Float? = null) {
        if (on != null) { audio = on; if (!on) synchronized(tamponAudio) { tamponAudio.reset() } }
        if (mode != null || volume != null) demod.regle(mode = mode?.uppercase(), volume = volume)
    }

    /** The audio come since last time (16-bit PCM, 12 kHz). */
    fun audioRecu(): ByteArray = synchronized(tamponAudio) { tamponAudio.toByteArray().also { tamponAudio.reset() } }

    /** Starts the session if it is not running; false (and [erreur]) when the radio cannot be found. */
    @Synchronized fun assure(): Boolean {
        touche()
        if (enMarche) return true
        val ip = hote.ifEmpty { trouve() ?: "" }
        if (ip.isEmpty()) { erreur = "SDR introuvable sur le réseau"; return false }
        erreur = null
        enMarche = true
        fil = Thread({ boucle(ip) }, "hpsdr-rx").apply { isDaemon = true; start() }
        return true
    }

    fun arrete() { enMarche = false }

    /** What the clients are sent: centre, span, S-meter, mode, the 2048 bins max-pooled to [n]. Null before the first spectrum. */
    fun instantane(n: Int = 512): JSONObject? {
        val s = spectre ?: return null
        val pas = s.size / n
        val bins = JSONArray()
        for (i in 0 until n) { var m = -1e9f; for (k in 0 until pas) m = maxOf(m, s[i * pas + k]); bins.put(Math.round(m)) }
        return JSONObject().put("center", freq).put("span", debit).put("smeter", Math.round(smeter * 10) / 10.0).put("mode", demod.mode)
            .put("audio", audio).put("bins", bins)
    }

    private fun boucle(ip: String) {
        val moi = Thread.currentThread()
        val debut = horloge()
        var seq = 0L
        var adresse: InetAddress? = null
        var prise: DatagramSocket? = null
        try {
            adresse = InetAddress.getByName(ip)
            val s = DatagramSocket(null).also { prise = it }
            s.reuseAddress = true; s.bind(InetSocketAddress(0)); s.soTimeout = 1000
            fun envoie(b: ByteArray) { s.send(DatagramPacket(b, b.size, adresse, port)); envoyes++ }
            envoie(HpsdrProtocole.metis(true))
            repeat(8) { envoie(HpsdrProtocole.ep2(seq++, HpsdrProtocole.CODES_DEBIT.getValue(debit), freq)) }
            val tampon = ByteArray(2048)
            val fenetre = DoubleArray(FFT) { 0.5 - 0.5 * cos(2.0 * PI * it / (FFT - 1)) }
            val re = DoubleArray(FFT); val im = DoubleArray(FFT)
            val acc = FloatArray(2 * FFT * 5); var nAcc = 0
            while (enMarche && fil === moi) {
                if (horloge() - dernierClient > clientMs || horloge() - debut > maxSessionMs) break
                val p = DatagramPacket(tampon, tampon.size)
                try { s.receive(p) } catch (e: SocketTimeoutException) {
                    envoie(HpsdrProtocole.ep2(seq++, HpsdrProtocole.CODES_DEBIT.getValue(debit), freq)); continue
                }
                val iq = HpsdrProtocole.ep6(p.data, p.length)
                // Keeps the stream going and applies the current frequency and span.
                envoie(HpsdrProtocole.ep2(seq++, HpsdrProtocole.CODES_DEBIT.getValue(debit), freq))
                if (iq.isEmpty()) continue
                // This radio's spectrum comes inverted (hardware-checked): conjugated so a carrier above the tuned
                // frequency shows above it, USB is USB and a click tunes where it points. Without it, NCO +5 kHz
                // showed at −5 kHz and the sidebands were swapped.
                conjugue(iq)
                val n = iq.size / 2
                if (audio) {
                    val pcm = demod.traite(iq, n)
                    if (pcm.isNotEmpty()) synchronized(tamponAudio) {
                        tamponAudio.write(pcm)
                        if (tamponAudio.size() > AUDIO_MAX) { val b = tamponAudio.toByteArray(); tamponAudio.reset(); tamponAudio.write(b, b.size - AUDIO_MAX, AUDIO_MAX) }
                    }
                }
                if (nAcc + iq.size > acc.size) nAcc = 0
                System.arraycopy(iq, 0, acc, nAcc, iq.size); nAcc += iq.size
                if (nAcc >= 2 * FFT) {
                    // The backlog: the last 2048 only when more than four FFTs wait.
                    val depart = if (nAcc > 2 * FFT * 4) nAcc - 2 * FFT else 0
                    for (k in 0 until FFT) { re[k] = acc[depart + 2 * k] * fenetre[k]; im[k] = acc[depart + 2 * k + 1] * fenetre[k] }
                    val reste = nAcc - depart - 2 * FFT
                    System.arraycopy(acc, depart + 2 * FFT, acc, 0, reste); nAcc = reste
                    spectre = spectreDb(re, im)
                }
            }
        } catch (e: Exception) {
            erreur = (e.message ?: e.javaClass.simpleName).take(120)
        } finally {
            runCatching { val a = adresse; val s = prise; if (a != null && s != null) { val b = HpsdrProtocole.metis(false); s.send(DatagramPacket(b, b.size, a, port)); envoyes++ } }
            runCatching { prise?.close() }
            if (fil === moi) { enMarche = false; spectre = null; fil = null }
        }
    }

    private fun spectreDb(re: DoubleArray, im: DoubleArray): FloatArray {
        Fft.transform(re, im)
        val db = FloatArray(FFT)
        for (k in 0 until FFT) {
            val j = (k + FFT / 2) % FFT   // fftshift: index 0 is −span/2
            db[k] = (20.0 * log10(sqrt(re[j] * re[j] + im[j] * im[j]) + 1e-6)).toFloat()
        }
        val c = FFT / 2
        var pic = -1e9
        for (k in c - 3..c + 3) pic = maxOf(pic, db[k].toDouble())
        smeter = 0.85 * smeter + 0.15 * (pic - 20.0 * log10(FFT * HpsdrDemod.PLEINE_ECHELLE.toDouble()))
        return db
    }

    companion object {
        const val FFT = 2048
        const val AUDIO_MAX = 48_000

        /** IQ (interleaved) conjugated in place: Q → −Q. */
        fun conjugue(iq: FloatArray) { var k = 1; while (k < iq.size) { iq[k] = -iq[k]; k += 2 } }
    }
}
