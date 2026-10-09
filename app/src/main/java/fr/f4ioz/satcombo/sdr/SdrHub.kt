/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sdr

// The "PLL or software" decision lives in the domain, Android-free, so a test
// can replay it second by second.
import fr.f4ioz.satcombo.domain.DopplerTuner
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.naman14.androidlame.LameBuilder
import fr.f4ioz.satcombo.audio.EncodeurMp3
import fr.f4ioz.satcombo.sonde.SondeHub
import fr.f4ioz.satcombo.sstv.SstvHub
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.concurrent.thread

/**
 * Where the RTL-SDR dongle meets the rest of SatMe.
 *
 * A singleton, like [SstvHub], because there is only ever one receiver. The
 * USB reader thread lives here: it demodulates, plays audio (speaker, or
 * headset if plugged in), feeds the SSTV engine and writes an MP3 next to the
 * pass recordings, so a dongle recording can be re-decoded later exactly like
 * a microphone one.
 *
 * Tuning follows Doppler: the ViewModel calls [setCenter] once a second; the
 * reader absorbs it in the software mixer and only retunes the PLL, between
 * blocks, when it must.
 */
object SdrHub {

    data class SdrState(
        /** A known dongle is connected (and permission granted). */
        val connected: Boolean = false,
        val deviceName: String? = null,
        /** Reader thread running. */
        val running: Boolean = false,
        /** Rest frequency, without Doppler. */
        val restHz: Long = 145_800_000L,
        /** Frequency actually set on the dongle (rest + Doppler). */
        val centerHz: Long = 145_800_000L,
        /** Applied Doppler correction, Hz. */
        val dopplerHz: Long = 0L,
        /** Actual dongle sample rate. */
        val sampleRate: Double = 0.0,
        /** Signal level in dBFS, refreshed a few times a second. */
        val levelDb: Float = -120f,
        /** Manual gain in tenths of dB, null = auto. */
        val gainTenthDb: Int? = null,
        val mode: RxMode = RxMode.NFM,
        /** Channel width, Hz. Zero = mode default. */
        val bandwidthHz: Int = 0,
        /** Squelch threshold, dBFS. -120 = off. */
        val squelchDb: Int = -120,
        /** Software fine tuning relative to the dongle frequency, Hz. */
        val offsetHz: Int = 0,
        /** Continuous auto re-centring on. */
        val autoTune: Boolean = false,
        /**
         * Time of the last successful auto re-centring (system ms), 0 if none.
         * Lets the UI say "re-centred N s ago" instead of leaving the operator
         * wondering whether the button did anything.
         */
        val tunedAtMs: Long = 0L,
        /** Spectrum span, Hz. */
        val spanHz: Double = 0.0,
        /** Audio output on (speaker, or headset if plugged in). */
        val audio: Boolean = true,
        val recording: Boolean = false,
        val recordFile: String? = null,
        val sstv: Boolean = false,
        val satName: String? = null,
        /** MB read since start, to see that it's alive. */
        val mbRead: Float = 0f,
        /** Last error, cleared on next start. */
        val error: String? = null,
        /** USB permission requested, awaiting answer. */
        val awaitingPermission: Boolean = false,
        /**
         * Dongle front end saturated. Misleading symptom: noise disappears, the
         * screen shows a strong signal, and nothing comes out. The fix is not
         * in the app: lower gain, antenna further from the transmitter, or an
         * attenuator.
         */
        val clipping: Boolean = false,
        /** Output audio peak, 0 to 1. VU meter of received modulation. */
        val afLevel: Float = 0f,
        /**
         * Doppler share absorbed in software, Hz, without touching the PLL.
         * Explains why the displayed frequency moves while the tuner doesn't.
         */
        val dopplerFineHz: Long = 0L,
        /** PLL writes since start. A well-run pass has one: the initial one. */
        val pllWrites: Int = 0
    )

    private val _state = MutableStateFlow(SdrState())
    val state: StateFlow<SdrState> = _state

    /**
     * Last spectrum frame, dBFS, lowest to highest frequency. A fresh array is
     * published per frame (~10/s): Compose can observe that, and no array is
     * read while being rewritten.
     */
    private val _spectrum = MutableStateFlow(FloatArray(0))
    val spectrum: StateFlow<FloatArray> = _spectrum

    /**
     * Panorama: same, over the full dongle bandwidth without decimation,
     * 1 058 400 Hz in 16k bins.
     *
     * Only computed on request ([wantPanorama]): a 16k FFT costs about four
     * regular ones and only one screen uses it; running it during a radiosonde
     * reception would waste battery.
     *
     * Empty until a frame is computed, and after a stop: a frozen panorama
     * would suggest the dongle is still listening.
     */
    private val _panorama = MutableStateFlow(FloatArray(0))
    val panorama: StateFlow<FloatArray> = _panorama

