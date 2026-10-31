/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.cat

import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.plateforme.Lignes
import fr.f4ioz.satcombo.plateforme.Ouverture
import fr.f4ioz.satcombo.plateforme.PortsSerie
import fr.f4ioz.satcombo.i18n.tf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * IC-9700 CI-V driver, behind a [SerialLink].
 *
 * Frame: `FE FE <rig> <controller> <cmd> [data…] FD`, rig at 0xA2 by default,
 * controller at 0xE0 by convention. Frequency is five little-endian BCD bytes;
 * the access tone is **big-endian** — that one difference cost whole SO-50 passes.
 *
 * [open] plugs in the real cable, [attach] an [Ic9700Sim] for bench tests.
 */
class CivController(private val ports: PortsSerie? = null) : RigDriver {

    /** Which Icom satellite rig: changes the satellite-mode command and what may be sent. */
    var modele: ModeleIcom = ModeleIcom.IC9700

    override val name: String get() = "${modele.libelle} (CI-V)"
    private var link: SerialLink? = null

    /**
     * One conversation at a time on the line.
     *
     * CI-V has delimiters and addresses, so it is sturdier than Yaesu, but a
     * question's `drain` can still discard a reply another coroutine awaits.
     * The Doppler loop writes while the TX-status poll asks twice a second;
     * without the lock one picks up the other's reply.
     */
    private val fil = kotlinx.coroutines.sync.Mutex()
    var radioAddr: Int = 0xA2
    var controllerAddr: Int = 0xE0

    /**
     * Gap between frames: 40 ms on a real CI-V bus, or the rig drops some;
     * zero on the bench.
     */
    var pacingMs: Long = 40L

    override val isOpen: Boolean get() = link != null

    companion object {
        /** The modes that have a data variant on the IC-9700: LSB-D, USB-D, FM-D. */
        val MODES_DONNEES = setOf(0x00, 0x01, 0x05)

        /**
         * Many CDC bridges (Icom's included) stay silent until the host
         * raises DTR/RTS: the port opens, writes succeed, nothing comes back.
         */
        private val LIGNES = Lignes.LEVEES
    }

    /** Plugs in any serial line — a real cable or a simulated rig. */
    fun attach(l: SerialLink) { link = l }

    /**
     * Reason for the last failure, in plain words, for the diagnostic screen.
     * Permission denied, device held by another app, missing port, silent rig:
     * four causes that used to share one "cannot open" message.
     */
    var lastError: String = ""

    /** When the rig last answered a read (ms; 0 = never): a link that writes but no longer hears shows here. */
    @Volatile var derniereReponseMs: Long = 0L
    /** When the link was opened (ms). */
    @Volatile var ouvertMs: Long = 0L
        private set

    /**
     * The last frame the rig refused (`FA`, NG), in words, and when (ms; 0
     * never): a refusal is an answer — the rig hears — but never a success.
     */
    @Volatile var dernierRefus: String = ""
        private set
    @Volatile var dernierRefusMs: Long = 0L
        private set
    /** Refusals since the link was opened. */
    @Volatile var refus: Int = 0
        private set

    private fun noteRefus(question: ByteArray) {
        refus++
        dernierRefus = CatDecode.describeCiv(question)
        dernierRefusMs = System.currentTimeMillis()
    }

    /**
     * All visible serial ports, device by device, flattened.
     * An IC-9700 alone gives two entries, not one.
     */
    fun availablePorts(): List<PortRef> =
        ports?.liste()?.map { PortRef(it.appareil, it.port, it.libelle) } ?: emptyList()

    /** Port [index]'s USB key and vendor id, or null. */
    /** Port [index] as the platform describes it (name, adapter, key), null when gone. */
    fun portSerie(index: Int): fr.f4ioz.satcombo.plateforme.PortSerie? = ports?.liste()?.getOrNull(index)

    fun identite(index: Int): Pair<String, Int>? =
        ports?.liste()?.getOrNull(index)?.let { it.cle to it.vendorId }

    /** Port labels, in selectable order. */
    fun availableDeviceNames(): List<String> = availablePorts().map { it.label }

