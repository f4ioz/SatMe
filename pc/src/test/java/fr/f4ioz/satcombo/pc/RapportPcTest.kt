/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.data.LogEntry
import fr.f4ioz.satcombo.data.LogStore
import fr.f4ioz.satcombo.domain.JournalPassage
import fr.f4ioz.satcombo.domain.SpectrePassage
import fr.f4ioz.satcombo.espace.MeteoSpatiale
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import fr.f4ioz.satcombo.sdr.SigMF
import fr.f4ioz.satcombo.sstv.SstvMeta
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipFile

/** The pass report built from a data folder's files, as SatMe-PC keeps them; and the detailed journal that feeds it. */
class RapportPcTest {

    private val dossier = File(System.getProperty("java.io.tmpdir"), "satme-rapport-" + System.nanoTime()).apply { mkdirs() }
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun fin() { portee.cancel(); dossier.deleteRecursively() }

    private val t0 = 1_791_800_000_000L   // the pass starts here, 10 minutes long
    private fun stamp(t: Long) = SimpleDateFormat("yyyyMMdd'_'HHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(t))
    private fun jour(t: Long) = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(t))

    /** The data folder of a pass of SO-50 with everything SatMe-PC may keep of it. */
    private class Fixtures(val j: JournalPc, val st: StationPc, val rp: RapportPc, val e: JournalPassage.Entree)

