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
 * A radio with a KISS TNC on USB — Kenwood TH-D72 (its USB port is a serial
 * line), TH-D74/D75, TM-D710, or a TNC like the Mobilinkd. Separate from the
 * CAT link: the IC-9700 can stay on CAT while a TH-D72 hangs off the hub.
 *
 * Frames the radio receives come in already decoded (its own modem) and join
 * the APRS page like the others. A frame to send goes out as one KISS frame;
 * the radio keys, modulates and returns to receive by itself.
 */
object TncKiss {

    data class Etat(
        val connecte: Boolean = false,
        /** The adapter as shown ("TH-D72 · /dev/bus/usb/…"). */
        val nom: String = "",
        val vitesse: Int = 9600,
        /** "KISS ON / RESTART" sent since connecting. */
        val initialise: Boolean = false,
        val recues: Int = 0,
        val envoyees: Int = 0,
        val erreur: String? = null,
        /** Read over the radio's PC commands before KISS: frequency (Hz) and band (0 = A, 1 = B). */
        val frequenceHz: Long? = null,
        val bande: Int? = null,
    )

    private val _etat = MutableStateFlow(Etat())
    val etat: StateFlow<Etat> = _etat.asStateFlow()

    /** A USB serial adapter: its label and its stable key. */
    data class Appareil(val nom: String, val cle: String)

    @Volatile private var lien: SerialLink? = null
    private var lecteur: Thread? = null

    /** Also told of each frame received (tests, diagnostics). */
    @Volatile var observateur: ((Trame) -> Unit)? = null

    /** What the radio said in text (PC commands, TNC prompts), for the switch to KISS. */
    private val texte = StringBuilder()

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

    private fun echec(raison: String): Boolean {
        _etat.value = _etat.value.copy(connecte = false, erreur = raison)
        return false
    }

    /** Plugs a serial line in (the real cable, or a test's fake) and starts reading. */
    fun attache(ctx: Context?, l: SerialLink, nom: String, vitesse: Int) {
        lien = l
        _etat.value = Etat(connecte = true, nom = nom, vitesse = vitesse)
        val decodeur = KissDecodeur { octets ->
            val t = Ax25.decodeSansFcs(octets) ?: return@KissDecodeur
            _etat.value = _etat.value.copy(recues = _etat.value.recues + 1)
            observateur?.invoke(t)
            if (ctx != null) AprsHub.recuDuTnc(ctx, t)
        }
        lecteur = Thread({
            val buf = ByteArray(512)
            while (lien === l) {
                val n = runCatching { l.read(buf, 200) }.getOrElse {
                    if (lien === l) _etat.value = _etat.value.copy(connecte = false, erreur = "lecture")
                    lien = null
                    -1
                }
                if (n > 0) {
                    synchronized(texte) {
                        for (i in 0 until n) texte.append((buf[i].toInt() and 0xFF).toChar())
                        if (texte.length > 4096) texte.delete(0, texte.length - 2048)
                    }
                    runCatching { decodeur.octets(buf, n) }
                }
            }
        }, "TncKiss").apply { isDaemon = true; start() }
    }

