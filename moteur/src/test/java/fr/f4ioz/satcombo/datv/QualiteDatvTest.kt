/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv

import fr.f4ioz.satcombo.datv.s2.Modcod
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The thresholds, the stream's rate, the verdict and the second-by-second follow-up. */
class QualiteDatvTest {

    @Test
    fun les_seuils_des_modcod() {
        assertEquals(1.0, QualiteDatv.seuilDb("QPSK 1/2", 4)!!, 1e-9)
        assertEquals(3.1, QualiteDatv.seuilDb("QPSK 2/3", 4)!!, 1e-9)
        assertEquals(4.03, QualiteDatv.seuilDb("QPSK 3/4", 4)!!, 1e-9)
        assertEquals(6.62, QualiteDatv.seuilDb("8PSK 2/3", 4)!!, 1e-9)
        assertEquals(3.4, QualiteDatv.seuilDb("QPSK 2/3", 4, courtes = true)!!, 1e-9)
        assertEquals(4.15, QualiteDatv.seuilDb("QPSK 1/2", 3)!!, 1e-9)
        assertNull(QualiteDatv.seuilDb("QPSK 2/3", 1))
        assertNull(QualiteDatv.seuilDb("", 4))
        // Every MODCOD LongMynd names has its threshold, in increasing order within a constellation.
        val s2 = (1..28).map { Datv.modcod(it, 4) }
        assertTrue(s2.all { QualiteDatv.seuilDb(it, 4) != null })
        for (c in listOf("QPSK", "8PSK", "16APSK", "32APSK")) {
            val l = s2.filter { it.startsWith("$c ") }.map { QualiteDatv.seuilDb(it, 4)!! }
            assertEquals(l.sorted(), l)
        }
    }

    @Test
    fun le_debit_du_flux_comme_le_modulateur() {
        // The owner's test: QPSK 2/3, 333 kS/s, pilots: 429.8 kbit/s, 286 packets/s.
        val d = QualiteDatv.debitTs("QPSK 2/3", 4, 333, false, true)!!
        assertEquals(Modcod.de("QPSK 2/3", false, true)!!.debitTs(333_000.0), d, 1e-6)
        assertEquals(429_800.0, d, 100.0)
        for (m in Modcod.tous(courte = true, pilotes = false) + Modcod.tous(courte = false, pilotes = true))
            assertEquals(m.libelle, m.debitTs(1_500_000.0), QualiteDatv.debitTs(m.constellation.libelle + " " + m.taux.libelle, 4, 1500, m.courte, m.pilotes)!!, 1e-6)
        // DVB-S: 2 bits × rate × 188/204.
        assertEquals(1_500_000 * 2 * 0.5 * 188 / 204.0, QualiteDatv.debitTs("QPSK 1/2", 3, 1500, null, null)!!, 1e-6)
        assertNull(QualiteDatv.debitTs("QPSK 2/3", 1, 333, null, null))
        assertEquals(42_960 / 1504.0, QualiteDatv.paquetsParTrame("QPSK 2/3", false)!!, 1e-9)
        assertTrue(QualiteDatv.debitTs("16APSK 3/4", 4, 1000, false, false)!! > QualiteDatv.debitTs("8PSK 3/4", 4, 1000, false, false)!!)
    }

    private fun m(marge: Double? = 6.0, cc: Double = 0.0, tei: Double = 0.0, fec: Boolean? = false, entieres: Boolean = false,
                  video: Double = 0.0, audio: Double = 0.0, images: Double? = 10.0) =
        QualiteDatv.Mesure(true, true, marge, cc, tei, fec, entieres, video, audio, images, 30)

    @Test
    fun le_verdict() {
        assertEquals(QualiteDatv.Verdict(0, "Image parfaite"), QualiteDatv.verdict(m()))
        assertEquals("Réception arrêtée", QualiteDatv.verdict(m().copy(actif = false)).texte)
        assertEquals("Pas accroché", QualiteDatv.verdict(m().copy(verrouille = false)).texte)
        // Losses with a thin margin: the signal.
        val juste = QualiteDatv.verdict(m(marge = 0.4, cc = 5.0, fec = true))
        assertEquals(2, juste.niveau)
        assertTrue(juste.texte.startsWith("Signal juste : des trames sont perdues après correction (BCH)"))
        assertTrue(juste.conseil.contains("MODCOD plus robuste"))
        // Losses, the FEC clean: the PC.
        assertEquals("Paquets perdus mais trames correctes : le PC n'arrive pas à suivre", QualiteDatv.verdict(m(cc = 3.0, fec = false)).texte)
        // Losses by whole frames with a good margin: the signal is distorted, not weak.
        val deforme = QualiteDatv.verdict(m(marge = 7.5, cc = 20.0, fec = null, entieres = true))
        assertEquals(2, deforme.niveau)
        assertTrue(deforme.texte, deforme.texte.contains("7,5 dB de marge"))
        assertTrue(QualiteDatv.verdict(m(cc = 0.0, tei = 1.0, fec = true)).texte.startsWith("Trames entières"))
        // The owner's indoor test: 7 dB of margin, 11.9 % BER, whole frames lost: the transmitter.
        val tx = QualiteDatv.verdict(m(marge = 7.0, cc = 20.0, fec = null, entieres = true).copy(berPct = 11.9))
        assertTrue(tx.texte, tx.texte.startsWith("Symboles nets (7,0 dB de marge) mais bits faux (BER 11,9 %)"))
        // A thin margin stays the signal's fault, whatever the BER.
        assertTrue(QualiteDatv.verdict(m(marge = 0.5, cc = 20.0).copy(berPct = 11.9)).texte.startsWith("Signal juste"))
        // Decoding errors alone.
        assertEquals("Erreurs de décodage vidéo seulement : débit vidéo trop haut / codage", QualiteDatv.verdict(m(video = 12.0)).texte)
        // No loss, little margin.
        assertEquals(1, QualiteDatv.verdict(m(marge = 0.5)).niveau)
        // A stray continuity error now and then is not a loss.
        assertEquals(0, QualiteDatv.verdict(m(cc = 0.1)).niveau)
    }

