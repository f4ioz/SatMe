/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.rotor

import fr.f4ioz.satcombo.cat.CatJournal
import fr.f4ioz.satcombo.cat.CatScan
import fr.f4ioz.satcombo.cat.PortRef
import fr.f4ioz.satcombo.cat.SerialLink
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.i18n.tf
import fr.f4ioz.satcombo.plateforme.Lignes
import fr.f4ioz.satcombo.plateforme.PortsSerie
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * The GS-232 dialect: Yaesu controllers and everything that copied them.
 *
 * ASCII over serial, CR-terminated: `W180 045` to aim, `C2` to ask the
 * position, `S` to stop. No checksum, no acknowledgement, no length. The
 * controller never says "not understood": it does nothing, and a mast that
 * has not finished turning looks exactly like one that never got the order.
 *
 * **Three digits, always.** The controller reads fixed columns. `W180 45` is
 * not "almost right", it is a 450° elevation read from a shifted field. Zero
 * padded, in [Locale.US], and anything that does not fit is refused.
 *
 * **Two reply forms.** GS-232B answers `AZ=180 EL=045`, GS-232A `+0180+0045`,
 * and the same box can switch on an internal jumper. Both are parsed with no
 * setting.
 *
 * Parse what controllers actually send, not the manual: Arduino emulators,
 * half the installed base today, write `AZ=155EL=016` without the space.
 */
object Gs232Codec {

    /** Position query, long form (azimuth AND elevation). */
    const val QUERY = "C2\r"

    /** Immediate stop of all motors. */
    const val STOP = "S\r"

    /**
     * The `Waaa eee` command, or null if it does not fit the format.
     *
     * Null rather than truncated, as in [RotorMath]: a malformed command does
     * not return an error, it turns the mast somewhere else.
     */
    fun moveCommand(azDeg: Double, elDeg: Double): String? {
        if (azDeg.isNaN() || elDeg.isNaN()) return null
        val az = azDeg.roundToInt()
        val el = elDeg.roundToInt()
        if (az < 0 || az > 999 || el < 0 || el > 999) return null
        return String.format(Locale.US, "W%03d %03d\r", az, el)
    }

    // The separator between the two fields is **optional**. The most common
    // Arduino emulator writes `AZ=155EL=016`; the old `\D+` required a char
    // before `EL` and rejected a correct reply as "unreadable". `=` is
    // optional too — some sketches write `AZ 155 EL 016`.
    private val B_FORM = Regex(
        """AZ\s*[=:]?\s*([+-]?\d+(?:\.\d+)?)\s*\D*?EL\s*[=:]?\s*([+-]?\d+(?:\.\d+)?)""",
        RegexOption.IGNORE_CASE)
    private val A_FORM = Regex("""([+-]\d{3,4})\s*([+-]\d{3,4})""")

    /**
     * Parses a position in either form.
     *
     * Null when it is neither — an early fragment, a command echo, line noise.
     * The caller retries next second; it must never assume azimuth zero.
     */
    fun parsePosition(reply: String): RotorPos? {
        B_FORM.find(reply)?.let { m ->
            val az = m.groupValues[1].toDoubleOrNull() ?: return@let
            val el = m.groupValues[2].toDoubleOrNull() ?: return@let
            return RotorPos(az, el)
        }
        A_FORM.find(reply)?.let { m ->
            val az = m.groupValues[1].toDoubleOrNull() ?: return@let
            val el = m.groupValues[2].toDoubleOrNull() ?: return@let
            return RotorPos(az, el)
        }
        return null
    }

