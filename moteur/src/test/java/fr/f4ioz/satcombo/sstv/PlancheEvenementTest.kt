/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

import fr.f4ioz.satcombo.data.SatPass
import fr.f4ioz.satcombo.domain.ActivitesAVenir
import fr.f4ioz.satcombo.domain.CampagneSstv
import fr.f4ioz.satcombo.i18n.I18n
import fr.f4ioz.satcombo.sstv.ClassementSstv.Origine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO

/** The sheet tied to its event: fill by slide, the event chosen, the analysis run again, the report. */
class PlancheEvenementTest {

    private val J = 86_400_000L
    /** 22/10/2026 15:10 UTC, the start of ARISS series 34. */
    private val T0 = 1_792_681_800_000L

    private fun shot(nom: String, t: Long, sat: String = "ISS", complete: Boolean = true) =
        SstvMeta.SstvShot(fileName = nom, satName = sat, timeMs = t, mode = "Robot36", complete = complete)

    private fun groupe(id: String, diapo: Int, o: Origine, vararg im: Pair<SstvMeta.SstvShot, Int>) =
        ClassementSstv.Groupe(id, im.sortedByDescending { it.second }.map { it.first }, diapo, 12, o, im.associate { it.first.fileName to it.second })

    // ------------------------------------------------------------ by slide

    @Test fun remplir_par_diapo_suit_l_ordre_des_numeros_la_meilleure_copie_et_les_sans_numero_apres() {
        val a = shot("a.png", T0 + 1000); val b = shot("b.png", T0 + 2000); val c = shot("c.png", T0 + 3000)
        val d = shot("d.png", T0 + 4000); val e = shot("e.png", T0 + 5000); val f = shot("f.png", T0 + 500)
        val gs = listOf(
            groupe("g3", 3, Origine.LU, a to 60, b to 90),       // slide 3: b, the best marked
            groupe("g1", 1, Origine.ESTIME, c to 95),           // slide 1 estimated…
            groupe("g1m", 1, Origine.MAIN, d to 40),            // …and by hand: the hand wins
            groupe("gx", 0, Origine.AUCUNE, e to 70),           // no number: after
            groupe("gy", 0, Origine.AUCUNE, f to 20))
        val serre = Planche.ordreParDiapo(gs, 12, vides = false)
        assertEquals(listOf("d.png", "b.png", "f.png", "e.png"), serre.map { it!!.fichier })
        val vides = Planche.ordreParDiapo(gs, 12, vides = true)
        assertEquals("d.png", vides[0]!!.fichier)
        assertNull(vides[1])
        assertEquals("b.png", vides[2]!!.fichier)
        assertEquals(12 + 2, vides.size)
        assertEquals(listOf("f.png", "e.png"), vides.drop(12).map { it!!.fichier })

        // A template of 12 boxes: slide n in box n, its number kept for the box; slides missing said.
        val m = Planche.genereGrille("p1", "Série 34")
        val r = Planche.remplitParDiapo(m, gs, 12, vides = true)
        assertEquals("d.png", r.modele.images[0]); assertNull(r.modele.images[1]); assertEquals("b.png", r.modele.images[2])
        assertEquals("1/12", Planche.etiquette(r.modele, 0))
        assertEquals("3/12", Planche.etiquette(r.modele, 2))
        assertEquals((1..12).filter { it != 1 && it != 3 }, r.manquantes)
        assertEquals(2, r.sansNumero)
        assertEquals(2, r.horsCases)   // the two with no number found no box
        // Without empty boxes, they all fit.
        val r2 = Planche.remplitParDiapo(m, gs, 12, vides = false)
        assertEquals(listOf("d.png", "b.png", "f.png", "e.png"), Planche.choisies(r2.modele))
        assertEquals("3/12", Planche.etiquette(r2.modele, 1))   // slide 3 moved up into box 2
        // A picture with no number: its box's place.
        assertEquals("3/12", Planche.etiquette(r2.modele, 2))
    }

