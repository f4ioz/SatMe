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
import fr.f4ioz.satcombo.data.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Between the APRS decoders and [ClientIGate]: the receive-only iGate of the
 * phone (APRS › Réglages › iGate APRS-IS).
 *
 * - **off by default**; its thread runs only while it is switched on;
 * - fed only with frames heard live on the air ([AprsHub]: the phone's
 *   sound or the dongle's, a KISS radio listening) — never a recording read
 *   again, a radio's waypoint rebuilt as a frame, nor what this phone sent;
 * - the passcode is kept in the app's no-backup folder (out of Android's
 *   backups), written there only, never shown back;
 * - nothing here transmits, and nothing from APRS-IS goes to a radio.
 */
object IGateHub {

    @Volatile private var app: Context? = null
    @Volatile private var client: ClientIGate? = null
    /** Read once from its file, then kept in memory. */
    @Volatile private var passcode: String? = null

    private val _etat = MutableStateFlow(ClientIGate.Etat())
    /** The client's state; [ClientIGate.Etat.phase] "coupe" while off. */
    val etat: StateFlow<ClientIGate.Etat> = _etat.asStateFlow()

    private fun fichier(ctx: Context) = File(ctx.noBackupFilesDir, "igate-passcode")

    private fun reglages(): ClientIGate.Reglages {
        val c = app ?: return ClientIGate.Reglages(false, "", "", "")
        val s = SettingsStore(c)
        return ClientIGate.Reglages(s.igateActif, s.igateServeur, indicatif(s), passcode(c))
    }

    /** The iGate's callsign: the one set, else the station's. */
    fun indicatif(s: SettingsStore): String = s.igateIndicatif.ifBlank { s.callsign.trim().uppercase() }

    private fun passcode(ctx: Context): String =
        passcode ?: runCatching { fichier(ctx).takeIf { it.isFile }?.readText()?.trim() }.getOrNull().orEmpty().also { passcode = it }

    /** Starts the client when the settings say so (at start, and after each change). */
    @Synchronized
    fun init(ctx: Context) {
        app = ctx.applicationContext
        val actif = runCatching { SettingsStore(ctx.applicationContext).igateActif }.getOrDefault(false)
        if (actif && client == null) {
            client = ClientIGate({ reglages() }, version = { fr.f4ioz.satcombo.data.TleRepository.version })
                .also { c -> suit(c) }
        } else if (!actif && client != null) {
            client?.arrete(); client = null
            _etat.value = ClientIGate.Etat()
        } else client?.change()
    }

    /** Mirrors the client's state while it lives. */
    private fun suit(c: ClientIGate) {
        Thread({
            while (client === c) {
                _etat.value = c.etat.value
                try { Thread.sleep(500) } catch (_: InterruptedException) { break }
            }
        }, "satme-igate-etat").apply { isDaemon = true }.start()
    }

    /** A frame heard live on the air. Nothing happens while the iGate is off. */
    fun entendu(t: Trame, quand: Long = System.currentTimeMillis()) {
        runCatching { client?.recoit(t, quand) }
    }

    fun setActif(ctx: Context, v: Boolean) { SettingsStore(ctx).igateActif = v; init(ctx) }
    fun setServeur(ctx: Context, v: String) { SettingsStore(ctx).igateServeur = v; init(ctx) }
    fun setIndicatif(ctx: Context, v: String) { SettingsStore(ctx).igateIndicatif = v; init(ctx) }

    /** Write only: a passcode as typed (checked by the caller). */
    fun posePasscode(ctx: Context, p: String) {
        val v = p.trim()
        runCatching { fichier(ctx).writeText(v) }
        passcode = v
        init(ctx)
    }

    fun effacePasscode(ctx: Context) {
        runCatching { fichier(ctx).delete() }
        passcode = ""
        init(ctx)
    }

    /** Whether one is set, never what it is. */
    fun passcodePose(ctx: Context): Boolean = passcode(ctx).isNotEmpty()
}
