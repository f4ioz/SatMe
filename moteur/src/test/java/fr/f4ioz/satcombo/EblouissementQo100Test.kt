/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.EblouissementQo100
import fr.f4ioz.satcombo.domain.PourquoiRien
import fr.f4ioz.satcombo.domain.SoleilQo100
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * QO-100's Sun outages: the threshold from the dish, the equinox seasons from
 * Paris (the geometry [SoleilQo100Test] checks at 1°), the outage under way,
 * the « Pourquoi rien ? » line and the API's answer.
 */
class EblouissementQo100Test {

    private val lat = 48.8566
    private val lon = 2.3522
    private val paris = ZoneId.of("Europe/Paris")
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()
    private fun jour(t: Long) = Instant.ofEpochMilli(t).atZone(ZoneOffset.UTC).toLocalDate().toString()

    @Test
    fun le_seuil_vient_du_diametre_de_la_parabole() {
        val d = EblouissementQo100.reglage(0)
        assertEquals(80, d.diametreCm)
        assertTrue(d.parDefaut)
        // 70 λ / D at 10.49 GHz: 70 × 0.02858 / 0.8 ≈ 2.50°.
        assertEquals(2.50, d.lobeDeg, 0.01)
        assertEquals(d.lobeDeg / 2 + 0.265, d.seuilDeg, 1e-9)
        val grande = EblouissementQo100.reglage(120)
        assertFalse(grande.parDefaut)
        assertTrue("plus grande, lobe plus étroit", grande.lobeDeg < d.lobeDeg)
        assertEquals(d.lobeDeg * 80 / 120, grande.lobeDeg, 1e-9)
    }

    @Test
    fun les_saisons_de_2026_depuis_paris_en_80_cm() {
        val r = EblouissementQo100.reglage(80)
        val l = EblouissementQo100.periodes(lat, lon, ms("2026-01-01T00:00:00Z"), r.seuilDeg, jours = 400, maximum = 40)
        val jours = l.map { jour(it.instantMs) }
        // The 1° alignment days are inside, and a wider lobe adds a day or two on each side.
        listOf("2026-02-28", "2026-03-01", "2026-03-02", "2026-03-03", "2026-03-04",
            "2026-10-09", "2026-10-10", "2026-10-11", "2026-10-12", "2026-10-13").forEach { assertTrue("$it manque dans $jours", it in jours) }
        assertTrue(l.size in 12..20)
        assertTrue(jours.all { it.startsWith("2026-02") || it.startsWith("2026-03") || it.startsWith("2026-10") })
        // One a day: never two within twelve hours.
        l.zipWithNext().forEach { (a, b) -> assertTrue(b.instantMs - a.instantMs > 12 * 3_600_000L) }
        l.forEach { p ->
            assertTrue(p.ecartDeg <= r.seuilDeg)
            assertTrue(p.debutMs <= p.instantMs && p.instantMs <= p.finMs)
            // The Sun moves 0.25°/min: a 3° wide window lasts at most a dozen minutes.
            assertTrue("${p.dureeS} s", p.dureeS in 60..800)
        }
        // The best day of March: about ten minutes, around 10:18 UTC (the 1° transit's instant).
        val mars = l.first { jour(it.instantMs) == "2026-03-02" }
        assertTrue(EblouissementQo100.minutes(mars) in 9..13)
        assertEquals(ms("2026-03-02T10:18:30Z").toDouble(), mars.instantMs.toDouble(), 90_000.0)
    }

