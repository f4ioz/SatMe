/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.data.LogStore
import fr.f4ioz.satcombo.domain.Ft8
import fr.f4ioz.satcombo.domain.Ft8Signal
import fr.f4ioz.satcombo.domain.JournalFt
import fr.f4ioz.satcombo.domain.JournalPassage
import fr.f4ioz.satcombo.domain.SonPassage
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File

/** The FT8/FT4 messages of the journal's passes: kept, given to the page, read again from a recording, drawn in the files. */
class JournalFtPcTest {

    private val dossier = File(System.getProperty("java.io.tmpdir"), "satme-journal-ft-" + System.nanoTime()).apply { mkdirs() }
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun fin() { portee.cancel(); dossier.deleteRecursively() }

    private val t0 = 1_791_800_000_000L / 15_000L * 15_000L

    private fun entendu(t: Long, texte: String, db: Int = -10) = JournalFt.analyse(texte).let { (de, a, loc) ->
        Ft8Pc.Entendu(t, texte, de, a, loc, 1200, db, 0.2, "FT8") }

    @Test
    fun les_decodages_sont_gardes_avec_leur_passage() {
        var maintenant = t0 + 700_000L
        val ft = JournalFtPc(File(dossier, "journal"), dossier) { maintenant }
        val e = JournalPassage.Entree(44909, "RS-44", t0, t0 + 600_000L, "JN15MS")
        ft.recoit(listOf(entendu(t0 + 60_000L, "CQ EA4XYZ IM79"), entendu(t0 + 60_000L, "F4IOZ DL3AB JO62")), 44909)
        // Another satellite followed (QO-100 the same evening), outside the window: not the pass's.
        ft.recoit(listOf(entendu(t0 + 75_000L, "CQ G4ABC IO91")), 43700)
        ft.recoit(listOf(entendu(t0 + 3_600_000L, "CQ G4ABC IO91")), 44909)
        assertTrue(File(dossier, "ft8-journal").listFiles()!!.single().name.endsWith(".tsv"))
        assertEquals(listOf("CQ EA4XYZ IM79", "F4IOZ DL3AB JO62"), ft.duPassage(e).map { it.message })
        // The pass closed: its own kept beside it, still there once the days' files are gone.
        ft.fige(e)
        assertTrue(File(dossier, "journal/${e.id}.ft").isFile)
        File(dossier, "ft8-journal").deleteRecursively()
        maintenant += 30L * 86_400_000L
        val l = ft.duPassage(e)
        assertEquals(2, l.size); assertEquals("EA4XYZ", l[0].appelant); assertEquals(44909, l[0].catnum)
        ft.oublie(e)
        assertTrue(ft.duPassage(e).isEmpty())
    }

    @Test
    fun les_tranches_du_banc_comptent_pour_un_passage_d_avant() {
        val ft = JournalFtPc(File(dossier, "journal"), dossier) { t0 + 700_000L }
        File(dossier, "ft8-tranches").mkdirs()
        File(dossier, "ft8-tranches/x_FT4.json").writeText(JSONObject().put("debut", t0 + 120_000L).put("mode", "FT4")
            .put("decodes", org.json.JSONArray().put(JSONObject().put("texte", "CQ QF4ABC JN18").put("frequenceHz", 812.4).put("rapportDb", -3).put("decalageS", 0.4)))
            .toString())
        val d = ft.duPassage(JournalPassage.Entree(44909, "RS-44", t0, t0 + 600_000L, "JN15MS")).single()
        assertEquals("FT4", d.mode); assertEquals(812, d.hz); assertEquals("QF4ABC", d.appelant); assertEquals(t0 + 120_000L, d.tMs)
    }

    /** A CQ from F4IOZ in IN77 at 1000 Hz, 0.5 s into its slot, as the engine's tests build it. */
    private fun cq(): FloatArray {
        val message = BooleanArray(Ft8.BITS_MESSAGE)
        Ft8.ecritEntier(message, 0, 28, 2L)
        Ft8.ecritEntier(message, 29, 28, Ft8.indicatifVers28("F4IOZ")!!)
        Ft8.ecritEntier(message, 59, 15, (('I' - 'A') * 1800 + ('N' - 'A') * 100 + 7 * 10 + 7).toLong())
        Ft8.ecritEntier(message, 74, 3, 1L)
        val bits = BooleanArray(Ft8.BITS)
        System.arraycopy(Ft8.avecControle(message), 0, bits, 0, Ft8.BITS_UTILES)
        val r = java.util.Random(1)
        for (i in Ft8.BITS_UTILES until Ft8.BITS) bits[i] = r.nextBoolean()
        return Ft8Signal.synthetise(Ft8.bitsVersSymboles(bits), Ft8Signal.FT8, 1000.0, 0.5)
    }

