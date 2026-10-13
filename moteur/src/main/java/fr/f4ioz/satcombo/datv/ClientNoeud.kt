/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv

import fr.f4ioz.satcombo.plateforme.Stockage
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * A SatMe-Node seen from SatMe-PC or the phone: joined once with the code it
 * gave at its start, then driven with the key it gave for it (kept here).
 * Blocking calls: off the main thread.
 */
class ClientNoeud(private val stockage: Stockage) {

    /** The node's address ("192.168.1.30" or with its port); empty: none. */
    var adresse: String
        get() = stockage.getString(CLE_ADRESSE, "") ?: ""
        private set(v) { stockage.edit().putString(CLE_ADRESSE, v).apply() }
    private var cle: String
        get() = stockage.getString(CLE_CLE, "") ?: ""
        set(v) { stockage.edit().putString(CLE_CLE, v).apply() }
    var nom: String
        get() = stockage.getString(CLE_NOM, "") ?: ""
        private set(v) { stockage.edit().putString(CLE_NOM, v).apply() }

    val associe: Boolean get() = adresse.isNotBlank() && cle.isNotBlank()

    private val http = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    /** For the stream: no read timeout (a search may last). */
    val httpFlux: OkHttpClient = http.newBuilder().readTimeout(0, TimeUnit.SECONDS).build()

    fun base(a: String = adresse) = "http://" + a.trim().removePrefix("http://").trimEnd('/').let { if (':' in it) it else "$it:$PORT" }

    private fun avecCle(chemin: String) = base() + chemin + (if ('?' in chemin) "&" else "?") + "cle=" + cle

    private fun get(chemin: String): String = http.newCall(Request.Builder().url(avecCle(chemin)).build()).execute().use {
        if (it.code == 403) throw IOException("le node refuse la clé : l'associer à nouveau avec son code")
        if (!it.isSuccessful) throw IOException("le node répond " + it.code)
        it.body?.string().orEmpty()
    }

    private fun post(chemin: String): JSONObject =
        http.newCall(Request.Builder().url(avecCle(chemin)).post(ByteArray(0).toRequestBody(null)).build()).execute().use {
            if (it.code == 403) throw IOException("le node refuse la clé : l'associer à nouveau avec son code")
            JSONObject(it.body?.string().orEmpty().ifBlank { "{}" })
        }

    /**
     * Joins the node at [a] with its [code]; an empty address forgets the
     * node. Null when done, else why not (the node kept as it was).
     */
    fun rejoins(a: String, code: String): String? {
        if (a.isBlank()) { adresse = ""; cle = ""; nom = ""; return null }
        return runCatching {
            val api = JSONObject(http.newCall(Request.Builder().url(base(a) + "/noeud/api").build()).execute().use { it.body?.string().orEmpty() })
            if (!api.optBoolean("noeud")) throw IOException("ce n'est pas un SatMe-Node")
            val j = JSONObject(http.newCall(Request.Builder().url(base(a) + "/noeud/entrer?code=" + java.net.URLEncoder.encode(code.trim(), "UTF-8")).build())
                .execute().use { it.body?.string().orEmpty() })
            if (!j.optBoolean("ok")) throw IOException("code refusé : celui que le node a donné à son démarrage")
            adresse = a.trim(); cle = j.getString("cle"); nom = api.optString("nom")
            null
        }.getOrElse { it.message ?: "node injoignable" }
    }

    /** Starts receiving [r] there; null when started, else why not. */
    fun demarre(r: ReglageDatv): String? {
        val q = "?ciel=" + r.cielKhz / 1000.0 + "&sr=" + r.srKs + "&ol=" + r.olKhz / 1000.0 + "&entree=" + (if (r.entreeB) "b" else "a") + "&lnb=" + r.lnb
        val rep = runCatching { post("/noeud/datv/demarre$q") }.getOrElse { return "node injoignable : " + (it.message ?: "") }
        return if (rep.optBoolean("ok", true)) null else rep.optString("erreur", "le node refuse")
    }

    fun arrete() { runCatching { post("/noeud/datv/arrete") } }

    /** The node's status as it gives it (JSON, see [Datv.etatDe] and [Datv.points]). */
    fun etat(): JSONObject = JSONObject(get("/noeud/datv"))

    /** Where the transport stream is read (HTTP, as long as it is read). */
    fun urlFlux(): String = avecCle("/noeud/datv/ts")

    companion object {
        const val PORT = 8090
        private const val CLE_ADRESSE = "datv_noeud"
        private const val CLE_CLE = "datv_noeud_cle"
        private const val CLE_NOM = "datv_noeud_nom"
    }
}
