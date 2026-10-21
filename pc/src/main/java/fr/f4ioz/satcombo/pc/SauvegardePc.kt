/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * SatMe-PC's whole station in one file, to set it up again on another PC
 * or after a reinstall: the settings, the log, the pass journal, APRS and
 * its trophies, the SSTV sheets — and, when asked, the recordings and the
 * pictures. What can be fetched again (elements, map tiles) stays out.
 *
 * A backup is put back when SatMe-PC starts: running, it holds its settings
 * in memory and would write them over. Nothing already here is lost: the
 * settings replaced are kept aside, the log is merged, files already here
 * stay as they are.
 */
class SauvegardePc(private val dossier: File, private val horloge: () -> Long = System::currentTimeMillis) {

    class Bilan(val ok: Boolean, val fichiers: Int = 0, val octets: Long = 0L, val date: Long = 0L, val version: String = "", val erreur: String = "")

    /** The backup made now, in exports/ (replacing the previous one of the same minute). */
    fun fabrique(medias: Boolean): File {
        val sortie = File(dossier, "exports").apply { mkdirs() }
        val f = File(sortie, "SatMe-PC-sauvegarde-" + stamp() + ".zip")
        ZipOutputStream(f.outputStream().buffered()).use { z ->
            val m = JSONObject().put("app", "SatMe-PC").put("sauvegarde", 1).put("version", versionPc())
                .put("date", horloge()).put("medias", medias)
            z.putNextEntry(ZipEntry(MANIFESTE)); z.write(m.toString(2).toByteArray()); z.closeEntry()
            for (n in FICHIERS) File(dossier, n).takeIf { it.isFile }?.let { ajoute(z, it, n) }
            for (d in DOSSIERS + (if (medias) MEDIAS else emptyList())) {
                val base = File(dossier, d)
                base.walkTopDown().filter { it.isFile }.forEach { ajoute(z, it, d + "/" + it.relativeTo(base).invariantSeparatorsPath) }
            }
        }
        return f
    }

    /** How big a backup would be, roughly (bytes), with and without the media. */
    fun taille(): Pair<Long, Long> {
        fun t(n: String) = File(dossier, n).let { f -> if (f.isFile) f.length() else f.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
        val base = (FICHIERS + DOSSIERS).sumOf { t(it) }
        return base to base + MEDIAS.sumOf { t(it) }
    }

    /**
     * A backup received: checked and laid in restauration/, to be put back at
     * the next start. Only the names a backup holds are taken; nothing outside.
     */
    fun recoit(entree: InputStream): Bilan {
        val attente = File(dossier, ATTENTE)
        attente.deleteRecursively()
        var manifeste: JSONObject? = null
        var n = 0; var octets = 0L
        runCatching {
            ZipInputStream(entree).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    val nom = e.name.replace('\\', '/')
                    if (e.isDirectory || !permis(nom)) continue
                    if (nom == MANIFESTE) { manifeste = runCatching { JSONObject(String(z.readBytes(), Charsets.UTF_8)) }.getOrNull(); continue }
                    val f = File(attente, nom)
                    if (!f.canonicalPath.startsWith(attente.canonicalPath + File.separator)) continue
                    f.parentFile.mkdirs()
                    f.outputStream().use { o -> octets += z.copyTo(o) }
                    if (octets > MAX_OCTETS) error("trop gros")
                    n++
                }
            }
        }.onFailure { attente.deleteRecursively(); return Bilan(false, erreur = "fichier illisible ou trop gros") }
        val m = manifeste
        if (m == null || m.optString("app") != "SatMe-PC") { attente.deleteRecursively(); return Bilan(false, erreur = "ce n'est pas une sauvegarde de SatMe-PC") }
        File(attente, MANIFESTE).writeText(m.toString())
        return Bilan(true, n, octets, m.optLong("date"), m.optString("version"))
    }

    /** A backup waiting for the next start, or null. */
    fun enAttente(): Bilan? {
        val m = File(dossier, ATTENTE + "/" + MANIFESTE).takeIf { it.isFile }?.let { runCatching { JSONObject(it.readText()) }.getOrNull() } ?: return null
        return Bilan(true, date = m.optLong("date"), version = m.optString("version"))
    }

    fun annule() { File(dossier, ATTENTE).deleteRecursively() }

    /**
     * At start, before anything reads the settings: a backup waiting is put
     * back. The settings it replaces go to avant-restauration-<date>/; the
     * log is merged (a contact is its time); files already here are kept.
     */
    fun appliqueSiPrete(): Boolean {
        val attente = File(dossier, ATTENTE)
        if (!File(attente, MANIFESTE).isFile) return false
        val avant = File(dossier, "avant-restauration-" + stamp()).apply { mkdirs() }
        for (n in FICHIERS) {
            val neuf = File(attente, n).takeIf { it.isFile } ?: continue
            val ici = File(dossier, n)
            if (ici.isFile) ici.copyTo(File(avant, n), overwrite = true)
            if (n == CARNET && ici.isFile) fusionneCarnet(ici, neuf) else neuf.copyTo(ici, overwrite = true)
        }
        for (d in DOSSIERS + MEDIAS) {
            val base = File(attente, d).takeIf { it.isDirectory } ?: continue
            base.walkTopDown().filter { it.isFile }.forEach { f ->
                val cible = File(File(dossier, d), f.relativeTo(base).path)
                if (!cible.exists()) { cible.parentFile.mkdirs(); f.copyTo(cible) }
            }
        }
        attente.deleteRecursively()
        return true
    }

    /** The log here and the backup's, together: each contact once (by its time). */
    private fun fusionneCarnet(ici: File, neuf: File) {
        val a = fr.f4ioz.satcombo.data.LogStore(ici)
        val b = fr.f4ioz.satcombo.data.LogStore(neuf).load()
        val deja = a.load().map { it.timeMs }.toHashSet()
        b.filter { it.timeMs !in deja }.forEach { a.add(it) }
    }

    private fun ajoute(z: ZipOutputStream, f: File, nom: String) {
        z.putNextEntry(ZipEntry(nom).apply { time = f.lastModified() })
        f.inputStream().use { it.copyTo(z) }
        z.closeEntry()
    }

    private fun permis(nom: String): Boolean = !nom.startsWith("/") && !nom.split('/').any { it == ".." || it.isEmpty() } &&
        (nom == MANIFESTE || nom in FICHIERS || (DOSSIERS + MEDIAS).any { nom.startsWith("$it/") })

    private fun stamp() = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(horloge()))

    companion object {
        const val MANIFESTE = "sauvegarde.json"
        const val CARNET = "qso_log.json"
        const val ATTENTE = "restauration"
        /** The settings (the phone's stores), the log, APRS's trophies, the SSTV campaigns. */
        val FICHIERS = listOf("reglages.properties", "satellites.properties", "agenda.properties", CARNET, "aprs-trophees.txt", "sstv-campagnes.json")
        /** The pass journal, APRS heard, the SSTV sheets. */
        val DOSSIERS = listOf("journal", "aprs", "planches")
        /** Heavy: only when asked. */
        val MEDIAS = listOf("enregistrements", "sstv", "meteor", "sondes")
        const val MAX_OCTETS = 8L * 1024 * 1024 * 1024
    }
}
