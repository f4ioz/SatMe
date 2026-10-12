/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import fr.f4ioz.satcombo.data.LogEntry
import fr.f4ioz.satcombo.data.Observer
import fr.f4ioz.satcombo.domain.Indicatifs
import fr.f4ioz.satcombo.domain.Orthodromie
import fr.f4ioz.satcombo.domain.QrzReponse
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.i18n.tf
import fr.f4ioz.satcombo.location.Maidenhead
import fr.f4ioz.satcombo.sonde.Geo
import fr.f4ioz.satcombo.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The station typed, as the PC's log shows it — on the phone in two steps,
 * the keyboard keeping the screen: a line under the callsign (the log's
 * figures, where it is), and the whole card on a tap (its QRZ card with its
 * photo, distance and bearing, the great circle on a map, every contact with
 * it and their notes). QRZ is asked when the card opens, not at every key.
 */
object FicheStation {

    /** Every contact with this station, whatever its suffix (/P, /M…), newest first. */
    fun historique(log: List<LogEntry>, indicatif: String): List<LogEntry> {
        val b = Indicatifs.base(indicatif)
        if (b.length < 3) return emptyList()
        return log.filter { Indicatifs.base(it.callsign) == b }.sortedByDescending { it.timeMs }
    }

    /** Where it is: QRZ's coordinates, else its square's centre, else the log's most frequent square. */
    fun position(fiche: QrzReponse.Fiche?, h: List<LogEntry>): Pair<Double, Double>? {
        val la = fiche?.lat; val lo = fiche?.lon
        if (la != null && lo != null) return la to lo
        fiche?.carre?.takeIf { it.length >= 4 }?.let { Maidenhead.centre(it) }?.let { return it }
        return h.mapNotNull { it.theirLocator.takeIf { l -> l.length >= 4 }?.uppercase() }.groupingBy { it }.eachCount()
            .maxByOrNull { it.value }?.key?.let { Maidenhead.centre(it) }
    }

