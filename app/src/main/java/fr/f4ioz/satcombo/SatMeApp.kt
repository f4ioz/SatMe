/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo

import android.app.Application
import fr.f4ioz.satcombo.diag.PlantageGarde

/**
 * La première chose qu'Android construit dans notre processus, et donc le seul
 * endroit d'où l'on puisse voir un plantage survenu avant l'écran d'accueil.
 *
 * Il n'y avait pas de classe `Application` jusqu'ici, et c'est précisément ce
 * qui manquait le 2 août : l'application mourait au démarrage chez un testeur,
 * et aucune de nos lignes n'avait encore tourné pour le raconter.
 *
 * On n'y met rien d'autre. Tout travail placé ici retarde l'apparition du
 * premier écran, sur tous les téléphones, pour toujours.
 */
class SatMeApp : Application() {

    /**
     * Et non `onCreate`. Android construit les fournisseurs de contenu — dont
     * celui qui initialise WorkManager — ENTRE `attachBaseContext` et
     * `onCreate`. Un plantage dans cette fenêtre-là échappait donc au
     * garde-fou, et c'est exactement la fenêtre où meurent les démarrages qui
     * dépendent d'un installeur de constructeur.
     */
    override fun attachBaseContext(base: android.content.Context?) {
        super.attachBaseContext(base)
        runCatching { PlantageGarde.installe(this) }
    }
}
