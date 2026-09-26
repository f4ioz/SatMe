/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.f4ioz.satcombo.MainViewModel
import fr.f4ioz.satcombo.UiState
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.ui.theme.*
import kotlin.math.*

/**
 * Le globe : où sont les satellites, vus de l'extérieur.
 *
 * Une carte plate ment sur les hautes latitudes et coupe les orbites en deux
 * au bord de l'image ; un satellite qui « sort à droite pour rentrer à
 * gauche » n'apprend rien à personne. Sur une sphère, une orbite est une
 * orbite.
 *
 * Projection orthographique : ce que verrait un œil très loin. Le calcul tient
 * en trois lignes et rien n'est déformé au centre du disque, là où l'on
 * regarde.
 *
 * Les données viennent d'où elles existaient déjà : les 196 pays embarqués
 * pour la carte QRV, et les traces au sol de `PassPredictor.groundTrack`.
 * Aucune donnée nouvelle, aucune dépendance nouvelle.
 */
@Composable
fun GlobeScreen(ui: UiState, vm: MainViewModel) {

    // La rotation, en degrés. La longitude tourne le globe, la latitude
    // l'incline — bornée pour ne jamais passer par-dessus le pôle, geste dont
    // personne ne sait revenir.
    var lonVue by remember { mutableStateOf(-(ui.observer?.lonDeg ?: 0.0).toFloat()) }
    var latVue by remember { mutableStateOf((ui.observer?.latDeg ?: 30.0).toFloat()) }
    var zoom by remember { mutableStateOf(1f) }

    val ctx = androidx.compose.ui.platform.LocalContext.current
    val pays = remember { fr.f4ioz.satcombo.data.PaysStore.tous(ctx) }

    // Les carrés n'étaient chargés que par la page Locator : arrivé
    // directement ici, le globe n'avait rien à peindre.
    LaunchedEffect(Unit) { vm.chargeLotwLocal() }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text(t("globe_hint"), color = TextLo, fontSize = 11.sp,
            modifier = Modifier.padding(bottom = 6.dp))

        Box(Modifier.fillMaxWidth().weight(1f), Alignment.Center) {
            Canvas(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    detectTransformGestures { _, pan, z, _ ->
                        // Le déplacement se cumule localement : lire l'état
                        // dans le bloc de geste le rendrait périmé dès le
                        // premier mouvement.
                        // Le doigt entraîne la surface : glisser vers la
                        // droite fait venir vers soi ce qui était à gauche.
                        // C'était inversé — on tournait le point de vue au
                        // lieu du globe, et le globe partait à l'envers.
                        lonVue += pan.x * 0.35f
                        latVue = (latVue - pan.y * 0.35f).coerceIn(-85f, 85f)
                        zoom = (zoom * z).coerceIn(0.8f, 12f)
                    }
                }
            ) {
                val r = min(size.width, size.height) / 2f * 0.92f * zoom
                val c = Offset(size.width / 2f, size.height / 2f)

                // L'océan, puis les terres.
                drawCircle(Color(0xFF0E2036), r, c)
                drawCircle(Color(0xFF1E4A6E), r, c, style = Stroke(width = 1.5f))

                paralleles(this, c, r, latVue, lonVue)

                // Les carrés, peints avant les côtes pour rester sous le
                // trait : vert pour ceux d'où j'ai émis, cyan pour ceux que
                // j'ai contactés, les deux couleurs superposées quand c'est
                // les deux. Un carré fait 2° de latitude sur 1° de longitude.
                if (ui.carnet.peindre) {
                    fun peins(carres: Set<String>, col: Color) {
                        for (k in carres) {
                            // `bounds` rend lat, lon, hauteur, largeur : le
                            // coin sud-ouest et les côtés.
                            val b = fr.f4ioz.satcombo.location.Maidenhead
                                .bounds(k) ?: continue
                            val la0 = b[0]; val lo0 = b[1]
                            val la1 = b[0] + b[2]; val lo1 = b[1] + b[3]
                            val chemin = Path()
                            var ouvert = false
                            val pts = listOf(
                                la0 to lo0, la0 to lo1, la1 to lo1, la1 to lo0, la0 to lo0)
                            for ((la, lo) in pts) {
                                val pt = projette(la, lo, latVue, lonVue, c, r)
                                if (pt == null) { ouvert = false; break }
                                if (!ouvert) { chemin.moveTo(pt.x, pt.y); ouvert = true }
                                else chemin.lineTo(pt.x, pt.y)
                            }
                            if (ouvert) drawPath(chemin, col)
                        }
                    }
                    peins(ui.carnet.lotwTravailles + ui.carnet.lotwConfirmes,
                        Color(0x5535E0C0))
                    peins(ui.carnet.lotwActives, Color(0x66E8B44A))
                }

                for (p in pays) {
                    for (a in p.anneaux) {
                        val chemin = Path()
                        var ouvert = false
                        var i = 0
                        while (i < a.size) {
                            val pt = projette(a[i], a[i + 1], latVue, lonVue, c, r)
                            if (pt == null) { ouvert = false } else {
                                if (!ouvert) { chemin.moveTo(pt.x, pt.y); ouvert = true }
                                else chemin.lineTo(pt.x, pt.y)
                            }
                            i += 2
                        }
                        drawPath(chemin, Color(0xFF6FA8C7), style = Stroke(width = 1.2f))
                    }
                }

                // La grille Maidenhead et les noms des carrés, à partir d'un
                // certain grossissement : au-delà, les traits se lisent ; en
                // dessous, ils feraient un moiré illisible.
                if (zoom >= 2f) {
                    val centreLat = latVue.toDouble()
                    val centreLon = -lonVue.toDouble()
                    val demi = (40.0 / zoom).coerceAtMost(40.0)
                    var la = floor((centreLat - demi) / 1.0) * 1.0
                    while (la <= centreLat + demi) {
                        var lo = floor((centreLon - demi * 2) / 2.0) * 2.0
                        while (lo <= centreLon + demi * 2) {
                            val k = fr.f4ioz.satcombo.location.Maidenhead
                                .fromLatLon(la + 0.5, lo + 1.0).take(4)
                            val pt = projette(la + 0.5, lo + 1.0, latVue, lonVue, c, r)
                            if (pt != null && zoom >= 3.5f) {
                                drawContext.canvas.nativeCanvas.drawText(
                                    k, pt.x, pt.y,
                                    android.graphics.Paint().apply {
                                        color = 0x66FFFFFF
                                        textSize = 9f * zoom.coerceAtMost(3f)
                                        textAlign = android.graphics.Paint.Align.CENTER
                                        isAntiAlias = true
                                    })
                            }
                            lo += 2.0
                        }
                        la += 1.0
                    }
                }

                // La trace au sol du satellite suivi, s'il y en a un.
                ui.groundTrack.takeIf { it.size > 2 }?.let { tr ->
                    val chemin = Path()
                    var ouvert = false
                    for ((la, lo) in tr) {
                        val pt = projette(la, lo, latVue, lonVue, c, r)
                        if (pt == null) ouvert = false
                        else if (!ouvert) { chemin.moveTo(pt.x, pt.y); ouvert = true }
                        else chemin.lineTo(pt.x, pt.y)
                    }
                    drawPath(chemin, Color(0xFF35E0C0), style = Stroke(width = 2f))
                    // La position du moment, au bout de la trace.
                    val (la, lo) = tr[tr.size / 8]
                    projette(la, lo, latVue, lonVue, c, r)?.let {
                        drawCircle(Color(0xFF35E0C0), 5f, it)
                    }
                }

                // Le QTH.
                ui.observer?.let { o ->
                    projette(o.latDeg, o.lonDeg, latVue, lonVue, c, r)?.let {
                        drawCircle(Color.White, 4.5f, it)
                        drawCircle(Color(0xFFE02020), 3f, it)
                    }
                }
            }
        }

        ui.selected?.let {
            Text(it.name, color = Cyan, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/**
 * Projection orthographique.
 *
 * Rend `null` quand le point est **derrière** la sphère : c'est le cosinus de
 * l'angle au centre qui le dit. Sans ce test, les côtes de l'autre hémisphère
 * se dessineraient par transparence et le globe ressemblerait à une assiette.
 */
private fun projette(
    latDeg: Double, lonDeg: Double, latVue: Float, lonVue: Float,
    c: Offset, r: Float,
): Offset? {
    val la = Math.toRadians(latDeg)
    val lo = Math.toRadians(lonDeg + lonVue)
    val la0 = Math.toRadians(latVue.toDouble())
    val cosCentre = sin(la0) * sin(la) + cos(la0) * cos(la) * cos(lo)
    if (cosCentre <= 0.02) return null
    val x = cos(la) * sin(lo)
    val y = cos(la0) * sin(la) - sin(la0) * cos(la) * cos(lo)
    return Offset(c.x + (r * x).toFloat(), c.y - (r * y).toFloat())
}

/** Équateur et tropiques, pour donner l'échelle sans charger le dessin. */
private fun paralleles(d: DrawScope, c: Offset, r: Float, latVue: Float, lonVue: Float) {
    for (lat in listOf(-66.5, -23.5, 0.0, 23.5, 66.5)) {
        val chemin = Path()
        var ouvert = false
        var lon = -180.0
        while (lon <= 180.0) {
            val pt = projette(lat, lon, latVue, lonVue, c, r)
            if (pt == null) ouvert = false
            else if (!ouvert) { chemin.moveTo(pt.x, pt.y); ouvert = true }
            else chemin.lineTo(pt.x, pt.y)
            lon += 3.0
        }
        d.drawPath(chemin,
            if (lat == 0.0) Color(0xFF2E5F80) else Color(0xFF1B3B52),
            style = Stroke(width = 1f))
    }
}
