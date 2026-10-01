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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * A radio that writes the APRS stations it decodes as waypoint lines on its
 * USB port — Yaesu FT3D, OUTPUT = WAY.P. Reception only: each station joins
 * the APRS page with its position. Nothing is ever written to the radio.
 */
object RecepteurWaypoints {

    data class Etat(
        val connecte: Boolean = false,
        val nom: String = "",
        val vitesse: Int = 9600,
        val recues: Int = 0,
        /** Lines read that were not waypoints (wrong speed shows up here). */
        val illisibles: Int = 0,
        val erreur: String? = null,
    )

    private val _etat = MutableStateFlow(Etat())
    val etat: StateFlow<Etat> = _etat.asStateFlow()

    /** Also told of each station (tests). */
    @Volatile var observateur: ((Waypoints.Station) -> Unit)? = null

    @Volatile private var lien: SerialLink? = null

    suspend fun connecte(ctx: Context, cle: String, vitesse: Int, poste: String = "FT3D"): Boolean =
        withContext(Dispatchers.IO) {
            deconnecte()
            val app = ctx.applicationContext
            val um = app.getSystemService(Context.USB_SERVICE) as UsbManager
            val pilote = UsbSerialProber.getDefaultProber().findAllDrivers(um)
                .firstOrNull { fr.f4ioz.satcombo.cat.cleDe(it.device) == cle } ?: return@withContext echec("absent")
            if (!UsbPermission.await(app, um, pilote.device, UsbPermission.ACTION_CAT)) return@withContext echec("permission")
            val cnx = um.openDevice(pilote.device) ?: return@withContext echec("ouverture")
            val port = pilote.ports.firstOrNull() ?: run { cnx.close(); return@withContext echec("port") }
            runCatching {
                port.open(cnx)
                port.setParameters(vitesse, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            }.onFailure { runCatching { port.close() }; return@withContext echec("ouverture") }
            attache(app, UsbSerialLink(port), TncKiss.appareils(app).firstOrNull { it.cle == cle }?.nom ?: cle,
                vitesse, poste)
            true
        }

    private fun echec(raison: String): Boolean {
        _etat.value = _etat.value.copy(connecte = false, erreur = raison)
        return false
    }

    /** Plugs a serial line in and reads it line by line. */
    fun attache(ctx: Context?, l: SerialLink, nom: String, vitesse: Int, poste: String = "FT3D") {
        lien = l
        _etat.value = Etat(connecte = true, nom = nom, vitesse = vitesse)
        Thread({
            val buf = ByteArray(512)
            val ligne = StringBuilder()
            while (lien === l) {
                val n = runCatching { l.read(buf, 200) }.getOrElse {
                    if (lien === l) _etat.value = _etat.value.copy(connecte = false, erreur = "lecture")
                    lien = null; -1
                }
                for (i in 0 until maxOf(n, 0)) {
                    val c = (buf[i].toInt() and 0xFF).toChar()
                    if (c == '\n' || c == '\r') {
                        if (ligne.isNotBlank()) traite(ctx, ligne.toString(), poste)
                        ligne.setLength(0)
                    } else if (ligne.length < 200) ligne.append(c)
                }
            }
        }, "RecepteurWaypoints").apply { isDaemon = true; start() }
    }

    private fun traite(ctx: Context?, ligne: String, poste: String) {
        val s = Waypoints.lit(ligne)
        if (s == null) {
            _etat.value = _etat.value.copy(illisibles = _etat.value.illisibles + 1)
            return
        }
        _etat.value = _etat.value.copy(recues = _etat.value.recues + 1)
        observateur?.invoke(s)
        if (ctx != null) AprsHub.recuDuPoste(ctx, Waypoints.trame(s, poste), poste)
    }

    fun deconnecte() {
        val l = lien ?: return
        lien = null
        runCatching { l.close() }
        _etat.value = _etat.value.copy(connecte = false)
    }
}
