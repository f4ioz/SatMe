/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sdr

import fr.f4ioz.satcombo.sdr.Balayage.Canal
import fr.f4ioz.satcombo.sdr.Balayage.Etat
import fr.f4ioz.satcombo.sdr.Balayage.Genre
import fr.f4ioz.satcombo.sdr.Balayage.Ordre
import fr.f4ioz.satcombo.sdr.Balayage.Reprise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The scan's state machine, with a pretend receiver (a spectrum drawn with
 * carriers where the test puts them), then with the test generator through
 * SatMe's receive chain.
 */
class BalayageTest {

    /**
     * A pretend receiver: [porteuses] (sky Hz → dB above the noise) drawn on a
     * 4096-bin spectrum of 176.4 kHz around where it is tuned; a tuning more
     * than 75 kHz away moves its centre (40 kHz below), as SatMe-PC does.
     */
    private class Faux(var centre: Long, val porteuses: MutableMap<Long, Double>, val binHz: Double = 43.06, val n: Int = 4096) {
        var trame = 0L
        var accords = 0
        var recentrages = 0
        var ecoute = 0L
        private var graine = 1
        private fun bruit(): Float { graine = graine * 1103515245 + 12345; return ((graine ushr 8) % 1000) / 1000f * 3f }
        fun vue(): Balayage.Vue {
            trame++
            val d = FloatArray(n) { -100f + bruit() }
            for ((hz, db) in porteuses) {
                val k = Math.round((hz - centre) / binHz).toInt() + n / 2
                if (k in 0 until n) for (j in -1..1) if (k + j in 0 until n) d[k + j] = (-100 + db - abs(j) * 6).toFloat()
            }
            return Balayage.Vue(trame, centre, binHz, d, squelch = porteuses.keys.any { abs(it - ecoute) < 3_000 })
        }
        fun applique(o: Ordre?) {
            when (o) {
                is Ordre.Accorde -> { accords++; ecoute = o.canal.hz; if (abs(o.canal.hz - centre) > 75_000) { centre = o.canal.hz - 40_000; recentrages++ } }
                is Ordre.Centre -> { recentrages++; centre = o.hz; ecoute = o.hz }
                null -> {}
            }
        }
    }

    /** Ticks every [pasMs] until [fin] or [jusqua] holds; the time at the end. */
    private fun tourne(b: Balayage, f: Faux, depuis: Long, jusqua: Long, pasMs: Long = 50, fin: () -> Boolean = { false }): Long {
        var t = depuis
        while (t < jusqua && !fin()) { f.applique(b.tic(t, f.vue())); t += pasMs }
        return t
    }

    private val plage = Balayage.Reglages(genre = Genre.PLAGE, debutHz = 145_500_000L, finHz = 145_700_000L, pasHz = 12_500.0, seuilDb = 12.0)

    @Test
    fun la_plage_trouve_la_porteuse_et_reprend_quand_elle_se_tait() {
        val f = Faux(145_600_000L, mutableMapOf(145_637_500L to 30.0))
        val b = Balayage(plage.copy(reprise = Reprise.PORTEUSE, maintienMs = 2_000), 0L)
        assertEquals(17, b.taille)
        var t = tourne(b, f, 0, 20_000) { b.etat == Etat.ARRETE }
        assertEquals(Etat.ARRETE, b.etat)
        assertEquals(145_637_500L, b.touches().single().canal.hz)
        assertTrue(b.touches().single().niveauDb > 20)
        // Still there: it stays.
        t = tourne(b, f, t, t + 5_000)
        assertEquals(Etat.ARRETE, b.etat)
        // Gone: 2 s later, on again.
        f.porteuses.clear()
        val coupe = t
        t = tourne(b, f, t, t + 10_000) { b.etat != Etat.ARRETE }
        assertEquals(Etat.CHERCHE, b.etat)
        assertTrue("${t - coupe} ms", t - coupe in 2_000..2_400)
    }

    @Test
    fun hors_de_la_bande_vue_le_recepteur_est_recentre() {
        val f = Faux(144_000_000L, mutableMapOf(145_650_000L to 25.0))
        val b = Balayage(plage, 0L)
        tourne(b, f, 0, 30_000) { b.etat == Etat.ARRETE }
        assertEquals(145_650_000L, b.touches().single().canal.hz)
        // 145.5 → 145.65 MHz: each move gives 115 kHz of offset tuning, a few moves only.
        assertTrue("${f.recentrages} déplacements", f.recentrages in 1..5)
        assertEquals(13, f.accords)
    }

