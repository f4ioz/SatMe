/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.station

import fr.f4ioz.satcombo.station.Montage.Genre
import fr.f4ioz.satcombo.station.Montage.Modele
import fr.f4ioz.satcombo.station.Montage.Option
import fr.f4ioz.satcombo.station.Montage.Port
import fr.f4ioz.satcombo.station.Montage.Sens
import fr.f4ioz.satcombo.station.Montage.Signal
import fr.f4ioz.satcombo.station.Montage.Voie

/**
 * The hardware the « Montage » page knows, each with its ports, and the
 * ready-made chains. The drawings are SatMe's own (a stylised front panel),
 * never a maker's picture or mark; the owner may add his own photo.
 */
object CatalogueMateriel {

    // Port helpers: radio in, out, both ways; data, sound, commands.
    private fun rfE(id: String, nom: String, voie: Voie, mhz: Int?, c: String) = Port(id, nom, Sens.ENTREE, Signal.RF, voie, mhz, c)
    private fun rfS(id: String, nom: String, voie: Voie, mhz: Int?, c: String) = Port(id, nom, Sens.SORTIE, Signal.RF, voie, mhz, c)
    private fun rfB(id: String, nom: String, mhz: Int?, c: String, voie: Voie = Voie.RXTX) = Port(id, nom, Sens.BIDIR, Signal.RF, voie, mhz, c)
    private fun usb(id: String = "usb", nom: String = "USB", multiple: Boolean = false) = Port(id, nom, Sens.BIDIR, Signal.DONNEES, connecteur = "USB", multiple = multiple)
    private fun rj45(id: String = "lan", nom: String = "Réseau", multiple: Boolean = false) = Port(id, nom, Sens.BIDIR, Signal.DONNEES, connecteur = "RJ45", multiple = multiple)
    private fun wifi(multiple: Boolean = false) = Port("wifi", "Wi-Fi", Sens.BIDIR, Signal.DONNEES, connecteur = "Wi-Fi", multiple = multiple)
    private fun audioE(id: String = "audio_in", nom: String = "Entrée audio") = Port(id, nom, Sens.ENTREE, Signal.AUDIO, connecteur = "jack")
    private fun audioS(id: String = "audio_out", nom: String = "Sortie audio") = Port(id, nom, Sens.SORTIE, Signal.AUDIO, connecteur = "jack")
    private fun audioB(id: String = "audio", nom: String = "Audio (ACC)") = Port(id, nom, Sens.BIDIR, Signal.AUDIO, connecteur = "DIN")
    private fun cat(id: String = "cat", nom: String = "CAT (série)") = Port(id, nom, Sens.BIDIR, Signal.COMMANDE, connecteur = "DB9")
    private fun ptt(id: String = "ptt", nom: String = "PTT") = Port(id, nom, Sens.BIDIR, Signal.COMMANDE, connecteur = "jack")
    private fun mat() = Port("mat", "Mât (rotor)", Sens.BIDIR, Signal.MECA, connecteur = "rotor")
    // A frequency reference (10 MHz, 25 MHz…), a DC supply, a GPS antenna, a program's network link.
    private fun refE(id: String, nom: String, mhz: Int?) = Port(id, nom, Sens.ENTREE, Signal.REF, mhz = mhz, connecteur = "SMA")
    private fun refS(id: String, nom: String, mhz: Int?) = Port(id, nom, Sens.SORTIE, Signal.REF, mhz = mhz, connecteur = "SMA")
    private fun dcE(id: String = "alim", nom: String = "Alimentation 12–13,8 V") = Port(id, nom, Sens.ENTREE, Signal.DC, connecteur = "DC")
    private fun gpsE(id: String = "gps", nom: String = "Antenne GPS") = Port(id, nom, Sens.ENTREE, Signal.GPS, connecteur = "SMA")
    private fun net(id: String = "net", nom: String = "Réseau / Internet", multiple: Boolean = false) =
        Port(id, nom, Sens.BIDIR, Signal.DONNEES, connecteur = "Internet", multiple = multiple)

    // Options helpers: fitted or not, an IF band, a reference's frequency.
    private fun oui(id: String, nom: String, defaut: Boolean) = Option(id, nom, listOf("1" to "oui", "0" to "non"), if (defaut) "1" else "0")
    private fun fi(id: String, nom: String, defaut: String) = Option(id, nom, listOf("144" to "144 MHz (2 m)", "432" to "432 MHz (70 cm)"), defaut)
    private val FREQ_REF = listOf("10", "20", "24", "25", "26", "27", "40", "100").map { it to "$it MHz" }
    private fun bande(mhz: Int) = if (mhz < 300) "2 m" else "70 cm"

