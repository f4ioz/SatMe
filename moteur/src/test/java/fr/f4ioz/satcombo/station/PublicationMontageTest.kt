/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.station

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** « Description du montage » for a web site: the paths found, the plan chosen, only the keys allowed, no address. */
class PublicationMontageTest {

    private fun prereglage(id: String) = CatalogueMateriel.PREREGLAGES.first { it.id == id }.chaine
    private fun modeles(c: Montage.Chaine, v: PublicationMontage.Voie) = v.etapes.map { s -> c.element(s.element)!!.modele }

    private val montee80 = PublicationMontage.Montee("IC-9700 + convertisseur montant", "Parabole 80 cm + source POTY", "parabole-qo100",
        80, 23.46, 20.0, 1.5, 34.97)

    @Test fun `the full QO-100 station gives its receive and transmit paths, from the dish`() {
        val c = prereglage("qo100-station")
        val v = PublicationMontage.voies(c)
        val rx = v.filter { it.rx }.map { modeles(c, it) }
        assertTrue(rx.toString(), listOf("parabole-qo100", "lnb-twin", "minitiouner") in rx)
        assertTrue(rx.toString(), listOf("parabole-qo100", "lnb-twin", "dxp-down-mk4", "pluto") in rx)
        val tx = v.filter { !it.rx }.map { modeles(c, it) }
        assertEquals(listOf(listOf("ic9700", "dxp-up-mk4", "dxp-pa-20w", "parabole-qo100")), tx)
        assertTrue(v.all { it.qo100 })
        // The bands on the cables: 10 GHz into the LNB, its IF, then 144 MHz out of the down-converter.
        val pluto = v.first { it.rx && modeles(c, it).last() == "pluto" }
        assertEquals(listOf(null, 10489, 739, 144), pluto.etapes.map { it.mhz })
        val up = v.first { !it.rx }
        assertEquals(listOf(null, 435, 2400, 2400), up.etapes.map { it.mhz })
    }

    @Test fun `LEO yagis are paths too, not QO-100 ones, after them`() {
        val c = prereglage("fm-ic9700-yagi")
        val v = PublicationMontage.voies(c)
        assertTrue(v.isNotEmpty())
        assertTrue(v.none { it.qo100 })
        assertTrue(v.any { it.rx } && v.any { !it.rx })
        assertEquals(setOf("yagi-2m", "yagi-70cm"), v.filter { it.rx }.map { modeles(c, it).first() }.toSet())
    }

    @Test fun `the plan published is the one drawing QO-100, the owner's on a tie`() {
        val p = PlansMontage.Plans(listOf(PlansMontage.Plan("p1", prereglage("fm-ic9700-yagi")), PlansMontage.Plan("p2", prereglage("qo100-pluto"))), "p1")
        assertEquals("p2", PublicationMontage.choisit(p)!!.id)
        val q = PlansMontage.Plans(listOf(PlansMontage.Plan("p1", prereglage("qo100-pluto")), PlansMontage.Plan("p2", prereglage("qo100-pluto"))), "p2")
        assertEquals("p2", PublicationMontage.choisit(q)!!.id)
        val r = PlansMontage.Plans(listOf(PlansMontage.Plan("p1", prereglage("fm-ic9700-yagi"))), "p1")
        assertEquals("p1", PublicationMontage.choisit(r)!!.id)
        assertNull(PublicationMontage.choisit(PlansMontage.Plans(listOf(PlansMontage.Plan("p1", Montage.Chaine())), "p1")))
    }

