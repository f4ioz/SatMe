/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.f4ioz.satcombo.data.SatPosition
import fr.f4ioz.satcombo.ui.theme.*
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.i18n.tf
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

data class DeviceOrientation(
    val azimuthDeg: Float, val elevationDeg: Float, val available: Boolean,
    val needsCalibration: Boolean = false   // sensor reports LOW/UNRELIABLE accuracy
)

/** Live "where the back of the phone points" (azimuth + elevation), smoothed,
 *  with magnetic declination applied (true-north azimuth). */
/**
 * Une couleur choisie par l'opérateur, rendue lisible sur fond blanc.
 *
 * En plein soleil le facteur limitant est le contraste, pas la teinte. On
 * garde donc la teinte — c'est elle que l'opérateur a choisie et qu'il
 * reconnaît — et l'on ne touche qu'à la clarté, jusqu'à passer le seuil de
 * 4,5:1 sur blanc.
 *
 * La luminance est calculée selon la pondération de la vision humaine : le
 * vert compte pour 71 %, le rouge 21 %, le bleu 7 %. Assombrir de la même
 * quantité toutes les composantes ferait passer un bleu déjà sombre pour
 * illisible et laisserait un jaune éblouissant.
 */
private fun assombrisPourSoleil(c: Color): Color {
    var couleur = c
    repeat(8) {
        val l = 0.2126f * couleur.red + 0.7152f * couleur.green + 0.0722f * couleur.blue
        if (l <= 0.30f) return couleur
        couleur = Color(couleur.red * 0.78f, couleur.green * 0.78f, couleur.blue * 0.78f,
            couleur.alpha)
    }
    return couleur
}

@Composable
fun rememberDeviceOrientation(
    declinationDeg: Float = 0f,
    aimMode: String = "EDGE"
): State<DeviceOrientation> {
    val ctx = LocalContext.current
    val state = remember { mutableStateOf(DeviceOrientation(0f, 0f, false)) }
    DisposableEffect(ctx, declinationDeg, aimMode) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val rv = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val listener = object : SensorEventListener {
            val r = FloatArray(9)
            var smAz = Float.NaN; var smEl = Float.NaN
            var lowAccuracy = false
            override fun onSensorChanged(e: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(r, e.values)
                // World frame: X east, Y north, Z up. world = R * device.
                // EDGE: aim along +Y device axis (top edge, screen up) -> column 1.
                // BACK: aim along -Z device axis (camera/AR style)     -> -column 2.
                val east: Float; val north: Float; val up: Float
                if (aimMode == "BACK") {
                    east = -r[2]; north = -r[5]; up = -r[8]
                } else {
                    east = r[1]; north = r[4]; up = r[7]
                }
                var az = Math.toDegrees(
                    kotlin.math.atan2(east.toDouble(), north.toDouble())).toFloat() + declinationDeg
                az = (az + 360f) % 360f
                val el = Math.toDegrees(
                    kotlin.math.asin(up.coerceIn(-1f, 1f).toDouble())).toFloat()
                if (smAz.isNaN()) { smAz = az; smEl = el }
                else {
                    val k = 0.25f
                    val d = ((az - smAz + 540f) % 360f) - 180f
                    smAz = (smAz + k * d + 360f) % 360f
                    smEl += k * (el - smEl)
                }
                state.value = DeviceOrientation(smAz, smEl, true, lowAccuracy)
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {
                lowAccuracy = a == SensorManager.SENSOR_STATUS_ACCURACY_LOW ||
                              a == SensorManager.SENSOR_STATUS_UNRELIABLE
                state.value = state.value.copy(needsCalibration = lowAccuracy)
            }
        }
        if (rv != null) sm.registerListener(listener, rv, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(listener) }
    }

    // --- l'aiguillage vers la boussole déportée ---
    //
    // Le module donne toujours le cap. Il donne **aussi** l'élévation, mais
    // seulement si l'opérateur a dit sur quel axe la lire : vissé sur la
    // flèche, le module est dans l'axe de l'antenne, ce que le téléphone tenu
    // à côté n'est jamais. Tant que l'axe n'est pas désigné, l'élévation reste
    // celle du téléphone — une aiguille fausse qui bouge est pire qu'une
    // aiguille approximative qu'on sait approximative.
    //
    // Les capteurs du téléphone continuent de tourner en arrière-plan. C'est
    // voulu : quand le module se tait — batterie, distance, boîtier resté dans
    // le sac — `cap` repasse à `null` et le cadran retrouve le téléphone à la
    // trame suivante, sans écran vide ni manipulation.
    //
    // La déclinaison s'applique ici comme aux capteurs du téléphone : le module
    // lit le champ magnétique terrestre, donc il donne un nord magnétique. Un
    // rotor, lui, est réglé au nord vrai et n'y a pas droit.
    //
    // Le porteur est créé inconditionnellement : un `remember` posé dans une
    // branche décale la table de slots de Compose dès que la branche change
    // d'avis — et elle en change à chaque fois que le module se tait.
    val depuisModule = remember { mutableStateOf(DeviceOrientation(0f, 0f, false)) }
    val capModule = fr.f4ioz.satcombo.ble.BoussoleBle.cap.value
    if (!fr.f4ioz.satcombo.ble.BoussoleBle.choisie || capModule == null) {
        // Le cap du téléphone est publié pour qui en a besoin hors de l'arbre
        // d'affichage — la page de démonstration, notamment. `SideEffect` et
        // non une écriture directe : on ne modifie rien pendant la composition.
        val vu = state.value
        SideEffect {
            fr.f4ioz.satcombo.domain.CapVivant.pose(
                if (vu.available) vu.azimuthDeg else null,
                if (vu.available) vu.elevationDeg else null, false)
        }
        return state
    }

    val azimut = ((capModule + declinationDeg) % 360f + 360f) % 360f
    val elModule = fr.f4ioz.satcombo.ble.BoussoleBle.elevation.value
    depuisModule.value = DeviceOrientation(
        azimuthDeg = azimut,
        elevationDeg = elModule ?: state.value.elevationDeg,
        available = true,
        // Le module fait sa propre fusion : la mise en garde d'Android sur la
        // précision du magnétomètre ne parle pas de lui.
        needsCalibration = false
    )
    val posé = depuisModule.value
    SideEffect {
        fr.f4ioz.satcombo.domain.CapVivant.pose(
            posé.azimuthDeg, posé.elevationDeg, true)
    }
    return depuisModule
}

