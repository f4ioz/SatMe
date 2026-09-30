/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.aprs

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Where an APRS frame's audio goes: the rig's USB sound card to transmit
 * (IC-9700: its "USB audio CODEC"), the phone's speaker for a test, or a WAV.
 */
object SortieAudio {

    const val FREQUENCE = 48_000

    private val USB = setOf(AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY)

    /** The rig's USB sound card as an output, or null when none is plugged in. */
    fun carteDuPoste(ctx: Context): AudioDeviceInfo? =
        (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type in USB }

    /**
     * Plays [pcm] and returns once it has been played (true), or false if the
     * device refused it. On [vers] when given, else the phone's normal output.
     */
    suspend fun joue(pcm: ShortArray, vers: AudioDeviceInfo?): Boolean = withContext(Dispatchers.IO) {
        val piste = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN).build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(FREQUENCE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
        }.getOrNull() ?: return@withContext false
        try {
            if (vers != null && !piste.setPreferredDevice(vers)) return@withContext false
            if (piste.write(pcm, 0, pcm.size) != pcm.size) return@withContext false
            piste.play()
            val fin = System.currentTimeMillis() + pcm.size * 1000L / FREQUENCE + 2_000
            while (piste.playbackHeadPosition < pcm.size && System.currentTimeMillis() < fin) delay(20)
            piste.playbackHeadPosition >= pcm.size
        } finally {
            runCatching { piste.stop() }
            piste.release()
        }
    }

    /** 16-bit mono WAV. */
    fun wav(pcm: ShortArray, fichier: File, frequence: Int = FREQUENCE): File {
        val b = java.nio.ByteBuffer.allocate(44 + pcm.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + pcm.size * 2).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(frequence)
            .putInt(frequence * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(pcm.size * 2)
        pcm.forEach { b.putShort(it) }
        fichier.writeBytes(b.array())
        return fichier
    }
}
