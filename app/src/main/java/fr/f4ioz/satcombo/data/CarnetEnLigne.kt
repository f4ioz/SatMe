/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Le carnet en ligne : Wavelog ou Cloudlog, sans distinction.
 *
 * Wavelog est un dérivé de Cloudlog et l'API a suivi : la vérification d'un
 * carré prend les mêmes paramètres des deux côtés — clé, *slug* public du
 * carnet, et une bande facultative où « SAT » restreint aux contacts
 * satellite. Un seul connecteur suffit donc, et l'on ne demande pas à
 * l'opérateur de déclarer lequel il utilise : la question est la même, le
 * chemin doit l'être aussi.
 *
 * Toutes les réponses sont mises en cache. La documentation demande
 * explicitement de n'appeler ces routes qu'en cas de besoin : un carré déjà
 * demandé ne se redemande pas.
 */
object CarnetEnLigne {

    /** Ce que le serveur sait d'un carré. */
    enum class Etat { INCONNU, TRAVAILLE, CONFIRME, JAMAIS }

    private val cache = HashMap<String, Etat>()

    /** Vide le cache — après un changement de réglages, ou à la demande. */
    fun oublie() { synchronized(cache) { cache.clear() }; prefixe = null }

    fun enCache(carre: String): Etat? = synchronized(cache) { cache[carre.uppercase()] }

    /**
     * Le carré a-t-il déjà été travaillé ?
     *
     * [base] est l'URL du serveur (« https://log.exemple.fr »), [cle] une clé
     * en lecture seule, [slug] le *slug* public du carnet.
     */
    suspend fun carre(
        base: String, cle: String, slug: String, carre: String, satellite: Boolean = true,
    ): Etat = withContext(Dispatchers.IO) {
        val k = carre.uppercase()
        synchronized(cache) { cache[k] }?.let { return@withContext it }
        val corps = JSONObject()
            .put("key", cle)
            .put("logbook_public_slug", slug)
            .put("grid", k)
            .apply { if (satellite) put("band", "SAT") }
            .toString()
        val etat = runCatching { poste(base, "api/logbook_check_grid", corps) }
            .getOrNull()
            ?.let { lis(it) }
            ?: Etat.INCONNU
        // Un échec réseau n'est pas une réponse : on ne le met pas en cache,
        // sinon une coupure passagère fige l'écran sur « inconnu ».
        if (etat != Etat.INCONNU) synchronized(cache) { cache[k] = etat }
        etat
    }

    /** Vérifie que les réglages fonctionnent, et rend un message lisible. */
    suspend fun essai(base: String, cle: String, slug: String): String =
        withContext(Dispatchers.IO) {
            runCatching {
                val r = poste(base, "api/logbook_check_grid", JSONObject()
                    .put("key", cle).put("logbook_public_slug", slug)
                    .put("grid", "JN18").toString())
                when {
                    r.contains("\"result\"", true) -> "OK"
                    // Une page HTML n'apprend rien : on dit ce qu'elle est.
                    r.trimStart().startsWith("<") ->
                        "le serveur répond une page, pas du JSON — vérifier l'adresse"
                    r.startsWith("HTTP 500") ->
                        "erreur 500 : slug ou clé refusés par le serveur"
                    r.startsWith("HTTP 401") || r.startsWith("HTTP 403") ->
                        "clé refusée"
                    r.startsWith("HTTP 404") -> "adresse introuvable"
                    else -> r.take(90)
                }
            }.getOrElse { "échec : " + it.javaClass.simpleName }
        }

    /**
     * Dépose un contact au carnet, et dit si le serveur l'a pris.
     *
     * [profil] est l'identifiant du profil de station Wavelog. Il n'est pas
     * facultatif côté serveur : sans lui le contact est refusé, ou pire, rangé
     * sous le mauvais indicatif de station — ce qui ne se voit qu'au moment où
     * LoTW refuse d'apparier.
     *
     * La clé de lecture qui suffit pour interroger les carrés **ne suffit pas
     * ici** : déposer demande une clé en écriture. Un opérateur qui a rempli
     * ses réglages pour la peinture des carrés se croira configuré ; c'est la
     * réponse du serveur qui le détrompera, et elle doit donc être rendue
     * telle quelle plutôt que résumée en « échec ».
     */
    suspend fun depose(
        base: String, cle: String, profil: String, adif: String,
    ): String = withContext(Dispatchers.IO) {
        val corps = JSONObject()
            .put("key", cle)
            .put("station_profile_id", profil)
            .put("type", "adif")
            .put("string", adif)
            .toString()
        runCatching { poste(base, "api/qso", corps) }.getOrElse {
            "échec : " + it.javaClass.simpleName
        }
    }

