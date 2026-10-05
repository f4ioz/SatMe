/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * A pass of the journal in one file, to keep it or give it to another
 * station: a ZIP holding the pass ([NOM_PASSAGE]), its recording
 * (`recordings/…mp3` and its `.info`) and its SSTV pictures (`sstv/…png`
 * and their `.meta`). The log's contacts and the APRS frames stay with
 * their own log: they are not the receiving station's.
 *
 * Opened with care: plain names only (no folder, no ".."), only the kinds
 * of file listed, [MAX_OCTETS] at most, nothing already there overwritten.
 */
object JournalPaquet {
    const val NOM_PASSAGE = "passage.passage"
    /** The moments marked (⚑) during the pass, one per line. */
    const val NOM_SIGNETS = "signets.tsv"
    const val MAX_OCTETS = 300L * 1024 * 1024
    const val TYPE = "application/zip"
    /** A pass file is a few hundred kB at most. */
    const val MAX_PASSAGE = 4 * 1024 * 1024

    /** The folders of a pack and the kinds of file each takes. */
    val PERMIS = mapOf(
        "recordings" to setOf("mp3", "info"),
        "sstv" to setOf("png", "meta"))

    private val NOM_SUR = Regex("""[A-Za-z0-9][A-Za-z0-9._+\-]{0,150}""")

    /** A plain file name SatMe could have written, of a kind [dossier] takes. */
    fun nomPermis(dossier: String, nom: String): Boolean =
        NOM_SUR.matches(nom) && ".." !in nom && nom.substringAfterLast('.', "").lowercase() in (PERMIS[dossier] ?: emptySet())

    /** The pack's name: satellite and start (UTC). */
    fun nom(e: JournalPassage.Entree): String {
        val f = java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        val sat = e.satName.replace(Regex("[^A-Za-z0-9+\\-]"), "_").ifBlank { "SAT" }
        return "SatMe_passage_${sat}_${f.format(java.util.Date(e.debutMs))}Z.zip"
    }

    /** Writes the pack: the pass, then each file under its folder (missing ones left out). */
    fun emballe(sortie: OutputStream, e: JournalPassage.Entree, fichiers: List<Pair<String, File>>,
                signets: List<JournalPassage.Signet> = emptyList()) {
        ZipOutputStream(sortie).use { z ->
            z.putNextEntry(ZipEntry(NOM_PASSAGE))
            z.write(JournalPassage.ecrit(e).toByteArray(Charsets.UTF_8))
            z.closeEntry()
            if (signets.isNotEmpty()) {
                z.putNextEntry(ZipEntry(NOM_SIGNETS))
                z.write(signets.joinToString("") { JournalPassage.ecritSignet(it) + "\n" }.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
            val vus = HashSet<String>()
            for ((dossier, f) in fichiers) {
                if (!f.isFile || !nomPermis(dossier, f.name) || !vus.add("$dossier/${f.name}")) continue
                z.putNextEntry(ZipEntry("$dossier/${f.name}"))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
    }

    /** What was opened: the pass, the files put in place, those already there, those refused. */
    data class Deballage(
        val entree: JournalPassage.Entree?,
        val poses: Int = 0,
        val dejaLa: Int = 0,
        val refuses: Int = 0,
        val signets: List<JournalPassage.Signet> = emptyList()
    )

    /** Opens a pack: its files into [dossiers] (by folder name), its pass returned (not yet kept). */
    fun deballe(entree: InputStream, dossiers: Map<String, File>): Deballage {
        var passage: JournalPassage.Entree? = null
        var signets: List<JournalPassage.Signet> = emptyList()
        var poses = 0; var deja = 0; var refuses = 0
        var total = 0L
        val tampon = ByteArray(64 * 1024)
        val faits = ArrayList<File>()
        try {
            ZipInputStream(entree).use { z ->
                while (true) {
                    val en = z.nextEntry ?: break
                    if (en.isDirectory) continue
                    val nom = en.name
                    if (nom == NOM_PASSAGE || nom == NOM_SIGNETS) {
                        val texte = java.io.ByteArrayOutputStream()
                        while (true) {
                            val n = z.read(tampon)
                            if (n < 0) break
                            if (texte.size() + n > MAX_PASSAGE) throw TropGros()
                            texte.write(tampon, 0, n)
                        }
                        total += texte.size()
                        val lu = texte.toByteArray().toString(Charsets.UTF_8)
                        if (nom == NOM_SIGNETS) signets = lu.lines().mapNotNull { JournalPassage.litSignet(it) }
                        else passage = JournalPassage.lit(lu)
                        continue
                    }
                    val dossier = nom.substringBefore('/', "")
                    val fichier = nom.substringAfter('/', "")
                    val cible = dossiers[dossier]
                    if (cible == null || !nomPermis(dossier, fichier)) { refuses++; continue }
                    val f = File(cible, fichier)
                    if (f.exists()) { deja++; continue }
                    cible.mkdirs()
                    val tmp = File(cible, ".$fichier.part")
                    tmp.outputStream().use { o ->
                        while (true) {
                            val n = z.read(tampon)
                            if (n < 0) break
                            total += n
                            if (total > MAX_OCTETS) { o.close(); tmp.delete(); throw TropGros() }
                            o.write(tampon, 0, n)
                        }
                    }
                    if (tmp.renameTo(f)) { poses++; faits += f } else tmp.delete()
                }
            }
        } catch (e: TropGros) {
            // Too big to be a pass: nothing of it kept.
            faits.forEach { it.delete() }
            return Deballage(null, 0, deja, refuses + 1)
        }
        // A pack without a pass is not one: what it brought is taken back.
        if (passage == null) { faits.forEach { it.delete() }; return Deballage(null, 0, deja, refuses) }
        // Only the signets of that pass's satellite.
        val p = passage!!
        return Deballage(p, poses, deja, refuses, signets.filter { it.catnum == p.catnum })
    }

    private class TropGros : RuntimeException()
}
