/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.demo

/**
 * The SatMe-PC guide (docs/station-pc.fr.md or docs/station-pc.md) as one HTML
 * page laid out for printing, which a browser prints to the PDF manual
 * (pc's `manuelPdf` task): a cover, a table of contents (with page numbers once
 * known), the introduction, then each chapter on a new page with its title in
 * the running header and « page n / N » at the bottom (CSS page margin boxes
 * and named pages). Chapters cut and rendered as [Manuel] does for F1; the
 * guide's pictures (raw HTML, left out by [Markdown]) are kept as figures with
 * their caption.
 */
object ManuelImprime {

    /** A picture of the guide: its file under docs/ (« images/pc-sstv.webp »), its width (% of the line), its text. */
    data class Image(val src: String, val largeur: Int, val alt: String)

    /** The guide ready to lay out: its title, its introduction (HTML), its chapters, its pictures (by figure). */
    data class Guide(val titre: String, val intro: String, val chapitres: List<Manuel.Chapitre>, val figures: List<List<Image>>)

    private val IMG = Regex("<img\\s+([^>]*?)/?>", RegexOption.IGNORE_CASE)
    private fun attribut(a: String, nom: String) = Regex("\\b$nom\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(a)?.groupValues?.get(1)

    /** Unescapes the few entities the guide's raw HTML may hold. */
    private fun texteHtml(s: String) = s.replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    /**
     * [src] with each raw HTML block of pictures (`<p align="center"><img …></p>`, on one line or several) replaced by
     * a marker paragraph `@@FIGn@@`; the pictures of figure n in the list.
     */
    fun figures(src: String): Pair<String, List<List<Image>>> {
        val l = src.replace("\r\n", "\n").split('\n')
        val sortie = StringBuilder()
        val figs = mutableListOf<List<Image>>()
        var i = 0
        var dansCode = false
        while (i < l.size) {
            val ligne = l[i]
            val t = ligne.trim()
            if (t.startsWith("```")) dansCode = !dansCode
            if (!dansCode && t.startsWith("<") && (t.startsWith("<p") || t.startsWith("<img"))) {
                // The block: up to its « </p> » (or the line alone).
                val bloc = StringBuilder(t)
                if (t.startsWith("<p") && !t.contains("</p>")) {
                    while (i + 1 < l.size) { i++; bloc.append(' ').append(l[i].trim()); if (l[i].contains("</p>")) break }
                }
                val imgs = IMG.findAll(bloc).mapNotNull { m ->
                    val a = m.groupValues[1]
                    val s = attribut(a, "src") ?: return@mapNotNull null
                    Image(s, attribut(a, "width")?.removeSuffix("%")?.trim()?.toIntOrNull()?.coerceIn(10, 100) ?: 100, texteHtml(attribut(a, "alt") ?: ""))
                }.toList()
                if (imgs.isNotEmpty()) {
                    val retrait = ligne.takeWhile { it == ' ' }
                    sortie.append('\n').append(retrait).append("@@FIG").append(figs.size).append("@@\n\n")
                    figs.add(imgs)
                }
                i++
                continue
            }
            sortie.append(ligne).append('\n')
            i++
        }
        return sortie.toString() to figs
    }

    /** The guide cut into its introduction and chapters (the table of contents of the Markdown left out). */
    fun guide(src: String): Guide {
        val (md, figs) = figures(src)
        val tous = Manuel.chapitres(md)
        val intro = tous.firstOrNull { it.id == "presentation" }
        val titre = intro?.titre ?: "SatMe-PC"
        // The introduction without its « # » title (it is on the cover).
        val introHtml = intro?.html?.replace(Regex("<h1[^>]*>.*?</h1>"), "") ?: ""
        return Guide(titre, introHtml, tous.filter { it.id != "presentation" }, figs)
    }

    /** The words of the page, in French or in English. */
    private class Mots(val manuel: String, val sommaire: String, val presentation: String, val version: String, val dev: String,
                       val mois: List<String>, val page: String, val figure: String)

    private val FR = Mots("Manuel de l'utilisateur", "Sommaire", "Présentation", "Version", "version de développement",
        listOf("janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août", "septembre", "octobre", "novembre", "décembre"), "page", "Figure")
    private val EN = Mots("User manual", "Contents", "Introduction", "Version", "development build",
        listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"), "page", "Figure")

    /** « 2026-10-29 » as « 29 octobre 2026 » / « 29 October 2026 »; anything else as it is. */
    fun date(iso: String, langue: String): String {
        val m = Regex("^(\\d{4})-(\\d{2})-(\\d{2})").find(iso) ?: return iso
        val (a, mo, j) = m.destructured
        val mots = if (langue == "en") EN else FR
        return "${j.toInt()} ${mots.mois.getOrElse(mo.toInt() - 1) { mo }} $a"
    }

    /** A CSS string's content: quotes, backslashes and line ends escaped. */
    private fun css(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")

    /** The anchors the table of contents leads to, in order: each chapter, then its sections. */
    fun ancres(g: Guide): List<String> = g.chapitres.flatMap { c -> listOf(c.id) + c.sections.map { it.first } }

    /**
     * The page to print. [pages]: each anchor's page (from a first print), else the numbers are left blank;
     * [images]: where the pictures are, seen from the page (« images/ » beside it); [logo] and [police]: the cover's
     * logo and wordmark font, beside the page.
     */
    fun html(src: String, langue: String, version: String, dateIso: String, pages: Map<String, Int> = emptyMap(),
             images: String = "images/", logo: String = "images/icon.png", police: String? = "polices/Orbitron-Black.ttf"): String {
        val g = guide(src)
        val m = if (langue == "en") EN else FR
        val e = Markdown::esc
        val nomCourt = g.titre.substringBefore(" — ").trim().ifEmpty { "SatMe-PC" }
        val sousTitre = g.titre.substringAfter(" — ", "").trim().replaceFirstChar { it.uppercase() }
        val versionTexte = if (version.isBlank() || version == "dev") m.dev else m.version + " " + version
        val quand = date(dateIso, langue)

        fun figure(n: Int): String {
            val imgs = g.figures.getOrNull(n) ?: return ""
            val total = imgs.sumOf { it.largeur }.coerceAtLeast(1)
            // Side by side as the guide shows them; one alone stays at its width, two or more share the line.
            return "<figure class=\"fig\">" + imgs.joinToString("") { im ->
                val w = if (imgs.size == 1) im.largeur else (im.largeur * 100 / maxOf(total, 100))
                val src = if (im.src.startsWith("images/")) images + im.src.removePrefix("images/") else im.src
                "<div class=\"im\" style=\"width:$w%\"><img src=\"" + e(src) + "\" alt=\"" + e(im.alt) + "\">" +
                    (if (im.alt.isNotBlank()) "<figcaption>" + e(im.alt) + "</figcaption>" else "") + "</div>"
            } + "</figure>"
        }
        fun avecFigures(h: String) = h.replace(Regex("(?:<p>)?@@FIG(\\d+)@@(?:</p>)?")) { figure(it.groupValues[1].toInt()) }
        fun numero(id: String) = pages[id]?.toString() ?: ""

        val b = StringBuilder()
        b.append("<!doctype html>\n<html lang=\"").append(e(langue)).append("\"><head><meta charset=\"utf-8\">")
        b.append("<title>").append(e("$nomCourt — ${m.manuel}")).append("</title>")
        b.append("<meta name=\"author\" content=\"SatMe\"><meta name=\"description\" content=\"").append(e(g.titre)).append("\">")
        b.append("<style>\n")
        if (police != null) b.append("@font-face { font-family: 'SatMe Titre'; src: url('").append(css(police)).append("'); }\n")
        b.append(STYLE)
        // The running header: the chapter's title on each of its pages (one named page per chapter).
        b.append("@page toc { @top-right { content: \"").append(css(m.sommaire)).append("\"; } }\n")
        b.append("@page intro { @top-right { content: \"").append(css(m.presentation)).append("\"; } }\n")
        b.append("@page { @top-left { content: \"").append(css("$nomCourt · ${m.manuel}")).append("\"; } ")
        b.append("@bottom-right { content: \"").append(css(m.page)).append(" \" counter(page) \" / \" counter(pages); } ")
        b.append("@bottom-left { content: \"").append(css(versionTexte)).append("\"; } }\n")
        g.chapitres.forEachIndexed { k, c ->
            b.append("@page c").append(k + 1).append(" { @top-right { content: \"").append(css("${k + 1}. ${c.titre}")).append("\"; } }\n")
            b.append(".c").append(k + 1).append(" { page: c").append(k + 1).append("; }\n")
        }
        b.append("</style></head><body>\n")

        // The cover.
        b.append("<section class=\"couv\"><div class=\"couv-haut\">")
        b.append("<img class=\"logo\" src=\"").append(e(logo)).append("\" alt=\"\">")
        b.append("<div class=\"marque\">").append(e(nomCourt)).append("</div>")
        if (sousTitre.isNotEmpty()) b.append("<div class=\"sous\">").append(e(sousTitre)).append("</div>")
        b.append("<div class=\"filet\"></div><div class=\"genre\">").append(e(m.manuel)).append("</div>")
        b.append("<div class=\"ver\">").append(e(versionTexte)).append(" · ").append(e(quand)).append("</div></div>")
        b.append("<div class=\"couv-bas\">SatMe — github.com/f4ioz/SatMe · GPL-2.0-or-later</div></section>\n")

        // The table of contents.
        b.append("<section class=\"toc\"><h1 class=\"toc-t\">").append(e(m.sommaire)).append("</h1><ol class=\"toc-l\">")
        g.chapitres.forEachIndexed { k, c ->
            b.append("<li class=\"toc-c\"><a href=\"#").append(e(c.id)).append("\"><span class=\"n\">").append(k + 1).append("</span><span class=\"t\">")
                .append(e(c.titre)).append("</span><span class=\"p\">").append(numero(c.id)).append("</span></a>")
            if (c.sections.isNotEmpty()) {
                b.append("<ol class=\"toc-s\">")
                c.sections.forEach { (id, t) ->
                    b.append("<li><a href=\"#").append(e(id)).append("\"><span class=\"t\">").append(e(t)).append("</span><span class=\"p\">").append(numero(id)).append("</span></a></li>")
                }
                b.append("</ol>")
            }
            b.append("</li>")
        }
        b.append("</ol></section>\n")

        // The introduction, then the chapters.
        b.append("<section class=\"intro\"><h1 class=\"intro-t\">").append(e(m.presentation)).append("</h1>").append(avecFigures(g.intro)).append("</section>\n")
        g.chapitres.forEachIndexed { k, c ->
            b.append("<section class=\"ch c").append(k + 1).append("\" data-n=\"").append(k + 1).append("\">").append(avecFigures(c.html).replaceFirst(Regex("(<h2 id=\"[^\"]*\">)"), "$1<span class=\"num\">${k + 1}</span>")).append("</section>\n")
        }
        b.append("</body></html>\n")
        return b.toString()
    }

    /** The pictures the guide shows (their paths under docs/), for copying them beside the page. */
    fun images(src: String): List<String> = figures(src).second.flatten().map { it.src }.distinct()

    private val STYLE = """
@page { size: A4; margin: 20mm 17mm 18mm 17mm;
  @top-left { font: 8.5pt/1 "Noto Sans", "DejaVu Sans", "Liberation Sans", Arial, sans-serif; color: #5b6478; }
  @top-right { font: 600 8.5pt/1 "Noto Sans", "DejaVu Sans", "Liberation Sans", Arial, sans-serif; color: #1d2b5c; }
  @bottom-left { font: 8pt/1 "Noto Sans", "DejaVu Sans", "Liberation Sans", Arial, sans-serif; color: #8a91a3; }
  @bottom-right { font: 8.5pt/1 "Noto Sans", "DejaVu Sans", "Liberation Sans", Arial, sans-serif; color: #5b6478; }
}
@page couv { margin: 0; @top-left { content: none; } @top-right { content: none; } @bottom-left { content: none; } @bottom-right { content: none; } }
html { -webkit-print-color-adjust: exact; print-color-adjust: exact; }
body { margin: 0; font: 9.6pt/1.45 "Noto Sans", "DejaVu Sans", "Liberation Sans", Arial, "Noto Color Emoji", sans-serif; color: #1c2230;
  overflow-wrap: anywhere; hyphens: auto; text-align: left; }
a { color: #2147a8; text-decoration: none; }
b { font-weight: 650; color: #11182a; }
code { font: 8.6pt "DejaVu Sans Mono", "Noto Sans Mono", "Liberation Mono", monospace; background: #eef1f7; border-radius: 3px; padding: 0 2px; overflow-wrap: anywhere; }
pre { background: #f2f4f9; border: 1px solid #dde2ee; border-left: 3px solid #4a6fd1; border-radius: 4px; padding: 6px 9px; white-space: pre-wrap; word-break: break-all; break-inside: avoid; }
pre code { background: none; padding: 0; }
p { margin: 0 0 6pt; orphans: 3; widows: 3; }
ul, ol { margin: 0 0 7pt; padding-left: 16pt; }
li { margin: 0 0 3.5pt; }
li > ul, li > ol { margin-top: 3pt; }
ul > li::marker { color: #4a6fd1; }
blockquote { margin: 6pt 0; padding: 4pt 10pt; border-left: 3px solid #c7cfe3; color: #3a4256; }
table { border-collapse: collapse; width: 100%; margin: 4pt 0 9pt; font-size: 8.8pt; break-inside: auto; }
thead { display: table-header-group; }
tr { break-inside: avoid; }
th { background: #1d2b5c; color: #fff; font-weight: 600; text-align: left; padding: 4pt 6pt; }
th code, td code { white-space: nowrap; overflow-wrap: normal; }
td { border-bottom: 1px solid #dfe3ec; padding: 3.5pt 6pt; vertical-align: top; }
tbody tr:nth-child(even) td { background: #f6f7fb; }
h2, h3, h4 { break-after: avoid; font-family: "Noto Sans", "DejaVu Sans", "Liberation Sans", Arial, sans-serif; color: #1d2b5c; }
h2 { font-size: 19pt; line-height: 1.2; margin: 0 0 12pt; padding-bottom: 6pt; border-bottom: 2px solid #4a6fd1; }
section.ch { break-before: page; }
h2 .num { display: inline-block; min-width: 1.4em; color: #4a6fd1; }
h3 { font-size: 12.5pt; margin: 14pt 0 6pt; padding-left: 7pt; border-left: 3.5px solid #e0a020; }
h4 { font-size: 10.5pt; margin: 10pt 0 4pt; }
hr { border: 0; border-top: 1px solid #dfe3ec; margin: 10pt 0; }
.fig { display: flex; flex-wrap: wrap; justify-content: center; gap: 2%; margin: 8pt 0 10pt; break-inside: avoid; }
.fig .im { display: flex; flex-direction: column; align-items: center; }
.fig img { max-width: 100%; max-height: 115mm; object-fit: contain; border: 1px solid #d5dbe8; border-radius: 4px; box-shadow: 0 1px 3px rgba(20,30,60,.12); }
.fig figcaption { font-size: 7.6pt; line-height: 1.35; color: #5b6478; font-style: italic; margin-top: 3pt; text-align: center; }
/* The cover. */
section.couv { page: couv; height: 297mm; width: 210mm; box-sizing: border-box; display: flex; flex-direction: column; justify-content: space-between;
  background: radial-gradient(ellipse at 50% 38%, #2a3d80 0%, #172352 45%, #0b1230 100%); color: #e8ecf8; padding: 46mm 22mm 16mm; text-align: center; }
.couv-haut { display: flex; flex-direction: column; align-items: center; }
.couv .logo { width: 52mm; height: 52mm; border-radius: 11mm; box-shadow: 0 6px 24px rgba(0,0,0,.45); }
.couv .marque { font: 40pt/1.1 'SatMe Titre', "Noto Sans", sans-serif; letter-spacing: 1.5pt; margin-top: 14mm; color: #fff; }
.couv .sous { font-size: 15pt; margin-top: 5mm; color: #c9d3f2; }
.couv .filet { width: 40mm; height: 2px; background: #e0a020; margin: 12mm auto 9mm; }
.couv .genre { font-size: 19pt; font-weight: 600; color: #fff; letter-spacing: .5pt; }
.couv .ver { font-size: 11pt; margin-top: 5mm; color: #aab6de; }
.couv-bas { font-size: 9pt; color: #8f9bc4; }
/* The table of contents. */
section.toc { page: toc; }
.toc-t, .intro-t { font-size: 22pt; color: #1d2b5c; margin: 0 0 12pt; padding-bottom: 6pt; border-bottom: 2px solid #4a6fd1; }
.toc-l, .toc-s { list-style: none; padding: 0; margin: 0; }
.toc-c { margin: 0 0 3pt; break-inside: avoid-page; }
.toc a { display: flex; align-items: baseline; color: #1c2230; }
.toc a .t { flex: 0 1 auto; }
.toc a::after { content: ""; order: 2; flex: 1 1 auto; border-bottom: 1px dotted #a9b0c2; margin: 0 4pt; transform: translateY(-3pt); min-width: 8pt; }
.toc a .p { order: 3; min-width: 18pt; text-align: right; font-variant-numeric: tabular-nums; }
.toc-c > a { font-weight: 650; font-size: 10.4pt; color: #1d2b5c; padding-top: 3pt; }
.toc-c > a .n { width: 18pt; flex: none; color: #4a6fd1; }
.toc-s { margin: 1pt 0 3pt 18pt; font-size: 8.8pt; }
.toc-s li { margin: 0 0 1pt; }
section.intro { page: intro; break-before: page; }
"""
}
