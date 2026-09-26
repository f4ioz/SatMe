/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.sonde

/**
 * Le vol d'une radiosonde, du premier point entendu au dernier.
 *
 * C'est le cœur de la chasse. Une sonde monte deux heures, éclate vers trente
 * kilomètres, retombe en une demi-heure et se tait au sol — ou continue
 * d'émettre pendant des heures si la pile tient. Ce qui décide de la retrouver
 * ou non tient en trois chiffres : le dernier point sûr, l'endroit où elle a
 * éclaté, et le point d'impact extrapolé quand on a perdu le signal avant le
 * sol.
 *
 * La classe est volontairement sans Android : elle se teste entièrement, et
 * c'est le genre de calcul où une erreur coûte un après-midi de marche.
 */
class SondeFlight(val serial: String, val type: String) {

    private val points = ArrayList<SondeFrame>(4096)

    /** Tous les points reçus, du plus ancien au plus récent. */
    val track: List<SondeFrame> get() = points

    /** Dernier point reçu, sûr ou non. */
    var last: SondeFrame? = null
        private set

    /** Dernier point à quatre satellites ou plus : celui vers lequel on marche. */
    var lastTrusted: SondeFrame? = null
        private set

    /** Point le plus haut retenu, qui est aussi l'éclatement du ballon. */
    var burst: SondeFrame? = null
        private set

    /** Nombre de trames retenues. */
    val count: Int get() = points.size

    /** Fréquence de la dernière réception, en hertz. */
    var freqHz: Long = 0L
        private set

    /**
     * Ajoute une trame. Rend vrai si elle a été retenue.
     *
     * Le filtre de continuité est ce qui protège des trames fausses d'un
     * décodeur sans CRC : entre deux points d'une même sonde il ne peut pas y
     * avoir plus d'une dizaine de kilomètres, même à la vitesse d'un jet
     * stream. Un point qui saute de cent kilomètres est un octet mal lu, pas un
     * ballon.
     */
    fun add(f: SondeFrame): Boolean {
        if (!f.plausible) return false
        val prev = last
        if (prev != null && f.trusted && prev.trusted) {
            val jump = Geo.distanceKm(prev.lat, prev.lon, f.lat, f.lon)
            val dt = elapsedSec(prev, f)
            // Cent mètres par seconde est déjà généreux : c'est le maximum
            // relevé dans un courant-jet. Vingt kilomètres de tolérance en plus
            // couvrent un trou de réception d'une poignée de minutes.
            if (jump > 0.1 * dt + 20.0) return false
        }
        points += f
        last = f
        if (f.freqHz > 0) freqHz = f.freqHz
        if (f.trusted) {
            lastTrusted = f
            val b = burst
            if (b == null || f.altM > b.altM) burst = f
        }
        return true
    }

    /**
     * Temps écoulé entre deux trames, en secondes.
     *
     * On demande l'heure à la sonde avant de la demander au téléphone. Les deux
     * donnent le même chiffre quand on écoute en direct, mais pas quand on
     * relit un enregistrement : un fichier d'une heure se redécode en deux
     * minutes, l'horloge du téléphone avance alors trente fois trop lentement
     * par rapport au vol, et le contrôle de continuité — qui autorise cent
     * mètres par seconde — refuserait des points parfaitement bons. L'horloge
     * GPS de la sonde, elle, dit toujours la vérité sur le vol, qu'on l'écoute
     * en direct, en différé ou depuis un journal relu six mois plus tard.
     */
    private fun elapsedSec(prev: SondeFrame, f: SondeFrame): Double {
        if (f.timeUtcMs > 0L && prev.timeUtcMs > 0L && f.timeUtcMs > prev.timeUtcMs) {
            return (f.timeUtcMs - prev.timeUtcMs) / 1000.0
        }
        if (f.heardAtMs > prev.heardAtMs) return (f.heardAtMs - prev.heardAtMs) / 1000.0
        return 1.0
    }

