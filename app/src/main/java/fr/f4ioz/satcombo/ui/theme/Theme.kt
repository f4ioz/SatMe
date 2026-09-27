/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * SatMe supports a dark "space" theme and a light theme inspired by the
 * Météo & Marées app (clean #eef2f7 background, blue accent, pill tabs).
 *
 * The codebase refers to palette names (Cyan, TextHi, SpaceCard…) directly,
 * often outside @Composable scope, so those names are top-level properties that
 * read from a single mutable holder updated when the theme changes.
 */
private data class Palette(
    val bg: Color, val surface: Color, val card: Color,
    val cyan: Color, val aurora: Color, val amber: Color, val magenta: Color,
    val textHi: Color, val textLo: Color, val outline: Color, val onPrimary: Color,
    val gradient: Brush, val isDark: Boolean
)

private val DarkPalette = Palette(
    bg = Color(0xFF0A0E17), surface = Color(0xFF131A26), card = Color(0xFF1A2433),
    cyan = Color(0xFF38E1D4), aurora = Color(0xFF6C8BFF), amber = Color(0xFFFFC65C),
    magenta = Color(0xFFFF6BA9), textHi = Color(0xFFEAF1FF), textLo = Color(0xFF8A98B0),
    outline = Color(0xFF2A3647), onPrimary = Color(0xFF00201D),
    gradient = Brush.verticalGradient(listOf(Color(0xFF0A0E17), Color(0xFF0E1626), Color(0xFF0A0E17))),
    isDark = true
)

// Light theme — Météo & Marées inspired.
private val LightPalette = Palette(
    bg = Color(0xFFEEF2F7), surface = Color(0xFFFFFFFF), card = Color(0xFFFFFFFF),
    cyan = Color(0xFF1F6FEB), aurora = Color(0xFF3B5BDB), amber = Color(0xFF9A5B00),
    magenta = Color(0xFFD6336C), textHi = Color(0xFF1E293B), textLo = Color(0xFF64748B),
    outline = Color(0xFFD7DEE8), onPrimary = Color(0xFFFFFFFF),
    gradient = Brush.verticalGradient(listOf(Color(0xFFEEF2F7), Color(0xFFE7EEF6), Color(0xFFEEF2F7))),
    isDark = false
)

/**
 * "Sun" palette — for reading the screen in full daylight.
 *
 * **Dark on light, against common intuition.** In sunlight the glass reflects
 * 4-5 % of ambient light: under 100,000 lux that is thousands of nits, while a
 * phone peaks around a thousand. A black pixel cannot be darker than that
 * reflection, so it turns grey and contrast collapses; a white pixel adds its
 * emission on top. That is why e-readers and outdoor GPS units are dark on
 * light.
 *
 * Hence pure white, pure black and **no mid greys**: the light theme's
 * `textLo` (#64748B) is 4.8:1 on white, fine indoors, invisible outside; here
 * it is #303030, 12:1. Cyan and magenta become dark saturated tones: light
 * cyan on white simply does not show.
 */
private val SunPalette = Palette(
    bg = Color(0xFFFFFFFF), surface = Color(0xFFFFFFFF), card = Color(0xFFFFFFFF),
    cyan = Color(0xFF00408A), aurora = Color(0xFF005E2E), amber = Color(0xFF8A4B00),
    magenta = Color(0xFFA80030), textHi = Color(0xFF000000), textLo = Color(0xFF303030),
    outline = Color(0xFF000000), onPrimary = Color(0xFFFFFFFF),
    gradient = Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFFFFFFF))),
    isDark = false
)

private var activePalette by mutableStateOf(DarkPalette)

/** The three themes, in selector order. */
const val THEME_SOMBRE = 0
const val THEME_CLAIR = 1
const val THEME_SOLEIL = 2

fun applyTheme(dark: Boolean) { applyTheme(if (dark) THEME_SOMBRE else THEME_CLAIR) }

