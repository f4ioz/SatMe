/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.data.SatPass
import fr.f4ioz.satcombo.data.Transmitter
import fr.f4ioz.satcombo.domain.ActivitesAVenir
import fr.f4ioz.satcombo.domain.CampagneSstv
import fr.f4ioz.satcombo.domain.PiloteSstv
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import fr.f4ioz.satcombo.telemesure.Genesis
import fr.f4ioz.satcombo.telemesure.RecepteurPaquets
import fr.f4ioz.satcombo.telemesure.SignalFskAudio
import fr.f4ioz.satcombo.telemesure.SignalPaquets
import fr.f4ioz.satcombo.telemesure.Ssdv
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
import java.io.File
import java.nio.file.Files

/**
 * SSDV campaigns, receive only: the decoder chosen by the satellite's
 * transmitter (GENESIS in USB for a HADES-SA-like satellite, FSK packets on the
 * SDR for a Geoscan-like one), fed by the engine's own signal generators on a
 * fake clock; the packets and pictures of the pass counted in the history and
 * the calendar; the boxes ticked by what the satellite sends.
 */
class SsdvCampagnePcTest {

    private val dossier = Files.createTempDirectory("satme-ssdv-ap").toFile()
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun fin() { portee.cancel(); dossier.deleteRecursively() }

    private val m = 60_000L
    private val t0 = 1_793_450_000_000L     // 1 Nov 2026
    private var heure = t0
    private fun stockage() = StockageFichier(File.createTempFile("tlm", ".properties").also { it.delete(); it.deleteOnExit() })

    private fun tx(desc: String, mode: String, baud: Double?) = Transmitter(desc, mode, null, null, 435_000_000L, 435_000_000L, false, true, "Transmitter", baud)

    @Test fun `le decodeur et les cases selon le satellite`() {
        val c = CampagneSstv.Campagne(id = "h", nom = "SSDV", catnum = 68446, satNom = "HADES-SA", emetteur = "x", debutMs = t0, finMs = t0 + 86_400_000L)
        assertEquals(AutoPiloteSstvPc.Companion.VoieSsdv.GENESIS, AutoPiloteSstvPc.voieSsdv(c, tx("GENESIS 800 bps", "GENESIS", 800.0)))
        assertEquals(AutoPiloteSstvPc.Companion.VoieSsdv.GENESIS, AutoPiloteSstvPc.voieSsdv(c, tx("SSDV", "SSDV", null)))
        assertEquals(AutoPiloteSstvPc.Companion.VoieSsdv.PAQUETS, AutoPiloteSstvPc.voieSsdv(c, tx("Geoscan GMSK 9k6", "GMSK", 9600.0)))
        assertEquals(AutoPiloteSstvPc.Companion.VoieSsdv.GENESIS, AutoPiloteSstvPc.voieSsdv(c.copy(freqHz = 436_888_000L, modulation = "USB-D"), null))
        assertEquals(AutoPiloteSstvPc.Companion.VoieSsdv.PAQUETS, AutoPiloteSstvPc.voieSsdv(c.copy(freqHz = 436_200_000L, modulation = "FM"), null))
        // The picture satellites' list, then what the transmitters say.
        assertEquals("SSDV", AutoPiloteSstvPc.imagesDe(68446, emptyList()))
        assertEquals("SSDV", AutoPiloteSstvPc.imagesDe(64890, emptyList()))
        assertEquals("SSTV", AutoPiloteSstvPc.imagesDe(25544, emptyList()))
        assertEquals("SSDV", AutoPiloteSstvPc.imagesDe(99001, listOf(tx("Geoscan telemetry + SSDV", "GMSK", 9600.0))))
        assertEquals("", AutoPiloteSstvPc.imagesDe(99002, listOf(tx("Telemetry", "GMSK", 9600.0))))
        assertTrue(AutoPiloteSstvPc.emetSsdv(tx("GENESIS 200", "GENESIS", 200.0)))
        assertFalse(AutoPiloteSstvPc.emetSsdv(tx("FM voice", "FM", null)))
    }

