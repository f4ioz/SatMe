/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import fr.f4ioz.satcombo.data.Adif
import fr.f4ioz.satcombo.data.LogEntry
import fr.f4ioz.satcombo.data.LogStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RedateContactTest {

    // 2026-10-20 18:00:00 UTC
    private val t0 = 1_792_519_200_000L
    private val a = LogEntry(t0, "RS-44", 44909, 120.0, 30.0, callsign = "F5ABC", mode = "USB")
    private val b = LogEntry(t0 - 3_600_000L, "SO-50", 27607, 200.0, 10.0, callsign = "EA4XYZ", mode = "FM")
    private val c = LogEntry(t0 - 7_200_000L, "QO-100", Qo100.NORAD, 160.0, 32.0, callsign = "G4AAA", mode = "USB")

    @Test
    fun lit_la_date_et_l_heure_en_utc() {
        assertEquals(t0, RedateContact.lis("2026-10-20", "18:00"))
        assertEquals(t0 + 42_000L, RedateContact.lis("2026-10-20", "18:00:42"))
        assertEquals(t0 + 42_000L, RedateContact.lis("20/10/2026", "180042"))
        assertEquals(t0, RedateContact.lis(" 2026-10-20 ", "1800"))
        assertNull(RedateContact.lis("2026-02-30", "12:00"))
        assertNull(RedateContact.lis("2026-10-20", "24:00"))
        assertNull(RedateContact.lis("2026-10-20", "12:60"))
        assertNull(RedateContact.lis("hier", "12:00"))
        assertEquals(-254_188_800_000L, RedateContact.lis("1961-12-12", "00:00"))
    }

    @Test
    fun ni_futur_ni_avant_1961() {
        assertNull(RedateContact.pourquoiPas(t0, t0))
        assertNull(RedateContact.pourquoiPas(t0 + 4 * 60_000L, t0))
        assertNotNull(RedateContact.pourquoiPas(t0 + 10 * 60_000L, t0))
        assertNotNull(RedateContact.pourquoiPas(RedateContact.lis("1960-01-01", "00:00")!!, t0))
        assertNull(RedateContact.pourquoiPas(RedateContact.lis("1962-01-01", "00:00")!!, t0))
    }

    @Test
    fun l_ordre_du_carnet_suit() {
        // a moved two and a half hours back: after b and c, newest first.
        val nouveau = t0 - 9_000_000L
        val r = RedateContact.redate(listOf(a, b, c), t0, nouveau) { _, _ -> 300.0 to 5.0 }!!
        assertEquals(listOf(b.timeMs, c.timeMs, nouveau), r.log.map { it.timeMs })
        assertEquals("F5ABC", r.contact.callsign)
        assertEquals(nouveau, r.log.last().timeMs)
    }

    @Test
    fun az_el_recalcules_a_la_nouvelle_heure() {
        var demande = 0L
        val r = RedateContact.redate(listOf(a, b), t0, t0 - 60_000L) { e, t -> demande = t; assertEquals(44909, e.catnum); 250.5 to 12.25 }!!
        assertEquals(t0 - 60_000L, demande)
        assertEquals(250.5, r.contact.azimuthDeg, 0.0); assertEquals(12.25, r.contact.elevationDeg, 0.0)
        // Unknown satellite then: cleared, not left describing another moment.
        val inconnu = RedateContact.redate(listOf(a), t0, t0 - 60_000L) { _, _ -> null }!!
        assertEquals(0.0, inconnu.contact.azimuthDeg, 0.0); assertEquals(0.0, inconnu.contact.elevationDeg, 0.0)
        // QO-100 is geostationary: the aim stays.
        val q = RedateContact.redate(listOf(c), c.timeMs, c.timeMs - 86_400_000L) { _, _ -> error("not asked") }!!
        assertEquals(160.0, q.contact.azimuthDeg, 0.0); assertEquals(32.0, q.contact.elevationDeg, 0.0)
    }

    @Test
    fun adif_date_et_heure_suivent() {
        val nouveau = RedateContact.lis("2026-10-19", "23:59:07")!!
        val r = RedateContact.redate(listOf(a), t0, nouveau) { _, _ -> 1.0 to 2.0 }!!
        val adif = Adif.export(r.log)
        assertTrue(adif, adif.contains("<QSO_DATE:8>20261019"))
        assertTrue(adif, adif.contains("<TIME_ON:6>235907"))
    }

    @Test
    fun cle_libre_et_doublon() {
        // On another contact's very time: a millisecond after, the key stays unique.
        val r = RedateContact.redate(listOf(a, b), t0, b.timeMs) { _, _ -> null }!!
        assertEquals(b.timeMs + 1, r.contact.timeMs)
        assertEquals(2, r.log.map { it.timeMs }.toSet().size)
        // Same callsign, same satellite, five minutes apart: a duplicate said.
        val a2 = a.copy(timeMs = t0 - 3 * 3_600_000L)
        val d = RedateContact.redate(listOf(a, a2), a2.timeMs, t0 - 5 * 60_000L) { _, _ -> null }!!
        assertEquals(listOf(t0), d.doublons.map { it.timeMs })
        assertTrue(RedateContact.avertissement(d.doublons)!!.contains("F5ABC"))
        // Another satellite: no duplicate.
        assertTrue(RedateContact.doublons(b.copy(callsign = "F5ABC", timeMs = t0 - 60_000L), listOf(a)).isEmpty())
    }

    @Test
    fun le_magasin_garde_l_ordre() {
        val f = java.io.File.createTempFile("carnet", ".json"); f.deleteOnExit()
        val s = LogStore(f)
        s.save(listOf(a, b, c))
        val r = RedateContact.redate(s.load(), c.timeMs, t0 + 60_000L) { _, _ -> null }!!
        s.save(r.log)
        assertEquals(listOf(t0 + 60_000L, t0, b.timeMs), s.load().map { it.timeMs })
    }
}
