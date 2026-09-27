/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import fr.f4ioz.satcombo.MainActivity
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.i18n.tf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Recorder state shared with the UI (ViewModel collects this). */
data class RecorderState(
    val recording: Boolean = false,
    val startMs: Long = 0L,
    val fileName: String? = null,
    val autoStopMs: Long? = null
)

/**
 * Foreground service (microphone type) that owns the MP3 pass recorder, so a
 * recording keeps running with the screen off or the app in the background —
 * required behaviour for unattended pass recording, and mandatory on modern
 * Android for any background mic capture. State is published via [state] and
 * the service stops itself at the auto-stop deadline (LOS + 5 s) or on demand.
 */
class RecorderService : Service() {

    companion object {
        const val ACTION_START = "fr.f4ioz.satcombo.REC_START"
        const val ACTION_STOP = "fr.f4ioz.satcombo.REC_STOP"
        const val EXTRA_SAT = "sat"
        const val EXTRA_AUTOSTOP = "autoStopMs"   // 0 = manual only
        const val EXTRA_SOURCE = "recSource"      // "MIC" | "BT" | "USB"
        const val EXTRA_UNPROC = "unprocessed"
        const val EXTRA_LOC = "locator"
        private const val CHANNEL = "recording"
        private const val NOTIF_ID = 4217

        private val _state = MutableStateFlow(RecorderState())
        val state: StateFlow<RecorderState> = _state

        // QSO markers dropped during the recording (triple-tap log): wall-clock
        // time + label. Offsets into the audio are derived from startMs when the
        // sidecar file is written at stop time.
        private val markers = mutableListOf<Pair<Long, String>>()

        /** Note a moment of interest (e.g. a logged QSO) in the running recording. */
        fun addMarker(label: String) {
            if (!_state.value.recording) return
            synchronized(markers) { markers.add(System.currentTimeMillis() to label) }
        }

        fun start(
            context: Context, satName: String, autoStopMs: Long?,
            source: String = "MIC", unprocessed: Boolean = false,
            locator: String = ""
        ) {
            val i = Intent(context, RecorderService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_SAT, satName)
                .putExtra(EXTRA_AUTOSTOP, autoStopMs ?: 0L)
                .putExtra(EXTRA_SOURCE, source)
                .putExtra(EXTRA_UNPROC, unprocessed)
                .putExtra(EXTRA_LOC, locator)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, RecorderService::class.java).setAction(ACTION_STOP))
        }

        private val USB_INPUT_TYPES = intArrayOf(
            android.media.AudioDeviceInfo.TYPE_USB_DEVICE,
            android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
            android.media.AudioDeviceInfo.TYPE_USB_ACCESSORY)

        /** USB audio capture devices plugged in right now, as product names.
         *  Empty = nothing to route to; the Settings screen shows this so the
         *  user can check his cable before the pass, not during it. */
        fun usbInputs(context: Context): List<String> = runCatching {
            val am = context.getSystemService(android.media.AudioManager::class.java)
                ?: return emptyList()
            am.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS)
                .filter { it.type in USB_INPUT_TYPES }
                .map { it.productName?.toString()?.trim().orEmpty().ifEmpty { "USB audio" } }
        }.getOrDefault(emptyList())

        /** True when the platform declares AudioSource.UNPROCESSED as usable —
         *  capture with no AGC and no noise suppression. */
        fun unprocessedSupported(context: Context): Boolean = runCatching {
            context.getSystemService(android.media.AudioManager::class.java)
                ?.getProperty(
                    android.media.AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        }.getOrDefault(false)
    }

    private val recorder = PassRecorder()
    private val handler = Handler(Looper.getMainLooper())
    private val autoStop = Runnable { finishRecording() }
    private var scoActive = false
    private var lastSat = AnnonceVocale.SANS_SATELLITE

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (_state.value.recording) return START_NOT_STICKY
                val sat = intent.getStringExtra(EXTRA_SAT) ?: AnnonceVocale.SANS_SATELLITE
                val auto = intent.getLongExtra(EXTRA_AUTOSTOP, 0L).takeIf { it > 0L }
                val src = intent.getStringExtra(EXTRA_SOURCE) ?: "MIC"
                val unproc = intent.getBooleanExtra(EXTRA_UNPROC, false)
                val loc = intent.getStringExtra(EXTRA_LOC).orEmpty()
                val now = System.currentTimeMillis()
                val fmt = SimpleDateFormat("yyyyMMdd'_'HHmmss'Z'", Locale.US)
                    .apply { timeZone = TimeZone.getTimeZone("UTC") }
                val safe = sat.replace(Regex("[^A-Za-z0-9_-]"), "-")
                val dir = File(getExternalFilesDir(null), "recordings").apply { mkdirs() }
                val file = File(dir, "SatMe_${safe}_${fmt.format(Date(now))}.mp3")

                synchronized(markers) { markers.clear() }
                lastSat = sat
                // Foreground FIRST (5 s rule for startForegroundService), then the
                // possibly-slow Bluetooth SCO bring-up on a worker thread.
                startInForeground(sat, src)
                Thread {
                    val btDev = if (src == "BT") bringUpBluetooth() else null
                    if (src == "BT" && btDev == null) {
                        handler.post {
                            android.widget.Toast.makeText(this,
                                t("bt_hfp_unavailable"), android.widget.Toast.LENGTH_LONG).show()
                        }
                        tearDownBluetooth()
                    }
                    // USB sound card (Behringer UCA202 & co): no link to bring up,
                    // just pin the capture to that input. Nothing plugged in ->
                    // say so and fall back to the phone mic rather than fail.
                    val usbDev = if (src == "USB") findUsbInput() else null
                    if (src == "USB" && usbDev == null) {
                        handler.post {
                            android.widget.Toast.makeText(this,
                                t("usb_audio_unavailable"), android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                    // UNPROCESSED = no AGC, no noise suppression: the rig has
                    // already done all the processing this audio should get.
                    // Meaningless on the SCO path (the modem processes anyway).
                    val baseSrc = if (unproc && unprocessedSupported(this))
                        android.media.MediaRecorder.AudioSource.UNPROCESSED
                    else android.media.MediaRecorder.AudioSource.MIC
                    // SCO links are 8/16 kHz mono: record at 16 kHz / 64 kbps there,
                    // full 44.1 kHz / 128 kbps on the phone mic and on USB.
                    val rate = if (btDev != null) 16_000 else 44_100
                    // SSTV listens to the raw capture, not to the MP3: the
                    // decoder sees the samples before the encoder touches them.
                    // Only runs when the SSTV extension is enabled: no CPU spent
                    // on a feature the operator cannot see.
                    val sstv = runCatching {
                        val st = fr.f4ioz.satcombo.data.SettingsStore(this)
                        st.sstvEnabled && fr.f4ioz.satcombo.data.Extensions.isUnlocked(
                            fr.f4ioz.satcombo.data.Extensions.SSTV, st.callsign, st.extensionsCode)
                    }.getOrDefault(false)
                    // Same for APT: with no header to wait for, it runs for the
                    // whole pass, so only when asked for (a NOAA pass is
                    // planned, not stumbled upon).
                    val apt = runCatching {
                        val st = fr.f4ioz.satcombo.data.SettingsStore(this)
                        st.aptEnabled && fr.f4ioz.satcombo.data.Extensions.isUnlocked(
                            fr.f4ioz.satcombo.data.Extensions.APT, st.callsign, st.extensionsCode)
                    }.getOrDefault(false)
                    if (sstv) fr.f4ioz.satcombo.sstv.SstvHub.startLive(this, rate, sat)
                    if (apt) fr.f4ioz.satcombo.apt.AptHub.startLive(this, rate, sat)
                    // Monitor (spectrum + speaker playback). Opened even with
                    // both options off, so they can be turned on mid-pass
                    // without restarting the recording.
                    val reg = runCatching { fr.f4ioz.satcombo.data.SettingsStore(this) }.getOrNull()
                    MoniteurAudio.demarrer(
                        rate = rate,
                        sourceExterne = src != "MIC",
                        spectre = reg?.monitorSpectre ?: false,
                        hautParleur = reg?.monitorSpeaker ?: false)
                    // One sink feeding everyone in order: the monitor first,
                    // since it only copies, then the decoders.
                    val sink: ((ShortArray, Int) -> Unit) = { p, n ->
                        MoniteurAudio.alimenter(p, n)
                        if (sstv) fr.f4ioz.satcombo.sstv.SstvHub.feedLive(p, n)
                        if (apt) fr.f4ioz.satcombo.apt.AptHub.feedLive(p, n)
                    }
                    // Spoken header (satellite, date, locator), synthesised
                    // while the microphone already listens: see PassRecorder.
                    val annonce = if (reg?.annonceVocale != false) {
                        val fr = fr.f4ioz.satcombo.i18n.I18n.current() == fr.f4ioz.satcombo.i18n.Lang.FR
                        val texte = AnnonceVocale.texte(sat, now, loc, fr)
                        java.util.concurrent.FutureTask {
                            AnnonceVocale.synthetise(this, texte,
                                if (fr) Locale.FRENCH else Locale.ENGLISH, rate)
                        }.also { Thread(it, "AnnonceVocale").start() }
                    } else null
                    val ok = runCatching {
                        if (btDev != null) recorder.start(file, sat, rate, 64, btDev,
                            android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                            sink, annonce)
                        else recorder.start(file, sat, rate, 128, usbDev, baseSrc, sink, annonce)
                    }.getOrDefault(false)
                    handler.post {
                        if (!ok) {
                            MoniteurAudio.arreter()
                            fr.f4ioz.satcombo.sstv.SstvHub.stopLive()
                            fr.f4ioz.satcombo.apt.AptHub.stopLive()
                            tearDownBluetooth()
                            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
                            return@post
                        }
                        _state.value = RecorderState(true, now, file.name, auto)
                        if (auto != null) {
                            val wait = auto - System.currentTimeMillis()
                            if (wait > 0) handler.postDelayed(autoStop, wait) else finishRecording()
                        }
                    }
                }.start()
            }
            ACTION_STOP -> finishRecording()
        }
        return START_NOT_STICKY
    }

    /**
     * Bring up the Bluetooth headset (HFP/SCO) audio link and return its input
     * device, or null if no HFP device answers within ~5 s. Requires a module
     * paired in HANDS-FREE profile — an A2DP-only transmitter cannot be captured.
     */
    private fun bringUpBluetooth(): android.media.AudioDeviceInfo? {
        val am = getSystemService(android.media.AudioManager::class.java) ?: return null
        return runCatching {
            am.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= 31) {
                val target = am.availableCommunicationDevices.firstOrNull {
                    it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                } ?: return@runCatching null
                if (!am.setCommunicationDevice(target)) return@runCatching null
                scoActive = true
            } else {
                @Suppress("DEPRECATION") am.startBluetoothSco()
                @Suppress("DEPRECATION") am.isBluetoothScoOn = true
                scoActive = true
            }
            // Wait for the SCO input to appear (async link establishment).
            repeat(25) {
                val dev = am.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS)
                    .firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                if (dev != null) return@runCatching dev
                Thread.sleep(200)
            }
            null
        }.getOrNull()
    }

    /** The first USB audio capture device, or null if the cable is not in. */
    private fun findUsbInput(): android.media.AudioDeviceInfo? = runCatching {
        getSystemService(android.media.AudioManager::class.java)
            ?.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS)
            ?.firstOrNull { it.type in USB_INPUT_TYPES }
    }.getOrNull()

    private fun tearDownBluetooth() {
        if (!scoActive) {
            runCatching { getSystemService(android.media.AudioManager::class.java)?.mode =
                android.media.AudioManager.MODE_NORMAL }
            return
        }
        scoActive = false
        runCatching {
            val am = getSystemService(android.media.AudioManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
            else {
                @Suppress("DEPRECATION") am.stopBluetoothSco()
                @Suppress("DEPRECATION") am.isBluetoothScoOn = false
            }
            am.mode = android.media.AudioManager.MODE_NORMAL
        }
    }

    private fun startInForeground(sat: String, src: String = "MIC") {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, t("rec_channel_name"),
                    NotificationManager.IMPORTANCE_LOW).apply {
                    description = t("rec_channel_desc")
                })
        }
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notif: Notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(t("rec_notif_title"))
            .setContentText(tf("rec_notif_text", sat) + when (src) {
                "BT" -> " · Bluetooth"
                "USB" -> " · USB"
                else -> ""
            })
            .setOngoing(true)
            .setContentIntent(tap)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun finishRecording() {
        handler.removeCallbacks(autoStop)
        if (_state.value.recording) {
            recorder.stop()
            // The monitor stops with the capture: its thread never outlives
            // the recording that feeds it.
            MoniteurAudio.arreter()
            // Flush the SSTV engine after the capture thread has stopped, so a
            // picture that was still building when the pass ended is kept.
            fr.f4ioz.satcombo.sstv.SstvHub.stopLive()
            // APT is written at the same point: the image is the whole pass,
            // so it only exists once the pass is over.
            fr.f4ioz.satcombo.apt.AptHub.stopLive()
            val startMs = _state.value.startMs
            _state.value = _state.value.copy(recording = false, autoStopMs = null)
            // Sidecar with QSO markers + export copy, off the main thread.
            val mp3 = recorder.currentFile
            Thread {
                val sidecar = writeSidecar(mp3, startMs)
                exportCopies(listOfNotNull(mp3, sidecar))
            }.start()
        }
        tearDownBluetooth()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Write "<name>.txt" next to the MP3, listing each marker as an offset into
     *  the audio (mm:ss) plus the absolute UTC time — so a QSO heard at replay
     *  position 03:41 can be matched to the log, and vice versa. */
    private fun writeSidecar(mp3: File?, startMs: Long): File? {
        if (mp3 == null) return null
        val marks = synchronized(markers) { markers.toList() }
        if (marks.isEmpty()) return null
        val utc = SimpleDateFormat("HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
        val dUtc = SimpleDateFormat("yyyy-MM-dd HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
        val txt = File(mp3.parentFile, mp3.name.removeSuffix(".mp3") + ".txt")
        return runCatching {
            txt.writeText(buildString {
                append(t("sidecar_header") + "\n")
                append(tf("sidecar_file", mp3.name) + "\n")
                append(tf("sidecar_sat_start", lastSat, dUtc.format(Date(startMs))) + "\n\n")
                // The spoken header shifts the pass audio by its length.
                val decalage = recorder.annonceMs
                for ((wall, label) in marks) {
                    val off = ((wall - startMs + decalage) / 1000).coerceAtLeast(0)
                    append("%02d:%02d".format(off / 60, off % 60))
                    append("  (${utc.format(Date(wall))})  $label\n")
                }
            })
            txt
        }.getOrNull()
    }

    /** Copy finished files into the user-chosen SAF folder, if configured. */
    private fun exportCopies(files: List<File>) {
        val uriStr = fr.f4ioz.satcombo.data.SettingsStore(this).recordingsTreeUri
        if (uriStr.isBlank()) return
        runCatching {
            val tree = androidx.documentfile.provider.DocumentFile.fromTreeUri(
                this, android.net.Uri.parse(uriStr)) ?: return
            for (f in files) {
                if (!f.exists()) continue
                val mime = if (f.name.endsWith(".mp3")) "audio/mpeg" else "text/plain"
                tree.findFile(f.name)?.delete()
                val doc = tree.createFile(mime, f.name) ?: continue
                contentResolver.openOutputStream(doc.uri)?.use { out ->
                    f.inputStream().use { it.copyTo(out) }
                }
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(autoStop)
        if (_state.value.recording) {
            recorder.stop()
            fr.f4ioz.satcombo.sstv.SstvHub.stopLive()
            fr.f4ioz.satcombo.apt.AptHub.stopLive()
            _state.value = _state.value.copy(recording = false, autoStopMs = null)
        }
        tearDownBluetooth()
        super.onDestroy()
    }
}
