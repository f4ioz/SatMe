/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.ais

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * AIS from the ITU-R M.1371 description: published sentences decoded to
 * the fields worked out by hand from the bit table, the framing (stuffing,
 * NRZI, the X.25 check) and a GMSK signal in noise decoded end to end.
 */
class AisTest {

    private fun msg(s: String): MessageAis {
        val a = Aivdm.Assembleur()
        var r: Pair<BooleanArray, Char>? = null
        for (l in s.split('\n')) r = a.lit(l) ?: r
        return MessageAis.lis(assertNotNullR(r).first)!!
    }
    private fun <T> assertNotNullR(v: T?): T { assertNotNull(v); return v!! }
    private fun payload(p: String, fill: Int = 0) = MessageAis.lis(Aivdm.desarme(p, fill)!!)!!

    // ---------------------------------------------------------------- sentences

    @Test fun somme_de_controle() {
        val a = Aivdm.Assembleur()
        // A sentence quoted with a wrong checksum (the XOR is 0x24): refused; right, read.
        assertNull(a.lit("!AIVDM,1,1,,A,13u?etPv2;0n:dDPwUM1U1Cb069D,0*23"))
        assertEquals(1, a.rejetees)
        assertNotNull(a.lit("!AIVDM,1,1,,A,13u?etPv2;0n:dDPwUM1U1Cb069D,0*24"))
        assertNotNull(a.lit("!AIVDM,1,1,,B,177KQJ5000G?tO`K>RA1wUbN0TKH,0*5C"))
    }

    @Test fun position_classe_a_publique() {
        val m = payload("13u?etPv2;0n:dDPwUM1U1Cb069D")
        assertEquals(1, m.type)
        assertEquals(265547250, m.mmsi)
        assertEquals(0, m.statut)
        assertEquals(-8, m.rot)
        assertEquals(13.9, m.vitesse!!, 1e-9)
        assertEquals(11.832976667, m.lon!!, 1e-6)   // 7099786 / 600000
        assertEquals(57.660353333, m.lat!!, 1e-6)   // 34596212 / 600000
        assertEquals(40.4, m.route!!, 1e-9)
        assertEquals(41, m.cap)
        assertEquals(53, m.horodatage)
    }

    @Test fun position_amarre_publique() {
        val m = msg("!AIVDM,1,1,,B,177KQJ5000G?tO`K>RA1wUbN0TKH,0*5C")
        assertEquals(477553000, m.mmsi)
        assertEquals(5, m.statut)   // moored
        assertEquals(0.0, m.vitesse!!, 0.0)
        assertEquals(-122.345833, m.lon!!, 1e-6)
        assertEquals(47.582833, m.lat!!, 1e-6)
        assertEquals(51.0, m.route!!, 1e-9)
        assertEquals(181, m.cap)
    }

    @Test fun statique_en_deux_phrases() {
        val m = msg("!AIVDM,2,1,1,A,55?MbV02;H;s<HtKR20EHE:0@T4@Dn2222222216L961O5Gf0NSQEp6ClRp8,0*1C\n!AIVDM,2,2,1,A,88888888880,2*25")
        assertEquals(5, m.type)
        assertEquals(351759000, m.mmsi)
        assertEquals(9134270, m.imo)
        assertEquals("3FOF8", m.indicatif)
        assertEquals("EVER DIADEM", m.nom)
        assertEquals(70, m.typeNavire)
        assertArrayEquals(intArrayOf(225, 70, 1, 31), m.dimensions)
        assertArrayEquals(intArrayOf(5, 15, 14, 0), m.eta)
        assertEquals(12.2, m.tirant!!, 1e-9)
        assertEquals("NEW YORK", m.destination)
    }

    @Test fun deuxieme_fragment_seul_ignore() {
        assertNull(Aivdm.Assembleur().lit("!AIVDM,2,2,1,A,88888888880,2*25"))
    }

    // Payloads built from the bit table apart from SatMe's encoder.
    @Test fun classe_b() {
        val m = payload("B3H`I600=wrnJ@6s7H0<Swg5h000")
        assertEquals(18, m.type); assertEquals("B", m.classe)
        assertEquals(227154200, m.mmsi)
        assertEquals(5.5, m.vitesse!!, 1e-9)
        assertEquals(-4.5, m.lon!!, 1e-9); assertEquals(48.4, m.lat!!, 1e-9)
        assertEquals(20.0, m.route!!, 1e-9); assertNull(m.cap); assertEquals(30, m.horodatage)
    }

    @Test fun classe_b_etendu() {
        val m = payload("C3H`I6@0?WrnJ@6s7H1=8v?0VFBPP:T0000000000000BP`110RP")
        assertEquals(19, m.type); assertEquals(227154201, m.mmsi)
        assertEquals(6.2, m.vitesse!!, 1e-9); assertEquals(123.4, m.route!!, 1e-9); assertEquals(124, m.cap)
        assertEquals(-4.5, m.lon!!, 1e-9); assertEquals(48.4, m.lat!!, 1e-9)
        assertEquals("SKIPPER", m.nom); assertEquals(37, m.typeNavire)
        assertArrayEquals(intArrayOf(10, 2, 2, 1), m.dimensions)
    }

