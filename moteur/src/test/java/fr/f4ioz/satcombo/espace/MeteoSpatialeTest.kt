/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.espace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The space weather products read from small samples written by hand in
 * the shapes the NOAA SWPC products have (objects, older rows under a
 * header row, a NUL after the JSON), the thresholds and the alerts.
 */
class MeteoSpatialeTest {

    private fun f(n: String) = MeteoSpatialeTest::class.java.getResourceAsStream("/espace/$n")!!.readBytes().toString(Charsets.UTF_8)
    private val t = { s: String -> MeteoSpatiale.temps(s)!! }

    private fun etat() = MeteoSpatiale.Etat(MeteoSpatiale.lisKp(f("kp.json")), MeteoSpatiale.lisPrevision(f("prevision.json")),
        MeteoSpatiale.lisFlux(f("f107.json")), MeteoSpatiale.lisRayons(f("rayons.json")), MeteoSpatiale.lisVent(f("vent.json"), f("champ.json")),
        MeteoSpatiale.lisAurore(f("aurore.json"), 48.4, -4.2, 1500.0))

    @Test fun temps() {
        assertEquals(1_792_281_600_000L, t("2026-10-18T00:00:00"))
        assertEquals(t("2026-10-18T00:00:00"), t("2026-10-18 00:00:00.000"))
        assertEquals(t("2026-10-18T20:11:00"), t("2026-10-18T20:11:00Z"))
        assertNull(MeteoSpatiale.temps("hier"))
    }

    @Test fun kp_objets_et_lignes() {
        val a = MeteoSpatiale.lisKp(f("kp.json"))
        val b = MeteoSpatiale.lisKp(f("kp-ancien.json"))
        assertEquals(8, a.size)
        assertEquals(a, b)
        assertEquals(5.33, a.first { it.t == t("2026-10-17T12:00:00") }.kp, 1e-9)
    }

    @Test fun prevision() {
        val p = MeteoSpatiale.lisPrevision(f("prevision.json"))
        assertEquals(18, p.size)
        assertEquals("estime", p.first { it.t == t("2026-10-18T00:00:00") }.nature)
        val pic = p.maxByOrNull { if (it.nature == "prevu") it.kp else 0.0 }!!
        assertEquals(6.0, pic.kp, 0.0); assertEquals("G2", pic.echelle)
    }

    @Test fun flux_a_midi() {
        val x = MeteoSpatiale.lisFlux(f("f107.json"))!!
        // The noon value is the index; the morning one after it is not taken.
        assertEquals(132.0, x.sfu, 0.0); assertEquals(117.0, x.moyenne90!!, 0.0)
    }

    @Test fun rayons_x_et_classes() {
        val r = MeteoSpatiale.lisRayons(f("rayons.json"))
        assertEquals(60, r.size)   // the short band left out, the NUL after the JSON ignored
        assertEquals("M5.2", MeteoSpatiale.classeX(r.maxOf { it.flux }))
        assertEquals("B8.0", MeteoSpatiale.classeX(8e-7))
        assertEquals("C2.1", MeteoSpatiale.classeX(2.1e-6))
        assertEquals("X1.2", MeteoSpatiale.classeX(1.2e-4))
        assertEquals("X12.0", MeteoSpatiale.classeX(1.2e-3))
        assertEquals(2, MeteoSpatiale.echelleR(5.2e-5)); assertEquals(1, MeteoSpatiale.echelleR(1e-5)); assertEquals(0, MeteoSpatiale.echelleR(9e-6))
    }

    @Test fun vent() {
        val v = MeteoSpatiale.lisVent(f("vent.json"), f("champ.json"))!!
        assertEquals(612.0, v.vitesse!!, 0.0); assertEquals(-9.0, v.bz!!, 0.0); assertEquals(14.0, v.bt!!, 0.0)
        assertEquals(t("2026-10-18T20:11:00"), v.t)
    }

    @Test fun aurore_a_la_station() {
        val a = MeteoSpatiale.lisAurore(f("aurore.json"), 48.4, -4.2, 1500.0)!!
        assertEquals(3, a.ici)
        // 61° N is 1 400 km north, 62° N beyond 1 500 km.
        assertEquals(16, a.proche); assertEquals(61.0, a.procheLat!!, 0.0)
        assertTrue(a.ovale.all { it[2] >= 10 && it[0] in -180..180 })
        assertEquals(t("2026-10-18T21:14:00"), a.prevision)
        assertNull(MeteoSpatiale.lisAurore("<html>", 48.0, 0.0))
    }

    @Test fun echelles_et_ecriture() {
        assertEquals("3+", MeteoSpatiale.kpTexte(3.33)); assertEquals("4−", MeteoSpatiale.kpTexte(3.67)); assertEquals("5", MeteoSpatiale.kpTexte(5.0)); assertEquals("0+", MeteoSpatiale.kpTexte(0.33))
        assertEquals(0, MeteoSpatiale.echelleG(4.33)); assertEquals(1, MeteoSpatiale.echelleG(4.67)); assertEquals(2, MeteoSpatiale.echelleG(6.0)); assertEquals(5, MeteoSpatiale.echelleG(9.0))
        assertEquals("calme", MeteoSpatiale.niveauKp(3.33)); assertEquals("agite", MeteoSpatiale.niveauKp(4.0)); assertEquals("orage", MeteoSpatiale.niveauKp(5.0)); assertEquals("fort", MeteoSpatiale.niveauKp(7.0))
    }

    @Test fun alertes() {
        val e = etat()
        // During the storm of the 17th.
        val a = MeteoSpatiale.alertes(e, t("2026-10-17T14:00:00"))
        assertEquals(1, a.size)
        assertEquals("kp-" + t("2026-10-17T12:00:00"), a[0].cle)
        assertTrue(a[0].titre, a[0].titre.contains("G1") && a[0].titre.contains("5+"))
        // The flare at 20:40 on the 18th, the storm over.
        val b = MeteoSpatiale.alertes(e, t("2026-10-18T20:45:00"))
        assertEquals(1, b.size)
        assertTrue(b[0].titre, b[0].titre.contains("M5.2"))
        // An hour later: nothing.
        assertTrue(MeteoSpatiale.alertes(e, t("2026-10-18T21:45:00")).isEmpty())
    }

    @Test fun json_page() {
        val o = MeteoSpatiale.json(etat(), t("2026-10-18T20:45:00"))
        val k = o.getJSONObject("kp")
        assertEquals(2.0, k.getDouble("kp"), 0.0); assertEquals("estime", k.getString("nature")); assertEquals("calme", k.getString("niveau"))
        assertEquals(3.0, o.getJSONObject("kpMaxPrevu").getDouble("kp"), 0.0)
        // From a day back to the end of the forecast.
        assertEquals(t("2026-10-17T21:00:00"), o.getJSONArray("barres").getJSONObject(0).getLong("t"))
        assertEquals(132, o.getJSONObject("f107").getInt("sfu"))
        assertEquals("C2.0", o.getJSONObject("rayons").getString("classe"))
        assertEquals("M5.2", o.getJSONObject("rayons").getString("max6h"))
        assertEquals(612.0, o.getJSONObject("vent").getDouble("vitesse"), 0.0)
        assertEquals(16, o.getJSONObject("aurore").getInt("proche"))
        assertEquals(1, o.getJSONArray("alertes").length())
        assertNotNull(MeteoSpatiale.PRODUITS["kp"])
        assertTrue(MeteoSpatiale.PRODUITS.values.none { it.contains("celestrak") } && MeteoSpatiale.BASE.startsWith("https://services.swpc.noaa.gov/"))
    }
}
