/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.plateforme.StockageMemoire
import fr.f4ioz.satcombo.sstv.SstvMeta
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

/** The SSTV sheet on SatMe-PC: the phone's routes, from the PC's pictures, drawn by Java2D. */
class PlancheWebPcTest {

    private val dossier = File(System.getProperty("java.io.tmpdir"), "satme-planche-" + System.nanoTime()).apply { mkdirs() }
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun fin() { portee.cancel(); dossier.deleteRecursively() }

    private fun planche(dossier: File = this.dossier): PlancheWebPc {
        val st = StationPc(StockageMemoire(), Catalogue(File(dossier, "cache"), enLigne = false), PortsSeriePc(), portee)
        st.settings.callsign = "F4IOZ"
        val sstv = File(dossier, "sstv").apply { mkdirs() }
        // Three pictures received, the oldest first in the sheet.
        listOf(0L, 120_000L, 240_000L).forEachIndexed { i, dt ->
            val t = 1_791_792_000_000L + dt
            val nom = SstvMeta.fileName("ISS", t, "PD120", true)
            val b = BufferedImage(320, 256, BufferedImage.TYPE_INT_RGB).apply { createGraphics().apply { color = Color(80 * i, 120, 200); fillRect(0, 0, 320, 256) } }
            ImageIO.write(b, "png", File(sstv, nom))
            File(sstv, SstvMeta.sidecarName(nom)).writeText(SstvMeta.encode(SstvMeta.SstvShot(nom, "ISS", t, mode = "PD120", locator = "JN18FS", source = "live")))
        }
        val son = SonStation(SonPc(), st, File(dossier, "enregistrements"), sstv)
        return PlancheWebPc(st, son, dossier)
    }

    private fun PlancheWebPc.lit(route: String, p: Map<String, String> = emptyMap(), methode: String = "GET", corps: ByteArray? = null) =
        sert(route, methode, { p[it].orEmpty() }) { corps }

    @Test
    fun une_grille_remplie_par_ordre_de_reception_puis_telechargee() {
        val pl = planche()
        assertEquals(3, JSONArray(String(pl.lit("/galerie").corps)).length())
        val id = JSONObject(String(pl.lit("/nouveau", mapOf("type" to "grille")).corps)).getString("id")
        pl.lit("/remplit", mapOf("id" to id))
        val m = JSONObject(String(pl.lit("/modele", mapOf("id" to id)).corps))
        assertEquals(3, m.getInt("recues")); assertEquals("ISS · SSTV", m.getString("titre")); assertEquals("JN18FS", m.getString("locator"))
        val apercu = pl.lit("/apercu", mapOf("id" to id, "w" to "800"))
        assertEquals("image/jpeg", apercu.type)
        assertEquals(800, ImageIO.read(apercu.corps.inputStream()).width)
        val png = pl.lit("/exporte", mapOf("id" to id))
        assertTrue(png.entetes.contains("SatMe_Planche_ISS_20261012.png"))
        assertEquals(2400, ImageIO.read(png.corps.inputStream()).width)
        // Only a picture of the gallery is ever served.
        assertEquals("404 Not Found", pl.lit("/vignette", mapOf("f" to "../../reglages.properties")).statut)
    }

    @Test
    fun une_planche_ajustee_a_deux_images_puis_rendue_au_modele() {
        val pl = planche()
        val id = JSONObject(String(pl.lit("/nouveau", mapOf("type" to "tour")).corps)).getString("id")
        assertEquals(2, JSONObject(String(pl.lit("/nombre", mapOf("id" to id, "n" to "2")).corps)).getInt("cases"))
        val texte = JSONObject(String(pl.lit("/modele", mapOf("id" to id)).corps)).getString("texte")
        assertTrue(texte.contains("origine="))
        // The page sends the template back as it read it: the fitting stays.
        pl.lit("/enregistre", mapOf("id" to id), "POST", texte.toByteArray())
        val nom = JSONArray(String(pl.lit("/galerie").corps)).getJSONObject(0).getString("f")
        val r = JSONObject(String(pl.lit("/bascule", mapOf("id" to id, "f" to nom)).corps))
        assertTrue(r.getBoolean("ok")); assertEquals(3, r.getInt("cases"))
        assertEquals(800, ImageIO.read(pl.lit("/apercu", mapOf("id" to id, "w" to "800")).corps.inputStream()).width)
        assertEquals(12, JSONObject(String(pl.lit("/nombre", mapOf("id" to id, "n" to "0")).corps)).getInt("cases"))
        assertEquals("404 Not Found", pl.lit("/bascule", mapOf("id" to id, "f" to "../x.png")).statut)
    }

