/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
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
 * Saving an export straight to a file, alongside sharing.
 *
 * `ACTION_SEND` answers "to whom"; in the field, offline, it often has nothing
 * to offer and the export goes nowhere. `ACTION_CREATE_DOCUMENT` answers
 * "where": the operator picks the location and name, so no storage permission
 * is needed. Neither replaces the other; both stay.
 */

/** What to write into the file once the destination is chosen. */
typealias Deversement = (OutputStream) -> Unit

/**
 * Hand-written contract instead of `CreateDocument`, which fixes the MIME type
 * at construction. Here type and suggested name travel with the call, so one
 * launcher serves every export.
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
 * Returns a `(name, MIME type, writer)` function that asks where to save, then
 * writes. The writer is kept in state because the round trip through another
 * activity is followed by a recomposition.
 */
@Composable
fun rememberEnregistrer(): (String, String, Deversement) -> Unit {
    val ctx = LocalContext.current
    val enAttente = remember { mutableStateOf<Deversement?>(null) }

    val lanceur = rememberLauncherForActivityResult(CreerDocument()) { uri ->
        val quoi = enAttente.value
        enAttente.value = null
        // Picker closed by the operator: a choice, not a failure. Stay silent.
        if (uri == null) return@rememberLauncherForActivityResult
        // A missing writer is a real failure: without this branch the picker
        // would close on an empty file and nobody would know why.
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
            // Without a failure branch, `runCatching` turns a hard failure into
            // a silent one: the log is not written but looks saved.
            onFailure = { e ->
                avertis(ctx, "${t("export_saved_fail")} — ${e.message ?: e.javaClass.simpleName}")
            }
        )
    }

    return { nom, mime, quoi ->
        enAttente.value = quoi
        val ouvert = runCatching { lanceur.launch(nom to mime) }
        if (ouvert.isFailure) {
            // Some stripped-down phones have no file picker at all. Say so
            // rather than leave a dead button.
            enAttente.value = null
            avertis(ctx, t("export_saved_nopicker"))
        }
    }
}

private fun avertis(ctx: Context, message: String) {
    android.widget.Toast.makeText(ctx, message, android.widget.Toast.LENGTH_SHORT).show()
}

// --- common writers ---

/** Copies a file from SatMe's private storage to the chosen destination. */
fun depuisFichier(f: java.io.File): Deversement = { sortie -> f.inputStream().use { it.copyTo(sortie) } }

/** Copies what a URI points to — what the PDF exports already return. */
fun depuisUri(ctx: Context, u: Uri): Deversement = { sortie ->
    val entree = ctx.contentResolver.openInputStream(u)
        ?: throw java.io.IOException("source illisible")
    entree.use { it.copyTo(sortie) }
}

/** Writes text as UTF-8. */
fun depuisTexte(s: String): Deversement = { sortie -> sortie.write(s.toByteArray(Charsets.UTF_8)) }

/**
 * A dated file name: `SatMe-carnet-20260912-1043.adi`.
 *
 * Dating by default avoids a "carnet.adi" overwritten every week. Local phone
 * time, not UTC: it is a file name read in a file browser, not traffic data.
 */
fun nomDate(base: String, extension: String): String {
    val d = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
        .format(java.util.Date())
    return "$base-$d.$extension"
}