    private val jour = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private val jourCourt = SimpleDateFormat("dd/MM/yy", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private val heure = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    /** "936 km 218° SW" from [moi], or null. */
    fun distance(moi: Observer?, ou: Pair<Double, Double>?): String? {
        if (moi == null || ou == null) return null
        val km = Geo.distanceKm(moi.latDeg, moi.lonDeg, ou.first, ou.second)
        val az = Geo.bearingDeg(moi.latDeg, moi.lonDeg, ou.first, ou.second)
        return "%,d km %d° %s".format(Locale.FRANCE, Math.round(km), Math.round(az), Geo.compass(az))
    }

    /** The line under the callsign: how many contacts, the last one, how far — or a new one. */
    @Composable
    fun Ligne(indicatif: String, log: List<LogEntry>, moi: Observer?, fiche: QrzReponse.Fiche?, onOuvre: () -> Unit) {
        val b = Indicatifs.base(indicatif)
        if (b.length < 3 || b.none { it.isDigit() }) return
        val h = remember(b, log.size) { historique(log, b) }
        val loin = remember(b, log.size, fiche) { distance(moi, position(fiche, h)) }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(8.dp)).background(SpaceCard)
            .clickable { onOuvre() }.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            val texte = if (h.isEmpty()) t("station_nouveau")
                else tf("station_ligne", h.size, jourCourt.format(Date(h.first().timeMs)), h.first().satName)
            Text(texte, color = if (h.isEmpty()) Aurora else TextHi, fontSize = 12.sp, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            loin?.let { Text(it, color = Amber, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1) }
            Spacer(Modifier.width(8.dp))
            Text(t("station_fiche") + " ›", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }

    /** The whole card, full screen. */
    @Composable
    fun Carte(indicatif: String, log: List<LogEntry>, moi: Observer?, moiNom: String, ficheConnue: QrzReponse.Fiche?,
              chercheQrz: suspend (String) -> QrzReponse.Fiche?, onFerme: () -> Unit) {
        val b = Indicatifs.base(indicatif)
        val h = remember(b, log.size) { historique(log, b) }
        var fiche by remember(b) { mutableStateOf(ficheConnue) }
        var attente by remember(b) { mutableStateOf(ficheConnue == null) }
        var photo by remember(b) { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(b) {
            if (fiche == null) fiche = runCatching { chercheQrz(b) }.getOrNull()?.takeIf { it.erreur.isBlank() && !it.vide }
            attente = false
            val url = fiche?.image.orEmpty()
            if (url.startsWith("https://")) photo = withContext(Dispatchers.IO) {
                runCatching {
                    okhttp3.OkHttpClient().newCall(okhttp3.Request.Builder().url(url)
                        .header("User-Agent", fr.f4ioz.satcombo.data.TleRepository.USER_AGENT).build()).execute()
                        .use { r -> if (r.isSuccessful) r.body?.bytes()?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } else null }
                }.getOrNull()
            }
        }
        val f = fiche
        val ou = position(f, h)
        Dialog(onDismissRequest = onFerme, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(color = SpaceBg, modifier = Modifier.fillMaxSize()) {
                LazyColumn(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        Row(verticalAlignment = Alignment.Top) {
                            photo?.let {
                                Image(it, null, contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(110.dp, 140.dp).clip(RoundedCornerShape(8.dp)))
                                Spacer(Modifier.width(12.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(f?.indicatif?.ifBlank { null } ?: b, color = Cyan, fontSize = 28.sp,
                                    fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                                val nom = f?.nom?.ifBlank { null } ?: h.firstOrNull { it.nom.isNotBlank() }?.nom
                                nom?.let { Text(it + (f?.surnom?.takeIf { s -> s.isNotBlank() }?.let { s -> " « $s »" } ?: ""), color = TextHi, fontWeight = FontWeight.Bold) }
                                val lieu = if (f != null) listOf(f.adresse, f.qth, f.etat, f.pays).filter { it.isNotBlank() }.joinToString(" · ")
                                    else h.firstOrNull { it.qth.isNotBlank() }?.qth.orEmpty()
                                if (lieu.isNotBlank()) Text(lieu, color = TextLo, fontSize = 12.sp)
                                if (f != null) {
                                    val info = listOf(f.carre, f.zoneCq.takeIf { it.isNotBlank() }?.let { "CQ $it" }, f.zoneItu.takeIf { it.isNotBlank() }?.let { "ITU $it" },
                                        f.classe.takeIf { it.isNotBlank() }?.let { tf("station_classe", it) }, f.depuis.takeIf { it.isNotBlank() }?.let { tf("station_licence", it) })
                                        .filterNotNull().filter { it.isNotBlank() }.joinToString(" · ")
                                    if (info.isNotBlank()) Text(info, color = TextLo, fontSize = 12.sp)
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                                        Badge("LoTW", f.lotw); Badge("eQSL", f.eqsl); Badge(t("station_qsl_papier"), f.qslPapier)
                                    }
                                    if (f.qslManager.isNotBlank()) Text(f.qslManager, color = Aurora, fontSize = 11.sp)
                                }
                                if (attente) Text(t("station_qrz_attente"), color = TextLo, fontSize = 11.sp)
                                else if (f == null) Text(t("station_qrz_absent"), color = TextLo, fontSize = 11.sp)
                            }
                            TextButton(onClick = onFerme) { Text(t("close"), color = Cyan) }
                        }
                    }
                    distance(moi, ou)?.let { d -> item { Text(d, color = Amber, fontSize = 26.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace) } }
                    if (moi != null && ou != null) item {
                        val arc = remember(ou) { Orthodromie.points(moi.latDeg, moi.lonDeg, ou.first, ou.second) }
                        val densite = androidx.compose.ui.platform.LocalDensity.current.density
                        CarteTuiles(arc + listOf(moi.latDeg to moi.lonDeg), b, Modifier.fillMaxWidth().aspectRatio(1.6f), zoomMax = 10.0) { c, p ->
                            dessineLiaison(c, p, arc, moiNom, b, densite)
                        }
                    }
                    item {
                        Text(t("station_contacts"), color = TextLo, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                        if (h.isEmpty()) Text(t("station_nouveau"), color = Aurora, fontSize = 14.sp)
                        else {
                            val sats = h.map { it.satName }.filter { it.isNotBlank() }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
                                .joinToString(", ") { "${it.key} ×${it.value}" }
                            Text(tf("station_resume", h.size, jour.format(Date(h.last().timeMs)), jour.format(Date(h.first().timeMs))) +
                                (if (sats.isNotBlank()) " · $sats" else ""), color = TextLo, fontSize = 12.sp)
                        }
                    }
                    items(h.take(100)) { e ->
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(SpaceCard).padding(horizontal = 10.dp, vertical = 6.dp)) {
                            Row {
                                Text(heure.format(Date(e.timeMs)), color = TextHi, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                Spacer(Modifier.width(10.dp))
                                Text(listOf(e.satName, e.mode, "${e.rstSent}/${e.rstRcvd}", e.theirLocator).filter { it.isNotBlank() && it != "/" }.joinToString(" · ") +
                                    if (Indicatifs.base(e.callsign) == b && e.callsign != b) " · ${e.callsign}" else "", color = TextLo, fontSize = 12.sp)
                            }
                            if (e.note.isNotBlank()) Text(e.note, color = Amber, fontSize = 13.sp, fontStyle = FontStyle.Italic)
                        }
                    }
                    item { Spacer(Modifier.height(20.dp)) }
                }
            }
        }
    }

    @Composable
    private fun Badge(texte: String, oui: Boolean) {
        Text(texte, color = if (oui) Aurora else TextLo, fontSize = 11.sp,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background((if (oui) Aurora else TextLo).copy(alpha = 0.15f)).padding(horizontal = 7.dp, vertical = 1.dp),
            textDecoration = if (oui) null else androidx.compose.ui.text.style.TextDecoration.LineThrough)
    }

    /** Both stations and the great circle between them. */
    private fun dessineLiaison(c: android.graphics.Canvas, p: ProjectionCarte, arc: List<Pair<Double, Double>>, moi: String, lui: String, d: Float) {
        val rose = android.graphics.Color.rgb(232, 111, 168)
        val chemin = android.graphics.Path()
        arc.forEachIndexed { i, (la, lo) -> if (i == 0) chemin.moveTo(p.x(lo), p.y(la)) else chemin.lineTo(p.x(lo), p.y(la)) }
        c.drawPath(chemin, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = rose; style = android.graphics.Paint.Style.STROKE; strokeWidth = 2.5f * d
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(6 * d, 4 * d), 0f) })
        fun point(la: Double, lo: Double, couleur: Int, nom: String) {
            val x = p.x(lo); val y = p.y(la)
            c.drawCircle(x, y, 6 * d, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = couleur })
            val t = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 12 * d; typeface = android.graphics.Typeface.DEFAULT_BOLD }
            val l = t.measureText(nom)
            val tx = if (x + 9 * d + l > p.largeur) x - 9 * d - l else x + 9 * d
            c.drawText(nom, tx, y - 5 * d, android.graphics.Paint(t).apply { color = android.graphics.Color.WHITE; style = android.graphics.Paint.Style.STROKE; strokeWidth = 3 * d })
            c.drawText(nom, tx, y - 5 * d, android.graphics.Paint(t).apply { color = android.graphics.Color.rgb(6, 19, 26) })
        }
        point(arc.first().first, arc.first().second, android.graphics.Color.rgb(255, 180, 84), moi)
        point(arc.last().first, arc.last().second, rose, lui)
    }
}
