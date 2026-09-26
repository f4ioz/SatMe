/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.sonde

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * La géométrie dont une chasse à la radiosonde a besoin.
 *
 * La RS41 ne transmet pas sa latitude : elle transmet sa position cartésienne
 * géocentrée (ECEF, en centimètres) et sa vitesse dans le même repère. Tout le
 * reste — latitude, longitude, altitude, vitesse au sol, cap, vitesse
 * verticale — se calcule ici. C'est du calcul pur, sans Android : c'est donc la
 * partie du décodage qui se vérifie entièrement en test unitaire, et c'est
 * voulu. Une erreur de signe sur un axe enverrait le chasseur dans le mauvais
 * département.
 */
object Geo {

    /** Demi-grand axe de l'ellipsoïde WGS84, en mètres. */
    const val A = 6_378_137.0

    /** Aplatissement de l'ellipsoïde WGS84. */
    const val F = 1.0 / 298.257223563

    /** Demi-petit axe, en mètres. */
    const val B = A * (1.0 - F)

    /** Première excentricité au carré. */
    const val E2 = F * (2.0 - F)

    /** Rayon terrestre moyen utilisé pour les distances au sol, en kilomètres. */
    const val EARTH_KM = 6371.0088

    /** Position géodésique : latitude et longitude en degrés, altitude en mètres. */
    data class Fix(val lat: Double, val lon: Double, val altM: Double)

    /** Vitesse locale : est, nord et vertical, en mètres par seconde. */
    data class Enu(val east: Double, val north: Double, val up: Double) {
        /** Vitesse au sol, en mètres par seconde. */
        val groundMps: Double get() = hypot(east, north)

        /** Cap suivi, en degrés depuis le nord, ramené dans 0..360. */
        val headingDeg: Double
            get() {
                if (groundMps < 1e-6) return 0.0
                val d = Math.toDegrees(atan2(east, north))
                return if (d < 0) d + 360.0 else d
            }
    }

    /**
     * ECEF vers géodésique, par la méthode de Bowring.
     *
     * Bowring converge en un seul passage à mieux que le millimètre pour toutes
     * les altitudes qui nous concernent (du niveau de la mer à quarante
     * kilomètres) : pas de boucle, pas de critère d'arrêt, donc pas de cas où
     * le décodage prendrait soudain plus de temps qu'un intervalle de trame.
     */
    fun ecefToGeodetic(x: Double, y: Double, z: Double): Fix {
        val p = hypot(x, y)
        if (p < 1e-9) {
            // Sur l'axe des pôles : la formule générale divise par zéro, et de
            // toute façon aucune radiosonde française ne passera par là.
            val lat = if (z >= 0) 90.0 else -90.0
            return Fix(lat, 0.0, abs(z) - B)
        }
        val ep2 = (A * A - B * B) / (B * B)
        val th = atan2(A * z, B * p)
        val st = sin(th)
        val ct = cos(th)
        val lat = atan2(z + ep2 * B * st * st * st, p - E2 * A * ct * ct * ct)
        val lon = atan2(y, x)
        val sl = sin(lat)
        val n = A / sqrt(1.0 - E2 * sl * sl)
        val alt = p / cos(lat) - n
        return Fix(Math.toDegrees(lat), Math.toDegrees(lon), alt)
    }

    /**
     * Vitesse ECEF vers vitesse locale est/nord/haut, à la latitude et à la
     * longitude données (en degrés). C'est la rotation classique du repère
     * géocentré vers le repère du lieu.
     */
    fun ecefVelToEnu(latDeg: Double, lonDeg: Double,
                     vx: Double, vy: Double, vz: Double): Enu {
        val la = Math.toRadians(latDeg)
        val lo = Math.toRadians(lonDeg)
        val sla = sin(la); val cla = cos(la)
        val slo = sin(lo); val clo = cos(lo)
        val e = -slo * vx + clo * vy
        val n = -sla * clo * vx - sla * slo * vy + cla * vz
        val u = cla * clo * vx + cla * slo * vy + sla * vz
        return Enu(e, n, u)
    }

    /** Distance au sol entre deux points, en kilomètres (formule du haversine). */
    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) +
            cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2.0 * EARTH_KM * atan2(sqrt(a), sqrt(1.0 - a))
    }

    /**
     * Azimut initial du premier point vers le second, en degrés depuis le nord.
     * C'est le chiffre à mettre sous les yeux du chasseur : la sonde est
     * par là.
     */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        val d = Math.toDegrees(atan2(y, x))
        return (d + 360.0) % 360.0
    }

    private val ROSE = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
        "S", "SSO", "SO", "OSO", "O", "ONO", "NO", "NNO")

    /** Le même azimut, en rose des vents à seize branches. */
    fun compass(deg: Double): String {
        val d = ((deg % 360.0) + 360.0) % 360.0
        return ROSE[(((d + 11.25) / 22.5).toInt()) % 16]
    }

    /** Origine du temps GPS (6 janvier 1980) en millisecondes Unix. */
    const val GPS_EPOCH_MS = 315_964_800_000L

    /**
     * Semaine GPS et temps dans la semaine vers l'horloge Unix.
     *
     * Le décalage de secondes intercalaires est un paramètre parce qu'il change
     * : dix-huit secondes depuis 2017, mais la sonde qu'on écoutera dans dix ans
     * n'en saura rien. Les firmwares récents transmettent le numéro de semaine
     * complet ; les anciens le donnent modulo 1024, d'où le rattrapage.
     */
    fun gpsToUnixMs(week: Int, itowMs: Long, leapSeconds: Int = 18): Long {
        val w = if (week in 1..1023) week + 2048 else week
        return GPS_EPOCH_MS + w * 604_800_000L + itowMs - leapSeconds * 1000L
    }
}