    /** Request permission for the first recognized device (async; user prompt). */
    fun requestPermission() {
        val pp = ports ?: return
        pp.liste().firstOrNull()?.let { pp.demande(it) }
    }

    /**
     * Requests permission for port [index] **and waits for the user's answer**.
     *
     * Showing the dialog and opening the port straight away failed, since
     * permission wasn't granted yet; only a second attempt worked.
     */
    suspend fun ensurePermission(index: Int = 0): Boolean {
        val pp = ports ?: return false
        // Index is a port, but permission is per device: both IC-9700 ports
        // share one grant and one dialog.
        val p = pp.liste().getOrNull(index) ?: return false
        return pp.autorise(p) || pp.demandeEtAttends(p)
    }

    /** Open the first recognized device at the given baud rate. */
    override suspend fun open(baud: Int): Boolean = open(0, baud)

    /**
     * Opens port [index] (chosen in settings), not blindly the first.
     *
     * The first one found isn't necessarily the rig: an SDR dongle also shows
     * up as a serial adapter, and depending on plug order we opened it.
     */
    suspend fun open(index: Int, baud: Int): Boolean {
        val pp = ports ?: return false
        // Never two openings of one port: the first, left open, keeps its claim on the USB device.
        close()
        val liste = pp.liste()
        val p = liste.getOrNull(index) ?: liste.firstOrNull()
        if (p == null) { lastError = t("cat_err_no_device"); return false }
        return branche(pp.ouvre(p, baud, LIGNES))
    }

    /**
     * Opens the adapter with key [cle] (see [IdentiteUsb]), first port, 8N1.
     *
     * For a rig paired with an FT-817 on a hub: opening "port N" could land
     * on the FT-817's cable; the key names the Icom's own adapter.
     */
    suspend fun openParCle(cle: String?, baud: Int, port: Int = 0): Boolean {
        val pp = ports ?: return false
        close()
        val permis = pp.liste().filter { pp.autorise(it) }
        val choisie = IdentiteUsb.resout(cle, permis.map { it.cle }.distinct())
        if (choisie == null) { lastError = t("cat_err_no_device"); return false }
        val p = permis.firstOrNull { it.cle == choisie && it.port == port }
        if (p == null) { lastError = t("cat_err_no_port"); return false }
        return branche(pp.ouvre(p, baud, LIGNES))
    }

    /** Takes the line just opened, or keeps the reason it was not. */
    private fun branche(o: Ouverture): Boolean {
        val l = o.lien
        if (l == null) { lastError = o.erreur; return false }
        link = l
        lastError = ""
        ouvertMs = System.currentTimeMillis(); derniereReponseMs = ouvertMs
        refus = 0
        return true
    }

    /**
     * Opens exactly [p] at [baud], nothing else tried — not the first port
     * when [p] is gone: the CAT link opened again on the port it was on.
     *
     * **RTS and DTR low** unless [lignes] says otherwise: this is the
     * unattended reopening ([fr.f4ioz.satcombo.station.RepriseCat]), and an
     * Icom whose « USB SEND » / « USB Keying » menu is set to DTR or RTS
     * keys its transmitter when a line goes up. Nothing unattended may key a
     * rig: a bridge that stays silent with its lines low leaves the rig
     * silent, to be reconnected by hand.
     */
    suspend fun ouvrePort(p: fr.f4ioz.satcombo.plateforme.PortSerie, baud: Int, lignes: Lignes = Lignes.BASSES): Boolean {
        val pp = ports ?: return false
        close()
        return branche(pp.ouvre(p, baud, lignes))
    }

    /** Serial ports of the adapter with key [cle] (an Icom shows two). */
    fun nombrePorts(cle: String?): Int {
        val liste = ports?.liste() ?: return 0
        val choisie = IdentiteUsb.resout(cle, liste.map { it.cle }.distinct()) ?: return 0
        return liste.count { it.cle == choisie }
    }

    override fun close() {
        runCatching { link?.close() }
        link = null
        forgetBands()
    }

    /** Encode a frequency (Hz) into 5 little-endian BCD bytes for CI-V cmd 0x05. */
    private fun freqToBcd(hz: Long): ByteArray = CatDecode.freqToBcdLe(hz)

