/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
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
 * Ce qui tient la liaison de la boussole ouverte, d'un bout à l'autre de
 * l'application.
 *
 * Il est posé au `Box` racine et non dans l'écran de pointage, pour la raison
 * habituelle : un élément global vissé dans un écran meurt avec lui. Ici, cela
 * voudrait dire perdre le cap chaque fois que l'opérateur consulte son carnet
 * en plein passage, et le retrouver dix secondes plus tard — dix secondes
 * qu'un passage à 90° d'élévation ne rend pas.
 *
 * Il ne dessine rien.
 */
@Composable
fun TenueBoussole(vm: MainViewModel) {
    val ctx = LocalContext.current
    val ui by vm.ui.collectAsState()
    val r = ui.rotor

    // Le calage et le sens suivent les réglages sans passer par la liaison :
    // les changer ne doit pas couper le module.
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
        // On retente tant que la source reste le module. Un module qu'on allume
        // après avoir ouvert l'application, ou qui sort d'un trou de portée,
        // doit se rattraper tout seul : sans cela l'opérateur doit revenir dans
        // les réglages, ce qu'il ne fera pas une antenne dans chaque main.
        //
        // Quinze secondes entre deux essais : assez rare pour ne pas manger la
        // batterie, assez fréquent pour qu'on ne s'en aperçoive pas.
        while (true) {
            val e = BoussoleBle.etat.value
            if (e == BoussoleBle.Etat.ARRET || e == BoussoleBle.Etat.ECHEC) {
                BoussoleBle.connecte(ctx, r.boussoleAdresse)
            }
            delay(15_000L)
        }
    }
}
