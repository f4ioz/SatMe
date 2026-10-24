/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.math.hypot

/** A template as one file: out, in again elsewhere, the same sheet; a bad file refused. */
class PlancheFichierTest {

    private val racine = File(System.getProperty("java.io.tmpdir"), "satme-pfichier-" + System.nanoTime()).apply { mkdirs() }
    @After fun fin() { racine.deleteRecursively() }
    private fun rangement(n: String) = Planche.Rangement(File(racine, n))

    private fun png(b: BufferedImage) = ByteArrayOutputStream().also { ImageIO.write(b, "png", it) }.toByteArray()
    private fun jpeg(b: BufferedImage) = ByteArrayOutputStream().also { ImageIO.write(b, "jpg", it) }.toByteArray()
    private fun image(w: Int, h: Int, type: Int = BufferedImage.TYPE_INT_RGB, px: (Int, Int) -> Int) =
        BufferedImage(w, h, type).apply { for (y in 0 until h) for (x in 0 until w) setRGB(x, y, px(x, y)) }

    /** A round patch on black, as exp75a.jpg: its surround to take away. */
    private fun ecussonRond() = image(120, 120) { x, y ->
        val d = hypot(x - 60.0, y - 60.0)
        if (d > 52) 0xFF050505.toInt() else if (d > 47) 0xFF1A5ABE.toInt() else 0xFF35B5E6.toInt()
    }

    /** What the platforms do on export: the patch decoded, its surround taken away, PNG. */
    private val detoure: (File) -> ByteArray? = { f ->
        val b = ImageIO.read(f)
        val px = PlancheDecor.detoure(b.getRGB(0, 0, b.width, b.height, null, 0, b.width), b.width, b.height)
        png(BufferedImage(b.width, b.height, BufferedImage.TYPE_INT_ARGB).apply { setRGB(0, 0, width, height, px, 0, width) })
    }

    /** A generic sheet as one makes it: fitted to four pictures, a background of one's own, two patches, a free text. */
    private fun planche(r: Planche.Rangement): Planche.Modele {
        val decor = r.nouveauNom("decor", "jpg")
        File(r.dossier, decor).writeBytes(jpeg(image(300, 200) { x, y -> (0xFF shl 24) or (x shl 8) or y }))
        val e1 = r.nouveauNom("ecusson", "png"); File(r.dossier, e1).writeBytes(png(ecussonRond()))
        val e2 = r.nouveauNom("ecusson", "png")
        File(r.dossier, e2).writeBytes(png(image(80, 40, BufferedImage.TYPE_INT_ARGB) { x, _ -> if (x < 40) 0x80FF0000.toInt() else 0xFF00FF00.toInt() }))
        var m = Planche.genereGrille(r.nouvelId(), "ARISS 2026").copy(titre = "ISS · SSTV", decor = decor)
        m = m.copy(textes = m.textes + Planche.Texte(Planche.Champ.LIBRE, Planche.Zone(0.4f, 0.9f, 0.3f, 0.05f), Planche.JAUNE, Planche.VOILE_NOIR, "73 de la station"))
        m = Planche.ajuste(m, listOf("a.png", "b.png", "c.png", "d.png"))
        m = Planche.ajouteEcusson(m, e1, 120, 120)
        m = Planche.ajouteEcusson(m, e2, 80, 40)
        m = m.copy(ecussons = m.ecussons.mapIndexed { i, e -> if (i == 0) e.copy(sansContour = true) else e })
        r.enregistre(m)
        return m
    }

    /** The saved lines with the files' names set aside (each folder names them its own way). */
    private fun lignes(m: Planche.Modele): String = Planche.ecrit(m.copy(id = "", fond = if (m.fond.isBlank()) "" else "F",
        decor = if (PlancheDecor.integre(m.decor) != null) m.decor else "D",
        ecussons = m.ecussons.mapIndexed { i, e -> e.copy(fichier = "E$i") }))

    private fun pixels(b: ByteArray): IntArray = ImageIO.read(b.inputStream()).let { it.getRGB(0, 0, it.width, it.height, null, 0, it.width) }

