/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.ui

import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.f4ioz.satcombo.MainViewModel
import fr.f4ioz.satcombo.UiState
import fr.f4ioz.satcombo.data.Extensions
import fr.f4ioz.satcombo.data.FlagDraw
import fr.f4ioz.satcombo.data.Flags
import fr.f4ioz.satcombo.data.QthPhoto
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.i18n.tf
import fr.f4ioz.satcombo.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "Photo QRV": shoot (or pick) a picture of the operating site and burn the
 * station identity into it — big locator, callsign, and optionally the date,
 * the distance to the neighbouring grid squares, the coordinates, the satellite
 * being worked, its polar plot and a discreet SatMe mark.
 *
 * Capture goes through the system camera app (ACTION_IMAGE_CAPTURE) and the
 * photo picker, so the app never asks for the CAMERA or storage permissions.
 * The shot is kept as-is in the app's private storage: the overlay is redrawn
 * on the fly, so an option can be changed later without losing the picture.
 */
@Composable
fun PhotoScreen(ui: UiState, vm: MainViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var source by remember { mutableStateOf<Bitmap?>(null) }
    var rendered by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var stampMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var toast by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    // Camera destination: a NEW file per capture (some camera apps refuse to
    // overwrite). NOT a remember{}: our process can be recreated or killed
    // while the camera is on top (rotating to landscape does it), so the path
    // lives in the ViewModel/preferences.

    /** Accepts a decoded shot, or reports why it could not be decoded. */
    fun accept(bmp: Bitmap?, why: String, fallback: (() -> Unit)? = null) {
        if (bmp == null) {
            busy = false
            if (fallback != null) { error = ""; fallback() } else error = why
            return
        }
        stampMs = System.currentTimeMillis()
        source = bmp
        busy = false
        error = ""
        vm.keepPhoto(bmp, stampMs)
    }

    /** Reads a file WE own — no ContentResolver, so no media permission needed. */
    fun ingestFile(f: File) {
        busy = true; toast = ""; error = ""
        scope.launch {
            val bmp = withContext(Dispatchers.IO) { QthPhoto.loadFile(f) }
            accept(bmp, t("photo_err_read") + " · FILE")
        }
    }

    /** Reads whatever URI the picker or the camera handed back. */
    fun ingest(uri: Uri?, tag: String, fallback: (() -> Unit)? = null) {
        if (uri == null) { error = t("photo_err_none"); return }
        busy = true; toast = ""; error = ""
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { QthPhoto.load(ctx, uri) } }
            // Exception class appended on purpose: the only way to tell a
            // permission refusal from a codec failure on a user's phone.
            val why = t("photo_err_read") + " · " + tag +
                (r.exceptionOrNull()?.let { " " + it.javaClass.simpleName } ?: "")
            accept(r.getOrNull(), why, fallback)
        }
    }

    // Last resort: ACTION_OPEN_DOCUMENT always returns a readable URI, even on
    // ROMs whose gallery hands out unreadable media URIs.
    val openDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) ingest(uri, "DOC") else error = t("photo_err_none") }

    // Not TakePicture(): the intent must carry the URI write grant, and some
    // camera apps report CANCELED after writing the file. The file decides,
    // not the result code.
    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val f = vm.pendingCaptureFile()
        vm.clearCapture()
        val thumb = runCatching {
            @Suppress("DEPRECATION")
            res.data?.extras?.get("data") as? Bitmap
        }.getOrNull()
        when {
            // 1) the file we handed to the camera — always readable by us
            f != null && QthPhoto.hasContent(f) -> ingestFile(f)
            // 2) the URI the camera returned instead (MIUI & co)
            res.data?.data != null -> ingest(res.data?.data, "APN") {
                if (thumb != null) accept(thumb, "") else error = t("photo_err_camera")
            }
            // 3) the low-resolution thumbnail some camera apps put in the extras
            thumb != null -> accept(thumb, "")
            else -> error = t("photo_err_camera")
        }
    }

    // NO automatic fallback: chaining the document picker opened a second
    // picker over the first and nothing could be selected. A cancel stays a
    // cancel; the file explorer is an explicit button in the error card.
    val pickPhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) ingest(uri, "GAL")
    }

    // Coming back to the page reopens the last kept picture.
    LaunchedEffect(Unit) {
        // A shot taken while the page was being rebuilt has no result to
        // deliver; the file on disk proves it exists. Safety net for landscape
        // captures.
        val pend = vm.pendingCaptureFile()
        if (pend != null && QthPhoto.hasContent(pend)) {
            vm.clearCapture()
            ingestFile(pend)
            return@LaunchedEffect
        }
        vm.clearCapture()
        // busy: a capture is already being decoded; reopening would drop it.
        if (source == null && !busy) {
            val last = ui.photoCurrentId ?: ui.qrvPhotos.firstOrNull()?.id
            if (last != null) {
                busy = true
                // restoreMeta = false when opened from a satellite: the picture
                // comes back, its old satellite and pass do not.
                vm.openQrvPhoto(last, restoreMeta = !ui.photoPinnedSat) { bmp ->
                    // Never over the picture that has just been shot.
                    if (bmp != null && source == null) { stampMs = last; source = bmp }
                    // Say so: a silent failure left a blank page under the
                    // old satellite name.
                    if (bmp == null) error = t("photo_err_read") + " · KEPT"
                    busy = false
                }
            }
        }
    }

    // Re-render whenever the picture or any overlay option changes.
    LaunchedEffect(
        source, ui.callsign, ui.photoShowCallsign, ui.photoShowDate, ui.photoShowGrids,
        ui.photoShowCoords, ui.photoShowSat, ui.photoShowPolar,
        ui.photoSatName, ui.photoTrack, ui.useUtc, ui.observer,
        ui.photoShowPass, ui.photoPolarScale, ui.photoSatLabelScale,
        ui.nearGridMeters, ui.photoPassAosMs,
        ui.photoLoc4, ui.photoShowAlt, ui.photoCallColor, ui.photoCallScale,
        ui.photoNearCount, ui.photoFlag, ui.carte.flagRight,
        ui.extensions, ui.units, ui.carte
    ) {
        // No photo: plain background. Callsign, locator, POTA and map still
        // make a usable QRV card.
        val src = source ?: Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888)
            .also { android.graphics.Canvas(it).drawColor(ui.carte.fondUni) }
        busy = true
        val opts = vm.photoOptions(stampMs)
        rendered = withContext(Dispatchers.Default) {
            runCatching { QthPhoto.render(src, opts) }.getOrNull()
        }
        if (rendered == null) error = t("photo_err_render")
        busy = false
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ---- capture buttons ----
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    error = ""
                    val f = File(ctx.cacheDir, "photos").apply { mkdirs() }
                        .resolve("capture_${System.currentTimeMillis()}.jpg")
                    vm.armCapture(f)
                    val ok = runCatching {
                        takePicture.launch(QthPhoto.captureIntent(ctx, QthPhoto.uri(ctx, f)))
                    }.isSuccess
                    if (!ok) error = t("photo_err_nocam")
                },
                colors = ButtonDefaults.buttonColors(containerColor = Cyan),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.PhotoCamera, null,
                    tint = if (isDarkTheme()) Color(0xFF00201D) else Color.White,
                    modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(t("photo_take"),
                    color = if (isDarkTheme()) Color(0xFF00201D) else Color.White,
                    fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            OutlinedButton(
                onClick = {
                    error = ""
                    pickPhoto.launch(PickVisualMediaRequest(
                        ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.PhotoLibrary, null, tint = Cyan, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(t("photo_pick"), color = Cyan, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }

        if (error.isNotBlank()) {
            Surface(color = Magenta.copy(alpha = 0.16f), shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(error, color = Magenta, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    // Document picker, only on explicit request.
                    OutlinedButton(
                        onClick = { error = ""; openDoc.launch(arrayOf("image/*")) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.FolderOpen, null, tint = Amber,
                            modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(t("photo_pick_files"), color = Amber,
                            fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }

        // ---- station line + satellite picker ----
        Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // Callsign above the locators: on a four-square corner the
                // locator line fills the width and truncated the callsign.
                Text(
                    if (ui.callsign.isBlank()) t("photo_no_callsign") else ui.callsign.uppercase(),
                    color = if (ui.callsign.isBlank()) Amber else TextHi,
                    fontWeight = FontWeight.Bold, fontSize = 15.sp,
                    letterSpacing = 1.sp)
                Text(vm.myLocatorFull(), color = Cyan, fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace, fontSize = 19.sp,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    maxLines = 1, softWrap = false)
                SatPicker(ui, vm)
                PassPicker(ui, vm)
                Text(t("photo_intro"), color = TextLo, fontSize = 12.sp)
            }
        }

        // Gestures read the map through this: `pointerInput` does not restart
        // on recomposition, so reading `ui.carte` directly would be stale.
        val carteMaj = rememberUpdatedState(ui.carte)

        // The QTH can change while the screen is open (manual locator, GPS
        // fix); the map follows.
        LaunchedEffect(ui.observer?.latDeg, ui.observer?.lonDeg, ui.carte.affichee) {
            if (ui.carte.affichee) vm.chargeCartePays()
        }

        // ---- preview ----
        Surface(color = SpaceCard, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().heightIn(min = 180.dp), contentAlignment = Alignment.Center) {
                val bmp = rendered
                when {
                    busy && bmp == null -> CircularProgressIndicator(color = Cyan, strokeWidth = 2.dp,
                        modifier = Modifier.size(28.dp))
                    bmp == null -> Text(t("photo_empty"), color = TextLo, fontSize = 13.sp,
                        modifier = Modifier.padding(24.dp))
                    else -> Image(bmp.asImageBitmap(), contentDescription = null,
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth()
                            .aspectRatio(bmp.width.toFloat() / bmp.height.coerceAtLeast(1))
                            // Drag and pinch the map on the preview; sliders
                            // below remain for fine adjustment.
                            .pointerInput(ui.carte.affichee) {
                                if (!ui.carte.affichee) return@pointerInput
                                // 1. Consume in the Initial pass, or the
                                //    scrolling column steals the drag.
                                // 2. **Accumulate locally.** The gesture block
                                //    captures the `ui` of the composition that
                                //    installed it, so re-reading `ui.carte.x`
                                //    restarted from the same point each time
                                //    (jitter, jumps back).
                                awaitEachGesture {
                                    val premier = awaitFirstDown(
                                        requireUnconsumed = false,
                                        pass = PointerEventPass.Initial)
                                    premier.consume()
                                    var x = carteMaj.value.x
                                    var y = carteMaj.value.y
                                    var taille = carteMaj.value.taille
                                    var precedent = premier.position
                                    var ecartInitial = 0f
                                    var tailleInitiale = taille
                                    while (true) {
                                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                                        val actifs = ev.changes.filter { it.pressed }
                                        if (actifs.isEmpty()) break
                                        actifs.forEach { it.consume() }

                                        if (actifs.size >= 2) {
                                            val d = (actifs[0].position - actifs[1].position)
                                                .getDistance()
                                            if (ecartInitial == 0f) {
                                                ecartInitial = d; tailleInitiale = taille
                                            } else if (ecartInitial > 1f) {
                                                taille = (tailleInitiale * (d / ecartInitial))
                                                    .coerceIn(0.15f, 0.9f)
                                                vm.setPhotoCarteTaille(taille)
                                            }
                                        } else {
                                            ecartInitial = 0f
                                        }

                                        val p0 = actifs[0].position
                                        val dx = p0.x - precedent.x
                                        val dy = p0.y - precedent.y
                                        precedent = p0
                                        if (dx != 0f || dy != 0f) {
                                            x = (x + dx / size.width).coerceIn(0.1f, 0.9f)
                                            y = (y + dy / size.height).coerceIn(0.1f, 0.9f)
                                            vm.setPhotoCartePosition(x, y)
                                        }
                                    }
                                }
                            })
                }
                if (busy && bmp != null) {
                    CircularProgressIndicator(color = Cyan, strokeWidth = 2.dp,
                        modifier = Modifier.size(24.dp))
                }
            }
        }

        // ---- actions on the rendered picture ----
        rendered?.let { bmp ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        scope.launch {
                            val file = withContext(Dispatchers.IO) {
                                QthPhoto.writeCache(ctx, bmp, stampMs)
                            }
                            val uri = QthPhoto.uri(ctx, file)
                            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "image/jpeg"
                                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                putExtra(android.content.Intent.EXTRA_TEXT,
                                    buildString {
                                        if (ui.callsign.isNotBlank()) append(ui.callsign.uppercase()).append(" · ")
                                        append(vm.myLocator())
                                        if (ui.photoSatName.isNotBlank()) append(" · ").append(ui.photoSatName)
                                    })
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            ctx.startActivity(android.content.Intent.createChooser(send, t("photo_share")))
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Aurora),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Share, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(t("photo_share"), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val name = "SatMe_${vm.myLocator()}_$stampMs.jpg"
                            val uri = withContext(Dispatchers.IO) {
                                QthPhoto.saveToGallery(ctx, bmp, name)
                            }
                            toast = if (uri != null) t("photo_saved") else t("photo_save_failed")
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Save, null, tint = Cyan, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(t("photo_save"), color = Cyan, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }

            // Attach to the running field session, so the PDF sheet carries it.
            val running = ui.activations.firstOrNull { it.running }
            if (running != null) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val file = withContext(Dispatchers.IO) {
                                QthPhoto.writeCache(ctx, bmp, stampMs)
                            }
                            vm.updateActivation(running.copy(photoPath = file.absolutePath))
                            toast = t("photo_attached")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(tf("photo_attach", running.name.ifBlank { running.locator }),
                        color = Amber, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }

        if (toast.isNotBlank()) {
            Text(toast, color = Aurora, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }

        // ---- kept pictures ----
        if (ui.qrvPhotos.isNotEmpty()) {
            Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 12.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(t("photo_kept"), color = TextLo, fontSize = 11.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp,
                            modifier = Modifier.weight(1f))
                        Text("${ui.qrvPhotos.size}", color = Cyan, fontSize = 11.sp,
                            fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.horizontalScroll(rememberScrollState())
                            .padding(horizontal = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        ui.qrvPhotos.forEach { p ->
                            KeptThumb(
                                photo = p, selected = p.id == ui.photoCurrentId,
                                useUtc = ui.useUtc,
                                file = vm.qrvPhotoFile(p.id),
                                onOpen = {
                                    busy = true; error = ""
                                    vm.openQrvPhoto(p.id) { bmp ->
                                        if (bmp != null) { stampMs = p.id; source = bmp }
                                        else error = t("photo_err_read")
                                        busy = false
                                    }
                                },
                                onDelete = {
                                    if (p.id == ui.photoCurrentId) { source = null; rendered = null }
                                    vm.deleteQrvPhoto(p.id)
                                }
                            )
                        }
                    }
                    Text(t("photo_kept_hint"), color = TextLo, fontSize = 11.sp,
                        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp))
                }
            }
        }

        // ---- overlay options ----
        Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 6.dp)) {
                Text(t("photo_options"), color = TextLo, fontSize = 11.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp,
                    modifier = Modifier.padding(start = 14.dp, top = 8.dp, bottom = 4.dp))
                // Locator length: "JN18" alone reads from much further away
                // than "JN18fv".
                Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                    Text(t("photo_opt_loc"), color = TextHi, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 4.dp)) {
                        val full = vm.myLocator().uppercase()
                        listOf(true to full.take(4), false to full).forEach { (short, lbl) ->
                            FilterChip(
                                selected = ui.photoLoc4 == short,
                                onClick = { vm.setPhotoOption("loc4", short) },
                                label = { Text(lbl, fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Cyan.copy(alpha = 0.25f),
                                    selectedLabelColor = Cyan)
                            )
                        }
                    }
                }
                PhotoToggle(t("photo_opt_call"), ui.photoShowCallsign) { vm.setPhotoOption("call", it) }
                // Callsign colour and size: the original amber vanishes on a
                // sunset sky or on snow.
                if (ui.photoShowCallsign) {
                    Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 6.dp)) {
                        Text(t("photo_opt_call_color"), color = TextLo, fontSize = 12.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(top = 6.dp)) {
                            CALL_COLORS.forEach { argb ->
                                val sel = ui.photoCallColor == argb
                                Box(
                                    Modifier.size(28.dp).clip(CircleShape)
                                        .background(Color(argb))
                                        .border(
                                            if (sel) 3.dp else 1.dp,
                                            if (sel) Cyan else TextLo.copy(alpha = 0.45f),
                                            CircleShape)
                                        .clickable { vm.setPhotoCallColor(argb) })
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 10.dp)) {
                            Text(t("photo_opt_call_size"), color = TextLo, fontSize = 12.sp,
                                modifier = Modifier.weight(1f))
                            Text("${(ui.photoCallScale * 100).toInt()} %", color = Cyan,
                                fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace)
                        }
                        Slider(
                            value = ui.photoCallScale,
                            onValueChange = { vm.setPhotoCallScale(it) },
                            valueRange = 0.6f..2.5f,
                            colors = SliderDefaults.colors(
                                thumbColor = Cyan, activeTrackColor = Cyan,
                                inactiveTrackColor = SpaceSurface)
                        )
                        // Flags beside the callsign. The left one is open to
                        // everyone; the right one (region, expedition, home
                        // country) stays behind an extension.
                        val catalogue = Flags.catalogue(Extensions.BZH in ui.extensions)
                        FlagPicker(t("photo_opt_flag"), ui.photoFlag, catalogue) {
                            vm.setPhotoFlag(it)
                        }
                        if (Extensions.FLAG in ui.extensions) {
                            FlagPicker(t("photo_opt_flag_right"), ui.carte.flagRight, catalogue) {
                                vm.setPhotoFlagRight(it)
                            }
                        }
                    }
                }
                Text(t("photo_sec_pass"), color = Cyan, fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                PhotoToggle(t("photo_opt_date"), ui.photoShowDate) { vm.setPhotoOption("date", it) }
                PhotoToggle(t("photo_opt_grids"), ui.photoShowGrids) { vm.setPhotoOption("grids", it) }
                // How many neighbouring squares to print, nearest first. Four
                // was the old fixed value; a corner wants more, a centre none.
                if (ui.photoShowGrids) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(t("photo_opt_near_count"), color = TextLo, fontSize = 12.sp,
                                modifier = Modifier.weight(1f))
                            Text("${ui.photoNearCount}", color = Cyan, fontSize = 12.sp,
                                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        }
                        Slider(
                            value = ui.photoNearCount.toFloat(),
                            onValueChange = { vm.setPhotoNearCount(it.toInt()) },
                            valueRange = 0f..8f,
                            steps = 7,
                            colors = SliderDefaults.colors(
                                thumbColor = Cyan, activeTrackColor = Cyan,
                                inactiveTrackColor = SpaceSurface)
                        )
                    }
                }
                PhotoToggle(t("photo_opt_coords"), ui.photoShowCoords) { vm.setPhotoOption("coords", it) }
                // Disabled without an altitude (manual position): an active
                // switch that prints nothing looks broken.
                PhotoToggle(t("photo_opt_alt"), ui.photoShowAlt,
                    enabled = ui.observer?.altMeters?.let { it != 0.0 } == true) {
                    vm.setPhotoOption("alt", it)
                }
                PhotoToggle(t("photo_opt_sat"), ui.photoShowSat) { vm.setPhotoOption("sat", it) }
                PhotoToggle(t("photo_opt_pass"), ui.photoShowPass,
                    enabled = ui.photoPassAosMs > 0L) { vm.setPhotoOption("pass", it) }
                // Announced frequency, free text next to the pass time
                // ("145.950 FM", "TX 435.100"). Empty prints nothing.
                Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                    OutlinedTextField(
                        value = ui.carte.qrgTexte,
                        onValueChange = { vm.setPhotoQrgTexte(it) },
                        label = { Text(t("photo_opt_qrg_field"), fontSize = 12.sp) },
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(
                            fontFamily = FontFamily.Monospace, fontSize = 15.sp),
                        modifier = Modifier.fillMaxWidth())
                    Text(t("photo_opt_pass_size"), color = TextHi, fontSize = 13.sp,
                        modifier = Modifier.padding(top = 6.dp))
                    Slider(value = ui.carte.passScale,
                        onValueChange = { vm.setPhotoPassScale(it) },
                        valueRange = 0.6f..2.5f,
                        colors = SliderDefaults.colors(thumbColor = Cyan,
                            activeTrackColor = Cyan, inactiveTrackColor = SpaceSurface))
                }
                PhotoToggle(t("photo_opt_polar"), ui.photoShowPolar,
                    enabled = ui.photoTrack.size > 1) { vm.setPhotoOption("polar", it) }

                // Polar plot size: a phone and a tablet want different discs.
                if (ui.photoShowPolar && ui.photoTrack.size > 1) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(t("photo_opt_polar_size"), color = TextHi, fontSize = 13.sp,
                                modifier = Modifier.weight(1f))
                            Text("${(ui.photoPolarScale * 100).toInt()} %", color = Cyan,
                                fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace)
                        }
                        Slider(
                            value = ui.photoPolarScale,
                            onValueChange = { vm.setPhotoPolarScale(it) },
                            valueRange = 0.5f..2.2f,
                            colors = SliderDefaults.colors(
                                thumbColor = Cyan, activeTrackColor = Cyan,
                                inactiveTrackColor = SpaceSurface)
                        )
                    }
                    // Satellite name under the plot: bigger for small shared
                    // images, discreet full page.
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(t("photo_opt_satlabel_size"), color = TextHi, fontSize = 13.sp,
                                modifier = Modifier.weight(1f))
                            Text("${(ui.photoSatLabelScale * 100).toInt()} %", color = Cyan,
                                fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace)
                        }
                        Slider(
                            value = ui.photoSatLabelScale,
                            onValueChange = { vm.setPhotoSatLabelScale(it) },
                            valueRange = 0.5f..2.5f,
                            colors = SliderDefaults.colors(
                                thumbColor = Cyan, activeTrackColor = Cyan,
                                inactiveTrackColor = SpaceSurface)
                        )
                    }
                }
                // **The "Place" section is open to everyone** now that a
                // nearby park is only proposed, never asserted.
                run {
                // Country outline: an overlay like the others.
                Text(t("photo_sec_place"), color = Cyan, fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                PhotoToggle(t("photo_opt_pota"), ui.carte.potaAffiche,
                    enabled = ui.observer != null) { vm.setPhotoPota(it) }
                if (ui.carte.potaAffiche) {
                    Text(
                        if (ui.carte.potaRef.isBlank()) t("photo_opt_pota_none")
                        else ui.carte.potaRef + "  " + ui.carte.potaNom.take(34),
                        color = if (ui.carte.potaRef.isBlank()) Amber else Cyan,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 14.dp))

                    // **A nearby park is proposed, not imposed.** Outside any
                    // known boundary SatMe only knows a park is within 3 km;
                    // the operator, whose callsign goes on the photo, decides
                    // whether he is inside.
                    if (ui.carte.potaRef.isBlank() && ui.carte.potaPropose.isNotBlank()) {
                        OutlinedButton(
                            onClick = { vm.accepteParcPropose() },
                            modifier = Modifier.fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 4.dp)) {
                            Text(tf("pota_propose", ui.carte.potaPropose,
                                ui.carte.potaProposeNom.take(28)),
                                color = Amber, fontSize = 12.sp)
                        }
                        Text(t("pota_propose_aide"), color = TextLo, fontSize = 10.sp,
                            modifier = Modifier.padding(horizontal = 14.dp))
                    }
                    Column(Modifier.padding(horizontal = 14.dp)) {
                        PhotoToggle(t("pota_show_name"), ui.carte.potaNomAffiche) {
                            vm.setPhotoPotaNom(it)
                        }
                        Text(t("pota_line_size"), color = TextHi, fontSize = 13.sp)
                        Slider(value = ui.carte.potaTaille,
                            onValueChange = { vm.setPhotoPotaTaille(it) },
                            valueRange = 0.6f..2.5f,
                            colors = SliderDefaults.colors(thumbColor = Cyan,
                                activeTrackColor = Cyan, inactiveTrackColor = SpaceSurface))
                        Text(t("pota_line_up"), color = TextHi, fontSize = 13.sp)
                        Slider(value = ui.carte.potaMonte,
                            onValueChange = { vm.setPhotoPotaMonte(it) },
                            valueRange = 0f..0.6f,
                            colors = SliderDefaults.colors(thumbColor = Cyan,
                                activeTrackColor = Cyan, inactiveTrackColor = SpaceSurface))
                    }
                }

                PhotoToggle(t("photo_opt_map"), ui.carte.affichee,
                    enabled = ui.observer != null) { vm.setPhotoCarte(it) }
                if (ui.carte.affichee) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 2.dp)) {
                        if (ui.carte.paysNom.isNotBlank()) {
                            Text(ui.carte.paysNom, color = Cyan, fontSize = 12.sp,
                                fontWeight = FontWeight.Bold)
                        } else if (ui.carte.anneaux.isEmpty()) {
                            Text(t("photo_opt_carte_none"), color = Amber, fontSize = 11.sp)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(t("photo_opt_carte_size"), color = TextHi, fontSize = 13.sp,
                                modifier = Modifier.weight(1f))
                            Text("${(ui.carte.taille * 100).toInt()} %", color = Cyan,
                                fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace)
                        }
                        Text(t("photo_opt_carte_hint"), color = TextLo, fontSize = 11.sp)
                        Slider(
                            value = ui.carte.taille,
                            onValueChange = { vm.setPhotoCarteTaille(it) },
                            valueRange = 0.15f..0.9f,
                            colors = SliderDefaults.colors(
                                thumbColor = Cyan, activeTrackColor = Cyan,
                                inactiveTrackColor = SpaceSurface))
                        // Outline content: the country, or the POTA park area
                        // zoomed in, position included.
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("PAYS" to t("qrv_map_country"),
                                   "ZONE" to t("qrv_map_zone"),
                                   "LES_DEUX" to t("qrv_map_both")).forEach { (cle, nom) ->
                                FilterChip(
                                    selected = ui.carte.contenu == cle,
                                    onClick = { vm.setPhotoCarteContenu(cle) },
                                    label = { Text(nom, fontSize = 11.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Cyan.copy(alpha = 0.3f),
                                        selectedLabelColor = Cyan))
                            }
                        }
                        // Plain fill: six bold colours, readable on light or
                        // dark photos.
                        if (ui.carte.remplissage == "UNI") {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(top = 4.dp)) {
                                listOf(0x66FFFFFF, 0x66000000, 0x6600B0FF,
                                       0x6600E676, 0x66FFD54F, 0x66FF5252).forEach { col ->
                                    Box(Modifier.size(26.dp)
                                        .background(Color(col.toLong() or 0xFF000000L.toLong()),
                                            RoundedCornerShape(6.dp))
                                        .border(
                                            if (ui.carte.couleur == col) 2.dp else 0.dp,
                                            Cyan, RoundedCornerShape(6.dp))
                                        .clickable { vm.setPhotoCarteCouleur(col) })
                                }
                            }
                        }
                        PhotoToggle(t("qrv_map_fade"), ui.carte.fondu) {
                            vm.setPhotoCarteFondu(it)
                        }
                        if (ui.carte.contenu == "ZONE" && ui.carte.zoneRef.isBlank()) {
                            Text(t("qrv_map_zone_none"), color = Amber, fontSize = 11.sp)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("DRAPEAU" to t("qrv_map_flag"),
                                   "UNI" to t("qrv_map_plain")).forEach { (cle, nom) ->
                                FilterChip(
                                    selected = ui.carte.remplissage == cle,
                                    onClick = { vm.setPhotoCarteRemplissage(cle) },
                                    label = { Text(nom, fontSize = 11.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Cyan.copy(alpha = 0.3f),
                                        selectedLabelColor = Cyan))
                            }
                        }
                    }
                }

                    // No logo switch: the SatMe mark signs every shared photo
                    // (the old switch had no effect for anyone but the author).
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * Which satellite the picture is about. The page is reachable from the global
 * menu with nothing selected, so the choice is made here; it drives both the
 * printed name and the polar plot.
 */
