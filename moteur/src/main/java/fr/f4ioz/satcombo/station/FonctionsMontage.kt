/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.station

import fr.f4ioz.satcombo.station.Montage.Chaine
import fr.f4ioz.satcombo.station.Montage.Genre
import fr.f4ioz.satcombo.station.Montage.Sens
import fr.f4ioz.satcombo.station.Montage.Signal
import org.json.JSONArray
import org.json.JSONObject

/**
 * SatMe's functions (receivers, decoders, transmitters, the sound) beside
 * the hardware of the « Montage » page: which function holds which board,
 * sound input or rig now, which one wants a board another holds, and the
 * whole chain one function needs, from the antenna to its screen.
 *
 * **Pure**: the PC (or the phone) fills an [Instantane] from the state its
 * tabs already read; nothing here starts, stops or opens anything.
 */
object FonctionsMontage {

    /** What SatMe does now, as its parts say it (false, empty or zero when a part is absent). */
    data class Instantane(
        // The SDR tab's receiver.
        val sdrPresent: Boolean = false, val sdrActif: Boolean = false, val sdrRecepteur: String = "rtl", val sdrErreur: String = "",
        val sdrMode: String = "", val sdrCielHz: Long = 0L, val sdrManuelleHz: Long = 0L, val sdrSuit: Boolean = false,
        val sdrPourSonde: Boolean = false, val sdrPourMeteor: Boolean = false, val sdrHautParleur: Boolean = false,
        /** QO-100 tuned on the SDR (the QO-100 cockpit). */
        val qo100ALaCle: Boolean = false,
        // DATV reception.
        val datvPresent: Boolean = false, val datvActif: Boolean = false, val datvRecepteur: String = "minitiouner",
        val datvSdrTenu: String = "", val datvVerrouille: Boolean = false, val datvSon: Boolean = false, val datvErreur: String = "",
        // Transmissions: the transmitter on the air ("pluto", "bladerf", "hackrf"), empty when none; the one chosen.
        val txDatv: String = "", val txSstv: String = "", val txIq: String = "",
        val txDatvChoisi: String = "", val txSstvChoisi: String = "",
        // The sound input that feeds the decoders.
        val sonPresent: Boolean = false, val sonActif: Boolean = false, val sonAppareil: String = "", val sonCarte: String = "",
        /** The SDR's demodulated sound feeds the decoders (it took the card's place). */
        val sonParSdr: Boolean = false, val cartes: List<String> = emptyList(), val sonErreur: String = "",
        // The decoders.
        val sstvActif: Boolean = false, val sstvSource: String = "auto", val sstvSourceActive: String = "",
        val ft8Actif: Boolean = false, val ft8Mode: String = "FT8", val cwActif: Boolean = false, val aprsActif: Boolean = false,
        val phoniePresent: Boolean = false, val phonieActif: Boolean = false, val phonieSource: String = "auto", val phonieSourceActive: String = "",
        val meteorPresent: Boolean = false,
        // The rig.
        val catConnecte: Boolean = false, val catModele: String = "", val catSimule: Boolean = false,
        /** A browser listens to the SDR's sound (« Écouter ici »). */
        val ecouteNavigateur: Boolean = false,
    ) {
        /** The transmission on the air, said for a refusal, or null. */
        fun emission(): String? = when {
            txDatv.isNotBlank() -> "émission DATV en cours (" + nomRessource("sdr:$txDatv") + ")"
            txSstv.isNotBlank() -> "émission SSTV en cours (" + nomRessource("sdr:$txSstv") + ")"
            txIq.isNotBlank() -> "calibration I/Q en cours (" + nomRessource("sdr:$txIq") + ")"
            else -> null
        }
        /** The function the SDR works for now, or null when it is off. */
        val sdrPour: String? get() = if (!sdrActif) null else when {
            sdrPourMeteor -> "meteor"; sdrPourSonde -> "sondes"; qo100ALaCle -> "qo100"; else -> "sdr"
        }
    }

    // ------------------------------------------------------------------ the functions

    enum class Sens2 { RX, TX, AUCUN }
    /** Where a function's sound goes: it makes some ([EMET]), takes some ([RECOIT]), both, or none. */
    enum class Son { EMET, RECOIT, AUCUN }

    /** Where the page goes to set a function: a tab, a sub-tab (or a Réglages category), an element to show. */
    data class Raccourci(val onglet: String, val sous: String = "", val ancre: String = "") {
        fun json(): JSONObject = JSONObject().put("onglet", onglet).put("sous", sous).put("ancre", ancre)
    }

    /** One of SatMe's functions; [colonne] its place in the software part of the drawing. */
    data class Fonction(val id: String, val nom: String, val sens: Sens2, val son: Son, val raccourci: Raccourci, val colonne: Int, val quoi: String)

    val FONCTIONS: List<Fonction> = listOf(
        Fonction("sdr", "Récepteur SDR", Sens2.RX, Son.EMET, Raccourci("sdr", ancre = "r-recepteur"), 0, "démodule l'IQ de la carte SDR et en fait du son"),
        Fonction("qo100", "Cockpit QO-100", Sens2.RX, Son.EMET, Raccourci("qo100"), 0, "le transpondeur étroit sur la carte SDR"),
        Fonction("meteor", "METEOR (images)", Sens2.RX, Son.AUCUN, Raccourci("meteor"), 0, "l'IQ du passage vers le décodeur d'images"),
        Fonction("sondes", "Radiosondes", Sens2.RX, Son.AUCUN, Raccourci("sondes"), 0, "les trames des sondes sur la carte SDR"),
        Fonction("datv-rx", "DATV réception", Sens2.RX, Son.EMET, Raccourci("datv", ancre = "d-recepteur"), 0, "l'image et le son DVB-S/S2"),
        Fonction("poste", "Poste (CAT)", Sens2.AUCUN, Son.EMET, Raccourci("reglages", "materiel"), 0, "la fréquence du poste pilotée, son son vers la carte son"),
        Fonction("son", "Carte son (onglet Son)", Sens2.RX, Son.EMET, Raccourci("son", ancre = "s-carte"), 1, "l'entrée son qui nourrit les décodeurs"),
        Fonction("sstv-rx", "SSTV réception", Sens2.RX, Son.RECOIT, Raccourci("sstv", "sx-p-rx", "sstv-source"), 2, "les images SSTV entendues"),
        Fonction("ft8", "FT8 / FT4", Sens2.RX, Son.RECOIT, Raccourci("ft8"), 2, "les messages FT8 et FT4"),
        Fonction("cw", "Décodeur CW", Sens2.RX, Son.RECOIT, Raccourci("carnet", ancre = "cw-on"), 2, "la télégraphie écrite"),
        Fonction("aprs", "APRS (réception)", Sens2.RX, Son.RECOIT, Raccourci("aprs"), 2, "les trames AX.25 à 1200 bauds"),
        Fonction("phonie", "Phonie → texte", Sens2.RX, Son.RECOIT, Raccourci("reglages", "phonie", "ph-source"), 2, "la voix écrite"),
        Fonction("ecoute", "Haut-parleur du PC", Sens2.AUCUN, Son.RECOIT, Raccourci("sdr"), 3, "le son joué sur ce PC"),
        Fonction("navigateur", "Écouter ici (navigateur)", Sens2.AUCUN, Son.RECOIT, Raccourci("sdr"), 3, "le son envoyé à la page"),
        Fonction("datv-tx", "DATV émission", Sens2.TX, Son.AUCUN, Raccourci("datv"), 0, "DVB-S2 par la carte SDR (onglet DATV)"),
        Fonction("sstv-tx", "SSTV émission", Sens2.TX, Son.AUCUN, Raccourci("sstv", "sx-p-tx"), 0, "une image en FM par la carte SDR"),
        Fonction("iq", "Calibration I/Q", Sens2.TX, Son.AUCUN, Raccourci("reglages", "emission"), 0, "la correction de l'émetteur"),
    )
    private val PAR_ID = FONCTIONS.associateBy { it.id }
    fun fonction(id: String): Fonction? = PAR_ID[id]

