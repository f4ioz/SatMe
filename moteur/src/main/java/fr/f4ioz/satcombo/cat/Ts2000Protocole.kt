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

    /**
     * The rig's refusals: `?;` (unknown, or not now — FR in SAT mode), `E;`
     * (a communication error), `O;` (its buffer overflowed). An answer — the
     * rig hears — but never a success.
     */
    fun refuse(reponse: String?): Boolean = reponse?.trim() in REFUS
    val REFUS = setOf("?;", "E;", "O;")

    /** A command that keys the transmitter: never sent, refused by [Ts2000Lien]. */
    fun emet(c: String): Boolean = c.startsWith("TX") || c.startsWith("KY")
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
