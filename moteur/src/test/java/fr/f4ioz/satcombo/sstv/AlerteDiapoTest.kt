/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

import fr.f4ioz.satcombo.data.SatPass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class AlerteDiapoTest {

    private val t0 = 1_792_700_000_000L
    private val p = 156.0

    /** Slide 1 begins at [t0]: slide n of loop k at t0 + ((k·12 + n − 1)·p) s. */
    private fun debut(k: Int, n: Int) = t0 + ((k * 12 + n - 1) * p * 1000).toLong()

    private fun shot(n: String, t: Long) = SstvMeta.SstvShot(n, "ISS", t, mode = "Robot 36")

    /** A series with slides 1–4 and 6 received (6 marked [note6]), a pass sending 5 and 6 whole. */
    private fun prevision(note6: Int, maintenant: Long): Pair<SerieSstv.Prevision, SatPass> {
        val serie = SerieSstv.Serie("ARISS SSTV Series 34", "ISS", 25544, t0 - 86_400_000L, t0 + 5 * 86_400_000L)
        val groupes = (listOf(1, 2, 3, 4, 6)).map { d ->
            val s = shot("SatMe_SSTV_ISS_$d.png", debut(0, d))
            ClassementSstv.Groupe("g$d", listOf(s), d, 12, ClassementSstv.Origine.MAIN, mapOf(s.fileName to if (d == 6) note6 else 95))
        }
        val m = HoraireDiapos.ajuste(groupes.map { HoraireDiapos.Repere(it.images.first().timeMs, it.diapo) })!!
        // The pass: from slide 5's start less a minute, to slide 6's end plus a minute, of loop 3.
        val aos = debut(3, 5) - 60_000L; val los = debut(3, 6) + 36_000L + 60_000L
        val track = (0 until 20).map { i -> 180.0 to 10.0 + 40.0 * Math.sin(Math.PI * (i + 0.5) / 20) }
        val pass = SatPass("ISS", 25544, aos, los, 49.0, 200.0, 40.0, false, false, track)
        val tab = HoraireDiapos.tableau(m, listOf(pass), 5.0, groupes.map { it.diapo }.toSet(), maintenant)
        return SerieSstv.Prevision(serie, groupes, m, tab, groupes.map { it.diapo }.toSet(), 5.0, emptyList()) to pass
    }

    @Test fun une_diapo_manquante_attendue_est_annoncee_une_fois_avant_le_passage() {
        val avant = debut(3, 5) - 3_600_000L
        val (pr, pass) = prevision(90, avant)
        val r = AlerteDiapo.Reglages(true, 15, 60)
        val l = AlerteDiapo.alertes(pr, r, avant)
        assertEquals(1, l.size)
        val a = l.first()
        assertEquals(listOf(5), a.diapos.map { it.diapo })
        assertEquals(debut(3, 5) - 15 * 60_000L, a.quandMs)
        // Not due yet, then due, then said: never again.
        assertTrue(AlerteDiapo.dues(l, emptySet(), a.quandMs - 1).isEmpty())
        assertEquals(1, AlerteDiapo.dues(l, emptySet(), a.quandMs).size)
        assertTrue(AlerteDiapo.dues(l, setOf(a.cle), a.quandMs + 1000).isEmpty())
        // The same pass seen again (the list worked out again): the same key.
        assertEquals(a.cle, AlerteDiapo.alertes(pr, r, avant + 60_000L).first().cle)
        // A pass begun long ago: not said.
        assertTrue(AlerteDiapo.dues(l, emptySet(), pass.aosEpochMs + 120_000L).isEmpty())
        // Kept and read back.
        assertEquals(setOf(a.cle), AlerteDiapo.litDites(AlerteDiapo.ecritDites(setOf(a.cle))))
    }

    @Test fun une_diapo_mal_notee_est_encore_voulue_et_le_texte_le_dit() {
        val avant = debut(3, 5) - 3_600_000L
        val (pr, _) = prevision(40, avant)
        val a = AlerteDiapo.alertes(pr, AlerteDiapo.Reglages(true, 15, 60), avant).single()
        assertEquals(listOf(5, 6), a.diapos.map { it.diapo })
        assertEquals(40, a.diapos.first { it.diapo == 6 }.meilleure)
        val txt = AlerteDiapo.texte(a, TimeZone.getTimeZone("Europe/Paris"))
        assertTrue(txt, txt.contains("UTC") && txt.contains("5") && txt.contains("ISS"))
        // Threshold 0: only those missing.
        assertEquals(listOf(5), AlerteDiapo.alertes(pr, AlerteDiapo.Reglages(true, 15, 0), avant).single().diapos.map { it.diapo })
        // Off: nothing.
        assertTrue(AlerteDiapo.alertes(pr, AlerteDiapo.Reglages(false, 15, 60), avant).isEmpty())
    }

    @Test fun l_elevation_suit_la_trace() {
        val track = listOf(0.0 to 0.0, 0.0 to 50.0, 0.0 to 0.0)
        val pass = SatPass("ISS", 25544, 0L, 300_000L, 50.0, 0.0, 0.0, false, false, track)
        assertEquals(50.0, AlerteDiapo.elevation(pass, 150_000L), 1.0)
        assertTrue(AlerteDiapo.elevation(pass, 10_000L) < 10.0)
    }
}
