/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.f4ioz.satcombo.MainViewModel
import fr.f4ioz.satcombo.ble.BoussoleBle
import fr.f4ioz.satcombo.domain.BoussoleWit
import fr.f4ioz.satcombo.domain.PointageAntenne
import fr.f4ioz.satcombo.domain.SequenceCalibrage
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.i18n.tf
import fr.f4ioz.satcombo.ui.theme.*

/**
 * Settings for the remote compass.
 *
 * Three steps, in order: pick the source, select the module, then calibrate.
 * Calibration comes last because it means nothing until frames arrive.
 */
@Composable
fun BoussoleCarte(vm: MainViewModel, ui: fr.f4ioz.satcombo.UiState) {
    val ctx = LocalContext.current
    val r = ui.rotor
    val etat by BoussoleBle.etat
    val raison by BoussoleBle.raison
    val cap by BoussoleBle.cap
    val brut by BoussoleBle.lacetBrut
    val attitude by BoussoleBle.attitudeBrute
    // Reuses the existing ACTION_CREATE_DOCUMENT launcher for the log export.
    val enregistreReleve = rememberEnregistrer()
    // Stored boom vector, read once: several blocks need it.
    val posee = PointageAntenne.depuisTexte(r.boussoleFleche)
    val trames by BoussoleBle.trames

    var messageAppr by remember { mutableStateOf("") }

    // A half-done calibration is a wrong one that looks right: nothing is
    // written until both sightings are taken.

    val demande = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { accords ->
        // Without this branch a refused permission makes "Search" do nothing,
        // with no explanation.
        if (accords.values.all { it }) BoussoleBle.cherche(ctx)
        else {
            BoussoleBle.raison.value = "permissions"
            BoussoleBle.etat.value = BoussoleBle.Etat.ECHEC
        }
    }

    Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            // **The thumbnail shows only with the module.** It depicts the
            // Bluetooth box; with the phone compass selected it would promise
            // hardware that is not in use.
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (r.boussoleSource == "BLE") {
                    MiniatureModule(Modifier.size(width = 34.dp, height = 46.dp))
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(t("bouss_titre"), color = TextHi, fontWeight = FontWeight.Bold)
                    if (r.boussoleSource == "BLE") {
                        Text(t("bouss_modele"), color = Cyan, fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Text(t("bouss_desc"), color = TextLo, fontSize = 11.sp,
                modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("TEL" to t("bouss_tel"), "BLE" to t("bouss_ble")).forEach { (id, label) ->
                    FilterChip(selected = r.boussoleSource == id,
                        onClick = { vm.setBoussoleSource(id) },
                        label = { Text(label, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Cyan.copy(alpha = 0.25f),
                            selectedLabelColor = Cyan))
                }
            }

            if (r.boussoleSource == "BLE") {
            // Not just advice: a module placed against an FT-817 reads the
            // speaker magnet, not the Earth.
            Spacer(Modifier.height(10.dp))
            Surface(color = Amber.copy(alpha = 0.13f), shape = RoundedCornerShape(8.dp)) {
                Text(t("bouss_avert"), color = Amber, fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
            }

            // --- module ---
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t("bouss_module"), color = TextHi, fontSize = 13.sp)
                    Text(
                        if (r.boussoleAdresse.isBlank()) t("bouss_aucun")
                        else "${r.boussoleNom.ifBlank { "?" }}  ${r.boussoleAdresse}",
                        color = TextLo, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
                if (etat == BoussoleBle.Etat.RECHERCHE) {
                    TextButton(onClick = { BoussoleBle.arreteRecherche() }) {
                        Text(t("bouss_recherche"), color = Cyan, fontSize = 12.sp)
                    }
                } else {
                    TextButton(onClick = {
                        if (BoussoleBle.permissionsAccordees(ctx)) BoussoleBle.cherche(ctx)
                        else demande.launch(BoussoleBle.permissionsNecessaires())
                    }) { Text(t("bouss_chercher"), color = Cyan, fontSize = 12.sp) }
                }
            }

            if (BoussoleBle.trouves.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                BoussoleBle.trouves.forEach { a ->
                    Row(Modifier.fillMaxWidth()
                        .clickable {
                            vm.setBoussoleModule(a.nom, a.adresse)
                            BoussoleBle.trouves.clear()
                            BoussoleBle.arreteRecherche()
                            BoussoleBle.connecte(ctx, a.adresse)
                        }
                        .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(a.nom, color = TextHi, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text("${a.rssi} dBm", color = TextLo, fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace)
                    }
                    HorizontalDivider(color = SpaceSurface)
                }
            }

            // --- status ---
            Spacer(Modifier.height(10.dp))
            val (couleur, texte) = when (etat) {
                BoussoleBle.Etat.CONNECTE -> Color(0xFF2FB344) to t("bouss_etat_connecte")
                BoussoleBle.Etat.CONNEXION -> Cyan to t("bouss_etat_connexion")
                BoussoleBle.Etat.RECHERCHE -> Cyan to t("bouss_recherche")
                BoussoleBle.Etat.MUET -> Amber to t("bouss_etat_muet")
                BoussoleBle.Etat.ECHEC -> Magenta to messageEchec(raison)
                BoussoleBle.Etat.ARRET -> TextLo to t("bouss_etat_arret")
            }
            Text(texte, color = couleur, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)

            if (cap != null) {
                Text(
                    "${t("bouss_cap")} ${Math.round(cap!!)}°   ·   $trames ${t("bouss_trames")}",
                    color = TextHi, fontSize = 20.sp, fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 4.dp))
            }

            if (etat != BoussoleBle.Etat.ARRET && etat != BoussoleBle.Etat.RECHERCHE) {
                TextButton(onClick = { BoussoleBle.coupe() }) {
                    Text(t("bouss_couper"), color = TextLo, fontSize = 12.sp)
                }
            }

            // **One dial, one offset.**
            //
            // This page once had two dials and four offsets plus a direction
            // switch. They answered the same question and contradicted each
            // other: a leftover offset and an inverted direction stacked on the
            // vector, and east came out west. The boom vector alone gives
            // heading **and** elevation, independent of polarisation. The rest
            // was removed, not disabled: a setting kept "just in case" ends up
            // taking over without anyone knowing why.


            // --- check dial ---
            //
            // "187°" looks right until compared with something. A needle is
            // compared with the landscape at a glance; that is how swapped
            // north/south was spotted.
            val capVu = cap
            if (capVu != null) {
                Spacer(Modifier.height(12.dp))
                // Measured outside the Canvas: `drawText` needs a measurer, too
                // costly to create every frame for four fixed letters.
                val mesureur = androidx.compose.ui.text.rememberTextMeasurer()
                val styleCardinal = androidx.compose.ui.text.TextStyle(
                    fontSize = 13.sp, fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace)
                Canvas(Modifier.fillMaxWidth().height(170.dp)) {
                    val r = minOf(size.width, size.height) / 2f - 30f
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    drawCircle(SpaceSurface, r, androidx.compose.ui.geometry.Offset(cx, cy))
                    drawCircle(Cyan.copy(alpha = 0.35f), r, androidx.compose.ui.geometry.Offset(cx, cy),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
                    // Minor ticks every 30° to estimate headings between
                    // cardinal points.
                    for (k in 0 until 12) {
                        if (k % 3 == 0) continue
                        val a1 = Math.toRadians((k * 30).toDouble())
                        val sx = cx + (r - 6f) * kotlin.math.sin(a1).toFloat()
                        val sy = cy - (r - 6f) * kotlin.math.cos(a1).toFloat()
                        val ex = cx + r * kotlin.math.sin(a1).toFloat()
                        val ey = cy - r * kotlin.math.cos(a1).toFloat()
                        drawLine(TextLo.copy(alpha = 0.4f),
                            androidx.compose.ui.geometry.Offset(sx, sy),
                            androidx.compose.ui.geometry.Offset(ex, ey), 2f)
                    }
                    // **Cardinal points written as letters.** A longer tick
                    // for north has to be guessed; a letter is read, even in
                    // full sun with the antenna in hand. A north mistaken for
                    // south cost many versions.
                    val lettres = listOf("N", "E", "S", "O")
                    for (k in 0 until 4) {
                        val a2 = Math.toRadians((k * 90).toDouble())
                        val l = if (k == 0) 16f else 10f
                        val sx = cx + (r - l) * kotlin.math.sin(a2).toFloat()
                        val sy = cy - (r - l) * kotlin.math.cos(a2).toFloat()
                        val ex = cx + r * kotlin.math.sin(a2).toFloat()
                        val ey = cy - r * kotlin.math.cos(a2).toFloat()
                        val teinte = if (k == 0) Magenta else TextHi
                        drawLine(teinte,
                            androidx.compose.ui.geometry.Offset(sx, sy),
                            androidx.compose.ui.geometry.Offset(ex, ey), 3f)
                        val mesure = mesureur.measure(lettres[k],
                            styleCardinal.copy(color = teinte))
                        val lx = cx + (r + 15f) * kotlin.math.sin(a2).toFloat() -
                            mesure.size.width / 2f
                        val ly = cy - (r + 15f) * kotlin.math.cos(a2).toFloat() -
                            mesure.size.height / 2f
                        drawText(mesure,
                            topLeft = androidx.compose.ui.geometry.Offset(lx, ly))
                    }
                    // Needle: where SatMe thinks the antenna points.
                    val a3 = Math.toRadians(capVu.toDouble())
                    val sin = kotlin.math.sin(a3).toFloat()
                    val cos = kotlin.math.cos(a3).toFloat()
                    val px = cx + (r - 20f) * sin
                    val py = cy - (r - 20f) * cos
                    // Tail on the opposite side; together with the arrowhead
                    // it removes the 180° ambiguity.
                    drawLine(Cyan.copy(alpha = 0.3f),
                        androidx.compose.ui.geometry.Offset(cx, cy),
                        androidx.compose.ui.geometry.Offset(cx - (r - 45f) * sin,
                            cy + (r - 45f) * cos), 4f)
                    drawLine(Cyan, androidx.compose.ui.geometry.Offset(cx, cy),
                        androidx.compose.ui.geometry.Offset(px, py), 6f)
                    // Triangular tip marks the front.
                    val pointe = androidx.compose.ui.graphics.Path().apply {
                        moveTo(cx + r * sin, cy - r * cos)
                        lineTo(cx + (r - 18f) * sin - 7f * cos,
                            cy - (r - 18f) * cos - 7f * sin)
                        lineTo(cx + (r - 18f) * sin + 7f * cos,
                            cy - (r - 18f) * cos + 7f * sin)
                        close()
                    }
                    drawPath(pointe, Cyan)
                    drawCircle(Cyan, 5f, androidx.compose.ui.geometry.Offset(cx, cy))
                }
                Text(
                    "${t("bouss_cardinal")} ${cardinal(capVu)}  ·  ${Math.round(capVu)}°",
                    color = TextHi, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Text(t("bouss_cadran_desc"), color = TextLo, fontSize = 11.sp,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }

            // Reset. Without it, a failed calibration leaves values that only
            // a reinstall clears.
            TextButton(onClick = {
                vm.setBoussoleFleche(null)
                vm.setBoussoleConvention("AXES_ECHANGES")
                vm.setBoussoleCalage(0f)
                messageAppr = t("bouss_remise_ok")
            }) {
                Text(t("bouss_remise"), color = Amber, fontSize = 12.sp)
            }

            // The "module convention" section and the three-sighting
            // calibration were removed. **They fixed a fault that did not
            // exist.** "North reads south, west reads east" means all four
            // points rotate **together**: a 180° rotation, not a mirror (a
            // mirror swaps E/W and leaves N/S). The only fault was a boom
            // vector learnt backwards; mirrors and frame changes stacked on an
            // imaginary cause broke elevation along the way.

            // --- calibration on a known azimuth ---
            //
            // Two polarisation poses give a line, not a direction: the end must
            // be guessed and can be wrong. A known azimuth leaves nothing to
            // guess.
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Explore, null, tint = Cyan,
                    modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(t("bouss_nord_titre"), color = TextHi, fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold)
            }
            Text(t("bouss_nord_desc"), color = TextLo, fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 6.dp))
            // --- mounting axis, declared, not guessed ---
            //
            // It is geometry: we know which face is screwed to the boom.
            // Deriving it from a magnetic sighting baked in the magnetometer
            // error (17° in one field log) and capped elevation by as much.
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Straighten, null, tint = Cyan,
                    modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(t("bouss_axe_titre"), color = TextHi, fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold)
            }
            Text(t("bouss_axe_desc"), color = TextLo, fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 4.dp))
            val axes = listOf(
                Triple(t("bouss_axe_avant"), 0f, 1f),
                Triple(t("bouss_axe_arriere"), 0f, -1f),
                Triple(t("bouss_axe_droite"), 1f, 0f),
                Triple(t("bouss_axe_gauche"), -1f, 0f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                axes.forEach { (nom, x, y) ->
                    val v = fr.f4ioz.satcombo.domain.Vec3(x, y, 0f)
                    val choisi = posee != null &&
                        kotlin.math.abs(posee.x - x) < 0.05f &&
                        kotlin.math.abs(posee.y - y) < 0.05f &&
                        kotlin.math.abs(posee.z) < 0.05f
                    TextButton(onClick = {
                        vm.setBoussoleFleche(v)
                        // An offset from another axis is meaningless now.
                        vm.setBoussoleCalage(0f)
                        messageAppr = t("bouss_axe_ok")
                    }) {
                        Text(nom, color = if (choisi) Cyan else TextLo, fontSize = 12.sp,
                            fontWeight = if (choisi) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }

            // A boom vector 38° off the real axis caps elevation at 52° instead
            // of 90°. The app cannot detect a crooked box, but it can warn.
            Surface(color = Amber.copy(alpha = 0.13f),
                shape = RoundedCornerShape(8.dp)) {
                Text(t("bouss_axe_avert"), color = Amber, fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
            }
            Spacer(Modifier.height(8.dp))
            // **One sighting, and it is exact.** The two- and three-sighting
            // calibrations were removed: they measured a convention the module
            // did not need. If the boom end is wrong, the flip button fixes it.
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                listOf(0f to t("bouss_nord"), 90f to t("bouss_est"),
                       180f to t("bouss_sud"), 270f to t("bouss_ouest"))
                    .forEach { (az, nom) ->
                    // **The sighting no longer touches the boom vector.**
                    // Learning it from a magnetic sighting baked the
                    // magnetometer error into the vector (elevation capped at
                    // 73°, i.e. a 17° tilt). The vector is now **declared**
                    // (mounting geometry) and the sighting only sets the
                    // heading offset (magnetism). Two questions, two settings.
                    TextButton(enabled = attitude != null && posee != null, onClick = {
                        val pose = attitude ?: return@TextButton
                        val f2 = posee ?: return@TextButton
                        val lu = PointageAntenne.pointageLibre(
                            pose, f2,
                            PointageAntenne.ConventionLibre.decode(r.boussoleConvention)
                        ).azimutDeg
                        var d = (az - lu) % 360f
                        if (d > 180f) d -= 360f
                        if (d < -180f) d += 360f
                        vm.setBoussoleCalage(d)
                        messageAppr = tf("bouss_nord_ok", nom)
                    }) {
                        Text(nom, color = if (attitude != null && posee != null) Cyan
                                          else TextLo,
                            fontSize = 13.sp)
                    }
                }
            }

            // **One-tap flip.** Removed once in favour of a cardinal sighting,
            // then restored: a vector learnt from two polarisation poses is a
            // line, not a direction, and its end can be wrong. Then nothing
            // else is wrong and this button is enough.
            TextButton(enabled = r.boussoleFleche.isNotBlank(), onClick = {
                val f = PointageAntenne.depuisTexte(r.boussoleFleche)
                if (f == null) messageAppr = t("bouss_nord_echec")
                else {
                    vm.setBoussoleFleche(PointageAntenne.retourne(f))
                    messageAppr = t("bouss_retourne_ok")
                }
            }) {
                Text(t("bouss_retourne"), color = Amber, fontSize = 12.sp)
            }

            // --- guided calibration sequence ---
            //
            // **Measure, don't guess.** Guessing the module convention from one
            // or two poses always held when level and failed elsewhere: one pose
            // constrains only part of the rotation. The nine poses together
            // determine everything (yaw over a full turn, roll both ways, pitch
            // to vertical). The log is exportable because copying nine triplets
            // by hand corrupts them.
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = SpaceSurface)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Rule, null, tint = Cyan,
                    modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(t("bouss_seq_titre"), color = TextHi, fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold)
            }
            Text(t("bouss_seq_desc"), color = TextLo, fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 8.dp))

            val releves = SequenceCalibrage.decode(r.boussoleReleves)
            val prochaine = SequenceCalibrage.prochaine(releves)

            if (prochaine != null) {
                Surface(color = Cyan.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(prochaine.titre, color = Cyan, fontSize = 14.sp,
                            fontWeight = FontWeight.Bold)
                        Text(prochaine.aide, color = TextHi, fontSize = 12.sp,
                            modifier = Modifier.padding(top = 4.dp))
                        // A drawing is misread far less than a sentence; logs
                        // were lost because the edge sighted flat was not the
                        // one raised next.
                        DessinPose(prochaine.cle)
                    }
                }
                Spacer(Modifier.height(8.dp))
                // Disabled without frames: it would record zeros that later
                // pass for a measurement.
                TextButton(enabled = attitude != null, onClick = {
                    attitude?.let { a ->
                        vm.ajouteReleve(prochaine.cle, a.roulis, a.tangage, a.lacet)
                    }
                }) {
                    Text(t("bouss_seq_relever"),
                        color = if (attitude != null) Cyan else TextLo,
                        fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                Text(t("bouss_seq_complet"), color = Amber, fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold)
            }

            Text(tf("bouss_seq_avancement", releves.size,
                    SequenceCalibrage.ETAPES.size),
                color = TextLo, fontSize = 11.sp,
                modifier = Modifier.padding(top = 6.dp))

            // Table shown as it fills, so an outlier stands out before export.
            SequenceCalibrage.ETAPES.forEach { e ->
                val v = releves.firstOrNull { it.cle == e.cle }
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(if (v == null) "○" else "●",
                        color = if (v == null) TextLo else Cyan, fontSize = 11.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(e.cle, color = TextLo, fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(78.dp))
                    Text(
                        if (v == null) "—"
                        else "%+7.1f %+7.1f %+7.1f".format(v.roulis, v.tangage, v.lacet),
                        color = if (v == null) TextLo else TextHi, fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace)
                }
            }

            // --- verdict, computed on the spot ---
            //
            // The operator needs it while still holding the antenna.
            if (SequenceCalibrage.complete(releves)) {
                val an = SequenceCalibrage.analyse(releves)
                Spacer(Modifier.height(10.dp))
                // Judged on the **worst** error, not the spread of level poses:
                // a convention that passes those and fails vertical is wrong.
                val franc = an.scoreDeg < 15f
                Text(
                    tf(if (franc) "bouss_an_bonne" else "bouss_an_dispersee",
                        Math.round(an.scoreDeg)),
                    color = if (franc) Cyan else Magenta, fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold)
                an.convention?.let { c ->
                    Text("${t("bouss_an_conv")} ${c.encode()}", color = TextLo,
                        fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
                if (!franc) {
                    // The cause is almost always the same; say it.
                    Text(t("bouss_an_arete"), color = Amber, fontSize = 11.sp)
                }
                an.controles.forEach { c ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                        Text(if (c.bon) "✓" else "✗",
                            color = if (c.bon) Cyan else Magenta, fontSize = 12.sp)
                        Spacer(Modifier.width(6.dp))
                        Text(c.cle, color = TextLo, fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.width(72.dp))
                        Text(c.lu, color = if (c.bon) TextHi else Magenta,
                            fontSize = 11.sp)
                    }
                }
                val f = an.fleche
                if (f != null && franc) {
                    TextButton(onClick = {
                        vm.setBoussoleFleche(f)
                        vm.setBoussoleConvention(
                            an.convention?.encode()
                                ?: PointageAntenne.ConventionLibre.PAR_DEFAUT.encode())
                        // The measured offset cancels the module's residual
                        // magnetic error (quantified by the vertical pose).
                        // Setting it to zero would bring it back.
                        vm.setBoussoleCalage(an.calageDeg)
                        messageAppr = t("bouss_an_applique")
                    }) {
                        Text(t("bouss_an_appliquer"), color = Cyan, fontSize = 13.sp,
                            fontWeight = FontWeight.Bold)
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(top = 6.dp)) {
                TextButton(enabled = releves.isNotEmpty(), onClick = {
                    val texte = SequenceCalibrage.rapport(
                        releves, r.boussoleNom, "20.04")
                    enregistreReleve(
                        "satme-boussole.txt", "text/plain", depuisTexte(texte))
                }) {
                    Text(t("bouss_seq_export"),
                        color = if (releves.isNotEmpty()) Cyan else TextLo, fontSize = 12.sp)
                }
                TextButton(enabled = releves.isNotEmpty(), onClick = { vm.videReleves() }) {
                    Text(t("bouss_seq_vider"), color = Amber, fontSize = 12.sp)
                }
            }

            // A calibration you cannot check cannot be trusted; this check is
            // what reveals a crooked box.
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CheckCircle, null, tint = Cyan,
                    modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(t("bouss_verif_titre"), color = TextHi, fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold)
            }
            Text(t("bouss_verif"), color = TextLo, fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp))
            // What SatMe cannot fix, it must at least name.
            Spacer(Modifier.height(8.dp))
            Surface(color = Amber.copy(alpha = 0.13f), shape = RoundedCornerShape(8.dp)) {
                Text(t("bouss_magneto"), color = Amber, fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
            }

            Spacer(Modifier.height(12.dp))
            // Either the module gives full pointing, or only heading and the
            // phone keeps elevation. No silent third case.
            Text(
                if (posee == null) t("bouss_fleche_absente")
                else "${t("bouss_fleche_presente")}  (%.2f, %.2f, %.2f)"
                    .format(posee.x, posee.y, posee.z),
                color = if (posee == null) TextLo else Cyan, fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 4.dp))

            // **The three raw angles, as the module sends them.**
            //
            // Elevation capped below 90° comes from a wrong matrix, not an
            // offset: levelling hides it at the calibration pose and it shows
            // as soon as you move away. Read flat then vertical, these numbers
            // identify the tilt axis, its sign and its range.
            attitude?.let { a ->
                Text(
                    "R %+6.1f   T %+6.1f   L %+6.1f".format(
                        a.roulis, a.tangage, a.lacet),
                    color = Amber, fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 6.dp))
                Text(t("bouss_bruts_desc"), color = TextLo, fontSize = 10.sp)
            }

            val elv = BoussoleBle.elevation.value
            if (elv != null) {
                Text("${t("bouss_elev")} ${Math.round(elv)}°",
                    color = TextHi, fontSize = 18.sp, fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 2.dp))
            }
            }
        }
    }
}

/**
 * Raw failure reason made readable.
 *
 * Causes the operator can fix get their own sentence; others fall into a
 * generic message that **keeps the code**: "liaison_perdue_8" means nothing to
 * the operator but everything to whoever fixes it, and the operator relays it.
 */
/** Readable name of a convention: nobody should have to read "AXES_ET_LACET". */
private fun messageEchec(raison: String): String = when {
    raison == "permissions" -> t("bouss_err_permissions")
    raison == "pas_de_bluetooth" -> t("bouss_err_pas_de_bluetooth")
    raison == "bluetooth_eteint" -> t("bouss_err_bluetooth_eteint")
    raison == "rien_trouve" -> t("bouss_err_rien_trouve")
    raison == "service_absent" -> t("bouss_err_service_absent")
    raison.startsWith("liaison_perdue") -> t("bouss_err_liaison_perdue")
    raison.isBlank() -> t("bouss_err_autre")
    else -> "${t("bouss_err_autre")} ($raison)"
}

/**
 * Nearest compass point: "N", "NNE", "NE"… "187°" cannot be checked at a
 * glance; "S" can, and shows at once you are facing south while aiming north.
 */
private fun cardinal(azimut: Float): String {
    val noms = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
                       "S", "SSO", "SO", "OSO", "O", "ONO", "NO", "NNO")
    var a = azimut % 360f
    if (a < 0f) a += 360f
    return noms[(((a + 11.25f) / 22.5f).toInt()) % 16]
}

