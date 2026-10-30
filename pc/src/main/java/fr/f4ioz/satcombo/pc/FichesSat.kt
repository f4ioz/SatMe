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
                     val pays: String, val operateur: String, val lancement: String, val mission: String, val orbite: String, val anecdote: String,
                     /** Its size: CubeSat units ("1U", "3U"), else a few words ("Microsatellite, 50 kg"); "" when no source says it. */
                     val taille: String = "")

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
        // Format : 10 kg, cube de 23 cm — https://space.skyrocket.de/doc_sdat/saudisat-1.htm , https://en.wikipedia.org/wiki/Saudi-OSCAR_50
        Fiche("so50", "SO-50 (SaudiSat-1C)", listOf(27607), listOf("SO-50", "SAUDISAT-1C", "SAUDISAT 1C"),
            listOf("en" to "Saudi-OSCAR 50"),
            "Arabie saoudite", "KACST (Cité du roi Abdulaziz pour la science et la technologie)", "20 décembre 2002",
            "Relais FM radioamateur", "Orbite basse inclinée de 64,6°",
            "Plus de vingt ans en orbite et toujours un des relais FM les plus écoutés : un portatif et une petite antenne suffisent.",
            taille = "Microsatellite de 10 kg"),
        // Format : 1U — https://en.wikipedia.org/wiki/Fox-1B , https://space.skyrocket.de/doc_sdat/fox-1a.htm
        Fiche("ao91", "AO-91 (Fox-1B)", listOf(43017), listOf("AO-91", "FOX-1B", "RADFXSAT"),
            listOf("en" to "Fox-1B"),
            "États-Unis", "AMSAT-NA, avec l’université Vanderbilt", "18 novembre 2017",
            "Relais FM radioamateur et mesure des radiations", "Orbite polaire basse (héliosynchrone)",
            "Un CubeSat d’un seul cube de 10 cm ; ses batteries fatiguées, il ne fonctionne plus qu’au soleil.", taille = "1U"),
        // Format : plateforme Microsat, cube de 15 cm — https://en.wikipedia.org/wiki/Eyesat-1 , https://space.skyrocket.de/doc_sdat/eyesat.htm
        Fiche("ao27", "AO-27 (Eyesat-1)", listOf(22825), listOf("AO-27", "EYESAT"),
            listOf("en" to "AMRAD-OSCAR 27"),
            "États-Unis", "AMRAD et Interferometrics", "26 septembre 1993",
            "Satellite commercial portant un relais FM radioamateur", "Orbite polaire basse (≈ 800 km)",
            "Un des tout premiers relais FM accessibles avec un simple portatif.", taille = "Microsatellite, cube de 15 cm"),
        // Format : plateforme Yubileiny restée attachée à l’étage Briz-KM — https://space.skyrocket.de/doc_sdat/dosaaf-85.htm ,
        // https://www.amsat.org/two-way-satellites/rs-44/
        Fiche("rs44", "RS-44 (DOSAAF-85)", listOf(44909), listOf("RS-44", "DOSAAF-85", "DOSAAF"),
            listOf(),
            "Russie", "DOSAAF (Russie)", "26 décembre 2019",
            "Transpondeur linéaire radioamateur (BLU et CW)", "≈ 1 200 à 1 500 km d’altitude, inclinée de 82,5°",
            "Son orbite haute donne de longs passages : des contacts à travers toute l’Europe et jusqu’en Amérique.",
            taille = "Petit satellite resté attaché à son étage Briz-KM"),
        Fiche("ao7", "AO-7", listOf(7530), listOf("AO-07", "AO-7", "OSCAR 7"),
            listOf("en" to "AMSAT-OSCAR 7"),
            "États-Unis, Canada, Allemagne, Australie (AMSAT)", "AMSAT", "15 novembre 1974",
            "Transpondeurs linéaires radioamateurs", "≈ 1 450 km d’altitude, inclinée de 102°",
            "Muet de 1981 à 2002, une batterie en court-circuit s’est rouverte : il revit depuis, au soleil, plus de 50 ans après son lancement !"),
        // Format : 50 kg, polyèdre de 26 faces de 44 × 47 cm — https://www.jarl.org/English/5_Fuji/espec.htm , https://amsat-uk.org/satellites/fuji-oscar-29-jas-2/
        Fiche("fo29", "FO-29 (JAS-2)", listOf(24278), listOf("FO-29", "JAS-2", "JAS 2"),
            listOf(),
            "Japon", "JARL (union des radioamateurs japonais)", "17 août 1996",
            "Transpondeur linéaire radioamateur", "≈ 800 à 1 300 km d’altitude, inclinée de 98,5°",
            "Un satellite de la JARL qui relaie les radioamateurs depuis près de 30 ans.",
            taille = "Microsatellite de 50 kg (polyèdre de 26 faces)"),
        Fiche("qo100", "QO-100 (Es’hail-2)", listOf(43700), listOf("QO-100", "ES'HAIL", "ESHAIL", "ES'HAIL-2", "ESHAIL-2"),
            listOf("en" to "Es'hail 2"),
            "Qatar", "Es’hailSat, avec AMSAT-DL et la QARS pour la partie radioamateur", "15 novembre 2018",
            "Télévision, et les premiers transpondeurs radioamateurs géostationnaires", "Géostationnaire, 35 786 km, vers 26° Est (au-dessus de l’Afrique)",
            "Toujours au même endroit du ciel : une parabole fixe suffit pour trafiquer jour et nuit, du Brésil à la Thaïlande."),
        // Format : 3U — https://space.skyrocket.de/doc_sdat/greencube.htm , https://www.nanosats.eu/sat/greencube
        Fiche("io117", "IO-117 (GreenCube)", listOf(53106), listOf("IO-117", "GREENCUBE"),
            listOf(),
            "Italie", "Université La Sapienza de Rome", "13 juillet 2022 (premier vol de Vega-C)",
            "Culture de plantes en orbite, et un relais numérique radioamateur", "Orbite moyenne, ≈ 5 800 km d’altitude",
            "Si haut qu’il relie les continents : un contact numérique entre la France et le Japon y est possible.", taille = "3U"),
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
        // Format : 1U — https://space.skyrocket.de/doc_sdat/funcube-1.htm , https://en.wikipedia.org/wiki/FUNcube-1
        Fiche("ao73", "AO-73 (FUNcube-1)", listOf(39444), listOf("AO-73", "FUNCUBE-1", "FUNCUBE"),
            listOf("en" to "FUNcube-1"),
            "Royaume-Uni et Pays-Bas", "AMSAT-UK et AMSAT-NL", "21 novembre 2013",
            "Satellite éducatif (télémétrie pour les écoles) et transpondeur linéaire", "Orbite polaire basse",
            "Fait pour les écoles : sa télémétrie se décode en classe avec une petite clé radio.", taille = "1U"),
        // Format : microsatellite de ≈ 50 cm de côté (50 ou 56 kg selon les sources) — https://space.skyrocket.de/doc_sdat/diwata-2.htm ,
        // https://en.wikipedia.org/wiki/Diwata-2
        Fiche("po101", "PO-101 (Diwata-2)", listOf(43678), listOf("PO-101", "DIWATA-2", "DIWATA 2"),
            listOf("en" to "Diwata-2"),
            "Philippines", "Université des Philippines et DOST", "29 octobre 2018",
            "Observation de la Terre, et un relais FM radioamateur", "Orbite héliosynchrone, ≈ 600 km",
            "Un satellite d’observation qui prête son relais FM aux radioamateurs à certaines heures.",
            taille = "Microsatellite, ≈ 50 cm de côté"),
        // Format : 1U — https://space.skyrocket.de/doc_sdat/jy1-sat.htm
        Fiche("jo97", "JO-97 (JY1Sat)", listOf(43803), listOf("JO-97", "JY1SAT"),
            listOf("en" to "JY1Sat"),
            "Jordanie", "Fondation du prince héritier de Jordanie, avec AMSAT-UK", "3 décembre 2018",
            "Transpondeur linéaire et images SSDV", "Orbite héliosynchrone basse",
            "Son nom rend hommage au roi Hussein de Jordanie, radioamateur sous l’indicatif JY1.", taille = "1U"),
        // Les satellites d’images (SSTV, SSDV) de SatellitesImages. Format laissé vide quand les sources se contredisent.
        // LAPAN-A2 : https://www.eoportal.org/satellite-missions/lapan-a2 , https://space.skyrocket.de/doc_sdat/lapan-a2.htm ,
        // https://www.n2yo.com/satellite/?s=40931 , https://www.arrl.org/news/lapan-a2-fm-and-aprs-satellite-launched ,
        // https://db.satnogs.org/satellite/40931 (SSDV). Masse 68 ou 74 kg selon les sources : format vide.
        Fiche("lapana2", "LAPAN-A2 (IO-86)", listOf(40931), listOf("LAPAN-A2", "LAPAN A2", "IO-86"),
            listOf(),
            "Indonésie", "LAPAN (institut national d’aéronautique et de l’espace d’Indonésie)", "28 septembre 2015",
            "Observation de la Terre et suivi des navires (AIS) ; relais FM, APRS et images SSDV radioamateurs", "Orbite basse presque équatoriale",
            "Il tourne presque au-dessus de l’équateur ; son relais APRS est pensé pour l’aide en cas de catastrophe."),
        // ASRTU-1 : https://db.satnogs.org/satellite/61781 (lancé le 2024-11-04 à 23 h 18 UTC), https://www.n2yo.com/satellite/?s=61781 ,
        // https://www.arrl.org/news/asrtu-1-cubesat-with-v-u-fm-transponder-submitted-for-iaru-coordination (12U, commandes d’images), https://www.nanosats.eu/sat/asrtu-1
        Fiche("asrtu1", "ASRTU-1 (AO-123)", listOf(61781), listOf("ASRTU-1", "AO-123"),
            listOf(),
            "Chine et Russie", "Institut de technologie de Harbin (équipe LilacSat) et université d’État de l’Amour, pour l’association des universités techniques sino-russes (ASRTU)",
            "4 novembre 2024", "Éducation : relais FM radioamateur, télémesure et images SSDV", "",
            "Construit par des étudiants chinois et russes, il obéit aux radioamateurs : on peut lui demander de prendre une photo et de la renvoyer.",
            taille = "12U"),
        // HADES-SA : https://db.satnogs.org/satellite/68446 , https://www.amsat.org/ans-158-amsat-news-service-weekly-bulletins/ ,
        // https://www.amsat-ea.org/proyectos/ , https://www.n2yo.com/satellite/?s=68446 , https://www.nanosats.eu/sat/hades-sa (PocketQube 1,5P, caméra, plaque)
        Fiche("hadessa", "HADES-SA (SO-127)", listOf(68446), listOf("HADES-SA", "SO-127", "SPINNYONE"),
            listOf(),
            "Espagne", "AMSAT-EA, avec Spinning Around (Royaume-Uni) pour la caméra", "30 mars 2026",
            "Messagerie numérique (BBS), voix CODEC2 et images SSDV radioamateurs", "",
            "Sa caméra photographie son propre mécanisme de déploiement, qui porte une petite plaque commémorative : une nouvelle image SSDV tous les trois jours.",
            taille = "PocketQube 1,5P"),
        // SEEDS II : https://www.arrl.org/news/ten-new-satellites-in-orbit , https://space.skyrocket.de/doc_sdat/seeds.htm ,
        // https://eoportal.org/satellite-missions/seeds-2 (cube de 10 cm, CW, paquets, voix, SSTV, premier SEEDS perdu), https://www.n2yo.com/satellite/?s=32791
        Fiche("seeds2", "SEEDS II (CO-66)", listOf(32791), listOf("SEEDS II", "SEEDS-2", "SEEDS", "CO-66"),
            listOf(),
            "Japon", "Université Nihon (projet étudiant)", "28 avril 2008",
            "Satellite étudiant : balise CW, paquets, voix de synthèse et images SSTV", "",
            "Il remplace le premier SEEDS, perdu dans l’échec de son lanceur ; et il « parle », d’une voix de synthèse.",
            taille = "1U"),
        // TeikyoSat-4 : https://www.teikyo-u.ac.jp/en/affiliate/education/teikyosat/summary (52 kg, 52 cm, missions),
        // https://space.skyrocket.de/doc_sdat/teikyosat-4.htm , https://www.n2yo.com/satellite/?s=49396 , https://db.satnogs.org/satellite/49396 (SSTV)
        Fiche("teikyosat4", "TeikyoSat-4 (Ooruri)", listOf(49396), listOf("TEIKYOSAT-4", "TEIKYOSAT 4", "OORURI"),
            listOf(),
            "Japon", "Université Teikyo (groupe d’étude des systèmes spatiaux)", "9 novembre 2021",
            "Expériences de biologie en orbite, et essai de liaison radioamateur rapide ; images SSTV", "",
            "Il a emporté un myxomycète en orbite, pour observer des micro-organismes en apesanteur.",
            taille = "Microsatellite de 52 kg (52 cm de côté)"),
        // WREN : https://space.skyrocket.de/doc_sdat/wren.htm , https://www.n2yo.com/satellite/?s=39435 , https://amsat-uk.org/tag/wren/
        // (PocketQube 5 cm, 250 g, garage, propulseurs à plasma), https://amsat-uk.org/2013/11/28/commands-for-wren-sstv-released
        Fiche("wren", "WREN", listOf(39435), listOf("WREN"),
            listOf(),
            "Allemagne", "STADOKO UG", "21 novembre 2013 (dans UniSat-5, qui l’a libéré en orbite)",
            "Essais technologiques (micro-propulseurs à plasma, roues d’inertie) et images SSTV de la Terre", "",
            "« Construit par quatre gars dans un garage », dit AMSAT-UK : sans doute le plus petit satellite à porter des propulseurs à plasma pulsé.",
            taille = "PocketQube 1P (5 cm de côté, 250 g)"),
        // SONATE-2 : https://space.skyrocket.de/doc_sdat/sonate-2.htm , https://www.n2yo.com/satellite/?s=59112 ,
        // https://www.amsat.org/ans-070-amsat-news-service-weekly-bulletins/ (6U+, IA),
        // https://informatik.uni-wuerzburg.de/aerospaceinfo/mitarbeiter/kayal/forschungsprojekte/sonate-2/information-for-radio-amateurs (SSTV, APRS, première image)
        Fiche("sonate2", "SONATE-2", listOf(59112), listOf("SONATE-2", "SONATE 2"),
            listOf(),
            "Allemagne", "Université de Wurtzbourg (Julius-Maximilians-Universität)", "4 mars 2024",
            "Démonstration d’intelligence artificielle en orbite ; images SSTV, APRS et télémesure radioamateurs", "",
            "Sa première image depuis l’orbite montrait la côte atlantique française ; l’université a compté plus de 44 000 émissions SSTV.",
            taille = "6U"),
        // UmKA-1 : https://space.skyrocket.de/doc_sdat/umka-1.htm (3U, Cygne et Andromède), https://db.satnogs.org/satellite/57172 ,
        // https://iaru.amsat-uk.org/finished_detail.php?serialnum=907 ; le lancement du 27 juin 2023 : https://www.russianspaceweb.com/meteor-m2-3.html
        Fiche("umka1", "UmKA-1 (RS40S)", listOf(57172), listOf("UMKA-1", "UMKA 1", "RS40S"),
            listOf(),
            "Russie", "École secondaire n° 29 P. I. Zabrodine de Podolsk", "27 juin 2023",
            "Satellite scolaire d’astronomie (un télescope) ; images SSTV et télémesure", "",
            "Avant le lancement, son équipe a photographié depuis le sol la constellation du Cygne et la galaxie d’Andromède avec sa caméra.",
            taille = "3U"),
        // VIZARD-meteo : https://space.skyrocket.de/doc_sdat/vizard-meteo.htm , https://db.satnogs.org/satellite/57189 (3U, SSTV Robot 72),
        // https://www.russianspaceweb.com/meteor-m2-3.html (opérateur, mission), https://www.ne.jp/asahi/hamradio/je9pel/umka1sat.htm (concours d’avril 2024)
        Fiche("vizard", "VIZARD-meteo (RS38S)", listOf(57189), listOf("VIZARD-METEO", "VIZARD", "RS38S", "RS-38S"),
            listOf(),
            "Russie", "Novye Intellektualnye Sistemy (Moscou), construit par SPUTNIX", "27 juin 2023",
            "Surveillance météo des régions polaires (programme éducatif Space-π) ; images SSTV", "",
            "En avril 2024, les finalistes du concours « Orbite Space-π » l’ont piloté eux-mêmes.",
            taille = "3U"),
        // Monitor-3 : https://space.skyrocket.de/doc_sdat/monitor-1.htm (3U, Monitor 2, 3 et 4 sur la même fusée), https://db.satnogs.org/satellite/57180 ,
        // https://www.nanosats.eu/sat/monitor-3
        Fiche("monitor3", "Monitor-3 (RS58S)", listOf(57180), listOf("MONITOR-3", "MONITOR 3", "RS58S"),
            listOf(),
            "Russie", "Institut de physique nucléaire Skobeltsyne, université d’État de Moscou", "27 juin 2023",
            "Observation des éruptions solaires et cosmiques en rayons X et gamma ; images SSTV", "",
            "Il est parti sur la même fusée que ses frères Monitor-2 et Monitor-4.",
            taille = "3U"),
        // UTMN2 : https://space.skyrocket.de/doc_sdat/utmn.htm (3U, élèves), https://db.satnogs.org/satellite/57203 , https://www.nanosats.eu/sat/utmn-2
        Fiche("utmn2", "UTMN2 (Tyumsat)", listOf(57203), listOf("UTMN2", "UTMN-2", "TYUMSAT", "RS27S"),
            listOf(),
            "Russie", "Université d’État de Tioumen", "27 juin 2023",
            "Surveillance de l’environnement de la région de Tioumen (thermométrie infrarouge) ; images SSTV", "",
            "Des étudiants et des chercheurs de l’université l’ont construit avec des élèves.",
            taille = "3U"),
        // AHMAT-1 : https://space.skyrocket.de/doc_sdat/akhmat-1.htm (3U, 2,5 kg), https://db.satnogs.org/satellite/57206 (ADS-B, SSTV, retombé le 2026-10-15),
        // https://www.n2yo.com/satellite/?s=57206
        Fiche("ahmat1", "AHMAT-1 (RS41S)", listOf(57206), listOf("AHMAT-1", "AKHMAT-1", "RS41S"),
            listOf(),
            "Russie", "Université d’État de Tchétchénie", "27 juin 2023",
            "Récepteur expérimental ADS-B (suivi des avions) ; images SSTV", "",
            "Il est rentré dans l’atmosphère le 15 octobre 2026, un peu plus de trois ans après son lancement.",
            taille = "3U"),
        // SAKHACUBE-CHOLBON : https://www.yksa.space/projects/missions/sakhacube-cholbon (1U, 28 déc. 2025 à 16 h 18 heure de Moscou, SSTV, Arduino),
        // https://db.satnogs.org/satellite/67290 , https://www.n2yo.com/satellite/?s=67290 , https://iaru.amsat-uk.org/finished.php
        Fiche("sakhacube", "SAKHACUBE-CHOLBON (RS18S)", listOf(67290), listOf("SAKHACUBE-CHOLBON", "SAKHACUBE", "CHOLBON", "RS18S"),
            listOf(),
            "Russie (Iakoutie)", "Académie des sciences de Sakha, avec YKSA", "28 décembre 2025",
            "Éducation et recherche ; « cartes postales » SSTV, télémesure et messages audio", "",
            "Le premier satellite de Iakoutie : ses 12 microcontrôleurs compatibles Arduino accueillent les programmes des élèves.",
            taille = "1U"),
        // QMR-KWT 2 : https://space.skyrocket.de/doc_sdat/qmr-kwt.htm (1U, Orbital Space, QMR-KWT lancé en 2021), https://db.satnogs.org/satellite/67291 ,
        // https://www.nanosats.eu/sat/qmr-kwt_2 . (La liste de l’IARU nomme une université de Moscou comme organisation de soutien.)
        Fiche("qmrkwt2", "QMR-KWT 2 (RS95S)", listOf(67291), listOf("QMR-KWT 2", "QMR-KWT-2", "RS95S"),
            listOf(),
            "Koweït", "Orbital Space", "28 décembre 2025",
            "Éducation : caméra, relais FM radioamateur et images SSTV", "",
            "Le deuxième de la série, après QMR-KWT lancé en 2021 ; en plus de ses images SSTV, il porte un relais FM.",
            taille = "1U"),
        // GALAPAGOS-UTE-SWSU : https://db.satnogs.org/satellite/67279 , https://www.n2yo.com/satellite/?s=67279 , https://www.nanosats.eu/sat/galapagos-ute (3U),
        // https://ute.edu.ec/nanosatelite-galapagos-ute/ (partenaires, troisième projet, étudiants à Koursk)
        Fiche("galapagos", "GALAPAGOS-UTE-SWSU", listOf(67279), listOf("GALAPAGOS-UTE-SWSU", "GALAPAGOS-UTE", "GALAPAGOS UTE", "HC1PX"),
            listOf(),
            "Équateur et Russie", "Université UTE (Quito) et université d’État du Sud-Ouest (Koursk)", "28 décembre 2025",
            "Observation des îles Galápagos et mesure des radiations ; images SSTV", "",
            "Le troisième projet spatial commun des deux universités ; trois étudiants équatoriens ont participé à ses essais à Koursk.",
            taille = "3U"),
        // Geoscan-2 : https://space.skyrocket.de/doc_sdat/geoskan-2.htm (3U, 25.07.2025), https://db.satnogs.org/satellite/64890 ,
        // https://tass.com/science/1994277/amp (le lancement du 25 juillet 2025)
        Fiche("geoscan2", "Geoscan-2 (RS92S2)", listOf(64890), listOf("GEOSCAN-2", "GEOSCAN 2", "RS92S2"),
            listOf(),
            "Russie", "Geoscan", "25 juillet 2025",
            "Démonstration technologique (réception des avions par ADS-B) ; balise et charge utile radioamateur", "",
            "", taille = "3U"),
        // Geoscan-4 et -5 : https://space.skyrocket.de/doc_sdat/geoskan-4.htm (3U, émetteurs de Gonets, données par paquets),
        // https://db.satnogs.org/satellite/64892 , https://db.satnogs.org/satellite/64891 , https://tass.com/science/1994277/amp (≈ 500 km et 1 500 km)
        Fiche("geoscan4", "Geoscan-4", listOf(64892), listOf("GEOSCAN-4", "GEOSCAN 4"),
            listOf(),
            "Russie", "Geoscan, avec Gonets pour les émetteurs", "25 juillet 2025",
            "Essais de transmission de données par paquets avec la constellation Gonets ; balise et charge utile radioamateur", "≈ 500 km d’altitude",
            "Avec Geoscan-5, il essaie de dialoguer avec les satellites Gonets, qui tournent bien plus haut (≈ 1 500 km).", taille = "3U"),
        Fiche("geoscan5", "Geoscan-5", listOf(64891), listOf("GEOSCAN-5", "GEOSCAN 5"),
            listOf(),
            "Russie", "Geoscan, avec Gonets pour les émetteurs", "25 juillet 2025",
            "Essais de transmission de données par paquets avec la constellation Gonets ; balise et charge utile radioamateur", "≈ 500 km d’altitude",
            "Avec Geoscan-4, il essaie de dialoguer avec les satellites Gonets, qui tournent bien plus haut (≈ 1 500 km).", taille = "3U"),
        // 239Alferov : https://space.skyrocket.de/doc_sdat/239alferov.htm , https://iaru.amsat-uk.org/finished_detail.php?serialnum=934 (3U, missions),
        // https://gcn.nasa.gov/circulars/43481.txt (3U, GRB 260101A), https://db.satnogs.org/satellite/64881
        Fiche("alferov239", "239Alferov (RS61S)", listOf(64881), listOf("239ALFEROV", "239-PTS", "239PTS", "RS61S"),
            listOf(),
            "Russie", "Lycée n° 239 et école physico-technique Alferov, Saint-Pétersbourg", "25 juillet 2025",
            "Éducation radioamateur (CW, voix, SSTV, SSDV, digipeater) et détection des sursauts gamma", "",
            "Un CubeSat de lycéens qui a vu un vrai sursaut gamma, GRB 260101A, annoncé dans une circulaire de la NASA.", taille = "3U"),
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