    /**
     * Les emplacements de station déclarés chez Wavelog.
     *
     * Rend la liste, ou lève. Sert à rattacher chaque contact au profil de
     * son carré : **Wavelog range d'après le profil et ignore le
     * `MY_GRIDSQUARE` du fichier.**
     */
    suspend fun profils(base: String, cle: String):
        List<fr.f4ioz.satcombo.domain.ProfilsStation.Profil> = withContext(Dispatchers.IO) {
        // **La clé va dans l'adresse, pas dans un corps JSON.**
        //
        // `station_info` et `statistics` se lisent en GET, la clé posée sur
        // l'URL ; seuls `qso` et `create_station` prennent un corps. Un POST
        // avec `{"key": …}` rend un 401 — donc « clé refusée », alors que la
        // clé est bonne et que c'est l'adresse qui est mal formée. Le message
        // désigne la mauvaise cause, et l'on va vérifier ses droits pour rien.
        val reponse = lit(base, "api/station_info/" + cle.trim())
        val tableau = runCatching { org.json.JSONArray(reponse) }.getOrElse {
            throw IllegalStateException(reponse.take(200))
        }
        (0 until tableau.length()).map { i ->
            val o = tableau.getJSONObject(i)
            fr.f4ioz.satcombo.domain.ProfilsStation.Profil(
                id = o.optString("station_id"),
                carre = o.optString("station_gridsquare"),
                indicatif = o.optString("station_callsign"),
                nom = o.optString("station_profile_name"))
        }
    }

    /**
     * Crée un emplacement de station.
     *
     * **La zone ITU n'est pas facultative.** Wavelog crée volontiers le profil
     * sans elle, puis refuse ensuite tous les contacts qui s'y rattachent — un
     * défaut qui ne se voit qu'au dépôt suivant, loin de sa cause. On refuse
     * donc ici plutôt que de fabriquer un profil mort-né.
     */
    suspend fun creeProfil(
        base: String, cle: String, nom: String, carre: String, indicatif: String,
        dxcc: String, pays: String, cq: String, itu: String,
    ): String = withContext(Dispatchers.IO) {
        if (itu.isBlank()) return@withContext "zone ITU obligatoire"
        val o = JSONObject()
            .put("station_profile_name", nom)
            .put("station_gridsquare", carre.trim().uppercase())
            .put("station_callsign", indicatif.trim().uppercase())
            .put("station_dxcc", dxcc.trim()).put("dxccname", pays.trim())
            .put("station_cq", cq.trim()).put("station_itu", itu.trim())
            .put("station_active", "1").put("link_active_logbook", "1")
            .put("station_city", "").put("station_iota", "").put("station_sota", "")
            .put("station_wwff", "").put("station_pota", "")
            .put("station_sig", "").put("station_sig_info", "").put("station_cnty", "")
        val corps = org.json.JSONArray().put(o).toString()
        runCatching { poste(base, "api/create_station/" + cle.trim(), corps) }
            .getOrElse { "échec : " + it.javaClass.simpleName }
    }

    /**
     * Le profil est-il en place ?
     *
     * « dupe » compte comme un succès : il existait déjà, ce qui est le
     * résultat voulu quand on rejoue une création.
     */
    fun profilEnPlace(reponse: String): Boolean {
        val r = reponse.lowercase()
        return r.contains("\"success\"") || r.contains("\"dupe\"")
    }

