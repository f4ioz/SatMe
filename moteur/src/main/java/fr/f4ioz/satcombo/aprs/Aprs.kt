/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.aprs

/**
 * What an APRS frame says (APRS 1.01): positions (plain, compressed,
 * Mic-E), messages and their acks, status, objects, weather, telemetry.
 * What is not understood stays readable as raw text: nothing is dropped.
 */
data class Paquet(
    val trame: Trame,
    /** When it was heard (epoch ms). */
    val quand: Long,
    val type: TypeAprs,
    val lat: Double? = null,
    val lon: Double? = null,
    /** Symbol table + code ("/>" a car, "/-" a house…). */
    val symbole: String? = null,
    val commentaire: String = "",
    /** Message: the addressee (ANSRVR…), the text and its number. */
    val destinataire: String? = null,
    val message: String? = null,
    val idMessage: String? = null,
    /** Object or item name. */
    val nom: String? = null,
    /** Mic-E: speed (km/h), course (°), status ("En route"…). */
    val vitesseKmh: Int? = null,
    val cap: Int? = null,
    val etatMicE: String? = null,
    /** Altitude in metres (Mic-E "xxx}", or "/A=001234" in feet). */
    val altitudeM: Int? = null,
    /** Sent by this phone, not heard. */
    val emis: Boolean = false,
    /** A weather station's report: wind, temperature, rain, pressure. */
    val meteo: Meteo? = null,
) {
    val source: String get() = trame.source.toString()

    /** Relayed by the ISS digipeater (RS0ISS, or its ARISS alias, marked as repeated). */
    val viaIss: Boolean
        get() = trame.relais.any { it.repete && it.indicatif in RELAIS_ISS }

    /** Part of APRS Thursday: to or about the HOTG group (ANSRVR, APRSPH). */
    val hotg: Boolean
        get() = (destinataire?.uppercase() in setOf("ANSRVR", "APRSPH") ||
            source.startsWith("ANSRVR")) && (message ?: "").uppercase().contains("HOTG") ||
            (message ?: "").uppercase().startsWith("CQ HOTG")

    companion object {
        val RELAIS_ISS = setOf("RS0ISS", "ARISS", "NA1SS", "APRSAT")
    }
}

/**
 * A weather report (APRS 1.01 chapter 12), in metric units. Each field is
 * null when the station does not send it ("..." in the frame).
 */
data class Meteo(
    val ventDeg: Int? = null,
    val ventKmh: Int? = null,
    val rafaleKmh: Int? = null,
    val temperatureC: Double? = null,
    val pluie1hMm: Double? = null,
    val humidite: Int? = null,
    val pressionHpa: Double? = null,
) {
    val vide: Boolean get() = this == Meteo()

    companion object {
        private fun mph(v: Int) = (v * 1.609344).toInt()

        /**
         * The weather fields at the start of [s]: "ddd/sss" (after a position)
         * or "cddd" + "sddd" (positionless), then gGGG tTTT rRRR hHH bBBBBB.
         * Returns the report and the comment left after it.
         */
        fun lit(s: String): Pair<Meteo, String> {
            var m = Meteo()
            var i = 0
            fun nombre(n: Int): Int? {
                if (i + n > s.length) return null
                val v = s.substring(i, i + n)
                i += n
                return v.trim().toIntOrNull()
            }
            if (s.length >= 7 && s[3] == '/' && s.substring(0, 3).all { it.isDigit() || it == '.' || it == ' ' }) {
                val d = nombre(3); i++
                val v = nombre(3)
                m = m.copy(ventDeg = d?.takeIf { it in 1..360 } ?: d?.let { 0 }, ventKmh = v?.let(::mph))
            }
            while (i < s.length) {
                val c = s[i]
                val n = when (c) { 'c', 's', 'g', 't', 'r', 'p', 'P' -> 3; 'h' -> 2; 'b' -> 5; 'L', 'l' -> 3; else -> break }
                if (i + 1 + n > s.length) break
                val brut = s.substring(i + 1, i + 1 + n)
                if (!brut.all { it.isDigit() || it == '.' || it == ' ' || it == '-' }) break
                i += 1 + n
                val v = brut.trim().toIntOrNull()
                m = when (c) {
                    'c' -> m.copy(ventDeg = v)
                    's' -> m.copy(ventKmh = v?.let(::mph))
                    'g' -> m.copy(rafaleKmh = v?.let(::mph))
                    't' -> m.copy(temperatureC = v?.let { Math.round((it - 32) * 50 / 9.0) / 10.0 })
                    'r' -> m.copy(pluie1hMm = v?.let { Math.round(it * 2.54) / 10.0 })
                    'h' -> m.copy(humidite = v?.let { if (it == 0) 100 else it })
                    'b' -> m.copy(pressionHpa = v?.let { it / 10.0 })
                    else -> m
                }
            }
            return m to s.substring(i).trim()
        }
    }
}

