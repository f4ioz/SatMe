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
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToLong

/**
 * The radio's one USB serial line, shared by its two bands: one conversation
 * at a time (a question, its one-line answer), like the other CAT drivers.
 */
class Thd72Lien(private val context: Context? = null) {
    @Volatile private var link: SerialLink? = null
    val isOpen: Boolean get() = link != null
    private val fil = Mutex()
    var pacingMs: Long = 30L

    fun attach(l: SerialLink) { link = l }

    /** Opens the adapter with key [cle] at [baud] (9600 on a TH-D72), 8N1. Once for both bands. */
    suspend fun open(cle: String?, baud: Int): Boolean = withContext(Dispatchers.IO) {
        if (link != null) return@withContext true
        val ctx = context ?: return@withContext false
        val um = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(um).filter { um.hasPermission(it.device) }
        val cles = drivers.map { cleDe(it.device) }
        val choisie = IdentiteUsb.resout(cle, cles) ?: return@withContext false
        val driver = drivers.getOrNull(cles.indexOf(choisie)) ?: return@withContext false
        val conn = um.openDevice(driver.device) ?: return@withContext false
        val p = driver.ports.firstOrNull() ?: run { conn.close(); return@withContext false }
        runCatching {
            p.open(conn)
            p.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            runCatching { p.setDTR(true); p.setRTS(true) }
            link = UsbSerialLink(p)
        }.isSuccess
    }

    fun close() {
        runCatching { link?.close() }
        link = null
    }

    /**
     * Sends [c] followed by CR and returns the first line answered (without
     * CR), "?" or "N" included; null when nothing came.
     */
    suspend fun commande(c: String, delaiMs: Int = 800): String? = withContext(Dispatchers.IO) {
        fil.withLock {
            val l = link ?: return@withLock null
            val tampon = ByteArray(256)
            while (l.read(tampon, 5) > 0) { /* leftovers belong to an older question */ }
            if (!l.write((c + "\r").toByteArray(Charsets.US_ASCII), 500)) return@withLock null
            val recu = StringBuilder()
            val fin = System.currentTimeMillis() + delaiMs
            while (System.currentTimeMillis() < fin) {
                val n = l.read(tampon, 50)
                for (i in 0 until n) recu.append((tampon[i].toInt() and 0xFF).toChar())
                val k = recu.indexOf("\r")
                if (k >= 0) {
                    if (pacingMs > 0) kotlinx.coroutines.delay(pacingMs)
                    return@withLock recu.substring(0, k).trim()
                }
            }
            null
        }
    }

    /** Is a TH-D72 (or a Kenwood speaking the same commands) on this line? */
    suspend fun estUnThd72(): Boolean = commande("FV 0")?.startsWith("FV 0,") == true

    suspend fun bandeCourante(): Int? = commande("BC")?.let { Regex("^BC (\\d)").find(it)?.groupValues?.get(1)?.toIntOrNull() }
    suspend fun choisitBande(b: Int): Boolean = commande("BC $b")?.startsWith("BC $b") == true
    suspend fun puissance(b: Int): Int? = commande("PC $b")?.let { Regex("^PC $b,(\\d)").find(it)?.groupValues?.get(1)?.toIntOrNull() }
    suspend fun reglePuissance(b: Int, p: Int): Boolean = commande("PC $b,$p")?.startsWith("PC $b,$p") == true
    suspend fun etatBande(b: Int): List<String>? = Thd72.champs(commande("FO $b"), b)
}

/**
 * One band of the TH-D72 as one side of a satellite pair: downlink on one
 * band, uplink on the other, the radio in full duplex. Frequencies are put
 * on the band's step (5 kHz as a rule); FM only.
 */
class Thd72Bande(val lien: Thd72Lien, val bande: Int) : PosteSimple {
    override val isOpen: Boolean get() = lien.isOpen
    override var pacingMs: Long
        get() = lien.pacingMs
        set(v) { lien.pacingMs = v }
    override fun attach(l: SerialLink) = lien.attach(l)
    override suspend fun open(cle: String?, baud: Int): Boolean = lien.open(cle, baud)
    override fun close() = lien.close()

    override suspend fun setFrequency(hz: Long): Boolean {
        val c = lien.etatBande(bande) ?: return false
        val cible = Thd72.arrondi(hz, Thd72.pas(c))
        if (cible == Thd72.frequence(c)) return true  // same channel: nothing to send
        val r = lien.commande(Thd72.commande(Thd72.avecFrequence(c, cible)))
        return Thd72.champs(r, bande)?.let { Thd72.frequence(it) == cible } == true
    }

    override suspend fun readFrequency(): Long? = lien.etatBande(bande)?.let { Thd72.frequence(it) }

    /** FM only: satellites a handheld works are FM. */
    override suspend fun setMode(mode: String): Boolean = mode.uppercase().startsWith("FM")

    override suspend fun setCtcss(tenthHz: Int): Boolean {
        val c = lien.etatBande(bande) ?: return false
        val n = Thd72.avecTon(c, tenthHz)
        if (n == c) return true
        return Thd72.champs(lien.commande(Thd72.commande(n)), bande) == n
    }

    /** No transmit-state command known on the TH-D72. */
    override suspend fun isTransmitting(): Boolean? = null
}