    @Test
    fun l_enregistrement_du_passage_se_redecode() {
        // A recording at 48 kHz starting 4 s before a slot: silence, then the CQ in its slot, then a little more.
        val rate = 48_000
        val debut = t0 - 4_000L
        val a = Ft8Signal.reechantillonne(cq(), Ft8Signal.FT8.cadenceHz, rate.toDouble())
        val n = rate * 21
        val pcm = ShortArray(n) { i -> val k = i - 4 * rate; if (k in a.indices) (a[k] * 0.4f * 32767).toInt().toShort() else 0 }
        val wav = File(dossier, "enregistrement.wav")
        RenduPc.ecritWav(fr.f4ioz.satcombo.domain.SourcePcm.de(pcm), rate, wav)
        val ft = JournalFtPc(File(dossier, "journal"), dossier) { t0 + 700_000L }
        val e = JournalPassage.Entree(44909, "RS-44", t0 - 60_000L, t0 + 600_000L, "JN15MS")
        val progres = ArrayList<Float>()
        val k = ft.redecode(e, listOf(SonPassage.Morceau(wav, debut, 0L, JournalPc.dureeWav(wav))), JournalPc.decodeur, "FT8") { progres += it }
        assertEquals(1, k)
        val d = ft.duPassage(e).single()
        assertEquals("CQ F4IOZ IN77", d.message); assertEquals(t0, d.tMs); assertEquals("F4IOZ", d.appelant)
        assertEquals(1000.0, d.hz.toDouble(), 10.0); assertEquals(0.5, d.dt, 0.3)
        assertEquals(1f, progres.last(), 0f)
        // Read again: nothing twice.
        ft.redecode(e, listOf(SonPassage.Morceau(wav, debut, 0L, JournalPc.dureeWav(wav))), JournalPc.decodeur, "FT8")
        assertEquals(1, ft.duPassage(e).size)
    }

    @Test
    fun le_panneau_est_dessine_a_sa_place_avec_sa_visibilite() {
        val l = (0 until 6).map { JournalFt.Decode(t0 + it * 15_000L, "FT8", -it, 0.1, 900, "CQ EA4XYZ IM79", "EA4XYZ", null, "IM79") }
        fun dessin(op: Int): BufferedImage = BufferedImage(1000, 500, BufferedImage.TYPE_INT_RGB).also { b ->
            val g = b.createGraphics(); g.color = Color.WHITE; g.fillRect(0, 0, 1000, 500)
            PanneauFtPc.dessine(g, l, JournalFt.Affichage(cadre = JournalFt.Cadre(0.1f, 0.2f, 0.4f, 0.5f), opacite = op), t0 + 60_000L, 1000f, 500f, "F4IOZ", emptySet())
            g.dispose()
        }
        // Its rectangle: 400×250 from (100, 100). A point of its background (its bottom margin, no text) and one outside.
        val m = JournalFt.mise(400f, 250f)
        val y = (350 - m.marge / 2).toInt()
        fun rouge(b: BufferedImage, x: Int, yy: Int) = b.getRGB(x, yy) shr 16 and 0xFF
        val plein = dessin(100); val moitie = dessin(50)
        // The background's own alpha (210/255) times the visibility, over white.
        assertEquals(255 * (1 - 210 / 255.0) + 5 * 210 / 255.0, rouge(plein, 300, y).toDouble(), 3.0)
        assertEquals(255 * (1 - 0.5 * 210 / 255.0) + 5 * 0.5 * 210 / 255.0, rouge(moitie, 300, y).toDouble(), 3.0)
        assertEquals(255, rouge(plein, 95, y)); assertEquals(255, rouge(plein, 300, 95)); assertEquals(255, rouge(plein, 505, 200))
        assertEquals(255, rouge(plein, 300, 355))
        // Fully transparent, or not shown: nothing drawn.
        assertEquals(255, rouge(dessin(0), 300, y))
    }