    private fun frame(cmd: Int, data: ByteArray = ByteArray(0)): ByteArray =
        byteArrayOf(0xFE.toByte(), 0xFE.toByte(), radioAddr.toByte(), controllerAddr.toByte(),
            cmd.toByte()) + data + byteArrayOf(CatDecode.END.toByte())

    /**
     * Gathers incoming bytes until [stop] is satisfied or [timeoutMs] elapses.
     *
     * Must loop: with a single read, a rig that sends an ACK before its reply
     * returned only the ACK and the reply was lost.
     */
    private fun collect(
        l: SerialLink, timeoutMs: Long, stop: (List<ByteArray>) -> Boolean
    ): List<ByteArray> {
        val acc = ArrayList<Byte>()
        val scratch = ByteArray(256)
        val deadline = System.currentTimeMillis() + timeoutMs
        var frames: List<ByteArray> = emptyList()
        while (true) {
            val n = runCatching { l.read(scratch, 100) }.getOrDefault(0)
            if (n > 0) {
                for (i in 0 until n) acc += scratch[i]
                frames = CatDecode.splitCiv(acc.toByteArray())
                if (stop(frames)) break
            } else if (pacingMs == 0L) {
                // On the bench nothing is in flight: an empty read means done.
                break
            }
            if (System.currentTimeMillis() >= deadline) break
        }
        frames.forEach { f ->
            CatJournal.log(false, f, if (CatDecode.isEcho(f, radioAddr, controllerAddr))
                "écho du bus : " + CatDecode.describeCiv(f) else CatDecode.describeCiv(f))
        }
        return frames
    }

    /** Discards leftover bytes before a question. */
    private fun drain(l: SerialLink) {
        val scratch = ByteArray(256)
        var guard = 0
        while (guard++ < 8) {
            val n = runCatching { l.read(scratch, 1) }.getOrDefault(0)
            if (n <= 0) break
        }
    }

    /**
     * Sends a frame and waits for the ACK.
     *
     * Returns false on NAK. When a refusal looked like success, the misencoded
     * access tone went unnoticed.
     */
    private suspend fun send(bytes: ByteArray): Boolean = withContext(Dispatchers.IO) { fil.withLock {
        val l = link ?: return@withLock false
        if (!l.write(bytes, 500)) return@withLock false
        CatJournal.log(true, bytes, CatDecode.describeCiv(bytes))
        // The rig needs a pause between frames, or it drops some.
        if (pacingMs > 0) kotlinx.coroutines.delay(pacingMs)
        val frames = collect(l, if (pacingMs > 0) 200L else 0L) { f ->
            CatDecode.isAck(f, radioAddr, controllerAddr) ||
                CatDecode.isNak(f, radioAddr, controllerAddr)
        }
        if (CatDecode.isAck(frames, radioAddr, controllerAddr) || CatDecode.isNak(frames, radioAddr, controllerAddr))
            derniereReponseMs = System.currentTimeMillis()
        val refuse = CatDecode.isNak(frames, radioAddr, controllerAddr)
        if (refuse) noteRefus(bytes)
        // A write counts as done unless refused: the rig may take it without being heard back.
        !refuse
    } }

