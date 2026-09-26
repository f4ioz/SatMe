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
import fr.f4ioz.satcombo.domain.Indicatifs
import org.json.JSONArray
import org.json.JSONObject

/**
 * L'index de prédiction venu d'ailleurs.
 *
 * Ce fichier ne contient **pas** de contacts. Il contient de quoi deviner : un
 * indicatif, un carré, une date, un satellite. La distinction est le cœur du
 * dispositif — le carnet de référence reste local et unique, et ceci n'est
 * qu'un cache de ce qu'un serveur sait de plus que lui. Deux carnets à
 * réconcilier, c'est un carnet de trop.
 *
 * Dix mille lignes tiennent en quelques centaines de kilo-octets et se
 * rechargent au démarrage sans qu'on s'en aperçoive. Une base de données serait
 * une dépendance et une migration pour interroger ce qui tient déjà en mémoire.
 */
class IndexImporte(context: Context) {

    private val fichier = context.filesDir.resolve("index_indicatifs.json")

    fun charge(): List<Indicatifs.Contact> {
        if (!fichier.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(fichier.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Indicatifs.Contact(
                    indicatif = o.optString("c"),
                    locator = o.optString("g"),
                    quandMs = o.optLong("t"),
                    satellite = o.optString("s"),
                    nom = o.optString("n"),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun enregistre(contacts: List<Indicatifs.Contact>) {
        val arr = JSONArray()
        contacts.forEach { c ->
            arr.put(JSONObject().apply {
                put("c", c.indicatif); put("g", c.locator)
                put("t", c.quandMs); put("s", c.satellite); put("n", c.nom)
            })
        }
        runCatching { fichier.writeText(arr.toString()) }
    }

    fun vide() { runCatching { fichier.delete() } }
}