    @Test
    fun un_modele_importe_ses_cases_trouvees() {
        val pl = planche()
        // A template: a dark sheet with four light boxes.
        val b = BufferedImage(1600, 1100, BufferedImage.TYPE_INT_RGB)
        b.createGraphics().apply {
            color = Color(10, 20, 40); fillRect(0, 0, 1600, 1100); color = Color.WHITE
            for (i in 0..1) for (j in 0..1) fillRect(150 + i * 700, 120 + j * 480, 560, 400)
        }
        val o = ByteArrayOutputStream().also { ImageIO.write(b, "png", it) }.toByteArray()
        val r = JSONObject(String(pl.lit("/importe", mapOf("nom" to "ARISS"), "POST", o).corps))
        assertTrue(r.toString(), r.getBoolean("ok"))
        assertEquals(4, r.getInt("cases"))
        assertTrue(File(dossier, "planches").listFiles()!!.any { it.name.startsWith("fond_") })
        assertEquals("400 Bad Request", pl.lit("/importe", emptyMap(), "GET", o).statut)
    }

    private fun png(b: BufferedImage) = ByteArrayOutputStream().also { ImageIO.write(b, "png", it) }.toByteArray()

    /** A round patch on black, as a mission badge is scanned (exp75a.jpg's shape): an orange disc ringed blue. */
    private fun ecussonRond(): BufferedImage = BufferedImage(300, 306, BufferedImage.TYPE_INT_RGB).apply {
        createGraphics().apply {
            setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
            color = Color.BLACK; fillRect(0, 0, 300, 306)
            color = Color(26, 90, 190); fillOval(10, 13, 280, 280)
            color = Color(240, 140, 30); fillOval(22, 25, 256, 256)
            dispose()
        }
    }

    @Test
    fun un_fond_et_un_ecusson_sans_son_contour_sur_la_planche_exportee() {
        val pl = planche()
        val id = JSONObject(String(pl.lit("/nouveau", mapOf("type" to "grille")).corps)).getString("id")
        // The backgrounds offered, each with its small picture.
        val decors = JSONArray(String(pl.lit("/decors").corps))
        assertTrue(decors.length() >= 7)
        for (k in 0 until decors.length()) {
            val v = pl.lit("/decor-vignette", mapOf("d" to decors.getJSONObject(k).getString("id"), "w" to "120"))
            assertEquals("image/jpeg", v.type)
        }
        assertEquals("404 Not Found", pl.lit("/decor-vignette", mapOf("d" to "../reglages.properties")).statut)
        // A background of one's own: a plain green picture, far wider than the sheet — it fills it, cut, not stretched.
        val vert = BufferedImage(3000, 900, BufferedImage.TYPE_INT_RGB).apply { createGraphics().apply { color = Color(20, 160, 60); fillRect(0, 0, 3000, 900); color = Color.RED; fillRect(0, 0, 300, 900); dispose() } }
        val d = JSONObject(String(pl.lit("/decor", mapOf("id" to id), "POST", png(vert)).corps))
        assertTrue(d.getBoolean("ok"))
        // A patch, its black surround taken away.
        val e = JSONObject(String(pl.lit("/ecusson", mapOf("id" to id), "POST", png(ecussonRond())).corps))
        assertTrue(e.toString(), e.getBoolean("ok"))
        var texte = JSONObject(String(pl.lit("/modele", mapOf("id" to id)).corps)).getString("texte")
        assertTrue(texte.contains("decor=decor_")); assertTrue(texte.contains("ecusson=ecusson_"))
        // The page ticks « remove the surround » and moves it to the top-left corner; a file of elsewhere is refused.
        texte = texte.lines().joinToString("\n") { l ->
            if (l.startsWith("ecusson=")) { val p = l.removePrefix("ecusson=").split(';'); "ecusson=${p[0]};0;0;0.2;${p[4]};1\necusson=../x.png;0;0;0.1;0.1;0" } else l
        }
        pl.lit("/enregistre", mapOf("id" to id), "POST", texte.toByteArray())
        texte = JSONObject(String(pl.lit("/modele", mapOf("id" to id)).corps)).getString("texte")
        assertEquals(1, texte.lines().count { it.startsWith("ecusson=") })
        assertTrue(texte.lines().first { it.startsWith("ecusson=") }.endsWith(";1"))
        val b = ImageIO.read(pl.lit("/exporte", mapOf("id" to id)).corps.inputStream())
        assertEquals(2400, b.width)
        fun rgb(x: Int, y: Int) = Color(b.getRGB(x, y))
        // The patch's square is 480 px wide at the top left: its corner shows the background, its middle the patch.
        val coin = rgb(6, 6); assertTrue("$coin", coin.green > 120 && coin.red < 80)
        val milieu = rgb(240, 240); assertTrue("$milieu", milieu.red > 200 && milieu.green in 100..180)
        // The background's red left edge was cut off (cover, centred): none at the right edge of the sheet either.
        val droite = rgb(2395, 1600); assertTrue("$droite", droite.green > 120 && droite.red < 80)
        // A fourth patch is refused.
        repeat(2) { pl.lit("/ecusson", mapOf("id" to id), "POST", png(ecussonRond())) }
        assertTrue(!JSONObject(String(pl.lit("/ecusson", mapOf("id" to id), "POST", png(ecussonRond())).corps)).getBoolean("ok"))
    }

