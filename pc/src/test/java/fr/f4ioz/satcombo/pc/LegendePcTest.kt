/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.domain.JournalPassage
import fr.f4ioz.satcombo.domain.JournalPassage.Marque
import fr.f4ioz.satcombo.domain.JournalPassage.TypeMarque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.TimeZone
import javax.imageio.ImageIO

/** The videos' caption: its size and zone asked, the pass's times, never one text over another; the session's gaps and turns. */
class LegendePcTest {

    private val t0 = 1_792_510_800_000L   // 2026-10-20 15:40 UTC
    private val echantillons = File(System.getenv("SATME_SESSION2") ?: "build/session2-echantillons").apply { mkdirs() }

    private fun lit(vararg kv: Pair<String, String>): (String) -> String = { k -> kv.toMap()[k].orEmpty() }

    @Test
    fun la_taille_et_le_fuseau_demandes() {
        assertEquals(1f, LegendePc.police(lit()), 0f)
        assertEquals(1f, LegendePc.police(lit("police" to "gros")), 0f)
        assertEquals(1.5f, LegendePc.police(lit("police" to "1.5")), 0f)
        assertEquals(0.6f, LegendePc.police(lit("police" to "0.1")), 0f)
        assertEquals(2.5f, LegendePc.police(lit("police" to "9")), 0f)
        assertEquals("UTC", LegendePc.fuseau(lit()).id)
        assertEquals("UTC", LegendePc.fuseau(lit("utc" to "1")).id)
        assertEquals(TimeZone.getDefault().id, LegendePc.fuseau(lit("utc" to "0")).id)
        assertEquals("UTC", LegendePc.zone(LegendePc.UTC, t0))
        assertEquals("CEST", LegendePc.zone(TimeZone.getTimeZone("Europe/Paris"), t0))
        assertEquals("CET", LegendePc.zone(TimeZone.getTimeZone("Europe/Paris"), t0 + 30L * 86_400_000L))
        // A zone without a short name: said « heure locale ».
        assertEquals("heure locale", LegendePc.zone(TimeZone.getTimeZone("GMT+05:30"), t0))
    }

    @Test
    fun les_heures_du_passage() {
        val e = entree()
        assertEquals(listOf("AOS 15:40:00", "TCA 15:45 (max 65°)", "LOS 15:50:00"), LegendePc.aosTcaLos(e, LegendePc.UTC))
        assertEquals(listOf("AOS 17:40:00", "TCA 17:45 (max 65°)", "LOS 17:50:00"), LegendePc.aosTcaLos(e, TimeZone.getTimeZone("Europe/Paris")))
        assertEquals("AOS dans 0:42", LegendePc.relatif(e, t0 - 42_000L))
        assertEquals("AOS + 3:12", LegendePc.relatif(e, t0 + 192_000L))
        assertEquals("AOS + 0:00", LegendePc.relatif(e, t0))
        assertEquals("après LOS", LegendePc.relatif(e, t0 + 600_001L))
        assertEquals("AOS dans 1:05:00", LegendePc.relatif(e, t0 - 3_900_000L))
    }

    @Test
    fun le_tour_de_la_session_demande() {
        assertEquals(1.5, SessionRenduPc.parametres(lit()).transition, 0.0)
        assertEquals(3.0, SessionRenduPc.parametres(lit("transition" to "3")).transition, 0.0)
        assertEquals(6.0, SessionRenduPc.parametres(lit("transition" to "60")).transition, 0.0)
        assertEquals(0.5, SessionRenduPc.parametres(lit("transition" to "0.1")).transition, 0.0)
        assertEquals(1.5, SessionRenduPc.parametres(lit("transition" to "NaN")).transition, 0.0)
    }

    private val fenetres = (0..2).map { k -> val de = t0 + k * 95 * 60_000L; (de - 30_000L)..(de + 390_000L) }