    /**
     * Sends a frame and gathers incoming frames until the expected reply shows up.
     *
     * Two pitfalls: a single read loses a reply preceded by an ACK; and
     * searching raw bytes finds our own question, since the single-wire CI-V
     * bus always echoes it. Hence framing plus address filtering.
     *
     * @return all frames received, in order, echo included.
     */
    suspend fun exchange(
        cmd: Int, data: ByteArray = ByteArray(0),
        expect: Int = -1, expectSub: Int = -1, timeoutMs: Long = 600
    ): List<ByteArray> = withContext(Dispatchers.IO) { fil.withLock {
        val l = link ?: return@withLock emptyList()
        // Anything left over belongs to the previous question.
        drain(l)
        val out = frame(cmd, data)
        if (!l.write(out, 500)) return@withLock emptyList()
        CatJournal.log(true, out, CatDecode.describeCiv(out))

        val frames = collect(l, timeoutMs) { f ->
            if (expect >= 0)
                CatDecode.payload(f, radioAddr, controllerAddr, expect, expectSub) != null ||
                    CatDecode.isNak(f, radioAddr, controllerAddr)
            else
                CatDecode.isAck(f, radioAddr, controllerAddr) ||
                    CatDecode.isNak(f, radioAddr, controllerAddr)
        }
        // Any frame from the rig itself (answer, ack, refusal): it hears us.
        if (CatDecode.isAck(frames, radioAddr, controllerAddr) || CatDecode.isNak(frames, radioAddr, controllerAddr) ||
                (expect >= 0 && CatDecode.payload(frames, radioAddr, controllerAddr, expect, expectSub) != null))
            derniereReponseMs = System.currentTimeMillis()
        if (CatDecode.isNak(frames, radioAddr, controllerAddr)) noteRefus(out)
        if (pacingMs > 0) kotlinx.coroutines.delay(pacingMs)
        frames
    } }

    /** Send a frame and read whatever the radio answers (for diagnostics). */
    suspend fun sendAndRead(cmd: Int, data: ByteArray = ByteArray(0)): ByteArray? {
        if (link == null) return null
        val frames = exchange(cmd, data, expect = cmd)
        return frames.fold(ByteArray(0)) { a, b -> a + b }
    }

    /**
     * Test the link by reading the operating frequency (CI-V cmd 0x03).
     * Returns a human-readable result.
     */
    suspend fun testLink(): String {
        if (link == null) return t("civ_no_port")
        val frames = exchange(0x03, expect = 0x03)
        if (frames.isEmpty()) return t("civ_no_reply")
        val hex = frames.flatMap { f -> f.map { it } }.joinToString(" ") { "%02X".format(it) }
        val p = CatDecode.payload(frames, radioAddr, controllerAddr, 0x03)
        val hz = p?.let { CatDecode.bcdLeToFreq(it) }
        return if (hz != null) tf("civ_link_ok", "%.5f MHz".format(java.util.Locale.US, hz / 1e6))
        else tf("civ_unexpected", hex)
    }

    /**
     * Is the rig transmitting? `null` if unknown.
     *
     * CI-V 0x1C 0x00: 00 on RX, 01 on TX. The only way to know when the
     * operator keys via VOX — the app commands nothing then, it observes.
     */
    suspend fun isTransmitting(): Boolean? {
        if (link == null) return null
        val frames = exchange(0x1C, byteArrayOf(0x00), expect = 0x1C)
        val p = CatDecode.payload(frames, radioAddr, controllerAddr, 0x1C) ?: return null
        // Payload is "00 <state>": read the last byte.
        return p.lastOrNull()?.let { (it.toInt() and 0xFF) != 0 }
    }

    /** Set the operating frequency (Hz) on the currently selected VFO. */
    suspend fun setFrequency(hz: Long): Boolean = send(frame(0x05, freqToBcd(hz)))

    /** Select VFO A or B (cmd 0x07 00=A / 01=B). For same-band split layout. */
    suspend fun selectVfo(sub: Boolean): Boolean =
        send(frame(0x07, byteArrayOf(if (sub) 0x01 else 0x00)))

    /** Enable/disable split (cmd 0x0F 01=on / 00=off). For same-band V/V (ISS). */
    suspend fun setSplitOn(on: Boolean): Boolean =
        send(frame(0x0F, byteArrayOf(if (on) 0x01 else 0x00)))

    /**
     * Keys (true) or unkeys the transmitter: CI-V 0x1C 0x00 01/00. Used by
     * APRS transmission (one frame at a time, always unkeyed after) and by
     * the rig's PTT on SatMe-PC ([EmissionPoste], behind its guards).
     */
    suspend fun setTransmit(on: Boolean): Boolean =
        send(frame(0x1C, byteArrayOf(0x00, if (on) 0x01 else 0x00)))

    /** The RF power level of the band in use (CI-V 0x14 0x0A, 0 to 255), null when not answered. */
    suspend fun readRfPower(): Int? {
        if (link == null) return null
        val frames = exchange(0x14, byteArrayOf(0x0A), expect = 0x14, expectSub = 0x0A)
        return CatDecode.payload(frames, radioAddr, controllerAddr, 0x14, 0x0A)?.let { PuissanceRf.decode(it) }
    }