@Composable
private fun SatPicker(ui: UiState, vm: MainViewModel) {
    var open by remember { mutableStateOf(false) }
    val favs = ui.satellites.filter { it.catalogNumber in ui.favorites }.sortedBy { it.name }
    Column {
        Surface(
            color = SpaceSurface, shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().clickable { open = true }
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t("photo_sat_label"), color = TextLo, fontSize = 10.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text(ui.photoSatName.ifBlank { t("photo_sat_none") },
                        color = if (ui.photoSatName.isBlank()) TextLo else Cyan,
                        fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    if (ui.photoPassLabel.isNotBlank()) {
                        Text(ui.photoPassLabel, color = TextLo, fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace)
                    }
                }
                Icon(Icons.Default.ExpandMore, null, tint = TextLo)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false },
            modifier = Modifier.heightIn(max = 420.dp)) {
            DropdownMenuItem(
                text = { Text(t("photo_sat_none")) },
                onClick = { open = false; vm.setPhotoSat(null) })
            // FAVOURITES ONLY, deliberately (the full list was tried and
            // rolled back): the bird being worked is in the favourites. A
            // non-favourite named by a kept picture keeps its name and passes;
            // it just cannot be picked here.
            favs.forEach { s ->
                DropdownMenuItem(
                    text = { Text(s.name, color = Cyan, fontWeight = FontWeight.Bold) },
                    onClick = { open = false; vm.setPhotoSat(s.catalogNumber) })
            }
        }
    }
}