    @Test
    fun un_fond_nasa_integre_rendu_sur_la_planche() {
        val pl = planche()
        val id = JSONObject(String(pl.lit("/nouveau", mapOf("type" to "tour")).corps)).getString("id")
        var texte = JSONObject(String(pl.lit("/modele", mapOf("id" to id)).corps)).getString("texte")
        val avant = ImageIO.read(pl.lit("/apercu", mapOf("id" to id, "w" to "600")).corps.inputStream())
        pl.lit("/enregistre", mapOf("id" to id), "POST", (texte + "decor=nasa_lagune\n").toByteArray())
        texte = JSONObject(String(pl.lit("/modele", mapOf("id" to id)).corps)).getString("texte")
        assertTrue(texte.contains("decor=nasa_lagune"))
        val apres = ImageIO.read(pl.lit("/apercu", mapOf("id" to id, "w" to "600")).corps.inputStream())
        // The nebula is bright where the starry sky was dark.
        fun lum(b: BufferedImage) = (0 until b.width step 7).sumOf { x -> Color(b.getRGB(x, 2)).let { it.red + it.green + it.blue } }
        assertTrue(lum(apres) > lum(avant) * 3)
    }

    @Test
    fun un_modele_exporte_puis_importe_ailleurs_rend_la_meme_planche() {
        val pl = planche()
        val id = JSONObject(String(pl.lit("/nouveau", mapOf("type" to "grille")).corps)).getString("id")
        pl.lit("/decor", mapOf("id" to id), "POST", png(BufferedImage(900, 600, BufferedImage.TYPE_INT_RGB).apply {
            createGraphics().apply { color = Color(20, 60, 140); fillRect(0, 0, 900, 600); color = Color.ORANGE; fillRect(0, 0, 450, 300); dispose() } }))
        pl.lit("/ecusson", mapOf("id" to id), "POST", png(ecussonRond()))
        var texte = JSONObject(String(pl.lit("/modele", mapOf("id" to id)).corps)).getString("texte")
        texte = texte.lines().joinToString("\n") { if (it.startsWith("ecusson=")) it.dropLast(1) + "1" else it }
        pl.lit("/enregistre", mapOf("id" to id), "POST", texte.toByteArray())
        pl.lit("/nombre", mapOf("id" to id, "n" to "2"))
        // The file: a zip to download, named after the template, no picture received in it.
        val f = pl.lit("/modele-exporte", mapOf("id" to id))
        assertEquals("application/zip", f.type)
        assertTrue(f.entetes, f.entetes.contains("filename=\"SatMe_Modele_Modele-1.satme-planche\"") || f.entetes.contains(".satme-planche\""))
        val manifeste = JSONObject(String(fr.f4ioz.satcombo.sstv.PlancheFichier.entrees(f.corps).getValue("manifeste.json")))
        assertTrue(!manifeste.getString("planche").contains("image="))
        // Another station takes it in: a new template, filled with its own pictures.
        val ailleurs = File(dossier, "ailleurs").apply { mkdirs() }
        val pl2 = planche(ailleurs)
        val r = JSONObject(String(pl2.lit("/modele-importe", emptyMap(), "POST", f.corps).corps))
        assertTrue(r.toString(), r.getBoolean("ok")); assertEquals(2, r.getInt("cases"))
        val id2 = r.getString("id")
        val t2 = JSONObject(String(pl2.lit("/modele", mapOf("id" to id2)).corps)).getString("texte")
        assertTrue(t2.contains("origine=") && t2.contains(";0\n") && !t2.contains("image="))
        // Drawn the same: the original without its pictures, and the copy.
        val t1 = JSONObject(String(pl.lit("/modele", mapOf("id" to id)).corps)).getString("texte")
        pl.lit("/enregistre", mapOf("id" to id), "POST", t1.lines().filterNot { it.startsWith("image=") }.joinToString("\n").toByteArray())
        val a = ImageIO.read(pl.lit("/exporte", mapOf("id" to id)).corps.inputStream())
        val b = ImageIO.read(pl2.lit("/exporte", mapOf("id" to id2)).corps.inputStream())
        assertEquals(a.width, b.width); assertEquals(a.height, b.height)
        var ecart = 0
        for (y in 0 until a.height step 3) for (x in 0 until a.width step 3) {
            val p = a.getRGB(x, y); val q = b.getRGB(x, y)
            ecart = maxOf(ecart, (0..2).maxOf { k -> kotlin.math.abs(((p shr (8 * k)) and 0xFF) - ((q shr (8 * k)) and 0xFF)) })
        }
        assertTrue("écart $ecart", ecart <= 2)
        // Back to the first station: a second template, never over the first; by the picture route too.
        val r2 = JSONObject(String(pl.lit("/importe", emptyMap(), "POST", pl2.lit("/modele-exporte", mapOf("id" to id2)).corps).corps))
        assertTrue(r2.getBoolean("ok")); assertEquals("Modèle 1 (2)", r2.getString("nom"))
        assertEquals(2, JSONObject(String(pl.lit("/liste").corps)).getJSONArray("modeles").length())
    }