    /** The functions a « Fonction » view may show (the chains). */
    val VUES = listOf("sstv-rx", "ft8", "cw", "aprs", "phonie", "sdr", "qo100", "datv-rx", "meteor", "sondes", "ecoute", "datv-tx", "sstv-tx")

    /** Whether function [id] runs now. */
    fun enMarche(id: String, i: Instantane): Boolean = when (id) {
        "sdr", "qo100", "meteor", "sondes" -> i.sdrPour == id
        "datv-rx" -> i.datvActif
        "poste" -> i.catConnecte
        "son" -> i.sonActif && !i.sonParSdr
        "sstv-rx" -> i.sstvActif && (if (sstvParEntree(i)) i.sonActif else i.sdrActif)
        "ft8" -> i.ft8Actif
        "cw" -> i.cwActif && i.sonActif
        "aprs" -> i.aprsActif && i.sonActif
        "phonie" -> i.phonieActif
        "ecoute" -> (i.sdrActif && i.sdrHautParleur && !i.sdrPourMeteor && !i.sdrPourSonde) || (i.datvActif && i.datvSon)
        "navigateur" -> i.ecouteNavigateur
        "datv-tx" -> i.txDatv.isNotBlank()
        "sstv-tx" -> i.txSstv.isNotBlank()
        "iq" -> i.txIq.isNotBlank()
        else -> false
    }

    /** Whether function [id] exists on this machine (its part is there). */
    fun presente(id: String, i: Instantane): Boolean = when (id) {
        "sdr", "qo100", "sondes", "ecoute", "navigateur" -> i.sdrPresent
        "meteor" -> i.sdrPresent && i.meteorPresent
        "datv-rx" -> i.datvPresent
        "son", "sstv-rx", "ft8", "cw", "aprs" -> i.sonPresent
        "phonie" -> i.phoniePresent
        else -> true
    }

    // ------------------------------------------------------------------ the resources and who holds them

    /** The resources functions take: the SDR boards, the DATV receiver, the decoders' sound input, the PC's speaker, the rig. */
    val RESSOURCES = listOf("sdr:rtl", "sdr:pluto", "sdr:bladerf", "sdr:hackrf", "datv:minitiouner", "son:entree", "son:sortie", "cat")

    fun nomRessource(r: String): String = when (r) {
        "sdr:rtl" -> "clé RTL-SDR"; "sdr:pluto" -> "ADALM-PLUTO"; "sdr:bladerf" -> "bladeRF"; "sdr:hackrf" -> "HackRF"
        "datv:minitiouner" -> "MiniTiouner"; "son:entree" -> "entrée son des décodeurs"; "son:sortie" -> "haut-parleur du PC"; "cat" -> "poste (CAT)"
        else -> r.substringAfter(':')
    }

    /** The resource behind a hardware role of the catalogue ("tx:pluto" → "sdr:pluto"), or null. */
    fun ressourceDuRole(role: String): String? {
        val (k, v) = role.split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        return when {
            k in setOf("sdr", "tx", "iq", "datv") && v in setOf("rtl", "pluto", "bladerf", "hackrf") -> "sdr:$v"
            role == "datv:minitiouner" -> "datv:minitiouner"
            role == "cat" -> "cat"
            role == "son" -> "son:entree"
            else -> null
        }
    }

    /** What a holder does with it. */
    enum class Role(val dit: String) { RECEPTION("réception"), EMISSION("émission"), ENTREE_SON("entrée son"), SORTIE_SON("sortie son"), COMMANDE("commande") }

    /** [ressource] held by function [detenteur] for [role]; [partage] the other functions using it with it; [detail] e.g. the card's name. */
    data class Possession(val ressource: String, val detenteur: String, val role: Role, val partage: List<String> = emptyList(), val detail: String = "") {
        /** « ADALM-PLUTO → occupé par DATV réception (réception) ». */
        fun dit(): String {
            fun n(id: String) = fonction(id)?.nom ?: id
            return when (role) {
                Role.COMMANDE -> "Poste" + (if (detail.isNotBlank()) " $detail" else "") + " → piloté en CAT"
                Role.SORTIE_SON -> nomRessource(ressource) + " → joue " + (listOf(detenteur) + partage).joinToString(", ") { n(it) }
                Role.ENTREE_SON -> nomRessource(ressource) + (if (detail.isNotBlank()) " (" + court(detail) + ")" else "") + " → " + n(detenteur) +
                    (if (partage.isNotEmpty()) ", écoutée par " + partage.joinToString(", ") { n(it) } else ", personne ne l'écoute")
                else -> nomRessource(ressource) + " → occupé par " + n(detenteur) + " (" + role.dit + ")"
            }
        }
    }

    /** The decoders listening to the sound input now. */
    private fun ecoutentEntree(i: Instantane): List<String> = buildList {
        if (i.sstvActif && sstvParEntree(i)) add("sstv-rx")
        if (i.ft8Actif) add("ft8")
        if (i.cwActif) add("cw")
        if (i.aprsActif) add("aprs")
        if (i.phonieActif && i.phonieSource in setOf("auto", "carte") && i.phonieSourceActive != "datv" && i.phonieSourceActive != "sdr") add("phonie")
    }

    /** SSTV hears the sound input (not the SDR's own sound path). */
    private fun sstvParEntree(i: Instantane) = when (i.sstvSource) {
        "carte" -> true; "sdr" -> false
        else -> i.sstvSourceActive != "sdr" && !(i.sdrActif && i.sdrPour in setOf("sdr", "qo100"))
    }

    /** Who holds what now. */
    fun possessions(i: Instantane): List<Possession> {
        val l = ArrayList<Possession>()
        fun board(k: String) = "sdr:" + k.ifBlank { "pluto" }
        if (i.txDatv.isNotBlank()) l += Possession(board(i.txDatv), "datv-tx", Role.EMISSION)
        if (i.txSstv.isNotBlank()) l += Possession(board(i.txSstv), "sstv-tx", Role.EMISSION)
        if (i.txIq.isNotBlank()) l += Possession(board(i.txIq), "iq", Role.EMISSION)
        if (i.datvActif) {
            val r = if (i.datvRecepteur == "minitiouner") "datv:minitiouner" else board(i.datvSdrTenu.ifBlank { i.datvRecepteur })
            if (l.none { it.ressource == r }) l += Possession(r, "datv-rx", Role.RECEPTION)
        }
        i.sdrPour?.let { f ->
            val r = board(i.sdrRecepteur)
            if (l.none { it.ressource == r }) l += Possession(r, f, Role.RECEPTION)
        }
        if (i.sonActif) {
            val ecoutent = ecoutentEntree(i)
            l += if (i.sonParSdr) Possession("son:entree", i.sdrPour ?: "sdr", Role.ENTREE_SON, ecoutent, "son démodulé de la carte SDR")
                else Possession("son:entree", "son", Role.ENTREE_SON, ecoutent, i.sonAppareil)
        }
        val hp = buildList {
            if (i.sdrActif && i.sdrHautParleur && !i.sdrPourMeteor && !i.sdrPourSonde) add(i.sdrPour ?: "sdr")
            if (i.datvActif && i.datvSon) add("datv-rx")
        }
        if (hp.isNotEmpty()) l += Possession("son:sortie", hp.first(), Role.SORTIE_SON, hp.drop(1))
        if (i.catConnecte) l += Possession("cat", "poste", Role.COMMANDE, detail = i.catModele + if (i.catSimule) ", simulé" else "")
        return l
    }

    /** A safe receive gesture: one setting of [ProfilsFonction.CLES] to [valeur], with its button's text. */
    data class Action(val texte: String, val cle: String, val valeur: String) {
        fun json(): JSONObject = JSONObject().put("texte", texte).put("cle", cle).put("valeur", valeur)
    }

