/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv

import fr.f4ioz.satcombo.i18n.I18n
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Our MiniTiouner's level on the ground station's spectrum, and the line comparing both. */
class NiveauRecuTest {

    @After fun francais() = I18n.apply("fr", "")

    private val r = ReglageDatv(cielKhz = 10_491_500L, srKs = 1500, olKhz = 9_750_000L)
    /** The beacon as the ground station sees it: 8.2 dB over its noise. */
    private val balise = SignalLarge(10_490_800, 10_492_240, 10_491_520, 1437, 1500, 37_870, 0.0, true, false)
    private val autre = SignalLarge(10_496_490, 10_496_983, 10_496_736, 492, 500, 34_977, -0.9, false, false)

    @Test
    fun mer_en_unites() {
        // (C+N)/N: 0 dB of C/N is 3 dB over the noise; a strong signal tends to its MER.
        assertEquals(3.01, NiveauRecu.hauteurDb(0.0), 0.01)
        assertEquals(6.89, NiveauRecu.hauteurDb(5.9), 0.01)
        assertEquals(20.04, NiveauRecu.hauteurDb(20.0), 0.01)
        assertEquals(BandeLarge.BRUIT, NiveauRecu.unites(0.0))
        assertEquals(BandeLarge.BRUIT + 32_768, NiveauRecu.unites(10.0))
        assertEquals(BandeLarge.BRUIT, NiveauRecu.unites(-3.0))
        assertEquals(8.2, NiveauRecu.hauteurStationDb(37_870), 0.01)
        // Both ways round.
        assertEquals(6.88, NiveauRecu.hauteurStationDb(NiveauRecu.unites(6.88)), 0.001)
    }

    @Test
    fun accroche_sur_la_balise() {
        val e = EtatDatv(actif = true, etat = 4, fiTrouveeKhz = 741_540L, srTrouveKs = 1499, merDb = 5.9, modcod = "QPSK 4/5", trames = false)
        val m = NiveauRecu.calcule(e, r, listOf(balise, autre))!!
        assertEquals(10_491_540L, m.khz)
        assertEquals(1499, m.srKs)
        assertEquals(2024L, m.largeurKhz)
        assertTrue(m.verrouille)
        assertEquals(NiveauRecu.unites(NiveauRecu.hauteurDb(5.9)), m.niveau)
        assertEquals(4.68, m.seuilDb!!, 1e-9)
        assertEquals(balise, m.station)
        assertEquals(8.2, m.stationDb!!, 1e-9)
        assertEquals("Balise : +8,2 dB à la station sol · chez moi : MER 5,9 dB (seuil QPSK 4/5 4,7 dB)", NiveauRecu.ligne(m))
        I18n.apply("en", "")
        assertEquals("Beacon: +8.2 dB at the ground station · here: MER 5.9 dB (QPSK 4/5 needs 4.7 dB)", NiveauRecu.ligne(m))
    }

    @Test
    fun recherche_la_balise_est_la() {
        val e = EtatDatv(actif = true, etat = 1, merDb = 0.0, modcod = "DummyPL")
        val m = NiveauRecu.calcule(e, r.copy(cielKhz = 10_491_522L), listOf(balise, autre))!!
        assertEquals(10_491_522L, m.khz)
        assertFalse(m.verrouille)
        assertNull(m.merDb); assertNull(m.niveau); assertNull(m.seuilDb)
        assertEquals("Balise : +8,2 dB à la station sol · chez moi : rien (recherche) — la balise est là : vérifier pointage / polarisation / LNB",
            NiveauRecu.ligne(m))
    }

    @Test
    fun recherche_rien_nulle_part() {
        val m = NiveauRecu.calcule(EtatDatv(actif = true, etat = 1), r.copy(cielKhz = 10_498_000L, srKs = 333), listOf(balise, autre))!!
        assertNull(m.station)
        assertEquals("Station sol : rien ici · chez moi : rien (recherche) — rien non plus à la station sol : choisir un signal occupé", NiveauRecu.ligne(m))
        // A signal of another station: « le signal ».
        val n = NiveauRecu.calcule(EtatDatv(actif = true, etat = 1), r.copy(cielKhz = 10_496_750L, srKs = 500), listOf(balise, autre))!!
        assertEquals(autre, n.station)
        assertTrue(NiveauRecu.ligne(n).endsWith("le signal est là : vérifier pointage / polarisation / LNB"))
        assertTrue(NiveauRecu.ligne(n).startsWith("Signal : +7,3 dB à la station sol"))
    }

    @Test
    fun arrete_sans_spectre_hors_bande() {
        val m = NiveauRecu.calcule(EtatDatv(), r, null)!!
        assertFalse(m.spectre)
        assertEquals("chez moi : récepteur arrêté", NiveauRecu.ligne(m))
        // Searching without a spectrum: no advice from it.
        assertEquals("chez moi : rien (recherche)", NiveauRecu.ligne(NiveauRecu.calcule(EtatDatv(actif = true, etat = 1), r, null)!!))
        // The narrow band or a terrestrial frequency: not on this spectrum.
        assertNull(NiveauRecu.calcule(EtatDatv(actif = true, etat = 1), r.copy(cielKhz = 10_489_750L), emptyList()))
        assertNull(NiveauRecu.calcule(EtatDatv(actif = true, etat = 1), r.copy(cielKhz = 1_245_000L, olKhz = 0L), emptyList()))
    }

    @Test
    fun json_pour_la_page() {
        val e = EtatDatv(actif = true, etat = 4, fiTrouveeKhz = 741_540L, srTrouveKs = 1499, merDb = 5.9, modcod = "QPSK 4/5")
        val j = NiveauRecu.json(NiveauRecu.calcule(e, r, listOf(balise))!!)
        assertEquals(10_491_540L, j.getLong("khz"))
        assertEquals(6.9, j.getDouble("hauteur"), 1e-9)
        assertEquals(8.2, j.getDouble("stationDb"), 1e-9)
        assertTrue(j.getBoolean("stationBalise"))
        assertTrue(j.getString("ligne").contains("MER 5,9 dB"))
    }
}
