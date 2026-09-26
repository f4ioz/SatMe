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
import org.json.JSONArray

/**
 * Les lieux habités de France : de quoi se repérer sur une carte.
 *
 * Un contour de parc dit où sont les limites, jamais où l'on est : posé sur
 * une photo, il ne ressemble à rien de reconnaissable. Trois noms de communes
 * autour suffisent à situer la zone d'un coup d'œil.
 *
 * Source GeoNames (licence CC BY), lieux habités de 200 habitants et plus,
 * **triés par population décroissante** — l'ordre du fichier fait tout le
 * travail : les premiers trouvés dans une fenêtre sont les plus importants,
 * donc ceux qu'on écrit. 25 187 entrées, 727 Ko.
 */
object Villes {

    class Ville(val nom: String, val lat: Double, val lon: Double)

    @Volatile private var cache: List<Ville>? = null

    fun charge(context: Context): List<Ville> {
        cache?.let { return it }
        val lu = runCatching {
            val txt = context.resources.openRawResource(R.raw.villes)
                .bufferedReader().use { it.readText() }
            val arr = JSONArray(txt)
            (0 until arr.length()).map { i ->
                val v = arr.getJSONArray(i)
                Ville(v.getString(0), v.getDouble(1), v.getDouble(2))
            }
        }.getOrDefault(emptyList())
        cache = lu
        return lu
    }

    /**
     * Les [max] villes les plus importantes dans la fenêtre donnée.
     *
     * Le fichier étant trié par population, on prend les premières qui
     * tombent dans la fenêtre et on s'arrête : pas de tri, pas de calcul de
     * distance, une seule passe.
     */
    fun dansFenetre(
        context: Context,
        latMin: Double, latMax: Double, lonMin: Double, lonMax: Double,
        max: Int = 6,
    ): List<Ville> {
        val out = ArrayList<Ville>(max)
        for (v in charge(context)) {
            if (v.lat in latMin..latMax && v.lon in lonMin..lonMax) {
                out.add(v)
                if (out.size >= max) break
            }
        }
        return out
    }
}
