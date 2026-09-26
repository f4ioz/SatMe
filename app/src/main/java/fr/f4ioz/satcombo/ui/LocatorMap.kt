/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import fr.f4ioz.satcombo.R
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.i18n.tf
import fr.f4ioz.satcombo.data.GeoResult
import fr.f4ioz.satcombo.data.Geocoder
import fr.f4ioz.satcombo.location.Maidenhead
import fr.f4ioz.satcombo.ui.theme.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Zoomable Maidenhead grid map with selectable base layers:
 *  - VECTOR : offline Natural Earth coastlines + cities (equirectangular)
 *  - OSM    : online OpenStreetMap raster tiles (Web Mercator)
 * The grid adapts (fields -> squares -> subsquares); EVERY visible cell of the
 * active level is labeled once cells are large enough.
 */
@Composable
fun LocatorMapDialog(
    initialLocator: String,
    mapStyle: String,
    initialPoint: Pair<Double, Double>? = null,
    /** Carrés à peindre : contactés, puis ceux d'où l'on a émis. */
    carresContactes: Set<String> = emptySet(),
    carresActives: Set<String> = emptySet(),
    showPota: Boolean = false,
    potaLoader: (suspend (Double, Double, Double, Double) -> List<fr.f4ioz.satcombo.data.PotaPark>)? = null,
    onPick: (String, Double?, Double?) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember { mutableStateOf(initialLocator.uppercase()) }
    // The exact spot the operator pointed at: the whole point of the picker is
    // that a QTH is a place, not the middle of a square.
    var point by remember { mutableStateOf(initialPoint) }
    var centerOn by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<GeoResult>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val geocoder = remember { Geocoder() }

    Dialog(onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = SpaceSurface, shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.92f)) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t("qth_label"), color = TextLo, fontSize = 14.sp)
                    Text(selected, color = Cyan, fontWeight = FontWeight.Black,
                        fontSize = 20.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.weight(1f))
                    Text(MapProviders.byId(mapStyle).label, color = TextLo, fontSize = 10.sp)
                }
                point?.let { (la, lo) ->
                    Text("◉  %.5f°, %.5f°".format(la, lo), color = Amber, fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace)
                }
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    placeholder = { Text(t("search_city"), color = TextLo) },
                    leadingIcon = { androidx.compose.material3.Icon(
                        Icons.Default.Search, null, tint = TextLo) },
                    trailingIcon = {
                        if (searching) androidx.compose.material3.CircularProgressIndicator(
                            Modifier.size(18.dp), color = Cyan, strokeWidth = 2.dp)
                        else if (query.isNotEmpty()) androidx.compose.material3.IconButton(onClick = {
                            query = ""; results = emptyList()
                        }) { androidx.compose.material3.Icon(
                            Icons.Default.Close, null, tint = TextLo) }
                    },
                    singleLine = true,
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = {
                        searching = true
                        scope.launch { results = geocoder.search(query); searching = false }
                    }),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                    shape = RoundedCornerShape(12.dp),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Cyan, unfocusedBorderColor = Color(0xFF2A3647),
                        focusedTextColor = TextHi, unfocusedTextColor = TextHi, cursorColor = Cyan),
                    modifier = Modifier.fillMaxWidth()
                )
                if (results.isNotEmpty()) {
                    Column(Modifier.fillMaxWidth().heightIn(max = 150.dp)
                        .verticalScroll(rememberScrollState())) {
                        results.forEach { g ->
                            Text(g.label, color = TextHi, fontSize = 13.sp,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    centerOn = g.latDeg to g.lonDeg
                                    selected = Maidenhead.fromLatLon(g.latDeg, g.lonDeg)
                                    point = g.latDeg to g.lonDeg
                                    results = emptyList(); query = g.label.substringBefore(",")
                                }.padding(vertical = 8.dp, horizontal = 4.dp))
                            androidx.compose.material3.HorizontalDivider(color = Color(0xFF2A3647))
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                LocatorMapCanvas(
                    selected = selected,
                    provider = MapProviders.byId(mapStyle),
                    centerOn = centerOn,
                    pin = point,
                    carresContactes = carresContactes,
                    carresActives = carresActives,
                    showPota = showPota,
                    potaLoader = potaLoader,
                    onSelect = { selected = it },
                    onPoint = { la, lo -> point = la to lo },
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
                Text(t("map_pin_hint"), color = TextLo, fontSize = 11.sp,
                    modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(8.dp))
                Row {
                    TextButton(onClick = onDismiss) { Text(t("cancel"), color = TextLo) }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { onPick(selected, point?.first, point?.second) },
                        colors = ButtonDefaults.buttonColors(containerColor = Cyan)) {
                        Text(tf("use_locator", selected), color = Color(0xFF00201D))
                    }
                }
            }
        }
    }
}