    /** La sonde a-t-elle éclaté ? On le sait quand elle est repassée sous son sommet. */
    val hasBurst: Boolean
        get() {
            val b = burst ?: return false
            val l = lastTrusted ?: return false
            return b.altM > 12_000.0 && l.altM < b.altM - 500.0
        }

    /** Altitude d'éclatement, en mètres, ou 0. */
    val burstAltM: Double get() = burst?.altM ?: 0.0

    /**
     * Vitesse de descente moyenne des dernières trames, en mètres par seconde,
     * comptée positive. Sert à extrapoler l'impact.
     */
    fun descentRate(samples: Int = 10): Double {
        val recent = points.filter { it.trusted }.takeLast(samples)
        if (recent.size < 2) return 0.0
        val v = recent.map { -it.climbMps }.filter { it > 0.5 }
        if (v.isEmpty()) return 0.0
        return v.average()
    }

    /**
     * Point d'impact estimé, ou null si la sonde monte encore ou si on n'a pas
     * de quoi extrapoler.
     *
     * L'extrapolation est délibérément simple : on prolonge le dernier vecteur
     * horizontal pendant le temps qu'il reste à tomber. Ce n'est pas un modèle
     * de vent, et cela ne prétend pas l'être — mais sur les mille derniers
     * mètres la dérive est faible et le résultat vaut mieux que rien. Un modèle
     * de vent complet demanderait des données que l'application n'a pas.
     */
    fun estimatedLanding(): Pair<Double, Double>? {
        val l = lastTrusted ?: return null
        if (l.altM <= 0.0) return null
        val rate = descentRate()
        if (rate < 1.0) return null
        val seconds = l.altM / rate
        if (seconds > 3600.0) return null
        val distM = l.speedMps * seconds
        val br = Math.toRadians(l.headingDeg)
        val dLat = distM * Math.cos(br) / 111_320.0
        val dLon = distM * Math.sin(br) /
            (111_320.0 * Math.cos(Math.toRadians(l.lat)).coerceAtLeast(0.05))
        return Pair(l.lat + dLat, l.lon + dLon)
    }

    /** Distance depuis le QTH jusqu'au dernier point sûr, en kilomètres. */
    fun distanceFromKm(lat: Double, lon: Double): Double {
        val l = lastTrusted ?: return 0.0
        return Geo.distanceKm(lat, lon, l.lat, l.lon)
    }

    /** Azimut depuis le QTH vers le dernier point sûr, en degrés. */
    fun bearingFrom(lat: Double, lon: Double): Double {
        val l = lastTrusted ?: return 0.0
        return Geo.bearingDeg(lat, lon, l.lat, l.lon)
    }

    /** Premier point du vol, ce qui donne l'heure et le lieu approximatifs du lâcher. */
    val first: SondeFrame? get() = points.firstOrNull { it.trusted }

    /** Durée écoutée, en secondes. */
    val durationSec: Long
        get() {
            val a = points.firstOrNull()?.heardAtMs ?: return 0L
            val b = points.lastOrNull()?.heardAtMs ?: return 0L
            return ((b - a) / 1000L).coerceAtLeast(0L)
        }

    /** Vide le vol, en gardant le nom. */
    fun clear() {
        points.clear()
        last = null
        lastTrusted = null
        burst = null
    }

    /** N'en garde qu'un point sur [step], pour ne pas faire un GPX de dix mégaoctets. */
    fun thinned(step: Int = 1): List<SondeFrame> {
        if (step <= 1) return points.filter { it.trusted }
        val t = points.filter { it.trusted }
        return t.filterIndexed { i, _ -> i % step == 0 || i == t.size - 1 }
    }
}

/**
 * Écriture des traces au format GPX et KML.
 *
 * Le GPX se charge dans OsmAnd ou dans un GPS de randonnée, le KML dans Google
 * Earth : ce sont les deux outils qu'un chasseur a sous la main. Les deux
 * exports portent les mêmes repères, parce qu'ils servent la même chose :
 * le dernier point sûr, l'éclatement, et l'impact estimé.
 */
object SondeExport {

