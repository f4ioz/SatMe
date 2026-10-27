/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** NASA's ISS live of the moment, read from its channel's live page (a saved snippet: no network here). */
class IssDirectPcTest {

    private val page = """<!DOCTYPE html><html lang="en"><head><meta charset="utf-8">
<title>Live High-Definition Views from the International Space Station (Official NASA Stream) - YouTube</title>
<link rel="shortlink" href="https://youtu.be/ROboUHfPUrU"><link rel="canonical" href="https://www.youtube.com/watch?v=ROboUHfPUrU">
<meta property="og:title" content="Live High-Definition Views from the International Space Station (Official NASA Stream)">
</head><body></body></html>"""

    @Test
    fun l_identifiant_et_le_titre() {
        val d = IssDirectPc.extrait(page)!!
        assertEquals("ROboUHfPUrU", d.first)
        assertEquals("Live High-Definition Views from the International Space Station (Official NASA Stream)", d.second)
        // The attributes the other way round, the title only in og:title, entities.
        val autre = """<link href="https://www.youtube.com/watch?v=abcDEF_12-x" rel="canonical"><title>YouTube</title>
<meta property="og:title" content="NASA &amp; the Space Station &#39;live&#39;">"""
        assertEquals("abcDEF_12-x" to "NASA & the Space Station 'live'", IssDirectPc.extrait(autre))
    }

    @Test
    fun pas_la_station_pas_de_source() {
        // Another live of the channel (a launch), a consent page, a channel page with no live.
        assertNull(IssDirectPc.extrait(page.replace("International Space Station", "Artemis launch").replace("Space Station", "rocket")))
        assertNull(IssDirectPc.extrait("<html><title>Before you continue to YouTube</title></html>"))
        assertNull(IssDirectPc.extrait("""<title>NASA - YouTube</title><link rel="canonical" href="https://www.youtube.com/channel/UCLA_DiR1FfKNvjuUpBHmylQ">"""))
    }

    @Test
    fun une_heure_en_cache_le_dernier_bon_garde() {
        var t = 1_000_000L
        var n = 0
        var reponse = page
        val i = IssDirectPc(telecharge = { n++; reponse }, horloge = { t })
        assertEquals("ROboUHfPUrU", i.direct()!!.id)
        t += 30 * 60_000L; i.direct(); assertEquals(1, n)
        // An hour later: asked again; not the station's live any more → the last good one kept, asked again five minutes on.
        reponse = "<title>Something else</title>"
        t += 31 * 60_000L
        assertEquals("ROboUHfPUrU", i.direct()!!.id); assertEquals(2, n)
        t += 60_000L; i.direct(); assertEquals(2, n)
        t += 5 * 60_000L; i.direct(); assertEquals(3, n)
        val o = JSONObject(String(i.sert().corps))
        assertEquals("ROboUHfPUrU", o.getString("id")); assertEquals(IssDirectPc.CHAINE, o.getString("chaine"))
    }

    @Test
    fun sans_internet_rien_mais_le_repli() {
        val i = IssDirectPc(telecharge = { throw java.io.IOException("hors ligne") })
        assertNull(i.direct())
        val o = JSONObject(String(i.sert().corps))
        assertTrue(o.isNull("id")); assertEquals("ROboUHfPUrU", o.getString("dernier"))
    }
}
