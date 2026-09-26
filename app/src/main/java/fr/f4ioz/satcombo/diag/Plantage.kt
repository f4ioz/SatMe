/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.diag

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * The story of the app's last death.
 *
 * Play Console tells nothing about a crash on a tester's phone: crash reports
 * need an install volume a three-person closed test never reaches, and only
 * count users who opted into diagnostics sharing. The pre-launch report tests
 * other devices. So when the app dies on a tester's phone, nobody knows.
 *
 * Hence this: when the process falls, the trace is written to app storage,
 * then Android finishes its job ("SatMe has stopped" still shows). On the next
 * launch we offer to email the file. No server, no third-party library, no
 * extra permission: a text file and a `mailto:` intent.
 *
 * This object knows nothing of Android: text formatting only, so it runs in
 * plain unit tests.
 */
object Plantage {

    /** The file lives in `filesDir`, deleted with the app. */
    const val NOM = "dernier-plantage.txt"

    const val DESTINATAIRE = "mail@f4ioz.fr"
    const val SUJET = "SatMe — rapport de plantage"

    /**
     * Intent extras go through a Binder buffer of a few hundred KB, shared
     * with everything else. A deep stack with chained causes quickly exceeds
     * it; past this limit we cut the end, since the first lines name the fault.
     */
    const val MAX = 12_000

    /** The full stack trace, causes included, as the JVM prints it. */
    fun trace(t: Throwable): String {
        val w = StringWriter()
        PrintWriter(w).use { t.printStackTrace(it) }
        return w.toString().trimEnd()
    }

    /**
     * Cuts the end, and says so. A silently truncated trace reads as complete,
     * and one then hunts for a root cause that was simply thrown away.
     */
    fun tronque(s: String, max: Int = MAX): String =
        if (s.length <= max) s
        else s.take(max) + "\n\n[…] rapport coupé à $max caractères sur ${s.length}."

    /**
     * The header matters as much as the stack. The modules line is what tells
     * a bug in our code from an install missing a split.
     */
    fun redige(
        t: Throwable,
        version: String,
        code: Int,
        appareil: String,
        androidVersion: String,
        modules: String,
        horodatage: String,
        fil: String = "?"
    ): String = tronque(
        buildString {
            appendLine("SatMe $version ($code)")
            appendLine("Appareil : $appareil")
            appendLine("Android : $androidVersion")
            appendLine("Modules : $modules")
            appendLine("Fil : $fil")
            appendLine("Quand : $horodatage")
            appendLine()
            append(trace(t))
        })

    /**
     * The email body. It asks the tester for one sentence: the trace says what
     * broke, never what they were doing.
     */
    fun corpsDuMail(rapport: String): String = buildString {
        appendLine("Bonjour,")
        appendLine()
        appendLine("SatMe s'est arrêté. Voici ce que l'application a noté.")
        appendLine()
        appendLine("Ce que je faisais à ce moment-là :")
        appendLine()
        appendLine()
        appendLine("----------------------------------------")
        append(rapport)
    }

    /**
     * The modules line, from what Android declares. A split APK (language,
     * density, ABI) can lose a split on some vendors' devices, and the app then
     * dies before its first line of code. Knowing there were none rules this
     * out at once.
     */
    fun modules(noms: Array<String?>?): String {
        val propres = noms?.filterNotNull()?.filter { it.isNotBlank() } ?: emptyList()
        return if (propres.isEmpty()) "aucun" else propres.joinToString(", ")
    }
}

/**
 * The report on disk. Kept apart from formatting so it can be tested with a
 * temp directory, without Android.
 *
 * Every operation swallows its failures: they run in a dying process, and an
 * exception here would replace the fault to explain with a fault in the
 * explanation.
 */
object PlantageDisque {

    private fun fichier(dossier: File) = File(dossier, Plantage.NOM)

    fun ecrit(dossier: File, texte: String): Boolean = runCatching {
        if (!dossier.exists()) dossier.mkdirs()
        fichier(dossier).writeText(texte)
        true
    }.getOrDefault(false)

    /** The report, or `null` when absent or empty. */
    fun lit(dossier: File): String? = runCatching {
        val f = fichier(dossier)
        if (!f.isFile) return null
        f.readText().ifBlank { null }
    }.getOrNull()

    fun efface(dossier: File): Boolean = runCatching {
        val f = fichier(dossier)
        !f.exists() || f.delete()
    }.getOrDefault(false)
}
