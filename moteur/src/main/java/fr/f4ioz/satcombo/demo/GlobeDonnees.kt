/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.demo

import org.json.JSONArray
import org.json.JSONObject

/**
 * What the globes put on the Earth besides its coastline: cities, mountain
 * summits, landmarks, and the cartoon's scenery (boats, whales, forests,
 * penguins). The same list for the page's globes ([OrbitesJs], as JSON) and
 * the journal's videos drawn by SatMe-PC, so both show the same world.
 * Positions in degrees; written here by hand (public facts, no data set).
 */
object GlobeDonnees {

    /** A city: [rang] 1 the world's great ones, 2 other capitals and space centres, 3 the smaller ones near home (shown with more detail). */
    data class Ville(val nom: String, val lat: Double, val lon: Double, val rang: Int)

    /** A country's capital (Natural Earth, public domain): its French name, its country's ISO code (its flag; empty when none), its population. */
    data class Capitale(val nom: String, val lat: Double, val lon: Double, val pays: String, val population: Int)

    /** The capitals of the world, the most populous first (« nom|lat|lon|pays|population » separated by « ; »). */
    val CAPITALES: List<Capitale> by lazy {
        CAPITALES_TEXTE.split(';').map { s -> s.split('|').let { Capitale(it[0], it[1].toDouble(), it[2].toDouble(), it[3], it[4].toInt()) } }
    }

    private const val CAPITALES_TEXTE =
        "Tokyo|35.687|139.749|JP|35676000;Mexico|19.444|-99.133|MX|19028000;Dacca|23.725|90.407|BD|12797394;Buenos Aires|-34.601|-58.399|AR|12795000;Le Caire|30.052|31.248|EG|11893000;Pékin|39.931|116.386|CN|11106000;Manille|14.606|120.980|PH|11100000;Moscou|55.754|37.614|RU|10452000;" +
        "Paris|48.869|2.331|FR|9904000;Séoul|37.568|126.998|KR|9796000;Jakarta|-6.172|106.827|ID|9125000;Londres|51.502|-0.119|GB|8567000;Lima|-12.046|-77.052|PE|8012000;Téhéran|35.674|51.422|IR|7873000;Kinshasa|-4.328|15.313|CD|7843000;Bogota|4.598|-74.085|CO|7772000;" +
        "Taipei|25.036|121.568|TW|6900273;Bangkok|13.752|100.515|TH|6704000;Santiago|-33.448|-70.669|CL|5720000;Madrid|40.402|-3.685|ES|5567000;Singapour|1.295|103.854|SG|5183700;Luanda|-8.836|13.232|AO|5172900;Bagdad|33.341|44.392|IQ|5054000;Khartoum|15.590|32.532|SD|4754000;" +
        "Riyad|24.643|46.771|SA|4465000;Hanoï|21.035|105.848|VN|4378000;Washington|38.901|-77.011|US|4338000;Abidjan|5.322|-4.042|CI|3802000;Brasilia|-15.781|-47.918|BR|3716996;Ankara|39.929|32.862|TR|3716000;Berlin|52.524|13.400|DE|3406000;Alger|36.765|3.049|DZ|3354000;" +
        "Rome|41.898|12.481|IT|3339000;Pyongyang|39.021|125.753|KP|3300000;Kaboul|34.519|69.181|AF|3277000;Athènes|37.985|23.731|GR|3242000;Le Cap|-33.918|18.433|ZA|3215000;Addis-Abeba|9.035|38.698|ET|3100000;Nairobi|-1.281|36.815|KE|3010000;Caracas|10.503|-66.919|VE|2985000;" +
        "Dar es Salam|-6.798|39.266|TZ|2930000;Lisbonne|38.725|-9.147|PT|2812000;Kiev|50.435|30.515|UA|2709000;Dakar|14.718|-17.475|SN|2604000;Damas|33.502|36.298|SY|2466000;Tunis|36.803|10.180|TN|2412500;Vienne|48.202|16.365|AT|2400000;Tripoli|32.892|13.180|LY|2189000;" +
        "Tachkent|41.314|69.293|UZ|2184000;La Havane|23.134|-82.366|CU|2174000;Saint-Domingue|18.472|-69.902|DO|2154000;Bakou|40.397|49.860|AZ|2122300;Accra|5.552|-0.219|GH|2121000;Koweït|29.372|47.976|KW|2063000;Sanaa|15.357|44.205|YE|2008000;Port-au-Prince|18.543|-72.338|HT|1998000;" +
        "Bucarest|44.435|26.098|RO|1942000;Asuncion|-25.294|-57.643|PY|1870000;Beyrouth|33.874|35.508|LB|1846000;Minsk|53.902|27.565|BY|1805000;Bruxelles|50.835|4.331|BE|1743000;Varsovie|52.252|20.998|PL|1707000;Rabat|34.025|-6.836|MA|1705000;Quito|-0.213|-78.502|EC|1701000;" +
        "Antananarivo|-18.915|47.515|MG|1697000;Budapest|47.502|19.081|HU|1679000;Yaoundé|3.869|11.515|CM|1611000;La Paz|-16.496|-68.152|BO|1590000;Abuja|9.085|7.531|NG|1576000;Harare|-17.816|31.043|ZW|1572000;Montevideo|-34.856|-56.173|UY|1513000;Bamako|12.652|-8.002|ML|1494000;" +
        "Conakry|9.533|-13.682|GN|1494000;Phnom Penh|11.552|104.915|KH|1466000;Lomé|6.134|1.221|TG|1452000;Doha|25.287|51.533|QA|1450000;Kuala Lumpur|3.169|101.698|MY|1448000;Maputo|-25.953|32.587|MZ|1446000;San Salvador|13.712|-89.205|SV|1433000;Kampala|0.319|32.581|UG|1420000;" +
        "Brazzaville|-4.257|15.283|CG|1355000;Pretoria|-25.705|28.227|ZA|1338000;Lusaka|-15.415|28.281|ZM|1328000;San José|9.937|-84.086|CR|1284000;Panama|8.970|-79.535|PA|1281000;Stockholm|59.353|18.095|SE|1264000;Sofia|42.685|23.315|BG|1185000;Prague|50.085|14.464|CZ|1162000;" +
        "Ouagadougou|12.372|-1.527|BF|1149000;Ottawa|45.419|-75.702|CA|1145000;Helsinki|60.178|24.932|FI|1115000;Erevan|40.183|44.512|AM|1102000;Mogadiscio|2.069|45.365|SO|1100000;Tbilissi|41.727|44.789|GE|1100000;Belgrade|44.821|20.466|RS|1099000;Douchanbé|38.560|68.774|TJ|1086244;" +
        "Copenhague|55.681|12.562|DK|1085000;Amman|31.952|35.931|JO|1060000;Dublin|53.335|-6.251|IE|1059000;Monrovia|6.315|-10.800|LR|1041000;Amsterdam|52.352|4.915|NL|1031000;Jérusalem|31.778|35.207|IL|1029300;Guatemala-Ville|14.623|-90.529|GT|1024000;N'Djaména|12.115|15.047|TD|989000;" +
        "Tegucigalpa|14.104|-87.219|HN|946000;Kingston|17.977|-76.767|JM|937700;Naypyidaw|19.769|96.117|MM|930000;Djibouti|11.595|43.148|DJ|923000;Managua|12.155|-86.270|NI|920000;Niamey|13.519|2.115|NE|915000;Tirana|41.328|19.819|AL|895350;Katmandou|27.719|85.315|NP|895000;" +
        "Oulan-Bator|47.919|106.915|MN|885000;Kigali|-1.952|30.059|RW|860000;Bichkek|42.875|74.583|KG|837000;Oslo|59.919|10.748|NO|835000;Bangui|4.367|18.558|CF|831925;Freetown|8.472|-13.236|SL|827000;Islamabad|33.702|73.165|PK|780000;Cotonou|6.402|2.518|BJ|762000;" +
        "Vientiane|17.967|102.600|LA|754000;Riga|56.950|24.100|LV|742572;Nouakchott|18.086|-15.975|MR|742144;Mascate|23.613|58.593|OM|734697;Achgabat|37.950|58.383|TM|727700;Zagreb|45.800|16.000|HR|722526;Sarajevo|43.850|18.383|BA|696731;Chișinău|47.005|28.858|MD|688134;" +
        "Lilongwe|-13.983|33.783|MW|646750;Asmara|15.333|38.933|ER|620802;Abou Dabi|24.467|54.367|AE|603492;Port-Louis|-20.167|57.500|MU|595491;Libreville|0.385|9.458|GA|578156;Manama|26.236|50.583|BH|563920;Vilnius|54.683|25.317|LT|542366;Skopje|42.000|21.433|MK|494087;" +
        "Hargeisa|9.560|44.065||477876;Pristina|42.667|21.166|XK|465186;Bloemfontein|-29.120|26.230|ZA|463064;Bratislava|48.150|17.117|SK|423737;Bissau|11.865|-15.598|GW|403339;Tallinn|59.434|24.728|EE|394024;Wellington|-41.300|174.783|NZ|393400;La Valette|35.900|14.515|MT|368250;" +
        "Maseru|-29.317|27.483|LS|361324;Noursoultan|51.181|71.428|KZ|345604;Bujumbura|-3.376|29.360|BI|331700;Canberra|-35.283|149.129|AU|327700;New Delhi|28.600|77.200|IN|317797;Ljubljana|46.055|14.515|SI|314807;Bandar Seri Begawan|4.883|114.933|BN|296500;Port-d'Espagne|10.652|-61.517|TT|294934;" +
        "Port Moresby|-9.465|147.193|PG|283733;Berne|46.917|7.467|CH|275329;Windhoek|-22.570|17.084|NA|268132;Georgetown|6.802|-58.167|GY|264350;Paramaribo|5.835|-55.167|SR|254169;Dili|-8.559|125.579|TL|234331;Nassau|25.083|-77.350|BS|227940;Sucre|-19.041|-65.260|BO|224838;" +
        "Nicosie|35.167|33.367|CY|224300;Colombo|6.932|79.858|LK|217000;Gaborone|-24.646|25.912|BW|208411;Yamoussoukro|6.818|-5.276|CI|206499;Bridgetown|13.102|-59.617|BB|191152;Suva|-18.133|178.442|FJ|175399;Reykjavik|64.150|-21.950|IS|166212;Malabo|3.750|8.783|GQ|155963;" +
        "Podgorica|42.466|19.266|ME|145850;Moroni|-11.704|43.240|KM|128698;Praia|14.917|-23.517|CV|113364;Malé|4.167|73.500|MV|112927;Luxembourg|49.612|6.130|LU|107260;Thimphou|27.473|89.639|BT|98676;Mbabane|-26.317|31.133|SZ|90138;São Tomé|0.333|6.733|ST|88219;" +
        "Honiara|-9.438|159.950|SB|76328;Apia|-13.842|-171.739|WS|61916;Andorre-la-Vieille|42.500|1.516|AD|53998;Kingstown|13.148|-61.212|VC|49485;Port-Vila|-17.733|168.317|VU|44040;Banjul|13.454|-16.592|GM|43094;Nukuʻalofa|-21.139|-175.221|TO|42620;Castries|14.002|-61.000|LC|37963;" +
        "Monaco|43.740|7.407|MC|36371;Vaduz|47.134|9.517|LI|36281;Saint John's|17.118|-61.850|AG|35499;Saint-Georges|12.053|-61.742|GD|33734;Victoria|-4.617|55.450|SC|33576;Saint-Marin|43.936|12.442|SM|29579;Tarawa-Sud|1.338|173.018|KI|28802;Majuro|7.103|171.380|MH|25400;" +
        "Roseau|15.301|-61.387|DM|23336;Basseterre|17.302|-62.717|KN|21887;Belmopan|17.252|-88.767|BZ|15220;Melekeok|7.487|134.627|PW|7026;Funafuti|-8.517|179.217|TV|4749;Palikir|6.917|158.150|FM|4645;Cité du Vatican|41.903|12.453|VA|832"

