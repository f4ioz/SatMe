/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.ui

/**
 * Ce que les deux coins chiffrés de la boussole doivent afficher.
 *
 * « Quand le rotor est connecté, la boussole est dirigée par les éléments
 * élévation azimut du rotor. Même quand le satellite n'est pas à vue. »
 *
 * L'aiguille suivait déjà le mât. Les coins, eux, ne montraient que le
 * satellite : deux tirets dès qu'il passait sous l'horizon, c'est-à-dire
 * pendant les cinquante minutes sur soixante où l'on règle justement son
 * installation. La position lue sur le contrôleur arrivait chaque seconde et
 * n'était affichée nulle part.
 *
 * Deux règles, et elles tiennent en une phrase chacune.
 *
 * **La position lue passe devant.** Dès que le mât dit où il pointe, c'est ce
 * chiffre-là qu'on montre — visible ou non, poursuite en marche ou à l'arrêt.
 * C'est le seul qui décrive le monde réel : le reste est calculé.
 *
 * **Le libellé change avec la source.** Un « 155° » sous le mot `AZ` et un
 * « 155° » sous le mot `AZ MÂT` ne veulent pas dire la même chose, et il n'y a
 * aucun moyen de les distinguer une fois affichés. Quand le contrôleur se
 * tait, le libellé redevient `AZ` tout court : le retour au satellite se voit,
 * au lieu de se deviner.
 *
 * La fonction est ici, et pas dans le composable, pour qu'elle puisse être
 * mise à l'essai. Un coin d'écran qui ment ne plante pas.
 */
object CoinsAim {

    /** Le libellé d'un coin, et le chiffre qui va dessous. */
    data class Coin(val libelle: String, val valeur: String)

    /** Le tiret montré quand il n'y a rien d'honnête à écrire. */
    const val RIEN = "—"

    /**
     * @param mat        position lue sur le contrôleur, ou null s'il se tait
     *                   (ou si le mât n'a pas cet axe : un rotor d'azimut seul
     *                   n'a pas d'élévation à donner).
     * @param sat        position du satellite, calculée.
     * @param satVisible faux quand le satellite est sous l'horizon — la valeur
     *                   calculée existe alors, mais ne veut plus rien dire.
     */
    fun coin(mat: Double?, sat: Double?, satVisible: Boolean,
             libelleMat: String, libelleSat: String): Coin {
        if (mat != null && !mat.isNaN()) {
            return Coin(libelleMat, Math.round(mat).toString() + "°")
        }
        if (satVisible && sat != null && !sat.isNaN()) {
            return Coin(libelleSat, sat.toInt().toString() + "°")
        }
        return Coin(libelleSat, RIEN)
    }
}
