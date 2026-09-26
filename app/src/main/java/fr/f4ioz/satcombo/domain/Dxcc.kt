/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.domain

/**
 * Le pays d'un indicatif, d'après son préfixe.
 *
 * La table vient de la liste ARRL des entités DXCC fournie par Olivier,
 * resserrée sur ce qu'un préfixe seul peut dire : les entités qui exigent de
 * connaître le suffixe numérique fin (Kaliningrad, les bases anglaises de
 * Chypre) sont traitées quand la règle tient en une ligne, ignorées sinon —
 * mieux vaut pas de pays qu'un pays faux.
 *
 * Le second champ est le code du drapeau dans [fr.f4ioz.satcombo.data.Flags]
 * quand il y est dessiné, vide sinon : on affiche alors le nom seul.
 *
 * L'appel se fait sur le préfixe efficace : pour EA5/F4IOZ c'est EA5 qui
 * compte — l'opérateur est en Espagne, c'est tout le sens du préfixe pays.
 */
object Dxcc {

    class Entite(val nom: String, val drapeau: String)

    /**
     * Préfixe → entité. Les clés sont essayées de la plus longue à la plus
     * courte, donc « EA8 » (Canaries) gagne sur « EA » (Espagne).
     */
    private val table: Map<String, Entite> = buildMap {
        fun p(prefixes: String, nom: String, drapeau: String = "") {
            val e = Entite(nom, drapeau)
            prefixes.split(" ").forEach { put(it, e) }
        }
        // Europe
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
        p("EA8 EB8 EC8", "Canaries", "ES")
        p("EA9 EB9 EC9", "Ceuta et Melilla", "ES")
        p("CT CQ CR CS", "Portugal", "PT")
        p("CT3 CS3", "Madère", "PT")
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
        p("TA TB TC", "Turquie", "TR")
        p("5B C4 H2", "Chypre")
        p("9H", "Malte", "MT")
        p("3A", "Monaco", "MC")
        p("C3", "Andorre")
        p("HV", "Vatican", "IT")
        p("T7", "Saint-Marin", "IT")
        p("JW", "Svalbard", "NO")
        p("JX", "Jan Mayen", "NO")
        // Amériques
        p("K W N AA AB AC AD AE AF AG AH AI AJ AK", "États-Unis", "US")
        p("KL AL NL WL", "Alaska", "US")
        p("KH6 AH6 NH6 WH6 KH7", "Hawaï", "US")
        p("KP4 NP4 WP4 KP3", "Porto Rico", "US")
        p("VE VA VO VY VB VC VD VF VG VX CF", "Canada", "CA")
        p("XE XA XB XC XD XF XG XH XI 4A 4B 4C 6D 6E 6F 6G 6H 6I 6J", "Mexique", "MX")
        p("PY PP PQ PR PS PT PU PV PW PX ZV ZW ZX ZY ZZ", "Brésil", "BR")
        p("LU LO LP LQ LR LS LT LV LW AY AZ L2 L3 L4 L5 L6 L7 L8 L9", "Argentine", "AR")
        p("CE CA CB CC CD XQ XR 3G", "Chili")
        p("OA OB OC 4T", "Pérou", "PE")
        p("HK HJ 5K", "Colombie")
        p("YV YW YX YY 4M", "Venezuela")
        p("HC HD", "Équateur")
        p("CX CV CW", "Uruguay")
        p("ZP", "Paraguay")
        p("CP", "Bolivie")
        p("HI", "Rép. dominicaine")
        p("CO CM CL T4", "Cuba")
        p("TI TE", "Costa Rica")
        p("HP HO", "Panama")
        p("TG TD", "Guatemala")
        p("XP OX", "Groenland", "DK")
        p("FP", "St-Pierre-et-Miquelon", "FR")
        p("FG", "Guadeloupe", "FR")
        p("FM", "Martinique", "FR")
        p("FY", "Guyane", "FR")
        p("FS", "Saint-Martin", "FR")
        p("FJ", "Saint-Barthélemy", "FR")
        p("VP9", "Bermudes", "GB")
        p("8P", "Barbade")
        p("6Y", "Jamaïque")
        // Afrique
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
        // Asie
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
        // Océanie
        p("VK AX", "Australie", "AU")
        p("ZL ZM", "Nouvelle-Zélande", "NZ")
        p("FK", "Nouvelle-Calédonie", "FR")
        p("FO", "Polynésie française", "FR")
        p("FW", "Wallis-et-Futuna", "FR")
        p("KH2 AH2", "Guam", "US")
        p("3D2", "Fidji")
        p("P2", "Papouasie-Nlle-Guinée")
        p("5W", "Samoa")
        p("A3", "Tonga")
    }

    /** Les longueurs de préfixe présentes, de la plus longue à la plus courte. */
    private val longueurs: List<Int> =
        table.keys.map { it.length }.distinct().sortedDescending()

    /**
     * L'entité DXCC d'un indicatif, ou nulle si le préfixe n'est pas connu.
     *
     * Le préfixe efficace est le préfixe pays s'il y en a un (EA5/F4IOZ → EA5) :
     * l'opérateur est là où le préfixe le met, c'est tout son sens. Un suffixe
     * d'exploitation (/P, /M) ne change rien au pays.
     */
    fun entite(indicatif: String): Entite? {
        val brut = indicatif.trim().uppercase()
        if (brut.isEmpty()) return null
        val morceaux = brut.split("/").filter { it.isNotEmpty() }
        val efficace = when {
            morceaux.isEmpty() -> return null
            // EA5/F4IOZ : le premier morceau est un préfixe pays s'il est plus
            // court que le corps et n'est pas un simple suffixe d'exploitation.
            morceaux.size >= 2 && morceaux[0].length in 1..4 &&
                morceaux[0].length < morceaux[1].length -> morceaux[0]
            else -> morceaux[0]
        }
        for (n in longueurs) {
            if (efficace.length >= n) {
                table[efficace.take(n)]?.let { return it }
            }
        }
        return null
    }
}
