/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.telemesure

import kotlin.math.pow

/**
 * The FUNcube-1 (AO-73) downlink frame, from AMSAT-UK's « FUNcube-1
 * Downlink Data » (issue 1.0, as flown) and its telemetry equations sheet:
 * 256 bytes = 2-bit satellite id, 6-bit frame type, 440 bits of real-time
 * telemetry, 200 bytes of payload (whole-orbit data, high-resolution data
 * or a fitter message). All fields MSB first, packed without gaps.
 *
 * Satellites with id 3 (« extended protocol ») lay out their frame their own
 * way: only the header is read for them.
 */
object Funcube {

    /** A telemetry value: its label, the value as shown, the raw count. */
    data class Mesure(val groupe: String, val nom: String, val valeur: String, val brut: Long)

    data class Trame(
        val satId: Int,
        val type: Int,
        val genre: String,
        val satellite: String,
        val mesures: List<Mesure>,
        /** The fitter message, when the payload is one. */
        val message: String?,
    )

    fun satellite(id: Int) = when (id) {
        0 -> "FUNcube-1 (modèle d’essai)"
        1 -> "FUNcube-2 (UKube-1)"
        2 -> "FUNcube-1 (AO-73)"
        else -> "protocole étendu"
    }

    /** WO 1…12, HR at 13, 17, 21, fitter messages the others (1…24). */
    fun genre(type: Int) = when (type) {
        in 1..12 -> "WO$type"
        13 -> "HR1"; 17 -> "HR2"; 21 -> "HR3"
        in 14..24 -> "FM" + (type - 14 - (if (type > 17) 1 else 0) - (if (type > 21) 1 else 0) + 1)
        else -> "type $type"
    }

    private class Lecteur(private val d: ByteArray, var pos: Int) {
        fun bits(n: Int): Long {
            var v = 0L
            repeat(n) {
                val b = (d[pos / 8].toInt() shr (7 - pos % 8)) and 1
                v = (v shl 1) or b.toLong(); pos++
            }
            return v
        }
    }

    private fun f1(x: Double) = String.format(java.util.Locale.FRANCE, "%.1f", x)
    private fun s8(v: Long) = if (v >= 128) v - 256 else v

    /** PA board temperature, °C, from its ADC (measured points, linear between them). */
    private val PA_TEMP = arrayOf(
        0 to 87.983, 79 to 55.3, 91 to 49.6, 103 to 45.3, 115 to 41.1, 125 to 37.6, 129 to 35.7, 137 to 33.6, 145 to 30.6,
        154 to 27.6, 161 to 25.1, 169 to 22.6, 176 to 20.0, 183 to 17.6, 189 to 15.1, 196 to 12.6, 203 to 10.0, 208 to 7.5,
        214 to 5.0, 220 to 2.4, 224 to 0.0, 230 to -2.9, 233 to -5.0, 237 to -7.5, 241 to -10.0, 244 to -12.3, 247 to -15.0,
        252 to -20.0, 255 to -22.846)

    internal fun tempPa(adc: Int): Double {
        for (k in 1 until PA_TEMP.size) {
            val (a0, t0) = PA_TEMP[k - 1]; val (a1, t1) = PA_TEMP[k]
            if (adc <= a1) return t0 + (t1 - t0) * (adc - a0) / (a1 - a0).toDouble()
        }
        return PA_TEMP.last().second
    }

