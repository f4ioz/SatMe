/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.domain.CalibrationFrequence
import fr.f4ioz.satcombo.sdr.Dsp
import org.json.JSONArray
import org.json.JSONObject

/**
 * The SDR calibration assistant (Réglages › Poste et rotor, or the SDR tab's
 * « Calibrer… »): the dongle tuned to a known signal, its peak found in the
 * last spectrum, the error worked out — the dongle's ppm, and behind an LNB
 * the oscillator's real frequency.
 *
 * Receive only: it tunes the SDR tab's receiver (manual frequency), nothing
 * else; it never transmits. Saving is the operator's click.
 */
class CalibrationSdrPc(private val station: StationPc, private val sdr: () -> SdrPc?) {

    /** The spectrum held at its maximum across reads (the GSM FCCH comes in bursts), for this PLL. */
    private var tenu: FloatArray? = null
    private var tenuPll = 0L

    /** The references offered, and the GSM helper's constant. */
    fun references(): JSONObject = JSONObject()
        .put("references", JSONArray(CalibrationFrequence.REFERENCES.map {
            JSONObject().put("cle", it.cle).put("nom", it.nom).put("hz", it.hz).put("explication", it.explication) }))
        .put("fcch", CalibrationFrequence.FCCH_DECALAGE_HZ)
        .put("ppm", station.settings.sdrPpm)

    /** The FCCH of [arfcn], or why not. */
    fun fcch(arfcn: Int): JSONObject = CalibrationFrequence.fcchHz(arfcn)?.let { JSONObject().put("ok", true).put("hz", it) }
        ?: JSONObject().put("ok", false).put("erreur", "canal $arfcn : ni GSM-900 (0–124, 975–1023) ni DCS-1800 (512–885)")

    /** The SDR tab's receiver to [cielHz] (manual frequency, no Doppler), started when it was not. */
    @Synchronized fun accorde(cielHz: Long): JSONObject {
        val d = sdr() ?: return JSONObject().put("ok", false).put("erreur", "pas de récepteur SDR")
        if (cielHz !in 24_000_000L..12_000_000_000L) return JSONObject().put("ok", false).put("erreur", "fréquence hors de portée")
        d.suitSatellite = false
        d.manuelleHz = cielHz
        d.decalageHz = 0
        tenu = null
        // The test generator (the demonstration) is centred at its start: started again on the new frequency.
        if (d.fictif && d.etat.value.actif) d.demarre()
        if (!d.etat.value.actif && !d.demarre()) return JSONObject().put("ok", false).put("erreur", d.etat.value.erreur.ifBlank { "le récepteur ne démarre pas" })
        return JSONObject().put("ok", true).put("fi", station.versCle(cielHz))
    }

