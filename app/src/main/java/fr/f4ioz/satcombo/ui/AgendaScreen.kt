/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.f4ioz.satcombo.MainViewModel
import fr.f4ioz.satcombo.UiState
import fr.f4ioz.satcombo.data.AgendaStore
import fr.f4ioz.satcombo.data.AgendaStore.AgendaEvent
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.i18n.tf
import fr.f4ioz.satcombo.notify.AgendaAlertWorker
import fr.f4ioz.satcombo.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Agenda: appointments the orbit doesn't know about.
 *
 * Pass predictions don't tell you a station waits on the 19:42 pass, or that
 * SSTV was announced for 1 August. Such news arrives days ahead via forums and
 * gets lost in between.
 *
 * An entry is when (instant or start–end window), what, satellite, frequency.
 * The reminder uses the same notification channel as pass alerts, so no
 * second permission. A window links the agenda to the rest of the app: every
 * pass of that satellite inside it is flagged in the list, the satellite page
 * and the date filter.
 */
@Composable
fun AgendaScreen(ui: UiState, vm: MainViewModel) {
    val ctx = LocalContext.current
    var events by remember { mutableStateOf(AgendaStore.purge(ctx)) }
    var editing by remember { mutableStateOf<AgendaEvent?>(null) }
    var creating by remember { mutableStateOf(false) }

    fun refresh() {
        events = AgendaStore.load(ctx)
        AgendaAlertWorker.reschedule(ctx)
        // Otherwise the pass list's agenda badges only update on next app start.
        vm.refreshAgenda()
    }

    val now = System.currentTimeMillis()
    // A window in progress stays "upcoming" until it ends.
    val upcoming = events.filter { it.endOrStartMs >= now }
    val past = events.filter { it.endOrStartMs < now }.sortedByDescending { it.timeMs }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { Spacer(Modifier.height(4.dp)) }

        item {
            Surface(color = SpaceCard, shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(t("agenda_intro"), color = TextLo, fontSize = 12.sp)
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { creating = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Cyan)
                    ) {
                        Icon(Icons.Default.Add, null, Modifier.size(18.dp),
                            tint = if (isDarkTheme()) SpaceBg else androidx.compose.ui.graphics.Color.White)
                        Spacer(Modifier.width(6.dp))
                        Text(t("agenda_add"),
                            color = if (isDarkTheme()) SpaceBg else androidx.compose.ui.graphics.Color.White)
                    }
                }
            }
        }

        if (upcoming.isEmpty() && past.isEmpty()) {
            item {
                Text(t("agenda_empty"), color = TextLo, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 12.dp))
            }
        }

        if (upcoming.isNotEmpty()) {
            item { AgendaHeader(t("agenda_upcoming")) }
            items(upcoming, key = { it.id }) { e ->
                AgendaRow(e, ui.useUtc, now,
                    onEdit = { editing = e },
                    onToggle = { AgendaStore.put(ctx, e.copy(enabled = !e.enabled)); refresh() },
                    onDelete = { AgendaStore.remove(ctx, e.id); refresh() })
            }
        }
        if (past.isNotEmpty()) {
            item { AgendaHeader(t("agenda_past")) }
            items(past, key = { it.id }) { e ->
                AgendaRow(e, ui.useUtc, now,
                    onEdit = { editing = e },
                    onToggle = { AgendaStore.put(ctx, e.copy(enabled = !e.enabled)); refresh() },
                    onDelete = { AgendaStore.remove(ctx, e.id); refresh() })
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }

    if (creating || editing != null) {
        AgendaEditDialog(
            initial = editing,
            useUtc = ui.useUtc,
            // Favourites first, then the whole loaded catalogue. Using only
            // computed passes emptied the list for a satellite with no pass in
            // the current window — exactly the case of SSTV announced three
            // weeks ahead.
            satNames = remember(ui.satellites, ui.favorites, ui.passes) {
                val favs = ui.satellites.filter { it.catalogNumber in ui.favorites }
                    .map { it.name }.sorted()
                val others = ui.satellites.map { it.name }.sorted()
                val fromPasses = ui.passes.map { it.satName }
                (favs + fromPasses + others).map { it.trim() }
                    .filter { it.isNotBlank() }.distinct()
            },
            onSave = { e -> AgendaStore.put(ctx, e); creating = false; editing = null; refresh() },
            onDismiss = { creating = false; editing = null })
    }
}

@Composable
private fun AgendaHeader(text: String) {
    Text(text, color = TextLo, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        letterSpacing = 1.2.sp, modifier = Modifier.padding(top = 10.dp, start = 4.dp))
}

/** Frequency as written on air: "436.950 MHz". */
internal fun agendaFreqLabel(hz: Long): String =
    "%.3f MHz".format(Locale.US, hz / 1e6)

