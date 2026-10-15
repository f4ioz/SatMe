/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.Ft8
import fr.f4ioz.satcombo.domain.Ft8Decodeur
import fr.f4ioz.satcombo.domain.Ft8Indicatifs
import fr.f4ioz.satcombo.domain.Ft8Signal
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Hashed callsigns: the hash itself, the table that puts a name on it, and
 * the messages that carry one.
 */
class Ft8IndicatifsTest {

    // ---------------------------------------------------------- the hash

    /** Values worked out by hand from the definition (base 38, × 47 055 833 459 mod 2^64, top bits). */
    @Test
    fun les_hachages_connus() {
        val attendus = mapOf(
            "F4IOZ" to Triple(804_932, 786, 196),
            "K1ABC" to Triple(2_920_267, 2851, 712),
            "W9XYZ" to Triple(3_982_604, 3889, 972),
            "PJ4/K1ABC" to Triple(1_420_834, 1387, 346),
            "F/G4ABC" to Triple(2_779_835, 2714, 678),
            "F4IOZ/P" to Triple(3_391_875, 3312, 828),
            "KH1/KH7Z" to Triple(825_805, 806, 201))
        for ((s, h) in attendus) {
            assertEquals("h22 $s", h.first, Ft8Indicatifs.hache(s, 22))
            assertEquals("h12 $s", h.second, Ft8Indicatifs.hache(s, 12))
            assertEquals("h10 $s", h.third, Ft8Indicatifs.hache(s, 10))
        }
    }

    /** The same definition with unbounded integers: no overflow slip in the 64-bit product. */
    @Test
    fun le_hachage_suit_sa_definition() {
        val alphabet = " 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ/"
        val deux64 = BigInteger.ONE.shiftLeft(64)
        for (s in listOf("F4IOZ", "ZZ9ZZZ", "3DA0/ZS6ABC", "A1A", "VK9/W1AW", "TX5S")) {
            var n = BigInteger.ZERO
            for (ch in s.padEnd(11, ' ')) n = n * BigInteger.valueOf(38) + BigInteger.valueOf(alphabet.indexOf(ch).toLong())
            val p = (n * BigInteger.valueOf(47_055_833_459L)).mod(deux64)
            for (bits in intArrayOf(10, 12, 22)) {
                assertEquals("$s $bits", p.shiftRight(64 - bits).toInt(), Ft8Indicatifs.hache(s, bits))
            }
        }
    }

    @Test
    fun les_hachages_courts_sont_le_haut_du_long() {
        val h22 = Ft8Indicatifs.hache("PJ4/K1ABC", 22)!!
        assertEquals(h22 ushr 10, Ft8Indicatifs.hache("PJ4/K1ABC", 12))
        assertEquals(h22 ushr 12, Ft8Indicatifs.hache("PJ4/K1ABC", 10))
        // Case and brackets do not count.
        assertEquals(h22, Ft8Indicatifs.hache("<pj4/k1abc>", 22))
    }

    // ---------------------------------------------------------- the table

    @Test
    fun la_table_retrouve_ce_qu_elle_a_entendu() {
        val t = Ft8Indicatifs()
        assertNull(t.trouve(1_420_834, 22))
        t.retiens("PJ4/K1ABC")
        assertEquals("PJ4/K1ABC", t.trouve(1_420_834, 22))
        assertEquals("PJ4/K1ABC", t.trouve(1387, 12))
        assertEquals("PJ4/K1ABC", t.trouve(346, 10))
    }

    @Test
    fun la_table_garde_les_plus_recents() {
        val t = Ft8Indicatifs(capacite = 3)
        for (s in listOf("F4AAA", "F4BBB", "F4CCC")) t.retiens(s)
        t.retiens("F4AAA")          // heard again: back to the newest end
        t.retiens("F4DDD")          // F4BBB, the oldest, goes
        assertEquals(listOf("F4DDD", "F4AAA", "F4CCC"), t.indicatifs())
        assertNull(t.trouve(Ft8Indicatifs.hache("F4BBB", 22)!!, 22))
    }

    @Test
    fun ce_qui_n_est_pas_un_indicatif_n_entre_pas() {
        val t = Ft8Indicatifs()
        for (s in listOf("", "CQ", "DX", "73", "RR73", "AB CD", "/F4IOZ", "F4IOZ/", "F4//IOZ", "ABCDEFGHIJ12")) t.retiens(s)
        assertEquals(emptyList<String>(), t.indicatifs())
    }

    @Test
    fun notre_indicatif_est_toujours_connu() {
        val t = Ft8Indicatifs()
        t.moi = { "f4ioz" }
        assertEquals("F4IOZ", t.trouve(804_932, 22))
        assertEquals("F4IOZ", t.trouve(786, 12))
        assertEquals("F4IOZ", t.trouve(196, 10))
        t.moi = { "" }
        assertNull(t.trouve(786, 12))
    }

