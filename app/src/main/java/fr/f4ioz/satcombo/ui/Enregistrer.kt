/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import fr.f4ioz.satcombo.i18n.t
import java.io.OutputStream

/**
 * L'enregistrement direct d'un export, à côté du partage.
 *
 * **Pourquoi le partage ne suffisait pas.** Tout sortait de SatMe par
 * `ACTION_SEND` : un carnet ADIF, une sauvegarde de configuration, une fiche
 * PDF partaient vers une messagerie ou un nuage. C'est le bon geste quand on
 * envoie quelque chose à quelqu'un, et le mauvais quand on veut simplement
 * poser un fichier dans un dossier du téléphone. Sur le terrain, en portable,
 * sans réseau, le partage n'a souvent rien à proposer — et l'export n'a nulle
 * part où aller.
 *
 * `ACTION_CREATE_DOCUMENT` fait l'autre moitié du travail : l'opérateur choisit
 * l'emplacement et le nom, et le fichier y est écrit. Aucune permission de
 * stockage n'est nécessaire, c'est lui qui désigne la destination.
 *
 * Le partage reste en place partout. Les deux répondent à des questions
 * différentes — « à qui » et « où » — donc aucun ne remplace l'autre.
 */

/** Ce qu'il faut écrire dans le fichier, une fois la destination choisie. */
typealias Deversement = (OutputStream) -> Unit

/**
 * Le contrat, écrit à la main plutôt que `CreateDocument`.
 *
 * `ActivityResultContracts.CreateDocument` fige le type MIME à la
 * construction ; il en faudrait un par format. Ici le type et le nom proposé
 * voyagent avec l'appel, et un seul lanceur sert tous les exports.
 */
private class CreerDocument : ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.second)
            .putExtra(Intent.EXTRA_TITLE, input.first)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}

/**
 * Rend une fonction `(nom, type MIME, déversement)` qui demande où enregistrer
 * puis écrit.
 *
 * Le déversement est retenu entre l'appel et le retour du sélecteur : il y a
 * un aller-retour par une autre activité, et le contenu à écrire doit survivre
 * à la recomposition qui suit.
 */
@Composable
fun rememberEnregistrer(): (String, String, Deversement) -> Unit {
    val ctx = LocalContext.current
    val enAttente = remember { mutableStateOf<Deversement?>(null) }

    val lanceur = rememberLauncherForActivityResult(CreerDocument()) { uri ->
        val quoi = enAttente.value
        enAttente.value = null
        // L'opérateur a refermé le sélecteur : c'est un choix, pas une panne.
        // On ne dit rien — un message ici serait du bruit à chaque hésitation.
        if (uri == null) return@rememberLauncherForActivityResult
        // En revanche, un déversement manquant est une panne franche : sans
        // cette branche, le sélecteur se refermerait sur un fichier vide et
        // personne ne saurait pourquoi.
        if (quoi == null) { avertis(ctx, t("export_saved_fail")); return@rememberLauncherForActivityResult }

        val ecrit = runCatching {
            ctx.contentResolver.openOutputStream(uri, "wt").use { sortie ->
                if (sortie == null) throw java.io.IOException("flux nul")
                quoi(sortie)
                sortie.flush()
            }
        }
        ecrit.fold(
            onSuccess = { avertis(ctx, t("export_saved_ok")) },
            // Un `runCatching` sans branche d'échec transforme une panne dure
            // en panne silencieuse : le carnet n'est pas écrit, et l'opérateur
            // croit l'avoir sauvegardé.
            onFailure = { e ->
                avertis(ctx, "${t("export_saved_fail")} — ${e.message ?: e.javaClass.simpleName}")
            }
        )
    }

    return { nom, mime, quoi ->
        enAttente.value = quoi
        val ouvert = runCatching { lanceur.launch(nom to mime) }
        if (ouvert.isFailure) {
            // Certains téléphones dépouillés n'ont aucun explorateur de
            // fichiers. Le dire vaut mieux qu'un bouton qui ne réagit pas.
            enAttente.value = null
            avertis(ctx, t("export_saved_nopicker"))
        }
    }
}

private fun avertis(ctx: Context, message: String) {
    android.widget.Toast.makeText(ctx, message, android.widget.Toast.LENGTH_SHORT).show()
}

// --- les déversements courants ---

/** Recopie un fichier de l'espace privé de SatMe vers la destination choisie. */
fun depuisFichier(f: java.io.File): Deversement = { sortie -> f.inputStream().use { it.copyTo(sortie) } }

/** Recopie ce que désigne une URI — c'est ce que rendent déjà les exports PDF. */
fun depuisUri(ctx: Context, u: Uri): Deversement = { sortie ->
    val entree = ctx.contentResolver.openInputStream(u)
        ?: throw java.io.IOException("source illisible")
    entree.use { it.copyTo(sortie) }
}

/** Écrit du texte, en UTF-8. */
fun depuisTexte(s: String): Deversement = { sortie -> sortie.write(s.toByteArray(Charsets.UTF_8)) }

/**
 * Un nom de fichier daté : `SatMe-carnet-20260912-1043.adi`.
 *
 * Le sélecteur propose ce nom, et l'opérateur peut le changer. Le dater par
 * défaut évite le travers du « carnet.adi » écrasé chaque semaine — et un
 * export qu'on croit avoir gardé n'existe plus.
 *
 * L'heure est celle du téléphone et non UTC : c'est un nom de fichier qu'on
 * relit dans un explorateur, pas une donnée de trafic.
 */
fun nomDate(base: String, extension: String): String {
    val d = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
        .format(java.util.Date())
    return "$base-$d.$extension"
}
