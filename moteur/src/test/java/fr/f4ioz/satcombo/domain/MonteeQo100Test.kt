/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import fr.f4ioz.satcombo.domain.MonteeQo100.CorrectionPluto
import fr.f4ioz.satcombo.domain.MonteeQo100.Mode
import fr.f4ioz.satcombo.domain.MonteeQo100.PlanPluto
import fr.f4ioz.satcombo.station.CatalogueMateriel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** QO-100's narrow-band uplink: the band plan per mode, the two chains' frequencies, the Pluto's plan and correction. */
class MonteeQo100Test {

    @Test fun `les cadrans par defaut sont permis`() {
        for (m in Mode.entries) assertNull(m.name, MonteeQo100.refus(m, MonteeQo100.cadranParDefaut(m)))
    }

    @Test fun `le plan de bande par mode`() {
        // SSB in the upper voice segment (downlink 10 489.800), the lower one (10 489.700).
        assertNull(MonteeQo100.refus(Mode.BLU, 2_400_300_000L))
        assertNull(MonteeQo100.refus(Mode.BLU, 2_400_200_000L))
        // Voice in the CW or digital segments: no.
        assertNotNull(MonteeQo100.refus(Mode.BLU, 2_400_025_000L))
        assertNotNull(MonteeQo100.refus(Mode.BLU, 2_400_100_000L))
        // FT8 on 10 489.540 yes, in the voice segment no.
        assertNull(MonteeQo100.refus(Mode.NUM, 2_400_040_000L))
        assertNotNull(MonteeQo100.refus(Mode.NUM, 2_400_300_000L))
        // CW in its segment and the mixed one.
        assertNull(MonteeQo100.refus(Mode.CW, 2_400_010_000L))
        assertNull(MonteeQo100.refus(Mode.CW, 2_400_400_000L))
        assertNotNull(MonteeQo100.refus(Mode.CW, 2_400_300_000L))
        // Everything goes in the mixed segment.
        for (m in Mode.entries) assertNull(m.name, MonteeQo100.refus(m, 2_400_420_000L))
    }

    @Test fun `jamais sur une balise ni l urgence ni la diffusion`() {
        // The middle beacon: downlink 10 489.745–755, uplink 2 400.245–255.
        for (m in Mode.entries) {
            assertTrue(MonteeQo100.refus(m, 2_400_249_000L)!!.contains("balise"))
            // The passband straddling the beacon's lower edge.
            assertNotNull(MonteeQo100.refus(m, 2_400_243_000L))
        }
        assertTrue(MonteeQo100.refus(Mode.BLU, 2_400_359_000L)!!.contains("urgence"))
        assertTrue(MonteeQo100.refus(Mode.BLU, 2_400_352_000L)!!.contains("diffusion"))
        // The top: the multimedia beacon above 10 489.990.
        assertNotNull(MonteeQo100.refus(Mode.BLU, 2_400_488_000L))
        assertNull(MonteeQo100.refus(Mode.BLU, 2_400_487_000L))
        // Outside the transponder.
        assertNotNull(MonteeQo100.refus(Mode.CW, 2_399_990_000L))
        assertNotNull(MonteeQo100.refus(Mode.BLU, 2_401_000_000L))
    }

    @Test fun `montee et descente liees`() {
        assertEquals(2_400_300_000L, MonteeQo100.cadranLie(10_489_800_000L))
        // One kHz up on the downlink, one kHz up on the uplink.
        assertEquals(1_000L, MonteeQo100.cadranLie(10_489_801_000L) - MonteeQo100.cadranLie(10_489_800_000L))
    }

    @Test fun `la chaine du poste frequence montee egale poste plus OL`() {
        val c = ChaineQo100.Chaine("Fixe", 10_344_973_000L, 1_968_000_000L)
        assertEquals(432_300_000L, MonteeQo100.cadranPoste(c, 2_400_300_000L))
        // Uplink = rig + OL, whatever the dial.
        for (m in listOf(2_400_010_000L, 2_400_250_000L, 2_400_480_000L)) assertEquals(m, MonteeQo100.cadranPoste(c, m)!! + c.monteeOlHz)
        assertNull(MonteeQo100.cadranPoste(ChaineQo100.Chaine("Pluto"), 2_400_300_000L))
    }

    @Test fun `l OL de montee mesure sur sa propre descente`() {
        // The converter really at 1 968.0123 MHz: the rig on 432.3 MHz lands on 2 400.3123 MHz, heard on 10 489.8123.
        val ol = MonteeQo100.olMonteeMesure(10_489_812_300L, 432_300_000L)
        assertEquals(1_968_012_300L, ol)
        assertTrue(ChaineQo100.monteeCredible(ol))
    }

