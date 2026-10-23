/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import fr.f4ioz.satcombo.data.SettingsStore
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.i18n.tf
import fr.f4ioz.satcombo.sstv.Planche
import fr.f4ioz.satcombo.sstv.PlancheDecor
import fr.f4ioz.satcombo.sstv.PlancheRendu
import fr.f4ioz.satcombo.sstv.SstvHub
import fr.f4ioz.satcombo.sstv.SstvMeta
import fr.f4ioz.satcombo.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The SSTV sheet: received pictures placed in the order chosen on an
 * imported template (an ARISS series) or a generic one, with the callsign,
 * name @ locator and dates — like the diploma pages operators make by hand.
 */
@Composable
fun PlancheScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val rangement = remember { Planche.Rangement(File(ctx.filesDir, "planches")) }
    val reglages = remember { SettingsStore(ctx) }
    var modeles by remember { mutableStateOf(rangement.modeles()) }
    var m by remember { mutableStateOf(modeles.lastOrNull()) }
    val galerie = remember { SstvHub.shots(ctx) }
    val parNom = remember(galerie) { galerie.associate { it.first.name to it } }
    // Selection: a box (>= 0) or a text (-1 - index).
    var choix by remember { mutableStateOf<Int?>(null) }
    // Opens on the pictures: choosing them is what a sheet is made of.
    var onglet by remember { mutableStateOf(0) }
    var filtreSat by remember { mutableStateOf("") }
    var indicatif by remember { mutableStateOf(reglages.callsign) }
    var nom by remember { mutableStateOf(reglages.plancheNom) }
    var locatorSaisi by remember { mutableStateOf<String?>(null) }
    var datesSaisies by remember { mutableStateOf<String?>(null) }
    var apercu by remember { mutableStateOf<Bitmap?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var occupe by remember { mutableStateOf(false) }
    val vignettes = remember { mutableStateMapOf<String, Bitmap>() }
    // The pictures of the preview, kept a little larger than the gallery's.
    val apercus = remember { java.util.concurrent.ConcurrentHashMap<String, Bitmap>() }
    val logo = remember { runCatching { ctx.packageManager.getApplicationIcon(ctx.applicationInfo) }.getOrNull() }

    fun change(n: Planche.Modele) {
        m = n
        modeles = modeles.map { if (it.id == n.id) n else it }.let { l -> if (l.any { it.id == n.id }) l else l + n }
    }
    // Work that reads the pictures (one copy of each slide): off the screen's thread, the sheet changed after.
    fun calcule(bloc: () -> Planche.Modele) {
        occupe = true
        scope.launch {
            val n = withContext(Dispatchers.Default) { runCatching(bloc).getOrNull() }
            occupe = false
            if (n != null) { change(n); choix = null }
        }
    }
    // Saved shortly after each change: no save button to forget.
    LaunchedEffect(m) {
        val x = m ?: return@LaunchedEffect; delay(600)
        withContext(Dispatchers.IO) { rangement.enregistre(x); runCatching { rangement.nettoie() } }
    }

    val places = m?.images.orEmpty().mapNotNull { (i, f) -> parNom[f]?.let { i to it } }.toMap()
    val valeurs = PlancheRendu.Valeurs(
        indicatif = indicatif, nom = nom,
        locator = locatorSaisi ?: Planche.locatorDominant(places.values.map { it.second.locator })
            .ifBlank { SstvHub.qthLocator },
        dates = datesSaisies ?: Planche.dates(places.values.map { it.second.timeMs }),
        titre = m?.titre?.ifBlank { null } ?: places.values.firstOrNull()?.second?.satName?.let { "$it · SSTV" } ?: "SSTV")

    val fondApercu = remember(m?.fond) { m?.let { rangement.fond(it) }?.let { PlancheRendu.charge(it, 1600) } }
    // The preview, drawn again a moment after each change.
    LaunchedEffect(m, valeurs, fondApercu) {
        val x = m ?: run { apercu = null; return@LaunchedEffect }
        delay(120)
        apercu = withContext(Dispatchers.Default) {
            val imgs = x.images.mapNotNull { (i, f) ->
                val s = parNom[f] ?: return@mapNotNull null
                val b = apercus[f] ?: PlancheRendu.charge(s.first, 640)?.also { apercus[f] = it } ?: return@mapNotNull null
                i to (b to s.second)
            }.toMap()
            apercus.keys.retainAll(x.images.values.toSet())
            PlancheRendu.dessine(x, fondApercu, 1000, imgs, valeurs, logo,
                PlancheRendu.chargeDecor(rangement, x, 1000), PlancheRendu.chargeEcussons(rangement, x, 500))
        }
    }

    val importe = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        occupe = true
        scope.launch {
            val n = withContext(Dispatchers.IO) { importeFond(ctx, rangement, uri, tf("planche_nom_modele", modeles.size + 1)) }
            occupe = false
            if (n == null) message = t("planche_import_echec") else {
                change(n); choix = null
                message = tf("planche_cases_trouvees", n.cases.size)
            }
        }
    }
    // A background of one's own, a patch: kept in the sheets folder, the sheet changed.
    val choisitDecor = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val x = m ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        occupe = true
        scope.launch {
            val nom = rangement.nouveauNom("decor", "jpg")
            val ok = withContext(Dispatchers.IO) { PlancheRendu.gardeDecor({ ctx.contentResolver.openInputStream(uri) }, File(rangement.dossier, nom)) }
            occupe = false
            if (ok) change(x.copy(decor = nom)) else message = t("planche_import_echec")
        }
    }
    val choisitEcusson = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val x = m ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        occupe = true
        scope.launch {
            val nom = rangement.nouveauNom("ecusson", "png")
            val taille = withContext(Dispatchers.IO) { PlancheRendu.gardeEcusson({ ctx.contentResolver.openInputStream(uri) }, File(rangement.dossier, nom)) }
            occupe = false
            if (taille == null) { message = t("planche_import_echec"); return@launch }
            val ratio = fondApercu?.let { it.width.toFloat() / it.height } ?: x.ratio
            val n = Planche.ajouteEcusson(x, nom, taille.first, taille.second, ratio)
            change(n); choix = choixEcusson(n.ecussons.size - 1)
        }
    }
    val enregistre = rememberEnregistrer()

    fun exporte(partager: Boolean) {
        val x = m ?: return
        occupe = true
        scope.launch {
            val f = withContext(Dispatchers.IO) { runCatching {
                val fond = rangement.fond(x)?.let { PlancheRendu.charge(it, 4000) }
                val imgs = x.images.mapNotNull { (i, f) ->
                    val s = parNom[f] ?: return@mapNotNull null
                    PlancheRendu.charge(s.first, 2000)?.let { i to (it to s.second) }
                }.toMap()
                val b = PlancheRendu.dessine(x, fond, fond?.width ?: 2400, imgs, valeurs, logo,
                    PlancheRendu.chargeDecor(rangement, x, 4000), PlancheRendu.chargeEcussons(rangement, x, 1600))
                val sat = imgs.values.firstOrNull()?.second?.satName?.replace(Regex("[^A-Za-z0-9-]"), "-") ?: "SSTV"
                val jour = valeurs.dates.take(10).replace("-", "").ifBlank { "planche" }
                // Shared: a JPEG, far smaller and quicker to make than the PNG (mail and messaging take it); saved: the PNG.
                val ext = if (partager) "jpg" else "png"
                val out = File(File(ctx.cacheDir, "export").apply { mkdirs() }, "SatMe_Planche_${sat}_$jour.$ext")
                out.outputStream().use {
                    if (partager) b.compress(Bitmap.CompressFormat.JPEG, 95, it) else b.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                b.recycle()
                out
            }.getOrNull() }
            occupe = false
            if (f == null) { message = t("planche_export_echec"); return@launch }
            if (partager) runCatching {
                val u = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
                ctx.startActivity(android.content.Intent.createChooser(
                    android.content.Intent(android.content.Intent.ACTION_SEND).setType("image/jpeg")
                        .putExtra(android.content.Intent.EXTRA_STREAM, u)
                        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION), f.name))
            } else enregistre(f.name, "image/png", depuisFichier(f))
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = SpaceBg, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t("planche_titre"), color = TextHi, fontWeight = FontWeight.Bold, fontSize = 18.sp,
                        modifier = Modifier.weight(1f))
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, t("close"), tint = TextLo) }
                }
                Text(t("planche_desc"), color = TextLo, fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))

                // Templates: those kept, and new ones.
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    modeles.forEach { x ->
                        FilterChip(selected = x.id == m?.id, onClick = { m = x; choix = null },
                            label = { Text(x.nom, fontSize = 12.sp) })
                    }
                    AssistChip(onClick = { importe.launch("image/*") }, label = { Text("＋ " + t("planche_importer"), fontSize = 12.sp, color = Cyan) })
                    AssistChip(onClick = { change(Planche.genereGrille(rangement.nouvelId(), tf("planche_nom_modele", modeles.size + 1))); choix = null },
                        label = { Text("＋ " + t("planche_grille"), fontSize = 12.sp, color = Cyan) })
                    AssistChip(onClick = { change(Planche.genereTour(rangement.nouvelId(), tf("planche_nom_modele", modeles.size + 1))); choix = null },
                        label = { Text("＋ " + t("planche_tour"), fontSize = 12.sp, color = Cyan) })
                }
                message?.let { Text(it, color = Amber, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)) }
                if (occupe) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))

                val x = m
                if (x == null) {
                    Text(t("planche_vide"), color = TextLo, fontSize = 13.sp, modifier = Modifier.padding(vertical = 24.dp))
                    return@Column
                }
                Spacer(Modifier.height(8.dp))
                val ratio = fondApercu?.let { it.width.toFloat() / it.height } ?: x.ratio
                ApercuPlanche(x, apercu, ratio, choix, onChoix = { choix = it; if (ecussonChoisi(it) != null) onglet = 3 }, onChange = { change(it) })
                // Where the series stands: what is still to receive.
                val (recues, manquent) = Planche.suivi(x) { it in parNom }
                if (x.ajustee) Text(tf("planche_ajustee", x.cases.size, x.origine.size), color = Cyan, fontSize = 13.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                else Text(if (manquent.isEmpty() && recues > 0) tf("planche_serie_complete", recues)
                    else tf("planche_serie", recues, x.cases.size, manquent.joinToString(", ")),
                    color = if (manquent.isEmpty() && recues > 0) Cyan else TextHi, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp))
                Text(t("planche_geste"), color = TextLo, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
                Spacer(Modifier.height(8.dp))

                // Share and save just above the tabs: at hand whichever tab is open.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    Button(onClick = { exporte(true) }, enabled = !occupe, modifier = Modifier.weight(1f)) { Text(t("rec_share")) }
                    OutlinedButton(onClick = { exporte(false) }, enabled = !occupe, modifier = Modifier.weight(1f)) {
                        Text(t("export_save"), color = Cyan)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("planche_images", "planche_disposition", "planche_textes", "planche_decor").forEachIndexed { i, cle ->
                        FilterChip(selected = onglet == i, onClick = { onglet = i }, label = { Text(t(cle), fontSize = 12.sp) })
                    }
                }
                Spacer(Modifier.height(6.dp))
                when (onglet) {
                    0 -> OngletImages(x, galerie, places, choix, filtreSat, vignettes,
                        onFiltre = { filtreSat = it }, onChoix = { choix = it }, onChange = { change(it) },
                        onCalcule = { calcule(it) }, onMessage = { message = it })
                    1 -> OngletDisposition(x, choix, fondApercu, onChoix = { choix = it }, onChange = { change(it) })
                    3 -> OngletDecor(x, choix, rangement, onChoix = { choix = it }, onChange = { change(it) },
                        onFond = { choisitDecor.launch("image/*") },
                        onEcusson = { if (x.ecussons.size >= Planche.ECUSSONS_MAX) message = t("planche_ecusson_plein") else choisitEcusson.launch("image/*") })
                    else -> OngletTextes(x, choix, indicatif, nom, valeurs,
                        onIndicatif = { indicatif = it },
                        onNom = { nom = it; reglages.plancheNom = it },
                        onLocator = { locatorSaisi = it }, onDates = { datesSaisies = it },
                        onChoix = { choix = it }, onChange = { change(it) })
                }

                Spacer(Modifier.height(12.dp))
                TextButton(onClick = {
                    rangement.supprime(x); modeles = rangement.modeles(); m = modeles.lastOrNull(); choix = null
                }) { Text(t("planche_supprimer_modele"), color = Magenta, fontSize = 12.sp) }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** The selection of patch [i] (boxes are >= 0, texts -1 - index). */
private fun choixEcusson(i: Int) = -10_000 - i
/** The patch selected, if a patch is. */
private fun ecussonChoisi(c: Int?): Int? = c?.takeIf { it <= -10_000 }?.let { -10_000 - it }

/**
 * The preview, with the boxes, texts and patches to move (drag) and resize
 * (bottom-right corner; a patch also with two fingers).
 */
@Composable
private fun ApercuPlanche(
    m: Planche.Modele, apercu: Bitmap?, ratio: Float, choix: Int?,
    onChoix: (Int?) -> Unit, onChange: (Planche.Modele) -> Unit
) {
    val modele by rememberUpdatedState(m)
    val choixActuel by rememberUpdatedState(choix)
    val coinPx = with(LocalDensity.current) { 22.dp.toPx() }
    // During a gesture: the zone as it moves, drawn over the preview.
    var enCours by remember { mutableStateOf<Planche.Zone?>(null) }

    fun zoneDe(i: Int): Planche.Zone? = ecussonChoisi(i)?.let { modele.ecussons.getOrNull(it)?.zone }
        ?: if (i >= 0) modele.cases.getOrNull(i) else modele.textes.getOrNull(-1 - i)?.zone
    fun avecZone(i: Int, z: Planche.Zone): Planche.Modele {
        ecussonChoisi(i)?.let { e ->
            return modele.copy(ecussons = modele.ecussons.toMutableList().also { it[e] = it[e].copy(zone = Planche.ecussonBorne(z)) })
        }
        return if (i >= 0) modele.copy(cases = modele.cases.toMutableList().also { it[i] = z.bornee() })
        else modele.copy(textes = modele.textes.toMutableList().also { it[-1 - i] = it[-1 - i].copy(zone = z.bornee()) })
    }
    fun sous(px: Float, py: Float): Int? {
        // Patches first, then texts: they sit on top of the boxes.
        modele.ecussons.indices.reversed().firstOrNull { modele.ecussons[it].zone.contient(px, py) }?.let { return choixEcusson(it) }
        modele.textes.indices.reversed().firstOrNull { modele.textes[it].zone.contient(px, py) }?.let { return -1 - it }
        return modele.cases.indices.firstOrNull { modele.cases[it].contient(px, py) }
    }

    Box(Modifier.fillMaxWidth().aspectRatio(ratio).clip(RoundedCornerShape(6.dp)).background(Color.Black)) {
        apercu?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.FillBounds, modifier = Modifier.matchParentSize()) }
        Canvas(Modifier.matchParentSize()
            .pointerInput(Unit) {
                detectTapGestures { o -> onChoix(sous(o.x / size.width, o.y / size.height)) }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val bas = awaitFirstDown(requireUnconsumed = false)
                    val o = bas.position
                    val c = choixActuel
                    val zc = c?.let { zoneDe(it) }
                    val redim = zc != null &&
                        kotlin.math.abs(o.x - (zc.x + zc.w) * size.width) < coinPx &&
                        kotlin.math.abs(o.y - (zc.y + zc.h) * size.height) < coinPx
                    val cible = if (redim) c!! else sous(o.x / size.width, o.y / size.height) ?: return@awaitEachGesture
                    val ecusson = ecussonChoisi(cible) != null
                    var z = zoneDe(cible) ?: return@awaitEachGesture
                    var bouge = false; var cumul = Offset.Zero
                    while (true) {
                        val ev = awaitPointerEvent()
                        val appuis = ev.changes.filter { it.pressed }
                        if (appuis.isEmpty()) break
                        if (appuis.size >= 2 && ecusson) {
                            // Two fingers on a patch: pinched larger or smaller, moved with them.
                            val zoom = ev.calculateZoom(); val pan = ev.calculatePan()
                            if (!bouge) { bouge = true; onChoix(cible) }
                            z = Planche.echelle(z, zoom).let { it.copy(x = it.x + pan.x / size.width, y = it.y + pan.y / size.height) }
                            ev.changes.forEach { it.consume() }
                        } else {
                            val ch = appuis.first(); val d = ch.positionChange()
                            cumul += d
                            if (!bouge && cumul.getDistance() > viewConfiguration.touchSlop) { bouge = true; onChoix(cible) }
                            if (!bouge) continue
                            ch.consume()
                            val dx = d.x / size.width; val dy = d.y / size.height
                            z = when {
                                redim && ecusson -> (z.w + dx).coerceAtLeast(0.01f).let { w -> z.copy(w = w, h = z.h * w / z.w) }
                                redim -> z.copy(w = z.w + dx, h = z.h + dy)
                                else -> z.copy(x = z.x + dx, y = z.y + dy)
                            }
                        }
                        enCours = z
                    }
                    if (bouge) onChange(avecZone(cible, z))
                    enCours = null
                }
            }
        ) {
            fun cadre(z: Planche.Zone, coul: Color, pointille: Boolean, epais: Float) {
                drawRect(coul, Offset(z.x * size.width, z.y * size.height), Size(z.w * size.width, z.h * size.height),
                    style = Stroke(epais, pathEffect = if (pointille) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null))
            }
            m.cases.forEachIndexed { i, z -> if (i != choix) cadre(z, Color.White.copy(alpha = 0.45f), false, 2f) }
            m.textes.forEachIndexed { i, tx -> if (-1 - i != choix) cadre(tx.zone, Amber.copy(alpha = 0.7f), true, 2f) }
            m.ecussons.forEachIndexed { i, e -> if (choixEcusson(i) != choix) cadre(e.zone, Magenta.copy(alpha = 0.7f), true, 2f) }
            val c = choix
            val z = enCours ?: c?.let { zoneDe(it) }
            if (z != null) {
                cadre(z, Cyan, false, 4f)
                drawCircle(Cyan, 9.dp.toPx(), Offset((z.x + z.w) * size.width, (z.y + z.h) * size.height))
            }
        }
    }
}

