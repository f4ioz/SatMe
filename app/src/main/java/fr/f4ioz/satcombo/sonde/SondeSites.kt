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
 * Où sont lâchées les radiosondes qu'on peut espérer entendre depuis la France,
 * et sur quoi les chercher.
 *
 * Les coordonnées sont celles des aires de lâcher, pas celles des stations
 * météo : entre les deux il y a parfois deux kilomètres, ce qui compte quand on
 * cherche à confirmer qu'un ballon vient bien de là. Les fréquences bougent —
 * une station peut changer de canal du jour au lendemain — donc elles servent à
 * pointer la clé au bon endroit, pas à identifier une sonde. C'est le numéro de
 * série transmis qui identifie.
 *
 * Les heures de lâcher sont le détail que tout le monde rate : Météo-France
 * lâche à 23 h 11 et 11 h 11 UTC, soit quarante-neuf minutes *avant* l'heure
 * synoptique, pour que le ballon soit à trente kilomètres pile à l'heure ronde.
 * Écouter à minuit et à midi, c'est arriver après la bataille.
 */
object SondeSites {

    /** Un site de lâcher. */
    data class Site(
        /** Indicatif OMM, ou une chaîne libre pour les sites occasionnels. */
        val wmo: String,
        val name: String,
        val country: String,
        val lat: Double,
        val lon: Double,
        /** Altitude du site, en mètres. */
        val altM: Int,
        /** Type de sonde habituel : "RS41", "M20", "M10", "DFM". */
        val sonde: String,
        /** Fréquences relevées, en kilohertz. */
        val freqKhz: List<Int>,
        /** Heures de lâcher, en heures UTC décimales (23,183 = 23 h 11). */
        val launchesUtc: List<Double>,
        /** Site occasionnel : campagnes, recherche, pas tous les jours. */
        val occasional: Boolean = false
    ) {
        /** Fréquence principale, en hertz. */
        val mainHz: Long get() = (freqKhz.firstOrNull() ?: 0) * 1000L
    }

    /** Lâcher de nuit de Météo-France, 23 h 11 UTC. */
    const val MF_NIGHT = 23.0 + 11.0 / 60.0

    /** Lâcher de jour de Météo-France, 11 h 11 UTC. */
    const val MF_DAY = 11.0 + 11.0 / 60.0

    private val MF = listOf(MF_NIGHT, MF_DAY)
    private val SYNOPTIC2 = listOf(0.0, 12.0)
    private val SYNOPTIC4 = listOf(0.0, 6.0, 12.0, 18.0)

    /** Les stations françaises en service. */
    val FRANCE = listOf(
        Site("07110", "Brest-Guipavas", "FR", 48.44425, -4.41238, 95, "M20",
            listOf(404_000), MF),
        Site("07145", "Trappes", "FR", 48.773097, 2.009377, 168, "M10",
            listOf(401_000), MF),
        Site("07510", "Bordeaux-Mérignac", "FR", 44.831398, -0.691992, 49, "M20",
            listOf(402_800, 402_400), MF),
        Site("07645", "Nîmes-Courbessac", "FR", 43.856576, 4.405732, 60, "M20",
            listOf(405_000, 403_800), MF),
        Site("07761", "Ajaccio", "FR", 41.918003, 8.792084, 6, "M20",
            listOf(403_000), MF))

