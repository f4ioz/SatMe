/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.clavier.DecodeurPsk31
import fr.f4ioz.satcombo.clavier.DecodeurRtty
import fr.f4ioz.satcombo.clavier.JournalClavier
import fr.f4ioz.satcombo.plateforme.Stockage
import org.json.JSONArray
import org.json.JSONObject

/**
 * PSK31 or RTTY on the PC's sound (the sound card, or the dongle when it is
 * the source), for the QO-100 cockpit's dock: off unless chosen, one mode at
 * a time, the text in stamped lines. The RTTY settings are also those of
 * the « Décodeurs multiples » RTTY channels. Receive only.
 */
class ClavierPc(private val stockage: Stockage, private val horloge: () -> Long = System::currentTimeMillis) {

    var actif: Boolean
        get() = stockage.getBoolean(CLE_ON, false)
        set(v) { stockage.edit().putBoolean(CLE_ON, v).apply(); if (!v) synchronized(this) { psk = null; rtty = null } }

    /** "PSK31" or "RTTY". */
    var mode: String
        get() = stockage.getString(CLE_MODE, PSK31).takeIf { it == RTTY } ?: PSK31
        set(v) { stockage.edit().putString(CLE_MODE, if (v.uppercase() == RTTY) RTTY else PSK31).apply() }

    /** The audio frequency listened to (Hz); 0: the mode's usual one (1 000 PSK31, 1 500 RTTY). */
    var centreChoisi: Int
        get() = stockage.getInt(CLE_CENTRE, 0)
        set(v) { stockage.edit().putInt(CLE_CENTRE, if (v == 0) 0 else v.coerceIn(300, 3_000)).apply(); synchronized(this) { psk?.centreHz = centre(PSK31); rtty?.centreHz = centre(RTTY) } }

    fun centre(m: String = mode): Double = centreChoisi.takeIf { it > 0 }?.toDouble() ?: if (m == RTTY) 1_500.0 else 1_000.0

    /** Squelch, 0..1 of the decision quality; 0: each decoder's own. */
    var seuil: Double
        get() = stockage.getInt(CLE_SEUIL, 0) / 100.0
        set(v) { stockage.edit().putInt(CLE_SEUIL, (v * 100).toInt().coerceIn(0, 95)).apply(); synchronized(this) { psk?.let { s(it) }; rtty?.let { s(it) } } }

    var reglagesRtty: DecodeurRtty.Reglages
        get() = DecodeurRtty.Reglages(stockage.getInt(CLE_BAUD, 4545) / 100.0, stockage.getInt(CLE_SHIFT, 170).toDouble(),
            stockage.getBoolean(CLE_INVERSE, false), stockage.getBoolean(CLE_USOS, true)).borne()
        set(v) {
            stockage.edit().putInt(CLE_BAUD, Math.round(v.baud * 100).toInt()).putInt(CLE_SHIFT, v.shiftHz.toInt())
                .putBoolean(CLE_INVERSE, v.inverse).putBoolean(CLE_USOS, v.usos).apply()
            synchronized(this) { rtty?.reglages = reglagesRtty }
        }

    val journal = JournalClavier(horloge)
    @Volatile private var psk: DecodeurPsk31? = null
    @Volatile private var rtty: DecodeurRtty? = null
    @Volatile private var rate = 0

    private fun s(d: DecodeurPsk31) { if (seuil > 0) d.seuil = seuil else d.seuil = 0.55 }
    private fun s(d: DecodeurRtty) { if (seuil > 0) d.seuil = seuil else d.seuil = 0.6 }

    fun ecoute(pcm: ShortArray, n: Int, r: Int) {
        if (!actif) return
        synchronized(this) {
            if (r != rate) { rate = r; psk = null; rtty = null }
            if (mode == RTTY) {
                psk = null
                val d = rtty ?: DecodeurRtty(r, { journal.ajoute(it) }, centre(RTTY), reglagesRtty).also { s(it); rtty = it }
                d.ecoute(pcm, n)
            } else {
                rtty = null
                val d = psk ?: DecodeurPsk31(r, { journal.ajoute(it) }, centre(PSK31)).also { s(it); psk = it }
                d.ecoute(pcm, n)
            }
        }
    }

    /** The page's gestures (`/api/clavier`): on, mode, centre, seuil, baud, shift, inverse, usos, efface. */
    fun action(q: Map<String, String>): JSONObject {
        q["on"]?.let { actif = it == "1" }
        q["mode"]?.let { mode = it }
        q["centre"]?.toIntOrNull()?.let { centreChoisi = it }
        q["seuil"]?.toIntOrNull()?.let { seuil = it / 100.0 }
        if (q.keys.any { it in setOf("baud", "shift", "inverse", "usos") }) {
            val r = reglagesRtty
            reglagesRtty = r.copy(baud = q["baud"]?.replace(',', '.')?.toDoubleOrNull() ?: r.baud,
                shiftHz = q["shift"]?.toDoubleOrNull() ?: r.shiftHz,
                inverse = q["inverse"]?.let { it == "1" } ?: r.inverse, usos = q["usos"]?.let { it == "1" } ?: r.usos)
        }
        if (q["efface"] == "1") journal.efface()
        return JSONObject().put("ok", true)
    }

    fun json(indicatif: String): JSONObject {
        val o = JSONObject().put("present", true).put("actif", actif).put("mode", mode).put("centreChoisi", centreChoisi)
            .put("centre", centre()).put("seuil", Math.round(seuil * 100)).put("version", journal.version)
        val r = reglagesRtty
        o.put("rtty", JSONObject().put("baud", r.baud).put("shift", r.shiftHz).put("inverse", r.inverse).put("usos", r.usos))
            .put("bauds", JSONArray(DecodeurRtty.BAUDS)).put("shifts", JSONArray(DecodeurRtty.SHIFTS))
        val p = psk; val d = rtty
        when {
            p != null -> o.put("frequence", Math.round(p.frequenceHz * 10) / 10.0).put("qualite", Math.round(p.qualite * 100)).put("ouvert", p.ouvert)
                .put("snr", nombre(p.snrDb)).put("imd", if (p.snrDb >= 10) nombre(p.imdDb) else JSONObject.NULL)
            d != null -> o.put("frequence", Math.round(d.frequenceHz * 10) / 10.0).put("qualite", Math.round(d.qualite * 100)).put("ouvert", d.ouvert)
                .put("snr", nombre(d.snrDb)).put("tons", d.present)
        }
        return o.put("lignes", journal.json(60, indicatif))
    }

    companion object {
        const val PSK31 = "PSK31"
        const val RTTY = "RTTY"
        const val CLE_ON = "pc_clavier"
        const val CLE_MODE = "pc_clavier_mode"
        const val CLE_CENTRE = "pc_clavier_centre"
        const val CLE_SEUIL = "pc_clavier_seuil"
        const val CLE_BAUD = "pc_rtty_baud"
        const val CLE_SHIFT = "pc_rtty_shift"
        const val CLE_INVERSE = "pc_rtty_inverse"
        const val CLE_USOS = "pc_rtty_usos"

        fun nombre(x: Double): Any = if (x.isNaN()) JSONObject.NULL else Math.round(x * 10) / 10.0
    }
}
