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
import fr.f4ioz.satcombo.data.SettingsStore
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PushAmsatTest {

    private val h = 3_600_000L
    private val m = 60_000L
    private val maintenant = 100 * h

    private fun qso(sat: String, t: Long, ind: String = "F4ABC") =
        LogEntry(timeMs = t, satName = sat, catnum = 0, azimuthDeg = 0.0, elevationDeg = 0.0, callsign = ind, myLocator = "JN18FS")

    @Test fun `un passage termine, signale une fois a son premier contact`() {
        val l = listOf(qso("ISS", maintenant - 40 * m), qso("ISS", maintenant - 35 * m), qso("ISS", maintenant - 30 * m))
        val r = PushAmsat.aEnvoyer(l, 0L, emptySet(), maintenant)
        assertEquals(1, r.size)
        assertEquals(maintenant - 40 * m, r[0].timeMs)
        assertEquals("JN18FS", r[0].locator)
        assertTrue(PushAmsat.aEnvoyer(l, 0L, setOf(r[0].cle), maintenant).isEmpty())
    }

    @Test fun `pas pendant le passage, ni avant l'activation, ni trop vieux`() {
        assertTrue(PushAmsat.aEnvoyer(listOf(qso("SO-50", maintenant - 5 * m)), 0L, emptySet(), maintenant).isEmpty())
        assertTrue(PushAmsat.aEnvoyer(listOf(qso("SO-50", maintenant - 2 * h)), maintenant - h, emptySet(), maintenant).isEmpty())
        assertTrue(PushAmsat.aEnvoyer(listOf(qso("SO-50", maintenant - 30 * h)), 0L, emptySet(), maintenant).isEmpty())
    }

    @Test fun `deux passages du meme satellite, deux satellites`() {
        val l = listOf(qso("AO-91", maintenant - 3 * h), qso("AO-91", maintenant - 3 * h + 5 * m),
            qso("AO-91", maintenant - h), qso("RS-44", maintenant - 2 * h))
        val r = PushAmsat.aEnvoyer(l, 0L, emptySet(), maintenant)
        assertEquals(listOf("AO-91", "RS-44", "AO-91"), r.map { it.sat })
    }

    @Test fun `rien sans l'option, puis un seul envoi`() {
        val s = SettingsStore(StockageMemoire())
        val envois = java.util.concurrent.CopyOnWriteArrayList<String>()
        val p = PushAmsat(s, { nom, _, _, _ -> envois += nom; true }, { maintenant })
        val l = listOf(qso("ISS", maintenant - 40 * m))
        p.tic(l, "F4IOZ", "JN18FS") { it }
        Thread.sleep(200)
        assertTrue(envois.isEmpty())
        s.amsatAuto = true
        // Turned on now: the contact before is not reported.
        p.tic(l, "F4IOZ", "JN18FS") { it }
        Thread.sleep(200)
        assertTrue(envois.isEmpty())
    }
}
