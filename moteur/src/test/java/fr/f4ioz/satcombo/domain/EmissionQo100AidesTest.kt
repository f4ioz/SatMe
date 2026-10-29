/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import fr.f4ioz.satcombo.cw.DecodeurCw
import fr.f4ioz.satcombo.domain.MonteeQo100.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/** The narrow band's TX helpers: the level guard, listen before talk, the CW memories, the journal. Pure, no radio. */
class EmissionQo100AidesTest {

    @Test fun `la garde de niveau baisse puis coupe et ne monte jamais`() {
        val g = GardeNiveauQo100(seuilDb = 0.0)
        var t = 0L
        // Under the beacon: nothing.
        assertNull(g.tic(-3.0, -20.0, -89.0, t))
        assertNull(g.tic(null, -20.0, -89.0, t))
        // Above it: one measurement is not enough.
        assertNull(g.tic(2.2, -20.0, -89.0, t))
        t += 500; assertNull(g.tic(2.2, -20.0, -89.0, t))
        t += 600
        val d = g.tic(2.2, -20.0, -89.0, t)!!
        // The excess plus one dB, rounded up: 4 dB.
        assertEquals(-24.0, d.gainDb!!, 0.0)
        assertNull(d.coupe)
        // Then a pause for the downlink to follow.
        t += 500; assertNull(g.tic(1.0, -24.0, -89.0, t))
        t += 1_100; assertEquals(-26.0, g.tic(1.0, -24.0, -89.0, t)!!.gainDb!!, 0.0)
        // Far above: cut at once.
        assertNotNull(g.tic(7.0, -26.0, -89.0, t + 10)!!.coupe)
        // No room left: cut.
        val h = GardeNiveauQo100(seuilDb = -3.0)
        h.tic(0.5, -88.0, -89.0, 0)
        assertNotNull(h.tic(0.5, -88.0, -89.0, 2_000)!!.coupe)
        // Never a gain above the one in force.
        val k = GardeNiveauQo100()
        for (i in 0..50) k.tic(if (i % 2 == 0) 3.0 else -10.0, -30.0, -89.0, i * 700L)?.gainDb?.let { assertTrue(it < -30.0) }
    }

    @Test fun `ecouter avant d emettre`() {
        val cadran = MonteeQo100.cadranParDefaut(Mode.BLU)
        val d = Qo100.descenteDepuisMontee(cadran)
        val blu = SignalQo100(1, d + 1_500, 2_400, ClasseQo100.BLU, 0.9, 0, 200, 15.0, "")
        assertNotNull(OccupationQo100.occupant(listOf(blu), Mode.BLU, cadran))
        assertTrue(OccupationQo100.texte(OccupationQo100.occupant(listOf(blu), Mode.BLU, cadran)!!).contains("occupée"))
        // 5 kHz away, weak, old, or a beacon: free.
        assertNull(OccupationQo100.occupant(listOf(blu.copy(hz = d + 6_500)), Mode.BLU, cadran))
        assertNull(OccupationQo100.occupant(listOf(blu.copy(snrDb = 2.0)), Mode.BLU, cadran))
        assertNull(OccupationQo100.occupant(listOf(blu.copy(vuIlYaMs = 30_000)), Mode.BLU, cadran))
        assertNull(OccupationQo100.occupant(listOf(blu.copy(classe = ClasseQo100.BALISE)), Mode.BLU, cadran))
        // CW: only its 200 Hz around the carrier.
        val c = MonteeQo100.cadranParDefaut(Mode.CW)
        val p = Qo100.descenteDepuisMontee(c) + 700
        assertNotNull(OccupationQo100.occupant(listOf(blu.copy(hz = p + 120, largeurHz = 50, classe = ClasseQo100.CW)), Mode.CW, c))
        assertNull(OccupationQo100.occupant(listOf(blu.copy(hz = p + 600, largeurHz = 50, classe = ClasseQo100.CW)), Mode.CW, c))
    }

    @Test fun `memoires CW et leurs champs`() {
        val l = MacrosCw.PAR_DEFAUT
        assertEquals(MacrosCw.NOMBRE, l.size)
        assertEquals("CQ CQ CQ DE F4IOZ F4IOZ K" to null, MacrosCw.developpe(l[0].texte, "f4ioz", "", "JN18fs"))
        // A field needed and empty: refused, said why.
        val (s, err) = MacrosCw.developpe(l[1].texte, "F4IOZ", " ", "JN18FS")
        assertNull(s); assertTrue(err!!.contains("correspondant"))
        assertEquals("DL1ABC DE F4IOZ TU UR 579 579 <BK>", MacrosCw.developpe(l[2].texte, "F4IOZ", "dl1abc", "", "579").first)
        assertEquals("LOC JN18FS JN18FS <BK>", MacrosCw.developpe(l[3].texte, "F4IOZ", "", "jn18fs").first)
        // Cleaned: no stray characters, unknown prosigns dropped.
        assertEquals("CQ <AR> TEST", MacrosCw.nettoie("cq <ar> <zz>test!"))
        assertEquals("TST", MacrosCw.nettoie("tést"))
        // Stored and read back, one changed.
        val m = MacrosCw.change(l, 5, "Test", "test {moi}")
        assertEquals("TEST {MOI}", MacrosCw.lisListe(MacrosCw.ecrisListe(m))[5].texte)
        assertEquals(l, MacrosCw.lisListe("pas du json"))
    }

    @Test fun `les prosignes se manipulent d un seul tenant`() {
        // <AR> = .-.-. : 5 elements, no letter gap inside.
        val ar = DecodeurCw.manipulation("<AR>", 20)
        assertEquals(DecodeurCw.manipulation("+", 20), ar)
        assertEquals(9, ar.size)
        // A plain word unchanged.
        assertTrue(DecodeurCw.manipulation("AR", 20) != ar)
    }

    @Test fun `le journal des emissions`() {
        val j = JournalEmissionQo100(max = 3)
        val jour = 1_700_000_000_000L
        fun p(debut: Long, dest: String, ms: Long) = JournalEmissionQo100.Passage(debut, ms, "blu", 2_400_300_000L, -20.0, dest, "micro", -4.5, "PTT relâché")
        j.ajoute(p(jour, "air", 10_000)); j.ajoute(p(jour + 1, "charge", 50_000)); j.ajoute(p(jour + 2, "air", 5_000)); j.ajoute(p(jour + 3, "simulation", 1_000))
        assertEquals(3, j.liste().size)
        assertEquals("simulation", j.liste().first().destination)
        assertEquals(5_000L, j.surLAirAujourdhui(jour + 10, ZoneOffset.UTC))
        val k = JournalEmissionQo100().also { it.reprends(j.texte()) }
        assertEquals(j.liste(), k.liste())
        assertTrue(k.csv().lines().first().startsWith("debut_utc"))
        assertEquals(4, k.csv().trim().lines().size)
    }
}
