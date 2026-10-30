/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The PTT output around the RF: active, delay, RF; RF off, delay, at rest. */
class SequenceurPttTest {

    /** A clock moved only by the waits, and what happened at which time. */
    private class Banc(var refuse: String? = null) {
        var t = 0L
        val journal = ArrayList<String>()
        val differes = ArrayList<() -> Unit>()
        val sortie = object : SortiePtt {
            override val nom = "relais"
            override fun pose(actif: Boolean): String? {
                journal += (if (actif) "active" else "repos") + "@" + t
                return if (actif) refuse else null
            }
        }
        fun rf(on: Boolean) { journal += (if (on) "hf" else "hf-off") + "@" + t }
    }

    private fun sequenceur(b: Banc, avant: Long = 300, apres: Long = 500, sortie: () -> SortiePtt = { b.sortie }, enDiffere: Boolean = false) =
        SequenceurPtt(sortie, { avant }, { apres }, attente = { b.t += it }, differe = { f -> if (enDiffere) b.differes += f else f() })

    @Test
    fun relais_puis_hf_puis_hf_coupee_puis_relais() {
        val b = Banc()
        val s = sequenceur(b)
        assertNull(s.avantRf())
        b.rf(true)
        assertTrue(s.actif)
        b.t += 10_000
        b.rf(false)
        s.apresRf()
        assertFalse(s.actif)
        assertEquals(listOf("active@0", "hf@300", "hf-off@10300", "repos@10800"), b.journal)
    }

    @Test
    fun sortie_refusee_pas_de_hf_et_remise_au_repos() {
        val b = Banc(refuse = "port absent")
        val s = sequenceur(b)
        val r = s.avantRf()
        assertTrue(r!!.contains("port absent") && r.contains("relais"))
        assertFalse(s.actif)
        // At rest at once, nothing waited: the caller does not start the RF.
        assertEquals(listOf("active@0", "repos@0"), b.journal)
        assertEquals("port absent", s.erreur)
    }

    @Test
    fun aucune_sortie_rien_n_attend() {
        val b = Banc()
        val s = sequenceur(b, sortie = { SortiePtt.Aucune })
        assertNull(s.avantRf())
        s.apresRf()
        assertEquals(0L, b.t)
        assertFalse(s.actif)
    }

    @Test
    fun un_nouveau_depart_pendant_le_delai_apres_garde_la_sortie_active() {
        val b = Banc()
        val s = sequenceur(b, enDiffere = true)
        s.avantRf(); s.apresRf()
        // Started again before the rest came: the pending rest dropped.
        assertNull(s.avantRf())
        b.differes.forEach { it() }
        assertTrue(s.actif)
        assertFalse(b.journal.any { it.startsWith("repos") })
        s.apresRf(); val dernier: () -> Unit = b.differes[b.differes.size - 1]; dernier()
        assertFalse(s.actif)
        assertEquals("repos", b.journal.last().substringBefore('@'))
    }

    @Test
    fun relache_maintenant_sans_delai_et_delais_bornes() {
        val b = Banc()
        val s = sequenceur(b, avant = 60_000, apres = -5)
        s.avantRf()
        // The delay held to 5 s at most, never negative.
        assertEquals(SequenceurPtt.DELAI_MAX_MS, b.t)
        val t = b.t
        s.relacheMaintenant()
        assertEquals("repos@$t", b.journal.last())
        assertFalse(s.actif)
        assertEquals(0L, SequenceurPtt.borne(-1)); assertEquals(5_000L, SequenceurPtt.borne(9_999))
    }
}