/** Selectable base maps. VECTOR is offline; the rest are raster tile servers. */
data class MapProvider(
    val id: String, val label: String,
    val template: String?, val attribution: String, val maxZ: Int, val dark: Boolean
)

object MapProviders {
    // Computed so the labels follow the active app language.
    val ALL get() = listOf(
        MapProvider("VECTOR", t("map_offline"), null, "Natural Earth", 99, true),
        MapProvider("OSM", "OpenStreetMap",
            "https://tile.openstreetmap.org/{z}/{x}/{y}.png", "© OpenStreetMap", 19, false),
        MapProvider("TOPO", "Topo",
            "https://a.tile.opentopomap.org/{z}/{x}/{y}.png", "© OpenTopoMap (CC-BY-SA)", 17, false),
        MapProvider("DARK", t("dark"),
            "https://a.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png", "© CARTO © OSM", 20, true),
        MapProvider("SAT", "Satellite",
            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
            "© Esri", 19, false),
    )
    fun byId(id: String) = ALL.firstOrNull { it.id == id } ?: ALL[0]
}

// ---------- offline vector data ----------

private data class City(val lon: Double, val lat: Double, val pop: Int, val name: String)

/**
 * Le tracé des terres, en degrés : `x = longitude + 180`, `y = 90 - latitude`.
 *
 * Rendu accessible au planisphère de `WorldMap`, qui dessinait jusque-là une
 * image dont personne ne savait plus d'où elle venait. Une seule source pour
 * les côtes : deux tracés du même monde finiraient par ne plus se ressembler.
 */
@Composable
internal fun rememberLandPath(): Path? {
    val ctx = LocalContext.current
    var path by remember { mutableStateOf<Path?>(null) }
    LaunchedEffect(Unit) {
        path = withContext(Dispatchers.Default) {
            runCatching {
                val text = ctx.resources.openRawResource(R.raw.land)
                    .bufferedReader().use { it.readText() }
                val arr = JSONArray(text)
                Path().apply {
                    for (i in 0 until arr.length()) {
                        val ring = arr.getJSONArray(i)
                        var j = 0
                        while (j + 1 < ring.length()) {
                            val x = (ring.getDouble(j) + 180.0).toFloat()
                            val y = (90.0 - ring.getDouble(j + 1)).toFloat()
                            if (j == 0) moveTo(x, y) else lineTo(x, y)
                            j += 2
                        }
                        close()
                    }
                }
            }.getOrNull()
        }
    }
    return path
}


@Composable
private fun rememberCommunes(): List<City> {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf<List<City>>(emptyList()) }
    LaunchedEffect(Unit) {
        list = withContext(Dispatchers.Default) {
            runCatching {
                val arr = JSONArray(ctx.resources.openRawResource(R.raw.communes_fr)
                    .bufferedReader().use { it.readText() })
                ArrayList<City>(arr.length()).apply {
                    for (i in 0 until arr.length()) {
                        val c = arr.getJSONArray(i)
                        add(City(c.getDouble(0), c.getDouble(1), 0, c.getString(2)))
                    }
                }
            }.getOrDefault(emptyList())
        }
    }
    return list
}

