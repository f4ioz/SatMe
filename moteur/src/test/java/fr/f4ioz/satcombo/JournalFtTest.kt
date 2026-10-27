/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.JournalFt
import fr.f4ioz.satcombo.domain.JournalPassage
import fr.f4ioz.satcombo.domain.OptionsJournal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The FT8/FT4 messages kept with a pass, and their panel's choices. */
class JournalFtTest {

    private val t0 = 1_791_800_000_000L / 15_000L * 15_000L

    @Test
    fun une_ligne_ecrite_se_relit_telle_quelle() {
        val d = JournalFt.Decode(t0, "FT4", -12, 0.3, 1234, "F4IOZ EA4XYZ IM79", "EA4XYZ", "F4IOZ", "IM79", ap = true, catnum = 44909)
        assertEquals(d, JournalFt.lit(JournalFt.ecrit(d)))
        // A tab or a line break inside a field never breaks the line.
        val e = d.copy(message = "CQ\tF4IOZ\nJN18", appelant = null, appele = null, locator = null, ap = false, catnum = 0)
        val lu = JournalFt.lit(JournalFt.ecrit(e))!!
        assertEquals("CQ F4IOZ JN18", lu.message)
        // What is not said is read from the message itself.
        assertEquals("F4IOZ", lu.appelant); assertEquals("JN18", lu.locator); assertNull(lu.appele)
        assertNull(JournalFt.lit("# comment")); assertNull(JournalFt.lit("12\tFT8")); assertNull(JournalFt.lit(""))
        assertEquals(2, JournalFt.litTout("# head\n" + JournalFt.ecritTout(listOf(d, e))).size)
    }

    @Test
    fun un_message_dit_qui_appelle_qui() {
        assertEquals(Triple("F4IOZ", null, "JN18"), JournalFt.analyse("CQ F4IOZ JN18"))
        assertEquals(Triple("F4IOZ", null, "JN18"), JournalFt.analyse("CQ DX F4IOZ JN18"))
        assertEquals(Triple("EA4XYZ", "F4IOZ", "IM79"), JournalFt.analyse("F4IOZ EA4XYZ IM79"))
        assertEquals(Triple("EA4XYZ", "F4IOZ", null), JournalFt.analyse("F4IOZ EA4XYZ RR73"))
        assertEquals(Triple("EA4XYZ", "F4IOZ", null), JournalFt.analyse("<F4IOZ> EA4XYZ -12"))
    }

    @Test
    fun ceux_du_passage_mes_appels_et_les_contacts_ressortent() {
        val a = JournalFt.Decode(t0, "FT8", -5, 0.1, 900, "CQ EA4XYZ IM79", "EA4XYZ", null, "IM79", catnum = 44909)
        val b = JournalFt.Decode(t0 + 15_000L, "FT8", -9, 0.2, 1500, "F4IOZ/P DL3AB JO62", "DL3AB", "F4IOZ/P", "JO62", catnum = 0)
        val c = JournalFt.Decode(t0 + 30_000L, "FT8", -1, 0.0, 700, "CQ G4ABC IO91", "G4ABC", null, "IO91", catnum = 43700)
        val d = JournalFt.Decode(t0 + 3_600_000L, "FT8", -1, 0.0, 700, "CQ G4ABC IO91", "G4ABC", null, "IO91", catnum = 44909)
        // Its window, its satellite (or none said): not QO-100's at the same time, not an hour later.
        assertEquals(listOf(a, b), JournalFt.dans(listOf(d, c, b, a), t0 - 60_000L, t0 + 600_000L, 44909))
        assertTrue(JournalFt.estCq(a)); assertFalse(JournalFt.estCq(b))
        assertTrue(JournalFt.pourMoi(b, "F4IOZ")); assertFalse(JournalFt.pourMoi(a, "F4IOZ")); assertFalse(JournalFt.pourMoi(a, ""))
        assertTrue(JournalFt.avecQso(a, setOf("EA4XYZ/P"))); assertFalse(JournalFt.avecQso(b, setOf("EA4XYZ")))
        // The same message of a slot once, a live one kept over a re-reading.
        assertEquals(3, JournalFt.fusionne(listOf(a, b), listOf(a.copy(db = -7), c)).size)
        assertEquals(-5, JournalFt.fusionne(listOf(a), listOf(a.copy(db = -7))).single().db)
    }

