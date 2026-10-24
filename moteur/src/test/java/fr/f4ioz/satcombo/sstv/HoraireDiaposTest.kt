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
import fr.f4ioz.satcombo.domain.ActivitesAVenir
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class HoraireDiaposTest {

    private val t0 = 1_792_700_000_000L   // 22/10/2026

    /** Slide [n] of the loop begun at [debut] with period [p] (s), [k] loops later, a jitter of a few seconds. */
    private fun repere(debut: Long, p: Double, k: Int, n: Int, r: Random) =
        HoraireDiapos.Repere(debut + ((k * 12 + n - 1) * p * 1000).toLong() + r.nextLong(-2000, 2000), n)

    @Test fun la_cadence_annoncee_se_lit() {
        assertEquals(156.0, HoraireDiapos.periode("36 s on / 2 min off"), 1e-9)
        assertEquals(156.0, HoraireDiapos.periode("36 seconds on with 2 minutes off"), 1e-9)
        assertEquals(126.0, HoraireDiapos.periode("36 s on / 1,5 min off"), 1e-9)
        assertEquals(HoraireDiapos.PERIODE_ANNONCEE_S, HoraireDiapos.periode(""), 1e-9)
    }

    @Test fun une_seule_image_donne_l_horaire_annonce() {
        val m = HoraireDiapos.ajuste(listOf(HoraireDiapos.Repere(t0, 7)))!!
        assertEquals(156.0, m.periodeS, 1e-9)
        assertTrue(!m.ajustee)
        assertEquals(7, HoraireDiapos.diapoA(m, t0))
        assertEquals(8, HoraireDiapos.diapoA(m, t0 + 156_000L))
        assertEquals(7, HoraireDiapos.diapoA(m, t0 + 12 * 156_000L))
        assertNull(HoraireDiapos.ajuste(emptyList()))
    }

    @Test fun la_periode_s_ajuste_et_une_relance_de_la_boucle_se_voit() {
        val r = Random(1)
        val p = 158.8
        // Four passes a day, four slides each, over a day; then the loop restarted 70 s later.
        val l = ArrayList<HoraireDiapos.Repere>()
        for (orbite in 0 until 5) for (i in 0 until 4) {
            val k = orbite * 35 + i          // ~92 min between passes
            l += repere(t0, p, k / 12, k % 12 + 1, r)
        }
        val relance = t0 + 6 * 35 * (p * 1000).toLong() + 70_000L
        for (i in 0 until 4) l += repere(relance, p, 0, 3 + i, r).copy(diapo = 3 + i)
        // A picture numbered wrong: left out, not taken for a restart.
        l += HoraireDiapos.Repere(t0 + (36 * p * 1000).toLong(), 9)
        val m = HoraireDiapos.ajuste(l.shuffled(r), 12, 156.0)!!
        assertTrue(m.ajustee)
        assertEquals(p, m.periodeS, 0.1)
        assertTrue("résidu ${m.residuS}", m.residuS < 3.0)
        assertEquals(1, m.ruptures.size)
        assertEquals(4, m.reperesDernier)
        assertEquals(24, m.reperes)
        // The last stretch's phase for what comes; the first's for its own pictures.
        val apres = relance + 4 * (p * 1000).toLong()   // slide 5 of the restarted loop
        assertEquals(5, HoraireDiapos.diapoA(m, apres))
        assertEquals(1, HoraireDiapos.diapoA(m, t0))
        assertTrue(HoraireDiapos.incertitudeS(m, apres + 86_400_000L) > HoraireDiapos.incertitudeS(m, apres))
    }

    private fun passage(aos: Long, los: Long, el: Double): SatPass {
        // A track of 30 s samples, a sine of its peak.
        val n = ((los - aos) / 30_000L).toInt().coerceAtLeast(3)
        val tr = (0 until n).map { i -> 0.0 to el * kotlin.math.sin(Math.PI * (i + 0.5) / n) }
        return SatPass("ISS", 25544, aos, los, el, 0.0, 180.0, false, false, tr)
    }

    @Test fun le_tableau_des_passages_dit_les_diapos_entieres_et_ce_qui_manque() {
        // Slide 1 begins at t0, one every 160 s.
        val m = HoraireDiapos.Modele(t0, 160.0)
        val p1 = passage(t0 + 100_000L, t0 + 700_000L, 60.0)        // 10 min
        val p2 = passage(t0 + 5_000_000L, t0 + 5_420_000L, 8.0)      // 7 min, low
        val tab = HoraireDiapos.tableau(m, listOf(p2, p1), 5.0, setOf(1, 2, 3, 4, 7, 8, 9, 10, 11, 12), t0)
        assertEquals(listOf(p1, p2), tab.passages.map { it.passage })
        val a = tab.passages[0]
        // AOS at +100 s: slide 1 (0–36 s) gone, 2 at 160 s… the last whole one ends before LOS (and above 5°).
        assertEquals(2, a.prevues.first().diapo)
        assertTrue(a.prevues.all { it.debutMs < p1.losEpochMs && it.finMs > p1.aosEpochMs })
        assertTrue(a.entieres.containsAll(listOf(3, 4)))
        assertEquals(listOf(5, 6), tab.manquantes)
        // Slide 5 (640–676 s) ends before the ISS goes under 5° (684 s): whole, the next chance for it; 6 (800 s) after LOS.
        assertTrue(5 in a.entieres)
        assertEquals(listOf(p1), tab.prochaines[5]!!.map { it.passage })
        assertTrue(tab.prochaines[6]!!.isEmpty())
        // The low pass: only what is sent above 5°.
        val b = tab.passages[1]
        assertTrue(b.debutHautMs > p2.aosEpochMs && b.finHautMs < p2.losEpochMs)
        for (d in b.prevues.filter { it.entiere }) assertTrue(d.debutMs >= b.debutHautMs && d.finMs <= b.finHautMs)
        for ((n, l) in tab.prochaines) for (ps in l) assertTrue(ps.prevues.any { it.diapo == n && it.entiere })
        // Nothing to come: no pass.
        assertTrue(HoraireDiapos.tableau(m, listOf(p1), 5.0, emptySet(), p1.losEpochMs + 1).passages.isEmpty())
    }

    @Test fun la_serie_vient_des_activites_a_venir() {
        val a = ActivitesAVenir.Activite(id = "x", type = ActivitesAVenir.Type.SSTV, satNom = "ISS", catnum = 25544,
            debutMs = t0, finMs = t0 + 4 * 86_400_000L, horaire = "36 s on / 2 min off", titre = "ARISS SSTV Series 34",
            sources = listOf(ActivitesAVenir.Source("ARISS")))
        val s = SerieSstv.choisit(listOf(a), emptyList(), t0 + 86_400_000L)!!
        assertEquals(156.0, s.periodeS, 1e-9)
        assertTrue(SerieSstv.dans(s, SstvMeta.SstvShot(fileName = "x.png", satName = "ISS", timeMs = t0 + 1000)))
        assertTrue(!SerieSstv.dans(s, SstvMeta.SstvShot(fileName = "x.png", satName = "RS-44", timeMs = t0 + 1000)))
        assertTrue(!SerieSstv.dans(s, SstvMeta.SstvShot(fileName = "x.png", satName = "ISS", timeMs = t0 - 86_400_000L)))
        assertNull(SerieSstv.choisit(listOf(a), emptyList(), t0 + 10 * 86_400_000L))
        assertEquals(5.0, SerieSstv.elMin(0.0), 1e-9)
        assertEquals(10.0, SerieSstv.elMin(10.0), 1e-9)
    }
}
