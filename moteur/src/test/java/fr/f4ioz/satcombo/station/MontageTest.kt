/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.station

import fr.f4ioz.satcombo.station.ActiviteMontage.Etat
import fr.f4ioz.satcombo.station.Montage.Bout
import fr.f4ioz.satcombo.station.Montage.Cable
import fr.f4ioz.satcombo.station.Montage.Chaine
import fr.f4ioz.satcombo.station.Montage.Element
import fr.f4ioz.satcombo.station.Montage.Lien
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MontageTest {

    private fun prereglage(id: String) = CatalogueMateriel.PREREGLAGES.first { it.id == id }.chaine

    @Test fun leCatalogueEstCoherent() {
        val ids = CatalogueMateriel.MODELES.map { it.id }
        assertEquals("ids uniques", ids.size, ids.toSet().size)
        for (m in CatalogueMateriel.MODELES) {
            assertTrue(m.id, Montage.ID.matches(m.id))
            assertTrue(m.id, m.ports.isNotEmpty() && m.l in 30..240 && m.h in 30..160)
            assertEquals(m.id, m.ports.size, m.ports.map { it.id }.toSet().size)
            m.ports.forEach { assertTrue(m.id + "." + it.id, Montage.ID.matches(it.id) && it.familles.isNotEmpty()) }
        }
        // The hardware the owner asked for is there.
        listOf("ic9700", "ic705", "ts2000", "ft991a", "ft818", "rtl-sdr", "pluto", "bladerf", "hackrf", "minitiouner", "satme-node",
            "pc", "telephone", "rotor-gs232", "carte-son", "bias-t", "interface-esp32", "charge", "filtre", "repartiteur",
            "lnb-pll", "dc-144", "dc-432", "dc-739", "pa-2400", "parabole-qo100", "yagi-2m", "yagi-70cm", "xquad", "colineaire", "antenne-interieure")
            .forEach { assertNotNull(it, CatalogueMateriel.modele(it)) }
    }

    @Test fun lesPrereglagesSontComplets() {
        assertEquals(5, CatalogueMateriel.PREREGLAGES.size)
        for (p in CatalogueMateriel.PREREGLAGES) {
            val c = p.chaine
            assertTrue(p.id, c.elements.size >= 4)
            // Every cable the builder asked for was accepted: nothing left unplugged but the spare ports.
            assertEquals(p.id, c, Montage.nettoie(c))
            assertTrue(p.id, c.elements.all { e -> c.liens.any { it.de.element == e.id || it.vers.element == e.id } })
        }
        assertEquals(6, prereglage("qo100-pluto").liens.size)
        assertEquals(5, prereglage("noeud-deporte").liens.size)
    }

    @Test fun allerRetourJson() {
        for (p in CatalogueMateriel.PREREGLAGES) {
            val texte = Montage.ecrit(p.chaine).toString()
            assertEquals(p.id, p.chaine, Montage.lit(texte))
        }
        assertTrue(Montage.lit("pas du json").vide)
        assertTrue(Montage.lit(null).vide)
    }

    @Test fun ceQuiEstLuEstNettoye() {
        val o = JSONObject("""{"elements":[{"id":"a","m":"pluto","nom":"<b>mon Pluto</b>"},{"id":"a","m":"pc"},{"id":"../x","m":"pc"},
            {"id":"b","m":"inconnu"},{"id":"c","m":"pc"},{"id":"d","m":"pa-2400"}],
            "liens":[{"id":"l1","de":"a.usb","vers":"c.usb","c":"USB"},{"id":"l2","de":"a.usb","vers":"c.usb","c":"ETHERNET"},
            {"id":"l3","de":"a.tx","vers":"d.entree","c":"COAX_SMA"},{"id":"l4","de":"a.tx","vers":"c.usb","c":"USB"},
            {"id":"l5","de":"a.rx","vers":"zz.rx","c":"COAX_SMA"},{"id":"l6","de":"d.sortie","vers":"d.entree","c":"COAX_N"}]}""")
        val c = Montage.lit(o)
        assertEquals(listOf("a", "c", "d"), c.elements.map { it.id })
        assertEquals("bmon Pluto/b", c.elements[0].nom)
        // l2: Ethernet does not go into a USB port; l4: the TX port has its cable; l5: no such element; l6: to itself.
        assertEquals(listOf("l1", "l3"), c.liens.map { it.id })
    }

    @Test fun unPortNePrendQuUnCableSaufSIlEnAccepteplusieurs() {
        val c = Chaine(listOf(Element("p1", "pluto"), Element("p2", "bladerf"), Element("pc", "pc")),
            listOf(Lien("l1", Bout("p1", "usb"), Bout("pc", "usb"), Cable.USB)))
        assertNull(Montage.refus(c, Bout("p2", "usb"), Bout("pc", "usb"), Cable.USB))
        assertNotNull(Montage.refus(c, Bout("p1", "usb"), Bout("pc", "lan"), Cable.ETHERNET))
        assertNotNull(Montage.refus(c, Bout("p1", "rx"), Bout("p2", "usb"), Cable.COAX_SMA))
    }

    @Test fun laDispositionVaDeLAntenneAuPc() {
        for (p in CatalogueMateriel.PREREGLAGES) {
            val c = p.chaine
            val plan = DispositionMontage.dispose(c)
            assertEquals(p.id, c.elements.size, plan.boites.size)
            assertEquals(p.id, c.liens.size, plan.traces.size)
            val col = plan.boites.associate { it.id to it.colonne }
            val genre = c.elements.associate { it.id to Montage.modele(it)!!.genre }
            val antennes = c.elements.filter { genre[it.id] == Montage.Genre.ANTENNE }.map { col[it.id]!! }
            val pc = c.elements.filter { genre[it.id] == Montage.Genre.ORDINATEUR }.map { col[it.id]!! }
            assertTrue(p.id, antennes.all { it == 0 })
            val maxAutres = c.elements.filter { genre[it.id] != Montage.Genre.ORDINATEUR && genre[it.id] != Montage.Genre.TELEPHONE }.maxOf { col[it.id]!! }
            assertTrue(p.id, pc.all { it > maxAutres })
            // No two drawings overlap; everything inside the drawing.
            for (a in plan.boites) {
                assertTrue(p.id, a.x >= 0 && a.y >= 0 && a.x + a.l <= plan.largeur && a.bas <= plan.hauteur)
                for (b in plan.boites) if (a !== b)
                    assertFalse("${p.id} : ${a.id} sur ${b.id}", a.x < b.x + b.l && b.x < a.x + a.l && a.y < b.bas && b.y < a.bas)
            }
            // Cables: orthogonal runs, from one port to the other.
            for (t in plan.traces) {
                assertTrue(p.id, t.points.size >= 2)
                t.points.zipWithNext().forEach { (u, v) -> assertTrue("${p.id} ${t.id} $u $v", u.first == v.first || u.second == v.second) }
                assertTrue(p.id, t.points.first() == t.de || t.points.first() == t.vers)
                assertTrue(p.id, t.points.last() == t.de || t.points.last() == t.vers)
                // A cable never runs across a drawing (other than its own two ends').
                val l = c.liens.first { it.id == t.id }
                for ((u, v) in t.points.zipWithNext()) for (b in plan.boites) {
                    if (b.id == l.de.element || b.id == l.vers.element) continue
                    val x0 = minOf(u.first, v.first); val x1 = maxOf(u.first, v.first); val y0 = minOf(u.second, v.second); val y1 = maxOf(u.second, v.second)
                    assertFalse("${p.id} ${t.id} traverse ${b.id}", x0 < b.x + b.l && x1 > b.x && y0 < b.bas && y1 > b.y)
                }
            }
        }
    }

    @Test fun laReceptionEstAuDessusDeLEmission() {
        val c = prereglage("qo100-pluto")
        val plan = DispositionMontage.dispose(c)
        val id = { m: String -> c.elements.first { it.modele == m }.id }
        // In the LNB's and the amplifier's column, the LNB (reception) above the amplifier (transmission).
        val lnb = plan.boite(id("lnb-pll"))!!; val pa = plan.boite(id("pa-2400"))!!
        assertEquals(lnb.colonne, pa.colonne)
        assertTrue(lnb.y < pa.y)
        // Left to right: dish, LNB, bias-T, Pluto, PC.
        val ordre = listOf("parabole-qo100", "lnb-pll", "bias-t", "pluto", "pc").map { plan.boite(id(it))!!.colonne }
        assertEquals(ordre.sorted(), ordre)
        assertEquals(ordre.size, ordre.toSet().size)
    }

    @Test fun laVueSimplifieeNeGardeQueCeQuiEstDemande() {
        val c = prereglage("qo100-ic9700")
        val garder = c.elements.take(3).map { it.id }.toSet()
        val plan = DispositionMontage.dispose(c, garder)
        assertEquals(garder, plan.boites.map { it.id }.toSet())
        assertTrue(plan.traces.all { t -> c.liens.first { it.id == t.id }.let { it.de.element in garder && it.vers.element in garder } })
        assertTrue(DispositionMontage.dispose(Chaine()).boites.isEmpty())
    }

    @Test fun lEmissionAllumeLeCheminVersLaParabole() {
        val c = prereglage("qo100-pluto")
        val id = { m: String -> c.elements.first { it.modele == m }.id }
        val a = ActiviteMontage.Activite(tx = setOf("tx:pluto"), mhz = mapOf("ciel-tx" to 2400.25))
        val al = ActiviteMontage.allume(c, a)
        assertEquals(Etat.TX, al.elements[id("pluto")])
        assertEquals(Etat.TX, al.elements[id("pa-2400")])
        assertEquals(Etat.TX, al.elements[id("parabole-qo100")])
        assertEquals(Etat.TX, al.elements[id("pc")])
        assertEquals(Etat.REPOS, al.elements[id("lnb-pll")])
        assertEquals(Etat.REPOS, al.elements[id("bias-t")])
        val versPatch = c.liens.first { it.vers == Bout(id("parabole-qo100"), "patch") }
        assertEquals(Etat.TX, al.liens[versPatch.id])
        assertEquals(2400.25, al.mhz[versPatch.id]!!, 1e-9)
        assertEquals(setOf(id("pluto"), id("pa-2400"), id("parabole-qo100"), id("pc")), al.enOeuvre())
    }

    @Test fun laReceptionRemonteJusquALaParaboleAvecLesBonnesFrequences() {
        val c = prereglage("qo100-ic9700")
        val id = { m: String -> c.elements.first { it.modele == m }.id }
        val a = ActiviteMontage.Activite(rx = setOf("cat"), mhz = mapOf("ciel-rx" to 10489.75, "fi-rx" to 432.75))
        val al = ActiviteMontage.allume(c, a)
        assertEquals(Etat.RX, al.elements[id("ic9700")])
        assertEquals(Etat.RX, al.elements[id("dc-432")])
        assertEquals(Etat.RX, al.elements[id("parabole-qo100")])
        // The up-converter is on the transmission path: not lit by a reception.
        assertEquals(Etat.REPOS, al.elements[id("up-144")])
        val ciel = c.liens.first { it.de.element == id("parabole-qo100") && it.de.port == "lnb" }
        val fi = c.liens.first { it.de.element == id("dc-432") }
        assertEquals(10489.75, al.mhz[ciel.id]!!, 1e-9)
        assertEquals(432.75, al.mhz[fi.id]!!, 1e-9)
    }

    @Test fun leNoeudPorteLaReceptionJusquAuPc() {
        val c = prereglage("noeud-deporte")
        val al = ActiviteMontage.allume(c, ActiviteMontage.Activite(rx = setOf("datv:minitiouner", "noeud")))
        val id = { m: String -> c.elements.first { it.modele == m }.id }
        listOf("minitiouner", "satme-node", "pc", "lnb-pll", "parabole-qo100").forEach { assertEquals(it, Etat.RX, al.elements[id(it)]) }
        // The phone is beyond the computer: not part of the reception.
        assertEquals(Etat.REPOS, al.elements[id("telephone")])
    }

    @Test fun lActiviteSeLitDansLesRoutes() {
        val l = ActiviteMontage.Lectures(
            etat = JSONObject("""{"cat":{"connecte":false,"modele":"IC9700"},"rotor":{"connecte":true},"convertisseurs":{"rxOl":9750000000,"txOl":0}}"""),
            sdr = JSONObject("""{"actif":true,"recepteur":"pluto","ciel":10489750000,"erreur":""}"""),
            datv = JSONObject("""{"ok":true,"actif":false,"recepteur":"minitiouner","erreur":"MiniTiouner absent","noeud":""}"""),
            emissionDatv = JSONObject("""{"ok":true,"emet":true,"emetteurEnCours":"bladerf","khzEnCours":2409750,"erreur":""}"""),
            emissionSstv = JSONObject("""{"ok":true,"emet":false}"""),
            iq = JSONObject("""{"ok":true,"actif":false}"""),
            qo100 = JSONObject("""{"descente":10489750000,"montee":2400250000}"""))
        val a = ActiviteMontage.depuis(l)
        assertEquals(setOf("sdr:pluto"), a.rx)
        assertEquals(setOf("tx:bladerf"), a.tx)
        assertEquals(setOf("rotor"), a.relies)
        assertEquals("MiniTiouner absent", a.erreurs["datv:minitiouner"])
        assertEquals(739.75, a.mhz["fi-rx"]!!, 1e-6)
        assertEquals(2409.75, a.mhz["ciel-tx"]!!, 1e-6)
        assertTrue(a.resume.any { it.startsWith("Émission DATV") })
        assertTrue(ActiviteMontage.depuis(ActiviteMontage.Lectures()).vide)
    }

    @Test fun laSuggestionPartDeCeQueSatMeSait() {
        val l = ActiviteMontage.Lectures(
            etat = JSONObject("""{"cat":{"modele":"IC9700"},"rotor":{"connecte":false},"convertisseurs":{"rxOl":10057000000,"txOl":0}}"""),
            sdr = JSONObject("""{"actif":false,"recepteur":"pluto"}"""),
            datv = JSONObject("""{"ok":true,"recepteur":"minitiouner","noeud":"192.168.1.20"}"""),
            emissionDatv = JSONObject("""{"ok":true,"emetteur":"bladerf","bladerf":{"lu":true},"hackrf":{"cartes":[]}}"""),
            lien = JSONObject("""{"on":true}"""))
        val s = ActiviteMontage.suggere(l)
        val modeles = s.chaine.elements.map { it.modele }.toSet()
        assertTrue(modeles.toString(), modeles.containsAll(listOf("ic9700", "pluto", "bladerf", "minitiouner", "satme-node", "pc", "parabole-qo100", "pa-2400", "dc-432", "telephone")))
        assertFalse(modeles.contains("rotor-gs232"))
        assertEquals(s.chaine, Montage.nettoie(s.chaine))
        assertTrue(s.pourquoi.isNotEmpty())
        // The bladeRF transmits to the amplifier.
        val c = s.chaine
        val pa = c.elements.first { it.modele == "pa-2400" }.id; val bl = c.elements.first { it.modele == "bladerf" }.id
        assertTrue(c.liens.any { it.de == Bout(bl, "tx1") && it.vers == Bout(pa, "entree") })
        // Nothing known: the computer alone.
        assertEquals(listOf("pc"), ActiviteMontage.suggere(ActiviteMontage.Lectures()).chaine.elements.map { it.modele })
        // It lays out.
        assertEquals(c.elements.size, DispositionMontage.dispose(c).boites.size)
    }

    @Test fun leCatalogueSeDonneALaPage() {
        val j = Montage.catalogueJson()
        assertEquals(CatalogueMateriel.MODELES.size, j.getJSONArray("modeles").length())
        assertEquals(5, j.getJSONArray("prereglages").length())
        val plan = DispositionMontage.json(DispositionMontage.dispose(prereglage("fm-ic9700-yagi")))
        assertEquals(5, plan.getJSONArray("boites").length())
    }
}
