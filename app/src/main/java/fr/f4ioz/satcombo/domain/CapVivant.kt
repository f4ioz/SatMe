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
 * L'orientation effective de l'antenne, quelle que soit sa source.
 *
 * **Pourquoi ce porteur.** L'arbitrage entre le module Bluetooth et la boussole
 * du téléphone se fait dans un composable : le ViewModel ne le voit pas. La
 * page de démonstration lisait donc le module directement, et n'affichait rien
 * dès que l'opérateur se servait du téléphone — c'est-à-dire la plupart du
 * temps.
 *
 * Le composable pose ici ce qu'il a retenu ; quiconque a besoin du cap le lit
 * ici. Une seule vérité, et elle n'est plus enfermée dans l'arbre d'affichage.
 *
 * Volatile et non un flux : la valeur est écrite à chaque image et lue une fois
 * par seconde. Un flux réactif ne servirait qu'à réveiller des collecteurs pour
 * rien.
 */
object CapVivant {
    @Volatile var azimutDeg: Float? = null
    @Volatile var elevationDeg: Float? = null
    /** Vrai si le module déporté fournit le cap, faux si c'est le téléphone. */
    @Volatile var depuisModule: Boolean = false

    fun pose(az: Float?, el: Float?, module: Boolean) {
        azimutDeg = az; elevationDeg = el; depuisModule = module
    }

    fun oublie() { azimutDeg = null; elevationDeg = null; depuisModule = false }
}
