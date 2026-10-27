/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site.pages

import fr.f4ioz.satcombo.location.Maidenhead
import fr.f4ioz.site.EntreeMenu
import fr.f4ioz.site.Groupe
import fr.f4ioz.site.ModuleSite
import fr.f4ioz.site.Site
import fr.f4ioz.site.http.Reponse
import fr.f4ioz.site.http.Requete
import fr.f4ioz.site.http.Routeur
import fr.f4ioz.site.vue.Html.h
import fr.f4ioz.site.vue.Page
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Grid: a locator (Maidenhead, SatMe's own reading) and its coordinates,
 * either way; between two places, the distance and the bearing both ways
 * (short and long path). A plain form: it works without JavaScript; « Ma
 * position » asks the browser, nothing leaves it but the locator typed.
 */
class PageGrid(private val s: Site) : ModuleSite {

    override val menu = listOf(EntreeMenu(Groupe.SATELLITES, 70, "/grid", "Grid", "grid", "Locator ↔ coordonnées, distance"))
    override fun plan() = listOf("/grid" to 0.5)

    override fun installe(r: Routeur) { r.get("/grid") { q -> page(q) } }

    /** A place from what was typed: a locator (2 to 6 characters… 4 or 6 read), or « lat, lon ». */
    data class Lieu(val texte: String, val lat: Double, val lon: Double, val locator: String, val exact: Boolean)

    private fun page(q: Requete): Reponse {
        val a = q.param("a")?.trim()?.take(40).orEmpty()
        val b = q.param("b")?.trim()?.take(40).orEmpty()
        val la = lieu(a.ifEmpty { s.config.locator })
        val lb = if (b.isNotEmpty()) lieu(b) else null
        val sb = StringBuilder("<header class=\"page-head\"><h1>Grid</h1><p>Locator Maidenhead ↔ coordonnées, distance et azimut entre deux stations (orthodromie, Terre sphérique de 6 371 km).</p></header>")
        sb.append("<form class=\"card grid-form\" method=\"get\" action=\"/grid\"><div class=\"card-body\">")
            .append("<label>Station A <input name=\"a\" id=\"grid-a\" value=\"").append(h(a.ifEmpty { s.config.locator })).append("\" placeholder=\"JN18FS ou 48.85, 2.35\" maxlength=\"40\" autocapitalize=\"characters\"></label>")
            .append("<label>Station B <small>(facultatif)</small> <input name=\"b\" value=\"").append(h(b)).append("\" placeholder=\"IO91WM\" maxlength=\"40\" autocapitalize=\"characters\"></label>")
            .append("<button class=\"btn solid\" type=\"submit\">Calculer</button> <button class=\"btn\" type=\"button\" id=\"grid-pos\" hidden>Ma position</button>")
            .append("<p class=\"dim\" id=\"grid-etat\"></p></div></form>")
        fun fiche(nom: String, l: Lieu?, texte: String) {
            sb.append("<section class=\"card\"><header class=\"card-head\"><h2 class=\"card-title\">").append(h(nom)).append("</h2></header><div class=\"card-body\">")
            if (l == null) { sb.append("<p class=\"card-empty\">« ").append(h(texte)).append(" » n'est ni un locator ni des coordonnées.</p></div></section>"); return }
            val bornes = Maidenhead.bounds(l.locator.take(6))
            sb.append("<dl class=\"item-meta\"><dt>Locator</dt><dd class=\"mono big\">").append(h(l.locator)).append("</dd>")
                .append("<dt>").append(if (l.exact) "Position" else "Centre du carré").append("</dt><dd class=\"mono\">").append(coord(l.lat, l.lon)).append("</dd>")
            if (bornes != null) sb.append("<dt>Carré ").append(h(l.locator.take(6))).append("</dt><dd class=\"mono\">")
                .append(String.format(Locale.FRANCE, "%.4f° à %.4f° N · %.4f° à %.4f° E", bornes[0], bornes[0] + bornes[2], bornes[1], bornes[1] + bornes[3])).append("</dd>")
            sb.append("</dl></div></section>")
        }
        sb.append("<div class=\"grid-res\">")
        fiche("Station A", la, a)
        if (b.isNotEmpty()) fiche("Station B", lb, b)
        sb.append("</div>")
        if (la != null && lb != null) {
            val d = distanceKm(la.lat, la.lon, lb.lat, lb.lon)
            val azAB = azimut(la.lat, la.lon, lb.lat, lb.lon); val azBA = azimut(lb.lat, lb.lon, la.lat, la.lon)
            sb.append("<section class=\"card\"><header class=\"card-head\"><h2 class=\"card-title\">De A à B</h2></header><div class=\"card-body\"><dl class=\"item-meta\">")
                .append("<dt>Distance</dt><dd class=\"mono big\">").append(String.format(Locale.FRANCE, "%,.0f km", d)).append("</dd>")
                .append("<dt>Azimut A → B</dt><dd class=\"mono\">").append(String.format(Locale.FRANCE, "%.1f° (%s)", azAB, cardinal(azAB))).append("</dd>")
                .append("<dt>Azimut B → A</dt><dd class=\"mono\">").append(String.format(Locale.FRANCE, "%.1f° (%s)", azBA, cardinal(azBA))).append("</dd>")
                .append("<dt>Chemin long</dt><dd class=\"mono\">").append(String.format(Locale.FRANCE, "%,.0f km · %.1f°", 2 * Math.PI * 6371.0 - d, (azAB + 180) % 360)).append("</dd>")
                .append("</dl></div></section>")
        }
        val js = """(function(){var b=document.getElementById('grid-pos');if(!b||!navigator.geolocation)return;b.hidden=false;
b.onclick=function(){var e=document.getElementById('grid-etat');e.textContent='Position demandée au navigateur…';
navigator.geolocation.getCurrentPosition(function(p){var la=p.coords.latitude,lo=p.coords.longitude;
document.getElementById('grid-a').value=la.toFixed(5)+', '+lo.toFixed(5);e.textContent='';b.form.submit();},
function(){e.textContent='Position refusée ou indisponible.';},{enableHighAccuracy:false,timeout:10000});};})();"""
        return s.gabarit.reponse(q, Page("Grid — locator et distance", "Convertisseur de locator Maidenhead ↔ latitude/longitude, distance et azimut entre deux stations.",
            sb.toString(), "/grid", robots = if (q.requete.isEmpty()) "index, follow" else "noindex, follow", scriptEnLigne = js)).cache("public, max-age=300")
    }

    companion object {
        private val LATLON = Regex("^\\s*(-?\\d{1,2}(?:[.,]\\d+)?)\\s*[,; ]\\s*(-?\\d{1,3}(?:[.,]\\d+)?)\\s*$")

        fun lieu(t: String): Lieu? {
            val u = t.trim().uppercase()
            Maidenhead.toLatLon(u)?.let { (la, lo) -> return Lieu(t, la, lo, u, false) }
            if (u.length == 2 && u[0] in 'A'..'R' && u[1] in 'A'..'R') Maidenhead.bounds(u)?.let { b -> return Lieu(t, b[0] + 5, b[1] + 10, u, false) }
            val m = LATLON.find(t.replace(", ", ";").replace(",", ".").replace(";", ",")) ?: LATLON.find(t) ?: return null
            val la = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
            val lo = m.groupValues[2].replace(',', '.').toDoubleOrNull() ?: return null
            if (la !in -90.0..90.0 || lo !in -180.0..180.0) return null
            return Lieu(t, la, lo, Maidenhead.fromLatLon(la, lo), true)
        }

        fun distanceKm(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
            val r = Math.PI / 180
            val a = sin((la2 - la1) * r / 2).let { it * it } + cos(la1 * r) * cos(la2 * r) * sin((lo2 - lo1) * r / 2).let { it * it }
            return 2 * 6371.0 * atan2(sqrt(a), sqrt(1 - a))
        }

        fun azimut(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
            val r = Math.PI / 180
            val y = sin((lo2 - lo1) * r) * cos(la2 * r)
            val x = cos(la1 * r) * sin(la2 * r) - sin(la1 * r) * cos(la2 * r) * cos((lo2 - lo1) * r)
            return (Math.toDegrees(atan2(y, x)) + 360) % 360
        }

        fun cardinal(a: Double) = listOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSO", "SO", "OSO", "O", "ONO", "NO", "NNO")[((a / 22.5) + 0.5).toInt() % 16]

        private fun coord(la: Double, lo: Double) = String.format(Locale.FRANCE, "%.5f° %s, %.5f° %s", kotlin.math.abs(la), if (la >= 0) "N" else "S", kotlin.math.abs(lo), if (lo >= 0) "E" else "O")
    }
}