/**
 * Which pass the picture is about. Photos are rarely shot during the pass
 * itself, so the operator picks it rather than the app guessing. Past passes
 * are listed too.
 */
@Composable
private fun PassPicker(ui: UiState, vm: MainViewModel) {
    // Hidden only when NO satellite is named. Keying on the catalogue number
    // alone lost the selector when a name could not be resolved yet (picture
    // reopened by name, list not loaded).
    if (ui.photoSatCat == null && ui.photoSatName.isBlank()) return
    // Named but unidentified: resolve it, the passes follow.
    LaunchedEffect(ui.photoSatCat, ui.photoSatName, ui.satellites.size) {
        if (ui.photoSatCat == null && ui.photoSatName.isNotBlank()) vm.rebindPhotoSatByName()
    }
    var open by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    // Recent past passes and upcoming ones.
    val list = remember(ui.photoPasses, ui.photoPassAosMs) {
        val past = ui.photoPasses.filter { it.losEpochMs < now }.takeLast(6)
        val rest = ui.photoPasses.filter { it.losEpochMs >= now }.take(8)
        past + rest
    }
    Column {
        Surface(
            color = SpaceSurface, shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
                .clickable(enabled = list.isNotEmpty()) { open = true }
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t("photo_pass_label"), color = TextLo, fontSize = 10.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text(ui.photoPassLabel.ifBlank { t("photo_pass_none") },
                        color = if (ui.photoPassLabel.isBlank()) TextLo else TextHi,
                        fontWeight = FontWeight.Bold, fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace)
                }
                if (list.isNotEmpty()) Icon(Icons.Default.ExpandMore, null, tint = TextLo)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            list.forEach { p ->
                val live = now in p.aosEpochMs..p.losEpochMs
                val past = p.losEpochMs < now
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                when {
                                    live -> "\u25CF "
                                    past -> "\u2190 "
                                    else -> "\u2192 "
                                },
                                color = if (live) Amber else TextLo, fontSize = 12.sp)
                            Text(vm.passLabel(p),
                                fontFamily = FontFamily.Monospace,
                                color = if (p.aosEpochMs == ui.photoPassAosMs) Cyan
                                    else if (past) TextLo else TextHi,
                                fontWeight = if (p.aosEpochMs == ui.photoPassAosMs)
                                    FontWeight.Bold else FontWeight.Normal)
                        }
                    },
                    onClick = { open = false; vm.setPhotoPass(p.aosEpochMs) })
            }
        }
    }
}