    @Test
    fun un_fichier_de_modele_douteux_est_refuse() {
        val pl = planche()
        val zip = ByteArrayOutputStream().also { o ->
            java.util.zip.ZipOutputStream(o).use { z ->
                z.putNextEntry(java.util.zip.ZipEntry("manifeste.json"))
                z.write(JSONObject().put("format", "satme-planche").put("version", 1).put("planche", "planche=1\nnom=X\n").toString().toByteArray())
                z.putNextEntry(java.util.zip.ZipEntry("../../reglages.properties")); z.write("x".toByteArray())
            }
        }.toByteArray()
        val r = JSONObject(String(pl.lit("/modele-importe", emptyMap(), "POST", zip).corps))
        assertTrue(!r.getBoolean("ok")); assertEquals("chemin", r.getString("raison"))
        val futur = ByteArrayOutputStream().also { o ->
            java.util.zip.ZipOutputStream(o).use { z ->
                z.putNextEntry(java.util.zip.ZipEntry("manifeste.json"))
                z.write(JSONObject().put("format", "satme-planche").put("version", 2).put("lisiblePar", 2).put("planche", "planche=1\nnom=X\n").toString().toByteArray())
            }
        }.toByteArray()
        assertEquals("version", JSONObject(String(pl.lit("/modele-importe", emptyMap(), "POST", futur).corps)).getString("raison"))
        assertEquals("400 Bad Request", pl.lit("/modele-importe", emptyMap(), "GET", zip).statut)
        assertEquals("404 Not Found", pl.lit("/modele-exporte", mapOf("id" to "../x")).statut)
        assertEquals(0, JSONObject(String(pl.lit("/liste").corps)).getJSONArray("modeles").length())
    }

    @Test
    fun la_page_servie_par_le_pc_parle_du_pc_pas_du_telephone() {
        fr.f4ioz.satcombo.i18n.I18n.apply("fr", "fr")
        val h = planche().page()
        assertTrue(h.contains("Enregistré sur le PC")); assertTrue(h.contains("Aucune image SSTV sur ce PC."))
        for (x in listOf("par le téléphone", "sur le téléphone", "au téléphone"))
            assertEquals(x, "", Regex(".{0,80}$x.{0,40}").find(h)?.value.orEmpty())
        // The phone's own page keeps its words.
        assertTrue(fr.f4ioz.satcombo.demo.PagePlanche.html().contains("Enregistré sur le téléphone"))
    }
}
