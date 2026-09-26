/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.ble

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import fr.f4ioz.satcombo.MainViewModel
import kotlinx.coroutines.delay

/**
 * Keeps the compass link open across the whole app. Draws nothing.
 *
 * Placed in the root `Box`, not the pointing screen: a global element inside a
 * screen dies with it — here, losing the heading whenever the operator opens
 * the log mid-pass, for ten seconds a 90° pass does not give back.
 */
@Composable
fun TenueBoussole(vm: MainViewModel) {
    val ctx = LocalContext.current
    val ui by vm.ui.collectAsState()
    val r = ui.rotor

    // Offset and convention follow the settings without touching the link:
    // changing them must not drop the module.
    LaunchedEffect(r.boussoleCalage, r.boussoleConvention, r.boussoleFleche) {
        BoussoleBle.calage = r.boussoleCalage
        BoussoleBle.convention = r.boussoleConvention
        BoussoleBle.fleche =
            fr.f4ioz.satcombo.domain.PointageAntenne.depuisTexte(r.boussoleFleche)
    }

    LaunchedEffect(r.boussoleSource, r.boussoleAdresse) {
        val voulu = r.boussoleSource == "BLE" && r.boussoleAdresse.isNotBlank()
        BoussoleBle.choisie = voulu
        if (!voulu) {
            BoussoleBle.coupe()
            return@LaunchedEffect
        }
        // Retry while the module is the source: one switched on late, or back
        // in range, must reconnect by itself — the operator will not go back to
        // the settings with an antenna in hand. Every 15 s: cheap on battery,
        // quick enough to go unnoticed.
        while (true) {
            val e = BoussoleBle.etat.value
            if (e == BoussoleBle.Etat.ARRET || e == BoussoleBle.Etat.ECHEC) {
                BoussoleBle.connecte(ctx, r.boussoleAdresse)
            }
            delay(15_000L)
        }
    }
}
