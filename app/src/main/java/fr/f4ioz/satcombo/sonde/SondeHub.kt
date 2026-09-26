/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sonde

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Radiosonde decoding, fed from the SDR chain output.
 *
 * As for SSTV, the dongle read loop calls [feedLive] with demodulated audio and
 * the work happens here, off the USB-critical path. But a radiosonde modulates
 * bits, not audio: we need the raw discriminator output, and de-emphasis must
 * be off, or the edges get rounded and nothing decodes.
 *
 * One decoder per family on the same stream: RS41 at 4800 baud, M20 plain
 * FSK at 9600 baud, M10 biphase at 9616 chips/s. **M20 and M10 must not share
 * a demodulator:** tuned to M10 half-bits, the M20 is read as two identical
 * symbols per bit and every frame gets discarded while unit tests still pass
 * (hence the end-to-end test). Naming the model turns off unneeded decoders
 * and narrows the SDR filter, worth a few dB on a distant sonde.
 */
object SondeHub {

    data class SondeState(
        /** Decoding is running. */
        val running: Boolean = false,
        /** Frames retained since start. */
        val frames: Int = 0,
        /** Rejected frames: helps judge whether tuning is right. */
        val rejected: Int = 0,
        val last: SondeFrame? = null,
        /** Serial and type of the tracked sonde. */
        val serial: String = "",
        val type: String = "",
        /** Log file being written. */
        val logFile: String? = null,
        /** Discriminator signal swing, 0 to 100. */
        val swing: Int = 0,
        /** Audio source: "SDR", "MIC" or "USB". */
        val source: String = "SDR",
        /** Selected model, or "AUTO" to search for all. */
        val model: String = SondeModel.AUTO,
        /** True when the sound card is too slow for the chosen model. */
        val marginal: Boolean = false
    )

    private val _state = MutableStateFlow(SondeState())
    val state: StateFlow<SondeState> = _state

    /** Checked on every block, hence volatile. */
    @Volatile
    var active: Boolean = false
        private set

    private var rs41: SondeDemod? = null
    private var m10: SondeDemod? = null
    private var m20: SondeDemod? = null
    private var m10Frame = ByteArray(Meteomodem.M10_LEN)
    private var model: String = SondeModel.AUTO
    private var freqHz = 0L
    private var flight: SondeFlight? = null
    private var logFile: File? = null
    private var frames = 0
    private var rejected = 0
    private var lastUiMs = 0L

    val currentFlight: SondeFlight? get() = flight

    /**
     * Arms decoding. [sampleRate] is the SDR chain output rate (44 100 Hz).
     * [freq] is copied into every frame so the log still says, months later,
     * what the sonde was transmitting on.
     */
    fun start(ctx: Context?, sampleRate: Int, freq: Long, source: String = "SDR",
              model: String = SondeModel.AUTO, log: Boolean = true) {
        freqHz = freq
        this.model = model
        val r = sampleRate.toDouble()
        rs41 = if (SondeModel.wantsRs41(model)) SondeDemod(r, Rs41.BAUD, 2048) else null
        // M20: two-level FSK, one symbol per bit.
        m20 = if (SondeModel.wantsM20(model))
            SondeDemod(r, Meteomodem.M20_BAUD, 2048) else null
        // M10: biphase mark, so the demodulator counts chips (9616/s, not the
        // 4808 bit/s payload, and not 19232, which made M10 undecodable).
        m10 = if (SondeModel.wantsM10(model))
            SondeDemod(r, Meteomodem.M10_CHIP_RATE, 2048) else null
        frames = 0
        rejected = 0
        flight = null
        // The context is only needed for the log, so demo and tests can run
        // the whole chain outside Android.
        logFile = if (log && ctx != null) runCatching { openLog(ctx) }.getOrNull() else null
        active = true
        _state.value = SondeState(running = true, logFile = logFile?.name, source = source,
            model = model, marginal = SondeModel.byId(model).marginal(sampleRate))
    }

    /** Disarms decoding. */
    fun stop() {
        active = false
        rs41 = null
        m10 = null
        m20 = null
        _state.value = _state.value.copy(running = false)
    }

    /** Clears the tracked flight without stopping decoding. */
    fun clearFlight() {
        flight = null
        frames = 0
        rejected = 0
        _state.value = _state.value.copy(frames = 0, rejected = 0, last = null,
            serial = "", type = "")
    }

