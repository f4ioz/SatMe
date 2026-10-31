/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import fr.f4ioz.satcombo.data.LogEntry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A contact changed after its upload: the changes, the APIv2 body, the one online contact it is. */
class ModifEnvoiTest {

    private val t = 1_791_800_000_000L  // 2026-10-12 10:13:20 UTC
    private val base = LogEntry(t, "ISS", 25544, 120.0, 20.0, myLocator = "JN18FS", callsign = "F5AAA", theirLocator = "JN03",
        mode = "USB", rstSent = "59", rstRcvd = "57", downlinkMhz = 145.8, uplinkMhz = 435.1)
    private fun envoye(e: LogEntry = base) = e.copy(envoyeMs = t + 1000, envoiAdif = ModifEnvoi.ecrit(ModifEnvoi.empreinte(e)))

    @Test fun `rien tant que rien n'a change, ni pour un contact pas envoye`() {
        assertFalse(ModifEnvoi.modifie(envoye()))
        assertFalse(ModifEnvoi.modifie(base.copy(callsign = "F5AAB")))
        assertFalse("sent before SatMe kept it: nothing known", ModifEnvoi.modifie(base.copy(envoyeMs = 1, callsign = "F5AAB")))
        // The azimuth is not what Wavelog cares about.
        assertFalse(ModifEnvoi.modifie(envoye().copy(azimuthDeg = 200.0)))
    }

    @Test fun `les changements, et l'annulation qui les efface`() {
        val e = envoye().copy(callsign = "F5AAB", rstRcvd = "55")
        val l = ModifEnvoi.ecarts(e)
        assertEquals(listOf("CALL", "RST_RCVD"), l.map { it.champ })
        assertEquals("indicatif F5AAA → F5AAB ; report reçu 57 → 55", ModifEnvoi.texte(l))
        assertFalse(ModifEnvoi.modifie(e.copy(callsign = "F5AAA", rstRcvd = "57")))
        assertEquals(l, ModifEnvoi.ecartsMemo(e))
    }

    @Test fun `le corps du PATCH - date et heure ensemble, frequences en Hz, le sous-mode`() {
        val e = envoye().copy(timeMs = t + 120_000, uplinkMhz = 435.12, mode = "LSB")
        val o = ModifEnvoi.corpsV2(e, ModifEnvoi.ecarts(e))!!
        assertEquals("2026-10-12", o.getString("qso_date"))
        assertEquals("101520", o.getString("time_on"))
        assertEquals(435_120_000L, o.getLong("freq"))
        assertEquals("LSB", o.getString("mode"))
        assertFalse(o.has("call"))
        // Only what APIv2 cannot change: no body.
        val g = envoye().copy(courriel = "a@b.c")
        assertNull(ModifEnvoi.corpsV2(g, ModifEnvoi.ecarts(g)))
        assertFalse(ModifEnvoi.ecarts(g).single().modifiable)
    }

    @Test fun `le contact en ligne - un seul, par l'ancien indicatif et l'ancienne heure`() {
        val avant = ModifEnvoi.empreinte(base)
        val l = ModifEnvoi.litListeV2("""{"data":[
            {"id":7,"call":"F5AAA","qso_date":"2026-10-12 10:13:20","sat_name":"ISS","station_id":1},
            {"id":8,"call":"F5AAA","qso_date":"2026-10-12 18:00:00","sat_name":"ISS","station_id":1},
            {"id":9,"call":"F5AAA","qso_date":"2026-10-12 10:13:40","sat_name":"SO-50","station_id":1}],"meta":{}}""")
        assertEquals(3, l.size)
        assertEquals(7L, ModifEnvoi.trouveV2(avant, l)!!.id)
        // Two alike: none — changing the wrong one is worse.
        val deux = l + l.first().copy(id = 70)
        assertNull(ModifEnvoi.trouveV2(avant, deux))
        assertNull(ModifEnvoi.trouveV2(avant, emptyList()))
        assertNull(ModifEnvoi.litListeV2("<html>").firstOrNull())
        assertEquals(t, ModifEnvoi.instantV2("2026-10-12", "10:13:20"))
    }

    @Test fun `apres le PATCH - ce que l'APIv2 ne change pas reste marque`() {
        val e = envoye().copy(theirLocator = "JN03,JN13", rstSent = "55")
        val l = ModifEnvoi.ecarts(e)
        val apres = ModifEnvoi.apresV2(e, ModifEnvoi.lit(e.envoiAdif)!!, l)
        val reste = ModifEnvoi.ecarts(e.copy(envoiAdif = ModifEnvoi.ecrit(apres)))
        assertEquals(listOf("VUCC_GRIDS"), reste.map { it.champ })
        assertTrue(ModifEnvoi.raisonV2("""{"error":{"code":"validation_error","message":"Invalid callsign"}}""") == "Invalid callsign")
        assertEquals("x", JSONObject(ModifEnvoi.ecrit(mapOf("a" to "x"))).getString("a"))
    }
}
