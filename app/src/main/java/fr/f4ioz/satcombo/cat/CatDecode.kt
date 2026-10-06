/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.cat

import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.i18n.tf

/**
 * The two CAT dialects as pure functions, so framing can be unit-tested with
 * hand-written bytes instead of a radio.
 *
 * **CI-V pitfall.** It is a single-wire bus: what you write comes back to you.
 * Many Icoms also have "CI-V USB Echo Back", which deliberately returns the
 * question before the answer. Searching for a 0x03 byte in the input found our
 * own command and read five bytes of nothing after it — we were reading back
 * our own frequency. Split into frames, keep only rig-to-controller frames,
 * and only then read the payload.
 */
object CatDecode {

    const val PREAMBLE = 0xFE
    const val END = 0xFD
    const val ACK = 0xFB
    const val NAK = 0xFA

    // ---------------------------------------------------------------- CI-V

    /**
     * Splits a buffer into complete CI-V frames: `FE FE … FD`.
     *
     * Stray bytes before a preamble or after the last end byte are dropped
     * silently — on a shared bus there are always some.
     */
    fun splitCiv(buf: ByteArray, n: Int = buf.size): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var i = 0
        val end = n.coerceAtMost(buf.size)
        while (i < end) {
            // Preamble is two 0xFE; some rigs send more, swallow them all.
            if ((buf[i].toInt() and 0xFF) != PREAMBLE) { i++; continue }
            var j = i
            while (j < end && (buf[j].toInt() and 0xFF) == PREAMBLE) j++
            if (j - i < 2) { i = j; continue }
            val start = j - 2
            var k = j
            while (k < end && (buf[k].toInt() and 0xFF) != END) k++
            if (k >= end) break            // truncated frame: leave it
            out += buf.copyOfRange(start, k + 1)
            i = k + 1
        }
        return out
    }

    /** Does this frame go from rig [radioAddr] to controller [ctrlAddr]? */
    fun isFromRadio(f: ByteArray, radioAddr: Int, ctrlAddr: Int): Boolean =
        f.size >= 6 &&
            (f[2].toInt() and 0xFF) == ctrlAddr &&
            (f[3].toInt() and 0xFF) == radioAddr

    /** Is this frame our own question, echoed back by the bus? */
    fun isEcho(f: ByteArray, radioAddr: Int, ctrlAddr: Int): Boolean =
        f.size >= 6 &&
            (f[2].toInt() and 0xFF) == radioAddr &&
            (f[3].toInt() and 0xFF) == ctrlAddr

    /** Command code of a frame, or -1 if too short. */
    fun command(f: ByteArray): Int = if (f.size >= 6) f[4].toInt() and 0xFF else -1

    /**
     * Payload of the rig's first reply to command [cmd].
     *
     * @param sub expected sub-command, or -1. When given, it is checked and stripped.
     * @return bytes between the command and the final 0xFD, or null if no frame
     *   matches — an ACK or NAK never does.
     */
    fun payload(
        frames: List<ByteArray>, radioAddr: Int, ctrlAddr: Int, cmd: Int, sub: Int = -1
    ): ByteArray? {
        for (f in frames) {
            if (!isFromRadio(f, radioAddr, ctrlAddr)) continue
            if (command(f) != cmd) continue
            var start = 5
            if (sub >= 0) {
                if (f.size < 7 || (f[5].toInt() and 0xFF) != sub) continue
                start = 6
            }
            if (f.size < start + 1) continue
            return f.copyOfRange(start, f.size - 1)
        }
        return null
    }

    /** A meter level (CI-V 0x15 0x02 S-meter…): two big-endian BCD bytes, 0000 to 0255. */
    fun niveauMetre(p: ByteArray?): Int? {
        if (p == null || p.size < 2) return null
        val d = IntArray(4) { i -> ((p[i / 2].toInt() and 0xFF) shr (if (i % 2 == 0) 4 else 0)) and 0x0F }
        if (d.any { it > 9 }) return null
        return (d[0] * 1000 + d[1] * 100 + d[2] * 10 + d[3]).takeIf { it in 0..255 }
    }

    /** Did the rig acknowledge (0xFB)? */
    fun isAck(frames: List<ByteArray>, radioAddr: Int, ctrlAddr: Int): Boolean =
        frames.any { isFromRadio(it, radioAddr, ctrlAddr) && command(it) == ACK }

    /** Did the rig refuse (0xFA)? */
    fun isNak(frames: List<ByteArray>, radioAddr: Int, ctrlAddr: Int): Boolean =
        frames.any { isFromRadio(it, radioAddr, ctrlAddr) && command(it) == NAK }

    // ------------------------------------------------------- frequency BCD

    /** Frequency in Hz as five little-endian BCD bytes (10 digits). */
    fun freqToBcdLe(hz: Long): ByteArray {
        val out = ByteArray(5)
        var d = hz
        for (i in 0 until 5) {
            val lo = (d % 10).toInt(); d /= 10
            val hi = (d % 10).toInt(); d /= 10
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    /**
     * The reverse. Null if not valid BCD or implausible: an absurd value means
     * we read at the wrong offset, and saying so beats displaying a number.
     */
    fun bcdLeToFreq(buf: ByteArray, start: Int = 0): Long? {
        if (start + 5 > buf.size) return null
        var hz = 0L
        var mult = 1L
        for (i in 0 until 5) {
            val b = buf[start + i].toInt() and 0xFF
            val lo = b and 0x0F
            val hi = (b shr 4) and 0x0F
            if (lo > 9 || hi > 9) return null
            hz += lo * mult; mult *= 10
            hz += hi * mult; mult *= 10
        }
        return if (hz in PLAUSIBLE_HZ) hz else null
    }

    /** Plausibility bounds for a frequency read back. */
    val PLAUSIBLE_HZ = 100_000L..30_000_000_000L

    // ------------------------------------------------------------- CTCSS

    /** Lowest tone an Icom accepts, in tenths of Hz. */
    const val TONE_MIN_TENTH = 670

    /** Highest. */
    const val TONE_MAX_TENTH = 2541

    /**
     * CTCSS tone as three **big-endian** BCD bytes: 88.5 Hz gives `00 08 85`.
     *
     * Pitfall that cost whole SO-50 passes: frequency is little-endian, and the
     * old code applied the same rule to the tone. 88.5 went out as `00 88 50`,
     * read by the rig as 885.0 Hz — out of range, ignored, yet ACKed with 0xFB,
     * so the app showed "tone set" while the repeater stayed silent. An ACK
     * means received, not understood.
     */
    fun toneToBcdBe(tenthHz: Int): ByteArray {
        val v = tenthHz.coerceIn(0, 9999)
        val s = "%04d".format(v)
        fun d(i: Int) = s[i] - '0'
        return byteArrayOf(
            0x00,
            ((d(0) shl 4) or d(1)).toByte(),
            ((d(2) shl 4) or d(3)).toByte()
        )
    }

    /** The reverse, in tenths of Hz. Null if not BCD. */
    fun bcdBeToTone(buf: ByteArray, start: Int = 0): Int? {
        if (start + 3 > buf.size) return null
        var v = 0
        for (i in start until start + 3) {
            val b = buf[i].toInt() and 0xFF
            val hi = (b shr 4) and 0x0F
            val lo = b and 0x0F
            if (hi > 9 || lo > 9) return null
            v = v * 100 + hi * 10 + lo
        }
        return v
    }

    /** Is this tone within what a rig really accepts? */
    fun toneInRange(tenthHz: Int): Boolean = tenthHz in TONE_MIN_TENTH..TONE_MAX_TENTH

    // ------------------------------------------------------------- Yaesu

    /** The four BCD bytes of an FT-817 frequency, in 10 Hz steps. */
    fun yaesuFreq(hz: Long): ByteArray {
        val tenHz = (hz / 10).coerceIn(0, 99_999_999)
        val s = "%08d".format(tenHz)
        fun b(i: Int) = (((s[i] - '0') shl 4) or (s[i + 1] - '0')).toByte()
        return byteArrayOf(b(0), b(2), b(4), b(6))
    }

    /** The reverse, from the first four bytes of a reply. */
    fun yaesuFreqOf(buf: ByteArray, start: Int = 0): Long? {
        if (start + 4 > buf.size) return null
        var hz = 0L
        for (i in start until start + 4) {
            val b = buf[i].toInt() and 0xFF
            val hi = (b shr 4) and 0x0F
            val lo = b and 0x0F
            if (hi > 9 || lo > 9) return null
            hz = hz * 100 + hi * 10 + lo
        }
        return hz * 10
    }

    // ------------------------------------------------- human-readable text

    private fun mhz(hz: Long): String = "%.5f MHz".format(java.util.Locale.US, hz / 1e6)

    /**
     * Mode name as printed on the rig's front panel.
     *
     * Public: the rig panel shows it. The wrong sideband means hearing yourself
     * inverted or not at all — invisible, since everything else looks right.
     */
    fun civModeName(b: Int): String = when (b) {
        0x00 -> "LSB"; 0x01 -> "USB"; 0x02 -> "AM"; 0x03 -> "CW"
        0x05 -> "FM"; 0x07 -> "CW-R"; else -> "mode %02X".format(b)
    }

    /**
     * A CI-V frame described in the app language, for the log. Not exhaustive: covers
     * the handful of commands the app actually sends.
     */
    fun describeCiv(f: ByteArray): String {
        if (f.size < 6) return t("catj_incomplete")
        val cmd = command(f)
        val d = f.copyOfRange(5, f.size - 1)
        fun sub(i: Int) = if (d.size > i) d[i].toInt() and 0xFF else -1
        return when (cmd) {
            ACK -> t("catj_ack")
            NAK -> t("catj_nak")
            0x03 -> bcdLeToFreq(d)?.let { tf("catj_freq_is", mhz(it)) } ?: t("catj_read_freq")
            0x04 -> t("catj_read_mode")
            0x05 -> bcdLeToFreq(d)?.let { tf("catj_freq_set", mhz(it)) } ?: t("catj_set_freq")
            0x06 -> if (d.isEmpty()) t("catj_set_mode") else tf("catj_mode_set", civModeName(sub(0)))
            0x07 -> when (sub(0)) {
                0x00 -> "VFO A"
                0x01 -> "VFO B"
                0xD0 -> t("catj_main_band")
                0xD1 -> t("catj_sub_band")
                else -> t("catj_vfo_choice")
            }
            0x0F -> when (sub(0)) {
                0x00 -> t("catj_split_off"); 0x01 -> t("catj_split_on"); else -> t("catj_split_state")
            }
            0x16 -> when (sub(0)) {
                0x5A -> if (sub(1) == 1) t("catj_sat_on") else t("catj_sat_off")
                0x42 -> if (sub(1) == 1) t("catj_tone_on") else t("catj_tone_off")
                else -> tf("catj_setting", "%02X".format(sub(0)))
            }
            0x1B -> if (d.size >= 4) {
                val ton = bcdBeToTone(d, 1)
                if (ton != null) tf("catj_tone_set", "%.1f".format(java.util.Locale.US, ton / 10.0)) else t("catj_tone")
            } else t("catj_read_tone")
            0x25 -> {
                val which = if (sub(0) == 0x01) t("catj_vfo_unsel") else t("catj_vfo_sel")
                val fq = if (d.size >= 6) bcdLeToFreq(d, 1) else null
                if (fq != null) "$which ← ${mhz(fq)}" else tf("catj_vfo_freq", which)
            }
            0x26 -> {
                val which = if (sub(0) == 0x01) t("catj_vfo_unsel") else t("catj_vfo_sel")
                if (d.size >= 2) "$which ← ${civModeName(sub(1))}" else tf("catj_vfo_mode", which)
            }
            else -> tf("catj_command", "%02X".format(cmd))
        }
    }

    private fun yaesuMode(b: Int): String = when (b) {
        0x00 -> "LSB"; 0x01 -> "USB"; 0x02 -> "CW"; 0x03 -> "CW-R"
        0x04 -> "AM"; 0x08 -> "FM"; 0x0A -> "DIG"; 0x0C -> "PKT"
        else -> "mode %02X".format(b)
    }

    /**
     * A Yaesu frame described in the app language. FT-817 CAT has no address or
     * delimiter, and reply length depends on the question: [fromRig] gives the
     * direction, [lastOp] the question when describing a reply.
     */
    fun describeYaesu(f: ByteArray, fromRig: Boolean, lastOp: Int = -1): String {
        if (!fromRig) {
            if (f.size < 5) return t("catj_incomplete")
            return when (f[4].toInt() and 0xFF) {
                0x00 -> t("catj_ptt_closed")
                0x01 -> yaesuFreqOf(f)?.let { tf("catj_freq_set", mhz(it)) } ?: t("catj_set_freq")
                0x03 -> t("catj_read_freq_mode")
                0x07 -> tf("catj_mode_set", yaesuMode(f[0].toInt() and 0xFF))
                0x0A -> when (f[0].toInt() and 0xFF) {
                    0x8A -> t("catj_tone_off")
                    0x4A -> t("catj_tone_tx")
                    0x2A -> t("catj_tone_txrx")
                    else -> t("catj_set_tone")
                }
                0x0B -> {
                    val ton = ((f[0].toInt() shr 4 and 0x0F) * 1000 + (f[0].toInt() and 0x0F) * 100 +
                        (f[1].toInt() shr 4 and 0x0F) * 10 + (f[1].toInt() and 0x0F))
                    tf("catj_tone_set", "%.1f".format(java.util.Locale.US, ton / 10.0))
                }
                0x81 -> t("catj_ptt_open")
                0xF7 -> t("catj_read_tx")
                else -> tf("catj_command", "%02X".format(f[4].toInt() and 0xFF))
            }
        }
        return when {
            lastOp == 0xF7 && f.size >= 1 ->
                if ((f[0].toInt() and 0x80) == 0) t("catj_rig_tx") else t("catj_rig_rx")
            f.size >= 5 -> {
                val fq = yaesuFreqOf(f)
                if (fq != null) tf("catj_freq_mode", mhz(fq), yaesuMode(f[4].toInt() and 0xFF))
                else t("catj_reply5")
            }
            f.size == 1 -> if (f[0].toInt() and 0xFF == 0x00) t("catj_ack") else
                t("catj_reply1")
            else -> tf("catj_reply_n", f.size)
        }
    }
}