@Composable
private fun OngletImages(
    m: Planche.Modele, galerie: List<Pair<File, SstvMeta.SstvShot>>,
    places: Map<Int, Pair<File, SstvMeta.SstvShot>>, choix: Int?, filtreSat: String,
    vignettes: MutableMap<String, Bitmap>,
    onFiltre: (String) -> Unit, onChoix: (Int?) -> Unit, onChange: (Planche.Modele) -> Unit,
    onCalcule: (() -> Planche.Modele) -> Unit, onMessage: (String) -> Unit
) {
    val sats = galerie.map { it.second.satName }.filter { it.isNotBlank() }.distinct()
    val liste = galerie.filter { filtreSat.isBlank() || it.second.satName == filtreSat }
    fun signatures() = PlancheRendu.signatures(liste.map { it.first })
    // How many pictures: the template's boxes, or the sheet fitted to fewer (the largest they can be).
    val max = Planche.nombreMax(m); val n = if (m.ajustee) m.cases.size else 0
    Text(t("planche_nb_images"), color = TextLo, fontSize = 11.sp)
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        (listOf(0, 1, 2, 3, 4, 6, 8, 9, 12, 16, n).filter { it <= max }.distinct().sorted()).forEach { k ->
            FilterChip(selected = k == n, onClick = {
                onCalcule { Planche.avecNombre(m, k, liste.map { it.second }, signatures()) }
            }, label = { Text(if (k == 0) tf("planche_nb_modele", max) else "$k", fontSize = 12.sp) })
        }
    }
    Text(if (choix != null && choix >= 0) tf("planche_choisir_image", choix + 1, m.cases.size) else t("planche_toucher_case"),
        color = TextHi, fontSize = 12.sp)
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = filtreSat.isBlank(), onClick = { onFiltre("") }, label = { Text(t("sstv_filter_all"), fontSize = 11.sp) })
        sats.forEach { s -> FilterChip(selected = filtreSat == s, onClick = { onFiltre(s) }, label = { Text(s, fontSize = 11.sp) }) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = {
            onCalcule { Planche.remplit(m, liste.map { it.second }, signatures()) }
        }) { Text(t("planche_remplir"), color = Cyan, fontSize = 11.sp) }
        if (choix != null && choix >= 0 && !m.ajustee) OutlinedButton(onClick = { onChange(m.copy(images = m.images - choix)) }) {
            Text(t("planche_vider_case"), color = Cyan, fontSize = 11.sp)
        }
        OutlinedButton(onClick = { onChange(m.copy(images = emptyMap())) }) { Text(t("planche_tout_vider"), color = Magenta, fontSize = 11.sp) }
    }
    // The picture of the box chosen, one place earlier or later.
    if (choix != null && choix >= 0 && m.images[choix] != null) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (choix > 0) OutlinedButton(onClick = { onChange(Planche.echange(m, choix, choix - 1)); onChoix(choix - 1) }) {
            Text(t("planche_avant"), color = Cyan, fontSize = 11.sp)
        }
        if (choix < m.cases.size - 1) OutlinedButton(onClick = { onChange(Planche.echange(m, choix, choix + 1)); onChoix(choix + 1) }) {
            Text(t("planche_apres"), color = Cyan, fontSize = 11.sp)
        }
    }
    Text(t(if (m.ajustee) "planche_aide_ajustee" else "planche_ordre_aide"), color = TextLo, fontSize = 10.sp,
        modifier = Modifier.padding(vertical = 4.dp))
    val utilise = m.images.entries.associate { it.value to it.key }
    // Large, two a row: here a picture is only chosen, so it gets the room to be seen.
    val heure = remember { java.text.SimpleDateFormat("dd/MM/yy HH:mm", java.util.Locale.getDefault()) }
    liste.chunked(2).forEach { rang ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            rang.forEach { (f, s) ->
                // Read off the screen's thread: a week of ISS passes is many pictures.
                val b by produceState(vignettes[f.name], f.name) {
                    if (value == null) value = withContext(Dispatchers.IO) { PlancheRendu.charge(f, 300, leger = true) }
                        ?.also { vignettes[f.name] = it }
                }
                val place = utilise[f.name]
                Column(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(SpaceCard)
                    .border(3.dp, if (place != null) Cyan else Color.Transparent, RoundedCornerShape(8.dp))
                    .clickable {
                        // A fitted sheet: on it or off it, the sheet fitted again.
                        if (m.ajustee) {
                            val n2 = Planche.bascule(m, f.name)
                            if (n2 == m && place == null) onMessage(tf("planche_pleine", m.cases.size)) else { onChange(n2); onChoix(null) }
                            return@clickable
                        }
                        // Into the box chosen, else the first empty one.
                        val c = choix?.takeIf { it >= 0 } ?: m.cases.indices.firstOrNull { it !in m.images.keys } ?: return@clickable
                        // One picture per box: taken from where it was before.
                        val im = m.images.filterValues { it != f.name } + (c to f.name)
                        onChange(m.copy(images = im))
                        onChoix((c + 1).takeIf { it < m.cases.size } ?: c)
                    }) {
                    Box {
                        b?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f)) }
                            ?: Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f))
                        if (place != null) Text("${place + 1}/${m.cases.size}", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(4.dp).background(Cyan, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
                    }
                    Column(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
                        Text(s.satName.ifBlank { "SSTV" } + " · " + s.mode, color = TextHi, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text(heure.format(java.util.Date(s.timeMs)), color = TextLo, fontSize = 11.sp)
                        val marques = listOfNotNull(
                            if (s.source == "live") t("planche_en_direct") else t("planche_redecodee"),
                            if (!s.complete) t("sstv_partial") else null)
                        Text(marques.joinToString(" · "), color = if (s.complete) TextLo else Amber, fontSize = 10.sp)
                    }
                }
            }
            repeat(2 - rang.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun OngletDisposition(
    m: Planche.Modele, choix: Int?, fond: Bitmap?, onChoix: (Int?) -> Unit, onChange: (Planche.Modele) -> Unit
) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (fond != null) OutlinedButton(onClick = {
            val (c, _) = detecte(fond)
            val d = Planche.duModele(m)
            if (c.isNotEmpty()) { onChange(d.copy(cases = c, images = d.images.filterKeys { it < c.size })); onChoix(null) }
        }) { Text(t("planche_detecter"), color = Cyan, fontSize = 11.sp) }
        OutlinedButton(onClick = {
            val z = Planche.Zone(0.4f, 0.4f, 0.16f, 0.16f * m.ratio * 0.75f).bornee()
            onChange(m.copy(cases = m.cases + z)); onChoix(m.cases.size)
        }) { Text("＋ " + t("planche_case"), color = Cyan, fontSize = 11.sp) }
        if (choix != null && choix >= 0) OutlinedButton(onClick = {
            // The pictures after it move up one box.
            val im = m.images.filterKeys { it != choix }.mapKeys { (k, _) -> if (k > choix) k - 1 else k }
            onChange(m.copy(cases = m.cases.filterIndexed { i, _ -> i != choix }, images = im)); onChoix(null)
        }) { Text(t("planche_supprimer_case"), color = Magenta, fontSize = 11.sp) }
    }
    Text(t("planche_nb_cases"), color = TextLo, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(4, 6, 8, 9, 10, 12, 16).forEach { n ->
            FilterChip(selected = m.cases.size == n && !m.ajustee, onClick = {
                // Over the same area as the template's boxes, as large as they can be: the template's new layout.
                val d = Planche.duModele(m)
                val zone = Planche.englobe(d.cases) ?: Planche.Zone(0.33f, 0.03f, 0.65f, 0.94f)
                val ratio = fond?.let { it.width.toFloat() / it.height } ?: m.ratio
                onChange(d.copy(cases = Planche.grilleOptimale(n, zone, ratio, Planche.aspectCase(m.legende)),
                    images = d.images.filterKeys { it < n })); onChoix(null)
            }, label = { Text("$n", fontSize = 12.sp) })
        }
    }
    // The line under each picture changes the boxes' shape: a fitted sheet is fitted again.
    Interrupteur(t("planche_legende"), t("planche_legende_desc"), m.legende) { onChange(Planche.reajuste(m.copy(legende = it))) }
    Interrupteur(t("planche_numeros"), t("planche_numeros_desc"), m.numeros) { onChange(m.copy(numeros = it)) }
}