fun applyTheme(theme: Int) {
    activePalette = when (theme) {
        THEME_SOMBRE -> DarkPalette
        THEME_SOLEIL -> SunPalette
        else -> LightPalette
    }
}

fun isDarkTheme(): Boolean = activePalette.isDark

/**
 * Is sun mode active? Drawings need it: a compass in thin pastel strokes reads
 * well in the shade and vanishes in the sun, whatever the text palette.
 */
fun isSunTheme(): Boolean = activePalette === SunPalette

// ---------------------------------------------------------------- UI scaling

/**
 * Width, in dp, the screens were laid out against. Phones range from roughly
 * 320 dp (small or "display size = large") to 440 dp, and Android's font-size
 * slider adds another multiplier on top: the same card is airy on one handset
 * and clipped on the next. Rather than sprinkling breakpoints everywhere, the
 * app pretends every device is this wide and the density is bent to match — the
 * layout is then identical everywhere, just physically bigger or smaller.
 */
private const val DESIGN_WIDTH_DP = 400f

/** One step is 32 dp of reference width: compact fits more, large reads easier. */
private const val STEP_DP = 32f

private var uniformUi by mutableStateOf(true)
private var uiScaleStep by mutableStateOf(0)
private var uiFollowSystemFont by mutableStateOf(false)

fun applyUiScale(uniform: Boolean, step: Int, followSystemFont: Boolean) {
    uniformUi = uniform
    uiScaleStep = step.coerceIn(-1, 1)
    uiFollowSystemFont = followSystemFont
}

val SpaceBg: Color get() = activePalette.bg
val SpaceSurface: Color get() = activePalette.surface
val SpaceCard: Color get() = activePalette.card
val Cyan: Color get() = activePalette.cyan
val Aurora: Color get() = activePalette.aurora
val Amber: Color get() = activePalette.amber
val Magenta: Color get() = activePalette.magenta
val TextHi: Color get() = activePalette.textHi
val TextLo: Color get() = activePalette.textLo
val SpaceGradient: Brush get() = activePalette.gradient

@Composable
fun SatComboTheme(content: @Composable () -> Unit) {
    val p = activePalette
    val scheme = if (p.isDark) darkColorScheme(
        primary = p.cyan, onPrimary = p.onPrimary, secondary = p.amber, tertiary = p.magenta,
        background = p.bg, onBackground = p.textHi, surface = p.surface, onSurface = p.textHi,
        surfaceVariant = p.card, onSurfaceVariant = p.textLo,
        primaryContainer = Color(0xFF11353A), outline = p.outline
    ) else lightColorScheme(
        primary = p.cyan, onPrimary = p.onPrimary, secondary = p.amber, tertiary = p.magenta,
        background = p.bg, onBackground = p.textHi, surface = p.surface, onSurface = p.textHi,
        surfaceVariant = p.card, onSurfaceVariant = p.textLo,
        primaryContainer = Color(0xFFDCE7FF), outline = p.outline
    )
    // Bend the density so the app always believes it has `target` dp of width,
    // and pin the text scale unless the operator asked to follow the system.
    val cfg = LocalConfiguration.current
    val base = LocalDensity.current
    val density = if (uniformUi && cfg.screenWidthDp > 0) {
        val target = DESIGN_WIDTH_DP - uiScaleStep * STEP_DP
        // Ratio is clamped: a tablet or a very narrow phone should bend towards
        // the reference, not all the way to unreadable.
        val ratio = (cfg.screenWidthDp / target).coerceIn(0.78f, 1.35f)
        Density(base.density * ratio, fontScaleOf(base))
    } else {
        Density(base.density, fontScaleOf(base))
    }

    CompositionLocalProvider(LocalDensity provides density) {
        MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
    }
}

/** System font slider honoured on request, otherwise neutral — and never so
 *  large that a two-line label turns into five. */
private fun fontScaleOf(base: Density): Float =
    if (uiFollowSystemFont) base.fontScale.coerceIn(0.85f, 1.30f) else 1f
