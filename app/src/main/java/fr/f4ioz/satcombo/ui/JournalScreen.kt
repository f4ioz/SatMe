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
                messagePaquet = messagePaquet)
            else FichePassage(ui, vm, c, liens[c.id] ?: JournalDesPassages.Liens(),
                onRetour = { choisi = null; passages = vm.journal.passages() },
                onSupprime = { vm.journal.supprime(c); passages = vm.journal.passages(); choisi = null })
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
    onOuvrePaquet: () -> Unit, messagePaquet: String
) {
    val jour = remember(ui.useUtc) { formatDate(ui.useUtc, "EEE dd/MM HH:mm") }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t("journal_titre"), color = TextHi, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, t("close"), tint = TextLo) }
        }
        Text(t("journal_desc"), color = TextLo, fontSize = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onImport, modifier = Modifier.padding(top = 6.dp)) {
                Text("⤓ " + t("journal_importer"), color = Cyan, fontSize = 12.sp)
            }
            OutlinedButton(onClick = onOuvrePaquet, modifier = Modifier.padding(top = 6.dp)) {
                Text("⤓ " + t("journal_paquet_ouvrir"), color = Cyan, fontSize = 12.sp)
            }
        }
        if (messagePaquet.isNotBlank()) Text(messagePaquet, color = Amber, fontSize = 11.sp)
        // A word when a pass is kept by itself (automatic SSTV, under CAT, its page left).
        var notif by remember { mutableStateOf(notifOn()) }
        FilterChip(selected = notif, onClick = { notif = !notif; setNotif(notif) },
            label = { Text((if (notif) "✓ " else "") + t("journal_notif") + " : " + t(if (notif) "journal_oui" else "journal_flash_non"), fontSize = 11.sp) })
        Spacer(Modifier.height(8.dp))
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
    onRetour: () -> Unit, onSupprime: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prevue = remember(e.id) { vm.journal.prevue(e) }
    val heure = remember(ui.useUtc) { formatDate(ui.useUtc, "HH:mm:ss") }
    val jour = remember(ui.useUtc) { formatDate(ui.useUtc, "EEEE dd/MM/yyyy HH:mm") }
    // The recording of the pass, if still there, and where the pass starts in it.
    // Its own recording, or one that covers the pass (a pass found again, an older one).
    val son = remember(e.id) { e.enregistrements.firstNotNullOfOrNull { vm.journal.enregistrement(it) } ?: vm.journal.sonDuPassage(e) }
    // When the file's start was (its header), and the header's length: the pass's
    // sound begins at debutSon + annonce; before that, the replay goes on silent.
    val annonce = remember(son) { son?.let { vm.journal.annonceDe(it) } ?: 0L }
    val debutSon = remember(son) { son?.let { vm.journal.origineSon(it) }?.takeIf { it > 0L } }
    // Replay: the moment shown (null = the whole pass).
    var instant by remember(e.id) { mutableStateOf<Long?>(null) }
    var joue by remember(e.id) { mutableStateOf(false) }
    var lecteur by remember { mutableStateOf<android.media.MediaPlayer?>(null) }
    DisposableEffect(e.id) { onDispose { runCatching { lecteur?.release() }; lecteur = null } }
    val vignettes = remember(e.id) { mutableStateMapOf<String, Bitmap>() }
    val enregistre = rememberEnregistrer()

    // While playing: the sound sets the time; without sound, ten times faster.
    LaunchedEffect(joue) {
        while (joue) {
            val p = lecteur
            instant = when {
                p != null && debutSon != null && runCatching { p.isPlaying }.getOrDefault(false) ->
                    debutSon + runCatching { p.currentPosition.toLong() }.getOrDefault(0L)
                // Before the recording's sound: on at real speed, silent, then the sound from its first sample.
                p != null && debutSon != null -> ((instant ?: e.debutMs) + 200L).also { t ->
                    if (t >= debutSon + annonce) runCatching { p.seekTo((t - debutSon).toInt()); p.start() }
                }
                else -> (instant ?: e.debutMs) + 2_000L
            }
            if ((instant ?: 0L) > e.finMs) { joue = false; runCatching { lecteur?.pause() } }
            delay(200)
        }
    }
    fun lance() {
        val t0 = (instant ?: e.debutMs).let { if (it >= e.finMs) e.debutMs else it }
        instant = t0
        if (son != null && debutSon != null) {
            val p = lecteur ?: runCatching {
                android.media.MediaPlayer().apply { setDataSource(son.absolutePath); prepare() }
            }.getOrNull()
            lecteur = p
            // Never the spoken header: before the sound, the loop waits for it.
            if (t0 >= debutSon + annonce) p?.let { runCatching { it.seekTo((t0 - debutSon).toInt()); it.start() } }
        }
        joue = true
    }
    fun arrete() { joue = false; runCatching { lecteur?.pause() } }

    val pt = instant?.let { JournalPassage.pointA(e, it) }
    val trace = e.points.filter { instant == null || it.tMs <= instant!! }.map { it.az to it.el }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onRetour) { Icon(Icons.AutoMirrored.Filled.ArrowBack, t("back"), tint = TextLo) }
            Column(Modifier.weight(1f)) {
                Text(e.satName, color = TextHi, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(jour.format(Date(e.debutMs)) + if (ui.useUtc) " UTC" else "", color = TextLo, fontSize = 11.sp)
            }
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
        val vitesseRejeu = if (son != null && debutSon != null) 1 else 10
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
        if (!surCarte) CielJournal(e, prevue, marques, instant, apercu ?: arrivee, tailleFlash, encarts) { cadreVu = it }
        else CarteJournal(e, sol, marques, instant, e.locator, apercu ?: arrivee, tailleFlash, encarts) { v, w -> vueVue = v to w }
        Text(t(if (surCarte) "journal_legende_sol" else "journal_legende_carte"), color = TextLo, fontSize = 10.sp)
        LegendeMarques()
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

        // Replay controls.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 6.dp)) {
            Button(onClick = { if (joue) arrete() else lance() }) {
                Text(if (joue) "⏸ " + t("journal_pause") else "▶ " + t(if (son != null) "journal_rejouer" else "journal_rejouer_x10"))
            }
            Text(instant?.let { heure.format(Date(it)) } ?: "", color = TextHi, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
        }
        Slider(value = ((instant ?: e.debutMs) - e.debutMs).toFloat() / e.dureeMs.coerceAtLeast(1),
            onValueChange = { f ->
                val t = e.debutMs + (f * e.dureeMs).toLong()
                instant = t
                val p = lecteur
                if (p != null && debutSon != null) runCatching {
                    if (t >= debutSon + annonce) { p.seekTo((t - debutSon).toInt()); if (joue && !p.isPlaying) p.start() }
                    else if (p.isPlaying) p.pause()
                }
            })
        pt?.let { p ->
            Text(buildList {
                add("Az %.0f° · El %.0f°".format(p.az, p.el))
                p.dlHz?.let { add("↓ %.4f MHz".format(it / 1e6)) }
                p.ulHz?.let { add("↑ %.4f MHz".format(it / 1e6)) }
                if (p.rotorAz != null && p.rotorEl != null) add(tf("journal_mat_a", p.rotorAz.toInt(), p.rotorEl.toInt()))
            }.joinToString(" · "), color = TextHi, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        if (son == null) Text(t(if (e.enregistrements.isEmpty()) "journal_sans_son" else "journal_son_absent"),
            color = TextLo, fontSize = 10.sp)

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

        if (l.qsos.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(tf("journal_qsos", l.qsos.size), color = TextHi, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            l.qsos.forEach { q ->
                val vu = instant == null || q.timeMs <= instant!!
                Text(heure.format(Date(q.timeMs)) + "  " + q.callsign + (if (q.theirLocator.isNotBlank()) " · " + q.theirLocator else ""),
                    color = TextHi, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.alpha(if (vu) 1f else 0.3f))
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
                    Column(Modifier.width(120.dp).alpha(if (!garde) 0.3f else if (vu) 1f else 0.15f).clickable {
                        masquees = if (garde) masquees + f.name else masquees - f.name
                        vm.journal.maj(e.copy(masquees = masquees))
                    }) {
                        Box {
                            if (b != null) Image(b.asImageBitmap(), null, contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(6.dp)))
                            Text(if (garde) "✓" else "✕", color = androidx.compose.ui.graphics.Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.align(Alignment.TopEnd).padding(3.dp)
                                    .background(if (garde) Cyan.copy(alpha = 0.85f) else Magenta.copy(alpha = 0.85f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 4.dp))
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
                Text(heure.format(Date(p.quand)) + "  " + p.source + if (p.viaIss) "  (ISS)" else "",
                    color = TextHi, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.alpha(if (vu) 1f else 0.3f))
            }
        }

        // Export: the view shown (sky or map, as framed), as a picture, a video or a GIF; then shared or saved.
        Spacer(Modifier.height(12.dp))
        Text(t("journal_exporter"), color = TextHi, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        var format by remember { mutableStateOf("video") }
        var avecSon by remember { mutableStateOf(true) }
        var fabrication by remember { mutableStateOf<Float?>(null) }
        var pret by remember { mutableStateOf<java.io.File?>(null) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("image" to "journal_fmt_image", "video" to "journal_video", "gif" to "sstv_gif").forEach { (k, cle) ->
                FilterChip(selected = format == k, onClick = { format = k; pret = null }, label = { Text(t(cle), fontSize = 12.sp) })
            }
        }
        if (format == "video" && son != null) Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { avecSon = !avecSon; pret = null }) {
            Checkbox(checked = avecSon, onCheckedChange = null)
            Text(t("journal_avec_son"), color = TextHi, fontSize = 12.sp)
        }
        var resolution by remember { mutableStateOf(vm.journal.videoRes()) }
        if (format == "video") {
            Text(t("journal_resolution"), color = TextLo, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("XS" to "journal_res_tres_legere", "M" to "journal_res_legere", "HD" to "journal_res_hd").forEach { (k, cle) ->
                    FilterChip(selected = resolution == k, onClick = { resolution = k; vm.journal.setVideoRes(k); pret = null },
                        label = { Text(t(cle), fontSize = 11.sp) })
                }
            }
        }
        var ouverture by remember { mutableStateOf(vm.journal.ouverture()) }
        if (format != "image") Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { ouverture = !ouverture; vm.journal.setOuverture(ouverture); pret = null }) {
            Checkbox(checked = ouverture, onCheckedChange = null)
            Column {
                Text(t("journal_ouverture"), color = TextHi, fontSize = 12.sp)
                Text(t("journal_ouverture_desc"), color = TextLo, fontSize = 10.sp)
            }
        }
        var recap by remember { mutableStateOf(vm.journal.recap()) }
        if (format != "image") Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { recap = !recap; vm.journal.setRecap(recap); pret = null }) {
            Checkbox(checked = recap, onCheckedChange = null)
            Column {
                Text(t("journal_recap"), color = TextHi, fontSize = 12.sp)
                Text(t("journal_recap_desc"), color = TextLo, fontSize = 10.sp)
            }
        }
        Text(t(when {
            format == "image" -> "journal_fmt_image_desc"
            format == "gif" -> "journal_fmt_gif_desc"
            son != null && avecSon -> "journal_fmt_video_son"
            else -> "journal_fmt_video_x10" }) + " " + t(if (surCarte) "journal_export_carte" else "journal_export_ciel"),
            color = TextLo, fontSize = 10.sp)
        Button(enabled = fabrication == null, modifier = Modifier.padding(top = 4.dp), onClick = {
            fabrication = 0f; pret = null
            scope.launch {
                val f = withContext(Dispatchers.IO) {
                    val qth = e.locator.takeIf { it.length >= 4 }?.let { fr.f4ioz.satcombo.location.Maidenhead.toLatLon(it) }
                    val sc = JournalRendu.Scene(e, prevue, marques, sol, qth, surCarte, ui.callsign, ui.useUtc, flashS, tailleFlash,
                        affSstv = affSstv, affFiches = affFiches,
                        cadreVu = if (surCarte) null else cadreVu, vueVue = if (surCarte) vueVue else null,
                        fiches = fiches, recap = recap && format != "image", ouverture = ouverture && format != "image", resolution = resolution)
                    when (format) {
                        "image" -> JournalRendu.png(ctx, sc, instant)
                        "gif" -> JournalRendu.gif(ctx, sc) { fabrication = it }
                        else -> JournalRendu.video(ctx, sc, if (avecSon) son else null, debutSon, annonce) { fabrication = it }
                    }
                }
                fabrication = null; pret = f
            }
        }) { Text(t("journal_creer")) }
        fabrication?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) }
        pret?.let { f ->
            val type = when { f.name.endsWith(".gif") -> "image/gif"; f.name.endsWith(".png") -> "image/png"; else -> "video/mp4" }
            Text(tf("sstv_video_taille", "%.1f".format(f.length() / 1_048_576.0)), color = TextHi, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(modifier = Modifier.weight(1f), onClick = { runCatching {
                    val u = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
                    ctx.startActivity(android.content.Intent.createChooser(
                        android.content.Intent(android.content.Intent.ACTION_SEND).setType(type)
                            .putExtra(android.content.Intent.EXTRA_STREAM, u)
                            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION), f.name))
                } }) { Text(t("rec_share"), fontSize = 12.sp) }
                OutlinedButton(modifier = Modifier.weight(1f), onClick = { enregistre(f.name, type, depuisFichier(f)) }) {
                    Text(t("export_save"), color = Cyan, fontSize = 12.sp)
                }
            }
        }
        // The pass in one file: kept elsewhere, or given to another station.
        Spacer(Modifier.height(12.dp))
        Text(t("journal_paquet"), color = TextHi, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(t("journal_paquet_desc"), color = TextLo, fontSize = 11.sp)
        var paquet by remember(e.id) { mutableStateOf<java.io.File?>(null) }
        var emballe by remember(e.id) { mutableStateOf(false) }
        if (paquet == null) OutlinedButton(enabled = !emballe, onClick = {
            emballe = true
            scope.launch {
                paquet = withContext(Dispatchers.IO) { runCatching { vm.journal.paquet(e) }.getOrNull() }
                emballe = false
            }
        }) { Text(if (emballe) t("journal_paquet_en_cours") else "⤒ " + t("journal_paquet_creer"), color = Cyan, fontSize = 12.sp) }
        paquet?.let { f ->
            Text(tf("sstv_video_taille", "%.1f".format(f.length() / 1_048_576.0)), color = TextHi, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(modifier = Modifier.weight(1f), onClick = { runCatching {
                    val u = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
                    ctx.startActivity(android.content.Intent.createChooser(
                        android.content.Intent(android.content.Intent.ACTION_SEND).setType(fr.f4ioz.satcombo.domain.JournalPaquet.TYPE)
                            .putExtra(android.content.Intent.EXTRA_STREAM, u)
                            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION), f.name))
                } }) { Text(t("rec_share"), fontSize = 12.sp) }
                OutlinedButton(modifier = Modifier.weight(1f), onClick = { enregistre(f.name, fr.f4ioz.satcombo.domain.JournalPaquet.TYPE, depuisFichier(f)) }) {
                    Text(t("export_save"), color = Cyan, fontSize = 12.sp)
                }
            }
        }
        TextButton(onClick = onSupprime) { Text(t("journal_supprimer"), color = Magenta, fontSize = 12.sp) }
        Spacer(Modifier.height(24.dp))
    }
}