    /** Sites français occasionnels : campagnes, recherche, essais constructeur. */
    val FRANCE_OCCASIONAL = listOf(
        Site("LAN", "CMS Lannion", "FR", 48.7506, -3.4729, 80, "M20",
            listOf(402_000), emptyList(), occasional = true),
        Site("QUI", "Quimper", "FR", 47.99975, -4.17785, 90, "M20",
            listOf(403_000), emptyList(), occasional = true),
        Site("OHP", "OHP Saint-Michel", "FR", 43.9314, 5.7125, 650, "M10",
            listOf(403_500), emptyList(), occasional = true),
        Site("URY", "Ury (Meteomodem)", "FR", 48.3183, 2.5936, 120, "M20",
            listOf(403_000), emptyList(), occasional = true),
        Site("TRA", "Trainou", "FR", 47.9647, 2.1125, 130, "M10",
            listOf(403_000), emptyList(), occasional = true),
        Site("SIR", "SIRTA Palaiseau", "FR", 48.7130, 2.2080, 156, "M10",
            listOf(403_000), emptyList(), occasional = true),
        Site("BOU", "Bourges", "FR", 47.0592, 2.3697, 161, "DFM",
            listOf(403_000), emptyList(), occasional = true))

    /** Les voisins dont les sondes atteignent régulièrement le territoire. */
    val NEIGHBOURS = listOf(
        Site("03808", "Camborne", "GB", 50.218698, -5.326914, 88, "RS41",
            listOf(405_700), SYNOPTIC2),
        Site("03953", "Valentia", "IE", 51.9381, -10.2433, 14, "RS41",
            listOf(400_500), SYNOPTIC2),
        Site("03743", "Larkhill", "GB", 51.2000, -1.8000, 132, "RS41",
            listOf(403_700), SYNOPTIC4),
        Site("06447", "Uccle", "BE", 50.7973, 4.3581, 100, "RS41",
            listOf(403_000), listOf(12.0)),
        Site("06458", "Beauvechain", "BE", 50.7586, 4.7683, 110, "DFM",
            listOf(403_000), SYNOPTIC2),
        Site("06260", "De Bilt", "NL", 52.1017, 5.1783, 4, "RS41",
            listOf(403_900), SYNOPTIC2),
        Site("06610", "Payerne", "CH", 46.8123, 6.9422, 491, "RS41",
            listOf(403_500), SYNOPTIC2),
        Site("10410", "Essen", "DE", 51.4053, 6.9672, 153, "RS41",
            listOf(405_300), SYNOPTIC2),
        Site("10618", "Idar-Oberstein", "DE", 49.6928, 7.3300, 376, "RS41",
            listOf(402_700), SYNOPTIC4),
        Site("10739", "Stuttgart-Schnarrenberg", "DE", 48.8281, 9.2000, 315, "RS41",
            listOf(404_500), SYNOPTIC2),
        Site("16045", "Cuneo-Levaldigi", "IT", 44.5478, 7.6122, 384, "RS41",
            listOf(403_700), SYNOPTIC2),
        Site("16044", "Udine-Campoformido", "IT", 46.0333, 13.1833, 94, "RS41",
            listOf(404_000), SYNOPTIC2),
        Site("08190", "Barcelone", "ES", 41.3833, 2.1167, 98, "M20",
            listOf(403_000), SYNOPTIC2),
        Site("08430", "Murcie", "ES", 38.0000, -1.1667, 62, "M10",
            listOf(403_400), SYNOPTIC2))

    /** Tout ce qui vole, sites occasionnels compris. */
    val ALL: List<Site> = FRANCE + FRANCE_OCCASIONAL + NEIGHBOURS

    /** Les sites triés par distance depuis un QTH. */
    fun nearest(lat: Double, lon: Double, max: Int = 6,
                includeOccasional: Boolean = false): List<Pair<Site, Double>> =
        (if (includeOccasional) ALL else ALL.filter { !it.occasional })
            .map { it to Geo.distanceKm(lat, lon, it.lat, it.lon) }
            .sortedBy { it.second }
            .take(max)

    /**
     * Le site le plus probable pour une sonde entendue à une position donnée.
     *
     * Ce n'est qu'une présomption : un ballon dérive de cinquante à cent
     * vingt kilomètres, donc au-delà de deux cents on ne se prononce pas.
     */
    fun likelyOrigin(lat: Double, lon: Double, type: String = ""): Site? {
        val cands = ALL.filter { type.isBlank() || it.sonde == type }
            .ifEmpty { ALL }
        val best = cands.minByOrNull { Geo.distanceKm(lat, lon, it.lat, it.lon) } ?: return null
        return if (Geo.distanceKm(lat, lon, best.lat, best.lon) <= 200.0) best else null
    }