    /**
     * A summit standing out of its surroundings (at least 450 m above the lowest point within 2°),
     * found in the ETOPO1 relief (NOAA, `relief.png`, 0.25° grid): where the globes put their mountains.
     * The ice sheets of Antarctica and Greenland are left out (high, but no mountains). [altitude] in metres.
     */
    data class Sommet(val lat: Double, val lon: Double, val altitude: Int)

    /**
     * A landmark: [id] its own, [type] its family (a key of [TYPES_MONUMENTS], the drawing when it has none of its own),
     * [pays] its country's ISO code, [rang] 1 the world's most famous (« principaux »), 2 the others,
     * [modele] its own drawing, null for its type's (the journal's videos draw the main ones that have their own).
     */
    data class Monument(val id: String, val nom: String, val lat: Double, val lon: Double, val type: String, val pays: String, val rang: Int, val modele: String?)

    /** The cartoon's scenery: [type] "bateau", "baleine", "foret", "manchot". */
    data class Decor(val type: String, val lat: Double, val lon: Double)

    val VILLES = listOf(
        Ville("Paris", 48.857, 2.352, 1), Ville("Londres", 51.507, -0.128, 1), Ville("New York", 40.713, -74.006, 1),
        Ville("Tokyo", 35.690, 139.692, 1), Ville("Moscou", 55.756, 37.617, 1), Ville("Pékin", 39.904, 116.407, 1),
        Ville("Sydney", -33.869, 151.209, 1), Ville("Rio de Janeiro", -22.907, -43.173, 1), Ville("Le Caire", 30.044, 31.236, 1),
        Ville("Los Angeles", 34.052, -118.244, 1), Ville("Mumbai", 19.076, 72.878, 1), Ville("Le Cap", -33.925, 18.424, 1),
        Ville("Buenos Aires", -34.604, -58.382, 1), Ville("Mexico", 19.433, -99.133, 1), Ville("Singapour", 1.352, 103.820, 1),
        Ville("Dubaï", 25.205, 55.271, 1), Ville("Lagos", 6.524, 3.379, 1), Ville("Istanbul", 41.008, 28.978, 1),
        Ville("Delhi", 28.614, 77.209, 1), Ville("Shanghai", 31.230, 121.474, 1),
        Ville("Rome", 41.903, 12.496, 2), Ville("Madrid", 40.417, -3.704, 2), Ville("Berlin", 52.520, 13.405, 2),
        Ville("Reykjavik", 64.147, -21.942, 2), Ville("Anchorage", 61.218, -149.900, 2), Ville("Honolulu", 21.307, -157.858, 2),
        Ville("Santiago", -33.449, -70.669, 2), Ville("Lima", -12.046, -77.043, 2), Ville("Nairobi", -1.292, 36.822, 2),
        Ville("Bangkok", 13.756, 100.502, 2), Ville("Séoul", 37.567, 126.978, 2), Ville("Jakarta", -6.208, 106.846, 2),
        Ville("Johannesburg", -26.204, 28.047, 2), Ville("Kinshasa", -4.441, 15.266, 2), Ville("Montréal", 45.502, -73.567, 2),
        Ville("Vancouver", 49.283, -123.121, 2), Ville("Perth", -31.951, 115.861, 2), Ville("Auckland", -36.848, 174.763, 2),
        Ville("Toronto", 43.653, -79.383, 2), Ville("Houston", 29.760, -95.370, 2), Ville("Baïkonour", 45.965, 63.305, 2),
        Ville("Kourou", 5.160, -52.650, 2), Ville("Cap Canaveral", 28.392, -80.608, 2), Ville("Alger", 36.754, 3.059, 2),
        Ville("Casablanca", 33.573, -7.590, 2), Ville("Dakar", 14.716, -17.467, 2), Ville("Stockholm", 59.329, 18.069, 2),
        Ville("Athènes", 37.984, 23.728, 2), Ville("Lisbonne", 38.722, -9.139, 2), Ville("Varsovie", 52.230, 21.012, 2),
        Ville("Lyon", 45.764, 4.836, 3), Ville("Marseille", 43.296, 5.370, 3), Ville("Toulouse", 43.605, 1.444, 3),
        Ville("Bordeaux", 44.838, -0.579, 3), Ville("Lille", 50.629, 3.057, 3), Ville("Nantes", 47.218, -1.554, 3),
        Ville("Strasbourg", 48.573, 7.752, 3), Ville("Brest", 48.390, -4.486, 3), Ville("Nice", 43.710, 7.262, 3),
        Ville("Rennes", 48.117, -1.678, 3), Ville("Ajaccio", 41.919, 8.739, 3), Ville("Bruxelles", 50.850, 4.352, 3),
        Ville("Genève", 46.204, 6.143, 3), Ville("Amsterdam", 52.370, 4.895, 3), Ville("Barcelone", 41.385, 2.173, 3),
        Ville("Munich", 48.135, 11.582, 3), Ville("Vienne", 48.208, 16.373, 3), Ville("Oslo", 59.913, 10.752, 3),
        Ville("Dublin", 53.350, -6.260, 3), Ville("Milan", 45.464, 9.190, 3))

