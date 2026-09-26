/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.data

import fr.f4ioz.satcombo.data.AgendaStore.AgendaEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The agenda: serialisation and matching a time window against a pass.
 *
 * Two things matter. An agenda written by an older version must read back
 * losslessly — an update that wipes appointments gets installed only once.
 * And a multi-day window must match every pass it contains; that is the
 * whole point of windows.
 */
class AgendaStoreTest {

    private val H = 3_600_000L

    private fun ev(
        start: Long, end: Long = 0L, sat: String = "QMR-KWT-2",
        kind: String = "", freq: Long = 0L
    ) = AgendaEvent(
        id = 1L, title = "SSTV", timeMs = start, endMs = end,
        satName = sat, kind = kind, freqHz = freq)

    @Test
    fun un_agenda_ecrit_par_lancienne_version_se_relit_entier() {
        // Seven fields: exactly what the previous version wrote.
        val old = "17\t1000000\t60\t1\tSSTV ISS\tISS (ZARYA)\tsur 145.800"
        val list = AgendaStore.decode(old)
        assertEquals(1, list.size)
        val e = list[0]
        assertEquals(17L, e.id)
        assertEquals(1_000_000L, e.timeMs)
        assertEquals("SSTV ISS", e.title)
        assertEquals("ISS (ZARYA)", e.satName)
        assertEquals("sur 145.800", e.note)
        // No window and no frequency, since it never had any.
        assertFalse(e.isWindow)
        assertEquals(0L, e.freqHz)
        assertEquals("", e.kind)
    }

    @Test
    fun le_tour_complet_ecriture_relecture_ne_perd_rien() {
        val e = AgendaEvent(
            id = 42L, title = "SSTV anniversaire", timeMs = 100 * H,
            satName = "QMR-KWT-2", leadMin = 120, note = "RS95S",
            enabled = false, endMs = 137 * H, kind = "SSTV", freqHz = 436_950_000L)
        val back = AgendaStore.decode(AgendaStore.encode(listOf(e)))
        assertEquals(listOf(e), back)
    }

    @Test
    fun une_ligne_abimee_ne_fait_pas_perdre_les_autres() {
        val text = AgendaStore.encode(listOf(ev(10 * H))) + "\nn_importe_quoi\n" +
            AgendaStore.encode(listOf(ev(20 * H).copy(id = 2L)))
        assertEquals(2, AgendaStore.decode(text).size)
    }

    @Test
    fun un_creneau_reconnait_tous_les_passages_quil_contient() {
        // 1 Aug 06:30 → 2 Aug 19:30: 37 hours, dozens of passes. Each must be
        // marked, not only the first.
        val e = ev(start = 0L, end = 37 * H)
        assertTrue(e.covers(1 * H, 1 * H + 600_000L))
        assertTrue(e.covers(18 * H, 18 * H + 600_000L))
        assertTrue(e.covers(36 * H, 36 * H + 600_000L))
    }

    @Test
    fun un_passage_hors_du_creneau_nest_pas_marque() {
        val e = ev(start = 10 * H, end = 12 * H)
        assertFalse(e.covers(5 * H, 5 * H + 600_000L))
        assertFalse(e.covers(20 * H, 20 * H + 600_000L))
    }

    @Test
    fun un_passage_a_cheval_sur_le_debut_ou_la_fin_compte_quand_meme() {
        val e = ev(start = 10 * H, end = 12 * H)
        // Starts before the window, ends inside.
        assertTrue(e.covers(10 * H - 300_000L, 10 * H + 300_000L))
        // Starts inside, ends after.
        assertTrue(e.covers(12 * H - 300_000L, 12 * H + 300_000L))
    }

    @Test
    fun sans_creneau_le_rendez_vous_garde_lancien_comportement() {
        // A single instant, with five minutes of slack either side.
        val e = ev(start = 10 * H)
        assertTrue(e.covers(10 * H - 60_000L, 10 * H + 60_000L))
        assertTrue(e.covers(10 * H + 120_000L, 10 * H + 600_000L))   // appointment 2 min before AOS
        assertFalse(e.covers(10 * H + 20 * 60_000L, 10 * H + 30 * 60_000L))
        assertEquals(e.timeMs, e.endOrStartMs)
    }

    @Test
    fun une_fin_anterieure_au_debut_est_traitee_comme_une_absence_de_fin() {
        // The screen cannot produce this, but files get hand-edited.
        val e = ev(start = 10 * H, end = 5 * H)
        assertFalse(e.isWindow)
        assertEquals(10 * H, e.endOrStartMs)
    }

    @Test
    fun le_recouvrement_avec_un_filtre_de_dates_se_lit_dans_les_deux_sens() {
        val e = ev(start = 10 * H, end = 12 * H)
        assertTrue(e.overlaps(0L, 11 * H))        // filter ends inside the window
        assertTrue(e.overlaps(11 * H, 40 * H))    // filter starts inside
        assertTrue(e.overlaps(0L, 40 * H))        // filter contains it
        assertFalse(e.overlaps(20 * H, 30 * H))
    }

    @Test
    fun le_rappel_sonne_avant_le_debut_et_non_avant_la_fin() {
        val e = ev(start = 100 * H, end = 137 * H).copy(leadMin = 60)
        assertEquals(99 * H, e.alertMs)
    }

    @Test
    fun la_frequence_ne_sannonce_que_lorsquelle_existe() {
        assertNull(ev(0L).freqMhz)
        assertEquals(436.950, ev(0L, freq = 436_950_000L).freqMhz!!, 1e-6)
    }

    @Test
    fun le_genre_est_range_en_majuscules_quoi_quon_tape() {
        val back = AgendaStore.decode(AgendaStore.encode(listOf(ev(0L, kind = "sstv"))))
        assertEquals("SSTV", back[0].kind)
    }

    @Test
    fun les_genres_proposes_commencent_par_le_choix_vide() {
        assertEquals("", AgendaStore.KINDS.first())
        assertTrue("SSTV" in AgendaStore.KINDS)
        assertEquals(AgendaStore.KINDS.size, AgendaStore.KINDS.distinct().size)
    }

    @Test
    fun une_tabulation_tapee_dans_un_champ_ne_casse_pas_la_ligne() {
        val e = ev(0L).copy(title = "SSTV\tanniversaire", note = "deux\tjours")
        val back = AgendaStore.decode(AgendaStore.encode(listOf(e)))
        assertEquals(1, back.size)
        assertEquals("SSTV anniversaire", back[0].title)
        assertEquals("deux jours", back[0].note)
    }

    @Test
    fun un_agenda_vide_se_relit_vide_sans_rien_inventer() {
        assertTrue(AgendaStore.decode(null).isEmpty())
        assertTrue(AgendaStore.decode("").isEmpty())
        assertTrue(AgendaStore.decode("   \n  \n").isEmpty())
    }
}
