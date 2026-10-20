/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

/**
 * The country of a callsign, from its prefix.
 *
 * Built from the ARRL DXCC entity list, cut down to what a prefix alone can
 * tell. Entities that need finer knowledge of the callsign (Kaliningrad, the
 * UK bases on Cyprus) are only handled when the rule fits in one line — no
 * country is better than a wrong one.
 *
 * The second field is the flag code in [fr.f4ioz.satcombo.data.Flags] when
 * drawn there, empty otherwise (name shown alone).
 */
object Dxcc {

    /** An entity: its name, its flag (country code), its continent (EU, AS, AF, NA, SA, OC — as the DXCC lists it). */
    class Entite(val nom: String, val drapeau: String, val continent: String = "") {
        /** The flag's code for the emoji: ISO 3166 (JE, GP…), or ENG/SCT/WLS. */
        val iso: String get() = INFOS[nom]?.iso ?: drapeau
        /** Rough centre of the entity (degrees), for a contact without a square. */
        val lat: Double? get() = INFOS[nom]?.lat
        val lon: Double? get() = INFOS[nom]?.lon
    }

    /**
     * Prefix → entity. Keys are tried longest first, so "EA8" (Canaries) wins
     * over "EA" (Spain).
     */
    private val table: Map<String, Entite> = buildMap {
        // The continent of the block below; an entry elsewhere says its own.
        var bloc = "EU"
        fun p(prefixes: String, nom: String, drapeau: String = "", continent: String = bloc) {
            val e = Entite(nom, drapeau, continent)
            prefixes.split(" ").forEach { put(it, e) }
        }
        // Europe
        bloc = "EU"
        p("F TM TO TV TW TX HW HX HY", "France", "FR")
        p("TK", "Corse", "FR")
        p("G M 2E GX", "Angleterre", "GB")
        p("GM MM 2M GS MS", "Écosse", "GB")
        p("GW MW 2W GC MC", "Pays de Galles", "GB")
        p("GI MI 2I GN MN", "Irlande du Nord", "GB")
        p("GD MD GT MT", "Île de Man", "GB")
        p("GJ MJ GH MH", "Jersey", "GB")
        p("GU MU GP MP", "Guernesey", "GB")
        p("EI EJ", "Irlande", "IE")
        p("DA DB DC DD DE DF DG DH DJ DK DL DM DN DO DP DQ DR", "Allemagne", "DE")
        p("EA EB EC ED EE EF EG EH AM AN AO", "Espagne", "ES")
        p("EA6 EB6 EC6", "Baléares", "ES")
        p("EA8 EB8 EC8", "Canaries", "ES", continent = "AF")
        p("EA9 EB9 EC9", "Ceuta et Melilla", "ES", continent = "AF")
        p("CT CQ CR CS", "Portugal", "PT")
        p("CT3 CS3", "Madère", "PT", continent = "AF")
        p("CU", "Açores", "PT")
        p("I IZ IK IU IW IX", "Italie", "IT")
        p("IS0 IM0", "Sardaigne", "IT")
        p("ON OO OP OQ OR OS OT", "Belgique", "BE")
        p("PA PB PC PD PE PF PG PH PI", "Pays-Bas", "NL")
        p("HB HE", "Suisse", "CH")
        p("HB0", "Liechtenstein", "CH")
        p("LX", "Luxembourg", "LU")
        p("OE", "Autriche", "AT")
        p("OZ 5P", "Danemark", "DK")
        p("OY", "Féroé", "FO")
        p("LA LB LC LD LE LF LG LH LI LJ LK LL LM LN", "Norvège", "NO")
        p("SA SB SC SD SE SF SG SH SI SJ SK SL SM", "Suède", "SE")
        p("OF OG OH OI", "Finlande", "FI")
        p("OH0", "Åland", "FI")
        p("TF", "Islande", "IS")
        p("SP SN SO SQ SR 3Z", "Pologne", "PL")
        p("OK OL", "Tchéquie", "CZ")
        p("OM", "Slovaquie", "SK")
        p("HA HG", "Hongrie", "HU")
        p("S5", "Slovénie", "SI")
        p("9A", "Croatie", "HR")
        p("E7", "Bosnie-Herzégovine")
        p("YT YU YZ", "Serbie", "RS")
        p("4O", "Monténégro")
        p("Z3", "Macédoine du Nord")
        p("ZA", "Albanie")
        p("SV SW SX SY SZ J4", "Grèce", "GR")
        p("SV5", "Dodécanèse", "GR")
        p("SV9", "Crète", "GR")
        p("LZ", "Bulgarie", "BG")
        p("YO YP YQ YR", "Roumanie", "RO")
        p("ER", "Moldavie")
        p("UR US UT UU UV UW UX UY UZ EM EN EO", "Ukraine", "UA")
        p("EU EV EW", "Biélorussie")
        p("ES", "Estonie", "EE")
        p("YL", "Lettonie", "LV")
        p("LY", "Lituanie", "LT")
        p("RA RZ UA UB UC UD UE UF UG UI R", "Russie", "RU")
        p((listOf("R", "RA", "RC", "RD", "RE", "RF", "RG", "RJ", "RK", "RL", "RM", "RN", "RO", "RQ", "RT", "RU", "RV", "RW", "RX", "RY", "RZ",
            "UA", "UB", "UC", "UD", "UE", "UF", "UG", "UH", "UI").flatMap { listOf(it + "9", it + "0") }).joinToString(" "), "Russie d'Asie", "RU", continent = "AS")
        p("TA TB TC", "Turquie", "TR", continent = "AS")
        p("5B C4 H2", "Chypre", continent = "AS")
        p("9H", "Malte", "MT")
        p("3A", "Monaco", "MC")
        p("C3", "Andorre")
        p("HV", "Vatican", "IT")
        p("T7", "Saint-Marin", "IT")
        p("JW", "Svalbard", "NO")
        p("JX", "Jan Mayen", "NO")
        // Americas (North; the South ones say so)
        bloc = "NA"
        p("K W N AA AB AC AD AE AF AG AH AI AJ AK", "États-Unis", "US")
        p("KL AL NL WL", "Alaska", "US")
        p("KH6 AH6 NH6 WH6 KH7", "Hawaï", "US", continent = "OC")
        p("KP4 NP4 WP4 KP3", "Porto Rico", "US")
        p("VE VA VO VY VB VC VD VF VG VX CF", "Canada", "CA")
        p("XE XA XB XC XD XF XG XH XI 4A 4B 4C 6D 6E 6F 6G 6H 6I 6J", "Mexique", "MX")
        p("PY PP PQ PR PS PT PU PV PW PX ZV ZW ZX ZY ZZ", "Brésil", "BR", continent = "SA")
        p("LU LO LP LQ LR LS LT LV LW AY AZ L2 L3 L4 L5 L6 L7 L8 L9", "Argentine", "AR", continent = "SA")
        p("CE CA CB CC CD XQ XR 3G", "Chili", continent = "SA")
        p("OA OB OC 4T", "Pérou", "PE", continent = "SA")
        p("HK HJ 5K", "Colombie", continent = "SA")
        p("YV YW YX YY 4M", "Venezuela", continent = "SA")
        p("HC HD", "Équateur", continent = "SA")
        p("CX CV CW", "Uruguay", continent = "SA")
        p("ZP", "Paraguay", continent = "SA")
        p("CP", "Bolivie", continent = "SA")
        p("HI", "Rép. dominicaine")
        p("CO CM CL T4", "Cuba")
        p("TI TE", "Costa Rica")
        p("HP HO", "Panama")
        p("TG TD", "Guatemala")
        p("XP OX", "Groenland", "DK")
        p("FP", "St-Pierre-et-Miquelon", "FR")
        p("FG", "Guadeloupe", "FR")
        p("FM", "Martinique", "FR")
        p("FY", "Guyane", "FR", continent = "SA")
        p("FS", "Saint-Martin", "FR")
        p("FJ", "Saint-Barthélemy", "FR")
        p("VP9", "Bermudes", "GB")
        p("8P", "Barbade")
        p("VP2E", "Anguilla")
        p("VP2M", "Montserrat")
        p("VP2V", "Îles Vierges britanniques")
        p("KP2 NP2 WP2", "Îles Vierges américaines", "US")
        p("6Y", "Jamaïque")
        // Africa
        bloc = "AF"
        p("CN", "Maroc", "MA")
        p("7X 7T 7U 7V 7W 7Y", "Algérie")
        p("3V", "Tunisie")
        p("SU", "Égypte", "EG")
        p("5A", "Libye")
        p("ZS ZR ZT ZU", "Afrique du Sud", "ZA")
        p("5N 5O", "Nigeria", "NG")
        p("FR", "La Réunion", "FR")
        p("FH", "Mayotte", "FR")
        p("3B8", "Maurice")
        p("6W 6V", "Sénégal")
        p("5Z 5Y", "Kenya")
        p("EA8", "Canaries", "ES")
        p("D4", "Cap-Vert")
        p("S7", "Seychelles")
        p("ZD7", "Sainte-Hélène", "GB")
        // Asia
        bloc = "AS"
        p("JA JE JF JG JH JI JJ JK JL JM JN JO JP JQ JR JS 7J 7K 7L 7M 7N 8J 8N", "Japon", "JP")
        p("HL DS DT 6K 6L 6M 6N", "Corée du Sud")
        p("BA BB BC BD BG BH BI BJ BL BM BT BY BZ", "Chine")
        p("BV", "Taïwan")
        p("VR2", "Hong Kong")
        p("VU", "Inde", "IN")
        p("4X 4Z", "Israël")
        p("JY", "Jordanie")
        p("OD", "Liban")
        p("HZ 7Z", "Arabie saoudite")
        p("A6", "Émirats arabes unis")
        p("A7", "Qatar")
        p("A9", "Bahreïn")
        p("9K", "Koweït")
        p("A4", "Oman")
        p("EP EQ", "Iran")
        p("AP AS", "Pakistan")
        p("S2", "Bangladesh", "BD")
        p("4S 4P 4Q 4R", "Sri Lanka")
        p("HS E2", "Thaïlande", "TH")
        p("3W XV", "Viêt Nam", "VN")
        p("9V", "Singapour")
        p("9M2 9M4", "Malaisie Ouest")
        p("9M6 9M8", "Malaisie Est")
        p("YB YC YD YE YF YG YH", "Indonésie", "ID")
        p("DU DV DW DX DY DZ 4F", "Philippines")
        p("EK", "Arménie")
        p("4L", "Géorgie")
        p("4J 4K", "Azerbaïdjan")
        p("UN UP UQ", "Kazakhstan")
        p("EX", "Kirghizistan")
        p("EY", "Tadjikistan")
        p("EZ", "Turkménistan")
        p("UJ UK UL UM", "Ouzbékistan")
        p("JT JU JV", "Mongolie")
        // Oceania
        bloc = "OC"
        p("VK AX", "Australie", "AU")
        p("ZL ZM", "Nouvelle-Zélande", "NZ")
        p("FK", "Nouvelle-Calédonie", "FR")
        p("FO", "Polynésie française", "FR")
        p("FW", "Wallis-et-Futuna", "FR")
        p("KH2 AH2", "Guam", "US")
        p("KH0 AH0 NH0 WH0", "Mariannes du Nord", "US")
        p("KH8 AH8 NH8 WH8", "Samoa américaines", "US")
        p("3D2", "Fidji")
        p("P2", "Papouasie-Nlle-Guinée")
        p("5W", "Samoa")
        p("A3", "Tonga")
    }

