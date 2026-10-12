/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.data.TleEntry
import fr.f4ioz.satcombo.i18n.tf
import fr.f4ioz.satcombo.meteor.MeteorImage
import fr.f4ioz.satcombo.meteor.RecepteurMeteor
import fr.f4ioz.satcombo.sstv.SstvMeta
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import javax.imageio.ImageIO
import kotlin.concurrent.thread

/**
 * METEOR-M pictures on the PC: the engine's receiver, the pictures written
 * as PNG with the phone's metadata next to them (same names, same notes),
 * in their own folder.
 */
class MeteorPc(private val station: StationPc, val dossier: File) {

    val recepteur = RecepteurMeteor(traits = { traits("land") to traits("borders") })

    /** Pictures written by this session, the last one's name. */
    @Volatile var dernier: String? = null; private set
    @Volatile var ecrites = 0; private set

    private fun traits(nom: String): List<FloatArray> {
        val texte = MeteorPc::class.java.getResourceAsStream("/traits/$nom.json")?.bufferedReader()?.use { it.readText() } ?: return emptyList()
        val a = org.json.JSONArray(texte)
        return List(a.length()) { i -> a.getJSONArray(i).let { l -> FloatArray(l.length()) { l.getDouble(it).toFloat() } } }
    }

    /** The elements of [sat] (a name such as "METEOR-M2-4"), among the satellites known. */
    fun elementsPour(sat: String): TleEntry? {
        fun cle(s: String) = s.uppercase().replace(Regex("[^A-Z0-9]"), "").removePrefix("METEORM")
        val c = cle(sat)
        return station.etat.value.satellites.firstOrNull { RecepteurMeteor.emetLrpt(it.catalogNumber, it.name) && cle(it.name) == c }
    }

    /** Writes [images]; the names written. */
    @Synchronized fun ecrit(images: List<RecepteurMeteor.Image>): List<String> {
        dossier.mkdirs()
        val noms = ArrayList<String>()
        for (i in images) {
            val nom = SstvMeta.fileName(i.satellite, i.debutMs, i.mode, i.lignes >= 800, kind = "LRPT")
            val ok = runCatching { ImageIO.write(image(i.image), "png", File(dossier, nom)) }.getOrDefault(false)
            if (!ok) continue
            val live = i.source == "live"
            val s = station.settings
            runCatching {
                File(dossier, SstvMeta.sidecarName(nom)).writeText(SstvMeta.encode(SstvMeta.SstvShot(
                    fileName = nom, satName = i.satellite, timeMs = i.debutMs, mode = i.mode, complete = i.lignes >= 800,
                    // A recording from elsewhere was not received here: no locator, no callsign.
                    locator = if (live) s.manualLocator else "", callsign = if (live) s.callsign else "",
                    source = i.source, recording = i.origine,
                    note = tf(if (i.mode == "LRPT-IR") "meteor_note_ir" else "meteor_note_couleur", i.lignes))), Charsets.UTF_8)
            }
            noms += nom
        }
        if (noms.isNotEmpty()) { dernier = noms.first(); ecrites += noms.size }
        return noms
    }

    private fun image(i: MeteorImage.Image): BufferedImage =
        BufferedImage(i.largeur, i.hauteur, BufferedImage.TYPE_INT_RGB).also { it.setRGB(0, 0, i.largeur, i.hauteur, i.pixels, 0, i.largeur) }

    /** The preview as PNG, or null. */
    fun apercuPng(): ByteArray? {
        val a = recepteur.apercu ?: return null
        return ByteArrayOutputStream().also { ImageIO.write(image(a), "png", it) }.toByteArray()
    }

    /** The pictures written, newest first, with their metadata. */
    fun images(): List<Pair<File, SstvMeta.SstvShot>> =
        (dossier.listFiles { f -> f.isFile && f.name.endsWith(".png") }?.sortedByDescending { it.lastModified() } ?: emptyList()).map { f ->
            val m = File(dossier, SstvMeta.sidecarName(f.name))
            f to SstvMeta.decode(f.name, runCatching { if (m.isFile) m.readText(Charsets.UTF_8) else null }.getOrNull())
        }

    fun fichier(nom: String): File? = File(dossier, nom).takeIf { nom.matches(Regex("[A-Za-z0-9._-]+")) && it.isFile && it.name.endsWith(".png") }

    fun supprime(nom: String): Boolean {
        val f = fichier(nom) ?: return false
        File(dossier, SstvMeta.sidecarName(nom)).delete()
        return f.delete()
    }

    /** A recording of the raw signal on this PC, decoded on a thread of its own. */
    fun decodeFichier(chemin: String): String? {
        val f = File(chemin.trim())
        if (!f.isFile) return "Fichier introuvable : $chemin"
        if (recepteur.actif) return "La clé reçoit METEOR : l'arrêter d'abord."
        if (recepteur.etat.progression >= 0f) return "Un fichier est déjà en cours."
        thread(isDaemon = true, name = "satme-meteor-fichier") {
            runCatching {
                RandomAccessFile(f, "r").use { raf ->
                    ecrit(recepteur.decodeFichier(raf.channel, f.name, RecepteurMeteor.dateDuNom(f.name) ?: f.lastModified(), ::elementsPour))
                }
            }
        }
        return null
    }
}
