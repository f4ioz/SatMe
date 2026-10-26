/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.datv.ChatLarge
import fr.f4ioz.satcombo.datv.LimiteDebit
import fr.f4ioz.satcombo.datv.SocketIo
import fr.f4ioz.satcombo.plateforme.Stockage
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * The QO-100 wide band chat (BATC's ground station page), in the DATV tab.
 * Off unless chosen; connected only while the option is on and the tab
 * asked within the last [VU_MS], again after a cut with a growing wait.
 *
 * Read only until the operator sends: the protocol's own packets (joining,
 * pongs) go, nothing else. A message goes only from [envoie] (the page's
 * « Envoyer »), the nick (the station's callsign) set just before the first
 * one of each connection; without a callsign, nothing can be sent.
 */
class ChatLargePc(
    private val stockage: Stockage,
    private val indicatif: () -> String,
    private val horloge: () -> Long = System::currentTimeMillis,
    private val ouvre: () -> Connexion = {
        val c = ClientWebSocketPc.ouvre(HOTE, 443, CHEMIN, "", SpectreLargePc.AGENT, delaiLectureMs = 60_000)
        Connexion({ c.recoit()?.toString(Charsets.UTF_8) }, { c.envoieTexte(it) }, { c.fermePoliment() })
    },
) {
    /** One connection: [recoit] blocks (null once closed), [envoie] a text frame. */
    class Connexion(val recoit: () -> String?, val envoie: (String) -> Unit, val ferme: () -> Unit)

    val chat = ChatLarge()

    var on: Boolean
        get() = stockage.getBoolean("datv_chat", false)
        set(v) { stockage.edit().putBoolean("datv_chat", v).apply(); if (!v) courant?.let { runCatching { it.ferme() } } }

    @Volatile private var vuMs = 0L
    @Volatile private var courant: Connexion? = null
    @Volatile private var joint = false
    @Volatile private var nickPose: String? = null
    @Volatile var etat = ""; private set
    @Volatile private var prochainEssai = 0L
    @Volatile private var delai = DELAI_MIN_MS
    private val limite = LimiteDebit(3_000L, 10)

    fun voulu(): Boolean = on && horloge() - vuMs < VU_MS

    fun demarre() {
        thread(isDaemon = true, name = "satme-datv-chat") {
            while (true) { runCatching { boucle() }; Thread.sleep(500) }
        }
        // The connection dropped soon after the tab is closed or the option turned off.
        thread(isDaemon = true, name = "satme-datv-chat-garde") {
            while (true) { Thread.sleep(1000); if (!voulu()) courant?.let { runCatching { it.ferme() } } }
        }
    }

    /** One connection, read while wanted. */
    fun boucle() {
        if (!voulu()) { etat = if (on) "en attente de l’onglet DATV" else ""; return }
        if (horloge() < prochainEssai) return
        etat = "connexion…"
        val c = runCatching { ouvre() }.getOrElse { echec(it.message ?: it.javaClass.simpleName); return }
        courant = c; joint = false; nickPose = null
        var evenements = 0
        try {
            while (voulu()) {
                val t = c.recoit() ?: break
                when (val p = SocketIo.lis(t)) {
                    is SocketIo.Paquet.Ouverture -> c.envoie(SocketIo.CONNEXION)
                    SocketIo.Paquet.Ping -> c.envoie(SocketIo.PONG)
                    SocketIo.Paquet.Connecte -> { joint = true; etat = "connecté" }
                    is SocketIo.Paquet.Refus -> { etat = "refusé : " + p.pourquoi; break }
                    SocketIo.Paquet.Deconnecte, SocketIo.Paquet.Fermeture -> break
                    is SocketIo.Paquet.Evenement -> if (chat.recoit(p.nom, p.donnees)) evenements++
                    SocketIo.Paquet.Autre -> {}
                }
            }
        } catch (e: Exception) {
            if (voulu()) etat = "coupé : " + (e.message ?: e.javaClass.simpleName)
        } finally {
            runCatching { c.ferme() }
            courant = null; joint = false; nickPose = null
        }
        if (evenements > 0) delai = DELAI_MIN_MS
        if (voulu()) echec(if (evenements == 0) "rien reçu" else "connexion fermée")
        else etat = if (on) "en attente de l’onglet DATV" else ""
    }

    private fun echec(pourquoi: String) {
        prochainEssai = horloge() + delai
        etat = "indisponible (" + pourquoi + "), nouvel essai dans " + delai / 1000 + " s"
        delai = minOf(delai * 2, DELAI_MAX_MS)
    }

    /** The operator's message, sent now (the nick first when needed); null when sent, else why not. */
    fun envoie(texte: String): String? {
        ModeDemo.refus("l'envoi dans le salon (indicatif fictif)")?.let { return it }
        val nick = ChatLarge.nick(indicatif()) ?: return "indicatif de la station manquant (Réglages) : le salon reste en lecture seule"
        val (t, pourquoi) = ChatLarge.texte(texte)
        if (t == null) return pourquoi
        val c = courant?.takeIf { joint } ?: return "salon non connecté"
        if (!limite.accepte(horloge())) return "un message toutes les 3 s au plus"
        return runCatching {
            if (nickPose != nick) { c.envoie(SocketIo.evenement("setnick", JSONObject().put("nick", nick))); nickPose = nick }
            c.envoie(SocketIo.evenement("message", JSONObject().put("message", t)))
            null
        }.getOrElse { "envoi impossible : " + (it.message ?: "") }
    }

    fun json(depuis: Long): JSONObject {
        vuMs = horloge()
        val nick = ChatLarge.nick(indicatif())
        return chat.json(depuis).put("ok", true).put("on", on).put("etat", etat).put("connecte", joint)
            .put("nick", nick ?: JSONObject.NULL).put("lectureSeule", nick == null)
    }

    fun action(chemin: String, q: Map<String, String>): JSONObject {
        when (chemin) {
            "/api/datv/chat/reglage" -> q["on"]?.let { on = it == "1"; if (on) { vuMs = horloge(); prochainEssai = 0L; delai = DELAI_MIN_MS } }
            "/api/datv/chat/envoie" -> envoie(q["texte"].orEmpty())?.let { return JSONObject().put("ok", false).put("erreur", it) }
            else -> return JSONObject().put("ok", false).put("erreur", "inconnu")
        }
        return JSONObject().put("ok", true)
    }

    companion object {
        const val HOTE = "eshail.batc.org.uk"
        val CHEMIN = "/wb/chat/socket.io/?room=" + ChatLarge.ROOM + "&EIO=4&transport=websocket"
        const val VU_MS = 10_000L
        const val DELAI_MIN_MS = 3_000L
        const val DELAI_MAX_MS = 120_000L
    }
}