    /** A function that wants what another holds, or lacks what it needs: [raison] in words, what to do about it. */
    data class Conflit(val fonction: String, val ressource: String, val raison: String, val grave: Boolean,
                       val actions: List<Action> = emptyList(), val raccourci: Raccourci? = null, val occupe: Boolean = false) {
        fun json(): JSONObject = JSONObject().put("fonction", fonction).put("ressource", ressource).put("raison", raison).put("grave", grave).put("occupe", occupe)
            .put("actions", JSONArray(actions.map { it.json() })).put("raccourci", raccourci?.json() ?: JSONObject.NULL)
    }

    /** What frees [r] held by [p]: a stop SatMe may do from here (receive only), else nothing (its tab). */
    private fun libere(p: Possession, i: Instantane): List<Action> = when (p.detenteur) {
        "sdr", "qo100", "meteor", "sondes" -> listOf(Action("Arrêter " + (fonction(p.detenteur)?.nom ?: "le récepteur SDR"), "sdr.actif", "0"))
        // The MiniTiouner feeds the LNB: its stop stays in the DATV tab.
        "datv-rx" -> if (i.datvRecepteur != "minitiouner") listOf(Action("Arrêter la réception DATV", "datv.actif", "0")) else emptyList()
        else -> emptyList()
    }

    /** The conflicts now: a function wanting a board, the sound input or a source another holds or nobody gives. */
    fun conflits(i: Instantane, poss: List<Possession> = possessions(i)): List<Conflit> {
        val l = ArrayList<Conflit>()
        fun tient(r: String) = poss.firstOrNull { it.ressource == r }
        fun nomF(id: String) = fonction(id)?.nom ?: id
        fun occupe(f: String, r: String, quoi: String, actionsEnPlus: List<Action> = emptyList()) {
            val p = tient(r) ?: return
            if (p.detenteur == f) return
            val tx = p.role == Role.EMISSION
            l += Conflit(f, r, nomRessource(r) + " occupé par " + nomF(p.detenteur) + " (" + p.role.dit + ") : " + quoi, true,
                (if (tx) emptyList() else libere(p, i)) + actionsEnPlus, fonction(p.detenteur)?.raccourci, occupe = true)
        }
        // The SDR tab set on a board another function holds.
        if (i.sdrPresent && !i.sdrActif) occupe("sdr", "sdr:" + i.sdrRecepteur, "le récepteur SDR (réglé dessus) ne peut pas démarrer")
        else if (i.sdrPresent && i.sdrErreur.isNotBlank()) l += Conflit("sdr", "sdr:" + i.sdrRecepteur, "Récepteur SDR : " + i.sdrErreur, true, raccourci = fonction("sdr")!!.raccourci)
        // DATV on a board.
        if (i.datvPresent && !i.datvActif && i.datvRecepteur != "minitiouner")
            occupe("datv-rx", "sdr:" + i.datvRecepteur, "la réception DATV (réglée dessus) ne peut pas démarrer")
        // The transmitters chosen, held by a reception (shown only: nothing is sent from here).
        if (i.txDatv.isBlank() && i.txDatvChoisi.isNotBlank()) tient("sdr:" + i.txDatvChoisi)?.takeIf { it.role != Role.EMISSION }?.let {
            l += Conflit("datv-tx", it.ressource, nomRessource(it.ressource) + " occupé par " + nomF(it.detenteur) + " : l'émission DATV (réglée dessus) devra attendre", false,
                libere(it, i), fonction("datv-tx")!!.raccourci)
        }
        if (i.txSstv.isBlank() && i.txSstvChoisi.isNotBlank()) tient("sdr:" + i.txSstvChoisi)?.takeIf { it.role != Role.EMISSION }?.let {
            l += Conflit("sstv-tx", it.ressource, nomRessource(it.ressource) + " occupé par " + nomF(it.detenteur) + " : l'émission SSTV (réglée dessus) devra attendre", false,
                libere(it, i), fonction("sstv-tx")!!.raccourci)
        }
        // The decoders and their sound.
        val carte = i.sonCarte.ifBlank { i.cartes.firstOrNull().orEmpty() }
        fun reprendCarte() = if (carte.isNotBlank()) listOf(Action("Reprendre la carte « " + court(carte) + " »", "son.entree", carte)) else emptyList()
        if (i.sstvActif) when {
            i.sstvSource == "carte" && i.sonParSdr -> l += Conflit("sstv-rx", "son:entree",
                "l'entrée son est prise par la carte SDR (son démodulé) : SSTV, réglé sur « carte son », entend la carte SDR", true,
                reprendCarte() + Action("SSTV : source carte SDR", "sstv.source", "sdr"), fonction("sstv-rx")!!.raccourci, occupe = true)
            i.sstvSource == "carte" && !i.sonActif -> l += Conflit("sstv-rx", "son:entree", "la carte son n'est pas démarrée : SSTV n'entend rien", false,
                reprendCarte(), fonction("son")!!.raccourci)
            i.sstvSource == "sdr" && !i.sdrActif -> l += Conflit("sstv-rx", "sdr:" + i.sdrRecepteur, "SSTV écoute la carte SDR, qui est arrêtée", false,
                listOf(Action("Démarrer le récepteur SDR", "sdr.actif", "1"), Action("SSTV : source carte son", "sstv.source", "carte")), fonction("sdr")!!.raccourci)
        }
        // FT8 and CW started by hand and left without sound (APRS and SSTV, on by default, simply wait).
        for (d in listOf("ft8", "cw")) if (enMarcheVoulu(d, i) && !i.sonActif)
            l += Conflit(d, "son:entree", nomF(d) + " attend du son : ni carte son ni carte SDR sur l'entrée des décodeurs", false, reprendCarte(), fonction("son")!!.raccourci)
        if (i.phonieActif) when {
            i.phonieSource == "carte" && i.sonParSdr -> l += Conflit("phonie", "son:entree",
                "l'entrée son est prise par la carte SDR : « Phonie → texte », réglé sur la carte son, entend la carte SDR", true,
                reprendCarte() + Action("Phonie : source carte SDR", "phonie.source", "sdr"), fonction("phonie")!!.raccourci, occupe = true)
            i.phonieSource == "sdr" && !i.sdrActif -> l += Conflit("phonie", "sdr:" + i.sdrRecepteur, "« Phonie → texte » écoute la carte SDR, qui est arrêtée", true,
                listOf(Action("Démarrer le récepteur SDR", "sdr.actif", "1")), fonction("sdr")!!.raccourci)
            i.phonieSource == "datv" && !i.datvActif -> l += Conflit("phonie", "datv:" + i.datvRecepteur, "« Phonie → texte » écoute la DATV, qui est arrêtée", false,
                raccourci = fonction("datv-rx")!!.raccourci)
        }
        return l
    }

    private fun enMarcheVoulu(id: String, i: Instantane) = when (id) { "ft8" -> i.ft8Actif; "cw" -> i.cwActif; else -> false }

    /** A card's name, short (Java's names carry the system's device in brackets). */
    fun court(nom: String): String = nom.substringBefore(" [").trim().ifBlank { nom }.take(40)

    // ------------------------------------------------------------------ the graph drawn

    enum class Genre2 { MATERIEL, FONCTION, SON }
    /** A cable's or a data path's kind: radio, IQ samples (USB, network), sound, commands, network. */
    enum class Type { RF, IQ, AUDIO, CAT, RESEAU }
    /** A node's or a link's state: running, in place but at rest, missing, held by another, in error. */
    enum class Statut { OK, PRET, MANQUE, OCCUPE, ERREUR }

    /**
     * A node: hardware ([element] its id in the chain when it is there),
     * a function or a sound end. [badges]: "materiel"/"fonction", "rx",
     * "tx", "son-emet", "son-recoit".
     */
    data class Noeud(val id: String, val nom: String, val genre: Genre2, val colonne: Int, val badges: List<String>, val statut: Statut,
                     val detail: String = "", val element: String? = null, val modele: String? = null,
                     val raccourci: Raccourci? = null, val actions: List<Action> = emptyList(), val fonction: String? = null) {
        fun json(): JSONObject = JSONObject().put("id", id).put("nom", nom).put("genre", genre.name.lowercase()).put("col", colonne)
            .put("badges", JSONArray(badges)).put("statut", statut.name.lowercase()).put("detail", detail)
            .put("element", element ?: JSONObject.NULL).put("modele", modele ?: JSONObject.NULL).put("fonction", fonction ?: JSONObject.NULL)
            .put("raccourci", raccourci?.json() ?: JSONObject.NULL).put("actions", JSONArray(actions.map { it.json() }))
    }

