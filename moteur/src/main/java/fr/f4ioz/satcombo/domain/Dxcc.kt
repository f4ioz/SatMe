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
 * The country of a callsign, from its prefix: the ARRL DXCC list.
 *
 * Every current entity is here with its ADIF number, its ADIF name, its
 * continent, its CQ and ITU zones, and the prefixes it is reached by; the
 * deleted entities are listed too (marked [Entite.supprimee]), for an ADIF
 * number read from an old log, but no prefix leads to them: their prefixes
 * belong to today's entities.
 *
 * Written from the DXCC list itself (entity names and ADIF numbers), not from
 * another program's prefix file.
 *
 * What a prefix alone cannot tell is handled in three ways:
 * - a longer prefix wins (EA8 Canaries over EA Spain, FT5W Crozet, KH6 Hawaii,
 *   VP2E Anguilla, CE0Y Easter Island);
 * - a few rules on the whole callsign (KG4 with a two-letter suffix is
 *   Guantánamo, the others are US; Russian call area 2 is Kaliningrad, 9 with
 *   F, G or X is European Russia; KC4AAA… Antarctica);
 * - a few known callsigns of entities without a prefix of their own (Mount
 *   Athos, Malpelo, Minami-Tori-shima, Clipperton, Conway Reef…).
 * When a prefix is shared and nothing decides (FT alone, VK9 alone, 4U), the
 * answer is null — no country is better than a wrong one.
 *
 * The second field is the flag code in [fr.f4ioz.satcombo.data.Flags] when
 * drawn there, empty otherwise (name shown alone).
 */
object Dxcc {

    /**
     * An entity: its name, its flag (code in [fr.f4ioz.satcombo.data.Flags]),
     * its continent (EU, AS, AF, NA, SA, OC, AN — as the DXCC list gives it).
     */
    class Entite(
        val nom: String, val drapeau: String, val continent: String = "",
        /** The DXCC (ADIF) entity number: 227 France, 41 Crozet. */
        val adif: Int = 0,
        /** The name in the ADIF enumeration, upper case (« CROZET I. »): the COUNTRY field. */
        val nomAdif: String = "",
        /** The CQ zones the entity spans, the ITU ones. */
        val zonesCq: List<Int> = emptyList(),
        val zonesItu: List<Int> = emptyList(),
        /** Deleted from the DXCC list: counts for no award any more. */
        val supprimee: Boolean = false,
        private val codeIso: String = "",
        /** Rough centre of the entity (degrees), for a contact without a square. */
        val lat: Double? = null,
        val lon: Double? = null,
        /** The prefixes it is reached by, as the table has them (FT0W FT1W…). */
        val prefixes: List<String> = emptyList(),
    ) {
        /** The flag's code for the emoji: ISO 3166 (JE, GP…), or ENG/SCT/WLS. */
        val iso: String get() = codeIso.ifEmpty { drapeau }
        /** The CQ zone when the entity has one only (France 14), null when several (USA 3, 4, 5). */
        val cq: Int? get() = zonesCq.singleOrNull()
        /** The ITU zone when the entity has one only, null when several. */
        val itu: Int? get() = zonesItu.singleOrNull()
        override fun toString() = "$nom ($adif)"
    }