    @Test fun statique_classe_b_deux_parties() {
        val a = payload("H3H`I604h85A8u<000000000000", 2)
        assertEquals(24, a.type); assertEquals(0, a.partie); assertEquals("ALBATROS", a.nom)
        val b = payload("H3H`I64TC1D0000612ijkl104224")
        assertEquals(1, b.partie); assertEquals(36, b.typeNavire); assertEquals("FAB1234", b.indicatif)
        assertArrayEquals(intArrayOf(8, 4, 2, 2), b.dimensions)
    }

    @Test fun aide_a_la_navigation() {
        val m = payload("E>jCJVDQ7bRRh1Pa24W0V2h7000OmpRP=p3t000003v@00", 4)
        assertEquals(21, m.type); assertEquals("AtoN", m.classe)
        assertEquals(992271001, m.mmsi); assertEquals(9, m.aide)
        assertEquals("BOUEE CARDINALE N", m.nom)
        assertEquals(-4.42, m.lon!!, 1e-9); assertEquals(48.5, m.lat!!, 1e-9)
        assertEquals(true, m.horsPosition)
    }

    @Test fun encodeur_rend_les_memes_charges() {
        // SatMe's encoder writes the same bits as the table above.
        assertEquals("H3H`I604h85A8u<000000000000", Aivdm.arme(EncodeurAis.statiqueA(227154200, "ALBATROS")).first)
        assertEquals("H3H`I64TC1D0000612ijkl104224", Aivdm.arme(EncodeurAis.statiqueB(227154200, 36, "FAB1234", intArrayOf(8, 4, 2, 2))).first)
    }

    @Test fun phrases_aller_retour() {
        val bits = EncodeurAis.statique(228137800, "MARIE-ANNE", "FGH2245", 30, intArrayOf(14, 6, 3, 3), "BREST", tirant = 2.4)
        val l = Aivdm.phrases(bits, 'B', 3)
        assertEquals(2, l.size)
        assertTrue(l[0].startsWith("!AIVDM,2,1,3,B,")); assertTrue(l[1].matches(Regex("!AIVDM,2,2,3,B,[^,]+,2\\*[0-9A-F]{2}")))
        assertTrue(l.all { it.length <= 82 })
        val a = Aivdm.Assembleur()
        assertNull(a.lit(l[0]))
        val (b, c) = a.lit(l[1])!!
        assertEquals('B', c)
        val m = MessageAis.lis(b)!!
        assertEquals("MARIE-ANNE", m.nom); assertEquals("FGH2245", m.indicatif); assertEquals("BREST", m.destination); assertEquals(2.4, m.tirant!!, 1e-9)
    }

    // ---------------------------------------------------------------- framing

    @Test fun crc_x25() {
        // The X.25 check of "123456789", each byte LSB first: 0x906E.
        val b = "123456789".toByteArray().flatMap { o -> (0 until 8).map { (o.toInt() shr it) and 1 == 1 } }.toBooleanArray()
        assertEquals(0x906E, HdlcAis.crc(b).inv() and 0xFFFF)
    }

    @Test fun trame_bourrage_nrzi() {
        // An MMSI of all ones makes long runs of 1s: stuffing exercised.
        val m = EncodeurAis.position(0x3FFFFFFF and 999_999_999, 48.0, -4.0, 102.3, 359.9, 359)
        val recus = ArrayList<BooleanArray>()
        val d = HdlcAis.Deframeur { recus += it }
        val t = HdlcAis.trame(m)
        // Six 1s never follow each other inside the frame (outside the flags).
        repeat(3) { t.forEach(d::niveau) }
        assertEquals(3, recus.size)
        assertArrayEquals(m, recus[0])
        // One level flipped in the middle: refused by the check.
        val f = t.copyOf(); f[120] = !f[120]
        val d2 = HdlcAis.Deframeur { recus += it }
        f.forEach(d2::niveau)
        assertEquals(3, recus.size)
    }

    @Test fun trame_niveau_inverse() {
        // NRZI does not care which level is which.
        val m = EncodeurAis.classeB(227154200, 48.4, -4.5, 5.5, 20.0, null)
        val recus = ArrayList<BooleanArray>()
        val d = HdlcAis.Deframeur { recus += it }
        HdlcAis.trame(m, niveau = true).map { !it }.forEach(d::niveau)
        assertEquals(1, recus.size); assertArrayEquals(m, recus[0])
    }

    // ---------------------------------------------------------------- the signal

    private fun signal(messages: List<Pair<BooleanArray, Double>>, bruit: Double, decalageHz: Double = 0.0, graine: Int = 3): FloatArray {
        val debit = DemodAis.DEBIT
        val out = FloatArray(2 * debit / 2)   // half a second
        var place = 2000
        val r = Random(graine)
        for ((m, canal) in messages) {
            val w = ModulateurAis.ajoute(HdlcAis.trame(m), debit, canal + decalageHz, 0.3, out, place, r.nextDouble() * 6.28)
            assertTrue(w > 0)
            place += w + 500
        }
        ModulateurAis.bruit(out, out.size / 2, bruit, r)
        return out
    }