    /** Sets the RF power level of the band in use (0 to 255). Not a transmit command: it keys nothing. */
    suspend fun setRfPower(niveau: Int): Boolean =
        send(frame(0x14, byteArrayOf(0x0A) + PuissanceRf.encode(niveau)))

    /**
     * The RF power of the band that transmits: SUB in satellite mode
     * (selected for the reading, MAIN again after), else the band in use.
     */
    suspend fun readRfPowerTx(): Int? {
        val sat = modele.vfoDirect && readSatelliteMode() == true
        if (sat) selectMainSub(true)
        val v = readRfPower()
        if (sat) selectMainSub(false)
        return v
    }

    /** Sets the RF power of the band that transmits (see [readRfPowerTx]). */
    suspend fun setRfPowerTx(niveau: Int): Boolean {
        val sat = modele.vfoDirect && readSatelliteMode() == true
        if (sat) selectMainSub(true)
        val ok = setRfPower(niveau)
        if (sat) selectMainSub(false)
        return ok
    }

    /** Satellite mode on? (IC-9700 and IC-9100: 0x16 0x5A; IC-910: 0x1A 0x07), null when unknown. */
    suspend fun readSatelliteMode(): Boolean? {
        if (link == null) return null
        val c = modele.commandeSat; val s = modele.sousCommandeSat
        val frames = exchange(c, byteArrayOf(s.toByte()), expect = c, expectSub = s)
        val p = CatDecode.payload(frames, radioAddr, controllerAddr, c, s) ?: return null
        return p.lastOrNull()?.let { (it.toInt() and 0xFF) == 1 }
    }

    /** Enable/disable satellite mode, 01 = on / 00 = off, with the model's command. */
    suspend fun setSatelliteMode(on: Boolean): Boolean =
        send(frame(modele.commandeSat, byteArrayOf(modele.sousCommandeSat.toByte(), if (on) 0x01 else 0x00)))

    /** Select MAIN or SUB band in satellite mode (cmd 0x07 0xD0=main / 0xD1=sub). */
    suspend fun selectMainSub(sub: Boolean): Boolean {
        onSub = sub
        return send(frame(0x07, byteArrayOf(if (sub) 0xD1.toByte() else 0xD0.toByte())))
    }

    /** Set operating mode on current VFO. cmd 0x06 <mode> <filter>.
     *  modes: 0x00 LSB, 0x01 USB, 0x02 AM, 0x03 CW, 0x05 FM, 0x07 CW-R, 0x08 USB-D… */
    suspend fun setMode(mode: Int, filter: Int = 0x01): Boolean =
        send(frame(0x06, byteArrayOf(mode.toByte(), filter.toByte())))

    /**
     * Data mode (USB-D, LSB-D, FM-D) of the **selected** band or VFO: CI-V
     * `1A 06 <data> <filter>`, data 00 = off / 01 = on, filter 01..03 =
     * FIL1..FIL3, and 00 with data off (IC-9700 CI-V Reference Guide, "Data
     * mode with filter width settings", p. 17). In data mode the IC-9700
     * takes its TX audio from *DATA MOD* (MENU › SET › Connectors › MOD
     * Input) instead of *DATA OFF MOD* — the way WSJT-X drives it.
     *
     * Not `26 <vfo> <mode> <data> <filter>`: the guide (p. 23) says 0x26 works
     * on MAIN only and returns FA (NG) in satellite mode — the uplink could
     * not be reached. So: select the band (07 D0/D1), then 06 for the mode and
     * 1A 06 for the data flag. **06 comes first**: a mode written with 06
     * may come back with data off (Hamlib sends 1A 06 after 06 for that).
     */
    suspend fun setDataMode(on: Boolean, filter: Int = 0x01): Boolean =
        modele.donnees && send(frame(0x1A, byteArrayOf(0x06, if (on) 0x01 else 0x00, if (on) filter.toByte() else 0x00)))