    @Test
    fun reprise_au_bout_du_temps_et_reprise_a_la_main() {
        val f = Faux(145_600_000L, mutableMapOf(145_550_000L to 30.0))
        val b = Balayage(plage.copy(reprise = Reprise.TEMPS, tempsMs = 3_000), 0L)
        var t = tourne(b, f, 0, 20_000) { b.etat == Etat.ARRETE }
        val arret = t
        t = tourne(b, f, t, t + 10_000) { b.etat != Etat.ARRETE }
        assertTrue("${t - arret}", t - arret in 3_000..3_200)
        // Round again: the same signal stops it again, counted twice.
        t = tourne(b, f, t, t + 20_000) { b.etat == Etat.ARRETE }
        assertEquals(2, b.touches().single().fois)

        val m = Balayage(plage.copy(reprise = Reprise.MANUELLE), 0L)
        val f2 = Faux(145_600_000L, mutableMapOf(145_550_000L to 30.0))
        t = tourne(m, f2, 0, 20_000) { m.etat == Etat.ARRETE }
        f2.porteuses.clear()
        t = tourne(m, f2, t, t + 15_000)
        assertEquals(Etat.ARRETE, m.etat)
        m.reprend()
        assertEquals(Etat.CHERCHE, m.etat)
    }

    @Test
    fun une_frequence_passee_ne_l_arrete_jamais() {
        val f = Faux(145_600_000L, mutableMapOf(145_550_000L to 30.0, 145_625_000L to 30.0))
        val b = Balayage(plage.copy(passe = listOf(145_550_000L), reprise = Reprise.TEMPS, tempsMs = 500), 0L)
        tourne(b, f, 0, 30_000)
        assertEquals(listOf(145_625_000L), b.touches().map { it.canal.hz })
        // Passed by while stopped on it: on again at once.
        val b2 = Balayage(plage.copy(reprise = Reprise.MANUELLE), 0L)
        tourne(b2, f, 0, 20_000) { b2.etat == Etat.ARRETE }
        b2.ajoutePasse(b2.touches().first().canal.hz)
        assertEquals(Etat.CHERCHE, b2.etat)
    }

    @Test
    fun le_squelch_ouvert_arrete_aussi() {
        val f = Faux(145_600_000L, mutableMapOf(145_587_500L to 3.0))
        val b = Balayage(plage.copy(critere = Balayage.Critere.SQUELCH), 0L)
        tourne(b, f, 0, 20_000) { b.etat == Etat.ARRETE }
        assertEquals(Etat.ARRETE, b.etat)
        assertEquals(145_587_500L, b.touches().single().canal.hz)
        // 3 dB: under the level threshold, not found by level.
        val n = Balayage(plage, 0L)
        tourne(n, f, 0, 10_000)
        assertTrue(n.touches().isEmpty())
    }

    @Test
    fun les_memoires_et_le_canal_prioritaire() {
        val iss = Canal(145_800_000L, "NFM", 0, "ISS", "a")
        val so50 = Canal(436_795_000L, "NFM", 0, "SO-50", "b")
        val relais = Canal(145_600_000L, "NFM", 0, "Relais", "c")
        val f = Faux(145_700_000L, mutableMapOf())
        val b = Balayage(Balayage.Reglages(genre = Genre.MEMOIRES, canaux = listOf(iss, so50), priorite = relais, prioriteMs = 2_000), 0L)
        var t = tourne(b, f, 0, 10_000)
        assertTrue(b.touches().isEmpty())
        assertTrue(f.accords > 10)
        // The priority channel comes alive: found within one priority period.
        f.porteuses[145_600_000L] = 30.0
        val debut = t
        t = tourne(b, f, t, t + 10_000) { b.etat == Etat.ARRETE }
        assertEquals("Relais", b.touches().single().canal.nom)
        assertTrue(b.touches().single().priorite)
        assertTrue("${t - debut}", t - debut <= 2_600)
    }

