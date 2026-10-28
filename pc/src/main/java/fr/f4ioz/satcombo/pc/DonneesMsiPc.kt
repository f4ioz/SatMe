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
import java.time.LocalDate

/**
 * SatMe-PC installed by `satme-pc-windows.msi`: its folder belongs to the
 * installer. The installers up to 2026.10.33 empty it whole when they are
 * uninstalled — and an update uninstalls the version before — so data never
 * lives there. A `donnees` folder found beside the program (the ZIP's
 * portable mode, made by hand here) is copied out once to %APPDATA%, checked,
 * and used from there; the original is never removed.
 *
 * The MSI is told from the ZIP by [MARQUEUR], a file the installer alone
 * puts beside the jars (pc/windows/paquet-windows.sh): the same program
 * otherwise, and an install folder can be anywhere.
 */
object DonneesMsiPc {
    /** Beside pc.jar in an MSI install only. */
    const val MARQUEUR = "installe-par-msi.txt"
    /** Left in the old `donnees` folder once copied: that folder no longer used. */
    const val COPIE_FAITE = "COPIE-FAITE-SatMe.txt"
    const val PORTABLE = "donnees"

    /** Where this program runs from (its pc.jar), or null. */
    fun jar(): File? = runCatching { File(DonneesMsiPc::class.java.protectionDomain.codeSource.location.toURI()) }.getOrNull()

    /** True when [jar] was installed by the MSI. */
    fun installeParMsi(jar: File? = jar()): Boolean = jar?.parentFile?.let { File(it, MARQUEUR).isFile } == true

    /**
     * The program's own folder (the one an update replaces), or null when
     * this is no installed program (classes run from a build): the folder
     * holding `lib` (Linux) or `app` (Windows), the jar in it.
     */
    fun programme(jar: File? = jar()): File? {
        val ici = jar?.takeIf { it.isFile && it.name.endsWith(".jar") }?.parentFile ?: return null
        return if (ici.name == "lib" || ici.name == "app") ici.parentFile?.absoluteFile else null
    }

    /** Why [dossier] cannot hold the data (inside the program's folder), or null. The ZIP's own portable folder allowed. */
    fun refus(dossier: File, jar: File? = jar()): String? {
        val p = programme(jar) ?: return null
        val pc = runCatching { p.canonicalFile }.getOrDefault(p)
        val c = runCatching { dossier.canonicalFile }.getOrDefault(dossier.absoluteFile)
        if (c != pc && !c.path.startsWith(pc.path + File.separator)) return null
        if (!installeParMsi(jar) && c == File(pc, PORTABLE)) return null
        return "ce dossier est dans celui du programme (" + pc.path + "), qu'une mise à jour remplace : en choisir un autre" +
            if (OutilsPc.windows) " (par ex. D:\\SatMe)" else ""
    }

    /** What was done at start: the folder to use, and the message shown once on the page. */
    data class Releve(val dossier: File, val message: String, val copie: Boolean)

    /**
     * At start, in an MSI install with a `donnees` folder beside the program
     * not copied yet: copied to [appdata]\SatMe when that is absent or empty,
     * else to [appdata]\SatMe-portable-<date>; the start options pointed at
     * the copy. Null when there is nothing to do. When the copy fails, the
     * old folder is used once more ([Releve.copie] false) and said.
     */
    fun auDemarrage(jar: File?, appdata: File?, aujourdhui: LocalDate = LocalDate.now()): Releve? {
        if (!installeParMsi(jar)) return null
        val racine = jar?.parentFile?.parentFile ?: return null
        val donnees = File(racine, PORTABLE)
        if (!donnees.isDirectory || File(donnees, COPIE_FAITE).exists()) return null
        if (donnees.walkTopDown().none { it.isFile }) return null
        fun echec(cible: File?, raison: String) = Releve(donnees,
            "Vos données sont dans le dossier du programme (${donnees.path}), qu'une mise à jour efface, et SatMe-PC n'a pas pu les copier" +
                (cible?.let { " dans ${it.path}" } ?: "") + " : $raison. Copiez ce dossier ailleurs avant d'installer une autre version, " +
                "puis choisissez-le dans Réglages › Démarrage › Dossier des données.", copie = false)
        if (appdata == null) return echec(null, "dossier APPDATA inconnu")
        val cible = cible(appdata, aujourdhui)
        val lanceur = FichierLanceur(File(donnees, FichierLanceur.NOM)).lit()
        val fait = runCatching { LanceurPc.copieDonnees(donnees, cible) }
        if (fait.getOrNull() != true) return echec(cible, fait.exceptionOrNull()?.message ?: "copie refusée")
        if (!memeContenu(donnees, cible)) return echec(cible, "la copie ne correspond pas à l'original")
        // Kept elsewhere than %APPDATA%\SatMe: the start options say where (those of the portable folder, which were in use).
        val principal = File(appdata, "SatMe")
        if (cible.absoluteFile != principal.absoluteFile) {
            runCatching { FichierLanceur(File(principal, FichierLanceur.NOM))
                .ecrit(lanceur.copy(dossier = lanceur.dossier ?: cible.absolutePath, copieDepuis = null)) }
                .onFailure { return echec(cible, "options de démarrage non écrites (" + (it.message ?: "erreur") + ")") }
        }
        runCatching {
            File(donnees, COPIE_FAITE).writeText("Ce dossier a été copié le $aujourdhui dans ${cible.absolutePath} : SatMe-PC s'en sert désormais.\r\n" +
                "Ce dossier-ci n'est plus utilisé, et une mise à jour de SatMe-PC peut l'effacer. Le supprimer quand tout va bien.\r\n", Charsets.UTF_8)
        }.onFailure { return echec(cible, "dossier du programme en lecture seule (" + (it.message ?: "erreur") + ")") }
        return Releve(cible.absoluteFile, "Vos données étaient dans le dossier du programme, qu'une mise à jour efface : elles ont été copiées dans " +
            cible.absolutePath + ", où SatMe-PC les garde désormais. L'ancien dossier (${donnees.path}) est gardé tel quel : le supprimer quand tout va bien.", copie = true)
    }

    /** %APPDATA%\SatMe when absent or empty, else a new SatMe-portable-<date>. */
    fun cible(appdata: File, aujourdhui: LocalDate): File {
        val principal = File(appdata, "SatMe")
        if (!principal.exists() || (principal.isDirectory && principal.list()?.isEmpty() == true)) return principal
        var n = 1
        while (true) {
            val f = File(appdata, "SatMe-portable-$aujourdhui" + if (n == 1) "" else "-$n")
            if (!f.exists()) return f
            n++
        }
    }

    /** Every file of [a] in [b], the same size ([COPIE_FAITE], written after, aside). */
    fun memeContenu(a: File, b: File): Boolean = runCatching {
        a.walkTopDown().filter { it.isFile && it != File(a, COPIE_FAITE) }.all { f -> File(b, f.relativeTo(a).path).let { it.isFile && it.length() == f.length() } }
    }.getOrDefault(false)
}
