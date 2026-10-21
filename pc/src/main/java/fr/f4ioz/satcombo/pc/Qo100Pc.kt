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
    /** What each signal on the transponder is: runs only while a page asks. */
    val signaux = SignauxQo100Pc(sdr, suivi, horloge)
    /** LEILA in the dongle's sound, and in the card's when the rig is on QO-100. */
    private val leila = fr.f4ioz.satcombo.domain.DetecteurLeila(fr.f4ioz.satcombo.sdr.Dsp.AUDIO_RATE)
    @Volatile private var leilaCarte: fr.f4ioz.satcombo.domain.DetecteurLeila? = null

    init { sdr.ecouteQo100 = { pcm, n -> leila.ecoute(pcm, n, horloge()) } }

    /** The sound card's sound: listened for LEILA while the rig is on QO-100. */
    fun ecouteCarte(pcm: ShortArray, n: Int, rate: Int) {
        if (!station.qo100.etat.value.auPoste) return
        val d = leilaCarte?.takeIf { it.rate == rate } ?: fr.f4ioz.satcombo.domain.DetecteurLeila(rate).also { leilaCarte = it }
        d.ecoute(pcm, n, horloge())
    }
    private var dernierMs = 0L

    /** Calibration held on the beacon by itself (Réglages and the QO-100 tab; off by default). */
    var auto: Boolean
        get() = station.settings.qo100CaleAuto
        set(v) { station.settings.qo100CaleAuto = v }

    /** The stream locked on the middle beacon ([fr.f4ioz.satcombo.sdr.VerrouBalise]; off by default). */
    var verrouBalise: Boolean
        get() = station.settings.qo100VerrouBalise
        set(v) { station.settings.qo100VerrouBalise = v }

    private fun surLeTranspondeur() = sdr.etat.value.actif && station.qo100.etat.value.aLaCle

    /**
     * Every second: the beacon measured — every second while the calibration
     * follows it (a drifting LNB moves several hertz a second), every 5 s
     * otherwise — and the calibration moved when chosen.
     */
    fun tic() {
        val t = horloge()
        val periode = if (auto && suivi.verrouille(t, 30_000L)) 1_000L else 5_000L
        if (!surLeTranspondeur() || t - dernierMs < periode - 50L) return
        sdr.demandePanorama()
        // After a calibration step, only a panorama made since counts: an older one was taken at the
        // old tuning, read on the new axis it pushes the calibration further each time (it swung
        // ±150 Hz and FT8 slid 7 Hz a second, 21 October).
        if (sdr.panoramaTrames < trameEcrite + TRAMES_APRES_ECRITURE) return
        dernierMs = t
        val calage = station.qo100.etat.value.calageHz
        // Known lately: looked for within 3 kHz only; lost, within 20 kHz.
        val fenetre = if (suivi.verrouille(t, 120_000L)) 3_000.0 else 20_000.0
        suivi.mesure(t, sdr.mesureBaliseCentre(fenetre), calage, auto && entreEmissions(t))?.let {
            station.qo100.calage(it)
            trameEcrite = sdr.panoramaTrames
        }
    }
    /** The panorama count when the calibration last moved. */
    private var trameEcrite = Long.MIN_VALUE / 2

    /**
     * The slot (ms) of an FT8/FT4 decoder hearing the dongle's sound, null
     * when none. Each calibration write retunes: every station jumps by as
     * much at once, and a jump of a tone or more inside a transmission
     * loses it (on 21 October, writes a second apart, tens to hundreds of
     * hertz each, left FT8 with nothing for dozens of slots).
     */
    @Volatile var creneauMs: () -> Long? = { null }

    private fun entreEmissions(t: Long): Boolean = entreEmissions(t, creneauMs())

    /** LEILA heard lately (10 s), in the dongle's sound or the card's. */
    fun leilaEntendue(): Boolean = horloge().let { t -> listOfNotNull(leila, leilaCarte).any { it.entendue(t) } }

    fun json(): JSONObject {
        val t = horloge()
        val d = suivi.dernier
        val o = JSONObject().put("auto", auto).put("cle", surLeTranspondeur()).put("verrou", suivi.verrouille(t))
            .put("calage", station.qo100.etat.value.calageHz)
        // The lock on the beacon: chosen, holding, what it takes out.
        o.put("verrouBalise", verrouBalise)
        sdr.verrou?.takeIf { verrouBalise && it.actif }?.let { v ->
            o.put("vbTient", v.verrouille).put("vbGigue", Math.round(v.gigueHz * 10) / 10.0)
                .put("vbEcart", Math.round(v.ecartLentHz))
        }
        d?.let { p ->
            o.put("ecart", Math.round(p.ecartHz)).put("rapport", Math.round(p.rapportDb * 10) / 10.0).put("quand", p.t)
                .put("erreur", Math.round(p.erreurHz)).put("ppm", Math.round(SuiviBaliseQo100.ppm(p.erreurHz) * 1000) / 1000.0)
        }
        // LEILA heard lately (10 s): the station listened to sends too strong.
        listOfNotNull(leila, leilaCarte).filter { it.entendue(t) }.maxByOrNull { it.dernierMs }?.let {
            o.put("leila", true).put("leilaHz", it.tonHz).put("leilaIlYa", (t - it.dernierMs) / 1000)
        }
        suivi.deriveHzParMin(t)?.let { o.put("derive", Math.round(it * 10) / 10.0) }
        o.put("historique", JSONArray(suivi.historique().map { JSONArray().put(it.t).put(Math.round(it.erreurHz)) }))
        o.put("journee", JSONArray(suivi.journee().map { JSONArray().put(it.first).put(Math.round(it.second)) }))
        o.put("journeeNiveau", JSONArray(suivi.journeeNiveau().map { JSONArray().put(it.first).put(Math.round(it.second * 10) / 10.0) }))
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

    companion object {
        /** Panoramas to wait after a calibration step before measuring again (one is made every 300 ms). */
        const val TRAMES_APRES_ECRITURE = 3L
        /**
         * The end of a slot where the calibration may move: transmissions
         * start at 0.5 s and last 12.64 s (FT8) or 5.04 s (FT4); a late
         * station still ends before the last second.
         */
        const val FIN_DE_CRENEAU_MS = 1_000L

        /** A calibration write allowed at [t]: no FT8/FT4 slot heard, or its last second (between transmissions). */
        fun entreEmissions(t: Long, creneauMs: Long?): Boolean {
            val c = creneauMs?.takeIf { it > FIN_DE_CRENEAU_MS } ?: return true
            return Math.floorMod(t, c) >= c - FIN_DE_CRENEAU_MS
        }
    }
}