    /** A link from [de] to [vers] (the way the signal or the sound goes). */
    data class Arc(val de: String, val vers: String, val type: Type, val statut: Statut, val texte: String = "") {
        fun json(): JSONObject = JSONObject().put("de", de).put("vers", vers).put("type", type.name.lowercase()).put("statut", statut.name.lowercase()).put("texte", texte)
    }

    /** One thing to do for a chain to work: in words, a safe gesture, where to set it. */
    data class Besoin(val texte: String, val action: Action? = null, val raccourci: Raccourci? = null) {
        fun json(): JSONObject = JSONObject().put("texte", texte).put("action", action?.json() ?: JSONObject.NULL).put("raccourci", raccourci?.json() ?: JSONObject.NULL)
    }

    data class Graphe(val noeuds: List<Noeud>, val arcs: List<Arc>, val besoins: List<Besoin> = emptyList(), val titre: String = "", val resume: String = "") {
        fun noeud(id: String) = noeuds.firstOrNull { it.id == id }
        fun json(): JSONObject = JSONObject().put("titre", titre).put("resume", resume).put("noeuds", JSONArray(noeuds.map { it.json() }))
            .put("arcs", JSONArray(arcs.map { it.json() })).put("besoins", JSONArray(besoins.map { it.json() }))
    }

    private fun badgesDe(f: Fonction, i: Instantane): List<String> = buildList {
        add("fonction")
        when (f.sens) { Sens2.RX -> add("rx"); Sens2.TX -> add("tx"); else -> {} }
        if (f.son == Son.EMET) add("son-emet")
        if (f.son == Son.RECOIT) add("son-recoit")
        if (f.id == "datv-rx" && !i.datvSon) remove("son-emet")
    }

    /** The detail line of a function's node: what it runs on, what it holds. */
    private fun detailDe(id: String, i: Instantane, poss: List<Possession>): String {
        val tient = poss.filter { it.detenteur == id }.joinToString(", ") { nomRessource(it.ressource) + if (it.detail.isNotBlank() && it.ressource == "son:entree") " : " + court(it.detail) else "" }
        val base = when (id) {
            "sdr", "qo100", "meteor", "sondes" -> nomRessource("sdr:" + i.sdrRecepteur) + (if (id == "sdr" && i.sdrMode.isNotBlank()) " · " + i.sdrMode else "") +
                (if (i.sdrCielHz > 0 && i.sdrPour == id) " · " + String.format(java.util.Locale.FRANCE, "%.3f", i.sdrCielHz / 1e6) + " MHz" else "")
            "datv-rx" -> nomRessource(if (i.datvRecepteur == "minitiouner") "datv:minitiouner" else "sdr:" + i.datvRecepteur) + if (i.datvActif) (if (i.datvVerrouille) " · accrochée" else " · recherche") else ""
            "poste" -> i.catModele.ifBlank { "pas de poste en CAT" }
            "son" -> if (i.sonParSdr) "prise par la carte SDR" else court(i.sonAppareil.ifBlank { i.sonCarte }).ifBlank { "aucune carte choisie" }
            "sstv-rx" -> "source " + mapOf("auto" to "auto", "carte" to "carte son", "sdr" to "carte SDR")[i.sstvSource]
            "ft8" -> i.ft8Mode
            "phonie" -> "source " + i.phonieSource
            "datv-tx" -> nomRessource("sdr:" + i.txDatv.ifBlank { i.txDatvChoisi.ifBlank { "pluto" } })
            "sstv-tx" -> nomRessource("sdr:" + i.txSstv.ifBlank { i.txSstvChoisi.ifBlank { "pluto" } })
            "iq" -> if (i.txIq.isNotBlank()) nomRessource("sdr:" + i.txIq) else ""
            else -> ""
        }
        return if (tient.isNotBlank() && id !in setOf("son")) base + (if (base.isNotBlank()) " — " else "") + "tient " + tient else base
    }

    /** The default safe gesture on a function's node (start or stop a reception), when there is one. */
    private fun actionsDe(id: String, i: Instantane): List<Action> = when (id) {
        "sdr" -> if (i.sdrActif) listOf(Action("Arrêter", "sdr.actif", "0")) else listOf(Action("Démarrer", "sdr.actif", "1"))
        "qo100", "meteor", "sondes" -> if (i.sdrPour == id) listOf(Action("Arrêter", "sdr.actif", "0")) else emptyList()
        "datv-rx" -> if (i.datvActif && i.datvRecepteur != "minitiouner") listOf(Action("Arrêter", "datv.actif", "0")) else emptyList()
        "sstv-rx" -> listOf(if (i.sstvActif) Action("Couper", "sstv.actif", "0") else Action("Activer", "sstv.actif", "1"))
        "ft8" -> if (i.ft8Actif) listOf(Action("Arrêter", "ft8.actif", "0")) else if (i.sonActif) listOf(Action("Démarrer", "ft8.actif", "1")) else emptyList()
        "cw" -> listOf(if (i.cwActif) Action("Couper", "cw.actif", "0") else Action("Activer", "cw.actif", "1"))
        "aprs" -> listOf(if (i.aprsActif) Action("Couper", "aprs.actif", "0") else Action("Activer", "aprs.actif", "1"))
        "son" -> if (!i.sonParSdr && i.sonActif) listOf(Action("Arrêter la carte", "son.entree", ""))
            else (i.sonCarte.ifBlank { i.cartes.firstOrNull().orEmpty() }).takeIf { it.isNotBlank() }?.let { listOf(Action("Démarrer « " + court(it) + " »", "son.entree", it)) } ?: emptyList()
        else -> emptyList()
    }

    private fun statutDe(id: String, i: Instantane, cf: List<Conflit>): Statut = when {
        cf.any { it.fonction == id && it.grave } -> if (cf.first { it.fonction == id && it.grave }.occupe) Statut.OCCUPE else Statut.MANQUE
        id == "sdr" && i.sdrErreur.isNotBlank() -> Statut.ERREUR
        id == "datv-rx" && i.datvErreur.isNotBlank() -> Statut.ERREUR
        enMarche(id, i) -> Statut.OK
        else -> Statut.PRET
    }

    private fun noeudFonction(id: String, i: Instantane, poss: List<Possession>, cf: List<Conflit>, colonne: Int? = null, nom: String? = null): Noeud {
        val f = fonction(id)!!
        return Noeud("f:$id", nom ?: f.nom, Genre2.FONCTION, colonne ?: f.colonne, badgesDe(f, i), statutDe(id, i, cf), detailDe(id, i, poss),
            raccourci = f.raccourci, actions = actionsDe(id, i), fonction = id)
    }

