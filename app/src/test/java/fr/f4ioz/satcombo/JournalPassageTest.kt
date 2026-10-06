/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.JournalPassage
import fr.f4ioz.satcombo.domain.JournalPassage.Etat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.sin

class JournalPassageTest {

    private val t0 = 1_759_500_000_000L

    /** A 10-minute pass seen every second: up to 60°, the rig and the mast following from minute 2. */
    private fun passage(c: JournalPassage.Collecte, catnum: Int = 25544, nom: String = "ISS",
                        duree: Int = 600, depart: Long = t0): JournalPassage.Entree? {
        var fini: JournalPassage.Entree? = null
        for (s in 0..duree + 40) {
            val el = if (s <= duree) 60 * sin(Math.PI * s / duree) else -2.0
            val az = (120.0 + s * 0.2) % 360
            val e = Etat(dlHz = if (s > 120) 437_800_000L + s else null, rotorAz = if (s > 120) az - 1 else null,
                rotorEl = if (s > 120) el else null, enregistrement = if (s in 10..500) "SatMe_ISS_x.mp3" else null,
                transpondeur = "FM", locator = "JN18FS", profil = "Fixe")
            c.suit(catnum, nom, depart + s * 1000L, az, el, e)?.let { fini = it }
        }
        return fini
    }

    @Test
    fun un_passage_suivi_est_garde_au_coucher() {
        val c = JournalPassage.Collecte()
        val e = passage(c)!!
        assertEquals(25544, e.catnum)
        assertEquals("JN18FS", e.locator); assertEquals("Fixe", e.profil); assertEquals("FM", e.transpondeur)
        assertEquals(listOf("SatMe_ISS_x.mp3"), e.enregistrements)
        assertEquals(60.0, e.elMax, 0.5)
        assertTrue(e.dureeMs in 590_000L..600_000L)
        // A point every 5 s or so, not every second.
        assertTrue("${e.points.size}", e.points.size in 100..200)
        assertTrue(e.avecCat && e.avecRotor)
        assertNull(e.points.first().dlHz)
        assertTrue(JournalPassage.couverture(e) > 0.95)
        assertNull(c.enCours)
    }

    @Test
    fun un_coup_d_oeil_n_est_pas_un_passage() {
        val c = JournalPassage.Collecte()
        for (s in 0..30) c.suit(1, "SO-50", t0 + s * 1000L, 100.0, 20.0, Etat())
        assertNull(c.ferme())
    }

    @Test
    fun changer_de_satellite_ferme_le_passage_suivi() {
        val c = JournalPassage.Collecte()
        for (s in 0..120) c.suit(1, "SO-50", t0 + s * 1000L, 100.0 + s * 0.5, 30.0, Etat())
        val fini = c.suit(2, "AO-73", t0 + 121_000L, 10.0, 15.0, Etat())
        assertEquals(1, fini?.catnum)
        assertEquals(2, c.enCours?.catnum)
    }

    @Test
    fun le_test_de_station_va_avec_le_passage_qu_il_prepare() {
        val c = JournalPassage.Collecte()
        c.test(mapOf("CAT" to "OK", "AUDIO" to "WARNING"), t0 - 600_000L)
        val e = passage(c)!!
        assertEquals("OK", e.test["CAT"]); assertEquals(t0 - 600_000L, e.testMs)
        // Taken the day before: it prepared nothing.
        val c2 = JournalPassage.Collecte()
        c2.test(mapOf("CAT" to "OK"), t0 - 86_400_000L)
        assertTrue(passage(c2)!!.test.isEmpty())
    }

    @Test
    fun un_passage_se_garde_et_se_relit() {
        val e = passage(JournalPassage.Collecte())!!.copy(satName = "ISS (ZARYA)\tx", locator = "JN18FS\\a",
            test = mapOf("CAT" to "OK"), testMs = t0 - 1000)
        val lu = JournalPassage.lit(JournalPassage.ecrit(e))!!
        assertEquals("ISS (ZARYA) x", lu.satName); assertEquals("JN18FS\\a", lu.locator)
        assertEquals(e.debutMs, lu.debutMs); assertEquals(e.finMs, lu.finMs)
        assertEquals(e.enregistrements, lu.enregistrements); assertEquals(e.test, lu.test)
        assertEquals(e.points.size, lu.points.size)
        assertEquals(e.points[50].dlHz, lu.points[50].dlHz)
        assertEquals(e.points[50].el, lu.points[50].el, 0.01)
        assertNull(JournalPassage.lit("n'importe quoi"))
    }

