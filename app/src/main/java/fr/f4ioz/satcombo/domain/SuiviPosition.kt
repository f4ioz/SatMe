/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.domain

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Suivre l'opérateur qui se déplace.
 *
 * Le défaut corrigé ici était discret et coûteux : le suivi de position n'était
 * lancé qu'à l'ouverture de la carte, et arrêté en la quittant. Sur la page des
 * passages — celle qu'on regarde pendant qu'on trafique — la position restait
 * celle du démarrage de l'application. Un opérateur qui monte sur une colline,
 * qui sort en portable ou qui roule voyait ses azimuts calculés pour l'endroit
 * d'où il était parti, sans qu'aucun message ne le lui dise.
 *
 * Deux questions se posent alors, et elles n'ont pas la même réponse :
 *
 *  - **Faut-il redessiner ?** Oui, à chaque point. Le carré Maidenhead affiché,
 *    le marqueur, les coordonnées : cela ne coûte rien et l'opérateur doit voir
 *    sa position bouger quand il bouge.
 *  - **Faut-il recalculer les passages ?** Non, pas à chaque point. Une
 *    prédiction SGP4 sur quarante-huit heures pour tous les satellites suivis
 *    est un travail réel, et la relancer toutes les deux secondes parce que le
 *    GPS a frémi de trois mètres viderait la batterie sans rien changer aux
 *    horaires.
 *
 * D'où un seuil. Ce fichier ne contient que son arithmétique — vérifiable au
 * banc, contrairement à un déplacement.
 */
object SuiviPosition {

    /**
     * Distance à partir de laquelle les passages méritent d'être recalculés.
     *
     * Trois kilomètres. En dessous, l'azimut d'un satellite en orbite basse
     * bouge de moins d'un dixième de degré et les heures d'AOS ne bougent pas
     * d'une seconde : recalculer serait dépenser sans rien gagner. Au-dessus,
     * un opérateur qui s'est vraiment déplacé — une colline, un autre carré,
     * une sortie en portable — mérite des chiffres qui parlent d'où il est.
     */
    const val SEUIL_M: Double = 3_000.0

    /**
     * Délai minimal entre deux recalculs, en millisecondes.
     *
     * Le seuil de distance ne suffit pas seul : en voiture on le franchit
     * toutes les deux minutes, et sur autoroute toutes les quatre-vingt-dix
     * secondes. Ce plancher de temps garantit qu'on ne passe pas la journée à
     * prédire au lieu d'afficher.
     */
    const val DELAI_MIN_MS: Long = 120_000L

    /** Cadence des points quand la carte est ouverte : le marqueur doit suivre. */
    const val CADENCE_CARTE_MS: Long = 2_000L

    /**
     * Cadence ailleurs dans l'application.
     *
     * Vingt secondes, et non deux. Le suivi tourne désormais en permanence —
     * c'est tout l'objet du correctif — donc sa cadence n'est plus un détail
     * d'affichage mais une ligne du bilan de batterie. Vingt secondes suffisent
     * amplement à voir un carré changer, et divisent par dix le nombre de
     * réveils du récepteur.
     */
    const val CADENCE_FOND_MS: Long = 20_000L

    /**
     * Silence au-delà duquel on considère que le suivi ne suit plus rien.
     *
     * Quatre-vingt-dix secondes : plus de quatre fois la cadence de fond, donc
     * un point manqué ou deux ne déclenchent rien, mais un fournisseur muet se
     * fait remplacer avant qu'on ait eu le temps de s'en apercevoir à l'écran.
     */
    const val SILENCE_MAX_MS: Long = 90_000L

    /**
     * Faut-il relancer le suivi ?
     *
     * La seconde condition est celle qui manquait, et c'est elle qui explique
     * le défaut observé : « je reste sur JN28FT, il faut aller sur une carte
     * pour la mise à jour ». Le suivi était bien démarré au lancement, mais
     * l'ancienne garde se contentait de vérifier que la tâche **existait** —
     * `if (job.isActive) return`. Or une demande de position adressée aux
     * services Google avant qu'ils ne soient prêts laisse une tâche
     * parfaitement vivante qui ne délivre jamais rien. La tâche existait, donc
     * on ne la relançait pas, donc plus rien n'arrivait jusqu'à ce qu'ouvrir la
     * carte demande une **autre cadence** — ce qui annulait et relançait la
     * tâche, et tout se remettait à marcher.
     *
     * Une tâche vivante n'est donc pas une preuve de fonctionnement. La preuve,
     * c'est un point reçu récemment.
     */
    fun doitRelancer(
        auto: Boolean,
        tacheActive: Boolean,
        dernierPointMs: Long,
        demarreDepuisMs: Long,
        maintenantMs: Long,
        silenceMaxMs: Long = SILENCE_MAX_MS,
    ): Boolean {
        if (!auto) return false
        if (!tacheActive) return true
        // On laisse au fournisseur le temps du premier point avant de le
        // déclarer muet : un démarrage à froid met parfois une minute.
        if (maintenantMs - demarreDepuisMs < silenceMaxMs) return false
        return maintenantMs - dernierPointMs > silenceMaxMs
    }

    /** Distance entre deux points du globe, en mètres. */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /**
     * Faut-il relancer la prédiction ?
     *
     * [depuisLat]/[depuisLon] valent le point où le dernier calcul a été fait.
     * Un premier point — aucun calcul antérieur — déclenche toujours : c'est le
     * cas d'une application qui vient de démarrer sans position connue.
     */
    fun doitRecalculer(
        depuisLat: Double?, depuisLon: Double?,
        versLat: Double, versLon: Double,
        dernierCalculMs: Long, maintenantMs: Long,
        seuilM: Double = SEUIL_M, delaiMinMs: Long = DELAI_MIN_MS,
    ): Boolean {
        if (depuisLat == null || depuisLon == null) return true
        if (maintenantMs - dernierCalculMs < delaiMinMs) return false
        return distanceM(depuisLat, depuisLon, versLat, versLon) >= seuilM
    }

    /**
     * Un point est-il vraisemblable ?
     *
     * Un récepteur GPS rend parfois un point aberrant — coordonnées nulles au
     * démarrage à froid, ou saut de plusieurs centaines de kilomètres sur une
     * mauvaise éphéméride. Le laisser passer déplacerait le QTH, donc les
     * azimuts, donc l'antenne, sur la foi d'un accident. Le zéro absolu mérite
     * une mention à part : c'est la valeur que rend un récepteur qui n'a pas
     * encore de position, et elle tombe dans le golfe de Guinée — un endroit
     * parfaitement valide, ce qui la rend d'autant plus traître.
     */
    fun vraisemblable(lat: Double, lon: Double): Boolean {
        if (abs(lat) > 90.0 || abs(lon) > 180.0) return false
        if (abs(lat) < 1e-9 && abs(lon) < 1e-9) return false
        return true
    }
}
