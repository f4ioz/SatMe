/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site.contenu

import fr.f4ioz.satcombo.demo.Markdown
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/** An article: `contenu/articles/<année>/<slug>.md`, its front matter and its text. */
data class Article(
    val annee: Int,
    val slug: String,
    val titre: String,
    val date: LocalDate,
    val tags: List<String>,
    val resume: String,
    /** A picture for the top of the article and the social networks (a path of the site). */
    val image: String?,
    val brouillon: Boolean,
    val html: String,
    val modifie: Long,
) {
    val url: String get() = "/articles/$annee/$slug"
    fun ms(z: ZoneId): Long = date.atStartOfDay(z).toInstant().toEpochMilli()
}

/** A page of its own (`contenu/pages/<slug>.md`): « À propos », « Contact »… served at `/<slug>`. */
data class PageTexte(val slug: String, val titre: String, val resume: String, val html: String)

/**
 * The articles and the pages, Markdown with a front matter (the old site's
 * format: `title`, `date`, `tags`, `summary`, `hero_image`, `draft`), read
 * from the content folder and read again when a file changes (looked at
 * every 30 s at most). Rendered by SatMe's own Markdown ([Markdown]), which
 * escapes every character and leaves raw HTML out.
 */
class Articles(private val dossier: File, private val horloge: () -> Long = System::currentTimeMillis) {

    @Volatile private var cache: List<Article> = emptyList()
    @Volatile private var pagesCache: Map<String, PageTexte> = emptyMap()
    @Volatile private var signature = ""
    @Volatile private var vu = 0L

    /** Every published article, the newest first (drafts left out). */
    fun tous(): List<Article> { relis(); return cache }

    fun article(annee: Int, slug: String): Article? = tous().firstOrNull { it.annee == annee && it.slug == slug }

    fun page(slug: String): PageTexte? { relis(); return pagesCache[slug] }
    fun pages(): List<PageTexte> { relis(); return pagesCache.values.sortedBy { it.slug } }

    /** The tags and how many articles carry each, the most used first. */
    fun tags(): List<Pair<String, Int>> = tous().flatMap { it.tags }.groupingBy { it }.eachCount().entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { it.key to it.value }

    @Synchronized private fun relis() {
        val t = horloge()
        if (t - vu < 30_000L && vu != 0L) return
        vu = t
        val fichiers = (File(dossier, "articles").walkTopDown().maxDepth(3).filter { it.isFile && it.name.endsWith(".md") }.toList() +
            (File(dossier, "pages").listFiles { f -> f.isFile && f.name.endsWith(".md") }?.toList() ?: emptyList()))
        val sig = fichiers.sortedBy { it.path }.joinToString("|") { it.path + ":" + it.lastModified() + ":" + it.length() }
        if (sig == signature) return
        signature = sig
        cache = fichiers.filter { it.parentFile?.parentFile?.name == "articles" }.mapNotNull { f -> runCatching { lisArticle(f) }.getOrNull() }
            .filter { !it.brouillon }.sortedWith(compareByDescending<Article> { it.date }.thenBy { it.slug })
        pagesCache = fichiers.filter { it.parentFile?.name == "pages" }.mapNotNull { f -> runCatching { lisPage(f) }.getOrNull() }.associateBy { it.slug }
    }

    private fun lisArticle(f: File): Article? {
        val annee = f.parentFile.name.toIntOrNull() ?: return null
        val slug = f.name.removeSuffix(".md")
        if (!SLUG.matches(slug)) return null
        val (fm, corps) = EnTete.decoupe(f.readText(Charsets.UTF_8))
        val date = fm["date"]?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() } ?: LocalDate.of(annee, 1, 1)
        return Article(annee, slug, fm["title"]?.ifBlank { null } ?: slug, date, EnTete.liste(fm["tags"]).map { it.lowercase() }.filter { TAG.matches(it) },
            fm["summary"].orEmpty(), fm["hero_image"]?.takeIf { it.startsWith("/") || it.startsWith("https://") },
            fm["draft"]?.equals("true", true) == true, Markdown.html(corps), f.lastModified())
    }

    private fun lisPage(f: File): PageTexte? {
        val slug = f.name.removeSuffix(".md")
        if (!SLUG.matches(slug)) return null
        val (fm, corps) = EnTete.decoupe(f.readText(Charsets.UTF_8))
        return PageTexte(slug, fm["title"]?.ifBlank { null } ?: slug, fm["summary"].orEmpty(), Markdown.html(corps))
    }

    companion object {
        val SLUG = Regex("^[a-z0-9][a-z0-9-]{0,80}$")
        val TAG = Regex("^[a-z0-9][a-z0-9-_]{0,39}$")
    }
}

/**
 * A front matter: the YAML at the top of a Markdown file between two `---`,
 * as the articles write it — `clé: valeur`, quoted or not, and lists as
 * `[a, b]` or as `- a` lines. Nothing else of YAML is needed.
 */
object EnTete {

    fun decoupe(texte: String): Pair<Map<String, String>, String> {
        val t = texte.removePrefix("﻿").replace("\r\n", "\n")
        if (!t.startsWith("---\n")) return emptyMap<String, String>() to t
        val fin = t.indexOf("\n---", 4)
        if (fin < 0) return emptyMap<String, String>() to t
        val tete = t.substring(4, fin)
        val corps = t.substring(fin + 4).removePrefix("\n")
        val m = LinkedHashMap<String, String>()
        var derniere: String? = null
        for (ligne in tete.split('\n')) {
            if (ligne.isBlank() || ligne.trimStart().startsWith("#")) continue
            val l = ligne.trimEnd()
            if (l.trimStart().startsWith("- ") && derniere != null) {
                val v = valeur(l.trimStart().removePrefix("- "))
                m[derniere] = (m[derniere].orEmpty().let { if (it.isEmpty()) "[" else it.removeSuffix("]") + ", " }) + v + "]"
                continue
            }
            val i = l.indexOf(':')
            if (i <= 0) continue
            val k = l.substring(0, i).trim()
            derniere = k
            m[k] = valeur(l.substring(i + 1).trim())
        }
        return m to corps
    }

    private fun valeur(v: String): String {
        val t = v.trim()
        return when {
            t.length >= 2 && t.startsWith("\"") && t.endsWith("\"") -> t.substring(1, t.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
            t.length >= 2 && t.startsWith("'") && t.endsWith("'") -> t.substring(1, t.length - 1).replace("''", "'")
            else -> t
        }
    }

    /** `[a, b]`, `a, b` → [a, b]. */
    fun liste(v: String?): List<String> = v?.trim()?.removePrefix("[")?.removeSuffix("]")?.split(',')
        ?.map { valeur(it) }?.filter { it.isNotEmpty() } ?: emptyList()
}
