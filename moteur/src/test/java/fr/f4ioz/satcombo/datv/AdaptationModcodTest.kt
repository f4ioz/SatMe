/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv

import fr.f4ioz.satcombo.datv.AdaptationModcod.Mesure
import fr.f4ioz.satcombo.datv.s2.Modcod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The MODCOD's adaptation on simulated measures, second by second. */
class AdaptationModcodTest {

    private fun mc(s: String) = Modcod.de(s, false, true)!!
    private val min = mc("QPSK 1/4")
    private val max = mc("8PSK 3/4")

    /** The adaptation driven second by second, the MODCOD followed as the transmitter would. */
    private inner class Banc(var courant: Modcod, val a: AdaptationModcod = AdaptationModcod(), var qo100: Boolean = false) {
        var t = 1_000_000L
        val changes = ArrayList<String>()
        fun seconde(m: Mesure, n: Int = 1) = repeat(n) {
            t += 1000
            a.tic(courant, min, max, 2.0, m, t, qo100)?.let { courant = it; changes += AdaptationModcod.nom(it) }
        }
        val nom get() = AdaptationModcod.nom(courant)
    }

    private fun mer(db: Double, pertes: Boolean = false) = Mesure(true, db, pertes)

    @Test
    fun la_plage_par_seuils_du_plus_robuste_au_plus_efficace() {
        val p = AdaptationModcod.plage(mc("QPSK 1/2"), min, max).map { AdaptationModcod.nom(it) }
        assertEquals("QPSK 1/4", p.first())
        assertEquals("8PSK 3/4", p.last())
        assertTrue("8PSK 5/6" !in p)
        // Reversed bounds are the same range.
        assertEquals(p, AdaptationModcod.plage(mc("QPSK 1/2"), max, min).map { AdaptationModcod.nom(it) })
        // Efficiency rises along the list.
        val l = AdaptationModcod.plage(mc("QPSK 1/2"), min, max)
        for (k in 1 until l.size) assertTrue(AdaptationModcod.efficacite(l[k]) >= AdaptationModcod.efficacite(l[k - 1]))
    }

    @Test
    fun montee_lente_seulement_apres_20_s_de_marge() {
        val b = Banc(mc("QPSK 1/2"))
        // MER 9 dB: QPSK 1/2 needs 1.0, margin 8 dB. The best with 2 dB kept: 8PSK 2/3 (6.62).
        b.seconde(mer(9.0), 19)
        assertTrue(b.changes.isEmpty())
        assertEquals("8PSK 2/3", b.a.etat.cible)
        assertTrue(b.a.etat.prochaine, b.a.etat.prochaine.startsWith("montée vers 8PSK 2/3"))
        b.seconde(mer(9.0), 2)
        assertEquals(listOf("8PSK 2/3"), b.changes)
        assertEquals("8PSK 2/3", b.a.historique().single().vers)
        assertTrue(b.a.historique().single().raison.startsWith("marge"))
        // A dip under the target + hysteresis restarts the 20 s.
        val c = Banc(mc("QPSK 1/2"))
        c.seconde(mer(9.0), 15)
        c.seconde(mer(4.0))   // margin 3.0 < 2 + 1.5
        c.seconde(mer(9.0), 15)
        assertTrue(c.changes.isEmpty())
        c.seconde(mer(9.0), 6)
        assertEquals(1, c.changes.size)
    }

    @Test
    fun descente_rapide_sur_marge_faible_ou_pertes() {
        val b = Banc(mc("8PSK 2/3"))
        // MER 6.8: margin 0.2 dB under 0.5. Two seconds and down to what 2 dB of margin allows (MER − 2 = 4.8: QPSK 4/5).
        b.seconde(mer(6.8))
        assertTrue(b.changes.isEmpty())
        assertTrue(b.a.etat.prochaine, b.a.etat.prochaine.contains("si cela dure"))
        b.seconde(mer(6.8))
        assertEquals(listOf("QPSK 4/5"), b.changes)
        // Then nothing while the receiver relocks.
        b.seconde(mer(2.0), 7)
        assertEquals(1, b.changes.size)
        assertTrue(b.a.etat.prochaine.startsWith("attente"))
        // Losses with a good margin: one step more robust at least.
        val c = Banc(mc("QPSK 2/3"))
        c.seconde(mer(12.0, pertes = true), 2)
        assertEquals(1, c.changes.size)
        assertTrue(AdaptationModcod.efficacite(c.courant) < AdaptationModcod.efficacite(mc("QPSK 2/3")))
        assertTrue(c.a.historique().last().raison.startsWith("paquets perdus"))
    }

