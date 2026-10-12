/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.demo

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Joining SatMe PC by its QR code: no address or code typed on either side.
 *
 * The PC's code says where it listens and a token drawn for this time
 * (`http://192.168.1.10:8091/appairage?j=…`). The phone opens its control
 * desk, as its switch does, and tells the PC where the desk is and its code;
 * the PC then joins the desk itself — its journal, its passes.
 */
object RejointPc {

    /** The PC's address and token from a scanned QR code; null when it is not SatMe PC's. */
    fun lit(contenu: String): Pair<String, String>? {
        val u = runCatching { java.net.URI(contenu.trim()) }.getOrNull() ?: return null
        if (u.scheme != "http" || u.host.isNullOrBlank() || u.port <= 0 || u.path != "/appairage") return null
        val j = u.rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("j=") }?.substring(2).orEmpty()
        if (j.length < 10) return null
        return "http://${u.host}:${u.port}" to j
    }

    private val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
        // The PC joins the desk before it answers: a few seconds.
        .readTimeout(25, TimeUnit.SECONDS).build()

    /**
     * Opens the control desk if it is not, and announces it to the PC at
     * [base] with [jeton]; null once the PC has joined, else why not (its
     * own words when it gave some). Blocking: off the main thread.
     */
    fun annonce(base: String, jeton: String): String? {
        if (!ServeurDemo.etat.value.actif) ServeurDemo.demarre()
        if (!ServeurDemo.etat.value.commandeActive) ServeurDemo.commande(true)
        val e = ServeurDemo.etat.value
        if (!e.actif) return fr.f4ioz.satcombo.i18n.t("cmd_qr_serveur")
        val adresse = e.urlCommande
        if (adresse.isBlank() || e.codeCommande.isBlank()) return fr.f4ioz.satcombo.i18n.t("cmd_qr_reseau")
        val url = "$base/appairage/telephone?j=" + enc(jeton) + "&adresse=" + enc(adresse) + "&code=" + enc(e.codeCommande) +
            "&nom=" + enc(ServeurDemo.nomStation)
        return runCatching {
            client.newCall(Request.Builder().url(url).post(ByteArray(0).toRequestBody(null)).build()).execute().use { r ->
                val j = JSONObject(r.body?.string().orEmpty())
                if (j.optBoolean("ok")) null else j.optString("erreur").ifBlank { "HTTP " + r.code }
            }
        }.getOrElse { fr.f4ioz.satcombo.i18n.tf("cmd_qr_injoignable", base.removePrefix("http://")) }
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
}