    @Test
    fun aller_retour_telephone_pc_telephone_la_meme_planche() {
        val a = rangement("a"); val b = rangement("b"); val c = rangement("c")
        val m = planche(a)
        val fichier = PlancheFichier.exporte(m, a, detoure)
        assertTrue(PlancheFichier.estZip(fichier))
        val mb = PlancheFichier.importe(fichier, b)
        assertEquals("ARISS 2026", mb.nom)
        assertTrue(mb.ajustee); assertTrue(mb.images.isEmpty())
        // The same lines but the pictures received; the patch already cut out.
        val attendu = m.copy(images = emptyMap(), ecussons = m.ecussons.map { it.copy(sansContour = false) })
        assertEquals(lignes(attendu), lignes(mb))
        assertArrayEquals(File(a.dossier, m.decor).readBytes(), File(b.dossier, mb.decor).readBytes())
        assertTrue(mb.decor.startsWith("decor_") && mb.ecussons.all { it.fichier.startsWith("ecusson_") })
        // The patch looks as it looked: its surround already transparent, as the sheet drew it.
        assertArrayEquals(pixels(detoure(File(a.dossier, m.ecussons[0].fichier))!!), pixels(File(b.dossier, mb.ecussons[0].fichier).readBytes()))
        assertArrayEquals(File(a.dossier, m.ecussons[1].fichier).readBytes(), File(b.dossier, mb.ecussons[1].fichier).readBytes())
        // And back: the same lines again, the same pictures.
        val mc = PlancheFichier.importe(PlancheFichier.exporte(mb, b, detoure), c)
        assertEquals(lignes(mb), lignes(mc))
        mb.ecussons.indices.forEach { i ->
            assertArrayEquals(File(b.dossier, mb.ecussons[i].fichier).readBytes(), File(c.dossier, mc.ecussons[i].fichier).readBytes())
        }
        // Filled with the importer's pictures: laid out as the template's maker would have it.
        val l = listOf("x1.png", "x2.png", "x3.png")
        assertEquals(Planche.ajuste(m, l).cases, Planche.ajuste(mc, l).cases)
        assertEquals(Planche.duModele(m).cases, Planche.duModele(mc).cases)
        assertEquals(Planche.duModele(m).textes, Planche.duModele(mc).textes)
        // Kept as SatMe keeps a template: read back from its folder the same.
        assertEquals(lignes(mc), lignes(c.modeles().single()))
    }

    @Test
    fun un_modele_importe_ariss_emporte_son_image() {
        val a = rangement("a"); val b = rangement("b")
        val id = a.nouvelId()
        val fond = jpeg(image(400, 283) { x, _ -> if (x % 50 < 40) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() })
        File(a.dossier, "fond_$id.jpg").writeBytes(fond)
        val m = Planche.genereImporte(id, "Série ARISS", "fond_$id.jpg", 400f / 283f, Planche.grille(12, ratioPlanche = 400f / 283f))
        a.enregistre(m)
        val mb = PlancheFichier.importe(PlancheFichier.exporte(m, a, detoure), b)
        assertEquals("fond_${mb.id}.jpg", mb.fond)
        assertArrayEquals(fond, File(b.dossier, mb.fond).readBytes())
        assertEquals(m.ratio, mb.ratio, 0f)
        assertEquals(lignes(m), lignes(mb))
        assertEquals(400 to 283, PlancheFichier.dimensions(fond))
    }

    @Test
    fun rien_de_personnel_sauf_si_on_le_demande() {
        val a = rangement("a")
        val m = planche(a)
        fun texte(f: ByteArray) = JSONObject(String(PlancheFichier.entrees(f).getValue(PlancheFichier.MANIFESTE))).getString("planche")
        val sans = texte(PlancheFichier.exporte(m, a, detoure))
        assertFalse(sans.contains("image=")); assertFalse(sans.contains("F4IOZ"))
        assertTrue(sans.contains("texte=INDICATIF;"))
        val avec = PlancheFichier.exporte(m, a, detoure, indicatif = "F4IOZ")
        assertTrue(texte(avec).contains("texte=LIBRE;") && texte(avec).contains(";F4IOZ"))
        assertFalse(texte(avec).contains("texte=INDICATIF;"))
        assertTrue(JSONObject(String(PlancheFichier.entrees(avec).getValue(PlancheFichier.MANIFESTE))).getBoolean("indicatifEcrit"))
    }

