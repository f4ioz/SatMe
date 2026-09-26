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
 * Ce qui distingue un modèle de radiosonde d'un autre, du point de vue du poste.
 *
 * On a longtemps fait tourner les décodeurs à l'aveugle, tous en même temps,
 * avec un filtre unique de vingt-deux kilohertz. C'était commode et c'était
 * faux : la largeur du filtre doit suivre l'excursion de la sonde écoutée. Une
 * RS41 tient dans quinze kilohertz ; lui en ouvrir vingt-deux, c'est laisser
 * entrer la moitié de bruit en plus pour rien, et perdre les deux décibels qui
 * font la différence entre une sonde décodée à cent kilomètres et une sonde
 * perdue. Les valeurs retenues ici sont celles que le projet auto_rx a mesurées
 * banc en main, modèle par modèle.
 *
 * Le mode automatique reste le réglage de départ : il ouvre large et fait
 * tourner tous les décodeurs, ce qui est le bon compromis quand on ne sait pas
 * encore ce qui passe. Dès que l'opérateur sait — et il le sait presque
 * toujours, la fréquence désigne la station et la station désigne le modèle —
 * lui nommer la sonde lui rend ces décibels.
 */
object SondeModel {

    /**
     * Un profil de réception.
     *
     * [baud] est le débit binaire vrai, [chipRate] celui auquel le
     * démodulateur doit tourner : ils diffèrent pour la M10, dont le codage
     * bi-phase impose de compter les demi-bits.
     */
    data class Profile(
        /** Identifiant rangé dans les réglages. */
        val id: String,
        /** Nom affiché, en clair et non traduit : ce sont des noms propres. */
        val label: String,
        /** Largeur du filtre FM conseillée, en hertz. */
        val bandwidthHz: Int,
        /** Débit binaire utile, en bits par seconde. 0 pour le mode automatique. */
        val baud: Double,
        /** Débit auquel tourne le démodulateur, en symboles par seconde. */
        val chipRate: Double,
        /** La trame est-elle codée en bi-phase, deux demi-bits par bit ? */
        val biphase: Boolean
    ) {
        /**
         * Nombre d'échantillons par symbole à ce débit d'échantillonnage.
         *
         * C'est le chiffre qui dit si la carte son suit : sous deux
         * échantillons par symbole, la récupération d'horloge n'a plus de quoi
         * travailler et le décodage devient une affaire de chance.
         */
        fun samplesPerChip(sampleRate: Int): Double =
            if (chipRate <= 0.0) 0.0 else sampleRate / chipRate

        /**
         * Vrai quand la carte son ne suit pas le débit de ce modèle.
         *
         * Le seuil est à deux, et non à trois comme on l'avait posé d'abord.
         * La M10 est le cas limite : ses demi-bits sortent à 9 616 par seconde,
         * ce qui donne 4,59 échantillons par symbole à 44 100 Hz — la 18.6 a
         * corrigé ici un facteur deux qui faisait croire à 2,29. Le seuil reste
         * à deux : radiosonde_auto_rx décode en production à 2,5 échantillons
         * par symbole, et une chaîne qui marche à 2,5 ne s'effondre pas à 2,29.
         * Placer l'avertissement à trois revenait à décourager l'opérateur
         * devant un montage parfaitement utilisable, et l'OHP de Saint-Michel,
         * la station la plus proche d'ici, lâche justement des M10.
         */
        fun marginal(sampleRate: Int): Boolean {
            val s = samplesPerChip(sampleRate)
            return s > 0.0 && s < 2.0
        }
    }

    /** Mode automatique : filtre large, tous les décodeurs en parallèle. */
    const val AUTO = "AUTO"

    val RS41 = Profile(
        id = "RS41", label = "Vaisala RS41",
        bandwidthHz = Rs41.BANDWIDTH_HZ,
        baud = Rs41.BAUD, chipRate = Rs41.BAUD, biphase = false)

    val M20 = Profile(
        id = "M20", label = "Meteomodem M20",
        bandwidthHz = Meteomodem.BANDWIDTH_HZ,
        baud = Meteomodem.M20_BAUD, chipRate = Meteomodem.M20_BAUD, biphase = false)

    val M10 = Profile(
        id = "M10", label = "Meteomodem M10",
        bandwidthHz = Meteomodem.BANDWIDTH_HZ,
        baud = Meteomodem.M10_BAUD, chipRate = Meteomodem.M10_CHIP_RATE,
        biphase = true)

    /** Le profil du mode automatique : le plus large des trois. */
    val ANY = Profile(
        id = AUTO, label = "Auto",
        bandwidthHz = maxOf(Rs41.BANDWIDTH_HZ, Meteomodem.BANDWIDTH_HZ),
        baud = 0.0, chipRate = 0.0, biphase = false)

    /** Les modèles proposés, mode automatique en tête. */
    val ALL = listOf(ANY, RS41, M20, M10)

    /** Le profil portant cet identifiant, ou le mode automatique. */
    fun byId(id: String?): Profile = ALL.firstOrNull { it.id == id } ?: ANY

    /** Largeur de filtre à demander à la chaîne SDR pour ce choix. */
    fun bandwidthFor(id: String?): Int = byId(id).bandwidthHz

    /** Faut-il faire tourner le décodeur RS41 pour ce choix ? */
    fun wantsRs41(id: String?): Boolean = id == null || id == AUTO || id == "RS41"

    /** Faut-il faire tourner le décodeur M20 ? */
    fun wantsM20(id: String?): Boolean = id == null || id == AUTO || id == "M20"

    /** Faut-il faire tourner le décodeur M10 ? */
    fun wantsM10(id: String?): Boolean = id == null || id == AUTO || id == "M10"

    /**
     * Les modèles qu'on ne sait pas encore décoder, pour mémoire.
     *
     * Ils sont nommés ici plutôt que passés sous silence : quelqu'un qui ne
     * décode rien sur 403,100 doit pouvoir vérifier en trois secondes que la
     * sonde qu'il écoute n'est simplement pas de la partie. Aucune des stations
     * du quart sud-est de la France ne lâche autre chose que des RS41 et des
     * Meteomodem, mais l'Allemagne lâche des Graw DFM et la Russie des MRZ.
     */
    val NOT_YET = listOf(
        "Graw DFM-09/17", "Vaisala RS92", "Meisei iMS-100", "iMet-4", "LMS6", "MRZ-N1")
}
