/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site

import fr.f4ioz.site.http.Routeur

/**
 * One part of the site: its routes, its entries in the menu and its pages
 * for the sitemap. A new page (the HF propagation's, the station's) is one
 * more module in [Modules.liste]: the shell, the menu, the security headers
 * and the error pages come with it.
 */
interface ModuleSite {
    /** Its entries in the rail (and the phone's drawer). */
    val menu: List<EntreeMenu> get() = emptyList()

    /** Its public paths for `sitemap.xml` (with their priority, 0..1). */
    fun plan(): List<Pair<String, Double>> = emptyList()

    /** Adds its routes. */
    fun installe(r: Routeur)

    /** Starts what it keeps up to date in the background (the elements, the logbook…). */
    fun demarre() {}
}

/** The rail's groups, in their order. */
enum class Groupe(val titre: String) {
    SITE("Le site"), SATELLITES("Satellites"), TRAFIC("Trafic & propagation"), STATION("Station")
}

/**
 * An entry of the rail: where it leads, its name and icon ([fr.f4ioz.site.vue.Icones]),
 * its place in its group. [bientot]: shown greyed, « bientôt », not a link (a page to
 * come). [actifPour]: the paths it lights up for (its own and those under it by default).
 */
data class EntreeMenu(
    val groupe: Groupe,
    val ordre: Int,
    val chemin: String,
    val libelle: String,
    val icone: String,
    val description: String = "",
    val bientot: Boolean = false,
    val actifPour: (String) -> Boolean = { p -> p == chemin || (chemin != "/" && p.startsWith("$chemin/")) },
)
