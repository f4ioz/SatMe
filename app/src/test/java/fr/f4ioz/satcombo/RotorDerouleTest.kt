/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.rotor.RotorDeroule
import fr.f4ioz.satcombo.rotor.RotorDeroule.Raison
import fr.f4ioz.satcombo.rotor.RotorMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Going round the end stop during a pass: when, and when not. */
class RotorDerouleTest {

    private val sud = RotorMath.Limits(azMaxDeg = 360.0, azStopDeg = 180.0)

    @Test
    fun un_depart_pres_de_la_butee_attend_de_l_autre_cote() {
        // Olivier's example: south stop, the pass starts at 210, crosses 180, goes on to 135.
        val passage = listOf(210.0 to 5.0, 195.0 to 20.0, 180.0 to 35.0, 160.0 to 30.0, 135.0 to 10.0)
        // Dead zone 30°: starting at 210 is within it on the other side — the mast waits there (540 = 180 seen from the east side).
        val p = RotorMath.plan(passage, sud, toleranceDeg = 30.0)!!
        assertEquals(1.0, p.coverage, 1e-9)
        assertEquals(540.0, RotorMath.clampAz(p.startAzDeg, sud), 1e-9)
        // Dead zone 5°: the high part is after the stop — still waiting there is best (elevation-weighted).
        assertEquals(540.0, RotorMath.clampAz(RotorMath.plan(passage, sud, toleranceDeg = 5.0)!!.startAzDeg, sud), 1e-9)
        // Most of the pass before the stop, a short tail beyond it: start at 260; the tail will need a turn.
        val autre = listOf(260.0 to 5.0, 230.0 to 30.0, 200.0 to 45.0, 185.0 to 20.0, 170.0 to 5.0)
        assertEquals(260.0, RotorMath.clampAz(RotorMath.plan(autre, sud, toleranceDeg = 5.0)!!.startAzDeg, sud), 1e-9)
    }

    @Test
    fun le_tour_seulement_au_dela_de_la_zone_morte() {
        assertFalse(RotorDeroule.besoin(erreurIci = 10.0, erreurAutre = 0.0, zoneMorte = 15.0))
        assertTrue(RotorDeroule.besoin(erreurIci = 20.0, erreurAutre = 0.0, zoneMorte = 15.0))
        // The other side no better: stay.
        assertFalse(RotorDeroule.besoin(erreurIci = 20.0, erreurAutre = 25.0, zoneMorte = 15.0))
    }

    @Test
    fun jamais_en_emission() {
        val d = RotorDeroule.decide(1_000L, 60_000L, emission = true, images = emptyList())
        assertFalse(d.tourner); assertEquals(Raison.EMISSION, d.raison)
    }

    @Test
    fun une_image_en_cours_ou_trop_proche_fait_attendre_sa_fin() {
        val image = 100_000L..136_000L
        val enCours = RotorDeroule.decide(110_000L, 60_000L, false, listOf(image))
        assertFalse(enCours.tourner); assertEquals(Raison.IMAGE_EN_COURS, enCours.raison); assertEquals(136_000L, enCours.jusquaMs)
        // 60 s of turning from 50 s: the picture would start at 100 s, mid-turn.
        val proche = RotorDeroule.decide(50_000L, 60_000L, false, listOf(image))
        assertFalse(proche.tourner); assertEquals(Raison.IMAGE_TROP_PROCHE, proche.raison); assertEquals(136_000L, proche.jusquaMs)
        // From 30 s it is over at 90 s, before the picture.
        assertTrue(RotorDeroule.decide(30_000L, 60_000L, false, listOf(image)).tourner)
        assertTrue(RotorDeroule.decide(140_000L, 60_000L, false, listOf(image)).tourner)
    }

    @Test
    fun les_images_de_l_iss_se_suivent_a_leur_rythme() {
        // The 04/10 pass: Robot 36 every 2 min 39, 36 s each.
        val recues = listOf(0L..36_000L, 159_000L..195_000L)
        val l = RotorDeroule.imagesAVenir(recues, depuis = 200_000L, jusqua = 700_000L)
        assertEquals(listOf(318_000L..354_000L, 477_000L..513_000L, 636_000L..672_000L), l)
        assertTrue(RotorDeroule.imagesAVenir(listOf(0L..36_000L), 0L, 1_000_000L).isEmpty())
    }

    @Test
    fun anticiper_quand_le_tour_tient_avant_l_image() {
        assertTrue(RotorDeroule.anticiper(40.0, 5.0, 10.0, zoneMorte = 15.0, avantImageMs = 80_000L, dureeMs = 65_000L))
        // Not enough time: wait for its end instead.
        assertFalse(RotorDeroule.anticiper(40.0, 5.0, 10.0, zoneMorte = 15.0, avantImageMs = 40_000L, dureeMs = 65_000L))
        // The other side would miss the satellite now: not yet.
        assertFalse(RotorDeroule.anticiper(40.0, 5.0, 25.0, zoneMorte = 15.0, avantImageMs = 80_000L, dureeMs = 65_000L))
        // Within the dead zone during the picture anyway: no turn.
        assertFalse(RotorDeroule.anticiper(10.0, 5.0, 5.0, zoneMorte = 15.0, avantImageMs = 80_000L, dureeMs = 65_000L))
    }

