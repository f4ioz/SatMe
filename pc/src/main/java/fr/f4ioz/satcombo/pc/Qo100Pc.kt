/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.domain.PanoramaQo100
import fr.f4ioz.satcombo.domain.Qo100
import fr.f4ioz.satcombo.domain.SignauxQo100
import fr.f4ioz.satcombo.domain.SuiviBaliseQo100
import org.json.JSONArray
import org.json.JSONObject

/**
 * QO-100 with the dongle on the transponder: the middle beacon measured
 * every few seconds, the LNB's error and drift kept, the calibration held on
 * the beacon by itself when chosen, the stations heard listed.
 */
class Qo100Pc(private val station: StationPc, private val sdr: SdrPc, private val horloge: () -> Long = System::currentTimeMillis) {

    val suivi = SuiviBaliseQo100()
    private var dernierMs = 0L

    /** Calibration held on the beacon by itself (Réglages and the QO-100 tab; off by default). */
    var auto: Boolean
        get() = station.settings.qo100CaleAuto
        set(v) { station.settings.qo100CaleAuto = v }

    private fun surLeTranspondeur() = sdr.etat.value.actif && station.qo100.etat.value.aLaCle

    /** Every second: every 5 s the beacon measured, and the calibration moved when chosen. */
    fun tic() {
        val t = horloge()
        if (!surLeTranspondeur() || t - dernierMs < 5_000L) return
        dernierMs = t
        sdr.demandePanorama()
        val calage = station.qo100.etat.value.calageHz
        // Known lately: looked for within 3 kHz only; lost, within 20 kHz.
        val fenetre = if (suivi.verrouille(t, 120_000L)) 3_000.0 else 20_000.0
        suivi.mesure(t, sdr.mesureBaliseCentre(fenetre), calage, auto)?.let { station.qo100.calage(it) }
    }

    fun json(): JSONObject {
        val t = horloge()
        val d = suivi.dernier
        val o = JSONObject().put("auto", auto).put("cle", surLeTranspondeur()).put("verrou", suivi.verrouille(t))
            .put("calage", station.qo100.etat.value.calageHz)
        d?.let { p ->
            o.put("ecart", Math.round(p.ecartHz)).put("rapport", Math.round(p.rapportDb * 10) / 10.0).put("quand", p.t)
                .put("erreur", Math.round(p.erreurHz)).put("ppm", Math.round(SuiviBaliseQo100.ppm(p.erreurHz) * 1000) / 1000.0)
        }
        suivi.deriveHzParMin(t)?.let { o.put("derive", Math.round(it * 10) / 10.0) }
        o.put("historique", JSONArray(suivi.historique().map { JSONArray().put(it.t).put(Math.round(it.erreurHz)) }))
        o.put("journee", JSONArray(suivi.journee().map { JSONArray().put(it.first).put(Math.round(it.second)) }))
        o.put("ecartees", suivi.ecartees)
        suivi.erreurRecente()?.let { o.put("erreurMediane", Math.round(it)) }
        // The stations heard: the transponder's view, a column per 500 Hz.
        val pan = sdr.panorama
        val axe = sdr.axeQo100()
        if (surLeTranspondeur() && pan != null && axe != null) {
            val cols = PanoramaQo100.colonnes(pan, axe.first, axe.second, 1000)
            if (cols != null) {
                val vus = cols.filter { it != PanoramaQo100.HORS }.sorted()
                if (vus.isNotEmpty()) o.put("plancher", Math.round(vus[vus.size / 2]))
                val l = SignauxQo100.trouve(cols, Qo100.REGLETTE_BAS_HZ, Qo100.REGLETTE_HAUT_HZ)
                o.put("signaux", JSONArray(l.map { s -> JSONObject().put("hz", s.hz).put("largeur", s.largeurHz).put("snr", Math.round(s.rapportDb))
                    .put("seg", s.segment ?: "") }))
            }
        }
        return o
    }
}