    @Test fun `une activite SSDV donne une campagne SSDV, decodeur SSTV decoche`() {
        val st = StationPc(StockageMemoire(), Catalogue(File(dossier, "cache"), enLigne = false), PortsSeriePc(), portee, { heure })
        val ap = AutoPiloteSstvPc(st, dossier, { heure }, { _, _, _ -> emptyList() }, stationPilote = Fausse())
        val a = ActivitesAVenir.Activite(id = "rss:hades", type = ActivitesAVenir.Type.SSDV, satNom = "HADES-SA", catnum = 68446,
            debutMs = t0, finMs = t0 + 2 * 86_400_000L, freqHz = 436_888_000L, modulation = "USB-D", titre = "HADES-SA SSDV", sources = listOf(ActivitesAVenir.Source("AMSAT-EA")))
        val c = ap.depuisActivite(a, 68446).getOrThrow()
        assertTrue(c.chaine.ssdv); assertFalse(c.chaine.sstv)
        assertEquals("USB-D", c.modulation)
        // An SSTV satellite: as before.
        val s = ap.depuisActivite(a.copy(id = "rss:iss", type = ActivitesAVenir.Type.SSTV, catnum = 25544, modulation = "FM", freqHz = 437_550_000L), 25544).getOrThrow()
        assertTrue(s.chaine.sstv); assertFalse(s.chaine.ssdv)
        // The form's list says what the satellite sends.
        val j = JSONObject(String(ap.sert("/api/autopilote/emetteurs", "GET", { k -> if (k == "catnum") "68446" else "" }) { null }.corps, Charsets.UTF_8))
        assertEquals("SSDV", j.getString("images"))
    }

    /** SO-127-like audio (USB, GENESIS FSK 800 bit/s, 1600 Hz shift): [n] packets of a test picture. */
    private fun so127(n: Int, numero: Int, fs: Int = 48_000): ShortArray {
        val paquets = SignalPaquets.paquetsMire(numero).take(n).map { Ssdv.recachete(it, Genesis.INDICATIF_SSDV) }
        val o = paquets.flatMap { Genesis.codeSsdv(it).toList() }.toByteArray()
        return SignalFskAudio.audio(Genesis.bits(o), fs, 800.0, 1_600.0, 1_500.0, 13.0, 2.0)
    }

    @Test fun `un passage facon HADES-SA - GENESIS 800 bit-s decode pour le passage seulement, compte`() {
        val t = TelemesurePc(stockage(), dossier, null, { heure })
        t.suiviGenesis = { "HADES-SA" to 68446 }
        assertFalse(t.genesisActif)
        // Before the pass: the card's sound is not decoded.
        val avant = so127(3, 20)
        t.ecouteCarte(avant, avant.size, 48_000)
        assertEquals(0 to 0, t.bilanSsdv(0, Long.MAX_VALUE))
        // The auto-pilot's pass (what its chain turns on), the clock at the pass.
        heure = t0 + 10 * m
        t.genesisPourPassage = true
        val pcm = so127(6, 21)
        t.ecouteCarte(pcm, pcm.size, 48_000)
        assertEquals(6 to 1, t.bilanSsdv(t0 + 9 * m, t0 + 20 * m))
        assertEquals("HADES-SA", t.paquets.assemblage.photos().single().satellite)
        // Another pass's window counts nothing; the pass over, the decoder off again.
        assertEquals(0 to 0, t.bilanSsdv(t0 + 30 * m, t0 + 40 * m))
        t.genesisPourPassage = false
        heure = t0 + 60 * m
        val apres = so127(4, 22)
        t.ecouteCarte(apres, apres.size, 48_000)
        assertEquals(1, t.paquets.assemblage.photos().size)
    }

    @Test fun `un passage en FSK 9600 - le decodeur de paquets sur le SDR pour le passage, compte`() {
        val t = TelemesurePc(stockage(), dossier, null, { heure })
        t.contextePaquets = { essai -> RecepteurPaquets.Contexte("Geoscan-2", 64890, 436_200_000L, 436_200_000L, essai) }
        t.emetteurPaquets = { Triple(9600, "Geoscan GMSK 9k6", "GMSK") }
        heure = t0 + 10 * m
        // The option off: the dongle's I/Q not taken.
        t.iqSdr(null, ShortArray(4096), 4096, 0.0, false)
        assertFalse(t.paquets.actif)
        t.paquetsPourPassage = true
        // Four SSDV packets in AX.25 frames, G3RUH 9600 bit/s, at the dongle's rate.
        val trames = SignalPaquets.paquetsMire(30).take(4).map { SignalPaquets.trameSsdv(it) }
        val fs = fr.f4ioz.satcombo.sdr.Dsp.RTL_RATE.toDouble()
        val (re, im) = SignalPaquets.baseband(fr.f4ioz.satcombo.telemesure.G3ruh.bits(trames), fs, 9600, 3_000.0, 18.0, 3)
        val iq = ShortArray(re.size * 2) { k -> ((if (k % 2 == 0) re[k / 2] else im[k / 2]) * 6000f).toInt().coerceIn(-32767, 32767).toShort() }
        var k = 0
        while (k < iq.size) {
            val n = minOf(32_768, iq.size - k)
            t.iqSdr(null, iq.copyOfRange(k, k + n), n, 0.0, false)
            k += n
            Thread.sleep(3)
        }
        assertTrue(t.paquets.actif)
        val limite = System.currentTimeMillis() + 30_000
        while (t.bilanSsdv(t0, t0 + 20 * m).first < 4 && System.currentTimeMillis() < limite) Thread.sleep(50)
        assertEquals(t.paquets.etat.toString(), 4 to 1, t.bilanSsdv(t0, t0 + 20 * m))
        // Off again: the decoder stops (the option is off).
        t.paquetsPourPassage = false
        assertFalse(t.paquets.actif)
    }

