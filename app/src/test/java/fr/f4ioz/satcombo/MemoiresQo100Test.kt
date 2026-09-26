/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.MemoiresQo100
import fr.f4ioz.satcombo.domain.MemoiresQo100.Memoire
import fr.f4ioz.satcombo.domain.Qo100
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Frequency shortcuts for the narrowband transponder. */
class MemoiresQo100Test {

    @Test
    fun les_reperes_couvrent_les_balises() {
        val cles = MemoiresQo100.reperes().map { it.cle }
        listOf("balise_basse", "balise_mediane", "balise_haute").forEach {
            assertTrue(it, it in cles)
        }
    }

    @Test
    fun la_balise_mediane_pointe_sur_sa_frequence() {
        val m = MemoiresQo100.reperes().first { it.cle == "balise_mediane" }
        assertEquals(Qo100.BALISE_MEDIANE_HZ, m.hz)
    }

    /**
     * Every marker lies between the two CW beacons, inclusive.
     *
     * Not "inside the transponder": the beacons frame the usable band rather
     * than sit in it (lower one 5 kHz below the first segment, upper one 3 kHz
     * above the last). That is what makes them bounds.
     */
    @Test
    fun aucun_repere_ne_sort_des_balises() {
        MemoiresQo100.reperes().forEach { m ->
            assertTrue("${m.cle} hors bande : ${m.hz}",
                m.hz >= Qo100.BALISE_BASSE_HZ && m.hz <= 10_490_000_000L)
        }
    }

    /** Operating segments give a marker a little past their edge. */
    @Test
    fun les_segments_de_travail_donnent_un_repere() {
        val cles = MemoiresQo100.reperes().map { it.cle }
        listOf("cw", "num_etroit", "ssb_bas", "mixte").forEach {
            assertTrue(it, it in cles)
        }
    }

    @Test
    fun les_reperes_sont_tous_marques_fixes() {
        assertTrue(MemoiresQo100.reperes().all { it.fixe })
    }

    @Test
    fun une_memoire_posee_se_range_par_frequence() {
        var m = emptyList<Memoire>()
        m = MemoiresQo100.pose(m, 10_489_800_000L, "copains")
        m = MemoiresQo100.pose(m, 10_489_600_000L, "réseau")
        assertEquals(listOf("réseau", "copains"), m.map { it.nom })
    }

    /**
     * Two memories less than 1 kHz apart are the same one: on 492 kHz with
     * 2.7 kHz signals, shortcuts 300 Hz apart are indistinguishable in use.
     */
    @Test
    fun poser_tout_pres_remplace_au_lieu_d_ajouter() {
        var m = MemoiresQo100.pose(emptyList(), 10_489_800_000L, "ancien")
        m = MemoiresQo100.pose(m, 10_489_800_300L, "nouveau")
        assertEquals(1, m.size)
        assertEquals("nouveau", m.first().nom)
    }

    @Test
    fun poser_plus_loin_ajoute_bien() {
        var m = MemoiresQo100.pose(emptyList(), 10_489_800_000L, "a")
        m = MemoiresQo100.pose(m, 10_489_805_000L, "b")
        assertEquals(2, m.size)
    }

    @Test
    fun sans_nom_la_memoire_prend_ses_kilohertz() {
        val m = MemoiresQo100.pose(emptyList(), 10_489_688_000L, "  ")
        assertEquals(".688", m.first().nom)
    }

    @Test
    fun une_memoire_se_retire() {
        var m = MemoiresQo100.pose(emptyList(), 10_489_800_000L, "x")
        m = MemoiresQo100.retire(m, 10_489_800_000L)
        assertTrue(m.isEmpty())
    }

    /** Fixed markers first, user memories after. */
    @Test
    fun les_reperes_precedent_les_memoires_posees(): Unit {
        val posees = listOf(Memoire("", 10_489_510_000L, fixe = false, nom = "tôt"))
        val toutes = MemoiresQo100.toutes(posees)
        assertTrue(toutes.first().fixe)
        assertEquals("tôt", toutes.last().nom)
    }
}
