/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site.contenu

import fr.f4ioz.site.publication.Vignettes
import java.io.File

/** A photo album: a folder of `contenu/galeries/`, its `info.md` (title, date, summary, then a text). */
data class Album(val nom: String, val titre: String, val date: String, val resume: String, val texteHtml: String, val photos: List<String>)

/**
 * The photo albums put by hand (QSL, antennas, expeditions): one folder
 * each, its JPEG and PNG pictures in name order, their thumbnails made once
 * and kept in the data folder. Names are checked: nothing outside the
 * albums is ever served.
 */
class Albums(private val dossier: File, private val cache: File) {

    fun tous(): List<Album> = (dossier.listFiles { f -> f.isDirectory && NOM.matches(f.name) } ?: emptyArray())
        .mapNotNull { album(it.name) }.sortedWith(compareByDescending<Album> { it.date }.thenBy { it.nom })

    fun album(nom: String): Album? {
        if (!NOM.matches(nom)) return null
        val d = File(dossier, nom).takeIf { it.isDirectory } ?: return null
        val (fm, corps) = File(d, "info.md").takeIf { it.isFile }?.let { EnTete.decoupe(it.readText()) } ?: (emptyMap<String, String>() to "")
        val photos = (d.listFiles { f -> f.isFile && PHOTO.matches(f.name) } ?: emptyArray()).map { it.name }.sorted()
        return Album(nom, fm["title"]?.ifBlank { null } ?: nom, fm["date"].orEmpty(), fm["summary"].orEmpty(),
            fr.f4ioz.satcombo.demo.Markdown.html(corps), photos)
    }

    /** A photo of an album, null for any other name. */
    fun photo(nom: String, fichier: String): File? {
        if (!NOM.matches(nom) || !PHOTO.matches(fichier)) return null
        val f = File(File(dossier, nom), fichier)
        return f.takeIf { it.isFile && it.canonicalPath.startsWith(dossier.canonicalPath + File.separator) }
    }

    /** Its thumbnail (made the first time it is asked for). */
    fun vignette(nom: String, fichier: String): File? {
        val src = photo(nom, fichier) ?: return null
        val v = File(File(cache, nom), "$fichier.jpg")
        if (v.isFile && v.lastModified() >= src.lastModified()) return v
        val b = Vignettes.vignette(src.readBytes(), 420) ?: return null
        v.parentFile.mkdirs(); v.writeBytes(b)
        return v
    }

    companion object {
        val NOM = Regex("^[a-z0-9][a-z0-9_-]{0,63}$")
        val PHOTO = Regex("^[A-Za-z0-9][A-Za-z0-9_.-]{0,100}\\.(jpg|jpeg|png|JPG|JPEG|PNG)$")
    }
}