    @Test
    fun hysteresis_pas_de_va_et_vient() {
        val b = Banc(mc("QPSK 1/2"))
        // Margin between the low mark and target + 1.5: held, whatever the time.
        b.seconde(mer(1.0 + 3.0), 120)
        assertTrue(b.changes.isEmpty())
        assertTrue(b.a.etat.prochaine.startsWith("maintien"))
        // Losses at QPSK 3/4 with margin: down, and 3/4 barred for two minutes even with margin.
        val c = Banc(mc("QPSK 3/4"))
        c.seconde(mer(9.0, pertes = true), 2)
        val apres = c.courant
        c.seconde(mer(9.0), 60)
        assertEquals(1, c.changes.size)
        assertEquals(apres, c.courant)
        c.seconde(mer(9.0), 90)
        assertEquals(2, c.changes.size)
    }

    @Test
    fun bornes_de_la_plage() {
        // Already the most robust: nothing lower, said so.
        val b = Banc(mc("QPSK 1/4"))
        b.seconde(mer(-3.0), 5)
        assertTrue(b.changes.isEmpty())
        assertTrue(b.a.etat.prochaine.contains("plus robuste autorisé"))
        // A huge margin: never above the range's top.
        val c = Banc(mc("QPSK 1/2"))
        c.seconde(mer(25.0), 120)
        assertEquals("8PSK 3/4", c.nom)
        assertEquals(1, c.changes.size)
        assertTrue(c.a.etat.prochaine.contains("le plus efficace autorisé"))
    }

    @Test
    fun boucle_non_confirmee_ou_perdue() {
        // Never seen: nothing moves, said why.
        val b = Banc(mc("QPSK 2/3"))
        b.seconde(Mesure(false, 9.0), 60)
        assertTrue(b.changes.isEmpty())
        assertTrue(b.a.etat.prochaine.startsWith("boucle non confirmée"))
        assertTrue(!b.a.etat.boucle)
        // Seen, then lost: after 10 s the most robust, then frozen.
        b.seconde(mer(5.0), 3)
        b.seconde(Mesure(false, null), 10)
        assertTrue(b.changes.isEmpty())
        b.seconde(Mesure(false, null), 1)
        assertEquals(listOf("QPSK 1/4"), b.changes)
        assertTrue(b.a.etat.fige)
        b.seconde(Mesure(false, null), 60)
        assertEquals(1, b.changes.size)
        assertTrue(b.a.historique().last().raison.startsWith("boucle perdue"))
        // Back: the adaptation resumes (up after its 20 s).
        b.seconde(mer(9.0), 30)
        assertTrue(!b.a.etat.fige)
        assertEquals(2, b.changes.size)
    }

    @Test
    fun puissance_suggeree_jamais_imposee() {
        val b = Banc(mc("8PSK 3/4"), qo100 = true)
        // 8PSK 3/4 needs 7.91: MER 17 → margin 9.1, above 2 + 6.
        b.seconde(mer(17.0), 5)
        assertTrue(b.a.etat.suggestion, b.a.etat.suggestion.contains("puissance pourrait baisser"))
        assertTrue(b.changes.isEmpty())
        // Not on the indoor test, nor below the best MODCOD.
        val c = Banc(mc("8PSK 3/4"), qo100 = false)
        c.seconde(mer(17.0), 5)
        assertEquals("", c.a.etat.suggestion)
        val d = Banc(mc("QPSK 1/2"), qo100 = true)
        d.seconde(mer(17.0), 5)
        assertEquals("", d.a.etat.suggestion)
    }

    @Test
    fun trames_courtes_seuils_un_peu_plus_hauts() {
        val n = AdaptationModcod.seuil(Modcod.de("QPSK 1/2", false, false)!!)!!
        val c = AdaptationModcod.seuil(Modcod.de("QPSK 1/2", true, false)!!)!!
        assertEquals(QualiteDatv.COURTES_DB, c - n, 1e-9)
        // 9/10 does not exist in short frames: not in the range.
        assertTrue(AdaptationModcod.plage(Modcod.de("QPSK 1/2", true, false)!!, min, mc("QPSK 9/10")).none { it.taux.libelle == "9/10" })
    }
}
