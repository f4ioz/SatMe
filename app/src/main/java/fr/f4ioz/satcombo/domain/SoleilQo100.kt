/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.domain

import java.util.Date
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/**
 * Le Soleil comme mire de pointage pour la parabole QO-100.
 *
 * ### Pourquoi le Soleil
 *
 * Pointer une parabole sur un géostationnaire est un problème singulier : la
 * cible est invisible, immobile, et le seul signal qui dirait qu'on l'a
 * trouvée n'arrive qu'une fois qu'on l'a trouvée. On tourne donc à l'aveugle
 * avec une boussole de téléphone — qui se trompe de plusieurs degrés dès qu'il
 * y a du fer dans le mur — et un niveau à bulle.
 *
 * Le Soleil résout ça, et il le résout de deux façons différentes qu'il faut
 * bien distinguer, parce que l'une sert tous les jours et l'autre deux fois
 * l'an.
 *
 * **Le passage en azimut**, [prochainPassageEnAzimut]. Chaque jour, il y a un
 * instant où le Soleil est exactement dans l'azimut du satellite. À cette
 * minute-là, l'ombre d'un piquet vertical — ou du bord de la parabole, ou d'un
 * fil à plomb — est exactement dans l'axe, à 180° près. On aligne l'azimut de
 * la parabole sur cette ombre et on n'a plus besoin de boussole du tout.
 * L'élévation reste à faire au niveau à bulle, mais l'élévation est l'angle
 * facile.
 *
 * **Le transit**, [prochainsTransits]. Deux périodes par an, autour des
 * équinoxes, le Soleil ne se contente pas de croiser l'azimut : il passe
 * exactement devant le satellite, à moins d'un degré. Ce jour-là, l'ombre de
 * la source au fond de la parabole se centre d'elle-même quand le pointage est
 * juste — c'est le réglage le plus précis qu'on puisse faire sans mesurer de
 * signal, et il donne les deux angles à la fois.
 *
 * Le même transit a une conséquence qu'il vaut mieux connaître d'avance : le
 * Soleil est une source de bruit énorme en bande X, et pendant les quelques
 * minutes où il est derrière le satellite, la réception se dégrade puis
 * disparaît. Ce n'est pas une panne. C'est pour cela que [Transit] porte une
 * durée : elle dit combien de temps ça va durer.
 *
 * ### Ce que le calcul vaut, et ce qu'il ne vaut pas
 *
 * La position du Soleil vient de [SunCalc], une approximation NOAA juste à
 * quelques centièmes de degré. Le disque solaire en fait un demi. Les instants
 * rendus sont donc bons à la minute, ce qui suffit très largement : personne
 * n'aligne une parabole à la seconde.
 *
 * Le calcul ignore la réfraction atmosphérique, qui relève le Soleil de
 * l'ordre d'un demi-degré à l'horizon et devient négligeable au-dessus d'une
 * dizaine de degrés. Depuis l'Europe le satellite est à 25° ou plus, donc la
 * question ne se pose pas. Elle se poserait pour une station très à l'est ou
 * très à l'ouest de 25,9° Est, où le satellite rase l'horizon — et là, la
 * réfraction serait le moindre des soucis.
 */
object SoleilQo100 {

    /**
     * Un passage du Soleil devant le satellite.
     *
     * [debutMs] et [finMs] bornent la fenêtre pendant laquelle l'écart reste
     * sous le seuil demandé ; [instantMs] est le moment du plus petit écart,
     * qui n'est pas exactement au milieu de la fenêtre parce que le Soleil ne
     * traverse pas la ligne de visée perpendiculairement.
     */
    data class Transit(
        /** L'instant du plus petit écart, en millisecondes depuis l'époque. */
        val instantMs: Long,
        /** Le plus petit écart angulaire atteint, en degrés. */
        val ecartDeg: Double,
        /** L'azimut du Soleil à cet instant — celui du satellite, ou tout comme. */
        val azSoleilDeg: Double,
        /** L'élévation du Soleil à cet instant. */
        val elSoleilDeg: Double,
        val debutMs: Long,
        val finMs: Long,
    ) {
        /** Combien de temps l'écart reste sous le seuil, en secondes. */
        val dureeS: Long get() = (finMs - debutMs) / 1000L
    }

