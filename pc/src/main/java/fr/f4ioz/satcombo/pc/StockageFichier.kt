/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.plateforme.Stockage
import java.io.File
import java.util.Properties

/**
 * The settings of the PC station, in a text file (`reglages.properties`),
 * one `key=value` per line, readable and editable by hand when SatMe is
 * stopped.
 *
 * Each value carries its type ("i:12", "b:true", "s:JN18FS"…): the settings
 * code asks for an Int or a String as on the phone, and a value written as
 * one type is never read back as another.
 *
 * Written at once on every change, through a temporary file renamed over the
 * old one: a PC switched off mid-write keeps the previous settings, not half
 * a file.
 */
class StockageFichier(private val fichier: File) : Stockage {
    private val valeurs = java.util.concurrent.ConcurrentHashMap<String, String>()

    init {
        if (fichier.isFile) runCatching {
            val p = Properties()
            fichier.reader(Charsets.UTF_8).use { p.load(it) }
            for (k in p.stringPropertyNames()) valeurs[k] = p.getProperty(k)
        }
    }

    private fun brut(cle: String, type: Char): String? =
        valeurs[cle]?.takeIf { it.length >= 2 && it[0] == type && it[1] == ':' }?.substring(2)

    override fun getString(cle: String, defaut: String?): String? = brut(cle, 's') ?: defaut
    override fun getInt(cle: String, defaut: Int): Int = brut(cle, 'i')?.toIntOrNull() ?: defaut
    override fun getLong(cle: String, defaut: Long): Long = brut(cle, 'l')?.toLongOrNull() ?: defaut
    override fun getFloat(cle: String, defaut: Float): Float = brut(cle, 'f')?.toFloatOrNull() ?: defaut
    override fun getBoolean(cle: String, defaut: Boolean): Boolean = brut(cle, 'b')?.toBooleanStrictOrNull() ?: defaut
    override fun getStringSet(cle: String, defaut: Set<String>?): Set<String>? =
        brut(cle, 'e')?.let { v -> if (v.isEmpty()) emptySet() else v.split('\u001F').toSet() } ?: defaut

    override fun edit(): Stockage.Edition = object : Stockage.Edition {
        private val changes = LinkedHashMap<String, String?>()
        override fun putString(cle: String, valeur: String?) = apply { changes[cle] = valeur?.let { "s:$it" } }
        override fun putInt(cle: String, valeur: Int) = apply { changes[cle] = "i:$valeur" }
        override fun putLong(cle: String, valeur: Long) = apply { changes[cle] = "l:$valeur" }
        override fun putFloat(cle: String, valeur: Float) = apply { changes[cle] = "f:$valeur" }
        override fun putBoolean(cle: String, valeur: Boolean) = apply { changes[cle] = "b:$valeur" }
        override fun putStringSet(cle: String, valeur: Set<String>?) =
            apply { changes[cle] = valeur?.let { "e:" + it.joinToString("\u001F") } }
        override fun remove(cle: String) = apply { changes[cle] = null }
        override fun apply() {
            synchronized(this@StockageFichier) {
                for ((k, v) in changes) if (v == null) valeurs.remove(k) else valeurs[k] = v
                ecris()
            }
        }
    }

    /** Every value as written in the file, its type in front ("s:JN18FS"): for « Déploiement ». */
    fun brutes(): Map<String, String> = HashMap(valeurs)

    /** The settings forced onto the disk now (before the system goes down). */
    fun synchronise() {
        synchronized(this) { runCatching { java.io.FileOutputStream(fichier, true).use { it.fd.sync() } } }
    }

    private fun ecris() {
        runCatching {
            fichier.parentFile?.mkdirs()
            val p = Properties()
            for ((k, v) in valeurs) p.setProperty(k, v)
            val tmp = File(fichier.path + ".tmp")
            tmp.writer(Charsets.UTF_8).use { p.store(it, "SatMe — réglages de la station PC") }
            if (!tmp.renameTo(fichier)) { fichier.delete(); tmp.renameTo(fichier) }
        }
    }
}