private fun deltaAngle(target: Double, current: Double): Double {
    var d = target - current
    while (d > 180) d -= 360
    while (d < -180) d += 360
    return d
}

/**
 * Pointing view, sky-chart style: the polar grid, the pass track and the
 * satellite are FIXED in world az/el (zenith centre, horizon edge, N up).
 * The moving element is the phone-aim bubble. Bring the bubble onto the
 * satellite to point at it.
 */
@Composable
fun CompassAim(
    target: SatPosition?,
    passTrack: List<Pair<Double, Double>> = emptyList(),
    trail: List<Pair<Double, Double>> = emptyList(),
    declinationDeg: Float = 0f,
    aimMode: String = "EDGE",
    onAimModeChange: (String) -> Unit = {},
    /**
     * Le geste d'appuis rapides : il **ouvre l'écran de saisie**, il n'écrit
     * rien. Le nom disait « quick log » tant qu'il posait un contact au
     * carnet — un contact sans indicatif, c'est-à-dire pas un contact.
     */
    onSaisie: () -> Unit = {},
    logTaps: Int = 3,                    // appuis qui déclenchent onSaisie (2 ou 3)
    compact: Boolean = false,            // hide verbose status/visée; show mode chips only if asked
    showModeChips: Boolean = true,
    headUp: Boolean = false,             // rotate map so phone heading is up; aim fixed on axis
    needleStyle: Boolean = false,        // big golden needle from center instead of guidance arrow
    cornerTL: Pair<String, String>? = null,   // label, value
    cornerTR: Pair<String, String>? = null,
    cornerBL: Pair<String, String>? = null,
    cornerBR: Pair<String, String>? = null,
    // User-customisable colours (defaults = the shipped palette).
    // Les couleurs par défaut valent pour les thèmes sombre et clair ; le mode
    // soleil les remplace plus bas par des teintes foncées et saturées. Un rose
    // et un orange pastel sur fond blanc, dehors, ne se distinguent ni l'un de
    // l'autre ni du fond.
    traceColor: Color = Color(0xFFFF6BA9),               // predicted pass track
    traceWidth: Float = 1f,                              // thickness × for trace + arrows
    needleFar: Color = Color(0xFFD6336C),                // azimuth: far / near / on-axis
    needleNear: Color = Color(0xFFF59F00),
    needleClose: Color = Color(0xFF2FB344),
    bubbleFar: Color = Color(0xFFD6336C),                // elevation: far / near / on-axis
    bubbleNear: Color = Color(0xFFF59F00),
    bubbleClose: Color = Color(0xFF2FB344),
    // Status rings around the dial: HIDDEN when far, then near/close colours.
    ringAzNear: Color = Color(0xFFF59F00),
    ringAzClose: Color = Color(0xFF2FB344),
    ringElNear: Color = Color(0xFFF59F00),
    ringElClose: Color = Color(0xFF2FB344),
    // Quand un mât tient l'antenne, c'est lui qui sait où elle pointe.
    rotorAzDeg: Double? = null,
    rotorElDeg: Double? = null,
    modifier: Modifier = Modifier
) {
    val sensor by rememberDeviceOrientation(declinationDeg, aimMode)
    // Le mât remplace le téléphone, et rien d'autre ne bouge : les couleurs,
    // les seuils, la trace et l'aiguille ne savent pas d'où vient la visée.
    // Un rotor d'azimut seul ne donne que l'azimut ; l'élévation reste celle du
    // téléphone, parce qu'aucun capteur du mât ne la connaît. La déclinaison ne
    // s'applique pas : un rotor est réglé au nord vrai, pas au nord magnétique.
    val dev = if (rotorAzDeg != null)
        DeviceOrientation(
            azimuthDeg = rotorAzDeg.toFloat(),
            elevationDeg = (rotorElDeg ?: sensor.elevationDeg.toDouble()).toFloat(),
            available = true,
            needsCalibration = false
        )
    else sensor

    val dAz = target?.let { deltaAngle(it.azimuthDeg, dev.azimuthDeg.toDouble()) }
    val dEl = target?.let { it.elevationDeg - dev.elevationDeg }
    val targetUp = (target?.elevationDeg ?: -1.0) >= 0
    // Handheld Arrow-type antennas are imprecise (wide beamwidth), so the green
    // "on target" zone is generous: within ~15° counts as aligned.
    val angErr = if (dAz != null && dEl != null) kotlin.math.hypot(dAz, dEl) else null
    val aligned = targetUp && angErr != null && angErr < 15.0
    val close = targetUp && angErr != null && angErr < 30.0

    // Separate 3-tier proximity for azimuth and elevation, each with its own
    // color: far = magenta, near = amber, on-target = green. Thresholds are in
    // degrees of absolute error. Level 2 = on target, 1 = near, 0 = far.
    val NEAR_DEG = 25.0   // within this = "proche"
    val CLOSE_DEG = 8.0   // within this = "très proche / OK"
    fun tier(errAbs: Double?): Int = when {
        errAbs == null -> 0
        errAbs <= CLOSE_DEG -> 2
        errAbs <= NEAR_DEG -> 1
        else -> 0
    }
    val azTier = if (targetUp) tier(dAz?.let { kotlin.math.abs(it) }) else 0
    val elTier = if (targetUp) tier(dEl?.let { kotlin.math.abs(it) }) else 0
    // The status RINGS go "close" at the ALIGNED/beam threshold (±15°), so both
    // rings turn green together with the ✓ ALIGNÉ label. Needle/bubble keep the
    // finer 8° tier for precise trimming.
    val BEAM_DEG = 15.0
    fun tierRing(errAbs: Double?): Int = when {
        errAbs == null -> 0
        errAbs <= BEAM_DEG -> 2
        errAbs <= NEAR_DEG -> 1
        else -> 0
    }
    val ringAzTier = if (targetUp) tierRing(dAz?.let { kotlin.math.abs(it) }) else 0
    val ringElTier = if (targetUp) tierRing(dEl?.let { kotlin.math.abs(it) }) else 0
    val soleilActif = fr.f4ioz.satcombo.ui.theme.isSunTheme()

    // Le système à trois couleurs — loin, proche, dans l'axe — doit rester
    // lisible dehors. Le vert #2FB344 et l'orange #F59F00 passent sous 3:1 sur
    // blanc en plein jour ; leurs équivalents foncés tiennent au-delà de 7:1
    // tout en restant distincts l'un de l'autre pour un œil pressé.
    //
    // **La couleur de tracé, elle, appartient à l'opérateur.** L'imposer en
    // mode Soleil rendait le réglage muet : on le changeait, rien ne bougeait,
    // et rien ne disait pourquoi. Un réglage qui ne fait rien est pire que pas
    // de réglage. On assombrit donc la couleur choisie au lieu de la
    // remplacer — elle reste reconnaissable, et lisible sur blanc.
    val traceColor = if (soleilActif) assombrisPourSoleil(traceColor) else traceColor
    val needleFar = if (soleilActif) Color(0xFFA80030) else needleFar
    val needleNear = if (soleilActif) Color(0xFF8A4B00) else needleNear
    val needleClose = if (soleilActif) Color(0xFF005E2E) else needleClose
    val bubbleFar = if (soleilActif) Color(0xFFA80030) else bubbleFar
    val bubbleNear = if (soleilActif) Color(0xFF8A4B00) else bubbleNear
    val bubbleClose = if (soleilActif) Color(0xFF005E2E) else bubbleClose
    val ringAzNear = if (soleilActif) Color(0xFF8A4B00) else ringAzNear
    val ringAzClose = if (soleilActif) Color(0xFF005E2E) else ringAzClose
    val ringElNear = if (soleilActif) Color(0xFF8A4B00) else ringElNear
    val ringElClose = if (soleilActif) Color(0xFF005E2E) else ringElClose

    val okC = if (soleilActif) Color(0xFF005E2E) else Color(0xFF2FB344)
    // Azimuth uses the needle palette, elevation the bubble palette (each 3-tier).
    fun tierColorAz(t: Int) = when (t) { 2 -> needleClose; 1 -> needleNear; else -> needleFar }
    fun tierColorEl(t: Int) = when (t) { 2 -> bubbleClose; 1 -> bubbleNear; else -> bubbleFar }
    val azColor = tierColorAz(azTier)
    val elColor = tierColorEl(elTier)

    // Haptic pulse the moment we become aligned, so you can feel the lock-on
    // without staring at the screen while pointing the antenna.
    val hapticView = androidx.compose.ui.platform.LocalView.current
    var wasAligned by remember { mutableStateOf(false) }
    LaunchedEffect(aligned) {
        if (aligned && !wasAligned) {
            hapticView.performHapticFeedback(
                android.view.HapticFeedbackConstants.CONFIRM)
        }
        wasAligned = aligned
    }

    // Le détecteur d'appuis : le nombre choisi d'appuis en 800 ms ouvre la
    // saisie d'une seule main. La fenêtre reste de 800 ms quel que soit le
    // compte, pour que deux appuis restent aussi délibérés que trois.
    val needTaps = logTaps.coerceIn(2, 3)
    val tapTimes = remember { mutableStateListOf<Long>() }

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(if (compact) 320.dp else 300.dp)) {
        Canvas(Modifier.fillMaxSize()
            .pointerInput(needTaps) {
                detectTapGestures(onTap = {
                    val now = System.currentTimeMillis()
                    tapTimes.add(now)
                    while (tapTimes.isNotEmpty() && now - tapTimes.first() > 800) tapTimes.removeAt(0)
                    if (tapTimes.size >= needTaps) { tapTimes.clear(); onSaisie() }
                })
            }) {
            val r = min(size.width, size.height) / 2f * 0.84f
            val c = Offset(size.width / 2f, size.height / 2f)
            val dark = fr.f4ioz.satcombo.ui.theme.isDarkTheme()
            // Au soleil, le cadran perd ses demi-teintes.
            //
            // Les gris de grille du thème clair ordinaire — #B9C6D8 et
            // #CFD9E6 — donnent moins de 2:1 de contraste sur leur fond. C'est
            // agréable à l'ombre et strictement invisible dehors, où le reflet
            // du verre noie tout ce qui n'est pas franchement noir. Le mode
            // soleil garde donc un fond blanc et une grille noire atténuée par
            // l'épaisseur du trait, jamais par la couleur.
            val soleil = fr.f4ioz.satcombo.ui.theme.isSunTheme()
            val dialBg = when {
                soleil -> Color(0xFFFFFFFF)
                dark -> Color(0xFF0E1724)
                else -> Color(0xFFE8EEF6)
            }
            val grid = when {
                soleil -> Color(0xFF000000)
                dark -> Color(0xFF2A3647)
                else -> Color(0xFFB9C6D8)
            }
            val gridSoft = when {
                soleil -> Color(0xFF707070)
                dark -> Color(0xFF20293A)
                else -> Color(0xFFCFD9E6)
            }

            // North stays up unless the user explicitly picks head-up mode.
            val rot = if (headUp && dev.available) -dev.azimuthDeg.toDouble() else 0.0

            // --- sky grid (cardinals rotate in head-up mode) ---
            drawCircle(dialBg, r, c)
            drawCircle(grid, r, c, style = Stroke(2.5f))
            drawCircle(gridSoft, r * 2f / 3f, c, style = Stroke(1.2f))
            drawCircle(gridSoft, r / 3f, c, style = Stroke(1.2f))
            drawLine(gridSoft, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1.2f)
            drawLine(gridSoft, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1.2f)
            if (needleStyle) {
                // Diagonal spokes (NE/SE/SW/NW) for the planetarium look.
                val d45 = (r * 0.70710678f)
                drawLine(gridSoft, Offset(c.x - d45, c.y - d45), Offset(c.x + d45, c.y + d45), 1.2f)
                drawLine(gridSoft, Offset(c.x - d45, c.y + d45), Offset(c.x + d45, c.y - d45), 1.2f)
            }
            drawCardinal(c, r, "N", 0, rot); drawCardinal(c, r, "E", 90, rot)
            drawCardinal(c, r, "S", 180, rot); drawCardinal(c, r, "W", 270, rot)

            // --- alignment status rings ---
            // Outer solid ring = AZIMUT, inner dashed ring (slightly smaller)
            // = ÉLÉVATION. Three states: far -> NO ring, near -> first colour,
            // on-axis -> second colour (both user-configurable). Readable at
            // arm's length without looking at the numbers.
            if (dev.available && targetUp) {
                val azRing = when (ringAzTier) { 2 -> ringAzClose; 1 -> ringAzNear; else -> null }
                val elRing = when (ringElTier) { 2 -> ringElClose; 1 -> ringElNear; else -> null }
                azRing?.let { drawCircle(it.copy(alpha = 0.9f), r, c, style = Stroke(6f)) }
                elRing?.let {
                    drawCircle(it.copy(alpha = 0.9f), r * 0.94f, c,
                        style = Stroke(4.5f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 12f))))
                }
            }

            // --- fixed pass track ---
            if (passTrack.size > 1) {
                val path = Path()
                passTrack.forEachIndexed { i, (az, el) ->
                    val pt = azElToXy(az, el, c, r, rot)
                    if (i == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
                }
                val tw = traceWidth.coerceIn(0.5f, 4f)
                drawPath(path, traceColor.copy(alpha = 0.75f),
                    style = Stroke(3f * tw,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f * tw, 9f * tw))))
                val aosPt = azElToXy(passTrack.first().first, passTrack.first().second, c, r, rot)
                val losPt = azElToXy(passTrack.last().first, passTrack.last().second, c, r, rot)
                drawCircle(traceColor, 7f * tw, aosPt)
                drawCircle(traceColor, 7f * tw, losPt, style = Stroke(3f * tw))
                for (f in listOf(0.25f, 0.50f, 0.75f)) {
                    val i = (passTrack.size * f).toInt().coerceIn(1, passTrack.size - 1)
                    val ma = azElToXy(passTrack[i - 1].first, passTrack[i - 1].second, c, r, rot)
                    val mb = azElToXy(passTrack[i].first, passTrack[i].second, c, r, rot)
                    drawArrowTriangle(ma, mb, traceColor, 16f * tw)
                }
            }

            // --- live trail (where the satellite actually went) ---
            if (trail.size > 1) {
                for (i in 1 until trail.size) {
                    val a = azElToXy(trail[i - 1].first, trail[i - 1].second, c, r, rot)
                    val b = azElToXy(trail[i].first, trail[i].second, c, r, rot)
                    val alpha = 0.15f + 0.55f * (i.toFloat() / trail.size)
                    drawLine(Aurora.copy(alpha = alpha), a, b, 3f)
                }
            }

            // --- fixed live satellite ---
            if (needleStyle && target != null && dev.available && dAz != null) {
                // The needle shows WHERE THE PHONE/ANTENNA POINTS on a north-up
                // dial: it sweeps around as you rotate the phone. Aim by turning
                // until the needle covers the satellite dot — it turns green when
                // you are aligned (±15°). Drawn even below the horizon (dimmed).
                val aRad = Math.toRadians((dev.azimuthDeg.toDouble() + rot) - 90.0)
                val nLen = r * 0.8f
                val tip = Offset(
                    c.x + (cos(aRad) * nLen).toFloat(),
                    c.y + (kotlin.math.sin(aRad) * nLen).toFloat())
                val ndx = tip.x - c.x; val ndy = tip.y - c.y
                val nd = kotlin.math.hypot(ndx.toDouble(), ndy.toDouble()).toFloat()
                val ux = ndx / nd; val uy = ndy / nd
                val pxv = -uy; val pyv = ux
                // Colour by AZIMUTH proximity (same 3-tier logic as the elevation
                // bubble): far = magenta, near = amber, on-axis = green.
                val col = azColor.copy(alpha = if (targetUp) 1f else 0.45f)
                val needle = androidx.compose.ui.graphics.Path().apply {
                    moveTo(tip.x, tip.y)
                    lineTo(c.x + pxv * 13f, c.y + pyv * 13f)
                    lineTo(c.x - pxv * 13f, c.y - pyv * 13f)
                    close()
                }
                drawPath(needle, col)
                drawPath(needle, Color(0x55000000).copy(alpha = if (targetUp) 0.33f else 0.18f), style = Stroke(2f))
                drawCircle(col, 8f, c)
                drawCircle(Color(0x55000000), 8f, c, style = Stroke(2f))
            }
            if (targetUp && target != null) {
                val pt = azElToXy(target.azimuthDeg, target.elevationDeg, c, r, rot)
                val col = if (target.sunlit) Amber else Aurora
                // **Le satellite doit se détacher du tracé, quelle que soit
                // la couleur du tracé.**
                //
                // Le point et la trajectoire partageaient la même famille de
                // teintes : sur fond clair ils se confondaient, et l'on
                // perdait de vue la seule chose qui bouge. On l'entoure donc
                // d'un anneau de la couleur du fond — une césure franche que
                // ni le tracé ni la grille ne peuvent imiter — avant de poser
                // le point et son contour.
                val fond = fr.f4ioz.satcombo.ui.theme.SpaceCard
                drawCircle(col.copy(alpha = 0.25f), 20f, pt)
                drawCircle(fond, 14f, pt)
                drawCircle(col, 10f, pt)
                drawCircle(
                    if (fr.f4ioz.satcombo.ui.theme.isDarkTheme()) Color.White
                    else Color(0xFF101010),
                    10f, pt, style = Stroke(2.5f))
            }

            // --- MOVING aim bubble (where the phone points) ---
            if (dev.available) {
                val el = dev.elevationDeg.toDouble()
                // Bubble color = ELEVATION proximity (green when el matches).
                val bubbleCol = elColor
                if (el >= 0) {
                    val pt = if (headUp) azElToXy(0.0, el, c, r, 0.0)
                             else azElToXy(dev.azimuthDeg.toDouble(), el, c, r, rot)
                    // Ring gets thicker/greener as elevation locks in.
                    val ringW = when (elTier) { 2 -> 6f; 1 -> 4.5f; else -> 3.5f }
                    drawCircle(bubbleCol.copy(alpha = if (elTier == 2) 0.32f else 0.14f), 40f, pt)
                    drawCircle(bubbleCol, 24f, pt, style = Stroke(ringW))
                    drawLine(bubbleCol, Offset(pt.x - 13f, pt.y), Offset(pt.x + 13f, pt.y), 3f)
                    drawLine(bubbleCol, Offset(pt.x, pt.y - 13f), Offset(pt.x, pt.y + 13f), 3f)
                    // Guidance arrow toward the satellite. Color = AZIMUTH proximity,
                    // and it grows as azimuth gets closer (big green = on axis).
                    if (!needleStyle && targetUp && target != null && !aligned) {
                        val sp = azElToXy(target.azimuthDeg, target.elevationDeg, c, r, rot)
                        val dx = sp.x - pt.x; val dy = sp.y - pt.y
                        val dist = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
                        if (dist > 44f) {
                            val ux = dx / dist; val uy = dy / dist
                            val from = Offset(pt.x + ux * 30f, pt.y + uy * 30f)
                            val len = when (azTier) { 2 -> 95f; 1 -> 78f; else -> 62f }
                            val to = Offset(
                                pt.x + ux * kotlin.math.min(dist - 20f, len),
                                pt.y + uy * kotlin.math.min(dist - 20f, len))
                            val stroke = when (azTier) { 2 -> 9f; 1 -> 7f; else -> 5.5f }
                            val head = when (azTier) { 2 -> 30f; 1 -> 25f; else -> 20f }
                            drawLine(azColor, from, to, stroke)
                            drawArrowTriangle(from, to, azColor, head)
                        }
                    }
                } else {
                    // Aiming below horizon: hollow marker on the rim. Head-up: top
                    // of the dial (axis); north-up: at the device azimuth.
                    val azr = if (headUp) 0.0 else Math.toRadians(dev.azimuthDeg.toDouble())
                    val pt = Offset(c.x + r * sin(azr).toFloat(), c.y - r * cos(azr).toFloat())
                    drawCircle(TextLo, 14f, pt, style = Stroke(3f))
                    drawLine(TextLo, pt, Offset(pt.x, pt.y + 18f), 2f)
                }
            }
        }
        // Corner info overlays (compact mode shows key telemetry around the plot).
        cornerTL?.let { CornerInfo(it.first, it.second, Alignment.TopStart) }
        cornerTR?.let { CornerInfo(it.first, it.second, Alignment.TopEnd) }
        cornerBL?.let { CornerInfo(it.first, it.second, Alignment.BottomStart) }
        cornerBR?.let { CornerInfo(it.first, it.second, Alignment.BottomEnd) }
        }

        if (!compact) {
            Spacer(Modifier.height(10.dp))
            when {
                target == null || !targetUp ->
                    Text(t("sat_below_follow_arc"),
                        color = TextLo, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                aligned ->
                    Text(t("aligned_beam"), color = Color(0xFF49D17F),
                        fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                else -> {
                    val az = dAz ?: 0.0; val el = dEl ?: 0.0
                    val hAz = when {
                        azTier == 2 -> t("azimuth_ok")
                        az > 0 -> tf("turn_right", az.toInt())
                        else -> tf("turn_left", (-az).toInt())
                    }
                    val hEl = when {
                        elTier == 2 -> t("elevation_ok")
                        el > 0 -> tf("raise_el", el.toInt())
                        else -> tf("lower_el", (-el).toInt())
                    }
                    Text(hAz, color = azColor, fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace)
                    Text(hEl, color = elColor, fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace)
                }
            }
        } else {
            // Compact: two short chips, each colored by its own axis tier.
            when {
                target == null || !targetUp ->
                    Text(t("below_horizon"), color = TextLo, fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold, fontSize = 14.sp,
                        modifier = Modifier.padding(top = 2.dp))
                aligned ->
                    Text(t("aligned_short"), color = okC, fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold, fontSize = 14.sp,
                        modifier = Modifier.padding(top = 2.dp))
                else -> {
                    val az = dAz ?: 0.0; val el = dEl ?: 0.0
                    val azTxt = if (azTier == 2) "✓az" else if (az > 0) "→${az.toInt()}°" else "←${(-az).toInt()}°"
                    val elTxt = if (elTier == 2) t("check_el_mini") else if (el > 0) "↑${el.toInt()}°" else "↓${(-el).toInt()}°"
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(top = 2.dp)) {
                        Text(azTxt, color = azColor, fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(elTxt, color = elColor, fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
            }
        }
        if (showModeChips) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AimModeChip(t("aim_slab"), aimMode == "EDGE") { onAimModeChange("EDGE") }
                AimModeChip(t("aim_back"), aimMode == "BACK") { onAimModeChange("BACK") }
            }
        }
        if (!compact) {
            Spacer(Modifier.height(8.dp))
            Surface(color = SpaceSurface, shape = RoundedCornerShape(10.dp)) {
                Text(
                    tf("aim_status_line", dev.azimuthDeg.toInt(), dev.elevationDeg.toInt()) +
                        (target?.takeIf { targetUp }
                            ?.let { tf("sat_status_suffix", it.azimuthDeg.toInt(), it.elevationDeg.toInt()) } ?: ""),
                    color = TextLo, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
        if (!dev.available) {
            Spacer(Modifier.height(6.dp))
            Text(t("orientation_sensor_unavailable"), color = Magenta, fontSize = 11.sp)
        }
        // Une ligne, et une seule : savoir que l'aiguille suit le mât et non la
        // main change tout ce qu'on en conclut quand elle ne bouge pas.
        if (rotorAzDeg != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                if (rotorElDeg != null) t("aim_source_rotor") else t("aim_source_rotor_az"),
                color = Cyan, fontSize = 11.sp
            )
        }
        // Magnetometer accuracy warning: Android reports LOW/UNRELIABLE until the
        // user recalibrates (figure-8 motion) or moves away from magnetic objects.
        if (dev.available && dev.needsCalibration) {
            Spacer(Modifier.height(6.dp))
            Surface(color = Amber.copy(alpha = 0.15f), shape = RoundedCornerShape(8.dp)) {
                Text(t("compass_calibrate"), color = Amber, fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
            }
        }
    }
}

@Composable
private fun BoxScope.CornerInfo(label: String, value: String, align: Alignment) {
    Column(
        modifier = Modifier.align(align).padding(6.dp),
        horizontalAlignment = when (align) {
            Alignment.TopEnd, Alignment.BottomEnd -> Alignment.End
            else -> Alignment.Start
        }
    ) {
        Text(label, color = TextLo, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Text(value, color = TextHi, fontSize = 18.sp, fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun AimModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) Cyan.copy(alpha = 0.20f) else SpaceSurface,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(label, color = if (selected) Cyan else TextLo, fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

// --- shared drawing helpers (local copies to keep this file standalone) ---

private fun azElToXy(azDeg: Double, elDeg: Double, c: Offset, r: Float, rotDeg: Double = 0.0): Offset {
    val radius = r * ((90.0 - elDeg.coerceIn(0.0, 90.0)) / 90.0).toFloat()
    val az = Math.toRadians(azDeg + rotDeg)
    return Offset(c.x + radius * sin(az).toFloat(), c.y - radius * cos(az).toFloat())
}


/** Filled triangle arrowhead at [tip], oriented along from->tip. */
private fun DrawScope.drawArrowTriangle(from: Offset, tip: Offset, color: Color, size: Float) {
    val ang = kotlin.math.atan2((tip.y - from.y).toDouble(), (tip.x - from.x).toDouble())
    fun pt(a: Double, d: Float) = Offset(
        tip.x + d * kotlin.math.cos(a).toFloat(),
        tip.y + d * kotlin.math.sin(a).toFloat())
    val p = Path().apply {
        val nose = pt(ang, size * 0.7f)
        moveTo(nose.x, nose.y)
        val b1 = pt(ang + 2.5, size * 0.7f)
        val b2 = pt(ang - 2.5, size * 0.7f)
        lineTo(b1.x, b1.y); lineTo(b2.x, b2.y); close()
    }
    drawPath(p, color)
    drawPath(p, (if (fr.f4ioz.satcombo.ui.theme.isDarkTheme()) Color.White else Color(0xFF1E293B)).copy(alpha = 0.5f), style = Stroke(1.2f))
}

private fun DrawScope.drawArrow(from: Offset, tip: Offset, color: Color, len: Float) {
    val ang = kotlin.math.atan2((tip.y - from.y).toDouble(), (tip.x - from.x).toDouble())
    for (s in listOf(2.6, -2.6)) {
        drawLine(color, tip, Offset(
            tip.x + len * kotlin.math.cos(ang + s).toFloat(),
            tip.y + len * kotlin.math.sin(ang + s).toFloat()), 3f)
    }
}

private fun DrawScope.drawCardinal(c: Offset, r: Float, label: String, azDeg: Int, rotDeg: Double = 0.0) {
    val az = Math.toRadians(azDeg.toDouble() + rotDeg)
    val x = c.x + (r + 24f) * sin(az).toFloat()
    val y = c.y - (r + 24f) * cos(az).toFloat()
    drawContext.canvas.nativeCanvas.drawText(
        label, x, y + 10f,
        android.graphics.Paint().apply {
            color = if (fr.f4ioz.satcombo.ui.theme.isDarkTheme())
                android.graphics.Color.parseColor("#8A98B0")
            else android.graphics.Color.parseColor("#5A6B82")
            textSize = 28f; textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true; isFakeBoldText = true
        }
    )
}