/** The colours of the marks. */
@Composable
private fun LegendeMarques() {
    @Composable
    fun Puce(c: Int, texte: String) = Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(androidx.compose.foundation.shape.CircleShape).background(androidx.compose.ui.graphics.Color(c)))
        Spacer(Modifier.width(4.dp)); Text(texte, color = TextLo, fontSize = 10.sp)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 2.dp)) {
        Puce(JournalRendu.QSO, "QSO"); Puce(JournalRendu.APRS, "APRS"); Puce(JournalRendu.ISS, t("journal_via_iss"))
        Puce(JournalRendu.SSTV[0], t("journal_l_sstv"))
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
    encarts: List<Pair<JournalPassage.Marque, JournalPassage.Fiche?>>, onCadre: (JournalPassage.Cadre) -> Unit
) {
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
            FlashImage(a, if (adroite) Alignment.BottomStart else Alignment.BottomEnd, tailleFlash)
        }
        if (encarts.isNotEmpty()) {
            // Same side as the picture (away from the satellite), at the other end: never under it.
            val p = instant?.let { JournalPassage.pointA(e, it) }
            val adroite = p != null && JournalPassage.ciel(p.az, p.el).first >= cadre.cx
            EncartsStations(encarts, e.locator, if (adroite) Alignment.TopStart else Alignment.TopEnd, haut = 40.dp)
        }
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
    encarts: List<Pair<JournalPassage.Marque, JournalPassage.Fiche?>>, onVue: (JournalPassage.VueCarte, Float) -> Unit
) {
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
                    JournalPassage.VueCarte(cx, cy, zoom), dp)
            }
        }
        Text(fournisseur.attribution, color = androidx.compose.ui.graphics.Color(0xFF333333), fontSize = 9.sp,
            modifier = Modifier.align(Alignment.BottomEnd).background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.7f)).padding(horizontal = 4.dp))
        arrivee?.let { FlashImage(it, if (satEcranX < 0.5f) Alignment.TopEnd else Alignment.TopStart, tailleFlash) }
        if (encarts.isNotEmpty()) EncartsStations(encarts, locator, if (satEcranX < 0.5f) Alignment.BottomEnd else Alignment.BottomStart)
    }
}

/** The cards of the stations just worked and heard (one for the log, one for APRS), stacked. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.EncartsStations(
    l: List<Pair<JournalPassage.Marque, JournalPassage.Fiche?>>, monLocator: String, coin: Alignment,
    /** Room left above (the "Whole sky" button). */
    haut: androidx.compose.ui.unit.Dp = 0.dp
) {
    Column(Modifier.align(coin).padding(top = haut).padding(8.dp).fillMaxWidth(0.58f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
private fun androidx.compose.foundation.layout.BoxScope.FlashImage(a: Pair<JournalPassage.Marque, Float>, coin: Alignment, taille: Float) {
    val (m, part) = a
    val b = remember(m.fichier) { m.fichier?.let { PlancheRendu.charge(java.io.File(it), 400) } } ?: return
    val dp = androidx.compose.ui.platform.LocalDensity.current.density
    androidx.compose.foundation.Canvas(Modifier.align(coin).padding(8.dp).fillMaxWidth(taille).aspectRatio(4f / 3f)) {
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