    /**
     * The software half of the full drawing: every function present (the
     * idle ones dimmed by the page), the sound ends, the links that carry
     * something now. The hardware half is [Montage]'s drawing; « pc » stands
     * for the computer there.
     */
    fun logiciel(i: Instantane, poss: List<Possession> = possessions(i), cf: List<Conflit> = conflits(i, poss)): Graphe {
        val n = ArrayList<Noeud>(); val a = ArrayList<Arc>()
        val discrets = setOf("qo100", "meteor", "sondes", "iq")
        for (f in FONCTIONS) if (presente(f.id, i) && f.id !in setOf("ecoute", "navigateur") &&
            (f.id !in discrets || enMarche(f.id, i) || cf.any { it.fonction == f.id })) n += noeudFonction(f.id, i, poss, cf)
        // The sound ends: the SDR's sound, the cards, the PC's speaker, the browser.
        val sdrSon = i.sdrActif && !i.sdrPourMeteor && !i.sdrPourSonde
        if (i.sdrPresent) n += Noeud("s:sdr", "Son démodulé (carte SDR)", Genre2.SON, 1, listOf("fonction", "son-emet"), if (sdrSon) Statut.OK else Statut.PRET,
            if (i.sonParSdr) "nourrit les décodeurs" else "", raccourci = Raccourci("sdr"))
        if (i.datvPresent) n += Noeud("s:datv", "Son DATV", Genre2.SON, 1, listOf("fonction", "son-emet"), if (i.datvActif && i.datvSon) Statut.OK else Statut.PRET, "", raccourci = Raccourci("datv"))
        n += Noeud("s:hp", "Haut-parleur du PC", Genre2.SON, 3, listOf("materiel", "son-recoit"), if (enMarche("ecoute", i)) Statut.OK else Statut.PRET, "", raccourci = Raccourci("sdr"))
        if (i.sdrPresent) n += Noeud("s:nav", "Écouter ici (navigateur)", Genre2.SON, 3, listOf("fonction", "son-recoit"), if (i.ecouteNavigateur) Statut.OK else Statut.PRET, "", raccourci = Raccourci("sdr"))
        // Each card Java knows: the one feeding first, at most four.
        val cartes = (listOfNotNull(i.sonAppareil.takeIf { it.isNotBlank() && !i.sonParSdr }, i.sonCarte.takeIf { it.isNotBlank() }) + i.cartes).distinct().take(3)
        val courts = cartes.map { court(it) }
        cartes.forEachIndexed { k, c ->
            val nomCarte = if (courts.count { it == courts[k] } > 1) c.take(40) else courts[k]
            val vive = i.sonActif && !i.sonParSdr && c == i.sonAppareil
            n += Noeud("c:$k", nomCarte, Genre2.SON, 0, listOf("materiel", "son-emet"), if (vive) Statut.OK else Statut.PRET,
                if (vive) "entrée des décodeurs" else if (c == i.sonCarte) "carte choisie (onglet Son)" else "", raccourci = Raccourci("son", ancre = "s-carte"),
                actions = if (vive) emptyList() else listOf(Action("Prendre cette carte", "son.entree", c)))
            if (vive) a += Arc("pc", "c:$k", Type.AUDIO, Statut.OK, "USB")
        }
        if (i.cartes.size > cartes.size) n += Noeud("c:plus", "+" + (i.cartes.size - cartes.size) + " autres cartes son", Genre2.SON, 0, listOf("materiel"), Statut.PRET, "",
            raccourci = Raccourci("son", ancre = "s-carte"))
        // Hardware to the functions holding it.
        for (p in poss) {
            if (p.ressource.startsWith("sdr:") || p.ressource == "datv:minitiouner" || p.ressource == "cat") {
                val t = if (p.ressource == "cat") Type.CAT else Type.IQ
                a += Arc("pc", "f:" + p.detenteur, t, Statut.OK, nomRessource(p.ressource))
            }
        }
        // The sound: who makes it, who takes it.
        if (sdrSon) a += Arc("f:" + (i.sdrPour ?: "sdr"), "s:sdr", Type.AUDIO, Statut.OK)
        if (i.datvActif && i.datvSon) a += Arc("f:datv-rx", "s:datv", Type.AUDIO, Statut.OK)
        val entree = if (i.sonActif) (if (i.sonParSdr) "s:sdr" else cartes.indexOf(i.sonAppareil).takeIf { it >= 0 }?.let { "c:$it" }) else null
        if (entree != null && !i.sonParSdr) a += Arc(entree, "f:son", Type.AUDIO, Statut.OK)
        val source = if (i.sonParSdr) "s:sdr" else "f:son"
        if (i.sonActif) for (d in ecoutentEntree(i)) a += Arc(source, "f:$d", Type.AUDIO, Statut.OK)
        if (i.sstvActif && !sstvParEntree(i) && sdrSon) a += Arc("s:sdr", "f:sstv-rx", Type.AUDIO, Statut.OK)
        if (i.phonieActif && (i.phonieSource == "sdr" || i.phonieSourceActive == "sdr") && sdrSon && !i.sonParSdr) a += Arc("s:sdr", "f:phonie", Type.AUDIO, Statut.OK)
        if (i.phonieActif && (i.phonieSource == "datv" || i.phonieSourceActive == "datv") && i.datvActif) a += Arc("s:datv", "f:phonie", Type.AUDIO, Statut.OK)
        if (sdrSon && i.sdrHautParleur) a += Arc("s:sdr", "s:hp", Type.AUDIO, Statut.OK)
        if (i.datvActif && i.datvSon) a += Arc("s:datv", "s:hp", Type.AUDIO, Statut.OK)
        if (i.ecouteNavigateur) a += Arc("s:sdr", "s:nav", Type.RESEAU, Statut.OK)
        val ids = n.map { it.id }.toSet() + "pc"
        return Graphe(n, a.filter { it.de in ids && it.vers in ids }.distinct())
    }

    // ------------------------------------------------------------------ one function's whole chain

    /** The elements of [c] playing [role] (catalogue roles: "sdr:pluto", "cat", "datv:minitiouner"…). */
    fun elementsDuRole(c: Chaine, role: String): List<Montage.Element> = c.elements.filter { e -> Montage.modele(e)?.roles?.contains(role) == true }

    /** A path found in the chain: its elements in order from the far end to the start, and its cables. */
    private data class Chemin(val elements: List<String>, val liens: List<String>)

    /**
     * The radio path from [depart] to the antenna: back along the receiving
     * ports ([tx] false), or on along the transmitting ones ([tx] true).
     * The far end first.
     */
    private fun radio(c: Chaine, depart: String, tx: Boolean): Chemin {
        val prof = LinkedHashMap<String, Int>(); val liens = LinkedHashSet<String>()
        val file = ArrayDeque<Triple<String, String?, Int>>(); file.add(Triple(depart, null, 0)); prof[depart] = 0
        while (file.isNotEmpty()) {
            val (id, arrivee, d) = file.removeFirst()
            val m = c.element(id)?.let { Montage.modele(it) } ?: continue
            for (p in m.ports) {
                if (p.signal != Signal.RF || p.id == arrivee) continue
                if (tx && !(p.voie.emet && p.sens != Sens.ENTREE)) continue
                if (!tx && !(p.voie.recoit && p.sens != Sens.SORTIE)) continue
                for (l in c.liens) {
                    val la = when {
                        l.de.element == id && l.de.port == p.id -> l.vers
                        l.vers.element == id && l.vers.port == p.id -> l.de
                        else -> continue
                    }
                    val loin = Montage.port(c, la) ?: continue
                    if (loin.signal != Signal.RF || (if (tx) !loin.voie.emet else !loin.voie.recoit)) continue
                    liens += l.id
                    if (la.element !in prof) { prof[la.element] = d + 1; file.add(Triple(la.element, la.port, d + 1)) }
                }
            }
        }
        return Chemin(prof.entries.sortedByDescending { it.value }.map { it.key }, liens.toList())
    }

    /** The data path from [depart] to the computer (through a node, an interface, a sound card), nearest first. */
    private fun versPc(c: Chaine, depart: String, audio: Boolean = false): Chemin? {
        val parent = HashMap<String, Pair<String, String>>(); val file = ArrayDeque(listOf(depart)); val vus = hashSetOf(depart)
        while (file.isNotEmpty()) {
            val id = file.removeFirst()
            val g = c.element(id)?.let { Montage.modele(it) }?.genre
            if (id != depart && g == Genre.ORDINATEUR) {
                val els = ArrayList<String>(); val li = ArrayList<String>(); var x = id
                while (x != depart) { els += x; val (p, l) = parent[x]!!; li += l; x = p }
                return Chemin(els.reversed(), li.reversed())
            }
            for (l in c.liens) {
                val (ici, autre) = when (id) { l.de.element -> l.de to l.vers; l.vers.element -> l.vers to l.de; else -> continue }
                val p = Montage.port(c, ici) ?: continue
                if (p.signal == Signal.RF || p.signal == Signal.MECA) continue
                if (audio && id == depart && p.signal == Signal.COMMANDE) continue
                val ga = c.element(autre.element)?.let { Montage.modele(it) }?.genre
                if (ga !in setOf(Genre.ORDINATEUR, Genre.NOEUD, Genre.INTERFACE, Genre.CARTE_SON)) continue
                if (vus.add(autre.element)) { parent[autre.element] = id to l.id; file.add(autre.element) }
            }
        }
        return null
    }

