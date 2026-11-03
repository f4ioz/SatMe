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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Satellite
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Image as ImageIcon
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.f4ioz.satcombo.MainViewModel
import fr.f4ioz.satcombo.UiState
import fr.f4ioz.satcombo.i18n.I18n
import fr.f4ioz.satcombo.i18n.Lang
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.i18n.tf
import fr.f4ioz.satcombo.telemesure.ArchiveGenesis
import fr.f4ioz.satcombo.telemesure.AssemblageSsdv
import fr.f4ioz.satcombo.telemesure.Genesis
import fr.f4ioz.satcombo.telemesure.GenesisHub
import fr.f4ioz.satcombo.telemesure.RecepteurGenesis
import fr.f4ioz.satcombo.telemesure.TelemesureGenesis
import fr.f4ioz.satcombo.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * GENESIS (HADES-SA / SO-127 and AMSAT-EA's other satellites): what the
 * phone hears in USB while it records — the telemetry made readable, the
 * SSDV pictures put together over several passes (missing blocks hatched),
 * the voice messages noted (played on SatMe-PC). Laid out like the APT page:
 * the live card, the decoder's settings, the pictures, the frames.
 */
@Composable
fun GenesisScreen(ui: UiState, vm: MainViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by GenesisHub.etat.collectAsState()
    val reg = remember { fr.f4ioz.satcombo.data.SettingsStore(ctx) }
    var mode by remember { mutableStateOf(reg.genesisMode) }
    var profil by remember { mutableStateOf(reg.genesisProfil) }
    var pickRecording by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    var abandon by remember { mutableStateOf<AssemblageSsdv.Photo?>(null) }

    // What the disk holds, read again when something was kept.
    val photos by produceState(emptyList<AssemblageSsdv.Photo>(), st.rev) {
        value = withContext(Dispatchers.IO) { runCatching { GenesisHub.essais() + GenesisHub.assemblage(ctx).photos() }.getOrDefault(emptyList()) }
    }
    val trames by produceState(emptyList<ArchiveGenesis.Entree>(), st.rev) {
        value = withContext(Dispatchers.IO) {
            GenesisHub.trames(ctx, System.currentTimeMillis() - 7 * 86_400_000L)
                .filter { it.type != Genesis.TYPE_SSDV }.sortedByDescending { it.t }.take(60)
        }
    }
    val voix by produceState(emptyList<org.json.JSONObject>(), st.rev) {
        value = withContext(Dispatchers.IO) { GenesisHub.voix(ctx) }
    }
    val heure = remember(ui.useUtc) { formatGenesis(ui.useUtc, "dd/MM HH:mm:ss") }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { Spacer(Modifier.height(4.dp)) }

        // ------------------------------------------------------------ live
        item {
            Surface(color = SpaceCard, shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Satellite, null,
                            tint = if (st.ecoute && st.rx.presence) Aurora else if (st.ecoute) Cyan else TextLo,
                            modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(t("genesis_live_titre"), color = TextHi, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { help = true }) {
                            Icon(Icons.Default.Info, t("genesis_aide_titre"), tint = TextLo, modifier = Modifier.size(18.dp))
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        when {
                            st.ecoute -> tf("genesis_ecoute", st.sat, nomProfil(st.profil))
                            st.decision == "attente" -> tf("genesis_attente", st.sat)
                            st.decision == "non" -> tf("genesis_non", st.sat)
                            st.decision == "coupe" -> t("genesis_coupe_etat")
                            else -> t("genesis_idle")
                        },
                        color = if (st.ecoute) Cyan else TextLo, fontSize = 13.sp)
                    if (st.ecoute || st.rx.trames > 0 || st.rx.ssdv > 0) {
                        Text(tf("genesis_stats", st.rx.trames, st.rx.ssdv, st.rx.crcFaux,
                            if (st.rx.recoit.isBlank()) "—" else st.rx.recoit + " bit/s",
                            String.format(I18n.locale(), "%.1f", st.rx.snrDb)),
                            color = if (st.rx.presence) Aurora else TextLo, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                    if (st.rx.voix > 0 || voix.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(t("genesis_voix_recue"), color = Amber, fontSize = 12.sp)
                    }
                    st.erreur?.let { Text(tf("genesis_erreur", it), color = Amber, fontSize = 11.sp) }
                    if (!st.ecoute) {
                        Spacer(Modifier.height(8.dp))
                        Text(t("genesis_hint"), color = TextLo, fontSize = 11.sp)
                        if (ui.recorderSource != "USB") Text(t("genesis_source_usb"), color = Amber, fontSize = 11.sp)
                    }
                }
            }
        }

        // ------------------------------------------------------------ settings
        item {
            Surface(color = SpaceCard, shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text(t("genesis_decodeur_titre"), color = TextHi, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(Modifier.height(4.dp))
                    listOf("SATELLITE" to "genesis_mode_satellite", "TOUJOURS" to "genesis_mode_toujours", "COUPE" to "genesis_mode_coupe").forEach { (c, k) ->
                        Row(Modifier.fillMaxWidth().toggleable(value = mode == c, role = Role.RadioButton,
                                onValueChange = { mode = c; reg.genesisMode = c }).padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = mode == c, onClick = null)
                            Text(t(k), color = TextHi, fontSize = 14.sp,
                                fontWeight = if (mode == c) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                    Text(t("genesis_mode_desc"), color = TextLo, fontSize = 11.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(t("genesis_debit"), color = TextLo, fontSize = 11.sp)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("" to t("genesis_debit_satellite"), "auto" to t("genesis_debit_auto"), "200" to "200 bit/s", "800" to "800 bit/s").forEach { (c, n) ->
                            FilterChip(selected = profil == c, onClick = { profil = c; reg.genesisProfil = c },
                                label = { Text(n, fontSize = 12.sp) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Cyan.copy(alpha = 0.2f), selectedLabelColor = Cyan))
                        }
                    }
                    Text(t("genesis_debit_desc"), color = TextLo, fontSize = 11.sp)
                    Spacer(Modifier.height(10.dp))
                    // A recording read again, and the test signal.
                    if (st.progression >= 0f) {
                        Text(st.fichier ?: "", color = Cyan, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        LinearProgressIndicator(progress = { st.progression },
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(4.dp), color = Cyan, trackColor = SpaceSurface)
                        Text(tf("genesis_relire_en_cours", st.trouves), color = TextLo, fontSize = 11.sp)
                        OutlinedButton(onClick = { GenesisHub.annuleFichier() }) {
                            Icon(Icons.Default.Close, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(t("cancel"))
                        }
                    } else {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { pickRecording = true }, colors = ButtonDefaults.buttonColors(containerColor = Cyan)) {
                                Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(t("sstv_pick_recording"), color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(onClick = { GenesisHub.lanceEssai(ctx) }, enabled = !st.essai) {
                                Text(t("genesis_essai"), color = Cyan, fontSize = 12.sp)
                            }
                        }
                        st.fichier?.let { Text(tf("genesis_relire_fini", it, st.trouves), color = TextLo, fontSize = 11.sp) }
                    }
                    when {
                        st.essai -> Text(t("genesis_essai_en_cours"), color = TextLo, fontSize = 11.sp)
                        st.essaiTrames >= 0 -> Text(tf("genesis_essai_fini", st.essaiTrames, st.essaiSsdv),
                            color = if (st.essaiTrames > 0 && st.essaiSsdv > 0) Aurora else Amber, fontSize = 11.sp)
                    }
                    Text(t("genesis_essai_desc"), color = TextLo, fontSize = 11.sp)
                }
            }
        }

        // ------------------------------------------------------------ pictures
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Icon(Icons.Default.ImageIcon, null, tint = TextLo, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(tf("genesis_images", photos.size), color = TextHi, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }
        if (photos.isEmpty()) item {
            Text(t("genesis_images_vide"), color = TextLo, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
        }
        items(photos) { ph ->
            PhotoSsdvCarte(ph, ui.useUtc, onAbandon = { abandon = ph })
        }

        // ------------------------------------------------------------ telemetry
        item {
            Text(tf("genesis_trames", trames.size), color = TextHi, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                modifier = Modifier.padding(top = 4.dp))
        }
        if (trames.isEmpty()) item {
            Text(t("genesis_trames_vide"), color = TextLo, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
        }
        items(trames) { e -> TrameGenesisCarte(e, heure) }

        // ------------------------------------------------------------ voice
        if (voix.isNotEmpty()) {
            item {
                Surface(color = SpaceCard, shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text(tf("genesis_voix_titre", voix.size), color = TextHi, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(t("genesis_voix_pc"), color = TextLo, fontSize = 11.sp)
                        voix.take(10).forEach { o ->
                            Text("▸ " + heure.format(Date(o.optLong("debut"))) + "  " + o.optString("sat"),
                                color = TextHi, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    if (pickRecording) {
        RecordingPicker(onDismiss = { pickRecording = false }, onPick = { f ->
            pickRecording = false
            scope.launch { withContext(Dispatchers.IO) { GenesisHub.decodeFichier(ctx, f) } }
        })
    }
    abandon?.let { ph ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { abandon = null },
            confirmButton = { TextButton(onClick = {
                abandon = null
                scope.launch { withContext(Dispatchers.IO) { GenesisHub.assemblage(ctx).abandonne(ph.id) }; GenesisHub.rafraichit() }
            }) { Text(t("genesis_abandonner"), color = Magenta) } },
            dismissButton = { TextButton(onClick = { abandon = null }) { Text(t("cancel"), color = Cyan) } },
            text = { Text(t("genesis_abandonner_confirme"), color = TextHi) })
    }
    if (help) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { help = false }) {
            Surface(color = SpaceCard, shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(t("genesis_aide_titre"), color = TextHi, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(t("genesis_aide"), color = TextLo, fontSize = 12.sp, lineHeight = 17.sp)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { help = false }, modifier = Modifier.align(Alignment.End),
                        colors = ButtonDefaults.buttonColors(containerColor = SpaceSurface)) {
                        Text(t("close"), color = TextHi)
                    }
                }
            }
        }
    }
}

private fun formatGenesis(utc: Boolean, motif: String) =
    SimpleDateFormat(motif, Locale.getDefault()).apply { if (utc) timeZone = java.util.TimeZone.getTimeZone("UTC") }

private fun nomProfil(cle: String): String = when (cle) {
    RecepteurGenesis.Profil.G200.cle -> "200 bit/s"
    RecepteurGenesis.Profil.G800.cle -> "800 bit/s"
    else -> t("genesis_debit_auto")
}

private fun en() = I18n.current() == Lang.EN

/** A number with its unit, the decimal separator of the language. */
internal fun valeurGenesis(v: Double, u: String): String =
    TelemesureGenesis.nombre(v, u).let { if (en()) it else it.replace('.', ',') }

/** The colours of the passes in a picture's strip (a missing packet: none). */
private val COULEURS_PASSAGES = listOf(0xFF26C6DA, 0xFF66BB6A, 0xFFFFA726, 0xFFAB47BC, 0xFFEC407A, 0xFF8D6E63).map { Color(it) }

/**
 * The picture as far as it came: the JPEG made of the packets held, the
 * MCUs no packet brought hatched over (dark, diagonal lines).
 */
internal fun apercuHachure(ph: AssemblageSsdv.Photo): Bitmap? {
    val (j, vues, taille) = ph.apercu() ?: return null
    val b = android.graphics.BitmapFactory.decodeByteArray(j, 0, j.size)?.copy(Bitmap.Config.ARGB_8888, true) ?: return null
    val v = vues ?: return b
    val (mw, mh) = taille
    if (mw <= 0 || mh <= 0) return b
    val cols = maxOf(1, b.width / mw)
    val c = android.graphics.Canvas(b)
    val fond = android.graphics.Paint().apply { color = 0xCC1A1F2B.toInt() }
    val trait = android.graphics.Paint().apply { color = 0xFF5A6478.toInt(); strokeWidth = 1.5f; isAntiAlias = true }
    for (i in v.indices) {
        if (v[i]) continue
        val x = (i % cols) * mw.toFloat(); val y = (i / cols) * mh.toFloat()
        if (y >= b.height) break
        c.save()
        c.clipRect(x, y, x + mw, y + mh)
        c.drawRect(x, y, x + mw, y + mh, fond)
        var k = -mh.toFloat()
        while (k < mw) { c.drawLine(x + k, y + mh, x + k + mh, y, trait); k += 6f }
        c.restore()
    }
    return b
}

/** « 22/55 paquets · 40 % », with « ≈ » when the count is guessed. */
internal fun ligneAvancement(ph: AssemblageSsdv.Photo): String {
    val (total, estime) = ph.totalEstime()
    return tf("genesis_paquets", ph.recus, (if (estime) "≈" else "") + total, ph.pourcent())
}

/** « réunie sur N passages » (or « 1 passage »). */
internal fun lignePassages(ph: AssemblageSsdv.Photo): String {
    val n = ph.contributeurs().size
    return if (n <= 1) t("genesis_un_passage") else tf("genesis_passages", n)
}

@Composable
private fun PhotoSsdvCarte(ph: AssemblageSsdv.Photo, utc: Boolean, onAbandon: () -> Unit) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, ph.id, ph.recus) {
        value = withContext(Dispatchers.Default) { runCatching { apercuHachure(ph) }.getOrNull() }
    }
    val jour = remember(utc) { formatGenesis(utc, "dd/MM/yy HH:mm") }
    Surface(color = SpaceCard, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(10.dp)) {
            bmp?.let { b ->
                Image(b.asImageBitmap(), null, contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)))
                Spacer(Modifier.height(6.dp))
            }
            BandePassages(ph)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(listOf(ph.satellite.ifBlank { ph.indicatif }, tf("genesis_image_n", ph.numero)).joinToString(" · ") +
                        if (ph.essai) " · " + t("genesis_essai_tag") else "",
                        color = TextHi, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(ligneAvancement(ph) + if (ph.complete) " · " + t("genesis_complete") else "",
                        color = if (ph.complete) Aurora else Amber, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Text(lignePassages(ph) + " · " + jour.format(Date(ph.miseAJour)) + if (utc) " UTC" else "",
                        color = TextLo, fontSize = 10.sp)
                }
                IconButton(onClick = { partageSsdv(ctx, ph) }) { Icon(Icons.Default.Share, t("genesis_partager"), tint = Cyan) }
                if (!ph.essai) IconButton(onClick = onAbandon) { Icon(Icons.Default.Delete, t("genesis_abandonner"), tint = TextLo) }
            }
        }
    }
}

/** The packets along the picture, coloured by the pass that brought them; a gap where one is missing. */
@Composable
private fun BandePassages(ph: AssemblageSsdv.Photo) {
    val bande = remember(ph.id, ph.recus) { runCatching { ph.bande() }.getOrDefault(emptyList()) }
    val total = bande.sumOf { it.second }.coerceAtLeast(1)
    val manque = SpaceSurface
    Canvas(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))) {
        for ((debut, n, i) in bande) {
            val x = size.width * debut / total
            val w = size.width * n / total
            drawRect(if (i < 0) manque else COULEURS_PASSAGES[i % COULEURS_PASSAGES.size], Offset(x, 0f), Size(w, size.height))
        }
    }
}

/** The picture as it stands (finished or not) through the share sheet. */
private fun partageSsdv(ctx: android.content.Context, ph: AssemblageSsdv.Photo) {
    runCatching {
        val d = File(ctx.cacheDir, "export").apply { mkdirs() }
        val f = File(d, "SSDV_" + ph.id + ".jpg")
        val b = apercuHachure(ph) ?: return
        f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
        ctx.startActivity(android.content.Intent.createChooser(
            android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, t("genesis_partager")))
    }
}

/** A telemetry frame: its name and short line; tapped, every value with its unit. */
@Composable
internal fun TrameGenesisCarte(e: ArchiveGenesis.Entree, heure: SimpleDateFormat) {
    var ouvert by remember { mutableStateOf(false) }
    val l = remember(e.type, e.hex) { TelemesureGenesis.lis(e.type, e.hex) }
    val anglais = en()
    Surface(color = SpaceCard, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().clickable { ouvert = !ouvert }) {
        Column(Modifier.padding(10.dp)) {
            Text(heure.format(Date(e.t)) + "  " + e.sat.ifBlank { "GENESIS" }, color = TextLo, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Text(TelemesureGenesis.nomType(e.type, anglais), color = TextHi, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            if (l == null) Text(t("genesis_trame_brute") + " " + e.hex.take(64), color = TextLo, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            else TrameLue(l, anglais, ouvert)
        }
    }
}

@Composable
private fun TrameLue(l: TelemesureGenesis.Lecture, anglais: Boolean, ouvert: Boolean) {
    Column {
        val resume = TelemesureGenesis.resume(l, anglais).let { if (anglais) it else it.replace(Regex("(\\d)\\.(\\d)"), "$1,$2") }
        if (resume.isNotBlank()) Text(resume, color = Cyan, fontSize = 12.sp)
        if (ouvert) {
            Spacer(Modifier.height(4.dp))
            l.horloge?.let { Text(tf("genesis_horloge", it), color = TextLo, fontSize = 10.sp) }
            l.champs.forEach { c ->
                val v = c.texte(anglais) ?: c.v?.let { valeurGenesis(it, c.u) } ?: "—"
                Row(Modifier.fillMaxWidth()) {
                    Text(c.libelle(anglais), color = TextLo, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    Text(v, color = TextHi, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
            l.serie?.let { s -> SerieGenesis(s, anglais) }
            l.bbs.filter { it.indicatif.isNotBlank() }.forEach { b ->
                Text("✉ " + b.indicatif + "  " + b.message + if (b.tramesVoix > 0) "  🔊 " + b.tramesVoix else "",
                    color = TextHi, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        } else Text(t("genesis_toucher"), color = TextLo, fontSize = 10.sp)
    }
}

/** A type 14 series: its 30 values, oldest on the left, 3 min apart. */
@Composable
private fun SerieGenesis(s: TelemesureGenesis.Serie, anglais: Boolean) {
    val v = s.valeurs
    val nums = v.filterNotNull()
    if (nums.isEmpty()) return
    val bas = nums.min(); val haut = nums.max()
    val trait = Cyan
    Text((if (anglais) s.en else s.fr) + " · " + tf("genesis_serie", v.size, s.pasS / 60), color = TextLo, fontSize = 10.sp,
        modifier = Modifier.padding(top = 4.dp))
    Canvas(Modifier.fillMaxWidth().height(48.dp).padding(vertical = 4.dp)) {
        val dx = size.width / maxOf(1, v.size - 1)
        var avant: Offset? = null
        v.forEachIndexed { i, x ->
            if (x == null) { avant = null; return@forEachIndexed }
            val y = if (haut > bas) size.height * (1f - ((x - bas) / (haut - bas)).toFloat()) else size.height / 2
            val p = Offset(i * dx, y)
            avant?.let { drawLine(trait, it, p, strokeWidth = 2f) }
            drawCircle(trait, 2f, p)
            avant = p
        }
    }
    Text(valeurGenesis(bas, s.u) + " … " + valeurGenesis(haut, s.u), color = TextLo, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
}

/**
 * The GENESIS part of a pass in the journal: the telemetry frames heard
 * during it and the pictures it brought packets to. Nothing when none.
 */
@Composable
fun GenesisDuPassage(satName: String, catnum: Int, debutMs: Long, finMs: Long, utc: Boolean) {
    val ctx = LocalContext.current
    val donnees by produceState<Pair<List<ArchiveGenesis.Entree>, List<AssemblageSsdv.Photo>>?>(null, debutMs, finMs, satName) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                // A little margin each side: the recording starts before AOS and ends after LOS.
                val de = debutMs - 5 * 60_000L; val a = finMs + 5 * 60_000L
                val e = GenesisHub.trames(ctx, de, a, satName, catnum)
                val cles = e.filter { it.type == Genesis.TYPE_SSDV }.map { it.cle }.toSet()
                val ph = GenesisHub.assemblage(ctx).photos().filter { it.id in cles }
                e.filter { it.type != Genesis.TYPE_SSDV } to ph
            }.getOrNull()
        }
    }
    val (trames, photos) = donnees ?: return
    if (trames.isEmpty() && photos.isEmpty()) return
    val heure = remember(utc) { formatGenesis(utc, "HH:mm:ss") }
    Spacer(Modifier.height(8.dp))
    Text(tf("genesis_journal", trames.size, photos.size), color = TextHi, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    photos.forEach { ph ->
        val bmp by produceState<Bitmap?>(null, ph.id, ph.recus) {
            value = withContext(Dispatchers.Default) { runCatching { apercuHachure(ph) }.getOrNull() }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
            bmp?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop,
                modifier = Modifier.size(width = 76.dp, height = 58.dp).clip(RoundedCornerShape(6.dp))) }
            Spacer(Modifier.width(8.dp))
            Column {
                Text(tf("genesis_image_n", ph.numero), color = TextHi, fontSize = 12.sp)
                Text(ligneAvancement(ph), color = if (ph.complete) Aurora else Amber, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Text(lignePassages(ph), color = TextLo, fontSize = 10.sp)
            }
        }
    }
    trames.take(20).forEach { e -> TrameGenesisCarte(e, heure); Spacer(Modifier.height(4.dp)) }
}