    /**
     * Le serveur a-t-il pris le contact ?
     *
     * Les installations ne répondent pas toutes la même chose : les unes un
     * JSON avec « created », les autres une phrase. On reconnaît donc ce qui
     * marque l'acceptation, et **on refuse tout le reste** — un envoi dont on
     * n'est pas sûr ne doit pas être marqué comme déposé, faute de quoi le
     * contact ne repartira jamais.
     */
    /** Ce que le serveur a fait du contact. */
    enum class Issue { PRIS, REFUS, DOUTE }

    /**
     * Pris, refusé, ou on ne sait pas — **trois issues, et non deux**.
     *
     * Une lecture qui expire n'est pas un refus : la requête est partie, le
     * serveur l'a peut-être traitée, et c'est sa réponse qui s'est perdue.
     * C'est ce qui est arrivé le 29 août — délai dépassé annoncé, contact
     * pourtant enregistré dans Wavelog.
     *
     * Confondre les deux coûte des deux côtés. Compter le doute pour un refus
     * fait redéposer ce qui est déjà pris, et surtout **abandonner le lot** au
     * premier serveur un peu lent. Le compter pour un succès ferait perdre le
     * contact quand la requête n'est réellement pas passée.
     *
     * On ne marque donc comme déposé que ce qui est acquitté, on n'arrête que
     * sur un refus franc, et le doute se dit pour ce qu'il est.
     */
    fun issue(reponse: String): Issue {
        val r = reponse.lowercase()
        if (r.contains("timed out") || r.contains("timeout") ||
            r.contains("aucune réponse") || r.startsWith("échec") ||
            r.contains("connection reset")) return Issue.DOUTE
        if (r.startsWith("http ") || r.contains("\"error\"") ||
            r.contains("auth error")) return Issue.REFUS
        if (r.contains("created") || r.contains("\"status\":\"ok\"") ||
            r.contains("qso added") || r.contains("success")) return Issue.PRIS
        // Le serveur a répondu quelque chose, et l'on ne sait pas quoi : ce
        // n'est pas davantage un refus qu'un succès.
        return Issue.DOUTE
    }

    /** Le contact est-il acquitté, sans ambiguïté ? */
    fun accepte(reponse: String): Boolean = issue(reponse) == Issue.PRIS

    private fun lis(reponse: String): Etat? = runCatching {
        val o = JSONObject(reponse)
        when (o.optString("result").lowercase()) {
            "confirmed" -> Etat.CONFIRME
            "workedbefore", "worked" -> Etat.TRAVAILLE
            "nope", "no", "notworked" -> Etat.JAMAIS
            else -> null
        }
    }.getOrNull()

    /**
     * Envoie la requête, en essayant les deux formes d'adresse.
     *
     * La documentation donne « base/api/qso », mais ses propres exemples en
     * ligne de commande écrivent « base/index.php/api/qso » : selon la façon
     * dont le serveur réécrit les adresses, l'une des deux échoue — souvent
     * par une erreur 500 plutôt qu'un franc 404, ce qui n'aide personne. On
     * essaie donc la forme courte, puis l'autre, et l'on retient celle qui
     * répond.
     */
    @Volatile private var prefixe: String? = null

    private fun poste(base: String, route: String, corps: String): String {
        val racine = base.trim().trimEnd('/')
        val formes = prefixe?.let { listOf(it) } ?: listOf("", "index.php/")
        var derniere = ""
        for (f in formes) {
            val r = runCatching { envoie("$racine/$f$route", corps) }.getOrNull()
            if (r != null && r.second in 200..299) { prefixe = f; return r.first }
            if (r != null) derniere = "HTTP ${r.second}"
        }
        return derniere.ifBlank { "aucune réponse" }
    }

    /**
     * Rapatrie l'ADIF du carnet en ligne pour nourrir le clavier.
     *
     * Wavelog rend un JSON qui **enveloppe** l'ADIF, avec deux champs qui
     * comptent autant que lui : le nombre de contacts et l'identifiant du
     * dernier exporté. Ce dernier est la clé du chargement différentiel — on
     * le garde, et l'appel suivant repart de là. Le point d'entrée est conçu
     * pour cela, et la documentation demande de ne pas ratisser tout le
     * journal à chaque fois : les instances limitent le débit.
     *
     * Une clé **en lecture seule suffit** ici, contrairement au dépôt. C'est
     * l'occasion d'en employer une autre que celle qui écrit.
     *
     * On ne rapatrie que l'index de prédiction — indicatif, carré, date,
     * satellite. Ramener les contacts entiers donnerait une seconde source de
     * vérité à réconcilier avec le carnet local, pour aucun bénéfice.
     */
    data class Moisson(val adif: String, val nombre: Int, val dernierId: Long,
                       val message: String)

