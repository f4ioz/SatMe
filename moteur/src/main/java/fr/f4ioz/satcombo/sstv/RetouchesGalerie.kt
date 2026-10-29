/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

import java.io.File
import java.util.Locale

/**
 * The finish of the gallery's pictures, asked picture by picture: its
 * colour set right ([CouleurSstv], nothing invented) and, when asked, a
 * learnt denoiser ([DebruiteurIa], always said « IA »).
 *
 * Like [CorrectionsGalerie], nothing received is touched: a picture's
 * switches are a small file in `corrigees/` (`<picture>.retouche`), its
 * finished versions PNGs beside (`<version>_couleur.png`, `_ia.png`,
 * `_couleur_ia.png`, made from whichever version is shown — as received,
 * corrected, fused —, each with its sidecar: what it was made from, how,
 * what was measured), made again when what they come from or the switches
 * change. A picture without switches of its own follows the defaults
 * (SSTV › Réglages), which never turn the « IA » on.
 */
class RetouchesGalerie(
    private val charge: (File) -> ClassementGalerie.Pixels?,
    private val ecrit: (File, IntArray, Int, Int) -> Boolean,
    private val defauts: () -> Defauts = { Defauts() },
    private val horloge: () -> Long = System::currentTimeMillis,
) {
    /** The learnt denoiser when installed (SatMe-PC), else null: the « IA » switch then does nothing. */
    @Volatile var ia: DebruiteurIa? = null

    /** The defaults for a picture without switches of its own: colour corrected or not, with which steps. */
    data class Defauts(val couleur: Boolean = false, val options: CouleurSstv.Options = CouleurSstv.Options())

    /** A picture's own switches; null follows the defaults. « IA » is the picture's own only. */
    data class Reglage(
        val couleur: Boolean? = null,
        val dominante: Boolean? = null,
        val chroma: Boolean? = null,
        val niveaux: Boolean? = null,
        val saturation: Boolean? = null,
        val ia: Boolean = false,
    ) {
        val vide: Boolean get() = couleur == null && dominante == null && chroma == null && niveaux == null && saturation == null && !ia
    }

    /** What is done to a picture: colour (with [options]) and/or IA. */
    class Effet(val couleur: Boolean, val options: CouleurSstv.Options, val ia: Boolean) {
        val actif: Boolean get() = (couleur && !options.rien) || ia
    }

    /** A finished version's sidecar. */
    data class Fiche(
        /** The PNG it was made from (in the gallery or in `corrigees/`), its size and date then. */
        val source: String,
        val taille: Long,
        val date: Long,
        val couleur: Boolean,
        /** [CouleurSstv.Options.code] of the steps asked. */
        val options: String,
        /** The model's id ([DebruiteurIa.id]), "" without IA. */
        val ia: String,
        val iaNom: String,
        val plan: CouleurSstv.Plan,
        val quandMs: Long,
        /** How long the IA took, ms. */
        val iaMs: Long = 0,
    )

    /** A finished version: its file and how it was made. */
    class Finie(val fichier: File, val fiche: Fiche)

    companion object {
        const val SUFFIXE_COULEUR = "_couleur"
        const val SUFFIXE_IA = "_ia"
        private const val REGLAGE = ".retouche"

        /** A finished version's PNG name, of version [base] (a PNG name). */
        fun nomFinie(base: String, couleur: Boolean, ia: Boolean): String =
            base.removeSuffix(".png") + (if (couleur) SUFFIXE_COULEUR else "") + (if (ia) SUFFIXE_IA else "") + ".png"

        /** A name made here, safe to serve from `corrigees/`. */
        fun nomValide(n: String) = File(n).name == n && !n.startsWith('.') &&
            (n.endsWith(SUFFIXE_COULEUR + ".png") || n.endsWith(SUFFIXE_IA + ".png"))

        fun encodeReglage(r: Reglage): String = buildString {
            fun b(k: String, v: Boolean?) { if (v != null) append(k).append('=').append(if (v) 1 else 0).append('\n') }
            b("couleur", r.couleur); b("dominante", r.dominante); b("chroma", r.chroma); b("niveaux", r.niveaux); b("saturation", r.saturation)
            if (r.ia) append("ia=1\n")
        }

        fun decodeReglage(t: String?): Reglage {
            if (t.isNullOrBlank()) return Reglage()
            val m = cles(t)
            fun b(k: String) = when (m[k]) { "1" -> true; "0" -> false; else -> null }
            return Reglage(b("couleur"), b("dominante"), b("chroma"), b("niveaux"), b("saturation"), m["ia"] == "1")
        }

        /** Picture [png]'s own switches, as kept beside the gallery [galerie] (its badges in the gallery). */
        fun reglageDe(galerie: File, png: String): Reglage =
            decodeReglage(runCatching { File(CorrectionsGalerie.dossier(galerie), png.removeSuffix(".png") + REGLAGE).takeIf { it.isFile }?.readText(Charsets.UTF_8) }.getOrNull())

        /**
         * Picture [png]'s switches and finished versions taken away (the
         * picture deleted): those of it as received and of its corrected
         * version (a fusion's stay: the other copies made it too).
         */
        fun oublie(galerie: File, png: String) {
            val d = CorrectionsGalerie.dossier(galerie)
            runCatching { File(d, png.removeSuffix(".png") + REGLAGE).delete() }
            for (b in listOf(png, CorrectionsGalerie.nomCorrigee(png))) for (c in listOf(true, false)) for (i in listOf(true, false)) if (c || i) {
                val f = File(d, nomFinie(b, c, i)); runCatching { File(d, f.name.removeSuffix(".png") + ".meta").delete(); f.delete() }
            }
        }

        private fun cles(t: String) = t.lines().mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it).trim() to l.substring(it + 1).trim() } }.toMap()

        private fun d(v: Double) = String.format(Locale.US, "%.2f", v)

        fun encode(f: Fiche): String = buildString {
            fun put(k: String, v: Any) { val s = v.toString().replace('\n', ' ').replace('\r', ' ').trim(); if (s.isNotEmpty()) append(k).append('=').append(s).append('\n') }
            put("source", f.source); put("taille", f.taille); put("date", f.date); put("couleur", if (f.couleur) 1 else 0); put("options", f.options)
            put("ia", f.ia); put("ianom", f.iaNom); if (f.iaMs > 0) put("iams", f.iaMs)
            val p = f.plan
            put("plan", listOf(p.dCb, p.dCr, p.dY, p.cbX, p.cbY, p.crX, p.crY, p.noir, p.gain, p.saturation).joinToString(";") { d(it) })
            put("quand", f.quandMs)
        }

        fun decode(t: String?): Fiche? {
            if (t.isNullOrBlank()) return null
            val m = cles(t)
            val source = m["source"] ?: return null
            val v = m["plan"]?.split(';')?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 10 }
            val plan = if (v == null) CouleurSstv.Plan() else CouleurSstv.Plan(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9])
            return Fiche(source, m["taille"]?.toLongOrNull() ?: -1, m["date"]?.toLongOrNull() ?: -1, m["couleur"] == "1", m["options"].orEmpty(),
                m["ia"].orEmpty(), m["ianom"].orEmpty(), plan, m["quand"]?.toLongOrNull() ?: 0L, m["iams"]?.toLongOrNull() ?: 0L)
        }
    }

    private fun dossier(galerie: File) = CorrectionsGalerie.dossier(galerie)
    private fun fichierReglage(galerie: File, png: String) = File(dossier(galerie), png.removeSuffix(".png") + REGLAGE)
    private fun meta(png: File) = File(png.parentFile, png.name.removeSuffix(".png") + ".meta")

    // ------------------------------------------------------------ switches

    /** Picture [png]'s own switches. */
    fun reglage(galerie: File, png: String): Reglage = reglageDe(galerie, png)

    /** Picture [png]'s own switches set to [r] (all default: its file taken away). */
    fun change(galerie: File, png: String, r: Reglage): Boolean {
        if (File(png).name != png || !png.endsWith(".png")) return false
        val f = fichierReglage(galerie, png)
        return runCatching {
            if (r.vide) { f.delete(); true } else { f.parentFile.mkdirs(); f.writeText(encodeReglage(r), Charsets.UTF_8); true }
        }.getOrDefault(false)
    }

    /** What is done to picture [png]: its switches over the defaults. */
    fun effet(galerie: File, png: String): Effet = effet(reglage(galerie, png))

    fun effet(r: Reglage): Effet {
        val d = defauts()
        val o = CouleurSstv.Options(r.dominante ?: d.options.dominante, r.chroma ?: d.options.chroma, r.niveaux ?: d.options.niveaux, r.saturation ?: d.options.saturation)
        return Effet(r.couleur ?: d.couleur, o, r.ia)
    }

    // ------------------------------------------------------------ finished versions

    /** Version [base]'s pixels measured ([CouleurSstv.corrige] with [o]), or null when unreadable. */
    fun analyse(base: File, o: CouleurSstv.Options = CouleurSstv.Options()): Pair<CouleurSstv.Resultat, ClassementGalerie.Pixels>? {
        val p = charge(base) ?: return null
        return CouleurSstv.corrige(p.px, p.w, p.h, o = o) to p
    }

    /** Version [base]'s finished file as [e] asks, if made and still up to date; null otherwise. */
    fun existante(galerie: File, base: File, e: Effet): Finie? {
        val couleur = e.couleur && !e.options.rien
        val ia = e.ia && this.ia != null
        if (!couleur && !ia) return null
        val f = File(dossier(galerie), nomFinie(base.name, couleur, ia))
        if (!f.isFile || !base.isFile) return null
        val fiche = decode(runCatching { meta(f).readText(Charsets.UTF_8) }.getOrNull()) ?: return null
        val modele = this.ia
        val aJour = fiche.source == base.name && fiche.taille == base.length() && fiche.date == base.lastModified() &&
            fiche.couleur == couleur && (!couleur || fiche.options == e.options.code()) && fiche.ia == (if (ia && modele != null) modele.id else "")
        return if (aJour) Finie(f, fiche) else null
    }

    /**
     * Version [base] (a PNG of the gallery or of `corrigees/`) finished as
     * [e] asks: made (or taken as made, when up to date) and kept beside;
     * null when nothing is asked, or it cannot be read. The IA only when
     * [avecIa] and a model is there (a second or three: a sheet with
     * twelve pictures takes its time the first time).
     */
    fun finie(galerie: File, base: File, e: Effet, avecIa: Boolean = true): Finie? {
        val modele = ia
        val ia = e.ia && avecIa && modele != null
        val couleur = e.couleur && !e.options.rien
        if (!couleur && !ia) return null
        existante(galerie, base, Effet(couleur, e.options, ia))?.let { return it }
        val p = charge(base) ?: return null
        var px = p.px
        var plan = CouleurSstv.Plan()
        if (couleur) { val r = CouleurSstv.corrige(p.px, p.w, p.h, o = e.options); px = r.px; plan = r.plan }
        var ms = 0L
        if (ia) {
            val t0 = System.nanoTime()
            px = runCatching { IaSstv.applique(modele!!, px, p.w, p.h) }.getOrNull() ?: return null
            ms = (System.nanoTime() - t0) / 1_000_000
        }
        val d = dossier(galerie); d.mkdirs()
        val f = File(d, nomFinie(base.name, couleur, ia))
        if (!ecrit(f, px, p.w, p.h)) return null
        val fiche = Fiche(base.name, base.length(), base.lastModified(), couleur, if (couleur) e.options.code() else "",
            if (ia) modele!!.id else "", if (ia) modele!!.nom else "", plan, horloge(), ms)
        if (runCatching { meta(f).writeText(encode(fiche), Charsets.UTF_8) }.isFailure) return null
        return Finie(f, fiche)
    }

}
