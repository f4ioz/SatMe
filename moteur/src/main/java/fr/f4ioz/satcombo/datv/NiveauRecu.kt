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
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.i18n.tf
import org.json.JSONObject

/**
 * Our own reception set on the ground station's spectrum: where the
 * MiniTiouner listens, how wide, and — locked — how high its MER puts the
 * signal above the noise on [BandeLarge]'s scale.
 */
data class MonNiveau(
    /** Where it listens (sky, kHz): the frequency found once locked, else the one tuned. */
    val khz: Long,
    val srKs: Int,
    /** The width drawn (kHz): the rate with its roll-off. */
    val largeurKhz: Long,
    val actif: Boolean,
    val verrouille: Boolean,
    /** LongMynd's state (0 starting, 1 searching, 2 headers, 3 DVB-S, 4 DVB-S2). */
    val etat: Int,
    /** Locked: the MER (dB), its height above the noise ((C+N)/N, dB), the spectrum's units there. */
    val merDb: Double?,
    val hauteurDb: Double?,
    val niveau: Int?,
    /** What the MODCOD needs (dB), when known. */
    val seuilDb: Double?,
    val modcod: String,
    /** The ground station's signal there and its height above its noise (dB); null when none (or no spectrum). */
    val station: SignalLarge?,
    val stationDb: Double?,
    /** Whether the ground station's spectrum was fresh. */
    val spectre: Boolean,
)

object NiveauRecu {
    /** The roll-off drawn around the rate (as the page's receiver channel). */
    const val ROLLOFF = 1.35

    /** A MER (≈ C/N, dB) as the height a spectrum shows above its noise: (C+N)/N. */
    fun hauteurDb(merDb: Double): Double = 10 * Math.log10(1 + Math.pow(10.0, merDb / 10))

    /** A height above the noise (dB) in the ground station's units. */
    fun unites(hauteurDb: Double): Int = Math.round(BandeLarge.BRUIT + hauteurDb.coerceAtLeast(0.0) * BandeLarge.UNITES_PAR_DB).toInt()

    /** A level in the ground station's units as its height above the noise (dB). */
    fun hauteurStationDb(niveau: Int): Double = (niveau - BandeLarge.BRUIT) / BandeLarge.UNITES_PAR_DB

    /** The ground station's signal at [khz] (the nearest within its width or its rate's tolerance), or null. */
    fun signalIci(khz: Long, srKs: Int, signaux: List<SignalLarge>): SignalLarge? =
        signaux.filter { Math.abs(it.centreKhz - khz) <= maxOf(NomsSignaux.tolerance(minOf(it.srKs, srKs)), it.largeurKhz / 2) }
            .minByOrNull { Math.abs(it.centreKhz - khz) }

    /**
     * Our level from the receiver's status [e] and settings [r]; [signaux]
     * the ground station's signals, null without a fresh spectrum. Null when
     * the receiver listens outside the wide band.
     */
    fun calcule(e: EtatDatv, r: ReglageDatv, signaux: List<SignalLarge>?): MonNiveau? {
        val trouve = e.actif && e.verrouille && e.fiTrouveeKhz > 0
        val khz = if (trouve) e.fiTrouveeKhz + r.olKhz else r.cielKhz
        if (khz !in BandeLarge.DEBUT_KHZ..BandeLarge.DEBUT_KHZ + BandeLarge.LARGEUR_KHZ) return null
        val sr = if (trouve && e.srTrouveKs > 0) e.srTrouveKs else r.srKs
        val verrouille = e.actif && e.verrouille
        val mer = e.merDb?.takeIf { verrouille }
        val h = mer?.let { hauteurDb(it) }
        val sg = signaux?.let { signalIci(khz, sr, it) }
        return MonNiveau(khz, sr, Math.round(sr * ROLLOFF), e.actif, verrouille, e.etat, mer, h, h?.let { unites(it) },
            QualiteDatv.seuilDb(e.modcod, e.etat, e.trames)?.takeIf { verrouille }, if (verrouille) e.modcod else "",
            sg, sg?.let { Math.round(hauteurStationDb(it.niveau) * 10) / 10.0 }, signaux != null)
    }

    private fun db(x: Double, signe: Boolean = false): String =
        String.format(I18n.locale(), if (signe) "%+.1f" else "%.1f", x).replace('-', '−')

    /**
     * One line comparing both: « Balise : +8,2 dB à la station sol · chez
     * moi : MER 5,9 dB (seuil QPSK 4/5 4,7 dB) », or nothing received and
     * what to check.
     */
    fun ligne(m: MonNiveau): String {
        val sg = m.station
        val nom = if (sg?.balise == true) t("datv_balise") else t("datv_niveau_signal")
        val sol = when {
            !m.spectre -> null
            sg != null && m.stationDb != null -> tf("datv_niveau_station", nom, db(m.stationDb, true))
            else -> t("datv_niveau_station_rien")
        }
        val moi = when {
            !m.actif -> t("datv_niveau_moi_arrete")
            m.verrouille && m.merDb != null -> tf("datv_niveau_moi_mer", db(m.merDb)) +
                (m.seuilDb?.let { " " + tf("datv_niveau_seuil", m.modcod, db(it)) } ?: "")
            m.verrouille -> t("datv_niveau_moi_accroche")
            else -> tf("datv_niveau_moi_rien", t("datv_niveau_etat_" + m.etat.coerceIn(0, 2))) + when {
                !m.spectre -> ""
                sg != null -> " — " + t(if (sg.balise) "datv_niveau_conseil_balise" else "datv_niveau_conseil_signal")
                else -> " — " + t("datv_niveau_conseil_vide")
            }
        }
        return listOfNotNull(sol, moi).joinToString(" · ")
    }

    /** For the page. */
    fun json(m: MonNiveau): JSONObject {
        fun nul(x: Any?) = x ?: JSONObject.NULL
        return JSONObject().put("khz", m.khz).put("sr", m.srKs).put("largeur", m.largeurKhz).put("actif", m.actif)
            .put("verrouille", m.verrouille).put("etat", m.etat).put("mer", nul(m.merDb)).put("hauteur", nul(m.hauteurDb?.let { Math.round(it * 10) / 10.0 }))
            .put("niveau", nul(m.niveau)).put("seuil", nul(m.seuilDb)).put("modcod", m.modcod)
            .put("stationCentre", nul(m.station?.centreKhz)).put("stationDb", nul(m.stationDb)).put("stationBalise", m.station?.balise == true)
            .put("ligne", ligne(m))
    }
}