    /** Sends [c] and waits until the radio's text answer satisfies [fini], or [ms] pass. */
    private fun commande(l: SerialLink, c: String, ms: Long, fini: (String) -> Boolean): String? {
        synchronized(texte) { texte.setLength(0) }
        if (!l.write(c.toByteArray(Charsets.US_ASCII), 1000)) return null
        val fin = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < fin) {
            val s = synchronized(texte) { texte.toString() }
            if (fini(s)) return s
            Thread.sleep(40)
        }
        return null
    }

    /**
     * Puts the radio in KISS, whatever state it is in (checked on a TH-D72):
     * - normal mode (a CR gets "?"): reads the band in use (BC) and its
     *   frequency (FO) — and tunes it to [frequenceHz] for APRS (FM,
     *   simplex, no tone) when given — starts the TNC in packet mode on that
     *   band (TN 2,b), then KISS ON / RESTART;
     * - TNC in packet mode ("cmd:"): KISS ON / RESTART;
     * - silence: already in KISS.
     * Nothing here makes the radio transmit.
     */
    suspend fun passeEnKiss(frequenceHz: Long? = null): Boolean = withContext(Dispatchers.IO) {
        val l = lien ?: return@withContext false
        val r = commande(l, "\r", 1500) { it.contains("?") || it.contains("cmd:") }
        if (r != null && !r.contains("cmd:")) {
            val bande = commande(l, "BC\r", 1000) { it.contains("BC ") }
                ?.let { Regex("BC (\\d)").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0
            // The whole line, up to its CR: the radio's answer may come in several pieces.
            fun ligneFo(t: String) = Regex("FO [^\r]*\r").find(t)?.value?.trim()
            val fo = commande(l, "FO $bande\r", 1000) { ligneFo(it) != null }?.let(::ligneFo)
            var hz = fo?.let { Kiss.frequenceFo(it) }
            val champs = fr.f4ioz.satcombo.cat.Thd72.champs(fo, bande)
            if (frequenceHz != null && champs != null && hz != frequenceHz) {
                val cmd = fr.f4ioz.satcombo.cat.Thd72.commande(fr.f4ioz.satcombo.cat.Thd72.pourAprs(champs, frequenceHz))
                // The radio answers the new state, or "N" when it refuses.
                commande(l, cmd + "\r", 1500) { ligneFo(it) != null || it.contains("N\r") }
                    ?.let(::ligneFo)?.let { Kiss.frequenceFo(it) }?.let { hz = it }
            }
            _etat.value = _etat.value.copy(frequenceHz = hz, bande = bande)
            if (commande(l, "TN 2,$bande\r", 6000) { it.contains("cmd:") } == null) {
                _etat.value = _etat.value.copy(erreur = "tnc")
                return@withContext false
            }
        }
        if (r != null) {
            if (commande(l, Kiss.ENTREE_KENWOOD, 4000) { it.contains("RESTART") } == null) {
                _etat.value = _etat.value.copy(erreur = "kiss")
                return@withContext false
            }
        }
        _etat.value = _etat.value.copy(initialise = true, erreur = null)
        true
    }

    /** One frame to the radio, which transmits it. */
    suspend fun envoie(t: Trame): Boolean = withContext(Dispatchers.IO) {
        val l = lien ?: return@withContext false
        val ok = l.write(Kiss.trame(Ax25.encodeSansFcs(t)), 1000)
        if (ok) _etat.value = _etat.value.copy(envoyees = _etat.value.envoyees + 1)
        ok
    }

    /**
     * Back to the radio's normal mode (checked on a TH-D72): leaves KISS (the
     * TNC returns to its "cmd:" prompt), gives control back to the radio
     * ("TC 1"), switches the TNC off ("TN 0,b"). About a second, blocking.
     */
    private fun quitteKiss(l: SerialLink) {
        runCatching {
            l.write(Kiss.SORTIE, 500)
            Thread.sleep(400)
            l.write("\rTC 1\r".toByteArray(Charsets.US_ASCII), 500)
            Thread.sleep(400)
            l.write("TN 0,${_etat.value.bande ?: 0}\r".toByteArray(Charsets.US_ASCII), 500)
            Thread.sleep(300)
        }
        _etat.value = _etat.value.copy(initialise = false)
    }

    /**
     * Moves a Kenwood radio already in KISS to [hz]: out of KISS, tuned, back
     * in. Some 5 seconds without reception. Only for a radio whose frequency
     * was read (a Kenwood answering "FO"): another TNC is left alone.
     */
    suspend fun regleFrequence(hz: Long): Boolean = withContext(Dispatchers.IO) {
        val l = lien ?: return@withContext false
        if (_etat.value.frequenceHz == null) return@withContext false
        if (_etat.value.frequenceHz == hz && _etat.value.initialise) return@withContext true
        quitteKiss(l)
        passeEnKiss(hz) && _etat.value.frequenceHz == hz
    }

    /**
     * Closes the line. By default puts the radio back as it was ([quitteKiss]).
     * Blocking for about a second: call it off the main thread.
     */
    fun deconnecte(sortirDuKiss: Boolean = true) {
        val l = lien ?: return
        if (sortirDuKiss) quitteKiss(l)
        lien = null
        runCatching { l.close() }
        lecteur = null
        _etat.value = _etat.value.copy(connecte = false, initialise = false)
    }
}
