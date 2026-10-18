/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.station

import fr.f4ioz.satcombo.station.EditionMontage.Op
import fr.f4ioz.satcombo.station.Montage.Bout
import fr.f4ioz.satcombo.station.Montage.Cable
import fr.f4ioz.satcombo.station.Montage.Chaine
import fr.f4ioz.satcombo.station.Montage.Element
import fr.f4ioz.satcombo.station.Montage.Signal
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MontageEditionTest {

    private fun ports(m: String, o: Map<String, String> = emptyMap()) = Montage.ports(Element("x", m, options = o))
    private fun ids(m: String, o: Map<String, String> = emptyMap()) = ports(m, o).map { it.id }

    @Test fun lesNouveauxAppareilsEtLeursPorts() {
        // Up-converters: the 10 MHz reference fitted on the MK4, not on the MK3; editable both ways.
        assertTrue("ref" in ids("dxp-up-mk4"))
        assertFalse("ref" in ids("dxp-up-mk3"))
        assertTrue("ref" in ids("dxp-up-mk3", mapOf("ref" to "1")))
        assertFalse("ref" in ids("dxp-up-mk4", mapOf("ref" to "0")))
        val up = ports("dxp-up-mk4", mapOf("fi" to "144"))
        assertEquals(144, up.first { it.id == "entree" }.mhz)
        assertEquals(Montage.Voie.TX, up.first { it.id == "sortie" }.voie)
        assertTrue(CatalogueMateriel.modele("dxp-up-mk4")!!.note.contains("faible puissance"))
        // Down-converter: its output chosen, 144 or 432.
        assertEquals(432, ports("dxp-down-mk4", mapOf("sortie" to "432")).first { it.id == "sortie" }.mhz)
        assertEquals(739, ports("dxp-down-mk3").first { it.id == "entree" }.mhz)
        // GPSDO 10 MHz: as many outputs as said; Leo Bodnar: each output its frequency.
        assertEquals(listOf("gps", "s1", "s2", "s3", "alim"), ids("dxp-gpsdo", mapOf("sorties" to "3")))
        val lb = ports("leo-bodnar", mapOf("f1" to "27", "f2" to "10"))
        assertEquals(27, lb.first { it.id == "s1" }.mhz); assertEquals(10, lb.first { it.id == "s2" }.mhz)
        assertEquals(listOf("gps", "s1", "usb"), ids("leo-bodnar", mapOf("sorties" to "1")))
        // An unknown value falls back to the default.
        assertEquals(25, ports("leo-bodnar", mapOf("f1" to "33")).first { it.id == "s1" }.mhz)
        // Twin LNB: two outputs; the 25 MHz reference only when modified.
        assertEquals(listOf("entree", "sortie1", "sortie2"), ids("lnb-twin"))
        assertTrue("ref25" in ids("lnb-twin", mapOf("ref" to "1")))
        // Amplifiers: transmit path, a PTT and a supply.
        for (pa in listOf("dxp-pa-20w", "dxp-pa-15w")) {
            val p = ports(pa)
            assertTrue(pa, p.filter { it.signal == Signal.RF }.all { it.voie == Montage.Voie.TX })
            assertTrue(pa, p.any { it.id == "ptt" } && p.any { it.signal == Signal.DC })
        }
        listOf("antenne-gps", "injecteur", "dc-block", "alim-138", "sequenceur", "molette-usb", "interface-esp32",
            "boite-wavelog", "boite-qrz", "boite-psk", "boite-chat", "boite-carnets", "boite-lien", "boite-telephone", "boite-noeud", "boite-ecoute")
            .forEach { assertNotNull(it, CatalogueMateriel.modele(it)) }
        // Every model's variant ports are among those it declares (the catalogue lists them all).
        for (m in CatalogueMateriel.MODELES) for (op in m.options) for ((v, _) in op.valeurs) {
            val p = m.portsAvec(mapOf(op.id to v))
            assertTrue(m.id, p.map { it.id }.toSet().size == p.size && p.all { x -> m.ports.any { it.id == x.id } })
        }
    }

    @Test fun lesNouveauxCablesSontProposesParLesPorts() {
        fun cable(a: Pair<String, String>, b: Pair<String, String>, oa: Map<String, String> = emptyMap(), ob: Map<String, String> = emptyMap()): Cable? {
            val pa = ports(a.first, oa).first { it.id == a.second }; val pb = ports(b.first, ob).first { it.id == b.second }
            return Montage.cableParDefaut(pa, pb)
        }
        assertEquals(Cable.REFERENCE, cable("dxp-gpsdo" to "s1", "dxp-up-mk4" to "ref"))
        assertEquals(Cable.REFERENCE, cable("leo-bodnar" to "s1", "lnb-twin" to "ref25", ob = mapOf("ref" to "1")))
        assertEquals(Cable.ALIM, cable("alim-138" to "dc", "dxp-pa-20w" to "alim"))
        assertEquals(Cable.GPS, cable("antenne-gps" to "gps", "dxp-gpsdo" to "gps"))
        assertEquals(Cable.INTERNET, cable("boite-wavelog" to "net", "pc" to "logiciel"))
        assertEquals(Cable.PTT, cable("sequenceur" to "ptt2", "dxp-pa-20w" to "ptt"))
        assertEquals(Cable.COAX_SMA, cable("dxp-up-mk4" to "sortie", "dxp-pa-20w" to "entree"))
        // A reference does not go into a radio input, nor a supply into a USB port.
        assertNull(cable("dxp-gpsdo" to "s1", "pluto" to "rx"))
        assertNull(cable("alim-138" to "dc", "pc" to "usb"))
    }

    @Test fun laStationQo100CompleteSAllumeEnEmission() {
        val c = CatalogueMateriel.PREREGLAGES.first { it.id == "qo100-station" }.chaine
        assertEquals(c, Montage.nettoie(c))
        assertEquals(20, c.liens.size)
        val id = { m: String -> c.elements.first { it.modele == m }.id }
        // The DATV transmitter on the Pluto: nothing of the up-converter's path (it is the rig's); the Pluto's reception lit back to the dish.
        val a = ActiviteMontage.allume(c, ActiviteMontage.Activite(rx = setOf("sdr:pluto"), mhz = mapOf("fi-rx" to 144.75)))
        assertEquals(ActiviteMontage.Etat.RX, a.elements[id("dxp-down-mk4")])
        assertEquals(ActiviteMontage.Etat.RX, a.elements[id("lnb-twin")])
        assertEquals(ActiviteMontage.Etat.RX, a.elements[id("parabole-qo100")])
        assertEquals(ActiviteMontage.Etat.REPOS, a.elements[id("dxp-gpsdo")])
        // The IF after the down-converter: 144 MHz, the frequency read put on it.
        val l = c.liens.first { it.de.element == id("dxp-down-mk4") && it.de.port == "sortie" }
        assertEquals(144.75, a.mhz[l.id]!!, 1e-6)
        // A transmission through the up-converter lights the amplifier and the dish's patch.
        val c2 = c.copy(elements = c.elements + Element("hk", "hackrf"),
            liens = c.liens.filter { it.vers != Bout(id("dxp-up-mk4"), "entree") } + Montage.Lien("lx", Bout("hk", "ant"), Bout(id("dxp-up-mk4"), "entree"), Cable.COAX_SMA))
        val t = ActiviteMontage.allume(c2, ActiviteMontage.Activite(tx = setOf("tx:hackrf")))
        assertEquals(ActiviteMontage.Etat.TX, t.elements[id("dxp-up-mk4")])
        assertEquals(ActiviteMontage.Etat.TX, t.elements[id("dxp-pa-20w")])
        assertEquals(ActiviteMontage.Etat.TX, t.elements[id("parabole-qo100")])
        // References and supplies are not part of a radio or data path.
        assertEquals(ActiviteMontage.Etat.REPOS, t.elements[id("alim-138")])
        assertEquals(ActiviteMontage.Etat.REPOS, t.elements[id("leo-bodnar")])
    }

    @Test fun ajouterRelierAnnulerRetablir() {
        val h = EditionMontage.Historique()
        var c = Chaine()
        fun fait(op: Op): EditionMontage.Resultat {
            val r = EditionMontage.applique(c, op)
            if (r.ok) { h.pousse(c, r.chaine); c = r.chaine }
            return r
        }
        val g = fait(Op.Ajoute("dxp-gpsdo", 40, 300)).id!!
        val up = fait(Op.Ajoute("dxp-up-mk4", 260, 300)).id!!
        assertEquals(2, c.elements.size)
        assertTrue(c.elements.all { it.place })
        // A cable from port to port: the kind the two ports call for.
        val l = fait(Op.Relie(Bout(g, "s1"), Bout(up, "ref")))
        assertTrue(l.ok)
        assertEquals(Cable.REFERENCE, c.liens.single().cable)
        // The same input twice: refused, the chain unchanged.
        val r = fait(Op.Relie(Bout(g, "s2"), Bout(up, "ref")))
        assertFalse(r.ok); assertEquals(1, c.liens.size)
        // A wrong kind: refused.
        assertFalse(EditionMontage.applique(c, Op.Cablage(l.id!!, Cable.USB)).ok)
        // A label put on the cable.
        fait(Op.Cablage(l.id!!, null, "câble de 1 m"))
        assertEquals("câble de 1 m", c.liens.single().note)
        // Undo three times: back to two pieces, no cable; redo once: the cable again.
        c = h.annule(c)!!; assertEquals("", c.liens.single().note)
        c = h.annule(c)!!; assertTrue(c.liens.isEmpty())
        c = h.annule(c)!!; assertEquals(1, c.elements.size)
        c = h.refait(c)!!; assertEquals(2, c.elements.size)
        assertTrue(h.peutRefaire)
        // A new edit forgets what was undone.
        fait(Op.Deplace(up, 400, 320))
        assertFalse(h.peutRefaire)
        assertEquals(400 to 320, c.element(up)!!.let { it.x to it.y })
    }

    @Test fun deplacerFigeLaDispositionAutomatique() {
        val c = CatalogueMateriel.PREREGLAGES.first { it.id == "qo100-pluto" }.chaine
        val auto = DispositionMontage.dispose(c)
        val premier = c.elements[0].id
        val r = EditionMontage.applique(c, Op.Deplace(premier, 500, 600))
        // The others stay where the automatic layout drew them; the moved one where it was put.
        for (e in r.chaine.elements) {
            if (e.id == premier) assertEquals(500 to 600, e.x to e.y)
            else assertEquals(auto.boite(e.id)!!.let { it.x to it.y }, e.x to e.y)
        }
        val p = DispositionMontage.dispose(r.chaine)
        assertTrue(p.libre)
        assertEquals(c.liens.size, p.traces.size)
        // Each cable from its two ports, square turns only.
        for (t in p.traces) {
            val l = r.chaine.liens.first { it.id == t.id }
            val pa = p.boite(l.de.element)!!.ports.first { it.id == l.de.port }
            assertEquals(pa.x to pa.y, t.points.first())
            t.points.zipWithNext().forEach { (u, v) -> assertTrue(u.first == v.first || u.second == v.second) }
        }
        // « Disposition auto »: all places forgotten.
        val a = EditionMontage.applique(r.chaine, Op.DispositionAuto)
        assertTrue(a.chaine.elements.none { it.place })
        assertFalse(DispositionMontage.dispose(a.chaine).libre)
    }

    @Test fun unReglageQuiRetireUnPortRetireSonCable() {
        var c = Chaine(listOf(Element("g", "dxp-gpsdo"), Element("u", "dxp-up-mk4")), emptyList())
        c = EditionMontage.applique(c, Op.Relie(Bout("g", "s1"), Bout("u", "ref"))).chaine
        assertEquals(1, c.liens.size)
        val r = EditionMontage.applique(c, Op.Regle("u", "ref", "0"))
        assertTrue(r.ok); assertTrue(r.chaine.liens.isEmpty()); assertTrue(r.dit.contains("retiré"))
        assertEquals("0", r.chaine.element("u")!!.options["ref"])
        assertFalse(EditionMontage.applique(c, Op.Regle("u", "ref", "peut-être")).ok)
        // The default not kept: the chain stays small.
        assertTrue(EditionMontage.applique(c, Op.Regle("u", "ref", "1")).chaine.element("u")!!.options.isEmpty())
        // A model swapped: the cables whose ports remain stay.
        val s = EditionMontage.applique(c, Op.Modele("u", "dxp-up-mk3"))
        assertEquals("dxp-up-mk3", s.chaine.element("u")!!.modele)
        assertTrue(s.chaine.liens.isEmpty())
    }

    @Test fun lesGestesSeLisentDesParametres() {
        fun lit(vararg q: Pair<String, String>) = EditionMontage.lit { k -> q.toMap()[k].orEmpty() }
        assertEquals(Op.Ajoute("pluto", 12, 30), lit("op" to "ajoute", "m" to "pluto", "x" to "12.4", "y" to "30"))
        assertEquals(Op.Relie(Bout("e1", "tx"), Bout("e2", "entree"), null, ""), lit("op" to "relie", "de" to "e1.tx", "vers" to "e2.entree"))
        assertNull(lit("op" to "relie", "de" to "../x", "vers" to "e2.entree"))
        assertEquals(Op.Cablage("l1", Cable.COAX_N, null), lit("op" to "cable", "id" to "l1", "c" to "COAX_N"))
        assertEquals(Op.Cablage("l1", null, "5 m"), lit("op" to "cable", "id" to "l1", "note" to "5 m", "avecNote" to "1"))
        assertEquals(Op.Photo("e4", true), lit("op" to "photo", "id" to "e4", "on" to "1"))
        assertEquals(Op.DispositionAuto, lit("op" to "auto"))
        assertNull(lit("op" to "efface-tout"))
    }

    @Test fun optionsPlacesEtPhotoAllerRetour() {
        val c = Chaine(listOf(Element("a", "leo-bodnar", "", mapOf("f1" to "27", "zz" to "1"), 10, 20, photo = true), Element("b", "lnb-twin", x = 5)), emptyList())
        val n = Montage.lit(Montage.ecrit(c).toString())
        assertEquals(mapOf("f1" to "27"), n.element("a")!!.options)
        assertEquals(10 to 20, n.element("a")!!.let { it.x to it.y })
        assertTrue(n.element("a")!!.photo)
        // Half a place is no place.
        assertFalse(n.element("b")!!.place)
        val j = Montage.portsJson(n)
        assertTrue(j.has("a") && j.getJSONArray("a").toString().contains("27"))
        val cat = Montage.catalogueJson()
        assertEquals(Montage.Palette.entries.size, cat.getJSONArray("palette").length())
        assertTrue(cat.toString().contains("\"REFERENCE\""))
        // The boîtes' states from what the PC knows.
        val b = ActiviteMontage.boites(JSONObject("""{"wavelog":{"url":true,"cle":true},"lien":{"on":true,"clients":2},"carnets":{"on":true,"clients":0,"port":4532},"psk":{"on":false}}"""))
        assertEquals(ActiviteMontage.EtatBoite.CONFIGURE, b["wavelog"]!!.etat)
        assertEquals(ActiviteMontage.EtatBoite.CONNECTE, b["lien"]!!.etat)
        assertEquals("en attente (port 4532)", b["carnets"]!!.texte)
        assertEquals(ActiviteMontage.EtatBoite.ARRET, b["psk"]!!.etat)
        val ch = Chaine(listOf(Element("w", "boite-wavelog"), Element("l", "boite-lien"), Element("p", "pc")),
            listOf(Montage.Lien("l1", Bout("l", "net"), Bout("p", "logiciel"), Cable.INTERNET)))
        val act = ActiviteMontage.depuis(ActiviteMontage.Lectures(boites = JSONObject("""{"lien":{"on":true,"clients":1},"wavelog":{"url":false}}""")))
        val al = ActiviteMontage.allume(ch, act)
        assertEquals(ActiviteMontage.Etat.RELIE, al.liens["l1"])
        assertEquals("1 client", al.boites["l"]!!.texte)
        assertEquals(ActiviteMontage.EtatBoite.ARRET, al.boites["w"]!!.etat)
    }
}
