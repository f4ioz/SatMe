/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import com.fazecast.jSerialComm.SerialPort
import fr.f4ioz.satcombo.cat.IdentiteUsb
import fr.f4ioz.satcombo.cat.SerialLink
import fr.f4ioz.satcombo.plateforme.Lignes
import fr.f4ioz.satcombo.plateforme.Ouverture
import fr.f4ioz.satcombo.plateforme.PortSerie
import fr.f4ioz.satcombo.plateforme.PortsSerie
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The PC's serial ports (`/dev/ttyUSB0`, `/dev/ttyACM0`, `COM3`…), through
 * jSerialComm.
 *
 * **Nothing is ever opened by itself**: a port opens only when the operator
 * picked it on the page for a rig or a mast. There is no permission to ask
 * on a PC (Linux: the user must be in the `dialout` group).
 *
 * **RTS and DTR low unless the driver asks** ([Lignes]): cleared before the
 * opening, so jSerialComm sets them low as soon as the port is open. On
 * Linux the system itself raises them for a few milliseconds while opening;
 * nothing can be done about that from here — which is why a port is opened
 * only on request, never tried at random.
 */
class PortsSeriePc : PortsSerie {

    /** The ports in a stable order: by path, so the indexes do not move between two lists. */
    private fun ports(): List<SerialPort> =
        runCatching { SerialPort.getCommPorts().toList() }.getOrDefault(emptyList())
            .sortedBy { it.systemPortPath }

    override fun liste(): List<PortSerie> = decrit(ports()).map { it.first }

    override fun autorise(p: PortSerie): Boolean = true
    override fun demande(p: PortSerie) {}
    override suspend fun demandeEtAttends(p: PortSerie): Boolean = true

    override suspend fun ouvre(p: PortSerie, baud: Int, lignes: Lignes, deuxArrets: Boolean): Ouverture = withContext(Dispatchers.IO) {
        val port = decrit(ports()).firstOrNull { it.first.cle == p.cle && it.first.port == p.port }?.second
            ?: return@withContext Ouverture(null, "port introuvable : ${p.libelle}")
        // Before opening: jSerialComm applies them as the port opens.
        if (lignes == Lignes.LEVEES) { port.setDTR(); port.setRTS() } else { port.clearDTR(); port.clearRTS() }
        port.setComPortParameters(baud, 8, if (deuxArrets) SerialPort.TWO_STOP_BITS else SerialPort.ONE_STOP_BIT,
            SerialPort.NO_PARITY)
        port.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED)
        if (!port.openPort()) {
            return@withContext Ouverture(null, "ouverture refusée : ${port.systemPortPath}" +
                if (System.getProperty("os.name").orEmpty().startsWith("Linux")) " (groupe dialout ?)" else "")
        }
        // Once more after the opening, as on the phone: some adapters forget them.
        if (lignes == Lignes.LEVEES) { port.setDTR(); port.setRTS() } else { port.clearDTR(); port.clearRTS() }
        Ouverture(LienPc(port), "")
    }

    companion object {
        /** The port's lasting name: its serial number when it has one, else its path. */
        fun cle(p: SerialPort): String {
            val serie = runCatching { p.serialNumber }.getOrNull()?.takeIf { it.isNotBlank() && it != "Unknown" }
            return if (serie != null) IdentiteUsb.cle(serie, p.vendorID.coerceAtLeast(0), p.productID.coerceAtLeast(0), p.systemPortPath)
            else p.systemPortPath
        }

        /**
         * The ports as the drivers count them: by adapter (an IC-9700 shows
         * two ports with one serial number), then port by port within it.
         */
        fun decrit(ports: List<SerialPort>): List<Pair<PortSerie, SerialPort>> {
            val cles = ports.map { cle(it) }
            val appareils = cles.distinct()
            return ports.mapIndexed { i, p ->
                val desc = runCatching { p.portDescription }.getOrNull().orEmpty()
                val c = cles[i]
                val freres = cles.withIndex().filter { it.value == c }.map { it.index }
                PortSerie(index = i, appareil = appareils.indexOf(c), port = freres.indexOf(i), nbPorts = freres.size,
                    libelle = p.systemPortPath + (if (desc.isNotBlank()) " — $desc" else ""),
                    cle = c, vendorId = p.vendorID, produit = desc.ifBlank { null },
                    serie = runCatching { p.serialNumber }.getOrNull()?.takeIf { it.isNotBlank() && it != "Unknown" },
                    chemin = p.systemPortPath) to p
            }
        }
    }
}

/** An open port of the PC, as the drivers see a serial line. */
class LienPc(private val port: SerialPort) : SerialLink {
    override fun write(bytes: ByteArray, timeoutMs: Int): Boolean {
        port.setComPortTimeouts(SerialPort.TIMEOUT_WRITE_BLOCKING, 0, timeoutMs)
        return port.writeBytes(bytes, bytes.size) == bytes.size
    }

    override fun read(buf: ByteArray, timeoutMs: Int): Int {
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, timeoutMs, 0)
        return port.readBytes(buf, buf.size).coerceAtLeast(0)
    }

    override fun close() { runCatching { port.closePort() } }
}
