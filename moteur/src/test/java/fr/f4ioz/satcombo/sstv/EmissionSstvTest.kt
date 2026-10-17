/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

import fr.f4ioz.satcombo.cw.DecodeurCw
import fr.f4ioz.satcombo.datv.Emetteur
import fr.f4ioz.satcombo.domain.ClasseQo100
import fr.f4ioz.satcombo.domain.Qo100
import fr.f4ioz.satcombo.domain.SignalQo100
import fr.f4ioz.satcombo.sdr.ModulationAudio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The SSTV transmission's rules, its programme and the callsign over the picture. */
class EmissionSstvTest {

    private fun d(mhz: Double, mod: ModulationAudio = ModulationAudio.NBFM, interieur: Boolean = false, charge: Boolean = false,
                  sat: Boolean = false, s: Double = 40.0, ind: String = "F4IOZ", e: Emetteur = Emetteur.BLADERF, confirme: Boolean = true,
                  dev: Double = 5_000.0) =
        DemandeSstvRf(Math.round(mhz * 1e6), mod, -50.0, s, ind, e, dev, interieur, charge, sat, confirme)

    private fun refuse(x: DemandeSstvRf, mot: String) {
        val r = ReglesSstvRf.refus(x)
        assertNotNull("accepté : $x", r)
        assertTrue("« $r » sans « $mot »", r!!.contains(mot))
    }

    @Test
    fun il_faut_un_clic_confirme_et_un_indicatif() {
        refuse(d(1245.0, interieur = true, confirme = false), "non confirmée")
        for (i in listOf("", "  ", "F4", "FOIOZ", "F4 IOZ", "1234")) refuse(d(1245.0, interieur = true, ind = i), "indicatif")
        assertNull(ReglesSstvRf.refus(d(1245.0, interieur = true)))
        assertNull(ReglesSstvRf.refus(d(1245.0, interieur = true, ind = "F4IOZ/P")))
    }

    @Test
    fun seulement_les_bandes_amateur_le_signal_entier_dedans() {
        assertNull(ReglesSstvRf.refus(d(145.500)))
        assertNull(ReglesSstvRf.refus(d(433.400)))
        assertNull(ReglesSstvRf.refus(d(1245.0)))
        assertNull(ReglesSstvRf.refus(d(2320.0, ModulationAudio.USB)))
        for (f in listOf(143.999, 146.5, 429.0, 441.0, 1230.0, 1301.0, 2299.0, 2451.0, 868.0, 50.0)) refuse(d(f), "hors des bandes")
        // The FM's edges: 144.002 MHz with 5 kHz of deviation spills below the band.
        refuse(d(144.002), "hors des bandes")
        assertNull(ReglesSstvRf.refus(d(144.002, ModulationAudio.USB)))
        refuse(d(144.0001, ModulationAudio.LSB), "hors des bandes")
        // Only the Pluto and the bladeRF; their ranges.
        refuse(d(1245.0, interieur = true, e = Emetteur.HACKRF), "ADALM-PLUTO ou bladeRF")
        refuse(d(1245.0, interieur = true, dev = 3_000.0), "excursion")
    }

    @Test
    fun sous_bandes_satellites_seulement_cochees_et_jamais_l_iss() {
        for (f in listOf(145.850, 145.950, 435.500, 437.900, 1265.0)) {
            refuse(d(f), "vers un satellite")
            assertNull("$f", ReglesSstvRf.refus(d(f, sat = true)))
        }
        // 145.800: never, ticked or not, whatever the mode (its channel included).
        for (f in listOf(145.800, 145.795, 145.805)) for (m in ModulationAudio.entries) {
            refuse(d(f, m), "ISS"); refuse(d(f, m, sat = true), "ISS")
        }
        refuse(d(145.800, interieur = true), "ISS")
        // The edge of the sub-band: the FM's band touching it is in it.
        refuse(d(145.796), "ISS")
        assertNull(ReglesSstvRf.refus(d(145.775)))
    }