    @Test fun `the message carries the pieces, their options and references, the uplink and the converters`() {
        val c = prereglage("qo100-station")
        val o = PublicationMontage.json("Station QO-100", c, montee80,
            listOf(PublicationMontage.Conversion("rx", 10_344_973_000L, false, 144_027_000L, 155_027_000L),
                PublicationMontage.Conversion("tx", 1_968_000_000L, false, 432_000_000L, 482_000_000L)))!!
        assertEquals("Station QO-100", o.getString("plan"))
        val el = o.getJSONArray("elements").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        val dish = el.first { it.getString("modele") == "parabole-qo100" }
        assertEquals("antenne", dish.getString("genre"))
        assertEquals(80, dish.getInt("diametreCm")); assertEquals(23.5, dish.getDouble("gainDbi"), 1e-9)
        val lnb = el.first { it.getString("modele") == "lnb-twin" }
        assertEquals("lnb", lnb.getString("genre"))
        assertEquals("oui", lnb.getJSONArray("options").getJSONObject(0).getString("valeur"))
        val ref = lnb.getJSONArray("references").getJSONObject(0)
        assertEquals(25, ref.getInt("mhz"))
        assertEquals("leo-bodnar", el.first { it.getString("id") == ref.getString("de") }.getString("modele"))
        assertEquals(JSONArray(listOf(739, 10489)).toString(), lnb.getJSONArray("bandesMhz").toString())
        val m = o.getJSONObject("montee")
        assertEquals(80, m.getInt("diametreCm")); assertEquals(20.0, m.getDouble("ampliW"), 1e-9); assertEquals(35.0, m.getDouble("pireMaxDbw"), 1e-9)
        val conv = o.getJSONArray("conversions")
        assertEquals(10344.973, conv.getJSONObject(0).getDouble("olMhz"), 1e-9)
        assertEquals("tx", conv.getJSONObject(1).getString("sens"))
        val voie = o.getJSONArray("voies").getJSONObject(0)
        assertEquals("rx", voie.getString("sens")); assertTrue(voie.getBoolean("qo100"))
        assertEquals(dish.getString("id"), voie.getJSONArray("etapes").getJSONObject(0).getString("id"))
        assertEquals("Coaxial F (TV)", voie.getJSONArray("etapes").getJSONObject(2).getString("cable"))
        cles(o)
    }

    @Test fun `the uplink profile only goes when the plan draws its antenna`() {
        val c = prereglage("qo100-pluto-helice")
        val o = PublicationMontage.json("", c, montee80)!!
        assertFalse(o.has("montee")); assertFalse(o.has("plan")); assertFalse(o.has("conversions"))
        val dish = o.getJSONArray("elements").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.first { it.getString("modele") == "parabole-qo100" }
        assertFalse(dish.has("diametreCm"))
        // The portable dish says its own size.
        val p = PublicationMontage.json("", prereglage("qo100-portable-35"))!!
        assertEquals(35, p.getJSONArray("elements").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.first { it.getString("modele") == "parabole-35" }.getInt("diametreCm"))
    }

    @Test fun `programs are left out, and a name that looks like an address gives way to the catalogue's`() {
        var c = prereglage("qo100-pluto")
        c = c.copy(nom = "Plan 192.168.1.20", elements = c.elements.map { e ->
            when (e.modele) {
                "pluto" -> e.copy(nom = "Pluto ip:192.168.2.1")
                "pa-2400" -> e.copy(nom = "PA S/N 2041XK7")
                "lnb-pll" -> e.copy(nom = "Mon LNB préféré")
                else -> e
            }
        } + Montage.Element("b1", "boite-wavelog", "wavelog.example.org"))
        val o = PublicationMontage.json(c.nom, c)!!
        val texte = o.toString()
        listOf("192.168", "2041XK7", "wavelog", "example.org", "\"b1\"").forEach { assertFalse(it, texte.contains(it)) }
        assertFalse(o.has("plan"))
        val el = o.getJSONArray("elements").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        assertEquals("ADALM-PLUTO", el.first { it.getString("modele") == "pluto" }.getString("nom"))
        assertEquals("Mon LNB préféré", el.first { it.getString("modele") == "lnb-pll" }.getString("nom"))
        assertEquals("Clé RTL-SDR bleue", PublicationMontage.propre("Clé RTL-SDR bleue", 60))
        cles(o)
    }

    @Test fun `an empty chain sends nothing`() {
        assertNull(PublicationMontage.json("x", Montage.Chaine()))
        assertNull(PublicationMontage.json("x", Montage.Chaine(listOf(Montage.Element("b1", "boite-qrz")))))
        assertNotNull(PublicationMontage.json("x", Montage.Chaine(listOf(Montage.Element("e1", "pc")))))
    }

    /** Every key of [o] is one [PublicationMontage.CLES] allows at its level. */
    private fun cles(o: JSONObject) {
        fun verifie(x: JSONObject, niveau: String) {
            val permis = PublicationMontage.CLES.getValue(niveau)
            for (k in x.keySet()) assertTrue("$niveau.$k", k in permis)
        }
        fun liste(a: JSONArray?, niveau: String, f: (JSONObject) -> Unit = {}) { if (a != null) for (i in 0 until a.length()) a.getJSONObject(i).also { verifie(it, niveau); f(it) } }
        verifie(o, "")
        liste(o.optJSONArray("elements"), "element") { e -> liste(e.optJSONArray("options"), "option"); liste(e.optJSONArray("references"), "reference") }
        liste(o.optJSONArray("voies"), "voie") { v -> liste(v.optJSONArray("etapes"), "etape") }
        o.optJSONObject("montee")?.let { verifie(it, "montee") }
        liste(o.optJSONArray("conversions"), "conversion")
    }
}
