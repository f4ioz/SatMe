/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
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
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import fr.f4ioz.satcombo.MainActivity
import fr.f4ioz.satcombo.i18n.t

/**
 * La prise de son dédiée aux radiosondes.
 *
 * Une sonde ne se reçoit pas forcément avec la clé RTL : beaucoup d'opérateurs
 * ont déjà un récepteur qui sort le 404 MHz proprement, et il suffit alors de
 * lui prendre son audio. Le service ouvre donc l'entrée choisie — micro du
 * téléphone devant le haut-parleur, ou carte son USB câblée sur la sortie
 * discriminateur — et pousse le son brut dans [fr.f4ioz.satcombo.sonde.SondeHub].
 *
 * Il ne ressemble pas à [RecorderService] sur un point essentiel : rien n'est
 * écrit sur la carte. Un vol de sonde dure trois heures, ce qui ferait cent
 * soixante-dix mégaoctets de MP3 dont personne ne veut ; seules les trames
 * décodées méritent d'être gardées, et c'est déjà le journal CSV qui s'en
 * charge. On se contente donc de lire le flux et de le donner au décodeur.
 *
 * Le Bluetooth n'est volontairement pas proposé : le profil mains-libres
 * échantillonne à 8 ou 16 kHz avec réducteur de bruit obligatoire, ce qui
 * détruit une modulation à 4800 ou 9600 bauds. Pour la même raison la capture
 * demande UNPROCESSED quand le téléphone sait le faire : la correction
 * automatique de gain arrondit les fronts et le décodage tombe à zéro trame.
 */
class SondeAudioService : Service() {

    companion object {
        const val ACTION_START = "fr.f4ioz.satcombo.SONDE_AUDIO_START"
        const val ACTION_STOP = "fr.f4ioz.satcombo.SONDE_AUDIO_STOP"
        const val EXTRA_SOURCE = "sondeSource"    // "MIC" | "USB"
        private const val CHANNEL = "sonde_audio"
        private const val NOTIF_ID = 4219

        /** Fréquence d'échantillonnage de la prise de son, en hertz. */
        const val RATE = 44_100

        /** Le service tourne-t-il ? Lu par le ViewModel pour l'affichage. */
        @Volatile
        var running: Boolean = false
            private set

        fun start(context: Context, source: String = "MIC") {
            context.startForegroundService(
                Intent(context, SondeAudioService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_SOURCE, source))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, SondeAudioService::class.java).setAction(ACTION_STOP))
        }

        private val USB_INPUT_TYPES = intArrayOf(
            android.media.AudioDeviceInfo.TYPE_USB_DEVICE,
            android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
            android.media.AudioDeviceInfo.TYPE_USB_ACCESSORY)
    }

    @Volatile private var capture = false
    private var rec: AudioRecord? = null
    private var thread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (capture) return START_NOT_STICKY
                val src = intent.getStringExtra(EXTRA_SOURCE) ?: "MIC"
                startInForeground(src)
                if (!openInput(src)) {
                    running = false
                    stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
                    return START_NOT_STICKY
                }
                capture = true
                running = true
                thread = Thread { pump() }.apply { isDaemon = true; start() }
            }
            ACTION_STOP -> finish()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        finish()
        super.onDestroy()
    }

    /**
     * Ouvre l'entrée demandée. La source UNPROCESSED est préférée dès que le
     * téléphone la déclare, sinon VOICE_RECOGNITION qui, sur la plupart des
     * appareils, se passe déjà du réducteur de bruit.
     */
    private fun openInput(src: String): Boolean = runCatching {
        val am = getSystemService(android.media.AudioManager::class.java)
        val unproc = runCatching {
            am?.getProperty(
                android.media.AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        }.getOrDefault(false)
        val source = when {
            unproc -> MediaRecorder.AudioSource.UNPROCESSED
            else -> MediaRecorder.AudioSource.VOICE_RECOGNITION
        }
        val min = AudioRecord.getMinBufferSize(
            RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return@runCatching false
        val r = AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, min * 4)
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return@runCatching false }
        if (src == "USB") {
            val dev = am?.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS)
                ?.firstOrNull { it.type in USB_INPUT_TYPES }
            if (dev != null) r.setPreferredDevice(dev)
        }
        r.startRecording()
        if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            r.release(); return@runCatching false
        }
        rec = r
        true
    }.getOrDefault(false)

    /** Boucle de lecture : des blocs courts, pour que le décodeur voie le
     *  signal presque en temps réel et que la jauge de niveau vive. */
    private fun pump() {
        val buf = ShortArray(4096)
        val r = rec ?: return
        while (capture) {
            val n = runCatching { r.read(buf, 0, buf.size) }.getOrDefault(-1)
            if (n <= 0) {
                if (n < 0) break
                continue
            }
            runCatching { fr.f4ioz.satcombo.sonde.SondeHub.feedLive(buf, n) }
        }
    }

    private fun finish() {
        if (!capture && rec == null) {
            running = false
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            return
        }
        capture = false
        running = false
        runCatching { thread?.join(500) }
        thread = null
        runCatching { rec?.stop() }
        runCatching { rec?.release() }
        rec = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startInForeground(src: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, t("sonde_audio_channel"),
                    NotificationManager.IMPORTANCE_LOW))
        }
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notif: Notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(t("sonde_audio_notif"))
            .setContentText(if (src == "USB") t("rec_source_usb") else t("rec_source_mic"))
            .setOngoing(true)
            .setContentIntent(tap)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }
}
