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
 * What an APRS symbol stands for, in words (APRS 1.01 appendix 2 and the
 * later additions to the alternate table): the table ('/' primary, '\'
 * alternate, or an overlay character 0-9 A-Z on the alternate one) and the
 * code. Written here in French, SatMe's language.
 */
object SymbolesAprs {

    private val PRIMAIRE: Map<Char, String> = mapOf(
        '!' to "poste de police", '"' to "réservé", '#' to "digipeater", '$' to "téléphone", '%' to "cluster DX",
        '&' to "passerelle HF", '\'' to "petit avion", '(' to "station satellite mobile", ')' to "fauteuil roulant",
        '*' to "motoneige", '+' to "Croix-Rouge", ',' to "scouts", '-' to "maison (QTH)", '.' to "croix (inconnu)",
        '/' to "point rouge", '0' to "cercle 0", '1' to "cercle 1", '2' to "cercle 2", '3' to "cercle 3", '4' to "cercle 4",
        '5' to "cercle 5", '6' to "cercle 6", '7' to "cercle 7", '8' to "cercle 8", '9' to "cercle 9",
        ':' to "incendie", ';' to "camping, portable", '<' to "moto", '=' to "locomotive", '>' to "voiture",
        '?' to "serveur de fichiers", '@' to "prévision d'ouragan", 'A' to "poste de secours", 'B' to "BBS",
        'C' to "canoë", 'D' to "—", 'E' to "observation (œil)", 'F' to "tracteur", 'G' to "carré locator",
        'H' to "hôtel", 'I' to "TCP/IP", 'J' to "—", 'K' to "école", 'L' to "ordinateur connecté", 'M' to "MacAPRS",
        'N' to "station NTS", 'O' to "ballon", 'P' to "police", 'Q' to "—", 'R' to "camping-car", 'S' to "navette spatiale",
        'T' to "SSTV", 'U' to "bus", 'V' to "ATV", 'W' to "service météo national", 'X' to "hélicoptère",
        'Y' to "voilier", 'Z' to "WinAPRS", '[' to "piéton", '\\' to "triangle (radiogoniométrie)", ']' to "boîte aux lettres (PBBS)",
        '^' to "gros avion", '_' to "station météo", '`' to "parabole", 'a' to "ambulance", 'b' to "vélo",
        'c' to "poste de commandement", 'd' to "pompiers (caserne)", 'e' to "cheval", 'f' to "camion de pompiers",
        'g' to "planeur", 'h' to "hôpital", 'i' to "île (IOTA)", 'j' to "jeep", 'k' to "camion", 'l' to "ordinateur portable",
        'm' to "relais Mic-E", 'n' to "nœud", 'o' to "centre de crise", 'p' to "chien (rover)", 'q' to "carré locator",
        'r' to "relais", 's' to "bateau à moteur", 't' to "relais routier", 'u' to "semi-remorque", 'v' to "fourgon",
        'w' to "station de pompage", 'x' to "xAPRS (Unix)", 'y' to "yagi à la maison", 'z' to "—",
        '{' to "—", '|' to "—", '}' to "—", '~' to "—",
    )

    private val ALTERNE: Map<Char, String> = mapOf(
        '!' to "urgence", '"' to "—", '#' to "digipeater", '$' to "banque, distributeur", '%' to "centrale électrique",
        '&' to "passerelle (iGate)", '\'' to "lieu d'accident", '(' to "nuageux", ')' to "réseau radio (firenet)",
        '*' to "neige", '+' to "église", ',' to "guides (scoutes)", '-' to "maison (HF)", '.' to "position ambiguë",
        '/' to "point de rendez-vous", '0' to "cercle (IRLP, EchoLink, WIRES)", '1' to "—", '2' to "—", '3' to "—",
        '4' to "—", '5' to "—", '6' to "—", '7' to "—", '8' to "réseau 802.11", '9' to "station-service",
        ':' to "grêle", ';' to "parc, pique-nique", '<' to "avis météo", '=' to "APRStt (DTMF)", '>' to "véhicule",
        '?' to "point d'information", '@' to "tempête tropicale", 'A' to "boîte (DTMF, RFID)", 'B' to "chasse-neige",
        'C' to "garde-côtes", 'D' to "bruine, dépôt", 'E' to "fumée", 'F' to "pluie verglaçante", 'G' to "averse de neige",
        'H' to "brume", 'I' to "averse", 'J' to "éclairs", 'K' to "portatif Kenwood", 'L' to "phare", 'M' to "MARS",
        'N' to "bouée de navigation", 'O' to "ballon, fusée", 'P' to "parking", 'Q' to "séisme", 'R' to "restaurant",
        'S' to "satellite", 'T' to "orage", 'U' to "ensoleillé", 'V' to "balise VOR", 'W' to "site météo",
        'X' to "pharmacie", 'Y' to "radios et appareils", 'Z' to "—", '[' to "nuage en mur", '\\' to "—", ']' to "—",
        '^' to "aéronef", '_' to "station météo", '`' to "pluie", 'a' to "ARES, WinLink", 'b' to "vent de sable",
        'c' to "protection civile", 'd' to "spot DX", 'e' to "neige fondue", 'f' to "entonnoir nuageux", 'g' to "coup de vent",
        'h' to "magasin", 'i' to "point d'intérêt", 'j' to "travaux", 'k' to "4×4 (SUV)", 'l' to "zone",
        'm' to "panneau", 'n' to "triangle", 'o' to "petit cercle", 'p' to "partiellement nuageux", 'q' to "—",
        'r' to "toilettes", 's' to "bateau", 't' to "tornade", 'u' to "camion", 'v' to "fourgon", 'w' to "inondation",
        'x' to "obstacle", 'y' to "Skywarn", 'z' to "abri", '{' to "brouillard", '|' to "—", '}' to "—", '~' to "—",
    )

    /** "/>" → "voiture"; "\&" with an overlay "I&" → "passerelle (iGate), surcharge I". Empty when not known. */
    fun nom(symbole: String?): String {
        if (symbole == null || symbole.length < 2) return ""
        val t = symbole[0]; val c = symbole[1]
        val n = (if (t == '/') PRIMAIRE[c] else ALTERNE[c])?.takeIf { it != "—" } ?: return ""
        return if (t == '/' || t == '\\') n else "$n, surcharge ${surcharge(t)}"
    }

    /** A compressed position's overlay digit (a-j) as the digit it stands for; others as they are. */
    fun surcharge(t: Char): Char = if (t in 'a'..'j') '0' + (t - 'a') else t
}