    /**
     * L'écart angulaire entre deux directions données en azimut et élévation.
     *
     * C'est la formule du cosinus de l'angle entre deux vecteurs unitaires,
     * écrite en coordonnées horizontales. Le [coerceIn] n'est pas une
     * précaution de style : sans lui, deux directions identiques donnent un
     * cosinus qui vaut 1,000000000000002 par accumulation d'arrondis, et
     * [acos] rend un NaN — sur la seule valeur qui compte vraiment ici.
     */
    fun ecartDeg(az1: Double, el1: Double, az2: Double, el2: Double): Double {
        val a1 = Math.toRadians(az1)
        val e1 = Math.toRadians(el1)
        val a2 = Math.toRadians(az2)
        val e2 = Math.toRadians(el2)
        val c = sin(e1) * sin(e2) + cos(e1) * cos(e2) * cos(a1 - a2)
        return Math.toDegrees(acos(c.coerceIn(-1.0, 1.0)))
    }

    /** Un écart d'azimut ramené dans ±180°, pour pouvoir en lire le signe. */
    private fun ecartAzimut(a: Double, b: Double): Double {
        var d = (a - b) % 360.0
        if (d > 180.0) d -= 360.0
        if (d < -180.0) d += 360.0
        return d
    }

    /**
     * Le prochain instant où le Soleil est dans l'azimut du satellite, ou
     * `null` s'il n'y en a pas dans les [jours] qui viennent.
     *
     * On cherche le changement de signe de l'écart d'azimut, Soleil levé, par
     * pas de deux minutes, puis on affine par dichotomie jusqu'à la seconde.
     * Deux minutes suffisent parce que l'azimut du Soleil ne bouge jamais de
     * plus d'un degré en deux minutes sous nos latitudes — sauf près du zénith
     * en zone tropicale, où il peut balayer très vite ; c'est pour ce cas-là,
     * et pour lui seul, que la recherche vérifie aussi que le Soleil est levé
     * *aux deux bornes* avant de conclure à une traversée.
     *
     * Rend `null` quand le satellite n'est pas visible du lieu : aligner une
     * parabole sur un satellite sous l'horizon n'a pas de sens.
     */
    fun prochainPassageEnAzimut(
        latDeg: Double,
        lonDeg: Double,
        depuisMs: Long,
        jours: Int = 3,
    ): Long? {
        val sat = Qo100.pointage(latDeg, lonDeg)
        if (!sat.visible) return null

        val pas = 120_000L
        val fin = depuisMs + jours * 86_400_000L
        var t = depuisMs
        var precedent = etat(latDeg, lonDeg, t, sat.azDeg)
        while (t < fin) {
            val suivant = etat(latDeg, lonDeg, t + pas, sat.azDeg)
            if (precedent.leve && suivant.leve &&
                precedent.ecartAz != 0.0 &&
                (precedent.ecartAz < 0) != (suivant.ecartAz < 0)
            ) {
                return affineAzimut(latDeg, lonDeg, t, t + pas, sat.azDeg)
            }
            t += pas
            precedent = suivant
        }
        return null
    }

    private class Etat(val leve: Boolean, val ecartAz: Double)

    private fun etat(latDeg: Double, lonDeg: Double, ms: Long, azSat: Double): Etat {
        val p = SunCalc.azElDeg(latDeg, lonDeg, Date(ms))
        return Etat(p[1] > 0.0, ecartAzimut(p[0], azSat))
    }

    /** Dichotomie sur le changement de signe, jusqu'à la seconde. */
    private fun affineAzimut(
        latDeg: Double,
        lonDeg: Double,
        debut: Long,
        finBorne: Long,
        azSat: Double,
    ): Long {
        var a = debut
        var b = finBorne
        val signeA = etat(latDeg, lonDeg, a, azSat).ecartAz < 0
        while (b - a > 1000L) {
            val m = (a + b) / 2
            if ((etat(latDeg, lonDeg, m, azSat).ecartAz < 0) == signeA) a = m else b = m
        }
        return (a + b) / 2
    }