@Composable
private fun rememberCities(): List<City> {
    val ctx = LocalContext.current
    var cities by remember { mutableStateOf<List<City>>(emptyList()) }
    LaunchedEffect(Unit) {
        cities = withContext(Dispatchers.Default) {
            runCatching {
                val arr = JSONArray(ctx.resources.openRawResource(R.raw.cities)
                    .bufferedReader().use { it.readText() })
                (0 until arr.length()).map { i ->
                    val c = arr.getJSONArray(i)
                    City(c.getDouble(0), c.getDouble(1), c.getInt(2), c.getString(3))
                }
            }.getOrDefault(emptyList())
        }
    }
    return cities
}


@Composable
private fun rememberLinesPath(resId: Int): Path? {
    val ctx = LocalContext.current
    var path by remember { mutableStateOf<Path?>(null) }
    LaunchedEffect(resId) {
        path = withContext(Dispatchers.Default) {
            runCatching {
                val arr = JSONArray(ctx.resources.openRawResource(resId)
                    .bufferedReader().use { it.readText() })
                Path().apply {
                    for (i in 0 until arr.length()) {
                        val line = arr.getJSONArray(i)
                        var j = 0
                        while (j + 1 < line.length()) {
                            val x = (line.getDouble(j) + 180.0).toFloat()
                            val y = (90.0 - line.getDouble(j + 1)).toFloat()
                            if (j == 0) moveTo(x, y) else lineTo(x, y)
                            j += 2
                        }
                    }
                }
            }.getOrNull()
        }
    }
    return path
}

@Composable
private fun rememberPolysPath(resId: Int): Path? {
    val ctx = LocalContext.current
    var path by remember { mutableStateOf<Path?>(null) }
    LaunchedEffect(resId) {
        path = withContext(Dispatchers.Default) {
            runCatching {
                val arr = JSONArray(ctx.resources.openRawResource(resId)
                    .bufferedReader().use { it.readText() })
                Path().apply {
                    for (i in 0 until arr.length()) {
                        val ring = arr.getJSONArray(i)
                        var j = 0
                        while (j + 1 < ring.length()) {
                            val x = (ring.getDouble(j) + 180.0).toFloat()
                            val y = (90.0 - ring.getDouble(j + 1)).toFloat()
                            if (j == 0) moveTo(x, y) else lineTo(x, y)
                            j += 2
                        }
                        close()
                    }
                }
            }.getOrNull()
        }
    }
    return path
}

// ---------- OSM tile loader (LRU, async) ----------

private class TileStore(private val scope: CoroutineScope) {
    val tiles = androidx.compose.runtime.mutableStateMapOf<String, ImageBitmap?>()
    private val order = ArrayDeque<String>()
    private val client = OkHttpClient()

    fun get(provider: MapProvider, z: Int, x: Int, y: Int): ImageBitmap? {
        val template = provider.template ?: return null
        val n = 1 shl z
        if (y < 0 || y >= n) return null
        val xw = ((x % n) + n) % n
        val key = "${provider.id}/$z/$xw/$y"
        if (tiles.containsKey(key)) return tiles[key]
        tiles[key] = null
        order.addLast(key)
        while (order.size > 90) tiles.remove(order.removeFirst())
        val url = template.replace("{z}", "$z").replace("{x}", "$xw").replace("{y}", "$y")
        scope.launch(Dispatchers.IO) {
            val bmp = runCatching {
                val req = Request.Builder().url(url)
                    .header("User-Agent", "SatCombo/2.8 amateur-radio app (F4IOZ)")
                    .build()
                client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) null
                    else BitmapFactory.decodeStream(r.body?.byteStream())?.asImageBitmap()
                }
            }.getOrNull()
            if (bmp != null) tiles[key] = bmp
        }
        return null
    }
}