    /**
     * The controller's own calibration, GS-232A style, as the K3NG Arduino
     * firmware implements it (`process_yaesu_command`, rotator_settings.h):
     *
     * - `O`  — azimuth **full CCW**: the pot reading now becomes the start of
     *   the azimuth range (`analog_az_full_ccw`).
     * - `F`  — azimuth **full CW**: the reading now becomes the end of the
     *   range (`analog_az_full_cw`).
     * - `O2` — elevation **0°** (`analog_el_0_degrees`).
     * - `F2` — elevation **top**: the reading now becomes the firmware's
     *   `ELEVATION_MAXIMUM_DEGREES` (180 by default) — not "the angle you
     *   are at": F2 at a real 160° makes 160 read as 180.
     *
     * Between the two the firmware maps linearly (`float_map`). Each one
     * prompts ("Elevate to 0 degrees and send keystroke..."), **blocks until
     * any byte comes**, then writes the EEPROM at once and answers
     * "Wrote to memory". Only built with a pot sensor
     * (FEATURE_AZ_POSITION_POTENTIOMETER); otherwise, as for anything it
     * does not know, `?>`. Ignored altogether in the first 5 s after boot
     * (ROTATIONAL_AND_CONFIGURATION_CMD_IGNORE_TIME_MS) — and opening the port
     * reboots an Arduino.
     */
    enum class Etalonnage(val commande: String) {
        AZ_ZERO("O"), AZ_PLEIN("F"), EL_ZERO("O2"), EL_PLEIN("F2");

        companion object {
            fun de(s: String?): Etalonnage? = entries.firstOrNull { it.commande.equals(s?.trim(), ignoreCase = true) }
        }
    }

    /** The firmware asks for a keystroke before reading the pot. */
    fun estInvite(ligne: String): Boolean = ligne.contains("keystroke", ignoreCase = true)

    /** The calibration is in the EEPROM. */
    fun estEcrit(ligne: String): Boolean = ligne.contains("wrote to memory", ignoreCase = true)

    /** "Not understood": GS-232 and K3NG answer `?>`. */
    fun estInconnu(ligne: String): Boolean = ligne.trim().startsWith("?>")

    /** The same frame in plain French, for the log. */
    fun describe(text: String, out: Boolean): String {
        val t = text.trim()
        if (out) {
            val m = Regex("""^W\s*(\d{3})\s+(\d{3})$""", RegexOption.IGNORE_CASE).find(t)
            if (m != null) return "consigne → azimut " + m.groupValues[1].toInt() +
                "°, élévation " + m.groupValues[2].toInt() + "°"
            Etalonnage.de(t)?.let { e -> return when (e) {
                Etalonnage.AZ_ZERO -> "étalonnage : azimut en butée gauche (CCW)"
                Etalonnage.AZ_PLEIN -> "étalonnage : azimut en butée droite (CW)"
                Etalonnage.EL_ZERO -> "étalonnage : élévation à 0°"
                Etalonnage.EL_PLEIN -> "étalonnage : élévation en haut"
            } }
            if (t.isEmpty()) return "touche (suite de l'étalonnage)"
            if (t.startsWith("C")) return "demande de position"
            if (t.startsWith("S")) return "arrêt immédiat"
            return "→ $t"
        }
        val p = parsePosition(t)
        return if (p != null) String.format(Locale.US,
            "position : azimut %.0f°, élévation %.0f°", p.azDeg, p.elDeg)
        else "réponse : $t"
    }
}

/**
 * GS-232 driver over any serial link — a real USB cable or a simulator.
 *
 * The USB adapter is chosen **by index**, on purpose: a full station often has
 * two identical adapters, one to the rig, one to the rotator. Taking "the
 * first" eventually sends `W180 045` to an IC-9700.
 */
class Gs232Rotor(private val ports: PortsSerie? = null) : RotorDriver {

    private var link: SerialLink? = null
    override val isOpen: Boolean get() = link != null

    /** Gap between frames. Zero on the bench. */
    var pacingMs: Long = 20L

    /**
     * Time left for the board to come back after opening.
     *
     * On Arduino, DTR is tied to RESET through a capacitor: opening the port
     * and raising DTR **reboots the microcontroller**. For about 1.5 s the
     * sketch is not running and anything sent is lost. Misleading symptom:
     * "open, but the controller does not answer" — port, cable and baud rate
     * are fine, the `C2` just came too early. So we wait, and ask several
     * times.
     *
     * Zero on the bench, where there is no board.
     */
    var settleMs: Long = 1500L

    /** Attaches an already open serial link — a cable or a simulator. */
    fun attach(l: SerialLink) { link = l }

    /**
     * Reason for the last failure: permission, device busy, no port, silent
     * box — four causes, four messages, as on the rig side.
     */
    var lastError: String = ""
        private set

    /** Last frame received, raw, to show the operator. */
    var lastReply: String = ""
        private set

    /** Last frame sent, raw. */
    var lastSent: String = ""
        private set