    /** A QO-100 up-converter: the IF from a transceiver or an SDR to 2400 MHz; a 10 MHz reference input when fitted. */
    private fun montant(id: String, nom: String, refParDefaut: Boolean) = Modele(id, nom, Genre.CONVERTISSEUR, "dxpup",
        listOf(rfE("entree", "Entrée FI (faible puissance)", Voie.TX, 432, "SMA"), rfS("sortie", "Sortie 2,4 GHz", Voie.TX, 2400, "SMA"),
            refE("ref", "Référence externe 10 MHz", 10), dcE()),
        96, 60, note = "Montée QO-100 : la FI d'un poste 2 m / 70 cm ou d'une carte SDR vers 2400 MHz. Entrée à faible puissance : " +
            "quelques milliwatts au plus (voir sa notice) — un poste à pleine puissance la détruit : puissance au minimum ou atténuateur. " +
            "Ports à vérifier sur ton modèle : la référence 10 MHz se coche dans ses réglages.",
        options = listOf(fi("fi", "Entrée FI", "432"), oui("ref", "Entrée de référence 10 MHz", refParDefaut))) { o ->
        val f = o["fi"]!!.toInt()
        listOfNotNull(rfE("entree", "Entrée FI " + f + " MHz (faible puissance)", Voie.TX, f, "SMA"), rfS("sortie", "Sortie 2,4 GHz", Voie.TX, 2400, "SMA"),
            if (o["ref"] == "1") refE("ref", "Référence externe 10 MHz", 10) else null, dcE())
    }

    /** A QO-100 down-converter on the receive side: the LNB's IF (739 MHz) to 144 or 432 MHz. */
    private fun descendant(id: String, nom: String, refParDefaut: Boolean) = Modele(id, nom, Genre.CONVERTISSEUR, "dxpdown",
        listOf(rfE("entree", "Entrée FI du LNB (739 MHz)", Voie.RX, 739, "SMA"), rfS("sortie", "Sortie 144 MHz", Voie.RX, 144, "SMA"),
            refE("ref", "Référence externe 10 MHz", 10), dcE()),
        96, 60, note = "Réception QO-100 : la FI du LNB (vers 739 MHz) descendue vers 144 ou 432 MHz, pour un poste ou une carte SDR. " +
            "Son oscillateur (vers 595 MHz pour 144) s'ajoute à celui du LNB dans la chaîne QO-100. Ports à vérifier sur ton modèle.",
        options = listOf(fi("sortie", "Sortie", "144"), oui("ref", "Entrée de référence 10 MHz", refParDefaut))) { o ->
        val f = o["sortie"]!!.toInt()
        listOfNotNull(rfE("entree", "Entrée FI du LNB (739 MHz)", Voie.RX, 739, "SMA"), rfS("sortie", "Sortie " + f + " MHz (" + bande(f) + ")", Voie.RX, f, "SMA"),
            if (o["ref"] == "1") refE("ref", "Référence externe 10 MHz", 10) else null, dcE())
    }

    /** A 2.4 GHz power amplifier: RF in and out, its PTT (or sequencer) input, its supply. */
    private fun pa(id: String, nom: String, watts: Int) = Modele(id, nom, Genre.AMPLI, "pa",
        listOf(rfE("entree", "Entrée 2,4 GHz", Voie.TX, 2400, "SMA"), rfS("sortie", "Sortie " + watts + " W", Voie.TX, 2400, "N"),
            Port("ptt", "PTT / séquenceur", Sens.ENTREE, Signal.COMMANDE, connecteur = "RCA"), dcE("alim", "Alimentation 13,8 V (plusieurs ampères)")),
        110, 64, note = "Amplificateur 2,4 GHz, " + watts + " W en sortie. Niveau d'entrée : celui de sa notice, sans le dépasser " +
            "(un excès le détruit) ; jamais d'émission sans antenne ou charge sur la sortie ; PTT avant la HF (séquenceur).")

    /** A boîte: a program or a service SatMe talks to, drawn beside the computer (its state read, nothing sent from the drawing). */
    private fun boite(id: String, nom: String, role: String, note: String) =
        Modele(id, nom, Genre.BOITE, "boite", listOf(net("net", "Réseau / Internet")), 112, 58, setOf("boite:$role"), note)