    /**
     * Les prochains passages du Soleil devant le satellite, à [seuilDeg] près.
     *
     * Le balayage est fait par pas d'une minute sur toute la période demandée,
     * ce qui fait de l'ordre de six cent mille positions solaires pour une
     * année — quelques dizaines de millisecondes, mais assez pour que l'appel
     * n'ait rien à faire sur le fil principal.
     *
     * Un transit est retenu quand l'écart minimal d'une journée passe sous le
     * seuil. Les journées voisines en font autant, forcément : la déclinaison
     * du Soleil ne bouge que d'un tiers de degré par jour près des équinoxes,
     * donc un même équinoxe donne plusieurs jours d'affilée. C'est voulu — on
     * veut les voir tous, pour choisir le sien.
     *
     * ### Le seuil, et pourquoi un degré
     *
     * Une parabole d'un mètre a un lobe à −3 dB de l'ordre de deux degrés en
     * bande X. Un degré d'écart, c'est donc déjà dans le lobe principal : le
     * bruit solaire monte, et l'ombre de la source se centre. Prendre plus
     * serré donnerait des dates plus rares et un réglage guère meilleur ;
     * prendre plus large noierait les vraies dates dans du à-peu-près.
     */
    fun prochainsTransits(
        latDeg: Double,
        lonDeg: Double,
        depuisMs: Long,
        jours: Int = 400,
        seuilDeg: Double = 1.0,
        maximum: Int = 8,
    ): List<Transit> {
        val sat = Qo100.pointage(latDeg, lonDeg)
        if (!sat.visible) return emptyList()

        val out = ArrayList<Transit>()
        val pas = 60_000L
        var jour = 0
        while (jour < jours && out.size < maximum) {
            val debutJour = depuisMs + jour * 86_400_000L
            val finJour = debutJour + 86_400_000L

            // Le minimum de la journée, à la minute.
            var meilleur = Long.MIN_VALUE
            var ecartMin = 360.0
            var t = debutJour
            while (t < finJour) {
                val e = ecartSoleil(latDeg, lonDeg, t, sat)
                if (e < ecartMin) {
                    ecartMin = e; meilleur = t
                }
                t += pas
            }

            if (ecartMin <= seuilDeg && meilleur != Long.MIN_VALUE) {
                // Affinage à la seconde autour de la minute retenue, puis
                // recherche des deux bords de la fenêtre sous le seuil.
                var instant = meilleur
                var e = ecartMin
                var s = meilleur - pas
                while (s <= meilleur + pas) {
                    val v = ecartSoleil(latDeg, lonDeg, s, sat)
                    if (v < e) { e = v; instant = s }
                    s += 1000L
                }
                val p = SunCalc.azElDeg(latDeg, lonDeg, Date(instant))
                out.add(
                    Transit(
                        instantMs = instant,
                        ecartDeg = e,
                        azSoleilDeg = p[0],
                        elSoleilDeg = p[1],
                        debutMs = bord(latDeg, lonDeg, instant, sat, seuilDeg, -1000L),
                        finMs = bord(latDeg, lonDeg, instant, sat, seuilDeg, 1000L),
                    )
                )
            }
            jour++
        }
        return out
    }

    private fun ecartSoleil(
        latDeg: Double,
        lonDeg: Double,
        ms: Long,
        sat: Qo100.Pointage,
    ): Double {
        val p = SunCalc.azElDeg(latDeg, lonDeg, Date(ms))
        return ecartDeg(p[0], p[1], sat.azDeg, sat.elDeg)
    }

    /**
     * Le bord de la fenêtre, en marchant seconde par seconde depuis le
     * minimum jusqu'à repasser au-dessus du seuil.
     *
     * La marche est bornée à une demi-heure : le Soleil parcourt un quart de
     * degré par minute, donc une fenêtre à un degré ne dépasse jamais une
     * dizaine de minutes. La borne est là pour qu'un seuil absurde — dix
     * degrés, cinquante — ne fasse pas tourner la boucle jusqu'à demain.
     */
    private fun bord(
        latDeg: Double,
        lonDeg: Double,
        instant: Long,
        sat: Qo100.Pointage,
        seuilDeg: Double,
        sens: Long,
    ): Long {
        var t = instant
        var n = 0
        while (n < 1800) {
            val suivant = t + sens
            if (ecartSoleil(latDeg, lonDeg, suivant, sat) > seuilDeg) return t
            t = suivant
            n++
        }
        return t
    }

    /**
     * L'azimut vers lequel l'ombre est projetée au moment du passage.
     *
     * C'est l'azimut du satellite plus 180°, et ça mérite d'être écrit ici
     * plutôt que dans l'écran : c'est la seule ligne du dispositif où une
     * erreur de signe retourne la parabole exactement à l'opposé du satellite,
     * et où rien dans l'affichage ne le signalerait.
     */
    fun azimutDeLOmbre(azSatDeg: Double): Double = (azSatDeg + 180.0) % 360.0

    /** Vrai si les deux azimuts sont à moins de [tolDeg] l'un de l'autre. */
    fun memeAzimut(a: Double, b: Double, tolDeg: Double = 0.5): Boolean =
        abs(ecartAzimut(a, b)) <= tolDeg
}
