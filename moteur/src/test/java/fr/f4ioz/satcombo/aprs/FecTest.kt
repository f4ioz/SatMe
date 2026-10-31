/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.aprs

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Reed-Solomon, FX.25 and IL2P reception, from frames made by the test encoders. */
class FecTest {

    private fun hex(s: String) = s.split(' ', '\n').filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()

    private val trame = Trame(Adresse("APRS"), Adresse("F4IOZ", 9), listOf(Adresse("WIDE1", 1)),
        "!4852.00N/00220.00E-SatMe, essai FX.25 et IL2P".toByteArray())

    // ------------------------------------------------------------------ Reed-Solomon

    @Test
    fun reed_solomon_corrige_jusqu_a_la_moitie_des_octets_de_controle() {
        val rnd = Random(7)
        for ((racines, fcr) in listOf(2 to 0, 16 to 0, 16 to 1, 32 to 1, 64 to 1)) {
            val rs = ReedSolomon(racines, fcr)
            for (essai in 0 until 40) {
                val n = rnd.nextInt(racines + 1, 256)
                val donnees = ByteArray(n - racines).also { rnd.nextBytes(it) }
                val bloc = donnees + rs.parite(donnees)
                assertEquals(0, rs.corrige(bloc.copyOf()))
                val t = rnd.nextInt(0, racines / 2 + 1)
                val abime = bloc.copyOf()
                val positions = (0 until n).shuffled(rnd).take(t)
                for (p in positions) abime[p] = (abime[p].toInt() xor rnd.nextInt(1, 256)).toByte()
                assertEquals("RS($n, ${n - racines}) $t erreurs", t, rs.corrige(abime))
                assertArrayEquals(bloc, abime)
            }
        }
    }

    @Test
    fun reed_solomon_au_dela_ne_touche_pas_au_bloc_ou_le_rend_coherent() {
        val rnd = Random(11)
        val rs = ReedSolomon(16, 1)
        var refus = 0
        for (essai in 0 until 200) {
            val donnees = ByteArray(64).also { rnd.nextBytes(it) }
            val bloc = donnees + rs.parite(donnees)
            val abime = bloc.copyOf()
            for (p in (0 until bloc.size).shuffled(rnd).take(12)) abime[p] = (abime[p].toInt() xor rnd.nextInt(1, 256)).toByte()
            val avant = abime.copyOf()
            val r = rs.corrige(abime)
            if (r < 0) { refus++; assertArrayEquals(avant, abime) }
            else assertEquals(0, rs.corrige(abime.copyOf()))  // a (wrong) codeword, never half-mended
        }
        assertTrue("$refus/200 refusés", refus > 190)
    }

    // ------------------------------------------------------------------ IL2P: the spec's own examples

    private val exemples = listOf(
        hex("96 82 64 88 8A AE E4 96 96 68 90 8A 94 6F 81") to
            hex("26 57 4D 57 F1 D2 A8 F0 6A F2 7B AD 23 BD C0 7F 00 1D 2B"),
        hex("86 A2 40 40 40 40 60 96 96 68 90 8A 94 FF 03 F0") to
            hex("6A EA 9C C2 01 11 FC 14 1F DA 6E F2 53 91 BD 47 6C 54 54"),
        hex("96 82 64 88 8A AE E4 96 96 68 90 8A 94 65 B8 CF 30 31 32 33 34 35 36 37 38") to
            hex("26 13 6D 02 8C FE FB E8 AA 94 2D 6A 34 43 35 3C 69 9F 0C 75 5A 38 A1 7F A5 DA D8 F6 EA " +
                "57 37 3D B1 2A B0 DE 44 A8 20 D0 1D 5A 2B 38"),
    )

    @Test
    fun il2p_encodeur_de_test_donne_les_exemples_de_la_specification() {
        for ((ax25, il2p) in exemples) assertArrayEquals(il2p, EncodeursFec.il2p(ax25))
    }

    private fun recoitIl2p(tons: BooleanArray, inverse: Boolean = false): List<Pair<ByteArray, Il2pDecodeur.Info>> {
        val l = ArrayList<Pair<ByteArray, Il2pDecodeur.Info>>()
        val d = Il2pDecodeur(inverse) { o, i -> l += o to i }
        for (b in tons) d.bit(b)
        return l
    }

    @Test
    fun il2p_exemples_de_la_specification_decodes() {
        for ((ax25, il2p) in exemples) {
            val r = recoitIl2p(EncodeursFec.tonsIl2p(il2p))
            assertEquals(1, r.size)
            assertArrayEquals(Ax25.avecFcs(ax25), r[0].first)
            assertTrue(r[0].second.crc)
        }
    }