    @Test
    fun qo100_en_blu_dans_les_segments_numerique_et_mixte_jamais_l_urgence() {
        val usb = ModulationAudio.USB
        refuse(d(2400.100, usb), "vers un satellite")
        assertNull(ReglesSstvRf.refus(d(2400.100, usb, sat = true)))
        assertNull(ReglesSstvRf.refus(d(2400.400, usb, sat = true)))
        assertNull(ReglesSstvRf.refus(d(2400.480, usb, sat = true)))
        refuse(d(2400.100, ModulationAudio.NBFM, sat = true), "USB")
        refuse(d(2400.100, ModulationAudio.LSB, sat = true), "USB")
        // The emergency segment (downlink 10489.858–865: uplink 2400.358–365), the broadcast, SSB, CW, the beacons.
        for (f in listOf(2400.359, 2400.355, 2400.200, 2400.020, 2400.050, 2400.248, 2400.488, 2400.149)) refuse(d(f, usb, sat = true), "QO-100")
        // The wide band and the rest of 2400–2450: DATV's, never SSTV.
        refuse(d(2405.0, usb, sat = true), "bande étroite")
        refuse(d(2420.0, usb, sat = true), "bande étroite")
        // Every accepted frequency has its whole signal in an allowed segment.
        var f = 2_400_000_000L
        while (f < 2_400_500_000L) {
            val x = DemandeSstvRf(f, usb, -50.0, 40.0, "F4IOZ", Emetteur.PLUTO, versSatellite = true, confirme = true)
            if (ReglesSstvRf.refus(x) == null) {
                val (b, h) = ReglesSstvRf.occupe(x)
                for (hz in listOf(b, h)) {
                    val s = Qo100.segment(Qo100.descenteDepuisMontee(hz))!!
                    assertTrue("$f : ${s.cle}", ReglesSstvRf.segmentSstv(s))
                    assertTrue(s.usage != Qo100.Usage.URGENCE)
                }
            }
            f += 500
        }
        assertNotNull(ReglesSstvRf.avertissement(d(2400.100, usb, sat = true)))
    }

    @Test
    fun essai_en_interieur_comme_la_datv_et_cinq_minutes() {
        assertNull(ReglesSstvRf.refus(d(1245.0, interieur = true)))
        assertNull(ReglesSstvRf.refus(d(2320.0, interieur = true)))
        refuse(d(145.500, interieur = true), "essai en intérieur")
        refuse(d(435.0, interieur = true), "essai en intérieur")
        refuse(d(1265.0, interieur = true), "sous-bande")
        refuse(d(2400.100, ModulationAudio.USB, interieur = true), "sous-bande")
        refuse(d(1245.0, interieur = true, sat = true), "jamais vers un satellite")
        refuse(d(1245.0, interieur = true, charge = true), "l'un ou l'autre")
        // PD 290 (4 min 49 s) fits, two of them do not.
        assertNull(ReglesSstvRf.refus(d(1245.0, interieur = true, s = 290.0)))
        refuse(d(1245.0, interieur = true, s = 580.0), "300 s au plus")
        refuse(d(145.5, s = 1000.0), "900 s au plus")
        // The gain: −40 dB at most indoors and into a load, the Réglages' ceiling otherwise.
        val x = d(1245.0, interieur = true)
        assertEquals(-40.0, ReglesSstvRf.plafondDb(x, 0.0), 0.0)
        assertEquals(-40.0, ReglesSstvRf.plafondDb(d(433.0, charge = true), -10.0), 0.0)
        assertEquals(-10.0, ReglesSstvRf.plafondDb(d(145.5), -10.0), 0.0)
        assertEquals(0.0, ReglesSstvRf.plafondDb(d(145.5), 5.0), 0.0)
        // The time given to the transmitter: the programme and a margin, never above the limit.
        assertEquals(55_000L, ReglesSstvRf.dureeEmissionMs(x))
        assertEquals(300_000L, ReglesSstvRf.dureeEmissionMs(d(1245.0, interieur = true, s = 295.0)))
        // Into a load: any frequency of the transmitter, nothing else to check.
        assertNull(ReglesSstvRf.refus(d(868.0, charge = true)))
        refuse(d(6100.0, charge = true), "plage du bladeRF")
        refuse(d(60.0, charge = true, e = Emetteur.PLUTO), "plage de l'ADALM-PLUTO")
    }

