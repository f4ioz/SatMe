/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.data

import android.content.Context

/** Simple persistent favorites (NORAD catalog numbers) via SharedPreferences. */
class FavoritesStore(context: Context) {
    private val prefs = context.getSharedPreferences("satcombo_favorites", Context.MODE_PRIVATE)
    private val key = "fav_catnums"

    fun load(): Set<Int> =
        prefs.getStringSet(key, emptySet()).orEmpty()
            .mapNotNull { it.toIntOrNull() }.toSet()

    fun save(catnums: Set<Int>) {
        prefs.edit().putStringSet(key, catnums.map { it.toString() }.toSet()).apply()
    }

    fun toggle(catnum: Int): Set<Int> {
        val cur = load().toMutableSet()
        if (!cur.add(catnum)) cur.remove(catnum)
        save(cur)
        return cur
    }
}
