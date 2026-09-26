/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.domain

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Les contours de pays, pour la photo QRV.
 *
 * L'idée vient des cartes qu'Olivier composait à la main : la silhouette de son
 * pays posée sur une photo du lieu, remplie d'un drapeau ou d'une seconde image,
 * avec un point à l'endroit exact d'où il émet. Ce fichier ne dessine rien — il
 * répond aux trois questions dont le dessin a besoin : dans quel pays suis-je,
 * quels morceaux de ce pays faut-il tracer, et où tombe chaque point à l'écran.
 *
 * Tout est en degrés jusqu'à la dernière ligne, et rien ici ne connaît Android :
 * la silhouette d'un pays se vérifie au banc, contrairement à un rendu.
 */
object Pays {

    /**
     * Un pays et ses anneaux, chacun aplati en lat, lon, lat, lon…
     *
     * Les anneaux sont rangés du plus grand au plus petit : pour la France, la
     * métropole d'abord, la Corse ensuite, les autres territoires après. C'est
     * ce qui permet de prendre « le morceau principal » sans le chercher.
     */
    class Contour(val code: String, val nom: String, val anneaux: List<DoubleArray>)

    /** Rectangle en degrés : sud, ouest, nord, est. */
    class Boite(val sud: Double, val ouest: Double, val nord: Double, val est: Double) {
        val hauteur: Double get() = nord - sud
        val largeur: Double get() = est - ouest
        val latMoyenne: Double get() = (nord + sud) / 2
    }

    /**
     * Le point est-il à l'intérieur de l'anneau ?
     *
     * Lancer de rayon horizontal, la méthode la plus courte qui soit juste. On
     * ne se soucie pas des trous : un pays troué — l'Italie et le Vatican, par
     * exemple — reste dessiné plein, et c'est ce qu'on veut sur une carte de
     * cinq centimètres.
     */
    fun dansAnneau(anneau: DoubleArray, lat: Double, lon: Double): Boolean {
        var dedans = false
        val n = anneau.size / 2
        var j = n - 1
        for (i in 0 until n) {
            val lati = anneau[2 * i]; val loni = anneau[2 * i + 1]
            val latj = anneau[2 * j]; val lonj = anneau[2 * j + 1]
            if ((lati > lat) != (latj > lat) &&
                lon < (lonj - loni) * (lat - lati) / (latj - lati) + loni
            ) dedans = !dedans
            j = i
        }
        return dedans
    }

    fun dansPays(c: Contour, lat: Double, lon: Double): Boolean =
        c.anneaux.any { dansAnneau(it, lat, lon) }

    /**
     * Le pays qui contient le point.
     *
     * En mer ou sur une côte simplifiée, personne ne répond : on rend alors le
     * pays dont le contour passe le plus près, faute de quoi un opérateur sur
     * une plage bretonne se retrouverait sans carte. Le seuil de dix degrés
     * évite d'attribuer l'Atlantique à l'Irlande.
     */
    fun trouve(contours: List<Contour>, lat: Double, lon: Double,
               seuilDeg: Double = 10.0): Contour? {
        contours.firstOrNull { dansPays(it, lat, lon) }?.let { return it }
        var meilleur: Contour? = null
        var d = Double.MAX_VALUE
        for (c in contours) for (a in c.anneaux) {
            val b = boite(listOf(a))
            val dist = distanceBoite(b, lat, lon)
            if (dist < d) { d = dist; meilleur = c }
        }
        return if (d <= seuilDeg) meilleur else null
    }

    private fun distanceBoite(b: Boite, lat: Double, lon: Double): Double {
        val dLat = max(0.0, max(b.sud - lat, lat - b.nord))
        val dLon = max(0.0, max(b.ouest - lon, lon - b.est)) * cos(Math.toRadians(lat))
        return kotlin.math.hypot(dLat, dLon)
    }