    @Test
    fun le_rangement_garde_les_plus_recents() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        val r = JournalPassage.Rangement(dir)
        val base = passage(JournalPassage.Collecte())!!
        for (i in 0 until JournalPassage.GARDE + 3) r.enregistre(base.copy(debutMs = t0 + i * 6_000_000L))
        val l = r.passages()
        assertEquals(JournalPassage.GARDE, l.size)
        assertEquals(t0 + (JournalPassage.GARDE + 2) * 6_000_000L, l.first().debutMs)
        assertFalse(File(dir, "25544_${t0}.passage").exists())
        dir.deleteRecursively()
    }

    @Test
    fun bilan_et_liens_par_le_temps() {
        val e = passage(JournalPassage.Collecte())!!
        val b = JournalPassage.bilan(e,
            qsosTemps = listOf(t0 + 200_000L, t0 + 650_000L, t0 + 3_600_000L),
            imagesTemps = listOf(t0 + 300_000L),
            tramesTemps = listOf(t0 + 100_000L to true, t0 + 101_000L to false, t0 - 3_600_000L to true))
        assertEquals(2, b.qsos)   // one just after LOS still counts
        assertEquals(1, b.images)
        assertEquals(2, b.trames); assertEquals(1, b.tramesViaIss)
        assertTrue(JournalPassage.memeSatellite("ISS (ZARYA)", "ISS"))
        assertFalse(JournalPassage.memeSatellite("AO-73", "SO-50"))
        assertEquals(e.points.first(), JournalPassage.pointA(e, t0 - 5))
    }

    @Test
    fun rejeu_le_son_et_l_heure_se_repondent() {
        // Recording named 15:26:05, a 10 s spoken header: 2 s into the pass audio = 15:26:07.
        val debut = 1_791_041_165_000L
        assertEquals(debut + 2_000L, JournalPassage.heureDuSon(debut, 10_000L, 12_000L))
        assertEquals(12_000L, JournalPassage.posDuSon(debut, 10_000L, debut + 2_000L))
        val e = passage(JournalPassage.Collecte())!!
        val (daz, del) = JournalPassage.ecartMat(e)!!
        assertEquals(1.0, daz, 0.01); assertEquals(0.0, del, 0.01)
        val (d0, mn, mx) = JournalPassage.descente(e)!!
        assertTrue(d0 == mn && mx > mn)
        assertNull(JournalPassage.ecartMat(e.copy(points = e.points.map { it.copy(rotorAz = null) })))
    }

    @Test
    fun le_ciel_le_cadrage_et_les_segments() {
        // Zenith at the centre, north up, east right, horizon at 1.
        val (x0, y0) = JournalPassage.ciel(0.0, 90.0); assertEquals(0.0, x0, 1e-9); assertEquals(0.0, y0, 1e-9)
        val (xn, yn) = JournalPassage.ciel(0.0, 0.0); assertEquals(0.0, xn, 1e-9); assertEquals(-1.0, yn, 1e-9)
        val (xe, _) = JournalPassage.ciel(90.0, 0.0); assertEquals(1.0, xe, 1e-9)
        // A low pass in the west is enlarged and centred on itself; a high one shows the whole sky.
        val bas = JournalPassage.cadre((0..20).map { (250.0 + it) to (2.0 + it % 5) })
        assertTrue(bas.echelle > 2 && bas.cx < -0.5)
        val haut = JournalPassage.cadre((0..90).map { (it * 2.0) to (if (it < 45) it * 2.0 else (90 - it) * 2.0) })
        assertEquals(1.0, haut.echelle, 1e-9)
        assertEquals(JournalPassage.Cadre(), JournalPassage.cadre(emptyList()))
        // The stretch of an SSTV picture starts from the point just before it.
        val e = passage(JournalPassage.Collecte())!!
        val seg = JournalPassage.segment(e, t0 + 100_000L, t0 + 200_000L)
        assertTrue(seg.first().tMs < t0 + 100_000L && seg.last().tMs <= t0 + 200_000L)
        assertTrue(seg.size in 18..24)
    }

    @Test
    fun d_anciens_passages_se_retrouvent_par_ce_qui_s_y_est_passe() {
        val S = JournalPassage.Source.entries
        fun ev(src: Int, t: Long, cat: Int = 25544, enr: String? = null) =
            JournalPassage.Evenement(S[src], t, cat, if (cat == 25544) "ISS" else "SO-50", "JN18FS", enr)
        // Each pass of the fake sky: 10 minutes every 90.
        val fenetre: (JournalPassage.Evenement) -> Pair<Long, Long>? = { e ->
            if (e.catnum == 99) null else {
                val k = (e.tMs - t0) / 5_400_000L
                val aos = t0 + k * 5_400_000L
                if (e.tMs - aos <= 600_000L) aos to aos + 600_000L else null
            }
        }
        val evts = listOf(
            ev(0, t0 + 120_000L), ev(1, t0 + 300_000L), ev(2, t0 + 310_000L), ev(3, t0 + 60_000L, enr = "SatMe_ISS_a.mp3"),
            ev(0, t0 + 5_400_000L + 200_000L, cat = 27607),          // another satellite, next pass
            ev(0, t0 + 2_000_000L),                                   // no pass then
            ev(1, t0 + 100_000L, cat = 99))                           // unknown satellite
        val (c, perdus) = JournalPassage.regroupe(evts, fenetre, emptyList())
        // Found by searching a few minutes around (elements drift): still one pass.
        val autour: (JournalPassage.Evenement) -> Pair<Long, Long>? = { _ -> t0 to t0 + 600_000L }
        val deux = listOf(ev(2, t0 - 600_000L), ev(2, t0 - 300_000L), ev(2, t0 - 400_000L))
        assertEquals(3, JournalPassage.regroupe(deux, autour, emptyList(), exige = null).first.single().nb)
        // APRS frames alone: not offered; an SSTV picture or a recording is enough.
        assertTrue(JournalPassage.regroupe(deux, autour, emptyList()).first.isEmpty())
        assertEquals(1, JournalPassage.regroupe(deux + ev(1, t0 + 100_000L), autour, emptyList()).first.size)
        assertEquals(1, JournalPassage.regroupe(listOf(ev(3, t0 + 60_000L, enr = "x.mp3")), autour, emptyList()).first.size)
        assertEquals(2, c.size); assertEquals(2, perdus)
        val iss = c.first { it.catnum == 25544 }
        assertEquals(4, iss.nb); assertEquals(S.toSet(), iss.sources)
        assertEquals(listOf("SatMe_ISS_a.mp3"), iss.enregistrements)
        assertEquals(t0, iss.aosMs)
        // Already in the journal: not offered again.
        val deja = JournalPassage.Entree(25544, "ISS", t0 + 30_000L, t0 + 590_000L)
        assertEquals(1, JournalPassage.regroupe(evts, fenetre, listOf(deja)).first.size)
        // Rebuilt from the predicted track, kept as such.
        val piste = (0..130).map { i -> Triple(t0 - 5_000L + i * 5_000L, 200.0 + i, 40 * sin(Math.PI * (i - 1) / 121.0)) }
        val e = JournalPassage.reconstitue(iss, piste)!!
        assertTrue(e.reconstitue && e.points.all { it.el >= 0 } && e.points.none { it.dlHz != null })
        assertTrue(JournalPassage.lit(JournalPassage.ecrit(e))!!.reconstitue)
        assertEquals(listOf("SatMe_ISS_a.mp3"), e.enregistrements)
    }

    @Test
    fun empreinte_et_balise_de_l_iss() {
        // The ISS at 420 km sees about 20° of Earth around it.
        val e = JournalPassage.empreinte(48.8, 2.3, 420.0)
        assertEquals(91, e.size)
        val d = e.map { (la, lo) ->
            val a = Math.toRadians(48.8); val b = Math.toRadians(la); val dl = Math.toRadians(lo - 2.3)
            Math.toDegrees(kotlin.math.acos(kotlin.math.sin(a) * kotlin.math.sin(b) + kotlin.math.cos(a) * kotlin.math.cos(b) * kotlin.math.cos(dl)))
        }
        assertTrue(d.all { kotlin.math.abs(it - 20.2) < 0.3 })
        assertTrue(JournalPassage.estLeSatellite("RS0ISS-4") && JournalPassage.estLeSatellite("NA1SS*"))
        assertFalse(JournalPassage.estLeSatellite("F4IOZ-7"))
    }

    @Test
    fun la_carte_se_cadre_sur_ce_qu_elle_montre() {
        assertEquals(0.5, JournalPassage.mercX(0.0), 1e-12); assertEquals(0.5, JournalPassage.mercY(0.0), 1e-12)
        val pts = listOf(48.8 to 2.3, 40.4 to -3.7, 52.5 to 13.4)
        val v = JournalPassage.cadreCarte(pts, 600.0, 600.0)
        val monde = 256.0 * Math.pow(2.0, v.zoom)
        // Every point inside the view, and the view not much larger than needed.
        val xs = pts.map { (JournalPassage.mercX(it.second) - v.cx) * monde + 300 }
        val ys = pts.map { (JournalPassage.mercY(it.first) - v.cy) * monde + 300 }
        assertTrue((xs + ys).all { it in 0.0..600.0 })
        assertTrue(xs.max() - xs.min() > 400 || ys.max() - ys.min() > 400)
        assertEquals(JournalPassage.VueCarte(), JournalPassage.cadreCarte(emptyList(), 600.0, 600.0))
    }

    @Test
    fun l_image_sstv_arrive_puis_reste_le_temps_choisi() {
        val m = JournalPassage.Marque(JournalPassage.TypeMarque.SSTV, t0, t0 + 36_000L, "Robot 36", fichier = "/x.png")
        val R = fr.f4ioz.satcombo.ui.JournalRendu
        assertNull(R.imageA(listOf(m), t0 - 1, 3_000L))
        assertEquals(0.5f, R.imageA(listOf(m), t0 + 18_000L, 3_000L)!!.second, 0.01f)
        assertEquals(1f, R.imageA(listOf(m), t0 + 38_000L, 3_000L)!!.second, 0.0f)
        assertNull(R.imageA(listOf(m), t0 + 40_000L, 3_000L))
        assertNull(R.imageA(listOf(m), null, 3_000L))
        // Without its file, nothing to show.
        assertNull(R.imageA(listOf(m.copy(fichier = null)), t0 + 18_000L, 3_000L))
    }

    @Test
    fun le_son_et_les_images_re_decodees_retrouvent_leur_heure() {
        val I = fr.f4ioz.satcombo.audio.InfoEnregistrement
        // The ISS recording of 03/10 15:26:05: 678.5 s long, last written 668 s after its name's time.
        val debut = 1_791_041_165_000L
        assertEquals(10_500L, I.estimeAnnonce(debut, debut + 668_000L, 678_500L))
        assertEquals(0L, I.estimeAnnonce(debut, debut + 700_000L, 678_500L))   // copied later: no estimate
        assertEquals(0L, I.estimeAnnonce(0L, debut, 678_500L))
        val M = fr.f4ioz.satcombo.sstv.SstvMeta
        val direct = fr.f4ioz.satcombo.sstv.SstvMeta.SstvShot(fileName = "a.png", timeMs = debut + 202_000L, mode = "Robot 36", source = "live")
        val refait = fr.f4ioz.satcombo.sstv.SstvMeta.SstvShot(fileName = "b.png", timeMs = debut + 212_500L, mode = "Robot 36", source = "file", recording = "x.mp3")
        // The same picture received live: its time.
        assertEquals(direct.timeMs, M.heureOrigine(refait, listOf(direct), 10_500L))
        // None live: the spoken header taken off, unless the decoding already did.
        assertEquals(debut + 202_000L, M.heureOrigine(refait, emptyList(), 10_500L))
        assertEquals(refait.timeMs, M.heureOrigine(refait.copy(recale = true), emptyList(), 10_500L))
        assertEquals(direct.timeMs, M.heureOrigine(direct, emptyList(), 10_500L))
        assertTrue(M.decode("b.png", M.encode(refait.copy(recale = true))).recale)
        // Pictures set aside are kept with the pass.
        val e = passage(JournalPassage.Collecte())!!.copy(masquees = setOf("b.png", "c d.png"))
        assertEquals(e.masquees, JournalPassage.lit(JournalPassage.ecrit(e))!!.masquees)
    }

    @Test
    fun une_fiche_pour_le_carnet_une_pour_l_aprs_l_iss_comprise() {
        val R = fr.f4ioz.satcombo.ui.JournalRendu
        val m = listOf(
            JournalPassage.Marque(JournalPassage.TypeMarque.QSO, t0, t0, "F4ABC", details = "FM · 59 / 59"),
            JournalPassage.Marque(JournalPassage.TypeMarque.APRS, t0 + 2_000L, t0 + 2_000L, "RS0ISS-4", details = "ARISS"))
        assertEquals("F4ABC", R.indicatifA(m, t0 + 2_500L, 3_000L, JournalPassage.TypeMarque.QSO)?.texte)
        assertEquals("RS0ISS-4", R.indicatifA(m, t0 + 2_500L, 3_000L, JournalPassage.TypeMarque.APRS)?.texte)
        assertNull(R.indicatifA(m, t0 + 3_500L, 3_000L, JournalPassage.TypeMarque.QSO))
        assertNull(R.indicatifA(m, null, 3_000L, JournalPassage.TypeMarque.APRS))
    }

    @Test
    fun le_son_se_cale_sur_la_fin_du_fichier() {
        val I = fr.f4ioz.satcombo.audio.InfoEnregistrement
        val nom = 1_791_041_165_000L      // the name's time: the recording asked for
        // The ISS recording of 03/10: 678.5 s long, last written 668 s after its name, no header kept.
        val a = I.estimeAnnonce(nom, nom + 668_000L, 678_500L)
        assertEquals(nom - 10_500L, I.origine(nom, nom + 668_000L, 678_500L, a))
        // A recording whose microphone opened 3 s late (a USB card): header 9 s kept,
        // capture 600 s from nom + 3 s. The file's start is found from its end, not its name.
        val fin = nom + 3_000L + 600_000L
        val o = I.origine(nom, fin, 9_000L + 600_000L, 9_000L)
        assertEquals(nom + 3_000L - 9_000L, o)
        // Position of a moment heard at nom + 100 s: in the pass audio, past the header.
        assertEquals(9_000L + 97_000L, nom + 100_000L - o)
        // A file copied since (its date no longer its end): back to the name's time.
        assertEquals(nom - 9_000L, I.origine(nom, nom + 5 * 86_400_000L, 609_000L, 9_000L))
    }

    @Test
    fun deux_morceaux_d_un_passage_n_en_font_qu_un() {
        val e = passage(JournalPassage.Collecte())!!
        // Followed on its page for the first half, in the background for the rest.
        val moitie = e.points[e.points.size / 2].tMs
        val a = e.copy(finMs = moitie, points = e.points.filter { it.tMs <= moitie }, enregistrements = emptyList())
        val b = e.copy(debutMs = moitie + 5_000L, points = e.points.filter { it.tMs > moitie }, profil = "", transpondeur = "")
        assertTrue(JournalPassage.memePassage(a, b))
        val f = JournalPassage.fusionne(a, b)
        assertEquals(e.debutMs, f.debutMs); assertEquals(e.finMs, f.finMs)
        assertEquals(e.points.size, f.points.size)
        assertEquals(listOf("SatMe_ISS_x.mp3"), f.enregistrements)
        assertEquals("Fixe", f.profil)
        assertFalse(JournalPassage.memePassage(a, b.copy(catnum = 1)))
        // Kept one after the other: one file.
        val dir = kotlin.io.path.createTempDirectory().toFile()
        val r = JournalPassage.Rangement(dir)
        r.enregistre(a); r.enregistre(b)
        val l = r.passages()
        assertEquals(1, l.size); assertEquals(e.points.size, l.single().points.size)
        dir.deleteRecursively()
    }

    @Test
    fun la_page_quittee_en_plein_passage_le_fond_reprend() {
        val page = JournalPassage.Collecte(); val fond = JournalPassage.Collecte()
        // 30 s on its page: too short alone, handed to the background.
        for (s in 0..30) page.suit(1, "SO-50", t0 + s * 1000L, 100.0 + s * 0.5, 30.0, Etat(transpondeur = "FM"))
        assertTrue(page.cedeA(fond)); assertNull(page.enCours)
        for (s in 31..90) fond.suit(1, "SO-50", t0 + s * 1000L, 100.0 + s * 0.5, 30.0, Etat(enregistrement = "r.mp3"))
        // Back on its page: the background hands it back.
        assertTrue(fond.cedeA(page))
        val e = page.ferme()!!
        assertEquals(t0, e.debutMs); assertEquals(t0 + 90_000L, e.finMs)
        assertEquals("FM", e.transpondeur); assertEquals(listOf("r.mp3"), e.enregistrements)
        // The other side follows another satellite: no hand-over.
        for (s in 0..30) page.suit(1, "SO-50", t0 + s * 1000L, 100.0, 30.0, Etat())
        for (s in 0..30) fond.suit(2, "AO-73", t0 + s * 1000L, 100.0, 30.0, Etat())
        assertFalse(page.cedeA(fond)); assertNotNull(page.enCours)
    }

    @Test
    fun un_passage_garde_tout_seul_le_dit() {
        val fond = JournalPassage.Collecte()
        for (s in 0..90) fond.suit(1, "SO-50", t0 + s * 1000L, 100.0 + s * 0.5, 30.0,
            Etat(enregistrement = "r.mp3", auto = JournalPassage.AUTO_CAT))
        val e = fond.ferme()!!
        assertEquals(JournalPassage.AUTO_CAT, e.auto)
        assertEquals(JournalPassage.AUTO_CAT, JournalPassage.lit(JournalPassage.ecrit(e))!!.auto)
        // Followed on its page: nothing said; merged with the background piece, it says it.
        val page = e.copy(auto = "")
        assertEquals("", JournalPassage.lit(JournalPassage.ecrit(page))!!.auto)
        assertEquals(JournalPassage.AUTO_CAT, JournalPassage.fusionne(page, e).auto)
    }

    @Test
    fun la_lecture_acceleree_ralentit_autour_de_ce_qui_s_est_passe() {
        val J = JournalPassage
        val d = 0L; val f = 600_000L
        val m = listOf(
            JournalPassage.Marque(JournalPassage.TypeMarque.QSO, 100_000L, 100_000L, "F4XYZ"),
            JournalPassage.Marque(JournalPassage.TypeMarque.QSO, 103_000L, 103_000L, "F5ABC"),    // close: joined
            JournalPassage.Marque(JournalPassage.TypeMarque.SSTV, 300_000L, 420_000L, "PD120"),   // a picture: its whole reception
            JournalPassage.Marque(JournalPassage.TypeMarque.APRS, 595_000L, 595_000L, "RS0ISS"))  // near the end
        // Contacts: 10 s before, 30 s after (the talk goes on); the picture: 1 s before to its end; APRS: 3 s after.
        val p = J.plagesNormales(m, d, f, 10_000L, 30_000L)
        assertEquals(listOf(90_000L..133_000L, 299_000L..420_000L, 585_000L..598_000L), p)
        val s = J.segments(d, f, p, 10)
        assertEquals(listOf(
            JournalPassage.Segment(0L, 90_000L, 10), JournalPassage.Segment(90_000L, 133_000L, 1), JournalPassage.Segment(133_000L, 299_000L, 10),
            JournalPassage.Segment(299_000L, 420_000L, 1), JournalPassage.Segment(420_000L, 585_000L, 10), JournalPassage.Segment(585_000L, 598_000L, 1),
            JournalPassage.Segment(598_000L, 600_000L, 10)), s)
        // 9 + 43 + 16.6 + 121 + 16.5 + 13 + 0.2 s of playing.
        assertEquals(219_300L, J.dureeLecture(s))
        assertEquals(50_000L, J.instantALecture(s, 5_000L))
        assertEquals(95_000L, J.instantALecture(s, 14_000L))
        assertEquals(600_000L, J.instantALecture(s, 1_000_000L))
        assertEquals(1, J.segmentA(s, 300_000L)!!.vitesse)
        assertEquals(10, J.segmentA(s, 425_000L)!!.vitesse)
        assertEquals(10, J.segmentA(s, 200_000L)!!.vitesse)
        // Nothing logged: all fast.
        assertEquals(listOf(JournalPassage.Segment(0L, 600_000L, 10)), J.segments(d, f, emptyList(), 10))
    }

    @Test
    fun un_signet_s_ecrit_se_relit_et_ralentit_comme_un_contact() {
        val sg = JournalPassage.Signet(1_791_000_000_000L, 25544, "voix \tfaible\n")
        val lu = JournalPassage.litSignet(JournalPassage.ecritSignet(sg))!!
        assertEquals(sg.tMs, lu.tMs); assertEquals(25544, lu.catnum); assertEquals("voix  faible ", lu.note)
        assertNull(JournalPassage.litSignet("n'importe quoi"))
        val m = listOf(JournalPassage.Marque(JournalPassage.TypeMarque.SIGNET, 200_000L, 200_000L, "★"))
        assertEquals(listOf(190_000L..230_000L), JournalPassage.plagesNormales(m, 0L, 600_000L, 10_000L, 30_000L))
    }

    @Test
    fun l_activite_entendue_ralentit_aussi() {
        // Nothing logged, a voice heard from 100 s to 110 s: normal from 1 s before to 2 s after.
        val p = JournalPassage.plagesNormales(emptyList(), 0L, 600_000L, 10_000L, 30_000L, listOf(100_000L..110_000L))
        assertEquals(listOf(99_000L..112_000L), p)
        // With a contact close by: one stretch.
        val m = listOf(JournalPassage.Marque(JournalPassage.TypeMarque.QSO, 115_000L, 115_000L, "F4XYZ"))
        assertEquals(listOf(99_000L..145_000L),
            JournalPassage.plagesNormales(m, 0L, 600_000L, 10_000L, 30_000L, listOf(100_000L..110_000L)))
    }

    @Test
    fun le_s_metre_de_l_icom_se_lit_se_garde_et_se_dit() {
        // CI-V 0x15 0x02: two big-endian BCD bytes.
        assertEquals(241, fr.f4ioz.satcombo.cat.CatDecode.niveauMetre(byteArrayOf(0x02, 0x41)))
        assertEquals(120, fr.f4ioz.satcombo.cat.CatDecode.niveauMetre(byteArrayOf(0x01, 0x20)))
        assertNull(fr.f4ioz.satcombo.cat.CatDecode.niveauMetre(byteArrayOf(0x0A, 0x00)))
        assertNull(fr.f4ioz.satcombo.cat.CatDecode.niveauMetre(byteArrayOf(0x01)))
        assertEquals("S0", JournalPassage.libelleS(0)); assertEquals("S5", JournalPassage.libelleS(67))
        assertEquals("S9", JournalPassage.libelleS(120)); assertEquals("S9+20", JournalPassage.libelleS(160))
        assertEquals("S9+60", JournalPassage.libelleS(241))
        // Gathered once a second at most, kept, read back, merged.
        val c = JournalPassage.Collecte()
        for (k in 0..180) c.suit(1, "SO-50", t0 + k * 500L, 100.0 + k * 0.2, 30.0, Etat(smetre = 60 + k % 50))
        val e = c.ferme()!!
        assertEquals(91, e.signal.size)
        val lu = JournalPassage.lit(JournalPassage.ecrit(e))!!
        assertEquals(e.signal, lu.signal)
        assertEquals(e.signal[10].s, JournalPassage.signalA(lu, e.signal[10].tMs + 400))
        assertNull(JournalPassage.signalA(lu, e.signal.last().tMs + 10_000))
        assertEquals(91, JournalPassage.fusionne(e, e.copy(signal = e.signal.take(5))).signal.size)
    }
}
