/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.demo

import fr.f4ioz.satcombo.domain.Convertisseur
import fr.f4ioz.satcombo.domain.MemoiresQo100
import fr.f4ioz.satcombo.domain.MesureBalise
import fr.f4ioz.satcombo.domain.PanoramaQo100
import fr.f4ioz.satcombo.domain.Qo100
import fr.f4ioz.satcombo.domain.SoleilQo100
import fr.f4ioz.satcombo.i18n.I18n
import fr.f4ioz.satcombo.station.PiloteQo100
import org.json.JSONArray
import org.json.JSONObject

/**
 * The station page's QO-100 tab, written once: SatMe-PC (its [PiloteQo100])
 * and the phone (its QO-100 screen's state) give where they listen, their
 * chain and their sky; the answers are the same, in the same words.
 */
object ApiQo100 {

    /** An RTL-SDR dongle's tuning range (R820T), Hz. */
    val GAMME_CLE = 24_000_000L..1_766_000_000L

    /** The dish's aim from here, and the Sun's dates that check it. */
    class Ciel(val p: Qo100.Pointage, val soleilAzimutMs: Long?, val transits: List<SoleilQo100.Transit>)

    /**
     * The tab's state. [cleRxHz] the dongle's frequency for the downlink;
     * [ciel] null while not computed, [observateurConnu] false when the
     * station does not know where it is; [plan] adds the band plan, the
     * transponders and the converter presets (asked once, when the tab opens).
     * [gammeCle]: what the receiver tunes to (an RTL-SDR dongle's by default).
     */
    fun etat(e: PiloteQo100.Etat, catConnecte: Boolean, cleRxHz: Long, ciel: Ciel?, observateurConnu: Boolean, plan: Boolean,
             gammeCle: LongRange = GAMME_CLE): JSONObject {
        val tr = I18n
        val seg = e.segment
        val c = e.chaineActive
        val o = JSONObject()
            .put("descente", e.descenteHz).put("montee", e.monteeHz).put("calage", e.calageHz)
            .put("transpondeur", e.transpondeur).put("sansBride", e.sansBride).put("surBalise", e.surBalise)
            .put("auPoste", e.auPoste).put("posteRx", e.posteRxHz).put("posteTx", e.posteTxHz).put("atteignable", e.posteAtteignable)
            .put("emission", e.emissionPermise).put("statut", e.statut).put("catConnecte", catConnecte)
            .put("aLaCle", e.aLaCle).put("cleRx", cleRxHz)
            .put("cleAtteignable", cleRxHz in gammeCle)
            .put("tp", JSONObject().put("bas", e.tp.descenteBasHz).put("haut", e.tp.descenteHautHz).put("centre", e.tp.centreDescenteHz))
            .put("segment", seg?.let { JSONObject().put("nom", tr.t("qo100_seg_" + it.cle)).put("largeur", it.largeurMaxHz)
                .put("avert", tr.t("qo100_warn_" + it.usage.cleAvertissement)).put("emission", it.emissionPermise) }
                ?: JSONObject().put("nom", "").put("largeur", 0).put("avert", tr.t("qo100_warn_hors")).put("emission", false))
            .put("memoires", JSONArray(MemoiresQo100.toutes(e.memoires).map { m ->
                JSONObject().put("hz", m.hz).put("nom", m.libelle { k -> tr.t(k) }).put("fixe", m.fixe) }))
            .put("chaines", JSONArray(e.chaines.map { it.nom })).put("chaine", c.nom)
            .put("olRx", c.descenteOlHz).put("olTx", c.monteeOlHz)
        if (ciel != null) {
            val k = ciel.p
            o.put("ciel", JSONObject().put("az", k.azDeg).put("el", k.elDeg).put("skew", k.skewDeg)
                .put("visible", k.visible).put("soleilAz", ciel.soleilAzimutMs ?: JSONObject.NULL)
                .put("ombre", SoleilQo100.azimutDeLOmbre(k.azDeg))
                .put("transits", JSONArray(ciel.transits.map { t -> JSONObject().put("t", t.instantMs).put("ecart", t.ecartDeg).put("duree", t.dureeS) })))
        } else o.put("ciel", if (!observateurConnu) JSONObject().put("inconnu", true) else JSONObject.NULL)
        if (plan) {
            o.put("segments", JSONArray(Qo100.SEGMENTS.map { g -> JSONObject().put("cle", g.cle).put("bas", g.basHz).put("haut", g.hautHz)
                .put("usage", g.usage.name).put("nom", tr.t("qo100_seg_" + g.cle)).put("repere", g.repereHz ?: JSONObject.NULL) }))
                .put("regletteBas", Qo100.REGLETTE_BAS_HZ).put("regletteHaut", Qo100.REGLETTE_HAUT_HZ)
                .put("transpondeurs", JSONArray(Qo100.TRANSPONDEURS.map { t -> JSONObject().put("cle", t.cle).put("nom", tr.t("qo100_tp_" + t.cle)) }))
                .put("presets", JSONArray(Convertisseur.PRESETS.map { p ->
                    JSONObject().put("cle", p.cle).put("nom", tr.t("preset_" + p.cle)).put("ol", p.olHz).put("descente", p.descente) }))
        }
        return o
    }

    /**
     * The waterfall's line: the dongle's spectrum on QO-100's nominal axis
     * ([axe]: centre, signed width), [n] columns, and the middle beacon as
     * measured there. [pan] null or empty: the dongle gives nothing yet.
     */
    fun panorama(sdr: Boolean, pan: FloatArray?, axe: Pair<Double, Double>?, n: Int, trames: Long, mesure: MesureBalise.Mesure?): JSONObject {
        val o = JSONObject().put("sdr", sdr)
        if (!sdr) return o
        if (pan != null && pan.isNotEmpty() && axe != null) {
            val cols = PanoramaQo100.colonnes(pan, axe.first, axe.second, n.coerceIn(64, 1024))
            o.put("trames", trames)
            o.put("colonnes", cols?.let { c -> JSONArray(c.map { v -> if (v == PanoramaQo100.HORS) JSONObject.NULL else Math.round(v) }) } ?: JSONObject.NULL)
        }
        mesure?.let { m ->
            o.put("balise", JSONObject().put("ecart", m.ecartArrondiHz).put("rapport", Math.round(m.rapportDb * 10) / 10.0)
                .put("ok", MesureBalise.calageSuffisant(m)))
        }
        return o
    }

    /** A frequency typed in MHz (comma or dot), in Hz; null when it is not one. */
    fun mhz(s: String?): Long? = s?.trim()?.replace(',', '.')?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.let { Math.round(it * 1e6) }

    /** The beacon heard there, if it is near 10 489.750 MHz; else null. */
    fun baliseEntendue(s: String?): Long? = mhz(s)?.takeIf { Math.abs(it - Qo100.BALISE_MEDIANE_HZ) < 2_000_000L }

    const val BALISE_LOIN = "Une fréquence près de 10 489,750 MHz, en MHz."
}
