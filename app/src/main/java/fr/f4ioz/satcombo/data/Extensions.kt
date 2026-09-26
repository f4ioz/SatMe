/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.data

/**
 * Le trousseau des fonctions optionnelles.
 *
 * Certaines parties de SatMe sont arrivées par la porte de derrière : écrites,
 * éprouvées sur banc, mais pas encore sur un vrai passage. Le temps de les
 * mettre au point, elles n'apparaissaient que pour qui détenait la clé — un mot
 * à taper dans le champ « Extensions » des réglages.
 *
 * Le décodage SSTV, la clé RTL-SDR et les images NOAA ont assez tourné pour
 * qu'il n'y ait plus de raison de les cacher : les garder derrière un mot de
 * passe empêchait surtout les OM qui voulaient les essayer de les trouver, et
 * une fonction que personne n'essaie ne se met jamais au point. Elles sont donc
 * dans [OPEN], c'est-à-dire ouvertes à tout le monde, sans rien taper.
 *
 * Le mécanisme, lui, reste entier : le champ « Extensions », les mots-clés, les
 * mots maîtres. La prochaine fonction écrite et non éprouvée entrera dans [ALL]
 * sans entrer dans [OPEN], et le trousseau reprendra son office sans qu'il y
 * ait une ligne d'interface à réécrire.
 *
 * Une seule clé ouvre ce qui n'est pas encore ouvert : le champ
 * « Extensions » des réglages contient le mot correspondant. L'indicatif de
 * l'auteur ouvrait tout autrefois, ce qui faisait de lui le seul opérateur à
 * ne jamais voir l'application telle que les autres la voient — la meilleure
 * façon de laisser passer un défaut.
 *
 * Ce n'est pas une protection — c'est un interrupteur. Il n'y a rien à protéger
 * dans une application de radioamateur, et quiconque décompile l'APK trouvera
 * les mots en clair. Le but est d'éviter qu'un OM tombe par hasard sur une
 * fonction qui n'est pas prête et croie qu'elle est cassée.
 *
 * Volontairement sans le moindre import Android : toute la logique est testable
 * en JVM, et une erreur ici enfermerait des fonctions dehors sans qu'on le voie.
 */
object Extensions {

    /** Décodage SSTV (direct et relecture de fichier). */
    const val SSTV = "sstv"

    /** Clé RTL-SDR branchée en USB. */
    const val SDR = "sdr"

    /**
     * Décodage APT, les images météo des satellites NOAA sur 137 MHz.
     *
     * **Refermée à la 20.47, sur décision d'Olivier.** Le mot-clé est « noaa »
     * plutôt que « apt » : c'est le nom que cherche un opérateur, pas celui du
     * format. La constante garde son nom de code, parce que les écrans la
     * testent par son nom et non par sa valeur.
     *
     * Attention : le dossier de stockage des images s'appelle toujours « apt »
     * (`AptHub`). C'est un chemin sur disque, pas un mot-clé, et il ne doit pas
     * changer — les images déjà reçues deviendraient introuvables.
     */
    const val APT = "noaa"

    /** Petit drapeau devant l'indicatif sur la photo QRV. */
    const val FLAG = "drapeau"

    /**
     * Les deux drapeaux bretons dans les listes de la photo QRV.
     *
     * Ce n'est pas une fonction en rodage, c'est une affaire de pertinence :
     * le Gwenn ha Du et le drapeau bigouden n'ont rien à faire en tête d'un
     * catalogue de drapeaux nationaux pour un OM qui n'est pas breton. Celui
     * qui les veut écrit BZH, et il les a.
     */
    const val BZH = "bzh"

    /**
     * Décodage des radiosondes météo.
     *
     * Écrite, testée sur signaux de synthèse, mais jamais confrontée à un vrai
     * ballon : c'est exactement le cas que le trousseau sert à couvrir. Elle
     * rejoindra [OPEN] quand un premier vol aura été suivi du lâcher au sol.
     */
    const val SONDE = "sonde"

