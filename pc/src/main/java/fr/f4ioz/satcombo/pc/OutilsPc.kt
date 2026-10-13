/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import java.io.File

/**
 * The programs SatMe-PC calls (ffmpeg, ffplay, rtl_tcp, VLC), wherever they
 * are: first in the `outils` folder that comes with it (the Windows package
 * carries them), then in the PATH, then where each system puts them.
 */
object OutilsPc {

    val windows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows")

    /** The folder next to SatMe-PC's jar (`app/` on Windows, `lib/` in the tar), and its parent. */
    private val dossiersAppli: List<File> by lazy {
        val jar = runCatching { File(OutilsPc::class.java.protectionDomain.codeSource.location.toURI()) }.getOrNull()
        val d = jar?.parentFile ?: return@lazy emptyList()
        listOfNotNull(File(d, "outils"), d.parentFile?.let { File(it, "outils") })
    }

    /** The places each system puts [nom], when not in the PATH. */
    private fun habituels(nom: String): List<String> = if (windows) {
        val pf = listOfNotNull(System.getenv("ProgramFiles"), System.getenv("ProgramFiles(x86)"), "C:\\Program Files")
        when (nom) {
            "vlc" -> pf.map { "$it\\VideoLAN\\VLC\\vlc.exe" }
            "ffmpeg", "ffplay" -> listOf("C:\\ffmpeg\\bin\\$nom.exe") + pf.map { "$it\\ffmpeg\\bin\\$nom.exe" }
            else -> emptyList()
        }
    } else listOf("/usr/bin/$nom", "/usr/local/bin/$nom", "/snap/bin/$nom")

    /** [nom] as a file that runs, or null. */
    fun trouve(nom: String): String? {
        val fichier = if (windows) "$nom.exe" else nom
        val chemins = dossiersAppli.map { File(it, fichier).path } +
            System.getenv("PATH").orEmpty().split(File.pathSeparatorChar).filter { it.isNotBlank() }.map { File(it, fichier).path } +
            habituels(nom)
        return chemins.firstOrNull { File(it).let { f -> f.isFile && (windows || f.canExecute()) } }
    }

    /** What to do when [nom] is missing, said for this system. */
    fun conseil(nom: String): String = when {
        windows -> "$nom manque : réinstaller SatMe-PC (il vient avec)"
        nom == "rtl_tcp" -> "installer rtl-sdr (sudo apt install rtl-sdr)"
        nom == "vlc" -> "installer VLC (sudo apt install vlc)"
        else -> "sudo apt install ffmpeg"
    }
}
