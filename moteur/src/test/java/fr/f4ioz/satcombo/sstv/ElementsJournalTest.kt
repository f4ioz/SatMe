/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

import fr.f4ioz.satcombo.domain.CadreSstv
import fr.f4ioz.satcombo.domain.JournalPassage
import fr.f4ioz.satcombo.domain.OptionsJournal
import fr.f4ioz.satcombo.domain.OptionsJournal.Element
import fr.f4ioz.satcombo.domain.OptionsJournal.Reglage
import fr.f4ioz.satcombo.i18n.I18n
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The report's panels in the journal's pictures and videos: drawn the same
 * twice, nothing from outside, each where it was put in its own shape, a
 * panel with nothing to draw left out — and the phone's simple chain where
 * there is no montage.
 */
class ElementsJournalTest {

    @Before fun langue() { I18n.apply("fr", "fr") }

    private val rapport = RapportEssai.rapport(montage = true)
    /** The pass of the report that brought slides 1, 2 and 3. */
    private val passe = rapport.passages.first { it.recues.containsAll(listOf(1, 2, 3)) }
    private val donnees = ElementsJournal.Donnees(rapport, passe, 10.0, rapport.montage, planche = rapport.planche,
        nomPlanche = rapport.nomPlanche, logo = RapportEssai.logo())

    /** A canvas that keeps what is drawn: the boxes and the texts. */
    private class Releve : RapportPdf.Toile, RapportPdf.Mesure {
        val rects = ArrayList<FloatArray>(); val textes = ArrayList<String>(); val images = ArrayList<FloatArray>(); var traits = 0
        override fun largeur(s: String, taille: Float, gras: Boolean) = s.length * taille * 0.55f
        override fun taille(image: ByteArray) = PlancheFichier.dimensions(image)
        override fun texte(s: String, x: Float, y: Float, taille: Float, couleur: Int, gras: Boolean) { textes += s }
        override fun ligne(xy: FloatArray, couleur: Int?, epaisseur: Float, pointilles: FloatArray?, rond: Boolean, ferme: Boolean, fond: Int?) { traits++ }
        override fun rect(x: Float, y: Float, w: Float, h: Float, fond: Int?, trait: Int?, epaisseur: Float, rayon: Float, pointilles: FloatArray?) {
            rects += floatArrayOf(x, y, w, h)
        }
        override fun cercle(x: Float, y: Float, r: Float, fond: Int?, trait: Int?, epaisseur: Float) { traits++ }
        override fun image(octets: ByteArray, x: Float, y: Float, w: Float, h: Float) { images += floatArrayOf(x, y, w, h) }
    }

    @Test fun chaque_panneau_dessine_le_meme_deux_fois_sans_rien_dehors() {
        for (k in Element.values()) {
            assertTrue(k.name, ElementsJournal.disponible(k, donnees))
            val a = ElementsJournal.svg(k, donnees)!!
            val b = ElementsJournal.svg(k, donnees)!!
            assertEquals(k.name, a, b)
            assertTrue(a.startsWith("<svg"))
            // Nothing fetched: the only address is SVG's own namespace, the pictures inside.
            assertEquals(k.name, 1, Regex("https?://").findAll(a).count())
            assertFalse(k.name, a.contains("NaN") || a.contains("Infinity"))
        }
        // The figures say this pass's slides, the series' and the best mark.
        val s = ElementsJournal.svg(Element.STATS, donnees)!!
        val c = ElementsJournal.chiffres(donnees)!!
        assertTrue(c.recues >= 3)
        assertTrue(s.contains(">${c.recues}/${c.prevues}<"))
        assertTrue(s.contains(">${rapport.stats.diapos}/12<"))
        assertEquals(passe.images.maxOf { it.note }, c.meilleure)
        // The sky: its title says the pass; the montage: the plan's name.
        assertTrue(ElementsJournal.svg(Element.CIEL, donnees)!!.contains(RapportEvenement.hm(passe.aosMs)))
        assertTrue(ElementsJournal.svg(Element.MONTAGE, donnees)!!.contains("IC-9700"))
    }

    @Test fun la_grille_met_en_avant_les_diapos_du_passage() {
        val r = Releve()
        ElementsJournal.dessine(r, r, Element.DIAPOS, donnees, 0f, 0f, 600f, 600f / ElementsJournal.ratio(Element.DIAPOS, donnees, r))
        // Every slide of the series numbered, those of this pass framed once more.
        for (n in 1..12) assertTrue("$n", r.textes.contains("$n/12") || r.textes.contains("≈$n/12"))
        val autre = Releve()
        ElementsJournal.dessine(autre, autre, Element.DIAPOS, ElementsJournal.Donnees(rapport, null, 10.0), 0f, 0f, 600f, 600f)
        assertEquals(passe.recues.size, r.rects.size - autre.rects.size)
    }

    @Test fun un_panneau_sans_rien_a_dessiner_nest_pas_propose() {
        val vide = ElementsJournal.Donnees()
        for (k in Element.values()) {
            assertFalse(k.name, ElementsJournal.disponible(k, vide))
            assertNull(ElementsJournal.svg(k, vide))
        }
        val o = OptionsJournal(elements = Element.values().associateWith { Reglage(incruste = true) })
        assertTrue(ElementsJournal.incrustations(o, vide, 1280f, 620f, Releve()).isEmpty())
    }

