/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

import fr.f4ioz.satcombo.sstv.ProchaineImageSstv.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The countdown to the next SSTV picture of a pass. */
class ProchaineImageSstvTest {

    private val r36 = SstvMode.byName("Robot 36")!!
    private val t0 = 1_793_135_760_000L

    @Test
    fun la_periode_tient_malgre_la_gigue_et_une_image_manquee() {
        val r = java.util.Random(4)
        // Every 156 s, ± 2 s, the fourth picture missed (a gap of two periods).
        val debuts = listOf(0, 1, 2, 4, 5).map { t0 + it * 156_000L + (r.nextGaussian() * 2000).toLong() }
        val (p, n) = ProchaineImageSstv.periode(debuts)!!
        assertTrue("période $p", abs(p - 156_000L) < 2_500L)
        assertEquals(4, n)
        assertNull(ProchaineImageSstv.periode(listOf(t0)))
    }

    @Test
    fun le_compte_a_rebours_se_recale_sur_chaque_image() {
        val d = listOf(t0, t0 + 156_000L)
        val e = ProchaineImageSstv.etat(t0 + 200_000L, d)
        assertEquals(Phase.ATTENTE, e.phase)
        assertEquals(t0 + 312_000L, e.prochaineMs)
        assertEquals(ProchaineImageSstv.Source.ESTIME, e.source)
        assertEquals(112_000L, e.resteMs(t0 + 200_000L))
        // The next one came 6 s early: the countdown starts again from it.
        val e2 = ProchaineImageSstv.etat(t0 + 320_000L, d + (t0 + 306_000L))
        assertEquals(t0 + 306_000L + e2.periodeMs, e2.prochaineMs)
    }

    @Test
    fun l_horaire_annonce_suffit_des_la_premiere_image() {
        val e = ProchaineImageSstv.etat(t0 + 50_000L, listOf(t0), periodeAnnonceeS = HoraireDiapos.periodeOuNull("Robot 36 · 36 s on / 2 min off"))
        assertEquals(Phase.ATTENTE, e.phase)
        assertEquals(ProchaineImageSstv.Source.HORAIRE, e.source)
        assertEquals(t0 + 156_000L, e.prochaineMs)
        assertEquals(Phase.INCONNU, ProchaineImageSstv.etat(t0 + 50_000L, listOf(t0)).phase)
        assertNull(HoraireDiapos.periodeOuNull("horaire à confirmer"))
    }

    @Test
    fun en_retard_puis_en_attente() {
        val d = listOf(t0, t0 + 156_000L)
        val juste = ProchaineImageSstv.etat(t0 + 312_000L + 20_000L, d)
        assertEquals("un peu en retard : encore imminente", Phase.ATTENTE, juste.phase)
        val retard = ProchaineImageSstv.etat(t0 + 312_000L + 40_000L, d)
        assertEquals(Phase.EN_RETARD, retard.phase)
        assertEquals(t0 + 468_000L, retard.suivanteMs)
        assertEquals(Phase.EN_ATTENTE, ProchaineImageSstv.etat(t0 + 156_000L + 2 * 156_000L + 1_000L, d).phase)
    }

    @Test
    fun au_los_fin_du_passage() {
        val d = listOf(t0, t0 + 156_000L)
        assertEquals(Phase.FIN_PASSAGE, ProchaineImageSstv.etat(t0 + 400_000L, d, losMs = t0 + 400_000L).phase)
        assertTrue(ProchaineImageSstv.etat(t0 + 200_000L, d, losMs = t0 + 300_000L).apresLos)
    }

    @Test
    fun une_image_en_cours_dit_sa_progression() {
        val e = ProchaineImageSstv.etat(t0 + 18_000L, emptyList(), ProchaineImageSstv.EnCours(t0, 120, r36), periodeAnnonceeS = 156.0)
        assertEquals(Phase.EN_COURS, e.phase)
        assertEquals(0.5, e.progression, 1e-9)
        assertEquals(18_000L, e.resteImageMs)
        assertEquals(t0 + 156_000L, e.prochaineMs)
    }

    /**
     * The UmKA-1 pass of 27/10/2026 (its recording, decoded again): the four
     * pictures began at 21:16:00.7, 21:18:01.5, 21:21:19.9 and 21:24:38.7
     * (one of them received cut in two, counted once). No timetable known.
     */
    @Test
    fun le_passage_umka_du_27_octobre() {
        val debutEnr = SstvMeta.debutEnregistrement("SatMe_UmKA-1--RS40S_20261027_211412Z.wav")
        val debuts = listOf(108_715L, 229_547L, 427_861L, 626_688L).map { debutEnr + it }
        val f = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        val lignes = ArrayList<String>()
        for (i in debuts.indices) {
            // Just after each picture ended: what the countdown said.
            val apres = debuts[i] + 40_000L
            val e = ProchaineImageSstv.etat(apres, debuts.take(i + 1), mode = r36)
            val reel = debuts.getOrNull(i + 1)
            lignes += "après l'image ${i + 1} (${f.format(debuts[i])}) : ${e.phase} " +
                (if (e.prochaineMs > 0) "prochaine ${f.format(e.prochaineMs)} (période ${e.periodeMs / 1000} s, ${e.images} images)" else "") +
                (if (reel != null) " — réelle ${f.format(reel)}" + (if (e.prochaineMs > 0) ", écart ${(reel - e.prochaineMs) / 1000} s" else "") else "")
        }
        println(lignes.joinToString("\n"))
        // After two pictures: 121 s, the third came 198 s later (late, then put right on it).
        val apres2 = ProchaineImageSstv.etat(debuts[1] + 40_000L, debuts.take(2))
        assertEquals(debuts[1] + 120_832L, apres2.prochaineMs)
        assertEquals(Phase.EN_RETARD, ProchaineImageSstv.etat(debuts[2] - 10_000L, debuts.take(2)).phase)
        // After three: the latest gap's rhythm, the fourth within a second.
        val apres3 = ProchaineImageSstv.etat(debuts[2] + 40_000L, debuts.take(3))
        assertTrue("écart ${debuts[3] - apres3.prochaineMs} ms", abs(debuts[3] - apres3.prochaineMs) < 1_000L)
    }
}
