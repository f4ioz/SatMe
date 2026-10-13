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

    private fun planche(): PlancheWebPc {
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
}