    @Test fun le_numero_estime_se_voit_et_suit_l_image_echangee() {
        val c = shot("c.png", T0); val d = shot("d.png", T0 + 1000)
        val gs = listOf(groupe("g5", 5, Origine.ESTIME, c to 80), groupe("g6", 6, Origine.LU, d to 70))
        val r = Planche.remplitParDiapo(Planche.genereGrille("p", "x"), gs, 12, vides = false)
        assertEquals("≈5/12", Planche.etiquette(r.modele, 0))
        val e = Planche.echange(r.modele, 0, 1)
        assertEquals("6/12", Planche.etiquette(e, 0)); assertEquals("≈5/12", Planche.etiquette(e, 1))
        assertEquals("3/12", Planche.etiquette(e, 2))
        assertEquals("≈5/12", ClassementSstv.badge(5, 12, Origine.ESTIME))
        assertEquals("5/12✎", ClassementSstv.badge(5, 12, Origine.MAIN))
        assertEquals("5/12", ClassementSstv.badge(5, 12, Origine.LU))
        assertEquals("", ClassementSstv.badge(0, 12, Origine.AUCUNE))
    }

    @Test fun une_planche_ajustee_garde_la_place_d_une_diapo_manquante() {
        val a = shot("a.png", T0); val b = shot("b.png", T0 + 1000)
        val gs = listOf(groupe("g1", 1, Origine.LU, a to 80), groupe("g4", 4, Origine.LU, b to 80))
        val m = Planche.ajuste(Planche.genereGrille("p", "x"), listOf("z.png"))
        assertTrue(m.ajustee)
        val r = Planche.remplitParDiapo(m, gs, 12, vides = true)
        // Four boxes: 1, 2 and 3 empty… up to 4; the empty ones at the end dropped.
        assertEquals(4, r.modele.cases.size)
        assertEquals(mapOf(0 to "a.png", 3 to "b.png"), r.modele.images)
        // The line under the pictures switched: the places stay.
        assertEquals(mapOf(0 to "a.png", 3 to "b.png"), Planche.reajuste(r.modele.copy(legende = !r.modele.legende)).images)
    }

    @Test fun l_evenement_les_numeros_et_l_option_vont_dans_le_fichier_et_une_vieille_planche_se_lit() {
        val ev = EvenementSstv.Evenement("c:serie-34", EvenementSstv.Genre.CAMPAGNE, "ARISS série 34; « Artemis »", "ISS", 25544, T0, T0 + 4 * J, 158.97)
        val m = Planche.genereGrille("p1", "Ma planche").copy(images = mapOf(0 to "a.png", 1 to "b.png"), evenement = ev, evenementMain = true,
            vides = true, diapos = mapOf("a.png" to "1/12", "b.png" to "≈2/12", "parti.png" to "9/12"))
        val l = Planche.lit("p1", Planche.ecrit(m))!!
        assertEquals("ARISS série 34 « Artemis »", l.evenement!!.nom)
        assertEquals(ev.copy(nom = "ARISS série 34 « Artemis »"), l.evenement)
        assertTrue(l.evenementMain); assertTrue(l.vides)
        // The number of a picture no longer on the sheet is not kept.
        assertEquals(mapOf("a.png" to "1/12", "b.png" to "≈2/12"), l.diapos)
        // A sheet of before: no event, nothing chosen by hand.
        val vieille = Planche.ecrit(Planche.genereGrille("p0", "Vieille")).lines().filter { !it.startsWith("evenement") }.joinToString("\n")
        val v = Planche.lit("p0", vieille)!!
        assertNull(v.evenement); assertFalse(v.evenementMain); assertTrue(v.diapos.isEmpty()); assertEquals("Vieille", v.nom)
    }

    // ------------------------------------------------------------ the event