    @Test fun gmsk_bout_a_bout() {
        val m1 = EncodeurAis.position(227006760, 48.712, -3.966, 14.5, 300.2, 300)
        val m2 = EncodeurAis.statique(636019825, "NORDIC STAR", "D5RT7", 70, intArrayOf(150, 40, 16, 16), "ROTTERDAM")
        val m3 = EncodeurAis.classeB(227154200, 48.4, -4.5, 5.5, 20.0, null)
        val m4 = EncodeurAis.aide(992271001, 9, "BOUEE CARDINALE N", 48.5, -4.42)
        val iq = signal(listOf(m1 to -25_000.0, m2 to 25_000.0, m3 to 25_000.0, m4 to -25_000.0), 0.05)
        val recus = ArrayList<Pair<BooleanArray, Char>>()
        val d = DemodAis { b, c, _ -> recus += b to c }
        d.traite(iq, iq.size / 2)
        assertEquals(4, recus.size)
        assertArrayEquals(m1, recus[0].first); assertEquals('A', recus[0].second)
        assertArrayEquals(m2, recus[1].first); assertEquals('B', recus[1].second)
        assertEquals("NORDIC STAR", MessageAis.lis(recus[1].first)!!.nom)
        assertArrayEquals(m3, recus[2].first)
        assertArrayEquals(m4, recus[3].first)
    }

    @Test fun gmsk_octets_bruit_et_decalage() {
        // As a dongle sends it: 8-bit samples, a tuning error of 1 kHz, noise.
        val msgs = (0 until 6).map { EncodeurAis.position(227000000 + it, 48.0 + it * 0.01, -4.0, 10.0 + it, 90.0, 90) to (if (it % 2 == 0) -25_000.0 else 25_000.0) }
        val iq = signal(msgs, 0.08, 1000.0, 7)
        val octets = ByteArray(iq.size) { ((iq[it] * 127.5f + 127.5f).toInt().coerceIn(0, 255)).toByte() }
        val recus = ArrayList<BooleanArray>()
        val d = DemodAis { b, _, _ -> recus += b }
        d.traite8(octets, octets.size / 2)
        assertEquals(6, recus.size)
        msgs.forEachIndexed { i, (m, _) -> assertArrayEquals(m, recus[i]) }
    }

    @Test fun bruit_seul_rien() {
        val iq = FloatArray(2 * DemodAis.DEBIT / 2)
        ModulateurAis.bruit(iq, iq.size / 2, 0.2, Random(5))
        val d = DemodAis { _, _, _ -> throw AssertionError("une trame dans le bruit") }
        d.traite(iq, iq.size / 2)
    }

    @Test fun simulateur_vers_table() {
        val sim = SimulateurAis(48.4, -4.5, 2)
        val t = TableNavires { 0L }
        t.station = 48.4 to -4.5
        val d = DemodAis { b, c, n -> MessageAis.lis(b)?.let { t.recoit(it, c, n) } }
        val buf = FloatArray(2 * DemodAis.DEBIT / 10)
        repeat(60) { val n = sim.bloc(100, buf); d.traite(buf, n) }
        val l = t.liste()
        assertTrue("navires : " + l.size, l.size >= 5)
        val brehat = l.first { it.mmsi == 227006760 }
        assertEquals("ILE DE BREHAT", brehat.nom); assertEquals(60, brehat.type)
        assertNotNull(brehat.lat)
        val o = TableNavires.json(brehat, t.station, 0L)
        assertEquals("passagers", o.getString("famille"))
        assertTrue(o.getDouble("km") in 10.0..40.0)
        assertTrue(l.any { it.classe == "AtoN" })
    }

    // ---------------------------------------------------------------- the table

    @Test fun table_fusionne() {
        var now = 1000L
        val t = TableNavires { now }
        t.station = 48.0 to -4.0
        t.recoit(MessageAis.lis(EncodeurAis.position(227006760, 48.1, -4.0, 12.0, 0.0, 0))!!, 'A')
        t.recoit(MessageAis.lis(EncodeurAis.statique(227006760, "ILE DE BREHAT", "FNXC", 60, intArrayOf(120, 30, 12, 12), "ROSCOFF"))!!, 'B')
        val n = t.liste().single()
        assertEquals("ILE DE BREHAT", n.nom); assertEquals("AB", n.canaux); assertEquals(150, n.longueur()); assertEquals(12.0, n.vitesse!!, 0.0)
        val o = TableNavires.json(n, t.station, now)
        assertEquals(11.1, o.getDouble("km"), 0.1); assertEquals(0, o.getInt("az"))
        now += 21 * 60_000L
        assertTrue(t.liste().isEmpty())
    }
}