    /*
     * One current entity per line:
     *   ADIF number | French name | ADIF name | continent | CQ zones | ITU zones | ISO | lat | lon | flag | prefixes
     * Zones separated by commas; prefixes by spaces. The families too long to
     * write out (FT#W, KH#, CE0#, Russian call areas) are added in [init].
     */
    private const val ACTUELLES = """
227|France|FRANCE|EU|14|27|FR|46.6|2.4|FR|F TM TO TP TV TW TX HW HX HY
214|Corse|CORSICA|EU|15|28|FR|42.1|9.1|FR|TK
223|Angleterre|ENGLAND|EU|14|27|ENG|52.5|-1.5|GB|G M 2E GX
279|Écosse|SCOTLAND|EU|14|27|SCT|56.8|-4.2|GB|GM MM 2M GS MS
294|Pays de Galles|WALES|EU|14|27|WLS|52.3|-3.7|GB|GW MW 2W GC MC
265|Irlande du Nord|NORTHERN IRELAND|EU|14|27|GB|54.6|-6.7|GB|GI MI 2I GN MN
114|Île de Man|ISLE OF MAN|EU|14|27|IM|54.2|-4.5|GB|GD MD 2D GT MT
122|Jersey|JERSEY|EU|14|27|JE|49.2|-2.1|GB|GJ MJ 2J GH MH
106|Guernesey|GUERNSEY|EU|14|27|GG|49.45|-2.58|GB|GU MU 2U GP MP
245|Irlande|IRELAND|EU|14|27|IE|53.2|-8.0|IE|EI EJ
230|Allemagne|FEDERAL REPUBLIC OF GERMANY|EU|14|28|DE|51.2|10.4|DE|DA DB DC DD DE DF DG DH DJ DK DL DM DN DO DP DQ DR
281|Espagne|SPAIN|EU|14|37|ES|40.3|-3.7|ES|EA EB EC ED EE EF EG EH AM AN AO
21|Baléares|BALEARIC IS.|EU|14|37|ES|39.6|2.9|ES|EA6 EB6 EC6 ED6 EE6 EF6 EG6 EH6 AM6 AN6 AO6
29|Canaries|CANARY IS.|AF|33|36|ES|28.3|-15.7|ES|EA8 EB8 EC8 ED8 EE8 EF8 EG8 EH8 AM8 AN8 AO8
32|Ceuta et Melilla|CEUTA & MELILLA|AF|33|37|ES|35.6|-4.2|ES|EA9 EB9 EC9 ED9 EE9 EF9 EG9 EH9 AM9 AN9 AO9
272|Portugal|PORTUGAL|EU|14|37|PT|39.6|-8.0|PT|CT CQ CR CS
256|Madère|MADEIRA IS.|AF|33|36|PT|32.75|-16.95|PT|CT3 CS3 CQ3 CR3
149|Açores|AZORES|EU|14|36|PT|38.5|-28.0|PT|CU CT8 CS8 CQ8 CR8
248|Italie|ITALY|EU|15|28|IT|42.8|12.5|IT|I IA IB IC ID IE IF IG IH II IJ IK IL IN IO IP IQ IR IU IV IW IX IY IZ
225|Sardaigne|SARDINIA|EU|15|28|IT|40.1|9.0|IT|IS0 IM0
209|Belgique|BELGIUM|EU|14|27|BE|50.6|4.6|BE|ON OO OP OQ OR OS OT
263|Pays-Bas|NETHERLANDS|EU|14|27|NL|52.2|5.5|NL|PA PB PC PD PE PF PG PH PI
287|Suisse|SWITZERLAND|EU|14|28|CH|46.8|8.2|CH|HB HE
251|Liechtenstein|LIECHTENSTEIN|EU|14|28|LI|47.15|9.55|CH|HB0 HE0
254|Luxembourg|LUXEMBOURG|EU|14|27|LU|49.8|6.1|LU|LX
206|Autriche|AUSTRIA|EU|15|28|AT|47.6|14.1|AT|OE 4U1V
221|Danemark|DENMARK|EU|14|18|DK|56.0|10.0|DK|OZ OU OV OW 5P 5Q
222|Féroé|FAROE IS.|EU|14|18|FO|62.0|-6.9|FO|OY
266|Norvège|NORWAY|EU|14|18|NO|62.0|10.0|NO|LA LB LC LD LE LF LG LH LI LJ LK LL LM LN
259|Svalbard|SVALBARD|EU|40|18|SJ|78.2|16.0|NO|JW
118|Jan Mayen|JAN MAYEN|EU|40|18|SJ|71.0|-8.5|NO|JX
284|Suède|SWEDEN|EU|14|18|SE|62.0|15.5|SE|SA SB SC SD SE SF SG SH SI SJ SK SL SM 7S 8S
224|Finlande|FINLAND|EU|15|18|FI|63.5|26.0|FI|OF OG OH OI
5|Åland|ALAND IS.|EU|15|18|AX|60.2|20.0|FI|OH0 OF0 OG0 OI0
167|Récif de Märket|MARKET REEF|EU|15|18|AX|60.3|19.13|FI|OJ0
242|Islande|ICELAND|EU|40|17|IS|64.9|-18.6|IS|TF
269|Pologne|POLAND|EU|15|28|PL|52.1|19.4|PL|SP SN SO SQ SR 3Z HF
503|Tchéquie|CZECH REPUBLIC|EU|15|28|CZ|49.8|15.5|CZ|OK OL
504|Slovaquie|SLOVAK REPUBLIC|EU|15|28|SK|48.7|19.7|SK|OM
239|Hongrie|HUNGARY|EU|15|28|HU|47.2|19.4|HU|HA HG
499|Slovénie|SLOVENIA|EU|15|28|SI|46.1|14.8|SI|S5
497|Croatie|CROATIA|EU|15|28|HR|45.1|15.2|HR|9A
501|Bosnie-Herzégovine|BOSNIA-HERZEGOVINA|EU|15|28|BA|44.2|17.8||E7
296|Serbie|SERBIA|EU|15|28|RS|44.0|20.9|RS|YT YU YZ
514|Monténégro|MONTENEGRO|EU|15|28|ME|42.8|19.3||4O
522|Kosovo|REPUBLIC OF KOSOVO|EU|15|28|XK|42.6|20.9||Z6
502|Macédoine du Nord|NORTH MACEDONIA|EU|15|28|MK|41.6|21.7||Z3
7|Albanie|ALBANIA|EU|15|28|AL|41.1|20.0||ZA
236|Grèce|GREECE|EU|20|28|GR|39.3|22.0|GR|SV SW SX SY SZ J4
45|Dodécanèse|DODECANESE|EU|20|28|GR|36.4|27.9|GR|SV5 SW5 SX5 SY5 SZ5 J45
40|Crète|CRETE|EU|20|28|GR|35.2|24.9|GR|SV9 SW9 SX9 SY9 SZ9 J49
180|Mont Athos|MOUNT ATHOS|EU|20|28|GR|40.16|24.33|GR|
212|Bulgarie|BULGARIA|EU|20|28|BG|42.7|25.3|BG|LZ
275|Roumanie|ROMANIA|EU|20|28|RO|45.9|24.9|RO|YO YP YQ YR
179|Moldavie|MOLDOVA|EU|16|29|MD|47.2|28.5||ER
288|Ukraine|UKRAINE|EU|16|29|UA|49.0|31.3|UA|UR US UT UU UV UW UX UY UZ EM EN EO
27|Biélorussie|BELARUS|EU|16|29|BY|53.7|28.0||EU EV EW
52|Estonie|ESTONIA|EU|15|29|EE|58.7|25.5|EE|ES
145|Lettonie|LATVIA|EU|15|29|LV|56.9|24.6|LV|YL
146|Lituanie|LITHUANIA|EU|15|29|LT|55.3|23.9|LT|LY
54|Russie|EUROPEAN RUSSIA|EU|16|19,20,29,30|RU|55.8|40.0|RU|
126|Kaliningrad|KALININGRAD|EU|15|29|RU|54.7|20.5|RU|
61|Terre François-Joseph|FRANZ JOSEF LAND|EU|40|75|RU|80.6|54.0|RU|R1FJ RI1FJ
15|Russie d'Asie|ASIATIC RUSSIA|AS|16,17,18,19,23|20,21,22,23,24,25,26,30,31,32,33,35,75|RU|60.0|95.0|RU|
390|Turquie|TURKEY|AS|20|39|TR|39.0|35.2|TR|TA TB TC YM
215|Chypre|CYPRUS|AS|20|39|CY|35.0|33.2||5B C4 H2 P3
283|Bases britanniques de Chypre|UK SOVEREIGN BASE AREAS ON CYPRUS|AS|20|39|CY|34.6|32.95|GB|ZC4
257|Malte|MALTA|EU|15|28|MT|35.9|14.4|MT|9H
246|Ordre souverain de Malte|SOVEREIGN MILITARY ORDER OF MALTA|EU|15|28||41.9|12.48||1A
260|Monaco|MONACO|EU|14|27|MC|43.74|7.42|MC|3A
203|Andorre|ANDORRA|EU|14|27|AD|42.5|1.55||C3
295|Vatican|VATICAN|EU|15|28|VA|41.9|12.45|IT|HV
278|Saint-Marin|SAN MARINO|EU|15|28|SM|43.94|12.46|IT|T7
233|Gibraltar|GIBRALTAR|EU|14|37|GI|36.14|-5.35|GB|ZB ZG
117|Siège de l'UIT|ITU HQ|EU|14|28||46.21|6.14||4U1I
291|États-Unis|UNITED STATES OF AMERICA|NA|3,4,5|6,7,8|US|39.5|-98.4|US|K W N AA AB AC AD AE AF AG AH AI AJ AK
6|Alaska|ALASKA|NA|1|1,2|US|63.6|-152.5|US|KL AL NL WL
110|Hawaï|HAWAII|OC|31|61|US|20.8|-156.3|US|
138|Kure|KURE I.|OC|31|61|UM|28.4|-178.3|US|
174|Midway|MIDWAY I.|OC|31|61|UM|28.2|-177.37|US|
123|Johnston|JOHNSTON I.|OC|31|61|UM|16.73|-169.53|US|
20|Baker et Howland|BAKER & HOWLAND IS.|OC|31|61|UM|0.5|-176.5|US|
197|Palmyra et Jarvis|PALMYRA & JARVIS IS.|OC|31|61,62|UM|5.88|-162.08|US|
297|Wake|WAKE I.|OC|31|65|UM|19.28|166.65|US|
103|Guam|GUAM|OC|27|64|GU|13.44|144.79|US|
166|Mariannes du Nord|MARIANA IS.|OC|27|64|MP|15.2|145.75|US|
9|Samoa américaines|AMERICAN SAMOA|OC|32|62|AS|-14.3|-170.7|US|
515|Swains|SWAINS I.|OC|32|62|AS|-11.06|-171.08|US|
202|Porto Rico|PUERTO RICO|NA|8|11|PR|18.2|-66.5|US|
285|Îles Vierges américaines|VIRGIN IS.|NA|8|11|VI|18.34|-64.9|US|
182|Navassa|NAVASSA I.|NA|8|11|UM|18.4|-75.01|US|
43|Desecheo|DESECHEO I.|NA|8|11|PR|18.38|-67.48|US|
105|Guantánamo|GUANTANAMO BAY|NA|8|11|CU|19.9|-75.15|US|
289|Siège de l'ONU|UNITED NATIONS HQ|NA|5|8|UN|40.75|-73.97||4U1U
1|Canada|CANADA|NA|1,2,3,4,5|2,3,4,9,75|CA|56.0|-96.0|CA|VE VA VO VY VB VC VD VF VG VX CF CG CH CI CJ CK CY CZ XJ XK XL XM XN XO
211|Sable|SABLE I.|NA|5|9|CA|43.95|-59.9|CA|CY0
252|Saint-Paul (Canada)|ST. PAUL I.|NA|5|9|CA|47.2|-60.15|CA|CY9
277|St-Pierre-et-Miquelon|ST. PIERRE & MIQUELON|NA|5|9|PM|46.9|-56.3|FR|FP
237|Groenland|GREENLAND|NA|40|5,75|GL|72.0|-40.0|DK|OX XP
64|Bermudes|BERMUDA|NA|5|11|BM|32.3|-64.75|GB|VP9
50|Mexique|MEXICO|NA|6|10|MX|23.6|-102.5|MX|XE XA XB XC XD XF XG XH XI 4A 4B 4C 6D 6E 6F 6G 6H 6I 6J
204|Revillagigedo|REVILLAGIGEDO|NA|6|10|MX|18.8|-111.0|MX|XF4
36|Clipperton|CLIPPERTON I.|NA|7|10|CP|10.3|-109.22|FR|
76|Guatemala|GUATEMALA|NA|7|11|GT|15.6|-90.3||TG TD
66|Belize|BELIZE|NA|7|11|BZ|17.2|-88.7||V3
80|Honduras|HONDURAS|NA|7|11|HN|14.8|-86.6||HR HQ
74|Salvador|EL SALVADOR|NA|7|11|SV|13.8|-88.9||YS HU
86|Nicaragua|NICARAGUA|NA|7|11|NI|12.9|-85.2||YN H6 H7 HT
308|Costa Rica|COSTA RICA|NA|7|11|CR|9.7|-83.8||TI TE
37|Île Cocos (Costa Rica)|COCOS I.|NA|7|11|CR|5.53|-87.06||TI9 TE9
88|Panama|PANAMA|NA|7|11|PA|8.5|-80.1||HP HO H3 H8 H9 3E 3F
70|Cuba|CUBA|NA|8|11|CU|21.6|-79.0||CO CM CL T4
60|Bahamas|BAHAMAS|NA|8|11|BS|24.5|-77.5||C6
82|Jamaïque|JAMAICA|NA|8|11|JM|18.1|-77.3||6Y
78|Haïti|HAITI|NA|8|11|HT|19.0|-72.4||HH 4V
72|Rép. dominicaine|DOMINICAN REPUBLIC|NA|8|11|DO|18.7|-70.2||HI
69|Caïmans|CAYMAN IS.|NA|8|11|KY|19.3|-81.25|GB|ZF
89|Turques-et-Caïques|TURKS & CAICOS IS.|NA|8|11|TC|21.75|-71.75|GB|VP5
12|Anguilla|ANGUILLA|NA|8|11|AI|18.22|-63.05||VP2E
96|Montserrat|MONTSERRAT|NA|8|11|MS|16.74|-62.19||VP2M
65|Îles Vierges britanniques|BRITISH VIRGIN IS.|NA|8|11|VG|18.42|-64.62||VP2V
213|Saint-Martin|SAINT MARTIN|NA|8|11|MF|18.07|-63.05|FR|FS
518|Sint Maarten|SINT MAARTEN|NA|8|11|SX|18.03|-63.07|NL|PJ7
516|Saint-Barthélemy|SAINT BARTHELEMY|NA|8|11|BL|17.9|-62.83|FR|FJ
519|Saba et Saint-Eustache|SABA & ST. EUSTATIUS|NA|8|11|BQ|17.6|-63.1|NL|PJ5 PJ6
249|Saint-Christophe-et-Niévès|ST. KITTS & NEVIS|NA|8|11|KN|17.3|-62.73||V4
94|Antigua-et-Barbuda|ANTIGUA & BARBUDA|NA|8|11|AG|17.1|-61.8||V2
79|Guadeloupe|GUADELOUPE|NA|8|11|GP|16.2|-61.6|FR|FG
95|Dominique|DOMINICA|NA|8|11|DM|15.4|-61.35||J7
84|Martinique|MARTINIQUE|NA|8|11|MQ|14.6|-61.0|FR|FM
97|Sainte-Lucie|ST. LUCIA|NA|8|11|LC|13.9|-60.97||J6
98|Saint-Vincent|ST. VINCENT|NA|8|11|VC|13.25|-61.2||J8
62|Barbade|BARBADOS|NA|8|11|BB|13.2|-59.55||8P
77|Grenade|GRENADA|NA|8|11|GD|12.1|-61.7||J3
17|Île d'Aves|AVES I.|NA|8|11|VE|15.67|-63.62||YV0 YW0 YX0 YY0
216|San Andrés et Providencia|SAN ANDRES & PROVIDENCIA|NA|7|11|CO|12.55|-81.7||HK0 HJ0 5J0 5K0
90|Trinité-et-Tobago|TRINIDAD & TOBAGO|SA|9|11|TT|10.45|-61.25||9Y 9Z
91|Aruba|ARUBA|SA|9|11|AW|12.5|-69.97|NL|P4
517|Curaçao|CURACAO|SA|9|11|CW|12.17|-68.98|NL|PJ2
520|Bonaire|BONAIRE|SA|9|11|BQ|12.18|-68.25|NL|PJ4
116|Colombie|COLOMBIA|SA|9|12|CO|4.6|-74.1||HK HJ 5J 5K
161|Malpelo|MALPELO I.|SA|9|12|CO|4.0|-81.6||
148|Venezuela|VENEZUELA|SA|9|12|VE|7.0|-66.0||YV YW YX YY 4M
129|Guyana|GUYANA|SA|9|12|GY|4.9|-58.9||8R
140|Suriname|SURINAME|SA|9|12|SR|4.0|-56.0||PZ
63|Guyane|FRENCH GUIANA|SA|9|12|GF|4.0|-53.0|FR|FY
120|Équateur|ECUADOR|SA|10|12|EC|-1.5|-78.2||HC HD
71|Galápagos|GALAPAGOS IS.|SA|10|12|EC|-0.7|-90.4||HC8 HD8
136|Pérou|PERU|SA|10|12|PE|-9.2|-75.0|PE|OA OB OC 4T
104|Bolivie|BOLIVIA|SA|10|12,14|BO|-16.3|-63.6||CP
108|Brésil|BRAZIL|SA|11|12,13,15|BR|-10.0|-52.0|BR|PP PQ PR PS PT PU PV PW PX PY ZV ZW ZX ZY ZZ
56|Fernando de Noronha|FERNANDO DE NORONHA|SA|11|13|BR|-3.85|-32.42|BR|
253|Rochers Saint-Pierre-et-Saint-Paul|ST. PETER & ST. PAUL ROCKS|SA|11|13|BR|0.92|-29.35|BR|
273|Trindade et Martim Vaz|TRINDADE & MARTIM VAZ IS.|SA|11|15|BR|-20.5|-29.3|BR|
132|Paraguay|PARAGUAY|SA|11|14|PY|-23.4|-58.4||ZP
144|Uruguay|URUGUAY|SA|13|14|UY|-32.8|-56.0||CX CV CW
100|Argentine|ARGENTINA|SA|13|14,16|AR|-35.0|-65.0|AR|LU LO LP LQ LR LS LT LV LW AY AZ L2 L3 L4 L5 L6 L7 L8 L9
112|Chili|CHILE|SA|12|14,16|CL|-33.0|-71.0||CE CA CB CC CD XQ XR 3G
47|Île de Pâques|EASTER I.|SA|12|63|CL|-27.1|-109.35||
125|Juan Fernández|JUAN FERNANDEZ IS.|SA|12|14|CL|-33.6|-78.85||
217|San Félix et San Ambrosio|SAN FELIX & SAN AMBROSIO|SA|12|14|CL|-26.3|-80.1||
141|Malouines|FALKLAND IS.|SA|13|16|FK|-51.7|-59.5|GB|VP8
235|Géorgie du Sud|SOUTH GEORGIA I.|SA|13|73|GS|-54.3|-36.7|GB|VP8SG
238|Orcades du Sud|SOUTH ORKNEY IS.|SA|13|73|AQ|-60.7|-45.6||VP8ORK
240|Sandwich du Sud|SOUTH SANDWICH IS.|SA|13|73|GS|-58.4|-26.4|GB|VP8STI VP8THU
241|Shetland du Sud|SOUTH SHETLAND IS.|SA|13|73|AQ|-62.0|-58.5||
13|Antarctique|ANTARCTICA|AN|12,13,29,30,32,38,39|67,69,70,71,72,73,74|AQ|-80.0|0.0||CE9 XR9 ZS7 ZL5 VK0 AX0 RI1AN 8J1R
199|Île Pierre-Ier|PETER 1 I.|AN|12|72|AQ|-68.8|-90.6|NO|
24|Bouvet|BOUVET|AF|38|67|BV|-54.43|3.38|NO|3Y
446|Maroc|MOROCCO|AF|33|37|MA|31.8|-7.1|MA|CN 5C 5D 5E 5F 5G
302|Sahara occidental|WESTERN SAHARA|AF|33|46|EH|24.2|-12.9||S0
400|Algérie|ALGERIA|AF|33|37|DZ|28.0|2.6||7X 7R 7T 7U 7V 7W 7Y
474|Tunisie|TUNISIA|AF|33|37|TN|34.0|9.5||3V TS
436|Libye|LIBYA|AF|34|38|LY|26.3|17.2||5A
478|Égypte|EGYPT|AF|34|38|EG|26.8|30.8|EG|SU 6A 6B
466|Soudan|SUDAN|AF|34|48|SD|15.5|30.0||ST 6T 6U
521|Soudan du Sud|SOUTH SUDAN (REPUBLIC OF)|AF|34|48|SS|7.0|30.0||Z8
53|Éthiopie|ETHIOPIA|AF|37|48|ET|9.0|39.5||ET 9E 9F
51|Érythrée|ERITREA|AF|37|48|ER|15.2|39.0||E3
382|Djibouti|DJIBOUTI|AF|37|48|DJ|11.6|43.1||J2
232|Somalie|SOMALIA|AF|37|48|SO|5.0|46.0||6O T5
430|Kenya|KENYA|AF|37|48|KE|0.0|37.9||5Z 5Y
286|Ouganda|UGANDA|AF|37|48|UG|1.4|32.3||5X
470|Tanzanie|TANZANIA|AF|37|53|TZ|-6.4|34.9||5H 5I
454|Rwanda|RWANDA|AF|36|52|RW|-2.0|29.9||9X
404|Burundi|BURUNDI|AF|36|52|BI|-3.4|29.9||9U
414|Rép. dém. du Congo|DEMOCRATIC REPUBLIC OF THE CONGO|AF|36|52|CD|-2.9|23.6||9Q 9R 9S 9T
412|Congo|REPUBLIC OF THE CONGO|AF|36|52|CG|-0.7|15.6||TN
420|Gabon|GABON|AF|36|52|GA|-0.8|11.6||TR
49|Guinée équatoriale|EQUATORIAL GUINEA|AF|36|47|GQ|1.6|10.3||3C
195|Annobón|ANNOBON I.|AF|36|52|GQ|-1.43|5.63||3C0
219|Sao Tomé-et-Principe|SAO TOME & PRINCIPE|AF|36|47|ST|0.3|6.6||S9
406|Cameroun|CAMEROON|AF|36|47|CM|5.7|12.7||TJ
408|Centrafrique|CENTRAL AFRICA|AF|36|47|CF|6.6|20.9||TL
410|Tchad|CHAD|AF|36|47|TD|15.4|18.7||TT
187|Niger|NIGER|AF|35|46|NE|17.6|8.1||5U
450|Nigeria|NIGERIA|AF|35|46|NG|9.1|8.7|NG|5N 5O
416|Bénin|BENIN|AF|35|46|BJ|9.3|2.3||TY
483|Togo|TOGO|AF|35|46|TG|8.6|0.8||5V
424|Ghana|GHANA|AF|35|46|GH|7.9|-1.0||9G
428|Côte d'Ivoire|COTE D'IVOIRE|AF|35|46|CI|7.5|-5.5||TU
480|Burkina Faso|BURKINA FASO|AF|35|46|BF|12.2|-1.6||XT
442|Mali|MALI|AF|35|46|ML|17.6|-4.0||TZ
444|Mauritanie|MAURITANIA|AF|35|46|MR|20.3|-10.9||5T
456|Sénégal|SENEGAL|AF|35|46|SN|14.5|-14.5||6W 6V
422|Gambie|THE GAMBIA|AF|35|46|GM|13.45|-15.4||C5
109|Guinée-Bissau|GUINEA-BISSAU|AF|35|46|GW|12.0|-15.0||J5
107|Guinée|GUINEA|AF|35|46|GN|10.4|-10.9||3X
458|Sierra Leone|SIERRA LEONE|AF|35|46|SL|8.5|-11.8||9L
434|Liberia|LIBERIA|AF|35|46|LR|6.4|-9.4||EL A8 D5 6Z
409|Cap-Vert|CAPE VERDE|AF|35|46|CV|15.1|-23.6||D4
401|Angola|ANGOLA|AF|36|52|AO|-11.2|17.9||D2 D3
482|Zambie|ZAMBIA|AF|36|53|ZM|-13.1|27.8||9J 9I
440|Malawi|MALAWI|AF|37|53|MW|-13.3|34.3||7Q
181|Mozambique|MOZAMBIQUE|AF|37|53|MZ|-18.7|35.5||C9 C8
452|Zimbabwe|ZIMBABWE|AF|38|53|ZW|-19.0|29.2||Z2
402|Botswana|BOTSWANA|AF|38|57|BW|-22.3|24.7||A2 8O
464|Namibie|NAMIBIA|AF|38|57|NA|-22.6|17.1||V5
462|Afrique du Sud|REPUBLIC OF SOUTH AFRICA|AF|38|57|ZA|-29.0|25.0|ZA|ZS ZR ZT ZU S8
201|Prince-Édouard et Marion|PRINCE EDWARD & MARION IS.|AF|38|57|ZA|-46.9|37.75|ZA|ZS8 ZR8 ZT8 ZU8
432|Lesotho|LESOTHO|AF|38|57|LS|-29.6|28.2||7P
468|Eswatini|KINGDOM OF ESWATINI|AF|38|57|SZ|-26.5|31.5||3DA
438|Madagascar|MADAGASCAR|AF|39|53|MG|-19.4|46.7||5R 5S 6X
411|Comores|COMOROS|AF|39|53|KM|-11.9|43.9||D6
169|Mayotte|MAYOTTE|AF|39|53|YT|-12.8|45.15|FR|FH
453|La Réunion|REUNION I.|AF|39|53|RE|-21.1|55.5|FR|FR
165|Maurice|MAURITIUS|AF|39|53|MU|-20.3|57.6||3B8
4|Agaléga et Saint-Brandon|AGALEGA & ST. BRANDON IS.|AF|39|53|MU|-10.4|56.6||3B6 3B7
207|Rodrigues|RODRIGUEZ I.|AF|39|53|MU|-19.7|63.4||3B9
379|Seychelles|SEYCHELLES|AF|39|53|SC|-4.7|55.5||S7
33|Chagos|CHAGOS IS.|AF|39|41|IO|-7.3|72.4|GB|VQ9
250|Sainte-Hélène|ST. HELENA|AF|36|66|SH|-15.95|-5.7|GB|ZD7
205|Ascension|ASCENSION I.|AF|36|66|AC|-7.95|-14.37|GB|ZD8
274|Tristan da Cunha et Gough|TRISTAN DA CUNHA & GOUGH I.|AF|38|66|TA|-37.1|-12.3|GB|ZD9
99|Glorieuses|GLORIOSO IS.|AF|39|53|TF|-11.55|47.3|FR|
124|Juan de Nova et Europa|JUAN DE NOVA, EUROPA|AF|39|53|TF|-17.05|42.72|FR|
276|Tromelin|TROMELIN I.|AF|39|53|TF|-15.89|54.52|FR|
41|Crozet|CROZET I.|AF|39|68|TF|-46.4|51.8|FR|
131|Kerguelen|KERGUELEN IS.|AF|39|68|TF|-49.35|70.2|FR|
10|Amsterdam et Saint-Paul|AMSTERDAM & ST. PAUL IS.|AF|39|68|TF|-37.8|77.55|FR|
111|Heard|HEARD I.|AF|39|68|HM|-53.1|73.5|AU|VK0H AX0H
339|Japon|JAPAN|AS|25|45|JP|36.2|138.3|JP|JA JE JF JG JH JI JJ JK JL JM JN JO JP JQ JR JS 7J 7K 7L 7M 7N 8J 8N
192|Ogasawara|OGASAWARA|AS|27|45|JP|27.1|142.2|JP|JD1
177|Minami-Tori-shima|MINAMI TORISHIMA|OC|27|90|JP|24.29|153.98|JP|
137|Corée du Sud|REPUBLIC OF KOREA|AS|25|44|KR|36.5|127.9||HL DS DT D7 D8 D9 6K 6L 6M 6N
344|Corée du Nord|DEMOCRATIC PEOPLE'S REP. OF KOREA|AS|25|44|KP|40.0|127.0||P5 P6 P7 P8 P9
318|Chine|CHINA|AS|23,24|33,42,43,44|CN|35.9|104.2||B BA BB BC BD BE BF BG BH BI BJ BK BL BR BS BT BY BZ
386|Taïwan|TAIWAN|AS|24|44|TW|23.7|121.0||BM BN BO BP BQ BU BV BW BX
505|Pratas|PRATAS I.|AS|24|44|TW|20.7|116.7||BV9P BU9P BX9P
506|Récif de Scarborough|SCARBOROUGH REEF|AS|27|50||15.15|117.75||BS7H
321|Hong Kong|HONG KONG|AS|24|44|HK|22.3|114.2||VR2
152|Macao|MACAO|AS|24|44|MO|22.17|113.55||XX9
363|Mongolie|MONGOLIA|AS|23|32,33|MN|46.9|103.8||JT JU JV
293|Viêt Nam|VIET NAM|AS|26|49|VN|14.1|108.3|VN|3W XV
143|Laos|LAOS|AS|26|49|LA|18.2|103.9||XW
312|Cambodge|CAMBODIA|AS|26|49|KH|12.6|104.9||XU
387|Thaïlande|THAILAND|AS|26|49|TH|15.9|101.0|TH|HS E2
309|Myanmar|MYANMAR|AS|26|49|MM|19.8|96.1||XZ XY
305|Bangladesh|BANGLADESH|AS|22|41|BD|23.7|90.4|BD|S2 S3
324|Inde|INDIA|AS|22|41|IN|21.0|78.9|IN|VU AT AU AV AW 8T 8U 8V 8W 8X 8Y
11|Andaman et Nicobar|ANDAMAN & NICOBAR IS.|AS|26|49|IN|11.7|92.7|IN|VU4
142|Laquedives|LAKSHADWEEP IS.|AS|22|41|IN|10.6|72.6|IN|VU7
315|Sri Lanka|SRI LANKA|AS|22|41|LK|7.9|80.8||4S 4P 4Q 4R
159|Maldives|MALDIVES|AS|22|41|MV|3.2|73.2||8Q
369|Népal|NEPAL|AS|22|42|NP|28.4|84.1||9N
306|Bhoutan|BHUTAN|AS|22|41|BT|27.5|90.4||A5
372|Pakistan|PAKISTAN|AS|21|41|PK|30.4|69.3||AP AQ AR AS 6P 6Q 6R 6S
3|Afghanistan|AFGHANISTAN|AS|21|40|AF|33.9|67.7||YA T6
330|Iran|IRAN|AS|21|40|IR|32.4|53.7||EP EQ 9B 9C 9D
333|Irak|IRAQ|AS|21|39|IQ|33.2|43.7||YI HN
384|Syrie|SYRIA|AS|20|39|SY|35.0|38.5||YK 6C
354|Liban|LEBANON|AS|20|39|LB|33.9|35.9||OD
336|Israël|ISRAEL|AS|20|39|IL|31.0|34.9||4X 4Z
510|Palestine|PALESTINE|AS|20|39|PS|31.9|35.2||E4
342|Jordanie|JORDAN|AS|20|39|JO|31.2|36.5||JY
378|Arabie saoudite|SAUDI ARABIA|AS|21|39|SA|23.9|45.1||HZ 7Z 8Z
348|Koweït|KUWAIT|AS|21|39|KW|29.3|47.5||9K
304|Bahreïn|BAHRAIN|AS|21|39|BH|26.07|50.55||A9
376|Qatar|QATAR|AS|21|39|QA|25.3|51.2||A7
391|Émirats arabes unis|UNITED ARAB EMIRATES|AS|21|39|AE|23.4|53.8||A6
370|Oman|OMAN|AS|21|39|OM|21.5|55.9||A4
492|Yémen|YEMEN|AS|21|39|YE|15.6|48.5||7O
381|Singapour|SINGAPORE|AS|28|54|SG|1.35|103.8||9V S6
299|Malaisie Ouest|WEST MALAYSIA|AS|28|54|MY|4.2|101.9||9M2 9M4 9W2 9W4
46|Malaisie Est|EAST MALAYSIA|OC|28|54|MY|3.5|115.0||9M6 9M8 9W6 9W8
345|Brunei|BRUNEI DARUSSALAM|OC|28|54|BN|4.5|114.7||V8
247|Spratly|SPRATLY IS.|AS|26|50||10.0|114.0||1S 9M0
375|Philippines|PHILIPPINES|OC|27|50|PH|12.9|121.8||DU DV DW DX DY DZ 4D 4E 4F 4G 4H 4I
327|Indonésie|INDONESIA|OC|28|51,54|ID|-2.5|118.0|ID|YB YC YD YE YF YG YH 7A 7B 7C 7D 7E 7F 7G 7H 7I 8A 8B 8C 8D 8E 8F 8G 8H 8I PK PL PM PN PO
511|Timor oriental|TIMOR-LESTE|OC|28|54|TL|-8.8|125.7||4W
14|Arménie|ARMENIA|AS|21|29|AM|40.1|45.0||EK
75|Géorgie|GEORGIA|AS|21|29|GE|42.3|43.4||4L
18|Azerbaïdjan|AZERBAIJAN|AS|21|29|AZ|40.1|47.6||4J 4K
130|Kazakhstan|KAZAKHSTAN|AS|17|29,30,31|KZ|48.0|66.9||UN UO UP UQ
292|Ouzbékistan|UZBEKISTAN|AS|17|30|UZ|41.4|64.6||UJ UK UL UM
135|Kirghizistan|KYRGYZSTAN|AS|17|30,31|KG|41.2|74.8||EX
262|Tadjikistan|TAJIKISTAN|AS|17|30|TJ|38.9|71.3||EY
280|Turkménistan|TURKMENISTAN|AS|17|30|TM|38.97|59.6||EZ
150|Australie|AUSTRALIA|OC|29,30|55,58,59|AU|-25.3|133.8|AU|VK AX VH VI VJ VL VM VN
147|Lord Howe|LORD HOWE I.|OC|30|60|AU|-31.55|159.08|AU|VK9L AX9L
189|Norfolk|NORFOLK I.|OC|32|60|NF|-29.04|167.95|AU|VK9N AX9N
303|Willis|WILLIS I.|OC|30|55|AU|-16.29|149.96|AU|VK9W AX9W
171|Récif Mellish|MELLISH REEF|OC|30|56|AU|-17.4|155.85|AU|VK9M AX9M
35|Christmas|CHRISTMAS I.|OC|29|54|CX|-10.48|105.63|AU|VK9X AX9X
38|Cocos (Keeling)|COCOS (KEELING) IS.|OC|29|54|CC|-12.15|96.83|AU|VK9C AX9C
153|Macquarie|MACQUARIE I.|OC|30|60|AU|-54.6|158.9|AU|VK0M AX0M
170|Nouvelle-Zélande|NEW ZEALAND|OC|32|60|NZ|-41.0|174.0|NZ|ZL ZM
34|Chatham|CHATHAM IS.|OC|32|60|NZ|-43.9|-176.5|NZ|ZL7 ZM7
133|Kermadec|KERMADEC IS.|OC|32|60|NZ|-29.25|-177.9|NZ|ZL8 ZM8
16|Îles subantarctiques néo-zélandaises|NEW ZEALAND SUBANTARCTIC ISLANDS|OC|32|60|NZ|-50.7|166.1|NZ|ZL9 ZM9
163|Papouasie-Nlle-Guinée|PAPUA NEW GUINEA|OC|28|51|PG|-6.3|143.9||P2
185|Salomon|SOLOMON IS.|OC|28|51|SB|-9.6|160.2||H4
507|Temotu|TEMOTU PROVINCE|OC|32|51|SB|-10.7|165.8||H40
158|Vanuatu|VANUATU|OC|32|56|VU|-15.4|166.9||YJ
162|Nouvelle-Calédonie|NEW CALEDONIA|OC|32|56|NC|-21.3|165.5|FR|FK
512|Chesterfield|CHESTERFIELD IS.|OC|30|56|NC|-19.9|158.3|FR|
176|Fidji|FIJI|OC|32|56|FJ|-17.7|178.0||3D2
489|Récif Conway|CONWAY REEF|OC|32|56|FJ|-21.75|174.6||
460|Rotuma|ROTUMA I.|OC|32|56|FJ|-12.5|177.07||
160|Tonga|TONGA|OC|32|62|TO|-21.2|-175.2||A3
190|Samoa|SAMOA|OC|32|62|WS|-13.76|-172.1||5W
188|Niue|NIUE|OC|32|62|NU|-19.05|-169.87||E6
234|Cook du Sud|SOUTH COOK IS.|OC|32|62|CK|-21.2|-159.8||E5
191|Cook du Nord|NORTH COOK IS.|OC|32|62|CK|-10.4|-161.0||
270|Tokelau|TOKELAU IS.|OC|31|62|TK|-9.2|-171.8|NZ|ZK3
298|Wallis-et-Futuna|WALLIS & FUTUNA IS.|OC|32|62|WF|-13.3|-176.2|FR|FW
282|Tuvalu|TUVALU|OC|31|65|TV|-8.5|179.2||T2
301|Kiribati occidental|W. KIRIBATI (GILBERT IS.)|OC|31|65|KI|1.4|173.0||T30
31|Kiribati central|C. KIRIBATI (BRITISH PHOENIX IS.)|OC|31|62|KI|-2.8|-171.7||T31
48|Kiribati oriental|E. KIRIBATI (LINE IS.)|OC|31|61,63|KI|1.9|-157.4||T32
490|Banaba|BANABA I. (OCEAN I.)|OC|31|65|KI|-0.86|169.54||T33
157|Nauru|NAURU|OC|31|65|NR|-0.53|166.93||C2
168|Marshall|MARSHALL IS.|OC|31|65|MH|7.1|171.2||V7
173|Micronésie|MICRONESIA|OC|27|65|FM|6.9|158.2||V6
22|Palaos|PALAU|OC|27|64|PW|7.5|134.6||T8
175|Polynésie française|FRENCH POLYNESIA|OC|32|63|PF|-17.7|-149.4|FR|FO
508|Australes|AUSTRAL I.|OC|32|63|PF|-23.4|-149.5|FR|
509|Marquises|MARQUESAS IS.|OC|31|63|PF|-9.0|-139.5|FR|
172|Pitcairn|PITCAIRN I.|OC|32|63|PN|-25.07|-130.1|GB|VP6
513|Ducie|DUCIE I.|OC|32|63|PN|-24.67|-124.79|GB|VP6D
"""