    @Test fun l_evenement_est_celui_qui_tient_les_images_le_pilote_avant_l_activite() {
        val c = CampagneSstv.Campagne(id = "serie-34", nom = "ARISS série 34", catnum = 25544, satNom = "ISS", emetteur = "SSTV", debutMs = T0, finMs = T0 + 4 * J)
        val a = ActivitesAVenir.Activite(id = "ariss-34@x", type = ActivitesAVenir.Type.SSTV, satNom = "ISS", catnum = 25544, debutMs = T0 - J, finMs = T0 + 5 * J,
            titre = "ARISS SSTV", sources = emptyList(), horaire = "36 s on / 2 min off")
        val autre = ActivitesAVenir.Activite(id = "contact", type = ActivitesAVenir.Type.CONTACT, satNom = "ISS", catnum = 25544, debutMs = T0, finMs = T0 + J,
            titre = "Contact", sources = emptyList())
        val l = EvenementSstv.liste(listOf(a, autre), listOf(c))
        assertEquals(2, l.size)
        val images = listOf(shot("a.png", T0 + 3_600_000L), shot("b.png", T0 + J))
        assertEquals("c:serie-34", EvenementSstv.choisit(l, images, T0)!!.id)
        // A picture before the campaign: the activity holds more.
        val avant = images + shot("c.png", T0 - 12 * 3_600_000L) + shot("d.png", T0 - 10 * 3_600_000L)
        assertEquals(EvenementSstv.Genre.ACTIVITE, EvenementSstv.choisit(l, avant, T0)!!.genre)
        // Another satellite's pictures: none of them.
        assertNull(EvenementSstv.choisit(l, listOf(shot("u.png", T0 + J, sat = "UMKA-1")), T0))
        // With no picture, the one under way.
        assertEquals("c:serie-34", EvenementSstv.choisit(l, emptyList(), T0 + J)!!.id)
        // Period read from the announcement.
        assertEquals(156.0, l.first { it.genre == EvenementSstv.Genre.ACTIVITE }.periodeS, 0.01)
    }

    @Test fun sans_evenement_la_galerie_fait_ses_series_et_la_ligne_se_relit() {
        val im = listOf(shot("a.png", T0), shot("b.png", T0 + J), shot("c.png", T0 + 20 * J), shot("u.png", T0, sat = "UMKA-1"))
        val l = EvenementSstv.depuisImages(im) { if (it == "ISS") 25544 else 0 }
        assertEquals(3, l.size)
        val iss = l.filter { it.satNom == "ISS" }.sortedBy { it.debutMs }
        assertEquals(25544, iss[0].catnum)
        assertTrue(EvenementSstv.dans(iss[0], im[1])); assertFalse(EvenementSstv.dans(iss[0], im[2]))
        val tous = EvenementSstv.tous(listOf(EvenementSstv.Evenement("c:x", EvenementSstv.Genre.CAMPAGNE, "x", "ISS", 25544, T0 - J, T0 + 2 * J)), im) { 25544 }
        // The campaign holds the first run: only the late ISS picture and UMKA's make runs of their own.
        assertEquals(3, tous.size)
        val e = iss[0]
        assertEquals(e, EvenementSstv.lit(EvenementSstv.ecrit(e)))
        assertNull(EvenementSstv.lit("n'importe quoi"))
    }

    // ------------------------------------------------------------ the analysis again