/** One kept picture: a thumbnail, its date, and a small delete cross. */
@Composable
private fun KeptThumb(
    photo: fr.f4ioz.satcombo.data.QrvPhoto, selected: Boolean, useUtc: Boolean,
    file: File, onOpen: () -> Unit, onDelete: () -> Unit
) {
    // Decoded at 1/8 size: sixty full-size shots would not fit in memory.
    val thumb by produceState<Bitmap?>(initialValue = null, file.absolutePath) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                android.graphics.BitmapFactory.decodeFile(
                    file.absolutePath,
                    android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 })
            }.getOrNull()
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            Box(
                Modifier.size(88.dp).clip(RoundedCornerShape(12.dp))
                    .background(SpaceSurface)
                    .then(if (selected)
                        Modifier.border(2.dp, Cyan, RoundedCornerShape(12.dp)) else Modifier)
                    .clickable { onOpen() },
                contentAlignment = Alignment.Center
            ) {
                val b = thumb
                if (b != null) {
                    Image(b.asImageBitmap(), contentDescription = null,
                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    CircularProgressIndicator(color = Cyan, strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp))
                }
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(26.dp).align(Alignment.TopEnd)) {
                Box(Modifier.size(20.dp).clip(CircleShape).background(Color(0xCC000000)),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Close, t("delete"), tint = Color.White,
                        modifier = Modifier.size(13.dp))
                }
            }
        }
        Text(tzFormat("dd/MM HH:mm", useUtc).format(java.util.Date(photo.id)),
            color = TextLo, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 3.dp))
        if (photo.satName.isNotBlank()) {
            Text(photo.satName.take(12), color = Cyan, fontSize = 10.sp,
                fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PhotoToggle(
    label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)
            .toggleable(value = checked && enabled, enabled = enabled, role = Role.Switch,
                onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = if (enabled) TextHi else TextLo, fontSize = 13.sp,
            modifier = Modifier.weight(1f))
        Switch(
            checked = checked && enabled, onCheckedChange = null, enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Cyan,
                checkedTrackColor = Cyan.copy(alpha = 0.35f))
        )
    }
}

