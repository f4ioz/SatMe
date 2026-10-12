/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.data

import android.content.Context
import android.content.SharedPreferences
import fr.f4ioz.satcombo.plateforme.Stockage

/** The phone's preferences file [nom], as a [Stockage]. */
fun stockage(context: Context, nom: String): Stockage =
    StockageAndroid(context.getSharedPreferences(nom, Context.MODE_PRIVATE))

/** The settings on the phone, in the preferences they have always been in. */
fun SettingsStore(context: Context): SettingsStore = SettingsStore(stockage(context, "satcombo_settings"))

/** The sources chosen, in their own preferences file. */
fun SourcesStore(context: Context): SourcesStore = SourcesStore(stockage(context, "satcombo_sources"))

/** [Stockage] over Android's preferences, call for call. */
class StockageAndroid(private val p: SharedPreferences) : Stockage {
    override fun getString(cle: String, defaut: String?): String? = p.getString(cle, defaut)
    override fun getInt(cle: String, defaut: Int): Int = p.getInt(cle, defaut)
    override fun getLong(cle: String, defaut: Long): Long = p.getLong(cle, defaut)
    override fun getFloat(cle: String, defaut: Float): Float = p.getFloat(cle, defaut)
    override fun getBoolean(cle: String, defaut: Boolean): Boolean = p.getBoolean(cle, defaut)
    override fun getStringSet(cle: String, defaut: Set<String>?): Set<String>? = p.getStringSet(cle, defaut)

    override fun edit(): Stockage.Edition = object : Stockage.Edition {
        private val e = p.edit()
        override fun putString(cle: String, valeur: String?) = apply { e.putString(cle, valeur) }
        override fun putInt(cle: String, valeur: Int) = apply { e.putInt(cle, valeur) }
        override fun putLong(cle: String, valeur: Long) = apply { e.putLong(cle, valeur) }
        override fun putFloat(cle: String, valeur: Float) = apply { e.putFloat(cle, valeur) }
        override fun putBoolean(cle: String, valeur: Boolean) = apply { e.putBoolean(cle, valeur) }
        override fun putStringSet(cle: String, valeur: Set<String>?) = apply { e.putStringSet(cle, valeur) }
        override fun remove(cle: String) = apply { e.remove(cle) }
        override fun apply() = e.apply()
    }
}

/** The contact log in the phone's files (`qso_log.json`). */
fun LogStore(context: Context): LogStore = LogStore(context.filesDir.resolve("qso_log.json"))
