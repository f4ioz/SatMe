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

    /** Starts receiving [r] there with [recepteur] (see [Datv.RECEPTEURS]); null when started, else why not. */
    fun demarre(r: ReglageDatv, recepteur: String = Datv.MINITIOUNER): String? {
        if (recepteur != Datv.MINITIOUNER) {
            // An older node knows only the MiniTiouner: it would start that one instead.
            val connus = runCatching { JSONObject(http.newCall(Request.Builder().url(base() + "/noeud/api").build()).execute().use { it.body?.string().orEmpty() }) }
                .getOrNull()?.optJSONArray("recepteurs")
            if (connus == null || (0 until connus.length()).none { connus.optString(it) == recepteur })
                return "ce SatMe-Node ne sait pas recevoir avec " + Datv.libelleRecepteur(recepteur) + " : le mettre à jour"
        }
        val q = "?ciel=" + r.cielKhz / 1000.0 + "&sr=" + r.srKs + "&ol=" + r.olKhz / 1000.0 + "&entree=" + (if (r.entreeB) "b" else "a") + "&lnb=" + r.lnb +
            "&recepteur=" + recepteur + (if (r.balayage.isEmpty()) "" else "&balayage=" + r.balayage.joinToString(","))
        val rep = runCatching { post("/noeud/datv/demarre$q") }.getOrElse { return "node injoignable : " + (it.message ?: "") }
        return if (rep.optBoolean("ok", true)) null else rep.optString("erreur", "le node refuse")
    }

    fun arrete() { runCatching { post("/noeud/datv/arrete") } }

    /** The node's status as it gives it (JSON, see [Datv.etatDe] and [Datv.points]). */
    fun etat(): JSONObject = JSONObject(get("/noeud/datv"))

    /** Where the transport stream is read (HTTP, as long as it is read). */
    fun urlFlux(): String = avecCle("/noeud/datv/ts")

    /** The node's host and port, for a connection of its own (the SDR's and the transmitter's streams). */
    fun hotePort(): Pair<String, Int> {
        val b = base().removePrefix("http://")
        return b.substringBeforeLast(':') to (b.substringAfterLast(':').toIntOrNull() ?: PORT)
    }

    /** [chemin] with the key, as a raw request asks it. */
    fun cheminAvecCle(chemin: String): String = chemin + (if ('?' in chemin) "&" else "?") + "cle=" + cle

    /** The node's devices and their roles (an older node: only its DATV receiver). */
    fun materiel(): JSONObject = runCatching { JSONObject(get("/noeud/materiel")) }.getOrElse { x ->
        if (x.message?.contains("404") == true) JSONObject().put("ancien", true) else throw x
    }

    /** The node's health ([SanteNoeud]); an older node: null. */
    fun sante(): JSONObject? = runCatching { JSONObject(get("/noeud/sante")) }.getOrElse { x ->
        if (x.message?.contains("404") == true) null else throw x
    }

    /** A supervision action on the node ("datv-arrete", "sdr-arrete", "tx-arrete", "relance"); null when done, else why not. */
    fun action(quoi: String): String? = runCatching {
        val r = post("/noeud/action?quoi=" + java.net.URLEncoder.encode(quoi, "UTF-8"))
        if (r.optBoolean("ok")) null else r.optString("erreur", "le node refuse")
    }.getOrElse { "node injoignable : " + (it.message ?: "") }

    /**
     * The node's Pi restarted ([eteint] false) or switched off, its
     * transmission stopped first when [arreteTx]; the node's answer
     * (`ok`, `erreur`, `emet` when it refused for a transmission under way).
     */
    fun systeme(eteint: Boolean, arreteTx: Boolean): JSONObject = runCatching {
        post("/noeud/systeme/" + (if (eteint) "eteint" else "redemarre") + (if (arreteTx) "?arreteTx=1" else ""))
    }.getOrElse {
        JSONObject().put("ok", false).put("erreur", if (it is org.json.JSONException) "ce SatMe-Node ne sait pas encore redémarrer ni éteindre son Pi : le mettre à jour"
            else "node injoignable : " + (it.message ?: ""))
    }

    /** Sets which device the node lends as its SDR (receive only); null when done, else why not. */
    fun choisitSdr(appareil: String): String? = runCatching {
        val r = post("/noeud/sdr/appareil?appareil=" + java.net.URLEncoder.encode(appareil, "UTF-8"))
        if (r.optBoolean("ok")) null else r.optString("erreur", "le node refuse")
    }.getOrElse { "node injoignable : " + (it.message ?: "") }

    /**
     * Sets which board the node transmits with ("aucun", "pluto", "bladerf");
     * a new one leaves « émission autorisée » off until ticked on the node.
     * Null when done, else why not.
     */
    fun choisitTx(appareil: String): String? = runCatching {
        val r = post("/noeud/tx/appareil?appareil=" + java.net.URLEncoder.encode(appareil, "UTF-8"))
        if (r.optBoolean("ok")) null else r.optString("erreur", "le node refuse")
    }.getOrElse { if (it is org.json.JSONException) "ce SatMe-Node choisit son émetteur sur sa propre page : le mettre à jour" else "node injoignable : " + (it.message ?: "") }

    companion object {
        const val PORT = 8090
        private const val CLE_ADRESSE = "datv_noeud"
        private const val CLE_CLE = "datv_noeud_cle"
        private const val CLE_NOM = "datv_noeud_nom"
    }
}
