/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.datv.Datv
import fr.f4ioz.satcombo.datv.Emetteur
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import fr.f4ioz.satcombo.sdr.CanalIqSimule
import fr.f4ioz.satcombo.sdr.CorrectionsIq
import fr.f4ioz.satcombo.sdr.EmetteurDatv
import fr.f4ioz.satcombo.sdr.EtatEmission
import fr.f4ioz.satcombo.sdr.ParametresIq
import fr.f4ioz.satcombo.sdr.SourceIq
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The calibration from the page, on a simulated station: no radio. */
class CalibrationIqPcTest {

    private class Faux(val corrections: CorrectionsIq) : MaterielIq {
        var refus: String? = null
        var series = mutableMapOf(Emetteur.PLUTO to "1044730a1", Emetteur.BLADERF to "b0c1", Emetteur.HACKRF to "")
        var demande: TonEssai? = null
        var rendu = 0
        var tx: Tx? = null

        class Tx : EmetteurDatv {
            @Volatile var e = EtatEmission(emet = true)
            override val nom = "faux"
            override fun etatCommun() = e
            override fun arrete(raison: String) { e = e.copy(emet = false, raison = raison) }
            override fun regleGain(db: Double): Double? = null
        }

        override fun serie(app: Emetteur, lis: Boolean) = series.getValue(app)
        override fun refus(app: Emetteur) = refus
        override fun mesureur(app: Emetteur) = if (app == Emetteur.BLADERF) Datv.PLUTO else Datv.BLADERF
        override fun emet(app: Emetteur, t: TonEssai, producteur: (ShortArray) -> Unit): Pair<EmetteurDatv?, String?> {
            demande = t
            // The transmitter asks for its board's correction, as the real ones do.
            t.correction(series.getValue(app))
            return Tx().also { tx = it } to null
        }
        override fun ouvreRx(rx: String, app: Emetteur): SourceIq =
            CanalIqSimule(corrections.pour(CalibrationIqPc.cle(app), series.getValue(app)), debit = CalibrationIqPc.DEBIT.toDouble(), bruit = 1e-4)
        override fun rendRx(rx: String) { rendu++ }
    }

    private fun attends(c: CalibrationIqPc) {
        val fin = System.currentTimeMillis() + 60_000
        while (c.etat.actif && System.currentTimeMillis() < fin) { c.json(); Thread.sleep(50) }
    }

    @Test
    fun calibration_automatique_gardee_pour_la_carte() {
        val s = StockageMemoire()
        val corr = CorrectionsIq(s)
        val f = Faux(corr)
        val c = CalibrationIqPc(s, corr, f, attenteMs = { 0L })
        assertEquals("émission non confirmée", c.demarre(Emetteur.PLUTO, auto = true, mesurer = false, confirme = false))
        assertNull(c.demarre(Emetteur.PLUTO, auto = true, mesurer = false, confirme = true))
        assertTrue(c.tient(Emetteur.PLUTO))
        attends(c)
        assertFalse(c.etat.actif)
        assertEquals(c.etat.erreur, "", c.etat.erreur)
        // Indoors, at 1245 MHz, −40 dB at most.
        val t = f.demande!!
        assertEquals(1_245_000_000L, t.frequenceHz)
        assertTrue(t.gainDb <= -40.0)
        assertFalse(f.tx!!.e.emet)
        assertEquals(1, f.rendu)
        val p = CorrectionsIq(s).pour(CorrectionsIq.PLUTO, "1044730a1").parametres
        assertFalse(p.iqNeutre)
        assertEquals(3.2, p.ppm, 0.05)
        val m = c.etat.mesure!!
        assertTrue("fuite " + m.fuiteDbc, m.fuiteDbc < -50)
        assertTrue("image " + m.imageDbc, m.imageDbc < -50)
        assertTrue(c.etat.resultat, c.etat.resultat.contains("fuite"))
        val j = c.json()
        assertEquals("pluto", j.getJSONArray("appareils").getJSONObject(0).getString("cle"))
        assertTrue(j.getJSONArray("appareils").getJSONObject(0).getString("resultat").isNotEmpty())
    }

    @Test
    fun le_bladerf_reste_la_reference_de_frequence() {
        val s = StockageMemoire()
        val corr = CorrectionsIq(s)
        val c = CalibrationIqPc(s, corr, Faux(corr), attenteMs = { 0L })
        assertNull(c.demarre(Emetteur.BLADERF, auto = true, mesurer = false, confirme = true))
        attends(c)
        val p = corr.pour(CorrectionsIq.BLADERF, "b0c1").parametres
        assertFalse(p.iqNeutre)
        assertEquals(0.0, p.ppm, 0.0)
        assertNotNull(c.etat.ppmMesure)
    }

    @Test
    fun refus_et_curseurs() {
        val s = StockageMemoire()
        val corr = CorrectionsIq(s)
        val f = Faux(corr)
        val c = CalibrationIqPc(s, corr, f, attenteMs = { 0L })
        assertTrue(c.demarre(Emetteur.HACKRF, auto = true, mesurer = false, confirme = true)!!.contains("numéro de série"))
        f.refus = "une émission DATV est en cours : l'arrêter d'abord"
        assertEquals(f.refus, c.demarre(Emetteur.PLUTO, auto = false, mesurer = true, confirme = true))
        assertFalse(c.enCours)
        // The sliders: kept, live on the board's correction.
        val vive = corr.pour(CorrectionsIq.PLUTO, "1044730a1")
        assertNull(c.regle(Emetteur.PLUTO, ParametresIq(dcI = 0.002, phaseDeg = 1.5)))
        assertEquals(0.002, vive.parametres.dcI, 0.0)
        assertEquals(1.5, CorrectionsIq(s).pour(CorrectionsIq.PLUTO, "1044730a1").parametres.phaseDeg, 0.0)
        assertNotNull(c.regle(Emetteur.HACKRF, ParametresIq(dcI = 0.002)))
        // The tone's attenuation: −40 dB at most.
        c.gainTxDb = -10.0
        assertEquals(-40.0, c.gainTxDb, 0.0)
    }
}
