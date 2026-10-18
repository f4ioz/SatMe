/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.cat

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** What the station shows now to a client of the rigctld network protocol. */
data class LectureRigctld(
    /** The frequency listened to (sky, Hz). */
    val frequenceHz: Long,
    /** In the protocol's words: USB, LSB, CW, FM, AM… */
    val mode: String,
    val bandeHz: Int,
    /** The transmit frequency when one goes with it (QO-100's uplink, the rig's TX): split then. */
    val emissionHz: Long? = null,
    /** Something SatMe drives is on the air now (read only). */
    val emet: Boolean = false,
    /** Retuning allowed (the SDR receiver only): told to the client in the state dump. */
    val accordPermis: Boolean = false,
)

/** The station seen by the protocol: read always; the receiver retuned only when allowed. */
interface StationRigctld {
    fun lecture(): LectureRigctld
    /** Retunes the receiver to [hz] (sky); 0, or a negative protocol error. Never the transmitter. */
    fun accorde(hz: Long): Int = RigctldProtocole.ENAVAIL
    /** Sets the receiver's demodulation ([mode] in the protocol's words, [bandeHz] ≤ 0: unchanged). */
    fun mode(mode: String, bandeHz: Int): Int = RigctldProtocole.ENAVAIL
}

/**
 * The rigctld text protocol, server side (from the rigctld(1)/rigctl(1)
 * manual pages): one command a line, short (`f`) or long (`\get_freq`); a
 * get answers its values one a line, a set answers `RPRT 0` or `RPRT -n`.
 * A first character `+` (or `;` `|` `,`) asks the extended answer: the
 * command's name, each value named, `RPRT n` last, `+` separating them by
 * new lines and the others by themselves.
 *
 * Read only for transmitting: PTT, split and anything else that would
 * touch a transmitter is refused (`RPRT -11`), always.
 */
class RigctldProtocole(private val station: StationRigctld, private val modele: Int = MODELE) {

    private data class Reponse(val valeurs: List<Pair<String, String>>, val code: Int = 0, val brut: Boolean = false)

    /** One line from the client → what to write back ("" nothing); null: close the connection. */
    fun traite(ligneLue: String): String? {
        var ligne = ligneLue.trim('\r', '\n', ' ', '\t')
        if (ligne.isEmpty()) return ""
        var sep: Char? = null
        if (ligne[0] in "+;|,") { sep = if (ligne[0] == '+') '\n' else ligne[0]; ligne = ligne.drop(1).trimStart() }
        if (ligne.isEmpty()) return ""
        val nom: String
        val args: List<String>
        if (ligne[0] == '\\') {
            val p = ligne.drop(1).split(Regex("\\s+"))
            nom = p[0]; args = p.drop(1)
        } else {
            nom = COURTS[ligne[0]] ?: ("?" + ligne[0])
            args = ligne.drop(1).trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        }
        if (nom == "quit") return null
        // A VFO named first (clients that always name it): only the receiving one may be set.
        val vfo = args.size >= 2 && (args[0].startsWith("VFO") || args[0] == "currVFO" || args[0] == "Main")
        val a = if (vfo) args.drop(1) else args
        val r = if (vfo && nom.startsWith("set_") && args[0] !in setOf(VFO_RX, "currVFO", "Main")) code(ENAVAIL) else execute(nom, a)
        return if (sep == null) normal(r) else etendu(nom, args, r, sep)
    }

    private fun normal(r: Reponse): String = when {
        r.code != 0 -> "RPRT ${r.code}\n"
        r.valeurs.isEmpty() && !r.brut -> "RPRT 0\n"
        else -> r.valeurs.joinToString("") { it.second + "\n" }
    }

    private fun etendu(nom: String, args: List<String>, r: Reponse, sep: Char): String {
        val b = StringBuilder(nom.removePrefix("?")).append(':')
        if (nom.startsWith("set_") && args.isNotEmpty()) b.append(' ').append(args.joinToString(" "))
        b.append(sep)
        if (r.code == 0) r.valeurs.forEach { (k, v) -> b.append(if (k.isEmpty()) v else "$k: $v").append(sep) }
        return b.append("RPRT ").append(r.code).append('\n').toString()
    }

    private fun get(vararg v: Pair<String, String>) = Reponse(v.toList())
    private fun code(c: Int) = Reponse(emptyList(), c)

