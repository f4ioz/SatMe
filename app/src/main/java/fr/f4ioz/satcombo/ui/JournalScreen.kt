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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import fr.f4ioz.satcombo.JournalDesPassages
import fr.f4ioz.satcombo.MainViewModel
import fr.f4ioz.satcombo.UiState
import fr.f4ioz.satcombo.audio.InfoEnregistrement
import fr.f4ioz.satcombo.data.SatPosition
import fr.f4ioz.satcombo.domain.JournalPassage
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.i18n.tf
import fr.f4ioz.satcombo.sstv.PlancheRendu
import fr.f4ioz.satcombo.sstv.SstvMeta
import fr.f4ioz.satcombo.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The pass journal (SatMe 21, Alpha 5): the passes followed, what came of
 * each, and a replay — the sky, the sound and the pictures at their time.
 */
@Composable
fun JournalScreen(ui: UiState, vm: MainViewModel, onClose: () -> Unit) {
    var passages by remember { mutableStateOf(vm.journal.passages()) }
    var liens by remember { mutableStateOf<Map<String, JournalDesPassages.Liens>>(emptyMap()) }
    var choisi by remember { mutableStateOf<JournalPassage.Entree?>(null) }
    LaunchedEffect(passages) { liens = withContext(Dispatchers.IO) { vm.journal.liens(passages) } }
    // Opened from a notification: straight on that pass (merged with another piece, its start may differ).
    val aOuvrir by vm.journalAOuvrir.collectAsState()
    LaunchedEffect(aOuvrir) {
        val v = aOuvrir ?: return@LaunchedEffect
        passages = vm.journal.passages()
        (passages.firstOrNull { it.id == v.id } ?: passages.firstOrNull { JournalPassage.memePassage(it, v) })
            ?.let { choisi = it }
        vm.journalAOuvrir.value = null
    }
    // A pass file from another phone (or kept elsewhere): opened, its pass shown.
    val scope = rememberCoroutineScope()
    var messagePaquet by remember { mutableStateOf("") }
    val ouvrePaquet = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val d = withContext(Dispatchers.IO) { runCatching { vm.journal.importePaquet(uri) }.getOrNull() }
            val en = d?.entree
            messagePaquet = if (en == null) t("journal_paquet_illisible")
                else tf("journal_paquet_ouvert", en.satName, d.poses, d.dejaLa)
            passages = vm.journal.passages()
            if (en != null) choisi = passages.firstOrNull { JournalPassage.memePassage(it, en) }
        }
    }

    Dialog(onDismissRequest = { if (choisi != null) choisi = null else onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = SpaceBg, modifier = Modifier.fillMaxSize()) {
            val c = choisi
            var importe by remember { mutableStateOf(false) }
            if (importe) ImportJournal(ui, vm, onFini = { n -> importe = false; if (n > 0) passages = vm.journal.passages() })
            if (c == null) ListeJournal(ui, passages, liens, onChoix = { choisi = it }, onClose = onClose,
                onImport = { importe = true }, notifOn = { vm.journal.notif() }, setNotif = { vm.journal.setNotif(it) },
                onOuvrePaquet = { runCatching { ouvrePaquet.launch(arrayOf(fr.f4ioz.satcombo.domain.JournalPaquet.TYPE, "application/octet-stream")) } },
                messagePaquet = messagePaquet, vm = vm,
                onRelire = { msg -> if (msg != null) messagePaquet = msg; passages = vm.journal.passages() })
            else FichePassage(ui, vm, c, liens[c.id] ?: JournalDesPassages.Liens(),
                onRetour = { choisi = null; passages = vm.journal.passages() },
                onSupprime = { vm.journal.supprime(c); passages = vm.journal.passages(); choisi = null },
                onSignets = { scope.launch { liens = withContext(Dispatchers.IO) { vm.journal.liens(passages) } } },
                onChange = { ne -> vm.journal.maj(ne); passages = vm.journal.passages(); choisi = passages.firstOrNull { it.id == ne.id } ?: ne })
        }
    }
}

private fun formatDate(utc: Boolean, motif: String) =
    SimpleDateFormat(motif, Locale.getDefault()).apply { if (utc) timeZone = TimeZone.getTimeZone("UTC") }

/** How a pass kept by itself was recorded. */
private fun autoLib(auto: String): String = when (auto) {
    JournalPassage.AUTO_SSTV -> t("journal_auto_sstv")
    JournalPassage.AUTO_CAT -> t("journal_auto_cat")
    else -> t("journal_auto_fond")
}

private fun duree(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }

