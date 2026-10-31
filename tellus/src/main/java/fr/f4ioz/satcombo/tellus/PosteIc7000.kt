/*
 * TellusMe — the SatMe ecosystem's terrestrial station
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.tellus

import fr.f4ioz.satcombo.cat.CatDecode
import fr.f4ioz.satcombo.cat.CivController
import fr.f4ioz.satcombo.cat.ModeleHf
import fr.f4ioz.satcombo.cat.SerialLink

/**
 * The line to the rig with a guard on it: only the CI-V commands a receiver
 * needs leave — read the frequency (03) and the mode (04), set them (05, 06),
 * read the S-meter (15 02). Anything else, 1C (PTT, the tuner) and 17 (CW
 * message, which keys the rig) first, is refused before it reaches the wire,
 * and counted. TellusMe's draft is receive-only; this makes it so whatever
 * the code above it asks.
 */
class LienReception(private val l: SerialLink) : SerialLink {
    @Volatile var refuses = 0; private set

    override fun write(bytes: ByteArray, timeoutMs: Int): Boolean {
        val trames = CatDecode.splitCiv(bytes)
        if (trames.isEmpty() || trames.any { !permis(it) }) { refuses++; return false }
        return l.write(bytes, timeoutMs)
    }

    override fun read(buf: ByteArray, timeoutMs: Int): Int = l.read(buf, timeoutMs)
    override fun close() = l.close()

    companion object {
        /** A frame TellusMe may send: commands 03, 04, 05, 06 and the S-meter read 15 02. */
        fun permis(f: ByteArray): Boolean {
            if (f.size < 6) return false
            return when (f[4].toInt() and 0xFF) {
                0x03, 0x04 -> f.size == 6
                0x05 -> f.size == 11
                0x06 -> f.size in 7..8
                0x15 -> f.size == 7 && (f[5].toInt() and 0xFF) == 0x02
                else -> false
            }
        }
    }
}

/**
 * The Icom IC-7000 on CI-V, receive side: frequency, mode, S-meter. The
 * framing, echo handling, address filtering and line lock are the engine's
 * ([CivController], tested on the IC-9700 / IC-705), the address 70h.
 *
 * **Echo and transceive.** The CI-V jack is one wire: every question comes
 * back as an echo, which the controller drops by address. With « CI-V
 * Transceive » on, the rig also broadcasts (to 00h) each turn of the knob;
 * those frames are not addressed to us and are dropped too — the page reads
 * the rig every 300 ms instead.
 *
 * **Reconnection.** [ouvreLien] opens the cable the operator chose; when the
 * rig has not answered for [silenceMs] the line is closed and reopened
 * every [relanceMs] (an unplugged cable, the rig off), never another port.
 */
class PosteIc7000(
    private val ouvreLien: () -> SerialLink?,
    adresse: Int = ModeleHf.IC7000.adresse,
    pacingMs: Long = 40L,
    private val silenceMs: Long = 5_000L,
    private val relanceMs: Long = 5_000L,
    private val horloge: () -> Long = System::currentTimeMillis,
) {
    private val civ = CivController(null).apply { radioAddr = adresse; this.pacingMs = pacingMs }
    private var lien: LienReception? = null

    @Volatile var connecte = false; private set
    @Volatile var frequence: Long? = null; private set
    @Volatile var mode: String? = null; private set
    /** 0 = S0, 120 = S9, 241 = S9+60 dB (Icom's scale), null when not read. */
    @Volatile var smetre: Int? = null; private set
    @Volatile var erreur: String = ""; private set
    @Volatile var reconnexions = 0; private set
    private var derniereReponse = 0L
    private var dernierEssai = 0L

    /** Commands the guard refused (always zero unless a bug asked for one). */
    val refuses: Int get() = lien?.refuses ?: 0

    @Synchronized fun ouvre(): Boolean {
        ferme()
        dernierEssai = horloge()
        val l = runCatching { ouvreLien() }.getOrNull()
        if (l == null) { erreur = "port du poste introuvable ou refusé"; return false }
        val g = LienReception(l)
        lien = g
        civ.attach(g)
        derniereReponse = horloge()
        connecte = true
        erreur = ""
        return true
    }

    @Synchronized fun ferme() {
        runCatching { civ.close() }
        lien = null
        connecte = false
    }

    /** One round: reads frequency, mode, S-meter; reconnects after a long silence. */
    suspend fun sonde() {
        if (!connecte) {
            if (horloge() - dernierEssai >= relanceMs && ouvre()) reconnexions++
            if (!connecte) return
        }
        val f = civ.readFrequency()
        val m = civ.readMode()
        val s = civ.readSMeter()
        if (f != null || m != null || s != null) derniereReponse = horloge()
        if (f != null) frequence = f
        if (m != null) mode = NOMS_MODES[m] ?: "?"
        smetre = s
        if (horloge() - derniereReponse > silenceMs) {
            erreur = "le poste ne répond plus : reconnexion"
            ferme()
            dernierEssai = horloge()
        }
    }

    /** Tunes the rig; refused outside what it receives. */
    suspend fun accorde(hz: Long): Boolean {
        if (!connecte || !ModeleHf.IC7000.recoit(hz)) return false
        val ok = civ.setFrequency(hz)
        if (ok) frequence = hz
        return ok
    }

    /** Sets the mode (LSB, USB, AM, CW, RTTY, FM, CW-R, RTTY-R). */
    suspend fun regleMode(nom: String): Boolean {
        val code = CODES_MODES[nom.uppercase()] ?: return false
        if (!connecte) return false
        val ok = civ.setMode(code, 0x01)
        if (ok) mode = nom.uppercase()
        return ok
    }

    companion object {
        /** IC-7000 mode codes (command 06 / 04), from its instruction manual. */
        val CODES_MODES = linkedMapOf("LSB" to 0x00, "USB" to 0x01, "AM" to 0x02, "CW" to 0x03, "RTTY" to 0x04,
            "FM" to 0x05, "CW-R" to 0x07, "RTTY-R" to 0x08)
        val NOMS_MODES = CODES_MODES.entries.associate { it.value to it.key }

        /** Icom's 0–241 meter as S units: « S7 », « S9+20 ». */
        fun texteS(v: Int?): String {
            if (v == null) return "—"
            if (v <= 120) return "S" + Math.round(v * 9.0 / 120).coerceIn(0, 9)
            return "S9+" + (Math.round((v - 120) * 60.0 / 121 / 10) * 10).coerceAtMost(60)
        }
    }
}
