/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.sonde

/**
 * Une trame de radiosonde décodée, quel que soit le constructeur.
 *
 * Toutes les sondes ne disent pas la même chose : la RS41 donne sa tension de
 * pile et son numéro de série alphanumérique, la M20 donne un numéro de série
 * numérique et pas toujours le nombre de satellites. Les champs inconnus valent
 * zéro plutôt que null : ce qui compte pour la chasse, c'est de savoir si le
 * point est digne de confiance, et [trusted] le dit d'un seul coup d'œil.
 */
data class SondeFrame(
    /** Type de sonde : "RS41", "M20", "M10". */
    val type: String,
    /** Numéro de série imprimé sur la sonde, vide si la trame ne le porte pas. */
    val serial: String = "",
    /** Compteur de trames de la sonde, utile pour repérer les trous. */
    val frameNo: Int = 0,
    /** Horodatage GPS converti en millisecondes Unix, 0 si inconnu. */
    val timeUtcMs: Long = 0L,
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    /** Altitude GPS au-dessus de l'ellipsoïde, en mètres. */
    val altM: Double = 0.0,
    /** Vitesse au sol, en mètres par seconde. */
    val speedMps: Double = 0.0,
    /** Cap suivi, en degrés depuis le nord. */
    val headingDeg: Double = 0.0,
    /** Vitesse verticale, en mètres par seconde. Négative en descente. */
    val climbMps: Double = 0.0,
    /** Satellites utilisés pour le point. */
    val sats: Int = 0,
    /**
     * Vrai quand la trame ne transmet pas le nombre de satellites.
     *
     * La M10 est dans ce cas : notre décodage lit sa position, son horloge GPS
     * et son compteur, mais pas le nombre de satellites du point. Sans ce
     * drapeau, [trusted] serait faux pour toutes les M10, et l'application
     * n'aurait jamais de dernier point sûr à donner au chasseur — ni
     * d'éclatement, ni de point de chute. Ce qui tient lieu de garantie, alors,
     * c'est l'horloge GPS : une trame qui porte une semaine et une heure GPS
     * valables vient d'un récepteur qui a fait le point.
     */
    val satsUnknown: Boolean = false,
    /** Tension de la pile, en volts. 0 = non transmise. */
    val batteryV: Double = 0.0,
    /** Fréquence sur laquelle la trame a été reçue, en hertz. */
    val freqHz: Long = 0L,
    /** Horloge du téléphone au moment de la réception, en millisecondes. */
    val heardAtMs: Long = 0L
) {

    /**
     * Le point est-il exploitable ?
     *
     * Sous quatre satellites, un récepteur GPS rend quand même des chiffres,
     * mais ils peuvent être faux de plusieurs kilomètres. Envoyer un chasseur
     * sur un point à trois satellites, c'est lui faire perdre son après-midi :
     * on préfère afficher le dernier point sûr, même vieux d'une minute.
     */
    val trusted: Boolean
        get() = (sats >= 4 || (satsUnknown && timeUtcMs > 0L)) &&
            (lat != 0.0 || lon != 0.0) &&
            lat > -90.0 && lat < 90.0 && lon >= -180.0 && lon <= 180.0

    /** La sonde descend-elle ? Le passage du positif au négatif, c'est l'éclatement. */
    val descending: Boolean get() = climbMps < -1.0

    /**
     * Contrôle de vraisemblance, appliqué avant même de regarder [trusted].
     *
     * Les formats M10 et M20 n'ont pas de contrôle de redondance que l'on
     * sache reproduire à coup sûr : c'est la physique qui sert de garde-fou.
     * Un ballon météo ne dépasse pas quarante kilomètres d'altitude, ne descend
     * pas sous le niveau de la mer et ne file pas à plus de cent mètres par
     * seconde au sol. Une trame qui prétend le contraire est du bruit.
     */
    val plausible: Boolean
        get() = lat >= -90.0 && lat <= 90.0 && lon >= -180.0 && lon <= 180.0 &&
            altM > -500.0 && altM < 45_000.0 &&
            speedMps >= 0.0 && speedMps < 200.0 &&
            climbMps > -120.0 && climbMps < 60.0
}
