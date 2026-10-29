/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc.manuel

import fr.f4ioz.satcombo.demo.ManuelImprime
import org.apache.pdfbox.Loader
import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import java.io.File
import java.net.URLDecoder
import java.nio.file.Files
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * The PDF manuals (`./gradlew :pc:manuelPdf`, and each release): the guide laid out by [ManuelImprime], printed by a
 * headless Chrome or Chromium (its page margin boxes make the headers and footers), twice — the first print tells
 * on which page each chapter and section starts (the PDF's named destinations, read with PDFBox), the second
 * writes those numbers in the table of contents.
 *
 * Arguments: the docs folder, the output folder, the version, then optionally the languages (« fr,en »).
 * The browser: `CHROME` in the environment, else google-chrome, chromium, chromium-browser on the PATH.
 * A snap Chromium only reads and writes under ~/snap/chromium/common: the work is done there, then copied out.
 * Writes SatMe-manuel-<langue>.pdf and its .html; exit code 2 when no browser is found, 1 when printing fails.
 */
fun main(args: Array<String>) {
    if (args.size < 3) { System.err.println("ManuelPdf <docs> <sortie> <version> [fr,en]"); exitProcess(64) }
    val docs = File(args[0]); val sortie = File(args[1]); val version = args[2]
    val langues = args.getOrNull(3)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: listOf("fr", "en")
    val date = System.getenv("SATME_MANUEL_DATE") ?: LocalDate.now().toString()
    sortie.mkdirs()
    val chrome = ManuelPdf.navigateur()
    for (l in langues) {
        val src = File(docs, if (l == "fr") "station-pc.fr.md" else "station-pc.md").readText()
        File(sortie, "SatMe-manuel-$l.html").writeText(ManuelImprime.html(src, l, version, date))
        if (chrome == null) continue
        try {
            val pdf = ManuelPdf.imprime(src, l, version, date, docs, chrome)
            pdf.copyTo(File(sortie, "SatMe-manuel-$l.pdf"), overwrite = true)
            println("SatMe-manuel-$l.pdf : " + File(sortie, "SatMe-manuel-$l.pdf").length() / 1024 + " Ko")
        } catch (e: Exception) {
            System.err.println("Manuel $l : ${e.message}")
            exitProcess(1)
        }
    }
    if (chrome == null) { System.err.println("Pas de Chrome ni de Chromium : seulement le HTML (CHROME=… pour en donner un)"); exitProcess(2) }
}

object ManuelPdf {

    /** The browser to print with, or null. */
    fun navigateur(): String? {
        System.getenv("CHROME")?.takeIf { it.isNotBlank() }?.let { return it }
        val chemin = System.getenv("PATH")?.split(File.pathSeparator) ?: emptyList()
        for (nom in listOf("google-chrome", "google-chrome-stable", "chromium", "chromium-browser")) {
            chemin.map { File(it, nom) }.firstOrNull { it.canExecute() }?.let { return it.path }
        }
        return null
    }

    /** A snap's browser: it can only read and write under ~/snap/<name>/common. */
    private fun snap(chrome: String): File? {
        val vrai = runCatching { File(chrome).canonicalPath }.getOrDefault(chrome)
        if (!chrome.startsWith("/snap/") && !vrai.endsWith("/usr/bin/snap") && !vrai.startsWith("/snap/")) return null
        return File(System.getProperty("user.home"), "snap/" + File(chrome).name.removeSuffix("-browser") + "/common")
    }

    /** The guide [src] as a PDF (a file in a work folder, deleted at the JVM's exit). */
    fun imprime(src: String, langue: String, version: String, date: String, docs: File, chrome: String): File {
        val base = snap(chrome)?.also { it.mkdirs() }
        val travail = (if (base != null) Files.createTempDirectory(base.toPath(), "satme-manuel-") else Files.createTempDirectory("satme-manuel-")).toFile()
        Runtime.getRuntime().addShutdownHook(Thread { travail.deleteRecursively() })
        // The pictures, the logo and the cover's font beside the page.
        val img = File(travail, "images").apply { mkdirs() }
        (ManuelImprime.images(src).filter { it.startsWith("images/") }.map { it.removePrefix("images/") } + "icon.png").distinct().forEach { n ->
            val f = File(docs, "images/$n"); if (f.isFile) f.copyTo(File(img, n), overwrite = true)
        }
        ManuelImprime::class.java.getResourceAsStream("/fr/f4ioz/satcombo/sstv/polices/Orbitron-Black.ttf")?.use { i ->
            File(travail, "polices").mkdirs(); File(travail, "polices/Orbitron-Black.ttf").outputStream().use { i.copyTo(it) }
        }
        val page = File(travail, "manuel-$langue.html")
        val pdf = File(travail, "manuel-$langue.pdf")
        var pages: Map<String, Int> = emptyMap()
        // First without the numbers, then with them until they hold (the contents keep their height: once is enough).
        for (essai in 0 until 3) {
            page.writeText(ManuelImprime.html(src, langue, version, date, pages))
            lance(chrome, travail, page, pdf)
            val lues = destinations(pdf)
            val attendues = ManuelImprime.ancres(ManuelImprime.guide(src))
            val manquent = attendues.filter { it !in lues }
            if (manquent.isNotEmpty()) System.err.println("Manuel $langue : sans page dans le PDF : " + manquent.take(5).joinToString())
            if (lues == pages || lues.isEmpty()) break
            pages = lues
        }
        return finit(pdf, langue, version)
    }

    /** The largest side a picture keeps: the page's text is 176 mm wide, ≈ 200 dpi. */
    private const val COTE_MAX = 1400

    /**
     * The printed PDF made lighter and labelled: Chrome keeps each screenshot losslessly (20 MB and more);
     * the large opaque ones are written again as JPEG (quality 0.85, [COTE_MAX] px at most), the rest as it is.
     */
    fun finit(pdf: File, langue: String, version: String): File {
        val sortie = File(pdf.parentFile, pdf.nameWithoutExtension + "-fini.pdf")
        Loader.loadPDF(pdf).use { d ->
            val faites = java.util.IdentityHashMap<org.apache.pdfbox.cos.COSBase, org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject>()
            for (page in d.pages) {
                val res = page.resources ?: continue
                for (nom in res.xObjectNames.toList()) {
                    val x = res.getXObject(nom) as? org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject ?: continue
                    val cle = x.cosObject
                    val deja = faites[cle]
                    if (deja != null) { res.put(nom, deja); continue }
                    if (x.cosObject.length < 120_000 || x.cosObject.getDictionaryObject(COSName.SMASK) != null || x.isStencil) continue
                    val img = runCatching { x.image }.getOrNull() ?: continue
                    val k = minOf(1.0, COTE_MAX.toDouble() / maxOf(img.width, img.height))
                    val w = maxOf(1, (img.width * k).toInt()); val h = maxOf(1, (img.height * k).toInt())
                    val rgb = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
                    rgb.createGraphics().apply {
                        setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                        setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY)
                        drawImage(img, 0, 0, w, h, null); dispose()
                    }
                    val jpeg = org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory.createFromImage(d, rgb, 0.85f)
                    if (jpeg.cosObject.length >= x.cosObject.length) { faites[cle] = x; continue }
                    faites[cle] = jpeg
                    res.put(nom, jpeg)
                }
            }
            d.documentInformation.apply {
                author = "SatMe"
                subject = (if (langue == "fr") "Manuel de l'utilisateur de SatMe-PC" else "SatMe-PC user manual") + (if (version.isBlank() || version == "dev") "" else " $version")
                keywords = "SatMe, SatMe-PC, " + (if (langue == "fr") "radioamateur, satellites" else "amateur radio, satellites")
            }
            d.documentCatalog.language = langue
            d.save(sortie)
        }
        return sortie
    }

    private fun lance(chrome: String, travail: File, page: File, pdf: File) {
        pdf.delete()
        val cmd = mutableListOf(chrome, "--headless=new", "--disable-gpu", "--no-first-run", "--no-default-browser-check",
            "--user-data-dir=" + File(travail, "profil").path, "--no-pdf-header-footer", "--generate-pdf-document-outline",
            "--allow-file-access-from-files", "--print-to-pdf=" + pdf.path)
        // A CI runner may refuse Chrome's sandbox (user namespaces); the page is our own.
        if (System.getenv("CI") == "true") cmd.add(1, "--no-sandbox")
        cmd.add(page.toURI().toString())
        val p = ProcessBuilder(cmd).directory(travail).redirectErrorStream(true).redirectOutput(File(travail, "chrome.log")).start()
        if (!p.waitFor(240, TimeUnit.SECONDS)) { p.destroyForcibly(); error("le navigateur ne répond pas (240 s)") }
        if (!pdf.isFile || pdf.length() < 1000) error("pas de PDF (code ${p.exitValue()}) : " + File(travail, "chrome.log").readText().takeLast(800))
    }

    /** Each named destination of [pdf] (Chrome makes one per internal link's target) and its page, from 1. */
    fun destinations(pdf: File): Map<String, Int> = Loader.loadPDF(pdf).use { d ->
        val r = HashMap<String, Int>()
        fun nom(s: String) = runCatching { URLDecoder.decode(s, Charsets.UTF_8) }.getOrDefault(s)
        // Old style: the catalog's /Dests dictionary.
        (d.documentCatalog.cosObject.getDictionaryObject(COSName.DESTS) as? COSDictionary)?.let { dict ->
            d.documentCatalog.dests?.let { dests ->
                for (k in dict.keySet()) {
                    val dest = dests.getDestination(k.name) as? PDPageDestination ?: continue
                    val n = dest.retrievePageNumber(); if (n >= 0) r[nom(k.name)] = n + 1
                }
            }
        }
        // New style: the /Names tree.
        d.documentCatalog.names?.dests?.let { arbre ->
            fun parcourt(n: org.apache.pdfbox.pdmodel.common.PDNameTreeNode<org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination>) {
                n.names?.forEach { (k, v) -> val p = v.retrievePageNumber(); if (p >= 0) r[nom(k)] = p + 1 }
                n.kids?.forEach { parcourt(it) }
            }
            parcourt(arbre)
        }
        r
    }
}
