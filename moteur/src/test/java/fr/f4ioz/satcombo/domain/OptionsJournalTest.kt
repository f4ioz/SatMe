/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import fr.f4ioz.satcombo.domain.OptionsJournal.Element
import fr.f4ioz.satcombo.domain.OptionsJournal.Reglage
import fr.f4ioz.satcombo.sstv.Planche
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The journal's choices kept with the pass: read back as written, an older pass read as before, an older SatMe unharmed. */
class OptionsJournalTest {

    private val passage = JournalPassage.Entree(25544, "ISS", 1_790_000_000_000L, 1_790_000_600_000L, "JN18FS",
        points = listOf(JournalPassage.Point(1_790_000_000_000L, 210.0, 2.0), JournalPassage.Point(1_790_000_300_000L, 120.0, 61.0),
            JournalPassage.Point(1_790_000_600_000L, 40.0, 1.5)))

    private val choix = OptionsJournal(
        version = Planche.Version.AUTO,
        versions = mapOf("sstv_20260722_101530.png" to Planche.Version.FUSION, "un=nom avec espaces.png" to Planche.Version.RECUE),
        marqueTraitees = true,
        elements = mapOf(
            Element.CIEL to Reglage(incruste = true, cadre = CadreSstv(0.7f, 0.5f, 0.25f)),
            Element.MONTAGE to Reglage(carte = true),
            Element.LOGO to Reglage(incruste = true, carte = true, cadre = CadreSstv(0.9f, 0.02f, 0.06f))),
        plan = "p2")

    @Test fun les_choix_relus_tels_quecrits() {
        val e = passage.copy(options = choix)
        val relu = JournalPassage.lit(JournalPassage.ecrit(e))!!
        assertEquals(choix, relu.options)
        // The rest of the pass as before.
        assertEquals(e.copy(options = OptionsJournal.DEFAUT), relu.copy(options = OptionsJournal.DEFAUT))
        // The logo's least width is its own (not the SSTV picture's 15 %).
        assertEquals(0.06f, relu.options.cadre(Element.LOGO).l, 1e-4f)
    }

    @Test fun un_passage_d_avant_se_lit_comme_avant() {
        // A pass written before these choices existed: nothing to read, the pictures as received, no panel.
        val ancien = "passage=1\ncatnum=25544\nsat=ISS\ndebut=1790000000000\nfin=1790000600000\nlocator=JN18FS\nprofil=\ntranspondeur=\n" +
            "masquee=sstv_1.png\np=1790000000000\t210.00\t2.00\t\t\t\t\n"
        val e = JournalPassage.lit(ancien)!!
        assertEquals(OptionsJournal.DEFAUT, e.options)
        assertTrue(e.options.vide)
        assertEquals(Planche.Version.RECUE, e.options.versionDe("sstv_1.png"))
        assertTrue(e.options.incrustes.isEmpty() && e.options.cartesFin.isEmpty() && e.options.cartesOuverture.isEmpty())
        // And written again: not a line more than before.
        val texte = JournalPassage.ecrit(passage)
        for (k in listOf("sstv_version=", "sstv_image=", "sstv_marque=", "element=", "plan=")) assertFalse(k, texte.contains("\n$k"))
    }

    @Test fun des_lignes_inconnues_ou_abimees_sont_ignorees() {
        var o = OptionsJournal.DEFAUT
        o = OptionsJournal.lit(o, "sstv_version", "nimportequoi")
        o = OptionsJournal.lit(o, "sstv_image", "sans_tabulation.png")
        o = OptionsJournal.lit(o, "element", "inconnu\ti\t0.1,0.1,0.3")
        o = OptionsJournal.lit(o, "element", "ciel\ti\tx,y")
        assertEquals(Planche.Version.RECUE, o.version)
        assertTrue(o.versions.isEmpty())
        assertEquals(setOf(Element.CIEL), o.elements.keys)
        // Unreadable place: its own.
        assertNull(o.reglage(Element.CIEL).cadre)
        assertEquals(Element.CIEL.defaut, o.cadre(Element.CIEL))
    }

    @Test fun deux_morceaux_du_passage_gardent_les_choix() {
        val a = passage.copy(options = choix)
        val b = passage.copy(debutMs = passage.debutMs + 60_000L)
        assertEquals(choix, JournalPassage.fusionne(b, a).options)
        assertEquals(choix, JournalPassage.fusionne(a, b).options)
    }

    @Test fun le_rangement_garde_les_choix() {
        val d = File(System.getProperty("java.io.tmpdir"), "satme-options-" + System.nanoTime())
        try {
            val r = JournalPassage.Rangement(d)
            r.enregistre(passage)
            r.enregistre(passage.copy(options = choix))
            assertEquals(choix, r.passages().single().options)
        } finally { d.deleteRecursively() }
    }

    @Test fun cartes_et_incrustations_dans_leur_ordre() {
        assertEquals(listOf(Element.CIEL, Element.LOGO), choix.incrustes)
        assertEquals(listOf(Element.MONTAGE), choix.cartesOuverture)
        assertEquals(listOf(Element.LOGO), choix.cartesFin)
        assertEquals(Planche.Version.FUSION, choix.versionDe("sstv_20260722_101530.png"))
        assertEquals(Planche.Version.AUTO, choix.versionDe("autre.png"))
    }
}
