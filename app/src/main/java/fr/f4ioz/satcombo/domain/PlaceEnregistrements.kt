/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

/**
 * The room the recordings and pictures take, and the recordings worth
 * offering to delete: older than [AGE_MS], used by no pass of the journal,
 * not the one being written. Pictures are counted, never offered: they are
 * the gallery.
 */
object PlaceEnregistrements {
    const val AGE_MS = 30L * 24 * 3_600_000L

    data class Fichier(val nom: String, val octets: Long, val modifieMs: Long)

    data class Bilan(
        val enregistrements: Int, val octetsEnregistrements: Long,
        val images: Int, val octetsImages: Long,
        /** Offered: MP3 names (their ".info" go with them). */
        val vieux: List<String>, val octetsVieux: Long
    )

    /**
     * [sons]: the recordings folder (MP3 and their ".info"); [images]: the
     * SSTV folder; [utilises]: the MP3 a pass of the journal names or finds;
     * [enCours]: the one being recorded.
     */
    fun bilan(sons: List<Fichier>, images: List<Fichier>, utilises: Set<String>, enCours: String?, maintenantMs: Long): Bilan {
        val mp3 = sons.filter { it.nom.endsWith(".mp3") }
        val infos = sons.filter { it.nom.endsWith(".info") }.associateBy { it.nom.removeSuffix(".info") }
        val vieux = mp3.filter { it.nom !in utilises && it.nom != enCours && maintenantMs - it.modifieMs > AGE_MS }
        val png = images.filter { it.nom.endsWith(".png") }
        return Bilan(
            mp3.size, sons.sumOf { it.octets },
            png.size, images.sumOf { it.octets },
            vieux.map { it.nom }.sortedBy { it },
            vieux.sumOf { it.octets + (infos[it.nom.removeSuffix(".mp3")]?.octets ?: 0L) })
    }

    /** "12.3 MB" / "850 kB"; [unites]: kB, MB, GB in the language shown. */
    fun taille(octets: Long, unites: List<String> = listOf("kB", "MB", "GB")): String = when {
        octets >= 1_073_741_824L -> "%.1f %s".format(octets / 1_073_741_824.0, unites[2])
        octets >= 1_048_576L -> "%.1f %s".format(octets / 1_048_576.0, unites[1])
        else -> "%d %s".format((octets + 1023) / 1024, unites[0])
    }
}
