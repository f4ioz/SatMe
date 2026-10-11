/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.rotor

import fr.f4ioz.satcombo.cat.CatJournal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.Writer
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale

/**
 * The dialect of `rotctld`, the Hamlib rotator daemon.
 *
 * No serial cable here: a TCP socket to a small server (often a Raspberry Pi
 * at the foot of the antenna) that talks to the controller. Hamlib knows far
 * more rotators than we ever will, and the phone needs no physical link.
 *
 * Text protocol, one command per line: `P 180.00 45.00` to aim, `p` to read,
 * `S` to stop; the server answers `RPRT 0` on success. Three traps, all hit:
 *
 * **Decimal point.** Hamlib parses numbers in C locale. `String.format`
 * without a [Locale] writes `180,00` on a French phone and the server answers
 * `RPRT -1` with no explanation. Everything here formats in [Locale.US].
 *
 * **`RPRT -1` instead of two position lines.** When the controller does not
 * answer, `p` returns a single error line. A reader that blindly expects two
 * lines eats the reply of the **next** command, and everything stays shifted
 * by one, forever.
 *
 * **Extended mode echo.** With extended replies, each answer is preceded by
 * the command echo (`get_pos:`), fields are named (`Azimuth: 180.000000`),
 * and it ends with `RPRT 0`. That final `RPRT 0` does not exist in simple
 * mode; forgetting it leaves an extra line in the pipe and the same shift.
 */
object RotctldCodec {

    /** Position query. */
    const val QUERY = "p\n"

    /** Immediate stop. */
    const val STOP = "S\n"

    /** The target, always with a decimal point whatever the locale. */
    fun moveCommand(azDeg: Double, elDeg: Double): String? {
        if (azDeg.isNaN() || elDeg.isNaN() || azDeg.isInfinite() || elDeg.isInfinite()) return null
        return String.format(Locale.US, "P %.2f %.2f\n", azDeg, elDeg)
    }
}

/**
 * The `rotctld` client over TCP.
 *
 * No Android dependency: tested against a thirty-line fake Hamlib server on a
 * system-chosen port.
 */
class RotctldRotor : RotorDriver {

    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: Writer? = null

    override val isOpen: Boolean get() = socket?.isConnected == true && socket?.isClosed == false

    /** Opens the socket to [host]:[port]. */
    suspend fun open(host: String, port: Int, timeoutMs: Int = 2000): Boolean =
        withContext(Dispatchers.IO) {
            close()
            runCatching {
                val s = Socket()
                s.connect(InetSocketAddress(host, port), timeoutMs)
                s.soTimeout = 1500
                socket = s
                reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.US_ASCII))
                writer = OutputStreamWriter(s.getOutputStream(), Charsets.US_ASCII)
                true
            }.getOrDefault(false)
        }

    override fun close() {
        runCatching { writer?.close() }
        runCatching { reader?.close() }
        runCatching { socket?.close() }
        writer = null; reader = null; socket = null
    }

    /**
     * Discards leftovers before asking a question.
     *
     * Safety net against the shift: a stray line from a previous reply (an
     * extended-mode `RPRT 0`, an unexpected error) goes here, not into the
     * next read.
     */
    private fun drain(r: BufferedReader) {
        var guard = 0
        runCatching { while (r.ready() && guard++ < 32) { if (r.readLine() == null) break } }
    }

    private fun send(s: String): Boolean {
        val w = writer ?: return false
        val r = reader ?: return false
        drain(r)
        return runCatching {
            w.write(s); w.flush()
            CatJournal.log(true, s.toByteArray(Charsets.US_ASCII), "rotctld → " + s.trim())
            true
        }.getOrDefault(false)
    }

    /** Waits for the report of a write command. True on `RPRT 0`. */
    private fun readRprt(): Boolean {
        val r = reader ?: return false
        var guard = 0
        while (guard++ < 8) {
            val line = runCatching { r.readLine() }.getOrNull() ?: return false
            val t = line.trim()
            if (t.isEmpty()) continue
            CatJournal.log(false, t.toByteArray(Charsets.US_ASCII), "rotctld ← $t")
            if (t.startsWith("RPRT", ignoreCase = true))
                return t.substring(4).trim().toIntOrNull() == 0
        }
        return false
    }

    /**
     * Reads a position, in simple or extended mode.
     *
     * The mode is detected on the fly: a line ending with a colon and nothing
     * after it is the command echo, so extended mode, so a final `RPRT` must be
     * consumed. In simple mode two numbers are enough.
     */
    private fun readPositionReply(): RotorPos? {
        val r = reader ?: return null
        val vals = ArrayList<Double>()
        var extended = false
        var guard = 0
        while (guard++ < 10) {
            val line = runCatching { r.readLine() }.getOrNull() ?: return null
            val t = line.trim()
            if (t.isEmpty()) continue
            CatJournal.log(false, t.toByteArray(Charsets.US_ASCII), "rotctld ← $t")
            if (t.startsWith("RPRT", ignoreCase = true)) {
                val code = t.substring(4).trim().toIntOrNull()
                return if (code == 0 && vals.size >= 2) RotorPos(vals[0], vals[1]) else null
            }
            val body = if (t.contains(':')) t.substringAfter(':').trim() else t
            if (body.isEmpty()) { extended = true; continue }
            val d = body.toDoubleOrNull() ?: continue
            vals += d
            if (vals.size == 2 && !extended) return RotorPos(vals[0], vals[1])
        }
        return null
    }

    override suspend fun moveTo(azDeg: Double, elDeg: Double): Boolean =
        withContext(Dispatchers.IO) {
            if (!isOpen) return@withContext false
            val cmd = RotctldCodec.moveCommand(azDeg, elDeg) ?: return@withContext false
            if (!send(cmd)) return@withContext false
            readRprt()
        }

    override suspend fun readPosition(): RotorPos? = withContext(Dispatchers.IO) {
        if (!isOpen) return@withContext null
        if (!send(RotctldCodec.QUERY)) return@withContext null
        readPositionReply()
    }

    override suspend fun stop(): Boolean = withContext(Dispatchers.IO) {
        if (!isOpen) return@withContext false
        if (!send(RotctldCodec.STOP)) return@withContext false
        readRprt()
    }
}