    private fun execute(nom: String, args: List<String>): Reponse {
        val l = runCatching { station.lecture() }.getOrElse { return code(EIO) }
        val split = l.emissionHz != null && l.emissionHz != l.frequenceHz
        return when (nom) {
            "get_freq" -> get("Frequency" to l.frequenceHz.toString())
            "get_mode" -> get("Mode" to l.mode, "Passband" to l.bandeHz.toString())
            "get_vfo" -> get("VFO" to VFO_RX)
            "get_ptt" -> get("PTT" to if (l.emet) "1" else "0")
            "get_split_vfo" -> get("Split" to if (split) "1" else "0", "TX VFO" to if (split) VFO_TX else VFO_RX)
            "get_split_freq" -> get("TX Frequency" to (l.emissionHz ?: l.frequenceHz).toString())
            "get_split_mode" -> get("TX Mode" to l.mode, "TX Passband" to l.bandeHz.toString())
            "get_rit" -> get("RIT" to "0")
            "get_xit" -> get("XIT" to "0")
            "get_powerstat" -> get("Power Status" to "1")
            "chk_vfo" -> get("ChkVFO" to "0")
            "get_info" -> get("Info" to "SatMe")
            "dump_state" -> Reponse(etat(l).map { "" to it }, brut = true)
            "dump_caps" -> Reponse(caps(l).map { "" to it }, brut = true)
            "set_freq" -> {
                if (!l.accordPermis) return code(ENAVAIL)
                val hz = args.firstOrNull()?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: return code(EINVAL)
                code(runCatching { station.accorde(Math.round(hz)) }.getOrDefault(EIO))
            }
            "set_mode" -> {
                if (args.firstOrNull() == "?") return get("" to MODES.joinToString(" "))
                if (!l.accordPermis) return code(ENAVAIL)
                val m = args.firstOrNull()?.uppercase(Locale.ROOT) ?: return code(EINVAL)
                if (m !in MODES) return code(EINVAL)
                val bande = args.getOrNull(1)?.toDoubleOrNull()?.toInt() ?: 0
                code(runCatching { station.mode(m, bande) }.getOrDefault(EIO))
            }
            // The receiving VFO only: anything else would mean another (transmitting) one.
            "set_vfo" -> when (args.firstOrNull()) {
                "?" -> get("" to VFO_RX)
                VFO_RX, "currVFO", "Main", "RX", "VFO" -> code(0)
                else -> code(ENAVAIL)
            }
            // What is already so is no change; anything else refused.
            "set_split_vfo" -> if (args.firstOrNull() == (if (split) "1" else "0")) code(0) else code(ENAVAIL)
            "set_rit", "set_xit" -> if (args.firstOrNull()?.toDoubleOrNull() == 0.0) code(0) else code(ENAVAIL)
            // Never: PTT, the transmit frequency and mode, the power.
            "set_ptt", "set_split_freq", "set_split_mode", "set_powerstat" -> code(ENAVAIL)
            else -> code(if (nom.startsWith("set_") || nom.startsWith("?")) ENAVAIL else ENIMPL)
        }
    }

    /**
     * The state dump a network client reads when it connects (protocol 1):
     * receive range, no transmit range, steps, filters, nothing settable
     * beyond the receiver, then `key=value` lines until `done`.
     */
    private fun etat(l: LectureRigctld): List<String> {
        val modes = "0x" + java.lang.Long.toHexString(MASQUE_MODES)
        return listOf(
            "1", modele.toString(), "0",
            String.format(Locale.US, "%.6f %.6f %s -1 -1 0x3 0x1", RX_MIN_HZ.toDouble(), RX_MAX_HZ.toDouble(), modes),
            "0 0 0 0 0 0 0",
            "0 0 0 0 0 0 0",
            "$modes 1", "$modes 10", "$modes 100", "0 0",
            "0xc 2400", "0x2 500", "0x1 6000", "0x20 12000", "0 0",
            "0", "0", "0", "0", "", "",
            "0x0", "0x0", "0x0", "0x0", "0x0", "0x0",
            "vfo_ops=0x0", "ptt_type=0x1", "targetable_vfo=0x0",
            "has_set_vfo=1", "has_get_vfo=1", "has_set_freq=" + (if (l.accordPermis) 1 else 0), "has_get_freq=1",
            "has_set_conf=0", "has_get_conf=0", "has_power2mW=0", "has_mW2power=0", "timeout=0",
            "rig_model=$modele", "done")
    }

    private fun caps(l: LectureRigctld): List<String> = listOf(
        "Caps dump for model: $modele", "Model name:\tSatMe", "Mfg name:\tF4IOZ", "Backend version:\t1",
        "Rig type:\tReceiver", "PTT type:\tRig capable (read only)",
        "Can set Frequency:\t" + (if (l.accordPermis) "Y" else "N"), "Can get Frequency:\tY",
        "Can set Mode:\t" + (if (l.accordPermis) "Y" else "N"), "Can get Mode:\tY",
        "Can set PTT:\tN", "Can get PTT:\tY", "Can get Split VFO:\tY", "Can get Split Freq:\tY",
        "Mode list: " + MODES.joinToString(" "))