    @Test
    fun la_page_les_recoit_et_le_fichier_les_montre_ou_elle_les_a_mis() {
        val st = StationPc(StockageMemoire(), Catalogue(File(dossier, "cache"), enLigne = false), PortsSeriePc(), portee)
        st.settings.callsign = "F4IOZ"
        val carnet = CarnetPc(st, LogStore(File(dossier, "qso_log.json")))
        val son = SonStation(SonPc(), st, File(dossier, "enregistrements"), File(dossier, "sstv"))
        val j = JournalPc(st, carnet, son, File(dossier, "journal"))
        val web = JournalWebPc(j, st, dossier)
        val e = JournalPassage.Entree(27607, "SO-50", t0, t0 + 600_000L, "JN18FS",
            points = (0..10).map { JournalPassage.Point(t0 + it * 60_000L, 100.0 + it * 10, 5.0 + it) })
        j.rangement.enregistre(e)
        j.ft.garde(e, (0 until 20).map { JournalFt.Decode(t0 + it * 15_000L, "FT8", -it, 0.1, 900 + it, if (it == 3) "F4IOZ EA4XYZ IM79" else "CQ DL3AB JO62", catnum = 27607) })
        fun lit(route: String, p: Map<String, String> = emptyMap()) = String(web.sert(route) { p[it].orEmpty() }.corps)

        val q = JSONObject(lit("/passage", mapOf("id" to e.id)))
        val ft = q.getJSONArray("ft")
        assertEquals(20, ft.length())
        val moi = ft.getJSONObject(3)
        assertEquals(1, moi.optInt("mi")); assertEquals("EA4XYZ", moi.getString("de")); assertTrue(moi.optString("py").isNotEmpty()); assertTrue(moi.optString("dr").isNotEmpty())
        assertEquals(1, ft.getJSONObject(0).optInt("c"))
        assertTrue(q.getJSONObject("ftAff").getBoolean("aff"))

        // Placed and dimmed on the page: kept with the pass.
        val r = JSONObject(lit("/ft/choix", mapOf("id" to e.id, "ft" to "1", "ftCadre" to "0.1,0.2,0.4,0.5", "ftOpacite" to "60", "ftOrdre" to "haut")))
        assertTrue(r.getBoolean("ok"))
        val kept = j.passage(e.id)!!.options.ft
        assertEquals(JournalFt.Cadre(0.1f, 0.2f, 0.4f, 0.5f), kept.cadre); assertEquals(60, kept.opacite); assertTrue(kept.recentEnHaut)
        assertEquals(60, JSONObject(lit("/passage", mapOf("id" to e.id))).getJSONObject("ftAff").getInt("op"))

        fun image(extra: Map<String, String>): BufferedImage {
            val f = JSONObject(lit("/fabrique", mapOf("id" to e.id, "quoi" to "image", "t" to "${t0 + 300_000L}", "format" to "paysage") + extra))
            var tr: JSONObject
            do { Thread.sleep(50); tr = JSONObject(lit("/travail", mapOf("n" to f.getString("travail")))) } while (!tr.getBoolean("fini"))
            return javax.imageio.ImageIO.read(web.sert("/fichier") { mapOf("n" to f.getString("travail"))[it].orEmpty() }.fichier!!)
        }
        // Where the page put it, in the 16:9 file's view.
        val avec = image(emptyMap()); val sans = image(mapOf("ft" to "0"))
        var x0 = 9999; var y0 = 9999; var x1 = -1; var y1 = -1
        for (y in 0 until 1080) for (x in 0 until 1920) if (avec.getRGB(x, y) != sans.getRGB(x, y)) {
            x0 = minOf(x0, x); y0 = minOf(y0, y); x1 = maxOf(x1, x); y1 = maxOf(y1, y)
        }
        // Its left and width: 10 % and 40 % of 1920 px (its border's half-width aside).
        assertEquals(192.0, x0.toDouble(), 3.0); assertEquals(960.0, x1.toDouble(), 3.0)
        // Its top and height: 20 % and 50 % of the view's height (the frame less its caption band, about 880 px here).
        val haut = (y0 + 1.5) / 0.2
        assertTrue("$haut", haut in 820.0..900.0)
        assertEquals(0.5 * haut, (y1 - y0).toDouble(), 5.0)
        // Nothing of it in the caption below the view.
        for (y in (haut + 3).toInt() until 1080) for (x in 0 until 1920 step 7) assertEquals(sans.getRGB(x, y), avec.getRGB(x, y))
        // A phone's 9:16: the same shares of its taller, narrower view.
        val tel = image(mapOf("format" to "telephone")); val telSans = image(mapOf("format" to "telephone", "ft" to "0"))
        val xs = (0 until 1080).filter { x -> (0 until 1920 step 4).any { y -> tel.getRGB(x, y) != telSans.getRGB(x, y) } }
        assertEquals(108.0, xs.first().toDouble(), 3.0); assertEquals(540.0, xs.last().toDouble(), 3.0)
        assertFalse(xs.isEmpty())
    }
}