    @Test
    fun le_panneau_montre_les_tranches_finies_les_plus_recentes_ou_l_on_veut() {
        val l = (0 until 10).map { JournalFt.Decode(t0 + it * 7_500L, "FT4", -it, 0.0, 1000, "CQ QF$it ABC JN18") }
        // At 30 s: the slots over (0, 7.5, 15, 22.5 s), the 3 newest.
        val bas = JournalFt.visibles(l, t0 + 30_000L, 3, recentEnHaut = false)
        assertEquals(listOf(1, 2, 3), bas.map { -it.db })
        assertEquals(listOf(3, 2, 1), JournalFt.visibles(l, t0 + 30_000L, 3, recentEnHaut = true).map { -it.db })
        assertTrue(JournalFt.visibles(l, t0 + 7_000L, 3, false).isEmpty())
        assertTrue(JournalFt.visibles(l, t0 + 60_000L, 0, false).isEmpty())
        // The font follows the width; the rows the height.
        val m = JournalFt.mise(340f, 200f)
        assertEquals(16.5f, m.police, 0.01f)
        assertEquals(((200f - 2 * m.marge - m.titre) / m.ligne).toInt(), m.lignes)
        assertTrue(JournalFt.mise(680f, 200f).police > m.police)
    }

    @Test
    fun les_choix_du_panneau_se_lisent_et_restent_avec_le_passage() {
        val a = JournalFt.Affichage(affiche = false, cadre = JournalFt.Cadre(0.1f, 0.2f, 0.4f, 0.5f), opacite = 40, recentEnHaut = true)
        assertEquals(a, JournalFt.Affichage.lit(a.texte()))
        assertEquals(JournalFt.Affichage.DEFAUT, JournalFt.Affichage.lit(""))
        // From the page's words: each one sent changes it, the rest stays; « auto »: its place by default.
        val p = mapOf("ft" to "1", "ftCadre" to "0.3,0.1,2,0.01", "ftOpacite" to "130", "ftOrdre" to "bas")
        val b = JournalFt.Affichage.depuis({ p[it].orEmpty() }, a)
        assertTrue(b.affiche); assertEquals(100, b.opacite); assertFalse(b.recentEnHaut)
        assertEquals(JournalFt.Cadre(0.3f, 0.1f, 1f, 0.08f), b.cadre)
        assertEquals(a, JournalFt.Affichage.depuis({ "" }, a))
        assertNull(JournalFt.Affichage.depuis({ if (it == "ftCadre") "auto" else "" }, a).cadre)
        // In pixels: inside the view, whatever the shares.
        val pl = JournalFt.Cadre(0.9f, 0.9f, 0.4f, 0.5f).place(1000f, 500f)
        assertEquals(600f, pl.x, 0.01f); assertEquals(250f, pl.y, 0.01f); assertEquals(400f, pl.l, 0.01f); assertEquals(250f, pl.h, 0.01f)
        // Kept in the pass's file and read back; an untouched panel writes nothing.
        val e = JournalPassage.Entree(44909, "RS-44", t0, t0 + 600_000L, "JN15MS", options = OptionsJournal(ft = a),
            points = listOf(JournalPassage.Point(t0, 10.0, 5.0)))
        val texte = JournalPassage.ecrit(e)
        assertTrue(texte.contains("ft=0\t0.1000,0.2000,0.4000,0.5000\t40\thaut"))
        assertEquals(a, JournalPassage.lit(texte)!!.options.ft)
        assertFalse(JournalPassage.ecrit(e.copy(options = OptionsJournal())).contains("ft="))
    }
}
