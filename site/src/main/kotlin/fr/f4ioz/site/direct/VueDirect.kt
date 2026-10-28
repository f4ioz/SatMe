/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site.direct

import org.json.JSONObject

/**
 * Which picture the live block shows: QO-100 (the spectrum, the DATV
 * picture, BATC's pages) by default; the auto-pilot's SSTV pass while one is
 * received — from its AOS to its LOS, then for [maintienMs] after LOS or
 * after its last picture, whichever is later — and back to QO-100 by
 * itself. A pass the station stopped telling about (SatMe-PC silent) before
 * its LOS is not shown: the station may be off.
 */
object VueDirect {

    enum class Vue(val nom: String) { QO100("qo100"), SSTV("sstv") }

    /**
     * [passage]: the auto-pilot's pass under way as last said (`phase`, `aos`, `los`); [derniereImageMs]: when its
     * last picture came (0: none); [frais]: SatMe-PC spoke lately; [bascule]: the setting « Basculer sur un passage SSTV en cours ».
     */
    fun choisis(passage: JSONObject?, derniereImageMs: Long, frais: Boolean, maintenant: Long, bascule: Boolean, maintienMs: Long): Vue {
        if (!bascule || passage == null) return Vue.QO100
        val aos = passage.optLong("aos"); val los = passage.optLong("los")
        if (aos <= 0L || los < aos) return Vue.QO100
        // Getting ready before AOS: still QO-100.
        if (maintenant < aos) return Vue.QO100
        if (maintenant <= los) return if (frais) Vue.SSTV else Vue.QO100
        return if (maintenant <= maxOf(los, derniereImageMs) + maintienMs) Vue.SSTV else Vue.QO100
    }
}