    @Test
    fun hors_des_passages_le_temps_va_a_v_hors() {
        // Far from the passes, a frame covers vHors × 1/8 s; inside them vPassage × 1/8 s.
        for (h in listOf(500.0, 1000.0, 4000.0)) {
            val plan = SessionRenduPc.plan(fenetres, emptyList(), "continu", SessionRenduPc.Vitesses(hors = h, passage = 20.0))
            val pas = plan.zipWithNext().map { (a, b) -> b.t - a.t }
            assertTrue("$h", pas.count { Math.abs(it - h * 125) <= 1 } > 3)
            assertTrue(pas.max() <= h * 125 + 1)
            assertTrue(plan.zipWithNext().any { (a, b) -> a is SessionRenduPc.Trame.Passe && b is SessionRenduPc.Trame.Passe && b.t - a.t == 2_500L })
        }
    }

    @Test
    fun le_tour_entre_deux_passages_dure_ce_qui_est_demande() {
        for (s in listOf(0.5, 1.5, 3.0, 6.0)) {
            val plan = SessionRenduPc.plan(fenetres, emptyList(), "passages", SessionRenduPc.Vitesses(passage = 30.0), SessionRenduPc.FPS, transition = s)
            val tours = plan.filterIsInstance<SessionRenduPc.Trame.Tourne>()
            assertEquals("$s s", 2 * Math.round(s * SessionRenduPc.FPS).toInt(), tours.size)
            assertEquals(s, tours.count { it.i == 1 }.toDouble() / SessionRenduPc.FPS, 0.07)
        }
    }

    private fun entree() = JournalPassage.Entree(25544, "ISS", t0, t0 + 600_000L, "JN18DU",
        points = (0..10).map { JournalPassage.Point(t0 + it * 60_000L, (300.0 + it * 15) % 360, if (it <= 5) 5.0 + it * 12 else 65.0 - (it - 5) * 12) },
        signal = (0..600).map { JournalPassage.Signal(t0 + it * 1000L, 60 + it % 80) },
        emissions = listOf(JournalPassage.Emission(t0 + 230_000L, t0 + 260_000L)))

    /** Everything the caption can say at once: TX, the S-meter, the frequency, a session's words. */
    private fun scene(paysage: Boolean, k: Float, tz: TimeZone = LegendePc.UTC, res: String = "M"): RenduPc.Scene {
        val e = entree()
        val sol = (0..40).map { s -> val f = s / 40.0; JournalPassage.Sol(t0 + s * 15_000L, 62.0 - 30 * f, -14.0 + 34 * f, 420.0) }
        val marques = listOf(Marque(TypeMarque.QSO, t0 + 240_000L, t0 + 240_000L, "F5XYZ", lat = 43.6, lon = 1.4))
        return RenduPc.Scene(e, emptyList(), marques, "F4IOZ", sol = sol, qth = 48.85 to 2.35, etiquetteQth = "F4IOZ · JN18DU",
            paysage = paysage, surGlobe = true, resolution = res, frequences = listOf(t0 to 145_800_000L), police = k, fuseau = tz,
            ouverture = false, recap = false).also { it.flashMs = 3_000L; it.legendeSession = Triple("Passage 12 / 150", "37 images reçues", true) }
    }