    suspend fun moissonne(
        base: String, cle: String, profils: List<String>, depuisId: Long,
        filtre: String = fr.f4ioz.satcombo.domain.FiltreMoisson.SAT,
    ): Moisson = withContext(Dispatchers.IO) {
        val corps = JSONObject()
            .put("key", cle.trim())
            .put("fetchfromid", depuisId)
            .apply {
                // Depuis la 2.5.1 le point d'entrée accepte un tableau de
                // profils : les sept emplacements d'un rover en un seul appel.
                // Un profil unique reste accepté seul, pour les versions
                // antérieures.
                if (profils.size == 1) put("station_id", profils.first())
                else put("station_id", org.json.JSONArray(profils))
            }
            .apply {
                // Le serveur ne sait trier que la bande satellite : son filtre
                // n'accepte qu'une bande, et aucun mode. Pour les autres choix
                // on rapatrie tout et l'on trie ici — donc bien plus lourd, et
                // l'écran doit le dire.
                fr.f4ioz.satcombo.domain.FiltreMoisson.bandeServeur(filtre)
                    ?.let { put("band", it) }
            }
            .toString()
        val reponse = poste(base, "api/get_contacts_adif", corps)
        val o = runCatching { JSONObject(reponse) }.getOrElse {
            return@withContext Moisson("", 0, depuisId, reponse.take(200))
        }
        Moisson(
            adif = o.optString("adif"),
            nombre = o.optInt("exported_qsos"),
            // Sans identifiant rendu, on **garde le précédent** : repartir de
            // zéro rechargerait tout le journal au prochain appel, ce que le
            // serveur nous demande précisément d'éviter.
            dernierId = o.optLong("lastfetchedid", depuisId),
            message = o.optString("message"))
    }

    /** Une lecture simple, en essayant les deux formes d'adresse. */
    private fun lit(base: String, route: String): String {
        val racine = base.trim().trimEnd('/')
        val formes = prefixe?.let { listOf(it) } ?: listOf("", "index.php/")
        var derniere = ""
        for (f in formes) {
            val r = runCatching { demande("$racine/$f$route") }.getOrNull()
            if (r != null && r.second in 200..299) { prefixe = f; return r.first }
            if (r != null) derniere = "HTTP ${r.second}"
        }
        return derniere.ifBlank { "aucune réponse" }
    }

    private fun demande(adresse: String): Pair<String, Int> {
        val co = URL(adresse).openConnection() as HttpURLConnection
        co.requestMethod = "GET"
        co.connectTimeout = 8000
        co.readTimeout = 90000
        co.setRequestProperty("Accept", "application/json")
        co.setRequestProperty("User-Agent", "SatMe (f4ioz.fr)")
        val code = co.responseCode
        val flux = if (code in 200..299) co.inputStream else co.errorStream
        return (flux?.bufferedReader()?.use { it.readText() } ?: "") to code
    }

    private fun envoie(adresse: String, corps: String): Pair<String, Int> {
        val url = URL(adresse)
        val co = url.openConnection() as HttpURLConnection
        co.requestMethod = "POST"
        co.connectTimeout = 8000
        co.readTimeout = 90000
        co.doOutput = true
        co.setRequestProperty("Content-Type", "application/json")
        // La documentation demande explicitement cet en-tête : sans lui,
        // certaines installations rendent du HTML et se plaignent ensuite.
        co.setRequestProperty("Accept", "application/json")
        co.setRequestProperty("User-Agent", "SatMe (f4ioz.fr)")
        co.outputStream.use { it.write(corps.toByteArray()) }
        val code = co.responseCode
        val flux = if (code in 200..299) co.inputStream else co.errorStream
        return (flux?.bufferedReader()?.use { it.readText() } ?: "") to code
    }
}
