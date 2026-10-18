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

        // ---- LNB and converters
        Modele("lnb-pll", "LNB PLL 10 GHz (sortie 739 MHz)", Genre.LNB, "lnb",
            listOf(rfE("entree", "Entrée 10 GHz", Voie.RX, 10489, "guide"), rfS("sortie", "Sortie FI", Voie.RX, 739, "F")), 80, 54, setOf("lnb")),
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

        // ---- around the cables
        Modele("bias-t", "T de polarisation (alimentation du LNB)", Genre.ALIMENTATION, "biast",
            listOf(rfB("lnb", "Vers le LNB (+ continu)", 739, "F", Voie.RX), rfS("rx", "Vers le récepteur", Voie.RX, 739, "SMA")), 70, 42),
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
            listOf(cat("cat", "CAT"), ptt(), usb(), wifi()), 90, 52),
        Modele("pc", "PC SatMe-PC", Genre.ORDINATEUR, "pc",
            listOf(usb("usb", "USB", multiple = true), rj45("lan", "Réseau", multiple = true), wifi(multiple = true),
                Port("son", "Carte son", Sens.BIDIR, Signal.AUDIO, connecteur = "jack"), Port("serie", "Port série", Sens.BIDIR, Signal.COMMANDE, connecteur = "DB9", multiple = true)),
            140, 104, setOf("pc")),
        Modele("telephone", "Téléphone SatMe", Genre.TELEPHONE, "telephone",
            listOf(usb("usb", "USB-C"), wifi()), 56, 100, setOf("telephone")),
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
        "bladerf" to "bladeRF 2.0", "filtre" to "Filtre", "poste" to "Poste")

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
