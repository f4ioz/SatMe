/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.demo

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

/**
 * The page's built-in manual (F1): the SatMe-PC guide (docs/station-pc.fr.md,
 * copied into the resources at build time) cut into chapters at its `##`
 * titles, each rendered to HTML by [Markdown], and searched in full text.
 * Served at `api/manuel` by SatMe-PC and the phone alike.
 */
object Manuel {

    /** One chapter: its anchor, its title, its HTML, its sections (`###`) and its plain text for the search. */
    data class Chapitre(val id: String, val titre: String, val html: String, val sections: List<Pair<String, String>>, val texte: String)

    /** One chapter found: how many times the words occur, a few excerpts (plain text). */
    data class Trouve(val id: String, val titre: String, val nombre: Int, val extraits: List<String>)

    /** The guide bundled, or null (a build without it: the page then hides the manual). */
    val SOURCE: String? by lazy { Manuel::class.java.getResourceAsStream("manuel.fr.md")?.use { it.readBytes().toString(Charsets.UTF_8) } }

    val CHAPITRES: List<Chapitre> by lazy { SOURCE?.let { chapitres(it) } ?: emptyList() }

    /** [json] of the guide bundled, made once: what `api/manuel` answers. */
    val REPONSE: ByteArray by lazy { json().toString().toByteArray(Charsets.UTF_8) }

    /**
     * The chapter each tab opens on (a `##` or `###` title's anchor); a tab not here opens
     * on « Les onglets d'un coup d'œil ». Checked against the guide by the tests.
     */
    val ONGLETS: Map<String, String> = mapOf(
        "station" to "station-et-passages",
        "carnet" to "le-carnet",
        "agenda" to "mode-automatique--ne-manquer-aucun-passage",
        "qo100" to "qo-100--le-cockpit",
        "datv" to "datv--réception",
        "montage" to "montage--la-chaîne-de-la-station",
        "sdr" to "le-récepteur-sdr",
        "noeud" to "satme-node--son-sdr-son-émetteur-sa-santé",
        "carte" to "avions-ads-b-et-carte",
        "orbites" to "orbites--la-terre-en-3d",
        "avions" to "avions-ads-b-et-carte",
        "ais" to "navires-ais",
        "espace" to "météo-spatiale",
        "tlm" to "télémesure",
        "son" to "enregistrer-le-son",
        "sstv" to "sstv",
        "planche" to "sstv",
        "ft8" to "ft8--ft4",
        "journal" to "journal-des-passages",
        "reglages" to "réglages",
        "sondes" to "encore-sur-le-pc",
        "meteor" to "encore-sur-le-pc",
    )
    const val PAR_DEFAUT = "les-onglets-dun-coup-dœil"

    /** The guide's own table of contents, left out (the French guide's and the English one's). */
    private val SOMMAIRES = setOf("sommaire", "contents")

    /** [src] cut at its `##` titles; what comes before the first is « Présentation », the table of contents is left out. */
    fun chapitres(src: String): List<Chapitre> {
        val lignes = src.replace("\r\n", "\n").split('\n')
        val morceaux = mutableListOf<Pair<String?, MutableList<String>>>(null to mutableListOf())
        var dansCode = false
        for (l in lignes) {
            if (l.trimStart().startsWith("```")) dansCode = !dansCode
            if (!dansCode && l.startsWith("## ")) morceaux.add(l.removePrefix("## ").trim() to mutableListOf(l))
            else morceaux.last().second.add(l)
        }
        return morceaux.mapNotNull { (titre, corps) ->
            if (titre != null && Markdown.ancre(titre) in SOMMAIRES) return@mapNotNull null
            val md = corps.joinToString("\n")
            val t = titre ?: corps.firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim() ?: "Présentation"
            if (titre == null && md.isBlank()) return@mapNotNull null
            val sections = corps.filter { it.startsWith("### ") }.map { val s = it.removePrefix("### ").trim(); Markdown.ancre(s) to Markdown.texteSimple(s) }
            Chapitre(if (titre == null) "presentation" else Markdown.ancre(titre), Markdown.texteSimple(t), Markdown.html(md), sections, Markdown.texte(md))
        }
    }

    /** The chapters for the page: outline, HTML, and the tab → chapter map. */
    fun json(l: List<Chapitre> = CHAPITRES): JSONObject = JSONObject().put("ok", l.isNotEmpty())
        .put("chapitres", JSONArray(l.map { c ->
            JSONObject().put("id", c.id).put("titre", c.titre).put("html", c.html)
                .put("sections", JSONArray(c.sections.map { (a, t) -> JSONObject().put("id", a).put("titre", t) }))
        }))
        .put("onglets", JSONObject(ONGLETS.filterValues { a -> l.any { it.id == a || it.sections.any { s -> s.first == a } } }).put("*", PAR_DEFAUT))

