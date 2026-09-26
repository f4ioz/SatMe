/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.data

import fr.f4ioz.satcombo.domain.QrzReponse
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * QRZ.com lookup through its XML interface, to fill in after the pass the
 * grid square that was not given or not heard.
 *
 * **Only missing grid squares are filled.** A square heard during the contact
 * beats the directory: the station may have been portable, and QRZ gives the
 * home address. Overwriting would replace a fact with a guess.
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

    /** Opens a session. Returns the server error, or an empty string. */
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
     * The record for a callsign. Cached: QRZ counts queries, and a busy pass
     * repeats callsigns.
     */
    fun cherche(indicatif: String): QrzReponse.Fiche {
        val ind = indicatif.trim().uppercase()
        if (ind.isEmpty()) return QrzReponse.Fiche()
        cache[ind]?.let { return it }
        val k = cle ?: return QrzReponse.Fiche(erreur = "pas connecté à QRZ")
        val f = runCatching {
            QrzReponse.lis(demande("s=$k;callsign=${encode(ind)}"))
        }.getOrElse { return QrzReponse.Fiche(erreur = "réseau : " + it.javaClass.simpleName) }
        // A lost session is not cached: it is retried after reconnecting.
        if (f.erreur.contains("session", ignoreCase = true)) {
            cle = null
            return f
        }
        cache[ind] = f
        return f
    }
}
