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
import fr.f4ioz.satcombo.data.SettingsStore
import fr.f4ioz.satcombo.domain.CampagneSstv
import fr.f4ioz.satcombo.domain.JournalPassage
import fr.f4ioz.satcombo.sstv.SstvMeta
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** `satme --demo`: made-up data, the same each time, recent, nobody's; nothing real opened. */
class ModeDemoTest {

    @After fun fin() { ModeDemo.desactive() }

    companion object {
        private val racine = File(System.getProperty("java.io.tmpdir"), "satme-demo-test-" + System.nanoTime()).apply { mkdirs() }
        /** Monday 26 October 2026, 07:33 UTC. */
        const val MAINTENANT = 1_793_000_000_000L
        const val JOUR = 86_400_000L
        /** Made once (a few seconds of orbits): the tests below read it. */
        private val premier: Pair<File, GenerateurDemo.Bilan> by lazy {
            val d = File(racine, "a")
            d to GenerateurDemo(MAINTENANT).genere(d)
        }
        @AfterClass @JvmStatic fun nettoie() { racine.deleteRecursively() }
    }

    private fun fichiers(d: File): Map<String, ByteArray> = d.walkTopDown().filter { it.isFile }.associate { f ->
        val b = f.readBytes()
        // The settings file's date line aside (Properties writes it).
        f.relativeTo(d).path to (if (f.name.endsWith(".properties")) String(b, Charsets.UTF_8).lines().filterNot { it.startsWith("#") }.sorted().joinToString("\n").toByteArray() else b)
    }

    @Test fun la_demo_a_de_quoi_montrer() {
        val (d, b) = premier
        assertTrue("passages : ${b.passages}", b.passages >= 8)
        assertTrue("contacts : ${b.contacts}", b.contacts >= 30)
        assertTrue("images : ${b.images}", b.images >= 2)
        assertTrue(File(d, ModeDemo.MARQUE).isFile)
        assertEquals(MAINTENANT, ModeDemo.genere(d))
        val s = SettingsStore(StockageFichier(File(d, "reglages.properties")))
        assertEquals(GenerateurDemo.INDICATIF, s.callsign)
        assertEquals(GenerateurDemo.LOCATOR, s.manualLocator)
        assertTrue(s.rotorSim)
        // The satellites known without a network: the elements carried in the program.
        val sats = Catalogue(File(d, "cache"), enLigne = false).satellites().map { it.catalogNumber }.toSet()
        assertTrue(GenerateurDemo.FAVORIS.all { it in sats })
        val st = StockageFichier(File(d, "reglages.properties"))
        assertEquals("virtuel", st.getString("pc_pol_port", null))
        assertEquals("WIMO", st.getString("pc_pol_uhf", null))
        assertEquals("AUCUN", st.getString("pc_pol_vhf", null))
        assertEquals(SonDemo.NOM, st.getString("pc_son_carte", null))
    }

    @Test fun meme_jour_memes_fichiers() {
        val (a, _) = premier
        val b = File(racine, "b")
        GenerateurDemo(MAINTENANT).genere(b)
        val fa = fichiers(a); val fb = fichiers(b)
        assertEquals(fa.keys, fb.keys)
        for (k in fa.keys) assertTrue("$k diffère", fa.getValue(k).contentEquals(fb.getValue(k)))
    }

    @Test fun personne_de_reel() {
        val (d, _) = premier
        val interdits = listOf("F4IOZ", "JN18FS", "Olivier", "Gouyen", "mdp", "password")
        d.walkTopDown().filter { it.isFile && !it.name.endsWith(".png") }.forEach { f ->
            val t = f.readText()
            for (m in interdits) assertFalse("« $m » dans ${f.name}", t.contains(m))
        }
        val log = LogStore(File(d, "qso_log.json")).load()
        // Every callsign in the Q series (kept by the ITU for the Q code): nobody's.
        assertTrue(log.all { it.callsign.startsWith("Q") && it.myLocator == GenerateurDemo.LOCATOR })
        assertTrue(GenerateurDemo.CORRESPONDANTS.all { it.indicatif.startsWith("Q") })
        val metas = File(d, "sstv").listFiles { f -> f.name.endsWith(".meta") }.orEmpty()
        assertTrue(metas.isNotEmpty())
        for (m in metas) assertEquals(GenerateurDemo.INDICATIF, SstvMeta.decode(m.name.removeSuffix(".meta") + ".png", m.readText()).callsign)
    }

    @Test fun dates_relatives_au_jour() {
        val (d, _) = premier
        val log = LogStore(File(d, "qso_log.json")).load()
        assertTrue(log.all { it.timeMs in (MAINTENANT - (GenerateurDemo.JOURS_CARNET + 1) * JOUR)..MAINTENANT })
        // Recent: the last few days hold contacts.
        assertTrue(log.count { it.timeMs > MAINTENANT - 3 * JOUR } >= 3)
        val passages = JournalPassage.Rangement(File(d, "journal")).passages()
        assertTrue(passages.all { it.debutMs in (MAINTENANT - (GenerateurDemo.JOURS_JOURNAL + 1) * JOUR)..MAINTENANT && it.finMs < MAINTENANT })
        assertTrue(passages.any { it.avecCat && it.avecRotor } && passages.any { it.reconstitue })
        val c = CampagneSstv.lit(File(d, "sstv-campagnes.json").readText()).campagnes.single()
        assertTrue(MAINTENANT in c.debutMs..c.finMs)
        // A month later: everything a month later.
        val e = File(racine, "c")
        GenerateurDemo(MAINTENANT + 30 * JOUR).genere(e)
        val log2 = LogStore(File(e, "qso_log.json")).load()
        assertTrue(log2.all { it.timeMs in (MAINTENANT + 30 * JOUR - (GenerateurDemo.JOURS_CARNET + 1) * JOUR)..(MAINTENANT + 30 * JOUR) })
        assertTrue(log2.maxOf { it.timeMs } > log.maxOf { it.timeMs } + 25 * JOUR)
    }