// ---------- the map ----------

@Composable
internal fun LocatorMapCanvas(
    selected: String,
    provider: MapProvider,
    centerOn: Pair<Double, Double>? = null,
    marker: Pair<Double, Double>? = null,   // exact (lat, lon) live position dot
    pin: Pair<Double, Double>? = null,      // exact (lat, lon) picked spot, drawn as a pin
    /** Carrés à peindre : contactés, puis ceux d'où l'on a émis. */
    carresContactes: Set<String> = emptySet(),
    carresActives: Set<String> = emptySet(),
    showPota: Boolean = false,
    potaLoader: (suspend (Double, Double, Double, Double) -> List<fr.f4ioz.satcombo.data.PotaPark>)? = null,
    onSelect: (String) -> Unit,
    onPoint: ((Double, Double) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val mercator = provider.template != null
    val densityObj = androidx.compose.ui.platform.LocalDensity.current
    val den = densityObj.density
    val txt = densityObj.density * densityObj.fontScale  // text honors system font size
    val landPath = if (!mercator) rememberLandPath() else null
    val lakesPath = if (!mercator) rememberPolysPath(R.raw.lakes) else null
    val bordersPath = if (!mercator) rememberLinesPath(R.raw.borders) else null
    val cities = rememberCities()
    val communes = rememberCommunes()  // dense FR place labels on every base map
    val scope = rememberCoroutineScope()
    val tileStore = remember { TileStore(scope) }

    var canvasSize by remember { mutableStateOf(Size.Zero) }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var initialized by remember { mutableStateOf(false) }
    var potaParks by remember { mutableStateOf<List<fr.f4ioz.satcombo.data.PotaPark>>(emptyList()) }

    // Load POTA parks for the visible window when zoomed in enough.
    LaunchedEffect(showPota, scale, offset, canvasSize) {
        if (!showPota || potaLoader == null || canvasSize.width <= 0) { potaParks = emptyList(); return@LaunchedEffect }
        kotlinx.coroutines.delay(250)  // debounce pans/zooms
        val degS = canvasSize.width / 360f * scale
        if (degS < 30f) { potaParks = emptyList(); return@LaunchedEffect } // too far out
        val lonMin = ((0f - offset.x) / degS - 180.0)
        val lonMax = ((canvasSize.width - offset.x) / degS - 180.0)
        // latitude window differs per projection; use generous lat bounds via screen corners
        val latTop = run {
            val wy = (0f - offset.y) / degS
            if (provider.template != null) {
                val n = 0.5 - wy / 360.0; Math.toDegrees(kotlin.math.atan(kotlin.math.sinh(2 * Math.PI * n)))
            } else 90.0 - wy
        }
        val latBot = run {
            val wy = (canvasSize.height - offset.y) / degS
            if (provider.template != null) {
                val n = 0.5 - wy / 360.0; Math.toDegrees(kotlin.math.atan(kotlin.math.sinh(2 * Math.PI * n)))
            } else 90.0 - wy
        }
        potaParks = potaLoader(minOf(latBot, latTop), maxOf(latBot, latTop),
            minOf(lonMin, lonMax), maxOf(lonMin, lonMax))
    }

    LaunchedEffect(centerOn, canvasSize) {
        if (centerOn != null && canvasSize.width > 0) {
            val (la, lo) = centerOn
            scale = 220f
            val cx = ((lo + 180.0) * (canvasSize.width / 360f * scale)).toFloat()
            val cyWorld = if (provider.template != null) {
                val l = Math.toRadians(la.coerceIn(-85.05, 85.05))
                (360.0 * (0.5 - kotlin.math.ln(kotlin.math.tan(Math.PI / 4 + l / 2)) / (2 * Math.PI))).toFloat()
            } else (90.0 - la).toFloat()
            val cy = cyWorld * (canvasSize.width / 360f * scale)
            offset = Offset(canvasSize.width / 2 - cx, canvasSize.height / 2 - cy)
        }
    }

    // World space: x = lon+180 in 0..360 "units"; y depends on projection.
    val worldH = if (mercator) 360f else 180f
    fun degScale() = canvasSize.width / 360f * scale
    fun latToWy(lat: Double): Float {
        return if (mercator) {
            val l = Math.toRadians(lat.coerceIn(-85.05, 85.05))
            (360.0 * (0.5 - ln(tan(PI / 4 + l / 2)) / (2 * PI))).toFloat()
        } else (90.0 - lat).toFloat()
    }
    fun wyToLat(wy: Float): Double {
        return if (mercator) {
            val n = 0.5 - wy / 360.0
            Math.toDegrees(atan(sinh(2 * PI * n)))
        } else 90.0 - wy
    }
    fun toScreen(lon: Double, lat: Double) = Offset(
        offset.x + ((lon + 180.0) * degScale()).toFloat(),
        offset.y + latToWy(lat) * degScale()
    )
    fun screenToLon(sx: Float) = (sx - offset.x) / degScale() - 180.0
    fun screenToLat(sy: Float) = wyToLat((sy - offset.y) / degScale())

    fun clampOffset() {
        val sw = 360f * degScale(); val sh = worldH * degScale()
        val minX = canvasSize.width - sw; val minY = canvasSize.height - sh
        offset = Offset(
            if (sw <= canvasSize.width) (canvasSize.width - sw) / 2 else offset.x.coerceIn(minX, 0f),
            if (sh <= canvasSize.height) (canvasSize.height - sh) / 2 else offset.y.coerceIn(minY, 0f)
        )
    }

    fun cellPx(lonSpanDeg: Double) = (lonSpanDeg * degScale()).toFloat()
    fun subsquareGridOn() = cellPx(2.0 / 24) >= 16f * den

    Canvas(
        modifier
            .background(SpaceBg, RoundedCornerShape(12.dp))
            .clipToBounds()
            .onSizeChanged {
                canvasSize = Size(it.width.toFloat(), it.height.toFloat())
                if (!initialized && canvasSize.width > 0) {
                    initialized = true
                    val b = Maidenhead.bounds(selected) ?: doubleArrayOf(48.0, 2.0, 1.0, 2.0)
                    // Zoom in close to the QTH so the user sees their surroundings,
                    // like opening a maps app centred on current position.
                    scale = 320f
                    val center = toScreen(b[1] + b[3] / 2, b[0] + b[2] / 2)
                    offset += Offset(canvasSize.width / 2 - center.x, canvasSize.height / 2 - center.y)
                    clampOffset()
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { tap ->
                    val lon = screenToLon(tap.x); val lat = screenToLat(tap.y)
                    if (lon in -180.0..180.0 && lat in -90.0..90.0) {
                        val full = Maidenhead.fromLatLon(lat, lon)
                        onSelect(if (subsquareGridOn()) full else full.take(4))
                        // Report the tapped position itself, not the square:
                        // the caller keeps the real coordinates.
                        onPoint?.invoke(lat, lon)
                    }
                }
            }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    // Cap chosen so OSM reaches its native max tile level (z19,
                    // street detail) plus a little upscale headroom, while world
                    // coordinates stay within Float precision (no map jitter).
                    val newScale = (scale * zoom).coerceIn(1f, 250_000f)
                    offset = centroid - (centroid - offset) * (newScale / scale) + pan
                    scale = newScale
                    clampOffset()
                }
            }
    ) {
        if (canvasSize.width <= 0) return@Canvas

        // ---------- base layer ----------
        if (mercator) {
            // OSM tiles. Choose z so tiles render near 1:1.
            val worldPx = 360f * degScale()
            val z = log2(worldPx / 256.0).roundToInt().coerceIn(1, provider.maxZ)
            val n = 1 shl z
            val tileScreen = worldPx / n
            val tx0 = floor((-offset.x) / tileScreen).toInt()
            val tx1 = floor((size.width - offset.x) / tileScreen).toInt()
            val ty0 = floor((-offset.y) / tileScreen).toInt().coerceIn(0, n - 1)
            val ty1 = floor((size.height - offset.y) / tileScreen).toInt().coerceIn(0, n - 1)
            for (tx in tx0..tx1) for (ty in ty0..ty1) {
                val bmp = tileStore.get(provider, z, tx, ty)
                val dst = Offset(offset.x + tx * tileScreen, offset.y + ty * tileScreen)
                if (bmp != null) {
                    drawImage(bmp,
                        dstOffset = IntOffset(dst.x.roundToInt(), dst.y.roundToInt()),
                        dstSize = IntSize((tileScreen + 1f).roundToInt(), (tileScreen + 1f).roundToInt()))
                } else {
                    drawRect(SpaceCard, dst, Size(tileScreen, tileScreen))
                }
            }
        } else {
            if (landPath != null) {
                withTransform({
                    translate(offset.x, offset.y)
                    scale(degScale(), degScale(), pivot = Offset.Zero)
                }) {
                    drawPath(landPath, SpaceCard)
                    drawPath(landPath, Color(0xFF3A4A60), style = Stroke(1.4f / degScale()))
                    lakesPath?.let {
                        drawPath(it, SpaceBg)
                        drawPath(it, Color(0xFF31405A), style = Stroke(1f / degScale()))
                    }
                    bordersPath?.let {
                        drawPath(it, Color(0xFF4A5A74), style = Stroke(1.1f / degScale()))
                    }
                }
            }
        }

        // ---------- visible window ----------
        val lonA = screenToLon(0f).coerceIn(-180.0, 180.0)
        val lonB = screenToLon(size.width).coerceIn(-180.0, 180.0)
        val latA = screenToLat(size.height).coerceIn(-90.0, 90.0)
        val latB = screenToLat(0f).coerceIn(-90.0, 90.0)

        // ---------- Maidenhead grid ----------
        fun drawGrid(lonStep: Double, latStep: Double, color: Color, width: Float) {
            var lon = floor(lonA / lonStep) * lonStep
            while (lon <= lonB) {
                val x = toScreen(lon, 0.0).x
                drawLine(color, Offset(x, 0f), Offset(x, size.height), width)
                lon += lonStep
            }
            var lat = floor(latA / latStep) * latStep
            while (lat <= latB + latStep) {
                val y = toScreen(0.0, lat).y
                drawLine(color, Offset(0f, y), Offset(size.width, y), width)
                lat += latStep
            }
        }

        /** Label EVERY visible cell of the level, centered, translucent. */
        fun drawLabels(lonStep: Double, latStep: Double, chars: Int) {
            val onTiles = provider.template != null && !provider.dark
            val paint = android.graphics.Paint().apply {
                color = if (onTiles) android.graphics.Color.argb(230, 13, 71, 161)
                        else android.graphics.Color.argb(200, 178, 212, 255)
                textSize = 15f * txt; isAntiAlias = true; isFakeBoldText = true
                textAlign = android.graphics.Paint.Align.CENTER
                setShadowLayer(4f * den, 0f, 0f,
                    if (onTiles) android.graphics.Color.WHITE else android.graphics.Color.BLACK)
            }
            var drawn = 0
            var lon = floor(lonA / lonStep) * lonStep
            while (lon <= lonB && drawn < 300) {
                var lat = floor(latA / latStep) * latStep
                while (lat <= latB + latStep && drawn < 300) {
                    val cLon = lon + lonStep / 2; val cLat = lat + latStep / 2
                    if (cLon in -180.0..180.0 && cLat in -90.0..90.0) {
                        val p = toScreen(cLon, cLat)
                        if (p.x in -60f..size.width + 60f && p.y in -20f..size.height + 20f) {
                            drawContext.canvas.nativeCanvas.drawText(
                                Maidenhead.fromLatLon(cLat, cLon).take(chars), p.x, p.y + 5f * den, paint)
                            drawn++
                        }
                    }
                    lat += latStep
                }
                lon += lonStep
            }
        }

        // Over raster tiles use a high-contrast line; offline keep the subtle palette.
        val gridCol = if (mercator) Color(0xCC1565C0) else null
        val fieldPx = cellPx(20.0)
        val squarePx = cellPx(2.0)
        val subPx = cellPx(2.0 / 24)

        if (fieldPx >= 7f * den) drawGrid(20.0, 10.0, gridCol ?: Color(0x663A4A60), 1.6f * den)
        if (squarePx >= 12f * den) drawGrid(2.0, 1.0, gridCol?.copy(alpha = 0.55f) ?: Color(0x4F2E3B4F), 1.1f * den)
        if (subPx >= 16f * den) drawGrid(2.0 / 24, 1.0 / 24, gridCol?.copy(alpha = 0.40f) ?: Color(0x3A26344A), 0.8f * den)

        // Exhaustive labels on the FINEST level whose cells can hold the text.
        val sub6 = 66f * txt   // min cell width for a 6-char label
        val sq4 = 50f * txt    // min cell width for a 4-char label
        when {
            subPx >= sub6 -> drawLabels(2.0 / 24, 1.0 / 24, 6)
            squarePx >= sq4 -> drawLabels(2.0, 1.0, 4)
            fieldPx >= sq4 -> drawLabels(20.0, 10.0, 2)
        }

        // ---------- QTH marker: the EXACT live position when provided,
        // otherwise the centre of the selected square (fallback). ----------
        // A pin means a real spot was chosen, so the square-centre fallback is
        // dropped: two dots claiming to be the QTH would be one too many.
        val markPt = marker?.let { toScreen(it.second, it.first) }
            ?: if (pin != null) null
               else Maidenhead.bounds(selected)?.let { b -> toScreen(b[1] + b[3] / 2, b[0] + b[2] / 2) }
        markPt?.let { q ->
            if (q.x in 0f..size.width && q.y in 0f..size.height) {
                drawCircle(Color(0x553B82F6), 16f * den, q)
                drawCircle(Color(0xFF3B82F6), 6f * den, q)
                drawCircle(Color.White, 6f * den, q, style = Stroke(2f * den))
            }
        }

        // ---------- POTA parks overlay ----------
        if (showPota && potaParks.isNotEmpty()) {
            val green = Color(0xFF49D17F)
            val halo = android.graphics.Paint().apply {
                color = android.graphics.Color.BLACK
                textSize = 16f * txt; isAntiAlias = true; isFakeBoldText = true
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 4f * den; strokeJoin = android.graphics.Paint.Join.ROUND
            }
            val fill = android.graphics.Paint().apply {
                color = android.graphics.Color.parseColor("#7FE3A0")
                textSize = 16f * txt; isAntiAlias = true; isFakeBoldText = true
            }
            var n = 0
            for (pk in potaParks) {
                val p = toScreen(pk.lonDeg, pk.latDeg)
                if (p.x < 0 || p.x > size.width || p.y < 0 || p.y > size.height) continue
                // tree-ish marker: filled green diamond
                val s = 5f * den
                val path = Path().apply {
                    moveTo(p.x, p.y - s); lineTo(p.x + s, p.y)
                    lineTo(p.x, p.y + s); lineTo(p.x - s, p.y); close()
                }
                drawPath(path, green)
                drawPath(path, Color.Black, style = Stroke(1.2f * den))
                if (n < 40) {
                    drawContext.canvas.nativeCanvas.drawText(pk.reference, p.x + 7f * den, p.y + 5f * den, halo)
                    drawContext.canvas.nativeCanvas.drawText(pk.reference, p.x + 7f * den, p.y + 5f * den, fill)
                }
                if (++n > 200) break
            }
        }

        // ---------- carrés travaillés et activés ----------
        //
        // Peints sous la sélection : le carré courant reste le plus lisible,
        // c'est lui qu'on vient chercher. Ce qui sort de l'écran n'est ni
        // dessiné ni calculé — la France entière tiendrait sinon dans la
        // boucle à chaque image.
        if (carresContactes.isNotEmpty() || carresActives.isNotEmpty()) {
            fun peins(carres: Set<String>, col: Color) {
                for (k in carres) {
                    val b = Maidenhead.bounds(k) ?: continue
                    val tl = toScreen(b[1], b[0] + b[2])
                    val br = toScreen(b[1] + b[3], b[0])
                    if (br.x < 0 || tl.x > size.width || br.y < 0 || tl.y > size.height) continue
                    drawRect(col, tl, Size(max(br.x - tl.x, 2f), max(br.y - tl.y, 2f)))
                }
            }
            peins(carresContactes, Color(0x4435E0C0))
            peins(carresActives, Color(0x55E8B44A))
        }

        // ---------- selection ----------
        Maidenhead.bounds(selected)?.let { b ->
            val tl = toScreen(b[1], b[0] + b[2])
            val br = toScreen(b[1] + b[3], b[0])
            val rs = Size(max(br.x - tl.x, 3f), max(br.y - tl.y, 3f))
            drawRect(Cyan.copy(alpha = 0.20f), tl, rs)
            drawRect(Cyan, tl, rs, style = Stroke(2.5f))
            drawContext.canvas.nativeCanvas.drawText(
                selected, tl.x, (tl.y - 5f * den).coerceAtLeast(16f * den),
                android.graphics.Paint().apply {
                    color = android.graphics.Color.parseColor("#38E1D4")
                    textSize = 18f * txt; isAntiAlias = true; isFakeBoldText = true
                    setShadowLayer(4f * den, 0f, 0f, android.graphics.Color.BLACK)
                })
        }

        // ---------- the pin ("puce"): its tip is the exact chosen point ----------
        pin?.let { (pla, plo) ->
            val p = toScreen(plo, pla)
            if (p.x in -40f * den..size.width + 40f * den && p.y in -60f * den..size.height + 40f * den) {
                val r = 7.5f * den                 // head radius
                val h = 24f * den                  // tip -> head centre
                val head = Offset(p.x, p.y - h)
                val neck = Path().apply {          // triangle joining head to tip
                    moveTo(head.x - r * 0.66f, head.y + r * 0.72f)
                    lineTo(head.x + r * 0.66f, head.y + r * 0.72f)
                    lineTo(p.x, p.y)
                    close()
                }
                drawPath(neck, Amber)
                drawCircle(Amber, r, head)
                drawCircle(Color.White, r, head, style = Stroke(2f * den))
                drawCircle(Color(0xFF201400), r * 0.38f, head)
                // the exact spot itself, so it stays readable under the pin
                drawCircle(Color.White, 2.2f * den, p)
                drawCircle(Color(0xFF201400), 2.2f * den, p, style = Stroke(1f * den))
            }
        }

        // ---------- footer hints ----------
        val level = if (subsquareGridOn()) t("precision_sub")
        else t("precision_square")
        val attrib = "   ·   " + provider.attribution
        drawContext.canvas.nativeCanvas.drawText(
            level + attrib, 8f * den, size.height - 8f * den,
            android.graphics.Paint().apply {
                color = android.graphics.Color.argb(190, 200, 210, 230)
                textSize = 12f * txt; isAntiAlias = true
                setShadowLayer(4f * den, 0f, 0f, android.graphics.Color.BLACK)
            })
    }
}
