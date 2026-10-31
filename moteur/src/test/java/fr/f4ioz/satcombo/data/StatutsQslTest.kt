/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.data

import fr.f4ioz.satcombo.data.StatutsQsl.Etat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** QSL statuses read from a (fictitious) Wavelog ADIF and laid on the local log. */
class StatutsQslTest {

    // 2026-10-20 10:15:00 UTC.
    private val t = AdifImport.instant("20261020", "101500")!!

    private val adif = "Wavelog<EOH>\n" +
        "<CALL:5>F5ZZZ<QSO_DATE:8>20261020<TIME_ON:6>101530<SAT_NAME:5>SO-50<PROP_MODE:3>SAT" +
        "<LOTW_QSL_SENT:1>Y<LOTW_QSL_RCVD:1>Y<EQSL_QSL_SENT:1>Y<EQSL_QSL_RCVD:1>N<QSL_SENT:1>N<QSL_RCVD:1>N<EOR>\n" +
        "<CALL:8>EA4ZZZ/P<QSO_DATE:8>20261020<TIME_ON:4>1018<SAT_NAME:5>SO-50<PROP_MODE:3>SAT" +
        "<LOTW_QSL_SENT:1>Y<LOTW_QSL_RCVD:1>N<QSL_SENT:1>Y<QSL_RCVD:1>Y<EOR>\n" +
        "<CALL:5>G0ZZZ<QSO_DATE:8>20261020<TIME_ON:6>120000<BAND:3>20m<LOTW_QSL_RCVD:1>Y<EOR>\n"

    @Test
    fun `les drapeaux se lisent, les contacts non satellite non`() {
        val l = StatutsQsl.lis(adif)
        assertEquals(2, l.size)
        assertEquals(Etat.CONFIRME, StatutsQsl.lotw(l[0]))
        assertEquals(Etat.ATTENTE, StatutsQsl.eqsl(l[0]))
        assertEquals(Etat.AUCUN, StatutsQsl.papier(l[0]))
        assertEquals(Etat.ATTENTE, StatutsQsl.lotw(l[1]))
        assertEquals(Etat.CONFIRME, StatutsQsl.papier(l[1]))
        assertEquals(Etat.AUCUN, StatutsQsl.eqsl(l[1]))
        assertEquals(Etat.AUCUN, StatutsQsl.etat("I", "Y"))
        assertEquals(Etat.CONFIRME, StatutsQsl.etat("V", ""))
    }

    @Test
    fun `chaque contact local retrouve le sien a quelques minutes pres`() {
        val locaux = listOf(
            LogEntry(t, "SAUDISAT 1C (SO-50)", 27607, 0.0, 10.0, callsign = "F5ZZZ"),
            LogEntry(t + 3 * 60_000L, "SO-50", 27607, 0.0, 10.0, callsign = "EA4ZZZ"),
            // Too far in time, or another satellite: no match.
            LogEntry(t + 30 * 60_000L, "SO-50", 27607, 0.0, 10.0, callsign = "F5ZZZ"),
            LogEntry(t + 60_000L, "AO-91", 43017, 0.0, 10.0, callsign = "EA4ZZZ"),
        )
        val m = StatutsQsl.associe(locaux, StatutsQsl.lis(adif))
        assertEquals("F5ZZZ", m[t]?.indicatif)
        assertEquals("EA4ZZZ/P", m[t + 3 * 60_000L]?.indicatif)
        assertNull(m[t + 30 * 60_000L])
        assertNull(m[t + 60_000L])
        val b = StatutsQsl.bilan(locaux, m)
        assertEquals(2, b.releves); assertEquals(2, b.sansStatut)
        assertEquals(1, b.lotwConfirmes); assertEquals(1, b.lotwAttente)
        assertEquals(0, b.eqslConfirmes); assertEquals(1, b.eqslAttente)
        assertEquals(1, b.papierConfirmes)
    }

    @Test
    fun `un contact en ligne ne sert qu-une fois, au plus proche`() {
        val s = StatutsQsl.lis(adif).first()
        val locaux = listOf(LogEntry(t - 60_000L, "SO-50", 0, 0.0, 0.0, callsign = "F5ZZZ"),
            LogEntry(t + 30_000L, "SO-50", 0, 0.0, 0.0, callsign = "F5ZZZ"))
        val m = StatutsQsl.associe(locaux, listOf(s))
        assertEquals(1, m.size)
        assertTrue((t + 30_000L) in m)
    }

    @Test
    fun `le magasin garde, remplace et se relit`() {
        val f = File(System.getProperty("java.io.tmpdir"), "satme-qsl-" + System.nanoTime() + ".json")
        try {
            val st = StatutsQsl.Store(f)
            val l = StatutsQsl.lis(adif)
            st.fusionne(l, mapOf("1" to 42L), complet = true, maintenant = 1000L)
            // A later read: the eQSL arrived.
            st.fusionne(listOf(l[0].copy(eqslRecu = "Y")), mapOf("1" to 43L), complet = false, maintenant = 2000L)
            val relu = StatutsQsl.Store(f)
            assertEquals(2, relu.statuts().size)
            assertEquals(Etat.CONFIRME, StatutsQsl.eqsl(relu.statuts().first { it.indicatif == "F5ZZZ" }))
            assertEquals(43L, relu.curseur("1"))
            assertEquals(1000L, relu.completMs())
            assertEquals(2000L, relu.releveMs())
            assertFalse(StatutsQsl.json(l[1]).has("eqsl"))
        } finally { f.delete() }
    }

    @Test
    fun `l-indicatif de base pour l-appariement`() {
        assertEquals("F4ZZZ", StatutsQsl.base("EA8/F4ZZZ/P"))
        assertEquals("F4ZZZ", StatutsQsl.base("f4zzz"))
    }
}
