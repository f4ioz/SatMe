/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.data

/** A parsed two-line element set plus optional amateur-radio frequency metadata. */
data class TleEntry(
    val name: String,
    val line1: String,
    val line2: String,
    val catalogNumber: Int = line1.drop(2).take(5).trim().toIntOrNull() ?: 0,
    val uplinkHz: Long? = null,
    val downlinkHz: Long? = null,
    val mode: String? = null
) {
    /**
     * Le nombre de tours par jour, colonnes 53 à 63 de la ligne 2.
     *
     * C'est ce qui distingue un satellite qui passe d'un satellite qui ne
     * passe pas, sans avoir à connaître son numéro : la donnée est dans le
     * TLE, et elle vaut pour tous les géostationnaires, pas seulement pour
     * celui qu'on a pensé à inscrire dans une liste.
     */
    val toursParJour: Double?
        get() = runCatching {
            line2.substring(52, 63).trim().toDouble()
        }.getOrNull()

    /**
     * Ce satellite reste-t-il immobile dans le ciel ?
     *
     * Un géostationnaire fait un tour par jour sidéral, soit 1,0027 tour.
     * La fourchette est large — de 0,9 à 1,1 — pour englober les
     * géosynchrones un peu inclinés, qui décrivent un huit mais restent
     * dans la même région du ciel : pour l'opérateur, la conséquence est la
     * même, la parabole ne bouge pas.
     *
     * Sans ligne 2 lisible on répond « non ». Mieux vaut afficher une
     * boussole inutile que la cacher sur un satellite qui passe.
     */
    val estImmobile: Boolean
        get() = toursParJour?.let { it in 0.9..1.1 } ?: false

    /** Epoch of the TLE (when the elements were measured), in epoch millis. */
    val epochMs: Long?
        get() = runCatching {
            // Columns 19-32 of line 1: 2-digit year + fractional day-of-year.
            val raw = line1.substring(18, 32).trim()
            val yy = raw.substring(0, 2).toInt()
            val year = if (yy < 57) 2000 + yy else 1900 + yy
            val dayOfYear = raw.substring(2).toDouble()
            val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            cal.clear(); cal.set(java.util.Calendar.YEAR, year)
            val millis = ((dayOfYear - 1.0) * 86_400_000.0).toLong()
            cal.timeInMillis + millis
        }.getOrNull()
}


/** Observer ground station. */
data class Observer(
    val latDeg: Double,
    val lonDeg: Double,
    val altMeters: Double = 0.0,
    val name: String = "QTH"
)

/** One predicted pass over the observer. */
data class SatPass(
    val satName: String,
    val catalogNumber: Int,
    val aosEpochMs: Long,        // acquisition of signal
    val losEpochMs: Long,        // loss of signal
    val maxElevationDeg: Double,
    val aosAzimuthDeg: Double,
    val losAzimuthDeg: Double,
    val sunlit: Boolean,         // satellite illuminated -> visible to eye
    val nightAtObserver: Boolean, // dark sky at QTH
    val track: List<Pair<Double, Double>> = emptyList() // (az, el) samples AOS->LOS
) {
    /** ISS-Detector-style: a pass you can actually see with the naked eye. */
    val visualPass: Boolean get() = sunlit && nightAtObserver && maxElevationDeg >= 10.0
    val durationSec: Long get() = (losEpochMs - aosEpochMs) / 1000
}

/** Instantaneous look-angles used for the live polar plot / compass. */
data class SatPosition(
    val azimuthDeg: Double,
    val elevationDeg: Double,
    val rangeKm: Double,
    val rangeRateKmS: Double,    // + = moving away
    val altKm: Double,
    val latDeg: Double,
    val lonDeg: Double,
    val sunlit: Boolean
)
