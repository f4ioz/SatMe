/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.plateforme

/**
 * Where the settings live: key → value, kept between two starts.
 *
 * Shaped like Android's `SharedPreferences` on purpose: the settings code
 * reads the same on the phone (preferences) and on a PC (a file), and the
 * values already written on a phone are read back unchanged.
 */
interface Stockage {
    fun getString(cle: String, defaut: String?): String?
    fun getInt(cle: String, defaut: Int): Int
    fun getLong(cle: String, defaut: Long): Long
    fun getFloat(cle: String, defaut: Float): Float
    fun getBoolean(cle: String, defaut: Boolean): Boolean
    fun getStringSet(cle: String, defaut: Set<String>?): Set<String>?

    /** A change, applied all at once by [Edition.apply]. */
    fun edit(): Edition

    interface Edition {
        fun putString(cle: String, valeur: String?): Edition
        fun putInt(cle: String, valeur: Int): Edition
        fun putLong(cle: String, valeur: Long): Edition
        fun putFloat(cle: String, valeur: Float): Edition
        fun putBoolean(cle: String, valeur: Boolean): Edition
        fun putStringSet(cle: String, valeur: Set<String>?): Edition
        fun remove(cle: String): Edition
        fun apply()
    }
}

/** Settings kept in memory only: the tests, and a station started without a file. */
class StockageMemoire : Stockage {
    private val valeurs = java.util.concurrent.ConcurrentHashMap<String, Any>()

    override fun getString(cle: String, defaut: String?): String? = valeurs[cle] as? String ?: defaut
    override fun getInt(cle: String, defaut: Int): Int = valeurs[cle] as? Int ?: defaut
    override fun getLong(cle: String, defaut: Long): Long = valeurs[cle] as? Long ?: defaut
    override fun getFloat(cle: String, defaut: Float): Float = valeurs[cle] as? Float ?: defaut
    override fun getBoolean(cle: String, defaut: Boolean): Boolean = valeurs[cle] as? Boolean ?: defaut
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(cle: String, defaut: Set<String>?): Set<String>? =
        (valeurs[cle] as? Set<String>)?.toSet() ?: defaut

    override fun edit(): Stockage.Edition = object : Stockage.Edition {
        private val changes = LinkedHashMap<String, Any?>()
        override fun putString(cle: String, valeur: String?) = apply { changes[cle] = valeur }
        override fun putInt(cle: String, valeur: Int) = apply { changes[cle] = valeur }
        override fun putLong(cle: String, valeur: Long) = apply { changes[cle] = valeur }
        override fun putFloat(cle: String, valeur: Float) = apply { changes[cle] = valeur }
        override fun putBoolean(cle: String, valeur: Boolean) = apply { changes[cle] = valeur }
        override fun putStringSet(cle: String, valeur: Set<String>?) = apply { changes[cle] = valeur?.toSet() }
        override fun remove(cle: String) = apply { changes[cle] = null }
        override fun apply() {
            for ((k, v) in changes) if (v == null) valeurs.remove(k) else valeurs[k] = v
        }
    }
}