    /** A chain under construction: the nodes by column, the links, what is needed. */
    private class Bati(val c: Chaine, val i: Instantane, val poss: List<Possession>, val cf: List<Conflit>, val famille: Set<String>) {
        val noeuds = LinkedHashMap<String, Noeud>(); val arcs = ArrayList<Arc>(); val besoins = ArrayList<Besoin>()
        fun ajoute(n: Noeud) { if (n.id !in noeuds) noeuds[n.id] = n }
        fun relie(de: String, vers: String, t: Type, s: Statut, texte: String = "") { if (arcs.none { it.de == de && it.vers == vers }) arcs += Arc(de, vers, t, s, texte) }
        fun besoin(b: Besoin) { if (besoins.none { it.texte == b.texte }) besoins += b }

        /** The holder of [r] when it is not one of this chain's functions. */
        fun autre(r: String): Possession? = poss.firstOrNull { it.ressource == r && it.detenteur !in famille }

        /**
         * The hardware for [role]: receiving, from the antenna (column
         * [col0]) to the radio, then the boxes to the computer; transmitting,
         * the computer at [col0], the radio, then on to the antenna. Returns
         * the computer's node and the next free column.
         */
        fun materiel(role: String, nom: String, col0: Int, tx: Boolean, actif: Boolean, typeVersPc: Type): Pair<String, Int> {
            val r = ressourceDuRole(role)
            val e = elementsDuRole(c, role).firstOrNull()
            val occ = r?.let { autre(it) }
            val st = when { occ != null -> Statut.OCCUPE; e == null -> Statut.MANQUE; actif -> Statut.OK; else -> Statut.PRET }
            val det = when { occ != null -> occ.dit(); e == null -> "pas dans le montage"; else -> "" }
            val vif = if (st == Statut.OK) Statut.OK else Statut.PRET
            if (occ != null && cf.none { it.ressource == r && it.fonction in famille }) besoin(Besoin(occ.dit(), libereAction(occ), fonction(occ.detenteur)?.raccourci))
            fun relieVers(de: String, vers: String, t: Type, s: Statut, texte: String = "") = if (tx) relie(vers, de, t, s, texte) else relie(de, vers, t, s, texte)
            if (e == null) {
                besoin(Besoin("« $nom » n'est pas dans le montage : Modifier › Ajouter, pour voir son antenne et ses câbles", raccourci = Raccourci("montage")))
                val id = "m:$role"
                ajoute(Noeud(id, nom, Genre2.MATERIEL, if (tx) col0 + 1 else col0, badgesMat(tx), st, det))
                ajoute(Noeud("m:pc", "SatMe-PC", Genre2.MATERIEL, if (tx) col0 else col0 + 1, listOf("materiel"), Statut.OK, ""))
                relieVers(id, "m:pc", typeVersPc, vif)
                return "m:pc" to col0 + 2
            }
            val ra = radio(c, e.id, tx)
            val loin = ra.elements.filter { it != e.id }
            val prof = loin.size
            val colRadio = if (tx) col0 + 1 else col0 + prof
            loin.forEachIndexed { k, id ->
                val el = c.element(id)!!
                ajoute(Noeud("e:$id", Montage.nom(el), Genre2.MATERIEL, if (tx) col0 + 1 + prof - k else col0 + k, badgesMat(tx), vif, "", id, el.modele))
            }
            ajoute(Noeud("e:" + e.id, Montage.nom(e), Genre2.MATERIEL, colRadio, badgesMat(tx), st, det, e.id, e.modele))
            // The cables drawn the way the signal goes: left to right.
            for (lid in ra.liens) {
                val l = c.liens.first { it.id == lid }
                val a = "e:" + l.de.element; val b = "e:" + l.vers.element
                val (x, y) = if ((noeuds[b]?.colonne ?: 0) >= (noeuds[a]?.colonne ?: 0)) a to b else b to a
                relie(x, y, Type.RF, vif, l.cable.nom)
            }
            val genres = loin.mapNotNull { c.element(it)?.let { el -> Montage.modele(el)?.genre } }
            if (Genre.ANTENNE !in genres && Genre.CHARGE !in genres)
                besoin(Besoin("Aucune antenne reliée à « " + Montage.nom(e) + " » dans le montage", raccourci = Raccourci("montage")))
            val vp = versPc(c, e.id, typeVersPc == Type.AUDIO)
            if (vp == null) {
                besoin(Besoin("« " + Montage.nom(e) + " » n'est relié à aucun ordinateur dans le montage", raccourci = Raccourci("montage")))
                ajoute(Noeud("m:pc", "SatMe-PC", Genre2.MATERIEL, if (tx) col0 else colRadio + 1, listOf("materiel"), Statut.MANQUE, "câble absent du montage"))
                relieVers("e:" + e.id, "m:pc", typeVersPc, Statut.MANQUE)
                return "m:pc" to (if (tx) col0 + 2 + prof else colRadio + 2)
            }
            if (tx) {
                // Transmitting: the computer, then the radio (the boxes between named on the cable).
                val pc = vp.elements.last(); val el = c.element(pc)!!
                ajoute(Noeud("e:$pc", Montage.nom(el), Genre2.MATERIEL, col0, listOf("materiel"), vif, "", pc, el.modele))
                relie("e:$pc", "e:" + e.id, typeVersPc, vif, vp.elements.dropLast(1).mapNotNull { c.element(it)?.let { x -> Montage.nom(x) } }.joinToString(" › "))
                return "e:$pc" to col0 + 2 + prof
            }
            var prec = "e:" + e.id
            var precCarte = false
            vp.elements.forEachIndexed { k, id ->
                val el = c.element(id)!!
                val carte = Montage.modele(el)?.genre == Genre.CARTE_SON
                val nid = "e:$id"
                ajoute(Noeud(nid, Montage.nom(el), Genre2.MATERIEL, colRadio + 1 + k,
                    listOf("materiel") + (if (carte) listOf("son-recoit", "son-emet") else emptyList()), vif, "", id, el.modele))
                relie(prec, nid, if (carte || precCarte || (k == 0 && typeVersPc == Type.AUDIO)) Type.AUDIO else if (k == 0) typeVersPc else Type.IQ, vif)
                prec = nid; precCarte = carte
            }
            return prec to colRadio + 1 + vp.elements.size
        }

        fun badgesMat(tx: Boolean) = listOf("materiel", if (tx) "tx" else "rx")

        fun libereAction(p: Possession): Action? = if (p.role == Role.EMISSION) null else when (p.detenteur) {
            "sdr", "qo100", "meteor", "sondes" -> Action("Arrêter " + (fonction(p.detenteur)?.nom ?: ""), "sdr.actif", "0")
            "datv-rx" -> if (i.datvRecepteur != "minitiouner") Action("Arrêter la réception DATV", "datv.actif", "0") else null
            else -> null
        }

        fun fonctionNoeud(id: String, col: Int, nom: String? = null): String {
            val n = noeudFonction(id, i, poss, cf, col, nom)
            ajoute(n)
            if (n.statut == Statut.PRET && id !in setOf("ecoute", "navigateur", "poste"))
                actionsDe(id, i).firstOrNull { it.valeur != "0" && it.valeur.isNotEmpty() }?.let { besoin(Besoin(fonction(id)!!.nom + " est à l'arrêt", it, fonction(id)!!.raccourci)) }
                    ?: besoin(Besoin(fonction(id)!!.nom + " est à l'arrêt : à démarrer dans son onglet", raccourci = fonction(id)!!.raccourci))
            cf.filter { it.fonction == id }.forEach { k -> besoin(Besoin(k.raison, k.actions.firstOrNull(), k.raccourci)) }
            return n.id
        }

