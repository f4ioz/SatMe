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

    @Test
    fun les_contacts_voyagent_quand_on_le_demande() {
        val la = kotlin.io.path.createTempDirectory().toFile()
        try {
            val t = e.debutMs + 60_000L
            val q = fr.f4ioz.satcombo.data.LogEntry(t, e.satName, e.catnum, 120.5, 30.25, myLocator = "JN18FS",
                callsign = "ea4xyz", theirLocator = "IN80", nom = "Juan\tPedro", qth = "Madrid", mode = "FM",
                rstSent = "59", rstRcvd = "57", downlinkMhz = 436.795, uplinkMhz = 145.85, note = "deux\nlignes",
                courriel = "juan@example.org")
            val ailleurs = q.copy(satName = "ISS", catnum = 25544, callsign = "F1AAA")
            val o = ByteArrayOutputStream()
            JournalPaquet.emballe(o, e, emptyList(), contacts = listOf(q, ailleurs))
            val d = JournalPaquet.deballe(ByteArrayInputStream(o.toByteArray()), dossiers(la))
            // Only the pass's own (its satellite), everything but the email.
            assertEquals(1, d.contacts.size)
            val r = d.contacts[0]
            assertEquals("EA4XYZ", r.callsign); assertEquals(t, r.timeMs); assertEquals("Juan Pedro", r.nom)
            assertEquals("Madrid", r.qth); assertEquals("IN80", r.theirLocator); assertEquals(436.795, r.downlinkMhz, 1e-9)
            assertEquals("deux lignes", r.note); assertEquals("", r.courriel); assertEquals(30.25, r.elevationDeg, 1e-9)
            // Without the option, no contact in the file.
            val o2 = ByteArrayOutputStream()
            JournalPaquet.emballe(o2, e, emptyList())
            assertTrue(JournalPaquet.deballe(ByteArrayInputStream(o2.toByteArray()), dossiers(la)).contacts.isEmpty())
            assertNull(JournalPaquet.litContact("pas un contact"))
        } finally { la.deleteRecursively() }
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

    @Test
    fun les_signets_voyagent_avec_le_passage() {
        val la = kotlin.io.path.createTempDirectory().toFile()
        try {
            val o = ByteArrayOutputStream()
            val sg = listOf(JournalPassage.Signet(e.debutMs + 30_000L, e.catnum, "F4XYZ ?"))
            JournalPaquet.emballe(o, e, emptyList(), sg)
            val d = JournalPaquet.deballe(ByteArrayInputStream(o.toByteArray()), dossiers(la))
            assertEquals(sg, d.signets)
            // Those of another satellite are not taken.
            val o2 = ByteArrayOutputStream()
            JournalPaquet.emballe(o2, e, emptyList(), sg + JournalPassage.Signet(e.debutMs, 1, ""))
            assertEquals(sg, JournalPaquet.deballe(ByteArrayInputStream(o2.toByteArray()), dossiers(la)).signets)
        } finally { la.deleteRecursively() }
    }

    @Test
    fun les_fichiers_gardent_leur_heure_d_origine() {
        val ici = kotlin.io.path.createTempDirectory().toFile()
        val la = kotlin.io.path.createTempDirectory().toFile()
        try {
            val mp3 = File(ici, "SatMe_RS-44_20261006_193124Z.mp3").apply { writeBytes(ByteArray(1000)) }
            mp3.setLastModified(1_791_315_485_000L)
            val o = ByteArrayOutputStream()
            JournalPaquet.emballe(o, e, listOf("recordings" to mp3))
            JournalPaquet.deballe(ByteArrayInputStream(o.toByteArray()), dossiers(la))
            assertEquals(1_791_315_485_000L, File(la, "recordings/${mp3.name}").lastModified())
        } finally { ici.deleteRecursively(); la.deleteRecursively() }
    }

    /** Pass files shared by Olivier (~/SatMe-atelier/essais-passages), when present on this PC. */
    @Test
    fun les_fichiers_de_passage_reels_s_ouvrent_en_entier() {
        val dossier = File(System.getProperty("user.home"), "SatMe-atelier/essais-passages")
        val zips = dossier.listFiles { f -> f.name.endsWith(".zip") }?.sortedBy { it.name } ?: emptyList()
        org.junit.Assume.assumeTrue("pas de fichier de passage réel", zips.isNotEmpty())
        for (z in zips) {
            val la = kotlin.io.path.createTempDirectory().toFile()
            try {
                val d = JournalPaquet.deballe(z.inputStream(), dossiers(la))
                val e = d.entree
                assertTrue("${z.name} : pas de passage", e != null)
                // Every recording the pass names came with it.
                e!!.enregistrements.forEach { assertTrue("${z.name} : $it absent", File(la, "recordings/$it").isFile) }
                // A pack that kept its files' times gives them back (the older ones are dated on opening, in the app).
                val dates = java.util.zip.ZipFile(z).use { zf -> zf.getEntry(JournalPaquet.NOM_DATES)?.let { zf.getInputStream(it).readBytes().toString(Charsets.UTF_8) } }
                if (dates != null) dates.lines().filter { '\t' in it }.forEach { l ->
                    val (nom, ms) = l.split('\t')
                    assertEquals("${z.name} : $nom", ms.trim().toLong() / 1000, File(la, nom).lastModified() / 1000)
                } else assertEquals(d.poses, d.sansDate.size)
                println("${z.name} : ${e.satName}, ${e.points.size} points, ${e.signal.size} S-mètre, ${d.poses} fichiers, dates " +
                    (if (dates != null) "gardées" else "à refaire"))
            } finally { la.deleteRecursively() }
        }
    }
}
