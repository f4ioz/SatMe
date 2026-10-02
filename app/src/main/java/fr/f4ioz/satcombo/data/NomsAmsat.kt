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
import java.io.File

/**
 * Catalogue number → AMSAT name ("AO-07", "ISS"). AMSAT's status reports go
 * by that name, while CelesTrak says "OSCAR 7" and SatNOGS "ISS (ZARYA)": with
 * any source but AMSAT's, the status matched nothing. Taken from AMSAT's
 * bulletin (through the SatMe GP server when one is set), kept on disk,
 * refreshed once a week.
 */
class NomsAmsat(context: Context) {
    private val fichier = File(context.filesDir, "noms_amsat.tsv")

    fun charge(): Map<Int, String> = runCatching {
        fichier.readLines().mapNotNull { l ->
            val f = l.split('\t')
            if (f.size == 2) f[0].toIntOrNull()?.let { it to f[1] } else null
        }.toMap()
    }.getOrDefault(emptyMap())

    /** Less than a week old. */
    fun aJour(): Boolean = fichier.exists() && System.currentTimeMillis() - fichier.lastModified() < 7 * 86_400_000L

    /** Downloads the table again; the old one stays when that fails. */
    suspend fun rafraichit(serveur: String, seul: Boolean): Map<Int, String> {
        val groupes = Sources.aTelecharger(setOf("amsat_gp")).map { ServeurGp.adresses(serveur, it, seul) }
        // Its own repository: the main one keeps the sources screen's discarded list.
        val lus = runCatching { TleRepository().fetchGroupesSecours(groupes) }.getOrDefault(emptyList())
        val table = table(lus)
        if (table.isEmpty()) return charge()
        runCatching { fichier.writeText(table.entries.joinToString("\n") { "${it.key}\t${it.value}" }) }
        return table
    }

    companion object {
        /** Names read from AMSAT's bulletin, by catalogue number. */
        fun table(lus: List<TleEntry>): Map<Int, String> =
            lus.filter { it.name.isNotBlank() && it.catalogNumber > 0 }.associate { it.catalogNumber to it.name.trim() }
    }
}
