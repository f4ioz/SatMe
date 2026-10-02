/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.f4ioz.satcombo.MainViewModel
import fr.f4ioz.satcombo.UiState
import fr.f4ioz.satcombo.aprs.AprsEmission
import fr.f4ioz.satcombo.aprs.AprsHub
import fr.f4ioz.satcombo.aprs.AprsJeu
import fr.f4ioz.satcombo.aprs.Meteo
import fr.f4ioz.satcombo.aprs.Paquet
import fr.f4ioz.satcombo.aprs.Trophees
import fr.f4ioz.satcombo.aprs.TypeAprs
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.i18n.tf
import fr.f4ioz.satcombo.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private val hhmm = SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
private val jjmm = SimpleDateFormat("dd/MM/yyyy", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

/** Midnight UTC today: APRS Thursday, and "the evening", run on UTC days. */
internal fun debutDuJourUtc(maintenant: Long = System.currentTimeMillis()): Long = maintenant - maintenant % 86_400_000L

/** A weather report in one line: "🌡 12.3 °C · 💨 SW 15 km/h (gusts 30) · 💧 80 % · 1013 hPa". */
internal fun ligneMeteo(m: Meteo): String = listOfNotNull(
    m.temperatureC?.let { "🌡 %.1f °C".format(Locale.US, it) },
    m.ventKmh?.let { v ->
        "💨 " + (m.ventDeg?.takeIf { v > 0 }?.let { pointCardinal(it.toDouble()) + " " } ?: "") + "$v km/h" +
            (m.rafaleKmh?.takeIf { it > v }?.let { " (" + tf("aprs_meteo_rafales", it) + ")" } ?: "")
    },
    m.pluie1hMm?.takeIf { it > 0 }?.let { "🌧 %.1f mm/h".format(Locale.US, it) },
    m.humidite?.let { "💧 $it %" },
    m.pressionHpa?.let { "%.0f hPa".format(Locale.US, it) },
).joinToString(" · ")

private fun pointCardinal(deg: Double): String =
    listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(((deg % 360) + 382.5) / 45).toInt() % 8]

// ------------------------------------------------------------------ cheers

/** What a trophy event says on the page: an emoji, a title, a line. */
private fun texteFete(ev: Trophees.Evenement): Triple<String, String, String>? = when (ev) {
    is Trophees.Evenement.RepeteIss -> Triple("🛰", t("aprs_fete_iss"),
        if (ev.autres.isEmpty()) t("aprs_fete_iss_seul") else tf("aprs_fete_iss_autres", ev.autres.take(8).joinToString(", ")))
    is Trophees.Evenement.Contact -> Triple("🤝", tf("aprs_fete_contact", ev.contact.indicatif), t("aprs_fete_contact_texte"))
    is Trophees.Evenement.NouveauBadge -> Triple("🏅", tf("aprs_fete_badge", t("aprs_badge_" + ev.badge.cle)),
        t("aprs_badge_" + ev.badge.cle + "_desc"))
    is Trophees.Evenement.NouveauCarre -> Triple("🟩", tf("aprs_fete_carre", ev.carre), tf("aprs_fete_par", ev.indicatif))
    is Trophees.Evenement.NouveauPays -> Triple("🌍", tf("aprs_fete_pays", ev.pays), tf("aprs_fete_par", ev.indicatif))
    is Trophees.Evenement.Record -> Triple("📏", tf("aprs_fete_record", ev.km), tf("aprs_fete_par", ev.indicatif))
}

/**
 * The last cheer, at the top of the APRS page until dismissed: our frame back
 * from the ISS above all. The phone gives a little buzz with it.
 */
@Composable
internal fun BanniereFete() {
    var fete by remember { mutableStateOf<Trophees.Evenement?>(null) }
    val vue = LocalView.current
    LaunchedEffect(Unit) {
        AprsHub.evenements.collect { ev ->
            // Our frame back from the ISS outranks a new square heard a second later.
            if (fete !is Trophees.Evenement.RepeteIss || ev is Trophees.Evenement.RepeteIss) fete = ev
            if (ev is Trophees.Evenement.RepeteIss || ev is Trophees.Evenement.Contact || ev is Trophees.Evenement.NouveauBadge)
                vue.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        }
    }
    val f = fete ?: return
    val (emoji, titre, texte) = texteFete(f) ?: return
    val fort = f is Trophees.Evenement.RepeteIss || f is Trophees.Evenement.Contact
    Surface(color = (if (fort) Aurora else Amber).copy(alpha = 0.18f), shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clickable { fete = null }) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(emoji, fontSize = 30.sp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(titre, color = TextHi, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(texte, color = TextLo, fontSize = 12.sp)
            }
            Text("✕", color = TextLo, fontSize = 16.sp)
        }
    }
}

// ---------------------------------------------------------------- the map

/**
 * Stations heard on a map: the ISS ground track and where it is, a line from
 * each station heard through it to where the ISS was then, the squares heard,
 * weather stations, and us. Tapping a station picks it for the hunt below.
 */