    /** Lower case, accents off: « Réglages » and « reglages » meet. */
    fun plie(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    /** The words of [q] (two letters or more), folded. */
    fun mots(q: String): List<String> = plie(q).split(Regex("[^\\p{L}\\p{N}._-]+")).map { it.trim('.', '-', '_') }.filter { it.length >= 2 }.distinct()

    /**
     * Full-text search: the chapters holding every word of [q], those with the
     * most occurrences first (a word in the title counts ten), each with up
     * to [extraitsMax] excerpts around its occurrences.
     */
    fun cherche(q: String, l: List<Chapitre> = CHAPITRES, extraitsMax: Int = 3): List<Trouve> {
        val m = mots(q)
        if (m.isEmpty()) return emptyList()
        return l.mapNotNull { c ->
            val p = plie(c.texte)
            val pt = plie(c.titre)
            var n = 0
            val positions = mutableListOf<Int>()
            for (w in m) {
                var k = 0; var ici = 0
                while (true) {
                    val i = p.indexOf(w, ici); if (i < 0) break
                    k++; positions.add(i); ici = i + w.length
                }
                if (k == 0 && !pt.contains(w)) return@mapNotNull null
                n += k
            }
            val poids = n + m.count { pt.contains(it) } * 10
            positions.sort()
            val extraits = mutableListOf<String>()
            var fin = -1
            for (i in positions) {
                if (extraits.size >= extraitsMax) break
                if (i < fin) continue
                val a = maxOf(0, i - 60); val b = minOf(c.texte.length, i + 100)
                // Plain and folded text have the same length (one mark per letter dropped is rare in the guide): cut on spaces.
                val de = if (a == 0) 0 else c.texte.indexOf(' ', a).let { if (it < 0 || it > i) a else it + 1 }
                val au = if (b >= c.texte.length) c.texte.length else c.texte.lastIndexOf(' ', b).let { if (it <= i) b else it }
                extraits.add((if (de > 0) "… " else "") + c.texte.substring(de, au).trim() + (if (au < c.texte.length) " …" else ""))
                fin = au
            }
            Trouve(c.id, c.titre, n, extraits) to poids
        }.sortedByDescending { it.second }.map { it.first }
    }

    fun chercheJson(q: String, l: List<Chapitre> = CHAPITRES): JSONObject {
        val r = cherche(q, l)
        return JSONObject().put("q", q).put("mots", JSONArray(mots(q))).put("total", r.sumOf { it.nombre })
            .put("chapitres", JSONArray(r.map { JSONObject().put("id", it.id).put("titre", it.titre).put("n", it.nombre).put("extraits", JSONArray(it.extraits)) }))
    }
}

/**
 * A small Markdown renderer, for the guide and the F4IOZ site's articles:
 * titles (with GitHub's anchors, so the guide's own links work), paragraphs,
 * bullet and numbered lists (nested by indentation), fenced code, tables,
 * bold, italics, inline code, links, pictures (`![texte](/chemin)`, the
 * site's own or https). Raw HTML lines (the guide's pictures) are left out;
 * every character of the text is escaped.
 */
object Markdown {

    /** Links to the guide's other files lead to them on GitHub. */
    const val DEPOT = "https://github.com/f4ioz/SatMe/blob/main/docs/"

    /** A title's anchor, as GitHub makes it: lower case, punctuation dropped, spaces as hyphens. */
    fun ancre(titre: String): String =
        texteSimple(titre).lowercase().filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' }.replace(' ', '-')