    /** The deleted entities: ADIF number | French name | ADIF name. No prefix leads to them. */
    private const val SUPPRIMEES = """
2|Abu Ail|ABU AIL IS.
8|Aldabra|ALDABRA
19|Bajo Nuevo|BAJO NUEVO
23|Récif Blenheim|BLENHEIM REEF
25|Bornéo du Nord britannique|BRITISH NORTH BORNEO
26|Somaliland britannique|BRITISH SOMALILAND
28|Zone du canal de Panama|CANAL ZONE
30|Célèbes et Moluques|CELEBE & MOLUCCA IS.
39|Comores (ancienne entité)|COMORO IS.
42|Damão et Diu|DAMAO, DIU
44|Desroches|DESROCHES
55|Farquhar|FARQUHAR
57|Afrique-Équatoriale française|FRENCH EQUATORIAL AFRICA
58|Indochine française|FRENCH INDO-CHINA
59|Afrique-Occidentale française|FRENCH WEST AFRICA
81|Allemagne (avant 1973)|GERMANY
85|Bonaire et Curaçao (ancienne entité)|BONAIRE, CURACAO
93|Récif Geyser|GEYSER REEF
101|Goa|GOA
102|Côte-de-l'Or et Togo|GOLD COAST, TOGOLAND
113|Ifni|IFNI
115|Somalie italienne|ITALIAN SOMALILAND
119|Java|JAVA
127|Kamaran|KAMARAN IS.
128|République carélo-finnoise|KARELO-FINNISH REPUBLIC
134|Récif Kingman|KINGMAN REEF
139|Kuria Muria|KURIA MURIA I.
151|Malyï Vyssotski|MALYJ VYSOTSKIJ I.
154|République arabe du Yémen|YEMEN ARAB REPUBLIC
155|Malaisie (Malaya)|MALAYA
164|Mandchourie|MANCHURIA
178|Récif Minerva|MINERVA REEF
183|Bornéo néerlandais|NETHERLANDS BORNEO
184|Nouvelle-Guinée néerlandaise|NETHERLANDS NEW GUINEA
186|Terre-Neuve et Labrador|NEWFOUNDLAND LABRADOR
193|Okinawa|OKINAWA (RYUKYU IS.)
194|Okino-Tori-shima|OKINO TORI-SHIMA
196|Palestine (ancienne entité)|PALESTINE (DELETED)
198|Territoire de Papouasie|PAPUA TERR.
200|Timor portugais|PORTUGUESE TIMOR
208|Ruanda-Urundi|RUANDA-URUNDI
210|Sarre|SAAR
218|Tchécoslovaquie|CZECHOSLOVAKIA
220|Sarawak|SARAWAK
226|Zone neutre Arabie saoudite–Irak|SAUDI ARABIA/IRAQ NEUTRAL ZONE
228|Banc Serrana et Roncador|SERRANA BANK & RONCADOR CAY
229|Allemagne de l'Est (RDA)|GERMAN DEMOCRATIC REPUBLIC
231|Sikkim|SIKKIM
243|Yémen du Sud|PEOPLE'S DEMOCRATIC REP. OF YEMEN
255|Sint Maarten, Saba, Saint-Eustache (ancienne entité)|ST. MAARTEN, SABA, ST. EUSTATIUS
258|Sumatra|SUMATRA
261|Îles Swan|SWAN IS.
264|Tanger|TANGIER
267|Territoire de Nouvelle-Guinée|TERRITORY OF NEW GUINEA
268|Tibet|TIBET
271|Trieste|TRIESTE
307|Zanzibar|ZANZIBAR
488|Walvis Bay|WALVIS BAY
493|Îles Penguin|PENGUIN IS.
"""

