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

/**
 * Kenwood TS-2000 PC commands, as the author's rig answered them in SAT mode
 * (RigExpert Tiny, FTDI FT2232, port A, 57600 8N1, RTS and DTR low):
 *
 * - `ID;` → `ID019;`: a TS-2000.
 * - `SA;` → `SA1010110RS44;`: satellite mode on (first digit), then its
 *   settings and the memory's name.
 * - In SAT mode, `FA` is the **downlink** (main band, receiving) and `FB`
 *   the **uplink**: `FA;` → `FA00435659600;`, `FA00435660000;` sets it
 *   (no answer: a Kenwood set is silent, a read-back checks it). Writing FA
 *   does not move FB (TRACE off): both are written, as on the IC-9700.
 * - `FR;` / `FT;` are refused (`?;`) in SAT mode.
 * - `MD;` → `MD2;` (1 LSB, 2 USB, 3 CW, 4 FM, 5 AM): the mode of the side
 *   under control. In SAT mode **`DC01;` toggles control** between downlink
 *   and uplink (`DC00;` does nothing, `DC;` always says `DC00;`); where it is
 *   shows in `SA`'s fourth digit — `SA1010110` downlink, `SA1011110` uplink.
 *   So the uplink's mode (and tone) is set by toggling, setting, toggling
 *   back. Seen on the author's rig on 07/10: SO-50 was failing because its
 *   uplink had stayed in LSB.
 * - `SM0;` → `SM00000;`: the main band's S-meter, 0 to 30.
 * - `IF;` → the main band's state: character 28 is 1 while transmitting, 29
 *   the mode (`IF00435659600    -0001000000022000010;` in USB, receiving).
 *
 * **Never transmits.** No `TX;`, no `KY`: the rig keys from its microphone
 * or the operator's PTT only. RTS and DTR stay low: on a RigExpert they may
 * key the transmitter.
 *
 * No Android in [Ts2000]: tested on the JVM, against [Ts2000Sim].
 */
object Ts2000 {

    /** What `ID;` answers on a TS-2000. */
    const val ID = "ID019;"

    /** CAT speeds tried, the rig's menu 56 being unknown (57600 on the author's). */
    val VITESSES = listOf(57_600, 9_600, 38_400, 19_200, 4_800, 115_200)

    /** `FA00435660000;`: [vfo] "A" (downlink in SAT mode) or "B" (uplink). */
    fun ecrit(vfo: String, hz: Long): String = "F$vfo%011d;".format(hz)

    /** The frequency in an `FA…;` / `FB…;` answer, or null. */
    fun frequence(reponse: String?, vfo: String): Long? =
        Regex("^F$vfo(\\d{11});$").find(reponse?.trim() ?: "")?.groupValues?.get(1)?.toLongOrNull()

    /** Kenwood mode codes. */
    fun codeMode(mode: String): Int? = when (mode.uppercase()) {
        "LSB" -> 1; "USB" -> 2; "CW" -> 3; "FM" -> 4; "AM" -> 5; else -> null
    }

    /** Satellite mode on, from a `SA…;` answer. */
    fun enSatellite(reponse: String?): Boolean = reponse?.trim()?.startsWith("SA1") == true

    /** Control on the uplink, from a `SA…;` answer (its fourth digit); null when unreadable. */
    fun controleMontee(reponse: String?): Boolean? {
        val r = reponse?.trim() ?: return null
        if (!r.startsWith("SA") || r.length < 9) return null
        return when (r[5]) { '1' -> true; '0' -> false; else -> null }
    }

    /** The memory's name in a `SA…;` answer ("RS44"), or "". */
    fun nomSatellite(reponse: String?): String =
        reponse?.trim()?.takeIf { it.startsWith("SA") && it.length > 10 }?.substring(9)?.removeSuffix(";")?.trim() ?: ""

    /** The S-meter (0 to 30) in a `SM0nnnn;` answer, or null. */
    fun smetre(reponse: String?): Int? =
        Regex("^SM0(\\d{4});$").find(reponse?.trim() ?: "")?.groupValues?.get(1)?.toIntOrNull()

    /**
     * The TS-2000's 0..30 on Icom's scale (S0..S9 over 0..120, then +60 dB
     * over 120..241), so the journal draws both rigs alike: S9 is about 15.
     */
    fun versIcom(v: Int): Int = if (v <= 15) v * 120 / 15 else 120 + (v - 15) * 121 / 15

    /** Transmitting, from an `IF…;` answer: its character 28 (29 is the mode). */
    fun emission(reponse: String?): Boolean? {
        val r = reponse?.trim() ?: return null
        if (!r.startsWith("IF") || r.length < 38) return null
        return r[28] == '1'
    }