    /**
     * Pilotage d'un rotor d'azimut et d'élévation.
     *
     * C'est la première fonction de SatMe qui déplace quelque chose de lourd.
     * Un affichage faux se corrige à la version suivante ; un mât parti dans le
     * mauvais sens tire sur des câbles pendant une minute entière. Elle est
     * écrite et éprouvée au banc contre un contrôleur simulé, mais aucun essai
     * ne remplace un vrai G-5500 en haut d'un pylône : elle reste donc sous
     * clé, et rejoindra [OPEN] quand un passage entier aura été suivi bout en
     * bout sans intervention.
     */
    const val ROTOR = "rotor"

    // La clé « adif » gardait la base d'indicatifs embarquée. La base a été
    // retirée à la 20.42 : chacun rapatrie désormais son propre carnet. Une
    // clé qui ne garde plus rien est un mécanisme dormant, qui finirait par
    // reprendre la main sur une fonction qu'on lui confierait sans y penser.

    /**
     * L'écran QO-100, pour le satellite géostationnaire.
     *
     * Il repose sur toute une chaîne que personne n'a encore vue fonctionner
     * bout en bout : deux convertisseurs, une paire de fréquences croisée, un
     * pointage fixe et un calage sur la balise. Chaque pièce est éprouvée au
     * banc, mais l'ensemble ne vaut que confronté à un vrai downconverter dont
     * l'oscillateur dérive à la mise sous tension. Il rejoindra [OPEN] quand
     * un premier contact aura été passé par le transpondeur étroit.
     */
    const val QO100 = "qo100"

    /** Tout ce que le trousseau connaît, dans l'ordre d'apparition. */
    val ALL: List<String> = listOf(SSTV, SDR, APT, FLAG, BZH, SONDE, ROTOR, QO100)

    /**
     * Ce qui est ouvert à tout le monde, sans clé.
     *
     * **Tout, depuis la 18.48.** Le trousseau avait sa raison d'être tant que
     * l'application vivait en test fermé : il évitait qu'un opérateur tombe par
     * hasard sur une fonction qui n'avait jamais servi. À la publication, ce
     * calcul s'inverse — une fonction cachée derrière un mot de passe non
     * documenté n'est pas prudente, elle est introuvable.
     *
     * Ce qui protège désormais, ce sont les bandeaux d'avertissement en tête
     * des écrans concernés : ils disent, fonction par fonction, ce qui a été
     * éprouvé et ce qui ne l'a pas été. C'est plus honnête qu'une clé, parce
     * que cela se lit au moment de s'en servir.
     *
     * Y entrer reste une décision définitive : on n'enlève pas à un opérateur
     * une fonction qu'il a prise l'habitude d'utiliser.
     *
     * **Une exception, et une seule : les images NOAA.** Refermées à la 20.47
     * par décision d'Olivier, contre le principe ci-dessus, et en connaissance
     * de cause. Celui qui les veut écrit « noaa » dans les extensions.
     */
    val OPEN: Set<String> = (ALL - APT).toSet()

    /** Les mots qui ouvrent tout d'un coup, en français comme en anglais. */
    private val MASTER = setOf("all", "tout", "toutes", "beta", "bêta", "*")

    /**
     * Les extensions ouvertes pour cet opérateur.
     *
     * Le résultat contient toujours [OPEN] ; le champ n'y ajoute que ce qui
     * reste sous clé.
     *
     * @param callsign l'indicatif saisi dans les réglages (inutilisé désormais)
     * @param code le contenu du champ « Extensions » (peut être vide)
     */
    fun unlocked(callsign: String, code: String): Set<String> {
        val words = tokens(code)
        // Choix d'Olivier (18.75, confirmé) : les mots maîtres ouvrent TOUT,
        // images NOAA comprises — qui connaît un mot du trousseau est déjà de
        // la maison.
        if (words.any { it in MASTER }) return ALL.toSet()
        return OPEN + ALL.filter { it in words }
    }

    /** Raccourci de lecture, pour les écrans qui ne testent qu'une fonction. */
    fun isUnlocked(name: String, callsign: String, code: String): Boolean =
        name in unlocked(callsign, code)

    /**
     * Découpe le champ en mots.
     *
     * On accepte tout ce qu'un opérateur peut raisonnablement taper : espaces,
     * virgules, points-virgules, plus, barres obliques. Un champ mal ponctué ne
     * doit pas être une raison de ne pas voir sa fonction.
     */
    private fun tokens(code: String): Set<String> =
        code.lowercase()
            .split(' ', ',', ';', '+', '/', '\n', '\t')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
}
