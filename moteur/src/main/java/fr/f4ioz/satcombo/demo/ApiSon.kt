/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.demo

import fr.f4ioz.satcombo.sstv.SstvMeta
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The station page's Sound and SSTV tabs, written once: SatMe-PC (its sound
 * card) and the phone (its recorder) say what they hear and keep; the
 * answers are the same, in the same words.
 */
object ApiSon {

    /** What the tabs show; a source to choose is a [cartes] entry (name, description). */
    class Etat(
        val cartes: List<Pair<String, String>>, val carte: String, val actif: Boolean, val rate: Int, val niveau: Float, val erreur: String,
        val enregistrement: String, val secondes: Long, val auto: Boolean, val enregAuto: Boolean, val sstv: Boolean, val aprs: Boolean,
        val sstvMode: String, val sstvLignes: Int, val sstvHauteur: Int, val sstvVersion: Long, val sstvDerniere: String,
        val enregistrements: List<File>, val images: List<SstvMeta.SstvShot>,
    )

    fun json(e: Etat): JSONObject = JSONObject().put("present", true)
        .put("cartes", JSONArray(e.cartes.map { JSONObject().put("nom", it.first).put("desc", it.second) }))
        .put("carte", e.carte).put("actif", e.actif).put("rate", e.rate).put("niveau", Math.round(e.niveau * 1000.0) / 1000.0).put("erreur", e.erreur)
        .put("enregistrement", e.enregistrement).put("secondes", e.secondes).put("auto", e.auto)
        .put("enregAuto", e.enregAuto).put("sstv", e.sstv).put("aprs", e.aprs)
        .put("sstvMode", e.sstvMode).put("sstvLignes", e.sstvLignes).put("sstvHauteur", e.sstvHauteur)
        .put("sstvVersion", e.sstvVersion).put("sstvDerniere", e.sstvDerniere)
        .put("enregistrements", JSONArray(e.enregistrements.take(50).map {
            JSONObject().put("nom", it.name).put("taille", it.length()).put("date", it.lastModified())
        }))
        .put("images", JSONArray(e.images.take(200).map {
            JSONObject().put("nom", it.fileName).put("sat", it.satName).put("t", it.timeMs).put("mode", it.mode).put("complete", it.complete)
        }))

    /** A file asked for by name: only a plain name, inside [dossier], ending in one of [suffixes]; else null. */
    fun fichier(dossier: File, nom: String, vararg suffixes: String): File? {
        val n = File(nom).name
        if (n != nom || n.startsWith('.')) return null
        return File(dossier, n).takeIf { f -> f.isFile && suffixes.any { n.endsWith(it, ignoreCase = true) } }
    }
}