@Composable
private fun ListeJournal(
    ui: UiState, passages: List<JournalPassage.Entree>, liens: Map<String, JournalDesPassages.Liens>,
    onChoix: (JournalPassage.Entree) -> Unit, onClose: () -> Unit, onImport: () -> Unit,
    notifOn: () -> Boolean, setNotif: (Boolean) -> Unit,
    onOuvrePaquet: () -> Unit, messagePaquet: String, vm: MainViewModel,
    onRelire: (String?) -> Unit = {}
) {
    val jour = remember(ui.useUtc) { formatDate(ui.useUtc, "EEE dd/MM HH:mm") }
    // By theme: the passes, the summary, finding passes again.
    var onglet by remember { mutableStateOf("PASSAGES") }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t("journal_titre"), color = TextHi, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, t("close"), tint = TextLo) }
        }
        Text(t("journal_desc"), color = TextLo, fontSize = 11.sp)
        androidx.compose.material3.TabRow(selectedTabIndex = ONGLETS_LISTE.indexOf(onglet).coerceAtLeast(0),
            containerColor = SpaceBg, contentColor = Cyan, modifier = Modifier.padding(vertical = 6.dp)) {
            ONGLETS_LISTE.forEach { o ->
                androidx.compose.material3.Tab(selected = onglet == o, onClick = { onglet = o },
                    selectedContentColor = Cyan, unselectedContentColor = TextLo) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(vertical = 6.dp)) {
                        Icon(ICONES_LISTE.getValue(o), contentDescription = null, modifier = Modifier.size(22.dp))
                        Text(if (o == "PASSAGES") tf("journal_onglet_passages", passages.size) else t("journal_onglet_" + o.lowercase()),
                            fontSize = 10.sp, maxLines = 1, softWrap = false)
                    }
                }
            }
        }
        if (onglet == "BILAN") { BilanJournalVue(ui, vm, passages, liens); return@Column }
        if (onglet == "RETROUVER") {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
                    Text("⤓ " + t("journal_importer"), color = Cyan, fontSize = 12.sp)
                }
                Text(t("journal_importer_court"), color = TextLo, fontSize = 10.sp)
                var enLigne by remember { mutableStateOf(false) }
                OutlinedButton(onClick = { enLigne = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("⤓ " + t("journal_enligne"), color = Cyan, fontSize = 12.sp)
                }
                Text(t("journal_enligne_court"), color = TextLo, fontSize = 10.sp)
                if (enLigne) ImportEnLigne(ui, vm) { msg -> enLigne = false; onRelire(msg) }
                OutlinedButton(onClick = onOuvrePaquet, modifier = Modifier.fillMaxWidth()) {
                    Text("⤓ " + t("journal_paquet_ouvrir"), color = Cyan, fontSize = 12.sp)
                }
                Text(t("journal_paquet_ouvrir_court"), color = TextLo, fontSize = 10.sp)
                if (messagePaquet.isNotBlank()) Text(messagePaquet, color = Amber, fontSize = 11.sp)
                // A word when a pass is kept by itself (automatic SSTV, under CAT, its page left).
                var notif by remember { mutableStateOf(notifOn()) }
                FilterChip(selected = notif, onClick = { notif = !notif; setNotif(notif) },
                    label = { Text((if (notif) "✓ " else "") + t("journal_notif") + " : " + t(if (notif) "journal_oui" else "journal_flash_non"), fontSize = 11.sp) })
            }
            return@Column
        }
        if (passages.isEmpty()) {
            Text(t("journal_vide"), color = TextLo, fontSize = 13.sp, modifier = Modifier.padding(vertical = 24.dp))
            return@Column
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(passages, key = { it.id }) { e ->
                val l = liens[e.id]
                Surface(color = SpaceCard, shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().clickable { onChoix(e) }) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        MiniPolarPlot(e.points.map { it.az to it.el }, Cyan, Modifier.size(64.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.satName, color = TextHi, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text(jour.format(Date(e.debutMs)) + (if (ui.useUtc) " UTC" else "") +
                                " · " + duree(e.dureeMs) + " · " + tf("journal_elmax", e.elMax.toInt()),
                                color = TextLo, fontSize = 11.sp)
                            val badges = buildList {
                                if (e.enregistrements.isNotEmpty()) add("● " + t("journal_b_son"))
                                if (e.avecCat) add("CAT")
                                if (e.avecRotor) add(t("journal_b_mat"))
                                l?.qsos?.size?.takeIf { it > 0 }?.let { add(tf("journal_b_qso", it)) }
                                l?.images?.size?.takeIf { it > 0 }?.let { add("🖼 $it") }
                                l?.trames?.size?.takeIf { it > 0 }?.let { n ->
                                    val iss = l.trames.count { it.viaIss }
                                    add("APRS $n" + if (iss > 0) " (ISS $iss)" else "")
                                }
                            }
                            if (badges.isNotEmpty()) Text(badges.joinToString("  ·  "), color = Cyan, fontSize = 11.sp)
                            if (e.reconstitue) Text(t("journal_reconstitue"), color = Amber, fontSize = 10.sp)
                            if (e.auto.isNotBlank()) Text(autoLib(e.auto), color = Amber, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FichePassage(
    ui: UiState, vm: MainViewModel, e: JournalPassage.Entree, l: JournalDesPassages.Liens,
    onRetour: () -> Unit, onSupprime: () -> Unit, onSignets: () -> Unit = {},
    onChange: (JournalPassage.Entree) -> Unit = {}
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prevue = remember(e.id) { vm.journal.prevue(e) }
    val heure = remember(ui.useUtc) { formatDate(ui.useUtc, "HH:mm:ss") }
    val jour = remember(ui.useUtc) { formatDate(ui.useUtc, "EEEE dd/MM/yyyy HH:mm") }
    // The recordings of the pass, in order (several pieces may make one pass): those it names,
    // else one that covers it. The replay plays the piece of the moment (the current one).
    val morceaux = remember(e.id, e.enregistrements) { vm.journal.morceaux(e) }
    var courant by remember(e.id) { mutableStateOf(morceaux.firstOrNull()) }
    LaunchedEffect(morceaux) { if (courant !in morceaux) courant = morceaux.firstOrNull() }
    /** The piece playing at [t], or the next one to come; the last one after them all. */
    fun morceauA(t: Long) = morceaux.firstOrNull { t < it.origine + it.dureeMs } ?: morceaux.lastOrNull()
    // The current piece: its file, when its start was heard (its header), the header's length:
    // its sound begins at debutSon + annonce; before that, the replay goes on silent.
    val son = courant?.f
    val annonce = courant?.annonce ?: 0L
    val debutSon = courant?.origine
    // Replay: the moment shown (null = the whole pass).
    var instant by remember(e.id) { mutableStateOf<Long?>(null) }
    var joue by remember(e.id) { mutableStateOf(false) }
    var lecteur by remember { mutableStateOf<android.media.MediaPlayer?>(null) }
    DisposableEffect(e.id) { onDispose { runCatching { lecteur?.release() }; lecteur = null } }
    val vignettes = remember(e.id) { mutableStateMapOf<String, Bitmap>() }
    val enregistre = rememberEnregistrer()
    // A jump lands this long before what was tapped; the accelerated replay slows down as long before.
    var avantS by remember { mutableStateOf(vm.journal.avantS()) }
    var accelere by remember { mutableStateOf(vm.journal.accelere()) }
    var apresQsoS by remember { mutableStateOf(vm.journal.apresQsoS()) }
    var rapide by remember { mutableStateOf(vm.journal.rapide()) }
    // Where the sound shows activity (voices, carriers, SSTV), worked out from the recording.
    var surActivite by remember { mutableStateOf(vm.journal.surActivite()) }
    // Shown during the replay and in the video: the S-meter, the RX frequency, the station's square on the map.
    var affSmetre by remember { mutableStateOf(vm.journal.affSmetre()) }
    var affFreq by remember { mutableStateOf(vm.journal.affFreq()) }
    var affLocator by remember { mutableStateOf(vm.journal.affLocator()) }
    // Where the rig was in the passband, at rest (Doppler taken off), moment by moment.
    // Without a frequency read under CAT (a pass rebuilt, imported, or followed without CAT):
    // each contact's own (the log keeps where the rig was), from its time on.
    val frequences = remember(e.id, e.points.size, l) {
        vm.journal.frequencesRepos(e).ifEmpty {
            l.qsos.filter { it.downlinkMhz > 0 }.map { it.timeMs to Math.round(it.downlinkMhz * 1e6) }.sortedBy { it.first }
        }
    }
    val etiquetteQth = if (affLocator) listOf(ui.callsign, e.locator).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { null } else null
    var activite by remember(e.id) { mutableStateOf<List<LongRange>>(emptyList()) }
    // Accelerated: the stretches, set once the marks are known (null: the whole pass alike).
    var segs by remember(e.id) { mutableStateOf<List<JournalPassage.Segment>?>(null) }
    val defil = rememberScrollState()
    // The fiche by theme: what happened, how to replay, the summary, sharing.
    var onglet by remember(e.id) { mutableStateOf("MOMENTS") }
    // A cut of the pass: its start, its end (taken on the replay), its title.
    var decoupeDe by remember(e.id) { mutableStateOf<Long?>(null) }
    var decoupeA by remember(e.id) { mutableStateOf<Long?>(null) }
    var titreDecoupe by remember(e.id) { mutableStateOf("") }
    // Sharing, one path: the whole pass or a moment, in which form, then the file made.
    var portee by remember(e.id) { mutableStateOf("tout") }
    var format by remember { mutableStateOf("video") }
    var fabrication by remember(e.id) { mutableStateOf<Float?>(null) }
    var pret by remember(e.id) { mutableStateOf<java.io.File?>(null) }
    var echec by remember(e.id) { mutableStateOf(false) }
    // The pass file (.zip) being made, from the save icon.
    var sauvegarde by remember(e.id) { mutableStateOf(false) }
    // A bookmark being written on (note) or made a contact.
    var signetEdite by remember(e.id) { mutableStateOf<JournalPassage.Signet?>(null) }
    // A contact being entered for the log: its time, a note, the bookmark it comes from.
    var contactA by remember(e.id) { mutableStateOf<Triple<Long, String, JournalPassage.Signet?>?>(null) }

    // While playing: the sound sets the time; without sound, ten times faster.
    LaunchedEffect(joue, courant) {
        while (joue) {
            val t = instant ?: e.debutMs
            // Another piece of recording at this moment: the player follows it (the loop starts again with it).
            val m = morceauA(t)
            if (m != null && m != courant) { runCatching { lecteur?.release() }; lecteur = null; courant = m; break }
            if (lecteur == null && son != null) lecteur = runCatching {
                android.media.MediaPlayer().apply { setDataSource(son.absolutePath); prepare() } }.getOrNull()
            val p = lecteur
            val seg = segs?.let { JournalPassage.segmentA(it, t) }
            instant = when {
                // Accelerated, nothing logged here: on fast and silent, up to where the next one begins.
                seg != null && seg.vitesse > 1 -> {
                    runCatching { if (p != null && p.isPlaying) p.pause() }
                    minOf(t + 200L * seg.vitesse, seg.aMs)
                }
                p != null && debutSon != null && runCatching { p.isPlaying }.getOrDefault(false) ->
                    debutSon + runCatching { p.currentPosition.toLong() }.getOrDefault(0L)
                // Before the recording's sound: on at real speed, silent, then the sound from its first sample.
                p != null && debutSon != null -> ((instant ?: e.debutMs) + 200L).also { t ->
                    if (t >= debutSon + annonce) runCatching { p.seekTo((t - debutSon).toInt()); p.start() }
                }
                // Without sound: ten times faster, or real time around what was logged.
                else -> t + if (segs != null) 200L else 2_000L
            }
            if ((instant ?: 0L) > e.finMs) { joue = false; runCatching { lecteur?.pause() } }
            delay(200)
        }
    }
    fun lance() {
        val t0 = (instant ?: e.debutMs).let { if (it >= e.finMs) e.debutMs else it }
        instant = t0
        // The piece of that moment; the loop opens it and starts its sound when it begins
        // (never the spoken header, nor while rushing).
        val m = morceauA(t0)
        if (m != courant) { runCatching { lecteur?.release() }; lecteur = null; courant = m }
        joue = true
    }
    fun arrete() { joue = false; runCatching { lecteur?.pause() } }
    /** A contact's, a frame's or a bookmark's moment, to share: set as the moment in the Share tab. */
    fun versMoment(titre: String, t0: Long) {
        decoupeDe = t0 - avantS * 1000L
        decoupeA = maxOf(t0 + apresQsoS * 1000L, t0 - avantS * 1000L + 5_000L)
        titreDecoupe = titre; portee = "moment"; pret = null; echec = false
        if (format == "image") format = "video"
        onglet = "PARTAGER"
    }
    /** Straight to what was tapped, [avantMs] before it, playing; the view brought back on screen. */
    fun allerA(t: Long, avantMs: Long = avantS * 1000L) {
        runCatching { lecteur?.pause() }
        joue = false
        instant = (t - avantMs).coerceIn(e.debutMs, e.finMs - 1)
        lance()
        scope.launch { defil.animateScrollTo(0) }
    }

    val pt = instant?.let { JournalPassage.pointA(e, it) }
    val trace = e.points.filter { instant == null || it.tMs <= instant!! }.map { it.az to it.el }

    Column(Modifier.fillMaxSize().verticalScroll(defil).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onRetour) { Icon(Icons.AutoMirrored.Filled.ArrowBack, t("back"), tint = TextLo) }
            Column(Modifier.weight(1f)) {
                Text(e.satName, color = TextHi, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(jour.format(Date(e.debutMs)) + if (ui.useUtc) " UTC" else "", color = TextLo, fontSize = 11.sp)
            }
            // The pass in one file (.zip): kept elsewhere, or given to another station.
            IconButton(onClick = { sauvegarde = true }) { Icon(Icons.Default.Save, t("journal_paquet"), tint = Cyan) }
        }
        if (sauvegarde) {
            var paquet by remember(e.id) { mutableStateOf<java.io.File?>(null) }
            LaunchedEffect(e.id) { paquet = withContext(Dispatchers.IO) { runCatching { vm.journal.paquet(e) }.getOrNull() } }
            androidx.compose.material3.AlertDialog(onDismissRequest = { sauvegarde = false },
                icon = { Icon(Icons.Default.Save, null, tint = Cyan) },
                title = { Text(t("journal_paquet")) },
                text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(t("journal_paquet_desc"), fontSize = 12.sp)
                    val f = paquet
                    if (f == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else {
                        Text(f.name + " · " + tf("sstv_video_taille", "%.1f".format(f.length() / 1_048_576.0)), fontSize = 11.sp)
                        // One under the other, full width: the two do not fit side by side on a phone.
                        Button(modifier = Modifier.fillMaxWidth(), onClick = { runCatching {
                            val u = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
                            ctx.startActivity(android.content.Intent.createChooser(
                                android.content.Intent(android.content.Intent.ACTION_SEND).setType(fr.f4ioz.satcombo.domain.JournalPaquet.TYPE)
                                    .putExtra(android.content.Intent.EXTRA_STREAM, u)
                                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION), f.name))
                        } }) { Text(t("rec_share")) }
                        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                            enregistre(f.name, fr.f4ioz.satcombo.domain.JournalPaquet.TYPE, depuisFichier(f)) }) {
                            Text(t("journal_sur_tel"), color = Cyan) }
                    }
                } },
                confirmButton = { TextButton(onClick = { sauvegarde = false }) { Text(t("close")) } })
        }
        // Which SSTV pictures: those received live (the originals) by default, if any.
        val aDuDirect = l.images.any { it.second.source == "live" }
        var filtreImages by remember(e.id) { mutableStateOf(if (aDuDirect) "live" else "") }
        // And picture by picture: those set aside are kept with the pass.
        var masquees by remember(e.id) { mutableStateOf(e.masquees) }
        val parSource = remember(l, filtreImages) {
            l.images.filter { (_, s) -> when (filtreImages) { "live" -> s.source == "live"; "file" -> s.source != "live"; else -> true } }
        }
        val lv = remember(parSource, masquees) { l.copy(images = parSource.filter { it.first.name !in masquees }) }
        // The sky (zoomable) or the map, with what happened on the trajectory.
        var decalageQso by remember { mutableStateOf(vm.journal.decalageQsoS()) }
        val marques = remember(lv, decalageQso) { vm.journal.marques(lv, decalageQso) }
        LaunchedEffect(morceaux) { activite = withContext(Dispatchers.IO) { morceaux.flatMap { vm.journal.activite(it.f) } } }
        LaunchedEffect(marques, accelere, rapide, avantS, apresQsoS, activite, surActivite) {
            segs = if (!accelere) null else JournalPassage.segments(e.debutMs, e.finMs,
                JournalPassage.plagesNormales(marques, e.debutMs, e.finMs, avantS * 1000L, apresQsoS * 1000L,
                    if (surActivite) activite else emptyList()), rapide)
        }
        // Who the stations are (log, APRS, QRZ.com), for their cards.
        var fiches by remember(e.id) { mutableStateOf<Map<String, JournalPassage.Fiche>>(emptyMap()) }
        LaunchedEffect(marques) { fiches = vm.journal.fiches(marques) }
        val sol = remember(e.id) { vm.journal.traceSol(e) }
        var surCarte by remember { mutableStateOf(false) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
            FilterChip(selected = !surCarte, onClick = { surCarte = false }, label = { Text(t("journal_vue_ciel"), fontSize = 12.sp) })
            FilterChip(selected = surCarte, onClick = { surCarte = true }, label = { Text(t("journal_vue_carte"), fontSize = 12.sp) })
        }
        // An SSTV picture shows from its first line, drawn as it arrives, then
        // stays the time chosen once complete (×10 when replayed without sound).
        var flashS by remember { mutableStateOf(vm.journal.flashS()) }
        var affSstv by remember { mutableStateOf(vm.journal.affSstv()) }
        var affFiches by remember { mutableStateOf(vm.journal.affFiches()) }
        var tailleFlash by remember { mutableStateOf(vm.journal.flashTaille()) }
        var apercuJusqua by remember { mutableStateOf(0L) }
        val vitesseRejeu = if (accelere || (son != null && debutSon != null)) 1 else 10
        val arrivee = if (affSstv) JournalRendu.imageA(marques, instant, flashS * 1000L * vitesseRejeu) else null
        val apercu = if (System.currentTimeMillis() < apercuJusqua)
            marques.firstOrNull { it.type == JournalPassage.TypeMarque.SSTV && it.fichier != null }?.let { it to 1f } else null
        LaunchedEffect(apercuJusqua) { if (apercuJusqua > 0) { delay(2_100); apercuJusqua = 0L } }
        // The view as shown, for the export.
        var cadreVu by remember(e.id) { mutableStateOf<JournalPassage.Cadre?>(null) }
        var vueVue by remember(e.id) { mutableStateOf<Pair<JournalPassage.VueCarte, Float>?>(null) }
        // The station worked or heard: its card, the same time as a picture.
        // A card for the last contact, another for the last APRS frame: both may show at once.
        val encarts = if (!affFiches) emptyList() else listOf(JournalPassage.TypeMarque.QSO, JournalPassage.TypeMarque.APRS)
            .mapNotNull { ty -> JournalRendu.indicatifA(marques, instant, flashS * 1000L * vitesseRejeu, ty)?.let { it to fiches[it.texte] } }
        // On the view itself, during the replay: RX where the rig was, the S-meter (when asked, when known).
        val rxIci = if (affFreq) instant?.let { t0 -> frequences.lastOrNull { it.first <= t0 + 2_000L }?.second } else null
        val sIci = if (affSmetre) instant?.let { JournalPassage.signalA(e, it) } else null
        val creteIci = if (sIci != null) instant?.let { JournalPassage.crete(e, it) } else null
        if (!surCarte) CielJournal(e, prevue, marques, instant, apercu ?: arrivee, tailleFlash, encarts, rxIci, sIci, creteIci) { cadreVu = it }
        else CarteJournal(e, sol, marques, instant, e.locator, apercu ?: arrivee, tailleFlash, encarts, etiquetteQth, rxIci, sIci, creteIci) { v, w -> vueVue = v to w }
        Text(t(if (surCarte) "journal_legende_sol" else "journal_legende_carte"), color = TextLo, fontSize = 10.sp)
        LegendeMarques()
        // The replay itself stays in view; the rest by theme, behind icons.
        // Replay controls.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 6.dp)) {
            Button(onClick = { if (joue) arrete() else lance() }) {
                Text(if (joue) "⏸ " + t("journal_pause") else "▶ " + t(when {
                    accelere -> "journal_rejouer_accelere"; son != null -> "journal_rejouer"; else -> "journal_rejouer_x10" }))
            }
            Text(instant?.let { heure.format(Date(it)) } ?: "", color = TextHi, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
            // A contact heard here, into the log with all the pass knew at this second.
            instant?.let { t0 -> OutlinedButton(onClick = { arrete(); contactA = Triple(t0, "", null); onglet = "MOMENTS" },
                contentPadding = PaddingValues(horizontal = 10.dp)) { Text("+ " + t("journal_contact_ici"), color = Cyan, fontSize = 12.sp) } }
        }
        Slider(value = ((instant ?: e.debutMs) - e.debutMs).toFloat() / e.dureeMs.coerceAtLeast(1),
            onValueChange = { f ->
                val t = e.debutMs + (f * e.dureeMs).toLong()
                instant = t
                val m = morceauA(t)
                if (m != courant) { runCatching { lecteur?.release() }; lecteur = null; courant = m }
                val p = lecteur
                if (p != null && debutSon != null && m == courant) runCatching {
                    if (t >= debutSon + annonce) { p.seekTo((t - debutSon).toInt()); if (joue && !p.isPlaying) p.start() }
                    else if (p.isPlaying) p.pause()
                }
            })
        // Under the slider: where the sound shows activity (light grey), and each mark, in its colour.
        if (activite.isNotEmpty() || marques.isNotEmpty()) androidx.compose.foundation.Canvas(
            Modifier.fillMaxWidth().height(8.dp).padding(horizontal = 10.dp)) {
            val d = e.dureeMs.coerceAtLeast(1).toFloat()
            fun x(t: Long) = ((t - e.debutMs) / d).coerceIn(0f, 1f) * size.width
            activite.forEach { r ->
                drawRect(androidx.compose.ui.graphics.Color(0x66CDD7E6), topLeft = androidx.compose.ui.geometry.Offset(x(r.first), 0f),
                    size = androidx.compose.ui.geometry.Size((x(r.last) - x(r.first)).coerceAtLeast(2f), size.height))
            }
            marques.forEach { m ->
                val c = androidx.compose.ui.graphics.Color(when (m.type) {
                    JournalPassage.TypeMarque.QSO -> JournalRendu.QSO; JournalPassage.TypeMarque.SSTV -> JournalRendu.SSTV[0]
                    JournalPassage.TypeMarque.SIGNET -> JournalRendu.SIGNET; else -> if (m.viaIss) JournalRendu.ISS else JournalRendu.APRS })
                drawRect(c, topLeft = androidx.compose.ui.geometry.Offset(x(m.debutMs), 0f),
                    size = androidx.compose.ui.geometry.Size((x(m.finMs) - x(m.debutMs)).coerceAtLeast(3f), size.height))
            }
        }
        pt?.let { p ->
            Text(buildList {
                add("Az %.0f° · El %.0f°".format(p.az, p.el))
                // RX where the rig was in the passband (rest frequency, as the log has it).
                frequences.lastOrNull { it.first <= (instant ?: 0L) + 2_000L }?.let { add("RX %.4f MHz".format(it.second / 1e6)) }
                p.ulHz?.let { add("↑ %.4f MHz".format(it / 1e6)) }
                if (p.rotorAz != null && p.rotorEl != null) add(tf("journal_mat_a", p.rotorAz.toInt(), p.rotorEl.toInt()))
            }.joinToString(" · "), color = TextHi, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        if (son == null) Text(t(if (e.enregistrements.isEmpty()) "journal_sans_son" else "journal_son_absent"),
            color = TextLo, fontSize = 10.sp)
        androidx.compose.material3.TabRow(selectedTabIndex = ONGLETS_FICHE.indexOf(onglet).coerceAtLeast(0),
            containerColor = SpaceBg, contentColor = Cyan, modifier = Modifier.padding(top = 8.dp)) {
            ONGLETS_FICHE.forEach { o ->
                androidx.compose.material3.Tab(selected = onglet == o, onClick = { onglet = o },
                    selectedContentColor = Cyan, unselectedContentColor = TextLo) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(vertical = 6.dp)) {
                        Icon(ICONES_FICHE.getValue(o), contentDescription = null, modifier = Modifier.size(22.dp))
                        Text(t("journal_onglet_" + o.lowercase()), fontSize = 10.sp, maxLines = 1, softWrap = false)
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        when (onglet) {
            "MOMENTS" -> {
            if (l.qsos.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(tf("journal_qsos", l.qsos.size), color = TextHi, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                l.qsos.forEach { q ->
                    val vu = instant == null || q.timeMs <= instant!!
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.alpha(if (vu) 1f else 0.3f)) {
                        Text("▸ " + heure.format(Date(q.timeMs)) + "  " + q.callsign + (if (q.theirLocator.isNotBlank()) " · " + q.theirLocator else ""),
                            color = TextHi, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f).clickable { allerA(q.timeMs - decalageQso * 1000L) }.padding(vertical = 3.dp))
                        // Its sound or a short video, from "Start before" to "Go on after".
                        if (son != null && debutSon != null) IconButton(onClick = { versMoment("QSO " + q.callsign, q.timeMs - decalageQso * 1000L) },
                            modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Share, t("journal_extrait"), tint = Cyan, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
            if (parSource.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(tf("journal_images", lv.images.size), color = TextHi, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(t("journal_images_choix"), color = TextLo, fontSize = 10.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    parSource.forEach { (f, s) ->
                        val b = remember(f.name) { vignettes[f.name] ?: PlancheRendu.charge(f, 300)?.also { vignettes[f.name] = it } }
                        val garde = f.name !in masquees
                        // During the replay, a picture appears once received; one set aside stays dim.
                        val vu = instant == null || s.timeMs <= instant!!
                        // Tapped: to where it started arriving; its ✓ / ✕ keeps or sets it aside.
                        val debutImage = marques.firstOrNull { it.fichier == f.absolutePath }?.debutMs
                            ?: (s.timeMs - (fr.f4ioz.satcombo.sstv.SstvMode.byName(s.mode)?.let { (it.frameSeconds * 1000).toLong() } ?: 60_000L))
                        Column(Modifier.width(120.dp).alpha(if (!garde) 0.3f else if (vu) 1f else 0.15f).clickable { allerA(debutImage, JournalPassage.AVANT_SSTV_MS) }) {
                            Box {
                                if (b != null) Image(b.asImageBitmap(), null, contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(6.dp)))
                                Text(if (garde) "✓" else "✕", color = androidx.compose.ui.graphics.Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.align(Alignment.TopEnd).padding(2.dp)
                                        .background(if (garde) Cyan.copy(alpha = 0.85f) else Magenta.copy(alpha = 0.85f), RoundedCornerShape(4.dp))
                                        .clickable {
                                            masquees = if (garde) masquees + f.name else masquees - f.name
                                            vm.journal.maj(e.copy(masquees = masquees))
                                        }
                                        .padding(horizontal = 8.dp, vertical = 2.dp))
                            }
                            Text(heure.format(Date(s.timeMs)) + " · " + s.mode + if (s.source == "live") "" else " · ⟳",
                                color = TextLo, fontSize = 9.sp)
                        }
                    }
                }
            }
            if (l.trames.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(tf("journal_trames", l.trames.size, l.trames.count { it.viaIss }), color = TextHi, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                l.trames.take(30).forEach { p ->
                    val vu = instant == null || p.quand <= instant!!
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.alpha(if (vu) 1f else 0.3f)) {
                        Text("▸ " + heure.format(Date(p.quand)) + "  " + p.source + if (p.viaIss) "  (ISS)" else "",
                            color = TextHi, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f).clickable { allerA(p.quand) }.padding(vertical = 3.dp))
                        IconButton(onClick = { versMoment("APRS " + p.source, p.quand) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Share, t("journal_extrait"), tint = Cyan, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            if (l.signets.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(tf("journal_signets", l.signets.size), color = TextHi, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                l.signets.forEach { sg ->
                    val vu = instant == null || sg.tMs <= instant!!
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.alpha(if (vu) 1f else 0.3f)) {
                        Text("⚑ " + heure.format(Date(sg.tMs)) + (if (sg.note.isNotBlank()) "  " + sg.note else ""),
                            color = androidx.compose.ui.graphics.Color(JournalRendu.SIGNET), fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f).clickable { allerA(sg.tMs) }.padding(vertical = 3.dp))
                        if (son != null && debutSon != null) IconButton(onClick = { versMoment("⚑ " + sg.note.ifBlank { heure.format(Date(sg.tMs)) }, sg.tMs) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Share, t("journal_extrait"), tint = Cyan, modifier = Modifier.size(18.dp))
                        }
                        // What was heard, written afterwards; or the contact it was, to the log.
                    IconButton(onClick = { signetEdite = sg }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Edit, t("journal_signet_editer"), tint = Cyan, modifier = Modifier.size(17.dp))
                    }
                    IconButton(onClick = { vm.journal.supprimeSignet(sg); onSignets() }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, t("journal_signet_supprimer"), tint = TextLo, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
            signetEdite?.let { sg ->
            var note by remember(sg) { mutableStateOf(sg.note) }
            androidx.compose.material3.AlertDialog(onDismissRequest = { signetEdite = null },
                title = { Text(tf("journal_signet_titre", heure.format(Date(sg.tMs)))) },
                text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    androidx.compose.material3.OutlinedTextField(note, { note = it.take(120) }, singleLine = true,
                        label = { Text(t("journal_signet_note")) }, modifier = Modifier.fillMaxWidth())
                    Text(t("journal_signet_carnet_desc"), color = TextLo, fontSize = 11.sp)
                } },
                confirmButton = { Row {
                    TextButton(onClick = {
                        vm.journal.noteSignet(sg, note); signetEdite = null
                        contactA = Triple(sg.tMs, note, sg)
                    }) { Text(t("journal_signet_au_carnet")) }
                    TextButton(onClick = { vm.journal.noteSignet(sg, note); signetEdite = null; onSignets() }) {
                        Text(t("journal_signet_garder_note")) }
                } },
                dismissButton = { TextButton(onClick = { signetEdite = null }) { Text(t("cancel")) } })
        }
        // A contact for the log at a moment of the pass (a bookmark's, or the one replayed).
        contactA?.let { (t0, note, sg) ->
            ContactDuJournal(ui, vm, e, t0, note, onFini = { ajoute ->
                if (ajoute && sg != null) vm.journal.supprimeSignet(sg)
                contactA = null; onSignets()
            })
        }
        if (l.qsos.isEmpty() && l.images.isEmpty() && l.trames.isEmpty() && l.signets.isEmpty())
                Text(t("journal_moments_vide"), color = TextLo, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
            }
            "REJEU" -> {
            // Fast where nothing was logged, normal from a little before each contact, picture or frame.
            if (marques.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    FilterChip(selected = accelere, onClick = { accelere = !accelere; vm.journal.setAccelere(accelere) },
                        label = { Text((if (accelere) "✓ " else "") + t("journal_accelere") + " : " + t(if (accelere) "journal_oui" else "journal_flash_non"), fontSize = 11.sp) })
                    if (accelere) listOf(5, 10, 20, 50).forEach { x ->
                        FilterChip(selected = rapide == x, onClick = { rapide = x; vm.journal.setRapide(x) }, label = { Text("×$x", fontSize = 11.sp) })
                    }
                }
                if (accelere) Text(tf("journal_accelere_desc", avantS, apresQsoS), color = TextLo, fontSize = 10.sp)
            if (accelere && son != null) FilterChip(selected = surActivite, onClick = { surActivite = !surActivite; vm.journal.setSurActivite(surActivite) },
                label = { Text((if (surActivite) "✓ " else "") + t("journal_activite") + " : " + t(if (surActivite) "journal_oui" else "journal_flash_non"), fontSize = 11.sp) })
            if (accelere && son != null && surActivite) Text(tf("journal_activite_desc", activite.count { it.last >= e.debutMs && it.first <= e.finMs }), color = TextLo, fontSize = 10.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t("journal_avant"), color = TextLo, fontSize = 11.sp)
                    Slider(value = avantS.toFloat(), valueRange = 0f..60f, steps = 11, modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        onValueChange = { avantS = it.toInt() }, onValueChangeFinished = { vm.journal.setAvantS(avantS) })
                    Text("−$avantS s", color = TextHi, fontSize = 11.sp)
                }
                // A voice contact goes on after it was logged: how long to stay at normal speed.
                if (accelere && marques.any { it.type == JournalPassage.TypeMarque.QSO }) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t("journal_apres_qso"), color = TextLo, fontSize = 11.sp)
                    Slider(value = apresQsoS.toFloat(), valueRange = 0f..120f, steps = 23, modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        onValueChange = { apresQsoS = it.toInt() }, onValueChangeFinished = { vm.journal.setApresQsoS(apresQsoS) })
                    Text("+$apresQsoS s", color = TextHi, fontSize = 11.sp)
                }
                Text(t("journal_aller_desc"), color = TextLo, fontSize = 10.sp)
            }
            if (l.images.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(t("journal_images_filtre"), color = TextLo, fontSize = 11.sp)
                listOf("" to "journal_f_toutes", "live" to "journal_f_direct", "file" to "journal_f_redecodees").forEach { (k, cle) ->
                    FilterChip(selected = filtreImages == k, onClick = { filtreImages = k }, label = { Text(t(cle), fontSize = 11.sp) })
                }
            }
            if (l.qsos.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                // Logged after the contact ended: put back to where it was heard, in step with the sound.
                Text(t("journal_decalage_qso"), color = TextLo, fontSize = 11.sp)
                Slider(value = decalageQso.toFloat(), valueRange = 0f..120f, steps = 23, modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    onValueChange = { decalageQso = it.toInt() }, onValueChangeFinished = { vm.journal.setDecalageQsoS(decalageQso) })
                Text("−$decalageQso s", color = TextHi, fontSize = 11.sp)
            }
            // Also shown (the replay and the video): the S-meter, the RX frequency, my square on the map.
            Text(t("journal_aff_aussi"), color = TextLo, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(Triple("journal_aff_smetre", affSmetre) { v: Boolean -> affSmetre = v; vm.journal.setAffSmetre(v) },
                    Triple("journal_aff_freq", affFreq) { v: Boolean -> affFreq = v; vm.journal.setAffFreq(v) },
                    Triple("journal_aff_locator", affLocator) { v: Boolean -> affLocator = v; vm.journal.setAffLocator(v) }
                ).forEach { (cle, on, change) ->
                    FilterChip(selected = on, onClick = { change(!on) },
                        label = { Text((if (on) "✓ " else "") + t(cle) + " : " + t(if (on) "journal_oui" else "journal_flash_non"), fontSize = 11.sp) })
                }
            }
            if (marques.isNotEmpty()) {
                // What shows during the replay, each on or off; then for how long.
                Text(t("journal_pendant_rejeu"), color = TextLo, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = affSstv, onClick = { affSstv = !affSstv; vm.journal.setAffSstv(affSstv) },
                        label = { Text((if (affSstv) "✓ " else "") + t("journal_aff_sstv") + " : " + t(if (affSstv) "journal_oui" else "journal_flash_non"), fontSize = 11.sp) })
                    FilterChip(selected = affFiches, onClick = { affFiches = !affFiches; vm.journal.setAffFiches(affFiches) },
                        label = { Text((if (affFiches) "✓ " else "") + t("journal_aff_fiches") + " : " + t(if (affFiches) "journal_oui" else "journal_flash_non"), fontSize = 11.sp) })
                }
                if (affSstv || affFiches) Text(t("journal_garde"), color = TextLo, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                if (affSstv || affFiches) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(3, 5, 10).forEach { n ->
                        FilterChip(selected = flashS == n, onClick = { flashS = n; vm.journal.setFlashS(n) },
                            label = { Text("$n s", fontSize = 11.sp) })
                    }
                }
            }
            if (affSstv && marques.any { it.type == JournalPassage.TypeMarque.SSTV }) {
                // The picture's size, set by eye; shown while it is being set.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t("journal_flash_taille"), color = TextLo, fontSize = 11.sp)
                    Slider(value = tailleFlash, valueRange = 0.15f..0.7f, modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        onValueChange = { v -> tailleFlash = v; apercuJusqua = System.currentTimeMillis() + 2_000L },
                        onValueChangeFinished = { vm.journal.setFlashTaille(tailleFlash) })
                    Text("%d %%".format((tailleFlash * 100).toInt()), color = TextHi, fontSize = 11.sp)
                }
            }
            }
            "BILAN" -> {
            // The recordings of the pass: several pieces may make one; attached or detached by hand.
            Text(tf("journal_enreg_titre", morceaux.size), color = TextHi, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            morceaux.forEach { m ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text((if (m == courant) "▶ " else "• ") + m.f.name.removePrefix("SatMe_").removeSuffix(".mp3") + "  " +
                        heure.format(Date(m.origine + m.annonce)) + " · " + duree(m.dureeMs - m.annonce),
                        color = TextHi, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                    if (m.f.name in e.enregistrements) IconButton(onClick = {
                        onChange(e.copy(enregistrements = e.enregistrements - m.f.name))
                    }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Close, t("journal_enreg_detacher"), tint = TextLo, modifier = Modifier.size(16.dp)) }
                }
            }
            var rattache by remember(e.id) { mutableStateOf(false) }
            TextButton(onClick = { rattache = !rattache }) { Text("+ " + t("journal_enreg_rattacher"), color = Cyan, fontSize = 12.sp) }
            if (rattache) {
                val proches = remember(e.id, e.enregistrements) { vm.journal.enregistrementsProches(e) }
                if (proches.isEmpty()) Text(t("journal_enreg_aucun"), color = TextLo, fontSize = 11.sp)
                proches.forEach { f ->
                    Text("⤓ " + f.name.removePrefix("SatMe_").removeSuffix(".mp3"), color = Cyan, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.fillMaxWidth().clickable {
                            onChange(e.copy(enregistrements = (e.enregistrements + f.name).distinct())); rattache = false
                        }.padding(vertical = 4.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
            // What came of it.
            Spacer(Modifier.height(10.dp))
            if (e.reconstitue) Text(t("journal_reconstitue_desc"), color = Amber, fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp))
            if (e.auto.isNotBlank()) Text(autoLib(e.auto) + " — " + t("journal_auto_desc"), color = Amber, fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp))
            Text(t("journal_bilan"), color = TextHi, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            val lignes = buildList {
                add(tf("journal_l_duree", duree(e.dureeMs), e.elMax.toInt()))
                if (e.locator.isNotBlank() || e.profil.isNotBlank())
                    add(listOf(e.locator, e.profil).filter { it.isNotBlank() }.joinToString(" · "))
                if (e.transpondeur.isNotBlank()) add(e.transpondeur)
                JournalPassage.descente(e)?.let { (d, mn, mx) ->
                    add(tf("journal_l_doppler", "%.4f".format(d / 1e6), ((mx - mn) / 1000.0).let { "%.1f".format(it) }))
                }
                JournalPassage.ecartMat(e)?.let { (a, b) -> add(tf("journal_l_mat", "%.1f".format(a), "%.1f".format(b))) }
                if (e.test.isNotEmpty()) add(tf("journal_l_test",
                    e.test.count { it.value == "OK" }, e.test.count { it.value == "WARNING" }, e.test.count { it.value == "ERROR" }))
            }
            lignes.forEach { Text("• $it", color = TextLo, fontSize = 12.sp) }
            }
            else -> {
            // One path: what (the whole pass, or a moment of it), in which form, one button, one result.
            Text(t("journal_creer_titre"), color = TextHi, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(t("journal_quoi"), color = TextLo, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = portee == "tout", onClick = { portee = "tout"; pret = null }, label = { Text(t("journal_tout_passage"), fontSize = 12.sp) })
                FilterChip(selected = portee == "moment", onClick = { portee = "moment"; pret = null; if (format == "image") format = "video" },
                    label = { Text(t("journal_un_moment"), fontSize = 12.sp) })
            }
            val dX = decoupeDe; val aX = decoupeA
            val valide = dX != null && aX != null && aX - dX >= 3_000L
            if (portee == "moment") {
                Text(t("journal_decoupe_desc"), color = TextLo, fontSize = 10.sp)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { decoupeDe = instant ?: e.debutMs; pret = null }) { Text("⇤ " + t("journal_decoupe_debut"), color = Cyan, fontSize = 12.sp) }
                    Text(decoupeDe?.let { heure.format(Date(it)) } ?: "—", color = TextHi, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    OutlinedButton(onClick = { decoupeA = instant ?: e.finMs; pret = null }) { Text(t("journal_decoupe_fin") + " ⇥", color = Cyan, fontSize = 12.sp) }
                    Text(decoupeA?.let { heure.format(Date(it)) } ?: "—", color = TextHi, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
                if (dX != null && aX != null && !valide) Text(t("journal_decoupe_invalide"), color = Amber, fontSize = 11.sp)
                // Who it is about: the stations of the pass, one tap for the title.
                val qui = remember(l) { (l.qsos.map { "QSO " + it.callsign } + l.trames.map { "APRS " + it.source }).distinct() }
                if (qui.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    qui.forEach { q -> FilterChip(selected = titreDecoupe == q, onClick = { titreDecoupe = q }, label = { Text(q, fontSize = 11.sp) }) }
                }
                OutlinedTextField(titreDecoupe, { titreDecoupe = it.take(40) }, singleLine = true,
                    label = { Text(t("journal_extrait_titre_video")) }, modifier = Modifier.fillMaxWidth())
            }
            Text(t("journal_sous_forme"), color = TextLo, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                buildList {
                    if (portee == "tout") add("image" to "journal_fmt_image")
                    add("video" to "journal_video"); add("gif" to "sstv_gif")
                    if (morceaux.isNotEmpty()) add("son" to "journal_extrait_son")
                }.forEach { (k, cle) -> FilterChip(selected = format == k, onClick = { format = k; pret = null }, label = { Text(t(cle), fontSize = 12.sp) }) }
            }
            // The options that matter for that form only.
            var avecSon by remember { mutableStateOf(true) }
            var resolution by remember { mutableStateOf(vm.journal.videoRes()) }
            var ouverture by remember { mutableStateOf(vm.journal.ouverture()) }
            var recap by remember { mutableStateOf(vm.journal.recap()) }
            if (format == "video" && portee == "tout" && marques.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { accelere = !accelere; vm.journal.setAccelere(accelere); pret = null }) {
                Checkbox(checked = accelere, onCheckedChange = null)
                Text(tf("journal_export_accelere", rapide), color = TextHi, fontSize = 12.sp)
            }
            if (format == "video" && morceaux.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { avecSon = !avecSon; pret = null }) {
                Checkbox(checked = avecSon, onCheckedChange = null)
                Text(t("journal_avec_son"), color = TextHi, fontSize = 12.sp)
            }
            if (format == "video") {
                Text(t("journal_resolution"), color = TextLo, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("XS" to "journal_res_tres_legere", "M" to "journal_res_legere", "HD" to "journal_res_hd").forEach { (k, cle) ->
                        FilterChip(selected = resolution == k, onClick = { resolution = k; vm.journal.setVideoRes(k); pret = null },
                            label = { Text(t(cle), fontSize = 11.sp) })
                    }
                }
            }
            if (format == "video" || format == "gif") Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { ouverture = !ouverture; vm.journal.setOuverture(ouverture); pret = null }) {
                Checkbox(checked = ouverture, onCheckedChange = null)
                Column {
                    Text(t("journal_ouverture"), color = TextHi, fontSize = 12.sp)
                    Text(t("journal_ouverture_desc"), color = TextLo, fontSize = 10.sp)
                }
            }
            if ((format == "video" || format == "gif") && portee == "tout") Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { recap = !recap; vm.journal.setRecap(recap); pret = null }) {
                Checkbox(checked = recap, onCheckedChange = null)
                Column {
                    Text(t("journal_recap"), color = TextHi, fontSize = 12.sp)
                    Text(t("journal_recap_desc"), color = TextLo, fontSize = 10.sp)
                }
            }
            Text(t(when {
                format == "son" -> "journal_fmt_son_desc"
                format == "image" -> "journal_fmt_image_desc"
                format == "gif" -> "journal_fmt_gif_desc"
                morceaux.isNotEmpty() && avecSon -> "journal_fmt_video_son"
                else -> "journal_fmt_video_x10" }) + if (format != "son") " " + t(if (surCarte) "journal_export_carte" else "journal_export_ciel") else "",
                color = TextLo, fontSize = 10.sp)
            Button(enabled = fabrication == null && (portee == "tout" || valide), modifier = Modifier.padding(top = 4.dp), onClick = {
                fabrication = 0f; pret = null; echec = false
                val moment = portee == "moment"
                val de = if (moment) dX!! else e.debutMs
                val a = if (moment) aX!! else e.finMs
                val fmt = format
                scope.launch {
                    val f = withContext(Dispatchers.IO) {
                        val nomX = if (moment) JournalRendu.nomExtrait(e, titreDecoupe.substringAfter(' ').ifBlank { "moment" }, de) else null
                        if (fmt == "son") JournalRendu.extraitWav(ctx, morceaux, de, a, nomX ?: JournalRendu.nomExtrait(e, "passage", de))
                        else {
                            val qth = e.locator.takeIf { it.length >= 4 }?.let { fr.f4ioz.satcombo.location.Maidenhead.toLatLon(it) }
                            val sc = JournalRendu.Scene(e, prevue, marques, sol, qth, surCarte, ui.callsign, ui.useUtc, flashS, tailleFlash,
                                affSstv = affSstv, affFiches = affFiches,
                                cadreVu = if (surCarte) null else cadreVu, vueVue = if (surCarte) vueVue else null,
                                fiches = fiches, recap = recap && !moment && fmt != "image", ouverture = ouverture && fmt != "image",
                                resolution = resolution,
                                segments = if (moment) listOf(JournalPassage.Segment(de, a, 1)) else if (fmt == "video" && accelere) segs else null,
                                nomFichier = nomX, titreExtrait = if (moment) titreDecoupe.trim() else null,
                                affSmetre = affSmetre, affFreq = affFreq, etiquetteQth = etiquetteQth, frequences = frequences)
                            when (fmt) {
                                "image" -> JournalRendu.png(ctx, sc, instant)
                                "gif" -> JournalRendu.gif(ctx, sc) { fabrication = it }
                                else -> JournalRendu.video(ctx, sc, if (avecSon) morceaux else emptyList()) { fabrication = it }
                            }
                        }
                    }
                    fabrication = null; pret = f; echec = f == null
                }
            }) { Text(t("journal_creer")) }
            fabrication?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) }
            if (echec) Text(t("journal_extrait_echec"), color = Amber, fontSize = 11.sp)
            pret?.let { f ->
                val type = when { f.name.endsWith(".gif") -> "image/gif"; f.name.endsWith(".png") -> "image/png"
                    f.name.endsWith(".wav") -> "audio/wav"; else -> "video/mp4" }
                Text(f.name + " · " + tf("sstv_video_taille", "%.1f".format(f.length() / 1_048_576.0)), color = TextHi, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(modifier = Modifier.weight(1f), onClick = { runCatching {
                        val u = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
                        ctx.startActivity(android.content.Intent.createChooser(
                            android.content.Intent(android.content.Intent.ACTION_SEND).setType(type)
                                .putExtra(android.content.Intent.EXTRA_STREAM, u)
                                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION), f.name))
                    } }) { Text(t("rec_share"), fontSize = 12.sp) }
                    OutlinedButton(modifier = Modifier.weight(1f), onClick = { enregistre(f.name, type, depuisFichier(f)) }) {
                        Text(t("journal_sur_tel"), color = Cyan, fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onSupprime) { Text(t("journal_supprimer"), color = Magenta, fontSize = 12.sp) }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * Passes found again from the online log (Wavelog / Cloudlog): its satellite
 * contacts read in full, kept by period, satellite and the station's square
 * of the time, their passes rebuilt; the contacts not on this phone yet come
 * into its log, marked as already online.
 */
@Composable
private fun ImportEnLigne(ui: UiState, vm: MainViewModel, onFini: (String?) -> Unit) {
    val scope = rememberCoroutineScope()
    // First the station locations (each with its square), then their contacts.
    var profils by remember { mutableStateOf<List<fr.f4ioz.satcombo.domain.ProfilsStation.Profil>?>(null) }
    var profilsChoisis by remember { mutableStateOf<Set<String>>(emptySet()) }
    var lus by remember { mutableStateOf<List<fr.f4ioz.satcombo.data.AdifImport.ContactEnLigne>?>(null) }
    var lecture by remember { mutableStateOf(false) }
    var erreur by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val (l, m) = withContext(Dispatchers.IO) { vm.profilsEnLigne() }
        profils = l; erreur = m
    }
    var jours by remember { mutableStateOf(30) }
    var sat by remember { mutableStateOf("") }
    var loc by remember { mutableStateOf("") }
    var candidats by remember { mutableStateOf<Pair<List<JournalPassage.Candidat>, Int>?>(null) }
    var gardes by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var occupe by remember { mutableStateOf(false) }
    val jour = remember(ui.useUtc) { formatDate(ui.useUtc, "EEE dd/MM/yy HH:mm") }
    val maintenant = remember { System.currentTimeMillis() }
    val retenus = remember(lus, jours, sat, loc) {
        lus.orEmpty().filter { q -> (jours == 0 || q.quandMs >= maintenant - jours * 86_400_000L) &&
            (sat.isBlank() || q.satellite == sat) && (loc.isBlank() || q.monLocator.startsWith(loc)) }
    }
    Dialog(onDismissRequest = { onFini(null) }) {
        Surface(color = SpaceCard, shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.padding(14.dp).heightIn(max = 620.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(t("journal_enligne"), color = TextHi, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(t("journal_enligne_desc"), color = TextLo, fontSize = 11.sp)
                val ps = profils
                if (ps == null) { LinearProgressIndicator(Modifier.fillMaxWidth()); return@Column }
                erreur?.let { Text(it, color = Amber, fontSize = 12.sp) }
                val l = lus
                if (l == null) {
                    // Which station locations to read (each its own square): all of them by default.
                    if (ps.isEmpty()) { TextButton(onClick = { onFini(null) }) { Text(t("close")) }; return@Column }
                    Text(t("journal_enligne_profils"), color = TextLo, fontSize = 11.sp)
                    ps.forEach { p ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable {
                            profilsChoisis = if (p.id in profilsChoisis) profilsChoisis - p.id else profilsChoisis + p.id }) {
                            Checkbox(checked = p.id in profilsChoisis, onCheckedChange = null)
                            Text(listOf(p.nom, p.carre, p.indicatif).filter { it.isNotBlank() }.joinToString(" · "), color = TextHi, fontSize = 13.sp)
                        }
                    }
                    Button(enabled = !lecture && profilsChoisis.isNotEmpty(), onClick = {
                        lecture = true
                        scope.launch {
                            val (r, m) = withContext(Dispatchers.IO) { vm.contactsEnLigne(ps.filter { it.id in profilsChoisis }) }
                            lus = r; erreur = m; lecture = false
                        }
                    }) { Text(t("journal_enligne_lire")) }
                    if (lecture) LinearProgressIndicator(Modifier.fillMaxWidth())
                    return@Column
                }
                if (l.isEmpty()) { TextButton(onClick = { lus = null }) { Text(t("back")) }; return@Column }
                val c = candidats
                if (c == null) {
                    Text(tf("journal_enligne_lus", l.size), color = TextHi, fontSize = 12.sp)
                    Text(t("journal_enligne_periode"), color = TextLo, fontSize = 11.sp)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(7 to "7 j", 30 to "30 j", 90 to "90 j", 365 to "1 an", 0 to t("journal_f_toutes")).forEach { (n, lib) ->
                            FilterChip(selected = jours == n, onClick = { jours = n }, label = { Text(lib, fontSize = 11.sp) }) }
                    }
                    Text(t("journal_enligne_sat"), color = TextLo, fontSize = 11.sp)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterChip(selected = sat.isBlank(), onClick = { sat = "" }, label = { Text(t("journal_f_toutes"), fontSize = 11.sp) })
                        l.groupingBy { it.satellite }.eachCount().entries.sortedByDescending { it.value }.forEach { (n, k) ->
                            FilterChip(selected = sat == n, onClick = { sat = n }, label = { Text("$n ($k)", fontSize = 11.sp) }) }
                    }
                    Text(t("journal_enligne_loc"), color = TextLo, fontSize = 11.sp)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterChip(selected = loc.isBlank(), onClick = { loc = "" }, label = { Text(t("journal_f_toutes"), fontSize = 11.sp) })
                        l.map { it.monLocator.take(6) }.filter { it.length >= 4 }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.forEach { (n, k) ->
                            FilterChip(selected = loc == n, onClick = { loc = n }, label = { Text("$n ($k)", fontSize = 11.sp) }) }
                    }
                    Button(enabled = !occupe && retenus.isNotEmpty(), onClick = {
                        occupe = true
                        scope.launch {
                            val r = withContext(Dispatchers.Default) { vm.journal.candidatsImport(vm.evenementsEnLigne(retenus)) }
                            candidats = r; gardes = emptySet(); occupe = false
                        }
                    }) { Text(tf("journal_enligne_chercher", retenus.size)) }
                    if (occupe) LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    val (liste, perdus) = c
                    Text(tf("journal_trouves", liste.size) + if (perdus > 0) " " + tf("journal_non_places", perdus) else "", color = TextHi, fontSize = 12.sp)
                    if (liste.isEmpty()) Text(t("journal_rien"), color = TextLo, fontSize = 12.sp)
                    liste.forEachIndexed { i, x ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()
                            .clickable { gardes = if (i in gardes) gardes - i else gardes + i }) {
                            Checkbox(checked = i in gardes, onCheckedChange = null)
                            Column {
                                Text(x.satName + " · " + jour.format(Date(x.aosMs)) + (if (ui.useUtc) " UTC" else ""), color = TextHi, fontSize = 13.sp)
                                Text(tf("journal_src_nb", x.nb) + (if (x.locator.isNotBlank()) " · " + x.locator else ""), color = TextLo, fontSize = 10.sp)
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { candidats = null }) { Text(t("back"), color = TextLo) }
                        Button(enabled = !occupe && gardes.isNotEmpty(), onClick = {
                            occupe = true
                            scope.launch {
                                val (p, q) = withContext(Dispatchers.IO) { vm.importeEnLigne(gardes.sorted().map { liste[it] }, retenus) }
                                occupe = false; onFini(tf("journal_enligne_fait", p, q))
                            }
                        }) { Text(tf("journal_importer_n", gardes.size)) }
                    }
                    if (occupe) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/**
 * A contact for the log at [tMs] of pass [e]: drafted with what the pass knew
 * (frequencies at rest, mode, az/el, locator), the callsign from the
 * correspondents already worked or looked up (log, QRZ.com), then written.
 */
@Composable
private fun ContactDuJournal(ui: UiState, vm: MainViewModel, e: JournalPassage.Entree, tMs: Long, note0: String,
                             onFini: (Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    var brouillon by remember(tMs) { mutableStateOf<fr.f4ioz.satcombo.data.LogEntry?>(null) }
    LaunchedEffect(tMs) { brouillon = vm.brouillonContact(e, tMs) }
    var indicatif by remember(tMs) { mutableStateOf("") }
    var carre by remember(tMs) { mutableStateOf("") }
    var rstE by remember(tMs) { mutableStateOf("59") }
    var rstR by remember(tMs) { mutableStateOf("59") }
    var nom by remember(tMs) { mutableStateOf("") }
    var qth by remember(tMs) { mutableStateOf("") }
    var note by remember(tMs) { mutableStateOf(note0) }
    var cherche by remember(tMs) { mutableStateOf(false) }
    val heure = remember(ui.useUtc) { formatDate(ui.useUtc, "HH:mm:ss") }
    androidx.compose.material3.AlertDialog(onDismissRequest = { onFini(false) },
        title = { Text(tf("journal_contact_titre", heure.format(Date(tMs)) + if (ui.useUtc) " UTC" else "")) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val b = brouillon
            if (b == null) Text(t("journal_paquet_en_cours"), color = TextLo, fontSize = 11.sp)
            else Text(listOf(b.satName, b.mode.ifBlank { null },
                if (b.downlinkMhz > 0) "↓ %.4f".format(b.downlinkMhz) else null,
                if (b.uplinkMhz > 0) "↑ %.4f MHz".format(b.uplinkMhz) else null,
                "Az %.0f° El %.0f°".format(b.azimuthDeg, b.elevationDeg)).filterNotNull().joinToString(" · "),
                color = TextLo, fontSize = 11.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(indicatif, { indicatif = it.uppercase().filter { c -> c.isLetterOrDigit() || c == '/' }.take(14) },
                    singleLine = true, label = { Text(t("journal_signet_indicatif")) }, modifier = Modifier.weight(1f))
                // Who it is: the log first, then QRZ.com.
                TextButton(enabled = indicatif.length >= 3 && !cherche, onClick = {
                    cherche = true
                    scope.launch {
                        vm.journal.ficheIndicatif(indicatif)?.let { f ->
                            if (carre.isBlank()) carre = f.locator
                            if (nom.isBlank()) nom = f.nom
                            if (qth.isBlank()) qth = listOf(f.qth, f.pays).filter { it.isNotBlank() }.joinToString(", ")
                        }
                        cherche = false
                    }
                }) { Text(if (cherche) "…" else t("journal_contact_chercher"), fontSize = 12.sp) }
            }
            // Already worked: one tap fills the callsign, its square and its name.
            val connus = remember(indicatif) { vm.suggestionsIndicatif(indicatif) }
            if (connus.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                connus.forEach { k -> FilterChip(selected = false, onClick = {
                    indicatif = k.indicatif
                    if (carre.isBlank()) carre = k.locators.maxByOrNull { it.contacts }?.locator.orEmpty()
                    if (nom.isBlank()) nom = k.nom
                }, label = { Text(k.indicatif + if (k.nom.isNotBlank()) " · " + k.nom else "", fontSize = 11.sp) }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(carre, { carre = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(6) },
                    singleLine = true, label = { Text("Locator") }, modifier = Modifier.weight(1.4f))
                OutlinedTextField(rstE, { rstE = it.filter { c -> c.isDigit() }.take(3) }, singleLine = true,
                    label = { Text("RS ↑") }, modifier = Modifier.weight(1f))
                OutlinedTextField(rstR, { rstR = it.filter { c -> c.isDigit() }.take(3) }, singleLine = true,
                    label = { Text("RS ↓") }, modifier = Modifier.weight(1f))
            }
            OutlinedTextField(nom, { nom = it.take(40) }, singleLine = true, label = { Text(t("journal_contact_nom")) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(qth, { qth = it.take(60) }, singleLine = true, label = { Text("QTH") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(note, { note = it.take(120) }, singleLine = true, label = { Text(t("journal_contact_note")) }, modifier = Modifier.fillMaxWidth())
        } },
        confirmButton = { TextButton(enabled = brouillon != null && indicatif.isNotBlank(), onClick = {
            val b = brouillon ?: return@TextButton
            onFini(vm.ajouteAuCarnet(b.copy(callsign = indicatif, theirLocator = carre, rstSent = rstE, rstRcvd = rstR,
                nom = nom.trim(), qth = qth.trim(), note = note.trim())))
        }) { Text(t("journal_signet_au_carnet")) } },
        dismissButton = { TextButton(onClick = { onFini(false) }) { Text(t("cancel")) } })
}

private val ONGLETS_LISTE = listOf("PASSAGES", "BILAN", "RETROUVER")

private val ICONES_LISTE = mapOf(
    "PASSAGES" to Icons.AutoMirrored.Filled.List,
    "BILAN" to Icons.Default.Assessment,
    "RETROUVER" to Icons.Default.Search,
)

/**
 * The journal at a glance: totals, by satellite, then the map of every
 * station worked or heard (OpenStreetMap), made on demand, shared or saved.
 */
@Composable
private fun BilanJournalVue(ui: UiState, vm: MainViewModel, passages: List<JournalPassage.Entree>,
                            liens: Map<String, JournalDesPassages.Liens>) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val enregistre = rememberEnregistrer()
    val b = remember(passages, liens) { vm.journal.bilan(passages, liens) }
    var carte by remember { mutableStateOf<java.io.File?>(null) }
    var enCours by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        val tt = b.total
        Text(tf("journal_bilan_total", tt.passages, tt.qsos, b.indicatifs, tt.images, b.stationsAprs, tt.signets),
            color = TextHi, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        // By satellite: passes, contacts, pictures, APRS stations, bookmarks.
        Row(Modifier.fillMaxWidth()) {
            listOf(t("journal_bilan_sat") to 2f, "#" to 1f, "QSO" to 1f, "🖼" to 1f, "APRS" to 1f, "⚑" to 1f).forEach { (x, p) ->
                Text(x, color = TextLo, fontSize = 11.sp, modifier = Modifier.weight(p))
            }
        }
        b.lignes.forEach { l ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(l.sat, color = TextHi, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(2f), maxLines = 1)
                listOf(l.passages, l.qsos, l.images, l.aprs, l.signets).forEach {
                    Text(if (it == 0) "·" else it.toString(), color = TextHi, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(tf("journal_bilan_carte_desc", b.stations.count { it.qso }, b.stations.count { !it.qso }), color = TextLo, fontSize = 11.sp)
        Button(enabled = !enCours && b.stations.isNotEmpty(), onClick = {
            enCours = true
            scope.launch {
                val qth = runCatching { vm.myLocator() }.getOrNull()?.takeIf { it.length >= 4 }
                    ?.let { fr.f4ioz.satcombo.location.Maidenhead.toLatLon(it) }
                carte = withContext(Dispatchers.IO) {
                    JournalRendu.carteStations(ctx, b.stations, qth,
                        tf("journal_bilan_legende", ui.callsign.ifBlank { "SatMe" }, b.stations.count { it.qso }, b.stations.count { !it.qso }))
                }
                enCours = false
            }
        }, modifier = Modifier.padding(top = 4.dp)) { Text(if (enCours) t("journal_paquet_en_cours") else t("journal_bilan_carte")) }
        carte?.let { f ->
            val img = remember(f) { android.graphics.BitmapFactory.decodeFile(f.absolutePath) }
            if (img != null) Image(img.asImageBitmap(), null, modifier = Modifier.fillMaxWidth().aspectRatio(1f).padding(top = 6.dp).clip(RoundedCornerShape(8.dp)))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                Button(modifier = Modifier.weight(1f), onClick = { runCatching {
                    val u = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
                    ctx.startActivity(android.content.Intent.createChooser(
                        android.content.Intent(android.content.Intent.ACTION_SEND).setType("image/png")
                            .putExtra(android.content.Intent.EXTRA_STREAM, u)
                            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION), f.name))
                } }) { Text(t("rec_share"), fontSize = 12.sp) }
                OutlinedButton(modifier = Modifier.weight(1f), onClick = { enregistre(f.name, "image/png", depuisFichier(f)) }) {
                    Text(t("export_save"), color = Cyan, fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private val ONGLETS_FICHE = listOf("MOMENTS", "REJEU", "BILAN", "PARTAGER")

private val ICONES_FICHE = mapOf(
    "MOMENTS" to Icons.Default.Schedule,
    "REJEU" to Icons.Default.Tune,
    "BILAN" to Icons.Default.Assessment,
    "PARTAGER" to Icons.Default.Share,
)

/** The colours of the marks. */
@Composable
private fun LegendeMarques() {
    @Composable
    fun Puce(c: Int, texte: String) = Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(androidx.compose.foundation.shape.CircleShape).background(androidx.compose.ui.graphics.Color(c)))
        Spacer(Modifier.width(4.dp)); Text(texte, color = TextLo, fontSize = 10.sp)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 2.dp).horizontalScroll(rememberScrollState())) {
        Puce(JournalRendu.QSO, "QSO"); Puce(JournalRendu.APRS, "APRS"); Puce(JournalRendu.ISS, t("journal_via_iss"))
        Puce(JournalRendu.SSTV[0], t("journal_l_sstv")); Puce(JournalRendu.SIGNET, "⚑ " + t("journal_l_signet"))
    }
}

/**
 * The sky of the pass, framed on the trajectory (a low pass is enlarged);
 * pinch to zoom, drag to move, double tap to come back.
 */
@Composable
private fun CielJournal(
    e: JournalPassage.Entree, prevue: List<Pair<Double, Double>>, marques: List<JournalPassage.Marque>, instant: Long?,
    arrivee: Pair<JournalPassage.Marque, Float>?, tailleFlash: Float,
    encarts: List<Pair<JournalPassage.Marque, JournalPassage.Fiche?>>,
    rx: Long? = null, smetre: Int? = null, crete: Int? = null,
    onCadre: (JournalPassage.Cadre) -> Unit
) {
    val bande = if (rx != null || smetre != null) HAUT_BANDE else 0.dp
    val base = remember(e.id) { JournalPassage.cadre(e.points.map { it.az to it.el }) }
    var toutLeCiel by remember(e.id) { mutableStateOf(false) }
    var zoom by remember(e.id) { mutableStateOf(1.0) }
    var px by remember(e.id) { mutableStateOf(0.0) }
    var py by remember(e.id) { mutableStateOf(0.0) }
    val sombre = isDarkTheme()
    val dp = androidx.compose.ui.platform.LocalDensity.current.density
    val depart = if (toutLeCiel) JournalPassage.Cadre() else base
    val cadre = JournalPassage.Cadre(depart.cx + px, depart.cy + py, (depart.echelle * zoom).coerceIn(0.8, 40.0))
    LaunchedEffect(cadre) { onCadre(cadre) }
    Box(Modifier.fillMaxWidth().aspectRatio(1f).padding(vertical = 6.dp).clip(RoundedCornerShape(12.dp))) {
        androidx.compose.foundation.Canvas(Modifier.matchParentSize()
            .pointerInput(e.id, toutLeCiel) {
                detectTransformGestures { _, dep, f, _ ->
                    zoom = (zoom * f).coerceIn(0.5, 20.0)
                    val r = minOf(size.width, size.height) / 2.0 * 0.88 * depart.echelle * zoom
                    px -= dep.x / r; py -= dep.y / r
                }
            }
            .pointerInput(e.id) {
                detectTapGestures(onDoubleTap = { zoom = 1.0; px = 0.0; py = 0.0 })
            }) {
            drawIntoCanvas { c ->
                JournalRendu.ciel(c.nativeCanvas, size.width, size.height, e, prevue, marques, instant, cadre, sombre, dp)
            }
        }
        arrivee?.let { a ->
            // Away from the satellite: the other side of the view.
            val p = instant?.let { JournalPassage.pointA(e, it) }
            val adroite = p != null && JournalPassage.ciel(p.az, p.el).first >= cadre.cx
            FlashImage(a, if (adroite) Alignment.BottomStart else Alignment.BottomEnd, tailleFlash, bande)
        }
        if (encarts.isNotEmpty()) {
            // Same side as the picture (away from the satellite), at the other end: never under it.
            val p = instant?.let { JournalPassage.pointA(e, it) }
            val adroite = p != null && JournalPassage.ciel(p.az, p.el).first >= cadre.cx
            EncartsStations(encarts, e.locator, if (adroite) Alignment.TopStart else Alignment.TopEnd, haut = 40.dp)
        }
        BandeSignal(rx, smetre, crete)
        TextButton(onClick = { toutLeCiel = !toutLeCiel; zoom = 1.0; px = 0.0; py = 0.0 },
            modifier = Modifier.align(Alignment.TopEnd)) {
            Text(t(if (toutLeCiel) "journal_vue_passage" else "journal_tout_ciel"), color = Cyan, fontSize = 11.sp)
        }
    }
}

/**
 * The pass on the map (OpenStreetMap): the ground track and the footprint of
 * the satellite, each SSTV picture as its stretch, each station worked or
 * heard as a point on the track at that moment and, in grey, at its locator
 * or APRS position; the station at home.
 */
@Composable
private fun CarteJournal(
    e: JournalPassage.Entree, sol: List<JournalPassage.Sol>, marques: List<JournalPassage.Marque>,
    instant: Long?, locator: String, arrivee: Pair<JournalPassage.Marque, Float>?, tailleFlash: Float,
    encarts: List<Pair<JournalPassage.Marque, JournalPassage.Fiche?>>, etiquetteQth: String?,
    rx: Long? = null, smetre: Int? = null, crete: Int? = null,
    onVue: (JournalPassage.VueCarte, Float) -> Unit
) {
    val bande = if (rx != null || smetre != null) HAUT_BANDE else 0.dp
    val fournisseur = remember { MapProviders.byId("OSM") }
    val portee = rememberCoroutineScope()
    val tuiles = remember { TileStore(portee) }
    var taille by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
    var cx by remember(e.id) { mutableStateOf(0.5) }
    var cy by remember(e.id) { mutableStateOf(0.5) }
    var zoom by remember(e.id) { mutableStateOf(2.0) }
    fun wx(lon: Double) = (lon + 180.0) / 360.0
    fun wy(lat: Double): Double {
        val l = Math.toRadians(lat.coerceIn(-85.05, 85.05))
        return (1 - kotlin.math.ln(kotlin.math.tan(l) + 1 / kotlin.math.cos(l)) / Math.PI) / 2
    }
    val qth = remember(locator) { locator.takeIf { it.length >= 4 }?.let { fr.f4ioz.satcombo.location.Maidenhead.toLatLon(it) } }
    // The satellite now (replay), else at the top of the pass.
    val haut = remember(e.id) { e.points.maxByOrNull { it.el }?.tMs }
    val ici = (instant ?: haut)?.let { JournalPassage.solA(sol, it) }
    val empreinte = remember(ici) { ici?.let { JournalPassage.empreinte(it.lat, it.lon, it.altKm) } ?: emptyList() }
    // Framed on the footprint, the ground track, the stations and home.
    LaunchedEffect(e.id, taille) {
        if (taille.width <= 0f) return@LaunchedEffect
        val pts = sol.map { it.lat to it.lon } + empreinte + marques.mapNotNull { m -> m.lat?.let { la -> m.lon?.let { la to it } } } + listOfNotNull(qth)
        if (pts.isEmpty()) return@LaunchedEffect
        val v = JournalPassage.cadreCarte(pts, taille.width.toDouble(), taille.height.toDouble())
        cx = v.cx; cy = v.cy; zoom = v.zoom
    }
    val dp = androidx.compose.ui.platform.LocalDensity.current.density
    var satEcranX by remember { mutableStateOf(0f) }
    Box(Modifier.fillMaxWidth().aspectRatio(1f).padding(vertical = 6.dp).clip(RoundedCornerShape(12.dp))
        .background(androidx.compose.ui.graphics.Color(0xFFDDE6F1))) {
        androidx.compose.foundation.Canvas(Modifier.matchParentSize()
            .onSizeChanged { taille = androidx.compose.ui.geometry.Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(e.id) {
                detectTransformGestures { centre, dep, facteur, _ ->
                    val w = 256.0 * Math.pow(2.0, zoom)
                    val qx = cx + (centre.x - size.width / 2) / w
                    val qy = cy + (centre.y - size.height / 2) / w
                    zoom = (zoom + kotlin.math.log2(facteur.toDouble())).coerceIn(0.0, fournisseur.maxZ.toDouble())
                    val w2 = 256.0 * Math.pow(2.0, zoom)
                    cx = (qx - (centre.x - size.width / 2) / w2 - dep.x / w2 + 1) % 1.0
                    cy = (qy - (centre.y - size.height / 2) / w2 - dep.y / w2).coerceIn(0.0, 1.0)
                }
            }) {
            val w = 256.0 * Math.pow(2.0, zoom)
            fun xy(lat: Double, lon: Double) = androidx.compose.ui.geometry.Offset(((wx(lon) - cx) * w + size.width / 2).toFloat(),
                ((wy(lat) - cy) * w + size.height / 2).toFloat())
            val z = kotlin.math.floor(zoom).toInt().coerceIn(0, fournisseur.maxZ)
            val n = 1 shl z
            val cote = (w / n).toFloat()
            val x0 = kotlin.math.floor((cx * w - size.width / 2) / cote).toInt()
            val x1 = kotlin.math.floor((cx * w + size.width / 2) / cote).toInt()
            val y0 = kotlin.math.floor((cy * w - size.height / 2) / cote).toInt().coerceAtLeast(0)
            val y1 = kotlin.math.floor((cy * w + size.height / 2) / cote).toInt().coerceAtMost(n - 1)
            for (tx in x0..x1) for (ty in y0..y1) {
                val o = androidx.compose.ui.geometry.Offset((tx * cote - cx * w + size.width / 2).toFloat(), (ty * cote - cy * w + size.height / 2).toFloat())
                val img = tuiles.get(fournisseur, z, tx, ty)
                if (img != null) drawImage(img, dstOffset = androidx.compose.ui.unit.IntOffset(o.x.toInt(), o.y.toInt()),
                    dstSize = androidx.compose.ui.unit.IntSize(cote.toInt() + 1, cote.toInt() + 1))
            }
            // What happened, drawn as in the video.
            onVue(JournalPassage.VueCarte(cx, cy, zoom), size.width)
            drawIntoCanvas { c ->
                satEcranX = JournalRendu.carte(c.nativeCanvas, size.width, size.height, e, sol, marques, instant, qth,
                    JournalPassage.VueCarte(cx, cy, zoom), dp, etiquetteQth)
            }
        }
        Text(fournisseur.attribution, color = androidx.compose.ui.graphics.Color(0xFF333333), fontSize = 9.sp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = bande).background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.7f)).padding(horizontal = 4.dp))
        arrivee?.let { FlashImage(it, if (satEcranX < 0.5f) Alignment.TopEnd else Alignment.TopStart, tailleFlash) }
        if (encarts.isNotEmpty()) EncartsStations(encarts, locator, if (satEcranX < 0.5f) Alignment.BottomEnd else Alignment.BottomStart, bas = bande)
        BandeSignal(rx, smetre, crete)
    }
}

/** Height of the signal band at the bottom of the view. */
private val HAUT_BANDE = 38.dp

/**
 * At the bottom of the sky or the map, during the replay: the RX frequency
 * (where the rig was in the passband, at rest) and the S-meter as the IC-9700
 * shows it — what was asked shown, when known.
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.BandeSignal(rx: Long?, s: Int?, crete: Int?) {
    if (rx == null && s == null) return
    val dpx = androidx.compose.ui.platform.LocalDensity.current.density
    Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(HAUT_BANDE)
        .background(androidx.compose.ui.graphics.Color(0xD00A0F18)).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        rx?.let { Text("RX %.4f".format(it / 1e6), color = Cyan, fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
        if (s != null) androidx.compose.foundation.Canvas(Modifier.weight(1f).fillMaxHeight()) {
            drawIntoCanvas { c -> JournalRendu.dessineSMetre(c.nativeCanvas, 0f, 0f, size.width, size.height, s, crete, dpx) }
        }
    }
}

/** The cards of the stations just worked and heard (one for the log, one for APRS), stacked. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.EncartsStations(
    l: List<Pair<JournalPassage.Marque, JournalPassage.Fiche?>>, monLocator: String, coin: Alignment,
    /** Room left above (the "Whole sky" button) and below (the signal band). */
    haut: androidx.compose.ui.unit.Dp = 0.dp, bas: androidx.compose.ui.unit.Dp = 0.dp
) {
    Column(Modifier.align(coin).padding(top = haut, bottom = bas).padding(8.dp).fillMaxWidth(0.58f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        l.forEach { (m, f) -> EncartStation(m, f, monLocator) }
    }
}

/** The card of a station: who, where, how far, what was said. */
@Composable
private fun EncartStation(m: JournalPassage.Marque, f: JournalPassage.Fiche?, monLocator: String) {
    val coul = androidx.compose.ui.graphics.Color(when {
        m.type == JournalPassage.TypeMarque.QSO -> JournalRendu.QSO; m.viaIss -> JournalRendu.ISS; else -> JournalRendu.APRS })
    Column(Modifier.fillMaxWidth()
        .background(androidx.compose.ui.graphics.Color(0xE10C121C), RoundedCornerShape(8.dp))
        .border(2.dp, coul, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp)) {
        Text((if (m.type == JournalPassage.TypeMarque.QSO) "QSO  " else "APRS  ") + m.texte,
            color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        JournalRendu.lignesEncart(m, f, monLocator).forEach {
            Text(it, color = androidx.compose.ui.graphics.Color(0xFFCDD7E6), fontSize = 11.sp, maxLines = 1)
        }
    }
}

/** The SSTV picture arriving, drawn line by line, in a corner away from the satellite. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.FlashImage(a: Pair<JournalPassage.Marque, Float>, coin: Alignment, taille: Float,
                                                                  bas: androidx.compose.ui.unit.Dp = 0.dp) {
    val (m, part) = a
    val b = remember(m.fichier) { m.fichier?.let { PlancheRendu.charge(java.io.File(it), 400) } } ?: return
    val dp = androidx.compose.ui.platform.LocalDensity.current.density
    androidx.compose.foundation.Canvas(Modifier.align(coin).padding(bottom = bas).padding(8.dp).fillMaxWidth(taille).aspectRatio(4f / 3f)) {
        drawIntoCanvas { c ->
            JournalRendu.dessineArrivee(c.nativeCanvas, b, android.graphics.RectF(0f, 0f, size.width, size.height), part, dp)
        }
    }
}

/**
 * Past passes found again: from the log, the SSTV pictures, the APRS frames
 * relayed by the ISS, the recordings — the sources chosen, then the passes.
 */
@Composable
private fun ImportJournal(ui: UiState, vm: MainViewModel, onFini: (Int) -> Unit) {
    val scope = rememberCoroutineScope()
    var evts by remember { mutableStateOf<Map<JournalPassage.Source, List<JournalPassage.Evenement>>?>(null) }
    var choisies by remember { mutableStateOf(JournalPassage.Source.entries.toSet()) }
    var candidats by remember { mutableStateOf<Pair<List<JournalPassage.Candidat>, Int>?>(null) }
    var gardes by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var occupe by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { evts = withContext(Dispatchers.IO) { vm.journal.evenementsImport() } }
    val jour = remember(ui.useUtc) { formatDate(ui.useUtc, "EEE dd/MM/yy HH:mm") }
    fun nomSource(s: JournalPassage.Source) = t(when (s) {
        JournalPassage.Source.CARNET -> "journal_src_carnet"; JournalPassage.Source.SSTV -> "journal_src_sstv"
        JournalPassage.Source.APRS -> "journal_src_aprs"; JournalPassage.Source.ENREGISTREMENT -> "journal_src_enreg" })
    Dialog(onDismissRequest = { onFini(0) }) {
        Surface(color = SpaceCard, shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.padding(14.dp).heightIn(max = 620.dp).verticalScroll(rememberScrollState())) {
                Text(t("journal_importer"), color = TextHi, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(t("journal_import_desc"), color = TextLo, fontSize = 11.sp, modifier = Modifier.padding(vertical = 4.dp))
                val e = evts
                if (e == null) { LinearProgressIndicator(Modifier.fillMaxWidth()); return@Column }
                val c = candidats
                if (c == null) {
                    JournalPassage.Source.entries.forEach { s ->
                        val n = e[s].orEmpty().size
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()
                            .clickable(enabled = n > 0) { choisies = if (s in choisies) choisies - s else choisies + s }) {
                            Checkbox(checked = s in choisies && n > 0, onCheckedChange = null, enabled = n > 0)
                            Text(nomSource(s) + " — " + tf("journal_src_nb", n), color = if (n > 0) TextHi else TextLo, fontSize = 13.sp)
                        }
                    }
                    Button(enabled = !occupe && choisies.any { e[it].orEmpty().isNotEmpty() }, onClick = {
                        occupe = true
                        scope.launch {
                            val r = withContext(Dispatchers.Default) { vm.journal.candidatsImport(choisies.flatMap { e[it].orEmpty() }) }
                            candidats = r; gardes = r.first.indices.toSet(); occupe = false
                        }
                    }, modifier = Modifier.padding(top = 8.dp)) { Text(t("journal_chercher")) }
                    if (occupe) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                } else {
                    val (liste, perdus) = c
                    Text(tf("journal_trouves", liste.size) + if (perdus > 0) " " + tf("journal_non_places", perdus) else "",
                        color = TextHi, fontSize = 12.sp)
                    if (liste.isEmpty()) Text(t("journal_rien"), color = TextLo, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
                    val maintenant = System.currentTimeMillis()
                    liste.forEachIndexed { i, x ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()
                            .clickable { gardes = if (i in gardes) gardes - i else gardes + i }) {
                            Checkbox(checked = i in gardes, onCheckedChange = null)
                            Column {
                                Text(x.satName + " · " + jour.format(Date(x.aosMs)) + (if (ui.useUtc) " UTC" else ""),
                                    color = TextHi, fontSize = 13.sp)
                                Text(x.sources.joinToString(" · ") { nomSource(it) } + " · " + tf("journal_src_nb", x.nb) +
                                    if (maintenant - x.aosMs > 30L * 86_400_000L) " · " + t("journal_ancien") else "",
                                    color = TextLo, fontSize = 10.sp)
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        TextButton(onClick = { candidats = null }) { Text(t("back"), color = TextLo) }
                        Button(enabled = !occupe && gardes.isNotEmpty(), onClick = {
                            occupe = true
                            scope.launch {
                                val n = withContext(Dispatchers.IO) { vm.journal.importe(gardes.sorted().map { liste[it] }) }
                                occupe = false; onFini(n)
                            }
                        }) { Text(tf("journal_importer_n", gardes.size)) }
                    }
                    if (occupe) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                }
            }
        }
    }
}
