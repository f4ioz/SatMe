/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RetentionSonTest {

    private val jour = 86_400_000L
    private val maintenant = 1_792_900_000_000L   // 24/10/2026

    private fun nom(joursAvant: Int, sat: String = "ISS"): String {
        val f = java.text.SimpleDateFormat("yyyyMMdd'_'HHmmss'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        return "SatMe_${sat}_" + f.format(java.util.Date(maintenant - joursAvant * jour)) + ".wav"
    }

    private fun fichier(n: String, mo: Int, joursAvant: Int) = RetentionSon.Fichier(n, mo * 1_048_576L, maintenant - joursAvant * jour + 600_000L)

    @Test fun par_defaut_rien_n_est_supprime() {
        val l = (1..30).map { fichier(nom(it), 10, it) }
        val p = RetentionSon.plan(RetentionSon.Reglages(), l, l.map { it.nom }.toSet(), emptyMap(), emptySet(), emptySet(), emptySet(), maintenant)
        assertTrue(p.supprimer.isEmpty())
        assertEquals(30, p.sstv)
    }

    @Test fun jamais_une_liee_a_une_planche_ni_gardee_ni_du_journal_ni_recente_ni_hors_sstv() {
        val planche = fichier(nom(40), 10, 40); val gardee = fichier(nom(41), 10, 41); val image = fichier(nom(42), 10, 42)
        val journal = fichier(nom(43), 10, 43); val vieille = fichier(nom(44), 10, 44); val recente = fichier(nom(0), 10, 0)
        val voix = fichier(nom(50, "FO-29"), 10, 50)
        val tous = listOf(planche, gardee, image, journal, vieille, recente, voix)
        val sstv = setOf(planche, gardee, image, journal, vieille, recente).map { it.nom }.toSet()
        val liees = mapOf(planche.nom to listOf("a.png"), gardee.nom to listOf("b.png"), image.nom to listOf("c.png"))
        val p = RetentionSon.plan(RetentionSon.Reglages(jours = 30, maxMo = 1), tous, sstv, liees, setOf("a.png"), setOf("b.png"), setOf(journal.nom), maintenant)
        assertEquals(setOf(image.nom, vieille.nom), p.supprimer.map { it.nom }.toSet())
        assertEquals(RetentionSon.Raison.PLANCHE, p.proteges[planche.nom])
        assertEquals(RetentionSon.Raison.GARDEE, p.proteges[gardee.nom])
        assertEquals(RetentionSon.Raison.JOURNAL, p.proteges[journal.nom])
        assertEquals(RetentionSon.Raison.RECENT, p.proteges[recente.nom])
        assertFalse(p.supprimer.any { it.nom == voix.nom })
    }

    @Test fun la_taille_supprime_les_plus_vieilles_d_abord_jusqu_a_la_limite() {
        val l = (2..11).map { fichier(nom(it), 100, it) }   // 1000 Mo
        val p = RetentionSon.plan(RetentionSon.Reglages(maxMo = 700), l, l.map { it.nom }.toSet(), emptyMap(), emptySet(), emptySet(), emptySet(), maintenant)
        assertEquals(listOf(nom(11), nom(10), nom(9)), p.supprimer.map { it.nom })
    }

    @Test fun appliquer_ne_supprime_que_le_plan_et_le_note() {
        val d = Files.createTempDirectory("ret").toFile()
        try {
            val a = File(d, nom(40)).apply { writeBytes(ByteArray(10)) }
            File(d, a.name.removeSuffix(".wav") + ".activite").writeText("x")
            val b = File(d, nom(41)).apply { writeBytes(ByteArray(10)) }
            val log = File(d, "retention-sons.log")
            val p = RetentionSon.Plan(listOf(RetentionSon.Fichier(a.name, 10, 0), RetentionSon.Fichier("../evil.wav", 1, 0)), emptyMap(), 2, 20)
            assertEquals(1, RetentionSon.applique(p, d, log, "plus de 30 jours", maintenant))
            assertFalse(a.exists()); assertTrue(b.exists())
            assertFalse(File(d, a.name.removeSuffix(".wav") + ".activite").exists())
            assertTrue(log.readText().contains(a.name))
            val g = File(d, "gardes.txt")
            assertEquals(setOf("x.png"), RetentionSon.marqueGarde(g, "x.png", true))
            assertEquals(setOf("x.png"), RetentionSon.litGardes(g))
            assertEquals(emptySet<String>(), RetentionSon.marqueGarde(g, "x.png", false))
        } finally { d.deleteRecursively() }
    }

    @Test fun les_liens_viennent_des_images() {
        val l = listOf(SstvMeta.SstvShot("a.png", recording = "SatMe_ISS_1.wav"), SstvMeta.SstvShot("b.png", recording = "SatMe_ISS_1.wav"), SstvMeta.SstvShot("c.png"))
        assertEquals(mapOf("SatMe_ISS_1.wav" to listOf("a.png", "b.png")), RetentionSon.liees(l))
    }
}