    /**
     * Every visible serial port, flattened across devices. A dual-channel
     * adapter has two ports and the rotator is not always on port 0.
     */
    fun availablePorts(): List<PortRef> =
        ports?.liste()?.map { PortRef(it.appareil, it.port, it.libelle) } ?: emptyList()

    /** The key and product name of port [index]'s adapter (the rotor search checks it is not a rig's). */
    fun identitePort(index: Int): Pair<String, String?>? =
        ports?.liste()?.getOrNull(index)?.let { it.cle to it.produit }

    /**
     * Requests permission for port [index] **and waits for the answer**.
     *
     * Opening right after showing the dialog fails: `hasPermission` is still
     * false, and the user had to try again once the dialog closed. Same bug as
     * the one that made the rig unreachable before 18.20.
     */
    suspend fun ensurePermission(index: Int): Boolean {
        val pp = ports ?: return false
        val p = pp.liste().getOrNull(index) ?: return false
        return pp.autorise(p) || pp.demandeEtAttends(p)
    }

    /**
     * Opens port [index] at [baud] baud, 8N1. GS-232 ships at 9600 but jumpers
     * allow 1200/2400/4800, so the operator chooses.
     *
     * RTS/DTR raised. An Arduino resets on DTR rising, but many sketches stay
     * silent until the host has asserted them: without this the port opens,
     * the command goes out, and nothing ever comes back.
     */
    suspend fun open(index: Int, baud: Int): Boolean {
        val pp = ports ?: return false
        val liste = pp.liste()
        val p = liste.getOrNull(index) ?: liste.firstOrNull()
        if (p == null) { lastError = t("cat_err_no_device"); return false }
        val o = pp.ouvre(p, baud, Lignes.LEVEES)
        val l = o.lien
        if (l == null) { lastError = o.erreur; return false }
        // The board may have just rebooted: let it finish booting, then
        // discard what the bootloader spat out (banner, zeros, echo) so
        // the first line read really answers our question.
        if (settleMs > 0L) withContext(Dispatchers.IO) {
            delay(settleMs)
            vide(l)
        }
        link = l
        cleUsb = p.cle
        ouvertMs = System.currentTimeMillis()
        lastError = ""
        return true
    }

    override fun close() {
        runCatching { link?.close() }
        link = null
        cleUsb = null
    }

    /**
     * The adapter open now (its key, as the rig side names adapters): a rig
     * search must not send its questions there — "D" moves a GS-232 down.
     */
    @Volatile var cleUsb: String? = null
        private set

    /** Recognised ports, in index order. */
    fun listDevices(): List<String> = availablePorts().map { it.label }

    /** Requests permission for every device lacking it, without waiting. */
    fun requestPermissions() {
        val pp = ports ?: return
        pp.liste().distinctBy { it.appareil }.forEach { pp.demande(it) }
    }

    private fun send(l: SerialLink, s: String): Boolean {
        val ok = l.write(s.toByteArray(Charsets.US_ASCII), 500)
        lastSent = s.trim()
        CatJournal.log(true, s.toByteArray(Charsets.US_ASCII), Gs232Codec.describe(s, out = true))
        return ok
    }

