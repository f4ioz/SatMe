/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** « Globe animé »: the video the page records itself, sent to SatMe-PC — its kind, its size, its first bytes, its name, ffmpeg's command, made an MP4. */
class VideoSkedNavigateurTest {

    private val de = 1_792_510_800_000L   // 2026-10-20 15:40 UTC
    private fun n(vertical: Boolean = false, images: Int = 810) = VideoSked.Navigateur("ISS (ZARYA)", "IN80", de, vertical, 30, images)
    private fun dossier(): File = Files.createTempDirectory("sked-navigateur").toFile().apply { deleteOnExit() }

    @Test
    fun seuls_webm_et_mp4_sont_admis() {
        assertEquals("webm", VideoSked.extensionNavigateur("video/webm;codecs=vp9"))
        assertEquals("webm", VideoSked.extensionNavigateur("Video/WebM"))
        assertEquals("mp4", VideoSked.extensionNavigateur("video/mp4; codecs=avc1.640028"))
        assertNull(VideoSked.extensionNavigateur("text/html"))
        assertNull(VideoSked.extensionNavigateur("video/x-matroska"))
        assertNull(VideoSked.extensionNavigateur(""))
        assertNull(VideoSked.extensionNavigateur(null))
    }

    @Test
    fun les_premiers_octets_disent_le_format() {
        val d = dossier()
        fun f(vararg b: Int) = File(d, "x" + b.size).apply { writeBytes(ByteArray(b.size) { b[it].toByte() }) }
        assertTrue(VideoSked.enteteAdmise("webm", f(0x1A, 0x45, 0xDF, 0xA3, 0x01, 0x00)))
        assertFalse(VideoSked.enteteAdmise("webm", f(0x3C, 0x68, 0x74, 0x6D, 0x6C)))
        val mp4 = File(d, "m.mp4").apply { writeBytes(byteArrayOf(0, 0, 0, 0x20) + "ftypisom".toByteArray()) }
        assertTrue(VideoSked.enteteAdmise("mp4", mp4))
        assertFalse("an MP4 is not a WebM", VideoSked.enteteAdmise("webm", mp4))
        assertFalse(VideoSked.enteteAdmise("mp4", f(0x1A, 0x45, 0xDF, 0xA3, 0, 0, 0, 0)))
        assertFalse(VideoSked.enteteAdmise("mp4", File(d, "absent")))
    }

    @Test
    fun le_nom_dit_le_globe_anime_et_le_format() {
        assertEquals("SatMe_Sked_ISS-ZARYA_IN80_20261020_1540Z_globe-anime.mp4", VideoSked.nomFichierNavigateur(n()))
        assertEquals("SatMe_Sked_ISS-ZARYA_IN80_20261020_1540Z_globe-anime_9x16.mp4", VideoSked.nomFichierNavigateur(n(vertical = true)))
        // Nothing of a path gets through, and the page serves it ([VideoSked.fichier]'s pattern).
        val m = VideoSked.nomFichierNavigateur(VideoSked.Navigateur("../../etc", "a/../b", de, false, 30, 1))
        assertTrue(m, m.matches(Regex("[A-Za-z0-9._-]+")) && !m.contains(".."))
    }

    @Test
    fun la_commande_de_ffmpeg_refait_le_rythme_et_un_mp4_lisible() {
        val c = VideoSked.commandeConversion("ffmpeg", File("/d/in.webm"), File("/d/out.mp4.part"), 30)
        fun apres(k: String) = c[c.indexOf(k) + 1]
        assertEquals("/d/in.webm", apres("-i"))
        assertTrue(apres("-vf"), apres("-vf").startsWith("setpts=N/(30*TB),") && apres("-vf").contains("trunc(iw/2)*2"))
        assertEquals("30", apres("-r"))
        assertEquals("libx264", apres("-c:v"))
        assertEquals("yuv420p", apres("-pix_fmt"))
        assertEquals("+faststart", apres("-movflags"))
        assertEquals("mp4", apres("-f"))
        assertEquals("pipe:1", apres("-progress"))
        assertTrue("no sound", "-an" in c)
        assertEquals("/d/out.mp4.part", c.last())
    }