        /** The SDR's receiving chain to its demodulator; returns the demodulator's node and the next column. */
        fun parSdr(f: String = "sdr"): Pair<String, Int> {
            val (pc, col) = materiel("sdr:" + i.sdrRecepteur, nomRessource("sdr:" + i.sdrRecepteur), 0, false, i.sdrActif, Type.IQ)
            val n = fonctionNoeud(f, col)
            relie(pc, n, Type.IQ, if (i.sdrPour == f) Statut.OK else Statut.PRET, "IQ")
            return n to col + 1
        }

        /** The rig's receiving chain to the sound card's input; returns the card's node and the next column. */
        fun parCarte(): Pair<String, Int> {
            val poste = c.elements.firstOrNull { e -> Montage.modele(e)?.genre == Genre.POSTE }
            val nom = poste?.let { Montage.nom(it) } ?: i.catModele.ifBlank { "Poste" }
            val (pc, col) = materiel("cat", nom, 0, false, i.catConnecte || (i.sonActif && !i.sonParSdr), Type.AUDIO)
            val carte = i.sonAppareil.takeIf { i.sonActif && !i.sonParSdr } ?: i.sonCarte
            var n = noeudFonction("son", i, poss, cf, col, "Carte son").let { it.copy(detail = if (carte.isNotBlank()) court(carte) else "aucune carte choisie") }
            if (i.sonParSdr) n = n.copy(statut = Statut.OCCUPE, detail = "prise par la carte SDR (son démodulé)")
            ajoute(n)
            val choix = i.sonCarte.ifBlank { i.cartes.firstOrNull().orEmpty() }
            when {
                i.sonParSdr -> besoin(Besoin("L'entrée des décodeurs est prise par la carte SDR : la carte son est arrêtée",
                    choix.takeIf { it.isNotBlank() }?.let { Action("Reprendre « " + court(it) + " »", "son.entree", it) }, Raccourci("son", ancre = "s-carte")))
                !i.sonActif -> besoin(Besoin(if (choix.isBlank()) "Aucune carte son choisie (onglet Son)" else "La carte son « " + court(choix) + " » n'est pas démarrée",
                    choix.takeIf { it.isNotBlank() }?.let { Action("Démarrer « " + court(it) + " »", "son.entree", it) }, Raccourci("son", ancre = "s-carte")))
            }
            relie(pc, n.id, Type.AUDIO, if (i.sonActif && !i.sonParSdr) Statut.OK else if (i.sonParSdr) Statut.OCCUPE else Statut.PRET, "son")
            if (!i.catConnecte) besoin(Besoin("Le poste n'est pas en CAT : la fréquence n'est pas suivie (le son passe quand même)", raccourci = Raccourci("reglages", "materiel")))
            return n.id to col + 1
        }
    }

    /**
     * The whole chain of function [f], from the antenna to its screen, on
     * the station's chain [c], with each link's state and what is needed.
     * Null for an unknown function.
     */
    fun vue(f: String, c: Chaine, i: Instantane, poss: List<Possession> = possessions(i), cf: List<Conflit> = conflits(i, poss)): Graphe? {
        val fo = fonction(f)?.takeIf { f in VUES } ?: return null
        val famille = when (f) {
            "sstv-rx", "ft8", "cw", "aprs", "phonie", "ecoute" -> setOf(f, "sdr", "qo100", "son", "poste", "datv-rx")
            "qo100" -> setOf("qo100", "sdr")
            else -> setOf(f)
        }
        val b = Bati(c, i, poss, cf, famille)
        fun ecran(de: String, col: Int, nom: String, actif: Boolean) {
            b.ajoute(Noeud("x:ecran", nom, Genre2.FONCTION, col, listOf("fonction"), if (actif) Statut.OK else Statut.PRET, "", raccourci = fo.raccourci))
            b.relie(de, "x:ecran", Type.RESEAU, if (actif) Statut.OK else Statut.PRET)
        }
        val ok = enMarche(f, i)
        when (f) {
            "sdr", "qo100" -> {
                val (n, col) = b.parSdr(f)
                b.ajoute(Noeud("s:sdr", "Son démodulé", Genre2.SON, col, listOf("fonction", "son-emet"), if (ok) Statut.OK else Statut.PRET, ""))
                b.relie(n, "s:sdr", Type.AUDIO, if (ok) Statut.OK else Statut.PRET)
                b.ajoute(Noeud("s:hp", "Haut-parleur du PC", Genre2.SON, col + 1, listOf("materiel", "son-recoit"), if (ok && i.sdrHautParleur) Statut.OK else Statut.PRET,
                    if (i.sdrHautParleur) "" else "son coupé (onglet SDR)", raccourci = Raccourci("sdr")))
                b.relie("s:sdr", "s:hp", Type.AUDIO, if (ok && i.sdrHautParleur) Statut.OK else Statut.PRET)
                if (i.sonParSdr) {
                    b.ajoute(Noeud("s:entree", "Entrée des décodeurs", Genre2.SON, col + 1, listOf("fonction", "son-recoit"), Statut.OK, ecoutentEntree(i).joinToString(", ") { fonction(it)?.nom ?: it }))
                    b.relie("s:sdr", "s:entree", Type.AUDIO, Statut.OK)
                }
                if (i.ecouteNavigateur) { b.ajoute(Noeud("s:nav", "Écouter ici", Genre2.SON, col + 1, listOf("fonction", "son-recoit"), Statut.OK, "")); b.relie("s:sdr", "s:nav", Type.RESEAU, Statut.OK) }
            }
            "meteor", "sondes" -> {
                val (n, col) = b.parSdr(f)
                ecran(n, col, if (f == "meteor") "Images METEOR" else "Carte des sondes", ok)
            }
            "datv-rx" -> {
                val role = "datv:" + i.datvRecepteur
                val (pc, col) = b.materiel(role, nomRessource(if (i.datvRecepteur == "minitiouner") "datv:minitiouner" else "sdr:" + i.datvRecepteur), 0, false, i.datvActif,
                    if (i.datvRecepteur == "minitiouner") Type.RESEAU else Type.IQ)
                val n = b.fonctionNoeud("datv-rx", col)
                b.relie(pc, n, if (i.datvRecepteur == "minitiouner") Type.RESEAU else Type.IQ, if (ok) Statut.OK else Statut.PRET, if (i.datvRecepteur == "minitiouner") "flux TS" else "IQ")
                ecran(n, col + 1, "Image DATV", ok)
                b.ajoute(Noeud("s:hp", "Haut-parleur du PC", Genre2.SON, col + 1, listOf("materiel", "son-recoit"), if (ok && i.datvSon) Statut.OK else Statut.PRET,
                    if (i.datvSon) "" else "son DATV coupé", raccourci = Raccourci("datv")))
                b.relie(n, "s:hp", Type.AUDIO, if (ok && i.datvSon) Statut.OK else Statut.PRET)
                if (i.datvRecepteur == "minitiouner") b.besoin(Besoin("La réception DATV se démarre dans l'onglet DATV (l'alimentation du LNB s'y règle)", raccourci = Raccourci("datv", ancre = "d-recepteur")))
            }
            "datv-tx", "sstv-tx" -> {
                val e = if (f == "datv-tx") i.txDatv.ifBlank { i.txDatvChoisi.ifBlank { "pluto" } } else i.txSstv.ifBlank { i.txSstvChoisi.ifBlank { "pluto" } }
                val (pc, _) = b.materiel("tx:$e", nomRessource("sdr:$e"), 1, true, ok, Type.IQ)
                val n = noeudFonction(f, i, poss, cf, 0)
                b.ajoute(n)
                b.relie(n.id, pc, Type.IQ, if (ok) Statut.OK else Statut.PRET, "IQ")
                cf.filter { it.fonction == f }.forEach { k -> b.besoin(Besoin(k.raison, k.actions.firstOrNull(), k.raccourci)) }
                b.besoin(Besoin("L'émission ne se démarre jamais d'ici : onglet " + (if (f == "datv-tx") "DATV" else "SSTV › Émission") + ", avec ses sécurités", raccourci = fo.raccourci))
            }
            "ecoute" -> {
                val (n, col) = if (i.datvActif && i.datvSon && !i.sdrActif) {
                    val r = b.materiel("datv:" + i.datvRecepteur, nomRessource(if (i.datvRecepteur == "minitiouner") "datv:minitiouner" else "sdr:" + i.datvRecepteur), 0, false, true, Type.IQ)
                    val x = b.fonctionNoeud("datv-rx", r.second); b.relie(r.first, x, Type.IQ, Statut.OK); x to r.second + 1
                } else b.parSdr("sdr")
                val st = if (ok) Statut.OK else Statut.PRET
                b.ajoute(Noeud("s:hp", "Haut-parleur du PC", Genre2.SON, col, listOf("materiel", "son-recoit"), st, "", raccourci = Raccourci("sdr")))
                b.relie(n, "s:hp", Type.AUDIO, st)
                if (!ok) b.besoin(Besoin("Le son du récepteur SDR n'est pas joué sur ce PC : case « son » de l'onglet SDR", raccourci = Raccourci("sdr")))
            }
            else -> {
                // The decoders: by the SDR's sound or by the sound card.
                val parSdr = when (f) {
                    "sstv-rx" -> !sstvParEntree(i) || (i.sonParSdr && i.sstvSource != "carte")
                    "phonie" -> i.phonieSource == "sdr" || i.phonieSourceActive == "sdr" || (i.phonieSource == "auto" && i.sonParSdr)
                    else -> i.sonParSdr
                }
                val parDatv = f == "phonie" && (i.phonieSource == "datv" || i.phonieSourceActive == "datv")
                val (src, col) = when {
                    parDatv -> {
                        val r = b.materiel("datv:" + i.datvRecepteur, nomRessource(if (i.datvRecepteur == "minitiouner") "datv:minitiouner" else "sdr:" + i.datvRecepteur), 0, false, i.datvActif, Type.IQ)
                        val x = b.fonctionNoeud("datv-rx", r.second); b.relie(r.first, x, Type.IQ, if (i.datvActif) Statut.OK else Statut.PRET); x to r.second + 1
                    }
                    parSdr -> b.parSdr(i.sdrPour?.takeIf { it == "qo100" } ?: "sdr")
                    else -> b.parCarte()
                }
                val n = b.fonctionNoeud(f, col)
                val vif = ok && (if (parDatv) i.datvActif else if (parSdr) i.sdrActif else i.sonActif && !i.sonParSdr)
                b.relie(src, n, Type.AUDIO, if (vif) Statut.OK else if (b.noeuds[src]?.statut == Statut.OCCUPE) Statut.OCCUPE else Statut.PRET, "son")
                ecran(n, col + 1, when (f) {
                    "sstv-rx" -> "Images SSTV"; "ft8" -> "Messages FT8 → carnet"; "cw" -> "Texte CW (Carnet)"; "aprs" -> "Trames APRS, carte"; else -> "Texte de la phonie"
                }, vif)
                if (f == "sstv-rx" && !parSdr && i.sstvSource == "sdr") b.besoin(Besoin("Choisir la source SSTV : carte son", Action("SSTV : source carte son", "sstv.source", "carte"), fo.raccourci))
            }
        }
        val noeuds = b.noeuds.values.toList()
        val nOk = noeuds.count { it.statut == Statut.OK }
        val mauvais = noeuds.filter { it.statut == Statut.OCCUPE || it.statut == Statut.MANQUE || it.statut == Statut.ERREUR }
        val resume = when {
            mauvais.any { it.statut == Statut.OCCUPE } -> "Bloquée : " + mauvais.first { it.statut == Statut.OCCUPE }.let { it.detail.ifBlank { it.nom + " occupé" } }
            ok && mauvais.isEmpty() -> "En marche de bout en bout"
            ok -> "En marche ; à voir : " + mauvais.joinToString(", ") { it.nom }
            else -> "À l'arrêt ($nOk maillons sur ${noeuds.size} en marche)"
        }
        return Graphe(noeuds, b.arcs, b.besoins, fo.nom, resume)
    }