    @Test
    fun il2p_corrige_les_octets_abimes_et_le_signal_inverse() {
        val ax25 = Ax25.encodeSansFcs(trame)  // a relay: type 0, the whole frame in the payload
        val sans = Ax25.encodeSansFcs(Trame(trame.destination, trame.source, emptyList(), trame.info))  // type 1
        for (f in listOf(ax25, sans)) for (crc in listOf(true, false)) {
            val p = EncodeursFec.il2p(f, crc)
            p[1] = (p[1].toInt() xor 0x40).toByte()          // the header: 1 byte (its code mends one)
            for (k in listOf(20, 25, 31, 40, 41, 44, 50)) p[k] = (p[k].toInt() xor 0x5A).toByte()  // payload
            val r = recoitIl2p(EncodeursFec.tonsIl2p(p))
            assertEquals(1, r.size)
            assertArrayEquals(Ax25.avecFcs(f), r[0].first)
            assertEquals(8, r[0].second.corriges)
            assertEquals(crc, r[0].second.crc)
            val inv = recoitIl2p(EncodeursFec.tonsIl2p(p).map { !it }.toBooleanArray(), inverse = true)
            assertArrayEquals(Ax25.avecFcs(f), inv.single().first)
        }
    }

    @Test
    fun il2p_longue_charge_en_plusieurs_blocs_et_paquets_a_la_suite() {
        val longue = Trame(Adresse("CQ"), Adresse("F4IOZ"), emptyList(), ByteArray(600) { (it % 90 + 32).toByte() })
        val a = EncodeursFec.il2p(Ax25.encodeSansFcs(longue), crc = false)
        val b = EncodeursFec.il2p(Ax25.encodeSansFcs(trame), crc = false)
        // Back to back: the second packet's sync word right after the first.
        val tons = EncodeursFec.tonsIl2p(a).toList().dropLast(32) + EncodeursFec.tonsIl2p(b, preambule = 0).toList()
        val r = recoitIl2p(tons.toBooleanArray())
        assertEquals(listOf(longue.tnc2(), trame.tnc2()), r.map { Ax25.decode(it.first)!!.tnc2() })
    }

    @Test
    fun il2p_crc_faux_rejete() {
        val p = EncodeursFec.il2p(Ax25.encodeSansFcs(trame))
        p[p.size - 1] = Il2p.HAMMING_CODE[(Il2p.HAMMING_CODE.indexOf(p[p.size - 1].toInt()) + 1) and 0xF].toByte()
        assertTrue(recoitIl2p(EncodeursFec.tonsIl2p(p)).isEmpty())
    }

    // ------------------------------------------------------------------ FX.25

    private fun recoitFx25(tons: BooleanArray): List<Pair<ByteArray, Int>> {
        val l = ArrayList<Pair<ByteArray, Int>>()
        val d = Fx25Decodeur { o, c -> l += o to c }
        var dernier = true
        for (t in tons) { d.bit(t == dernier); dernier = t }
        return l
    }

    @Test
    fun fx25_tous_les_codes_et_correction() {
        val o = Ax25.encode(trame)
        for (code in Fx25.CODES) {
            if (EncodeursFec.blocFx25(o, code) == null) continue
            assertArrayEquals(o, recoitFx25(EncodeursFec.tonsFx25(o, code)).single().first)
            val t = code.controle / 2
            val r = recoitFx25(EncodeursFec.tonsFx25(o, code) { b ->
                for (k in 0 until t) b[k * (code.n / t)] = (b[k * (code.n / t)].toInt() xor 0xA5).toByte()
            })
            assertArrayEquals("code ${code.numero}", o, r.single().first)
            assertEquals(t, r.single().second)
        }
    }

    @Test
    fun fx25_et_il2p_a_travers_le_demodulateur_afsk() {
        val f = 22050
        val o = Ax25.encode(trame)
        val code = Fx25.CODES.first { it.numero == 0x02 }  // RS(144, 128): fits this frame
        val recues = ArrayList<Reception>()
        val d = AfskDemodulateur(f) { }
        d.surReception = { recues += it }
        // FX.25 with 6 bytes spoiled: plain AX.25 cannot, the code can.
        d.traite(Afsk.moduleTons(EncodeursFec.tonsFx25(o, code) { b -> for (k in 3 until 9) b[k] = (b[k].toInt() xor 0xFF).toByte() }, f))
        d.traite(ShortArray(f / 2))
        d.traite(Afsk.moduleTons(EncodeursFec.tonsIl2p(EncodeursFec.il2p(Ax25.encodeSansFcs(trame))), f))
        d.traite(ShortArray(f / 2))
        assertEquals(listOf(OrigineTrame.FX25, OrigineTrame.IL2P), recues.map { it.origine })
        assertTrue(recues.all { it.trame == trame })
        assertEquals("FX.25 (6 octets corrigés)", recues[0].marque)
        assertEquals(1, d.statistiques.fx25)
        assertEquals(1, d.statistiques.il2p)
    }

    @Test
    fun fx25_trame_propre_comptee_comme_ax25() {
        // Read by the ordinary decoder first (its flag closes before the check bytes), counted once.
        val f = 22050
        val d = AfskDemodulateur(f) { }
        val l = ArrayList<Reception>()
        d.surReception = { l += it }
        d.traite(Afsk.moduleTons(EncodeursFec.tonsFx25(Ax25.encode(trame), Fx25.CODES[0]), f))
        d.traite(ShortArray(f / 2))
        assertEquals(1, l.size)
        assertEquals(1, d.statistiques.total)
    }

    @Test
    fun il2p_bruit_ne_donne_rien() {
        val rnd = Random(3)
        assertNull(recoitIl2p(BooleanArray(2_000_000) { rnd.nextBoolean() }).firstOrNull())
    }
}