    /**
     * The tone number for [dixiemesHz] (670 for 67.0 Hz): Kenwood's 42 tones,
     * numbered from 01. *Not yet tried on the real rig.*
     */
    fun numeroTon(dixiemesHz: Int): Int =
        (Thd72.TONS.indices.minByOrNull { kotlin.math.abs(Thd72.TONS[it] - dixiemesHz) } ?: 0) + 1

    /** A command that keys the transmitter: never sent, refused by [Ts2000Lien]. */
    fun emet(c: String): Boolean = c.startsWith("TX") || c.startsWith("KY")
}

/**
 * The TS-2000's serial line, shared by both sides (one cable, one rig).
 * Finds the port that answers `ID019;` and the speed it answers at.
 */
class Ts2000Lien(private val context: Context? = null) {
    @Volatile private var link: SerialLink? = null
    val isOpen: Boolean get() = link != null
    private val fil = Mutex()
    var pacingMs: Long = 20L
    /** The speed found, for the status line. */
    var vitesse: Int = 0
        private set
    /** The adapter it answered on (its key). */
    var cle: String? = null
        private set

    fun attach(l: SerialLink) { link = l }

    /**
     * Opens the adapter with key [cle]: each of its ports (the RigExpert Tiny
     * has two, CAT on the first) at each speed, [baud] first, until the rig
     * says `ID019;`. Port by port, so the one carrying PTT is never reached
     * once the CAT port has answered.
     */
    suspend fun open(cle: String?, baud: Int): Boolean = ouverture.withLock { ouvre(cle, baud) }

    /** One search at a time: both sides share this line. */
    private val ouverture = Mutex()

    private suspend fun ouvre(cle: String?, baud: Int): Boolean = withContext(Dispatchers.IO) {
        if (link != null) return@withContext true
        val ctx = context ?: return@withContext false
        val um = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(um).filter { um.hasPermission(it.device) }
        val cles = drivers.map { cleDe(it.device) }
        val choisie = IdentiteUsb.resout(cle, cles) ?: return@withContext false
        val driver = drivers.getOrNull(cles.indexOf(choisie)) ?: return@withContext false
        val vitesses = (listOf(baud) + Ts2000.VITESSES).filter { it > 0 }.distinct()
        trace("adaptateur ${driver.device.productName} ${driver.javaClass.simpleName}, ${driver.ports.size} port(s)")
        // A RigExpert carries CAT on its first port only; the second is PTT/CW: never written to.
        val ports = if (driver.device.productName?.contains("RigExpert", ignoreCase = true) == true) driver.ports.take(1) else driver.ports
        for ((i, p) in ports.withIndex()) {
            for (v in vitesses) {
                val conn = um.openDevice(driver.device) ?: run { trace("openDevice refusé"); return@withContext false }
                val ouvert = runCatching {
                    p.open(conn)
                    // RTS and DTR low before anything else: on a RigExpert they may be PTT.
                    runCatching { p.setDTR(false); p.setRTS(false) }
                    p.setParameters(v, 8, if (v <= 4_800) UsbSerialPort.STOPBITS_2 else UsbSerialPort.STOPBITS_1,
                        UsbSerialPort.PARITY_NONE)
                    runCatching { p.setDTR(false); p.setRTS(false) }
                }
                var echo = false
                if (ouvert.isSuccess) {
                    link = LienTrace(p)
                    val r = identifie()
                    trace("port $i à $v bauds : ID; → ${r ?: "rien"}")
                    if (r == Ts2000.ID) { vitesse = v; this@Ts2000Lien.cle = choisie; link = UsbSerialLink(p); return@withContext true }
                    // A port that sends our own words back is not a rig (the Tiny's PTT/CW port).
                    echo = r == "ID;"
                    link = null
                } else trace("port $i à $v bauds : ouverture impossible (${ouvert.exceptionOrNull()?.message})")
                runCatching { p.close() }
                runCatching { conn.close() }
                if (echo) break
            }
        }
        false
    }

    fun close() {
        runCatching { link?.close() }
        link = null
    }

    /**
     * `ID;` asked so the rig hears it whole: first a lone ';' ends whatever
     * the opening of the port left in its buffer (it answers `?;` to that,
     * thrown away), then the question — asked twice if it still says `?;`.
     */
    private suspend fun identifie(): String? {
        commande(";", 300)
        val r = commande("ID;", 700)
        return if (r == "?;") commande("ID;", 700) else r
    }

    /** What the search tried and got, in the phone's log (`adb logcat -s SatMeTs2000`). */
    private fun trace(m: String) { runCatching { android.util.Log.i("SatMeTs2000", m) } }

