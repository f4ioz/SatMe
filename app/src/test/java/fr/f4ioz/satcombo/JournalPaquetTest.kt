/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.JournalPaquet
import fr.f4ioz.satcombo.domain.JournalPassage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class JournalPaquetTest {
    private val e = JournalPassage.Entree(27607, "SO-50", 1_791_199_396_195L, 1_791_199_560_436L, "JN18FT",
        enregistrements = listOf("SatMe_SO-50_20261005_112323Z.mp3"),
        points = (0..20).map { JournalPassage.Point(1_791_199_396_195L + it * 5_000L, 230.0 + it, 5.0 + it, null, null, null, null) })

    private fun dossiers(racine: File) = mapOf("recordings" to File(racine, "recordings"), "sstv" to File(racine, "sstv"))

    @Test
    fun un_passage_fait_l_aller_retour_avec_son_son_et_ses_images() {
        val ici = kotlin.io.path.createTempDirectory().toFile()
        val la = kotlin.io.path.createTempDirectory().toFile()
        try {
            val mp3 = File(ici, "SatMe_SO-50_20261005_112323Z.mp3").apply { writeBytes(ByteArray(10_000) { it.toByte() }) }
            val png = File(ici, "SSTV_20261005_112400Z_PD120.png").apply { writeBytes(ByteArray(500) { 7 }) }
            val o = ByteArrayOutputStream()
            JournalPaquet.emballe(o, e, listOf("recordings" to mp3, "sstv" to png, "recordings" to File(ici, "absent.info")))
            val d = JournalPaquet.deballe(ByteArrayInputStream(o.toByteArray()), dossiers(la))
            assertEquals(e.id, d.entree!!.id); assertEquals(e.points.size, d.entree!!.points.size)
            assertEquals(2, d.poses)
            assertTrue(File(la, "recordings/${mp3.name}").readBytes().contentEquals(mp3.readBytes()))
            assertTrue(File(la, "sstv/${png.name}").isFile)
            // Opened again: nothing overwritten.
            val d2 = JournalPaquet.deballe(ByteArrayInputStream(o.toByteArray()), dossiers(la))
            assertEquals(0, d2.poses); assertEquals(2, d2.dejaLa)
            assertTrue(JournalPaquet.nom(e).startsWith("SatMe_passage_SO-50_20261005_"))
        } finally { ici.deleteRecursively(); la.deleteRecursively() }
    }

    private fun zip(vararg entrees: Pair<String, ByteArray>): ByteArray {
        val o = ByteArrayOutputStream()
        ZipOutputStream(o).use { z -> for ((n, b) in entrees) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return o.toByteArray()
    }

    @Test
    fun un_fichier_piege_ne_pose_rien_hors_de_ses_dossiers() {
        val la = kotlin.io.path.createTempDirectory().toFile()
        try {
            val b = zip(JournalPaquet.NOM_PASSAGE to JournalPassage.ecrit(e).toByteArray(),
                "recordings/../../evil.mp3" to byteArrayOf(1), "sstv/x.apk" to byteArrayOf(1),
                "shared_prefs/a.xml" to byteArrayOf(1), "recordings/.cache.mp3" to byteArrayOf(1),
                "recordings/bon.mp3" to byteArrayOf(1))
            val d = JournalPaquet.deballe(ByteArrayInputStream(b), dossiers(la))
            assertEquals(1, d.poses); assertEquals(4, d.refuses)
            assertEquals(listOf("bon.mp3"), File(la, "recordings").list()!!.toList())
            assertFalse(File(la.parentFile, "evil.mp3").exists())
            assertFalse(JournalPaquet.nomPermis("recordings", "a/b.mp3"))
            assertFalse(JournalPaquet.nomPermis("sstv", "x.png.."))
        } finally { la.deleteRecursively() }
    }

    @Test
    fun sans_passage_ce_n_est_pas_un_paquet() {
        val la = kotlin.io.path.createTempDirectory().toFile()
        try {
            val d = JournalPaquet.deballe(ByteArrayInputStream(zip("recordings/bon.mp3" to byteArrayOf(1))), dossiers(la))
            assertNull(d.entree)
            assertFalse(File(la, "recordings/bon.mp3").exists())
            assertNull(JournalPaquet.deballe(ByteArrayInputStream("pas un zip".toByteArray()), dossiers(la)).entree)
        } finally { la.deleteRecursively() }
    }
}