/**
 * Callsign colours. Six, not thirty: the point is to recover contrast, not to
 * match the landscape. All opaque and bold.
 */
private val CALL_COLORS = listOf(
    0xFFFFC65C.toInt(),   // amber, the original
    0xFFFFFFFF.toInt(),   // white
    0xFF101418.toInt(),   // black, for snow and bright sky
    0xFF38E1D4.toInt(),   // cyan
    0xFF49D17F.toInt(),   // green
    0xFFFF4D8D.toInt()    // magenta
)

/**
 * Strip of flag thumbnails, preceded by "None". Drawn by the same code that
 * renders the photo, so what you pick is exactly what gets printed.
 */
@Composable
private fun FlagPicker(
    label: String,
    current: String,
    catalogue: List<Flags.Flag>,
    onPick: (String) -> Unit
) {
    Text(label, color = TextLo, fontSize = 12.sp,
        modifier = Modifier.padding(top = 12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 6.dp)
            .horizontalScroll(rememberScrollState())) {
        val none = current.isBlank()
        Surface(
            color = if (none) Cyan.copy(alpha = 0.22f) else SpaceSurface,
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.clickable { onPick("") }) {
            Text(t("photo_flag_none"),
                color = if (none) Cyan else TextLo, fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp))
        }
        catalogue.forEach { fl ->
            val sel = current.equals(fl.code, true)
            val bmp = remember(fl.code) { FlagDraw.bitmap(fl, 44) }
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = fl.label,
                modifier = Modifier.height(24.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .border(
                        if (sel) 3.dp else 1.dp,
                        if (sel) Cyan else TextLo.copy(alpha = 0.45f),
                        RoundedCornerShape(3.dp))
                    .clickable { onPick(fl.code) })
        }
    }
    current.takeIf { it.isNotBlank() }?.let { code -> Flags.byCode(code) }?.let { fl ->
        Text(fl.label, color = Cyan, fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp))
    }
}
