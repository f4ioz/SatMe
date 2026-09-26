/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.ui

import kotlin.math.pow

/**
 * Contrast on white for sun mode, in plain Kotlin so it is JVM-tested.
 *
 * Components are sRGB as Compose stores them (gamma-encoded, 0..1). They must
 * be linearised before weighting: weighting them directly left magenta at
 * 3.1:1 and darkened orange to 10:1.
 */
internal object ContrasteSoleil {

    /** WCAG AA for normal text. */
    const val CIBLE = 4.5f

    private fun lineaire(c: Float): Float =
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

    /** WCAG relative luminance. */
    fun luminance(r: Float, g: Float, b: Float): Float =
        0.2126f * lineaire(r) + 0.7152f * lineaire(g) + 0.0722f * lineaire(b)

    fun contrasteSurBlanc(r: Float, g: Float, b: Float): Float =
        1.05f / (luminance(r, g, b) + 0.05f)

    /**
     * Darkens in small steps, keeping the hue, until [CIBLE] is reached on
     * white. A colour already readable comes back unchanged.
     */
    fun assombris(r: Float, g: Float, b: Float): FloatArray {
        var k = 1f
        // 0.9^60 ≈ 0.002: black long before the loop runs out.
        repeat(60) {
            if (contrasteSurBlanc(r * k, g * k, b * k) >= CIBLE) return floatArrayOf(r * k, g * k, b * k)
            k *= 0.9f
        }
        return floatArrayOf(0f, 0f, 0f)
    }
}