    val MODELES: List<Modele> = listOf(
        // ---- antennas
        Modele("parabole-qo100", "Parabole + source QO-100 (POTY / patch)", Genre.ANTENNE, "parabole",
            listOf(rfS("lnb", "Source 10 GHz", Voie.RX, 10489, "guide"), rfE("patch", "Patch 2,4 GHz", Voie.TX, 2400, "N")),
            130, 120, setOf("parabole"), "Parabole fixe vers QO-100 ; la source double bande reçoit 10 GHz et émet 2,4 GHz."),
        Modele("parabole-rx", "Parabole TV (réception seule)", Genre.ANTENNE, "parabole",
            listOf(rfS("lnb", "Source 10 GHz", Voie.RX, 10489, "guide")), 120, 110, setOf("parabole")),
        Modele("yagi-2m", "Yagi 2 m", Genre.ANTENNE, "yagi", listOf(rfB("ant", "Antenne 2 m", 145, "N"), mat()), 130, 70),
        Modele("yagi-70cm", "Yagi 70 cm", Genre.ANTENNE, "yagi", listOf(rfB("ant", "Antenne 70 cm", 435, "N"), mat()), 120, 62),
        Modele("yagi-bibande", "Yagi bibande 2 m / 70 cm", Genre.ANTENNE, "yagi",
            listOf(rfB("ant144", "2 m", 145, "N"), rfB("ant432", "70 cm", 435, "N"), mat()), 130, 70),
        Modele("xquad", "X-quad 2 m", Genre.ANTENNE, "xquad", listOf(rfB("ant", "Antenne", 145, "N"), mat()), 110, 90),
        Modele("colineaire", "Colinéaire 2 m / 70 cm", Genre.ANTENNE, "colineaire",
            listOf(rfB("ant", "Antenne", 145, "N")), 60, 120),
        Modele("antenne-interieure", "Antenne intérieure (essais)", Genre.ANTENNE, "interieure",
            listOf(rfB("ant", "Antenne", null, "SMA")), 60, 80),
        Modele("antenne-gps", "Antenne GPS active", Genre.ANTENNE, "gps",
            listOf(Port("gps", "Sortie GPS (alimentée par le récepteur)", Sens.SORTIE, Signal.GPS, connecteur = "SMA")), 56, 46,
            note = "Une antenne par récepteur GPS (GPSDO) : à l'extérieur, ciel dégagé."),

        // ---- LNB and converters
        Modele("lnb-pll", "LNB PLL 10 GHz (sortie 739 MHz)", Genre.LNB, "lnb",
            listOf(rfE("entree", "Entrée 10 GHz", Voie.RX, 10489, "guide"), rfS("sortie", "Sortie FI", Voie.RX, 739, "F")), 80, 54, setOf("lnb")),
        Modele("lnb-twin", "LNB Octagon Twin « Green »", Genre.LNB, "lnb2",
            listOf(rfE("entree", "Entrée 10 GHz", Voie.RX, 10489, "guide"), rfS("sortie1", "Sortie 1 (FI)", Voie.RX, 739, "F"),
                rfS("sortie2", "Sortie 2 (FI)", Voie.RX, 739, "F"), refE("ref25", "Référence externe 25 MHz (LNB modifié)", 25)),
            90, 62, setOf("lnb"),
            "Deux sorties indépendantes (chacune sa polarisation par la tension 13 / 18 V), un seul oscillateur pour les deux. " +
                "Modifié, son quartz remplacé par un 25 MHz externe (d'un GPSDO, par un injecteur sur le coaxial) : coche la référence.",
            options = listOf(oui("ref", "Référence externe 25 MHz", false))) { o ->
            listOfNotNull(rfE("entree", "Entrée 10 GHz", Voie.RX, 10489, "guide"), rfS("sortie1", "Sortie 1 (FI)", Voie.RX, 739, "F"),
                rfS("sortie2", "Sortie 2 (FI)", Voie.RX, 739, "F"),
                if (o["ref"] == "1") refE("ref25", "Référence externe 25 MHz (LNB modifié)", 25) else null)
        },
        descendant("dxp-down-mk4", "DX Patrol convertisseur descendant MK4", true),
        descendant("dxp-down-mk3", "DX Patrol convertisseur descendant MK3", false),
        montant("dxp-up-mk4", "DX Patrol Upconverter MK4", true),
        montant("dxp-up-mk3", "DX Patrol Upconverter MK3", false),
        Modele("dc-144", "Convertisseur QO-100 → 144 MHz", Genre.CONVERTISSEUR, "convertisseur",
            listOf(rfE("entree", "Entrée 10 GHz", Voie.RX, 10489, "SMA"), rfS("sortie", "Sortie 144 MHz", Voie.RX, 144, "BNC")), 86, 54, setOf("lnb")),
        Modele("dc-432", "Convertisseur QO-100 → 432 MHz", Genre.CONVERTISSEUR, "convertisseur",
            listOf(rfE("entree", "Entrée 10 GHz", Voie.RX, 10489, "SMA"), rfS("sortie", "Sortie 432 MHz", Voie.RX, 432, "N")), 86, 54, setOf("lnb")),
        Modele("dc-739", "Convertisseur QO-100 → 739 MHz", Genre.CONVERTISSEUR, "convertisseur",
            listOf(rfE("entree", "Entrée 10 GHz", Voie.RX, 10489, "SMA"), rfS("sortie", "Sortie 739 MHz", Voie.RX, 739, "SMA")), 86, 54, setOf("lnb")),
        Modele("up-144", "Convertisseur montant 144 → 2400 MHz", Genre.CONVERTISSEUR, "convertisseur",
            listOf(rfE("entree", "Entrée 144 MHz", Voie.TX, 144, "N"), rfS("sortie", "Sortie 2,4 GHz", Voie.TX, 2400, "SMA")), 86, 54),
        Modele("up-432", "Convertisseur montant 432 → 2400 MHz", Genre.CONVERTISSEUR, "convertisseur",
            listOf(rfE("entree", "Entrée 432 MHz", Voie.TX, 432, "N"), rfS("sortie", "Sortie 2,4 GHz", Voie.TX, 2400, "SMA")), 86, 54),
        Modele("pa-2400", "Amplificateur 2,4 GHz", Genre.AMPLI, "ampli",
            listOf(rfE("entree", "Entrée", Voie.TX, 2400, "SMA"), rfS("sortie", "Sortie", Voie.TX, 2400, "N")), 96, 60),
        pa("dxp-pa-20w", "DX Patrol ampli 2,4 GHz 20 W", 20),
        pa("dxp-pa-15w", "DX Patrol ampli 2,4 GHz 15 W", 15),
        Modele("sequenceur", "Séquenceur (PTT)", Genre.AMPLI, "sequenceur",
            listOf(Port("ptt_in", "PTT du poste", Sens.ENTREE, Signal.COMMANDE, connecteur = "RCA"),
                Port("ptt1", "Étape 1 (relais, convertisseur)", Sens.SORTIE, Signal.COMMANDE, connecteur = "RCA"),
                Port("ptt2", "Étape 2 (ampli)", Sens.SORTIE, Signal.COMMANDE, connecteur = "RCA"), dcE()),
            84, 52, note = "Met en émission dans l'ordre (relais, convertisseur, puis ampli) et relâche dans l'ordre inverse."),

        // ---- frequency references
        Modele("dxp-gpsdo", "DX Patrol GPSDO 10 MHz", Genre.REFERENCE, "gpsdo",
            listOf(gpsE(), refS("s1", "Sortie 10 MHz n° 1", 10), refS("s2", "Sortie 10 MHz n° 2", 10), refS("s3", "Sortie 10 MHz n° 3", 10),
                refS("s4", "Sortie 10 MHz n° 4", 10), dcE()),
            104, 62, setOf("gpsdo"), "Référence 10 MHz calée sur le GPS, pour les convertisseurs montant et descendant : leurs fréquences ne dérivent plus.",
            options = listOf(Option("sorties", "Sorties 10 MHz", listOf("1" to "1", "2" to "2", "3" to "3", "4" to "4"), "2"))) { o ->
            val n = o["sorties"]!!.toInt()
            listOf(gpsE()) + (1..n).map { refS("s$it", "Sortie 10 MHz n° $it", 10) } + dcE()
        },
        Modele("leo-bodnar", "Leo Bodnar GPSDO programmable", Genre.REFERENCE, "bodnar",
            listOf(gpsE(), refS("s1", "Sortie 1", 25), refS("s2", "Sortie 2", 10), usb("usb", "USB (réglage, alimentation)")),
            92, 56, setOf("gpsdo"), "Chaque sortie programmée à sa fréquence par son logiciel (25 ou 27 MHz pour un LNB, 10 MHz pour un convertisseur) : " +
                "SatMe ne la règle pas, il note ce que tu as choisi.",
            options = listOf(Option("sorties", "Sorties", listOf("1" to "1", "2" to "2"), "2"), Option("f1", "Sortie 1", FREQ_REF, "25"),
                Option("f2", "Sortie 2", FREQ_REF, "10"))) { o ->
            val n = o["sorties"]!!.toInt()
            listOf(gpsE()) + (1..n).map { k -> val f = o["f$k"]!!.toInt(); refS("s$k", "Sortie $k ($f MHz)", f) } + usb("usb", "USB (réglage, alimentation)")
        },

        // ---- around the cables
        Modele("bias-t", "T de polarisation (alimentation du LNB)", Genre.ALIMENTATION, "biast",
            listOf(rfB("lnb", "Vers le LNB (+ continu)", 739, "F", Voie.RX), rfS("rx", "Vers le récepteur", Voie.RX, 739, "SMA")), 70, 42),
        Modele("injecteur", "Injecteur continu + référence (vers le LNB)", Genre.ALIMENTATION, "injecteur",
            listOf(rfB("lnb", "Vers le LNB (FI + continu + référence)", 739, "F", Voie.RX), rfS("rx", "Vers le récepteur", Voie.RX, 739, "F"),
                Port("dc", "Entrée continue (13 / 18 V)", Sens.ENTREE, Signal.DC, connecteur = "DC"), refE("ref", "Entrée référence (25 MHz)", 25)),
            84, 50, note = "Met sur le même coaxial la tension du LNB et sa référence externe ; la FI repart vers le récepteur."),
        Modele("dc-block", "Bloqueur de continu (DC block)", Genre.FILTRE, "dcblock",
            listOf(rfE("entree", "Entrée", Voie.RXTX, null, "SMA"), rfS("sortie", "Sortie", Voie.RXTX, null, "SMA")), 64, 34,
            note = "Laisse passer la HF, arrête le continu : protège une entrée qui ne doit pas recevoir l'alimentation du LNB."),
        Modele("alim-138", "Alimentation 13,8 V", Genre.ALIMENTATION, "alim",
            listOf(Port("dc", "Sortie 13,8 V", Sens.SORTIE, Signal.DC, connecteur = "DC", multiple = true)), 90, 56),
        Modele("filtre", "Filtre passe-bande", Genre.FILTRE, "filtre",
            listOf(rfE("entree", "Entrée", Voie.RXTX, null, "SMA"), rfS("sortie", "Sortie", Voie.RXTX, null, "SMA")), 80, 44),
        Modele("attenuateur", "Atténuateur", Genre.CHARGE, "attenuateur",
            listOf(rfE("entree", "Entrée", Voie.RXTX, null, "SMA"), rfS("sortie", "Sortie", Voie.RXTX, null, "SMA")), 70, 36),
        Modele("charge", "Charge fictive", Genre.CHARGE, "charge", listOf(rfE("entree", "Entrée", Voie.TX, null, "N")), 80, 50),
        Modele("repartiteur", "Répartiteur (1 vers 2)", Genre.COUPLEUR, "repartiteur",
            listOf(rfE("entree", "Entrée", Voie.RX, null, "F"), rfS("sortie1", "Sortie 1", Voie.RX, null, "F"), rfS("sortie2", "Sortie 2", Voie.RX, null, "F")), 70, 56),

        // ---- rotor
        Modele("rotor-gs232", "Rotor site/azimut + pupitre GS-232", Genre.ROTOR, "rotor",
            listOf(Port("moteur", "Commande du rotor", Sens.BIDIR, Signal.COMMANDE, connecteur = "rotor", multiple = true), usb("serie", "Série / USB")),
            90, 90, setOf("rotor")),

        // ---- transceivers (no maker's mark: a front panel of SatMe's own)
        Modele("ic9700", "Icom IC-9700", Genre.POSTE, "ic9700",
            listOf(rfB("ant144", "ANT 144 MHz", 145, "PL"), rfB("ant432", "ANT 430 MHz", 435, "N"), rfB("ant1200", "ANT 1200 MHz", 1270, "N"),
                usb("usb", "USB (CAT + son)"), rj45("lan", "LAN")), 170, 84, setOf("cat")),
        Modele("ic705", "Icom IC-705", Genre.POSTE, "ic705",
            listOf(rfB("ant", "ANT (HF à 430 MHz)", 145, "BNC"), usb("usb", "USB (CAT + son)"), wifi()), 120, 74, setOf("cat")),
        Modele("ts2000", "Kenwood TS-2000", Genre.POSTE, "ts2000",
            listOf(rfB("ant144", "ANT 144 MHz", 145, "N"), rfB("ant432", "ANT 430 MHz", 435, "N"), cat("cat", "COM (CAT)"), audioB("acc2", "ACC 2 (son)")),
            170, 84, setOf("cat")),
        Modele("ft991a", "Yaesu FT-991A", Genre.POSTE, "ft991",
            listOf(rfB("ant", "ANT HF/50", 28, "PL"), rfB("antv", "ANT 144/430", 145, "N"), usb("usb", "USB (CAT + son)")), 160, 80, setOf("cat")),
        Modele("ft818", "Yaesu FT-818", Genre.POSTE, "ft818",
            listOf(rfB("ant", "ANT (BNC avant)", 145, "BNC"), cat("cat", "CAT"), audioB("data", "DATA (son)")), 110, 66, setOf("cat")),
        Modele("poste", "Poste émetteur-récepteur", Genre.POSTE, "poste",
            listOf(rfB("ant", "Antenne", 145, "N"), usb("usb", "USB / CAT"), audioB("audio", "Son")), 150, 76, setOf("cat")),

        // ---- SDR boards
        Modele("rtl-sdr", "Clé RTL-SDR", Genre.SDR, "rtlsdr", listOf(rfE("rx", "Antenne", Voie.RX, null, "SMA"), usb()), 92, 34, setOf("sdr:rtl")),
        Modele("pluto", "ADALM-PLUTO", Genre.SDR, "pluto",
            listOf(rfE("rx", "RX", Voie.RX, 739, "SMA"), rfS("tx", "TX", Voie.TX, 2400, "SMA"), usb("usb", "USB (réseau)")),
            96, 62, setOf("sdr:pluto", "datv:pluto", "tx:pluto", "iq:pluto")),
        Modele("bladerf", "bladeRF 2.0 micro", Genre.SDR, "bladerf",
            listOf(rfE("rx1", "RX1", Voie.RX, 739, "SMA"), rfE("rx2", "RX2", Voie.RX, null, "SMA"),
                rfS("tx1", "TX1", Voie.TX, 2400, "SMA"), rfS("tx2", "TX2", Voie.TX, null, "SMA"), usb("usb", "USB 3")),
            120, 70, setOf("sdr:bladerf", "datv:bladerf", "tx:bladerf", "iq:bladerf")),
        Modele("hackrf", "HackRF One", Genre.SDR, "hackrf",
            listOf(rfB("ant", "ANTENNA (RX/TX)", null, "SMA"), usb()), 110, 66, setOf("sdr:hackrf", "tx:hackrf", "iq:hackrf")),
        Modele("sdr-recepteur", "Récepteur SDR (Airspy, SDRplay…)", Genre.SDR, "sdr",
            listOf(rfE("rx", "Antenne", Voie.RX, null, "SMA"), usb()), 100, 50, setOf("sdr:autre")),

        // ---- DATV
        Modele("minitiouner", "MiniTiouner Pro V2", Genre.RECEPTEUR_DATV, "minitiouner",
            listOf(rfE("lnb_a", "Entrée A (LNB)", Voie.RX, 739, "F"), rfE("lnb_b", "Entrée B", Voie.RX, null, "F"), usb()),
            120, 64, setOf("datv:minitiouner")),

        // ---- around the computer
        Modele("satme-node", "SatMe-Node (boîtier déporté)", Genre.NOEUD, "noeud",
            listOf(usb("usb", "USB", multiple = true), rj45(), wifi()), 96, 64, setOf("noeud")),
        Modele("carte-son", "Carte son USB (PCM2902)", Genre.CARTE_SON, "carteson",
            listOf(audioB("audio", "Entrée / sortie"), usb()), 84, 40, setOf("son")),
        Modele("interface-esp32", "Boîtier d'interface ESP32-S3", Genre.INTERFACE, "esp32",
            listOf(cat("cat", "CAT"), ptt(), usb(), wifi()), 90, 52, setOf("esp32")),
        Modele("molette-usb", "Molette de volume USB (VFO)", Genre.INTERFACE, "molette", listOf(usb()), 64, 52, setOf("molette"),
            "Tournée, elle change la fréquence (onglet en cours) ; SatMe la lit sans rien lui envoyer."),
        Modele("pc", "PC SatMe-PC", Genre.ORDINATEUR, "pc",
            listOf(usb("usb", "USB", multiple = true), rj45("lan", "Réseau", multiple = true), wifi(multiple = true),
                Port("son", "Carte son", Sens.BIDIR, Signal.AUDIO, connecteur = "jack"), Port("serie", "Port série", Sens.BIDIR, Signal.COMMANDE, connecteur = "DB9", multiple = true),
                net("logiciel", "Réseau / Internet (logiciels)", multiple = true)),
            140, 104, setOf("pc")),
        Modele("telephone", "Téléphone SatMe", Genre.TELEPHONE, "telephone",
            listOf(usb("usb", "USB-C"), wifi()), 56, 100, setOf("telephone")),

        // ---- boîtes: programs and services beside the computer (display only)
        boite("boite-wavelog", "Wavelog (carnet en ligne)", "wavelog", "Le carnet en ligne : adresse et clé dans Réglages › Carnet. La page du montage n'y envoie rien."),
        boite("boite-qrz", "QRZ.com (recherche d'indicatifs)", "qrz", "Les fiches des indicatifs : identifiant dans Réglages."),
        boite("boite-psk", "PSK Reporter", "psk", "Les stations FT8 / FT4 entendues, envoyées seulement si tu l'as choisi (onglet FT8)."),
        boite("boite-chat", "Spectre large bande et chat DATV", "chat", "Le spectre large bande de QO-100 et le chat des stations DATV (onglet DATV)."),
        boite("boite-carnets", "Programmes de carnet (rigctld)", "carnets", "Les logiciels de carnet qui lisent la fréquence de SatMe (Réglages › Interfaces)."),
        boite("boite-lien", "Lien satellite (WebSocket)", "lien", "Les programmes et le téléphone qui suivent le satellite par le lien de SatMe."),
        boite("boite-telephone", "SatMe téléphone (appairé)", "telephone", "Le téléphone appairé à ce PC (QR code)."),
        boite("boite-noeud", "SatMe-Node (connexion)", "noeud", "Le boîtier déporté que SatMe joint par le réseau."),
        boite("boite-ecoute", "Navigateur « Écouter ici »", "ecoute", "Le son du récepteur envoyé à une page (bouton « Écouter ici »), et combien l'écoutent."),
        boite("boite-espace", "Météo spatiale (Internet)", "espace", "L'indice Kp, le vent solaire et les aurores, lus sur Internet (onglet Météo spatiale)."),
    )

