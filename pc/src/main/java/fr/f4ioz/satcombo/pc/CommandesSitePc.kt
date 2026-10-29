/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.plateforme.Stockage
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * « Publication web › Commandes à distance »: your site (an Alexa skill, a
 * page of yours) asks the station questions and a few safe actions, without
 * ever connecting to it. SatMe-PC, outbound only, through the same address
 * and token as the publications ([SitePc.envoieDirect]):
 *
 * - « Questions » (off by default): every minute, `commandes-etat` — every
 *   question's answer ready-made in French and English, the next pass of
 *   the favourites and a few common satellites (with keys to match a name
 *   said), the actions accepted now;
 * - « Actions » (off by default): `commandes-attente`, a request the site
 *   holds up to 25 s and answers with the commands waiting; each is carried
 *   out through [CommandesPc] (safe actions only: anything else, a
 *   transmission above all, is refused) and its result sent back as
 *   `commandes-resultat`.
 *
 * Documented in docs/publication-web(.fr).md. Nothing goes while the
 * « Publication web » module is off or its address is not tested.
 */
class CommandesSitePc(
    private val stockage: Stockage,
    private val site: SitePc,
    private val commandes: CommandesPc,
    private val horloge: () -> Long = System::currentTimeMillis,
    /** The satellites asked about besides the favourites (the one followed, the campaigns'). */
    private val autres: () -> Set<Int> = { emptySet() },
) {
    var questions: Boolean
        get() = stockage.getBoolean("site_cmd_questions", false)
        set(v) { stockage.edit().putBoolean("site_cmd_questions", v).apply(); prochainEtat = 0L; reveille() }
    var actions: Boolean
        get() = stockage.getBoolean("site_cmd_actions", false)
        set(v) { stockage.edit().putBoolean("site_cmd_actions", v).apply(); prochainEtat = 0L; reveille() }

    /** The site can be reached: the module on, a web address tested. */
    private val possible: Boolean get() = runCatching { site.fluxPossible }.getOrDefault(false)

    @Volatile var dernierEtatMs = 0L; private set
    @Volatile var derniereAttenteMs = 0L; private set
    @Volatile var erreur = ""; private set
    @Volatile private var prochainEtat = 0L
    private val verrou = Object()
    private val faites = ArrayDeque<String>()

    private fun reveille() = synchronized(verrou) { verrou.notifyAll() }

    fun demarre() {
        thread(isDaemon = true, name = "satme-site-commandes-etat") { boucleEtat() }
        thread(isDaemon = true, name = "satme-site-commandes") { boucleActions() }
    }

    /** The state body sent to the site ([actions] lists what it may queue). */
    fun etat(): JSONObject = JSONObject().put("t", horloge())
        .put("reponses", commandes.reponses())
        .put("passages", commandes.passagesConnus(runCatching { autres() }.getOrDefault(emptySet())))
        .put("actions", JSONArray(if (actions) Intention.ACTIONS_SURES.map { it.id } else emptyList()))

    /** One push of the state, now. Its HTTP status, or null when it could not go. */
    fun envoieEtat(): Int? {
        if (!possible || (!questions && !actions)) return null
        val r = site.envoieDirect(TYPE_ETAT, etat()) ?: return null
        dernierEtatMs = horloge()
        erreur = if (r.first in 200..299) "" else "état refusé par le site (HTTP ${r.first})"
        return r.first
    }

    private fun boucleEtat() {
        while (true) {
            try {
                if (horloge() >= prochainEtat) {
                    runCatching { envoieEtat() }.onFailure { erreur = "site injoignable : " + (it.message ?: it.toString()).take(160) }
                    prochainEtat = horloge() + PERIODE_ETAT_MS
                }
                synchronized(verrou) { verrou.wait(5_000L) }
            } catch (e: InterruptedException) { return }
        }
    }

    private fun boucleActions() {
        var attente = 5_000L
        while (true) {
            try {
                if (!possible || !actions) { synchronized(verrou) { verrou.wait(10_000L) }; continue }
                val ok = attendUneFois()
                attente = if (ok) 1_000L else (attente * 2).coerceAtMost(300_000L)
                if (!ok) synchronized(verrou) { verrou.wait(attente) }
            } catch (e: InterruptedException) { return
            } catch (e: Exception) {
                erreur = "site injoignable : " + (e.message ?: e.toString()).take(160)
                synchronized(verrou) { verrou.wait(attente) }
                attente = (attente * 2).coerceAtMost(300_000L)
            }
        }
    }

    /** One long poll: the commands the site holds, carried out, their results sent. False when the site refused or failed. */
    fun attendUneFois(): Boolean {
        val r = site.envoieDirect(TYPE_ATTENTE, JSONObject().put("attente", ATTENTE_S)) ?: return false
        derniereAttenteMs = horloge()
        if (r.first == 404 || r.first == 400 || r.first == 403) { erreur = "le site ne prend pas les commandes (HTTP ${r.first})"; return false }
        if (r.first !in 200..299) { erreur = "HTTP ${r.first}"; return false }
        erreur = ""
        val l = runCatching { JSONObject(r.second).optJSONArray("commandes") }.getOrNull() ?: return true
        var change = false
        for (k in 0 until l.length()) {
            val c = l.optJSONObject(k) ?: continue
            val id = c.optString("id").take(80)
            if (id.isEmpty() || synchronized(faites) { id in faites }) continue
            synchronized(faites) { faites.addLast(id); while (faites.size > 200) faites.removeFirst() }
            val rep = execute(c)
            change = change || rep.ok
            runCatching { site.envoieDirect(TYPE_RESULTAT, JSONObject().put("id", id).put("ok", rep.ok).put("fr", rep.fr).put("en", rep.en)) }
        }
        // A change done: the state again at once (the site answers the next question from it).
        if (change) { prochainEtat = 0L; reveille() }
        return true
    }

    /** A command from the site: a safe action's id only (a sentence, if one comes, through the same filter). */
    internal fun execute(c: JSONObject): CommandesPc.Reponse {
        val i = Intention.de(c.optString("intention"))
        if (i != null && i !in Intention.ACTIONS_SURES)
            return commandes.intention(i.id, SOURCE, c.optString("satellite").ifBlank { null }, actions = false)
        if (i != null) return commandes.intention(i.id, SOURCE, actions = actions)
        val texte = c.optString("texte").take(255)
        if (texte.isNotBlank()) return commandes.phrase(texte, SOURCE, actions)
        return commandes.intention(c.optString("intention").take(40), SOURCE, actions = false)
    }

    fun json(): JSONObject = JSONObject().put("questions", questions).put("actions", actions).put("possible", possible)
        .put("etat", dernierEtatMs).put("attente", derniereAttenteMs).put("erreur", erreur)

    fun regle(p: (String) -> String): JSONObject {
        p("questions").takeIf { it.isNotEmpty() }?.let { questions = it == "1" }
        p("actions").takeIf { it.isNotEmpty() }?.let { actions = it == "1" }
        return JSONObject().put("ok", true)
    }

    companion object {
        const val SOURCE = "site"
        const val TYPE_ETAT = "commandes-etat"
        const val TYPE_ATTENTE = "commandes-attente"
        const val TYPE_RESULTAT = "commandes-resultat"
        const val PERIODE_ETAT_MS = 60_000L
        const val ATTENTE_S = 25
    }
}