    private class Fausse(val ssdv: Pair<Int, Int> = 0 to 0) : PiloteSstv.Station {
        override fun emission(): String? = null
        override fun conflit(c: CampagneSstv.Campagne): String? = null
        override fun capture() = emptyMap<String, String>()
        override fun prepare(c: CampagneSstv.Campagne, p: SatPass) = PiloteSstv.Preparation(listOf("SSDV en BLU prêt"))
        override fun enregistre(c: CampagneSstv.Campagne): String? = null
        override fun arreteEnregistrement(): String = "SatMe_HADES-SA.wav"
        override fun images(deMs: Long, aMs: Long) = emptyList<String>()
        override fun ssdv(deMs: Long, aMs: Long) = ssdv
        override fun parque(): String? = null
        override fun restaure(avant: Map<String, String>): List<String> = emptyList()
        override fun annonce(texte: String) {}
    }

    @Test fun `l'historique et le calendrier comptent les images SSDV du passage`() {
        val st = StationPc(StockageMemoire(), Catalogue(File(dossier, "cache"), enLigne = false), PortsSeriePc(), portee, { heure })
        val p = SatPass("HADES-SA", 68446, t0 + 10 * m, t0 + 20 * m, 48.0, 0.0, 0.0, false, false)
        val pas = { _: Int, de: Long, a: Long -> listOf(p).filter { it.losEpochMs > de && it.aosEpochMs < a } }
        val ap = AutoPiloteSstvPc(st, dossier, { heure }, pas, stationPilote = Fausse(57 to 2))
        assertTrue(ap.garde(CampagneSstv.Campagne(id = "", nom = "hades-SA SSDV", catnum = 68446, satNom = "HADES-SA", freqHz = 436_888_000L, modulation = "USB-D",
            debutMs = t0, finMs = t0 + 86_400_000L, chaine = CampagneSstv.Chaine(sstv = false, ssdv = true))).isSuccess)
        ap.regleMaitre(true)
        heure = t0 + 9 * m; ap.tic()
        heure = t0 + 21 * m; ap.tic()
        val tr = ap.historique.single()
        assertEquals(CampagneSstv.Statut.FAIT, tr.statut)
        assertEquals(57, tr.ssdvPaquets); assertEquals(2, tr.ssdvImages); assertEquals(2, tr.nbImages)
        assertTrue(tr.faits.toString(), tr.faits.contains("SSDV : 57 paquets, 2 images"))
        // Kept on disk with the counts.
        assertEquals(2, CampagneSstv.lit(File(dossier, "sstv-campagnes.json").readText()).historique.single().ssdvImages)
        val h = JSONObject(String(ap.sert("/api/autopilote", "GET", { "" }) { null }.corps, Charsets.UTF_8)).getJSONArray("historique").getJSONObject(0)
        assertEquals(57, h.getInt("ssdvPaquets"))
        val cal = CalendrierPc(st, { ap }, { null }, { heure }, pas)
        val items = cal.json(t0 - 86_400_000L, t0 + 86_400_000L).getJSONArray("items").let { l -> (0 until l.length()).map { l.getJSONObject(it) } }
        val pass = items.single { it.getString("sorte") == "passage" }
        assertEquals(2, pass.getInt("images")); assertEquals(57, pass.getInt("ssdvPaquets"))
        assertEquals(2, items.single { it.getString("sorte") == "campagne" }.getInt("images"))
    }
}