    private val PAR_ID = MODELES.associateBy { it.id }

    /** The names under the drawings, when the catalogue's is too long for them. */
    private val COURTS = mapOf(
        "parabole-qo100" to "Parabole QO-100", "parabole-rx" to "Parabole TV", "yagi-bibande" to "Yagi 2 m / 70 cm",
        "antenne-interieure" to "Antenne intérieure", "colineaire" to "Colinéaire", "lnb-pll" to "LNB PLL",
        "dc-144" to "Conv. 10 GHz → 144", "dc-432" to "Conv. 10 GHz → 432", "dc-739" to "Conv. 10 GHz → 739",
        "up-144" to "Conv. 144 → 2,4 GHz", "up-432" to "Conv. 432 → 2,4 GHz", "pa-2400" to "Ampli 2,4 GHz",
        "bias-t" to "T de polarisation", "repartiteur" to "Répartiteur", "rotor-gs232" to "Rotor GS-232",
        "sdr-recepteur" to "Récepteur SDR", "minitiouner" to "MiniTiouner", "satme-node" to "SatMe-Node",
        "carte-son" to "Carte son USB", "interface-esp32" to "Interface ESP32-S3", "pc" to "SatMe-PC", "telephone" to "Téléphone",
        "bladerf" to "bladeRF 2.0", "filtre" to "Filtre", "poste" to "Poste",
        "antenne-gps" to "Antenne GPS", "lnb-twin" to "LNB Twin Green", "dxp-down-mk4" to "DX Patrol ↓ MK4", "dxp-down-mk3" to "DX Patrol ↓ MK3",
        "dxp-up-mk4" to "DX Patrol Up MK4", "dxp-up-mk3" to "DX Patrol Up MK3", "dxp-pa-20w" to "Ampli 2,4 GHz 20 W", "dxp-pa-15w" to "Ampli 2,4 GHz 15 W",
        "sequenceur" to "Séquenceur", "dxp-gpsdo" to "GPSDO 10 MHz", "leo-bodnar" to "GPSDO Leo Bodnar", "injecteur" to "Injecteur DC + réf.",
        "dc-block" to "DC block", "alim-138" to "Alim. 13,8 V", "molette-usb" to "Molette USB", "boite-wavelog" to "Wavelog", "boite-qrz" to "QRZ.com",
        "boite-psk" to "PSK Reporter", "boite-chat" to "Large bande · chat", "boite-carnets" to "Carnets (rigctld)", "boite-lien" to "Lien satellite",
        "boite-telephone" to "SatMe téléphone", "boite-noeud" to "SatMe-Node (réseau)", "boite-ecoute" to "Écouter ici",
        "boite-espace" to "Météo spatiale")