    // ------------------------------------------------------------------ the page's JSON

    fun json(i: Instantane, c: Chaine): JSONObject {
        val poss = possessions(i); val cf = conflits(i, poss)
        // Which element of the chain each holding lights.
        val parElement = JSONObject()
        for (e in c.elements) {
            val roles = Montage.modele(e)?.roles ?: continue
            val r = roles.mapNotNull { ressourceDuRole(it) }.toSet()
            val p = poss.filter { it.ressource in r }
            val k = cf.filter { it.ressource in r }
            if (p.isEmpty() && k.isEmpty()) continue
            parElement.put(e.id, JSONObject()
                .put("tient", JSONArray(p.map { JSONObject().put("fonction", it.detenteur).put("nom", fonction(it.detenteur)?.nom ?: it.detenteur).put("role", it.role.dit).put("tx", it.role == Role.EMISSION) }))
                .put("conflits", JSONArray(k.map { it.raison })))
        }
        return JSONObject()
            .put("possessions", JSONArray(poss.map { p ->
                JSONObject().put("ressource", p.ressource).put("nom", nomRessource(p.ressource)).put("fonction", p.detenteur).put("fonctionNom", fonction(p.detenteur)?.nom ?: p.detenteur)
                    .put("role", p.role.dit).put("tx", p.role == Role.EMISSION).put("partage", JSONArray(p.partage)).put("detail", p.detail).put("dit", p.dit())
            }))
            .put("conflits", JSONArray(cf.map { it.json() }))
            .put("logiciel", logiciel(i, poss, cf).json())
            .put("elements", parElement)
            .put("emission", i.emission() ?: "")
            .put("vues", JSONArray(VUES.filter { presente(it, i) || it in setOf("datv-tx", "sstv-tx") }.map { JSONObject().put("id", it).put("nom", fonction(it)!!.nom) }))
    }

    // ------------------------------------------------------------------ the demonstrations (?essai=)

    /** A pretend state for the page's demonstration: never what the station does. */
    fun essai(quoi: String): Instantane? {
        val base = Instantane(sdrPresent = true, datvPresent = true, sonPresent = true, phoniePresent = true, meteorPresent = true,
            cartes = listOf("USB PCM2902 Audio CODEC [plughw:1,0]", "HDA Intel PCH [plughw:0,0]"), sonCarte = "USB PCM2902 Audio CODEC [plughw:1,0]",
            txDatvChoisi = "pluto", txSstvChoisi = "pluto")
        return when (quoi) {
            "rx" -> base.copy(sdrActif = true, sdrRecepteur = "pluto", sdrMode = "USB", sdrCielHz = 10_489_750_000L, sonActif = true, sonParSdr = true,
                sonAppareil = "Clé SDR (rtl_tcp)", ft8Actif = true, datvActif = true, datvRecepteur = "minitiouner", datvSon = true, catConnecte = true, catModele = "IC9700", sdrHautParleur = true)
            "tx" -> base.copy(datvActif = true, datvRecepteur = "minitiouner", txDatv = "pluto", txSstv = "bladerf", catConnecte = true, catModele = "IC9700")
            "datv-pluto" -> base.copy(datvActif = true, datvRecepteur = "pluto", datvSdrTenu = "pluto", datvVerrouille = true, datvSon = true,
                sdrRecepteur = "pluto", sdrErreur = "", catConnecte = true, catModele = "IC9700")
            "sstv-carte" -> base.copy(catConnecte = true, catModele = "IC9700", sonActif = true, sonAppareil = "USB PCM2902 Audio CODEC [plughw:1,0]",
                sstvActif = true, sstvSource = "carte", sstvSourceActive = "carte", ft8Actif = true, sdrRecepteur = "rtl")
            else -> null
        }
    }
    val ESSAIS = listOf("rx", "tx", "datv-pluto", "sstv-carte")
}
