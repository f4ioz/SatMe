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
import java.util.Properties

/** What the command line said; null where it said nothing (the launcher file or the default then). */
data class ArgumentsPc(
    val port: Int? = null, val reseau: Boolean? = null, val dossier: File? = null,
    val noeud: Boolean = false, val bureau: Boolean? = null,
    val version: Boolean = false, val aide: Boolean = false,
    /** Started again by « Redémarrer SatMe-PC »: the previous one's process, waited for. */
    val apres: Long? = null,
) {
    companion object {
        fun lit(args: Array<String>): ArgumentsPc {
            var a = ArgumentsPc()
            var i = 0
            while (i < args.size) {
                when (args[i]) {
                    "--port" -> args.getOrNull(++i)?.toIntOrNull()?.takeIf { it in 1..65535 }?.let { a = a.copy(port = it) }
                    "--reseau" -> a = a.copy(reseau = true)
                    "--local" -> a = a.copy(reseau = false)
                    "--noeud" -> a = a.copy(noeud = true)
                    "--bureau" -> a = a.copy(bureau = true)
                    "--console" -> a = a.copy(bureau = false)
                    "--dossier" -> args.getOrNull(++i)?.takeIf { it.isNotBlank() }?.let { a = a.copy(dossier = File(it)) }
                    "--apres" -> args.getOrNull(++i)?.toLongOrNull()?.let { a = a.copy(apres = it) }
                    "-v", "--version" -> a = a.copy(version = true)
                    "-h", "--help", "--aide" -> a = a.copy(aide = true)
                }
                i++
            }
            return a
        }
    }
}

/**
 * The startup options kept in a small file of their own, read by `main`
 * before anything else: « Accès depuis le réseau » (--reseau), « Port »
 * (--port), « Dossier des données » (--dossier). Not in the data folder
 * (it says where that folder is): `~/.config/satme/satme-pc.properties`
 * (or `$XDG_CONFIG_HOME/satme/`) on Linux and macOS,
 * `%APPDATA%\SatMe\satme-pc.properties` on Windows.
 *
 * The command line wins over the file, the file over the defaults. A key
 * absent (or unreadable) is no choice made. Written by Réglages ›
 * Démarrage; read at the next start only.
 */
class FichierLanceur(val fichier: File) {

    data class Options(val port: Int? = null, val reseau: Boolean? = null, val dossier: String? = null,
                       /** The data folder to copy into the new one at the next start (« Copier mes données »). */
                       val copieDepuis: String? = null)

    fun lit(): Options {
        if (!fichier.isFile) return Options()
        val p = Properties()
        if (runCatching { fichier.reader(Charsets.UTF_8).use { p.load(it) } }.isFailure) return Options()
        return Options(
            port = p.getProperty(PORT)?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 },
            reseau = p.getProperty(RESEAU)?.trim()?.lowercase()?.let { if (it == "true" || it == "1" || it == "oui") true else if (it == "false" || it == "0" || it == "non") false else null },
            dossier = p.getProperty(DOSSIER)?.trim()?.takeIf { it.isNotEmpty() },
            copieDepuis = p.getProperty(COPIE)?.trim()?.takeIf { it.isNotEmpty() })
    }

    /** [o] written whole (null: the key left out, the default then), through a temporary file. */
    fun ecrit(o: Options) {
        fichier.parentFile?.mkdirs()
        val lignes = buildString {
            append("# SatMe-PC : options de démarrage (Réglages › Démarrage), lues au lancement.\n")
            append("# La ligne de commande (--port, --reseau / --local, --dossier) passe avant ce fichier.\n")
            o.port?.let { append("$PORT=$it\n") }
            o.reseau?.let { append("$RESEAU=$it\n") }
            o.dossier?.let { append("$DOSSIER=" + echappe(it) + "\n") }
            o.copieDepuis?.let { append("$COPIE=" + echappe(it) + "\n") }
        }
        val tmp = File(fichier.path + ".tmp")
        tmp.writeText(lignes, Charsets.UTF_8)
        if (!tmp.renameTo(fichier)) { fichier.delete(); if (!tmp.renameTo(fichier)) error("écriture impossible : " + fichier.path) }
    }

    companion object {
        const val NOM = "satme-pc.properties"
        const val PORT = "port"
        const val RESEAU = "reseau"
        const val DOSSIER = "dossier"
        const val COPIE = "copie_depuis"

        /** Where this user's file is: %APPDATA%\SatMe on Windows, the XDG config folder elsewhere. */
        fun emplacement(env: (String) -> String? = System::getenv, windows: Boolean = OutilsPc.windows,
                        maison: String = System.getProperty("user.home")): File {
            val appdata = env("APPDATA")
            if (windows && !appdata.isNullOrBlank()) return File(File(appdata, "SatMe"), NOM)
            val xdg = env("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() && File(it).isAbsolute }
            return File(File(xdg?.let(::File) ?: File(maison, ".config"), "satme"), NOM)
        }

        /** A Properties value: backslashes (Windows paths) and leading spaces kept. */
        private fun echappe(s: String): String = s.replace("\\", "\\\\").replace("\n", "").let { if (it.startsWith(" ")) "\\" + it else it }
    }
}