    /** The line while searching: USB errors written to the log instead of swallowed. */
    private inner class LienTrace(private val port: UsbSerialPort) : SerialLink {
        override fun write(bytes: ByteArray, timeoutMs: Int): Boolean =
            runCatching { port.write(bytes, timeoutMs) }.onFailure { trace("écriture : ${it.javaClass.simpleName} ${it.message}") }.isSuccess
        override fun read(buf: ByteArray, timeoutMs: Int): Int =
            runCatching { port.read(buf, timeoutMs) }.onFailure { trace("lecture : ${it.javaClass.simpleName} ${it.message}") }.getOrDefault(0)
        override fun close() { runCatching { port.close() } }
    }

    /**
     * Sends [c] (ending in ';'). A question gets its answer (up to ';'), null
     * when nothing came; a setting gets "" (the rig answers nothing), or "?;"
     * when it refuses one at once. Never a command that keys the transmitter.
     */
    suspend fun commande(c: String, delaiMs: Int = 500): String? = withContext(Dispatchers.IO) {
        require(!Ts2000.emet(c)) { "no transmit command" }
        fil.withLock {
            val l = link ?: return@withLock null
            // At least one USB packet: an FTDI FT2232H (the RigExpert Tiny) sends 512 bytes at a
            // time, and a smaller buffer makes every read fail without a word.
            val tampon = ByteArray(4096)
            while (l.read(tampon, 5) > 0) { /* leftovers belong to an older question */ }
            if (!l.write(c.toByteArray(Charsets.US_ASCII), 500)) return@withLock null
            CatJournal.log(true, c.toByteArray(Charsets.US_ASCII), c)
            // A question is two letters (plus an optional digit for SM0;) then ';'.
            val question = Regex("^[A-Z]{2}\\d?;$").matches(c)
            val recu = StringBuilder()
            val fin = System.currentTimeMillis() + if (question) delaiMs else 40
            while (System.currentTimeMillis() < fin) {
                val n = l.read(tampon, if (question) 50 else 20)
                for (i in 0 until n) recu.append((tampon[i].toInt() and 0xFF).toChar())
                val k = recu.indexOf(";")
                if (k >= 0) {
                    val r = recu.substring(0, k + 1).trim()
                    CatJournal.log(false, r.toByteArray(Charsets.US_ASCII), r)
                    if (pacingMs > 0) kotlinx.coroutines.delay(pacingMs)
                    return@withLock r
                }
            }
            if (pacingMs > 0) kotlinx.coroutines.delay(pacingMs)
            if (question) null else ""
        }
    }

    suspend fun estUnTs2000(): Boolean = commande("ID;") == Ts2000.ID

    /** One toggle at a time: the side under control must never be left wrong. */
    private val bascule = Mutex()

    /** Puts control on the uplink ([montee]) or the downlink, by `DC01;` (a toggle), checked by `SA`. */
    private suspend fun controle(montee: Boolean): Boolean {
        repeat(2) {
            val ici = Ts2000.controleMontee(commande("SA;")) ?: return false
            if (ici == montee) return true
            commande("DC01;")
        }
        return Ts2000.controleMontee(commande("SA;")) == montee
    }

    /**
     * Runs [bloc] with control on the uplink, then gives control back to the
     * downlink whatever happened — the operator's knob and `MD` belong there.
     */
    suspend fun surLaMontee(bloc: suspend () -> Boolean): Boolean = bascule.withLock {
        try {
            controle(montee = true) && bloc()
        } finally {
            controle(montee = false)
        }
    }
    suspend fun satellite(): String? = commande("SA;")
    /** The S-meter on Icom's scale (see [Ts2000.versIcom]), null when not read. */
    suspend fun smetre(): Int? = Ts2000.smetre(commande("SM0;"))?.let { Ts2000.versIcom(it) }
    suspend fun emission(): Boolean? = Ts2000.emission(commande("IF;"))
}

/**
 * One side of the TS-2000 in SAT mode: the downlink on VFO A, the uplink on
 * VFO B, the rig in full duplex. Each write is read back.
 */
class Ts2000Cote(val lien: Ts2000Lien, val descente: Boolean) : PosteSimple {
    private val vfo = if (descente) "A" else "B"
    override val isOpen: Boolean get() = lien.isOpen
    override var pacingMs: Long
        get() = lien.pacingMs
        set(v) { lien.pacingMs = v }
    override fun attach(l: SerialLink) = lien.attach(l)
    override suspend fun open(cle: String?, baud: Int): Boolean = lien.open(cle, baud)
    override fun close() = lien.close()

    override suspend fun setFrequency(hz: Long): Boolean {
        if (hz <= 0) return false
        if (lien.commande(Ts2000.ecrit(vfo, hz)) == "?;") return false
        return Ts2000.frequence(lien.commande("F$vfo;"), vfo) == hz
    }