    @Test fun `le plan du Pluto garde son LO hors du transpondeur`() {
        for (cadran in Qo100.NB.monteeBasHz..Qo100.NB.monteeHautHz step 5_000L) for (d in listOf(-60_000L, 0L, 25_000L, 60_000L)) {
            val p = PlanPluto.pour(cadran, d)
            val r = 1 + CorrectionPluto.relative(d)
            // Where the LO leaks and where the image falls, as really transmitted: below 2 400.000.
            val fuite = p.loHz * r
            val image = (p.loHz - p.ncoHz) * r
            assertTrue("fuite $cadran $d", fuite < 2_400_000_000.0 - 300_000)
            assertTrue("image $cadran $d", image < 2_400_000_000.0 - 300_000)
            // The shift within the sample rate's useful band.
            assertTrue(abs(p.ncoHz) < PlanPluto.DEBIT * 0.4)
            // And the signal where asked, to the hertz.
            assertEquals(cadran.toDouble(), (p.loHz + p.ncoHz) * r, 0.5)
        }
    }

    @Test fun `la correction du Pluto mesuree sur la descente`() {
        val montee = 2_400_300_000L
        val attendu = Qo100.descenteDepuisMontee(montee + 700)
        // Heard 2.4 kHz high: about 1 ppm, 2 400 Hz at 2 400 MHz.
        val c = CorrectionPluto.mesure(attendu, attendu + 2_400, montee, 0L)
        assertEquals(2_400.0, c.toDouble(), 1.0)
        assertEquals(1.0, CorrectionPluto.ppm(c), 0.001)
        // A second measurement adds the residual.
        assertEquals(2_400.0 - 100, CorrectionPluto.mesure(attendu, attendu - 100, montee, c).toDouble(), 1.0)
        // Applied: the dial asked lower by as much.
        val p = PlanPluto.pour(montee, c)
        assertEquals(montee.toDouble(), (p.loHz + p.ncoHz) * (1 + CorrectionPluto.relative(c)), 0.5)
        assertTrue(p.loHz + p.ncoHz < montee - 2_000)
        assertTrue(CorrectionPluto.credible(60_000L))
        assertTrue(!CorrectionPluto.credible(500_000L))
    }

    @Test fun `le profil fixe et le portable`() {
        val fixe = ProfilMontee.PAR_DEFAUT.first { it.nom == "Fixe" }
        val portable = ProfilMontee.PAR_DEFAUT.first { it.nom == "Portable" }
        assertEquals(23.5, ProfilMontee.gainParabole(0.80), 0.4)
        assertEquals(16.3, ProfilMontee.gainParabole(0.35), 0.4)
        assertEquals(15.0, fixe.gainDbi, 0.01)
        // 10 W, 1.5 dB of cable, 15 dBi: 23.5 dBW.
        assertEquals(23.5, fixe.pireMaxDbw!!, 0.01)
        // The power back from the EIRP.
        assertEquals(10.0, fixe.wattsPour(23.5), 0.01)
        // Under the beacon: less for FT8 than for voice, never above the amplifier.
        assertTrue(fixe.conseilleW(MonteeQo100.Mode.NUM) < fixe.conseilleW(MonteeQo100.Mode.BLU))
        assertTrue(fixe.conseilleW(MonteeQo100.Mode.BLU) <= fixe.ampliW)
        assertTrue(portable.conseilleW(MonteeQo100.Mode.BLU) < 10.0)
        // The amplifier's gain known: the Pluto's attenuation suggested.
        assertNull(fixe.gainPlutoConseille(MonteeQo100.Mode.BLU))
        val g = fixe.copy(ampliGainDb = 40.0).gainPlutoConseille(MonteeQo100.Mode.NUM)!!
        assertTrue(g in -89.75..0.0)
        // Each profile has its drawing in the Montage.
        for (p in ProfilMontee.PAR_DEFAUT) assertNotNull(p.nom, CatalogueMateriel.PREREGLAGES.firstOrNull { it.id == p.prereglage })
        for (a in ProfilMontee.Antenne.entries) if (a.montage.isNotEmpty()) assertNotNull(a.montage, CatalogueMateriel.modele(a.montage))
    }

    @Test fun `les profils se gardent`() {
        val l = ProfilMontee.range(ProfilMontee.PAR_DEFAUT, ProfilMontee("fixe", MonteeQo100.Chaine.PLUTO, ProfilMontee.Antenne.AUTRE, 18.0, 25.0, 30.0, 2.0))
        assertEquals(ProfilMontee.PAR_DEFAUT.size, l.size)
        val relu = ProfilMontee.lisListe(ProfilMontee.ecrisListe(l))
        assertEquals(l, relu)
        assertEquals(18.0, relu.first().gainDbi, 0.0)
        assertEquals(ProfilMontee.PAR_DEFAUT, ProfilMontee.lisListe("pas du json"))
        assertEquals(ProfilMontee.PAR_DEFAUT, ProfilMontee.lisListe(null))
        // The absurd clamped.
        val b = ProfilMontee("x", ampliW = Double.NaN, pertesDb = 99.0).borne()
        assertEquals(0.0, b.ampliW, 0.0); assertEquals(20.0, b.pertesDb, 0.0)
    }
}