/**
 * An entry's time on one line. A same-day window doesn't repeat the date
 * ("Mon 03 Aug 06:30 → 19:30"); a multi-day window shows both dates.
 */
internal fun agendaWhen(e: AgendaEvent, useUtc: Boolean): String {
    val zone = if (useUtc) TimeZone.getTimeZone("UTC") else TimeZone.getDefault()
    val dt = SimpleDateFormat("EEE dd MMM  HH:mm", Locale.getDefault()).apply { timeZone = zone }
    val hm = SimpleDateFormat("HH:mm", Locale.getDefault()).apply { timeZone = zone }
    val tag = if (useUtc) "UTC" else "LOC"
    val head = dt.format(Date(e.timeMs)).replaceFirstChar { it.uppercase() }
    if (!e.isWindow) return "$head  $tag"
    val sameDay = SimpleDateFormat("yyyyDDD", Locale.US).apply { timeZone = zone }
        .let { it.format(Date(e.timeMs)) == it.format(Date(e.endMs)) }
    val tail = if (sameDay) hm.format(Date(e.endMs))
    else dt.format(Date(e.endMs)).replaceFirstChar { it.uppercase() }
    return "$head → $tail  $tag"
}

@Composable
private fun AgendaRow(
    e: AgendaEvent, useUtc: Boolean, now: Long,
    onEdit: () -> Unit, onToggle: () -> Unit, onDelete: () -> Unit
) {
    val over = e.endOrStartMs < now
    val live = e.activeAt(now, 0L)
    Surface(color = SpaceCard, shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Event, null,
                tint = if (over) TextLo else if (live) Aurora else Amber,
                modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(e.title, color = if (over) TextLo else TextHi,
                        fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    if (e.kind.isNotBlank()) {
                        Spacer(Modifier.width(6.dp))
                        Surface(color = Cyan.copy(alpha = 0.16f), shape = RoundedCornerShape(5.dp)) {
                            Text(e.kind, color = Cyan, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
                        }
                    }
                    // Flag a window in progress: time to get the antenna out now.
                    if (live) {
                        Spacer(Modifier.width(6.dp))
                        Surface(color = Aurora.copy(alpha = 0.18f), shape = RoundedCornerShape(5.dp)) {
                            Text(t("agenda_live"), color = Aurora, fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
                        }
                    }
                }
                Text(
                    agendaWhen(e, useUtc) +
                        (if (e.satName.isNotBlank()) "  ·  " + e.satName else ""),
                    color = if (over) TextLo.copy(alpha = 0.7f) else Cyan,
                    fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                if (e.freqHz > 0L)
                    Text("↓ " + agendaFreqLabel(e.freqHz),
                        color = if (over) TextLo else Amber, fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                if (e.note.isNotBlank())
                    Text(e.note, color = TextLo, fontSize = 11.sp)
                if (!over)
                    Text(tf("agenda_lead_at", leadLabel(e.leadMin)),
                        color = if (e.enabled) Aurora else TextLo, fontSize = 10.sp)
            }
            IconButton(onClick = onToggle, modifier = Modifier.size(34.dp)) {
                Icon(
                    if (e.enabled) Icons.Default.NotificationsActive
                    else Icons.Default.NotificationsOff,
                    contentDescription = t("agenda_toggle"),
                    tint = if (e.enabled) Aurora else TextLo,
                    modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Default.Edit, t("edit"), tint = Cyan,
                    modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Default.Delete, t("delete"), tint = Magenta,
                    modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** Reminder lead times offered, from none to a week (minutes). */
private val LEADS = listOf(0, 5, 15, 30, 60, 120, 360, 720, 1440, 2880, 10080)

private fun leadLabel(min: Int): String = when {
    min <= 0 -> t("agenda_lead_now")
    min < 60 -> tf("agenda_lead_min", min)
    min < 1440 -> tf("agenda_lead_hour", min / 60)
    else -> tf("agenda_lead_day", min / 1440)
}

/** Broken-down date-time, as the pickers handle it. */
private data class Stamp(
    var year: Int, var month: Int, var day: Int, var hour: Int, var minute: Int)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgendaEditDialog(
    initial: AgendaEvent?,
    useUtc: Boolean,
    satNames: List<String>,
    onSave: (AgendaEvent) -> Unit,
    onDismiss: () -> Unit
) {
    val zone = remember(useUtc) {
        if (useUtc) TimeZone.getTimeZone("UTC") else TimeZone.getDefault()
    }
    // Default: today, next round hour. Defaulting to tomorrow looked like a
    // wrong date (open on the 30th, get the 31st).
    val startMs = remember {
        initial?.timeMs ?: (System.currentTimeMillis() + 3_600_000L)
    }
    fun stampOf(ms: Long, round: Boolean) = Calendar.getInstance(zone).apply {
        timeInMillis = ms
        set(Calendar.SECOND, 0)
        // New entries start on the hour, not at 14:37.
        if (round) set(Calendar.MINUTE, 0)
    }.let {
        Stamp(it.get(Calendar.YEAR), it.get(Calendar.MONTH), it.get(Calendar.DAY_OF_MONTH),
            it.get(Calendar.HOUR_OF_DAY), it.get(Calendar.MINUTE))
    }

    var title by remember { mutableStateOf(initial?.title ?: "") }
    var sat by remember { mutableStateOf(initial?.satName ?: "") }
    var note by remember { mutableStateOf(initial?.note ?: "") }
    var lead by remember { mutableStateOf(initial?.leadMin ?: 60) }
    var kind by remember { mutableStateOf(initial?.kind ?: "") }
    // Frequency typed in MHz, as announced: "436.950".
    var freq by remember {
        mutableStateOf(initial?.freqHz?.takeIf { it > 0L }
            ?.let { "%.3f".format(Locale.US, it / 1e6) } ?: "")
    }
    // Events last: SSTV is announced Saturday to Sunday, not 19:00 sharp. So the
    // window is on by default, ending one hour later; switch it off for an instant.
    var window by remember { mutableStateOf(initial?.isWindow ?: true) }

    var s0 by remember { mutableStateOf(stampOf(startMs, initial == null)) }
    var s1 by remember {
        mutableStateOf(stampOf(
            initial?.endMs?.takeIf { it > 0L } ?: (startMs + 3_600_000L), initial == null))
    }

    // 0 = closed, 1 = start, 2 = end.
    var pickDate by remember { mutableStateOf(0) }
    var pickTime by remember { mutableStateOf(0) }
    var leadOpen by remember { mutableStateOf(false) }
    var satOpen by remember { mutableStateOf(false) }
    var kindOpen by remember { mutableStateOf(false) }

    fun epoch(s: Stamp): Long = Calendar.getInstance(zone).apply {
        clear(); set(s.year, s.month, s.day, s.hour, s.minute, 0)
    }.timeInMillis

    val dateFmt = remember(useUtc) {
        SimpleDateFormat("EEE dd MMM yyyy", Locale.getDefault())
            .apply { timeZone = zone }
    }
    val badWindow = window && epoch(s1) <= epoch(s0)

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = !badWindow,
                onClick = {
                    // A half-typed frequency must not block saving; it's ignored.
                    val hz = freq.trim().replace(',', '.').toDoubleOrNull()
                        ?.takeIf { it > 0.0 }?.let { (it * 1e6).toLong() } ?: 0L
                    onSave(AgendaEvent(
                        id = initial?.id ?: System.currentTimeMillis(),
                        title = title.ifBlank { t("agenda_untitled") },
                        timeMs = epoch(s0), satName = sat, leadMin = lead, note = note,
                        enabled = initial?.enabled ?: true,
                        endMs = if (window) epoch(s1) else 0L,
                        kind = kind, freqHz = hz))
                }
            ) { Text(t("save"), color = if (badWindow) TextLo else Cyan) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("cancel"), color = TextLo) } },
        title = { Text(if (initial == null) t("agenda_add") else t("agenda_edit"),
            color = TextHi) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = title, onValueChange = { title = it },
                    label = { Text(t("agenda_what")) },
                    placeholder = { Text(t("agenda_what_hint")) },
                    singleLine = true, modifier = Modifier.fillMaxWidth())

                // Satellite: suggested from a list but free text — the entry may
                // target a satellite SatMe doesn't track yet.
                Box {
                    OutlinedTextField(
                        value = sat, onValueChange = { sat = it },
                        label = { Text(t("agenda_sat")) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            IconButton(onClick = { satOpen = true }) {
                                Icon(Icons.Default.ArrowDropDown, null, tint = TextLo)
                            }
                        })
                    DropdownMenu(expanded = satOpen, onDismissRequest = { satOpen = false }) {
                        satNames.take(60).forEach { n ->
                            DropdownMenuItem(text = { Text(n) },
                                onClick = { sat = n; satOpen = false })
                        }
                    }
                }

                // Kind and frequency on one line, like an announcement:
                // "SSTV on 436.950".
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) {
                        OutlinedButton(onClick = { kindOpen = true },
                            modifier = Modifier.fillMaxWidth()) {
                            Text(if (kind.isBlank()) t("agenda_kind") else kind,
                                fontSize = 12.sp, color = TextHi)
                            Icon(Icons.Default.ArrowDropDown, null, tint = TextLo)
                        }
                        DropdownMenu(expanded = kindOpen, onDismissRequest = { kindOpen = false }) {
                            AgendaStore.KINDS.forEach { k ->
                                DropdownMenuItem(
                                    text = { Text(if (k.isBlank()) t("agenda_kind_none") else k) },
                                    onClick = { kind = k; kindOpen = false })
                            }
                        }
                    }
                    OutlinedTextField(
                        value = freq, onValueChange = { freq = it },
                        label = { Text(t("agenda_freq")) },
                        placeholder = { Text("436.950") },
                        singleLine = true, modifier = Modifier.weight(1.2f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }

                Text(t("agenda_start"), color = TextLo, fontSize = 11.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickDate = 1 }, modifier = Modifier.weight(1f)) {
                        Text(dateFmt.format(Date(epoch(s0))), fontSize = 12.sp, color = TextHi)
                    }
                    OutlinedButton(onClick = { pickTime = 1 }) {
                        Text("%02d:%02d %s".format(s0.hour, s0.minute, if (useUtc) "UTC" else "LOC"),
                            fontSize = 12.sp, color = Amber, fontWeight = FontWeight.Bold)
                    }
                }

                // Window optional: a sked is an instant, a two-day SSTV event
                // needs a start and an end.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t("agenda_window"), color = TextHi, fontSize = 13.sp,
                        modifier = Modifier.weight(1f))
                    Switch(checked = window, onCheckedChange = { window = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Cyan,
                            checkedTrackColor = Cyan.copy(alpha = 0.4f)))
                }

                if (window) {
                    Text(t("agenda_end"), color = TextLo, fontSize = 11.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { pickDate = 2 }, modifier = Modifier.weight(1f)) {
                            Text(dateFmt.format(Date(epoch(s1))), fontSize = 12.sp, color = TextHi)
                        }
                        OutlinedButton(onClick = { pickTime = 2 }) {
                            Text("%02d:%02d %s".format(s1.hour, s1.minute, if (useUtc) "UTC" else "LOC"),
                                fontSize = 12.sp, color = Amber, fontWeight = FontWeight.Bold)
                        }
                    }
                    if (badWindow)
                        Text(t("agenda_window_bad"), color = Magenta, fontSize = 11.sp)
                }

                Box {
                    OutlinedButton(onClick = { leadOpen = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(t("agenda_lead") + " : " + leadLabel(lead),
                            fontSize = 12.sp, color = TextHi)
                        Icon(Icons.Default.ArrowDropDown, null, tint = TextLo)
                    }
                    DropdownMenu(expanded = leadOpen, onDismissRequest = { leadOpen = false }) {
                        LEADS.forEach { m ->
                            DropdownMenuItem(text = { Text(leadLabel(m)) },
                                onClick = { lead = m; leadOpen = false })
                        }
                    }
                }

                OutlinedTextField(
                    value = note, onValueChange = { note = it },
                    label = { Text(t("agenda_note")) },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        })

    if (pickDate != 0) {
        val which = pickDate
        val cur = if (which == 1) s0 else s1
        // DatePicker works in UTC midnight: pass UTC midnight of the shown day,
        // not the local instant, or a 01:00 entry opens on the previous day.
        val utcMidnight = remember(which, cur.year, cur.month, cur.day) {
            Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                clear(); set(cur.year, cur.month, cur.day, 0, 0, 0)
            }.timeInMillis
        }
        val dpState = rememberDatePickerState(initialSelectedDateMillis = utcMidnight)
        DatePickerDialog(
            onDismissRequest = { pickDate = 0 },
            confirmButton = {
                TextButton(onClick = {
                    dpState.selectedDateMillis?.let { ms ->
                        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                        c.timeInMillis = ms
                        val n = cur.copy(
                            year = c.get(Calendar.YEAR),
                            month = c.get(Calendar.MONTH),
                            day = c.get(Calendar.DAY_OF_MONTH))
                        if (which == 1) s0 = n else s1 = n
                    }
                    pickDate = 0
                }) { Text(t("ok"), color = Cyan) }
            },
            dismissButton = {
                TextButton(onClick = { pickDate = 0 }) { Text(t("cancel"), color = TextLo) }
            }
        ) { DatePicker(state = dpState) }
    }

    if (pickTime != 0) {
        val which = pickTime
        val cur = if (which == 1) s0 else s1
        val tpState = rememberTimePickerState(cur.hour, cur.minute, true)
        AlertDialog(
            onDismissRequest = { pickTime = 0 },
            confirmButton = {
                TextButton(onClick = {
                    val n = cur.copy(hour = tpState.hour, minute = tpState.minute)
                    if (which == 1) s0 = n else s1 = n
                    pickTime = 0
                }) { Text(t("ok"), color = Cyan) }
            },
            dismissButton = {
                TextButton(onClick = { pickTime = 0 }) { Text(t("cancel"), color = TextLo) }
            },
            title = { Text(
                (if (which == 1) t("agenda_start") else t("agenda_end")) + " · " +
                    (if (useUtc) "UTC" else "LOC"),
                color = TextHi) },
            text = { TimePicker(state = tpState) })
    }
}
