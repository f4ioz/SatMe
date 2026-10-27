/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site.sat

import fr.f4ioz.satcombo.demo.GlobeDonnees
import fr.f4ioz.satcombo.demo.IssDirectJs
import fr.f4ioz.satcombo.demo.OngletOrbites
import fr.f4ioz.satcombo.demo.OngletSked
import fr.f4ioz.satcombo.demo.OrbitesJs
import fr.f4ioz.satcombo.demo.PagePc
import fr.f4ioz.site.vue.Statique

/**
 * SatMe-PC's tabs on the site, the same code: the Orbites tab (its globes,
 * its looks, its dashboard and Vitrine, the ISS live window) and the Sked
 * tab (its windows, its replay on the map and on the globe) — their markup,
 * styles and scripts are [OngletOrbites], [OngletSked], [OrbitesJs] and
 * [IssDirectJs], shared with SatMe-PC's page. Put together here once, at
 * startup, as two scripts and one stylesheet the browsers keep.
 */
object AppliSatme {

    private fun ressource(nom: String): String =
        AppliSatme::class.java.getResourceAsStream("/fr/f4ioz/site/static/$nom")?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""

    /** The styles: SatMe-PC's design tokens and its tabs' own, after the site's few needed classes (css/appli.css). */
    val CSS: String by lazy {
        "/* SatMe-PC's tabs (moteur: PagePc.STYLE_JETONS, OngletOrbites.CSS, OngletSked.CSS, IssDirectJs.CSS) */\n" +
            PagePc.STYLE_JETONS + "\n" + OngletOrbites.CSS + "\n" + OngletSked.CSS + "\n" + IssDirectJs.CSS + "\n"
    }

    private val MONDE: String by lazy { "const GLOBE_MONDE = " + GlobeDonnees.json() + ";\n" }

    val JS_ORBITES: String by lazy {
        ressource("js/hote-satme.js") + "\n" + MONDE + OrbitesJs.JS + "\n" + OngletOrbites.JS + "\n" + IssDirectJs.JS + "\n" + ressource("js/orbites-init.js")
    }

    val JS_SKED: String by lazy {
        ressource("js/hote-satme.js") + "\n" + MONDE + OrbitesJs.JS + "\n" + OngletSked.JS + "\n" + ressource("js/sked-init.js")
    }

    /** The tab's markup, shown (SatMe-PC's page hides its tabs until chosen). */
    fun visible(html: String): String = html.replaceFirst(" hidden>", ">")

    fun installe(st: Statique) {
        st.ajoute("css/satme-onglets.css", CSS.toByteArray(Charsets.UTF_8))
        st.ajoute("js/orbites.js", JS_ORBITES.toByteArray(Charsets.UTF_8))
        st.ajoute("js/sked.js", JS_SKED.toByteArray(Charsets.UTF_8))
    }
}