    /** [m]'s name under its drawing. */
    fun court(m: Montage.Modele): String = COURTS[m.id] ?: m.nom

    fun modele(id: String): Modele? = PAR_ID[id]

    /** A ready-made chain: a starting point the owner adapts. */
    data class Prereglage(val id: String, val nom: String, val quoi: String, val chaine: Montage.Chaine)

    val PREREGLAGES: List<Prereglage> by lazy {
        listOf(
            Prereglage("qo100-pluto", "QO-100 avec Pluto", "Parabole, LNB et T de polarisation en réception ; Pluto, amplificateur 2,4 GHz et patch en émission",
                Montage.Bati("QO-100 avec Pluto").run {
                    val d = ajoute("parabole-qo100"); val l = ajoute("lnb-pll"); val t = ajoute("bias-t")
                    val p = ajoute("pluto"); val a = ajoute("pa-2400"); val pc = ajoute("pc")
                    relie(d, "lnb", l, "entree"); relie(l, "sortie", t, "lnb"); relie(t, "rx", p, "rx")
                    relie(p, "tx", a, "entree"); relie(a, "sortie", d, "patch"); relie(p, "usb", pc, "usb")
                    chaine()
                }),
            Prereglage("qo100-ic9700", "QO-100 avec IC-9700 + convertisseurs", "Convertisseur 10 GHz → 432 MHz vers l'IC-9700 ; montée 144 → 2400 MHz et amplificateur",
                Montage.Bati("QO-100 avec IC-9700").run {
                    val d = ajoute("parabole-qo100"); val dc = ajoute("dc-432"); val r = ajoute("ic9700")
                    val up = ajoute("up-144"); val a = ajoute("pa-2400"); val pc = ajoute("pc")
                    relie(d, "lnb", dc, "entree"); relie(dc, "sortie", r, "ant432")
                    relie(r, "ant144", up, "entree"); relie(up, "sortie", a, "entree"); relie(a, "sortie", d, "patch")
                    relie(r, "usb", pc, "usb")
                    chaine()
                }),
            Prereglage("datv-minitiouner-bladerf", "DATV MiniTiouner + bladeRF en émission", "Le MiniTiouner reçoit (il alimente le LNB), le bladeRF émet en DVB-S2 par l'amplificateur",
                Montage.Bati("DATV MiniTiouner + bladeRF").run {
                    val d = ajoute("parabole-qo100"); val l = ajoute("lnb-pll"); val m = ajoute("minitiouner")
                    val b = ajoute("bladerf"); val a = ajoute("pa-2400"); val pc = ajoute("pc")
                    relie(d, "lnb", l, "entree"); relie(l, "sortie", m, "lnb_a")
                    relie(b, "tx1", a, "entree"); relie(a, "sortie", d, "patch")
                    relie(m, "usb", pc, "usb"); relie(b, "usb", pc, "usb")
                    chaine()
                }),
            Prereglage("fm-ic9700-yagi", "Satellites FM : IC-9700 + Yagi", "Deux Yagi 2 m et 70 cm sur un rotor GS-232, l'IC-9700 en CAT par USB",
                Montage.Bati("Satellites FM").run {
                    val y2 = ajoute("yagi-2m"); val y7 = ajoute("yagi-70cm"); val ro = ajoute("rotor-gs232")
                    val r = ajoute("ic9700"); val pc = ajoute("pc")
                    relie(y2, "ant", r, "ant144", Montage.Cable.COAX_N); relie(y7, "ant", r, "ant432")
                    relie(y2, "mat", ro, "moteur"); relie(y7, "mat", ro, "moteur")
                    relie(r, "usb", pc, "usb"); relie(ro, "serie", pc, "usb")
                    chaine()
                }),
            Prereglage("qo100-station", "Station QO-100 complète (GPSDO, convertisseurs)",
                "LNB Twin : MiniTiouner et convertisseur descendant vers le Pluto ; IC-9700 → Upconverter MK4 → ampli 20 W ; GPSDO 10 MHz des convertisseurs, 25 MHz du LNB",
                Montage.Bati("Station QO-100").run {
                    val d = ajoute("parabole-qo100"); val l = ajoute("lnb-twin", options = mapOf("ref" to "1"))
                    val m = ajoute("minitiouner"); val dc = ajoute("dxp-down-mk4"); val p = ajoute("pluto")
                    val r = ajoute("ic9700"); val up = ajoute("dxp-up-mk4"); val a = ajoute("dxp-pa-20w")
                    val g1 = ajoute("antenne-gps"); val gp = ajoute("dxp-gpsdo"); val g2 = ajoute("antenne-gps"); val lb = ajoute("leo-bodnar")
                    val al = ajoute("alim-138"); val pc = ajoute("pc")
                    relie(d, "lnb", l, "entree"); relie(l, "sortie1", m, "lnb_a"); relie(l, "sortie2", dc, "entree"); relie(dc, "sortie", p, "rx")
                    relie(r, "ant432", up, "entree"); relie(up, "sortie", a, "entree"); relie(a, "sortie", d, "patch")
                    relie(g1, "gps", gp, "gps"); relie(gp, "s1", up, "ref"); relie(gp, "s2", dc, "ref")
                    relie(g2, "gps", lb, "gps"); relie(lb, "s1", l, "ref25")
                    relie(al, "dc", a, "alim"); relie(al, "dc", up, "alim"); relie(al, "dc", dc, "alim"); relie(al, "dc", gp, "alim")
                    relie(m, "usb", pc, "usb"); relie(p, "usb", pc, "usb"); relie(r, "usb", pc, "usb"); relie(lb, "usb", pc, "usb")
                    chaine()
                }),
            Prereglage("noeud-deporte", "SatMe-Node déporté", "Le MiniTiouner près de la parabole sur un SatMe-Node, relié au PC par le réseau ; le téléphone en Wi-Fi",
                Montage.Bati("SatMe-Node déporté").run {
                    val d = ajoute("parabole-qo100"); val l = ajoute("lnb-pll"); val m = ajoute("minitiouner")
                    val n = ajoute("satme-node"); val pc = ajoute("pc"); val t = ajoute("telephone")
                    relie(d, "lnb", l, "entree"); relie(l, "sortie", m, "lnb_a"); relie(m, "usb", n, "usb")
                    relie(n, "lan", pc, "lan"); relie(pc, "wifi", t, "wifi")
                    chaine()
                }),
        )
    }
}