    @Test
    fun la_demande_et_ses_bornes() {
        val a = VideoSked.navigateur { k -> mapOf("sat" to "AO-91", "autre" to "FN31", "de" to "$de", "format" to "telephone", "fps" to "500", "images" to "-3")[k].orEmpty() }
        assertEquals(VideoSked.Navigateur("AO-91", "FN31", de, true, 60, 1), a)
        val b = VideoSked.navigateur { "" }
        assertEquals(VideoSked.Navigateur("sat", "", 0L, false, 30, 810), b)
    }

    @Test
    fun trop_gros_vide_ou_d_un_autre_type_refuse_sans_rien_lire() {
        val v = VideoSked(dossier()) { _, _, _ -> null }
        var lu = false
        val lis: (File) -> Boolean = { lu = true; true }
        assertNotNull(v.recoitNavigateur("video/webm", VideoSked.MAX_NAVIGATEUR + 1, n(), lis))
        assertNotNull(v.recoitNavigateur("video/webm", 0, n(), lis))
        assertNotNull(v.recoitNavigateur("application/octet-stream", 1000, n(), lis))
        assertNotNull(v.recoitNavigateur(null, 1000, n(), lis))
        assertFalse(lu)
        assertFalse(v.etat.enCours)
    }

    @Test
    fun ce_qui_n_est_pas_une_video_est_refuse_et_efface() {
        RenduPc.ffmpeg() ?: return
        val d = dossier()
        val v = VideoSked(d) { _, _, _ -> null }
        val err = v.recoitNavigateur("video/webm", 20, n()) { f -> f.writeText("<html>pas une vidéo"); true }
        assertNotNull(err)
        assertFalse(v.etat.enCours)
        assertEquals(err, v.etat.erreur)
        assertTrue(d.listFiles().orEmpty().joinToString { it.name }, d.listFiles().orEmpty().isEmpty())
        // Cut on its way: refused too.
        assertNotNull(v.recoitNavigateur("video/webm", 20, n()) { false })
        assertTrue(d.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun une_video_webm_devient_un_mp4_a_cote_des_autres() {
        val ff = RenduPc.ffmpeg() ?: return
        val d = dossier()
        // What the browser would send: one second of WebM (VP8), 30 images.
        val webm = File(Files.createTempDirectory("sked-webm").toFile(), "page.webm")
        val fait = ProcessBuilder(ff, "-y", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc=size=320x240:rate=30", "-t", "1", "-c:v", "libvpx", "-b:v", "500k", webm.path)
            .redirectErrorStream(true).start().let { it.inputStream.readBytes(); it.waitFor() == 0 }
        if (!fait || !webm.isFile) return   // an ffmpeg without VP8
        val v = VideoSked(d) { _, _, _ -> null }
        assertNull(v.recoitNavigateur("video/webm;codecs=vp8", webm.length(), n(images = 30)) { f -> webm.copyTo(f, overwrite = true); true })
        assertEquals("une vidéo est déjà en cours", v.recoitNavigateur("video/webm", webm.length(), n()) { f -> webm.copyTo(f, overwrite = true); true })
        val fin = System.currentTimeMillis() + 120_000L
        while (v.etat.enCours && System.currentTimeMillis() < fin) Thread.sleep(100)
        assertEquals("", v.etat.erreur)
        assertEquals(VideoSked.nomFichierNavigateur(n()), v.etat.nom)
        val mp4 = v.fichier(v.etat.nom)
        assertNotNull(mp4)
        assertTrue(VideoSked.enteteAdmise("mp4", mp4!!))
        // Only the MP4 is left: the browser's file and the part made gone.
        assertEquals(listOf(mp4.name), d.listFiles().orEmpty().map { it.name })
    }
}