    @Test
    fun jamais_par_dessus_un_modele_le_nom_numerote() {
        val a = rangement("a"); val b = rangement("b")
        val m = planche(a)
        val f = PlancheFichier.exporte(m, a, detoure)
        val avant = Planche.ecrit(m)
        val n2 = PlancheFichier.importe(f, a)
        val n3 = PlancheFichier.importe(f, a)
        assertEquals(listOf("ARISS 2026", "ARISS 2026 (2)", "ARISS 2026 (3)"), a.modeles().map { it.nom }.sorted())
        assertEquals(3, a.modeles().map { it.id }.toSet().size)
        assertEquals(avant, Planche.ecrit(a.modeles().first { it.id == m.id }))
        assertNotEquals(n2.decor, n3.decor)
        assertEquals("ARISS 2026 (2)", PlancheFichier.nomLibre("ARISS 2026", listOf("ARISS 2026")))
        assertEquals("X (4)", PlancheFichier.nomLibre("X (2)", listOf("X", "X (2)", "X (3)")))
        assertEquals("SatMe_Modele_Serie-ARISS-n-3.satme-planche", PlancheFichier.nomFichier(m.copy(nom = "Série ARISS n°3")))
        assertTrue(b.modeles().isEmpty())
    }

    // ------------------------------------------------------- files from anyone

