/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site

import fr.f4ioz.site.admin.Admin
import fr.f4ioz.site.pages.Accueil
import fr.f4ioz.site.pages.Base
import fr.f4ioz.site.pages.Bientot
import fr.f4ioz.site.pages.PageGrid
import fr.f4ioz.site.pages.PageLogbook
import fr.f4ioz.site.pages.PageSatme
import fr.f4ioz.site.pages.PagesArticles
import fr.f4ioz.site.pages.PagesGaleries
import fr.f4ioz.site.publication.ApiPublication
import fr.f4ioz.site.sat.ApiSat
import fr.f4ioz.site.sat.PageCiel
import fr.f4ioz.site.sat.PageOrbites
import fr.f4ioz.site.sat.PagePassages
import fr.f4ioz.site.sat.PageSked
import fr.f4ioz.site.sat.PageTle

/**
 * The site's pages, in one place. To add one (phase 2: the DX cluster, the
 * bands, the beacons, WSPR, the openings, the station's SDR): write a
 * [ModuleSite] — its routes, its [EntreeMenu]s, its paths for the sitemap —
 * put it in this list and take its slot out of [Bientot.SLOTS].
 */
object Modules {
    fun liste(s: Site): List<ModuleSite> = listOf(
        Base(s),
        Accueil(s),
        PagesArticles(s),
        PagesGaleries(s),
        PageLogbook(s),
        PageSatme(s),
        ApiSat(s),
        PagePassages(s),
        PageSked(s),
        PageOrbites(s),
        PageCiel(s),
        PageTle(s),
        PageGrid(s),
        ApiPublication(s.config, s.publications),
        Admin(s),
        Bientot(),
        // Last: « /<slug> » for the pages of contenu/pages.
        PagesArticles.PagesTexte(s),
    )
}
