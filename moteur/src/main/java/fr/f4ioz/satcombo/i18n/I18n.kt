/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.i18n

/**
 * Lightweight in-app localization. Kotlin-based (not res/values) so that:
 *  - interpolated strings ("T-${countdown}") stay type-safe,
 *  - the language switches live without recreating the Activity,
 *  - it mirrors the dynamic theme approach already used in the app.
 *
 * Language resolution: "auto" follows the phone locale; "fr"/"en" force it.
 *
 * The language switches live: every read calls [surLecture] and every change
 * [surChangement]. The phone ties both to a Compose state, so a screen reading
 * [t] / [tf] is redrawn when the language changes; elsewhere they do nothing.
 */
enum class Lang { FR, EN }

object I18n {
    @Volatile private var active = Lang.FR

    /** Called on every read of the language (see the class comment). */
    @Volatile var surLecture: () -> Unit = {}

    /** Called when the language changes. */
    @Volatile var surChangement: () -> Unit = {}

    /** Apply a language preference: "auto", "fr" or "en". */
    fun apply(pref: String, phoneLang: String) {
        active = when (pref) {
            "fr" -> Lang.FR
            "en" -> Lang.EN
            else -> if (phoneLang.lowercase().startsWith("fr")) Lang.FR else Lang.EN
        }
        surChangement()
    }

    fun current(): Lang { surLecture(); return active }

    /** Java locale matching the active app language, for date/number formatting. */
    fun locale(): java.util.Locale =
        if (current() == Lang.EN) java.util.Locale.ENGLISH else java.util.Locale.FRENCH

    /** Translate a key. Falls back to the French value, then the key itself. */
    fun t(key: String): String {
        val table = if (current() == Lang.EN) EN else FR
        return table[key] ?: FR[key] ?: key
    }

    /**
     * Both placeholder styles are accepted: {0} and %s.
     *
     * Some keys use %s/%d and were once shown raw on screen (the agenda "%
     * bug"). So {n} is substituted first, then String.format runs if a printf
     * pattern remains. The runCatching is required: some keys contain a
     * literal % (doubled or not) that would make format throw.
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
