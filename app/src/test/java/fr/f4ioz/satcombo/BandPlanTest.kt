/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.cat.BandPlan
import fr.f4ioz.satcombo.cat.BandPlan.Band
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Switching between V/U and U/V satellites. Constraint: *VFO A and B can never
 * share a band*. The key test swaps both bands without ever putting them
 * together on the way.
 */
class BandPlanTest {

    private val rs44Dl = 435_660_000L   // V/U: receive on 70 cm…
    private val rs44Ul = 145_940_000L   // …transmit on 2 m.
    private val ao91Dl = 145_960_000L   // U/V: exactly the reverse.
    private val ao91Ul = 435_250_000L

    /** Radio state after playing all steps, checked at each step. */
    private fun jouer(mainNow: Long, subNow: Long, mainT: Long, subT: Long): Pair<Long, Long> {
        var m = mainNow
        var s = subNow
        for (e in BandPlan.steps(m, s, mainT, subT)) {
            if (e.sub) s = e.hz else m = e.hz
            assertNotEquals("les deux bandes se sont retrouvées ensemble : " +
                "MAIN $m / SUB $s", BandPlan.band(m), BandPlan.band(s))
        }
        return m to s
    }

    @Test
    fun les_bandes_du_poste_sont_reconnues() {
        assertEquals(Band.V, BandPlan.band(145_940_000L))
        assertEquals(Band.U, BandPlan.band(435_660_000L))
        assertEquals(Band.L, BandPlan.band(1_296_100_000L))
        assertEquals(Band.AUTRE, BandPlan.band(14_070_000L))
    }

    @Test
    fun sans_rien_savoir_du_poste_l_ordre_reste_celui_d_avant() {
        // Uplink first, downlink last: we end on receive, with the useful dial
        // at hand. This must not change while no band is known.
        val e = BandPlan.steps(null, null, rs44Dl, rs44Ul)
        assertEquals(2, e.size)
        assertTrue("la montée n'est pas écrite en premier", e[0].sub)
        assertEquals(rs44Ul, e[0].hz)
        assertEquals(rs44Dl, e[1].hz)
    }

    @Test
    fun le_doppler_d_un_meme_satellite_n_ajoute_aucune_etape() {
        // 100 loop cycles on RS-44: bands do not change, so nothing is added.
        // A parking step every cycle would add three frames ten times a second
        // on the CI-V bus.
        var m = rs44Dl
        var s = rs44Ul
        for (i in 1..100) {
            val dl = rs44Dl - i * 100L
            val ul = rs44Ul + i * 30L
            val e = BandPlan.steps(m, s, dl, ul)
            assertEquals("étape de trop au tour $i : $e", 2, e.size)
            for (x in e) if (x.sub) s = x.hz else m = x.hz
        }
        assertEquals(rs44Dl - 10_000L, m)
    }

    @Test
    fun passer_de_v_sur_u_a_u_sur_v_echange_les_bandes_sans_les_reunir() {
        // The reported case, both ways. Without parking, the first write put
        // 435 against 435 and the radio refused, leaving both VFOs on 435.
        val (m1, s1) = jouer(rs44Dl, rs44Ul, ao91Dl, ao91Ul)
        assertEquals(ao91Dl, m1)
        assertEquals(ao91Ul, s1)

        val (m2, s2) = jouer(ao91Dl, ao91Ul, rs44Dl, rs44Ul)
        assertEquals(rs44Dl, m2)
        assertEquals(rs44Ul, s2)
    }

    @Test
    fun l_echange_pur_gare_la_montee_sur_une_troisieme_bande() {
        val e = BandPlan.steps(rs44Dl, rs44Ul, ao91Dl, ao91Ul)
        assertEquals("l'échange demande trois écritures : $e", 3, e.size)
        assertTrue("la première écriture n'est pas un garage de la montée", e[0].sub)
        assertEquals("le garage n'est pas sur la troisième bande",
            Band.L, BandPlan.band(e[0].hz))
        assertEquals(ao91Dl, e[1].hz)
        assertEquals(ao91Ul, e[2].hz)
    }

    @Test
    fun quand_un_seul_ordre_passe_c_est_celui_la_qui_est_choisi() {
        // Radio on 2 m RX / 23 cm TX; target 2 m RX / 70 cm TX. The uplink can
        // go first without conflict; no parking needed.
        val e = BandPlan.steps(145_960_000L, 1_296_000_000L, 145_960_000L, 435_250_000L)
        assertEquals(2, e.size)
        assertTrue(e[0].sub)

        // Reverse: the uplink targets 2 m, where the downlink still sits, so it
        // cannot go first. The downlink targets the free 70 cm band: two writes,
        // downlink first. (If the uplink held 70 cm, it would be the pure swap
        // above and need parking.)
        val f = BandPlan.steps(145_960_000L, 1_296_000_000L, 435_660_000L, 145_940_000L)
        assertEquals("un garage inutile a été ajouté : $f", 2, f.size)
        assertTrue("la descente aurait dû partir la première", !f[0].sub)
        val (m, s) = jouer(145_960_000L, 1_296_000_000L, 435_660_000L, 145_940_000L)
        assertEquals(435_660_000L, m)
        assertEquals(145_940_000L, s)
    }

    @Test
    fun une_consigne_impossible_ne_pose_que_la_descente() {
        // Both frequencies on one band: exactly what the radio cannot do. Set
        // what lets us hear and leave the uplink alone rather than write it
        // anywhere.
        val e = BandPlan.steps(rs44Dl, rs44Ul, 435_800_000L, 435_250_000L)
        assertEquals(1, e.size)
        assertTrue(!e[0].sub)
        assertEquals(435_800_000L, e[0].hz)
    }

    @Test
    fun le_garage_evite_toujours_les_deux_bandes_visees() {
        assertEquals(Band.L, BandPlan.band(BandPlan.garageHz(Band.V, Band.U)))
        assertEquals(Band.U, BandPlan.band(BandPlan.garageHz(Band.V, Band.L)))
        assertEquals(Band.V, BandPlan.band(BandPlan.garageHz(Band.U, Band.L)))
    }
}