    /** Prefix lengths present in the table, longest first. */
    private val longueurs: List<Int> =
        table.keys.map { it.length }.distinct().sortedDescending()

    /** True when [prefixe] is one of the table's own (F, EA, 9A), not merely starting with one. */
    fun prefixe(prefixe: String): Boolean = table.containsKey(prefixe.uppercase())

    /**
     * DXCC entity of a callsign, or null when the prefix is unknown — or the
     * station is at sea or in the air (/MM, /AM: no entity).
     *
     * The location prefix wins (EA5/F4IOZ → EA5, DL1ABC/OH0 → OH0): the
     * operator is where the prefix puts them. An operating suffix (/P, /M)
     * does not change the country (see [IndicatifCompose]).
     * [simple]: [indicatif] is one piece, matched as it is.
     */
    fun entite(indicatif: String, simple: Boolean = false): Entite? {
        val brut = indicatif.trim().uppercase()
        if (brut.isEmpty()) return null
        val efficace = if (simple) brut else {
            val d = IndicatifCompose.decoupe(brut)
            if (d.maritime || d.aeronautique) return null
            d.lieu
        }
        if (efficace.isEmpty()) return null
        for (n in longueurs) {
            if (efficace.length >= n) {
                table[efficace.take(n)]?.let { return it }
            }
        }
        return null
    }