    @Test
    fun les_lignes_des_decodeurs() {
        assertEquals('v', QualiteDatv.classe("[h264 @ 0x55d5c1a4e2c0] error while decoding MB 12 7, bytestream -5"))
        assertEquals('v', QualiteDatv.classe("[h264 @ 0x55d5c1a4e2c0] concealing 210 DC, 210 AC, 210 MV errors in P frame"))
        assertEquals('v', QualiteDatv.classe("[hevc @ 0x1] Could not find ref with POC 3"))
        assertEquals('a', QualiteDatv.classe("[aac @ 0x7f] Number of bands (31) exceeds limit (14)."))
        assertEquals('t', QualiteDatv.classe("[mpegts @ 0x2] Packet corrupt (stream = 0, dts = 1)."))
        assertNull(QualiteDatv.classe(""))
        assertNull(QualiteDatv.classe("Input #0, mpegts, from 'pipe:0':"))
        assertEquals(4, QualiteDatv.repetitions("    Last message repeated 4 times"))
        assertNull(QualiteDatv.repetitions("[aac @ 0x7f] Reserved bit set."))
    }

    @Test
    fun les_compteurs_qui_rebouclent_et_le_json() {
        assertEquals(0, Datv.ecartCompteur(null, 9, Datv.MODULO_BCH))
        assertEquals(3, Datv.ecartCompteur(14, 1, Datv.MODULO_BCH))
        assertEquals(10, Datv.ecartCompteur(65_530, 4, Datv.MODULO_LDPC))
        val e = EtatDatv(actif = true, etat = 4, merDb = 9.5, modcod = "QPSK 2/3", lna = 12, puissanceI = 40, puissanceQ = 41, viterbiPct = 0.5,
            ldpc = 77, bch = 3, bchNonCorrige = true, ldpcTotal = 1234, bchTotal = 56)
        val j = Datv.json(e, ReglageDatv(), emptyList())
        val r = Datv.etatDe(JSONObject(j.toString()), EtatDatv())
        assertEquals(e.copy(actif = false), r.copy(actif = false))
        // A node's older JSON, without them: nothing invented.
        val vieux = Datv.etatDe(JSONObject().put("etat", 4).put("mer", 5.0), EtatDatv())
        assertNull(vieux.bchNonCorrige); assertNull(vieux.ldpc); assertEquals(0L, vieux.bchTotal)
    }

    @Test
    fun le_suivi_seconde_par_seconde() {
        val f = AnalyseTsTest.Fabrique()
        val a = AnalyseTs()
        val s = SuiviQualite()
        val e = EtatDatv(actif = true, etat = 4, merDb = 10.0, modcod = "QPSK 2/3", pilotes = true, trames = false, srTrouveKs = 333, bchNonCorrige = false)
        var o = JSONObject()
        // 286 packets a second for 10 s, from the 5th second on whole frames lost (87 packets of 286 kept).
        for (sec in 0 until 10) {
            val l = f.suite(286)
            val garde = if (sec < 5) l else l.filterIndexed { i, _ -> (i / 29) % 4 == 0 }
            garde.forEach { a.ajoute(it) }
            o = s.seconde(sec * 1000L, e, 333, a.bilan(), 0, 0, 0, imagesIci = false)
        }
        assertEquals(6.9, o.getDouble("marge"), 1e-9)
        assertEquals(3.1, o.getDouble("seuil"), 1e-9)
        assertEquals(87 / 286.0, o.getDouble("ratio"), 0.02)
        assertTrue(o.getDouble("cc") > 1)
        assertTrue(o.getBoolean("tramesEntieres"))
        val v = o.getJSONObject("verdict")
        assertEquals(2, v.getInt("niveau"))
        assertTrue(v.getString("texte"), v.getString("texte").startsWith("Trames entières"))
        assertEquals(10, o.getJSONObject("histo").getJSONArray("mer").length())
        assertTrue(o.getJSONArray("pids").length() >= 4)
        assertFalse(o.isNull("ratioPcr"))
    }
}
