/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.demo

import fr.f4ioz.satcombo.plateforme.Stockage

/**
 * The station's modules: whole areas the owner switches on or off in
 * Réglages › Modules (dish reception, SSTV, the functions not about
 * satellites…). A module switched off leaves the menu: its tabs, the
 * workspace left empty, its settings cards and status-bar items; what it
 * would start is refused where the server can say so cheaply.
 *
 * Kept by the server as the set switched OFF ([CLE]), so a module added
 * later comes on by itself. The core (passes, CAT, rotor, Doppler, log,
 * journal, sound, settings) is always there.
 *
 * The functions not about satellites ([FonctionsNonSat]) are modules too:
 * their own key is kept in step ([pose]), the first start reading it
 * ([migre]) so nothing ticked or unticked changes.
 */
object ModulesStation {

    /**
     * A module: [onglets] its tabs, [masques] the page's elements that only
     * concern it (CSS selectors), [utilise] the modules it works through
     * (advice only), [avis] a hint shown beside it.
     */
    data class Module(
        val id: String,
        val nom: String,
        val quoi: String,
        val onglets: List<String> = emptyList(),
        val masques: List<String> = emptyList(),
        val utilise: List<String> = emptyList(),
        val avis: String = "",
    )

    /** A ready-made choice: the modules on (all others off). */
    data class Profil(val id: String, val nom: String, val quoi: String, val modules: Set<String>)

    /** What is always there, said under the list. */
    const val COEUR = "Suivi et passages (Suivi, Agenda, Frise, Carrés, Sked, Carte), poste, rotor et Doppler, Carnet, Journal, Son, Réglages, accès à distance, mises à jour"

    val MODULES: List<Module> = listOf(
        Module("sdr", "SDR", "Le récepteur SDR : écoute, spectre, cascade, balayage", listOf("sdr"), listOf("#sb-sdr", "#sb-bal", "#af-carte"),
            avis = "La base de METEOR, des sondes, des avions et des navires (ou un SatMe-Node)"),
        Module("qo100", "QO-100", "Le cockpit du transpondeur étroit géostationnaire", listOf("qo100"), listOf("#rq-carte"),
            avis = "Une parabole, un LNB, un poste ou le SDR"),
        Module("datv", "DATV", "Télévision numérique : réception MiniTiouner, émission DVB-S2", listOf("datv"), listOf("#sb-datv"),
            avis = "Un MiniTiouner ; l’émission par un ADALM-PLUTO ou un bladeRF"),
        Module("ft8", "FT8 / FT4", "Décodage, messages, radar", listOf("ft8")),
        Module("cw", "CW", "Le décodeur CW du Carnet et de QO-100", masques = listOf("#cw-carte")),
        Module("phonie", "Phonie → texte", "La voix entendue écrite", masques = listOf("#ph-carte", "#phr-carte")),
        Module("annonces", "Annonces vocales", "Passages, émission, appels dits à voix haute", masques = listOf("#ann-carte")),
        Module("aprs", "APRS / KISS", "Les trames APRS entendues, la carte, les trophées", listOf("aprs")),
        Module("tlm", "Télémesure", "Paquets 1200 à 9600 bit/s, images SSDV, FUNcube", listOf("tlm")),
        Module("sstv", "SSTV", "Réception, émission, images, planche, auto-pilote de l’Agenda", listOf("sstv", "planche"),
            listOf("#v-agenda .st-nav button[data-p=\"ag-p-sstv\"]"), avis = "La Planche en fait partie"),
        Module("orbites", "Orbites", "Le globe 3D, les empreintes, un passage animé", listOf("orbites")),
        Module("espace", "Météo spatiale", "Kp, flux solaire, éruptions, aurore", listOf("espace")),
        Module("activites", "Activités à venir", "SSTV, ARISS, SSDV, METEOR annoncés en ligne (Agenda)",
            masques = listOf("#v-agenda .st-nav button[data-p=\"ag-p-act\"]")),
        Module("meteor", "METEOR", "Les images LRPT des satellites METEOR", listOf("meteor"), utilise = listOf("sdr"), avis = "Utilise le SDR"),
        Module("sondes", "Sondes", "Les radiosondes, 400 à 406 MHz", listOf("sondes"), utilise = listOf("sdr"), avis = "Utilise le SDR ou un SatMe-Node"),
        Module("avions", "Avions", "ADS-B 1090 MHz, la diffusion par avions", listOf("avions"), utilise = listOf("sdr"), avis = "Une clé SDR ou l’ADALM-PLUTO"),
        Module("ais", "Navires", "AIS 162 MHz : navires et balises", listOf("ais"), utilise = listOf("sdr"), avis = "Une clé SDR"),
        Module("montage", "Montage", "La chaîne de la station : antennes, convertisseurs, câbles", listOf("montage"), listOf("#sb-mt")),
        Module("noeud", "SatMe-Node", "Le Raspberry Pi près de l’antenne", listOf("noeud"), listOf("#sb-noeud")),
        Module("telephone", "Téléphone", "SatMe sur le téléphone : réglages et contacts", masques = listOf("#carteTelephone")),
        Module("polar", "Polarisation", "Le commutateur de polarisation d’antenne (V, H, circulaires) : boîtier ESP32 en USB", masques = listOf("#pol-carte", "#polr-carte"),
            avis = "Un boîtier ESP32 et un WiMo ; ne fait rien tant qu’aucun port n’est choisi (l’ESP32 virtuel pour essayer)"),
    )