    /**
     * Reads a full line, assembling it chunk by chunk.
     *
     * At 9600 baud `AZ=180 EL=045` takes about 12 ms and rarely arrives in one
     * read; a single read caught `AZ=1` and reported "no reply" (the same
     * mistake once made on the CAT side).
     *
     * **A terminated line is not necessarily the reply.** The emulator echoes
     * the previous `W155 016`, CR-terminated, before the position. Returning
     * that echo as the `C2` reply made the position vanish and the compass
     * flip to the satellite for a beat. So we keep reading until a line that
     * **parses**, or the deadline. The last unparseable line is kept for the
     * log: "something, but not that" and "nothing" are fixed differently.
     */
    private fun readLine(l: SerialLink, timeoutMs: Long): String? {
        val sb = StringBuilder()
        val scratch = ByteArray(64)
        val deadline = System.currentTimeMillis() + timeoutMs
        var silences = 0
        // Last complete line the parser refused: not used for pointing, only
        // to explain why the mast is not pointing.
        var illisible: String? = null
        while (true) {
            val n = runCatching { l.read(scratch, 200) }.getOrDefault(0)
            if (n > 0) {
                silences = 0
                for (i in 0 until n) {
                    val c = scratch[i].toInt().toChar()
                    if (c == '\r' || c == '\n') {
                        if (sb.isNotEmpty()) {
                            val ligne = sb.toString()
                            if (Gs232Codec.parsePosition(ligne) != null) return ligne
                            illisible = ligne
                            sb.setLength(0)
                        }
                    } else sb.append(c)
                }
            } else {
                // The line went quiet. Some controllers don't terminate their
                // last line: here, and only here, an unterminated reply is
                // accepted.
                //
                // Accepting it as soon as it parses is a costly trap: on a
                // real cable the frame arrives char by char, and `AZ=090 EL=0`
                // parses fine — a 0° elevation in the middle of a reply that
                // said 30, and the mast heads for the horizon while the
                // satellite climbs. Hence two silences in a row: one may just
                // be a gap between bytes.
                silences++
                if (silences >= 2) {
                    if (Gs232Codec.parsePosition(sb.toString()) != null) return sb.toString()
                    if (pacingMs == 0L) break
                }
            }
            if (System.currentTimeMillis() >= deadline) break
        }
        // Nothing readable came. Still return what was heard: the unterminated
        // remainder if any, else the last line the parser refused.
        return if (sb.isNotEmpty()) sb.toString() else illisible
    }

    /** Discards whatever sits in the receive buffer, uninterpreted. */
    private fun vide(l: SerialLink) {
        val scratch = ByteArray(64)
        var garde = 0
        while (garde++ < 32) {
            val n = runCatching { l.read(scratch, 50) }.getOrDefault(0)
            if (n <= 0) return
        }
    }

    /** Number of queries it took to get the first reply. */
    var lastTries: Int = 0
        private set

    /**
     * Queries the controller up to [tries] times before calling it silent.
     *
     * One question proves nothing: an Arduino emulator may be just out of
     * boot, a freshly powered controller may take a second to serve the port.
     * Giving up after one try sends the operator to check a cable that is fine.
     *
     * The last frame received is kept even if unreadable: it tells "nothing"
     * from "something, but not that".
     */
    suspend fun probePosition(tries: Int = 3, gapMs: Long = 400L): RotorPos? {
        var dernier = ""
        for (i in 1..tries.coerceAtLeast(1)) {
            lastTries = i
            val pos = runCatching { readPosition() }.getOrNull()
            if (pos != null) return pos
            if (lastReply.isNotBlank()) dernier = lastReply
            if (i < tries && gapMs > 0L) delay(gapMs)
        }
        // Better an unreadable reply on screen than an assumed silence.
        if (dernier.isNotBlank()) lastReply = dernier
        return null
    }

    override suspend fun moveTo(azDeg: Double, elDeg: Double): Boolean =
        withContext(Dispatchers.IO) {
            val l = link ?: return@withContext false
            val cmd = Gs232Codec.moveCommand(azDeg, elDeg) ?: return@withContext false
            send(l, cmd)
        }

    override suspend fun readPosition(): RotorPos? = withContext(Dispatchers.IO) {
        val l = link ?: return@withContext null
        // Discard leftovers first: a command echo or a late reply from the
        // previous round would pass for the answer to this `C2`.
        vide(l)
        if (!send(l, Gs232Codec.QUERY)) { lastReply = ""; return@withContext null }
        val line = readLine(l, 800)
        // Silence is information — the only one when nothing works. Keep it
        // for the screen rather than let the operator guess between "not
        // plugged", "wrong port" and "wrong baud rate".
        lastReply = line ?: ""
        if (line == null) return@withContext null
        CatJournal.log(false, line.toByteArray(Charsets.US_ASCII),
            Gs232Codec.describe(line, out = false))
        Gs232Codec.parsePosition(line)
    }

    override suspend fun stop(): Boolean = withContext(Dispatchers.IO) {
        val l = link ?: return@withContext false
        send(l, Gs232Codec.STOP)
    }

    /** When the port was opened (ms), 0 for a link attached (a simulator). */
    private var ouvertMs = 0L