    // ---------------------------------------------------------- le plan de balayage

    /** Première fréquence du plan de balayage, en hertz. */
    const val SCAN_FROM_HZ = 400_150_000L

    /**
     * Dernière fréquence du plan de balayage, en hertz.
     *
     * La bande météo va officiellement jusqu'à 406 MHz, mais on s'arrête à
     * 405,9 : au-dessus commencent les balises de détresse COSPAS-SARSAT, et il
     * n'y a aucune raison de promener un récepteur dessus. Cette limite n'est
     * pas un réglage, elle est en dur.
     */
    const val SCAN_TO_HZ = 405_900_000L

    /** Pas de balayage, en hertz : les sondes se calent au multiple de 10 kHz. */
    const val SCAN_STEP_HZ = 10_000L

    /** Une fréquence est-elle dans la bande autorisée à l'écoute ? */
    fun inBand(hz: Long): Boolean = hz in SCAN_FROM_HZ..SCAN_TO_HZ

    /**
     * Les fréquences à essayer, dans l'ordre : d'abord celles des stations
     * proches, ensuite le balayage complet. Chercher d'abord là où on sait
     * qu'il y a quelque chose fait gagner plusieurs minutes à chaque lâcher.
     */
    fun scanPlan(lat: Double, lon: Double): List<Long> {
        val out = LinkedHashSet<Long>()
        for ((site, _) in nearest(lat, lon, max = 8, includeOccasional = true)) {
            for (k in site.freqKhz) {
                val hz = k * 1000L
                if (inBand(hz)) out += hz
            }
        }
        var hz = SCAN_FROM_HZ
        while (hz <= SCAN_TO_HZ) { out += hz; hz += SCAN_STEP_HZ }
        return out.toList()
    }

    /** Largeur de filtre conseillée pour un type de sonde, en hertz. */
    fun bandwidthFor(sonde: String): Int = when (sonde) {
        "M10", "M20" -> Meteomodem.BANDWIDTH_HZ
        else -> Rs41.BANDWIDTH_HZ
    }

    /** Débit binaire d'un type de sonde, en bauds. */
    fun baudFor(sonde: String): Double = when (sonde) {
        "M10" -> Meteomodem.M10_BAUD
        "M20" -> Meteomodem.M20_BAUD
        else -> Rs41.BAUD
    }

    /**
     * Minutes restant avant le prochain lâcher d'un site, ou -1 si le site n'a
     * pas d'horaire régulier. [nowUtcMinutes] est l'heure UTC du jour, en
     * minutes depuis minuit.
     */
    fun minutesToNextLaunch(site: Site, nowUtcMinutes: Int): Int {
        if (site.launchesUtc.isEmpty()) return -1
        var best = Int.MAX_VALUE
        for (h in site.launchesUtc) {
            val m = Math.round(h * 60.0).toInt()
            var d = m - nowUtcMinutes
            if (d < 0) d += 24 * 60
            if (d < best) best = d
        }
        return best
    }

    /**
     * Est-on dans une fenêtre d'écoute utile pour ce site ? On ouvre dix
     * minutes avant le lâcher et on laisse tourner trois heures : c'est la
     * durée d'un vol complet, montée, éclatement et descente.
     */
    fun listeningNow(site: Site, nowUtcMinutes: Int): Boolean {
        if (site.launchesUtc.isEmpty()) return false
        for (h in site.launchesUtc) {
            val m = Math.round(h * 60.0).toInt()
            var since = nowUtcMinutes - m
            if (since < 0) since += 24 * 60
            if (since <= 180 || since >= 24 * 60 - 10) return true
        }
        return false
    }
}