    /** Set by the QO-100 screen when it opens, cleared when it closes. */
    @Volatile
    private var wantPanorama = false

    /** See [wantPanorama]. No effect while the dongle isn't running. */
    fun setPanorama(on: Boolean) {
        wantPanorama = on
        if (!on) _panorama.value = FloatArray(0)
    }

    /** Panorama span, Hz. Fixed: the dongle rate. */
    val PANORAMA_SPAN_HZ: Double = Dsp.RTL_RATE.toDouble()

    /** USB block size imposed by usbfs, see [RtlSdr.XFER]. */
    private const val BLOCK = RtlSdr.XFER

    /**
     * USB transfers in flight.
     *
     * At 1 058 400 S/s a 16 KB block is 7.7 ms, so sixteen give ~125 ms of
     * slack: enough to absorb a GC pause or a waterfall render without losing
     * a sample.
     */
    private const val STREAM_DEPTH = 16

    /** Max wait for a USB block; also how long the loop takes to notice a stop, so keep it short. */
    private const val READ_MS = 250L

    /** How long [stopWorker] waits before force-closing the dongle. */
    private const val WORKER_JOIN_MS = 1500L

    private var sdr: RtlSdr? = null
    private var worker: Thread? = null
    @Volatile private var running = false
    /**
     * The reader thread hasn't released the dongle yet.
     *
     * `running` means "keep going", this means "still running". Between [stop]
     * clearing `running` and the loop actually closing the AudioTrack and the
     * dongle, a few hundred ms pass. Opening a second receiver in that window
     * gave two audio outputs and two USB connections on one dongle: choppy
     * audio on the second start.
     */
    @Volatile private var workerAlive = false
    /**
     * Reception session number. A reader thread from a stale session may no
     * longer write state or feed SSTV: it just closes quietly.
     */
    @Volatile private var generation = 0
    @Volatile private var pendingHz = 0L
    @Volatile private var wantAudio = true
    @Volatile private var wantMode = RxMode.NFM
    @Volatile private var wantBandwidth = 0
    @Volatile private var wantSquelch = -120
    @Volatile private var wantOffset = 0

    /**
     * The fine offset is the sum of two separate intents: the operator's
     * (finger on the waterfall) and Doppler tracking (moves on its own).
     * Adding them at the last moment lets tracking work without ever wiping
     * the manual tweak, which a single shared value used to do.
     */
    @Volatile private var userOffset = 0
    @Volatile private var dopplerFine = 0
    @Volatile private var pllMoveCount = 0

    /**
     * FM de-emphasis. On for voice, off for telemetry: on a radiosonde it
     * rounds the edges and nothing decodes.
     */
    @Volatile private var wantDeemph = false

    /** Pending auto-tune request on the strongest bin. */
    @Volatile private var wantPeak = false
    @Volatile private var peakFromHz = -40_000.0
    @Volatile private var peakToHz = 40_000.0

    /** Pending centroid re-centring: auto-tune for carrierless modulations, radiosondes included. */
    @Volatile private var wantCentroid = false

    /** Re-centring repeats on its own every [AUTO_TUNE_MS]. */
    @Volatile private var autoCentroid = false

    /** Search half-width, Hz. */
    @Volatile private var centroidSearchHz = 25_000.0

    /**
     * Target audio offset for the lock, Hz, and search centre.
     *
     * Zero for classic re-centring (carrier in the middle). Non-zero for SSB,
     * where the voice must fall inside the passband, not straddle zero; see
     * [AccordFin.cibleVoixHz].
     */
    @Volatile private var centroidCibleHz = 0
    @Volatile private var centroidAutourDuPoint = false

    /**
     * Interval between auto re-centrings.
     *
     * Two seconds: slow enough not to disturb a decode in progress, quick
     * enough to follow a warming crystal or the Doppler of a sonde overhead.
     * It sets an absolute value rather than adding, so repeating it on a
     * tuned signal does nothing.
     */
    private const val AUTO_TUNE_MS = 2_000L

    private var appCtx: Context? = null
    private var receiver: BroadcastReceiver? = null
    private var usbReceiver: BroadcastReceiver? = null
    private var autoStart: (() -> Unit)? = null

    // ------------------------------------------------------------ detection

    /** Is a known dongle plugged in? Says nothing about permission. */
    fun devicePresent(ctx: Context): UsbDevice? {
        val um = ctx.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return null
        return runCatching { RtlSdr.find(um) }.getOrNull()
    }

    fun deviceLabel(d: UsbDevice): String {
        val name = d.productName ?: "RTL-SDR"
        return "$name (%04x:%04x)".format(d.vendorId, d.productId)
    }