    @Test
    fun le_balayage_par_le_spectre_juge_une_bande_entiere_a_la_fois() {
        // A wide receiver: 16384 bins of 64.6 Hz (1.06 MHz).
        val f = Faux(430_000_000L, mutableMapOf(433_412_500L to 25.0, 433_425_000L to 20.0, 437_100_000L to 20.0), binHz = 64.6, n = 16_384)
        val r = Balayage.Reglages(genre = Genre.SPECTRE, debutHz = 430_000_000L, finHz = 440_000_000L, pasHz = 12_500.0,
            seuilDb = 12.0, reprise = Reprise.TEMPS, tempsMs = 1_000, largeurVueHz = 900_000L)
        val b = Balayage(r, 0L)
        assertEquals(12, b.taille)
        var t = tourne(b, f, 0, 60_000) { b.tours >= 1 }
        // Both signals, the neighbouring channel of the strong one not stopped on (a local top only).
        assertEquals(listOf(433_412_500L, 437_100_000L), b.touches().map { it.canal.hz }.sorted())
        // 10 MHz in a round of 12 bands, two stops of 1 s: far under the 800 channels one by one.
        assertTrue("$t ms", t < 8_000)
        // The receiver judged the band, the channels never tuned one by one.
        assertTrue("${f.accords}", f.accords <= 4)
    }

    @Test
    fun le_centre_de_la_bande_n_est_pas_pris_pour_un_signal() {
        val v = Balayage.Vue(1, 145_000_000L, 43.06, FloatArray(4096) { if (abs(it - 2048) <= 2) -40f else -100f })
        assertEquals(0.0, v.niveau(145_000_000L + 6_250, 10_000.0)!!, 3.0)
        assertTrue(v.niveau(145_000_000L, 2_000.0)?.let { it < 3 } ?: true)
        assertNull(v.niveau(145_200_000L, 10_000.0))
    }

    @Test
    fun le_generateur_d_essai_porteuse_trouvee_par_la_plage() {
        // The generator's carrier alone, 25 kHz above its centre; its I/Q through the receive chain's spectrum.
        val rien = GenerateurIq.Signal(false, 0, 0.0)
        val centre = 145_800_000L
        val g = GenerateurIq(GenerateurIq.Reglages(porteuse = GenerateurIq.Signal(true, 25_000, 30.0), cw = rien, blu = rien, fm = rien, ft8 = rien, sstv = rien),
            cadence = false, debutMs = 0L)
        val chaine = RxChain()
        val iq = ShortArray(2 * 16_384)
        val pcm = ShortArray(chaine.maxAudio(iq.size))
        var trames = 0L
        fun vue(): Balayage.Vue {
            val avant = chaine.spectrum.frames
            while (chaine.spectrum.frames == avant) { val n = g.read16(iq); chaine.process16(iq, n, pcm, feedSpectrum = true) }
            trames++
            return Balayage.Vue(trames, centre, Dsp.RTL_RATE.toDouble() / Dsp.DECIM_1 / Dsp.SPECTRUM_SIZE, chaine.spectrum.magDb.copyOf())
        }
        val b = Balayage(Balayage.Reglages(genre = Genre.PLAGE, debutHz = centre - 50_000, finHz = centre + 50_000, pasHz = 12_500.0, seuilDb = 12.0), 0L)
        var t = 0L
        var vu: Ordre? = null
        while (t < 30_000 && b.etat != Etat.ARRETE) { val o = b.tic(t, vue()); if (o != null) vu = o; t += 100 }
        assertEquals(Etat.ARRETE, b.etat)
        val h = b.touches().single()
        assertEquals(centre + 25_000, h.canal.hz)
        assertTrue("${h.niveauDb} dB", h.niveauDb > 20)
        assertNotNull(vu)
        // And nothing elsewhere: the noise alone never stops it (one round).
        val b2 = Balayage(Balayage.Reglages(genre = Genre.PLAGE, debutHz = centre - 75_000, finHz = centre - 30_000, pasHz = 5_000.0, seuilDb = 12.0, boucle = false), 0L)
        t = 0
        while (t < 30_000 && b2.etat != Etat.FINI) { b2.tic(t, vue()); t += 100 }
        assertEquals(Etat.FINI, b2.etat)
        assertTrue(b2.touches().isEmpty())
    }
}