@Composable
private fun OngletTextes(
    m: Planche.Modele, choix: Int?, indicatif: String, nom: String, v: PlancheRendu.Valeurs,
    onIndicatif: (String) -> Unit, onNom: (String) -> Unit, onLocator: (String) -> Unit, onDates: (String) -> Unit,
    onChoix: (Int?) -> Unit, onChange: (Planche.Modele) -> Unit
) {
    @Composable
    fun Champ(lib: String, valeur: String, sur: (String) -> Unit) = OutlinedTextField(
        value = valeur, onValueChange = sur, label = { Text(lib, fontSize = 11.sp) }, singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp))
    Champ(t("planche_nom_du_modele"), m.nom) { onChange(m.copy(nom = it)) }
    Champ(t("planche_t_titre"), m.titre.ifBlank { v.titre }) { onChange(m.copy(titre = it)) }
    Champ(t("planche_t_indicatif"), indicatif, onIndicatif)
    Champ(t("planche_t_nom"), nom, onNom)
    Champ(t("planche_t_locator"), v.locator, onLocator)
    Champ(t("planche_t_dates"), v.dates, onDates)

    Text(t("planche_ajouter_texte"), color = TextLo, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(Planche.Champ.TITRE to "planche_t_titre", Planche.Champ.INDICATIF to "planche_t_indicatif",
            Planche.Champ.NOM_LOCATOR to "planche_t_nom_locator", Planche.Champ.DATES to "planche_t_dates",
            Planche.Champ.LIBRE to "planche_t_libre").forEach { (c, cle) ->
            AssistChip(onClick = {
                val tx = Planche.Texte(c, Planche.Zone(0.35f, 0.45f, 0.3f, 0.08f), Planche.BLANC, 0,
                    if (c == Planche.Champ.LIBRE) "73" else "")
                onChange(m.copy(textes = m.textes + tx)); onChoix(-1 - m.textes.size)
            }, label = { Text("＋ " + t(cle), fontSize = 11.sp, color = Cyan) })
        }
    }
    val i = choix?.takeIf { it < 0 }?.let { -1 - it }
    val tx = i?.let { m.textes.getOrNull(it) } ?: return
    fun maj(n: Planche.Texte) = onChange(m.copy(textes = m.textes.toMutableList().also { it[i] = n }))
    Spacer(Modifier.height(6.dp))
    Text(t("planche_texte_choisi"), color = TextHi, fontSize = 12.sp)
    if (tx.champ == Planche.Champ.LIBRE) Champ(t("planche_t_libre"), tx.libre) { maj(tx.copy(libre = it)) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 4.dp)) {
        Text(t("planche_couleur"), color = TextLo, fontSize = 11.sp)
        Planche.COULEURS.forEach { c ->
            Box(Modifier.size(26.dp).clip(CircleShape).background(Color(c))
                .border(if (tx.couleur == c) 3.dp else 1.dp, if (tx.couleur == c) Cyan else TextLo, CircleShape)
                .clickable { maj(tx.copy(couleur = c)) })
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(t("planche_fond_texte"), color = TextLo, fontSize = 11.sp)
        listOf(0 to "planche_fond_aucun", Planche.VOILE_BLANC to "planche_fond_clair", Planche.VOILE_NOIR to "planche_fond_sombre")
            .forEach { (f, cle) ->
                FilterChip(selected = tx.fond == f, onClick = { maj(tx.copy(fond = f)) }, label = { Text(t(cle), fontSize = 11.sp) })
            }
    }
    TextButton(onClick = { onChange(m.copy(textes = m.textes.filterIndexed { j, _ -> j != i })); onChoix(null) }) {
        Text(t("planche_supprimer_texte"), color = Magenta, fontSize = 12.sp)
    }
}