enum class TypeAprs { POSITION, MESSAGE, ACCUSE, STATUT, OBJET, METEO, TELEMETRIE, TIERS, AUTRE }

object Aprs {

    fun lit(t: Trame, quand: Long = System.currentTimeMillis()): Paquet {
        val s = t.texte
        val base = Paquet(t, quand, TypeAprs.AUTRE, commentaire = s.trimEnd('\r', '\n'))
        if (s.isEmpty()) return base
        return runCatching {
            when (s[0]) {
                '!', '=' -> position(base, s, 1)
                '/', '@' -> position(base, s, 8)
                '`', '\'', 0x1C.toChar(), 0x1D.toChar() -> micE(base, t, s)
                ':' -> message(base, s)
                '>' -> base.copy(type = TypeAprs.STATUT, commentaire = s.substring(1).trimEnd('\r', '\n'))
                ';' -> objet(base, s)
                ')' -> item(base, s)
                '_' -> {
                    // Positionless: "_MMDDhhmm" then the fields.
                    val (m, reste) = Meteo.lit(s.drop(9).trimEnd('\r', '\n'))
                    base.copy(type = TypeAprs.METEO, meteo = m.takeUnless { it.vide }, commentaire = reste)
                }
                'T' -> base.copy(type = TypeAprs.TELEMETRIE)
                '}' -> base.copy(type = TypeAprs.TIERS, commentaire = s.substring(1).trimEnd('\r', '\n'))
                '$' -> nmea(base, s)
                else -> null
            }
        }.getOrNull() ?: base
    }

    /** Plain "4903.50N/07201.75W-" or compressed "/5L!!<*e7>7P[", starting at [i]. */
    private fun position(base: Paquet, s: String, i: Int): Paquet? {
        if (s.length <= i) return null
        val c = s[i]
        val p = (if (c.isDigit() || c == ' ') positionClaire(s, i) else positionCompressee(s, i))
            ?: return null
        val (lat, lon, symbole, fin) = p
        val type = if (symbole.endsWith("_")) TypeAprs.METEO else TypeAprs.POSITION
        var (alt, reste) = altitudePieds(s.substring(minOf(fin, s.length)).trimEnd('\r', '\n'))
        var meteo: Meteo? = null
        if (type == TypeAprs.METEO) {
            val (m, r) = Meteo.lit(reste)
            meteo = m.takeUnless { it.vide }; reste = r
        }
        return base.copy(type = type, lat = lat, lon = lon, symbole = symbole,
            commentaire = reste, altitudeM = alt, meteo = meteo)
    }

    /** "/A=001234" (feet) anywhere in a comment: taken out, in metres. */
    private fun altitudePieds(c: String): Pair<Int?, String> {
        val m = Regex("/A=(-?\\d{6})").find(c) ?: return null to c
        return (m.groupValues[1].toInt() * 0.3048).toInt() to c.removeRange(m.range).trim()
    }

