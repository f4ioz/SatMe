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
 * Kenwood TH-D72 PC commands, as the author's radio answered them (USB,
 * CP210x, 9600 8N1, text lines ending in CR):
 *
 * - `FO b` → `FO b,0144800000,0,0,0,1,0,0,0,00,08,000,0,00600000,0`: the whole
 *   state of band b (0 = A, 1 = B). Fields: band, frequency (10 digits), step
 *   code, shift, reverse, tone on, CTCSS on, DCS on, ?, tone index, CTCSS
 *   index, DCS index, ?, offset, mode (0 = FM).
 * - `FO b,<same fields>` sets it and echoes it; a frequency off the band's
 *   step grid is refused with `N` and nothing changes.
 * - `BC` / `BC b`: band in use (the one PTT keys). `PC b` / `PC b,p`: power
 *   (0 high 5 W, 1 low, 2 extra low). `FV 0`: firmware. `?`: unknown command.
 *
 * No Android here: tested on the JVM, against [Thd72Sim].
 */
object Thd72 {

    /** Step codes of the FO command, in Hz. */
    val PAS: Map<String, Long> = mapOf("0" to 5_000L, "1" to 6_250L, "2" to 8_330L, "3" to 10_000L,
        "4" to 12_500L, "5" to 15_000L, "6" to 20_000L, "7" to 25_000L, "8" to 30_000L,
        "9" to 50_000L, "A" to 100_000L)

    /** The 42 tones of Kenwood radios, in tenths of Hz, by index. */
    val TONS: List<Int> = listOf(670, 693, 719, 744, 770, 797, 825, 854, 885, 915, 948, 974, 1000,
        1035, 1072, 1109, 1148, 1188, 1230, 1273, 1318, 1365, 1413, 1462, 1514, 1567, 1622, 1679,
        1738, 1799, 1862, 1928, 2035, 2065, 2107, 2181, 2257, 2291, 2336, 2418, 2503, 2541)

    /** Fields of an FO reply for band [bande], or null when it is not one. */
    fun champs(reponse: String?, bande: Int): List<String>? {
        val r = reponse?.trim() ?: return null
        if (!r.startsWith("FO $bande,")) return null
        val c = r.removePrefix("FO ").split(',')
        return c.takeIf { it.size >= 15 && it[1].length == 10 && it[1].all(Char::isDigit) }
    }

    fun frequence(c: List<String>): Long = c[1].toLong()
    fun pas(c: List<String>): Long = PAS[c[2]] ?: 5_000L

    /** [hz] on the band's step grid: the radio refuses anything else. */
    fun arrondi(hz: Long, pasHz: Long): Long = (hz.toDouble() / pasHz).roundToLong() * pasHz

    fun avecFrequence(c: List<String>, hz: Long): List<String> =
        c.toMutableList().also { it[1] = "%010d".format(hz) }

    /** Tone on the transmit side ([dixiemesHz] = 670 for 67.0 Hz), 0 to turn it off. */
    fun avecTon(c: List<String>, dixiemesHz: Int): List<String> = c.toMutableList().also {
        // Fields 5, 6, 7: tone, CTCSS, DCS on; field 9: tone index.
        if (dixiemesHz <= 0) { it[5] = "0"; return@also }
        val i = TONS.indices.minByOrNull { k -> kotlin.math.abs(TONS[k] - dixiemesHz) } ?: 0
        it[5] = "1"; it[6] = "0"; it[7] = "0"; it[9] = "%02d".format(i)
    }

    fun commande(c: List<String>): String = "FO " + c.joinToString(",")
}

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

/**
 * An in-memory TH-D72, answering as the author's did: two bands with their
 * FO state, step grid enforced ("N"), band in use, power, firmware.
 */
class Thd72Sim : SerialLink {
    val bandes = arrayOf(
        "0,0144800000,0,0,0,1,0,0,0,00,08,000,0,00600000,0".split(',').toMutableList(),
        "1,0435850000,0,0,0,0,0,0,0,09,04,000,0,01600000,0".split(',').toMutableList())
    var bandeCourante = 0
    val puissances = intArrayOf(0, 0)
    /** Every FO write accepted, in order: a test reads what the radio was told. */
    val ecritures = ArrayList<String>()
    private val sortie = java.util.concurrent.LinkedBlockingQueue<Byte>()
    private val ligne = StringBuilder()

    override fun write(bytes: ByteArray, timeoutMs: Int): Boolean {
        for (b in bytes) {
            val c = (b.toInt() and 0xFF).toChar()
            if (c == '\r') { repond(ligne.toString()); ligne.setLength(0) } else ligne.append(c)
        }
        return true
    }

    private fun dit(s: String) = (s + "\r").forEach { sortie.put(it.code.toByte()) }

    private fun repond(cmd: String) {
        val m = Regex("^FO (\\d)(,.*)?$").find(cmd)
        when {
            cmd == "FV 0" -> dit("FV 0,1.00,1.07,A,1")
            cmd == "BC" -> dit("BC $bandeCourante")
            cmd.matches(Regex("BC [01]")) -> { bandeCourante = cmd.last() - '0'; dit(cmd) }
            cmd.matches(Regex("PC [01]")) -> dit("$cmd,${puissances[cmd.last() - '0']}")
            cmd.matches(Regex("PC [01],[012]")) -> { puissances[cmd[3] - '0'] = cmd.last() - '0'; dit(cmd) }
            m != null && m.groupValues[2].isEmpty() -> {
                val b = m.groupValues[1].toInt(); if (b > 1) dit("N") else dit("FO " + bandes[b].joinToString(","))
            }
            m != null -> {
                val b = m.groupValues[1].toInt()
                val c = cmd.removePrefix("FO ").split(',')
                val hz = c.getOrNull(1)?.toLongOrNull()
                val pas = Thd72.PAS[c.getOrNull(2)]
                if (b > 1 || c.size != 15 || hz == null || pas == null || hz % pas != 0L) { dit("N"); return }
                bandes[b] = c.toMutableList(); ecritures += cmd; dit(cmd)
            }
            else -> dit("?")
        }
    }

    override fun read(buf: ByteArray, timeoutMs: Int): Int {
        val premier = sortie.poll(timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS) ?: return 0
        buf[0] = premier
        var n = 1
        while (n < buf.size) { buf[n] = sortie.poll() ?: break; n++ }
        return n
    }

    override fun close() {}
}