/**
 * The options SatMe-PC starts with, and where each came from: the command
 * line first, then the launcher file, then the default.
 */
data class LanceurPc(val port: Int, val reseau: Boolean, val dossier: File,
                     val sourcePort: Source, val sourceReseau: Source, val sourceDossier: Source,
                     val fichier: File) {

    enum class Source(val cle: String) { LIGNE("ligne"), FICHIER("fichier"), DEFAUT("defaut") }

    companion object {
        const val PORT_DEFAUT = 8090

        fun resout(a: ArgumentsPc, f: FichierLanceur.Options, fichier: File, dossierDefaut: File): LanceurPc {
            fun <T> choix(ligne: T?, fic: T?, defaut: T): Pair<T, Source> =
                if (ligne != null) ligne to Source.LIGNE else if (fic != null) fic to Source.FICHIER else defaut to Source.DEFAUT
            val (port, sp) = choix(a.port, f.port, PORT_DEFAUT)
            val (reseau, sr) = choix(a.reseau, f.reseau, false)
            val (dossier, sd) = choix(a.dossier, f.dossier?.let(::File), dossierDefaut)
            return LanceurPc(port, reseau, dossier.absoluteFile, sp, sr, sd, fichier)
        }

        /**
         * Why [cible] cannot be the data folder (null: it can): an absolute
         * path, not inside the folder used now (a copy would copy itself),
         * writable; with [copier], empty or new (nothing there overwritten).
         */
        fun refusDossier(cible: String, actuel: File, copier: Boolean): String? {
            val t = cible.trim()
            if (t.isEmpty()) return null
            val f = File(t)
            if (!f.isAbsolute) return "chemin complet attendu (par ex. " + (if (OutilsPc.windows) "D:\\SatMe" else "/home/moi/satme-donnees") + ")"
            val c = runCatching { f.canonicalFile }.getOrNull() ?: return "chemin illisible"
            val a = runCatching { actuel.canonicalFile }.getOrDefault(actuel.absoluteFile)
            if (c == a) return null
            if (c.path.startsWith(a.path + File.separator)) return "ce dossier est dans le dossier actuel des données : en choisir un autre"
            if (a.path.startsWith(c.path + File.separator) && copier) return "ce dossier contient le dossier actuel : en choisir un autre"
            if (c.exists() && !c.isDirectory) return "c'est un fichier, pas un dossier"
            if (copier && c.isDirectory && (c.list()?.isNotEmpty() == true)) return "ce dossier n'est pas vide : pour y copier les données, choisir un dossier vide ou nouveau"
            // Writable: made if needed, a file written and removed (a folder made here is kept, empty).
            val essai = runCatching {
                c.mkdirs()
                val e = File(c, ".satme-essai-" + System.nanoTime())
                e.writeText("ok"); e.delete()
            }
            return if (essai.isSuccess && c.isDirectory) null else "SatMe-PC ne peut pas écrire dans ce dossier"
        }

        /**
         * At start, the data asked to be copied (« Copier mes données »):
         * every file of [depuis] into [vers] when it is empty or new; the old
         * folder is never touched. True when copied.
         */
        fun copieDonnees(depuis: File, vers: File): Boolean {
            if (!depuis.isDirectory) return false
            val a = depuis.canonicalFile; val b = vers.canonicalFile
            if (a == b || b.path.startsWith(a.path + File.separator)) return false
            if (b.isDirectory && b.list()?.isNotEmpty() == true) return false
            b.mkdirs()
            a.walkTopDown().filter { it.isFile }.forEach { f ->
                val cible = File(b, f.relativeTo(a).path)
                cible.parentFile.mkdirs()
                f.copyTo(cible, overwrite = false)
                runCatching { cible.setLastModified(f.lastModified()) }
            }
            return true
        }
    }
}