    /** The summits, highest first (worked out once from the relief, written here: « lat lon altitude » separated by « ; »). */
    val SOMMETS: List<Sommet> by lazy {
        SOMMETS_TEXTE.split(';').map { s -> s.trim().split(' ').let { Sommet(it[0].toDouble(), it[1].toDouble(), it[2].toInt()) } }
    }

    private const val SOMMETS_TEXTE =
        "35.50 80.75 5619;35.75 77.25 5564;30.00 85.25 5556;30.75 83.25 5487;31.75 80.50 5371;34.00 89.25 5316;34.00 90.50 5314;34.50 85.75 5302;28.25 86.75 5250;28.25 90.50 5248;" +
        "30.75 93.25 5240;31.75 85.50 5142;29.50 97.00 5119;32.00 95.25 5078;-27.75 -68.75 4944;38.75 72.25 4943;-22.50 -67.50 4830;-15.00 -72.50 4745;-16.75 -70.25 4706;36.75 72.00 4701;" +
        "-14.00 -70.75 4666;33.25 99.00 4664;34.00 97.75 4653;29.75 99.50 4632;34.75 99.25 4624;32.50 97.50 4618;-29.50 -69.75 4570;-19.50 -66.25 4530;-14.75 -69.50 4486;38.25 96.50 4482;" +
        "-13.00 -75.25 4456;-26.00 -66.75 4449;-11.25 -76.25 4439;35.75 70.25 4404;-10.00 -77.00 4380;31.25 101.50 4344;-17.50 -67.25 4084;42.25 80.25 4081;41.25 77.75 4011;-32.75 -69.75 3927;" +
        "43.25 85.75 3660;39.00 93.75 3547;-0.75 -78.50 3484;39.25 69.25 3454;35.25 67.00 3405;39.00 -106.50 3355;37.75 -107.25 3331;6.50 -72.50 3192;42.25 73.50 3161;44.75 80.25 3031;" +
        "44.00 -109.50 2997;42.00 71.50 2993;46.75 92.00 2980;40.75 -110.50 2978;7.00 39.50 2869;47.75 98.00 2850;37.25 -118.75 2822;4.50 -74.00 2820;19.50 -99.75 2814;11.00 39.25 2812;" +
        "37.00 -105.25 2797;61.00 -140.25 2760;46.75 100.75 2737;48.50 88.25 2722;9.50 39.25 2708;19.50 -98.25 2692;-29.25 29.00 2681;-4.00 138.50 2679;3.75 -75.75 2624;26.25 -106.25 2604;" +
        "36.00 52.00 2603;29.50 57.00 2594;38.25 -111.75 2591;-5.75 143.50 2573;32.75 50.25 2573;43.25 43.00 2557;43.25 94.25 2547;31.00 52.00 2540;45.25 7.00 2535;38.00 43.75 2533;" +
        "26.00 103.25 2526;2.50 -76.25 2513;52.25 -117.00 2501;24.00 -105.50 2492;45.25 97.75 2471;43.75 -114.75 2439;34.00 -109.25 2419;15.50 44.25 2410;8.50 -71.25 2408;-0.50 35.75 2398;" +
        "31.75 -6.25 2397;28.00 -107.50 2395;44.25 -113.25 2392;42.00 47.00 2390;14.25 44.50 2386;38.00 47.50 2374;42.50 44.50 2363;46.50 9.75 2363;15.25 -91.50 2360;40.00 45.75 2359;" +
        "30.75 67.75 2325;51.75 100.25 2320;44.25 -107.25 2305;39.00 -116.50 2293;21.50 -101.50 2280;14.00 39.50 2278;62.00 -143.25 2266;50.00 98.00 2265;40.50 41.00 2249;39.00 -114.50 2247;" +
        "45.75 -113.50 2245;1.00 35.25 2242;33.00 -105.50 2224;36.75 47.00 2216;39.75 39.50 2203;-3.50 28.75 2189;17.25 -96.75 2168;-1.75 29.25 2165;25.00 -100.50 2159;28.75 66.50 2156;" +
        "8.00 36.25 2144;18.25 43.00 2142;41.75 96.00 2133;43.50 90.25 2132;41.75 -115.75 2129;50.25 -116.50 2101;50.25 -115.00 2092;62.25 141.25 2062;36.25 -112.00 2049;47.50 -113.00 2041;" +
        "10.75 -73.75 2017;33.00 59.50 2004;51.25 -123.50 1970;36.50 48.75 1962;47.25 13.50 1938;-9.25 34.50 1938;43.75 40.50 1937;48.25 107.50 1930;51.75 -125.25 1901;45.75 83.00 1890;" +
        "58.25 -125.00 1888;20.75 17.25 1874;47.00 85.25 1859;62.50 -127.00 1858;38.50 36.50 1858;21.00 18.50 1857;9.25 42.00 1846;41.75 -119.25 1844;48.50 109.00 1843;43.50 103.75 1837;" +
        "33.75 107.75 1826;48.50 -120.75 1825;23.25 5.75 1818;-3.00 37.00 1817;-22.75 17.00 1815;31.75 109.00 1804;-8.50 147.25 1802;-25.50 30.25 1797;35.75 59.25 1794;-7.50 29.75 1783;" +
        "-19.75 47.25 1780;61.50 -147.25 1777;4.00 97.25 1771;41.25 111.00 1753;-8.25 31.50 1752;24.25 121.25 1748;44.00 -103.75 1748;65.75 141.25 1743;28.75 61.00 1742;52.00 95.75 1740;" +
        "51.00 92.75 1739;51.25 103.50 1738;41.25 112.25 1734;37.25 58.00 1724;-12.50 16.50 1717;50.50 84.25 1716;43.25 100.75 1714;-4.25 35.25 1709;64.50 -132.00 1691;-12.50 15.00 1690;" +
        "56.50 113.25 1671;57.50 -128.25 1667;56.75 -126.25 1663;38.75 112.00 1658;-15.00 13.50 1657;65.00 146.00 1657;61.75 143.00 1656;-39.00 -70.75 1655;44.50 -118.50 1654;39.25 113.50 1654;" +
        "41.25 106.75 1652;55.25 110.50 1647;58.00 -132.25 1646;56.50 119.00 1642;59.00 -137.50 1629;60.25 -135.75 1624;55.75 114.75 1619;-49.00 -73.25 1608;38.75 105.75 1607;31.25 -110.25 1603;" +
        "-2.00 120.25 1603;63.00 -151.25 1601;41.50 128.75 1599;9.75 -83.75 1595;-31.75 24.50 1588;36.75 29.75 1585;69.00 -143.50 1577;36.75 32.75 1573;78.50 -79.75 1565;61.25 -152.75 1555;" +
        "30.50 -104.00 1554;2.25 30.50 1550;25.75 94.75 1548;-25.75 16.75 1542;34.00 36.25 1535;61.75 8.50 1535;46.75 -121.25 1531;22.25 93.50 1528;63.50 -145.25 1527;42.75 117.75 1526;" +
        "40.00 103.25 1525;64.00 147.50 1522;61.75 -133.00 1522;41.25 -122.75 1521;19.50 -155.50 1518;-28.00 23.75 1513;10.50 47.50 1511;-9.50 27.75 1510;82.25 -76.75 1507;41.25 115.50 1505;" +
        "-18.25 32.50 1502;63.75 144.25 1498;65.25 144.00 1485;6.25 10.50 1484;63.50 -147.00 1480;-11.25 31.75 1476;59.00 -134.00 1471;39.75 109.25 1465;15.00 48.50 1461;-11.25 26.25 1460;" +
        "-12.75 30.75 1456;79.75 -91.25 1455;-5.75 30.50 1450;42.75 20.00 1450;42.50 1.00 1449;25.25 8.00 1445;28.75 -102.25 1442;-18.50 14.00 1439;55.75 130.25 1436;-14.25 49.00 1436;" +
        "-31.75 22.50 1431;64.50 -138.50 1429;47.50 82.00 1427;60.00 -130.75 1427;45.25 114.50 1426;42.00 23.50 1420;75.25 -82.25 1411;30.25 110.50 1409;-6.25 34.00 1405;3.50 34.00 1399;" +
        "29.50 64.00 1398;64.50 -135.00 1392;-32.25 20.75 1389;27.50 37.00 1388;13.25 24.25 1388;40.75 32.75 1386;64.50 133.00 1384;52.75 134.50 1383;65.75 129.25 1382;67.50 128.25 1382;" +
        "5.50 -61.75 1377;-41.50 -71.00 1375;68.50 -147.75 1373;24.75 9.50 1366;37.25 113.50 1351;-1.25 133.75 1351;35.75 138.00 1348;40.50 -1.75 1347;-20.25 28.50 1343;69.00 95.75 1331;" +
        "57.25 127.75 1330;-24.50 28.00 1327;68.00 -150.50 1327;34.25 -117.00 1326;25.75 40.00 1325;41.00 92.50 1324;-42.00 -68.50 1319;-43.75 170.25 1314;-27.25 18.75 1310;-36.00 148.50 1308;" +
        "19.50 103.75 1301;14.50 -88.50 1301;34.00 1.75 1294;50.50 134.50 1294;42.00 -3.00 1293;-31.50 -65.00 1285;17.00 121.00 1284;-17.00 26.50 1274;-43.25 -72.00 1265;9.75 45.25 1262;" +
        "42.75 -6.25 1250;56.50 124.00 1250;20.75 97.25 1243;47.25 120.25 1242;66.75 144.25 1241;-14.50 34.00 1238;45.50 23.75 1235;-8.50 15.75 1229;54.00 117.75 1229;7.00 14.25 1225;" +
        "-44.75 -71.50 1225;38.00 -2.50 1221;40.25 20.75 1220;69.00 93.00 1218;-13.00 35.50 1212;58.00 129.25 1211;81.75 -81.25 1209;35.50 6.00 1205;34.50 -1.25 1200;-22.50 26.50 1200;" +
        "68.00 -153.25 1194;-44.50 168.75 1186;60.75 152.50 1185;29.00 35.50 1178;26.75 58.00 1174;9.50 9.00 1172;-40.50 -69.25 1172;30.25 35.75 1168;40.50 -5.25 1168;-42.25 173.00 1159;" +
        "-22.00 -45.00 1159;19.00 -71.00 1157;-13.00 -41.75 1155;42.50 13.25 1154;53.00 114.50 1144;55.25 157.75 1144;4.00 -64.75 1142;-28.25 -50.00 1135;31.00 -115.50 1128;-30.00 151.75 1127;" +
        "44.25 17.00 1124;28.75 34.00 1118;17.00 22.75 1111;63.00 157.50 1111;47.00 25.50 1109;-33.00 -66.00 1108;-8.75 24.75 1107;-18.25 -43.75 1103;-22.25 -46.25 1099;48.25 24.50 1097;" +
        "11.00 50.00 1096;25.50 91.75 1095;-26.50 -51.75 1095;4.00 115.75 1095;54.00 89.25 1094;29.25 37.00 1094;62.00 171.00 1089;-23.50 46.50 1088;55.75 160.50 1087;59.25 115.00 1085;" +
        "-2.00 101.50 1082;61.75 150.25 1069;18.00 8.75 1069;22.00 44.00 1066;44.75 3.75 1064;-20.00 -46.75 1062;68.75 98.50 1056;2.00 116.00 1052;54.75 -115.75 1052;61.75 155.50 1048;" +
        "47.25 137.25 1047;68.00 178.00 1044;-16.00 -48.00 1043;72.25 -76.75 1029;47.75 -123.50 1022;67.25 17.75 1019;61.25 147.75 1018;10.00 77.25 1018;49.00 20.00 1015;12.00 108.25 1010;" +
        "51.50 122.50 1006;14.25 22.25 1005;-29.50 19.50 1002;49.00 76.00 1002;11.50 76.50 997;38.75 22.25 996;-42.00 146.50 989;79.00 17.75 984;-24.50 -50.00 984;64.75 163.25 976;" +
        "54.75 123.00 973;-7.00 107.75 972;35.25 -83.25 971;18.50 82.75 967;13.25 76.00 967;57.00 137.50 967;15.00 108.00 964;62.00 11.25 958;44.25 128.25 957;48.50 138.25 955;" +
        "23.25 57.25 954;66.75 167.50 953;-13.75 -47.50 949;38.50 -80.00 947;23.50 26.00 945;19.00 36.50 939;27.75 119.50 939;55.50 106.50 938;-15.50 -42.50 933;65.50 165.50 924;" +
        "68.00 -137.25 924;-33.75 149.75 920;53.75 105.25 920;17.00 52.75 920;5.00 -66.75 914;57.00 -119.25 910;60.25 -149.75 906;23.75 46.50 900"