    @Test
    fun le_carnet_amorce_la_table() {
        val t = Ft8Indicatifs()
        t.amorce(listOf("F/G4ABC", "K1ABC"))
        assertEquals("F/G4ABC", t.trouve(2_779_835, 22))
        assertEquals("K1ABC", t.trouve(712, 10))
        // The log is not saved with what was heard.
        assertEquals(emptyList<String>(), t.indicatifs())
    }

    @Test
    fun la_table_se_garde_d_un_demarrage_a_l_autre() {
        val st = StockageMemoire()
        val a = Ft8Indicatifs()
        a.branche(st)
        a.retiens("F4IOZ/P"); a.retiens("K1ABC"); a.retiens("PJ4/K1ABC")
        a.sauve()
        val b = Ft8Indicatifs()
        b.branche(st)
        assertEquals(listOf("PJ4/K1ABC", "K1ABC", "F4IOZ/P"), b.indicatifs())
        assertEquals("F4IOZ/P", b.trouve(3312, 12))
    }

    // ---------------------------------------------------------- messages

    private fun typeUn(c1: Long, c2: Long, g15: Int, i3: Int = 1, r1: Boolean = false, r2: Boolean = false): BooleanArray {
        val m = BooleanArray(Ft8.BITS_MESSAGE)
        Ft8.ecritEntier(m, 0, 28, c1); m[28] = r1
        Ft8.ecritEntier(m, 29, 28, c2); m[57] = r2
        Ft8.ecritEntier(m, 59, 15, g15.toLong())
        Ft8.ecritEntier(m, 74, 3, i3.toLong())
        return m
    }

    private fun typeQuatre(h12: Int, enClair: String, hacheEnSecond: Boolean, r2: Int, cq: Boolean): BooleanArray {
        val m = BooleanArray(Ft8.BITS_MESSAGE)
        Ft8.ecritEntier(m, 0, 12, h12.toLong())
        Ft8.ecritEntier(m, 12, 58, Ft8.indicatifVers58(enClair)!!)
        m[70] = hacheEnSecond
        Ft8.ecritEntier(m, 71, 2, r2.toLong())
        m[73] = cq
        Ft8.ecritEntier(m, 74, 3, 4L)
        return m
    }

    @Test
    fun un_hachage_inconnu_reste_entre_chevrons() {
        val t = Ft8Indicatifs()
        val m = typeUn(Ft8.indicatifVers28("W9XYZ")!!, Ft8.hacheVers28("PJ4/K1ABC")!!, 32_400 + 25)
        val d = Ft8.deplie(m, t)!!
        assertEquals("W9XYZ <...> -10", d.brut)
        assertNull(d.appelant)
        assertEquals("W9XYZ", d.appele)
    }

    /** Our own encoder, then our own decoder: the hash heard in clear, then resolved. */
    @Test
    fun un_hachage_entendu_en_clair_se_resout() {
        val t = Ft8Indicatifs()
        // "CQ PJ4/K1ABC": type 4 with the compound callsign in clear.
        val cq = Ft8.deplie(typeQuatre(0, "PJ4/K1ABC", false, 0, cq = true), t)!!
        assertEquals("CQ PJ4/K1ABC", cq.brut)
        assertEquals("PJ4/K1ABC", cq.appelant)
        // W9XYZ answers: "<PJ4/K1ABC> W9XYZ -10" in type 1, the compound one hashed on 22 bits.
        val m = typeUn(Ft8.hacheVers28("PJ4/K1ABC")!!, Ft8.indicatifVers28("W9XYZ")!!, 32_400 + 25)
        val d = Ft8.deplie(m, t)!!
        assertEquals("<PJ4/K1ABC> W9XYZ -10", d.brut)
        assertEquals("PJ4/K1ABC", d.appele)
        assertEquals("W9XYZ", d.appelant)
        // PJ4/K1ABC closes: "<W9XYZ> PJ4/K1ABC RR73", W9XYZ hashed on 12 bits.
        val fin = Ft8.deplie(typeQuatre(Ft8Indicatifs.hache("W9XYZ", 12)!!, "PJ4/K1ABC", false, 2, cq = false), t)!!
        assertEquals("<W9XYZ> PJ4/K1ABC RR73", fin.brut)
        assertEquals("W9XYZ", fin.appele)
        assertEquals("PJ4/K1ABC", fin.appelant)
        // The other order: "PJ4/K1ABC <W9XYZ> RRR".
        val autre = Ft8.deplie(typeQuatre(Ft8Indicatifs.hache("W9XYZ", 12)!!, "PJ4/K1ABC", true, 1, cq = false), t)!!
        assertEquals("PJ4/K1ABC <W9XYZ> RRR", autre.brut)
        assertEquals("W9XYZ", autre.appelant)
    }

    @Test
    fun un_message_pour_nous_en_hachage_se_lit() {
        val t = Ft8Indicatifs()
        t.moi = { "F4IOZ" }
        val d = Ft8.deplie(typeQuatre(Ft8Indicatifs.hache("F4IOZ", 12)!!, "F/G4ABC", false, 0, cq = false), t)!!
        assertEquals("<F4IOZ> F/G4ABC", d.brut)
        assertEquals("F4IOZ", d.appele)
        assertEquals("F/G4ABC", d.appelant)
    }

