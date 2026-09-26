/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.data

/**
 * Le système d'unités dans lequel l'application écrit les distances, les
 * altitudes et les vitesses.
 *
 * Un radioamateur français annonce « 412 km » et « 180 m d'altitude », un
 * américain « 256 miles » et « 590 feet », et celui qui suit un ballon ou un
 * bateau raisonne en milles nautiques et en nœuds. Ce sont les mêmes chiffres,
 * mais personne ne fait la conversion de tête pendant un passage de trois
 * minutes.
 *
 * Tout est ici, en Kotlin pur, sans le moindre import Android : les facteurs de
 * conversion et les seuils de bascule (au-dessous d'un kilomètre on écrit des
 * mètres, au-dessous d'un mille des pieds) sont exactement le genre de détail
 * qui se retourne silencieusement, donc ils se testent sur la JVM.
 */
object Units {

    /** Mètres, kilomètres, km/h — le système du reste du monde. */
    const val METRIC = "metric"

    /** Pieds, miles, mph — le système anglo-saxon. */
    const val IMPERIAL = "imperial"

    /** Milles nautiques, nœuds, pieds — la marine et l'aéronautique. */
    const val NAUTICAL = "nautical"

    /** Les trois systèmes, dans l'ordre du sélecteur. */
    val ALL: List<String> = listOf(METRIC, IMPERIAL, NAUTICAL)

    // ---- facteurs exacts ---------------------------------------------------
    /** Un mètre en pieds internationaux (exactement 1 / 0,3048). */
    const val FEET_PER_METER = 3.2808398950131235

    /** Un kilomètre en miles terrestres. */
    const val MILES_PER_KM = 0.621371192237334

    /** Un kilomètre en milles nautiques (le mille vaut 1852 m, exactement). */
    const val NM_PER_KM = 1000.0 / 1852.0

    /** Un mètre par seconde en nœuds. */
    const val KNOTS_PER_MPS = 3600.0 / 1852.0

    /**
     * Ramène ce qui est enregistré dans les réglages à un système connu.
     * Une valeur absente, vide ou devenue inconnue retombe sur le métrique
     * plutôt que de faire disparaître les distances de l'écran.
     */
    fun normalize(v: String?): String {
        val k = v?.trim()?.lowercase().orEmpty()
        return if (k in ALL) k else METRIC
    }

    /** Le nom court de l'unité de distance, pour un en-tête de colonne. */
    fun distanceUnit(sys: String): String = when (normalize(sys)) {
        IMPERIAL -> "mi"
        NAUTICAL -> "NM"
        else -> "km"
    }

    /**
     * Une distance donnée en kilomètres, écrite au dixième près, avec bascule
     * vers l'unité courte quand elle devient plus lisible : 900 mètres se lit
     * mieux que 0,9 km, et 1200 pieds mieux que 0,23 mile.
     */
    fun distance(km: Double, sys: String): String = when (normalize(sys)) {
        IMPERIAL -> {
            val mi = km * MILES_PER_KM
            if (mi < 1.0) "%.0f ft".format(km * 1000.0 * FEET_PER_METER)
            else "%.1f mi".format(mi)
        }
        NAUTICAL -> {
            val nm = km * NM_PER_KM
            // En mer on ne descend pas sous le mille en fractions : sous un
            // demi-mille on annonce des mètres, comme sur une passerelle.
            if (nm < 0.5) "%.0f m".format(km * 1000.0) else "%.1f NM".format(nm)
        }
        else -> if (km < 1.0) "%.0f m".format(km * 1000.0) else "%.1f km".format(km)
    }

    /**
     * La même distance sans décimale : pour les grands nombres d'un tableau de
     * passages, où le dixième de kilomètre n'apprend rien.
     */
    fun distanceRound(km: Double, sys: String): String = when (normalize(sys)) {
        IMPERIAL -> "%.0f mi".format(km * MILES_PER_KM)
        NAUTICAL -> "%.0f NM".format(km * NM_PER_KM)
        else -> "%.0f km".format(km)
    }

    /** Le nombre seul, sans unité, pour qui écrit son unité lui-même. */
    fun distanceValue(km: Double, sys: String): Double = when (normalize(sys)) {
        IMPERIAL -> km * MILES_PER_KM
        NAUTICAL -> km * NM_PER_KM
        else -> km
    }

    /**
     * Une hauteur en mètres. Le nautique se lit en pieds comme l'aéronautique :
     * c'est ce qui figure sur les cartes et dans les bulletins.
     */
    fun altitude(m: Double, sys: String): String = when (normalize(sys)) {
        METRIC -> "%.0f m".format(m)
        else -> "%.0f ft".format(m * FEET_PER_METER)
    }

    /** Une petite longueur, donnée en mètres (rayon, marge, précision GPS). */
    fun shortDistance(m: Double, sys: String): String = when (normalize(sys)) {
        METRIC -> "%.0f m".format(m)
        else -> "%.0f ft".format(m * FEET_PER_METER)
    }

    /**
     * Une vitesse au sol, donnée en mètres par seconde : kilomètres-heure,
     * miles-heure ou nœuds selon le système.
     */
    fun speed(mps: Double, sys: String): String = when (normalize(sys)) {
        IMPERIAL -> "%.0f mph".format(mps * 3.6 * MILES_PER_KM)
        NAUTICAL -> "%.0f kt".format(mps * KNOTS_PER_MPS)
        else -> "%.0f km/h".format(mps * 3.6)
    }

    /**
     * Une vitesse verticale, donnée en mètres par seconde. Elle garde son signe
     * : c'est lui qui dit qu'un ballon vient d'éclater.
     */
    fun vertical(mps: Double, sys: String): String = when (normalize(sys)) {
        METRIC -> "%+.1f m/s".format(mps)
        else -> "%+.1f ft/s".format(mps * FEET_PER_METER)
    }
}