    /** The selected band's data mode (1A 06), null when not answered or not an IC-9700. */
    suspend fun readDataMode(): Boolean? {
        if (link == null || !modele.donnees) return null
        val frames = exchange(0x1A, byteArrayOf(0x06), expect = 0x1A, expectSub = 0x06)
        val p = CatDecode.payload(frames, radioAddr, controllerAddr, 0x1A, 0x06) ?: return null
        return p.firstOrNull()?.let { (it.toInt() and 0xFF) != 0 }
    }

    /**
     * Mode, then data flag when [data] (see [setDataMode]): what the Doppler
     * loop sends while FT8/FT4 wants USB-D, so its own mode writes do not
     * undo the data flag. [data] false sends 06 alone, as before.
     */
    suspend fun setModeData(mode: Int, data: Boolean): Boolean {
        val ok = setMode(mode)
        return if (data && modele.donnees && mode in MODES_DONNEES) setDataMode(true) && ok else ok
    }

    /**
     * Reads the current VFO frequency (Hz), or null. The frame must come from
     * the rig, carry command 0x03, and hold a plausible frequency.
     */
    suspend fun readFrequency(): Long? {
        if (link == null) return null
        val frames = exchange(0x03, expect = 0x03)
        val p = CatDecode.payload(frames, radioAddr, controllerAddr, 0x03) ?: return null
        return CatDecode.bcdLeToFreq(p)
    }

    /**
     * The S-meter (CI-V 0x15 0x02): 0 = S0, 120 = S9, 241 = S9+60 dB; null if
     * not answered. Read only; on the main band (the downlink in satellite mode).
     */
    suspend fun readSMeter(): Int? {
        if (link == null) return null
        val frames = exchange(0x15, byteArrayOf(0x02), expect = 0x15, expectSub = 0x02)
        return CatDecode.niveauMetre(CatDecode.payload(frames, radioAddr, controllerAddr, 0x15, 0x02))
    }

    /**
     * Reads the current VFO mode (0x00 LSB, 0x01 USB, 0x05 FM…), or null.
     * Reply is `04 <mode> <filter>`. Must be read back: the mode can also be
     * changed by hand.
     */
    suspend fun readMode(): Int? {
        if (link == null) return null
        val frames = exchange(0x04, expect = 0x04)
        val p = CatDecode.payload(frames, radioAddr, controllerAddr, 0x04) ?: return null
        return if (p.isEmpty()) null else p[0].toInt() and 0xFF
    }

    /**
     * Read the SELECTED (00) or UNSELECTED (01) VFO frequency via cmd 0x25.
     * This does NOT change which VFO is active (unlike 0x03 + band select).
     */
    suspend fun readVfoFreq(unselected: Boolean): Long? {
        if (link == null || !modele.vfoDirect) return null
        val sub = if (unselected) 0x01 else 0x00
        val frames = exchange(0x25, byteArrayOf(sub.toByte()), expect = 0x25, expectSub = sub)
        val p = CatDecode.payload(frames, radioAddr, controllerAddr, 0x25, sub) ?: return null
        return CatDecode.bcdLeToFreq(p)
    }

    /**
     * Set the SELECTED (00) or UNSELECTED (01) VFO frequency via cmd 0x25,
     * WITHOUT changing the active band/VFO. In IC-9700 satellite mode the
     * unselected VFO is the uplink (SUB) — so we tune it without swapping.
     * Caveat: in cross-band satellite mode the rig refuses 0x25/0x26 on the
     * unselected VFO — see [setSatellitePair].
     */
    suspend fun setVfoFreq(hz: Long, unselected: Boolean): Boolean =
        modele.vfoDirect && send(frame(0x25, byteArrayOf(if (unselected) 0x01 else 0x00) + freqToBcd(hz)))

    /** Set the SELECTED/UNSELECTED VFO mode via cmd 0x26 (no band swap). */
    suspend fun setVfoMode(mode: Int, unselected: Boolean, filter: Int = 0x01, dataMode: Int = 0x00): Boolean =
        modele.vfoDirect && send(frame(0x26, byteArrayOf(if (unselected) 0x01 else 0x00, mode.toByte(), dataMode.toByte(), filter.toByte())))

