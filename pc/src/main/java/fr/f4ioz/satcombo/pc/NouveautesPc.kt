/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

/**
 * SatMe-PC's versions as a release names them: "2026.10.27", "2026.10.13.1"
 * (the tag "satme-pc-2026.10.27" without its prefix). A build by hand is
 * "dev": never older nor newer than anything, so it never nags.
 */
object VersionPc {
    const val PREFIXE_TAG = "satme-pc-"
    private val FORME = Regex("\\d{1,6}(\\.\\d{1,6}){1,4}")

    /** Its numbers, or null for "dev" or anything that is not a release's version. */
    fun nombres(v: String?): List<Int>? {
        val t = v?.trim()?.removePrefix(PREFIXE_TAG) ?: return null
        if (!FORME.matches(t)) return null
        return t.split('.').map { it.toInt() }
    }

    fun publiee(v: String?): Boolean = nombres(v) != null

    /** Below 0, 0, above 0 as [a] is older than, the same as, newer than [b] (numbers compared, not text); null when either is not a release. */
    fun compare(a: String?, b: String?): Int? {
        val x = nombres(a) ?: return null
        val y = nombres(b) ?: return null
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    /** [a] is a release newer than [b] (false when either is "dev"). */
    fun plusRecente(a: String?, b: String?): Boolean = (compare(a, b) ?: 0) > 0
}

/**
 * « Quoi de neuf » : the notes written for each version in pc/NOUVEAUTES.md,
 * bundled in the jar at build time. One `## 2026.10.27` section per version
 * (newest first), each with a `### fr` and a `### en` part; `## Prochaine
 * version` holds the notes of the release being prepared (renamed before
 * the tag; until then it stands for a release that has no section of its own).
 */
class NouveautesPc(source: String?) {

    /** One version's notes, in Markdown. */
    data class Notes(val version: String, val fr: String, val en: String) {
        fun texte(anglais: Boolean) = (if (anglais) en else fr).ifBlank { if (anglais) fr else en }
    }

    val notes: List<Notes> = source?.let { lit(it) } ?: emptyList()

    /** The section for [version], or the one being prepared standing for it. */
    fun pour(version: String): Notes? =
        notes.firstOrNull { VersionPc.compare(it.version, version) == 0 }
            ?: notes.firstOrNull { it.version == PROCHAINE }?.takeIf { VersionPc.publiee(version) }?.copy(version = version)

    /**
     * What changed since [vue] (the last version this station started), up to
     * [actuelle], newest first. [vue] unknown (a station older than these
     * notes): the current version's only. Nothing for a build by hand.
     */
    fun depuis(vue: String?, actuelle: String): List<Notes> {
        if (!VersionPc.publiee(actuelle)) return emptyList()
        if (!VersionPc.publiee(vue)) return listOfNotNull(pour(actuelle))
        if (!VersionPc.plusRecente(actuelle, vue)) return emptyList()
        val datees = notes.filter { VersionPc.plusRecente(it.version, vue) && (VersionPc.compare(it.version, actuelle) ?: 1) <= 0 }
        val ici = if (datees.none { VersionPc.compare(it.version, actuelle) == 0 }) listOfNotNull(pour(actuelle)) else emptyList()
        return (ici + datees).sortedWith { a, b -> VersionPc.compare(b.version, a.version) ?: 0 }
    }

    /** Every version's notes, newest first (the help menu); the one being prepared too on a build by hand. */
    fun toutes(actuelle: String): List<Notes> {
        val datees = notes.filter { VersionPc.publiee(it.version) }
        val avant = notes.firstOrNull { it.version == PROCHAINE }?.let { p ->
            if (!VersionPc.publiee(actuelle)) p
            else if (datees.none { VersionPc.compare(it.version, actuelle) == 0 }) p.copy(version = actuelle) else null
        }
        return listOfNotNull(avant) + datees.sortedWith { a, b -> VersionPc.compare(b.version, a.version) ?: 0 }
    }

    companion object {
        const val PROCHAINE = "Prochaine"
        const val RESSOURCE = "/nouveautes/NOUVEAUTES.md"

        /** The notes bundled in the jar (empty when built without them). */
        fun duJar(): NouveautesPc =
            NouveautesPc(NouveautesPc::class.java.getResourceAsStream(RESSOURCE)?.use { it.readBytes().toString(Charsets.UTF_8) })

        /** [src] cut at its `##` titles, each at its `### fr` / `### en`; HTML comments (the writing rules) left out. */
        fun lit(src: String): List<Notes> {
            val texte = src.replace("\r\n", "\n").replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
            val sections = mutableListOf<Pair<String, MutableList<String>>>()
            for (l in texte.split('\n')) {
                if (l.startsWith("## ")) sections.add(l.removePrefix("## ").trim() to mutableListOf())
                else sections.lastOrNull()?.second?.add(l)
            }
            return sections.mapNotNull { (titre, lignes) ->
                val mot = titre.split(' ', '(', '—', '-').firstOrNull { it.isNotBlank() }.orEmpty()
                val version = when {
                    VersionPc.publiee(mot) -> mot
                    titre.startsWith(PROCHAINE, ignoreCase = true) -> PROCHAINE
                    else -> return@mapNotNull null
                }
                val parts = mutableMapOf<String, MutableList<String>>()
                var ici = "*"
                for (l in lignes) {
                    val t = l.trim().lowercase()
                    if (t == "### fr" || t == "### en") ici = t.removePrefix("### ") else parts.getOrPut(ici) { mutableListOf() }.add(l)
                }
                fun part(k: String) = parts[k]?.joinToString("\n")?.trim().orEmpty()
                val commun = part("*")
                // A section still empty (« ## Prochaine version » just after a release) is no notes at all.
                Notes(version, part("fr").ifBlank { commun }, part("en").ifBlank { commun }).takeIf { it.fr.isNotBlank() || it.en.isNotBlank() }
            }
        }
    }
}
