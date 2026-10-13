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
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import fr.f4ioz.satcombo.cat.SerialLink
import fr.f4ioz.satcombo.cat.UsbSerialLink
import fr.f4ioz.satcombo.usb.UsbPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * A radio with a KISS TNC on USB — Kenwood TH-D72 (its USB port is a serial
 * line), TH-D74/D75, TM-D710, or a TNC like the Mobilinkd. Separate from the
 * CAT link: the IC-9700 can stay on CAT while a TH-D72 hangs off the hub.
 *
 * Frames the radio receives come in already decoded (its own modem) and join
 * the APRS page like the others. A frame to send goes out as one KISS frame;
 * the radio keys, modulates and returns to receive by itself.
 */
object TncKiss {

    /** The protocol, the engine's (the same on SatMe-PC): frames go to the APRS page, and to [observateur]. */
    @Volatile private var app: Context? = null
    private val lien = LienKiss { t -> observateur?.invoke(t); app?.let { AprsHub.recuDuTnc(it, t) } }

    val etat: StateFlow<LienKiss.Etat> = lien.etat

    /** A USB serial adapter: its label and its stable key. */
    data class Appareil(val nom: String, val cle: String)

    /** Also told of each frame received (tests, diagnostics). */
    @Volatile var observateur: ((Trame) -> Unit)? = null

    fun appareils(ctx: Context): List<Appareil> = runCatching {
        val um = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        UsbSerialProber.getDefaultProber().findAllDrivers(um).map { d ->
            Appareil(listOfNotNull(d.device.productName, d.device.manufacturerName).joinToString(" · ")
                .ifBlank { d.device.deviceName }, fr.f4ioz.satcombo.cat.cleDe(d.device))
        }
    }.getOrDefault(emptyList())

    /** Opens the adapter [cle] at [vitesse] 8N1, asking for USB permission if needed. */
    suspend fun connecte(ctx: Context, cle: String, vitesse: Int): Boolean = withContext(Dispatchers.IO) {
        deconnecte(sortirDuKiss = false)
        val app = ctx.applicationContext
        val um = app.getSystemService(Context.USB_SERVICE) as UsbManager
        val pilote = UsbSerialProber.getDefaultProber().findAllDrivers(um)
            .firstOrNull { fr.f4ioz.satcombo.cat.cleDe(it.device) == cle }
            ?: return@withContext echec("absent")
        if (!UsbPermission.await(app, um, pilote.device, UsbPermission.ACTION_CAT)) return@withContext echec("permission")
        val cnx = um.openDevice(pilote.device) ?: return@withContext echec("ouverture")
        val port = pilote.ports.firstOrNull() ?: run { cnx.close(); return@withContext echec("port") }
        runCatching {
            port.open(cnx)
            port.setParameters(vitesse, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            runCatching { port.setDTR(true); port.setRTS(true) }
        }.onFailure { runCatching { port.close() }; return@withContext echec("ouverture") }
        attache(app, UsbSerialLink(port), appareils(app).firstOrNull { it.cle == cle }?.nom ?: cle, vitesse)
        true
    }

    private fun echec(raison: String): Boolean = lien.echec(raison)

    /** Plugs a serial line in (the real cable, or a test's fake) and starts reading. */
    fun attache(ctx: Context?, l: SerialLink, nom: String, vitesse: Int) {
        app = ctx?.applicationContext
        lien.attache(l, nom, vitesse)
    }

    suspend fun passeEnKiss(frequenceHz: Long? = null): Boolean = lien.passeEnKiss(frequenceHz)

    /** One frame to the radio, which transmits it. */
    suspend fun envoie(t: Trame): Boolean = lien.envoie(t)

    suspend fun regleFrequence(hz: Long): Boolean = lien.regleFrequence(hz)

    /** Closes the line; by default puts the radio back as it was. Blocking about a second. */
    fun deconnecte(sortirDuKiss: Boolean = true) = lien.deconnecte(sortirDuKiss)
}