    private fun zip(vararg entrees: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { o ->
        ZipOutputStream(o).use { z -> entrees.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
    }.toByteArray()

    private fun manifeste(planche: String, version: Int = 1, lisiblePar: Int = 1, format: String = "satme-planche") =
        JSONObject().put("format", format).put("version", version).put("lisiblePar", lisiblePar).put("planche", planche).toString().toByteArray()

    private val petitPng get() = png(image(4, 4) { _, _ -> 0xFF336699.toInt() })

    private fun refuse(raison: String, f: ByteArray) {
        val r = rangement("r")
        try { PlancheFichier.importe(f, r); fail("accepté : $raison") } catch (e: PlancheFichier.Refus) { assertEquals(raison, e.raison) }
        // Nothing written, nothing written elsewhere.
        assertTrue(r.dossier.listFiles()!!.isEmpty())
        assertEquals(setOf("r"), racine.list()!!.toSet())
    }

    @Test
    fun un_fichier_malveillant_est_refuse_sans_rien_ecrire() {
        val texte = "planche=1\nnom=X\nratio=1.414\ncase=0.1;0.1;0.2;0.2\necusson=ecusson1.png;0.4;0.4;0.1;0.1;0\n"
        refuse("chemin", zip(PlancheFichier.MANIFESTE to manifeste(texte), "../ecusson1.png" to petitPng))
        refuse("chemin", zip(PlancheFichier.MANIFESTE to manifeste(texte), "a/ecusson1.png" to petitPng))
        refuse("chemin", zip(PlancheFichier.MANIFESTE to manifeste(texte), "/etc/passwd" to petitPng))
        // A name in the template that leads elsewhere: never followed, the file refused.
        refuse("image", zip(PlancheFichier.MANIFESTE to manifeste(texte.replace("ecusson1.png", "../../ecusson1.png")), "ecusson1.png" to petitPng))
        // Not a picture under a picture's name; a picture claiming to be huge.
        refuse("image", zip(PlancheFichier.MANIFESTE to manifeste(texte), "ecusson1.png" to "#!/bin/sh\nrm -rf /\n".toByteArray()))
        val geant = petitPng.copyOf().also { b -> listOf(16, 20).forEach { i -> b[i] = 0; b[i + 1] = 0; b[i + 2] = 0xC3.toByte(); b[i + 3] = 0x50 } }
        assertEquals(50000 to 50000, PlancheFichier.dimensions(geant))
        refuse("image", zip(PlancheFichier.MANIFESTE to manifeste(texte), "ecusson1.png" to geant))
        // A zip bomb: a few kB that would be 20 MB.
        refuse("taille", zip(PlancheFichier.MANIFESTE to manifeste(texte), "ecusson1.png" to ByteArray(20 * 1024 * 1024)))
        refuse("taille", ByteArray((PlancheFichier.TAILLE_MAX + 1).toInt()).also { it[0] = 'P'.code.toByte(); it[1] = 'K'.code.toByte(); it[2] = 3; it[3] = 4 })
        // Not a zip, no manifest, another format, a broken zip, too many entries.
        refuse("zip", petitPng)
        refuse("manifeste", zip("ecusson1.png" to petitPng))
        refuse("manifeste", zip(PlancheFichier.MANIFESTE to manifeste(texte, format = "autre")))
        refuse("manifeste", zip(PlancheFichier.MANIFESTE to "{pas du json".toByteArray()))
        refuse("zip", zip(PlancheFichier.MANIFESTE to manifeste(texte)).copyOf(60))
        refuse("zip", zip(*(Array(9) { "f$it.png" to petitPng } + (PlancheFichier.MANIFESTE to manifeste(texte)))))
    }

    @Test
    fun un_modele_qui_deborde_est_ramene_dans_la_planche() {
        val texte = "planche=1\nnom=" + "N".repeat(300) + "\nratio=NaN\n" + "case=-3;9;40;0.001\n".repeat(200) +
            "texte=LIBRE;0.1;0.1;0.2;0.1;-1;0;" + "x".repeat(500) + "\nimage=0;a.png\n"
        val m = PlancheFichier.importe(zip(PlancheFichier.MANIFESTE to manifeste(texte)), rangement("r"))
        assertEquals(60, m.cases.size); assertTrue(m.cases.all { it.x >= 0f && it.x + it.w <= 1f && it.y + it.h <= 1f })
        assertEquals(1.414f, m.ratio, 0f); assertEquals(200, m.textes.single().libre.length)
        assertTrue(m.nom.length <= 60); assertTrue(m.images.isEmpty())
    }

    @Test
    fun les_versions() {
        val texte = "planche=1\nnom=Futur\nratio=1.414\ncase=0.1;0.1;0.2;0.2\nquelquechose=de nouveau\n"
        // A later version that says this one can read it: read, what it adds left aside.
        val futur = JSONObject(String(manifeste(texte, version = 3, lisiblePar = 1))).put("nouveau", JSONObject().put("x", 1))
        val m = PlancheFichier.importe(zip(PlancheFichier.MANIFESTE to futur.toString().toByteArray(), "autre.bin" to ByteArray(10)), rangement("r"))
        assertEquals("Futur", m.nom); assertEquals(1, m.cases.size)
        assertEquals(3, PlancheFichier.lit(zip(PlancheFichier.MANIFESTE to futur.toString().toByteArray())).version)
        racine.listFiles()!!.forEach { it.deleteRecursively() }
        // One this version cannot read: said so.
        refuse("version", zip(PlancheFichier.MANIFESTE to manifeste(texte, version = 2, lisiblePar = 2)))
        refuse("manifeste", zip(PlancheFichier.MANIFESTE to manifeste(texte, version = 0)))
        // What SatMe writes today.
        val e = JSONObject(String(PlancheFichier.entrees(PlancheFichier.exporte(Planche.genereTour("t", "Tour"), rangement("r"), detoure))
            .getValue(PlancheFichier.MANIFESTE)))
        assertEquals(PlancheFichier.VERSION, e.getInt("version")); assertEquals(1, e.getInt("lisiblePar"))
        assertEquals("satme-planche", e.getString("format"))
    }

    @Test
    fun un_fichier_satme_n_est_pas_une_image() {
        // What older versions do with it: ask for a picture, find none (not PNG, not JPEG).
        val f = PlancheFichier.exporte(Planche.genereTour("t", "Tour"), rangement("r"), detoure)
        assertEquals(null, PlancheFichier.genre(f))
        assertEquals(null, ImageIO.read(f.inputStream()))
        ZipInputStream(f.inputStream()).use { assertEquals(PlancheFichier.MANIFESTE, it.nextEntry!!.name) }
    }
}
