/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site.station

import fr.f4ioz.site.EntreeMenu
import fr.f4ioz.site.Groupe
import fr.f4ioz.site.ModuleSite
import fr.f4ioz.site.Site
import fr.f4ioz.site.http.Reponse
import fr.f4ioz.site.http.Requete
import fr.f4ioz.site.http.Routeur
import fr.f4ioz.site.vue.Html.h
import fr.f4ioz.site.vue.Page
import org.json.JSONObject

/**
 * Station: the station's SDR (a Brick2, openHPSDR Protocol 1) heard live —
 * spectrum, waterfall, audio — **for its operator only** (the admin
 * session), and only when the site runs on the station's LAN and the
 * configuration says `station = true` (off by default). Receive only:
 * nothing here can transmit (see [HpsdrProtocole]). Visitors see a short
 * page saying it is reserved; nothing about the LAN (address, MAC) is ever
 * shown to them.
 */
class PageStation(private val s: Site) : ModuleSite {

    private val c = s.config
    private val actif = c.station

    val session: HpsdrSession by lazy {
        HpsdrSession(c.stationHote, c.stationPort, trouve = { decouverte().takeIf { it.enLigne }?.ip })
    }
    private var ws: ServeurWs? = null

    @Volatile private var derniere: Pair<Long, HpsdrDecouverte.Radio>? = null

    /** The radio found on the LAN, asked again every 8 s at most. */
    @Synchronized fun decouverte(): HpsdrDecouverte.Radio {
        derniere?.takeIf { System.currentTimeMillis() - it.first < 8_000L }?.let { return it.second }
        val r = HpsdrDecouverte.cherche(c.stationHote, c.stationPort, c.stationDiffusion)
        derniere = System.currentTimeMillis() to r
        return r
    }

    override val menu = listOf(EntreeMenu(Groupe.STATION, 10, "/station", "Station", "station", "Le récepteur SDR de la station (privé)"))
    override fun plan() = listOf("/station" to 0.3)

    override fun installe(r: Routeur) {
        r.get("/station") { q -> page(q) }
        r.get("/api/sdr/status") { q -> statut(q) }
    }

    override fun demarre() {
        if (!actif || c.stationWsPort <= 0) return
        val origines = setOf(c.urlBaseNette)
        ws = ServeurWs(c.ecoute, c.stationWsPort, session, s.admin::sessionValide, origines).also {
            runCatching { it.demarre() }.onFailure { e -> println("station : WebSocket non ouvert (${e.message})") }
        }
    }

    private fun statut(q: Requete): Reponse {
        if (!actif) return s.pageErreur(q, 404)
        if (!s.admin.estAdmin(q)) return Reponse.erreur(403, "réservé à l'opérateur")
        val d = decouverte()
        val o = JSONObject().put("online", d.enLigne).put("in_use", d.occupee).put("board", d.carte).put("firmware", d.firmware)
            .put("ip", d.ip).put("mac", d.mac).put("session", session.enMarche).put("error", d.erreur.ifEmpty { session.erreur.orEmpty() })
        return Reponse.json(o).cache("private, no-store")
    }

    private fun page(q: Requete): Reponse {
        val admin = s.admin.estAdmin(q)
        val sb = StringBuilder("<header class=\"page-head\"><h1>Station</h1><p>Le récepteur SDR de la station : un Brick2 (classe Hermes / ANAN-10E, 1,8–60 MHz, " +
            "openHPSDR), relié au réseau local. Spectre, waterfall et écoute en direct, <b>en réception seulement</b> : l'émission reste conduite sur place par l'opérateur.</p></header>")
        when {
            !actif || !admin -> {
                sb.append("<section class=\"card\"><div class=\"card-body\"><p class=\"card-empty\">")
                    .append(if (!actif) "Le récepteur n'est pas relié à ce site pour le moment." else "Réservé à l'opérateur de la station : le panadapter prend la main sur la radio pendant l'écoute.")
                    .append("</p></div></section>")
                if (c.stationWebSdr.isNotEmpty()) sb.append("<p class=\"actions\"><a class=\"btn solid\" href=\"").append(h(c.stationWebSdr)).append("\" rel=\"noopener\">Écouter la station (WebSDR)</a></p>")
            }
            else -> sb.append(panneau())
        }
        val p = Page("Station — récepteur SDR", "Le récepteur SDR HF de la station F4IOZ (réception seulement).", sb.toString(), "/station",
            robots = if (admin) "noindex, nofollow" else "index, follow", styles = listOf("css/hf.css"),
            scripts = if (actif && admin) listOf("js/station.js") else emptyList(), classeCorps = "page-hf")
        return s.gabarit.reponse(q, p).cache(if (admin) "private, no-store" else "public, max-age=300")
    }