    /** The landmarks' families, as the globes' filter shows them. */
    val TYPES_MONUMENTS = linkedMapOf(
        "tour" to "Tours et phares", "pont" to "Ponts", "chateau" to "Châteaux, palais, portes", "religieux" to "Temples, églises, mosquées",
        "ancien" to "Sites antiques", "statue" to "Statues", "naturel" to "Merveilles naturelles", "moderne" to "Architecture moderne",
        "espace" to "Bases spatiales, observatoires")

    /** One to a few famous landmarks of most countries (« id|nom|lat|lon|type|pays|rang|modele » separated by « ; »). */
    val MONUMENTS: List<Monument> by lazy {
        MONUMENTS_TEXTE.split(';').map { s -> s.split('|').let { Monument(it[0], it[1], it[2].toDouble(), it[3].toDouble(), it[4], it[5], it[6].toInt(), it[7].ifEmpty { null }) } }
    }

    private const val MONUMENTS_TEXTE =
        "tour_eiffel|Tour Eiffel|48.858|2.294|tour|FR|1|tour_eiffel;mont_saint_michel|Mont-Saint-Michel|48.636|-1.511|religieux|FR|1|;versailles|Château de Versailles|48.805|2.120|chateau|FR|2|;arc_triomphe|Arc de Triomphe|48.874|2.295|chateau|FR|2|;" +
        "pont_du_gard|Pont du Gard|43.947|4.535|pont|FR|2|;mont_blanc|Mont Blanc|45.833|6.865|naturel|FR|1|;chambord|Château de Chambord|47.616|1.517|chateau|FR|2|;viaduc_millau|Viaduc de Millau|44.077|3.022|pont|FR|2|;" +
        "carcassonne|Cité de Carcassonne|43.206|2.364|chateau|FR|2|;lascaux|Grotte de Lascaux|45.054|1.168|ancien|FR|2|;pic_du_midi|Observatoire du Pic du Midi|42.936|0.142|espace|FR|2|;phare_eckmuhl|Phare d'Eckmühl|47.797|-4.372|tour|FR|1|phare;" +
        "kourou|Centre spatial de Kourou|5.236|-52.768|espace|FR|1|fusee;big_ben|Big Ben|51.501|-0.125|tour|GB|1|big_ben;tower_bridge|Tower Bridge|51.506|-0.075|pont|GB|2|;stonehenge|Stonehenge|51.179|-1.826|ancien|GB|1|;" +
        "edimbourg|Château d'Édimbourg|55.949|-3.200|chateau|GB|2|;chaussee_geants|Chaussée des Géants|55.241|-6.512|naturel|GB|2|;jodrell_bank|Radiotélescope de Jodrell Bank|53.237|-2.307|espace|GB|2|;moher|Falaises de Moher|52.972|-9.426|naturel|IE|2|;" +
        "newgrange|Newgrange|53.695|-6.475|ancien|IE|2|;sagrada_familia|Sagrada Família|41.404|2.174|religieux|ES|1|;alhambra|Alhambra|37.176|-3.588|chateau|ES|1|;cordoue|Mosquée-cathédrale de Cordoue|37.879|-4.779|religieux|ES|2|;" +
        "guggenheim|Musée Guggenheim de Bilbao|43.268|-2.934|moderne|ES|2|;segovie|Aqueduc de Ségovie|40.948|-4.118|pont|ES|2|;teide|Teide|28.272|-16.642|naturel|ES|2|;belem|Tour de Belém|38.692|-9.216|tour|PT|1|;" +
        "pena|Palais de Pena|38.788|-9.391|chateau|PT|2|;colisee|Colisée|41.890|12.492|ancien|IT|1|colisee;pise|Tour de Pise|43.723|10.396|tour|IT|1|;saint_marc|Basilique Saint-Marc|45.434|12.340|religieux|IT|2|;" +
        "dome_milan|Dôme de Milan|45.464|9.192|religieux|IT|2|;vesuve|Vésuve|40.821|14.426|naturel|IT|2|;pompei|Pompéi|40.749|14.485|ancien|IT|2|;etna|Etna|37.751|14.993|naturel|IT|2|;" +
        "saint_pierre|Basilique Saint-Pierre|41.902|12.454|religieux|VA|1|;parthenon|Parthénon|37.972|23.726|ancien|GR|1|;meteores|Météores|39.721|21.631|religieux|GR|2|;delphes|Delphes|38.482|22.501|ancien|GR|2|;" +
        "brandebourg|Porte de Brandebourg|52.516|13.378|chateau|DE|1|;neuschwanstein|Château de Neuschwanstein|47.558|10.750|chateau|DE|1|;cologne|Cathédrale de Cologne|50.941|6.958|religieux|DE|2|;kinderdijk|Moulins de Kinderdijk|51.884|4.637|tour|NL|1|moulin;" +
        "atomium|Atomium|50.895|4.341|moderne|BE|1|;bock|Casemates du Bock|49.612|6.137|chateau|LU|2|;cervin|Cervin|45.976|7.658|naturel|CH|1|;chillon|Château de Chillon|46.414|6.927|chateau|CH|2|;" +
        "schonbrunn|Château de Schönbrunn|48.185|16.312|chateau|AT|2|;pont_charles|Pont Charles|50.086|14.411|pont|CZ|2|;wawel|Château du Wawel|50.054|19.935|chateau|PL|2|;parlement_budapest|Parlement de Budapest|47.507|19.046|chateau|HU|2|;" +
        "bratislava|Château de Bratislava|48.142|17.100|chateau|SK|2|;bled|Île de Bled|46.362|14.090|religieux|SI|2|;dubrovnik|Remparts de Dubrovnik|42.641|18.108|chateau|HR|2|;plitvice|Lacs de Plitvice|44.880|15.616|naturel|HR|2|;" +
        "mostar|Vieux pont de Mostar|43.337|17.815|pont|BA|2|;belgrade|Forteresse de Belgrade|44.823|20.450|chateau|RS|2|;kotor|Bouches de Kotor|42.425|18.771|naturel|ME|2|;butrint|Butrint|39.746|20.020|ancien|AL|2|;" +
        "kaneo|Saint-Jean de Kaneo, Ohrid|41.111|20.789|religieux|MK|2|;rila|Monastère de Rila|42.134|23.340|religieux|BG|2|;bran|Château de Bran|45.515|25.367|chateau|RO|2|;orheiul|Orheiul Vechi|47.302|28.966|religieux|MD|2|;" +
        "laure_kiev|Laure des Grottes de Kiev|50.434|30.557|religieux|UA|2|;mir|Château de Mir|53.451|26.473|chateau|BY|2|;trakai|Château de Trakai|54.652|24.934|chateau|LT|2|;riga|Maison des Têtes noires|56.947|24.107|chateau|LV|2|;" +
        "tallinn|Vieille ville de Tallinn|59.437|24.745|chateau|EE|2|;suomenlinna|Suomenlinna|60.145|24.988|chateau|FI|2|;stockholm|Hôtel de ville de Stockholm|59.327|18.054|tour|SE|2|;geiranger|Geirangerfjord|62.101|7.094|naturel|NO|1|;" +
        "svalbard|Réserve mondiale de semences|78.236|15.491|moderne|NO|2|;petite_sirene|La Petite Sirène|55.693|12.599|statue|DK|2|;geysir|Geysir|64.313|-20.300|naturel|IS|1|;ilulissat|Fjord glacé d'Ilulissat|69.130|-49.900|naturel|GL|2|;" +
        "kremlin|Kremlin|55.752|37.617|chateau|RU|1|kremlin;ermitage|Ermitage|59.940|30.314|chateau|RU|2|;baikal|Lac Baïkal|53.500|108.000|naturel|RU|1|;elbrouz|Elbrouz|43.355|42.439|naturel|RU|2|;" +
        "plesetsk|Cosmodrome de Plessetsk|62.925|40.577|espace|RU|2|fusee;vostotchny|Cosmodrome Vostotchny|51.884|128.333|espace|RU|2|fusee;sainte_sophie|Sainte-Sophie|41.008|28.980|religieux|TR|1|;cappadoce|Cappadoce|38.643|34.828|naturel|TR|2|;" +
        "pamukkale|Pamukkale|37.924|29.121|naturel|TR|2|;ephese|Éphèse|37.940|27.341|ancien|TR|2|;tombeaux_rois|Tombeaux des Rois, Paphos|34.786|32.405|ancien|CY|2|;hagar_qim|Temples de Ħaġar Qim|35.828|14.442|ancien|MT|2|;" +
        "guergueti|Trinité de Guerguéti|42.662|44.620|religieux|GE|2|;khor_virap|Monastère de Khor Virap|39.878|44.576|religieux|AM|2|;flame_towers|Flame Towers|40.360|49.826|moderne|AZ|2|;baikonour|Baïkonour|45.920|63.342|espace|KZ|1|fusee;" +
        "baiterek|Tour Baïterek|51.128|71.431|tour|KZ|2|;registan|Registan de Samarcande|39.655|66.976|religieux|UZ|1|;darvaza|Cratère de Darvaza|40.253|58.440|naturel|TM|2|;issyk_koul|Lac Issyk-Koul|42.450|77.250|naturel|KG|2|;" +
        "ismail_samani|Pic Ismail Samani|38.943|72.016|naturel|TJ|2|;bamiyan|Vallée de Bamiyan|34.832|67.827|ancien|AF|2|;persepolis|Persépolis|29.935|52.891|ancien|IR|1|;ispahan|Place Naghsh-e Jahan|32.657|51.677|religieux|IR|2|;" +
        "ur|Ziggourat d'Ur|30.963|46.103|ancien|IQ|2|;palmyre|Palmyre|34.551|38.269|ancien|SY|2|;baalbek|Baalbek|34.007|36.204|ancien|LB|2|;massada|Massada|31.316|35.354|ancien|IL|2|;" +
        "petra|Pétra|30.329|35.444|ancien|JO|1|;hegra|Hégra (Al-Ula)|26.791|37.953|ancien|SA|2|;burj_khalifa|Burj Khalifa|25.197|55.274|moderne|AE|1|;doha_mia|Musée d'art islamique|25.295|51.539|moderne|QA|2|;" +
        "qalat_bahrein|Qal'at al-Bahreïn|26.233|50.520|chateau|BH|2|;tours_koweit|Tours de Koweït|29.389|48.004|tour|KW|2|;qabus|Mosquée Sultan Qabus|23.584|58.388|religieux|OM|2|;shibam|Shibam|15.927|48.627|ancien|YE|2|;" +
        "pyramides|Pyramides de Gizeh|29.979|31.134|ancien|EG|1|pyramides;abou_simbel|Abou Simbel|22.337|31.626|ancien|EG|2|;leptis|Leptis Magna|32.638|14.291|ancien|LY|2|;el_jem|Amphithéâtre d'El Jem|35.296|10.707|ancien|TN|2|;" +
        "timgad|Timgad|35.484|6.468|ancien|DZ|2|;hassan2|Mosquée Hassan-II|33.608|-7.633|religieux|MA|1|;richat|Œil du Sahara (Richat)|21.124|-11.395|naturel|MR|2|;chinguetti|Mosquée de Chinguetti|20.462|-12.364|religieux|MR|2|;" +
        "djenne|Grande Mosquée de Djenné|13.905|-4.556|religieux|ML|1|;renaissance|Monument de la Renaissance africaine|14.722|-17.495|statue|SN|2|;agadez|Grande Mosquée d'Agadez|16.973|7.991|religieux|NE|2|;loropeni|Ruines de Loropéni|10.300|-3.567|ancien|BF|2|;" +
        "cape_coast|Château de Cape Coast|5.103|-1.243|chateau|GH|2|;yamoussoukro|Notre-Dame de la Paix|6.811|-5.297|religieux|CI|2|;zuma|Zuma Rock|9.128|7.234|naturel|NG|2|;mont_cameroun|Mont Cameroun|4.203|9.170|naturel|CM|2|;" +
        "lalibela|Églises de Lalibela|12.032|39.041|religieux|ET|1|;aksoum|Obélisques d'Aksoum|14.131|38.719|ancien|ET|2|;meroe|Pyramides de Méroé|16.938|33.749|ancien|SD|2|;mont_kenya|Mont Kenya|-0.152|37.308|naturel|KE|2|;" +
        "kilimandjaro|Kilimandjaro|-3.067|37.356|naturel|TZ|1|;source_nil|Source du Nil (Jinja)|0.424|33.204|naturel|UG|2|;karisimbi|Volcans des Virunga|-1.508|29.445|naturel|RW|2|;nyiragongo|Nyiragongo|-1.520|29.250|naturel|CD|2|;" +
        "victoria|Chutes Victoria|-17.925|25.857|naturel|ZW|1|;grand_zimbabwe|Grand Zimbabwe|-20.268|30.934|ancien|ZW|2|;ile_mozambique|Île de Mozambique|-15.035|40.735|chateau|MZ|2|;baobabs|Allée des Baobabs|-20.251|44.419|naturel|MG|1|;" +
        "table|Montagne de la Table|-33.963|18.403|naturel|ZA|1|;bonne_esperance|Cap de Bonne-Espérance|-34.357|18.474|naturel|ZA|2|;sossusvlei|Dunes de Sossusvlei|-24.730|15.340|naturel|NA|2|;okavango|Delta de l'Okavango|-19.300|22.900|naturel|BW|2|;" +
        "kalandula|Chutes de Kalandula|-9.075|16.000|naturel|AO|2|;morne|Le Morne Brabant|-20.453|57.318|naturel|MU|2|;fogo|Pico do Fogo|14.950|-24.350|naturel|CV|2|;taj_mahal|Taj Mahal|27.175|78.042|religieux|IN|1|taj_mahal;" +
        "porte_inde|Porte de l'Inde|18.922|72.835|chateau|IN|2|;temple_or|Temple d'or d'Amritsar|31.620|74.876|religieux|IN|2|;qutb|Qutb Minar|28.524|77.185|tour|IN|2|;sriharikota|Base de Sriharikota|13.733|80.235|espace|IN|2|fusee;" +
        "badshahi|Mosquée Badshahi|31.588|74.310|religieux|PK|2|;k2|K2|35.881|76.513|naturel|PK|2|;everest|Everest|27.988|86.925|naturel|NP|1|;taktshang|Monastère de Taktshang|27.492|89.363|religieux|BT|2|;" +
        "sangsad|Parlement de Dacca|23.762|90.378|moderne|BD|2|;sigiriya|Rocher de Sigiriya|7.957|80.760|ancien|LK|2|;muraille|Grande Muraille|40.430|116.570|ancien|CN|1|muraille;cite_interdite|Cité interdite|39.916|116.397|chateau|CN|2|;" +
        "terre_cuite|Armée de terre cuite|34.385|109.279|ancien|CN|2|;perle_orient|Tour de la Perle de l'Orient|31.240|121.500|tour|CN|2|;potala|Palais du Potala|29.658|91.117|chateau|CN|2|;jiuquan|Base de Jiuquan|40.958|100.291|espace|CN|2|fusee;" +
        "wenchang|Base de Wenchang|19.614|110.951|espace|CN|2|fusee;gengis_khan|Statue de Gengis Khan|47.808|107.530|statue|MN|2|;gyeongbokgung|Palais Gyeongbokgung|37.580|126.977|chateau|KR|2|;fuji|Mont Fuji|35.361|138.727|naturel|JP|1|fuji;" +
        "skytree|Tokyo Skytree|35.710|139.811|tour|JP|2|;itsukushima|Torii d'Itsukushima|34.296|132.320|religieux|JP|2|;himeji|Château de Himeji|34.839|134.694|chateau|JP|2|;tanegashima|Base de Tanegashima|30.400|130.970|espace|JP|2|fusee;" +
        "taipei101|Taipei 101|25.034|121.565|moderne|TW|2|;banaue|Rizières de Banaue|16.919|121.058|naturel|PH|2|;mayon|Volcan Mayon|13.257|123.685|naturel|PH|2|;ha_long|Baie d'Ha Long|20.910|107.184|naturel|VN|1|;" +
        "that_luang|That Luang|17.976|102.634|religieux|LA|2|;angkor|Angkor Vat|13.412|103.867|religieux|KH|1|;wat_arun|Wat Arun|13.744|100.489|religieux|TH|2|;shwedagon|Pagode Shwedagon|16.798|96.150|religieux|MM|2|;" +
        "petronas|Tours Petronas|3.158|101.712|moderne|MY|1|;marina_bay|Marina Bay Sands|1.284|103.861|moderne|SG|2|;borobudur|Borobudur|-7.608|110.204|religieux|ID|1|;bromo|Volcan Bromo|-7.942|112.953|naturel|ID|2|;" +
        "omar_ali|Mosquée Omar Ali Saifuddin|4.890|114.939|religieux|BN|2|;cristo_rei|Cristo Rei de Dili|-8.517|125.608|statue|TL|2|;opera_sydney|Opéra de Sydney|-33.857|151.215|moderne|AU|1|opera_sydney;uluru|Uluru|-25.345|131.036|naturel|AU|1|;" +
        "barriere_corail|Grande Barrière de corail|-18.290|147.700|naturel|AU|2|;milford|Milford Sound|-44.641|167.897|naturel|NZ|2|;sky_tower|Sky Tower|-36.848|174.762|tour|NZ|2|;mahia|Base de lancement de Mahia|-39.262|177.865|espace|NZ|2|fusee;" +
        "liberte|Statue de la Liberté|40.689|-74.045|statue|US|1|liberte;empire_state|Empire State Building|40.748|-73.986|moderne|US|2|;golden_gate|Golden Gate|37.820|-122.478|pont|US|1|golden_gate;grand_canyon|Grand Canyon|36.107|-112.113|naturel|US|1|;" +
        "rushmore|Mont Rushmore|43.879|-103.459|statue|US|2|;maison_blanche|Maison-Blanche|38.898|-77.037|chateau|US|2|;old_faithful|Old Faithful (Yellowstone)|44.460|-110.828|naturel|US|2|;gateway_arch|Gateway Arch|38.625|-90.185|moderne|US|2|;" +
        "space_needle|Space Needle|47.620|-122.349|tour|US|2|;monument_valley|Monument Valley|36.998|-110.098|naturel|US|2|;kilauea|Kīlauea|19.421|-155.287|naturel|US|2|;canaveral|Cap Canaveral|28.573|-80.649|espace|US|1|fusee;" +
        "vandenberg|Base de Vandenberg|34.742|-120.573|espace|US|2|fusee;houston|Centre spatial de Houston|29.552|-95.097|espace|US|2|;tour_cn|Tour CN|43.643|-79.387|tour|CA|1|;niagara|Chutes du Niagara|43.079|-79.075|naturel|CA|1|;" +
        "frontenac|Château Frontenac|46.812|-71.205|chateau|CA|2|;lac_louise|Lac Louise|51.417|-116.217|naturel|CA|2|;chichen_itza|Chichén Itzá|20.684|-88.568|ancien|MX|1|;teotihuacan|Teotihuacan|19.692|-98.844|ancien|MX|2|;" +
        "tikal|Tikal|17.222|-89.623|ancien|GT|2|;trou_bleu|Grand Trou bleu|17.316|-87.535|naturel|BZ|2|;copan|Copán|14.840|-89.142|ancien|HN|2|;santa_ana|Volcan Santa Ana|13.853|-89.630|naturel|SV|2|;" +
        "masaya|Volcan Masaya|11.984|-86.161|naturel|NI|2|;arenal|Volcan Arenal|10.463|-84.703|naturel|CR|2|;canal_panama|Canal de Panama|8.997|-79.591|moderne|PA|1|;capitole_havane|Capitole de La Havane|23.135|-82.359|chateau|CU|2|;" +
        "citadelle|Citadelle Laferrière|19.573|-72.244|chateau|HT|2|;zipaquira|Cathédrale de sel de Zipaquirá|5.019|-74.006|religieux|CO|2|;salto_angel|Salto Ángel|5.967|-62.535|naturel|VE|1|;kaieteur|Chutes de Kaieteur|5.175|-59.481|naturel|GY|2|;" +
        "galapagos|Îles Galápagos|-0.650|-90.400|naturel|EC|2|;mitad_mundo|Mitad del Mundo|-0.002|-78.456|statue|EC|2|;machu_picchu|Machu Picchu|-13.163|-72.545|ancien|PE|1|;nazca|Lignes de Nazca|-14.739|-75.130|ancien|PE|2|;" +
        "uyuni|Salar d'Uyuni|-20.134|-67.489|naturel|BO|1|;christ|Christ Rédempteur|-22.952|-43.211|statue|BR|1|christ;iguacu|Chutes d'Iguaçu|-25.695|-54.437|naturel|BR|1|;congres_brasilia|Congrès national de Brasilia|-15.800|-47.864|moderne|BR|2|;" +
        "teatro_amazonas|Théâtre Amazonas|-3.130|-60.023|chateau|BR|2|;itaipu|Barrage d'Itaipu|-25.408|-54.589|moderne|PY|2|;la_mano|La Main de Punta del Este|-34.962|-54.943|statue|UY|2|;perito_moreno|Glacier Perito Moreno|-50.496|-73.138|naturel|AR|1|;" +
        "obelisque_ba|Obélisque de Buenos Aires|-34.604|-58.382|statue|AR|2|;aconcagua|Aconcagua|-32.653|-70.011|naturel|AR|2|;moai|Moaï de l'île de Pâques|-27.120|-109.350|statue|CL|1|moai;torres_paine|Torres del Paine|-50.942|-72.990|naturel|CL|2|;" +
        "paranal|Observatoire du Paranal|-24.627|-70.404|espace|CL|2|;brooklyn|Pont de Brooklyn|40.706|-73.997|pont|US|2|;oresund|Pont de l'Øresund|55.575|12.827|pont|DK|2|;bosphore|Pont du Bosphore|41.045|29.034|pont|TR|2|;" +
        "harbour_bridge|Pont du port de Sydney|-33.852|151.211|pont|AU|2|;unite|Statue de l'Unité|21.838|73.719|statue|IN|2|;leshan|Bouddha géant de Leshan|29.545|103.773|statue|CN|2|;mere_patrie|La Mère-Patrie, Volgograd|48.742|44.537|statue|RU|2|;" +
        "manneken_pis|Manneken-Pis|50.845|4.350|statue|BE|2|"