    private fun esc(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun iso(ms: Long): String {
        if (ms <= 0L) return ""
        val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
        f.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return f.format(java.util.Date(ms))
    }

    fun gpx(flight: SondeFlight, step: Int = 1): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<gpx version=\"1.1\" creator=\"SatMe\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        append("  <metadata><name>").append(esc(flight.serial)).append("</name></metadata>\n")
        flight.lastTrusted?.let {
            append(wpt(it.lat, it.lon, it.altM, "Dernier point sûr", iso(it.timeUtcMs)))
        }
        flight.burst?.takeIf { flight.hasBurst }?.let {
            append(wpt(it.lat, it.lon, it.altM, "Éclatement", iso(it.timeUtcMs)))
        }
        flight.estimatedLanding()?.let { (la, lo) ->
            append(wpt(la, lo, 0.0, "Impact estimé", ""))
        }
        append("  <trk><name>").append(esc(flight.serial)).append(" ")
            .append(esc(flight.type)).append("</name><trkseg>\n")
        for (p in flight.thinned(step)) {
            append("      <trkpt lat=\"%.6f\" lon=\"%.6f\"><ele>%.1f</ele>"
                .format(java.util.Locale.US, p.lat, p.lon, p.altM))
            if (p.timeUtcMs > 0) append("<time>").append(iso(p.timeUtcMs)).append("</time>")
            append("</trkpt>\n")
        }
        append("  </trkseg></trk>\n</gpx>\n")
    }

    private fun wpt(lat: Double, lon: Double, alt: Double, name: String, time: String) =
        buildString {
            append("  <wpt lat=\"%.6f\" lon=\"%.6f\"><ele>%.1f</ele>"
                .format(java.util.Locale.US, lat, lon, alt))
            if (time.isNotEmpty()) append("<time>").append(time).append("</time>")
            append("<name>").append(esc(name)).append("</name></wpt>\n")
        }

    fun kml(flight: SondeFlight, step: Int = 1): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document>\n")
        append("  <name>").append(esc(flight.serial)).append("</name>\n")
        flight.lastTrusted?.let { pin("Dernier point sûr", it.lat, it.lon, it.altM) }
        flight.burst?.takeIf { flight.hasBurst }?.let { pin("Éclatement", it.lat, it.lon, it.altM) }
        flight.estimatedLanding()?.let { (la, lo) -> pin("Impact estimé", la, lo, 0.0) }
        append("  <Placemark><name>").append(esc(flight.type))
            .append("</name><LineString><altitudeMode>absolute</altitudeMode>\n")
        append("    <coordinates>")
        for (p in flight.thinned(step)) {
            append("%.6f,%.6f,%.1f ".format(java.util.Locale.US, p.lon, p.lat, p.altM))
        }
        append("</coordinates></LineString></Placemark>\n")
        append("</Document></kml>\n")
    }

    private fun StringBuilder.pin(name: String, lat: Double, lon: Double, alt: Double) {
        append("  <Placemark><name>").append(esc(name)).append("</name>")
        append("<Point><coordinates>%.6f,%.6f,%.1f</coordinates></Point></Placemark>\n"
            .format(java.util.Locale.US, lon, lat, alt))
    }

    /**
     * Une ligne de journal, lisible à l'œil et relisible par la machine.
     * Le format est volontairement plat : un jour où l'application ne sera plus
     * là, un tableur ouvrira encore le fichier.
     */
    fun csvHeader() = "utc;serial;type;lat;lon;alt_m;speed_mps;heading_deg;climb_mps;sats;batt_v;freq_hz\n"

    fun csvLine(f: SondeFrame): String = "%s;%s;%s;%.6f;%.6f;%.1f;%.1f;%.0f;%.1f;%d;%.1f;%d\n"
        .format(java.util.Locale.US, iso(f.timeUtcMs), f.serial, f.type,
            f.lat, f.lon, f.altM, f.speedMps, f.headingDeg, f.climbMps,
            f.sats, f.batteryV, f.freqHz)
}