@Composable
internal fun OngletCarte(ui: UiState, vm: MainViewModel, paquets: List<Paquet>) {
    val obs = ui.observer
    var periode by rememberSaveable { mutableStateOf("JOUR") }
    var monde by rememberSaveable { mutableStateOf(false) }
    // OpenStreetMap by default; the offline drawing needs no network.
    var fond by rememberSaveable { mutableStateOf("OSM") }
    var choisie by rememberSaveable { mutableStateOf<String?>(null) }
    val depuis = if (periode == "JOUR") debutDuJourUtc(ui.nowMs.takeIf { it > 0 } ?: System.currentTimeMillis()) else 0L
    val vus = remember(paquets, depuis) { paquets.filter { it.quand >= depuis } }
    // Our own frames heard back are drawn as a link to the ISS, not listed as a station.
    val moi = AprsJeu.base(vm.aprsSource())
    val stations = remember(vus, obs, moi) {
        AprsJeu.stations(vus, obs?.latDeg, obs?.lonDeg).filter { AprsJeu.base(it.indicatif) != moi || moi.isEmpty() }
    }
    // Where the ISS was when each station was heard through it.
    val liens = remember(vus) {
        vus.filter { it.viaIss && it.lat != null && !it.emis }.mapNotNull { p ->
            vm.aprsSousIss(p.quand)?.let { (p.lat!! to p.lon!!) to it }
        }
    }
    val minute = (ui.nowMs / 60_000L)
    val trace = remember(minute) { vm.aprsTraceIss(System.currentTimeMillis() - 15 * 60_000L) }
    val issIci = remember(ui.nowMs / 5_000L) { vm.aprsSousIss(System.currentTimeMillis()) }
    val carres = remember(stations) { stations.map { it.carre }.toSet() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("JOUR" to t("aprs_carte_jour"), "TOUT" to t("aprs_carte_7j")).forEach { (c, n) ->
                    Puce(periode == c, n) { periode = c }
                }
                Puce(!monde, t("aprs_carte_autour")) { monde = false }
                Puce(monde, t("aprs_carte_monde")) { monde = true }
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("OSM" to "OpenStreetMap", "TOPO" to "Topo", "DARK" to t("dark"), "VECTOR" to t("map_offline"))
                    .forEach { (c, n) -> Puce(fond == c, n) { fond = c } }
            }
        }
        item {
            val qth = obs?.let { it.latDeg to it.lonDeg }
            val fournisseur = MapProviders.byId(fond)
            if (fournisseur.template == null)
                CarteStations(stations, qth, trace, issIci, liens, carres, choisie, monde) { choisie = it }
            else
                CarteTuiles(fournisseur, stations, qth, trace, issIci, liens, carres, choisie, monde) { choisie = it }
        }
        item {
            Text(tf("aprs_carte_legende", stations.size, carres.size, liens.size), color = TextLo, fontSize = 11.sp)
        }
        val s = stations.firstOrNull { it.indicatif == choisie }
        if (s != null) item { CarteChasse(ui, s, paquets) { choisie = null } }
        else item { Text(t("aprs_carte_toucher"), color = TextLo, fontSize = 12.sp) }
        // The farthest first: that is what one looks for.
        items(stations.sortedByDescending { it.km ?: 0.0 }.take(40), key = { it.indicatif }) { st ->
            Surface(color = if (st.indicatif == choisie) Cyan.copy(alpha = 0.15f) else SpaceCard,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().clickable { choisie = st.indicatif }) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(st.indicatif, color = TextHi, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp, modifier = Modifier.weight(1f))
                    if (st.viaIss) Text("ISS ", color = Aurora, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    if (st.meteo != null) Text("☁ ", color = Amber, fontSize = 12.sp)
                    Text(st.carre + (st.km?.let { " · %d km".format(it.toInt()) } ?: ""), color = TextLo, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun Puce(choisie: Boolean, nom: String, onClic: () -> Unit) {
    FilterChip(selected = choisie, onClick = onClic, label = { Text(nom, fontSize = 12.sp) },
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Cyan.copy(alpha = 0.2f), selectedLabelColor = Cyan))
}

/** The part of the world shown: around what was heard, or the whole world. Spans in degrees, 2:1. */
private data class Vue(val lon0: Double, val lat1: Double, val dLon: Double, val dLat: Double)

private fun vueAutour(points: List<Pair<Double, Double>>, monde: Boolean): Vue {
    if (monde || points.isEmpty()) return Vue(-180.0, 90.0, 360.0, 180.0)
    var la0 = points.minOf { it.first }; var la1 = points.maxOf { it.first }
    var lo0 = points.minOf { it.second }; var lo1 = points.maxOf { it.second }
    val mLa = maxOf((la1 - la0) * 0.15, 1.0); val mLo = maxOf((lo1 - lo0) * 0.15, 2.0)
    la0 -= mLa; la1 += mLa; lo0 -= mLo; lo1 += mLo
    var dLon = maxOf(lo1 - lo0, 16.0); var dLat = maxOf(la1 - la0, 8.0)
    if (dLon < 2 * dLat) dLon = 2 * dLat else dLat = dLon / 2
    dLon = minOf(dLon, 360.0); dLat = minOf(dLat, 180.0)
    val cLon = (lo0 + lo1) / 2; val cLat = (la0 + la1) / 2
    val lon0 = (cLon - dLon / 2).coerceIn(-180.0, 180.0 - dLon)
    val lat1 = (cLat + dLat / 2).coerceIn(-90.0 + dLat, 90.0)
    return Vue(lon0, lat1, dLon, dLat)
}

@Composable
private fun CarteStations(
    stations: List<AprsJeu.Station>, qth: Pair<Double, Double>?, trace: List<Pair<Double, Double>>,
    iss: Pair<Double, Double>?, liens: List<Pair<Pair<Double, Double>, Pair<Double, Double>>>,
    carres: Set<String>, choisie: String?, monde: Boolean, onChoisit: (String?) -> Unit,
) {
    val dark = isDarkTheme()
    val terres = rememberLandPath()
    val points = remember(stations, qth) { stations.map { it.lat to it.lon } + listOfNotNull(qth) }
    val vue = remember(points, monde) { vueAutour(points, monde) }
    Box(Modifier.fillMaxWidth().aspectRatio(2f).clip(RoundedCornerShape(14.dp))
        .background(if (dark) Color(0xFF0B1018) else Color(0xFFDDE6F1))) {
        Canvas(Modifier.matchParentSize().pointerInput(stations, vue) {
            detectTapGestures { o ->
                val w = size.width.toDouble(); val h = size.height.toDouble()
                val proche = stations.minByOrNull { s ->
                    val x = (s.lon - vue.lon0) / vue.dLon * w; val y = (vue.lat1 - s.lat) / vue.dLat * h
                    (x - o.x) * (x - o.x) + (y - o.y) * (y - o.y)
                }
                onChoisit(proche?.indicatif)
            }
        }) {
            val w = size.width; val h = size.height
            val sx = (w / vue.dLon).toFloat(); val sy = (h / vue.dLat).toFloat()
            fun xy(lat: Double, lon: Double) = Offset(((lon - vue.lon0) * sx).toFloat(), ((vue.lat1 - lat) * sy).toFloat())
            terres?.let { p ->
                withTransform({
                    scale(sx, sy, Offset.Zero)
                    translate(-(vue.lon0 + 180).toFloat(), -(90 - vue.lat1).toFloat())
                }) {
                    drawPath(p, color = if (dark) Color(0xFF16232F) else Color(0xFFBFD2E4))
                    drawPath(p, color = if (dark) Color(0xFF2A3D4E) else Color(0xFF8FA8BF), style = Stroke(width = 1.2f / sx))
                }
            }
            // Squares heard: 2° × 1° each.
            carres.forEach { c ->
                // bounds = latMin, lonMin, latSpan, lonSpan.
                val b = fr.f4ioz.satcombo.location.Maidenhead.bounds(c) ?: return@forEach
                val a = xy(b[0] + b[2], b[1]); val z = xy(b[0], b[1] + b[3])
                drawRect(Aurora.copy(alpha = 0.12f), a, androidx.compose.ui.geometry.Size(z.x - a.x, z.y - a.y))
            }
            for (i in 1 until trace.size) {
                val (la1, lo1) = trace[i - 1]; val (la2, lo2) = trace[i]
                if (abs(lo2 - lo1) > 180.0) continue
                drawLine(Aurora.copy(alpha = 0.6f), xy(la1, lo1), xy(la2, lo2), 2f)
            }
            liens.forEach { (st, sat) -> drawLine(Aurora.copy(alpha = 0.35f), xy(st.first, st.second), xy(sat.first, sat.second), 1.5f) }
            stations.forEach { s ->
                val c = when { s.meteo != null -> Amber; s.viaIss -> Aurora; else -> Cyan }
                val p = xy(s.lat, s.lon)
                if (s.indicatif == choisie) drawCircle(Color.White, 9f, p, style = Stroke(2.5f))
                drawCircle(c, 5f, p)
            }
            iss?.let { (la, lo) ->
                val p = xy(la, lo)
                drawCircle(Amber.copy(alpha = 0.3f), 13f, p); drawCircle(Amber, 6f, p)
            }
            qth?.let { (la, lo) ->
                val p = xy(la, lo)
                drawCircle(Magenta, 6f, p)
                drawCircle(if (dark) Color.White else Color(0xFF1E293B), 6f, p, style = Stroke(1.5f))
            }
        }
    }
}

/**
 * The same layers over map tiles (OpenStreetMap, Topo, dark): Web Mercator,
 * pinch to zoom, drag to pan, tap a station. Starts framed on what was
 * heard (or the whole world); the tile source is credited on the map.
 */
@Composable
private fun CarteTuiles(
    fournisseur: MapProvider, stations: List<AprsJeu.Station>, qth: Pair<Double, Double>?,
    trace: List<Pair<Double, Double>>, iss: Pair<Double, Double>?,
    liens: List<Pair<Pair<Double, Double>, Pair<Double, Double>>>,
    carres: Set<String>, choisie: String?, monde: Boolean, onChoisit: (String?) -> Unit,
) {
    val portee = rememberCoroutineScope()
    val tuiles = remember { TileStore(portee) }
    var taille by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
    // Centre in world units (0..1 both ways) and zoom level (world = 256·2^zoom px).
    var cx by remember { mutableStateOf(0.5) }
    var cy by remember { mutableStateOf(0.5) }
    var zoom by remember { mutableStateOf(2.0) }
    fun wx(lon: Double) = (lon + 180.0) / 360.0
    fun wy(lat: Double): Double {
        val l = Math.toRadians(lat.coerceIn(-85.05, 85.05))
        return (1 - kotlin.math.ln(kotlin.math.tan(l) + 1 / kotlin.math.cos(l)) / Math.PI) / 2
    }
    val points = remember(stations, qth) { stations.map { it.lat to it.lon } + listOfNotNull(qth) }
    // Frame what was heard (or the world) once the size is known, and again when asked.
    LaunchedEffect(points.size, monde, taille) {
        if (taille.width <= 0f) return@LaunchedEffect
        if (monde || points.isEmpty()) { cx = 0.5; cy = 0.5; zoom = kotlin.math.log2(taille.width / 256.0).coerceAtLeast(0.0); return@LaunchedEffect }
        val xs = points.map { wx(it.second) }; val ys = points.map { wy(it.first) }
        val dx = (xs.max() - xs.min()).coerceAtLeast(1e-4); val dy = (ys.max() - ys.min()).coerceAtLeast(1e-4)
        cx = (xs.max() + xs.min()) / 2; cy = (ys.max() + ys.min()) / 2
        zoom = minOf(kotlin.math.log2(taille.width * 0.8 / (256 * dx)), kotlin.math.log2(taille.height * 0.8 / (256 * dy)))
            .coerceIn(1.0, 14.0)
    }
    val dark = isDarkTheme()
    Box(Modifier.fillMaxWidth().height(340.dp).clip(RoundedCornerShape(14.dp))
        .background(if (dark) Color(0xFF0B1018) else Color(0xFFDDE6F1))) {
        Canvas(Modifier.matchParentSize()
            .onSizeChanged { taille = androidx.compose.ui.geometry.Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(Unit) {
                detectTransformGestures { centre, deplacement, facteur, _ ->
                    val w = 256.0 * Math.pow(2.0, zoom)
                    // The point under the fingers stays put while zooming.
                    val px = cx + (centre.x - size.width / 2) / w
                    val py = cy + (centre.y - size.height / 2) / w
                    zoom = (zoom + kotlin.math.log2(facteur.toDouble())).coerceIn(0.0, fournisseur.maxZ.toDouble())
                    val w2 = 256.0 * Math.pow(2.0, zoom)
                    cx = (px - (centre.x - size.width / 2) / w2 - deplacement.x / w2 + 1) % 1.0
                    cy = (py - (centre.y - size.height / 2) / w2 - deplacement.y / w2).coerceIn(0.0, 1.0)
                }
            }
            .pointerInput(stations) {
                detectTapGestures { o ->
                    val w = 256.0 * Math.pow(2.0, zoom)
                    val proche = stations.minByOrNull { s ->
                        val x = (wx(s.lon) - cx) * w + size.width / 2; val y = (wy(s.lat) - cy) * w + size.height / 2
                        (x - o.x) * (x - o.x) + (y - o.y) * (y - o.y)
                    }
                    onChoisit(proche?.indicatif)
                }
            }) {
            val w = 256.0 * Math.pow(2.0, zoom)
            fun xy(lat: Double, lon: Double) = Offset(((wx(lon) - cx) * w + size.width / 2).toFloat(),
                ((wy(lat) - cy) * w + size.height / 2).toFloat())
            // Tiles at the nearest whole level, scaled to the current zoom.
            val z = kotlin.math.floor(zoom).toInt().coerceIn(0, fournisseur.maxZ)
            val n = 1 shl z
            val cote = (w / n).toFloat()
            val x0 = kotlin.math.floor((cx * w - size.width / 2) / cote).toInt()
            val x1 = kotlin.math.floor((cx * w + size.width / 2) / cote).toInt()
            val y0 = kotlin.math.floor((cy * w - size.height / 2) / cote).toInt().coerceAtLeast(0)
            val y1 = kotlin.math.floor((cy * w + size.height / 2) / cote).toInt().coerceAtMost(n - 1)
            for (tx in x0..x1) for (ty in y0..y1) {
                val o = Offset((tx * cote - cx * w + size.width / 2).toFloat(), (ty * cote - cy * w + size.height / 2).toFloat())
                val img = tuiles.get(fournisseur, z, tx, ty)
                if (img != null) drawImage(img, dstOffset = androidx.compose.ui.unit.IntOffset(o.x.toInt(), o.y.toInt()),
                    dstSize = androidx.compose.ui.unit.IntSize(cote.toInt() + 1, cote.toInt() + 1))
            }
            carres.forEach { c ->
                // bounds = latMin, lonMin, latSpan, lonSpan.
                val b = fr.f4ioz.satcombo.location.Maidenhead.bounds(c) ?: return@forEach
                val a = xy(b[0] + b[2], b[1]); val e = xy(b[0], b[1] + b[3])
                drawRect(Aurora.copy(alpha = 0.16f), a, androidx.compose.ui.geometry.Size(e.x - a.x, e.y - a.y))
            }
            for (i in 1 until trace.size) {
                val (la1, lo1) = trace[i - 1]; val (la2, lo2) = trace[i]
                if (abs(lo2 - lo1) > 180.0) continue
                drawLine(Color(0xFFE0A100), xy(la1, lo1), xy(la2, lo2), 3f)
            }
            liens.forEach { (st, sat) -> drawLine(Color(0xFF3B6FD8).copy(alpha = 0.6f), xy(st.first, st.second), xy(sat.first, sat.second), 2f) }
            val texte = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 28f; color = android.graphics.Color.BLACK; isFakeBoldText = true
                setShadowLayer(5f, 0f, 0f, android.graphics.Color.WHITE)
            }
            stations.forEach { s ->
                val c = when { s.meteo != null -> Color(0xFFE08A00); s.viaIss -> Color(0xFF3B6FD8); else -> Color(0xFF00897B) }
                val p = xy(s.lat, s.lon)
                if (p.x < -20 || p.y < -20 || p.x > size.width + 20 || p.y > size.height + 20) return@forEach
                if (s.indicatif == choisie) drawCircle(Color(0xFFE5484D), 13f, p, style = Stroke(3.5f))
                drawCircle(Color.White, 8f, p); drawCircle(c, 6f, p)
                // Names once zoomed in enough to read them.
                if (zoom >= 6 || s.indicatif == choisie) drawContext.canvas.nativeCanvas.drawText(s.indicatif, p.x + 10f, p.y - 8f, texte)
            }
            iss?.let { (la, lo) -> val p = xy(la, lo); drawCircle(Color(0xFFE0A100).copy(alpha = 0.35f), 16f, p); drawCircle(Color(0xFFE0A100), 7f, p) }
            qth?.let { (la, lo) -> val p = xy(la, lo); drawCircle(Color.White, 9f, p); drawCircle(Magenta, 7f, p) }
        }
        Text(fournisseur.attribution, color = Color(0xFF333333), fontSize = 9.sp,
            modifier = Modifier.align(Alignment.BottomEnd).background(Color.White.copy(alpha = 0.7f)).padding(horizontal = 4.dp))
    }
}

/**
 * The hunt: distance and course to the station picked, and an arrow that
 * turns with the phone held flat — walk where it points. With a handheld
 * (FT3D, TH-D72) it is a little fox hunt.
 */
@Composable
private fun CarteChasse(ui: UiState, s: AprsJeu.Station, paquets: List<Paquet>, onFerme: () -> Unit) {
    val obs = ui.observer
    val decl = remember(obs) {
        obs?.let {
            android.hardware.GeomagneticField(it.latDeg.toFloat(), it.lonDeg.toFloat(), it.altMeters.toFloat(),
                System.currentTimeMillis()).declination
        } ?: 0f
    }
    val orient by rememberDeviceOrientation(decl)
    val cap = obs?.let { fr.f4ioz.satcombo.sonde.Geo.bearingDeg(it.latDeg, it.lonDeg, s.lat, s.lon) }
    val dernier = paquets.filter { (it.nom ?: it.source) == s.indicatif }.maxByOrNull { it.quand }
    Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.indicatif, color = TextHi, fontWeight = FontWeight.Bold, fontSize = 17.sp,
                        fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                    TextButton(onClick = onFerme) { Text("✕", color = TextLo) }
                }
                Text(t("aprs_chasse_titre"), color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                s.km?.let { km ->
                    Text((if (km < 10) "%.2f km".format(Locale.US, km) else "%d km".format(km.toInt())) +
                        (cap?.let { " · %03d° %s".format(it.toInt(), pointCardinal(it)) } ?: ""),
                        color = TextHi, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                Text(s.carre + " · " + tf("aprs_chasse_entendu", hhmm.format(Date(s.dernier))), color = TextLo, fontSize = 12.sp)
                s.meteo?.let { Text(ligneMeteo(it), color = Amber, fontSize = 12.sp) }
                dernier?.commentaire?.takeIf { it.isNotBlank() }?.let { Text(it, color = TextLo, fontSize = 12.sp) }
                if (!orient.available) Text(t("aprs_chasse_sans_boussole"), color = Amber, fontSize = 11.sp)
            }
            if (cap != null && orient.available) {
                // Up = where the phone's top edge points; the arrow turns toward the station.
                val angle = ((cap - orient.azimuthDeg + 540) % 360 - 180).toFloat()
                Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.matchParentSize().rotate(angle)) {
                        val c = Offset(size.width / 2, size.height / 2)
                        val r = size.minDimension / 2
                        drawCircle(Cyan.copy(alpha = 0.12f), r, c)
                        val fleche = Path().apply {
                            moveTo(c.x, c.y - r * 0.85f)
                            lineTo(c.x + r * 0.35f, c.y + r * 0.45f)
                            lineTo(c.x, c.y + r * 0.2f)
                            lineTo(c.x - r * 0.35f, c.y + r * 0.45f)
                            close()
                        }
                        drawPath(fleche, if (abs(angle) < 10) Aurora else Cyan)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------- messages

/** In KISS mode the radio's frequency cannot be read: the operator vouches for it. */
@Composable
internal fun ConfirmeFrequenceKiss(ok: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = ok, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = ok, onCheckedChange = null)
        Text(t("aprs_kiss_frequence_ok"), color = TextHi, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp))
    }
}

/**
 * Messages like a chat: one conversation per station, ours on the right with
 * ✓ once acknowledged, theirs on the left with a button to acknowledge. Above
 * them, APRS Thursday: join, and today's participants, one tap to write.
 */
@Composable
internal fun OngletMessages(ui: UiState, vm: MainViewModel, paquets: List<Paquet>, mode: String,
                            frequenceOk: Boolean, onFrequenceOk: (Boolean) -> Unit) {
    val moi = vm.aprsSource()
    val resultat by vm.aprsEnvoi.collectAsState()
    val fils = remember(paquets, moi) { AprsJeu.fils(paquets, moi) }
    val participants = remember(paquets, moi, ui.nowMs / 60_000L) {
        AprsJeu.participantsHotg(paquets, debutDuJourUtc(), moi)
    }
    var ouvert by rememberSaveable { mutableStateOf<String?>(null) }
    var nouveau by rememberSaveable { mutableStateOf("") }
    // The ISS digipeater, or the terrestrial network: by default where APRS works now.
    var chemin by rememberSaveable { mutableStateOf(if (vm.aprsCheminParDefaut() == listOf("ARISS")) "ARISS" else "WIDE") }
    val cheminListe = if (chemin == "ARISS") listOf("ARISS") else listOf("WIDE1-1", "WIDE2-1")
    val kiss by fr.f4ioz.satcombo.aprs.TncKiss.etat.collectAsState()
    val connue = kiss.frequenceHz?.let { hz -> AprsEmission.FENETRES.any { hz in it } } == true
    val peutEmettre = moi.isNotBlank() && mode != "FT3D" && (mode != "KISS" || (kiss.connecte && (frequenceOk || connue)))
    fun envoie(dest: String, texte: String, avecNumero: Boolean = true) {
        val info = AprsEmission.message(dest, texte, if (avecNumero) vm.aprsNumeroSuivant() else null)
        vm.aprsEmetSelonMode(AprsEmission.trame(moi, cheminListe, info), frequenceOk)
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("APRS Thursday", color = TextHi, fontWeight = FontWeight.Bold)
                    Text(t("aprs_jeudi_desc"), color = TextLo, fontSize = 11.sp)
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("CQ HOTG" to t("aprs_jeudi_rejoindre"), "K HOTG" to t("aprs_jeudi_k"),
                            "U HOTG" to t("aprs_jeudi_quitter")).forEach { (txt, nom) ->
                            OutlinedButton(enabled = peutEmettre, onClick = { envoie("ANSRVR", txt) }) {
                                Text(nom, color = Cyan, fontSize = 12.sp)
                            }
                        }
                    }
                    Text(if (participants.isEmpty()) t("aprs_jeudi_personne") else tf("aprs_jeudi_participants", participants.size),
                        color = TextLo, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                    if (participants.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        participants.forEach { c -> Puce(ouvert == c, c) { ouvert = c } }
                    }
                }
            }
        }
        item {
            Column {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Puce(chemin == "ARISS", t("aprs_tx_chemin_iss")) { chemin = "ARISS" }
                    Puce(chemin == "WIDE", "WIDE1-1,WIDE2-1 (" + t("aprs_travail_terre_court") + ")") { chemin = "WIDE" }
                }
                if (mode == "KISS" && !connue) ConfirmeFrequenceKiss(frequenceOk, onFrequenceOk)
                if (moi.isBlank()) Text(t("aprs_tx_sans_indicatif"), color = Amber, fontSize = 11.sp)
                if (mode == "FT3D") Text(t("aprs_ft3d_tx"), color = Amber, fontSize = 11.sp)
                if (resultat.isNotBlank()) Text(resultat, color = TextHi, fontSize = 12.sp)
            }
        }
        // A conversation with a station not written to yet (a Thursday participant).
        val tous = fils.toMutableList()
        ouvert?.let { o -> if (tous.none { it.correspondant == o }) tous.add(0, AprsJeu.Fil(o, emptyList())) }
        if (tous.isEmpty()) item { Text(t("aprs_msg_vide"), color = TextLo, fontSize = 12.sp, modifier = Modifier.padding(8.dp)) }
        items(tous, key = { it.correspondant }) { f ->
            val estOuvert = ouvert == f.correspondant
            Surface(color = SpaceCard, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(Modifier.fillMaxWidth().clickable { ouvert = if (estOuvert) null else f.correspondant },
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(f.correspondant, color = TextHi, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f))
                        f.messages.lastOrNull()?.let {
                            Text(hhmm.format(Date(it.quand)) + " UTC", color = TextLo, fontSize = 11.sp)
                        }
                        Text(if (estOuvert) "  ▲" else "  ▼", color = TextLo)
                    }
                    if (!estOuvert) {
                        f.messages.lastOrNull()?.message?.let { Text(it, color = TextLo, fontSize = 12.sp, maxLines = 1) }
                    } else {
                        f.messages.forEach { m -> Bulle(m, paquets, peutEmettre) { id -> envoie(m.source, "ack$id", avecNumero = false) } }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                            OutlinedTextField(value = nouveau, onValueChange = { nouveau = it.take(AprsEmission.LONGUEUR_MESSAGE) },
                                placeholder = { Text(t("aprs_msg_ecrire"), color = TextLo, fontSize = 12.sp) },
                                singleLine = true, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(6.dp))
                            Button(enabled = peutEmettre && nouveau.isNotBlank(),
                                onClick = { envoie(f.correspondant, nouveau); nouveau = "" },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE5484D))) {
                                Text(t("aprs_msg_envoyer"), color = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One message: ours on the right (✓ when acknowledged), theirs on the left (button to acknowledge it). */
@Composable
private fun Bulle(m: Paquet, paquets: List<Paquet>, peutEmettre: Boolean, onAccuse: (String) -> Unit) {
    val accuse = AprsJeu.estAccuse(m, paquets)
    // Theirs, already acknowledged by us?
    val dejaAccuse = !m.emis && m.idMessage != null && paquets.any {
        it.emis && it.type == TypeAprs.ACCUSE && it.idMessage == m.idMessage && AprsJeu.base(it.destinataire) == AprsJeu.base(m.source)
    }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = if (m.emis) Arrangement.End else Arrangement.Start) {
        Surface(color = if (m.emis) Color(0xFFE5484D).copy(alpha = 0.18f) else Cyan.copy(alpha = 0.12f),
            shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(0.85f)) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                Text(m.message.orEmpty(), color = TextHi, fontSize = 13.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(hhmm.format(Date(m.quand)) + (if (m.viaIss) " · ISS" else ""), color = TextLo, fontSize = 10.sp,
                        modifier = Modifier.weight(1f))
                    if (m.emis) Text(if (accuse) "✓ " + t("aprs_accuse") else "…", color = if (accuse) Aurora else TextLo, fontSize = 11.sp)
                    else if (m.idMessage != null) {
                        if (dejaAccuse) Text("✓", color = Aurora, fontSize = 11.sp)
                        else TextButton(enabled = peutEmettre, onClick = { onAccuse(m.idMessage) },
                            contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Text(t("aprs_msg_accuser"), color = Cyan, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

// --------------------------------------------------------------- trophies

/**
 * The evening in numbers (shareable as a picture), today's contacts with a
 * button to log them, the records, and the badges.
 */
@Composable
internal fun OngletTrophees(ui: UiState, vm: MainViewModel, paquets: List<Paquet>) {
    val ctx = LocalContext.current
    val tr by AprsHub.trophees.collectAsState()
    val moi = vm.aprsSource()
    val obs = ui.observer
    val pays: (String) -> String? = { c -> fr.f4ioz.satcombo.domain.Dxcc.entite(AprsJeu.base(c))?.nom }
    val depuis = debutDuJourUtc(ui.nowMs.takeIf { it > 0 } ?: System.currentTimeMillis())
    val bilan = remember(paquets, moi, depuis) { AprsJeu.bilan(paquets, depuis, moi, obs?.latDeg, obs?.lonDeg, pays) }
    val contacts = remember(paquets, moi) { AprsJeu.contacts(paquets, moi) }
    var retour by remember { mutableStateOf("") }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(tf("aprs_bilan_titre", jjmm.format(Date(depuis))), color = TextHi, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Chiffre("${bilan.stations}", t("aprs_bilan_stations"))
                        Chiffre("${bilan.pays.size}", t("aprs_bilan_pays"))
                        Chiffre("${bilan.carres.size}", t("aprs_bilan_carres"))
                        Chiffre("${bilan.viaIss}", t("aprs_bilan_iss"))
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Chiffre("${bilan.envoyes}", t("aprs_bilan_envoyes"))
                        Chiffre("${bilan.repetes}", t("aprs_bilan_repetes"))
                        Chiffre("${bilan.accuses}", t("aprs_bilan_accuses"))
                        Chiffre("${bilan.contacts}", t("aprs_bilan_contacts"))
                    }
                    bilan.plusLoin?.let { s ->
                        Text(tf("aprs_bilan_plus_loin", s.indicatif, s.km?.toInt() ?: 0), color = TextHi, fontSize = 12.sp,
                            modifier = Modifier.padding(top = 8.dp))
                    }
                    if (bilan.pays.isNotEmpty()) Text(bilan.pays.sorted().joinToString(" · "), color = TextLo, fontSize = 11.sp)
                    OutlinedButton(onClick = { partageBilan(ctx, bilan, moi, depuis, obs) }, modifier = Modifier.padding(top = 8.dp)) {
                        Text(t("aprs_bilan_partager"), color = Cyan, fontSize = 12.sp)
                    }
                }
            }
        }
        item {
            Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(t("aprs_contacts_titre"), color = TextHi, fontWeight = FontWeight.Bold)
                    Text(t("aprs_contacts_desc"), color = TextLo, fontSize = 11.sp)
                    if (contacts.isEmpty()) Text(t("aprs_contacts_aucun"), color = TextLo, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                    contacts.forEach { c ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(c.indicatif + (c.carre?.let { " · $it" } ?: ""), color = TextHi, fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                                Text(jjmm.format(Date(c.quand)) + " " + hhmm.format(Date(c.quand)) + " UTC" +
                                    (if (c.viaIss) " · ISS" else " · " + t("aprs_contacts_terrestre")), color = TextLo, fontSize = 11.sp)
                            }
                            if (c.viaIss) OutlinedButton(onClick = { retour = vm.aprsAuCarnet(c) }) {
                                Text(t("aprs_contacts_carnet"), color = Cyan, fontSize = 12.sp)
                            }
                        }
                    }
                    if (retour.isNotBlank()) Text(retour, color = Aurora, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
        item {
            Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(t("aprs_records_titre"), color = TextHi, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Chiffre("${tr.carres.size}", t("aprs_bilan_carres"))
                        Chiffre("${tr.pays.size}", t("aprs_bilan_pays"))
                        Chiffre("${tr.repetesIss}", t("aprs_bilan_repetes"))
                        Chiffre("${tr.contacts.size}", t("aprs_bilan_contacts"))
                    }
                    if (tr.recordKm > 0) Text(tf("aprs_record_distance", tr.recordKm.toInt(), tr.recordIndicatif,
                        jjmm.format(Date(tr.recordQuand))), color = TextHi, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        item { Text(t("aprs_badges_titre"), color = TextHi, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp)) }
        Trophees.Badge.entries.chunked(2).forEach { paire ->
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    paire.forEach { b -> CarteBadge(b, tr.badges[b], Modifier.weight(1f)) }
                    if (paire.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun Chiffre(valeur: String, nom: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(min = 64.dp)) {
        Text(valeur, color = Cyan, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        Text(nom, color = TextLo, fontSize = 10.sp, textAlign = TextAlign.Center)
    }
}

private val EMOJI_BADGE = mapOf(
    Trophees.Badge.PREMIER_PAQUET to "📡", Trophees.Badge.PREMIER_ISS to "🛰", Trophees.Badge.REPETE_ISS to "🔁",
    Trophees.Badge.ISS_10 to "🚀", Trophees.Badge.CONTACT to "🤝", Trophees.Badge.CONTACT_ISS to "🌌",
    Trophees.Badge.CARRES_10 to "🟩", Trophees.Badge.CARRES_50 to "🗺", Trophees.Badge.PAYS_10 to "🌍",
    Trophees.Badge.KM_1000 to "📏", Trophees.Badge.KM_2000 to "🎯", Trophees.Badge.KM_3000 to "🏆",
    Trophees.Badge.JEUDI to "🗓", Trophees.Badge.METEO to "⛅",
)

@Composable
private fun CarteBadge(b: Trophees.Badge, gagne: Long?, modifier: Modifier) {
    Surface(color = if (gagne != null) Aurora.copy(alpha = 0.14f) else SpaceCard, shape = RoundedCornerShape(12.dp),
        modifier = modifier) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(EMOJI_BADGE[b] ?: "🏅", fontSize = 24.sp, color = if (gagne != null) Color.Unspecified else TextLo.copy(alpha = 0.4f))
            Spacer(Modifier.width(8.dp))
            Column {
                Text(t("aprs_badge_" + b.cle), color = if (gagne != null) TextHi else TextLo, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text(gagne?.let { jjmm.format(Date(it)) } ?: t("aprs_badge_" + b.cle + "_desc"), color = TextLo, fontSize = 10.sp)
            }
        }
    }
}

/** The evening's tally as a picture, to post after APRS Thursday. */
private fun partageBilan(ctx: android.content.Context, b: AprsJeu.Bilan, moi: String, jour: Long,
                         obs: fr.f4ioz.satcombo.data.Observer?) {
    runCatching {
        val w = 1080; val h = 1080
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        c.drawColor(0xFF0B1018.toInt())
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        fun texte(s: String, x: Float, y: Float, taille: Float, couleur: Int, gras: Boolean = false,
                  centre: Boolean = false) {
            p.textSize = taille; p.color = couleur
            p.typeface = if (gras) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
            p.textAlign = if (centre) android.graphics.Paint.Align.CENTER else android.graphics.Paint.Align.LEFT
            c.drawText(s, x, y, p)
        }
        val cyan = 0xFF34D8D8.toInt(); val blanc = 0xFFE6EDF5.toInt(); val gris = 0xFF8A9BB0.toInt(); val vert = 0xFF49D17F.toInt()
        texte("APRS", 80f, 150f, 110f, cyan, gras = true)
        texte(jjmm.format(Date(jour)) + " UTC", 80f, 215f, 44f, gris)
        texte(moi.ifBlank { "SatMe" } + (obs?.let { " · " + fr.f4ioz.satcombo.location.Maidenhead.fromLatLon(it.latDeg, it.lonDeg).take(6) } ?: ""),
            80f, 290f, 56f, blanc, gras = true)
        val cases = listOf(
            "${b.stations}" to t("aprs_bilan_stations"), "${b.pays.size}" to t("aprs_bilan_pays"),
            "${b.carres.size}" to t("aprs_bilan_carres"), "${b.viaIss}" to t("aprs_bilan_iss"),
            "${b.repetes}" to t("aprs_bilan_repetes"), "${b.contacts}" to t("aprs_bilan_contacts"))
        cases.forEachIndexed { i, (v, n) ->
            val x = 80f + (i % 3) * 320f + 140f; val y = 470f + (i / 3) * 230f
            texte(v, x, y, 120f, if (i == 4) vert else cyan, gras = true, centre = true)
            texte(n, x, y + 60f, 38f, gris, centre = true)
        }
        b.plusLoin?.let { s -> texte(tf("aprs_bilan_plus_loin", s.indicatif, s.km?.toInt() ?: 0), 80f, 920f, 42f, blanc) }
        texte("SatMe · APRS Thursday", w / 2f, 1030f, 34f, gris, centre = true)
        val dossier = java.io.File(ctx.cacheDir, "export").apply { mkdirs() }
        val f = java.io.File(dossier, "SatMe_APRS_" + SimpleDateFormat("yyyyMMdd", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(jour)) + ".png")
        f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
        ctx.startActivity(android.content.Intent.createChooser(
            android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, f.name).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
