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
import fr.f4ioz.satcombo.sdr.Bb60Absent
import fr.f4ioz.satcombo.sdr.Bb60Sdr
import fr.f4ioz.satcombo.sdr.FauxBb60
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The SDR tab with a Signal Hound BB60: the library absent (said, nothing
 * else broken), then a fake API in its place. Never the real library, never
 * a device.
 */
class Bb60PcTest {

    private val dossier = Files.createTempDirectory("satme-bb60").toFile()
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var sdr: SdrPc? = null

    @After fun fin() { runCatching { sdr?.arrete() }; portee.cancel(); dossier.deleteRecursively() }

    private fun attends(ms: Long = 10_000, quoi: () -> Boolean) {
        val fin = System.currentTimeMillis() + ms
        while (!quoi()) { check(System.currentTimeMillis() < fin) { "trop long" }; Thread.sleep(50) }
    }

    private fun sdr(): SdrPc {
        val st = StationPc(StockageMemoire(), Catalogue(dossier, enLigne = false), PortsSeriePc(), portee)
        st.settings.sdrAudio = false
        return SdrPc(st, SonPc(), StockageMemoire(), portee).also { sdr = it; it.suitSatellite = false; it.manuelleHz = 435_000_000L }
    }

    @Test
    fun la_bibliotheque_absente_est_dite_sans_rien_casser() {
        // A path where nothing is: never the system's own (the real library may be installed on this PC).
        try { Bb60Ffm.charge(File(dossier, "libbb_api.so").path); fail("chargée") } catch (x: Bb60Absent) {
            assertTrue(x.message!!, x.message!!.startsWith(Bb60Ffm.ABSENTE))
        }
        // A folder without it.
        try { Bb60Ffm.charge(dossier.path); fail("chargée") } catch (x: Bb60Absent) {
            assertTrue(x.message!!, x.message!!.startsWith(Bb60Ffm.ABSENTE))
        }
        val d = sdr()
        d.recepteur = SdrPc.BB60
        d.bb60Biblio = File(dossier, "absente").path
        assertFalse(d.demarre())
        assertTrue(d.etat.value.erreur, d.etat.value.erreur.startsWith("bibliothèque Signal Hound absente"))
        assertFalse(d.etat.value.actif)
        // « Détecter » says the same, and the JSON carries it.
        assertTrue(d.detecteBb60().message.startsWith("bibliothèque Signal Hound absente"))
        assertTrue(JsonPc.sdr(d).getJSONObject("bb60").getString("message").startsWith("bibliothèque Signal Hound absente"))
        // Another receiver still starts.
        d.recepteur = SdrPc.GENERATEUR
        assertTrue(d.demarre())
        assertTrue(d.etat.value.actif)
    }

    @Test
    fun une_bibliotheque_sans_les_fonctions_du_bb60_est_refusee() {
        // The C library itself, loaded through the same path as bb_api: found, but not Signal Hound's.
        val libc = listOf("/lib/x86_64-linux-gnu/libc.so.6", "/lib/aarch64-linux-gnu/libc.so.6", "/usr/lib64/libc.so.6").map(::File).firstOrNull { it.isFile }
        assumeTrue(libc != null)
        try { Bb60Ffm.charge(libc!!.path); fail("acceptée") } catch (x: Bb60Absent) {
            assertTrue(x.message!!, x.message!!.contains("bbGetAPIVersion absent"))
        }
    }

    @Test
    fun le_bb60_recoit_au_debit_de_l_onglet_et_dit_ses_diagnostics() {
        val faux = FauxBb60()
        val d = sdr()
        d.chargeBb60 = { faux }
        d.recepteur = SdrPc.BB60
        d.reglagesBb60 = Bb60Sdr.Reglages(referenceDbm = -45.0, gain = 3, attenuation = 0)
        assertEquals(-45.0, d.reglagesBb60.referenceDbm, 0.0)
        assertTrue(d.demarre())
        attends { faux.appels.contains("initie") }
        assertTrue(faux.appels.toString(), faux.appels.containsAll(listOf("reference(-45.0)", "gainAtten(3,0)", "centre(435000000)", "iq(16,952560)")))
        attends { d.etat.value.debitKo > 0 }
        assertTrue(d.etat.value.tuner, d.etat.value.tuner.startsWith("Signal Hound BB60D"))
        assertEquals(9_000L..6_000_000_000L, d.gammeHz)
        // A new level applied at once: the stream started again with it.
        d.reglagesBb60 = d.reglagesBb60.copy(referenceDbm = -10.0, gain = -1)
        attends { faux.appels.contains("reference(-10.0)") && faux.appels.contains("gainAtten(-1,-1)") }
        attends { d.bb60?.diagnostic != null }
        val j = JsonPc.sdr(d).getJSONObject("bb60")
        assertEquals(-10, j.getInt("ref"))
        assertEquals(16, j.getJSONObject("flux").getInt("decimation"))
        assertEquals(41.5, j.getJSONObject("diag").getDouble("temperature"), 0.0)
        assertTrue(j.getString("libelle").contains("n° 1234567"))
        d.arrete()
        attends { faux.appels.any { it.startsWith("ferme(") } }
    }
}