    fun boite(anneaux: List<DoubleArray>): Boite {
        var s = 90.0; var n = -90.0; var o = 180.0; var e = -180.0
        for (a in anneaux) {
            var i = 0
            while (i < a.size) {
                val lat = a[i]; val lon = a[i + 1]
                s = min(s, lat); n = max(n, lat); o = min(o, lon); e = max(e, lon)
                i += 2
            }
        }
        return Boite(s, o, n, e)
    }

    /**
     * Les anneaux à dessiner autour d'un point.
     *
     * On garde le plus grand anneau proche du point, puis tous ceux qui se
     * trouvent dans son voisinage. Pour un opérateur en Bretagne, cela donne la
     * métropole **et la Corse** — qu'on veut voir — mais pas la Guyane ni la
     * Réunion, qui feraient une carte illisible. Depuis la Guadeloupe, la même
     * règle rend l'île seule, ce qui est également ce qu'il faut.
     *
     * Aucune liste de territoires à tenir à jour : c'est la géographie qui
     * décide.
     */
    fun morceauxAutour(c: Contour, lat: Double, lon: Double,
                       voisinageDeg: Double = 12.0): List<DoubleArray> {
        if (c.anneaux.isEmpty()) return emptyList()
        val principal = c.anneaux.firstOrNull { dansAnneau(it, lat, lon) }
            ?: c.anneaux.minByOrNull { distanceBoite(boite(listOf(it)), lat, lon) }
            ?: return emptyList()
        val bp = boite(listOf(principal))
        return c.anneaux.filter { a ->
            a === principal || chevauche(bp, boite(listOf(a)), voisinageDeg)
        }
    }

    private fun chevauche(a: Boite, b: Boite, marge: Double): Boolean =
        b.ouest <= a.est + marge && b.est >= a.ouest - marge &&
            b.sud <= a.nord + marge && b.nord >= a.sud - marge

    /**
     * Le placement du contour dans un cadre de l'écran.
     *
     * Deux précautions. La longitude est comprimée par le cosinus de la
     * latitude, sans quoi la France apparaîtrait un tiers trop large — c'est
     * l'erreur classique quand on projette des degrés directement en pixels.
     * Et l'échelle est la même dans les deux sens, pour que le pays garde sa
     * forme au lieu d'être étiré au cadre.
     */
    class Placement(
        val boite: Boite,
        val echelle: Double,
        val decalageX: Double,
        val decalageY: Double,
        val compression: Double,
    ) {
        fun x(lon: Double): Double = decalageX + (lon - boite.ouest) * compression * echelle
        fun y(lat: Double): Double = decalageY + (boite.nord - lat) * echelle
    }

    fun place(boite: Boite, cadreX: Double, cadreY: Double,
              cadreL: Double, cadreH: Double): Placement {
        val compression = cos(Math.toRadians(boite.latMoyenne)).coerceAtLeast(0.05)
        val l = (boite.largeur * compression).coerceAtLeast(1e-9)
        val h = boite.hauteur.coerceAtLeast(1e-9)
        val echelle = min(cadreL / l, cadreH / h)
        val restL = cadreL - l * echelle
        val restH = cadreH - h * echelle
        return Placement(boite, echelle, cadreX + restL / 2, cadreY + restH / 2, compression)
    }

    /** Un pays est-il d'Europe, au sens de la boîte préchargée ? */
    fun enEurope(b: Boite): Boolean =
        b.nord > 34.0 && b.sud < 72.0 && b.est > -32.0 && b.ouest < 45.0

    /** Surface approchée d'un anneau, en degrés carrés — pour classer les morceaux. */
    fun aire(anneau: DoubleArray): Double {
        var s = 0.0
        val n = anneau.size / 2
        for (i in 0 until n) {
            val j = (i + 1) % n
            s += anneau[2 * i + 1] * anneau[2 * j] - anneau[2 * j + 1] * anneau[2 * i]
        }
        return abs(s) / 2
    }
}