    @Test
    fun le_programme_finit_par_lui_meme_avec_l_indicatif_en_cw() {
        val m = SstvMode.byName("Robot 36")!!
        val px = IntArray(m.width * m.height)
        val p = ProgrammeSstv(listOf(m to px, m to px), 8_000, cw = "DE F4IOZ", cwWpm = 25)
        val manip = DecodeurCw.manipulation("DE F4IOZ", 25).sumOf { Math.round(it.second * 8.0) }
        val un = SstvEncoder.Source(m, px, 8_000).totalSamples
        assertEquals(2L * un + 8_000 + 8_000 + manip, p.totalEchantillons)
        val b = ShortArray(4096)
        var total = 0L; var pic = 0
        while (true) { val n = p(b); if (n == 0) break; total += n; for (k in 0 until n) pic = maxOf(pic, kotlin.math.abs(b[k].toInt())) }
        assertEquals(p.totalEchantillons, total)
        assertEquals(1.0, p.progres, 0.0)
        assertEquals(0, p(b))
        // Full scale: the encoder's level brought to full deviation.
        assertTrue("$pic", pic in 32_000..32_767)
    }

    @Test
    fun l_indicatif_est_ecrit_en_haut_de_l_image() {
        val w = 320; val h = 240
        val px = IntArray(w * h) { 0x808080 }
        val o = IndicatifSstv.incruste(px, w, h, "f4ioz")
        val bande = IndicatifSstv.hauteurBande(h)
        assertEquals(24, bande)
        // Below the band: untouched; in it: the background and white dots only, as many as the glyphs say.
        for (i in bande * w until w * h) assertEquals(0x808080, o[i])
        val e = IndicatifSstv.echelle(w, h, 5)
        val points = "F4IOZ".sumOf { c -> IndicatifSstv.GLYPHES.getValue(c).count { it == '#' } }
        assertEquals(points * e * e, (0 until bande * w).count { o[it] == IndicatifSstv.ENCRE })
        assertEquals(bande * w - points * e * e, (0 until bande * w).count { o[it] == IndicatifSstv.FOND })
        // A long callsign stays inside the width.
        val l = IndicatifSstv.incruste(IntArray(w * h), w, h, "F4IOZ/P DE TK5ABC")
        assertTrue((0 until bande).all { y -> l[y * w] == IndicatifSstv.FOND && l[y * w + w - 1] == IndicatifSstv.FOND })
        assertEquals(40, IndicatifSstv.GLYPHES.size)
        assertTrue(IndicatifSstv.GLYPHES.values.all { it.length == 35 && it.all { c -> c == '#' || c == '.' } })
    }

    @Test
    fun niveau_sur_qo100_face_a_la_balise() {
        fun s(hz: Long, snr: Double) = SignalQo100(1, hz, 2500, ClasseQo100.BLU, 1.0, 0L, 0L, snr, "")
        val bas = 2_400_100_300L; val haut = 2_400_102_800L
        val nous = Qo100.descenteDepuisMontee(2_400_101_700L)
        val m = NiveauSstvQo100.mesure(listOf(s(Qo100.BALISE_MEDIANE_HZ + 100, 20.0), s(nous, 23.0), s(nous + 50_000, 30.0)), bas, haut)
        assertEquals(3.0, m.ecartDb!!, 1e-9)
        assertNull(NiveauSstvQo100.mesure(listOf(s(nous, 23.0)), bas, haut).ecartDb)
        assertNull(NiveauSstvQo100.mesure(listOf(s(Qo100.BALISE_MEDIANE_HZ, 20.0)), bas, haut).nousDb)
    }
}
