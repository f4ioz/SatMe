/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.demo.RequeteHttp
import fr.f4ioz.satcombo.plateforme.Stockage
import org.json.JSONObject
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * **SatMe-Node**: SatMe on a small computer next to the antenna (a Raspberry
 * Pi), the MiniTiouner plugged into it, driven from SatMe-PC or the phone
 * over the network (`satme --noeud`).
 *
 * The node only receives: LongMynd there ([DatvPc] without players), its
 * status and its transport stream sent to the clients. Nothing is decoded on
 * it, which is why a Raspberry Pi 3 is enough. Or an ADALM-PLUTO / a
 * bladeRF plugged into it and SatMe's DVB-S2 demodulator ([MaterielNoeud]):
 * that one wants a Raspberry Pi 4 or 5, the same stream going out.
 *
 * **A code, then a key.** At its first start the node draws a six-digit
 * code, said in its log (`journalctl -u satme-noeud`) and kept. A client
 * gives it once and gets a key, kept on both sides: the node can restart,
 * the client stays joined. Without a key, the node only says what it is.
 */
class NoeudPc(
    private val datv: DatvPc,
    private val stockage: Stockage,
    private val port: Int,
    val nom: String = runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrDefault("satme-noeud"),
    /** Its SDR lent to SatMe-PC as a receiver, when it has one ([SdrNoeudPc]). */
    val sdr: SdrNoeudPc? = null,
    /** Its transmitter driven from SatMe-PC ([EmissionNoeudPc]), behind its own guards. */
    val tx: EmissionNoeudPc? = null,
    /** Its health ([SanteNoeudPc]), for SatMe-PC's « Node » panel. */
    val sante: SanteNoeudPc? = null,
    /** Run by systemd, which starts it again when it ends in error: « relancer » allowed. */
    private val relancable: () -> Boolean = { SanteNoeudPc.sousSystemd() },
    /** Ends the process for systemd to start it again (never in the tests). */
    private val quitte: () -> Unit = { Runtime.getRuntime().halt(3) },
) {
    private var socket: ServerSocket? = null
    val portReel: Int get() = socket?.localPort ?: port

    /** The code to give a client, drawn once and kept. */
    val code: String = stockage.getString(CLE_CODE, null)?.takeIf { it.length == 6 }
        ?: (1..6).map { "0123456789".random() }.joinToString("").also { stockage.edit().putString(CLE_CODE, it).apply() }

    private fun cles(): Set<String> = stockage.getStringSet(CLE_CLES, emptySet()).orEmpty()

    fun demarre() {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(port))
        socket = s
        thread(isDaemon = true, name = "satme-noeud") {
            while (!s.isClosed) {
                val c = runCatching { s.accept() }.getOrNull() ?: continue
                thread(isDaemon = true, name = "satme-noeud-client") { runCatching { sert(c) }; runCatching { c.close() } }
            }
        }
    }

    fun arrete() { runCatching { socket?.close() } }

    private fun sert(c: Socket) {
        c.soTimeout = 15_000
        val r = RequeteHttp.lit(c.getInputStream()) ?: return
        val chemin = r.chemin.substringBefore('?')
        val q = r.chemin.substringAfter('?', "").split('&').mapNotNull {
            val i = it.indexOf('='); if (i <= 0) null else it.substring(0, i) to java.net.URLDecoder.decode(it.substring(i + 1), "UTF-8")
        }.toMap()
        val o = c.getOutputStream()
        when (chemin) {
            // What it is, for anyone: nothing more.
            "/noeud/api" -> json(o, JSONObject().put("noeud", true).put("nom", nom)
                .put("services", org.json.JSONArray(listOfNotNull("datv", sdr?.let { "sdr" }, tx?.let { "tx" })))
                .put("longmynd", datv.programme() != null).put("recepteurs", org.json.JSONArray(fr.f4ioz.satcombo.datv.Datv.RECEPTEURS))
                .put("appareils", appareils()))
            // The node's own settings: from the node itself only (its loopback), never over the network.
            "/noeud/reglages" -> {
                if (r.methode != "POST") { envoie(o, "405 Method Not Allowed", "text/plain", ByteArray(0)); return }
                if (c.inetAddress?.isLoopbackAddress != true) {
                    envoie(o, "403 Forbidden", "application/json; charset=utf-8", JSONObject().put("ok", false).put("erreur", SUR_PLACE).toString().toByteArray())
                    return
                }
                q["sdr"]?.let { a -> if (sdr?.etat?.actif != true) sdr?.appareil = a }
                val e = tx?.regleLocal(true, q["autorise"]?.let { it == "1" }, q["tx"], q["gainMax"]?.replace(',', '.')?.toDoubleOrNull())
                json(o, if (e == null) JSONObject().put("ok", true).put("appareils", appareils()) else JSONObject().put("ok", false).put("erreur", e))
            }
            "/noeud/entrer" -> {
                if (q["code"]?.trim() == code) {
                    val cle = (1..24).map { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")
                    stockage.edit().putStringSet(CLE_CLES, (cles() + cle).toList().takeLast(20).toSet()).apply()
                    json(o, JSONObject().put("ok", true).put("cle", cle))
                } else {
                    // A second per wrong code: guessing a million of them is not worth it.
                    Thread.sleep(1000)
                    json(o, JSONObject().put("ok", false))
                }
            }
            "/" -> page(o, c.inetAddress?.isLoopbackAddress == true)
            else -> {
                if (q["cle"].isNullOrBlank() || q["cle"] !in cles()) { envoie(o, "403 Forbidden", "text/plain", "clé".toByteArray()); return }
                route(chemin, q, r.methode, o, c)
            }
        }
    }

    private fun route(chemin: String, q: Map<String, String>, methode: String, o: OutputStream, c: Socket) {
        when (chemin) {
            "/noeud/datv" -> json(o, JsonPc.datv(datv))
            "/noeud/datv/demarre" -> {
                if (methode != "POST") { envoie(o, "405 Method Not Allowed", "text/plain", ByteArray(0)); return }
                fun khz(k: String) = q[k]?.replace(',', '.')?.toDoubleOrNull()?.let { Math.round(it * 1000.0) }
                val r0 = datv.reglage
                val r = r0.copy(cielKhz = khz("ciel") ?: r0.cielKhz, srKs = q["sr"]?.toIntOrNull() ?: r0.srKs, olKhz = khz("ol") ?: r0.olKhz,
                    entreeB = q["entree"]?.let { it == "b" } ?: r0.entreeB, lnb = q["lnb"]?.takeIf { it in setOf("", "v", "h") } ?: "", son = false)
                // The receiver asked (an older client asks none: the MiniTiouner).
                datv.recepteur = q["recepteur"]?.takeIf { it in fr.f4ioz.satcombo.datv.Datv.RECEPTEURS } ?: fr.f4ioz.satcombo.datv.Datv.MINITIOUNER
                val e = datv.demarre(r)
                json(o, if (e == null) JSONObject().put("ok", true) else JSONObject().put("ok", false).put("erreur", e))
            }
            "/noeud/datv/arrete" -> { datv.arrete(); json(o, JSONObject().put("ok", true)) }
            // The node's devices and what each does now.
            "/noeud/materiel" -> json(o, appareils().put("sdrEtat", sdr?.json() ?: JSONObject.NULL).put("txEtat", tx?.json() ?: JSONObject.NULL))
            // Its SDR as a receiver: one connection, I/Q one way, tuning the other ([SdrNoeudPc]).
            "/noeud/sdr/flux" -> sdr?.sert(c, q, o) ?: envoie(o, "404 Not Found", "text/plain", "non".toByteArray())
            // Which device is its SDR (receive only, harmless): from SatMe-PC with the key too.
            "/noeud/sdr/appareil" -> {
                val s = sdr ?: return envoie(o, "404 Not Found", "text/plain", "non".toByteArray())
                if (methode != "POST") { envoie(o, "405 Method Not Allowed", "text/plain", ByteArray(0)); return }
                val a = q["appareil"].orEmpty()
                json(o, when {
                    a !in SdrNoeudPc.APPAREILS -> JSONObject().put("ok", false).put("erreur", "appareil inconnu")
                    s.etat.actif && a != s.appareil -> JSONObject().put("ok", false).put("erreur", "le SDR du node est en service : l'arrêter d'abord")
                    else -> { s.appareil = a; JSONObject().put("ok", true).put("appareils", appareils()) }
                })
            }
            // Its transmitter: a POST, behind « émission autorisée » and the watchdog ([EmissionNoeudPc]).
            "/noeud/tx/flux" -> {
                val t = tx ?: return envoie(o, "404 Not Found", "text/plain", "non".toByteArray())
                if (methode != "POST") { envoie(o, "405 Method Not Allowed", "text/plain", ByteArray(0)); return }
                t.sert(c, q, o)
            }
            "/noeud/tx/arrete" -> { tx?.arrete(); json(o, JSONObject().put("ok", true)) }
            // Its health: system, network, boards, services, last log lines (read only).
            "/noeud/sante" -> json(o, sante?.json() ?: JSONObject().put("ok", false))
            // A service stopped, or the node's SatMe started again (by systemd); never the OS.
            "/noeud/action" -> {
                if (methode != "POST") { envoie(o, "405 Method Not Allowed", "text/plain", ByteArray(0)); return }
                json(o, action(q["quoi"].orEmpty()))
            }
            "/noeud/datv/ts" -> {
                // The stream as it comes, for as long as the client reads it.
                c.soTimeout = 0
                o.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
                o.flush()
                val file = datv.abonne()
                try {
                    while (true) {
                        val bloc = file.poll(2, TimeUnit.SECONDS) ?: run { if (!datv.etat.actif) return; null } ?: continue
                        o.write(bloc)
                        if (file.isEmpty()) o.flush()
                    }
                } catch (_: Exception) {
                } finally { datv.desabonne(file) }
            }
            else -> envoie(o, "404 Not Found", "text/plain", "non".toByteArray())
        }
    }

    /** One supervision action from SatMe-PC: stops only, or SatMe started again by systemd. */
    fun action(quoi: String): JSONObject {
        println("Action demandée : $quoi")
        when (quoi) {
            "datv-arrete" -> datv.arrete()
            "sdr-arrete" -> sdr?.arrete()
            "tx-arrete" -> tx?.arrete("arrêt demandé depuis SatMe-PC (supervision)")
            "relance" -> {
                if (!relancable()) return JSONObject().put("ok", false).put("erreur", "SatMe-Node lancé à la main (pas par son service systemd) : rien pour le relancer")
                tx?.arrete("SatMe-Node relancé")
                runCatching { datv.arrete() }
                Thread({ Thread.sleep(600); quitte() }, "satme-noeud-relance").apply { isDaemon = true; start() }
                return JSONObject().put("ok", true).put("message", "SatMe-Node se relance (quelques secondes)")
            }
            else -> return JSONObject().put("ok", false).put("erreur", "action inconnue")
        }
        return JSONObject().put("ok", true)
    }

    /** The node's devices and their roles: DATV receiver, SDR receiver, transmitter. */
    fun appareils(): JSONObject {
        val s = sdr; val t = tx
        return JSONObject().put("datv", datv.recepteur).put("datvActif", datv.etat.actif)
            .put("sdr", s?.appareil ?: "").put("sdrActif", s?.etat?.actif == true).put("sdrClient", s?.etat?.client ?: "")
            .put("sdrLibelle", s?.etat?.libelle ?: "")
            .put("tx", t?.appareil ?: "").put("txAutorise", t?.autorisee == true).put("txEmet", t?.etat?.emet == true)
            .put("txGainMax", t?.gainMaxDb ?: JSONObject.NULL)
    }

    /**
     * A page for whoever opens the node in a browser: what it is, its devices
     * and their roles; never its code. Opened on the node itself ([local]),
     * its settings can be changed there — « émission autorisée » only so.
     */
    private fun page(o: OutputStream, local: Boolean) {
        val e = datv.etat
        val s = sdr?.etat
        val t = tx
        fun ligne(role: String, app: String, texte: String) =
            "<tr><td style=\"color:#9AA7B4;padding-right:16px\">$role</td><td><b>${esc(app)}</b></td><td style=\"padding-left:16px\">$texte</td></tr>"
        val datvTexte = (if (e.actif) esc(e.libelle) + (e.merDb?.let { " · MER %.1f dB".format(it) } ?: "") else "à l'arrêt") +
            (if (datv.programme() == null && datv.recepteur == fr.f4ioz.satcombo.datv.Datv.MINITIOUNER) " — <b>LongMynd introuvable</b>" else "")
        val sdrTexte = when {
            s == null -> "—"
            s.actif -> "envoie à " + esc(s.client) + " · " + String.format(java.util.Locale.FRANCE, "%.3f", s.centreHz / 1e6) + " MHz"
            s.erreur.isNotBlank() -> "à l'arrêt — " + esc(s.erreur)
            else -> "à l'arrêt"
        }
        val txTexte = when {
            t == null -> "—"
            t.etat.emet -> "<b style=\"color:#FF6B6B\">ÉMET</b> " + String.format(java.util.Locale.FRANCE, "%.3f", t.etat.frequenceHz / 1e6) + " MHz pour " + esc(t.etat.client)
            !t.autorisee -> "émission <b>non autorisée</b>"
            else -> "émission autorisée · à l'arrêt"
        }
        fun choix(champ: String, v: String) = "<select name=\"$champ\">" + SdrNoeudPc.APPAREILS.joinToString("") { a ->
            "<option value=\"$a\"" + (if (a == v) " selected" else "") + ">" + SdrNoeudPc.nomAppareil(a) + "</option>" } + "</select>"
        val reglages = if (!local) "<p style=\"color:#9AA7B4\">Les réglages du node (son SDR, son émetteur, « émission autorisée ») se changent " +
            "<b>sur le node lui-même</b> : ouvrir cette page sur le Raspberry Pi, ou par un tunnel <code>ssh -L 8091:localhost:$portReel pi@node</code> " +
            "puis <code>http://localhost:8091</code>.</p>"
        else """<form id="f" style="background:#121A23;padding:12px 16px;border-radius:8px;max-width:560px">
<p><b>Réglages du node</b> (page ouverte sur le node : modifiable)</p>
<p>SDR prêté à SatMe-PC : ${choix("sdr", sdr?.appareil ?: SdrNoeudPc.AUCUN)}</p>
<p>Émetteur : ${choix("tx", t?.appareil ?: SdrNoeudPc.AUCUN)} · plafond <input name="gainMax" size="5" value="${t?.gainMaxDb ?: -10.0}"> dB</p>
<p><label><input type="checkbox" name="autorise"${if (t?.autorisee == true) " checked" else ""}> <b>émission autorisée sur ce node</b></label>
<br><small style="color:#9AA7B4">Décoché : rien n'émet, et une émission en cours s'arrête aussitôt.</small></p>
<button type="submit">Enregistrer</button> <span id="m"></span></form>
<script>document.getElementById('f').onsubmit = async ev => { ev.preventDefault(); const f = ev.target;
const q = 'sdr=' + f.sdr.value + '&tx=' + f.tx.value + '&gainMax=' + encodeURIComponent(f.gainMax.value) + '&autorise=' + (f.autorise.checked ? '1' : '0');
const r = await fetch('/noeud/reglages?' + q, { method: 'POST' }); const j = await r.json();
document.getElementById('m').textContent = j.ok ? 'enregistré' : j.erreur; };</script>"""
        val h = """<!doctype html><html lang="fr"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>SatMe-Node</title><body style="font-family:system-ui,sans-serif;background:#0B1016;color:#E8F0F7;padding:24px;line-height:1.6">
<h2>SatMe-Node · ${esc(nom)}</h2>
<table>${ligne("Réception DATV", fr.f4ioz.satcombo.datv.Datv.libelleRecepteur(datv.recepteur), datvTexte)}
${if (sdr != null) ligne("SDR (réception)", SdrNoeudPc.nomAppareil(sdr.appareil), sdrTexte) else ""}
${if (t != null) ligne("Émission", SdrNoeudPc.nomAppareil(t.appareil), txTexte) else ""}</table>
$reglages
<p style="color:#9AA7B4">Se pilote depuis SatMe-PC (onglet DATV, « SatMe-Node » ; Station › SatMe-Node) ou le téléphone, avec le code donné au démarrage du node
(<code>journalctl -u satme-noeud</code>).</p></body></html>"""
        envoie(o, "200 OK", "text/html; charset=utf-8", h.toByteArray())
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;")

    private fun json(o: OutputStream, j: JSONObject) = envoie(o, "200 OK", "application/json; charset=utf-8", j.toString().toByteArray())

    private fun envoie(o: OutputStream, statut: String, type: String, corps: ByteArray) {
        o.write("HTTP/1.1 $statut\r\nContent-Type: $type\r\nContent-Length: ${corps.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
        o.write(corps); o.flush()
    }

    companion object {
        private const val CLE_CODE = "noeud_code"
        private const val CLE_CLES = "noeud_cles"
        const val SUR_PLACE = "ce réglage se change sur le node lui-même (sa page ouverte sur le node, ou par un tunnel SSH)"
    }
}