    /** [s] without its inline marks (bold, code, links reduced to their text). */
    fun texteSimple(s: String): String = s
        .replace(Regex("\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")
        .replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
        .replace(Regex("(?<![\\w*])\\*([^*\\s][^*]*)\\*(?![\\w*])"), "$1")
        .replace(Regex("`([^`]*)`"), "$1")

    /** The plain text of [md], for the search: no marks, no HTML, one space between blocks. */
    fun texte(md: String): String = md.split('\n')
        .filterNot { it.trimStart().startsWith("<") || it.trimStart().startsWith("```") || Regex("^\\s*\\|?\\s*:?-{3,}").containsMatchIn(it) }
        .joinToString(" ") { texteSimple(it).replace(Regex("^\\s*(#+|[-*]|\\d+\\.)\\s+"), "").replace('|', ' ') }
        .replace(Regex("\\s+"), " ").trim()

    fun esc(s: String): String {
        val b = StringBuilder(s.length + 8)
        for (c in s) when (c) {
            '&' -> b.append("&amp;"); '<' -> b.append("&lt;"); '>' -> b.append("&gt;"); '"' -> b.append("&quot;"); '\'' -> b.append("&#39;")
            else -> b.append(c)
        }
        return b.toString()
    }

    /** Inline marks of one line of text, escaped. */
    fun enLigne(s: String): String {
        val b = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '`' -> {
                    val j = s.indexOf('`', i + 1)
                    if (j < 0) { b.append(esc(c.toString())); i++ }
                    else { b.append("<code>").append(esc(s.substring(i + 1, j))).append("</code>"); i = j + 1 }
                }
                c == '!' && s.startsWith("![", i) -> {
                    // A picture: its address kept only when it is the site's own (/…) or on the web (https://…).
                    val j = fermant(s, i + 1, '[', ']')
                    val k = if (j > 0 && j + 1 < s.length && s[j + 1] == '(') fermant(s, j + 1, '(', ')') else -1
                    val url = if (k > 0) s.substring(j + 2, k).trim() else ""
                    if (k > 0 && adresseSure(url)) {
                        b.append("<img src=\"").append(esc(url)).append("\" alt=\"").append(esc(texteSimple(s.substring(i + 2, j)))).append("\" loading=\"lazy\">")
                        i = k + 1
                    } else { b.append('!'); i++ }
                }
                c == '[' -> {
                    val j = fermant(s, i, '[', ']')
                    if (j > 0 && j + 1 < s.length && s[j + 1] == '(') {
                        val k = fermant(s, j + 1, '(', ')')
                        if (k > 0) { b.append(lien(s.substring(j + 2, k).trim(), enLigne(s.substring(i + 1, j)))); i = k + 1; continue }
                    }
                    b.append('['); i++
                }
                c == '*' && s.startsWith("**", i) -> {
                    val j = s.indexOf("**", i + 2)
                    if (j > i + 2) { b.append("<b>").append(enLigne(s.substring(i + 2, j))).append("</b>"); i = j + 2 }
                    else { b.append("**"); i += 2 }
                }
                (c == '*' || c == '_') && ouvreItalique(s, i) -> {
                    val j = fermeItalique(s, i + 1, c)
                    if (j > 0) { b.append("<i>").append(enLigne(s.substring(i + 1, j))).append("</i>"); i = j + 1 }
                    else { b.append(c); i++ }
                }
                else -> { b.append(esc(c.toString())); i++ }
            }
        }
        return b.toString()
    }

    private fun fermant(s: String, i: Int, o: Char, f: Char): Int {
        var n = 0
        for (k in i until s.length) { if (s[k] == o) n++ else if (s[k] == f && --n == 0) return k }
        return -1
    }

    /** An italic mark opens: not inside a word, followed by a letter. */
    private fun ouvreItalique(s: String, i: Int) =
        (i == 0 || !s[i - 1].isLetterOrDigit()) && i + 1 < s.length && !s[i + 1].isWhitespace() && s[i + 1] != s[i]

    private fun fermeItalique(s: String, de: Int, c: Char): Int {
        var k = de
        while (k < s.length) {
            val j = s.indexOf(c, k); if (j < 0) return -1
            if (!s[j - 1].isWhitespace() && (j + 1 >= s.length || !s[j + 1].isLetterOrDigit())) return j
            k = j + 1
        }
        return -1
    }

    private fun lien(url: String, texte: String): String = when {
        url.startsWith("#") -> "<a href=\"" + esc(url) + "\" data-ancre=\"" + esc(url.substring(1)) + "\">" + texte + "</a>"
        url.startsWith("http://") || url.startsWith("https://") -> "<a href=\"" + esc(url) + "\" target=\"_blank\" rel=\"noopener\">" + texte + "</a>"
        Regex("^[\\w.-]+\\.md(#.*)?$").matches(url) -> "<a href=\"" + esc(DEPOT + url) + "\" target=\"_blank\" rel=\"noopener\">" + texte + "</a>"
        // A page of the same site (the F4IOZ site's articles): « /articles/… ».
        url.startsWith("/") && !url.startsWith("//") && adresseSure(url) -> "<a href=\"" + esc(url) + "\">" + texte + "</a>"
        else -> texte
    }

    /** An address that can be written in a page as it is: the site's own path, or https/http; no quote, space or backslash. */
    private fun adresseSure(url: String): Boolean =
        (url.startsWith("https://") || url.startsWith("http://") || (url.startsWith("/") && !url.startsWith("//"))) &&
            url.none { it.isWhitespace() || it == '"' || it == '\'' || it == '\\' || it == '<' || it == '>' }

    private fun cellules(l: String): List<String> {
        var t = l.trim()
        if (t.startsWith("|")) t = t.substring(1)
        if (t.endsWith("|") && !t.endsWith("\\|")) t = t.dropLast(1)
        // A « | » inside inline code stays in its cell.
        val r = mutableListOf<String>(); val b = StringBuilder(); var code = false
        for (c in t) { if (c == '`') code = !code; if (c == '|' && !code) { r.add(b.toString().trim()); b.clear() } else b.append(c) }
        r.add(b.toString().trim())
        return r
    }

