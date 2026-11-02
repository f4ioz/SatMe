/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.telemesure

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * SO-127's GENESIS framing and the USB-audio FSK demodulator — on
 * **synthetic** signals made from the published description (the reference
 * values of AMSAT-EA's document for the CRC and the scrambler).
 */
class GenesisTest {

    private fun hex(s: String) = s.split(' ').map { it.toInt(16).toByte() }.toByteArray()

    @Test fun crc_du_document() {
        // CRC-16/CCITT-FALSE check value, and the document's example.
        assertEquals(0x29B1, Genesis.crc16("123456789".toByteArray()))
        assertEquals(0x7D58, Genesis.crc16("EASAT-2".toByteArray()))
    }

    @Test fun embrouilleur_du_document() {
        val clair = "GENESIS-Genesis".toByteArray()
        val attendu = hex("C7 43 4C 27 4B 17 13 D7 6B 05 AA D1 89 97 47")
        assertArrayEquals(attendu, Genesis.embrouille(clair))
        assertArrayEquals(clair, Genesis.desembrouille(attendu))
    }

    @Test fun une_trame_relue_dans_les_deux_polarites() {
        val donnees = ByteArray(28) { (it * 7 + 3).toByte() }
        val o = Genesis.code(1, Genesis.ADRESSE_HADES_SA, donnees)
        assertEquals(31, o[2].toInt())
        for (inverse in listOf(false, true)) {
            val l = ArrayList<Genesis.Trame>()
            val d = Genesis.Deframeur({ l += it })
            for (b in IntArray(13) { it and 1 } + Genesis.bits(o) + IntArray(40)) d.bit(if (inverse) b xor 1 else b)
            assertEquals(1, l.size)
            assertEquals(1, l[0].type); assertEquals("HADES-SA", l[0].emetteur)
            assertArrayEquals(donnees, l[0].donnees)
        }
    }

    @Test fun paquet_ssdv_vu_par_le_deframeur_et_la_recherche() {
        val p = Ssdv.codeImage(SignalPaquets.mire(), 320, 240, "Q0SAT", 7).first()
        // As HADES-SA sends it: its callsign bytes are « BF 35 FB A3 ».
        val q = Ssdv.recachete(p, Genesis.INDICATIF_SSDV)
        assertEquals(0xBF.toByte(), q[2]); assertEquals(0xA3.toByte(), q[5])
        val bits = Genesis.bits(Genesis.codeSsdv(q), preambule = 0) + IntArray(16)
        val parDeframeur = ArrayList<ByteArray>()
        val d = Genesis.Deframeur({}, { parDeframeur += it })
        val parRecherche = ArrayList<Ssdv.Paquet>()
        val c = ChercheurSsdv { pq, _ -> parRecherche += pq }
        for (b in bits) { d.bit(b); c.bit(b) }
        assertEquals(1, parDeframeur.size)
        assertArrayEquals(q, parDeframeur[0])
        assertEquals(1, parRecherche.size)
        assertEquals(7, parRecherche[0].image)
        assertEquals(0, parRecherche[0].corriges)
        assertArrayEquals(q, Ssdv.lis(parDeframeur[0])!!.brut)
    }

    @Test fun recherche_ssdv_lsb_et_inverse() {
        val p = Ssdv.codeImage(SignalPaquets.mire(), 320, 240, "Q0SAT", 3)[2]
        val lsb = IntArray(p.size * 8) { (p[it / 8].toInt() ushr (it % 8)) and 1 }
        val l = ArrayList<String>()
        val c = ChercheurSsdv { _, v -> l += v }
        for (b in IntArray(5) + lsb + IntArray(9)) c.bit(b xor 1)
        assertEquals(listOf("LSB inversé"), l)
    }

    /** A picture's SSDV packets, GENESIS-framed, as audio at [fs]. */
    private fun audio(paquets: List<ByteArray>, fs: Int, debit: Double, ecart: Double, centre: Double, ebN0: Double, derive: Double,
                      graine: Long = 5L): ShortArray {
        val tlm = Genesis.code(2, 3, ByteArray(15) { it.toByte() })
        val o = paquets.flatMap { p -> (Genesis.codeSsdv(Ssdv.recachete(p, Genesis.INDICATIF_SSDV)) + ByteArray(4) { 0xAA.toByte() }).toList() }.toByteArray()
        val bits = Genesis.bits(tlm) + Genesis.bits(o, preambule = 0) + IntArray(64) { it and 1 }
        return SignalFskAudio.audio(bits, fs, debit, ecart, centre, ebN0, derive, graine = graine)
    }

    private fun recoit(pcm: ShortArray, fs: Int, p: RecepteurGenesis.Profil): Pair<RecepteurGenesis, AssemblageSsdv> {
        val a = AssemblageSsdv(null) { 1_000L }
        val r = RecepteurGenesis(null, a) { 1_000L }
        r.profil = p
        r.contexte = { RecepteurGenesis.Contexte("HADES-SA", 68446) }
        var k = 0
        while (k < pcm.size) { val n = minOf(4096, pcm.size - k); r.traite(pcm.copyOfRange(k, k + n), n, fs); k += n }
        return r to a
    }

    @Test fun image_entiere_a_800_bits_par_seconde_bruit_et_derive() {
        val paquets = Ssdv.codeImage(SignalPaquets.mire(), 320, 240, "Q0SAT", 12)
        val fs = 48_000
        // The tones at 900 / 2500 Hz, drifting 4 Hz/s (≈ 1 min of a pass: 240 Hz), Eb/N0 12 dB.
        val pcm = audio(paquets, fs, 800.0, 1_600.0, 1_700.0, 12.0, 4.0)
        val (r, a) = recoit(pcm, fs, RecepteurGenesis.Profil.G800)
        val ph = a.photos().single()
        assertEquals(12, ph.numero)
        assertEquals("${ph.recus}/${paquets.size}", paquets.size, ph.recus)
        assertTrue(ph.complete)
        assertTrue(ph.jpeg()!!.size > 1000)
        assertEquals("HADES-SA", ph.satellite)
        assertEquals(1, r.etat.trames)
    }

    @Test fun a_200_bits_par_seconde_44100() {
        val paquets = Ssdv.codeImage(SignalPaquets.mire(), 320, 240, "Q0SAT", 4).take(6)
        val fs = 44_100
        val pcm = audio(paquets, fs, 200.0, 1_125.0, 1_500.0, 11.0, -2.0)
        val (_, a) = recoit(pcm, fs, RecepteurGenesis.Profil.G200)
        assertEquals(6, a.photos().single().recus)
    }

    @Test fun bruit_seul_rien() {
        val fs = 48_000
        val r = java.util.Random(3)
        val pcm = ShortArray(fs * 20) { (r.nextGaussian() * 3000).toInt().toShort() }
        val (rg, a) = recoit(pcm, fs, RecepteurGenesis.Profil.G800)
        assertTrue(a.photos().isEmpty())
        assertEquals(0, rg.etat.trames)
    }

    @Test fun profil_selon_l_emetteur() {
        // AMSAT-EA's satellites alternate 200 / 800 bit/s: both are listened to, whatever SatNOGS DB says this month.
        assertEquals(RecepteurGenesis.Profil.AUTO, RecepteurGenesis.Profil.pour("GENESIS FSK", 800.0))
        assertEquals(RecepteurGenesis.Profil.AUTO, RecepteurGenesis.Profil.pour("GENESIS FSK", 200.0))
        assertEquals(RecepteurGenesis.Profil.AUTO, RecepteurGenesis.Profil.pour("SSDV", 800.0))
        assertEquals(RecepteurGenesis.Profil.AUTO, RecepteurGenesis.Profil.pour("FSK", 800.0))
        assertEquals(listOf(RecepteurGenesis.Profil.G200, RecepteurGenesis.Profil.G800), RecepteurGenesis.Profil.AUTO.voies)
        assertEquals(RecepteurGenesis.Profil.AUTO, RecepteurGenesis.Profil.de("auto"))
        assertEquals(null, RecepteurGenesis.Profil.pour("FSK", 9600.0))
        assertEquals(null, RecepteurGenesis.Profil.pour("AFSK", 1200.0))
    }

    @Test fun capture_relue() {
        val paquets = Ssdv.codeImage(SignalPaquets.mire(), 320, 240, "Q0SAT", 9).take(3).map { Ssdv.recachete(it, Genesis.INDICATIF_SSDV) }
        val bits = Genesis.bits(paquets.flatMap { Genesis.codeSsdv(it).toList() }.toByteArray())
        val o = ByteArray((bits.size + 7) / 8) { i -> var x = 0; for (k in 0 until 8) x = (x shl 1) or bits.getOrElse(i * 8 + k) { 0 }; x.toByte() }
        val l = ArrayList<Ssdv.Paquet>()
        RecepteurGenesis.relit(o, {}, { l += it })
        assertEquals(3, l.size)
    }

    @Test fun paquet_court_dans_une_trame() {
        // fsphil's ssdv « -l 128 »: what ssdv2sat sends through an FM repeater, one packet a frame.
        val p = Ssdv.codeImage(SignalPaquets.mire(), 320, 240, "YB0X", 2, fec = false)[0]
        val court = p.copyOf(128)
        val n = 128 - Ssdv.ENTETE - 4
        val crc = java.util.zip.CRC32().apply { update(court, 1, Ssdv.ENTETE + n - 1) }.value
        for (k in 0 until 4) court[Ssdv.ENTETE + n + k] = (crc ushr (24 - 8 * k)).toByte()
        val l = Ssdv.cherche(byteArrayOf(1, 2) + court)
        assertEquals(1, l.size)
        assertEquals("YB0X", l[0].indicatif)
        assertEquals(n, l[0].donnees.size)
        court[40] = (court[40] + 1).toByte()
        assertTrue(Ssdv.cherche(court).isEmpty())
    }

    @Test fun seuil_vers_10_db() {
        // Eb/N0 11 dB: every packet (Reed-Solomon repairing a few bytes each); noncoherent FSK with RS(255,223) gives up near 9 dB.
        val paquets = Ssdv.codeImage(SignalPaquets.mire(), 320, 240, "Q0SAT", 30).take(8)
        val pcm = audio(paquets, 48_000, 800.0, 1_600.0, 1_500.0, 11.0, 0.0, graine = 7)
        val (_, a) = recoit(pcm, 48_000, RecepteurGenesis.Profil.G800)
        assertEquals(8, a.photos().single().recus)
    }

    // ---------------------------------------------------------------- both rates at once, the low tone

    @Test fun auto_les_deux_debits_dans_le_meme_son() {
        val fs = 24_000
        val a200 = Ssdv.codeImage(SignalPaquets.mire(), 320, 240, "Q0SAT", 21).take(3)
        val a800 = Ssdv.codeImage(SignalPaquets.mire(), 320, 240, "Q0SAT", 22).take(5)
        val pcm = audio(a200, fs, 200.0, 1_125.0, 1_400.0, 13.0, 1.0, graine = 11) + audio(a800, fs, 800.0, 1_600.0, 1_700.0, 13.0, -1.0, graine = 12)
        val (r, a) = recoit(pcm, fs, RecepteurGenesis.Profil.AUTO)
        assertEquals(3, a.photos().first { it.numero == 21 }.recus)
        assertEquals(5, a.photos().first { it.numero == 22 }.recus)
        // The same telemetry frame in both parts, less than a minute apart: kept once (the two chains, the two polarities too).
        assertEquals(1, r.etat.trames)
        assertEquals("800", r.etat.recoit)
        assertEquals("auto", r.etat.profil)
    }

    @Test fun ton_bas_a_40_hz() {
        // HADES-SA heard with the receiver tuned high: its lower tone near 40 Hz, the upper one at 1165 Hz.
        val fs = 24_000
        val paquets = Ssdv.codeImage(SignalPaquets.mire(), 320, 240, "Q0SAT", 5).take(4)
        // The lower tone too low for its matched filter: the upper one alone (on-off), 3 dB lost — 16 dB here, HADES-SA was heard at 25.
        val pcm = audio(paquets, fs, 200.0, 1_125.0, 40.0 + 562.5, 16.0, 0.0, graine = 9)
        val (r, a) = recoit(pcm, fs, RecepteurGenesis.Profil.AUTO)
        assertEquals(4, a.photos().single().recus)
        assertEquals("200", r.etat.recoit)
    }

    @Test fun continu_et_ronflement_seuls_rien() {
        // An offset and 50 Hz hum over noise: no pair of tones taken, nothing decoded.
        val fs = 24_000
        val rnd = java.util.Random(4)
        val pcm = ShortArray(fs * 15) { i -> (2500 + 2000 * kotlin.math.sin(2 * Math.PI * 50 * i / fs) + rnd.nextGaussian() * 2000).toInt().toShort() }
        val (r, a) = recoit(pcm, fs, RecepteurGenesis.Profil.AUTO)
        assertTrue(a.photos().isEmpty())
        assertEquals(0, r.etat.trames)
        assertEquals(0, r.etat.voix)
    }

    // ---------------------------------------------------------------- Codec2 voice (type 11)

    private fun c2Essai(k: Int) = ByteArray(VoixGenesis.TRAMES_PAR_PAQUET * VoixGenesis.OCTETS_C2) { i ->
        // Each 4-byte frame: 28 bits of something, the 4 last bits zero (the .c2 layout).
        if (i % 4 == 3) ((k * 31 + i * 7) and 0xF0).toByte() else (k * 13 + i * 5 + 1).toByte()
    }

    @Test fun voix_cle_et_bourrage() {
        for (k in 0 until 5) {
            val c2 = c2Essai(k)
            assertArrayEquals(c2, VoixGenesis.c2(VoixGenesis.envoye(c2)))
        }
    }

    @Test fun voix_vue_par_le_deframeur_sans_crc() {
        val l = ArrayList<Triple<Int, Int, ByteArray>>()
        val d = Genesis.Deframeur({ fail("pas une trame de télémesure") }, surVoix = { a, n, o -> l += Triple(a, n, o) })
        val o1 = VoixGenesis.envoye(c2Essai(1))
        for (b in Genesis.bits(Genesis.codeVoix(Genesis.ADRESSE_HADES_SA, 7, o1)) + IntArray(16)) d.bit(b)
        // An unknown sender (address 0): not taken, there is no CRC to vouch for it.
        for (b in Genesis.bits(Genesis.codeVoix(0, 8, o1)) + IntArray(16)) d.bit(b xor 1)
        assertEquals(1, l.size)
        assertEquals(3, l[0].first); assertEquals(7, l[0].second)
        assertArrayEquals(o1, l[0].third)
    }

    @Test fun voix_de_hades_sa_reelle() {
        // Four voice frames as SatMe's demodulator gave them off HADES-SA (2 Nov 2026, 200 bit/s): numbers 2 to 5.
        val bits = GenesisTest::class.java.getResourceAsStream("/genesis/hades-sa-voix-4-trames.bits")!!.use { it.readBytes() }
        val l = ArrayList<Pair<Int, ByteArray>>()
        RecepteurGenesis.relit(bits, {}, {}) { n, o -> l += n to o }
        assertEquals(listOf(2, 3, 4, 5), l.map { it.first })
        // The first 700C frame of packet 2: 28 bits CEFF804, then 4 zero bits.
        assertArrayEquals(hex("CE FF 80 40"), VoixGenesis.c2(l[0].second).copyOf(4))
    }

    @Test fun message_de_voix_rassemble_et_ecrit() {
        val dir = java.nio.file.Files.createTempDirectory("voix").toFile()
        try {
            val m = AssemblageVoix(dir)
            val t0 = 1_793_588_000_000L
            for (n in listOf(0, 1, 3, 1)) m.ajoute(n, VoixGenesis.envoye(c2Essai(n)), t0 + n * 1_600L, "HADES-SA (SpinnyONE)")
            // A number far ahead (a bit wrong in it, there is no CRC): dropped.
            assertFalse(m.ajoute(65, VoixGenesis.envoye(c2Essai(5)), t0 + 8_000L, "HADES-SA (SpinnyONE)"))
            val msg = m.courant!!
            assertEquals(listOf(0, 1, 3), msg.paquets.keys.toList())
            assertEquals(listOf(2), msg.manquants())
            assertEquals(1.6, msg.dureeS(), 1e-9)
            val c2 = msg.c2()
            assertEquals(4 * 40, c2.size)
            assertTrue(c2.copyOfRange(80, 120).all { it == 0.toByte() })
            assertArrayEquals(c2Essai(3), c2.copyOfRange(120, 160))
            val l = AssemblageVoix.liste(dir)
            assertEquals(1, l.size)
            assertEquals(3, l[0].getJSONArray("recus").length())
            assertTrue(AssemblageVoix.fichier(dir, l[0].getString("nom"))!!.length() == 160L)
            assertEquals(null, AssemblageVoix.fichier(dir, "../x"))
            // The number back to 0: a new message.
            m.ajoute(0, VoixGenesis.envoye(c2Essai(9)), t0 + 20_000L, "HADES-SA (SpinnyONE)")
            assertEquals(2, AssemblageVoix.liste(dir).size)
        } finally { dir.deleteRecursively() }
    }

    @Test fun voix_par_le_recepteur() {
        val fs = 24_000
        val o = (0 until 4).flatMap { n -> (Genesis.codeVoix(3, n, VoixGenesis.envoye(c2Essai(n))) + ByteArray(2) { 0xAA.toByte() }).toList() }.toByteArray()
        val pcm = SignalFskAudio.audio(Genesis.bits(o) + IntArray(64) { it and 1 }, fs, 200.0, 1_125.0, 1_300.0, 14.0, 0.5, graine = 3)
        val (r, _) = recoit(pcm, fs, RecepteurGenesis.Profil.AUTO)
        assertEquals(4, r.etat.voix)
        assertEquals(listOf(0, 1, 2, 3), r.voix.courant!!.paquets.keys.toList())
        assertArrayEquals(c2Essai(2), r.voix.courant!!.paquets[2])
        assertTrue(r.trames.any { it.trame.type == Genesis.TYPE_CODEC2 && !it.trame.crcJuste })
    }
}
