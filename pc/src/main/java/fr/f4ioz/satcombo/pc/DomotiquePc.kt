/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.demo.ModulesStation
import fr.f4ioz.satcombo.plateforme.Stockage
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import kotlin.concurrent.thread

/**
 * « Home Assistant » (module off by default): the station's state published
 * to the owner's MQTT broker, with Home Assistant's MQTT discovery so its
 * entities appear by themselves — next pass and AOS, satellites in view,
 * auto-pilot, last SSTV picture, DATV lock / MER / station, beacon, rotator
 * position, last QSO —, switches for the safe actions only
 * ([Intention.ACTIONS_SURES], when the owner allows them) and a « Commande »
 * text entity read by [CommandesPc]. From Home Assistant, Alexa or Google
 * Home reach those entities (docs/assistant-domotique.fr.md).
 *
 * SatMe-PC connects out to the broker; nothing is opened on this PC. The
 * password is kept in the settings, never given back to the page nor
 * written in a log. No switch, no command can transmit: [CommandesPc]
 * refuses whatever is not a safe action.
 */
class DomotiquePc(
    private val stockage: Stockage,
    private val commandes: CommandesPc,
    private val horloge: () -> Long = System::currentTimeMillis,
    private val module: () -> Boolean = { ModulesStation.actif(stockage, ModulesStation.DOMOTIQUE) },
    /** SatMe-PC's version, for the device shown in Home Assistant. */
    private val version: String = versionPc(),
    /** The connection (the tests' broker is in-process). */
    private val fabrique: (DomotiquePc) -> MqttPc = { it.client() },
) {
    // ------------------------------------------------------------------ settings

    var on: Boolean
        get() = stockage.getBoolean("ha_on", false)
        set(v) { stockage.edit().putBoolean("ha_on", v).apply(); reveille() }
    var hote: String
        get() = stockage.getString("ha_hote", "") ?: ""
        set(v) { stockage.edit().putString("ha_hote", v.trim()).apply(); reconnecte() }
    var port: Int
        get() = stockage.getInt("ha_port", 1883)
        set(v) { stockage.edit().putInt("ha_port", v.coerceIn(1, 65_535)).apply(); reconnecte() }
    var tls: Boolean
        get() = stockage.getBoolean("ha_tls", false)
        set(v) { stockage.edit().putBoolean("ha_tls", v).apply(); reconnecte() }
    var utilisateur: String
        get() = stockage.getString("ha_utilisateur", "") ?: ""
        set(v) { stockage.edit().putString("ha_utilisateur", v.trim()).apply(); reconnecte() }
    /** Kept here, never given back (only whether there is one). */
    private var motDePasse: String
        get() = stockage.getString("ha_mdp", "") ?: ""
        set(v) { stockage.edit().putString("ha_mdp", v).apply(); reconnecte() }
    /** The topics' root (`satme` → `satme/etat`…), also the device's id in Home Assistant. */
    var racine: String
        get() = (stockage.getString("ha_racine", "satme") ?: "satme").ifBlank { "satme" }
        set(v) { val n = v.trim().lowercase().replace(Regex("[^a-z0-9_-]"), "").ifBlank { "satme" }; if (n != racine) { retireDecouverte(); stockage.edit().putString("ha_racine", n).apply(); reconnecte() } }
    /** Home Assistant's discovery prefix. */
    var prefixe: String
        get() = (stockage.getString("ha_prefixe", "homeassistant") ?: "homeassistant").ifBlank { "homeassistant" }
        set(v) { stockage.edit().putString("ha_prefixe", v.trim().trim('/').ifBlank { "homeassistant" }).apply(); reconnecte() }
    /** The switches and the « Commande » text may act (safe actions only); off: state only. */
    var actions: Boolean
        get() = stockage.getBoolean("ha_actions", false)
        set(v) { stockage.edit().putBoolean("ha_actions", v).apply(); runCatching { publieDecouverte() } }

    val actif: Boolean get() = runCatching { module() }.getOrDefault(false)
    private val voulu: Boolean get() = actif && on && hote.isNotBlank()

    // ------------------------------------------------------------------ connection

    @Volatile private var mqtt: MqttPc? = null
    @Volatile var etat = "coupé"; private set
    @Volatile var erreur = ""; private set
    @Volatile var connecteDepuis = 0L; private set
    @Volatile var dernierEnvoi = 0L; private set
    @Volatile private var dernierEtat = ""
    private val verrou = Object()
    @Volatile private var fil: Thread? = null

    internal fun client(): MqttPc = MqttPc(hote, port, "satme-" + racine, utilisateur, motDePasse, tls,
        testament = "$racine/disponible" to "offline")

    fun demarre() {
        if (fil?.isAlive == true) return
        fil = thread(isDaemon = true, name = "satme-domotique") { boucle() }
    }

    private fun reveille() = synchronized(verrou) { verrou.notifyAll() }

    /** A setting of the connection changed: the next turn connects again with it. */
    private fun reconnecte() { mqtt?.let { runCatching { it.deconnecte() } }; mqtt = null; reveille() }

    @Volatile private var fini = false

    private fun boucle() {
        var attente = 5_000L
        while (!fini) {
            try {
                if (!voulu) {
                    mqtt?.let { m -> runCatching { m.publie("$racine/disponible", "offline", true) }; runCatching { m.deconnecte() } }
                    mqtt = null
                    etat = "coupé"
                    synchronized(verrou) { verrou.wait(5_000L) }
                    continue
                }
                if (mqtt?.connecte != true) {
                    etat = "connexion"
                    val m = fabrique(this)
                    m.perdu = { r -> erreur = r; etat = "perdu" ; reveille() }
                    m.recu = { t, p -> runCatching { recoit(t, p) } }
                    m.connecte()
                    mqtt = m
                    connecteDepuis = horloge()
                    erreur = ""; etat = "connecté"; attente = 5_000L
                    m.abonne("$racine/commande/#", "$prefixe/status")
                    publieDecouverte()
                    m.publie("$racine/disponible", "online", true)
                    dernierEtat = ""
                }
                publieEtat()
                synchronized(verrou) { verrou.wait(PERIODE_MS) }
            } catch (e: InterruptedException) { return
            } catch (e: Exception) {
                erreur = (e.message ?: e.toString()).take(200); etat = "erreur"
                mqtt?.let { runCatching { it.deconnecte() } }; mqtt = null
                synchronized(verrou) { verrou.wait(attente) }
                attente = (attente * 2).coerceAtMost(300_000L)
            }
        }
    }

    // ------------------------------------------------------------------ what Home Assistant sees

    private val appareil: JSONObject get() = JSONObject().put("identifiers", JSONArray().put("satme_$racine")).put("name", "SatMe")
        .put("manufacturer", "SatMe").put("model", "SatMe-PC").put("sw_version", version)

    /** One entity: its component, id, name, and the rest of its discovery config. */
    private class Entite(val composant: String, val id: String, val nom: String, val reste: JSONObject.() -> Unit = {})

    private fun capteurs(): List<Entite> = listOf(
        Entite("sensor", "prochain_passage", "Prochain passage") { put("value_template", "{{ value_json.prochain_passage }}"); put("icon", "mdi:satellite-variant") },
        Entite("sensor", "prochain_aos", "Prochain AOS") { put("value_template", "{{ value_json.prochain_aos }}"); put("device_class", "timestamp") },
        Entite("sensor", "visibles", "Satellites visibles") { put("value_template", "{{ value_json.visibles }}"); put("state_class", "measurement"); put("icon", "mdi:satellite-uplink") },
        Entite("sensor", "autopilote", "Auto-pilote SSTV") { put("value_template", "{{ value_json.autopilote }}"); put("icon", "mdi:robot") },
        Entite("sensor", "derniere_sstv", "Dernière image SSTV") { put("value_template", "{{ value_json.derniere_sstv }}"); put("icon", "mdi:image") },
        Entite("sensor", "derniere_sstv_heure", "Heure de la dernière image SSTV") { put("value_template", "{{ value_json.derniere_sstv_heure }}"); put("device_class", "timestamp") },
        Entite("binary_sensor", "datv_verrou", "DATV verrouillée") { put("value_template", "{{ value_json.datv_verrou }}"); put("payload_on", "ON"); put("payload_off", "OFF") },
        Entite("sensor", "datv_mer", "DATV MER") { put("value_template", "{{ value_json.datv_mer }}"); put("unit_of_measurement", "dB"); put("state_class", "measurement") },
        Entite("sensor", "datv_station", "DATV station") { put("value_template", "{{ value_json.datv_station }}"); put("icon", "mdi:television") },
        Entite("sensor", "balise", "Balise QO-100") { put("value_template", "{{ value_json.balise }}"); put("unit_of_measurement", "dB"); put("state_class", "measurement") },
        Entite("sensor", "rotor_azimut", "Rotor azimut") { put("value_template", "{{ value_json.rotor_azimut }}"); put("unit_of_measurement", "°") },
        Entite("sensor", "rotor_elevation", "Rotor élévation") { put("value_template", "{{ value_json.rotor_elevation }}"); put("unit_of_measurement", "°") },
        Entite("sensor", "dernier_qso", "Dernier QSO") { put("value_template", "{{ value_json.dernier_qso }}"); put("icon", "mdi:account-voice") },
        Entite("sensor", "reponse", "Réponse de SatMe") { put("value_template", "{{ value_json.reponse }}"); put("icon", "mdi:message-reply-text") },
    )

    /** The switches: each a safe action's pair (on / off). */
    private val INTERRUPTEURS = listOf(
        Triple("autopilote", "Auto-pilote SSTV", Intention.AUTOPILOTE_ON to Intention.AUTOPILOTE_OFF),
        Triple("sstv", "Réception SSTV", Intention.SSTV_ON to Intention.SSTV_OFF),
        Triple("datv", "Réception DATV", Intention.DATV_ON to Intention.DATV_OFF),
        Triple("son_muet", "Son coupé", Intention.SON_MUET to Intention.SON_REMET),
    )

    private fun actionsEntites(): List<Entite> = INTERRUPTEURS.map { (id, nom, _) ->
        Entite("switch", id, nom) { put("value_template", "{{ value_json.${if (id == "autopilote") "autopilote_on" else id} }}"); put("command_topic", "$racine/commande/$id")
            put("payload_on", "ON"); put("payload_off", "OFF"); put("state_on", "ON"); put("state_off", "OFF") }
    } + Entite("text", "commande", "Commande SatMe") { put("command_topic", "$racine/commande/texte"); put("max", 255); put("mode", "text")
        put("value_template", "{{ value_json.derniere_commande }}") }

    private fun config(e: Entite): JSONObject = JSONObject().put("name", e.nom).put("unique_id", "${racine}_${e.id}").put("object_id", "${racine}_${e.id}")
        .put("state_topic", "$racine/etat").put("availability_topic", "$racine/disponible").put("device", appareil).apply(e.reste)

    private fun topicConfig(e: Entite) = "$prefixe/${e.composant}/$racine/${e.id}/config"

    /** Every entity's config (retained); the actions' removed (an empty config) while they are not allowed. */
    fun publieDecouverte() {
        val m = mqtt ?: return
        capteurs().forEach { m.publie(topicConfig(it), config(it).toString(), true) }
        actionsEntites().forEach { e -> m.publie(topicConfig(e), if (actions) config(e).toString() else "", true) }
    }

    private fun retireDecouverte() {
        val m = mqtt ?: return
        (capteurs() + actionsEntites()).forEach { runCatching { m.publie(topicConfig(it), "", true) } }
    }

    @Volatile private var derniereReponse = ""
    @Volatile private var derniereCommande = ""

    /** The state as one JSON (each entity takes its field). */
    fun etatJson(): JSONObject {
        val o = JSONObject()
        val r = { i: Intention -> commandes.repond(i) }
        val pp = r(Intention.PROCHAIN_PASSAGE)
        o.put("prochain_passage", pp.fr.take(250))
        val aos = r(Intention.PROCHAIN_AOS)
        o.put("prochain_aos", aos.aos?.let { Instant.ofEpochMilli(it).toString() } ?: "None")
        o.put("prochain_aos_sat", aos.satellite?.first.orEmpty())
        val vis = commandes.visiblesMaintenant()
        o.put("visibles", vis.size).put("visibles_liste", vis.joinToString(", ") { it.first })
        o.put("autopilote", r(Intention.AUTOPILOTE_ETAT).fr.take(250))
        o.put("derniere_sstv", r(Intention.DERNIERE_SSTV).fr.take(250))
        val m = commandes.monde()
        o.put("derniere_sstv_heure", m.derniereSstv()?.second?.let { Instant.ofEpochMilli(it).toString() } ?: "None")
        val d = runCatching { m.datv() }.getOrNull()
        o.put("datv_verrou", if (d?.actif == true && d.verrouille) "ON" else "OFF")
        o.put("datv_mer", d?.takeIf { it.actif && it.verrouille }?.merDb?.let { Math.round(it * 10) / 10.0 } ?: "None")
        o.put("datv_station", d?.takeIf { it.actif && it.verrouille }?.let { it.indicatif.ifBlank { it.service } }.orEmpty())
        val b = runCatching { m.balise() }.getOrNull()
        o.put("balise", b?.takeIf { it.ecoutee && it.rapportDb != null && horloge() - it.quandMs < 120_000L }?.rapportDb?.let { Math.round(it * 10) / 10.0 } ?: "None")
        val ro = runCatching { m.rotor() }.getOrNull()?.takeIf { it.connecte }
        o.put("rotor_azimut", ro?.azDeg?.let { Math.round(it) } ?: "None")
        o.put("rotor_elevation", ro?.takeIf { !it.azSeul }?.elDeg?.let { Math.round(it) } ?: "None")
        o.put("dernier_qso", r(Intention.DERNIER_QSO).fr.take(250))
        o.put("reponse", derniereReponse.take(250)).put("derniere_commande", derniereCommande.take(250))
        // The switches' states.
        o.put("sstv", if (m.sstvActif()) "ON" else "OFF")
        o.put("datv", if (d?.actif == true) "ON" else "OFF")
        o.put("son_muet", if (m.muet()) "ON" else "OFF")
        // The auto-pilot's switch reads its own field (the sensor above is a sentence).
        o.put("autopilote_on", if (runCatching { m.autopilote()?.maitre }.getOrNull() == true) "ON" else "OFF")
        return o
    }

    /** The state sent when it changed, else once every [PERIODE_MS]. */
    fun publieEtat(force: Boolean = false) {
        val m = mqtt ?: return
        val e = etatJson().toString()
        if (!force && e == dernierEtat && horloge() - dernierEnvoi < PERIODE_MS - 500) return
        m.publie("$racine/etat", e, true)
        dernierEtat = e; dernierEnvoi = horloge()
    }

    /** A message from Home Assistant: a switch, the « Commande » text, or Home Assistant back online. */
    internal fun recoit(topic: String, texte: String) {
        if (topic == "$prefixe/status") { if (texte == "online") { publieDecouverte(); publieEtat(true) }; return }
        val quoi = topic.removePrefix("$racine/commande/").takeIf { it != topic } ?: return
        val reponse = when (quoi) {
            "texte" -> { derniereCommande = texte.take(255); commandes.phrase(texte.take(255), SOURCE, actions) }
            "intention" -> commandes.intention(texte.trim(), SOURCE, actions = actions)
            else -> {
                val (_, _, paire) = INTERRUPTEURS.firstOrNull { it.first == quoi } ?: return
                val i = when (texte.trim().uppercase()) { "ON" -> paire.first; "OFF" -> paire.second; else -> return }
                commandes.intention(i.id, SOURCE, actions = actions)
            }
        }
        derniereReponse = reponse.fr
        publieEtat(true)
    }

    // ------------------------------------------------------------------ the page

    fun json(): JSONObject = JSONObject().put("module", actif).put("on", on).put("hote", hote).put("port", port).put("tls", tls)
        .put("utilisateur", utilisateur).put("motDePasse", motDePasse.isNotEmpty()).put("racine", racine).put("prefixe", prefixe)
        .put("actions", actions).put("etat", etat).put("erreur", erreur).put("depuis", connecteDepuis).put("envoi", dernierEnvoi)

    /** The page's settings ([p]: a form's field; the password only when given, « sansMdp » forgets it). */
    fun regle(p: (String) -> String): JSONObject {
        if (!actif) return JSONObject().put("ok", false).put("erreur", MODULE_COUPE)
        p("hote").takeIf { it.isNotEmpty() }?.let { if (it != hote) hote = it }
        p("port").toIntOrNull()?.let { if (it != port) port = it }
        p("tls").takeIf { it.isNotEmpty() }?.let { if ((it == "1") != tls) tls = it == "1" }
        if (p("utilisateurDonne") == "1") p("utilisateur").let { if (it != utilisateur) utilisateur = it }
        p("mdp").takeIf { it.isNotEmpty() }?.let { motDePasse = it }
        if (p("sansMdp") == "1") motDePasse = ""
        p("racine").takeIf { it.isNotEmpty() }?.let { racine = it }
        p("prefixe").takeIf { it.isNotEmpty() }?.let { if (it != prefixe) prefixe = it }
        p("actions").takeIf { it.isNotEmpty() }?.let { actions = it == "1" }
        p("on").takeIf { it.isNotEmpty() }?.let { on = it == "1" }
        return JSONObject().put("ok", true)
    }

    /** SatMe-PC closing: « offline » said, the connection closed, the loop ended. */
    fun arrete() {
        fini = true
        reveille()
        mqtt?.let { m -> runCatching { m.publie("$racine/disponible", "offline", true) }; runCatching { m.deconnecte() } }
        mqtt = null
    }

    companion object {
        const val SOURCE = "domotique"
        const val PERIODE_MS = 30_000L
        const val MODULE_COUPE = "Module « Home Assistant » désactivé — l'activer dans Réglages › Modules"
    }
}