    /**
     * The measurement on the last spectrum: [cielHz] the reference, searched
     * within [fenetreHz]; [maintien] holds each bin's maximum across reads;
     * [picManuel] (sky Hz) replaces the peak found (the operator's click);
     * [deuxLobes] for the PSK beacon.
     */
    @Synchronized fun mesure(cielHz: Long, fenetreHz: Double, maintien: Boolean, picManuel: Long?, deuxLobes: Boolean): JSONObject {
        val d = sdr() ?: return JSONObject().put("ok", false).put("erreur", "pas de récepteur SDR")
        val sp = d.spectre ?: return JSONObject().put("ok", false).put("erreur", "pas encore de spectre : la clé SDR démarre-t-elle ?")
        val pll = d.spectrePllHz
        val parBin = Dsp.RTL_RATE.toDouble() / Dsp.DECIM_1 / sp.size
        val cielPll = d.cielDePll(pll)
        val sens = d.sensSpectre(cielPll)
        val attendu = sens * (cielHz - cielPll).toDouble()
        val demi = sp.size / 2 * parBin
        if (kotlin.math.abs(attendu) > demi) return JSONObject().put("ok", false).put("erreur", "la référence n'est pas dans le spectre de la clé (accord en cours ?)")
        val s = if (maintien) {
            val t = tenu?.takeIf { tenuPll == pll && it.size == sp.size }
            (t?.let { FloatArray(sp.size) { i -> maxOf(it[i], sp[i]) } } ?: sp.copyOf()).also { tenu = it; tenuPll = pll }
        } else { tenu = null; sp }
        val fen = fenetreHz.coerceIn(500.0, demi)
        val pic = if (picManuel != null) {
            val off = sens * (picManuel - cielPll).toDouble()
            val j = (Math.round(off / parBin).toInt() + sp.size / 2).coerceIn(0, sp.size - 1)
            CalibrationFrequence.Pic(off, s[j].toDouble(), 0.0)
        } else if (deuxLobes) CalibrationFrequence.picDouble(s, parBin, attendu, fen) else CalibrationFrequence.pic(s, parBin, attendu, fen)

        val o = JSONObject().put("ok", true).put("ciel", cielHz).put("pll", pll).put("ppm", station.settings.sdrPpm)
            .put("fictif", d.fictif)
        // The zoom: the window and a little more, in sky order (low to high), at most 600 points.
        val large = minOf(fen * 1.6, demi)
        val k = 600
        val db = JSONArray()
        for (i in 0 until k) {
            val fa = -large + 2 * large * i / k; val fb = fa + 2 * large / k
            // Sky [fa, fb] around the reference → bins, the axis turned over when the converter is.
            val a0 = attendu + sens * fa; val b0 = attendu + sens * fb
            val j0 = Math.floor(minOf(a0, b0) / parBin).toInt() + sp.size / 2
            val j1 = maxOf(j0 + 1, Math.ceil(maxOf(a0, b0) / parBin).toInt() + sp.size / 2)
            var m = -200f
            for (j in j0 until j1) if (j in s.indices) m = maxOf(m, s[j])
            db.put(Math.round(m))
        }
        o.put("zoom", JSONObject().put("de", cielHz - large).put("a", cielHz + large).put("db", db).put("fenetre", fen))
        if (pic == null || (picManuel == null && pic.snrDb < CalibrationFrequence.SNR_MIN_DB))
            return o.put("pic", JSONObject.NULL).put("conseil", "Aucun signal net dans la fenêtre : vérifier l'antenne, élargir la fenêtre, ou cliquer sur le pic dans le spectre.")

        val luCiel = cielPll + sens * pic.decalageHz
        val ecartCiel = luCiel - cielHz
        // At the dongle: its own frequency, the offset with the converter's sign.
        val fi = station.versCle(cielHz).toDouble()
        val ecartFi = sens * ecartCiel
        o.put("pic", JSONObject().put("ciel", Math.round(luCiel)).put("snr", Math.round(pic.snrDb * 10) / 10.0).put("manuel", picManuel != null))
            .put("ecart", Math.round(ecartCiel)).put("fi", Math.round(fi))
            .put("ppmCle", Math.round(CalibrationFrequence.ppmCle(station.settings.sdrPpm, ecartFi, fi) * 100) / 100.0)
            .put("ppmCleEntier", CalibrationFrequence.ppmCleEntier(station.settings.sdrPpm, ecartFi, fi))
            .put("ppmEcart", Math.round(CalibrationFrequence.ppm(ecartFi, fi) * 100) / 100.0)
        // Behind a converter: its real oscillator, if the dongle is right.
        val i = station.bandes().indexOfFirst { it.sdr && it.couvre(cielHz) }
        if (i >= 0) {
            val b = station.bandes()[i]
            val ol = CalibrationFrequence.olReel(b.olHz, ecartCiel)
            o.put("bande", JSONObject().put("i", i).put("nom", b.nom).put("ol", b.olHz).put("olReel", ol)
                .put("erreur", ol - b.olHz).put("ppmOl", Math.round(CalibrationFrequence.ppm((ol - b.olHz).toDouble(), b.olHz.toDouble()) * 1000) / 1000.0)
                .put("credible", CalibrationFrequence.olCredible(b.olHz, ol)))
        }
        return o
    }

    /** The dongle's correction saved (and applied to the running dongle). */
    fun posePpm(v: Int): JSONObject {
        val d = sdr() ?: return JSONObject().put("ok", false).put("erreur", "pas de récepteur SDR")
        if (v !in -CalibrationFrequence.PPM_CLE_MAX..CalibrationFrequence.PPM_CLE_MAX) return JSONObject().put("ok", false).put("erreur", "hors de ±200 ppm")
        d.ppm(v)
        tenu = null
        return JSONObject().put("ok", true).put("ppm", station.settings.sdrPpm)
    }

    /** Line [i]'s oscillator set to [olHz] (the measured one), if it is still the line [nom]. */
    fun poseOl(i: Int, nom: String, olHz: Long): JSONObject {
        val t = station.bandes()
        val b = t.getOrNull(i)?.takeIf { it.nom == nom } ?: return JSONObject().put("ok", false).put("erreur", "la table a changé : mesurer à nouveau")
        if (!CalibrationFrequence.olCredible(b.olHz, olHz)) return JSONObject().put("ok", false).put("erreur", "écart de plus de 1 MHz : mauvaise référence ou mauvais préréglage")
        station.poseBandes(t.toMutableList().also { it[i] = b.copy(olHz = olHz) })
        tenu = null
        return JSONObject().put("ok", true).put("ol", olHz)
    }
}
