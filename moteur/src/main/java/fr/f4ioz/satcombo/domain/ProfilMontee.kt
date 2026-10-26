/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.pow

/**
 * An uplink profile for QO-100: which chain, which antenna, which amplifier
 * and how much cable — the home station and the portable one differ in all
 * four. From it, an **estimated EIRP** and a **suggested power** to stay
 * under the beacon's level.
 *
 * Orders of magnitude only, and said so: the dish's efficiency, the feed,
 * the pointing, the amplifier's real output and the transponder's load all
 * move the result by several dB. The real check is one's own signal against
 * the beacon on the downlink.
 */
data class ProfilMontee(
    val nom: String,
    val chaine: MonteeQo100.Chaine = MonteeQo100.Chaine.PLUTO,
    val antenne: Antenne = Antenne.HELICE_20,
    /** The antenna's gain when [antenne] is [Antenne.AUTRE] (dBi). */
    val gainAutreDbi: Double = 12.0,
    /** The amplifier's rated output (W); 0: none. */
    val ampliW: Double = 10.0,
    /** The amplifier's gain (dB) when known; 0: unknown. */
    val ampliGainDb: Double = 0.0,
    /** Cable and connector losses between the amplifier and the antenna (dB). */
    val pertesDb: Double = 1.0,
) {
    /** The antennas a QO-100 uplink commonly uses. */
    enum class Antenne(val cle: String, val libelle: String, val montage: String) {
        PARABOLE_80("parabole80", "Parabole 80 cm + source POTY", "parabole-qo100"),
        PARABOLE_35("parabole35", "Parabole 35 cm + hélice (portable)", "parabole-35"),
        HELICE_20("helice20", "Hélice 20 tours, circulaire droite (sans parabole)", "helice-2400"),
        AUTRE("autre", "Autre (gain saisi)", "");

        companion object {
            fun de(cle: String?): Antenne? = entries.firstOrNull { it.cle == cle?.trim()?.lowercase() }
        }
    }

    /** The antenna's gain (dBi) at 2 400 MHz. */
    val gainDbi: Double get() = when (antenne) {
        Antenne.PARABOLE_80 -> gainParabole(0.80)
        Antenne.PARABOLE_35 -> gainParabole(0.35)
        Antenne.HELICE_20 -> GAIN_HELICE_20_DBI
        Antenne.AUTRE -> gainAutreDbi
    }

    /** The EIRP (dBW) for [watts] at the amplifier's output. */
    fun pireDbw(watts: Double): Double = 10 * log10(watts) - pertesDb + gainDbi

    /** The EIRP at the amplifier's rated output (dBW), or null without one. */
    val pireMaxDbw: Double? get() = ampliW.takeIf { it > 0 }?.let { pireDbw(it) }

    /** The power at the amplifier's output (W) for an EIRP of [dbw]. */
    fun wattsPour(dbw: Double): Double = 10.0.pow((dbw - gainDbi + pertesDb) / 10)

    /** The suggested power at the amplifier's output (W) for [mode]: the beacon's level less its margin, never above the rating. */
    fun conseilleW(mode: MonteeQo100.Mode): Double {
        val w = wattsPour(PIRE_BALISE_DBW - marge(mode))
        return if (ampliW > 0) minOf(w, ampliW) else w
    }

    /**
     * The Pluto's attenuation for that power (dB, ≤ 0) when the amplifier's
     * gain is known, the Pluto giving about [PLUTO_MAX_DBM] at 0 dB.
     */
    fun gainPlutoConseille(mode: MonteeQo100.Mode): Double? {
        if (chaine != MonteeQo100.Chaine.PLUTO || ampliGainDb <= 0) return null
        val dbm = 10 * log10(conseilleW(mode) * 1000)
        return (dbm - ampliGainDb - PLUTO_MAX_DBM).coerceIn(-89.75, 0.0)
    }

    /** The Montage's ready-made chain that draws this profile. */
    val prereglage: String get() = when {
        chaine == MonteeQo100.Chaine.POSTE -> "qo100-station"
        antenne == Antenne.PARABOLE_35 -> "qo100-portable-35"
        antenne == Antenne.HELICE_20 -> "qo100-pluto-helice"
        else -> "qo100-pluto"
    }

    /** The profile in a few words, with its estimate (said as one). */
    fun resume(mode: MonteeQo100.Mode = MonteeQo100.Mode.BLU): String {
        val p = pireMaxDbw
        return String.format(Locale.FRANCE, "%s · %s (%.1f dBi) · %s · pertes %.1f dB · PIRE max ≈ %s · conseillé ≈ %s (ordre de grandeur)",
            chaine.libelle, antenne.libelle, gainDbi, if (ampliW > 0) fmtW(ampliW) else "sans ampli", pertesDb,
            p?.let { fmtPire(it) } ?: "—", fmtW(conseilleW(mode)))
    }

    fun json(): JSONObject = JSONObject().put("nom", nom).put("chaine", chaine.cle).put("antenne", antenne.cle).put("gainAutre", gainAutreDbi)
        .put("ampliW", ampliW).put("ampliGain", ampliGainDb).put("pertes", pertesDb)

    /** Within what a station can be (NaN and the absurd clamped). */
    fun borne(): ProfilMontee = copy(nom = nom.trim().take(40).ifBlank { "Profil" },
        gainAutreDbi = b(gainAutreDbi, -10.0, 45.0), ampliW = b(ampliW, 0.0, 2_000.0), ampliGainDb = b(ampliGainDb, 0.0, 80.0), pertesDb = b(pertesDb, 0.0, 20.0))

    companion object {
        const val FREQUENCE_HZ = 2_400_250_000L
        /** Aperture efficiency of an amateur offset dish with its feed. */
        const val RENDEMENT_PARABOLE = 0.55
        /** A 20-turn axial-mode helix at 2.4 GHz (≈ 2.5 m long): the published measurements sit near 15 dBic. */
        const val GAIN_HELICE_20_DBI = 15.0
        /**
         * The EIRP that puts an SSB voice about level with the middle beacon
         * on the downlink (dBW, ≈ 500 W): the order of magnitude reported by
         * stations and AMSAT-DL's guidance, within ±6 dB.
         */
        const val PIRE_BALISE_DBW = 27.0
        /** What the Pluto gives at 0 dB attenuation on 2.4 GHz (dBm, about). */
        const val PLUTO_MAX_DBM = 5.0

        /** Below the beacon by this much (dB): voice a little under it, CW more, digital modes well under. */
        fun marge(mode: MonteeQo100.Mode): Double = when (mode) {
            MonteeQo100.Mode.BLU -> 3.0
            MonteeQo100.Mode.CW -> 6.0
            MonteeQo100.Mode.NUM -> 10.0
        }

        /** A dish's gain (dBi) at 2.4 GHz for its diameter (m). */
        fun gainParabole(diametreM: Double): Double {
            val lambda = 299_792_458.0 / FREQUENCE_HZ
            return 10 * log10(RENDEMENT_PARABOLE * (PI * diametreM / lambda).pow(2))
        }

        /** The home station and the portable one, to be adjusted (amplifier, cable). */
        val PAR_DEFAUT = listOf(
            ProfilMontee("Fixe", MonteeQo100.Chaine.PLUTO, Antenne.HELICE_20, ampliW = 10.0, pertesDb = 1.5),
            ProfilMontee("Portable", MonteeQo100.Chaine.PLUTO, Antenne.PARABOLE_35, ampliW = 10.0, pertesDb = 0.5),
            ProfilMontee("IC-9700", MonteeQo100.Chaine.POSTE, Antenne.PARABOLE_80, ampliW = 20.0, pertesDb = 1.5),
        )

        fun lis(o: JSONObject): ProfilMontee? {
            val nom = o.optString("nom").trim().ifBlank { return null }
            return ProfilMontee(nom, MonteeQo100.Chaine.de(o.optString("chaine")) ?: MonteeQo100.Chaine.PLUTO,
                Antenne.de(o.optString("antenne")) ?: Antenne.AUTRE, o.optDouble("gainAutre", 12.0), o.optDouble("ampliW", 0.0),
                o.optDouble("ampliGain", 0.0), o.optDouble("pertes", 1.0)).borne()
        }

        fun lisListe(texte: String?): List<ProfilMontee> = runCatching {
            val a = JSONArray(texte ?: return PAR_DEFAUT)
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::lis) }.distinctBy { it.nom.lowercase() }.take(12)
        }.getOrNull()?.ifEmpty { null } ?: PAR_DEFAUT

        fun ecrisListe(l: List<ProfilMontee>): String = JSONArray(l.map { it.json() }).toString()

        /** Stores [p] by name (case ignored), appended if new. */
        fun range(l: List<ProfilMontee>, p: ProfilMontee): List<ProfilMontee> {
            val i = l.indexOfFirst { it.nom.equals(p.nom, ignoreCase = true) }
            return if (i < 0) (l + p).take(12) else l.toMutableList().also { it[i] = p }
        }

        fun fmtW(w: Double): String = when {
            w >= 10 -> String.format(Locale.FRANCE, "%.0f W", w)
            w >= 1 -> String.format(Locale.FRANCE, "%.1f W", w)
            else -> String.format(Locale.FRANCE, "%.0f mW", w * 1000)
        }

        fun fmtPire(dbw: Double): String = String.format(Locale.FRANCE, "%.1f dBW (%s)", dbw, fmtW(10.0.pow(dbw / 10)))

        private fun b(v: Double, min: Double, max: Double) = if (v.isNaN()) min else v.coerceIn(min, max)
    }
}
