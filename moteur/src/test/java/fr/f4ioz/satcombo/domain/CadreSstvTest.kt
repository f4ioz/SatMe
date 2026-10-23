/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The SSTV picture placed by hand: shares of the view to pixels, kept inside it. */
class CadreSstvTest {

    private fun proche(attendu: Float, lu: Float) = assertEquals(attendu, lu, 0.01f)

    @Test
    fun les_parts_deviennent_des_pixels_en_4_3() {
        // 16:9 at 1920 px, its view 855 px high: a third of the width, from 60 % across, 10 % down.
        val p = CadreSstv(0.6f, 0.1f, 1f / 3).place(1920f, 855f)
        proche(1152f, p.x); proche(85.5f, p.y); proche(640f, p.l); proche(480f, p.h)
        // The same shares in the phone's square view, at 720 px.
        val q = CadreSstv(0.6f, 0.1f, 1f / 3).place(720f, 720f)
        proche(432f, q.x); proche(72f, q.y); proche(240f, q.l); proche(180f, q.h)
    }

    @Test
    fun il_reste_dans_la_vue_et_entre_15_et_90_pour_cent() {
        // Past the right and bottom edges: pushed back inside.
        val p = CadreSstv(0.95f, 0.9f, 0.4f).place(1000f, 800f)
        proche(600f, p.x); proche(500f, p.y)
        // Too small, too large.
        proche(150f, CadreSstv(0f, 0f, 0.02f).place(1000f, 800f).l)
        proche(900f, CadreSstv(0f, 0f, 2f).place(1000f, 1000f).l)
        // Never taller than a flat view: 4:3 of its height at most.
        val r = CadreSstv(0f, 0f, 0.9f).place(1000f, 300f)
        proche(400f, r.l); proche(300f, r.h); proche(0f, r.y)
    }

    @Test
    fun lu_et_ecrit() {
        val c = CadreSstv.lit("0.25,0.5,0.3")!!
        assertEquals(CadreSstv(0.25f, 0.5f, 0.3f), c)
        assertEquals(c, CadreSstv.lit(c.texte()))
        assertEquals(CadreSstv(1f, 0f, CadreSstv.MAX), CadreSstv.lit("3,-1,7"))
        assertNull(CadreSstv.lit("")); assertNull(CadreSstv.lit("auto")); assertNull(CadreSstv.lit(null))
        assertNull(CadreSstv.lit("1,x,2")); assertNull(CadreSstv.lit("NaN,0,0.3")); assertNull(CadreSstv.lit("0.1,0.2"))
    }
}