    @Test fun le_telephone_sans_montage_dessine_la_chaine_simple() {
        val d = ElementsJournal.Donnees(chaine = rapport.chaine, nomChaine = rapport.nomChaine)
        assertTrue(ElementsJournal.disponible(Element.MONTAGE, d))
        val svg = ElementsJournal.svg(Element.MONTAGE, d)!!
        assertTrue(svg.contains("IC-9700") && svg.contains("SatMe") && svg.contains("Carte USB du poste"))
    }

    @Test fun limage_de_la_video_porte_les_panneaux_choisis_a_leur_place() {
        val o = OptionsJournal(elements = mapOf(
            Element.CIEL to Reglage(incruste = true, cadre = CadreSstv(0.6f, 0.4f, 0.2f)),
            Element.STATS to Reglage(incruste = true),
            Element.LOGO to Reglage(incruste = true, cadre = CadreSstv(0.95f, 0.95f, 0.07f)),
            // A card only: not over the replay.
            Element.MONTAGE to Reglage(carte = true)))
        val w = 1280f; val h = 620f
        val r = Releve()
        val l = ElementsJournal.dessineIncrustations(r, r, o, donnees, w, h, ox = 0f, oy = 0f)
        assertEquals(listOf(Element.CIEL, Element.STATS, Element.LOGO), l.map { it.k })
        val ciel = l[0].place
        // Where the mouse put it, its own shape kept.
        assertEquals(0.6f * w, ciel.x, 0.5f); assertEquals(0.4f * h, ciel.y, 0.5f); assertEquals(0.2f * w, ciel.l, 0.5f)
        assertEquals(ElementsJournal.ratio(Element.CIEL, donnees, r), ciel.l / ciel.h, 0.01f)
        // Its card drawn there.
        assertTrue(r.rects.any { kotlin.math.abs(it[0] - ciel.x) < 1f && kotlin.math.abs(it[1] - ciel.y) < 1f && kotlin.math.abs(it[2] - ciel.l) < 1f })
        // Not moved: its own place.
        val stats = l[1].place
        assertEquals(Element.STATS.defaut.place(w, h, ElementsJournal.ratio(Element.STATS, donnees, r), Element.STATS.min), stats)
        // The logo kept inside the view, at its own small width.
        val logo = l[2].place
        assertTrue(logo.x + logo.l <= w + 0.01f && logo.y + logo.h <= h + 0.01f)
        assertEquals(0.07f * w, logo.l, 0.5f)
        assertTrue(r.images.any { kotlin.math.abs(it[0] - logo.x) < 1f && kotlin.math.abs(it[1] - logo.y) < 1f })
        // The view's top left moved (the caption below, a frame drawn in a larger picture): all of it moved.
        val r2 = Releve()
        ElementsJournal.dessineIncrustations(r2, r2, o, donnees, w, h, ox = 10f, oy = 20f)
        assertEquals(r.rects.size, r2.rects.size)
        assertEquals(r.rects[0][0] + 10f, r2.rects[0][0], 0.01f); assertEquals(r.rects[0][1] + 20f, r2.rects[0][1], 0.01f)
    }

    @Test fun une_carte_de_la_video_tient_dans_son_image() {
        for (k in Element.values()) {
            val r = Releve()
            ElementsJournal.carte(r, r, k, donnees, 1280f, 720f, "ISS · 22/10/2026")
            assertTrue(k.name, r.textes.contains(ElementsJournal.nom(k)))
            for (b in r.rects + r.images) assertTrue(k.name, b[0] >= -0.5f && b[1] >= -0.5f && b[0] + b[2] <= 1280.5f && b[1] + b[3] <= 720.5f)
        }
    }

    @Test fun un_passage_sans_evenement_garde_son_ciel_et_ses_images() {
        val e = JournalPassage.Entree(25544, "ISS", 1_790_000_000_000L, 1_790_000_600_000L, points = (0..12).map {
            JournalPassage.Point(1_790_000_000_000L + it * 50_000L, (200.0 + it * 12) % 360, 60 * kotlin.math.sin(Math.PI * it / 12)) })
        val s = SstvMeta.SstvShot("sstv_1.png", "ISS", 1_790_000_400_000L, mode = "PD120")
        val p = ElementsJournal.passageDuJournal(e, listOf(s to (87 to 4)))
        assertEquals(e.points.size, p.trace.size)
        assertEquals(listOf(4), p.recues)
        assertTrue(p.images.single().trace.size >= 2)
        val d = ElementsJournal.Donnees(passage = p)
        assertTrue(ElementsJournal.disponible(Element.CIEL, d) && ElementsJournal.disponible(Element.PROFIL, d))
        assertFalse(ElementsJournal.disponible(Element.DIAPOS, d) || ElementsJournal.disponible(Element.STATS, d))
        assertNotNull(ElementsJournal.svg(Element.CIEL, d))
    }
}
