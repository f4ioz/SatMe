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
import fr.f4ioz.satcombo.sstv.SstvMode
import fr.f4ioz.satcombo.sstv.SstvSon
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
        /** A recording, even by hand, stopped after its pass; null where not offered. */
        val coupeLos: Boolean? = null,
        /** The SSTV decoder in detail (its settings, what it hears); null where not given. */
        val detail: Sstv? = null,
        /** Where the pictures are: each one says whether its sound and GIF are kept. */
        val dossierImages: File? = null,
        /** Each card's friendly name, by its own (« IC-9700 (USB Audio CODEC) »); the own name stays the id. */
        val libelles: Map<String, String> = emptyMap(),
        /** The rig's own card, when one is recognised ("" none). */
        val carteDuPoste: String = "",
    )

    /** The SSTV decoder: settings, what it hears, the work under way. */
    class Sstv(
        /** "auto", "carte", "sdr"; "" where the source is not chosen here. */
        val source: String = "",
        /** The source feeding it now, in words ("" when none). */
        val sourceActive: String = "",
        /** Time since its last sound (ms), -1 when never. */
        val depuisMs: Long = -1L,
        val niveau: Float = 0f,
        /** Tuning error measured by the decoder (Hz), when known. */
        val ecartHz: Int? = null,
        /** The sync tone seen away from 1200 Hz (Hz), when it stands out. */
        val ecartSyncHz: Int? = null,
        /** dB from [spectreBasHz] by [spectrePasHz]; null when not computed. */
        val spectre: FloatArray? = null,
        val spectreBasHz: Int = 0,
        val spectrePasHz: Int = 0,
        val force: String = "",
        val continu: Boolean = true,
        val nettoyage: Boolean = true,
        /** A picture under way (header heard or started by hand). */
        val decodage: Boolean = false,
        val progres: Float = 0f,
        val gardees: Int = 0,
        val erreur: String = "",
        /** What to do when nothing useful is heard. */
        val conseil: String = "",
        val redecode: String = "",
        val redecodeProgres: Float = -1f,
        val redecodeImages: Int = 0,
        val gif: String = "",
        /** What this side can do: decode a recording again, make a GIF. */
        val peutRedecoder: Boolean = true,
        val peutGif: Boolean = true,
        /** The automatic tuning; null where there is none. */
        val calage: Calage? = null,
    )

    /** The automatic SSTV tuning: on, the offset measured, the correction applied, by what, and advice. */
    class Calage(
        val actif: Boolean = false,
        /** Hz, in the sound as it arrives; null when not measured. */
        val mesureHz: Int? = null,
        /** Hz of sound moved so far. */
        val totalHz: Int = 0,
        /** The receiver is being moved now (else advice only). */
        val applique: Boolean = false,
        /** What it moves, in words. */
        val voie: String = "",
        /** The last header's offset (Hz). */
        val visHz: Int? = null,
        val corrections: Int = 0,
        /** What to do by hand, "" when nothing. */
        val conseil: String = "",
    )

    private fun calage(c: Calage): JSONObject = JSONObject().put("actif", c.actif).put("mesureHz", c.mesureHz ?: JSONObject.NULL)
        .put("totalHz", c.totalHz).put("applique", c.applique).put("voie", c.voie).put("visHz", c.visHz ?: JSONObject.NULL)
        .put("corrections", c.corrections).put("conseil", c.conseil)

    fun sstv(d: Sstv): JSONObject = JSONObject().put("source", d.source).put("sourceActive", d.sourceActive)
        .put("depuisMs", d.depuisMs).put("niveau", Math.round(d.niveau * 1000.0) / 1000.0)
        .put("ecartHz", d.ecartHz ?: JSONObject.NULL).put("ecartSyncHz", d.ecartSyncHz ?: JSONObject.NULL)
        .put("spectre", d.spectre?.let { s -> JSONArray(s.map { Math.round(it).toInt() }) } ?: JSONObject.NULL)
        .put("spectreBas", d.spectreBasHz).put("spectrePas", d.spectrePasHz)
        .put("force", d.force).put("continu", d.continu).put("nettoyage", d.nettoyage)
        .put("decodage", d.decodage).put("progres", Math.round(d.progres * 1000.0) / 1000.0).put("gardees", d.gardees)
        .put("erreur", d.erreur).put("conseil", d.conseil)
        .put("redecode", d.redecode).put("redecodeProgres", Math.round(d.redecodeProgres * 1000.0) / 1000.0).put("redecodeImages", d.redecodeImages)
        .put("gif", d.gif).put("peutRedecoder", d.peutRedecoder).put("peutGif", d.peutGif)
        .put("afc", d.calage?.let { calage(it) } ?: JSONObject.NULL)
        .put("modes", JSONArray(SstvMode.ALL.map { it.name }))

    fun json(e: Etat): JSONObject = JSONObject().put("present", true)
        .put("cartes", JSONArray(e.cartes.map { JSONObject().put("nom", it.first).put("desc", it.second)
            .put("lib", e.libelles[it.first] ?: it.first).put("poste", it.first == e.carteDuPoste) }))
        .put("carteDuPoste", e.carteDuPoste)
        .put("carte", e.carte).put("actif", e.actif).put("rate", e.rate).put("niveau", Math.round(e.niveau * 1000.0) / 1000.0).put("erreur", e.erreur)
        .put("enregistrement", e.enregistrement).put("secondes", e.secondes).put("auto", e.auto)
        .put("enregAuto", e.enregAuto).put("sstv", e.sstv).put("aprs", e.aprs).put("coupeLos", e.coupeLos ?: JSONObject.NULL)
        .put("sstvMode", e.sstvMode).put("sstvLignes", e.sstvLignes).put("sstvHauteur", e.sstvHauteur)
        .put("sstvVersion", e.sstvVersion).put("sstvDerniere", e.sstvDerniere)
        .put("enregistrements", JSONArray(e.enregistrements.take(50).map {
            JSONObject().put("nom", it.name).put("taille", it.length()).put("date", it.lastModified())
        }))
        .put("images", JSONArray(e.images.take(200).let { l ->
            // « Réunir »: each partial picture's other half, when the two are one picture.
            val paires = fr.f4ioz.satcombo.sstv.ReunionSstv.paires(l.filter { it.source != "essai" })
            l.map { it to paires.firstNotNullOfOrNull { (a, b) -> if (a.fileName == it.fileName) b.fileName else if (b.fileName == it.fileName) a.fileName else null } }
        }.map { (it, avec) ->
            val o = JSONObject().put("nom", it.fileName).put("sat", it.satName).put("t", it.timeMs).put("mode", it.mode).put("complete", it.complete)
            if (it.trous.isNotEmpty()) o.put("trous", fr.f4ioz.satcombo.sstv.SstvMeta.trousTexte(it.trous))
            if (it.reunion.isNotEmpty()) o.put("reunion", JSONArray(it.reunion))
            if (avec != null) o.put("reunirAvec", avec)
            e.dossierImages?.let { d ->
                val png = File(d, it.fileName)
                o.put("son", SstvSon.fichier(png).isFile).put("gif", SstvSon.gif(png).isFile)
                    .put("source", it.source).put("enregistrement", it.recording).put("redecode", it.redecodeMs > 0L)
                // Its own finish (« IA », colour) for the gallery's badges.
                val r = fr.f4ioz.satcombo.sstv.RetouchesGalerie.reglageDe(d, it.fileName)
                if (r.ia) o.put("ia", true)
                if (r.couleur != null) o.put("couleur", r.couleur)
            }
            o
        }))
        .put("sstvDetail", e.detail?.let { sstv(it) } ?: JSONObject.NULL)

    /** A file asked for by name: only a plain name, inside [dossier], ending in one of [suffixes]; else null. */
    fun fichier(dossier: File, nom: String, vararg suffixes: String): File? {
        val n = File(nom).name
        if (n != nom || n.startsWith('.')) return null
        return File(dossier, n).takeIf { f -> f.isFile && suffixes.any { n.endsWith(it, ignoreCase = true) } }
    }
}