    /**
     * Mic-E comment: a radio type code first (']' Kenwood D700, '>' D7…),
     * then maybe "xxx}" — altitude in base 91, metres above −10 km.
     */
    private fun commentaireMicE(c: String): Pair<Int?, String> {
        var t = c
        if (t.isNotEmpty() && t[0] in "]>`'") t = t.substring(1)
        if (t.length >= 4 && t[3] == '}' && t.substring(0, 3).all { it.code in 33..123 }) {
            return (base91(t.substring(0, 3)) - 10000) to t.substring(4).trim()
        }
        return null to t.trim()
    }

    private data class Pos(val lat: Double, val lon: Double, val symbole: String, val fin: Int)

    private fun positionClaire(s: String, i: Int): Pos? {
        if (s.length < i + 19) return null
        // Ambiguity: spaces stand for hidden digits; take the middle of the square.
        val la = s.substring(i, i + 8).replace(' ', '5')
        val lo = s.substring(i + 9, i + 18).replace(' ', '5')
        val lat = la.substring(0, 2).toInt() + la.substring(2, 7).toDouble() / 60
        val lon = lo.substring(0, 3).toInt() + lo.substring(3, 8).toDouble() / 60
        val ns = la[7]; val ew = lo[8]
        if (ns !in "NSns" || ew !in "EWew" || lat > 90 || lon > 180) return null
        return Pos(if (ns in "Ss") -lat else lat, if (ew in "Ww") -lon else lon,
            "${s[i + 8]}${s[i + 18]}", i + 19)
    }

    private fun base91(s: String): Int = s.fold(0) { acc, ch -> acc * 91 + (ch.code - 33) }

    private fun positionCompressee(s: String, i: Int): Pos? {
        if (s.length < i + 13) return null
        val table = s[i]
        if (!(table == '/' || table == '\\' || table.isUpperCase() || table in 'a'..'j')) return null
        val lat = 90 - base91(s.substring(i + 1, i + 5)) / 380926.0
        val lon = -180 + base91(s.substring(i + 5, i + 9)) / 190463.0
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return Pos(lat, lon, "$table${s[i + 9]}", i + 13)
    }

    private val ETATS_MIC_E = mapOf(
        7 to "Off duty", 6 to "En route", 5 to "In service", 4 to "Returning",
        3 to "Committed", 2 to "Special", 1 to "Priority", 0 to "Emergency")

    /** Mic-E: latitude and flags in the destination field, the rest in 8 bytes. */
    private fun micE(base: Paquet, t: Trame, s: String): Paquet? {
        val d = t.destination.indicatif
        if (d.length != 6 || s.length < 9) return null
        val chiffres = IntArray(6)
        var bits = 0; var perso = false
        for (k in 0 until 6) {
            val c = d[k]
            chiffres[k] = when (c) {
                in '0'..'9' -> c - '0'
                in 'A'..'J' -> { if (k < 3) { bits = bits or (4 shr k); perso = true }; c - 'A' }
                in 'P'..'Y' -> { if (k < 3) bits = bits or (4 shr k); c - 'P' }
                'K' -> { if (k < 3) { bits = bits or (4 shr k); perso = true }; 0 }
                'L' -> 0
                'Z' -> { if (k < 3) bits = bits or (4 shr k); 0 }
                else -> return null
            }
        }
        val nord = d[3] in 'P'..'Z'
        val decalage = d[4] in 'P'..'Z'
        val ouest = d[5] in 'P'..'Z'
        var lat = chiffres[0] * 10 + chiffres[1] + (chiffres[2] * 10 + chiffres[3] + (chiffres[4] * 10 + chiffres[5]) / 100.0) / 60
        if (!nord) lat = -lat
        val b = s.substring(1).map { it.code }
        var deg = b[0] - 28 + if (decalage) 100 else 0
        if (deg in 180..189) deg -= 80 else if (deg in 190..199) deg -= 190
        var min = b[1] - 28
        if (min >= 60) min -= 60
        val cent = b[2] - 28
        var lon = deg + (min + cent / 100.0) / 60
        if (ouest) lon = -lon
        var noeuds = (b[3] - 28) * 10 + (b[4] - 28) / 10
        if (noeuds >= 800) noeuds -= 800
        var cap = ((b[4] - 28) % 10) * 100 + (b[5] - 28)
        if (cap >= 400) cap -= 400
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        val etat = if (perso) "Custom-$bits" else ETATS_MIC_E[bits]
        val (alt, texte) = commentaireMicE(s.substring(9).trimEnd('\r', '\n'))
        return base.copy(type = TypeAprs.POSITION, lat = lat, lon = lon,
            symbole = "${s[8]}${s[7]}", commentaire = texte, altitudeM = alt,
            vitesseKmh = (noeuds * 1.852).toInt().takeIf { noeuds > 0 },
            cap = cap.takeIf { noeuds > 0 && cap in 1..360 }, etatMicE = etat)
    }

