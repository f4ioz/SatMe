/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.domain

/**
 * Le désignateur AMSAT, à partir du nom brut d'un jeu d'éléments.
 *
 * Un TLE nomme parfois le satellite avec le corps de fusée qui l'accompagne :
 * « RS-44 & BREEZE-KM R/B ». LoTW et Wavelog apparient sur `SAT_NAME`, et un
 * contact déclaré sous le nom long ne rencontrera jamais celui que l'autre
 * station a déclaré sous « RS-44 ».
 *
 * L'export d'Olivier du 28 août porte les deux formes pour le même satellite,
 * selon la source des éléments : la moitié de ses contacts RS-44 ne
 * s'apparieraient pas.
 *
 * La correction ne se fait pas à l'insu de l'opérateur — un nom de satellite
 * est une donnée qu'il a peut-être voulue telle quelle. Elle se demande.
 */
object NomSatellite {

    private val CONNUS = mapOf(
        "RS-44 & BREEZE-KM R/B" to "RS-44",
        "JAS-2 (FO-29)" to "FO-29",
        "FUJI-OSCAR 29" to "FO-29",
        "SAUDISAT 1C (SO-50)" to "SO-50",
        "ISS (ZARYA)" to "ISS",
        "QO-100 (ES'HAIL 2)" to "QO-100",
        "ES'HAIL 2" to "QO-100",
    )

    fun propre(nom: String): String {
        val brut = nom.trim()
        CONNUS[brut]?.let { return it }
        // « MACHIN (AO-XX) » : le désignateur est entre parenthèses.
        if (brut.endsWith(")") && brut.contains("(")) {
            val dedans = brut.substringAfterLast("(").dropLast(1).trim()
            if (dedans.isNotEmpty()) return dedans
        }
        // « RS-44 & QUELQUE CHOSE » : ce qui précède l'esperluette.
        if (brut.contains("&")) return brut.substringBefore("&").trim()
        return brut
    }

    /** Vrai si ce nom gagnerait à être nettoyé. */
    fun aNettoyer(nom: String): Boolean = propre(nom) != nom.trim()
}