    /** Enable/disable repeater tone (CTCSS) on TX. cmd 0x16 0x42. */
    suspend fun setToneOn(on: Boolean): Boolean =
        send(frame(0x16, byteArrayOf(0x42, if (on) 0x01 else 0x00)))

    /**
     * Sets the access tone (670 = 67.0 Hz). Command 0x1B 0x00, three
     * **big-endian** BCD bytes: 88.5 Hz gives `00 08 85`.
     *
     * Encoding it like a frequency gives `00 88 50` = 885.0 Hz: out of range,
     * ignored, yet ACKed. SO-50 never opened and the fault was sought in power.
     */
    suspend fun setToneFreq(tenthHz: Int): Boolean {
        if (!CatDecode.toneInRange(tenthHz)) return false
        return send(frame(0x1B, byteArrayOf(0x00) + CatDecode.toneToBcdBe(tenthHz)))
    }

    /** Reads back the access tone set in the rig, in tenths of Hz. */
    suspend fun readToneFreq(): Int? {
        if (link == null) return null
        val frames = exchange(0x1B, byteArrayOf(0x00), expect = 0x1B, expectSub = 0x00)
        val p = CatDecode.payload(frames, radioAddr, controllerAddr, 0x1B, 0x00) ?: return null
        return CatDecode.bcdBeToTone(p)
    }

    private var onSub = false  // tracks which band is currently selected

    /**
     * What the rig shows, as far as we know.
     *
     * Updated on each successful write, reset to null when no longer reliable
     * (satellite change, unplug). Used only to choose the write order of the
     * satellite pair. Read from the rig once, then tracked from our own
     * commands — reading two frequencies ten times a second would clog the bus.
     */
    private var knownMain: Long? = null
    private var knownSub: Long? = null
    private var bandesLues = false

    /**
     * Forget the assumed rig state. Call on satellite change and on close —
     * the moments when someone else may have touched the VFOs.
     */
    fun forgetBands() { knownMain = null; knownSub = null; bandesLues = false }

    /** Read frequency of MAIN (downlink) = the selected band in sat mode. */
    suspend fun readMainFrequency(): Long? {
        if (onSub) { selectMainSub(false) }
        return readFrequency()
    }

    /** Read frequency of SUB (uplink) band, then return to MAIN. */
    suspend fun readSubFrequency(): Long? {
        selectMainSub(true)
        val f = readFrequency()
        selectMainSub(false)
        return f
    }

    /**
     * Downlink on MAIN, uplink on SUB, in satellite mode.
     *
     * 0x25/0x26 can't reach SUB on an IC-9700 in cross-band satellite mode:
     * select the band (0x07 D0/D1), then write with 0x05.
     *
     * **Order matters**: the rig refuses both bands on the same band, and going
     * V/U → U/V is a pure swap, so any fixed order gets a silent NAK. [BandPlan]
     * picks the order from the rig state, parking the uplink on a third band if
     * needed. Always ends on MAIN so the RX dial stays with the operator.
     */
    suspend fun setSatellitePair(downlinkHz: Long, uplinkHz: Long) {
        // First pair of a pass: read the rig once; afterwards our own commands suffice.
        if (!bandesLues) {
            // Only once: a silent rig must not make us re-read ten times a
            // second. With no answer we fall back to the usual order.
            bandesLues = true
            knownSub = readSubFrequency()
            knownMain = readMainFrequency()
        }
        val etapes = BandPlan.steps(knownMain, knownSub, downlinkHz, uplinkHz)
        for (e in etapes) {
            selectMainSub(e.sub)
            if (setFrequency(e.hz)) {
                if (e.sub) knownSub = e.hz else knownMain = e.hz
            } else {
                // A refusal means the rig isn't where we thought: re-read it
                // next round rather than dig deeper.
                forgetBands()
            }
        }
        if (onSub) selectMainSub(false)
    }

    /** Set only the uplink (SUB), then return to MAIN for RX. */
    override suspend fun setUplink(uplinkHz: Long) {
        selectMainSub(true)
        setFrequency(uplinkHz)
        selectMainSub(false)
    }

