/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.cat.BandPlan
import fr.f4ioz.satcombo.cat.BandPlan.Band
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * « Problème de U/V V/U, ça ne switch pas. »
 *
 * La contrainte, telle qu'Olivier l'a écrite : *on ne peut pas être sur la même
 * bande en même temps sur VFO A et B*. Tout ce qui suit en découle, et le seul
 * essai qui compte vraiment est le dernier : passer d'un satellite en V/U à un
 * satellite en U/V, c'est-à-dire échanger les deux bandes, sans jamais les
 * réunir en chemin.
 */
class BandPlanTest {

    private val rs44Dl = 435_660_000L   // V/U : on écoute en 70 cm…
    private val rs44Ul = 145_940_000L   // …et l'on émet en 2 m.
    private val ao91Dl = 145_960_000L   // U/V : l'inverse, exactement.
    private val ao91Ul = 435_250_000L

    /** L'état du poste après avoir joué toutes les étapes, vérifié à chaque pas. */
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
        // La montée d'abord, la descente ensuite : on finit sur la réception,
        // molette utile sous la main. C'est le comportement de la 18.18, et il
        // ne doit pas changer tant qu'aucune bande n'est connue.
        val e = BandPlan.steps(null, null, rs44Dl, rs44Ul)
        assertEquals(2, e.size)
        assertTrue("la montée n'est pas écrite en premier", e[0].sub)
        assertEquals(rs44Ul, e[0].hz)
        assertEquals(rs44Dl, e[1].hz)
    }

    @Test
    fun le_doppler_d_un_meme_satellite_n_ajoute_aucune_etape() {
        // Cent tours de boucle sur RS-44 : les bandes ne bougent pas, donc rien
        // ne doit s'ajouter. Une étape de garage à chaque tour ferait trois
        // trames de plus dix fois par seconde sur le bus CI-V.
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
        // Le cas d'Olivier, dans les deux sens. Sans garage, la première
        // écriture posait 435 en face de 435 et le poste refusait : d'où les
        // deux fréquences en 435 dans le panneau POSTE.
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
        // Le poste écoute en 2 m et émet en 23 cm ; on veut écouter en 2 m et
        // émettre en 70 cm. La montée peut partir la première sans gêner
        // personne, et rien ne justifie un garage.
        val e = BandPlan.steps(145_960_000L, 1_296_000_000L, 145_960_000L, 435_250_000L)
        assertEquals(2, e.size)
        assertTrue(e[0].sub)

        // L'inverse : la montée vise le 2 m, où la descente se trouve encore ;
        // elle ne peut donc pas partir la première. Mais la descente, elle,
        // vise le 70 cm, que personne n'occupe — deux écritures suffisent, et
        // c'est la descente qui ouvre. (Si la montée occupait le 70 cm, ce
        // serait l'échange pur de l'essai précédent, et il faudrait un garage.)
        val f = BandPlan.steps(145_960_000L, 1_296_000_000L, 435_660_000L, 145_940_000L)
        assertEquals("un garage inutile a été ajouté : $f", 2, f.size)
        assertTrue("la descente aurait dû partir la première", !f[0].sub)
        val (m, s) = jouer(145_960_000L, 1_296_000_000L, 435_660_000L, 145_940_000L)
        assertEquals(435_660_000L, m)
        assertEquals(145_940_000L, s)
    }

    @Test
    fun une_consigne_impossible_ne_pose_que_la_descente() {
        // Deux fréquences sur la même bande : c'est précisément ce que le poste
        // ne sait pas faire. On pose ce qui permet d'entendre et l'on ne touche
        // pas à la montée, plutôt que de l'écrire n'importe où.
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
