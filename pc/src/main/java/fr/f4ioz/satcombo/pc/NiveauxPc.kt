/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.demo.ReponseWeb
import fr.f4ioz.satcombo.domain.HistoriqueNiveau
import fr.f4ioz.satcombo.plateforme.Stockage
import fr.f4ioz.satcombo.sdr.Dsp
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.log10
import kotlin.math.pow

/**
 * « Niveau dans le temps »: what the receiver hears, followed second by
 * second while it runs — the listened channel's level, the power around a
 * marker, the noise floor, QO-100's beacon above its noise — kept for a day
 * in bounded rings (10 min at 1 s, 1 h at 10 s, 24 h at 1 min). Receive
 * only: read from the spectra already computed, nothing asked of the hardware.
 */
class NiveauxPc(private val sdr: SdrPc, private val qo100: Qo100Pc?, private val stockage: Stockage,
                private val horloge: () -> Long = System::currentTimeMillis) {

    val series: Map<String, HistoriqueNiveau> = linkedMapOf(
        CANAL to HistoriqueNiveau(), REPERE to HistoriqueNiveau(), PLANCHER to HistoriqueNiveau(), BALISE to HistoriqueNiveau())

    /** The marker: Hz from the listened frequency (it follows the Doppler with it), and the width summed around it. */
    var repereHz: Int
        get() = stockage.getInt(CLE_REPERE, 10_000)
        set(v) { stockage.edit().putInt(CLE_REPERE, v.coerceIn(-88_000, 88_000)).apply() }
    var repereLargeurHz: Int
        get() = stockage.getInt(CLE_LARGEUR, 2_500)
        set(v) { stockage.edit().putInt(CLE_LARGEUR, v.coerceIn(100, 50_000)).apply() }

    private var trameVue = -1L
    private var baliseVue = -1L

    /** Every second: one measure of each, those the receiver gives now. */
    fun tic() {
        val t = horloge()
        val e = sdr.etat.value
        if (e.actif) {
            series.getValue(CANAL).ajoute(t, e.niveauDb.toDouble())
            val sp = sdr.spectre
            if (sp != null && sdr.trames != trameVue) {
                trameVue = sdr.trames
                plancher(sp)?.let { series.getValue(PLANCHER).ajoute(t, it) }
                puissance(sp, (e.fineHz + e.decalageHz + repereHz).toDouble(), repereLargeurHz.toDouble())?.let { series.getValue(REPERE).ajoute(t, it) }
            }
        }
        // The beacon: each new measure of the tracker (every 5 s while locked).
        qo100?.suivi?.dernier?.takeIf { it.t != baliseVue && qo100.suivi.verrouille(t) }?.let { p ->
            baliseVue = p.t; series.getValue(BALISE).ajoute(p.t, p.rapportDb.toDouble())
        }
    }

    fun sert(chemin: String, methode: String, q: (String) -> String): ReponseWeb {
        val fen = FENETRES[q("fenetre")] ?: FENETRES.getValue("10m")
        return when {
            methode == "POST" && chemin == "/api/niveaux/repere" -> {
                q("hz").toIntOrNull()?.let { repereHz = it }
                q("largeur").toIntOrNull()?.let { repereLargeurHz = it }
                json(JSONObject().put("ok", true))
            }
            methode == "POST" && chemin == "/api/niveaux/vider" -> { series.values.forEach { it.vide() }; json(JSONObject().put("ok", true)) }
            methode == "GET" && chemin == "/api/niveaux.csv" -> {
                val t = horloge()
                val nom = "satme-niveaux-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm").format(java.util.Date(t)) + ".csv"
                ReponseWeb("200 OK", "text/csv; charset=utf-8",
                    HistoriqueNiveau.csv(series.map { (k, h) -> k to h.points(fen, t) }).toByteArray(Charsets.UTF_8),
                    "Content-Disposition: attachment; filename=\"$nom\"\r\n")
            }
            methode == "GET" && chemin == "/api/niveaux" -> json(etat(fen, q("serie")))
            else -> json(JSONObject().put("ok", false).put("erreur", "inconnu"))
        }
    }

    /** The series [serie] (all of them, their statistics only, when empty) over [fenetreMs]. */
    fun etat(fenetreMs: Long, serie: String): JSONObject {
        val t = horloge()
        val o = JSONObject().put("ok", true).put("actif", sdr.etat.value.actif).put("maintenant", t)
            .put("repere", repereHz).put("largeur", repereLargeurHz).put("fenetre", fenetreMs)
            .put("pas", series.values.first().palierPour(fenetreMs).pasMs)
            .put("qo100", qo100 != null && sdr.station.qo100.etat.value.aLaCle)
        val stats = JSONObject()
        for ((k, h) in series) {
            val pts = h.points(fenetreMs, t)
            HistoriqueNiveau.stats(pts)?.let { s -> stats.put(k, JSONObject().put("min", r1(s.min)).put("max", r1(s.max)).put("moy", r1(s.moy))
                .put("dernier", r1(pts.last().moy))) }
            if (k == serie) o.put("points", JSONArray(pts.map { p -> JSONArray().put(p.t).put(r1(p.moy)).put(r1(p.min)).put(r1(p.max)) }))
        }
        return o.put("stats", stats)
    }

    private fun json(o: JSONObject) = ReponseWeb("200 OK", "application/json; charset=utf-8", o.toString().toByteArray(Charsets.UTF_8))

    companion object {
        const val CANAL = "canal"
        const val REPERE = "repere"
        const val PLANCHER = "plancher"
        const val BALISE = "balise"
        const val CLE_REPERE = "pc_niveaux_repere"
        const val CLE_LARGEUR = "pc_niveaux_largeur"
        val FENETRES = mapOf("10m" to 600_000L, "1h" to 3_600_000L, "24h" to 86_400_000L)

        private fun r1(v: Double) = Math.round(v * 10) / 10.0

        /** The spectrum's noise floor: the median bin of its middle 80 % (the filter's edges left out). */
        fun plancher(sp: FloatArray): Double? {
            val n = sp.size; if (n < 16) return null
            val l = sp.copyOfRange(n / 10, n - n / 10).filter { it.isFinite() }.sorted()
            return l.getOrNull(l.size / 2)?.toDouble()
        }

        /**
         * The power within [largeurHz] around [centreHz] (Hz from the PLL) in
         * a spectrum covering the dongle's rate after the first decimation:
         * the bins' powers summed, dB. Null when out of view.
         */
        fun puissance(sp: FloatArray, centreHz: Double, largeurHz: Double, parBinHz: Double = Dsp.RTL_RATE.toDouble() / Dsp.DECIM_1 / sp.size): Double? {
            val nb = sp.size
            val j0 = Math.floor((centreHz - largeurHz / 2) / parBinHz).toInt() + nb / 2
            val j1 = Math.max(j0 + 1, Math.ceil((centreHz + largeurHz / 2) / parBinHz).toInt() + nb / 2)
            if (j0 < 0 || j1 > nb) return null
            var s = 0.0
            for (j in j0 until j1) s += 10.0.pow(sp[j] / 10.0)
            return 10 * log10(s)
        }
    }
}