    @Test
    fun les_suffixes_r_et_p_sont_retenus() {
        val t = Ft8Indicatifs()
        val r = Ft8.deplie(typeUn(2L, Ft8.indicatifVers28("F4IOZ")!!, 0, i3 = 1, r2 = true), t)!!
        assertEquals("CQ F4IOZ/R AA00", r.brut)
        assertEquals("F4IOZ", r.appelant)
        val p = Ft8.deplie(typeUn(2L, Ft8.indicatifVers28("G4ABC")!!, 0, i3 = 2, r2 = true), t)!!
        assertEquals("CQ G4ABC/P AA00", p.brut)
        assertEquals("G4ABC/P", t.trouve(Ft8Indicatifs.hache("G4ABC/P", 22)!!, 22))
        assertEquals("F4IOZ/R", t.trouve(Ft8Indicatifs.hache("F4IOZ/R", 22)!!, 22))
    }

    @Test
    fun les_appels_diriges_se_lisent() {
        val t = Ft8Indicatifs()
        val dx = 1_003L + ((0 * 27 + 0) * 27 + 4) * 27 + 24      // "  DX"
        assertEquals("CQ DX F4IOZ JN18",
            Ft8.deplie(typeUn(dx, Ft8.indicatifVers28("F4IOZ")!!, 9 * 1800 + 13 * 100 + 18), t)!!.brut)
        assertEquals("CQ 145 F4IOZ JN18",
            Ft8.deplie(typeUn(3L + 145, Ft8.indicatifVers28("F4IOZ")!!, 9 * 1800 + 13 * 100 + 18), t)!!.brut)
    }

    @Test
    fun le_double_message_d_une_expedition() {
        val t = Ft8Indicatifs()
        t.retiens("KH1/KH7Z")
        val m = BooleanArray(Ft8.BITS_MESSAGE)
        Ft8.ecritEntier(m, 0, 28, Ft8.indicatifVers28("K1ABC")!!)
        Ft8.ecritEntier(m, 28, 28, Ft8.indicatifVers28("W9XYZ")!!)
        Ft8.ecritEntier(m, 56, 10, Ft8Indicatifs.hache("KH1/KH7Z", 10)!!.toLong())
        Ft8.ecritEntier(m, 66, 5, 11L)                       // 2 × 11 − 30 = −8
        Ft8.ecritEntier(m, 71, 3, 1L)
        Ft8.ecritEntier(m, 74, 3, 0L)
        val d = Ft8.deplie(m, t)!!
        assertEquals("K1ABC RR73; W9XYZ <KH1/KH7Z> -8", d.brut)
        assertEquals("KH1/KH7Z", d.appelant)
        assertEquals("W9XYZ", d.appele)
        assertEquals(-8, d.rapportDb)
    }

    @Test
    fun un_indicatif_sur_58_bits_fait_l_aller_retour() {
        for (s in listOf("PJ4/K1ABC", "F/G4ABC", "3DA0/ZS6ABC", "F4IOZ/P"))
            assertEquals(s, Ft8.indicatifDepuis58(Ft8.indicatifVers58(s)!!))
        // Past 38^11, or no callsign: nothing.
        assertNull(Ft8.indicatifDepuis58((1L shl 58) - 1))
        assertNull(Ft8.indicatifDepuis58(Ft8.indicatifVers58("CQ")!!))
    }

    /** The whole chain: a type 4 message to us, through the air and the decoder. */
    @Test
    fun le_decodeur_resout_notre_indicatif() {
        val avant = Ft8Indicatifs.commun.moi
        Ft8Indicatifs.commun.moi = { "F4IOZ" }
        try {
            val m = typeQuatre(Ft8Indicatifs.hache("F4IOZ", 12)!!, "PJ4/K1ABC", false, 0, cq = false)
            val bits = BooleanArray(Ft8.BITS)
            Ft8.avecControle(m).copyInto(bits, 0, 0, Ft8.BITS_UTILES)
            val r = java.util.Random(7)
            // Without the parity bits the decoder falls back on hard decisions; the CRC still guards.
            for (i in Ft8.BITS_UTILES until Ft8.BITS) bits[i] = r.nextBoolean()
            val audio = Ft8Signal.synthetise(Ft8.bitsVersSymboles(bits), Ft8Signal.FT8, 1200.0, 0.5)
            val l = Ft8Decodeur.decode(audio, Ft8Signal.FT8.cadenceHz, Ft8Signal.FT8)
            val d = l.firstOrNull { it.message.brut.endsWith("PJ4/K1ABC") }
            assertNotNull("décodé : " + l.map { it.message.brut }, d)
            assertEquals("<F4IOZ> PJ4/K1ABC", d!!.message.brut)
            assertEquals("F4IOZ", d.message.appele)
        } finally {
            Ft8Indicatifs.commun.moi = avant
        }
    }
}