    override suspend fun readFrequency(): Long? = Ts2000.frequence(lien.commande("F$vfo;"), vfo)

    /** This side's mode: the downlink directly, the uplink with control toggled onto it. */
    override suspend fun setMode(mode: String): Boolean {
        val n = Ts2000.codeMode(mode) ?: return false
        suspend fun regle(): Boolean {
            if (lien.commande("MD;") == "MD$n;") return true
            lien.commande("MD$n;")
            return lien.commande("MD;") == "MD$n;"
        }
        return if (descente) regle() else lien.surLaMontee { regle() }
    }

    /** The uplink's tone (TO / TN), with control on the uplink. *The tone itself not yet heard on the air.* */
    override suspend fun setCtcss(tenthHz: Int): Boolean {
        if (descente) return true
        return lien.surLaMontee {
            if (tenthHz <= 0) lien.commande("TO0;") != "?;"
            else lien.commande("TN%02d;".format(Ts2000.numeroTon(tenthHz))) != "?;" && lien.commande("TO1;") != "?;"
        }
    }

    override suspend fun isTransmitting(): Boolean? = lien.emission()
}

/**
 * An in-memory TS-2000 in SAT mode, answering as the author's did: FA the
 * downlink, FB the uplink, sets silent, unknown commands `?;`. Counts any
 * command that would key the transmitter: a test checks it stays at zero.
 */
class Ts2000Sim(var sat: Boolean = true) : SerialLink {
    var fa = 435_659_600L
    var fb = 145_950_640L
    /** The downlink's mode; [modeMontee] the uplink's (as on the author's rig: USB down, LSB up). */
    var mode = 2
    var modeMontee = 1
    var smetre = 7
    var tonOn = false
    var ton = 1
    /** Control on the uplink (toggled by DC01 in SAT mode). */
    var controleMontee = false
    var emet = false
    /** Commands that would key the transmitter (never expected). */
    var tentativesEmission = 0
    /** Every setting accepted, in order. */
    val ecritures = ArrayList<String>()
    /** Commands answered `?;` (unknown, or refused in SAT mode). */
    var refus = 0
    private val sortie = java.util.concurrent.LinkedBlockingQueue<Byte>()
    private val cmd = StringBuilder()

    override fun write(bytes: ByteArray, timeoutMs: Int): Boolean {
        for (b in bytes) {
            val c = (b.toInt() and 0xFF).toChar()
            if (c == ';') { repond(cmd.toString()); cmd.setLength(0) } else cmd.append(c)
        }
        return true
    }

    private fun dit(s: String) = s.forEach { sortie.put(it.code.toByte()) }

    private fun repond(c: String) {
        when {
            c.startsWith("TX") || c.startsWith("KY") -> { tentativesEmission++; dit("?;") }
            c == "ID" -> dit(Ts2000.ID)
            c == "SA" -> dit("SA${if (sat) 1 else 0}01${if (controleMontee) 1 else 0}110RS44;")
            c == "DC" -> dit("DC00;")
            c == "DC01" -> { if (sat) controleMontee = !controleMontee; ecritures += "$c;" }
            c == "DC00" -> ecritures += "$c;"
            c == "FA" -> dit("FA%011d;".format(fa))
            c == "FB" -> dit("FB%011d;".format(fb))
            c.matches(Regex("FA\\d{11}")) -> { fa = c.substring(2).toLong(); ecritures += "$c;" }
            c.matches(Regex("FB\\d{11}")) -> { fb = c.substring(2).toLong(); ecritures += "$c;" }
            c == "FR" || c == "FT" -> if (sat) { refus++; dit("?;") } else dit("${c}0;")
            c == "MD" -> dit("MD${if (controleMontee) modeMontee else mode};")
            c.matches(Regex("MD[1-9]")) -> { if (controleMontee) modeMontee = c[2] - '0' else mode = c[2] - '0'; ecritures += "$c;" }
            c == "SM0" -> dit("SM0%04d;".format(smetre))
            c == "IF" -> dit("IF%011d    -0001000000%d%d2000010;".format(fa, if (emet) 1 else 0, mode))
            c == "TO" -> dit("TO${if (tonOn) 1 else 0};")
            c.matches(Regex("TO[01]")) -> { if (controleMontee) tonOn = c[2] == '1' else refus++; ecritures += "$c;" }
            c == "TN" -> dit("TN%02d;".format(ton))
            c.matches(Regex("TN\\d{2}")) -> { if (controleMontee) ton = c.substring(2).toInt() else refus++; ecritures += "$c;" }
            else -> { refus++; dit("?;") }
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
