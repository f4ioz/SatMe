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
import fr.f4ioz.satcombo.domain.ControleAdif.Gravite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ADIF check, contact by contact. Fictitious contacts only. */
class ControleAdifTest {

    private val maintenant = 1_793_000_000_000L
    private val t0 = maintenant - 86_400_000L

    private fun bon(c: String = "F5ZZZ", t: Long = t0, sat: String = "SO-50") =
        LogEntry(t, sat, 27607, 100.0, 20.0, myLocator = "JN18FS", callsign = c, theirLocator = "JN03",
            mode = "FM", uplinkMhz = 145.850, downlinkMhz = 436.795)

    private fun codes(e: LogEntry) = ControleAdif.controle(listOf(e), maintenant).map { it.code }

    @Test
    fun `un contact propre ne dit rien`() {
        assertEquals(emptyList<String>(), codes(bon()))
        assertEquals(emptyList<String>(), codes(bon(c = "EA8/F4ZZZ/P")))
        assertEquals(emptyList<String>(), codes(bon(c = "TM100XYZ")))
    }

    @Test
    fun `chaque defaut est nomme avec sa gravite`() {
        val l = ControleAdif.controle(listOf(bon().copy(callsign = "")), maintenant)
        assertEquals("sans-indicatif", l.single().code)

        fun g(e: LogEntry, code: String) = ControleAdif.controle(listOf(e), maintenant).first { it.code == code }.gravite
        assertEquals(Gravite.BLOQUANT, g(bon(c = "F5 ZZZ"), "indicatif-caracteres"))
        assertEquals(Gravite.AVERTISSEMENT, g(bon(c = "FZZZ"), "indicatif-douteux"))
        assertEquals(Gravite.AVERTISSEMENT, g(bon().copy(theirLocator = "JN0"), "locator-invalide"))
        assertEquals(Gravite.BLOQUANT, g(bon().copy(uplinkMhz = 0.0), "montante-absente"))
        assertEquals(Gravite.BLOQUANT, g(bon().copy(uplinkMhz = 100.0), "montante-hors-bande"))
        assertEquals(Gravite.AVERTISSEMENT, g(bon().copy(downlinkMhz = 0.0), "descendante-absente"))
        assertEquals(Gravite.AVERTISSEMENT, g(bon().copy(downlinkMhz = 600.0), "descendante-hors-bande"))
        assertEquals(Gravite.BLOQUANT, g(bon().copy(mode = ""), "mode-absent"))
        assertEquals(Gravite.BLOQUANT, g(bon().copy(mode = "XYZ"), "mode-inconnu"))
        assertEquals(Gravite.AVERTISSEMENT, g(bon().copy(qth = "Besançon"), "non-ascii"))
        assertEquals(Gravite.AVERTISSEMENT, g(bon(t = maintenant + 3_600_000L), "date-future"))
    }

    @Test
    fun `une ligne de carres dans le locator est signalee comme corrigee a l-export`() {
        val a = ControleAdif.controle(listOf(bon().copy(myLocator = "JN06,JN16")), maintenant).single()
        assertEquals("grille-dans-locator", a.code)
        assertTrue(a.auto)
        assertTrue(a.message, a.message.contains("MY_VUCC_GRIDS"))
        val trois = ControleAdif.controle(listOf(bon().copy(theirLocator = "JN06,JN16,JN07")), maintenant).single()
        assertEquals("grille-invalide", trois.code)
    }

    @Test
    fun `le satellite inconnu de LoTW, avec la suggestion quand SatMe la connait`() {
        val iss = ControleAdif.controle(listOf(bon(sat = "ISS")), maintenant).single()
        assertEquals("satellite-lotw", iss.code)
        assertTrue(iss.message, iss.message.contains("ARISS"))
        val long = ControleAdif.controle(listOf(bon(sat = "RS-44 & BREEZE-KM R/B")), maintenant).single()
        assertTrue(long.message, long.message.contains("« RS-44 »"))
        assertTrue(ControleAdif.controle(listOf(bon(sat = "AO-07")), maintenant).single().message.contains("« AO-7 »"))
        // Terrestrial DATV has no satellite at all: nothing to say.
        assertEquals(emptyList<String>(), codes(bon(sat = "DATV terrestre")))
    }

    @Test
    fun `un doublon a moins de deux minutes, pas au-dela ni sur un autre satellite`() {
        val l = ControleAdif.controle(listOf(bon(t = t0), bon(c = "F5ZZZ/P", t = t0 + 60_000), bon(t = t0 + 400_000),
            bon(t = t0 + 410_000, sat = "AO-91")), maintenant)
        val d = l.filter { it.code == "doublon" }
        assertEquals(1, d.size)
        assertEquals(t0 + 60_000, d.single().t)
    }

    @Test
    fun `le json compte par gravite`() {
        val l = ControleAdif.controle(listOf(bon().copy(mode = ""), bon(t = t0 + 999_000).copy(qth = "Montréal")), maintenant)
        val j = ControleAdif.json(l, 2)
        assertEquals(1, j.getInt("bloquants"))
        assertEquals(1, j.getInt("avertissements"))
        assertEquals(2, j.getInt("contacts"))
        assertEquals("bloquant", j.getJSONArray("anomalies").getJSONObject(0).getString("g"))
    }
}