    /** The table's mark for a prefix shared by several entities (FT5, VK9): no answer. */
    private val AMBIGU = Entite("", "")

    private fun zones(s: String) = s.split(',').mapNotNull { it.trim().toIntOrNull() }

    private val toutes: List<Entite>

    private val parNumero: Map<Int, Entite>

    /**
     * Prefix → entity. Keys are tried longest first, so "EA8" (Canaries) wins
     * over "EA" (Spain).
     */
    private val table: Map<String, Entite>

    /** Rules on the whole callsign, tried before the prefixes. */
    private val exceptions: List<Pair<Regex, Entite>>

    init {
        // The families added by rule: prefix → ADIF number.
        val familles = LinkedHashMap<String, Int>()
        // French Southern and Antarctic Lands and the Scattered Islands: FT, a digit, a letter.
        for (d in 0..9) for ((l, n) in listOf('G' to 99, 'J' to 124, 'E' to 124, 'T' to 276, 'W' to 41, 'X' to 131, 'Z' to 10, 'Y' to 13))
            familles["FT$d$l"] = n
        // The US Pacific and Caribbean: KH#, KP# (and AH, NH, WH, NP, WP).
        for (p in listOf("AH", "KH", "NH", "WH")) {
            listOf(0 to 166, 1 to 20, 2 to 103, 3 to 123, 4 to 174, 5 to 197, 6 to 110, 7 to 110, 8 to 9, 9 to 297)
                .forEach { (d, n) -> familles["$p$d"] = n }
            familles["${p}7K"] = 138
            familles["${p}8S"] = 515
        }
        for (p in listOf("KP", "NP", "WP"))
            listOf(1 to 182, 2 to 285, 3 to 202, 4 to 202, 5 to 43).forEach { (d, n) -> familles["$p$d"] = n }
        // Chile's islands: CE0X San Félix, CE0Y Easter Island, CE0Z Juan Fernández.
        for (p in listOf("CA", "CB", "CC", "CD", "CE", "XQ", "XR", "3G")) {
            familles["${p}0X"] = 217; familles["${p}0Y"] = 47; familles["${p}0Z"] = 125
        }
        // Brazil's islands: PY0F Fernando de Noronha, PY0S St Peter and St Paul, PY0T Trindade.
        for (p in "PP PQ PR PS PT PU PV PW PX PY ZV ZW ZX ZY ZZ".split(' ')) {
            familles["${p}0F"] = 56; familles["${p}0S"] = 253; familles["${p}0T"] = 273
        }
        // Russia by call area: 2 Kaliningrad, 8, 9 and 0 Asia, the others Europe.
        val russes = listOf("R", "RA", "RC", "RD", "RE", "RF", "RG", "RJ", "RK", "RL", "RM", "RN", "RO", "RQ", "RT", "RU", "RV", "RW",
            "RX", "RY", "RZ", "UA", "UB", "UC", "UD", "UE", "UF", "UG", "UH", "UI")
        for (p in russes) {
            familles[p] = 54
            familles["${p}2"] = 126
            for (d in listOf("8", "9", "0")) familles["$p$d"] = 15
        }

        val actuelles = ACTUELLES.trim().lines().filter { it.isNotBlank() }.map { l ->
            val c = l.split('|')
            require(c.size == 11) { "Dxcc : ligne mal formée « $l »" }
            val n = c[0].toInt()
            val pre = c[10].split(' ').filter { it.isNotBlank() } + familles.filterValues { it == n }.keys
            Entite(c[1], c[9], c[3], n, c[2], zones(c[4]), zones(c[5]), false, c[6],
                c[7].toDoubleOrNull(), c[8].toDoubleOrNull(), pre)
        }
        val anciennes = SUPPRIMEES.trim().lines().filter { it.isNotBlank() }.map { l ->
            val c = l.split('|')
            Entite(c[1], "", "", c[0].toInt(), c[2], supprimee = true)
        }
        toutes = actuelles + anciennes
        parNumero = toutes.associateBy { it.adif }
        require(parNumero.size == toutes.size) { "Dxcc : numéro ADIF en double" }
        table = buildMap {
            actuelles.forEach { e -> e.prefixes.forEach { put(it, e) } }
            // Shared prefixes that a longer one must decide: alone, they name no entity.
            (0..9).forEach { put("FT$it", AMBIGU) }
            listOf("VK9", "AX9").forEach { put(it, AMBIGU) }
        }

        fun r(motif: String, n: Int) = Regex(motif) to parNumero.getValue(n)
        exceptions = listOf(
            // Guantánamo: KG4 with a two-letter suffix (KG4AB); KG4ABC is an ordinary US call.
            r("^KG4([A-Z]{2})?$", 105),
            // The US Antarctic stations: KC4AAA (South Pole), KC4AAC, KC4USV (McMurdo)…
            r("^KC4(AA|US)[A-Z]$", 13),
            // Call area 9 with F, G (Perm) or X (Komi): west of the Urals.
            r("^(R[A-Z]?|U[A-I])9[FGX]", 54),
            // Known callsigns of entities without a prefix of their own.
            r("^SV2ASP$", 180),
            r("^3Y0X$", 199),
            r("^JD1BMM$", 177),
            r("^HK0(NA|TU)$", 161),
            r("^(FO0AAA|TX5K)$", 36),
            r("^TX3X$", 512),
            r("^VK0EK$", 111),
            r("^3D2(C|CR)$", 489),
            r("^3D2R$", 460),
        )
    }

    /** Prefix lengths present in the table, longest first. */
    private val longueurs: List<Int> =
        table.keys.map { it.length }.distinct().sortedDescending()

    /** The current DXCC entities. */
    val actuelles: List<Entite> get() = toutes.filter { !it.supprimee }

    /** The deleted ones, kept for an ADIF number read from an old log. */
    val supprimees: List<Entite> get() = toutes.filter { it.supprimee }

    /** The entity with this ADIF number (current or deleted), or null. */
    fun parAdif(numero: Int): Entite? = parNumero[numero]

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
        for ((motif, e) in exceptions) if (motif.containsMatchIn(efficace)) return e
        for (n in longueurs) {
            if (efficace.length >= n) {
                table[efficace.take(n)]?.let { return it.takeIf { it !== AMBIGU } }
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
}
