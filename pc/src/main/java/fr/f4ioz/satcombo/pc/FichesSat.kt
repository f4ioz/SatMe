/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

/**
 * The well-known satellites' cards: SatMe's own few lines about each (who,
 * when, what for, where, a story to tell at a stand), and the Wikipedia
 * articles ([articles], French first) its summary and photo are asked from
 * ([FichesSatPc]). Found by catalogue number, else by one of its names.
 */
object FichesSat {

    data class Fiche(val cle: String, val nom: String, val catnums: List<Int>, val noms: List<String>, val articles: List<Pair<String, String>>,
                     val pays: String, val operateur: String, val lancement: String, val mission: String, val orbite: String, val anecdote: String)

    val toutes = listOf(
        Fiche("iss", "Station spatiale internationale (ISS)", listOf(25544), listOf("ISS", "ZARYA"),
            listOf("fr" to "Station spatiale internationale", "en" to "International Space Station"),
            "International (États-Unis, Russie, Europe, Japon, Canada)", "NASA, Roscosmos, ESA, JAXA, ASC", "20 novembre 1998 (premier module, Zarya)",
            "Laboratoire habité en permanence depuis 2000", "≈ 420 km d’altitude, inclinée de 51,6°, un tour en 92 min",
            "Des élèves y parlent aux astronautes par radio (programme ARISS) ; son relais et ses images SSTV s’écoutent avec un simple talkie-walkie."),
        Fiche("css", "Station spatiale chinoise (Tiangong)", listOf(48274), listOf("CSS", "TIANHE", "TIANGONG"),
            listOf("fr" to "Station spatiale chinoise", "en" to "Tiangong space station"),
            "Chine", "Agence chinoise des vols habités (CMSA)", "29 avril 2021 (module central Tianhe)",
            "Station habitée chinoise", "≈ 390 km d’altitude, inclinée de 41,5°",
            "Elle emporte un équipement radioamateur : ses images SSTV se reçoivent depuis le sol."),
        Fiche("so50", "SO-50 (SaudiSat-1C)", listOf(27607), listOf("SO-50", "SAUDISAT-1C", "SAUDISAT 1C"),
            listOf("en" to "Saudi-OSCAR 50"),
            "Arabie saoudite", "KACST (Cité du roi Abdulaziz pour la science et la technologie)", "20 décembre 2002",
            "Relais FM radioamateur", "Orbite basse inclinée de 64,6°",
            "Plus de vingt ans en orbite et toujours un des relais FM les plus écoutés : un portatif et une petite antenne suffisent."),
        Fiche("ao91", "AO-91 (Fox-1B)", listOf(43017), listOf("AO-91", "FOX-1B", "RADFXSAT"),
            listOf("en" to "Fox-1B"),
            "États-Unis", "AMSAT-NA, avec l’université Vanderbilt", "18 novembre 2017",
            "Relais FM radioamateur et mesure des radiations", "Orbite polaire basse (héliosynchrone)",
            "Un CubeSat d’un seul cube de 10 cm ; ses batteries fatiguées, il ne fonctionne plus qu’au soleil."),
        Fiche("ao27", "AO-27 (Eyesat-1)", listOf(22825), listOf("AO-27", "EYESAT"),
            listOf("en" to "AMRAD-OSCAR 27"),
            "États-Unis", "AMRAD et Interferometrics", "26 septembre 1993",
            "Satellite commercial portant un relais FM radioamateur", "Orbite polaire basse (≈ 800 km)",
            "Un des tout premiers relais FM accessibles avec un simple portatif."),
        Fiche("rs44", "RS-44 (DOSAAF-85)", listOf(44909), listOf("RS-44", "DOSAAF-85", "DOSAAF"),
            listOf(),
            "Russie", "DOSAAF (Russie)", "26 décembre 2019",
            "Transpondeur linéaire radioamateur (BLU et CW)", "≈ 1 200 à 1 500 km d’altitude, inclinée de 82,5°",
            "Son orbite haute donne de longs passages : des contacts à travers toute l’Europe et jusqu’en Amérique."),
        Fiche("ao7", "AO-7", listOf(7530), listOf("AO-07", "AO-7", "OSCAR 7"),
            listOf("en" to "AMSAT-OSCAR 7"),
            "États-Unis, Canada, Allemagne, Australie (AMSAT)", "AMSAT", "15 novembre 1974",
            "Transpondeurs linéaires radioamateurs", "≈ 1 450 km d’altitude, inclinée de 102°",
            "Muet de 1981 à 2002, une batterie en court-circuit s’est rouverte : il revit depuis, au soleil, plus de 50 ans après son lancement !"),
        Fiche("fo29", "FO-29 (JAS-2)", listOf(24278), listOf("FO-29", "JAS-2", "JAS 2"),
            listOf(),
            "Japon", "JARL (union des radioamateurs japonais)", "17 août 1996",
            "Transpondeur linéaire radioamateur", "≈ 800 à 1 300 km d’altitude, inclinée de 98,5°",
            "Un satellite de la JARL qui relaie les radioamateurs depuis près de 30 ans."),
        Fiche("qo100", "QO-100 (Es’hail-2)", listOf(43700), listOf("QO-100", "ES'HAIL", "ESHAIL", "ES'HAIL-2", "ESHAIL-2"),
            listOf("en" to "Es'hail 2"),
            "Qatar", "Es’hailSat, avec AMSAT-DL et la QARS pour la partie radioamateur", "15 novembre 2018",
            "Télévision, et les premiers transpondeurs radioamateurs géostationnaires", "Géostationnaire, 35 786 km, vers 26° Est (au-dessus de l’Afrique)",
            "Toujours au même endroit du ciel : une parabole fixe suffit pour trafiquer jour et nuit, du Brésil à la Thaïlande."),
        Fiche("io117", "IO-117 (GreenCube)", listOf(53106), listOf("IO-117", "GREENCUBE"),
            listOf(),
            "Italie", "Université La Sapienza de Rome", "13 juillet 2022 (premier vol de Vega-C)",
            "Culture de plantes en orbite, et un relais numérique radioamateur", "Orbite moyenne, ≈ 5 800 km d’altitude",
            "Si haut qu’il relie les continents : un contact numérique entre la France et le Japon y est possible."),
        Fiche("noaa15", "NOAA 15", listOf(25338), listOf("NOAA 15", "NOAA-15"),
            listOf("fr" to "NOAA-15", "en" to "NOAA-15"),
            "États-Unis", "NOAA", "13 mai 1998",
            "Satellite météo (images APT à 137 MHz)", "Orbite polaire, ≈ 810 km",
            "Ses images APT se recevaient avec une simple antenne et un récepteur ; il a été arrêté en 2025."),
        Fiche("noaa18", "NOAA 18", listOf(28654), listOf("NOAA 18", "NOAA-18"),
            listOf("en" to "NOAA-18"),
            "États-Unis", "NOAA", "20 mai 2005",
            "Satellite météo (images APT à 137 MHz)", "Orbite polaire, ≈ 850 km",
            "Il a envoyé ses images APT à tous ceux qui l’écoutaient pendant vingt ans, jusqu’à son arrêt en 2025."),
        Fiche("noaa19", "NOAA 19", listOf(33591), listOf("NOAA 19", "NOAA-19"),
            listOf("fr" to "NOAA-19", "en" to "NOAA-19"),
            "États-Unis", "NOAA", "6 février 2009",
            "Satellite météo (images APT à 137 MHz)", "Orbite polaire, ≈ 870 km",
            "Le dernier de la série : ses images APT s’écoutaient comme un « tic-tac » ; arrêté en 2025."),
        Fiche("meteor23", "Meteor-M n° 2-3", listOf(57166), listOf("METEOR-M2 3", "METEOR-M 2-3", "METEOR-M2-3", "METEOR M2-3"),
            listOf("en" to "Meteor-M No.2-3", "fr" to "Meteor (satellite)"),
            "Russie", "Roscosmos et Roshydromet", "27 juin 2023",
            "Satellite météo (images LRPT à 137 MHz)", "Orbite polaire héliosynchrone, ≈ 830 km",
            "Ses images numériques LRPT se décodent avec une clé SDR et une antenne en V : SatMe le fait."),
        Fiche("meteor24", "Meteor-M n° 2-4", listOf(59051), listOf("METEOR-M2 4", "METEOR-M 2-4", "METEOR-M2-4", "METEOR M2-4"),
            listOf("en" to "Meteor-M No.2-4", "fr" to "Meteor (satellite)"),
            "Russie", "Roscosmos et Roshydromet", "29 février 2024",
            "Satellite météo (images LRPT à 137 MHz)", "Orbite polaire héliosynchrone, ≈ 830 km",
            "Lancé un 29 février ! Ses images LRPT se reçoivent en direct avec une clé SDR."),
        Fiche("hubble", "Télescope spatial Hubble", listOf(20580), listOf("HST", "HUBBLE"),
            listOf("fr" to "Télescope spatial Hubble", "en" to "Hubble Space Telescope"),
            "États-Unis et Europe", "NASA et ESA", "24 avril 1990",
            "Télescope spatial", "≈ 520 km d’altitude, inclinée de 28,5°",
            "Son miroir mal poli a été corrigé par des astronautes en 1993 ; depuis, il a changé notre vision de l’Univers."),
        Fiche("ao73", "AO-73 (FUNcube-1)", listOf(39444), listOf("AO-73", "FUNCUBE-1", "FUNCUBE"),
            listOf("en" to "FUNcube-1"),
            "Royaume-Uni et Pays-Bas", "AMSAT-UK et AMSAT-NL", "21 novembre 2013",
            "Satellite éducatif (télémétrie pour les écoles) et transpondeur linéaire", "Orbite polaire basse",
            "Fait pour les écoles : sa télémétrie se décode en classe avec une petite clé radio."),
        Fiche("po101", "PO-101 (Diwata-2)", listOf(43678), listOf("PO-101", "DIWATA-2", "DIWATA 2"),
            listOf("en" to "Diwata-2"),
            "Philippines", "Université des Philippines et DOST", "29 octobre 2018",
            "Observation de la Terre, et un relais FM radioamateur", "Orbite héliosynchrone, ≈ 600 km",
            "Un satellite d’observation qui prête son relais FM aux radioamateurs à certaines heures."),
        Fiche("jo97", "JO-97 (JY1Sat)", listOf(43803), listOf("JO-97", "JY1SAT"),
            listOf("en" to "JY1Sat"),
            "Jordanie", "Fondation du prince héritier de Jordanie, avec AMSAT-UK", "3 décembre 2018",
            "Transpondeur linéaire et images SSDV", "Orbite héliosynchrone basse",
            "Son nom rend hommage au roi Hussein de Jordanie, radioamateur sous l’indicatif JY1."),
    )

    private val parNumero = toutes.flatMap { f -> f.catnums.map { it to f } }.toMap()

    private fun cle(s: String) = s.uppercase().replace(Regex("[^A-Z0-9]"), "")

    /** [catnum]'s card, else the one whose name starts [nom] ("ISS (ZARYA)", "SO-50 [+]"); null when unknown. */
    fun cherche(catnum: Int, nom: String): Fiche? {
        parNumero[catnum]?.let { return it }
        val n = cle(nom.substringBefore('(').ifBlank { nom })
        if (n.length < 3) return null
        return toutes.firstOrNull { f -> f.noms.any { cle(it) == n } } ?: toutes.firstOrNull { f -> f.noms.any { val k = cle(it); k.length >= 4 && n.startsWith(k) } }
    }
}
