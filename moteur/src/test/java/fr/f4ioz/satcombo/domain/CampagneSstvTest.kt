/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import fr.f4ioz.satcombo.data.Observer
import fr.f4ioz.satcombo.data.SatPass
import fr.f4ioz.satcombo.data.TleEntry
import fr.f4ioz.satcombo.domain.CampagneSstv.Campagne
import fr.f4ioz.satcombo.domain.CampagneSstv.Statut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CampagneSstvTest {

    private val m = 60_000L
    private val iss = TleEntry("ISS (ZARYA)",
        "1 25544U 98067A   24001.50000000  .00016717  00000-0  30777-3 0  9990",
        "2 25544  51.6416 247.4627 0006703 130.5360 325.0288 15.49512407430303")
    private val paris = Observer(48.80, 2.48, 50.0, "JN18FS")
    private val t0 = 1704110400000L   // 2024-01-01 12:00 UTC, the elements' epoch

    private fun campagne(id: String = "ariss", de: Long = t0, a: Long = t0 + 3 * 86_400_000L, el: Double = 10.0) =
        Campagne(id = id, nom = "ARISS SSTV", catnum = 25544, satNom = "ISS", freqHz = 437_550_000L, modeSstv = "Robot 36",
            debutMs = de, finMs = a, elMin = el)

    @Test fun `les passages de l'ISS dans la periode, au-dessus de l'elevation, avec les vrais elements`() {
        val c = campagne(de = t0 + 86_400_000L, a = t0 + 2 * 86_400_000L, el = 20.0)
        val tous = PassPredictor().upcomingPasses(iss, paris, t0, 72, 0.0)
        val l = CampagneSstv.passages(c, tous)
        assertTrue("au moins un passage haut sur une journée", l.isNotEmpty())
        assertTrue(l.all { it.maxElevationDeg >= 20.0 && it.losEpochMs > c.debutMs && it.aosEpochMs < c.finMs })
        // Passes outside the window, or too low, are left out.
        assertTrue(tous.size > l.size)
        assertEquals(l.sortedBy { it.aosEpochMs }, l)
    }

    private fun p(n: Int, aos: Long, min: Int, el: Double = 40.0) = SatPass("S$n", n, aos, aos + min * m, el, 0.0, 0.0, false, false)

    @Test fun `deux campagnes en meme temps, la premiere garde son passage, l'autre est perdue`() {
        val a = campagne("a")
        val b = campagne("b").copy(catnum = 7530, satNom = "AO-07")
        val pa = p(25544, t0 + 60 * m, 10)
        val pb = p(7530, t0 + 65 * m, 10)
        val pb2 = p(7530, t0 + 200 * m, 10)
        val plan = CampagneSstv.plan(listOf(a, b), { c -> if (c.id == "a") listOf(pa) else listOf(pb, pb2) }, emptyList(), t0)
        assertEquals(listOf(Statut.PREVU, Statut.CHEVAUCHE, Statut.PREVU), plan.map { it.statut })
        assertTrue(plan[1].raison.contains("ARISS"))
    }

    @Test fun `un passage fait ou ecarte garde ce qu'il est devenu, meme si son AOS a un peu bouge`() {
        val c = campagne()
        val pa = p(25544, t0 + 60 * m, 10)
        val t = CampagneSstv.Trace("ariss", "ARISS", 25544, "ISS", pa.aosEpochMs - 40_000L, pa.losEpochMs, 40.0, Statut.ECARTE, "émission en cours")
        val plan = CampagneSstv.plan(listOf(c), { listOf(pa) }, listOf(t), t0)
        assertEquals(Statut.ECARTE, plan.single().statut)
        assertEquals("émission en cours", plan.single().raison)
        // Inactive: nothing planned; over: gone.
        assertTrue(CampagneSstv.plan(listOf(c.copy(active = false)), { listOf(pa) }, emptyList(), t0).isEmpty())
        assertTrue(CampagneSstv.plan(listOf(c), { listOf(pa) }, emptyList(), pa.losEpochMs + 31_000L).isEmpty())
    }

    @Test fun `la campagne est verifiee et relue telle quelle`() {
        assertTrue(CampagneSstv.valide(campagne().copy(finMs = t0)).isFailure)
        assertTrue(CampagneSstv.valide(campagne().copy(freqHz = 0L, emetteur = "")).isFailure)
        assertTrue(CampagneSstv.valide(campagne().copy(catnum = 0)).isFailure)
        val v = CampagneSstv.valide(campagne().copy(lien = "javascript:alert(1)", modeSstv = "Inconnu", elMin = 120.0, nom = "<b>x</b>")).getOrThrow()
        assertEquals("", v.lien); assertEquals(CampagneSstv.AUTO, v.modeSstv); assertEquals(90.0, v.elMin, 0.0); assertFalse(v.nom.contains("<"))
        val c = campagne().copy(lien = "https://ariss-usa.org/ARISS_SSTV/", horaire = "36 s on / 2 min off",
            chaine = CampagneSstv.Chaine(profil = "sstv-par-ic-9700", connecterPoste = true, parquer = true))
        val f = CampagneSstv.Fichier(true, listOf(c), listOf(CampagneSstv.Trace("ariss", "ARISS", 25544, "ISS", 1, 2, 30.0, Statut.FAIT, images = listOf("a.png"))),
            CampagneSstv.EnCoursSauve("ariss", 5L, mapOf("sstv.actif" to "0")))
        val r = CampagneSstv.lit(CampagneSstv.ecrit(f).toString())
        assertEquals(f, r)
        assertEquals(CampagneSstv.Fichier(), CampagneSstv.lit("pas du json"))
    }

    @Test fun `un identifiant tire du nom, unique`() {
        val a = campagne("ariss-sstv-series-34")
        assertEquals("ariss-sstv-series-34-2", CampagneSstv.nouvelId("ARISS SSTV Series 34", listOf(a)))
        assertEquals("evenement-ete", CampagneSstv.nouvelId("Événement été", emptyList()))
    }
}