    /**
     * Consumes a block of demodulated audio. Called from the dongle read loop:
     * anything done here delays USB reads, hence bounded buffers and no
     * blocking writes apart from the log.
     */
    fun feedLive(pcm: ShortArray, count: Int) {
        if (!active || count <= 0) return
        val now = System.currentTimeMillis()

        // ------------------------------------------------------------ RS41
        rs41?.let { a ->
            a.feedBits(pcm, count, null)
            if (a.bitsAvailable >= Rs41.LEN_STD * 8 + 64) {
                var found = false
                // Byte alignment is unknown: try all eight bit offsets. Cheap,
                // since the header search fails immediately on seven of them.
                for (off in 0 until 8) {
                    val n = a.packBytes(off)
                    val hit = Rs41.scan(a.bytes, 0, n, freqHz, now) ?: continue
                    accept(hit.frame)
                    found = true
                    break
                }
                if (!found) rejected++
                a.trimTo(Rs41.LEN_STD * 8)
            }
        }

        // -------------------------------------------------------------- M20
        // One symbol per bit, MSB first: read the frame straight from symbols.
        m20?.let { d ->
            d.feedBits(pcm, count, null)
            if (d.bitsAvailable >= Meteomodem.M20_LEN * 8 + 128) {
                val chips = d.chipsCopy()
                for (off in 0 until 8) {
                    val n = d.packBytesMsb(chips, chips.size, off)
                    val hit = Meteomodem.scanM20(d.bytes, 0, n, freqHz, now) ?: continue
                    accept(hit.frame)
                    break
                }
                d.trimTo(Meteomodem.M20_LEN * 8)
            }
        }

        // -------------------------------------------------------------- M10
        // Chip level: the sync pattern gives the exact start, the checksum
        // validates. Every pattern occurrence is tried, since a buffer can
        // hold several bursts.
        m10?.let { b ->
            b.feedBits(pcm, count, null)
            val need = Meteomodem.M10_LEN * 16 + Meteomodem.M10_SYNC.size
            if (b.bitsAvailable >= need + 64) {
                val chips = b.chipsCopy()
                var at = 0
                var got = false
                while (at < chips.size) {
                    val k = Meteomodem.findSync(chips, chips.size, at)
                    if (k < 0) break
                    if (Meteomodem.frameFromChips(chips, k, m10Frame)) {
                        val f = Meteomodem.parseM10(m10Frame, freqHz, now)
                        if (f != null) { accept(f); got = true; break }
                    }
                    at = k + 1
                }
                if (!got) rejected++
                b.trimTo(need)
            }
        }

        if (now - lastUiMs >= 300L) {
            lastUiMs = now
            val any = rs41 ?: m20 ?: m10
            val sw = ((any?.swing(pcm, count) ?: 0) / 328).coerceIn(0, 100)
            _state.value = _state.value.copy(frames = frames, rejected = rejected, swing = sw)
        }
    }

    private fun accept(f: SondeFrame) {
        val fl = flight ?: SondeFlight(
            if (f.serial.isNotBlank()) f.serial else "?", f.type).also { flight = it }
        // A new serial means another sonde: start a new flight rather than mix
        // two tracks.
        if (f.serial.isNotBlank() && fl.serial != "?" && fl.serial != f.serial) {
            flight = SondeFlight(f.serial, f.type)
        }
        val target = flight!!
        if (!target.add(f)) { rejected++; return }
        frames++
        runCatching { appendLog(f) }
        _state.value = _state.value.copy(
            frames = frames, last = f, serial = target.serial, type = target.type)
    }

    // ---------------------------------------------------------------- log

    /** Sonde log directory, created if needed. */
    fun logDir(ctx: Context): File =
        File(ctx.filesDir, "sondes").also { if (!it.exists()) it.mkdirs() }

    private fun openLog(ctx: Context): File {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
            .format(java.util.Date())
        val f = File(logDir(ctx), "sonde-$stamp.csv")
        if (!f.exists()) f.writeText(SondeExport.csvHeader())
        return f
    }

    private fun appendLog(f: SondeFrame) {
        val file = logFile ?: return
        file.appendText(SondeExport.csvLine(f))
    }

    /** Saved logs, newest first. */
    fun logs(ctx: Context): List<File> =
        logDir(ctx).listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    /** Writes the current flight track in the requested format, returns the file. */
    fun export(ctx: Context, kml: Boolean): File? {
        val fl = flight ?: return null
        if (fl.count == 0) return null
        val ext = if (kml) "kml" else "gpx"
        val name = (fl.serial.ifBlank { "sonde" }) + "." + ext
        val out = File(logDir(ctx), name)
        out.writeText(if (kml) SondeExport.kml(fl) else SondeExport.gpx(fl))
        return out
    }
}