    private val SEPARATEUR = Regex("^\\s*\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)*\\|?\\s*$")
    private val PUCE = Regex("^(\\s*)([-*+]|\\d+[.)])\\s+(.*)$")

    /** [md] as HTML. */
    fun html(md: String): String {
        val l = md.replace("\r\n", "\n").split('\n')
        val o = StringBuilder()
        val para = mutableListOf<String>()
        // Open lists: their indentation and kind (ul / ol).
        val listes = ArrayDeque<Pair<Int, String>>()
        var liOuvert = false
        fun finPara() {
            if (para.isEmpty()) return
            val t = para.joinToString(" ") { it.trim() }
            if (liOuvert) o.append(enLigne(t)) else o.append("<p>").append(enLigne(t)).append("</p>")
            para.clear()
        }
        fun fermeListes(jusqua: Int = -1) {
            while (listes.isNotEmpty() && listes.last().first > jusqua) {
                o.append("</li></").append(listes.removeLast().second).append('>')
            }
            liOuvert = listes.isNotEmpty()
        }
        var i = 0
        while (i < l.size) {
            val ligne = l[i]
            val t = ligne.trim()
            val retrait = ligne.length - ligne.trimStart().length
            when {
                t.startsWith("```") -> {
                    finPara()
                    if (listes.isNotEmpty() && retrait < 2) fermeListes()
                    val code = StringBuilder()
                    i++
                    while (i < l.size && !l[i].trim().startsWith("```")) {
                        code.append(l[i].let { if (it.length >= retrait && it.substring(0, retrait).isBlank()) it.substring(retrait) else it.trimStart() }).append('\n'); i++
                    }
                    o.append("<pre><code>").append(esc(code.toString().trimEnd('\n'))).append("</code></pre>")
                }
                t.isEmpty() -> { finPara() }
                t.startsWith("<") -> { finPara() }
                t.startsWith("#") && Regex("^#{1,6}\\s").containsMatchIn(t) -> {
                    finPara(); fermeListes()
                    val n = t.takeWhile { it == '#' }.length
                    val titre = t.substring(n).trim()
                    o.append("<h").append(n).append(" id=\"").append(esc(ancre(titre))).append("\">").append(enLigne(titre)).append("</h").append(n).append('>')
                }
                t.startsWith("|") && i + 1 < l.size && SEPARATEUR.matches(l[i + 1]) -> {
                    finPara(); fermeListes()
                    val tete = cellules(t)
                    val alignes = cellules(l[i + 1]).map { c -> if (c.endsWith(":")) if (c.startsWith(":")) "center" else "right" else "" }
                    fun td(tag: String, c: String, k: Int) = "<$tag" + (alignes.getOrNull(k)?.takeIf { it.isNotEmpty() }?.let { " style=\"text-align:$it\"" } ?: "") + ">" + enLigne(c) + "</$tag>"
                    o.append("<table><thead><tr>").append(tete.mapIndexed { k, c -> td("th", c, k) }.joinToString("")).append("</tr></thead><tbody>")
                    i += 2
                    while (i < l.size && l[i].trim().startsWith("|")) {
                        o.append("<tr>").append(cellules(l[i]).mapIndexed { k, c -> td("td", c, k) }.joinToString("")).append("</tr>"); i++
                    }
                    o.append("</tbody></table>")
                    continue
                }
                t == "---" || t == "***" -> { finPara(); fermeListes(); o.append("<hr>") }
                t.startsWith(">") -> {
                    finPara(); fermeListes()
                    val q = mutableListOf<String>()
                    while (i < l.size && l[i].trim().startsWith(">")) { q.add(l[i].trim().removePrefix(">").trim()); i++ }
                    o.append("<blockquote>").append(enLigne(q.joinToString(" "))).append("</blockquote>")
                    continue
                }
                PUCE.matches(ligne) -> {
                    finPara()
                    val m = PUCE.find(ligne)!!
                    val ind = m.groupValues[1].length
                    val genre = if (m.groupValues[2][0].isDigit()) "ol" else "ul"
                    if (listes.isNotEmpty() && ind < listes.last().first) fermeListes(ind)
                    if (listes.isNotEmpty() && listes.last().first == ind && listes.last().second != genre) fermeListes(ind - 1)
                    if (listes.isEmpty() || ind > listes.last().first) {
                        o.append('<').append(genre).append("><li>"); listes.addLast(ind to genre)
                    } else o.append("</li><li>")
                    liOuvert = true
                    para.add(m.groupValues[3])
                }
                else -> {
                    // A line under a list item, indented: still the item's; otherwise the list ends.
                    if (listes.isNotEmpty() && retrait == 0 && para.isEmpty()) fermeListes()
                    para.add(t)
                }
            }
            i++
        }
        finPara(); fermeListes()
        return o.toString()
    }
}
