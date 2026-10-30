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
 * (SSTV › Réglages). The « IA » is never on by default: picture by picture,
 * or — when the owner switches « Débruitage IA automatique » on — on the
 * pictures that [IaAuto] found noisy enough, marked so ([Reglage.iaAuto]);
 * a picture's own switch always wins.
 */
class RetouchesGalerie(
    private val charge: (File) -> ClassementGalerie.Pixels?,
    private val ecrit: (File, IntArray, Int, Int) -> Boolean,
    private val defauts: () -> Defauts = { Defauts() },
    private val horloge: () -> Long = System::currentTimeMillis,
) {
    /** The learnt denoiser when installed (SatMe-PC), else null: the « IA » switch then does nothing. */
    @Volatile var ia: DebruiteurIa? = null

    /**
     * The defaults for a picture without switches of its own: colour corrected
     * or not, with which steps; and how the IA is run ([ia]: strength, tiles,
     * before or after the colour), whoever asked it.
     */
    data class Defauts(
        val couleur: Boolean = false,
        val options: CouleurSstv.Options = CouleurSstv.Options(),
        val ia: IaSstv.Reglages = IaSstv.Reglages(),
    )

    /**
     * A picture's own switches; null follows the defaults. [ia]: the owner's
     * own choice for this picture (null: none); [iaAuto]: an IA version was
     * made by itself ([IaAuto]) — it counts while [ia] is null, so a picture
     * unticked in the viewer stays without.
     */
    data class Reglage(
        val couleur: Boolean? = null,
        val dominante: Boolean? = null,
        val chroma: Boolean? = null,
        val niveaux: Boolean? = null,
        val saturation: Boolean? = null,
        val ia: Boolean? = null,
        val iaAuto: Boolean = false,
    ) {
        val vide: Boolean get() = couleur == null && dominante == null && chroma == null && niveaux == null && saturation == null && ia == null && !iaAuto
        /** The IA asked for this picture: its own switch, else made by itself. */
        val iaVoulue: Boolean get() = ia ?: iaAuto
    }

    /** What is done to a picture: colour (with [options]) and/or IA (run as [iaReglages]). */
    class Effet(val couleur: Boolean, val options: CouleurSstv.Options, val ia: Boolean, val iaReglages: IaSstv.Reglages = IaSstv.Reglages()) {
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
        /** [IaSstv.Reglages.code] of the IA's run ("" without IA). */
        val iaCode: String = "",
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
            if (r.ia != null) append("ia=").append(if (r.ia) 1 else 0).append('\n')
            if (r.iaAuto) append("iaauto=1\n")
        }

        fun decodeReglage(t: String?): Reglage {
            if (t.isNullOrBlank()) return Reglage()
            val m = cles(t)
            fun b(k: String) = when (m[k]) { "1" -> true; "0" -> false; else -> null }
            return Reglage(b("couleur"), b("dominante"), b("chroma"), b("niveaux"), b("saturation"), b("ia"), m["iaauto"] == "1")
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
            put("ia", f.ia); put("ianom", f.iaNom); if (f.iaMs > 0) put("iams", f.iaMs); put("iareg", f.iaCode)
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
                m["ia"].orEmpty(), m["ianom"].orEmpty(), plan, m["quand"]?.toLongOrNull() ?: 0L, m["iams"]?.toLongOrNull() ?: 0L,
                // Made before the IA had settings: made as the defaults are.
                m["iareg"] ?: if (m["ia"].isNullOrEmpty()) "" else IaSstv.Reglages().code())
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
        return Effet(r.couleur ?: d.couleur, o, r.iaVoulue, d.ia.borne())
    }

    // ------------------------------------------------------------ finished versions

    /** Version [base]'s pixels measured ([CouleurSstv.corrige] with [o]), or null when unreadable. */
    fun analyse(base: File, o: CouleurSstv.Options = CouleurSstv.Options()): Pair<CouleurSstv.Resultat, ClassementGalerie.Pixels>? {
        val p = charge(base) ?: return null
        return CouleurSstv.corrige(p.px, p.w, p.h, o = o) to p
    }

    /** Version [base]'s noise (the luma's, levels: [CouleurSstv.Analyse.bruitY]), or null when unreadable. */
    fun bruit(base: File): Double? {
        val p = charge(base) ?: return null
        return CouleurSstv.analyse(p.px, p.w, p.h).bruitY
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
            fiche.couleur == couleur && (!couleur || fiche.options == e.options.code()) && fiche.ia == (if (ia && modele != null) modele.id else "") &&
            (!ia || fiche.iaCode == codeIa(e, couleur))
        return if (aJour) Finie(f, fiche) else null
    }

    /** The IA's code as kept: the order before / after the colour only counts with a colour. */
    private fun codeIa(e: Effet, couleur: Boolean): String = e.iaReglages.borne().let { if (couleur) it else it.copy(avantCouleur = false) }.code()

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
        existante(galerie, base, Effet(couleur, e.options, ia, e.iaReglages))?.let { return it }
        val p = charge(base) ?: return null
        val (px, plan, ms) = calcule(p, couleur, e.options, if (ia) modele else null, e.iaReglages) ?: return null
        val d = dossier(galerie); d.mkdirs()
        val f = File(d, nomFinie(base.name, couleur, ia))
        if (!ecrit(f, px, p.w, p.h)) return null
        val fiche = Fiche(base.name, base.length(), base.lastModified(), couleur, if (couleur) e.options.code() else "",
            if (ia) modele!!.id else "", if (ia) modele!!.nom else "", plan, horloge(), ms, if (ia) codeIa(e, couleur) else "")
        if (runCatching { meta(f).writeText(encode(fiche), Charsets.UTF_8) }.isFailure) return null
        return Finie(f, fiche)
    }

    /**
     * [p] finished: its colour set right when [couleur] (with [o]), through
     * [modele] when given (as [reglages] say: before or after the colour);
     * the pixels, the colour's plan, the IA's time (ms). Null when the IA failed.
     */
    private fun calcule(p: ClassementGalerie.Pixels, couleur: Boolean, o: CouleurSstv.Options, modele: DebruiteurIa?,
                        reglages: IaSstv.Reglages): Triple<IntArray, CouleurSstv.Plan, Long>? {
        var px = p.px
        var plan = CouleurSstv.Plan()
        var ms = 0L
        val r = reglages.borne()
        fun passeIa(): Boolean {
            val t0 = System.nanoTime()
            px = runCatching { IaSstv.applique(modele!!, px, p.w, p.h, r) }.getOrNull() ?: return false
            ms = (System.nanoTime() - t0) / 1_000_000
            return true
        }
        // The IA before the colour when asked (the colour then measured on the denoised picture), else after.
        val avant = modele != null && couleur && r.avantCouleur
        if (avant && !passeIa()) return null
        if (couleur) { val c = CouleurSstv.corrige(px, p.w, p.h, o = o); px = c.px; plan = c.plan }
        if (modele != null && !avant && !passeIa()) return null
        return Triple(px, plan, ms)
    }

    /** A preview for SSTV › Réglages' « Tester sur une image »: [base] without and with the IA. */
    class Apercu(val sans: IntArray, val avec: IntArray, val w: Int, val h: Int, val ms: Long, val couleur: Boolean)

    /**
     * Version [base] as [e] asks (its colour when on), without the IA and
     * with it as [reglages] say — nothing written (the settings being tried
     * are not kept); null without a model, or when unreadable.
     */
    fun apercu(base: File, e: Effet, reglages: IaSstv.Reglages = e.iaReglages): Apercu? {
        val modele = ia ?: return null
        val p = charge(base) ?: return null
        val couleur = e.couleur && !e.options.rien
        val sans = calcule(p, couleur, e.options, null, reglages) ?: return null
        val avec = calcule(p, couleur, e.options, modele, reglages) ?: return null
        return Apercu(sans.first, avec.first, p.w, p.h, avec.third, couleur)
    }

}
