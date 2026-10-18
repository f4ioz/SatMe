/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.adsb

import fr.f4ioz.satcombo.sonde.Geo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class AdsbTest {

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    // ---------------------------------------------------------------- parity

    @Test fun parite_exemples_publics() {
        // Widely published example extended squitters: identification, an even/odd position pair, a velocity.
        for (f in listOf("8D4840D6202CC371C32CE0576098", "8D40621D58C382D690C8AC2863A7", "8D40621D58C386435CC412692AD6", "8D485020994409940838175B284F"))
            assertEquals(f, 0, ModeS.syndrome(hex(f), 112))
    }

    @Test fun identification_publique() {
        val m = MessageAdsb.lis(hex("8D4840D6202CC371C32CE0576098"), 112) as MessageAdsb.Identification
        assertEquals(0x4840D6, m.icao)
        assertEquals("KLM1023", m.indicatif)
    }

    @Test fun position_publique_globale() {
        val p0 = MessageAdsb.lis(hex("8D40621D58C382D690C8AC2863A7"), 112) as MessageAdsb.Position
        val p1 = MessageAdsb.lis(hex("8D40621D58C386435CC412692AD6"), 112) as MessageAdsb.Position
        assertFalse(p0.impair); assertTrue(p1.impair)
        assertEquals(38000, p0.altitudePieds)
        val (lat, lon) = Cpr.globale(p0.latCpr, p0.lonCpr, p1.latCpr, p1.lonCpr, impairDernier = false)!!
        assertEquals(52.2572, lat, 1e-3)
        assertEquals(3.9194, lon, 1e-3)
    }

    @Test fun vitesse_publique() {
        val v = MessageAdsb.lis(hex("8D485020994409940838175B284F"), 112) as MessageAdsb.Vitesse
        assertEquals(159.2, v.vitesseNoeuds!!, 0.1)
        assertEquals(182.88, v.routeDeg!!, 0.05)
        assertEquals(-832, v.vertical)
    }

    @Test fun crc_calcule_et_un_bit_repare() {
        val f = EncodeurAdsb.identification(0xABCDEF, "F4IOZ")
        assertEquals(0, ModeS.syndrome(f, 112))
        for (i in listOf(5, 20, 47, 88, 111)) {
            val g = f.copyOf()
            g[i ushr 3] = (g[i ushr 3].toInt() xor (1 shl (7 - (i and 7)))).toByte()
            assertTrue(ModeS.syndrome(g, 112) != 0)
            assertEquals(i, ModeS.corrigeUnBit(g, 112))
            assertTrue(f.contentEquals(g))
        }
        // The DF field is never "repaired".
        val g = f.copyOf(); g[0] = (g[0].toInt() xor 0x80).toByte()
        assertEquals(-1, ModeS.corrigeUnBit(g, 112))
    }

    @Test fun trames_construites_relues() {
        val id = MessageAdsb.lis(EncodeurAdsb.identification(0x3C6DD2, "DLH4AB"), 112) as MessageAdsb.Identification
        assertEquals("DLH4AB", id.indicatif); assertEquals("moyen", id.categorie)
        val v = MessageAdsb.lis(EncodeurAdsb.vitesse(0x3C6DD2, 450.0, 300.0, -1024), 112) as MessageAdsb.Vitesse
        assertEquals(450.0, v.vitesseNoeuds!!, 1.0); assertEquals(300.0, v.routeDeg!!, 0.2); assertEquals(-1024, v.vertical)
        val p = MessageAdsb.lis(EncodeurAdsb.position(0x3C6DD2, 48.0, 2.0, 37_025, true), 112) as MessageAdsb.Position
        assertEquals(37_025, p.altitudePieds); assertTrue(p.impair); assertFalse(p.gnss)
        val g = MessageAdsb.lis(EncodeurAdsb.position(0x3C6DD2, 48.0, 2.0, 30_000, false, tc = 20), 112) as MessageAdsb.Position
        assertTrue(g.gnss); assertEquals(30_000.0, g.altitudePieds!!.toDouble(), 4.0)
        val s = MessageAdsb.lis(EncodeurAdsb.surface(0x3C6DD2, 49.0, 2.55, false, mov = 20, routeDeg = 90.0), 112) as MessageAdsb.Position
        assertTrue(s.surface); assertEquals(5.5, s.vitesseNoeuds!!, 0.01); assertEquals(90.0, s.routeDeg!!, 1.5)
        val df4 = EncodeurAdsb.altitudeCourte(0x3C6DD2, 12_500)
        assertEquals(0x3C6DD2, ModeS.syndrome(df4, 56))
        assertEquals(12_500, (MessageAdsb.lis(df4, 56, 0x3C6DD2) as MessageAdsb.Altitude).altitudePieds)
    }

    @Test fun gillham_bijection_par_100_pieds() {
        // Every 13-bit code without M or Q that decodes gives a distinct multiple of 100 ft above −1200.
        val vus = HashSet<Int>()
        for (c in 0 until 8192) {
            if (c and 0x50 != 0) continue
            val a = ModeS.gillham(c) ?: continue
            assertEquals(0, (a + 1200) % 100)
            assertTrue(a >= -1200)
            assertTrue("doublon $a", vus.add(a))
        }
        assertTrue(vus.size > 1200)
        // The lowest: C4 alone.
        assertEquals(-1200, ModeS.gillham(0b0000100000000))
    }

    @Test fun squawk_7700() {
        // A = 7 (A1 A2 A4), B = 7, C = 0, D = 0.
        val id = (1 shl 11) or (1 shl 9) or (1 shl 7) or (1 shl 5) or (1 shl 3) or (1 shl 1)
        assertEquals("7700", ModeS.squawk(id))
    }

    // ---------------------------------------------------------------- CPR

    @Test fun cpr_globale_aller_retour_moins_de_10_m() {
        val r = Random(7)
        repeat(300) {
            val lat = r.nextDouble(-80.0, 80.0); val lon = r.nextDouble(-180.0, 180.0)
            val e = Cpr.encode(lat, lon, false); val o = Cpr.encode(lat, lon, true)
            for (dernier in listOf(false, true)) {
                val p = Cpr.globale(e.first, e.second, o.first, o.second, dernier) ?: continue
                assertTrue("$lat $lon → $p", Geo.distanceKm(lat, lon, p.first, p.second) < 0.010)
            }
        }
    }

    @Test fun cpr_locale_station() {
        // JN18 (Paris area), an aircraft 250 km away.
        val st = 48.85 to 2.35
        val r = Random(3)
        repeat(200) {
            val lat = st.first + r.nextDouble(-2.2, 2.2); val lon = st.second + r.nextDouble(-3.0, 3.0)
            for (impair in listOf(false, true)) {
                val (y, x) = Cpr.encode(lat, lon, impair)
                val p = Cpr.locale(y, x, impair, st.first, st.second)
                assertTrue(Geo.distanceKm(lat, lon, p.first, p.second) < 0.010)
            }
        }
    }

    @Test fun cpr_surface_avec_reference() {
        val lat = 49.0097; val lon = 2.5479
        val e = Cpr.encode(lat, lon, false, surface = true); val o = Cpr.encode(lat, lon, true, surface = true)
        val p = Cpr.globale(e.first, e.second, o.first, o.second, true, surface = true, ref = 48.85 to 2.35)!!
        assertTrue(Geo.distanceKm(lat, lon, p.first, p.second) < 0.005)
        val l = Cpr.locale(o.first, o.second, true, 48.85, 2.35, surface = true)
        assertTrue(Geo.distanceKm(lat, lon, l.first, l.second) < 0.005)
        assertNull(Cpr.globale(e.first, e.second, o.first, o.second, true, surface = true))
    }

    @Test fun nl_bornes() {
        assertEquals(59, Cpr.nl(0.0)); assertEquals(2, Cpr.nl(87.0)); assertEquals(1, Cpr.nl(88.0))
        assertEquals(Cpr.nl(52.0), Cpr.nl(-52.0))
    }

    // ---------------------------------------------------------------- the table

    @Test fun table_paire_puis_locale() {
        var t = 1_000_000L
        val tab = TableAvions { t }
        tab.station = 48.85 to 2.35
        tab.recoit(MessageAdsb.lis(EncodeurAdsb.position(0x123456, 49.5, 3.1, 35_000, false), 112)!!, -20.0)
        t += 400
        tab.recoit(MessageAdsb.lis(EncodeurAdsb.position(0x123456, 49.501, 3.102, 35_000, true), 112)!!, -20.0)
        val a = tab.liste().single()
        assertTrue(a.posSure)
        assertEquals(49.501, a.lat!!, 1e-4)
        t += 500
        tab.recoit(MessageAdsb.lis(EncodeurAdsb.position(0x123456, 49.505, 3.11, 35_000, false), 112)!!, -20.0)
        assertEquals(3.11, a.lon!!, 1e-4)
        assertEquals(35_000, a.altitudePieds)
        assertEquals(3L, a.messages)
        // Forgotten after a minute of silence.
        t += 61_000
        assertTrue(tab.liste().isEmpty())
    }

    @Test fun table_locale_station_seule_marquee() {
        var t = 0L
        val tab = TableAvions { t }
        tab.station = 48.85 to 2.35
        tab.recoit(MessageAdsb.lis(EncodeurAdsb.position(0x111111, 48.0, 1.5, 20_000, true), 112)!!, -30.0)
        val a = tab.liste().single()
        assertFalse(a.posSure)
        assertEquals(48.0, a.lat!!, 1e-4)
    }

    @Test fun sbs_lignes() {
        val tab = TableAvions { 1_700_000_000_000L }
        val lignes = ArrayList<String>()
        tab.ecouteur = { m, a -> Sbs.ligne(m, a, 1_700_000_000_000L)?.let { lignes += it } }
        tab.recoit(MessageAdsb.lis(EncodeurAdsb.identification(0x4840D6, "KLM1023"), 112)!!, -10.0)
        tab.recoit(MessageAdsb.lis(EncodeurAdsb.vitesse(0x4840D6, 300.0, 90.0, 640), 112)!!, -10.0)
        assertEquals(2, lignes.size)
        assertEquals("MSG,1,1,1,4840D6,1,2023/11/14,22:13:20.000,2023/11/14,22:13:20.000,KLM1023,,,,,,,,,,,", lignes[0])
        val f = lignes[1].split(",")
        assertEquals(22, f.size); assertEquals("4", f[1]); assertEquals("300", f[12]); assertEquals("90", f[13]); assertEquals("640", f[16])
    }

    // ---------------------------------------------------------------- demodulation

    @Test fun demodulation_flux_synthetique_avec_bruit() {
        val r = Random(11)
        val n = 400_000
        val mag = FloatArray(n) { kotlin.math.abs(0.03f * (r.nextFloat() + r.nextFloat() + r.nextFloat() - 1.5f)) }
        val envoyees = ArrayList<String>()
        var pos = 500
        var k = 0
        while (pos + 300 < n) {
            val f = when (k % 4) {
                0 -> EncodeurAdsb.identification(0x400000 + k, "T" + k)
                1 -> EncodeurAdsb.position(0x400000 + k, 45.0 + k * 0.01, 5.0, 30_000, k % 2 == 0)
                2 -> EncodeurAdsb.vitesse(0x400000 + k, 400.0, 45.0, 0)
                else -> EncodeurAdsb.allCall(0x400000 + k)
            }
            val bits = if (k % 4 == 3) 56 else 112
            // Phases from 0 to 0.3 of a sample, amplitudes 10 to 20 dB over the noise.
            EncodeurAdsb.module(f, bits, mag, pos, 0.15f + 0.15f * r.nextFloat(), (k % 4) * 0.1f)
            envoyees += f.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
            pos += 300 + r.nextInt(2000)
            k++
        }
        val recues = ArrayList<String>()
        val d = DemodAdsb { recues += it.hex() }
        // Fed in uneven blocks, as a socket gives them.
        var i = 0
        while (i < n) { val m = minOf(n - i, 7_777 + r.nextInt(30_000)); d.traite(mag.copyOfRange(i, i + m), m); i += m }
        val trouvees = envoyees.count { it in recues }
        assertTrue("$trouvees / ${envoyees.size}", trouvees >= envoyees.size * 95 / 100)
        // Nothing made up: every reply out is one sent.
        assertTrue(recues.all { it in envoyees })
    }

    @Test fun demodulation_un_bit_casse_repare() {
        val f = EncodeurAdsb.identification(0x3944F0, "FHBXA")
        val mag = FloatArray(2000)
        EncodeurAdsb.module(f, 112, mag, 100, 0.5f)
        // Bit 60 inverted on the air: its two half-microseconds swapped.
        val s = 100 + 16 + 2 * 60
        val t = mag[s]; mag[s] = mag[s + 1]; mag[s + 1] = t
        val recues = ArrayList<DemodAdsb.Trame>()
        val d = DemodAdsb { recues += it }
        d.traite(mag, mag.size)
        assertEquals(1, recues.size)
        assertTrue(recues[0].corrigee)
        assertTrue(f.contentEquals(recues[0].octets))
        d.corrige = false
        recues.clear(); d.remet(); d.traite(mag, mag.size)
        assertTrue(recues.isEmpty())
    }

    @Test fun simulateur_jusqu_a_la_table() {
        val sim = SimulateurAdsb(48.85, 2.35)
        val tab = TableAvions()
        tab.station = 48.85 to 2.35
        val d = DemodAdsb(connu = tab::connu) { tab.recoit(it) }
        val buf = FloatArray(200_000)
        repeat(30) { val n = sim.bloc(100, buf); d.traite(buf, n) }
        val l = tab.liste()
        assertEquals(sim.vols.size, l.size)
        for (v in sim.vols) {
            val a = l.first { it.icao == v.icao }
            assertEquals(v.indicatif, a.indicatif)
            assertNotNull(a.lat)
            assertTrue(Geo.distanceKm(v.lat, v.lon, a.lat!!, a.lon!!) < 2.0)
        }
    }

    // ---------------------------------------------------------------- scatter

    @Test fun milieu_et_horizon() {
        val (la, lo) = DiffusionAvions.milieu(0.0, 0.0, 0.0, 10.0)
        assertEquals(0.0, la, 1e-9); assertEquals(5.0, lo, 1e-9)
        val m = DiffusionAvions.milieu(48.85, 2.35, 52.37, 4.9)
        assertEquals(Geo.distanceKm(48.85, 2.35, m.first, m.second), Geo.distanceKm(52.37, 4.9, m.first, m.second), 0.01)
        // 4/3 earth: about 4.12 √h km.
        assertEquals(4.12 * (10.0 + 100.0), DiffusionAvions.horizonKm(100.0, 10_000.0), 1.5)
        assertEquals(0.0, DiffusionAvions.horizonKm(0.0, 0.0), 1e-9)
        assertTrue(DiffusionAvions.elevationDeg(100.0, 11_000.0, 200.0) > 2.0)
        assertTrue(DiffusionAvions.elevationDeg(100.0, 11_000.0, 430.0) < 0.1)
    }

    @Test fun fenetre_avion_traversant_le_milieu() {
        val r = DiffusionAvions.Reglage(48.0, 0.0, 100.0, 48.0, 8.0, 100.0, bande = "23cm", rayonKm = 50.0)
        val mil = DiffusionAvions.milieu(48.0, 0.0, 48.0, 8.0)
        // 60 km west of the midpoint (along the path), heading east at 480 kt (~889 km/h), 36 000 ft.
        val (la, lo) = DiffusionAvions.avance(mil.first, mil.second, 270.0, 60.0)
        val now = 10_000_000L
        val a = TableAvions.Avion(0xAAAAAA).apply { lat = la; lon = lo; posMs = now; altitudePieds = 36_000; vitesseNoeuds = 480.0; routeDeg = 90.0 }
        val f = DiffusionAvions.fenetres(r, listOf(a), now).single()
        assertFalse(f.ouverte)
        // Enters after ~10 km (40 s), leaves after ~110 km (~445 s).
        assertEquals(40.0, (f.debutMs - now) / 1000.0, 6.0)
        assertEquals(445.0, (f.finMs - now) / 1000.0, 6.0)
        assertTrue(f.elA > 0 && f.elB > 0)
        // Flying east along the path at the middle: one range shrinks as the other grows, the Doppler small.
        assertTrue(kotlin.math.abs(f.dopplerHz) < 1500.0)
        // Too low to see both ends: no window.
        a.altitudePieds = 3_500
        assertTrue(DiffusionAvions.fenetres(r, listOf(a), now).isEmpty())
    }

    @Test fun trajet_trop_long() {
        val r = DiffusionAvions.Reglage(48.0, -5.0, 10.0, 48.0, 10.0, 10.0)
        assertFalse(DiffusionAvions.geometrieJson(r).getBoolean("possible"))
    }
}