    @Test fun relancer_l_analyse_garde_ce_que_la_main_a_donne_sauf_si_on_le_demande() {
        val dir = Files.createTempDirectory("relance").toFile()
        try {
            fun png(nom: String, gris: Int) = File(dir, nom).also { f ->
                val b = BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB)
                for (y in 0 until 240) for (x in 0 until 320) b.setRGB(x, y, if ((x / 40 + y / 40) % 2 == 0) gris * 0x010101 else 0x203040)
                ImageIO.write(b, "png", f)
            }
            png("a.png", 200); png("b.png", 90)
            SstvMeta.change(File(dir, "a.png")) { it.copy(satName = "ISS", timeMs = T0, mode = "Robot36", diapo = 7, diapos = 12, groupe = "mien") }
            SstvMeta.change(File(dir, "b.png")) { it.copy(satName = "ISS", timeMs = T0 + 200_000L, mode = "Robot36") }
            fun shots() = listOf("a.png", "b.png").map { n -> SstvMeta.decode(n, File(dir, SstvMeta.sidecarName(n)).readText()) }
            var lus = 0
            val g = ClassementGalerie { f -> lus++; ImageIO.read(f).let { ClassementGalerie.Pixels(it.getRGB(0, 0, it.width, it.height, null, 0, it.width), it.width, it.height) } }
            val e1 = g.etat(dir, shots(), attendre = true)
            assertEquals(2, lus)
            assertEquals(7, e1.groupes.first { gr -> gr.images.any { it.fileName == "a.png" } }.diapo)
            // Asked again: measured again (the cache forgotten), the hand's number kept.
            assertEquals(2, g.relance(dir, shots()))
            assertEquals(0, g.mesurees(dir, shots()))
            val e2 = g.etat(dir, shots(), attendre = true)
            assertEquals(4, lus)
            val a = e2.groupes.first { gr -> gr.images.any { it.fileName == "a.png" } }
            assertEquals(7, a.diapo); assertEquals(Origine.MAIN, a.origine); assertEquals("mien", shots()[0].groupe)
            // Forgetting the hand: the number and the group gone.
            g.relance(dir, shots(), oublieMain = true)
            assertEquals(0, shots()[0].diapo); assertEquals("", shots()[0].groupe)
            assertEquals("ISS", shots()[0].satName)
            val e3 = g.etat(dir, shots(), attendre = true)
            assertTrue(e3.groupes.none { it.origine == Origine.MAIN })
        } finally { dir.deleteRecursively() }
    }

    // ------------------------------------------------------------ the report

    private fun passe(aos: Long, minutes: Int, el: Double, azA: Double = 300.0, azL: Double = 120.0): SatPass {
        val n = minutes * 2
        val tr = (0 until n).map { i -> val u = (i + 0.5) / n; (azA + (azL - azA + 360) % 360 * u) % 360 to el * kotlin.math.sin(Math.PI * u) }
        return SatPass("ISS", 25544, aos, aos + minutes * 60_000L, el, azA, azL, sunlit = false, nightAtObserver = false, track = tr)
    }

    private fun rapport(): RapportEvenement.Rapport {
        val ev = EvenementSstv.Evenement("c:serie-34", EvenementSstv.Genre.CAMPAGNE, "ARISS série 34", "ISS", 25544, T0, T0 + 4 * J, 158.97)
        val p1 = passe(T0 + 3_600_000L, 10, 55.0)          // two pictures received
        val p2 = passe(T0 + 5 * 3_600_000L, 10, 40.0)      // high, nothing received
        val p3 = passe(T0 + 9 * 3_600_000L, 6, 4.0)        // too low
        val p4 = passe(T0 + 3 * J, 10, 30.0)               // to come
        val a = shot("a.png", p1.aosEpochMs + 3 * 60_000L); val b = shot("b.png", p1.aosEpochMs + 6 * 60_000L, complete = false)
        val c = shot("c.png", p1.aosEpochMs + 6 * 60_000L + 5000)
        val gs = listOf(groupe("g1", 1, Origine.LU, a to 88), groupe("g2", 2, Origine.ESTIME, b to 41, c to 52))
        val modele = HoraireDiapos.ajuste(listOf(HoraireDiapos.Repere(a.timeMs - 36_000L, 1)), 12, 158.97)
        val prev = SerieSstv.Prevision(ev.serie(), gs, modele, null, setOf(1, 2), 10.0, listOf(a to p1.aosEpochMs, b to p1.aosEpochMs, c to p1.aosEpochMs))
        val trace = CampagneSstv.Trace("serie-34", "ARISS série 34", 25544, "ISS", p2.aosEpochMs, p2.losEpochMs, 40.0, CampagneSstv.Statut.ECARTE, "rotor occupé")
        return RapportEvenement.construit(ev, prev, listOf(p3, p1, p4, p2), RapportEvenement.Station("F4IOZ", "JN18FS"), 10.0, T0 + J,
            positions = { ts -> ts.map { 200.0 to 20.0 } }, geometrie = RapportEvenement.Geometrie(true, T0 - J), traces = listOf(trace),
            chaine = listOf(listOf(RapportEvenement.Etape("Antennes", "Yagi 70 cm")), listOf(RapportEvenement.Etape("Postes", "IC-9700"))),
            nomChaine = "IC-9700 satellites", planche = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()), nomPlanche = "Série 34",
            vignette = { byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) })
    }

    @Test fun le_rapport_dit_ce_que_devient_chaque_passage_et_chaque_diapo() {
        val r = rapport()
        assertEquals(listOf(RapportEvenement.Statut.RECU, RapportEvenement.Statut.ATTENDU_RIEN, RapportEvenement.Statut.BAS, RapportEvenement.Statut.A_VENIR),
            r.passages.map { it.statut })
        val p1 = r.passages[0]
        assertEquals(listOf(1, 2), p1.recues); assertEquals(3, p1.images.size)
        assertTrue(p1.images.all { it.trace.size == 5 && it.trace.all { pt -> pt.el == 20.0 } })
        assertTrue("prévues ${p1.prevues}", p1.prevues.isNotEmpty())
        assertTrue(r.passages[1].pilote.isNotBlank())
        assertEquals(2, r.stats.diapos); assertEquals(1, r.stats.diaposEstimees)
        assertEquals(3, r.stats.images); assertEquals(2, r.stats.completes)
        assertEquals(1, r.stats.passagesRecus); assertEquals(1, r.stats.passagesRates); assertEquals(1, r.stats.passagesAVenir)
        assertEquals(3, r.stats.passagesHauts)
        assertEquals(88, r.stats.noteMax)
        val d2 = r.diapos[1]
        assertEquals("c.png", d2.meilleure); assertEquals(2, d2.copies); assertEquals(Origine.ESTIME, d2.origine)
        // A slide missing: the high pass gone by that should have sent it, if any.
        val manquantes = r.diapos.filter { !it.recue }
        assertEquals(10, manquantes.size)
        assertTrue(manquantes.any { d -> d.attendue.any { it.aosMs == r.passages[1].aosMs } })
        assertTrue(manquantes.none { d -> d.attendue.any { it.statut == RapportEvenement.Statut.A_VENIR } })
        assertEquals(2, r.vignettes.size)
    }

    @Test fun le_rapport_html_se_fait_seul_sans_rien_chercher_dehors() {
        for (langue in listOf("fr", "en")) {
            I18n.apply(langue, langue)
            val h = RapportEvenement.html(rapport())
            assertTrue(h.startsWith("<!doctype html>"))
            assertTrue(h.contains("F4IOZ") && h.contains("JN18FS") && h.contains("ARISS série 34"))
            assertTrue(h.contains("<svg class=\"polaire\"") && h.contains("<svg class=\"chrono\"") && h.contains("<svg class=\"profil\""))
            assertTrue(h.contains("IC-9700") && h.contains("data:image/jpeg;base64,"))
            assertTrue(h.contains("@media print"))
            // Nothing fetched: every src a picture inside, no link, no stylesheet, no script, no font.
            assertFalse(Regex("(?i)(src|href)\\s*=\\s*\"(?!data:)").containsMatchIn(h))
            assertFalse(h.contains("http://") || h.contains("https://"))
            assertFalse(h.contains("<script") || h.contains("@import") || h.contains("url("))
            // Every number a number (no NaN or infinity in the drawings).
            assertFalse(h.contains("NaN") || h.contains("Infinity"))
        }
        I18n.apply("fr", "fr")
        // An event with nothing: still a page.
        val ev = EvenementSstv.Evenement("i:x", EvenementSstv.Genre.IMAGES, "Rien", "ISS", 0, T0, T0 + J)
        val vide = RapportEvenement.construit(ev, SerieSstv.Prevision(ev.serie(), emptyList(), null, null, emptySet(), 10.0, emptyList()),
            emptyList(), RapportEvenement.Station("", ""), 10.0, T0)
        val h = RapportEvenement.html(vide)
        assertNotNull(h); assertTrue(h.contains("0/12")); assertFalse(h.contains("NaN"))
        assertTrue(RapportEvenement.nomFichier(vide).matches(Regex("SatMe_Rapport_Rien_\\d{8}\\.html")))
    }
}