    @Test
    fun l_eblouissement_en_cours_et_sa_fin() {
        val r = EblouissementQo100.reglage(80)
        val depuis = ms("2026-03-02T00:00:00Z")
        val l = EblouissementQo100.periodes(lat, lon, depuis, r.seuilDeg, jours = 3)
        val p = l.first()
        assertEquals("2026-03-02", jour(p.instantMs))
        assertEquals(p, EblouissementQo100.enCours(l, p.instantMs))
        assertNull(EblouissementQo100.enCours(l, p.finMs + 60_000L))
        assertEquals(p, EblouissementQo100.prochaine(l, depuis))
        // From inside the window, the outage under way is still the first: its start is not cut off.
        val dedans = EblouissementQo100.periodes(lat, lon, p.instantMs, r.seuilDeg, jours = 2)
        assertEquals(p.debutMs.toDouble(), dedans.first().debutMs.toDouble(), 2_000.0)
        // The separation at the instant really is under the threshold, and above it an hour later.
        assertTrue(EblouissementQo100.ecartDeg(lat, lon, p.instantMs)!! < r.seuilDeg)
        assertTrue(EblouissementQo100.ecartDeg(lat, lon, p.instantMs + 3_600_000L)!! > 5.0)
    }

    @Test
    fun pourquoi_rien_dit_que_c_est_le_soleil() {
        val fin = ms("2026-03-02T10:24:00Z")
        val t = fin - 120_000L
        val sdr = PourquoiRien.Sdr(true, 10_489_750_000L)
        val d = PourquoiRien.qo100(t, sdr, true, null, 0L, fin, paris)
        assertEquals(PourquoiRien.Gravite.INFO, d.gravite)
        assertEquals("Éblouissement solaire en cours jusqu'à 11:24 (10:24 UTC) — normal, pas une panne : le Soleil passe derrière QO-100 vu d'ici.", d.texte)
        // Over: back to the beacon's diagnosis.
        assertEquals(PourquoiRien.Gravite.ATTENTION, PourquoiRien.qo100(fin + 1, sdr, true, null, 0L, fin, paris).gravite)
        // The SDR stopped is still said first.
        assertTrue(PourquoiRien.qo100(t, sdr.copy(actif = false), true, null, 0L, fin, paris).texte.contains("arrêtée"))

        val datv = PourquoiRien.Datv(true, false, 1, "recherche", null, t - 60_000L, 0L, 10_491_500L, 1500, "h", "")
        assertTrue(PourquoiRien.datv(t, datv, fin, paris).texte.startsWith("Éblouissement solaire en cours jusqu'à 11:24"))
        // Locked: locked, whatever the Sun.
        assertEquals(PourquoiRien.Gravite.OK, PourquoiRien.datv(t, datv.copy(etat = 3, libelle = "verrouillé", verrouDepuisMs = t - 5_000L), fin, paris).gravite)
        assertFalse(PourquoiRien.datv(t, datv).texte.contains("Éblouissement"))
    }

    @Test
    fun la_reponse_de_l_api() {
        val r = EblouissementQo100.reglage(0)
        val depuis = ms("2026-03-02T00:00:00Z")
        val l = EblouissementQo100.periodes(lat, lon, depuis, r.seuilDeg, jours = 3)
        val p = l.first()
        val o = EblouissementQo100.json(r, l, p.instantMs, paris, true)
        assertEquals(80, o.getJSONObject("parabole").getInt("diametreCm"))
        assertTrue(o.getJSONObject("parabole").getBoolean("parDefaut"))
        assertEquals(1.52, o.getJSONObject("parabole").getDouble("seuilDeg"), 0.01)
        val ec = o.getJSONObject("enCours")
        assertEquals(p.finMs, ec.getLong("fin"))
        assertTrue(ec.getString("texte").contains("pas une panne"))
        assertTrue(ec.getString("debutUtc").startsWith("2026-03-02T10:"))
        assertEquals(EblouissementQo100.heure(p.debutMs, paris), ec.getString("debutLocal"))
        assertEquals("Europe/Paris", o.getString("zone"))
        assertTrue(o.getString("conseil").contains("pointage") || o.getString("conseil").contains("vise juste"))
        assertEquals(l.size, o.getJSONArray("periodes").length())
        assertTrue(EblouissementQo100.json(r, null, depuis, paris, true).getBoolean("calcul"))
        assertTrue(EblouissementQo100.json(r, null, depuis, paris, false).getBoolean("inconnu"))
        assertTrue(o.isNull("prochaine").not() || l.size == 1)
        assertNotNull(o.opt("prochaine"))
    }
}