    companion object {
        const val PORT_DEFAUT = 4532
        /** The model number told in the state dump: a generic one. */
        const val MODELE = 1
        const val EINVAL = -1
        const val ENIMPL = -4
        const val EIO = -6
        const val ENAVAIL = -11
        const val VFO_RX = "VFOA"
        const val VFO_TX = "VFOB"
        const val RX_MIN_HZ = 100_000L
        const val RX_MAX_HZ = 12_000_000_000L
        /** AM, CW, USB, LSB, FM, CWR, PKTLSB, PKTUSB, PKTFM: the protocol's mode bits. */
        const val MASQUE_MODES = 0x1L or 0x2L or 0x4L or 0x8L or 0x20L or 0x80L or 0x400L or 0x800L or 0x1000L
        val MODES = listOf("AM", "CW", "USB", "LSB", "FM", "CWR", "PKTUSB", "PKTLSB", "PKTFM")

        private val COURTS = mapOf(
            'f' to "get_freq", 'F' to "set_freq", 'm' to "get_mode", 'M' to "set_mode",
            'v' to "get_vfo", 'V' to "set_vfo", 't' to "get_ptt", 'T' to "set_ptt",
            's' to "get_split_vfo", 'S' to "set_split_vfo", 'i' to "get_split_freq", 'I' to "set_split_freq",
            'x' to "get_split_mode", 'X' to "set_split_mode", 'j' to "get_rit", 'J' to "set_rit",
            'z' to "get_xit", 'Z' to "set_xit", '1' to "dump_caps", '_' to "get_info",
            'q' to "quit", 'Q' to "quit")

        /** A mode as read (rig, SDR demodulator) → the protocol's word. */
        fun modeProtocole(m: String?): String {
            val u = m.orEmpty().uppercase(Locale.ROOT).replace('_', '-').trim()
            val data = u.contains("DATA") || u.startsWith("PKT") || u.endsWith("-D")
            return when {
                u.isEmpty() -> "USB"
                u == "CWR" || u == "CW-R" -> "CWR"
                u.startsWith("CW") -> "CW"
                u.contains("FM") -> if (data) "PKTFM" else "FM"
                u.contains("LSB") || u == "DATA-L" -> if (data) "PKTLSB" else "LSB"
                u.startsWith("AM") -> "AM"
                // USB, SSB, DATA-U, a linear transponder's words…
                else -> if (data) "PKTUSB" else "USB"
            }
        }

        /** The usual passband of a mode, when none is set. */
        fun bandeParDefaut(mode: String): Int = when (mode) {
            "CW", "CWR" -> 500
            "AM" -> 6000
            "FM", "PKTFM" -> 12000
            else -> 2400
        }
    }
}

/**
 * The TCP server: one thread a client, one [RigctldProtocole] each. Lines
 * longer than [LIGNE_MAX] or more than [CLIENTS_MAX] clients are cut.
 */
class ServeurRigctld(private val station: StationRigctld) {

    private val clients = CopyOnWriteArrayList<Socket>()
    @Volatile private var serveur: ServerSocket? = null

    val ecoute: Boolean get() = serveur?.isClosed == false
    val nbClients: Int get() = clients.size
    val portReel: Int get() = serveur?.localPort ?: 0

    /** Listens on [adresse] (null: every interface); the exception when the port is taken. */
    @Synchronized fun demarre(adresse: java.net.InetAddress?, port: Int) {
        if (ecoute) return
        val s = ServerSocket().apply {
            reuseAddress = true
            bind(if (adresse == null) InetSocketAddress(port) else InetSocketAddress(adresse, port))
        }
        serveur = s
        thread(isDaemon = true, name = "satme-rigctld") {
            while (!s.isClosed) {
                val c = runCatching { s.accept() }.getOrNull() ?: continue
                if (clients.size >= CLIENTS_MAX) { runCatching { c.close() }; continue }
                clients += c
                thread(isDaemon = true, name = "satme-rigctld-client") {
                    runCatching { sert(c) }
                    clients -= c; runCatching { c.close() }
                }
            }
        }
    }

    @Synchronized fun arrete() {
        runCatching { serveur?.close() }
        serveur = null
        clients.forEach { runCatching { it.close() } }
        clients.clear()
    }

    private fun sert(c: Socket) {
        c.tcpNoDelay = true
        val entree = BufferedReader(InputStreamReader(c.getInputStream(), Charsets.US_ASCII))
        val sortie = c.getOutputStream()
        val p = RigctldProtocole(station)
        val b = StringBuilder()
        while (true) {
            val ch = entree.read()
            if (ch < 0) return
            if (ch != '\n'.code) {
                if (b.length < LIGNE_MAX) b.append(ch.toChar())
                continue
            }
            val r = p.traite(b.toString()) ?: return
            b.setLength(0)
            if (r.isNotEmpty()) { sortie.write(r.toByteArray(Charsets.US_ASCII)); sortie.flush() }
        }
    }

    companion object {
        const val LIGNE_MAX = 1024
        const val CLIENTS_MAX = 16
    }
}