@Composable
private fun OngletDecor(
    m: Planche.Modele, choix: Int?, rangement: Planche.Rangement, onChoix: (Int?) -> Unit, onChange: (Planche.Modele) -> Unit,
    onFond: () -> Unit, onEcusson: () -> Unit
) {
    Text(t("planche_fond_planche"), color = TextHi, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    if (m.fond.isNotBlank()) Text(t("planche_fond_modele"), color = TextLo, fontSize = 11.sp, modifier = Modifier.padding(vertical = 4.dp))
    else {
        // The backgrounds SatMe offers, small, and one's own when there is one.
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val ids = PlancheDecor.INTEGRES.map { it.id to t(it.cle) } +
                listOfNotNull(m.decor.takeIf { it.isNotBlank() && PlancheDecor.integre(it) == null }?.let { it to t("planche_fond_perso") })
            ids.forEach { (id, nom) ->
                val b by produceState<Bitmap?>(null, id) {
                    value = withContext(Dispatchers.IO) {
                        PlancheDecor.integre(id)?.let { PlancheRendu.apercuDecor(it, 240) } ?: rangement.fichier(id)?.let { PlancheRendu.charge(it, 240) }
                    }
                }
                Column(Modifier.width(110.dp).clip(RoundedCornerShape(8.dp)).background(SpaceCard)
                    .border(3.dp, if (m.decor == id) Cyan else Color.Transparent, RoundedCornerShape(8.dp))
                    .clickable { onChange(m.copy(decor = id)) }) {
                    b?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(1.414f)) }
                        ?: Box(Modifier.fillMaxWidth().aspectRatio(1.414f))
                    Text(nom, color = TextHi, fontSize = 10.sp, maxLines = 2, modifier = Modifier.padding(4.dp))
                }
            }
        }
        OutlinedButton(onClick = onFond) { Text(t("planche_fond_choisir"), color = Cyan, fontSize = 11.sp) }
        Text(t("planche_fond_aide"), color = TextLo, fontSize = 10.sp, modifier = Modifier.padding(vertical = 4.dp))
    }

    Spacer(Modifier.height(8.dp))
    Text(tf("planche_ecussons", m.ecussons.size, Planche.ECUSSONS_MAX), color = TextHi, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        m.ecussons.indices.forEach { i ->
            FilterChip(selected = ecussonChoisi(choix) == i, onClick = { onChoix(choixEcusson(i)) },
                label = { Text("${i + 1}", fontSize = 12.sp) })
        }
        if (m.ecussons.size < Planche.ECUSSONS_MAX) AssistChip(onClick = onEcusson,
            label = { Text("＋ " + t("planche_ecusson_ajouter"), fontSize = 12.sp, color = Cyan) })
    }
    Text(t("planche_ecusson_aide"), color = TextLo, fontSize = 10.sp)
    val i = ecussonChoisi(choix)
    val e = i?.let { m.ecussons.getOrNull(it) }
    if (i == null || e == null) {
        if (m.ecussons.isNotEmpty()) Text(t("planche_ecusson_toucher"), color = TextLo, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        return
    }
    fun maj(n: Planche.Ecusson) = onChange(m.copy(ecussons = m.ecussons.toMutableList().also { it[i] = n }))
    Spacer(Modifier.height(6.dp))
    Text(tf("planche_ecusson_choisi", i + 1), color = TextHi, fontSize = 12.sp)
    Interrupteur(t("planche_ecusson_contour"), t("planche_ecusson_contour_desc"), e.sansContour) { maj(e.copy(sansContour = it)) }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { maj(e.copy(zone = Planche.echelle(e.zone, 1f / 1.15f))) }) { Text(t("planche_ecusson_moins"), color = Cyan, fontSize = 11.sp) }
        OutlinedButton(onClick = { maj(e.copy(zone = Planche.echelle(e.zone, 1.15f))) }) { Text(t("planche_ecusson_plus"), color = Cyan, fontSize = 11.sp) }
    }
    TextButton(onClick = { onChange(m.copy(ecussons = m.ecussons.filterIndexed { j, _ -> j != i })); onChoix(null) }) {
        Text(t("planche_ecusson_supprimer"), color = Magenta, fontSize = 12.sp)
    }
}

