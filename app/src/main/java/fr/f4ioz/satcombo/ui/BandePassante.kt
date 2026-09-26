/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import fr.f4ioz.satcombo.ui.theme.Cyan
import fr.f4ioz.satcombo.ui.theme.SpaceSurface

/**
 * Read-only position within the transponder passband, for the keyboard screen.
 * A tunable slider there would get nudged by a finger aiming at a letter; you
 * only need to know low, middle or high.
 */
@Composable
fun BandePassante(
    basHz: Long?, hautHz: Long?, courantHz: Long?,
    modifier: Modifier = Modifier,
) {
    if (basHz == null || hautHz == null || courantHz == null) return
    val etendue = (hautHz - basHz).toDouble()
    if (etendue <= 0) return
    val part = ((courantHz - basHz) / etendue).coerceIn(0.0, 1.0).toFloat()

    Canvas(modifier.fillMaxWidth().height(10.dp).padding(vertical = 2.dp)) {
        val h = size.height
        // The band.
        drawRoundRect(
            color = SpaceSurface,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2))
        // Filled portion, readable at a glance.
        drawRoundRect(
            color = Cyan.copy(alpha = 0.35f),
            size = Size(size.width * part, h),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2))
        // Marker: a solid line reads better than a dot on a 10-pt bar.
        val x = (size.width * part).coerceIn(1.5f, size.width - 1.5f)
        drawRect(Cyan, topLeft = Offset(x - 1.5f, 0f), size = Size(3f, h))
    }
}
