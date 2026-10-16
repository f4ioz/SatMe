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

/** The HackRF's transmitter against a fake hackrf_transfer: never a real one. */
class HackrfEmissionTest {

    private val faux = ArrayList<FauxHackrf>()
    @After fun fin() { HackrfEmission.arreteTout(); faux.forEach { it.close() } }

    private fun faux(mode: String = "normal", limite: Long = 0L): FauxHackrf {
        assumeTrue("sh absent", File("/bin/sh").canExecute())
        return FauxHackrf(mode, limite).also { faux += it }
    }

    private fun attends(ms: Long, c: () -> Boolean): Boolean {
        val fin = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < fin) { if (c()) return true; Thread.sleep(20) }
        return c()
    }

    private val ton: (ShortArray) -> Unit = { b -> for (k in b.indices step 2) { b[k] = 10_000; b[k + 1] = -10_000 } }

    private fun reglage(vga: Int = 0, dureeMs: Long = 60_000L, serie: String = "", garde: () -> String? = { null }) =
        HackrfEmission.Reglage(frequenceHz = 1_255_000_000L, debit = 2_331_000L, vgaDb = vga, dureeMs = dureeMs, bandeHz = 416_000L,
            paires = 16_384, serie = serie, garde = garde)

    @Test
    fun ligne_de_commande_amplificateur_toujours_coupe_gain_borne() {
        val l = HackrfEmission.commande("hackrf_transfer", reglage(vga = 7), 7)
        assertEquals(listOf("hackrf_transfer", "-t", "-", "-f", "1255000000", "-s", "2331000", "-b", "1750000", "-x", "7", "-a", "0", "-p", "0"), l)
        assertEquals(listOf("-d", "a06063c8234e925f"), HackrfEmission.commande("h", reglage(serie = "a06063c8234e925f"), 0).subList(1, 3))
        // Whatever is asked, 10 dB at most, never below 0.
        val h = HackrfEmission("h")
        assertEquals(10, h.vgaApplique(47))
        assertEquals(10, HackrfEmission("h", vgaMax = 47).vgaApplique(47))
        assertEquals(3, h.vgaApplique(10, plafond = 3))
        assertEquals(0, h.vgaApplique(-5))
        // Any other line is refused: the amplifier, the antenna port's supply, a higher gain, a file, other options.
        val bon = l.toMutableList()
        fun refuse(m: (MutableList<String>) -> Unit) {
            val x = bon.toMutableList().also(m)
            assertTrue(x.toString(), runCatching { HackrfEmission.verifie(x) }.isFailure)
        }
        HackrfEmission.verifie(bon)
        refuse { it[it.indexOf("-a") + 1] = "1" }
        refuse { it[it.indexOf("-p") + 1] = "1" }
        refuse { it[it.indexOf("-x") + 1] = "11" }
        refuse { it[it.indexOf("-x") + 1] = "47" }
        refuse { it[it.indexOf("-t") + 1] = "fichier.iq" }
        refuse { it += listOf("-R") }
        refuse { it += listOf("-c", "127") }
        refuse { it += listOf("-a", "1") }
        refuse { it.removeAt(it.indexOf("-a") + 1); it.remove("-a") }
        refuse { it[it.indexOf("-s") + 1] = "1000000" }
        // The narrowest baseband filter that holds the signal.
        assertEquals(1_750_000L, HackrfEmission.filtre(416_000L))
        assertEquals(3_500_000L, HackrfEmission.filtre(2_700_000L))
        assertEquals(28_000_000L, HackrfEmission.filtre(40_000_000L))
    }

    @Test
    fun conversion_16_bits_vers_8_bits() {
        val src = shortArrayOf(0, 127, 128, 255, 256, -128, -129, -256, 32767, -32768, 12800, -12800)
        val dst = ByteArray(src.size)
        HackrfEmission.versInt8(src, dst, src.size)
        assertArrayEquals(byteArrayOf(0, 0, 1, 1, 1, -1, -1, -1, 127, -127, 50, -50), dst)
    }

    @Test
    fun hackrf_info_et_messages_d_erreur() {
        val c = HackrfEmission.lisInfo("""
            hackrf_info version: 2024.02.1
            libhackrf version: 2024.02.1 (0.9)
            Found HackRF
            Index: 0
            Serial number: 0000000000000000a06063c8234e925f
            Board ID Number: 2 (HackRF One)
            Firmware Version: 2024.02.1 (API:1.08)
            Part ID Number: 0xa000cb3c 0x00614764
        """.trimIndent())
        assertEquals(listOf(HackrfEmission.Carte("a06063c8234e925f", "HackRF One", "2024.02.1")), c)
        assertEquals("HackRF One · n° …234e925f · firmware 2024.02.1", c[0].libelle)
        assertTrue(HackrfEmission.lisInfo("hackrf_info version: x\nNo HackRF boards found.").isEmpty())
        assertTrue(HackrfEmission.traduis("hackrf_open() failed: HACKRF_ERROR_NOT_FOUND (-5)")!!.startsWith("HackRF absent"))
        assertTrue(HackrfEmission.traduis("No HackRF boards found.")!!.startsWith("HackRF absent"))
        assertTrue(HackrfEmission.traduis("hackrf_open() failed: Resource busy (LIBUSB_ERROR_BUSY)")!!.contains("déjà pris"))
        assertTrue(HackrfEmission.traduis("hackrf_open() failed: LIBUSB_ERROR_ACCESS")!!.contains("udev"))
        assertNull(HackrfEmission.traduis("Exiting... hackrf_is_streaming() result: HACKRF_ERROR_STREAMING_EXIT_CALLED (-1004)"))
        assertNull(HackrfEmission.traduis("call hackrf_set_sample_rate(2331000 Hz/2.331 MHz)"))
        assertNull(HackrfEmission.traduis("4.6 MiB / 1.000 sec = 4.6 MiB/second, average power -12.0 dBfs"))
    }

    @Test
    fun emet_puis_s_arrete_par_la_fin_de_l_entree() {
        val f = faux()
        val h = HackrfEmission(f.transfer.path)
        assertNull(h.demarre(reglage(vga = 30), ton))
        assertTrue(h.etat.emet)
        assertEquals(10.0, h.etat.gainDb, 0.0)
        assertTrue(attends(5000) { f.compte > 300_000 })
        // The command line it got: amplifier off, gain held.
        assertEquals(listOf("-t", "-", "-f", "1255000000", "-s", "2331000", "-b", "1750000", "-x", "10", "-a", "0", "-p", "0"), f.args)
        assertNull(h.regleGain(40.0))
        h.arrete()
        assertFalse(h.etat.emet)
        assertEquals("arrêt demandé", h.etat.raison)
        assertEquals("", h.etat.erreur)
        // Ended by the end of its input, not killed.
        assertTrue(attends(2000) { f.finEntree })
        assertEquals(0, HackrfEmission.enCours())
        assertTrue(h.etat.tampons > 0)
    }

    @Test
    fun hackrf_absent_ou_occupe_refuse_au_demarrage() {
        val f = faux("absent")
        val h = HackrfEmission(f.transfer.path)
        val e = h.demarre(reglage(), ton)
        assertNotNull(e)
        assertTrue(e!!, e.startsWith("HackRF absent"))
        assertFalse(h.etat.emet)
        f.mode = "occupe"
        assertTrue(h.demarre(reglage(), ton)!!.contains("déjà pris"))
        assertEquals(0, HackrfEmission.enCours())
        // No program at all.
        assertTrue(HackrfEmission(File(f.dossier, "rien").path).demarre(reglage(), ton)!!.startsWith("hackrf_transfer ne démarre pas"))
    }

    @Test
    fun outil_qui_meurt_en_route_arrete_tout() {
        val f = faux("meurt")
        val h = HackrfEmission(f.transfer.path, attenteDemarrageMs = 100L)
        assertNull(h.demarre(reglage(), ton))
        assertTrue(attends(5000) { !h.etat.emet })
        assertTrue(h.etat.erreur, h.etat.erreur.contains("HACKRF_ERROR_STREAMING_THREAD_ERR"))
        assertEquals(0, HackrfEmission.enCours())
    }

    @Test
    fun outil_bloque_chien_de_garde_puis_tue() {
        val f = faux("bouche")
        val h = HackrfEmission(f.transfer.path, chienMs = 800L, attenteArretMs = 400L, attenteDemarrageMs = 100L)
        assertNull(h.demarre(reglage(), ton))
        assertTrue(attends(8000) { !h.etat.emet })
        assertTrue(h.etat.erreur, h.etat.erreur.startsWith("le HackRF ne prend plus"))
        // It did not hear SIGTERM: killed.
        assertEquals(0, HackrfEmission.enCours())
        assertFalse(f.finEntree)
    }

    @Test
    fun duree_maximale_et_garde() {
        val f = faux()
        val h = HackrfEmission(f.transfer.path)
        assertNull(h.demarre(reglage(dureeMs = 900L), ton))
        assertTrue(attends(5000) { !h.etat.emet })
        assertEquals("durée maximale atteinte", h.etat.raison)
        var page: String? = null
        assertNull(h.demarre(reglage(garde = { page }), ton))
        Thread.sleep(300)
        assertTrue(h.etat.emet)
        page = "page SatMe fermée"
        assertTrue(attends(3000) { !h.etat.emet })
        assertEquals("page SatMe fermée", h.etat.raison)
        assertEquals(0, HackrfEmission.enCours())
    }

    @Test
    fun arret_general_a_la_fermeture() {
        val f = faux("bouche")
        val h = HackrfEmission(f.transfer.path, attenteDemarrageMs = 100L)
        assertNull(h.demarre(reglage(), ton))
        assertEquals(1, HackrfEmission.enCours())
        HackrfEmission.arreteTout()
        assertEquals(0, HackrfEmission.enCours())
        h.arrete()
    }

    @Test
    fun refus_avant_tout_lancement() {
        val f = faux()
        val h = HackrfEmission(f.transfer.path)
        assertTrue(h.demarre(reglage().copy(debit = 1_000_000L), ton)!!.contains("débit"))
        assertTrue(h.demarre(reglage().copy(frequenceHz = 7_000_000_000L), ton)!!.contains("fréquence"))
        assertTrue(h.demarre(reglage(serie = "-a 1"), ton)!!.contains("série"))
        assertTrue(f.args.isEmpty())
    }
}