    val DECOR = listOf(
        Decor("bateau", 45.0, -35.0), Decor("bateau", 30.0, -48.0), Decor("bateau", 50.0, -20.0), Decor("bateau", -20.0, -15.0),
        Decor("bateau", -35.0, 5.0), Decor("bateau", 20.0, -150.0), Decor("bateau", -10.0, -120.0), Decor("bateau", 35.0, 160.0),
        Decor("bateau", 2.0, 170.0), Decor("bateau", -12.0, 82.0), Decor("bateau", -30.0, 60.0), Decor("bateau", 35.8, 18.0),
        Decor("bateau", 15.0, 63.0), Decor("bateau", 47.0, -6.5), Decor("bateau", 57.0, 3.0), Decor("bateau", -45.0, -140.0),
        Decor("baleine", -55.0, 0.0), Decor("baleine", 58.0, -165.0), Decor("baleine", 20.0, -40.0), Decor("baleine", -40.0, 110.0),
        Decor("foret", -5.0, -62.0), Decor("foret", -8.0, -55.0), Decor("foret", 0.5, 22.0), Decor("foret", 60.0, 100.0),
        Decor("foret", 62.0, 75.0), Decor("foret", 55.0, -100.0), Decor("foret", 52.0, -80.0), Decor("foret", 1.0, 114.0),
        Decor("foret", 63.0, 26.0), Decor("foret", 48.5, 10.0), Decor("foret", 45.0, -122.5),
        Decor("manchot", -74.0, 0.0), Decor("manchot", -70.0, 140.0), Decor("manchot", -72.0, -100.0), Decor("manchot", -67.0, 60.0))

    /** All of it for the page: `{villes: [[nom, lat, lon, rang]], sommets: [[lat, lon, altitude]], capitales: [[nom, lat, lon, pays, population]], monuments: [[id, nom, lat, lon, type, pays, rang, modele]], typesMonuments: [[type, nom]], decor: [[type, lat, lon]]}`. */
    fun json(): String = JSONObject()
        .put("villes", JSONArray(VILLES.map { JSONArray(listOf(it.nom, it.lat, it.lon, it.rang)) }))
        .put("sommets", JSONArray(SOMMETS.map { JSONArray(listOf(it.lat, it.lon, it.altitude)) }))
        .put("capitales", JSONArray(CAPITALES.map { JSONArray(listOf(it.nom, it.lat, it.lon, it.pays, it.population)) }))
        .put("monuments", JSONArray(MONUMENTS.map { JSONArray(listOf(it.id, it.nom, it.lat, it.lon, it.type, it.pays, it.rang, it.modele ?: "")) }))
        .put("typesMonuments", JSONArray(TYPES_MONUMENTS.map { JSONArray(listOf(it.key, it.value)) }))
        .put("decor", JSONArray(DECOR.map { JSONArray(listOf(it.type, it.lat, it.lon)) }))
        .toString()
}
