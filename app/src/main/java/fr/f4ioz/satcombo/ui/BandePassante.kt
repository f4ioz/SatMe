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
 * Où l'on se trouve dans le transpondeur, sans rien pouvoir toucher.
 *
 * L'écran du clavier sert à écrire un indicatif pendant que le passage
 * défile : y mettre le curseur réglable de la page du passage inviterait à
 * déplacer la fréquence d'un doigt qui visait une lettre. Une bande et un
 * repère suffisent — on veut savoir si l'on est en bas, au milieu ou en haut,
 * pas régler.
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
        // La bande.
        drawRoundRect(
            color = SpaceSurface,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2))
        // La portion parcourue, pour situer d'un coup d'œil.
        drawRoundRect(
            color = Cyan.copy(alpha = 0.35f),
            size = Size(size.width * part, h),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2))
        // Le repère : un trait franc, plus lisible qu'une pastille sur une
        // barre haute de dix points.
        val x = (size.width * part).coerceIn(1.5f, size.width - 1.5f)
        drawRect(Cyan, topLeft = Offset(x - 1.5f, 0f), size = Size(3f, h))
    }
}