    @Test
    fun la_vitesse_se_mesure_en_tournant() {
        assertEquals(6.0, RotorDeroule.mesureVitesse(100.0, 0L, 106.0, 1_000L, null)!!, 1e-9)
        assertEquals(6.8, RotorDeroule.mesureVitesse(100.0, 0L, 110.0, 1_000L, 6.0)!!, 1e-9)
        assertNull(RotorDeroule.mesureVitesse(100.0, 0L, 100.2, 1_000L, 6.0))   // still
        assertNull(RotorDeroule.mesureVitesse(100.0, 0L, 130.0, 10_000L, 6.0))  // readings too far apart
        assertEquals(65_000L, RotorDeroule.dureeMs(360.0, 6.0))
    }

    // ------------------------------------------------ a whole pass, second by second

    /** A pass from azimuth 20 to 250 in 600 s, crossing a south stop 70° beyond it. */
    private fun az(t: Long) = 20.0 + 230.0 * t / 600_000.0
    private fun el(t: Long) = 60.0 * kotlin.math.sin(Math.PI * t / 600_000.0)
    private val passage = (0..60).map { az(it * 10_000L) to el(it * 10_000L) }

    private data class Rejeu(val etats: List<Pair<Long, RotorDeroule.Etat>>, val cmdFin: Double)

    /** The mast following the pass (6 °/s), [images] / [emission] as given. */
    private fun rejoue(images: List<LongRange> = emptyList(), emission: (Long) -> Boolean = { false },
                       imagesA: (Long) -> List<LongRange> = { images }): Rejeu {
        val plan = RotorMath.plan(passage, sud, toleranceDeg = 15.0)!!
        var cmd = fr.f4ioz.satcombo.rotor.RotorPos(RotorMath.clampAz(plan.startAzDeg, sud), 0.0)
        val etats = ArrayList<Pair<Long, RotorDeroule.Etat>>()
        for (t in 0L..600_000L step 1_000L) {
            val v = RotorDeroule.vise(az(t), el(t), cmd, sud, false, 15.0, t, imagesA(t), emission(t), 6.0) { x ->
                if (x in 0..600_000) az(x) to el(x) else null }
            etats += t to v.etat
            cmd = fr.f4ioz.satcombo.rotor.RotorPos(v.aim.azDeg, v.aim.elDeg)
        }
        return Rejeu(etats, cmd.azDeg)
    }

    private fun Rejeu.premier(e: RotorDeroule.Etat) = etats.firstOrNull { it.second == e }?.first

    @Test
    fun le_tour_se_fait_une_fois_passe_la_zone_morte() {
        val r = rejoue()
        // Starts on the side that covers the high part (380 = 20 seen past the stop), the stop at 540 = 180.
        val tour = r.premier(RotorDeroule.Etat.TOUR)!!
        // 15° beyond the stop: az 195 at 456.5 s.
        assertTrue("tour à $tour", tour in 456_000L..458_000L)
        assertTrue(r.etats.filter { it.first < tour }.all { it.second == RotorDeroule.Etat.SUIT })
        // Once round, never back: one turn per pass.
        assertEquals(1, r.etats.count { it.second == RotorDeroule.Etat.TOUR })
        assertEquals(250.0, r.cmdFin, 0.5)
    }

    @Test
    fun pas_de_tour_pendant_l_emission() {
        val r = rejoue(emission = { it in 440_000L..520_000L })
        assertEquals(null, r.etats.firstOrNull { it.second == RotorDeroule.Etat.TOUR && it.first <= 520_000L })
        assertTrue(r.etats.any { it.second == RotorDeroule.Etat.ATTEND_EMISSION })
        assertEquals(521_000L, r.premier(RotorDeroule.Etat.TOUR))
    }

    @Test
    fun pas_de_tour_pendant_une_image() {
        // A picture the decoder starts on at 450 s (not known before it begins).
        val r = rejoue(imagesA = { t -> if (t >= 450_000L) listOf(450_000L..486_000L) else emptyList() })
        assertEquals(null, r.premier(RotorDeroule.Etat.TOUR_ANTICIPE))
        assertTrue(r.etats.any { it.second == RotorDeroule.Etat.ATTEND_IMAGE })
        assertEquals(487_000L, r.premier(RotorDeroule.Etat.TOUR))
    }

    @Test
    fun le_tour_anticipe_avant_l_image_attendue() {
        // Two pictures received at the ISS's pace (159 s): the next one is due at 460..496 s, beyond the dead zone.
        val recues = listOf(142_000L..178_000L, 301_000L..337_000L)
        val avenir = RotorDeroule.imagesAVenir(recues, 0L, 600_000L)
        assertEquals(460_000L..496_000L, avenir.first())
        val r = rejoue(images = avenir)
        val t = r.premier(RotorDeroule.Etat.TOUR_ANTICIPE)!!
        // Gone round early enough to be there before it: the turn (≈ 62 s) ends before 460 s.
        assertTrue("anticipé à $t", t + RotorDeroule.dureeMs(360.0, 6.0) <= 460_000L)
        assertEquals(null, r.premier(RotorDeroule.Etat.TOUR))
        assertEquals(250.0, r.cmdFin, 0.5)
    }
}