    @Test fun jamais_le_dossier_d_une_station() {
        val d = File(racine, "station").apply { mkdirs() }
        File(d, "reglages.properties").writeText("callsign=s\\:X\n")
        File(d, "qso_log.json").writeText("[]")
        assertNotNull(ModeDemo.refusDossier(d))
        assertTrue(runCatching { ModeDemo.prepare(d, reset = true, maintenant = MAINTENANT) }.isFailure)
        ModeDemo.efface(d)
        assertTrue(File(d, "qso_log.json").isFile)
        assertNull(ModeDemo.refusDossier(File(racine, "nouveau")))
    }

    @Test fun remise_a_neuf_et_dates_refaites() {
        val d = File(racine, "r")
        ModeDemo.prepare(d, reset = false, maintenant = MAINTENANT)
        File(d, "acces-distant.properties").writeText("hash=x\n")
        File(d, "qso_log.json").writeText("[]")
        // Two days on: the dated part made again, the settings kept.
        File(d, "reglages.properties").appendText("essai=s\\:1\n")
        ModeDemo.prepare(d, reset = false, maintenant = MAINTENANT + 3 * JOUR)
        assertEquals(MAINTENANT + 3 * JOUR, ModeDemo.genere(d))
        assertTrue(LogStore(File(d, "qso_log.json")).load().isNotEmpty())
        assertEquals("1", StockageFichier(File(d, "reglages.properties")).getString("essai", null))
        // Reset: everything again, the remote access password kept.
        ModeDemo.prepare(d, reset = true, maintenant = MAINTENANT + 3 * JOUR)
        assertNull(StockageFichier(File(d, "reglages.properties")).getString("essai", null))
        assertTrue(File(d, "acces-distant.properties").isFile)
    }

    @Test fun rien_de_reel_ouvert() {
        assertFalse(ModeDemo.actif)
        ModeDemo.active()
        assertTrue(PortsSeriePc.sansSerie())
        val son = SonPc()
        son.virtuelles += SonDemo().source()
        assertEquals(listOf(SonDemo.NOM), son.appareils().map { it.nom })
        assertFalse(son.demarre("Une vraie carte"))
        assertNotNull(ModeDemo.refus("x"))
        assertNull(OutilsPc.trouve("rtl_tcp"))
        assertTrue(MirePc(File(racine, "exports")).sorties().isEmpty())
        ModeDemo.desactive()
        assertNull(ModeDemo.refus("x"))
    }

    @Test fun le_bandeau_sur_chaque_page() {
        val html = "<!doctype html><html><head></head><body class=\"x\"><p>a</p></body></html>"
        assertEquals(html, ModeDemo.bandeau(html))
        ModeDemo.active()
        val b = ModeDemo.bandeau(html)
        assertTrue(b.contains("<body class=\"x\"><div id=\"satme-demo\""))
        assertTrue(b.contains("DÉMO — données fictives"))
    }

    @Test fun images_faites_maison_et_stables() {
        val a = ImagesDemo.image(3, 12, 320, 240, 7L)
        val b = ImagesDemo.image(3, 12, 320, 240, 7L)
        assertEquals(320, a.width); assertEquals(240, a.height)
        assertTrue(a.getRGB(0, 0, 320, 240, null, 0, 320).contentEquals(b.getRGB(0, 0, 320, 240, null, 0, 320)))
        val partielle = ImagesDemo.image(3, 12, 320, 240, 7L, 0.5)
        // Below the half, the lost signal: dark.
        assertTrue((partielle.getRGB(160, 200) and 0xFF) < 40)
    }
    /** The generator's signals go onto the satellites up in its view; nothing moves for those down. */
    @Test fun signaux_sur_les_satellites_leves() {
        fun tx(d: String, mode: String, bas: Long, haut: Long? = null) =
            fr.f4ioz.satcombo.data.Transmitter(d, mode, null, null, bas, haut, haut != null, true, if (haut != null) "Transponder" else "Transmitter")
        val leve = fr.f4ioz.satcombo.domain.EchelleSdr.Sat(1, "UP", 20.0, 0.0, 0.0)
        val couche = fr.f4ioz.satcombo.domain.EchelleSdr.Sat(2, "DOWN", -20.0, 0.0, 0.0)
        val c = GenerateurDemo.SDR_CENTRE_HZ
        val m = fr.f4ioz.satcombo.domain.EchelleSdr.marques(listOf(
            leve to listOf(tx("Voice", "FM", c + 40_000), tx("Linear", "USB", c - 60_000, c - 10_000), tx("Beacon", "CW", c + 70_000)),
            couche to listOf(tx("SSTV", "FM", c - 80_000))), c - 88_200, c + 88_200, 0L)
        val r0 = fr.f4ioz.satcombo.sdr.GenerateurIq.Reglages()
        val r = ModeDemo.surSatellites(r0, m, c)
        assertEquals(40_000, r.fm.decalageHz)
        assertEquals(70_000, r.cw.decalageHz)
        assertEquals(-40_000, r.blu.decalageHz)
        assertEquals("down: left alone", r0.sstv, r.sstv)
        assertEquals(r, ModeDemo.surSatellites(r, m, c))
    }
}