    /**
     * How long after opening a calibration may go out. K3NG ignores them for
     * 5 s after boot, and opening the port has just rebooted it: sent too
     * early, `O2` is lost without a word and the operator believes it done.
     */
    var demarrageMs: Long = 5_500L

    /** What a calibration command gave. */
    enum class Issue {
        /** "Wrote to memory": in the EEPROM. */
        ECRIT,
        /** `?>`: this controller has no such command (another firmware, no pot sensor). */
        INCONNU,
        /** Not a byte back. */
        MUET,
        /** Something came back, but not the confirmation. */
        SANS_CONFIRMATION
    }

    /** The outcome and every line heard, raw, for the operator. */
    data class ReponseEtalonnage(val issue: Issue, val lignes: List<String>)

    /**
     * Sends one of the controller's own calibrations ([Gs232Codec.Etalonnage])
     * and carries out its handshake: the prompt, the keystroke it waits for
     * (a lone CR: the firmware takes any byte, and a CR alone is no command
     * should it not be waiting), the confirmation.
     *
     * The caller must hold the line: a position query slipped in between
     * would be read as the keystroke, the pot read wherever the mast is.
     */
    suspend fun etalonne(e: Gs232Codec.Etalonnage): ReponseEtalonnage = withContext(Dispatchers.IO) {
        val l = link ?: return@withContext ReponseEtalonnage(Issue.MUET, emptyList())
        if (settleMs > 0L && ouvertMs > 0L) {
            val reste = ouvertMs + demarrageMs - System.currentTimeMillis()
            if (reste > 0L) delay(reste)
        }
        vide(l)
        val lignes = ArrayList<String>()
        send(l, e.commande + "\r")
        // The firmware first waits 200 ms (clear_serial_buffer), then prompts.
        lignes += litLignes(l, if (pacingMs == 0L) 50L else 1_500L) {
            Gs232Codec.estInvite(it) || Gs232Codec.estInconnu(it)
        }
        if (lignes.any { Gs232Codec.estInconnu(it) }) {
            lastReply = lignes.joinToString(" | ")
            return@withContext ReponseEtalonnage(Issue.INCONNU, lignes)
        }
        // The keystroke, prompt or not: a firmware that did not prompt on this
        // port still waits for it (and blocks the controller meanwhile).
        if (pacingMs > 0L) delay(100L)
        send(l, "\r")
        lignes += litLignes(l, if (pacingMs == 0L) 50L else 2_000L) {
            Gs232Codec.estEcrit(it) || Gs232Codec.estInconnu(it)
        }
        lastReply = lignes.joinToString(" | ")
        lignes.forEach { CatJournal.log(false, it.toByteArray(Charsets.US_ASCII), "réponse : $it") }
        val issue = when {
            lignes.any { Gs232Codec.estEcrit(it) } -> Issue.ECRIT
            lignes.any { Gs232Codec.estInconnu(it) } -> Issue.INCONNU
            lignes.none { it.isNotBlank() } -> Issue.MUET
            else -> Issue.SANS_CONFIRMATION
        }
        ReponseEtalonnage(issue, lignes)
    }

    /**
     * Every line heard within [timeoutMs], until one satisfies [fin]. Unlike
     * [readLine] nothing is filtered: a calibration's replies are prose.
     */
    private fun litLignes(l: SerialLink, timeoutMs: Long, fin: (String) -> Boolean): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        val scratch = ByteArray(64)
        val deadline = System.currentTimeMillis() + timeoutMs
        var silences = 0
        while (System.currentTimeMillis() < deadline) {
            val n = runCatching { l.read(scratch, 100) }.getOrDefault(0)
            if (n > 0) {
                silences = 0
                for (i in 0 until n) {
                    val c = scratch[i].toInt().toChar()
                    if (c == '\r' || c == '\n') {
                        if (sb.isNotEmpty()) {
                            val ligne = sb.toString().trim(); sb.setLength(0)
                            if (ligne.isNotEmpty()) { out += ligne; if (fin(ligne)) return out }
                        }
                    } else sb.append(c)
                }
            } else if (pacingMs == 0L && ++silences >= 2) break
        }
        if (sb.isNotBlank()) out += sb.toString().trim()
        return out
    }
}

