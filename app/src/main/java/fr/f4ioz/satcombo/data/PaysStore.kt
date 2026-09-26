/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.data

import android.content.Context
import fr.f4ioz.satcombo.R
import fr.f4ioz.satcombo.domain.Pays
import org.json.JSONObject

/**
 * Le catalogue des contours de pays.
 *
 * 196 pays, 463 Ko, embarqués dans l'application. Le choix d'embarquer plutôt
 * que de télécharger est délibéré : sur un APK de sept méga-octets, c'est six
 * pour cent, et cela évite un serveur, un cache, un mode dégradé et une panne de
 * plus le jour où l'on est en portable sans réseau — c'est-à-dire précisément
 * quand on compose une carte QRV.
 *
 * Les contours viennent de Natural Earth au 1:50 M, simplifiés par
 * Douglas-Peucker : la France métropolitaine tient en 211 points, la Corse en
 * 41. Assez fin pour que la silhouette soit juste à l'œil, assez grossier pour
 * ne pas peser.
 *
 * Le catalogue se charge une fois et reste en mémoire : quelques centaines de
 * milliers de doubles, et l'analyse du JSON coûte trop cher pour être refaite à
 * chaque ouverture de l'écran.
 */
object PaysStore {

    @Volatile private var cache: List<Pays.Contour>? = null

    fun tous(context: Context): List<Pays.Contour> {
        cache?.let { return it }
        val lu = runCatching { lis(context) }.getOrDefault(emptyList())
        cache = lu
        return lu
    }

    private fun lis(context: Context): List<Pays.Contour> {
        val texte = context.resources.openRawResource(R.raw.countries)
            .bufferedReader().use { it.readText() }
        val racine = JSONObject(texte)
        val out = ArrayList<Pays.Contour>(racine.length())
        val codes = racine.keys()
        while (codes.hasNext()) {
            val code = codes.next()
            val o = racine.getJSONObject(code)
            val arr = o.getJSONArray("r")
            val anneaux = ArrayList<DoubleArray>(arr.length())
            for (i in 0 until arr.length()) {
                val a = arr.getJSONArray(i)
                val d = DoubleArray(a.length())
                for (j in 0 until a.length()) d[j] = a.getDouble(j)
                anneaux.add(d)
            }
            out.add(Pays.Contour(code, o.optString("n", code), anneaux))
        }
        // Du plus grand au plus petit : les pays vastes d'abord, ce qui rend le
        // premier résultat pertinent quand un point tombe dans deux boîtes.
        return out.sortedByDescending { c -> c.anneaux.sumOf { Pays.aire(it) } }
    }

    /** Le pays où se trouve l'opérateur, et les morceaux à dessiner autour. */
    fun autour(context: Context, lat: Double, lon: Double): Pair<Pays.Contour, List<DoubleArray>>? {
        val c = Pays.trouve(tous(context), lat, lon) ?: return null
        return c to Pays.morceauxAutour(c, lat, lon)
    }
}
