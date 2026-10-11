/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.usb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import fr.f4ioz.satcombo.cat.CatScan
import fr.f4ioz.satcombo.cat.UsbSerialLink
import fr.f4ioz.satcombo.cat.cleDe
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.i18n.tf
import fr.f4ioz.satcombo.plateforme.Lignes
import fr.f4ioz.satcombo.plateforme.Ouverture
import fr.f4ioz.satcombo.plateforme.PortSerie
import fr.f4ioz.satcombo.plateforme.PortsSerie
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The phone's serial ports: USB adapters seen by usb-serial-for-android.
 * Permission is per adapter (both IC-9700 ports share one grant and one
 * dialog), asked under the adapter's own request code so that two requests
 * at once never overwrite each other.
 */
class PortsSerieAndroid(private val context: Context) : PortsSerie {

    private val um get() = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private fun pilotes() = UsbSerialProber.getDefaultProber().findAllDrivers(um)
    private fun appareil(p: PortSerie): UsbDevice? = pilotes().getOrNull(p.appareil)?.device

    override fun liste(): List<PortSerie> {
        val out = ArrayList<PortSerie>()
        pilotes().forEachIndexed { d, drv ->
            val n = drv.ports.size.coerceAtLeast(1)
            val dev = drv.device
            for (i in 0 until n) {
                out.add(PortSerie(out.size, d, i, n, CatScan.etiquette(dev.productName, dev.deviceName, i, n),
                    cleDe(dev), dev.vendorId, dev.productName))
            }
        }
        return out
    }

    override fun autorise(p: PortSerie): Boolean =
        appareil(p)?.let { runCatching { um.hasPermission(it) }.getOrDefault(false) } ?: false

    override fun demande(p: PortSerie) {
        val dev = appareil(p) ?: return
        UsbPermission.ensure(context, um, dev, UsbPermission.ACTION_CAT, dev.deviceId)
    }

    override suspend fun demandeEtAttends(p: PortSerie): Boolean {
        val dev = appareil(p) ?: return false
        if (runCatching { um.hasPermission(dev) }.getOrDefault(false)) return true
        return UsbPermission.await(context, um, dev, UsbPermission.ACTION_CAT, dev.deviceId)
    }

    override suspend fun ouvre(p: PortSerie, baud: Int, lignes: Lignes): Ouverture = withContext(Dispatchers.IO) {
        val drv = pilotes().getOrNull(p.appareil) ?: return@withContext Ouverture(null, t("cat_err_no_device"))
        if (!um.hasPermission(drv.device)) return@withContext Ouverture(null, t("cat_err_denied"))
        val conn = um.openDevice(drv.device) ?: return@withContext Ouverture(null, t("cat_err_open_device"))
        val port = drv.ports.getOrNull(p.port)
        if (port == null) { conn.close(); return@withContext Ouverture(null, t("cat_err_no_port")) }
        runCatching {
            port.open(conn)
            port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            val haut = lignes == Lignes.LEVEES
            runCatching { port.setDTR(haut); port.setRTS(haut) }
            Ouverture(UsbSerialLink(port), "")
        }.getOrElse {
            runCatching { port.close() }
            Ouverture(null, tf("cat_err_open_port", it.message ?: "?"))
        }
    }

    companion object {
        /** Is this newly plugged device a serial bridge (and not an SDR dongle)? */
        fun estSerie(dev: UsbDevice?): Boolean =
            dev != null && runCatching { UsbSerialProber.getDefaultProber().probeDevice(dev) != null }.getOrDefault(false)
    }
}