    // ---- Same-band (V/V, e.g. ISS FM) layout: satellite mode OFF, split ON,
    //      RX on VFO A, TX on VFO B. ----

    /** Arm same-band: sat mode off, split on. Call once at pass init. */
    suspend fun armSameBandSplit() {
        setSatelliteMode(false)
        selectVfo(false)        // VFO A = RX
        setSplitOn(true)
    }

    /** Read VFO A (RX) frequency in same-band split. */
    suspend fun readVfoAFrequency(): Long? {
        selectVfo(false)
        return readFrequency()
    }

    /** Same-band pair: RX on VFO A, TX on VFO B (split). Finish on A. */
    suspend fun setSplitPair(downlinkHz: Long, uplinkHz: Long) {
        selectVfo(true)         // VFO B = TX
        setFrequency(uplinkHz)
        selectVfo(false)        // back to VFO A = RX
        setFrequency(downlinkHz)
    }

    /** Same-band: set only the TX (VFO B), return to RX (VFO A). */
    suspend fun setSplitUplink(uplinkHz: Long) {
        selectVfo(true)
        setFrequency(uplinkHz)
        selectVfo(false)
    }

    // ---- RigDriver interface mappings ----
    override suspend fun enterSatelliteMode() { setSatelliteMode(true) }
    override suspend fun setModes(downlink: String, uplink: String) {
        // 0x06 sets the mode on the currently selected band; select each in turn.
        selectMainSub(true);  setMode(modeByte(uplink))
        selectMainSub(false); setMode(modeByte(downlink))
    }
    override suspend fun readDownlink(): Long? = readMainFrequency()
    override suspend fun setPair(downlinkHz: Long, uplinkHz: Long) = setSatellitePair(downlinkHz, uplinkHz)
    override suspend fun setCtcss(tenthHz: Int) {
        if (tenthHz > 0) { setToneOn(true); setToneFreq(tenthHz) } else setToneOn(false)
    }

    private fun modeByte(m: String): Int = modeCiv(m)
}

/** CI-V mode byte for a mode name (0x00 LSB, 0x01 USB, 0x02 AM, 0x03 CW, 0x05 FM). */
fun modeCiv(m: String): Int = when (m.uppercase()) {
    "LSB" -> 0x00; "USB" -> 0x01; "AM" -> 0x02; "CW" -> 0x03; "FM" -> 0x05
    else -> 0x01
}

/**
 * The Icom satellite rigs SatMe drives over CI-V, and what differs between
 * them. Same frames, same MAIN/SUB selection (0x07 D0/D1), same tone; but:
 *
 *  - **address** by default: A2, 7C, 60;
 *  - **speed**: the IC-9700's USB goes to 115 200, the IC-9100's USB and the
 *    IC-910's CI-V jack stop at 19 200;
 *  - **satellite mode**: 0x16 0x5A, except on the IC-910, which only knows
 *    0x1A 0x07 (and refuses the other);
 *  - **0x25/0x26** (unselected VFO): IC-9700 only;
 *  - **data mode** (1A 06, USB-D): driven on the IC-9700 only. The IC-910H
 *    has none; the IC-9100's is left to the operator (SatMe keys neither).
 */
enum class ModeleIcom(val id: String, val libelle: String, val adresse: Int, val baud: Int,
                      val commandeSat: Int, val sousCommandeSat: Int, val vfoDirect: Boolean,
                      /** Data mode (USB-D…) switched by SatMe (1A 06): the IC-9700 only, the one SatMe keys for FT8/FT4. */
                      val donnees: Boolean = false) {
    IC9700("IC9700", "Icom IC-9700", 0xA2, 115_200, 0x16, 0x5A, true, true),
    IC9100("IC9100", "Icom IC-9100", 0x7C, 19_200, 0x16, 0x5A, false),
    IC910("IC910", "Icom IC-910H", 0x60, 19_200, 0x1A, 0x07, false);

    companion object {
        /** The model of a rig setting ("IC910"…); anything else is driven as an IC-9700. */
        fun de(id: String): ModeleIcom = entries.firstOrNull { it.id == id } ?: IC9700
        val IDS: Set<String> = entries.map { it.id }.toSet()
    }
}
