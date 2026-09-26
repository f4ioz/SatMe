/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.data

import fr.f4ioz.satcombo.domain.QrzReponse
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * L'annuaire QRZ.com, par son interface XML.
 *
 * Sert à combler après le passage ce qu'on n'a pas pu noter pendant : le
 * carré du correspondant, quand il ne l'a pas donné ou qu'on ne l'a pas
 * entendu.
 *
 * **Seuls les carrés absents sont comblés.** Un carré noté à l'oreille
 * pendant le contact vaut mieux qu'un carré d'annuaire : l'autre était
 * peut-être portable, et QRZ donne son domicile. Écraser reviendrait à
 * remplacer un fait par une présomption.
 */
class Qrz {

    private var cle: String? = null
    private val cache = HashMap<String, QrzReponse.Fiche>()

    val connecte: Boolean get() = cle != null

    private fun demande(params: String): String {
        val url = URL("https://xmldata.qrz.com/xml/current/?$params;agent=SatMe")
        val c = url.openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.setRequestProperty("User-Agent", "SatMe")
            c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            runCatching { c.disconnect() }
        }
    }

    private fun encode(v: String) = URLEncoder.encode(v, "UTF-8")

    /** Ouvre une session. Rend l'erreur du serveur, ou une chaîne vide. */
    fun connecte(utilisateur: String, motDePasse: String): String {
        val f = runCatching {
            QrzReponse.lis(demande("username=${encode(utilisateur)};password=${encode(motDePasse)}"))
        }.getOrElse { return "réseau : " + it.javaClass.simpleName }
        if (f.erreur.isNotBlank()) return f.erreur
        if (f.cle.isBlank()) return "QRZ n'a pas rendu de clé de session"
        cle = f.cle
        cache.clear()
        return ""
    }

    fun oublie() { cle = null; cache.clear() }

    /**
     * La fiche d'un indicatif. Le cache évite de redemander deux fois le même
     * appel — QRZ compte les requêtes, et un passage chargé en répète.
     */
    fun cherche(indicatif: String): QrzReponse.Fiche {
        val ind = indicatif.trim().uppercase()
        if (ind.isEmpty()) return QrzReponse.Fiche()
        cache[ind]?.let { return it }
        val k = cle ?: return QrzReponse.Fiche(erreur = "pas connecté à QRZ")
        val f = runCatching {
            QrzReponse.lis(demande("s=$k;callsign=${encode(ind)}"))
        }.getOrElse { return QrzReponse.Fiche(erreur = "réseau : " + it.javaClass.simpleName) }
        // Une session perdue ne se met pas en cache : elle se rejoue après
        // reconnexion.
        if (f.erreur.contains("session", ignoreCase = true)) {
            cle = null
            return f
        }
        cache[ind] = f
        return f
    }
}
