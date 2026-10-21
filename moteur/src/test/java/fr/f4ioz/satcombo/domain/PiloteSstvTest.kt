/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import fr.f4ioz.satcombo.data.SatPass
import fr.f4ioz.satcombo.domain.CampagneSstv.Campagne
import fr.f4ioz.satcombo.domain.CampagneSstv.Statut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PiloteSstvTest {

    private val m = 60_000L
    private val t0 = 1_000_000 * m
    private var heure = t0

    /** The station as the auto-pilot sees it, every gesture noted. */
    private inner class Fausse : PiloteSstv.Station {
        val gestes = ArrayList<String>()
        var emission: String? = null
        var conflit: String? = null
        var erreurPrepare: String? = null
        var enregistre = false
        var images = listOf("SatMe_SSTV_ISS_a.png")
        override fun emission() = emission
        override fun conflit(c: Campagne) = conflit
        override fun capture() = mapOf("sstv.actif" to "0", "suivi" to "7530").also { gestes += "capture" }
        override fun prepare(c: Campagne, p: SatPass): PiloteSstv.Preparation {
            gestes += "prepare"
            return PiloteSstv.Preparation(listOf("ISS suivi", "SSTV prêt (Robot 36)"), erreurPrepare)
        }
        override fun enregistre(c: Campagne): String? { gestes += "enregistre"; enregistre = true; return null }
        override fun arreteEnregistrement(): String { gestes += "arrete"; enregistre = false; return "SatMe_ISS.wav" }
        override fun images(deMs: Long, aMs: Long) = images
        override fun parque(): String? { gestes += "parque"; return null }
        override fun restaure(avant: Map<String, String>): List<String> { gestes += "restaure " + avant["suivi"]; return listOf("état remis") }
        override fun annonce(texte: String) { gestes += "annonce" }
    }

    private val c = Campagne(id = "ariss", nom = "ARISS SSTV", catnum = 25544, satNom = "ISS", freqHz = 437_550_000L, modeSstv = "Robot 36",
        debutMs = t0, finMs = t0 + 3 * 86_400_000L, avantMin = 2, chaine = CampagneSstv.Chaine(parquer = true))
    private val p = SatPass("ISS", 25544, t0 + 60 * m, t0 + 70 * m, 45.0, 0.0, 0.0, false, false)

    private fun monte(): Triple<PiloteSstv, Fausse, ArrayList<CampagneSstv.Trace>> {
        val f = Fausse(); val h = ArrayList<CampagneSstv.Trace>()
        return Triple(PiloteSstv(f, { heure }) { h += it }, f, h)
    }
    private fun plan(h: List<CampagneSstv.Trace>, a: PiloteSstv) =
        CampagneSstv.plan(listOf(c), { listOf(p) }, h, heure, a.enCours?.let { it.campagne.id to it.passage.aosEpochMs })

    @Test fun `avant l'AOS la chaine, a l'AOS l'enregistrement, apres le LOS l'arret, le parcage et l'etat remis`() {
        val (a, f, h) = monte()
        heure = p.aosEpochMs - 3 * m
        a.tic(plan(h, a), true)
        assertNull("trop tôt", a.enCours)
        heure = p.aosEpochMs - 2 * m
        a.tic(plan(h, a), true)
        assertEquals(PiloteSstv.Phase.PRET, a.enCours!!.phase)
        assertEquals(listOf("capture", "prepare", "annonce"), f.gestes)
        heure = p.aosEpochMs + 1_000L
        a.tic(plan(h, a), true)
        assertEquals(PiloteSstv.Phase.EN_COURS, a.enCours!!.phase)
        assertTrue(f.enregistre)
        heure = p.losEpochMs + 10_000L
        a.tic(plan(h, a), true)
        assertTrue("encore 30 s après le LOS", f.enregistre)
        heure = p.losEpochMs + 31_000L
        a.tic(plan(h, a), true)
        assertNull(a.enCours)
        assertEquals(listOf("capture", "prepare", "annonce", "enregistre", "annonce", "arrete", "parque", "restaure 7530", "annonce"), f.gestes)
        val t = h.single()
        assertEquals(Statut.FAIT, t.statut); assertEquals("SatMe_ISS.wav", t.enregistrement); assertEquals(1, t.images.size)
        assertTrue(t.faits.contains("rotor parqué"))
        // Done: not taken again (the plan read as if it were still on).
        heure = p.losEpochMs
        assertEquals(Statut.FAIT, plan(h, a).single().statut)
    }

    @Test fun `une emission en cours, le passage est ecarte avec sa raison, rien n'est touche`() {
        val (a, f, h) = monte()
        f.emission = "DATV sur QO-100"
        heure = p.aosEpochMs - m
        a.tic(plan(h, a), true)
        assertNull(a.enCours)
        assertEquals(emptyList<String>(), f.gestes)
        assertEquals(Statut.ECARTE, h.single().statut)
        assertTrue(h.single().raison.contains("émission en cours"))
        // Said once, even with the plan not read again.
        a.tic(CampagneSstv.plan(listOf(c), { listOf(p) }, emptyList(), heure), true)
        assertEquals(1, h.size)
    }

    @Test fun `un recepteur tenu par la DATV, le passage est ecarte`() {
        val (a, f, h) = monte()
        f.conflit = "la DATV tient le récepteur SDR"
        heure = p.aosEpochMs - m
        a.tic(plan(h, a), true)
        assertEquals("la DATV tient le récepteur SDR", h.single().raison)
        assertEquals(emptyList<String>(), f.gestes)
    }

    @Test fun `une emission commencee avant l'AOS, le passage est laisse et l'etat n'est pas remis pendant l'emission`() {
        val (a, f, h) = monte()
        heure = p.aosEpochMs - m
        a.tic(plan(h, a), true)
        f.emission = "SSTV"
        a.tic(plan(h, a), true)
        assertNull(a.enCours)
        assertEquals(Statut.INTERROMPU, h.single().statut)
        assertTrue(f.gestes.none { it.startsWith("restaure") || it == "parque" })
    }

    @Test fun `la chaine qui echoue est defaite, STOP et l'interrupteur general arretent le passage`() {
        val (a, f, h) = monte()
        f.erreurPrepare = "poste non connecté"
        heure = p.aosEpochMs - m
        a.tic(plan(h, a), true)
        assertNull(a.enCours)
        assertTrue(f.gestes.contains("restaure 7530"))
        assertEquals("poste non connecté", h.single().raison)
        // STOP.
        val (b, g, hb) = monte()
        b.tic(plan(hb, b), true)
        b.stop()
        b.tic(plan(hb, b), true)
        assertEquals(Statut.ARRETE, hb.single().statut)
        assertTrue(g.gestes.contains("restaure 7530"))
        // The master switch off.
        val (d, _, hd) = monte()
        d.tic(plan(hd, d), true)
        d.tic(plan(hd, d), false)
        assertEquals("auto-pilote coupé", hd.single().raison)
        // Off: nothing starts.
        val (e, ge, he) = monte()
        e.tic(plan(he, e), false)
        assertNull(e.enCours); assertTrue(ge.gestes.isEmpty())
    }

    @Test fun `apres un redemarrage en plein passage, il est repris avec l'etat d'avant`() {
        val (a, f, h) = monte()
        heure = p.aosEpochMs + 3 * m
        a.reprend(c, p, mapOf("suivi" to "43017"))
        assertEquals(PiloteSstv.Phase.EN_COURS, a.enCours!!.phase)
        assertTrue(f.enregistre)
        assertEquals(Statut.EN_COURS, plan(h, a).single().statut)
        heure = p.losEpochMs + 40_000L
        a.tic(plan(h, a), true)
        assertTrue(f.gestes.contains("restaure 43017"))
        assertTrue(h.single().faits.contains("repris après un redémarrage"))
        // Restarted mid-window with nothing kept: the pass is simply taken up again by the plan.
        val (b, g, hb) = monte()
        heure = p.aosEpochMs + 2 * m
        b.tic(plan(hb, b), true)
        assertEquals(PiloteSstv.Phase.EN_COURS, b.enCours!!.phase)
        assertTrue(g.enregistre)
    }
}
