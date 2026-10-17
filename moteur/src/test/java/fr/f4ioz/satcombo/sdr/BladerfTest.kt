/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sdr

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import kotlin.math.sqrt

/** The bladeRF's receiver and transmitter against a fake bladeRF-cli: never a real one. */
class BladerfTest {

    private val faux = ArrayList<FauxBladerf>()
    @After fun fin() { Bladerf.arreteTout(); faux.forEach { it.close() } }

    private fun faux(mode: String = "normal", limite: Long = 0L): FauxBladerf {
        assumeTrue("sh ou mkfifo absent", File("/bin/sh").canExecute() && File("/usr/bin/mkfifo").canExecute())
        return FauxBladerf(mode, limite).also { faux += it }
    }

    private fun attends(ms: Long, c: () -> Boolean): Boolean {
        val fin = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < fin) { if (c()) return true; Thread.sleep(20) }
        return c()
    }

    // ------------------------------------------------------------ the tool's words

    @Test
    fun info_et_messages() {
        val c = Bladerf.lisInfo("""
            Board:                    Nuand bladeRF 2.0 (bladerf2)
            Serial #:                 aac46df92fb64a47a150cc2ab9b40e1f
            FPGA size:                49 KLE
            USB speed:                SuperSpeed
            Firmware version:           2.6.0-git-09c82087
            FPGA version:               0.16.0 (configured by USB host)
        """.trimIndent())!!
        assertEquals(Bladerf.Carte("bladeRF 2.0", "aac46df92fb64a47a150cc2ab9b40e1f", "0.16.0", "2.6.0"), c)
        assertEquals("bladeRF 2.0 · n° …b9b40e1f · FPGA 0.16.0 · firmware 2.6.0", c.libelle)
        assertTrue(Bladerf.lisInfo("USB speed: HighSpeed\nSerial #: 12ab")!!.libelle.contains("USB 3 conseillé"))
        assertNull(Bladerf.lisInfo("Failed to open device (first available): No devices available"))
        assertTrue(Bladerf.traduis("Failed to open device (first available): No devices available")!!.startsWith("bladeRF absent"))
        assertTrue(Bladerf.traduis("Failed to open device (*): Device or resource busy")!!.contains("déjà pris"))
        assertTrue(Bladerf.traduis("  set: Invalid gain setting for RX1 (99)")!!.startsWith("bladeRF-cli : "))
        assertTrue(Bladerf.traduis("Error: File not found")!!.startsWith("bladeRF-cli : "))
        assertNull(Bladerf.traduis("[INFO @ host/libraries/libbladeRF/src/helpers/version.c:79] Firmware version (v2.6.0) is newer"))
        assertNull(Bladerf.traduis("RX1 Frequency:  435000000 Hz (Range: [70000000, 6000000000])"))
        assertNull(Bladerf.traduis("Setting TX1 overall gain to 16 dB"))
        assertEquals(listOf("b", "-d", "*:serial=aac4", "-i"), Bladerf.commande("b", "aac4").takeLast(4))
        // Its answers line by line: through stdbuf when there is one.
        Bladerf.stdbuf?.let { assertEquals(listOf(it, "-oL", "-eL"), Bladerf.commande("b", "").take(3)) }
        assertTrue(runCatching { Bladerf.commande("b", "-e tx") }.isFailure)
    }

    @Test
    fun le_recepteur_n_envoie_que_du_rx() {
        for (c in listOf("set frequency rx1 435000000", "set samplerate rx1 2116800", "set bandwidth rx1 1058400", "set gain rx1 30",
            "set agc rx1 on", "rx config file=/tmp/x format=bin n=0 channel=1 samples=16384 buffers=32 xfers=16 timeout=2000", "rx start", "rx stop", "quit"))
            BladerfSdr.verifie(c)
        for (c in listOf("set frequency tx1 435000000", "set gain tx1 60", "tx start", "tx config file=/tmp/x format=bin repeat=0", "set biastee rx1 on",
            "set loopback rf_lna1", "rx config file=/tmp/x format=bin n=0 channel=1,2", "set frequency rx1 435000000; tx start", "calibrate dc tx"))
            assertTrue(c, runCatching { BladerfSdr.verifie(c) }.isFailure)
    }

    @Test
    fun l_emetteur_ne_dit_que_ce_qui_est_prevu() {
        for (c in listOf("set gain tx1 16", "set gain tx1 -23", "set gain tx1 66", "set frequency tx1 2405250000", "set samplerate tx1 2331000",
            "set bandwidth tx1 416000", "tx config file=/tmp/t format=bin repeat=1 delay=0 channel=1 samples=65536 buffers=16 xfers=8 timeout=2000",
            "tx", "tx start", "tx stop", "quit"))
            BladerfEmission.verifie(c)
        for (c in listOf("set gain tx1 67", "set gain tx1 -24", "set gain tx1 dsa 0", "set frequency tx1 7000000000", "set frequency tx1 10000000",
            "set samplerate tx1 100000", "tx config file=/tmp/t format=bin repeat=0 delay=0 channel=1 samples=65536 buffers=16 xfers=8 timeout=2000",
            "tx config file=/tmp/t format=bin repeat=1 delay=0 channel=1,2 samples=65536 buffers=16 xfers=8 timeout=2000",
            "set biastee tx1 on", "set loopback bb_txvga1_rxlpf", "set frequency rx1 435000000", "rx start", "tx start; set gain tx1 66"))
            assertTrue(c, runCatching { BladerfEmission.verifie(c) }.isFailure)
        // The Pluto's attenuation, whole dB, under every ceiling; libbladeRF's gain is it plus 66.
        val b = BladerfEmission("x", gainMaxDb = -10.0)
        assertEquals(-10.0, b.gainApplique(0.0), 0.0)
        assertEquals(-40.0, b.gainApplique(-5.0, plafondDb = -40.0), 0.0)
        assertEquals(-31.0, b.gainApplique(-30.5), 0.0)
        assertEquals(-89.0, b.gainApplique(-200.0), 0.0)
        assertEquals(-89.0, b.gainApplique(Double.NaN), 0.0)
        assertEquals(26, b.gainCli(-40.0))
        val src = shortArrayOf(0, 8, -8, 16, 32767, -32768, 16000, -16000)
        val dst = ByteArray(src.size * 2)
        BladerfEmission.versSc16q11(src, dst, src.size)
        val v = (0 until src.size).map { ((dst[2 * it].toInt() and 0xff) or (dst[2 * it + 1].toInt() shl 8)).toShort().toInt() }
        assertEquals(listOf(0, 1, -1, 1, 2047, -2047, 1000, -1000), v)
    }

    // ------------------------------------------------------------ receiving

    @Test
    fun reception_bout_en_bout() {
        val f = faux()
        val r = BladerfSdr.ouvre(f.cli.path, "aac46df9", dossier = f.tubes)
        assertEquals(1058400.0, r.setSampleRate(Dsp.RTL_RATE), 0.0)
        r.setGain(null)
        r.setCenterFreq(435_000_000L)
        r.setGain(300)
        val b = ShortArray(16_384)
        var n = 0
        assertTrue(attends(8000) { n = r.read16(b, 200); n > 0 })
        assertEquals(16_384, n)
        // The tone ×16 (12 to 16 bits), filtered: 16000 of amplitude, 11300 RMS on I and Q.
        val rms = sqrt((0 until n).sumOf { b[it].toDouble() * b[it] } / n)
        assertTrue("rms $rms", rms in 10_000.0..12_500.0)
        r.setCenterFreq(145_800_000L)
        r.setCenterFreq(10_000_000L)
        assertTrue(r.avertissement.contains("hors de la plage"))
        r.close()
        assertTrue(attends(3000) { Bladerf.enCours() == 0 })
        val c = f.commandes
        assertEquals("print frequency rx1", c.first())
        assertTrue(c.toString(), c.containsAll(listOf("set samplerate rx1 2116800", "set bandwidth rx1 1058400", "set agc rx1 on",
            "set frequency rx1 435000000", "set agc rx1 off", "set gain rx1 30", "rx start", "set frequency rx1 145800000", "rx stop", "quit")))
        assertTrue(c.any { it.startsWith("rx config file=" + f.tubes.path) && it.contains("format=bin n=0 channel=1") })
        assertTrue(c.toString(), c.none { it.startsWith("tx") || it.contains("tx1") })
        assertEquals(listOf("-d *:serial=aac46df9 -i"), f.args)
        assertTrue(r.octets > 0)
    }

    @Test
    fun recepteur_absent_message_clair() {
        val f = faux("absent")
        val x = runCatching { BladerfSdr.ouvre(f.cli.path, dossier = f.tubes) }.exceptionOrNull()
        assertTrue(x is IOException)
        assertTrue(x!!.message, x.message!!.startsWith("bladeRF absent"))
        assertEquals(0, Bladerf.enCours())
        assertTrue(runCatching { BladerfSdr.ouvre(File(f.dossier, "rien").path, dossier = f.tubes) }.exceptionOrNull()!!.message!!.contains("ne démarre pas"))
    }

    @Test
    fun detection_lecture_seule() {
        val f = faux()
        val (c, m) = Bladerf.detecte(f.cli.path)
        assertEquals("", m)
        assertEquals("aac46df92fb64a47a150cc2ab9b40e1f", c!!.serie)
        assertEquals("0.16.0", c.fpga)
        assertEquals(listOf("-e info -e version"), f.args)
        assertTrue(f.commandes.isEmpty())
        f.mode = "absent"
        assertTrue(Bladerf.detecte(f.cli.path).second.startsWith("bladeRF absent"))
    }

    // ------------------------------------------------------------ transmitting (the fake only)

    private val ton: (ShortArray) -> Unit = { b -> for (k in b.indices step 2) { b[k] = 16_000; b[k + 1] = -16_000 } }

    private fun reglage(gain: Double = -50.0, plafond: Double = 0.0, dureeMs: Long = 60_000L, garde: () -> String? = { null }) =
        BladerfEmission.Reglage(frequenceHz = 2_405_250_000L, debit = 2_331_000L, gainDb = gain, dureeMs = dureeMs, bandeHz = 416_000L,
            plafondDb = plafond, paires = 16_384, serie = "", garde = garde)

    private fun emetteur(f: FauxBladerf, chienMs: Long = 3_000L, attenteFluxMs: Long = 5_000L) =
        BladerfEmission(f.cli.path, gainMaxDb = -10.0, chienMs = chienMs, attenteArretMs = 800L, attenteReponseMs = 3_000L,
            attenteFluxMs = attenteFluxMs, dossierFifo = f.tubes)

    @Test
    fun emet_reglages_verifies_puis_arret_propre() {
        val f = faux(limite = 4096)
        val e = emetteur(f)
        assertNull(e.demarre(reglage(gain = -50.0), ton))
        assertTrue(e.etat.emet)
        assertEquals(-50.0, e.etat.gainDb, 0.0)
        assertTrue(attends(5000) { f.compte > 200_000 })
        assertEquals("on", f.txActif)
        // The samples in SC16 Q11: 16000 / 16 = 1000.
        val iq = f.iq
        assertEquals(4096, iq.size)
        assertEquals(1000, ((iq[0].toInt() and 0xff) or (iq[1].toInt() shl 8)).toShort().toInt())
        assertEquals(-1000, ((iq[2].toInt() and 0xff) or (iq[3].toInt() shl 8)).toShort().toInt())
        // A new gain while sending, under the ceiling.
        assertEquals(-20.0, e.regleGain(-20.0)!!, 0.0)
        assertEquals(-10.0, e.regleGain(0.0)!!, 0.0)
        assertEquals(-10.0, e.etat.gainDb, 0.0)
        e.arrete()
        assertFalse(e.etat.emet)
        assertEquals("arrêt demandé", e.etat.raison)
        assertEquals("", e.etat.erreur)
        assertEquals("off", f.txActif)
        assertTrue(f.quitte)
        assertEquals(0, Bladerf.enCours())
        val c = f.commandes
        val config = c.first { it.startsWith("tx config") }
        assertEquals(listOf("set gain tx1 16", "set frequency tx1 2405250000", "set samplerate tx1 2331000", "set bandwidth tx1 416000", config, "tx",
            "tx start", "set gain tx1 16", "set gain tx1 46", "set gain tx1 56", "tx stop", "quit"), c)
        assertTrue(config.contains("repeat=1 delay=0 channel=1"))
        assertTrue(c.none { it.contains("rx") })
        assertFalse(f.tubes.listFiles().orEmpty().any { it.name == "tx.iq" })
    }

    @Test
    fun plafond_interieur_et_charge() {
        val f = faux()
        val e = emetteur(f)
        assertNull(e.demarre(reglage(gain = 0.0, plafond = -40.0), ton))
        assertEquals(-40.0, e.etat.gainDb, 0.0)
        assertEquals(-40.0, e.regleGain(-3.0)!!, 0.0)
        e.arrete()
        assertTrue(f.commandes.filter { it.startsWith("set gain") }.all { it == "set gain tx1 26" })
    }

    @Test
    fun gamme_de_gain_inattendue_rien_n_est_lance() {
        val f = faux("gamme")
        val e = emetteur(f)
        val m = e.demarre(reglage(), ton)
        assertNotNull(m)
        assertTrue(m!!, m.contains("gamme de gain"))
        assertFalse(e.etat.emet)
        assertTrue(f.commandes.none { it == "tx start" })
        assertEquals(0, Bladerf.enCours())
    }

    @Test
    fun gain_relu_trop_fort_arret() {
        val f = faux("gainfort")
        val e = emetteur(f)
        val m = e.demarre(reglage(), ton)
        assertNotNull(m)
        assertTrue(m!!, m.contains("gain relu"))
        assertFalse(e.etat.emet)
        assertEquals("off", f.txActif)
        assertEquals(listOf("tx stop", "quit"), f.commandes.takeLast(2))
        assertEquals(0, Bladerf.enCours())
    }

    @Test
    fun flux_jamais_pris_ou_erreur_en_route() {
        val f = faux("bouche")
        val e = emetteur(f, attenteFluxMs = 1_500L)
        // Buffers larger than the pipe: the first one never goes through.
        val m = e.demarre(reglage().copy(paires = 32_768), ton)
        assertNotNull(m)
        assertTrue(m!!, m.contains("ne prend pas"))
        assertEquals("off", f.txActif)
        assertEquals(0, Bladerf.enCours())
        f.mode = "erreur"
        assertNull(e.demarre(reglage(), ton))
        assertTrue(attends(5000) { !e.etat.emet })
        assertTrue(e.etat.erreur, e.etat.erreur.contains("Transfer timed out"))
        assertEquals("off", f.txActif)
        assertEquals(0, Bladerf.enCours())
    }

    @Test
    fun absent_refuse_au_demarrage() {
        val f = faux("absent")
        val m = emetteur(f).demarre(reglage(), ton)
        assertTrue(m!!, m.startsWith("bladeRF absent"))
        assertEquals(0, Bladerf.enCours())
        assertTrue(f.commandes.isEmpty())
    }

    @Test
    fun refus_avant_tout_lancement() {
        val f = faux()
        val e = emetteur(f)
        assertTrue(e.demarre(reglage().copy(debit = 100_000L), ton)!!.contains("débit"))
        assertTrue(e.demarre(reglage().copy(frequenceHz = 7_000_000_000L), ton)!!.contains("fréquence"))
        assertTrue(e.demarre(reglage().copy(serie = "-e tx"), ton)!!.contains("série"))
        assertTrue(f.args.isEmpty())
    }

    @Test
    fun duree_maximale_garde_et_arret_general() {
        val f = faux()
        val e = emetteur(f)
        assertNull(e.demarre(reglage(dureeMs = 900L), ton))
        assertTrue(attends(5000) { !e.etat.emet })
        assertEquals("durée maximale atteinte", e.etat.raison)
        var page: String? = null
        assertNull(e.demarre(reglage(garde = { page }), ton))
        Thread.sleep(300)
        assertTrue(e.etat.emet)
        page = "page SatMe fermée"
        assertTrue(attends(3000) { !e.etat.emet })
        assertEquals("page SatMe fermée", e.etat.raison)
        assertEquals("off", f.txActif)
        assertEquals(0, Bladerf.enCours())
        assertArrayEquals(arrayOf("tx stop", "quit"), f.commandes.takeLast(2).toTypedArray())
    }
}