    /**
     * The flag as an emoji: the two regional letters of [Entite.iso], or the
     * subdivision sequence of England, Scotland, Wales. Empty when unknown.
     */
    fun emoji(e: Entite?): String {
        val c = e?.iso.orEmpty()
        return when {
            c.length == 2 -> String(Character.toChars(0x1F1E6 + c[0].code - 'A'.code)) + String(Character.toChars(0x1F1E6 + c[1].code - 'A'.code))
            c.length == 3 -> String(Character.toChars(0x1F3F4)) + ("gb" + c.lowercase()).map { String(Character.toChars(0xE0000 + it.code)) }.joinToString("") +
                String(Character.toChars(0xE007F))
            else -> ""
        }
    }

    /** The entity's flag, centre and ISO code ([Entite.iso]: ENG/SCT/WLS for the UK's nations). */
    private class Info(val lat: Double, val lon: Double, val iso: String)

    private val INFOS: Map<String, Info> = buildMap {
        fun i(nom: String, lat: Double, lon: Double, iso: String) { put(nom, Info(lat, lon, iso)) }
        i("France", 46.6, 2.4, "FR"); i("Corse", 42.1, 9.1, "FR"); i("Angleterre", 52.5, -1.5, "ENG"); i("Écosse", 56.8, -4.2, "SCT")
        i("Pays de Galles", 52.3, -3.7, "WLS"); i("Irlande du Nord", 54.6, -6.7, "GB"); i("Île de Man", 54.2, -4.5, "IM"); i("Jersey", 49.2, -2.1, "JE")
        i("Guernesey", 49.45, -2.58, "GG"); i("Irlande", 53.2, -8.0, "IE"); i("Allemagne", 51.2, 10.4, "DE"); i("Espagne", 40.3, -3.7, "ES")
        i("Baléares", 39.6, 2.9, "ES"); i("Canaries", 28.3, -15.7, "ES"); i("Ceuta et Melilla", 35.6, -4.2, "ES"); i("Portugal", 39.6, -8.0, "PT")
        i("Madère", 32.75, -16.95, "PT"); i("Açores", 38.5, -28.0, "PT"); i("Italie", 42.8, 12.5, "IT"); i("Sardaigne", 40.1, 9.0, "IT")
        i("Belgique", 50.6, 4.6, "BE"); i("Pays-Bas", 52.2, 5.5, "NL"); i("Suisse", 46.8, 8.2, "CH"); i("Liechtenstein", 47.15, 9.55, "LI")
        i("Luxembourg", 49.8, 6.1, "LU"); i("Autriche", 47.6, 14.1, "AT"); i("Danemark", 56.0, 10.0, "DK"); i("Féroé", 62.0, -6.9, "FO")
        i("Norvège", 62.0, 10.0, "NO"); i("Suède", 62.0, 15.5, "SE"); i("Finlande", 63.5, 26.0, "FI"); i("Åland", 60.2, 20.0, "AX")
        i("Islande", 64.9, -18.6, "IS"); i("Pologne", 52.1, 19.4, "PL"); i("Tchéquie", 49.8, 15.5, "CZ"); i("Slovaquie", 48.7, 19.7, "SK")
        i("Hongrie", 47.2, 19.4, "HU"); i("Slovénie", 46.1, 14.8, "SI"); i("Croatie", 45.1, 15.2, "HR"); i("Bosnie-Herzégovine", 44.2, 17.8, "BA")
        i("Serbie", 44.0, 20.9, "RS"); i("Monténégro", 42.8, 19.3, "ME"); i("Macédoine du Nord", 41.6, 21.7, "MK"); i("Albanie", 41.1, 20.0, "AL")
        i("Grèce", 39.3, 22.0, "GR"); i("Dodécanèse", 36.4, 27.9, "GR"); i("Crète", 35.2, 24.9, "GR"); i("Bulgarie", 42.7, 25.3, "BG")
        i("Roumanie", 45.9, 24.9, "RO"); i("Moldavie", 47.2, 28.5, "MD"); i("Ukraine", 49.0, 31.3, "UA"); i("Biélorussie", 53.7, 28.0, "BY")
        i("Estonie", 58.7, 25.5, "EE"); i("Lettonie", 56.9, 24.6, "LV"); i("Lituanie", 55.3, 23.9, "LT"); i("Russie", 55.8, 40.0, "RU")
        i("Russie d'Asie", 60.0, 95.0, "RU"); i("Turquie", 39.0, 35.2, "TR"); i("Chypre", 35.0, 33.2, "CY"); i("Malte", 35.9, 14.4, "MT")
        i("Monaco", 43.74, 7.42, "MC"); i("Andorre", 42.5, 1.55, "AD"); i("Vatican", 41.9, 12.45, "VA"); i("Saint-Marin", 43.94, 12.46, "SM")
        i("Svalbard", 78.2, 16.0, "SJ"); i("Jan Mayen", 71.0, -8.5, "SJ")
        i("États-Unis", 39.5, -98.4, "US"); i("Alaska", 63.6, -152.5, "US"); i("Hawaï", 20.8, -156.3, "US"); i("Porto Rico", 18.2, -66.5, "PR")
        i("Canada", 56.0, -96.0, "CA"); i("Mexique", 23.6, -102.5, "MX"); i("Brésil", -10.0, -52.0, "BR"); i("Argentine", -35.0, -65.0, "AR")
        i("Chili", -33.0, -71.0, "CL"); i("Pérou", -9.2, -75.0, "PE"); i("Colombie", 4.6, -74.1, "CO"); i("Venezuela", 7.0, -66.0, "VE")
        i("Équateur", -1.5, -78.2, "EC"); i("Uruguay", -32.8, -56.0, "UY"); i("Paraguay", -23.4, -58.4, "PY"); i("Bolivie", -16.3, -63.6, "BO")
        i("Rép. dominicaine", 18.7, -70.2, "DO"); i("Cuba", 21.6, -79.0, "CU"); i("Costa Rica", 9.7, -83.8, "CR"); i("Panama", 8.5, -80.1, "PA")
        i("Guatemala", 15.6, -90.3, "GT"); i("Groenland", 72.0, -40.0, "GL"); i("St-Pierre-et-Miquelon", 46.9, -56.3, "PM")
        i("Guadeloupe", 16.2, -61.6, "GP"); i("Martinique", 14.6, -61.0, "MQ"); i("Guyane", 4.0, -53.0, "GF"); i("Saint-Martin", 18.07, -63.05, "MF")
        i("Saint-Barthélemy", 17.9, -62.83, "BL"); i("Bermudes", 32.3, -64.75, "BM"); i("Barbade", 13.2, -59.55, "BB"); i("Jamaïque", 18.1, -77.3, "JM")
        i("Anguilla", 18.22, -63.05, "AI"); i("Montserrat", 16.74, -62.19, "MS"); i("Îles Vierges britanniques", 18.42, -64.62, "VG")
        i("Îles Vierges américaines", 18.34, -64.9, "VI")
        i("Maroc", 31.8, -7.1, "MA"); i("Algérie", 28.0, 2.6, "DZ"); i("Tunisie", 34.0, 9.5, "TN"); i("Égypte", 26.8, 30.8, "EG")
        i("Libye", 26.3, 17.2, "LY"); i("Afrique du Sud", -29.0, 25.0, "ZA"); i("Nigeria", 9.1, 8.7, "NG"); i("La Réunion", -21.1, 55.5, "RE")
        i("Mayotte", -12.8, 45.15, "YT"); i("Maurice", -20.3, 57.6, "MU"); i("Sénégal", 14.5, -14.5, "SN"); i("Kenya", 0.0, 37.9, "KE")
        i("Cap-Vert", 15.1, -23.6, "CV"); i("Seychelles", -4.7, 55.5, "SC"); i("Sainte-Hélène", -15.95, -5.7, "SH")
        i("Japon", 36.2, 138.3, "JP"); i("Corée du Sud", 36.5, 127.9, "KR"); i("Chine", 35.9, 104.2, "CN"); i("Taïwan", 23.7, 121.0, "TW")
        i("Hong Kong", 22.3, 114.2, "HK"); i("Inde", 21.0, 78.9, "IN"); i("Israël", 31.0, 34.9, "IL"); i("Jordanie", 31.2, 36.5, "JO")
        i("Liban", 33.9, 35.9, "LB"); i("Arabie saoudite", 23.9, 45.1, "SA"); i("Émirats arabes unis", 23.4, 53.8, "AE"); i("Qatar", 25.3, 51.2, "QA")
        i("Bahreïn", 26.07, 50.55, "BH"); i("Koweït", 29.3, 47.5, "KW"); i("Oman", 21.5, 55.9, "OM"); i("Iran", 32.4, 53.7, "IR")
        i("Pakistan", 30.4, 69.3, "PK"); i("Bangladesh", 23.7, 90.4, "BD"); i("Sri Lanka", 7.9, 80.8, "LK"); i("Thaïlande", 15.9, 101.0, "TH")
        i("Viêt Nam", 14.1, 108.3, "VN"); i("Singapour", 1.35, 103.8, "SG"); i("Malaisie Ouest", 4.2, 101.9, "MY"); i("Malaisie Est", 3.5, 115.0, "MY")
        i("Indonésie", -2.5, 118.0, "ID"); i("Philippines", 12.9, 121.8, "PH"); i("Arménie", 40.1, 45.0, "AM"); i("Géorgie", 42.3, 43.4, "GE")
        i("Azerbaïdjan", 40.1, 47.6, "AZ"); i("Kazakhstan", 48.0, 66.9, "KZ"); i("Kirghizistan", 41.2, 74.8, "KG"); i("Tadjikistan", 38.9, 71.3, "TJ")
        i("Turkménistan", 38.97, 59.6, "TM"); i("Ouzbékistan", 41.4, 64.6, "UZ"); i("Mongolie", 46.9, 103.8, "MN")
        i("Australie", -25.3, 133.8, "AU"); i("Nouvelle-Zélande", -41.0, 174.0, "NZ"); i("Nouvelle-Calédonie", -21.3, 165.5, "NC")
        i("Polynésie française", -17.7, -149.4, "PF"); i("Wallis-et-Futuna", -13.3, -176.2, "WF"); i("Guam", 13.44, 144.79, "GU")
        i("Mariannes du Nord", 15.2, 145.75, "MP"); i("Samoa américaines", -14.3, -170.7, "AS")
        i("Fidji", -17.7, 178.0, "FJ"); i("Papouasie-Nlle-Guinée", -6.3, 143.9, "PG"); i("Samoa", -13.76, -172.1, "WS"); i("Tonga", -21.2, -175.2, "TO")
    }
}
