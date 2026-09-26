/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.i18n

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Lightweight in-app localization. Kotlin-based (not res/values) so that:
 *  - interpolated strings ("T-${countdown}") stay type-safe,
 *  - the language switches live without recreating the Activity,
 *  - it mirrors the dynamic theme approach already used in the app.
 *
 * Language resolution: "auto" follows the phone locale; "fr"/"en" force it.
 * Reading [t] / [tf] inside a @Composable recomposes on change because the
 * active language is backed by mutableStateOf.
 */
enum class Lang { FR, EN }

object I18n {
    private var active by mutableStateOf(Lang.FR)

    /** Apply a language preference: "auto", "fr" or "en". */
    fun apply(pref: String, phoneLang: String) {
        active = when (pref) {
            "fr" -> Lang.FR
            "en" -> Lang.EN
            else -> if (phoneLang.lowercase().startsWith("fr")) Lang.FR else Lang.EN
        }
    }

    fun current(): Lang = active

    /** Java locale matching the active app language, for date/number formatting. */
    fun locale(): java.util.Locale =
        if (active == Lang.EN) java.util.Locale.ENGLISH else java.util.Locale.FRENCH

    /** Translate a key. Falls back to the French value, then the key itself. */
    fun t(key: String): String {
        val table = if (active == Lang.EN) EN else FR
        return table[key] ?: FR[key] ?: key
    }

    /**
     * Les deux écritures de trous acceptées : {0} et %s.
     *
     * Historiquement la table n'utilisait que {0}, {1}… puis des clés en %s/%d
     * s'y sont glissées et s'affichaient telles quelles à l'écran — le « bug
     * des % » vu dans l'agenda. Plutôt que de traquer chaque clé, on accepte
     * les deux : substitution des {n} d'abord, puis String.format si un motif
     * printf subsiste. Le runCatching est indispensable, certaines clés
     * contiennent un % littéral (doublé ou non) qui ferait lever le format.
     */
    private val PRINTF = Regex("%[-#+ 0,(]*\\d*(?:\\.\\d+)?[sSdfxX]")

    /** Translate with positional args: t("pass_in", "3 min") replaces {0} or %s. */
    fun tf(key: String, vararg args: Any?): String {
        var s = t(key)
        args.forEachIndexed { i, a -> s = s.replace("{$i}", a?.toString() ?: "") }
        if (args.isNotEmpty() && PRINTF.containsMatchIn(s))
            s = runCatching { String.format(locale(), s, *args) }.getOrDefault(s)
        return s
    }
}

/** Convenience top-level helpers. */
fun t(key: String): String = I18n.t(key)
fun tf(key: String, vararg args: Any?): String = I18n.tf(key, *args)