/**
 * A fake GS-232 controller with a mast that takes time to turn.
 *
 * An instant simulator would prove nothing: the interesting questions arise
 * because a rotator is **slow**. 6°/s is a G-5500; half a turn takes thirty
 * seconds while the satellite keeps moving. That is where overlap and
 * hysteresis earn their keep.
 *
 * The test drives the clock with [advance]: a real clock fails one day on
 * a loaded machine. Counters hold degrees **actually travelled**, to compare
 * pointing strategies.
 */
class Gs232Simulator(
    val azMaxDeg: Double = 450.0,
    val elMaxDeg: Double = 180.0,
    /** Degrees per second, azimuth and elevation alike. */
    val speedDegPerSec: Double = 6.0,
    /** Where the mast stands at start: a south-stop ring starts at 180, not 0. */
    azInitDeg: Double = 0.0,
    /**
     * Behave as the K3NG Arduino firmware: its calibrations (`O`, `F`, `O2`,
     * `F2`, with their keystroke handshake and "Wrote to memory") and `?>` to
     * what it does not know. Off: a plain GS-232 box that ignores them.
     */
    val k3ng: Boolean = false
) : SerialLink {

    /** Where the mast **really** is (its pots); the controller reports it through its calibration. */
    var azDeg: Double = azInitDeg; private set
    var elDeg: Double = 0.0; private set

    // The controller's calibration, K3NG's way: the pot readings it takes for
    // the two ends of each range, mapped linearly between. As shipped, exact.
    private val azBas = azInitDeg
    /** Pot reading taken as full CCW / full CW (here pots read in real degrees). */
    var azCal0: Double = azInitDeg; private set
    var azCal1: Double = azMaxDeg; private set
    /** Pot reading taken as 0° / as the firmware's maximum ([elMaxDeg]). */
    var elCal0: Double = 0.0; private set
    var elCal1: Double = elMaxDeg; private set

    /** EEPROM writes, as a power cycle would find them. */
    var ecrituresEeprom: Int = 0; private set

    /** The real top of the mast, mechanically: the motor stalls there whatever it is told. */
    var elButeeReelleDeg: Double = elMaxDeg

    /** A calibration waiting for its keystroke, or null. */
    private var attenteTouche: String? = null

    /**
     * Throws the elevation calibration off as a worn pot does: the real
     * angle [reelLuEnHaut] now reads as the top ([elMaxDeg]).
     */
    fun deregleElevation(reelLuEnHaut: Double, reelLuAZero: Double = 0.0) { elCal0 = reelLuAZero; elCal1 = reelLuEnHaut }

    /**
     * The control box's own buttons: the mast taken straight to a real
     * position, whatever the controller believes — how an operator gets to a
     * stop the controller would not command.
     */
    fun auBoitier(azReel: Double = azDeg, elReel: Double = elDeg) {
        azDeg = azReel; elDeg = elReel.coerceIn(0.0, elButeeReelleDeg); azTarget = azDeg; elTarget = elDeg
    }

    /** Same for azimuth: the real angles read as the two ends of the range. */
    fun deregleAzimut(reelCcw: Double, reelCw: Double) { azCal0 = reelCcw; azCal1 = reelCw }

    // Exact as shipped: then not even a rounding, the numbers pass through.
    private val azExact get() = azCal0 == azBas && azCal1 == azMaxDeg
    private val elExact get() = elCal0 == 0.0 && elCal1 == elMaxDeg

    /** What the controller reports for the real position. */
    fun azLu(): Double = if (azExact) azDeg else azBas + (azDeg - azCal0) / (azCal1 - azCal0) * (azMaxDeg - azBas)
    fun elLu(): Double = if (elExact) elDeg else (elDeg - elCal0) / (elCal1 - elCal0) * elMaxDeg

    private fun azReelDe(lu: Double) = if (azExact) lu else azCal0 + (lu - azBas) / (azMaxDeg - azBas) * (azCal1 - azCal0)
    private fun elReelDe(lu: Double) = if (elExact) lu else elCal0 + lu / elMaxDeg * (elCal1 - elCal0)
    private var azTarget: Double = azInitDeg
    private var elTarget: Double = 0.0

    /** Azimuth degrees actually travelled since start. */
    var azTravelDeg: Double = 0.0; private set

    /** Elevation degrees actually travelled since start. */
    var elTravelDeg: Double = 0.0; private set

    /** Number of commands the controller refused. */
    var refusals: Int = 0; private set

    /** Everything the controller received, line by line — for tests. */
    val received = ArrayList<String>()

    private val outbox = ArrayDeque<Byte>()
    private val inbox = StringBuilder()
    private var closed = false
    val isClosed: Boolean get() = closed

    /** True until the mast has arrived. */
    val isMoving: Boolean
        get() = abs(azTarget - azDeg) > 1e-9 || abs(elTarget - elDeg) > 1e-9

    /**
     * Lets [ms] milliseconds pass: the mast moves toward its target, no faster
     * than its mechanics allow.
     */
    fun advance(ms: Long) {
        val budget = speedDegPerSec * ms / 1000.0
        val az = step(azDeg, azTarget, budget)
        val el = step(elDeg, elTarget, budget)
        azTravelDeg += abs(az - azDeg)
        elTravelDeg += abs(el - elDeg)
        azDeg = az
        elDeg = el
    }

    private fun step(from: Double, to: Double, budget: Double): Double {
        val d = to - from
        return if (abs(d) <= budget) to else from + budget * sign(d)
    }

    override fun write(bytes: ByteArray, timeoutMs: Int): Boolean {
        if (closed) return false
        // The firmware blocked in get_keystroke(): whatever comes is the key, all of it swallowed.
        attenteTouche?.let { c -> attenteTouche = null; received += "(touche)"; termineEtalonnage(c); return true }
        for (b in bytes) {
            val c = b.toInt().toChar()
            if (c == '\r' || c == '\n') { handle(inbox.toString()); inbox.setLength(0) }
            else inbox.append(c)
        }
        return true
    }

    override fun read(buf: ByteArray, timeoutMs: Int): Int {
        var n = 0
        while (n < buf.size && outbox.isNotEmpty()) buf[n++] = outbox.removeFirst()
        return n
    }

    override fun close() { closed = true; outbox.clear(); inbox.setLength(0) }

    private fun emit(s: String) { s.toByteArray(Charsets.US_ASCII).forEach { outbox.addLast(it) } }

    private fun handle(rawLine: String) {
        val line = rawLine.trim()
        if (line.isEmpty()) return
        received += line
        when (line.first().uppercaseChar()) {
            'W' -> {
                // `Waaa eee`: exactly three digits each side. A real controller
                // reads fixed columns and silently ignores anything misplaced;
                // here it is counted.
                val m = Regex("""^W\s*(\d{3})\s+(\d{3})$""", RegexOption.IGNORE_CASE).find(line)
                if (m == null) { refusals++; return }
                val az = m.groupValues[1].toDouble()
                val el = m.groupValues[2].toDouble()
                if (az > azMaxDeg || el > elMaxDeg) { refusals++; if (k3ng) emit("?>\r\n"); return }
                // Told in the controller's numbers; the pots go where those numbers point.
                azTarget = azReelDe(az); elTarget = elReelDe(el).coerceIn(0.0, elButeeReelleDeg)
            }
            'C' -> emit(String.format(Locale.US, "AZ=%03d EL=%03d\r",
                azLu().roundToInt(), elLu().roundToInt()))
            'S' -> { azTarget = azDeg; elTarget = elDeg }
            'O', 'F' -> {
                if (!k3ng) { refusals++; return }
                val c = line.uppercase()
                if (c != "O" && c != "F" && c != "O2" && c != "F2") { emit("?>\r\n"); refusals++; return }
                attenteTouche = c
                emit(when (c) {
                    "O2" -> "Elevate to 0 degrees and send keystroke...\r\n"
                    "F2" -> "Elevate to 180 (or max elevation) and send keystroke...\r\n"
                    "O" -> "Rotate to full CCW and send keystroke...\r\n"
                    else -> "Rotate to full CW and send keystroke...\r\n"
                })
            }
            else -> { refusals++; if (k3ng) emit("?>\r\n") }
        }
    }

    private fun termineEtalonnage(c: String) {
        when (c) {
            "O2" -> elCal0 = elDeg
            "F2" -> elCal1 = elDeg
            "O" -> azCal0 = azDeg
            else -> azCal1 = azDeg
        }
        ecrituresEeprom++
        emit("Wrote to memory\r\n")
    }
}