@Composable
private fun Interrupteur(titre: String, desc: String, valeur: Boolean, sur: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { sur(!valeur) }.padding(vertical = 4.dp)) {
        Column(Modifier.weight(1f)) {
            Text(titre, color = TextHi, fontSize = 13.sp)
            Text(desc, color = TextLo, fontSize = 10.sp)
        }
        Switch(checked = valeur, onCheckedChange = null)
    }
}

/** The boxes and text frames of a template, looked for on a 600-pixel copy. */
private fun detecte(fond: Bitmap): Pair<List<Planche.Zone>, List<Pair<Planche.Zone, Int>>> {
    val w = 600; val h = (600f * fond.height / fond.width).toInt().coerceAtLeast(1)
    val p = Bitmap.createScaledBitmap(fond, w, h, true)
    val px = IntArray(w * h); p.getPixels(px, 0, w, 0, 0, w, h)
    return Planche.detecteCases(px, w, h) to Planche.detecteCadresTexte(px, w, h)
}

/** An imported template: kept (at most 4000 pixels wide), its boxes and frames found. */
private fun importeFond(ctx: android.content.Context, r: Planche.Rangement, uri: android.net.Uri, nom: String): Planche.Modele? = runCatching {
    val id = r.nouvelId()
    val brut = File(ctx.cacheDir, "planche_import")
    ctx.contentResolver.openInputStream(uri)?.use { i -> brut.outputStream().use { i.copyTo(it) } } ?: return null
    val b = PlancheRendu.charge(brut, 4000) ?: return null
    brut.delete()
    val nomFond = "fond_$id.jpg"
    File(r.dossier, nomFond).outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 92, it) }
    val (cases, cadres) = detecte(b)
    Planche.genereImporte(id, nom, nomFond, b.width.toFloat() / b.height, cases, cadres)
}.getOrNull()