    private fun panneau(): String = """<section class="card"><header class="card-head"><h2 class="card-title">Radio</h2><span class="card-meta" id="sdr-maj">—</span></header>
<div class="card-body"><div class="sdr-etat"><span class="sdr-led" id="sdr-led"></span><b id="sdr-etat">Interrogation…</b></div><dl class="item-meta" id="sdr-grille"></dl></div></section>
<section class="card" id="panadapter"><header class="card-head"><h2 class="card-title">Panadapter</h2><span class="card-meta" id="pan-status" role="status">arrêté</span></header><div class="card-body">
<div class="hf-filtres"><button type="button" class="btn solid" id="pan-toggle">Démarrer</button>
<span class="hf-puces" id="pan-bands">${listOf("160m", "80m", "40m", "30m", "20m", "17m", "15m", "12m", "10m").joinToString("") { "<button type=\"button\" class=\"puce\" data-band=\"$it\">${it.removeSuffix("m")}</button>" }}</span>
<label>f <input type="number" id="pan-freq" step="0.001" min="0.1" max="60" value="7.100"> MHz</label>
<label>Largeur <select id="pan-rate"><option value="48000">48 kHz</option><option value="96000">96 kHz</option><option value="192000">192 kHz</option><option value="384000">384 kHz</option></select></label></div>
<div class="hf-filtres"><button type="button" class="btn" id="pan-audio" aria-pressed="false">Audio coupé</button>
<label>Mode <select id="pan-mode"><option>USB</option><option>LSB</option><option>CW</option><option>AM</option><option>FM</option></select></label>
<label>Volume <input type="range" id="pan-vol" min="0" max="1" step="0.02" value="0.6"></label>
<label><input type="checkbox" id="mk-band" checked> plan IARU</label><label><input type="checkbox" id="mk-beacon" checked> balises</label><label><input type="checkbox" id="mk-dx" checked> spots</label></div>
<div class="pan-lecture"><span>centre <b id="pan-center">—</b></span><span>largeur <b id="pan-span">—</b></span><span id="pan-edges">—</span><span class="pan-smetre"><i id="pan-smeter-bar"></i></span><b id="pan-smeter-txt">S—</b></div>
<canvas id="pan-canvas" class="pan-canvas" aria-label="Panadapter : spectre et waterfall"></canvas>
<div class="hf-filtres"><label>Luminosité <input type="range" id="pan-bright" min="-0.5" max="0.5" step="0.02" value="0"></label><label>Contraste <input type="range" id="pan-contrast" min="0.5" max="2.5" step="0.05" value="1"></label><label>Moyennage <input type="range" id="pan-avg" min="0" max="0.92" step="0.04" value="0.4"></label></div>
<div class="hf-filtres"><button type="button" class="btn" id="pan-mem-save">Mémoriser</button><span class="hf-puces" id="pan-mem-list"></span></div>
<p class="hf-aide"><b>Réception seulement</b>, jamais d'émission. Le panadapter <b>prend la main sur la radio</b> pendant l'écoute (un seul client openHPSDR) : l'arrêter libère le SDR pour Thetis ou piHPSDR. Deux onglets ouverts pilotent la même radio (le dernier réglage gagne). Un clic sur le spectre accorde ; on entend le trait central. S-mètre relatif, non étalonné.</p>
</div></section>"""
}
