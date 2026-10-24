/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.station

import fr.f4ioz.satcombo.station.ActiviteMontage.Activite
import fr.f4ioz.satcombo.station.ActiviteMontage.Etat
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlansMontageTest {

    /** The owner's real file: one chain, version 1. */
    private val ANCIEN = """{"v":1,"nom":"SatMe-Node déporté","elements":[{"id":"e1","m":"parabole-qo100","nom":""},{"id":"e2","m":"lnb-pll","nom":""},
        {"id":"e3","m":"minitiouner","nom":""},{"id":"e4","m":"satme-node","nom":""},{"id":"e5","m":"pc","nom":""},{"id":"e6","m":"telephone","nom":"","x":700,"y":40}],
        "liens":[{"id":"l1","de":"e1.lnb","vers":"e2.entree","c":"DIRECT","note":""},{"id":"l2","de":"e2.sortie","vers":"e3.lnb_a","c":"COAX_F","note":""},
        {"id":"l3","de":"e3.usb","vers":"e4.usb","c":"USB","note":""},{"id":"l4","de":"e4.lan","vers":"e5.lan","c":"ETHERNET","note":""},
        {"id":"l5","de":"e5.wifi","vers":"e6.wifi","c":"WIFI","note":""}]}"""

    private fun noeud() = CatalogueMateriel.PREREGLAGES.first { it.id == "noeud-deporte" }.chaine
    private fun ic9700() = Montage.Bati("IC-9700 satellites").run {
        val y2 = ajoute("yagi-2m"); val y7 = ajoute("yagi-70cm"); val r = ajoute("ic9700"); val pc = ajoute("pc")
        relie(y2, "ant", r, "ant144", Montage.Cable.COAX_N); relie(y7, "ant", r, "ant432"); relie(r, "usb", pc, "usb")
        chaine()
    }
    /** The node and the IC-9700 in one plan. */
    private fun tout() = Montage.Bati("Tout").run {
        val d = ajoute("parabole-qo100"); val l = ajoute("lnb-pll"); val m = ajoute("minitiouner"); val n = ajoute("satme-node")
        val y7 = ajoute("yagi-70cm"); val r = ajoute("ic9700"); val pc = ajoute("pc")
        relie(d, "lnb", l, "entree"); relie(l, "sortie", m, "lnb_a"); relie(m, "usb", n, "usb"); relie(n, "lan", pc, "lan")
        relie(y7, "ant", r, "ant432"); relie(r, "usb", pc, "usb")
        chaine()
    }

    private val DATV = Activite(rx = setOf("datv:minitiouner", "noeud"))
    private val SSTV = Activite(rx = setOf("cat"))

    @Test fun lAncienFichierDevientLePremierPlanSansRienPerdre() {
        val p = PlansMontage.lit(ANCIEN)
        assertEquals(1, p.plans.size)
        assertEquals("p1", p.affiche)
        assertTrue(p.suivre)
        assertEquals(Montage.lit(ANCIEN), p.plans[0].chaine)
        assertEquals("SatMe-Node déporté", p.plans[0].nom)
        assertEquals(700, p.plans[0].chaine.element("e6")!!.x)
        // No file: one empty plan.
        assertEquals(1, PlansMontage.lit(null).plans.size)
        assertTrue(PlansMontage.lit("pas du json").choisi.chaine.vide)
    }

    @Test fun plusieursPlansSEcriventEtSeRelisent() {
        val p = PlansMontage.lit(ANCIEN).let { it.copy(plans = it.plans + PlansMontage.Plan("p2", ic9700()), affiche = "p2", suivre = false) }
        val texte = PlansMontage.ecrit(p).toString()
        val q = PlansMontage.lit(texte)
        assertEquals(listOf("p1", "p2"), q.plans.map { it.id })
        assertEquals(p.plans.map { it.chaine }, q.plans.map { it.chaine })
        assertEquals("p2", q.affiche); assertFalse(q.suivre)
        // An older SatMe reading the file sees the first plan as its single chain.
        assertEquals(Montage.lit(ANCIEN), Montage.lit(texte))
        assertEquals("p3", PlansMontage.nouvelId(q))
        assertEquals("IC-9700 satellites (2)", PlansMontage.nomLibre(q, "IC-9700 satellites"))
    }

    @Test fun lePlanQuiDessineCeQuiTourneEstChoisi() {
        val p = PlansMontage.Plans(listOf(PlansMontage.Plan("p1", noeud()), PlansMontage.Plan("p2", ic9700())), "p1")
        val s = PlansMontage.Suivi()
        assertEquals("p1", s.choisit(p, DATV, 0).plan)
        val c = PlansMontage.Suivi().choisit(p, SSTV, 0)
        assertEquals("p2", c.plan); assertTrue(c.suit); assertEquals(setOf("cat"), c.roles)
        // Nothing working: the owner's plan, not followed.
        val r = PlansMontage.Suivi().choisit(p.copy(affiche = "p2"), Activite(), 0)
        assertEquals("p2", r.plan); assertFalse(r.suit)
        // Toggle off: always the owner's.
        assertEquals("p1", PlansMontage.Suivi().choisit(p.copy(suivre = false), SSTV, 0).plan)
    }

    @Test fun aucunPlanNeDessineCeQuiTourne() {
        val p = PlansMontage.Plans(listOf(PlansMontage.Plan("p1", noeud())), "p1")
        val c = PlansMontage.Suivi().choisit(p, SSTV, 0)
        assertTrue(c.detectee); assertNull(c.plan)
    }

    @Test fun lePlanNeChangePasAuMoindreSouffle() {
        val p = PlansMontage.Plans(listOf(PlansMontage.Plan("p1", noeud()), PlansMontage.Plan("p2", ic9700()), PlansMontage.Plan("p3", tout())), "p2")
        val s = PlansMontage.Suivi(delaiMs = 10_000, finMs = 30_000)
        assertEquals("p1", s.choisit(p, DATV, 0).plan)
        // SSTV starts beside the DATV: the plan of both wins, but only once it lasted.
        val deux = Activite(rx = setOf("datv:minitiouner", "noeud", "cat"))
        assertEquals("p1", s.choisit(p, deux, 1_000).plan)
        assertEquals("p1", s.choisit(p, deux, 9_000).plan)
        assertEquals("p3", s.choisit(p, deux, 11_500).plan)
        // Back to DATV alone: the plan shown still draws it, it stays (no flip-flop).
        assertEquals("p3", s.choisit(p, DATV, 12_000).plan)
        assertEquals("p3", s.choisit(p, DATV, 60_000).plan)
        // A short pause: kept; a long one: back to the owner's plan.
        assertEquals("p3", s.choisit(p, Activite(), 70_000).plan)
        assertEquals("p2", s.choisit(p, Activite(), 95_000).plan)
        // The plan shown draws nothing of a new activity: changed at once.
        assertEquals("p1", s.choisit(p, DATV, 100_000).plan)
        assertEquals("p2", s.choisit(p, SSTV, 100_500).plan)
    }

    @Test fun unChoixALaMainResteJusquALaFinDeLActivite() {
        val p = PlansMontage.Plans(listOf(PlansMontage.Plan("p1", noeud()), PlansMontage.Plan("p2", ic9700())), "p1")
        val s = PlansMontage.Suivi(finMs = 30_000)
        assertEquals("p2", s.choisit(p, SSTV, 0).plan)
        s.epingle("p1")
        val c = s.choisit(p, SSTV, 1_000)
        assertEquals("p1", c.plan); assertTrue(c.epingle)
        assertEquals("p1", s.choisit(p, SSTV, 50_000).plan)
        // The activity over: the pin forgotten; the next one followed again.
        s.choisit(p, Activite(), 60_000); s.choisit(p, Activite(), 95_000)
        assertNull(s.epingle)
        assertEquals("p2", s.choisit(p, SSTV, 100_000).plan)
        // The toggle used again: the pin forgotten at once.
        s.epingle("p1"); s.relache()
        assertEquals("p2", s.choisit(p, SSTV, 101_000).plan)
    }

    // ------------------------------------------------------------------ the rig's codec lights the rig

    private val CODEC = "CODEC [plughw:2,0]"
    private fun sstvParCodec() = FonctionsMontage.Instantane(sonPresent = true, sonActif = true, sonAppareil = CODEC, sonCarte = CODEC, cartes = listOf(CODEC),
        sstvActif = true, sstvSource = "auto", sstvSourceActive = "carte", carteDuPoste = CODEC, catModele = "IC9700",
        libelles = mapOf(CODEC to "IC-9700 (USB Audio CODEC)"))
    private fun lectures(satellite: Boolean) = ActiviteMontage.Lectures(etat = JSONObject(
        """{"cat":{"connecte":false,"modele":"IC9700"}""" + (if (satellite) ""","suivi":{"nom":"ISS"},"ciel":{"dl":437550000,"ul":null}""" else "") + "}"))

    @Test fun laSstvParLeCodecDuPosteAllumeLePosteSansCat() {
        val a = ActiviteMontage.depuis(lectures(true), sstvParCodec())
        assertTrue(a.rx.toString(), "cat" in a.rx)
        assertEquals(437.55, a.mhz["poste-rx"]!!, 1e-6)
        assertTrue(a.resume.toString(), a.resume.any { it.startsWith("SSTV : IC-9700 par sa carte son USB, 437,550 MHz") && "satellite suivi" in it })
        val c = ic9700()
        val al = ActiviteMontage.allume(c, a)
        assertEquals(Etat.RX, al.elements["e3"])      // the IC-9700
        assertEquals(Etat.RX, al.elements["e2"])      // its 70 cm Yagi
        assertEquals(Etat.REPOS, al.elements["e1"])   // not the 2 m one
        assertEquals(Etat.RX, al.elements["e4"])      // the PC
        val l70 = c.liens.first { it.vers.port == "ant432" || it.de.port == "ant432" }.id
        assertEquals(437.55, al.mhz[l70]!!, 1e-6)
        assertEquals(Etat.RX, al.liens[c.liens.first { it.de.port == "usb" }.id])
        // No satellite followed: no frequency, the rig and both its antennas lit.
        val b = ActiviteMontage.depuis(lectures(false), sstvParCodec())
        assertNull(b.mhz["poste-rx"])
        val bl = ActiviteMontage.allume(c, b)
        assertEquals(Etat.RX, bl.elements["e1"]); assertEquals(Etat.RX, bl.elements["e3"])
        // Another card: that card, not the rig.
        val autre = sstvParCodec().copy(sonAppareil = "PCM2902 [plughw:1,0]", carteDuPoste = CODEC)
        val x = ActiviteMontage.depuis(lectures(true), autre)
        assertFalse("cat" in x.rx); assertTrue("son" in x.rx)
        // Nothing decoding: nothing added.
        assertTrue(ActiviteMontage.depuis(lectures(true), sstvParCodec().copy(sstvActif = false)).vide)
    }

    @Test fun lesAutresDecodeursEtLeCatAussi() {
        val i = sstvParCodec().copy(sstvActif = false, aprsActif = true, ft8Actif = true, catConnecte = true)
        val l = ActiviteMontage.Lectures(etat = JSONObject("""{"cat":{"connecte":true,"modele":"IC9700","dl":145825000},"suivi":{"nom":"ISS"},"ciel":{"dl":437800000}}"""))
        val a = ActiviteMontage.depuis(l, i)
        // With CAT, its frequency (APRS on 2 m), not the satellite's.
        assertEquals(145.825, a.mhz["poste-rx"]!!, 1e-6)
        assertTrue(a.resume.toString(), a.resume.any { it.startsWith("FT8, APRS : IC-9700") && "sans CAT" !in it })
        val al = ActiviteMontage.allume(ic9700(), a)
        assertEquals(Etat.RX, al.elements["e1"]); assertEquals(Etat.REPOS, al.elements["e2"])
    }

    @Test fun laChaineDetecteeMontreCeQuiTourne() {
        val l = lectures(true)
        val a = ActiviteMontage.depuis(l, sstvParCodec())
        val p = DetectionMontage.propose(l, emptyList(), listOf(DetectionMontage.CarteSon(2, "CODEC", "USB Audio CODEC", "08bb:2901")))
        val v = PlansMontage.chaineVive(p.chaine, a)
        assertNotNull(v)
        assertEquals(setOf("ic9700", "yagi-70cm", "pc"), v!!.elements.map { it.modele }.toSet())
        assertEquals(2, v.liens.size)
        assertEquals("IC-9700 · SSTV", PlansMontage.nomPropose(v, a))
        // Kept as a plan, it draws what works: chosen.
        val plans = PlansMontage.Plans(listOf(PlansMontage.Plan("p1", noeud()), PlansMontage.Plan("p2", v)), "p1")
        assertEquals("p2", PlansMontage.Suivi().choisit(plans, a, 0).plan)
        assertNull(PlansMontage.chaineVive(p.chaine, Activite()))
    }
}
