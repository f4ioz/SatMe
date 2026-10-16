/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.pc.FichiersDatvPc.Raccourci
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO

class FichiersDatvPcTest {

    private val base = kotlin.io.path.createTempDirectory("satme-fichiers").toFile()
    private val home = File(base, "home").apply { mkdirs() }
    private val dehors = File(base, "dehors").apply { mkdirs() }
    private val envois = File(home, ".satme/datv-sources")
    private val f = FichiersDatvPc(envois, File(base, "cache"), { listOf(Raccourci("Dossier personnel", home), Raccourci("Fichiers envoyés", envois)) },
        ffmpeg = { null }, maxOctets = 1000)

    @After fun nettoie() { base.deleteRecursively() }

    private fun png(f: File, w: Int = 400, h: Int = 300) { f.parentFile.mkdirs(); ImageIO.write(BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "png", f) }

    @Test fun `seuls les dossiers permis se lisent`() {
        png(File(home, "Images/a.png")); File(home, "Images/b.txt").writeText("x"); File(home, "Images/c.mp4").writeText("x")
        File(home, ".cache").mkdirs(); png(File(dehors, "secret.png"))
        assertNotNull(f.permis(File(home, "Images/a.png").path))
        assertNull(f.permis(File(dehors, "secret.png").path))
        // ".." does not lead out.
        assertNull(f.permis(home.path + "/../dehors/secret.png"))
        assertNull(f.permis("Images/a.png"))
        assertNull(f.permis(""))
        assertNull(f.permis(home.path + "\u0000x"))
        val l = f.liste(File(home, "Images").path, "image")
        assertTrue(l.getBoolean("ok"))
        val noms = (0 until l.getJSONArray("entrees").length()).map { l.getJSONArray("entrees").getJSONObject(it).getString("nom") }
        assertEquals(listOf("a.png"), noms)
        assertEquals(home.toPath().toRealPath().toString(), l.getString("parent"))
        // Videos only: the mp4.
        val v = f.liste(File(home, "Images").path, "video").getJSONArray("entrees")
        assertEquals("c.mp4", v.getJSONObject(0).getString("nom"))
        // The home's parent is not offered, nor listed.
        val h = f.liste(home.path, "image")
        assertTrue(h.isNull("parent"))
        assertTrue((0 until h.getJSONArray("entrees").length()).none { h.getJSONArray("entrees").getJSONObject(it).getString("nom") == ".cache" })
        assertFalse(f.liste(dehors.path, "image").getBoolean("ok"))
        assertFalse(f.liste(base.path, "image").getBoolean("ok"))
        // Empty: the first shortcut, the home.
        assertEquals(home.toPath().toRealPath().toString(), f.liste("", "image").getString("chemin"))
    }

    @Test fun `un lien vers l'exterieur n'est pas suivi`() {
        png(File(dehors, "secret.png"))
        val lien = File(home, "raccourci")
        val lienFichier = File(home, "photo.png")
        runCatching {
            Files.createSymbolicLink(lien.toPath(), dehors.toPath())
            Files.createSymbolicLink(lienFichier.toPath(), File(dehors, "secret.png").toPath())
        }.onFailure { return } // no links on this system
        assertNull(f.permis(lien.path))
        assertNull(f.permis(File(lien, "secret.png").path))
        assertNull(f.permis(lienFichier.path))
        assertFalse(f.liste(lien.path, "image").getBoolean("ok"))
        assertNull(f.vignette(lienFichier.path))
        val e = f.liste(home.path, "").getJSONArray("entrees")
        assertTrue((0 until e.length()).none { e.getJSONObject(it).getString("nom") in setOf("raccourci", "photo.png") })
        // A link that stays inside is fine.
        png(File(home, "Images/a.png"))
        Files.createSymbolicLink(File(home, "dedans").toPath(), File(home, "Images").toPath())
        assertNotNull(f.permis(File(home, "dedans/a.png").path))
    }

    @Test fun `vignette d'une image, gardee en cache`() {
        png(File(home, "grande.png"), 1600, 900)
        val b = f.vignette(File(home, "grande.png").path)!!
        val im = ImageIO.read(ByteArrayInputStream(b))
        assertEquals(FichiersDatvPc.VIGNETTE, im.width)
        assertEquals(1, File(base, "cache").listFiles()!!.size)
        assertEquals(b.size, f.vignette(File(home, "grande.png").path)!!.size)
        File(home, "x.txt").writeText("x")
        assertNull(f.vignette(File(home, "x.txt").path))
    }

    @Test fun `noms des fichiers envoyes`() {
        assertEquals("photo.png", FichiersDatvPc.nomSur("photo.PNG")?.lowercase())
        assertEquals("passwd.png", FichiersDatvPc.nomSur("../../etc/passwd.png"))
        assertEquals("a_b.mp4", FichiersDatvPc.nomSur("C:\\Users\\x\\a\"b.mp4"))
        assertEquals("fichier.jpg", FichiersDatvPc.nomSur("...jpg"))
        assertNull(FichiersDatvPc.nomSur("script.sh"))
        assertNull(FichiersDatvPc.nomSur("sans-extension"))
        assertEquals("Vidéo été.webm", FichiersDatvPc.nomSur("Vidéo été.webm"))
    }

    @Test fun `envoi limite, nomme, liste et supprime`() {
        val octets = ByteArray(500) { it.toByte() }
        assertTrue(f.recoit("a.png", 2000, ByteArrayInputStream(ByteArray(2000))).isFailure)
        assertTrue(f.recoit("a.png", 0, ByteArrayInputStream(ByteArray(0))).isFailure)
        assertTrue(f.recoit("a.exe", 500, ByteArrayInputStream(octets)).isFailure)
        // Cut short: nothing kept.
        assertTrue(f.recoit("a.png", 800, ByteArrayInputStream(octets)).isFailure)
        assertTrue(envois.listFiles()!!.isEmpty())
        val a = f.recoit("../../a.png", 500, ByteArrayInputStream(octets)).getOrThrow()
        assertEquals(envois.canonicalFile, a.parentFile.canonicalFile)
        assertTrue(a.readBytes().contentEquals(octets))
        val b = f.recoit("a.png", 500, ByteArrayInputStream(octets)).getOrThrow()
        assertEquals("a-2.png", b.name)
        f.recoit("v.mp4", 10, ByteArrayInputStream(octets)).getOrThrow()
        val l = f.envoisJson().getJSONArray("fichiers")
        assertEquals(3, l.length())
        assertEquals(setOf("image", "video"), (0 until 3).map { l.getJSONObject(it).getString("genre") }.toSet())
        // Uploads are browsable (their own root).
        assertNotNull(f.permis(a.path))
        assertFalse(f.supprime("../home"))
        assertFalse(f.supprime(".satme"))
        assertFalse(f.supprime("absent.png"))
        assertTrue(f.supprime("a.png"))
        assertFalse(File(envois, "a.png").exists())
        assertEquals(2, f.envoisJson().getJSONArray("fichiers").length())
    }

    @Test fun `racines par defaut`() {
        val l = FichiersDatvPc.racinesParDefaut(envois)
        assertEquals(System.getProperty("user.home"), l.first().dossier.path)
        assertTrue(l.any { it.dossier == envois && it.racine })
    }
}