    /** Reads a 256-byte frame. */
    fun lis(d: ByteArray): Trame {
        require(d.size >= 256)
        val l = Lecteur(d, 0)
        val id = l.bits(2).toInt()
        val type = l.bits(6).toInt()
        val m = ArrayList<Mesure>()
        var message: String? = null
        if (id != 3) {
            fun ajoute(g: String, nom: String, n: Int, aff: (Long) -> String) { val v = l.bits(n); m += Mesure(g, nom, aff(v), v) }
            val mv = { v: Long -> "$v mV" }; val ma = { v: Long -> "$v mA" }; val brut = { v: Long -> v.toString() }
            val deg = { v: Long -> s8(v).toString() + " °C" }
            ajoute("Énergie", "Tension photo 1", 16, mv); ajoute("Énergie", "Tension photo 2", 16, mv); ajoute("Énergie", "Tension photo 3", 16, mv)
            ajoute("Énergie", "Courant photo total", 16, ma); ajoute("Énergie", "Tension batterie", 16, mv); ajoute("Énergie", "Courant système", 16, ma)
            ajoute("Énergie", "Redémarrages", 16, brut); ajoute("Énergie", "Erreurs logicielles EPS", 16, brut)
            ajoute("Énergie", "Temp. convertisseur 1", 8, deg); ajoute("Énergie", "Temp. convertisseur 2", 8, deg); ajoute("Énergie", "Temp. convertisseur 3", 8, deg)
            ajoute("Énergie", "Temp. batterie", 8, deg)
            ajoute("Énergie", "Verrouillages 5 V", 8, brut); ajoute("Énergie", "Verrouillages 3,3 V", 8, brut)
            ajoute("Énergie", "Cause de redémarrage", 8) { v -> listOf("mise sous tension", "externe", "baisse de tension", "chien de garde", "JTAG", "autre").getOrElse(v.toInt()) { v.toString() } }
            ajoute("Énergie", "Suivi du point de puissance", 8) { v -> listOf("matériel", "MPPT", "fixe logiciel").getOrElse(v.toInt()) { v.toString() } }
            ajoute("Panneaux", "Capteur solaire X+", 10, brut); ajoute("Panneaux", "Capteur solaire Y+", 10, brut); ajoute("Panneaux", "Capteur solaire Z+", 10, brut)
            ajoute("Panneaux", "Temp. panneau X+", 10) { v -> f1(-0.2073 * v + 158.239) + " °C" }
            ajoute("Panneaux", "Temp. panneau X−", 10) { v -> f1(-0.2083 * v + 159.227) + " °C" }
            ajoute("Panneaux", "Temp. panneau Y+", 10) { v -> f1(-0.2076 * v + 158.656) + " °C" }
            ajoute("Panneaux", "Temp. panneau Y−", 10) { v -> f1(-0.2087 * v + 159.045) + " °C" }
            ajoute("Panneaux", "Bus 3,3 V tension", 10, brut); ajoute("Panneaux", "Bus 3,3 V courant", 10, brut); ajoute("Panneaux", "Bus 5 V tension", 10, brut)
            ajoute("Radio", "Doppler récepteur", 8, brut); ajoute("Radio", "RSSI récepteur", 8, brut)
            ajoute("Radio", "Temp. carte radio", 8) { v -> f1(-0.857 * v + 193.672) + " °C" }
            ajoute("Radio", "Courant réception", 8) { v -> f1(0.636 * v) + " mA" }
            ajoute("Radio", "Courant émission 3,3 V", 8) { v -> f1(0.636 * v) + " mA" }
            ajoute("Radio", "Courant émission 5 V", 8) { v -> f1(1.272 * v) + " mA" }
            ajoute("Ampli", "Puissance réfléchie", 8) { v -> f1(0.005 * v.toDouble().pow(2.0629)) + " mW" }
            ajoute("Ampli", "Puissance directe", 8) { v -> f1(0.005 * v.toDouble().pow(2.0629)) + " mW" }
            ajoute("Ampli", "Temp. ampli", 8) { v -> f1(tempPa(v.toInt())) + " °C" }
            ajoute("Ampli", "Courant ampli", 8) { v -> f1(0.5496 * v + 2.5435) + " mA" }
            ajoute("Antennes", "Temp. antenne 0", 8, brut); ajoute("Antennes", "Temp. antenne 1", 8, brut)
            val oui = { v: Long -> if (v != 0L) "oui" else "non" }
            for (k in 0..3) ajoute("Antennes", "Antenne $k déployée", 1, oui)
            ajoute("Logiciel", "Numéro de séquence", 24, brut)
            ajoute("Logiciel", "Commandes DTMF", 6, brut); ajoute("Logiciel", "Dernière commande DTMF", 5, brut); ajoute("Logiciel", "Commande DTMF réussie", 1, oui)
            for (n in listOf("Données ASIB valides", "Données EPS valides", "Données ampli valides", "Données radio valides", "Données MSE valides",
                "Données antennes bus B valides", "Données antennes bus A valides", "En éclipse", "En mode sûr", "ABF matériel", "ABF logiciel",
                "Attente de déploiement au démarrage")) ajoute("Logiciel", n, 1, oui)
            val g = genre(type)
            if (g.startsWith("FM")) {
                val sb = StringBuilder()
                for (k in 56 until 256) {
                    val c = d[k].toInt() and 0xFF
                    if (c == 0) break
                    sb.append(if (c in 0x20..0x7E) c.toChar() else if (c == 10 || c == 13) ' ' else '·')
                }
                message = sb.toString().trim()
            }
        }
        return Trame(id, type, genre(type), satellite(id), m, message)
    }
}