    private fun fixtures(): Fixtures {
        val st = StationPc(StockageMemoire(), Catalogue(File(dossier, "cache"), enLigne = false), PortsSeriePc(), portee)
        st.settings.journalDecalageQsoS = 0
        st.settings.callsign = "F4XYZ"
        val carnet = CarnetPc(st, LogStore(File(dossier, "qso_log.json")))
        val son = SonStation(SonPc(), st, File(dossier, "enregistrements"), File(dossier, "sstv"))
        val j = JournalPc(st, carnet, son, File(dossier, "journal"))
        // A recording (5 s), a contact, a picture.
        val nomSon = "SatMe_SO-50_${stamp(t0)}.wav"
        File(dossier, "enregistrements").mkdirs()
        EnregistreurWav(File(dossier, "enregistrements/$nomSon"), 16_000).apply { val s = ShortArray(5 * 16_000); ajoute(s, s.size); ferme() }
        val e = JournalPassage.Entree(27607, "SO-50", t0, t0 + 600_000L, "JN18FS", transpondeur = "FM 436.795",
            enregistrements = listOf(nomSon),
            points = (0..10).map { JournalPassage.Point(t0 + it * 60_000L, 100.0 + it * 10, if (it <= 5) it * 9.0 else (10 - it) * 9.0) },
            signal = (0..600 step 5).map { JournalPassage.Signal(t0 + it * 1000L, if (it in 200..400) 7 else 0) })
        j.rangement.enregistre(e)
        carnet.store.add(LogEntry(t0 + 120_000L, "SO-50", 27607, 120.0, 7.0, callsign = "F5ABC", theirLocator = "JN03", mode = "FM", nom = "Jean",
            rstSent = "59", rstRcvd = "57"))
        val image = SstvMeta.fileName("SO-50", t0 + 240_000L, "Robot 36", true)
        File(dossier, "sstv").mkdirs()
        javax.imageio.ImageIO.write(java.awt.image.BufferedImage(320, 240, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", File(dossier, "sstv/$image"))
        File(dossier, "sstv/" + SstvMeta.sidecarName(image)).writeText(
            SstvMeta.encode(SstvMeta.SstvShot(image, "SO-50", t0 + 240_000L, mode = "Robot 36", source = "live")))
        // Telemetry: two frames of the pass, one of another satellite.
        File(dossier, "telemesure").mkdirs()
        File(dossier, "telemesure/funcube-${jour(t0).replace("-", "")}.jsonl").writeText(
            "{\"t\":${t0 + 300_000L},\"satellite\":\"SO-50\",\"valide\":true}\n{\"t\":${t0 + 310_000L},\"satellite\":\"SO-50\",\"valide\":false}\n" +
                "{\"t\":${t0 + 320_000L},\"satellite\":\"AO-73\",\"valide\":true}\n")
        // The voice written down.
        File(dossier, "phonie").mkdirs()
        File(dossier, "phonie/${jour(t0)}.jsonl").writeText(
            "{\"src\":\"carte\",\"sat\":\"SO-50\",\"texte\":\"CQ satellite de F5ABC\",\"langue\":\"fr\",\"t\":${t0 + 118_000L},\"duree\":3.1,\"id\":1}\n" +
                "{\"src\":\"carte\",\"sat\":\"QO-100\",\"texte\":\"autre chose\",\"langue\":\"fr\",\"t\":${t0 + 119_000L},\"duree\":3.1,\"id\":2}\n")
        // The detailed journal: its lines and its decodes.
        val sp = File(dossier, "journal/spectres").apply { mkdirs() }
        val lignes = (0 until 300).map { i -> SpectrePassage.Ligne(t0 + i * 2_000L, FloatArray(128) { k -> if (i in 100..199 && k in 63..64) -75f else -100f }) }
        File(sp, SpectrePassage.nom(27607, t0)).outputStream().use { SpectrePassage.ecrit(SpectrePassage.Bande(27607, "SO-50", 25_000.0, lignes), it) }
        File(sp, "27607_$t0.decodes.jsonl").writeText("{\"t\":${t0 + 200_000L},\"g\":\"CW\",\"x\":\"CQ DE F1AAA\"}\n")
        // The Montage.
        File(dossier, "montage.json").writeText(fr.f4ioz.satcombo.station.Montage.ecrit(fr.f4ioz.satcombo.station.Montage.Chaine(listOf(
            fr.f4ioz.satcombo.station.Montage.Element("p1", "ts2000"), fr.f4ioz.satcombo.station.Montage.Element("a1", "yagi-70cm", "Yagi du toit")))).toString())
        val r = RapportPc(j, st, dossier, kp = { listOf(MeteoSpatiale.Kp(t0 + 1_800_000L, 4.33, "observe")) },
            ft8 = { listOf(Triple(t0 + 150_000L, "CQ F1BBB JN18", "FT8 · -12 dB")) }, horloge = { t0 + 3_600_000L })
        return Fixtures(j, st, r, e)
    }

    @Test
    fun le_rapport_rassemble_tout_ce_que_le_dossier_garde_du_passage() {
        val fx = fixtures(); val rp = fx.rp; val e = fx.e
        val r = rp.rapport(e, RapportPc.Forme.PAGE, "pc")
        assertEquals("SO-50", r.satellite); assertEquals(45.0, r.elMax, 0.01); assertEquals("F4XYZ", r.indicatif)
        assertEquals(listOf("Poste" to "Kenwood TS-2000", "Antenne" to "Yagi du toit"), r.station.map { it.genre to it.nom })
        assertEquals(4.33, r.kp!!, 0.0)
        assertEquals(listOf("F5ABC"), r.contacts.map { it.indicatif })
        assertEquals(1, r.images.size); assertEquals("Robot 36", r.images[0].legende)
        assertEquals(2, r.tramesTelemesure); assertEquals(1, r.tramesTelemesureValides)
        assertEquals(listOf("CW", "FT8", "PHONIE"), r.evenements.map { it.genre }.sorted())
        // The S-meter as the level; the detailed journal's lines as the waterfall.
        assertEquals("S", r.niveauUnite); assertEquals(121, r.niveaux.size)
        assertEquals(300, r.cascade!!.lignes.size); assertEquals(SpectrePassage.SOURCE_SPECTRES, r.cascade!!.source)
        assertEquals(1, r.sons.size); assertTrue(r.sons[0].url!!.startsWith("son?id=27607_$t0&n=0"))
        val q = fr.f4ioz.satcombo.domain.RapportPassage.qualite(r)
        assertEquals(100.0 / 300, q.partSignal!!, 0.01); assertEquals(25.0, q.snrPicDb!!, 0.6)
        // Its page through the journal's routes.
        val web = JournalWebPc(fx.j, fx.st, dossier)
        assertEquals("404 Not Found", web.sert("/rapport") { "" }.statut)
        web.rapport = rp
        val page = String(web.sert("/rapport") { mapOf("id" to e.id, "cle" to "pc")[it].orEmpty() }.corps)
        assertTrue(page.contains("Partager") && page.contains("rapport/zip?id=") && page.contains("<audio"))
        // The fixtures' folder kept for a trial SatMe-PC (its screenshots), when that folder was made beforehand.
        File("build/rapport-fixtures").takeIf { it.isDirectory }?.let { dossier.copyRecursively(it, overwrite = true) }
        val seul = web.sert("/rapport/html") { mapOf("id" to e.id)[it].orEmpty() }
        assertTrue(seul.entetes.contains("attachment") && seul.entetes.contains(".html"))
        assertFalse(String(seul.corps).contains("<audio") || String(seul.corps).contains("class=\"barre\""))
    }

    @Test
    fun le_zip_porte_le_rapport_le_son_l_image_et_la_cascade() {
        val fx = fixtures(); val rp = fx.rp; val e = fx.e
        val z = rp.paquet(e)
        val noms = ZipFile(z).use { f -> f.entries().toList().map { it.name } }
        assertEquals("rapport.html", noms.first())
        assertTrue(noms.toString(), noms.contains("cascade.png") && noms.any { it.startsWith("sons/") && it.endsWith(".wav") } && noms.any { it.startsWith("sstv/") })
        val html = ZipFile(z).use { f -> f.getInputStream(f.getEntry("rapport.html")).readBytes().toString(Charsets.UTF_8) }
        assertTrue(html.contains("src=\"sons/SatMe_SO-50_"))
    }

    @Test
    fun une_cascade_i_q_passe_avant_les_spectres_notes() {
        val fx = fixtures(); val rp = fx.rp; val e = fx.e
        // A small SigMF recording of the pass: a carrier at the listened frequency (+3 kHz from the centre).
        val d = File(dossier, "enregistrements-iq").apply { mkdirs() }
        val debit = 48_000.0
        val n = (debit * 4).toInt()
        val b = java.nio.ByteBuffer.allocate(n * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) { val ph = 2 * Math.PI * 3_000.0 * i / debit; b.putShort((6000 * Math.cos(ph)).toInt().toShort()); b.putShort((6000 * Math.sin(ph)).toInt().toShort()) }
        val base = SigMF.nomBase(t0 + 60_000L, "SO-50", 436_795_000L)
        File(d, base + SigMF.EXT_DATA).writeBytes(b.array())
        File(d, base + SigMF.EXT_META).writeText(SigMF.ecritMeta(SigMF.Meta(SigMF.Format.CI16, debit, t0 + 60_000L,
            listOf(SigMF.Capture(0, 436_795_000L, 436_792_000L, 3_000.0), SigMF.Capture(n / 2L, 436_795_000L, 436_792_000L, 3_000.0)),
            satellite = "SO-50", norad = 27607)))
        val r = rp.rapport(e, RapportPc.Forme.FICHIER)
        val c = r.cascade!!
        assertEquals(SpectrePassage.SOURCE_IQ, c.source)
        assertTrue(c.lignes.size >= 10)
        // The carrier in the middle columns.
        c.lignes.forEach { l -> assertTrue(l.db.indices.maxBy { l.db[it] } in 60..67) }
        assertEquals(1, r.iq.size)
        // Worked out once, kept beside the spectra.
        assertTrue(File(rp.dossierSpectres, "iq_" + base + SpectrePassage.EXT).isFile)
    }

    @Test
    fun le_journal_detaille_note_un_passage_et_le_ferme() {
        val st = StockageMemoire()
        var t = 0L
        var passage: Pair<Int, String>? = 25544 to "ISS"
        var trames = 0L
        var cw = ""
        val sp = File(dossier, "spectres")
        // A spectrum with a carrier where the receiver listens (+10 kHz from the PLL).
        val parBin = fr.f4ioz.satcombo.sdr.Dsp.RTL_RATE.toDouble() / fr.f4ioz.satcombo.sdr.Dsp.DECIM_1 / 1024
        val spectre = FloatArray(1024) { k -> if (Math.abs(k - (512 + 10_000 / parBin)) < 1.5) -60f else -100f }
        val j = SpectreJournalPc(st, sp, { passage }, { Triple(spectre, trames, 10_000.0) }, cw = { cw },
            ft8 = { listOf(Triple(5_000L, "CQ F1AAA JN18", "")) }, horloge = { t })
        // Off: nothing kept.
        repeat(20) { t += 250; trames++; j.tic() }
        assertFalse(sp.exists())
        j.actif = true
        repeat(200) { i -> t += 250; trames++; if (i == 100) cw = "CQ DE F5ABC"; j.tic() }
        // Set: a minute later, the pass is written.
        passage = null
        repeat(250) { t += 250; j.tic() }
        val f = sp.listFiles()!!.map { it.name }.sorted()
        assertTrue(f.toString(), f.any { it.endsWith(SpectrePassage.EXT) } && f.any { it.endsWith(".png") } && f.any { it.endsWith(".decodes.jsonl") })
        val b = sp.listFiles { x -> x.name.endsWith(SpectrePassage.EXT) }!!.first().inputStream().use { SpectrePassage.lit(it) }
        // 50 s of spectra, one line every 2 s; the carrier in the middle.
        assertTrue("${b.lignes.size}", b.lignes.size in 24..26)
        b.lignes.forEach { l -> assertTrue(l.db.indices.maxBy { l.db[it] } in 62..65) }
        val d = SpectreJournalPc.decodes(sp.listFiles { x -> x.name.endsWith(".decodes.jsonl") }!!.first())
        assertEquals(listOf("FT8" to "CQ F1AAA JN18", "CW" to "CQ DE F5ABC"), d.map { it.genre to it.texte })
    }

    @Test
    fun le_texte_cw_nouveau_meme_quand_le_debut_est_coupe() {
        assertEquals("", SpectreJournalPc.nouveauTexte(null, "ABC"))
        assertEquals("DEF", SpectreJournalPc.nouveauTexte("ABC", "ABCDEF"))
        val long = "X".repeat(10) + "0123456789".repeat(5)
        assertEquals(" K", SpectreJournalPc.nouveauTexte(long, long.substring(15) + " K"))
        assertEquals("NEW", SpectreJournalPc.nouveauTexte("OLD TEXT", "NEW"))
    }
}