    /** Registers the USB permission and attach/detach receivers. Call once, when the SDR screen appears. */
    @Synchronized
    fun attach(ctx: Context) {
        val app = ctx.applicationContext
        appCtx = app
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                if (i?.action != RtlSdr.ACTION_USB_PERMISSION) return
                val granted = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                _state.value = _state.value.copy(awaitingPermission = false)
                if (granted) {
                    val go = autoStart
                    autoStart = null
                    go?.invoke()
                } else {
                    autoStart = null
                    _state.value = _state.value.copy(error = "usb_denied")
                }
            }
        }
        val filter = IntentFilter(RtlSdr.ACTION_USB_PERMISSION)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            app.registerReceiver(r, filter)
        }
        receiver = r

        // Detach can't be declared in the manifest: Android only delivers it to
        // runtime-registered receivers. Without it, unplugging left `running`
        // true, the play button dead and the displayed state meaningless.
        val u = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                val d = i?.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                when (i?.action) {
                    UsbManager.ACTION_USB_DEVICE_DETACHED ->
                        if (d == null || RtlSdr.isRtl(d)) onDeviceDetached()
                    UsbManager.ACTION_USB_DEVICE_ATTACHED ->
                        if (d != null && RtlSdr.isRtl(d)) onDeviceAttached(app, d)
                }
            }
        }
        val usbFilter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        }
        // Protected system broadcasts come from outside the app, hence
        // RECEIVER_EXPORTED, unlike the permission reply.
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(u, usbFilter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            app.registerReceiver(u, usbFilter)
        }
        usbReceiver = u

        refreshPresence(app)
    }

    @Synchronized
    fun detach() {
        val app = appCtx ?: return
        val r = receiver
        if (r != null) runCatching { app.unregisterReceiver(r) }
        receiver = null
        val u = usbReceiver
        if (u != null) runCatching { app.unregisterReceiver(u) }
        usbReceiver = null
    }

    /**
     * Called when the system reports a newly attached device.
     *
     * The manifest filter also lets CAT serial adapters through: only react to
     * an RTL-SDR, and open nothing; the operator decides when to start.
     */
    fun onDeviceAttached(ctx: Context, dev: UsbDevice?) {
        attach(ctx)
        if (dev != null && RtlSdr.isRtl(dev)) {
            _state.value = _state.value.copy(
                deviceName = deviceLabel(dev), error = null, awaitingPermission = false)
        }
        refreshPresence(ctx)
    }

    /**
     * The dongle was unplugged.
     *
     * Stop everything and reset state: the system revokes USB permission on
     * unplug, so a replug must go through the permission request again. A
     * stale flag left true here is what made reception misbehave after a
     * replug.
     */
    /** APRS decoding hooked to the dongle's audio for this run. */
    @Volatile private var aprsSdr = false

    @Synchronized
    fun onDeviceDetached() {
        val wasRunning = running || workerAlive
        if (wasRunning) {
            stopWorker()
            if (_state.value.sstv) runCatching { SstvHub.stopLive() }
            if (aprsSdr) { runCatching { fr.f4ioz.satcombo.aprs.AprsHub.stopLive() }; aprsSdr = false }
            _spectrum.value = FloatArray(0)
            _panorama.value = FloatArray(0)
        }
        autoStart = null
        _state.value = _state.value.copy(
            connected = false,
            deviceName = null,
            running = false,
            levelDb = -120f,
            sstv = false,
            awaitingPermission = false,
            error = if (wasRunning) "sdr_unplugged" else null)
    }

    /**
     * Physical presence only. Not "plugged in *and* receiving", which made the
     * UI think no dongle was there as soon as playback stopped.
     */
    fun refreshPresence(ctx: Context) {
        val d = devicePresent(ctx)
        _state.value = _state.value.copy(
            connected = d != null,
            deviceName = d?.let { deviceLabel(it) })
    }

    // ------------------------------------------------------------- start

    /**
     * Opens the dongle and starts reception. [restHz] is the satellite rest
     * frequency; Doppler is applied later by [setCenter].
     *
     * Returns false at once if USB permission is missing: it is requested, and
     * reception starts by itself once the operator accepts.
     */
    @Synchronized
    fun start(
        ctx: Context,
        satName: String,
        restHz: Long,
        gainTenthDb: Int? = null,
        agc: Boolean = false,
        ppm: Int = 0,
        sstv: Boolean = false,
        record: Boolean = false,
        audio: Boolean = true,
        mode: RxMode = RxMode.NFM,
        bandwidthHz: Int = 0,
        squelchDb: Int = -120,
        offsetHz: Int = 0
    ): Boolean {
        if (running) return true
        // Previous session hasn't released the dongle: wait rather than open a
        // second USB connection on the same hardware.
        if (workerAlive) {
            runCatching { worker?.join(1500) }
            if (workerAlive) { fail("sdr_busy"); return false }
        }
        val app = ctx.applicationContext
        appCtx = app
        val um = app.getSystemService(Context.USB_SERVICE) as? UsbManager
        if (um == null) { fail("usb_unavailable"); return false }
        val dev = RtlSdr.find(um)
        if (dev == null) { fail("sdr_not_found"); return false }

        if (!um.hasPermission(dev)) {
            autoStart = {
                start(app, satName, restHz, gainTenthDb, agc, ppm, sstv, record, audio,
                    mode, bandwidthHz, squelchDb, offsetHz)
            }
            _state.value = _state.value.copy(
                awaitingPermission = true, error = null,
                deviceName = deviceLabel(dev))
            RtlSdr.requestPermission(app, dev)
            return false
        }

        val s = RtlSdr(app)
        if (!s.open(dev)) { fail(s.lastError ?: "sdr_open_failed"); return false }

        val rate = s.setSampleRate(Dsp.RTL_RATE)
        if (ppm != 0) s.setFreqCorrection(ppm)
        s.setAgc(agc)
        s.setGain(gainTenthDb)
        val tuned = s.setCenterFreq(restHz)
        // Buffer reset is done right before queuing transfers, not here:
        // meanwhile the RTL2832 FIFO would fill with no reader and overflow.

        sdr = s
        pendingHz = restHz
        wantAudio = audio
        wantMode = mode
        wantBandwidth = bandwidthHz
        wantSquelch = squelchDb
        userOffset = offsetHz
        dopplerFine = 0
        pllMoveCount = 0
        wantOffset = offsetHz
        running = true
        _state.value = SdrState(
            connected = true,
            deviceName = deviceLabel(dev),
            running = true,
            restHz = restHz,
            centerHz = if (tuned > 0) tuned else restHz,
            dopplerHz = 0L,
            sampleRate = rate,
            gainTenthDb = gainTenthDb,
            audio = audio,
            sstv = sstv,
            satName = satName,
            recording = record,
            mode = mode,
            bandwidthHz = bandwidthHz,
            squelchDb = squelchDb,
            offsetHz = offsetHz,
            // Auto re-centring is an operator setting, not a session one: it
            // survives an unplug.
            autoTune = autoCentroid,
            spanHz = Dsp.RTL_RATE.toDouble() / Dsp.DECIM_1)

        if (sstv) runCatching { SstvHub.startLive(app, Dsp.AUDIO_RATE, satName) }
        // APRS from the dongle's audio too (ISS digipeater), when turned on.
        aprsSdr = runCatching {
            val st = fr.f4ioz.satcombo.data.SettingsStore(app)
            st.aprsEnabled && fr.f4ioz.satcombo.data.Extensions.isUnlocked(
                fr.f4ioz.satcombo.data.Extensions.APRS, st.callsign, st.extensionsCode)
        }.getOrDefault(false)
        if (aprsSdr) runCatching { fr.f4ioz.satcombo.aprs.AprsHub.startLive(app, Dsp.AUDIO_RATE, satName) }

        val recFile: File? = if (record) newRecordFile(app, satName) else null
        if (recFile != null) {
            _state.value = _state.value.copy(recordFile = recFile.name)
        }

        val gen = ++generation
        workerAlive = true
        worker = thread(name = "SdrReader", isDaemon = true) {
            runLoop(gen, s, satName, recFile)
        }
        return true
    }

    private fun newRecordFile(ctx: Context, satName: String): File {
        val fmt = SimpleDateFormat("yyyyMMdd'_'HHmmss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
        val safe = satName.replace(Regex("[^A-Za-z0-9_-]"), "-")
        val dir = File(ctx.getExternalFilesDir(null), "recordings").apply { mkdirs() }
        return File(dir, "SatMe_${safe}_${fmt.format(Date())}.mp3")
    }

    private fun fail(msg: String) {
        _state.value = _state.value.copy(
            running = false, connected = false, error = msg, awaitingPermission = false)
    }

    // --------------------------------------------------------------- loop

    private fun runLoop(gen: Int, s: RtlSdr, satName: String, recFile: File?) {
        /** Is this session still the current one? */
        fun mine() = gen == generation
        val chain = RxChain()
        val iq = ByteArray(BLOCK)
        val pcm = ShortArray(chain.maxAudio(BLOCK))
        var track: AudioTrack? = null
        var playing = false
        var lame: com.naman14.androidlame.AndroidLame? = null
        var mp3Out: FileOutputStream? = null
        var mp3Buf: ByteArray? = null
        // Did we take the single encoder? `finally` must know: releasing a
        // turn we never took would free someone else's.
        var encodeurPris = false
        // Compare setpoint to setpoint, never to the achieved frequency: the
        // PLL sigma-delta returns a slightly different value, and comparing
        // them would retune on every block.
        var appliedHz = pendingHz
        var achievedHz = _state.value.centerHz
        // Doppler absorbed in software, and the counter that proves it.
        var fineHz = 0L
        var pllWrites = 1   // the initial one, already done by [start]
        var bytes = 0L
        var lastUi = 0L
        var lastSpec = 0L
        var lastPan = 0L
        var lastTune = 0L
        var lastFrames = 0L
        var lastPanFrames = 0L
        var errors = 0
        var idle = 0

        try {
            // Async stream: the kernel keeps filling buffers while we
            // demodulate. Otherwise all compute time is time the dongle isn't
            // read.
            s.resetBuffer()
            val streaming = s.startStream(STREAM_DEPTH)

            // Always open audio output, paused if needed: opening it mid-way
            // leaves a gap in SSTV decoding (and made the Sound button do
            // nothing during reception).
            track = runCatching { openTrack() }.getOrNull()
            if (wantAudio) { runCatching { track?.play() }; playing = true }

            // Only one MP3 encoder per process (see [EncodeurMp3]). If taken,
            // drop recording but keep receiving: killing the SDR because a
            // test-signal export is running would be absurd. The recording
            // indicator goes off and the UI says why.
            if (recFile != null && !EncodeurMp3.prend(EncodeurMp3.SDR)) {
                _state.value = _state.value.copy(recording = false, error = "sdr_mp3_busy")
            } else if (recFile != null) {
                encodeurPris = true
                lame = LameBuilder()
                    .setInSampleRate(Dsp.AUDIO_RATE)
                    .setOutSampleRate(Dsp.AUDIO_RATE)
                    .setOutChannels(1)
                    .setOutBitrate(128)
                    .setQuality(5)
                    .setId3tagTitle(satName)
                    .setId3tagArtist("F4IOZ")
                    .setId3tagComment("SatMe RTL-SDR")
                    .build()
                mp3Out = FileOutputStream(recFile)
                mp3Buf = ByteArray((pcm.size * 1.25).toInt() + 7200)
            }

            while (running && mine()) {
                // Doppler. While the shift fits in the fine offset, the
                // software mixer absorbs it and the tuner doesn't move. When
                // the PLL must move, it's between blocks, never mid-read.
                val want = pendingHz
                if (want > 0) {
                    val plan = DopplerTuner.plan(want, appliedHz, fineHz)
                    if (plan.retune) {
                        val got = s.setCenterFreq(plan.pllHz)
                        appliedHz = plan.pllHz
                        achievedHz = if (got > 0) got else plan.pllHz
                        pllWrites++
                    }
                    if (plan.fineHz != fineHz) {
                        fineHz = plan.fineHz
                        chain.dopplerFineHz = fineHz.toDouble()
                    }
                }

                // Live settings are re-read every block instead of interrupting
                // the thread: no lock in the hot loop.
                if (chain.mode != wantMode) { chain.mode = wantMode; chain.reset() }
                val bw = wantBandwidth.toDouble()
                if (chain.bandwidthHz != bw) chain.bandwidthHz = bw
                val sq = wantSquelch.toFloat()
                if (chain.squelch.thresholdDb != sq) chain.squelch.thresholdDb = sq
                val off = wantOffset.toDouble()
                if (chain.offsetHz != off) chain.offsetHz = off
                if (chain.deemphasis != wantDeemph) chain.deemphasis = wantDeemph

                // Short wait: it bounds how long the loop takes to notice a
                // stop, hence how long [stop] blocks.
                val n = if (streaming) s.readStream(iq, READ_MS) else s.read(iq, READ_MS.toInt())
                if (n == 0) {
                    // Nothing within the timeout: the dongle is quiet, not broken.
                    idle++
                    if (idle > 60) { failIf(mine(), "sdr_read_failed"); break }
                    continue
                }
                if (n < 0) {
                    errors++
                    if (errors > 20) { failIf(mine(), "sdr_read_failed"); break }
                    continue
                }
                errors = 0
                idle = 0
                bytes += n
                // METEOR pictures: the raw samples, before any audio chain (120 kHz wide,
                // nothing to listen to). Handed over, decoded on their own thread.
                if (fr.f4ioz.satcombo.meteor.MeteorHub.actif && mine()) {
                    fr.f4ioz.satcombo.meteor.MeteorHub.iq(iq, n, (fineHz + wantOffset).toDouble())
                }

                val nowSpec = System.currentTimeMillis()
                val wantSpec = nowSpec - lastSpec >= 90L
                // Panorama at a third of the spectrum rate: a 16k FFT costs
                // four times more, and a transponder's population doesn't
                // change in 300 ms.
                val wantPan = wantPanorama && nowSpec - lastPan >= 300L
                val produced = chain.process(
                    iq, n, pcm, feedSpectrum = wantSpec, feedPanorama = wantPan)
                if (wantSpec) lastSpec = nowSpec
                if (wantPan) lastPan = nowSpec
                if (chain.panorama.frames != lastPanFrames) {
                    lastPanFrames = chain.panorama.frames
                    if (mine() && wantPanorama) {
                        _panorama.value = chain.panorama.magDb.copyOf()
                    }
                }
                // A spectrum frame spans several USB blocks: publish when it's
                // complete, not when it was started.
                if (chain.spectrum.frames != lastFrames) {
                    lastFrames = chain.spectrum.frames
                    if (mine()) _spectrum.value = chain.spectrum.magDb.copyOf()
                    if (wantPeak) {
                        wantPeak = false
                        val p = chain.peakOffsetHz(peakFromHz, peakToHz)
                        val hz = Math.round(p).toInt().coerceIn(-80_000, 80_000)
                        // Absolute target: subtract the Doppler share so only
                        // the operator's part is rewritten.
                        userOffset = hz - dopplerFine
                        wantOffset = hz
                        if (mine()) _state.value = _state.value.copy(offsetHz = hz)
                    }
                    // Centroid re-centring. The result is absolute relative to
                    // the dongle tuning: write it, don't add it. Zero means
                    // "nothing above noise": keep the current tuning rather than
                    // jump to mid-band on a silence.
                    val autoDue = autoCentroid && nowSpec - lastTune >= AUTO_TUNE_MS
                    if (wantCentroid || autoDue) {
                        wantCentroid = false
                        lastTune = nowSpec
                        val autour =
                            if (centroidAutourDuPoint) (userOffset + dopplerFine).toDouble()
                            else 0.0
                        val c = chain.centroidOffsetHz(
                            searchHz = centroidSearchHz, centreHz = autour)
                        if (c != 0.0) {
                            val hz = fr.f4ioz.satcombo.domain.AccordFin
                                .accordVise(c, centroidCibleHz)
                                .toInt().coerceIn(-80_000, 80_000)
                            userOffset = hz - dopplerFine
                            wantOffset = hz
                            if (mine()) _state.value = _state.value
                                .copy(offsetHz = hz, tunedAtMs = nowSpec)
                        }
                    }
                }
                if (produced > 0) {
                    val tr = track
                    if (tr != null) {
                        if (wantAudio) {
                            if (!playing) { runCatching { tr.play() }; playing = true }
                            runCatching { tr.write(pcm, 0, produced) }
                        } else if (playing) {
                            runCatching { tr.pause(); tr.flush() }
                            playing = false
                        }
                    }
                    if (mine()) runCatching { SstvHub.feedLive(pcm, produced) }
                    if (aprsSdr && mine()) runCatching { fr.f4ioz.satcombo.aprs.AprsHub.feedLive(pcm, produced) }
                    if (SondeHub.active && mine()) {
                        runCatching { SondeHub.feedLive(pcm, produced) }
                    }
                    val l = lame
                    val b = mp3Buf
                    if (l != null && b != null) {
                        val enc = l.encode(pcm, pcm, produced, b)
                        if (enc > 0) mp3Out?.write(b, 0, enc)
                    }
                }

                val now = System.currentTimeMillis()
                if (now - lastUi >= 250L && mine()) {
                    lastUi = now
                    val cur = _state.value
                    _state.value = cur.copy(
                        // Report what we actually listen to, software shift
                        // included, not the tuner frequency.
                        centerHz = achievedHz + fineHz,
                        dopplerFineHz = fineHz,
                        pllWrites = pllWrites,
                        levelDb = chain.levelDb,
                        offsetHz = wantOffset,
                        mode = wantMode,
                        bandwidthHz = wantBandwidth,
                        squelchDb = wantSquelch,
                        clipping = chain.clipRatio > 0.002f,
                        afLevel = chain.audioPeak,
                        mbRead = (bytes / 1_048_576.0).toFloat())
                }
            }
        } catch (e: Exception) {
            failIf(mine(), e.message ?: "sdr_loop_error")
        } finally {
            runCatching {
                val l = lame
                val b = mp3Buf
                if (l != null && b != null) {
                    val flushed = l.flush(b)
                    if (flushed > 0) mp3Out?.write(b, 0, flushed)
                }
            }
            runCatching { lame?.close() }
            // After `lame_close`, never before: see [EncodeurMp3].
            if (encodeurPris) EncodeurMp3.rend(EncodeurMp3.SDR)
            runCatching { mp3Out?.flush(); mp3Out?.close() }
            // Order matters: stop audio before closing USB, or the audio
            // buffer keeps draining on a dead stream.
            runCatching { if (playing) track?.pause() }
            runCatching { track?.flush() }
            runCatching { track?.stop() }
            runCatching { track?.release() }
            runCatching { s.stopStream() }
            runCatching { s.close() }
            // METEOR pictures are written when the dongle stops, off this thread
            // (a full pass is several megapixels to compress).
            if (fr.f4ioz.satcombo.meteor.MeteorHub.actif) {
                Thread({ runCatching { fr.f4ioz.satcombo.meteor.MeteorHub.arrete() } }, "meteor-fin").start()
            }
            // Last act: announce the dongle is released. [stop] and [start]
            // wait for this.
            workerAlive = false
        }
    }

    /** [fail] only if the calling session is still current. */
    private fun failIf(mine: Boolean, msg: String) { if (mine) fail(msg) }

    private fun openTrack(): AudioTrack {
        val min = AudioTrack.getMinBufferSize(
            Dsp.AUDIO_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // A byte is not a sample: AUDIO_RATE bytes = 0.5 s of 16-bit mono. A
        // quarter second held until SSTV decoding and MP3 encoding ran
        // together.
        val size = maxOf(min * 2, Dsp.AUDIO_RATE)   // ~0.5 s of margin
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(Dsp.AUDIO_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
            .setBufferSizeInBytes(size)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        // USAGE_MEDIA plays on the phone speaker unless a headset or
        // Bluetooth speaker is connected: what we want for listening to a pass.
        runCatching { t.setVolume(AudioTrack.getMaxVolume()) }
        return t
    }

    // ------------------------------------------------------------- control

    /**
     * New target frequency, Doppler included. Called once a second; changes
     * below [DopplerTuner.DEADBAND_HZ] are ignored.
     */
    fun setCenter(hz: Long, restHz: Long = _state.value.restHz) {
        if (!running) return
        val cur = pendingHz
        // Small deadband (10 Hz, was 100): a new setpoint no longer costs a
        // PLL write, only a complex multiply in the software mixer.
        if (cur > 0 && Math.abs(hz - cur) < DopplerTuner.DEADBAND_HZ) return
        pendingHz = hz
        // A new dongle frequency cancels the Doppler share of the fine offset:
        // it was relative to the old position.
        dopplerFine = 0
        _state.value = _state.value.copy(restHz = restHz, dopplerHz = hz - restHz)
        applyOffset()
    }

    /** Manual gain (tenths of dB), or null for auto. */
    fun setGain(tenthDb: Int?) {
        val s = sdr ?: return
        runCatching { s.setGain(tenthDb) }
        _state.value = _state.value.copy(gainTenthDb = tenthDb)
    }

    /**
     * Mutes or unmutes audio only: the reader pauses/resumes the AudioTrack,
     * demodulation and SSTV decoding carry on unchanged.
     */
    fun setAudio(on: Boolean) {
        wantAudio = on
        _state.value = _state.value.copy(audio = on)
    }

    /** De-emphasis on/off. Turn it off for anything but voice. */
    fun setDeemphasis(on: Boolean) { wantDeemph = on }

    /** Changes demodulation mode during reception. */
    fun setMode(m: RxMode) {
        wantMode = m
        _state.value = _state.value.copy(mode = m)
    }

    /** Channel width, Hz; zero = mode default. */
    fun setBandwidth(hz: Int) {
        wantBandwidth = hz
        _state.value = _state.value.copy(bandwidthHz = hz)
    }

    /** Squelch threshold, dBFS; -120 = off. */
    fun setSquelch(db: Int) {
        wantSquelch = db
        _state.value = _state.value.copy(squelchDb = db)
    }

    /**
     * Software fine tuning, Hz relative to the dongle frequency: what a finger
     * on the waterfall moves. Applied before the channel filter, so any
     * station visible on the spectrum is reachable without touching the PLL.
     */
    fun setOffset(hz: Int) {
        userOffset = hz.coerceIn(-80_000, 80_000)
        applyOffset()
    }

    /**
     * Doppler share of the fine offset, written by the tracking loop. Added to
     * the operator's tweak, not replacing it: you can tune by hand while
     * tracking keeps compensating.
     */
    fun setDopplerFine(hz: Int) {
        dopplerFine = hz.coerceIn(-80_000, 80_000)
        applyOffset()
    }

    /**
     * Retunes the dongle and resets the Doppler share. Reserved for
     * re-centring: the only audible action, counted so we can check it stays
     * rare.
     */
    fun retune(pllHz: Long, fineHz: Int, restHz: Long) {
        if (!running) return
        pendingHz = pllHz
        pllMoveCount++
        dopplerFine = fineHz.coerceIn(-80_000, 80_000)
        // Publish the new frequency at once, before the reader has written it
        // to the dongle. Otherwise tracking reads the old position next tick,
        // thinks the retune was lost and asks again, and the counter runs away.
        // The reader corrects it within 250 ms with the achieved frequency
        // (a few tens of Hz off, PLL sigma-delta).
        _state.value = _state.value.copy(
            centerHz = pllHz,
            restHz = restHz,
            dopplerHz = pllHz + dopplerFine - restHz,
            pllWrites = pllMoveCount)
        applyOffset()
    }

    /** Sum of both offsets, clamped and published. */
    private fun applyOffset() {
        val v = (userOffset + dopplerFine).coerceIn(-80_000, 80_000)
        wantOffset = v
        _state.value = _state.value.copy(offsetHz = v, dopplerFineHz = dopplerFine.toLong())
    }

    /** Total current correction, Hz, fine offset included: the number to display. */
    fun dopplerAppliedHz(): Long {
        val st = _state.value
        return st.centerHz + st.dopplerFineHz - st.restHz
    }

    /**
     * Auto-tunes on the strongest carrier in the window. The reader resolves
     * the request on the next spectrum frame: only it owns the chain, so this
     * avoids a lock in the hot loop.
     */
    fun tunePeak(fromHz: Double, toHz: Double) {
        if (!running) return
        peakFromHz = minOf(fromHz, toHz)
        peakToHz = maxOf(fromHz, toHz)
        wantPeak = true
    }

    /**
     * Re-centres fine tuning on the signal centroid: auto-tune for carrierless
     * modulations (radiosondes, telemetry beacons, anything with two humps and
     * nothing in between). See [RxChain.centroidOffsetHz]. Resolved by the
     * reader on the next spectrum frame, like [tunePeak].
     */
    fun tuneCentroid(searchHz: Double = 25_000.0) {
        if (!running) return
        centroidSearchHz = searchHz.coerceIn(2_000.0, 80_000.0)
        centroidCibleHz = 0
        centroidAutourDuPoint = false
        wantCentroid = true
    }

    /**
     * Locks onto the received voice, for SSB.
     *
     * Two differences from [tuneCentroid], both matter. The target isn't zero:
     * voice must land around 1500 Hz in the audio band, not straddle the tuned
     * frequency, or you hear half of each syllable. And the search is narrow
     * (3 kHz, one channel) to lock on the station already heard, not the
     * strongest neighbour.
     */
    fun caleVoix(cibleHz: Int, searchHz: Double = 3_000.0) {
        if (!running) return
        centroidSearchHz = searchHz.coerceIn(1_000.0, 80_000.0)
        centroidCibleHz = cibleHz
        centroidAutourDuPoint = true
        wantCentroid = true
    }

    /**
     * Turns continuous re-centring on or off. The first one is requested at
     * once so the button responds within a second; then every [AUTO_TUNE_MS].
     */
    fun setAutoTune(on: Boolean, searchHz: Double = 25_000.0) {
        centroidSearchHz = searchHz.coerceIn(2_000.0, 80_000.0)
        centroidCibleHz = 0
        centroidAutourDuPoint = false
        autoCentroid = on
        if (on && running) wantCentroid = true
        _state.value = _state.value.copy(autoTune = on)
    }

    val isRunning: Boolean get() = running

    @Synchronized
    fun stop() {
        if (!running && !workerAlive) return
        stopWorker()
        if (_state.value.sstv) runCatching { SstvHub.stopLive() }
        if (aprsSdr) { runCatching { fr.f4ioz.satcombo.aprs.AprsHub.stopLive() }; aprsSdr = false }
        _spectrum.value = FloatArray(0)
        _panorama.value = FloatArray(0)
        val present = appCtx?.let { devicePresent(it) != null } ?: false
        _state.value = _state.value.copy(
            running = false, connected = present, levelDb = -120f, sstv = false)
    }

    /**
     * Really stops the reader thread and only returns once the dongle is
     * released.
     *
     * Trap: a `join` whose result is ignored is not enough. The loop can sit
     * over a second in its USB wait, then flush the MP3 and sleep the tuner; a
     * new start meanwhile opened a second audio output and USB connection
     * while the old one cut the endpoint under it (first pass clean, second
     * choppy). So: clear the flag, wait, and if the thread persists, close the
     * USB connection to unblock its wait.
     */
    private fun stopWorker() {
        running = false
        generation++          // the current session loses write rights
        val w = worker
        val s = sdr
        if (w != null) {
            runCatching { w.join(WORKER_JOIN_MS) }
            if (w.isAlive) {
                // Closing the connection makes requestWait() return.
                runCatching { s?.close() }
                runCatching { w.join(WORKER_JOIN_MS) }
            }
        }
        worker = null
        sdr = null
    }

    /** True if the media volume is zero, so the UI can warn. */
    fun musicVolumeZero(ctx: Context): Boolean {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0
    }
}