    private val PAR_ID = MODULES.associateBy { it.id }

    val PROFILS: List<Profil> = listOf(
        Profil("fm", "Satellites FM/SSB", "Les satellites en phonie, CW et numérique",
            setOf("ft8", "cw", "phonie", "annonces", "aprs", "tlm", "orbites", "espace", "activites", "telephone", "polar")),
        Profil("sstv", "SSTV ARISS", "Les images de l’ISS et des satellites SSTV (le SDR peut être décoché)",
            setOf("sstv", "sdr", "orbites", "activites", "annonces", "telephone", "polar")),
        Profil("qo100", "Parabole QO-100 / DATV", "Le transpondeur géostationnaire et la télévision numérique",
            setOf("sdr", "qo100", "datv", "ft8", "cw", "phonie", "annonces", "noeud", "montage", "telephone")),
        Profil("meteo", "Météo et non-sat", "Images METEOR, radiosondes, avions, navires, météo spatiale",
            setOf("sdr", "meteor", "sondes", "avions", "ais", "espace", "annonces", "telephone")),
        Profil("tout", "Tout", "Tous les modules", MODULES.map { it.id }.toSet()),
    )

    /** The modules switched off (a set of [MODULES]' ids); absent until [migre] or a first choice. */
    const val CLE = "pc_modules_coupes"

    fun module(id: String): Module? = PAR_ID[id]

    /**
     * The modules switched off: those stored, else (never chosen) the
     * functions not about satellites left unticked, so the menu stays as it was.
     */
    fun coupes(st: Stockage): Set<String> =
        st.getStringSet(CLE, null)?.filter { it in PAR_ID }?.toSet()
            ?: FonctionsNonSat.ONGLETS.keys.filter { it !in FonctionsNonSat.choisis(st).orEmpty() }.toSet()

    /** Decided once (after [FonctionsNonSat.migre]): everything on but the functions not about satellites left unticked. */
    fun migre(st: Stockage): Set<String> {
        if (st.getStringSet(CLE, null) != null) return coupes(st)
        return pose(st, coupes(st))
    }

    /** [coupes] stored, the functions not about satellites' own key kept in step. */
    fun pose(st: Stockage, coupes: Set<String>): Set<String> {
        val l = coupes.filter { it in PAR_ID }.toSet()
        st.edit().putStringSet(CLE, l).putStringSet(FonctionsNonSat.CLE, FonctionsNonSat.ONGLETS.keys.filter { it !in l }.toSet()).apply()
        return l
    }

    /** What switching [id] on or off would leave off (unchanged for an unknown id). */
    fun bascule(coupes: Set<String>, id: String, on: Boolean): Set<String> =
        if (id !in PAR_ID) coupes else if (on) coupes - id else coupes + id

    /** What [profil] leaves off; null for an unknown profile. */
    fun profil(profil: String): Set<String>? =
        PROFILS.firstOrNull { it.id == profil }?.let { p -> MODULES.map { it.id }.filter { it !in p.modules }.toSet() }

    /** The profile that leaves exactly [coupes] off, if any. */
    fun profilDe(coupes: Set<String>): String? = PROFILS.firstOrNull { profil(it.id) == coupes }?.id

    fun actif(st: Stockage, id: String): Boolean = id !in coupes(st)

    /** The module owning tab [onglet], if any (the core's tabs have none). */
    fun proprietaire(onglet: String): Module? = MODULES.firstOrNull { onglet in it.onglets }

    /** [tous] without the tabs of the modules off. */
    fun onglets(tous: List<String>, coupes: Set<String>): List<String> = tous.filter { o -> proprietaire(o)?.let { it.id in coupes } != true }

    /** The page's elements to hide: those of the modules off. */
    fun masques(coupes: Set<String>): List<String> = MODULES.filter { it.id in coupes }.flatMap { it.masques }
}