    /** ":ANSRVR   :CQ HOTG hello{12" — addressee padded to 9 characters. */
    private fun message(base: Paquet, s: String): Paquet? {
        if (s.length < 11 || s[10] != ':') return null
        val qui = s.substring(1, 10).trim()
        var corps = s.substring(11).trimEnd('\r', '\n')
        val accuse = Regex("^(ack|rej)([A-Za-z0-9}]{1,5})$").find(corps)
        if (accuse != null) {
            return base.copy(type = TypeAprs.ACCUSE, destinataire = qui, message = corps,
                idMessage = accuse.groupValues[2], commentaire = "")
        }
        var id: String? = null
        val k = corps.lastIndexOf('{')
        if (k >= 0 && corps.length - k in 2..7) { id = corps.substring(k + 1).removeSuffix("}"); corps = corps.substring(0, k) }
        return base.copy(type = TypeAprs.MESSAGE, destinataire = qui, message = corps,
            idMessage = id, commentaire = "")
    }

    /** ";NAME     *DDHHMMz4903.50N/07201.75W-comment" */
    private fun objet(base: Paquet, s: String): Paquet? {
        if (s.length < 18) return null
        val nom = s.substring(1, 10).trim()
        val p = position(base, s, 18) ?: return base.copy(type = TypeAprs.OBJET, nom = nom)
        return p.copy(type = TypeAprs.OBJET, nom = nom)
    }

    /** ")NAME!4903.50N/07201.75W-" */
    private fun item(base: Paquet, s: String): Paquet? {
        val k = s.indexOfAny(charArrayOf('!', '_'), 1).takeIf { it in 4..10 } ?: return null
        val nom = s.substring(1, k)
        val p = position(base, s, k + 1) ?: return base.copy(type = TypeAprs.OBJET, nom = nom)
        return p.copy(type = TypeAprs.OBJET, nom = nom)
    }

    /** Raw GPS sentences some trackers send: $GPRMC and $GPGGA give a position. */
    private fun nmea(base: Paquet, s: String): Paquet? {
        val f = s.trimEnd('\r', '\n').substringBefore('*').split(',')
        val (la, ns, lo, ew) = when {
            f[0].endsWith("RMC") && f.size > 6 && f[2] == "A" -> listOf(f[3], f[4], f[5], f[6])
            f[0].endsWith("GGA") && f.size > 5 -> listOf(f[2], f[3], f[4], f[5])
            else -> return null
        }
        if (la.length < 4 || lo.length < 5) return null
        var lat = la.substring(0, 2).toInt() + la.substring(2).toDouble() / 60
        var lon = lo.substring(0, 3).toInt() + lo.substring(3).toDouble() / 60
        if (ns == "S") lat = -lat
        if (ew == "W") lon = -lon
        return base.copy(type = TypeAprs.POSITION, lat = lat, lon = lon, commentaire = "")
    }
}
