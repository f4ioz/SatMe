/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * A SatMe-Node's health as its `/noeud/sante` gives it, read the same way
 * by SatMe-PC and the phone: what the system files say (parsed here), and
 * the alerts drawn from it — too hot, throttled, a bladeRF on USB 2, the
 * SDR stream losing frames, a service in error, the node silent.
 */
object SanteNoeud {
    data class Alerte(val niveau: String, val texte: String) {
        fun json(): JSONObject = JSONObject().put("niveau", niveau).put("texte", texte)
    }

    const val DANGER = "danger"
    const val ATTENTION = "warn"

    /** Above this, the Pi slows itself down soon (°C). */
    const val CHAUD_C = 75.0
    const val BRULANT_C = 80.0

    /** `/proc/uptime`'s first number (s), or null. */
    fun uptime(t: String): Double? = t.trim().split(' ').firstOrNull()?.toDoubleOrNull()

    /** `/proc/loadavg`: the 1, 5 and 15 minute loads. */
    fun charge(t: String): List<Double> = t.trim().split(' ').take(3).mapNotNull { it.toDoubleOrNull() }

    /** A thermal zone's millidegrees, as °C. */
    fun temperature(t: String): Double? = t.trim().toLongOrNull()?.let { it / 1000.0 }

    /** `/proc/meminfo`: total and available (kB). */
    fun memoire(t: String): Pair<Long, Long>? {
        fun v(k: String) = t.lines().firstOrNull { it.startsWith("$k:") }?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLongOrNull()
        val tot = v("MemTotal") ?: return null
        return tot to (v("MemAvailable") ?: v("MemFree") ?: 0L)
    }

    /**
     * `vcgencmd get_throttled` (« throttled=0x50005 »): the bits now and
     * since boot, in words. Null when it is not a Pi's answer.
     */
    data class Bridage(val brut: Int, val maintenant: List<String>, val depuisDemarrage: List<String>)

    fun bridage(t: String): Bridage? {
        val h = t.substringAfter("throttled=", "").trim().removePrefix("0x").takeIf { it.isNotEmpty() } ?: return null
        val v = h.toIntOrNull(16) ?: return null
        fun mots(decalage: Int) = listOfNotNull(
            "sous-alimentation".takeIf { v and (1 shl decalage) != 0 },
            "fréquence plafonnée".takeIf { v and (1 shl (decalage + 1)) != 0 },
            "ralenti (bridé)".takeIf { v and (1 shl (decalage + 2)) != 0 },
            "limite de température".takeIf { v and (1 shl (decalage + 3)) != 0 })
        return Bridage(v, mots(0), mots(16))
    }

    /** A known board from its USB id: its role on a node. */
    val USB_CONNUS = mapOf("0403:6010" to "MiniTiouner", "2cf0:5250" to "bladeRF 2.0", "0456:b673" to "ADALM-PLUTO",
        "1d50:6089" to "HackRF One", "0bda:2838" to "clé RTL-SDR", "0bda:2832" to "clé RTL-SDR")

    /** USB speed in Mb/s from sysfs `speed` (« 480 », « 5000 »): its name. */
    fun vitesseUsb(mbps: String): String = when (mbps.trim().substringBefore('.').toIntOrNull()) {
        null -> ""; in 0..12 -> "USB 1"; in 13..480 -> "USB 2"; else -> "USB 3"
    }

    /**
     * The alerts for the health [s] (null: the node did not answer, [erreur] why).
     * The words for the page, the most serious first.
     */
    fun alertes(s: JSONObject?, erreur: String = ""): List<Alerte> {
        if (s == null) return if (erreur.isBlank()) emptyList() else listOf(Alerte(DANGER, "node injoignable : $erreur"))
        val l = ArrayList<Alerte>()
        val sys = s.optJSONObject("systeme") ?: JSONObject()
        if (sys.has("temperature")) {
            val t = sys.optDouble("temperature")
            if (t >= BRULANT_C) l += Alerte(DANGER, "surchauffe : %.0f °C".format(Locale.FRANCE, t))
            else if (t >= CHAUD_C) l += Alerte(ATTENTION, "chaud : %.0f °C (ventiler le boîtier)".format(Locale.FRANCE, t))
        }
        sys.optJSONArray("bridage")?.let { a -> if (a.length() > 0) l += Alerte(DANGER, "Pi bridé maintenant : " + liste(a)) }
        sys.optJSONArray("bridageDepuis")?.let { a ->
            if (a.length() > 0 && (sys.optJSONArray("bridage")?.length() ?: 0) == 0) l += Alerte(ATTENTION, "bridé depuis le démarrage : " + liste(a))
        }
        val disque = sys.optLong("disqueLibreMo", -1L)
        if (disque in 0..499) l += Alerte(if (disque < 100) DANGER else ATTENTION, "carte microSD presque pleine : $disque Mo libres")
        val mem = sys.optJSONObject("memoire")
        if (mem != null && mem.optLong("totalKo") > 0 && mem.optLong("libreKo") * 10 < mem.optLong("totalKo")) l += Alerte(ATTENTION, "mémoire presque pleine")
        s.optJSONArray("usb")?.let { u ->
            for (i in 0 until u.length()) {
                val d = u.optJSONObject(i) ?: continue
                if (d.optString("nom") == "bladeRF 2.0" && d.optString("vitesse") == "USB 2")
                    l += Alerte(ATTENTION, "bladeRF sur un port USB 2 : le brancher sur un port USB 3 (bleu)")
            }
        }
        val sv = s.optJSONObject("services") ?: JSONObject()
        for (k in listOf("datv", "sdr", "tx")) {
            val o = sv.optJSONObject(k) ?: continue
            if (o.optString("etat") == "erreur" && o.optString("erreur").isNotBlank()) l += Alerte(ATTENTION, NOMS[k] + " : " + o.optString("erreur"))
        }
        sv.optJSONObject("sdr")?.let { o ->
            val p = o.optDouble("pertesPct", 0.0)
            if (o.optString("etat") == "marche" && p >= 1.0) l += Alerte(ATTENTION, "flux I/Q : %.0f %% des trames perdues (réseau trop lent ? alléger le flux)".format(Locale.FRANCE, p))
        }
        if (sv.optJSONObject("tx")?.optString("etat") == "marche") l += Alerte(DANGER, "le node ÉMET")
        return l.sortedBy { if (it.niveau == DANGER) 0 else 1 }
    }

    private val NOMS = mapOf("datv" to "réception DATV", "sdr" to "SDR", "tx" to "émission")

    private fun liste(a: JSONArray) = (0 until a.length()).joinToString(", ") { a.optString(it) }

    fun json(l: List<Alerte>): JSONArray = JSONArray(l.map { it.json() })
}