    @Test
    fun aucun_texte_de_la_legende_n_en_couvre_un_autre() {
        val instant = t0 + 242_000L   // transmitting, the S-meter on (the contact)
        for (paysage in listOf(true, false)) for (res in listOf("XS", "M", "HD")) for (k in listOf(0.6f, 1f, 1.6f, 2.5f)) {
            val (w, h) = RenduPc.tailleVideo(paysage, res)
            val sc = scene(paysage, k, res = res)
            val haut = RenduPc.hautVue(w, h, sc)
            val m = RenduPc.legende(w, h, sc, instant)
            val nom = "${if (paysage) "paysage" else "portrait"} $res ×$k"
            assertEquals(nom, (h - haut).toFloat(), m.hauteur, 1f)
            // The view keeps most of the frame.
            assertTrue(nom, haut >= h * 0.5f - 1)
            for (p in m.places) assertTrue("$nom ${p.texte} in the band", p.x >= 0 && p.x + p.l <= w + 0.5f && p.y >= 0 && p.y + p.h <= m.hauteur + 0.5f)
            for (i in m.places.indices) for (j in i + 1 until m.places.size) {
                val a = m.places[i]; val b = m.places[j]
                val r1 = java.awt.geom.Rectangle2D.Float(a.x, a.y, a.l, a.h); val r2 = java.awt.geom.Rectangle2D.Float(b.x, b.y, b.l, b.h)
                assertTrue("$nom: « ${a.texte} » over « ${b.texte} »", !r1.intersects(r2))
            }
            // The satellite and the time always there.
            val textes = m.places.map { it.el.texte }
            assertTrue("$nom $textes", "ISS" in textes && "2026-10-20" in textes && "15:44:02 UTC" in textes && m.places.none { it.texte.endsWith("…") })
            // At the usual size, everything.
            if (k == 1f && (paysage || res != "XS")) assertTrue("$nom $textes", textes.any { it.startsWith("TCA") } && textes.any { it.startsWith("AOS +") } &&
                textes.any { it.startsWith("Az") } && textes.contains("Passage 12 / 150") && m.places.any { it.el.sorte == LegendePc.Sorte.SMETRE })
        }
    }

    @Test
    fun la_bande_du_16_9_reste_basse() {
        // Everything on (TX, S-meter, frequency, a session's words): the band at most 22 % of a 16:9 frame,
        // 28 % with the largest texts; the upright phone's as before.
        val instant = t0 + 242_000L
        for (res in listOf("XS", "M", "HD")) for (k in listOf(0.6f, 1f, 1.6f, 2.5f)) {
            val (w, h) = RenduPc.tailleVideo(true, res)
            val part = 1f - RenduPc.hautVue(w, h, scene(true, k, res = res)).toFloat() / h
            println("bande 16:9 $res ×$k : %.1f %%".format(part * 100))
            assertTrue("$res ×$k: ${part * 100} %", part <= (if (k <= 1f) 0.22f else 0.28f) + 0.002f)
            val m = RenduPc.legende(w, h, scene(true, k, res = res), instant)
            if (k == 1f) assertTrue("$res: everything at the usual size", m.places.size >= 14)
        }
        for (k in listOf(0.6f, 1f, 1.6f, 2f, 2.5f)) {
            val (w, h) = RenduPc.tailleVideo(false, "M", vertical = true)
            println("bande 9:16 ×$k : %.1f %%".format(100 - RenduPc.hautVue(w, h, scene(false, k).let { RenduPc.Scene(it.e, it.prevue, it.marques, it.indicatif,
                vertical = true, frequences = it.frequences, police = k) }) * 100f / h))
        }
    }

    @Test
    fun les_images_d_exemple() {
        val instant = t0 + 242_000L
        for (paysage in listOf(true, false)) for (k in listOf(1f, 1.6f, 2f)) for (utc in listOf(true, false)) {
            val (w, h) = RenduPc.tailleVideo(paysage, "M")
            val sc = scene(paysage, k, if (utc) LegendePc.UTC else TimeZone.getTimeZone("Europe/Paris"))
            val img = RenduPc.image(w, h, sc, instant)
            ImageIO.write(img, "png", File(echantillons, "legende-${if (paysage) "paysage" else "portrait"}-police$k-${if (utc) "utc" else "locale"}.png"))
        }
        // The smallest and the largest, the phone's portrait in XS.
        val (w, h) = RenduPc.tailleVideo(false, "XS")
        ImageIO.write(RenduPc.image(w, h, scene(false, 2.5f, res = "XS"), instant), "png", File(echantillons, "legende-portrait-XS-police2.5.png"))
        val (w2, h2) = RenduPc.tailleVideo(true, "XS")
        ImageIO.write(RenduPc.image(w2, h2, scene(true, 2.5f, res = "XS"), instant), "png", File(echantillons, "legende-paysage-XS-police2.5.png"))
        // The opening card at scale 2.
        ImageIO.write(RenduPc.titre(1280, 720, scene(true, 2f, TimeZone.getTimeZone("Europe/Paris"))), "png", File(echantillons, "titre-police2-locale.png"))
    }
}
