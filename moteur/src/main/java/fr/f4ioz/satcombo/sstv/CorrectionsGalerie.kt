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

/**
 * The gallery's corrected pictures, kept apart from what was received.
 *
 * A picture as received is never touched. Its corrected version is a
 * separate PNG in the `corrigees/` folder beside the gallery (so the
 * gallery, its sorting and its exports never take one for a reception),
 * with a sidecar saying how it was made and its mark before and after:
 *  - **from its sound** ([Methode.SON]): the picture's own `.wav` decoded
 *    again, the whole of it at once ([RedecodageSstv]), cleaned as live
 *    ([SstvNettoyage]), then its specks ([CorrectionSstv]) — the one that
 *    counts on the owner's ISS pictures (mark 91.6 → 95.4 on average, up
 *    to 24 → 89 on a picture the live decoder garbled);
 *  - **from the picture** ([Methode.IMAGE]), when there is no sound: its
 *    specks only ([CorrectionSstv]) — a point or two;
 *  - **the copies of a slide put together** ([Methode.FUSION], [FusionSstv]),
 *    each copy decoded again from its sound when it has one: the noise of a
 *    pass is never on the same pixels twice.
 * The phone and SatMe-PC give [charge] and [ecrit] (their own PNG reading
 * and writing).
 */
class CorrectionsGalerie(
    private val charge: (File) -> ClassementGalerie.Pixels?,
    private val ecrit: (File, IntArray, Int, Int) -> Boolean,
    private val horloge: () -> Long = System::currentTimeMillis,
) {

    enum class Methode(val code: String) { SON("son"), IMAGE("image"), FUSION("fusion");
        companion object { fun de(c: String) = values().firstOrNull { it.code == c } } }

    /** A corrected version: what it is made from, how, its mark before (the best copy for a fusion) and after. */
    data class Fiche(
        /** The picture (its PNG name), or the group's id for a fusion. */
        val source: String,
        /** Its PNG in [DOSSIER]. */
        val fichier: String,
        val methode: Methode,
        val noteAvant: Int,
        val noteApres: Int,
        val quandMs: Long,
        /** A fusion's copies (PNG names), the best first; the ones left out (another slide) not there. */
        val copies: List<String> = emptyList(),
        /** Of these copies, the ones decoded again from their sound. */
        val duSon: Int = 0,
        /** Decoding from the sound: clock drift (ppm), noise in the pulses (Hz), tone scale set again (gain). */
        val derivePpm: Int = 0,
        val bruitHz: Int = 0,
        val gain: Double = 1.0,
        /** Made on its own at the picture's end ([RedecodageAuto]), not asked for. */
        val auto: Boolean = false,
    )

    /** The result of an action: the fiche, or why not ([ILLISIBLE], [SANS_SON], [RIEN_DANS_LE_SON], [ECRITURE], [UNE_COPIE]). */
    class Issue(val fiche: Fiche?, val erreur: String = "")

    companion object {
        const val DOSSIER = "corrigees"
        const val SUFFIXE = "_corrigee"
        const val SUFFIXE_FUSION = "_fusion"

        const val ILLISIBLE = "illisible"
        const val SANS_SON = "sans_son"
        const val RIEN_DANS_LE_SON = "rien_dans_le_son"
        const val ECRITURE = "ecriture"
        const val UNE_COPIE = "une_copie"
        /** [corrigeSiMieux]: a corrected version is already there (made by hand, or before). */
        const val DEJA = "deja"
        /** [corrigeSiMieux]: decoded again, it marks no better than as received: nothing kept. */
        const val PAS_MIEUX = "pas_mieux"

        fun dossier(galerie: File) = File(galerie, DOSSIER)

        /** The corrected version's PNG name of picture [png]. */
        fun nomCorrigee(png: String) = png.removeSuffix(".png") + SUFFIXE + ".png"

        /** The fused picture's PNG name of the group [id] (its id is a picture's name, or the hand's key). */
        fun nomFusion(id: String) = id.removeSuffix(".png").replace(Regex("[^A-Za-z0-9_.-]"), "-").take(120) + SUFFIXE_FUSION + ".png"

        /**
         * A processed picture (corrected, fused), never a reception: what must
         * not go where the picture as received is required (the ARISS award
         * pack asks for unmodified pictures).
         */
        fun estTraitee(f: File) = f.parentFile?.name == DOSSIER || nomValide(f.name)

        /** A name of the folder, safe to serve: a plain PNG name made here. */
        fun nomValide(n: String) = File(n).name == n && !n.startsWith('.') && n.endsWith(".png") &&
            (n.endsWith(SUFFIXE + ".png") || n.endsWith(SUFFIXE_FUSION + ".png"))

        fun encode(f: Fiche): String = buildString {
            fun put(k: String, v: Any) { val s = v.toString().replace('\n', ' ').replace('\r', ' ').trim(); if (s.isNotEmpty()) append(k).append('=').append(s).append('\n') }
            put("source", f.source); put("methode", f.methode.code); put("avant", f.noteAvant); put("apres", f.noteApres); put("quand", f.quandMs)
            if (f.copies.isNotEmpty()) put("copies", f.copies.joinToString(";"))
            if (f.duSon > 0) put("duson", f.duSon)
            if (f.methode == Methode.SON) { put("ppm", f.derivePpm); put("bruit", f.bruitHz); put("gain", String.format(java.util.Locale.US, "%.3f", f.gain)) }
            if (f.auto) put("auto", 1)
        }

        fun decode(fichier: String, texte: String?): Fiche? {
            if (texte.isNullOrBlank()) return null
            val m = texte.lines().mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it).trim() to l.substring(it + 1).trim() } }.toMap()
            val methode = Methode.de(m["methode"].orEmpty()) ?: return null
            return Fiche(m["source"].orEmpty(), fichier, methode, m["avant"]?.toIntOrNull() ?: -1, m["apres"]?.toIntOrNull() ?: -1,
                m["quand"]?.toLongOrNull() ?: 0L, m["copies"]?.split(';')?.filter { it.isNotBlank() }.orEmpty(), m["duson"]?.toIntOrNull() ?: 0,
                m["ppm"]?.toIntOrNull() ?: 0, m["bruit"]?.toIntOrNull() ?: 0, m["gain"]?.toDoubleOrNull() ?: 1.0, m["auto"] == "1")
        }

        private fun note(px: IntArray, w: Int, h: Int, hauteurMode: Int, complete: Boolean) =
            ClassementSstv.analyse(px, w, h, hauteurMode, complete).note
    }

    // ------------------------------------------------------------ reading

    private fun meta(png: File) = File(png.parentFile, png.name.removeSuffix(".png") + ".meta")

    private fun lit(galerie: File, nom: String): Fiche? {
        val f = File(dossier(galerie), nom)
        if (!f.isFile) return null
        return decode(nom, runCatching { meta(f).readText(Charsets.UTF_8) }.getOrNull())
    }

    /** Picture [png]'s corrected version, or null. */
    fun corrigee(galerie: File, png: String): Fiche? = lit(galerie, nomCorrigee(png))

    /** Group [id]'s fused picture, or null. */
    fun fusion(galerie: File, id: String): Fiche? = lit(galerie, nomFusion(id))

    /** A file of the folder to serve (a picture made here), or null. */
    fun fichier(galerie: File, nom: String): File? = if (nomValide(nom)) File(dossier(galerie), nom).takeIf { it.isFile } else null

    /**
     * What a sheet shows in the box of picture [png] (mark [note]) when it
     * takes the best versions: of its slide's versions — [png] as received,
     * the corrected version of each copy of its group [groupe], the group's
     * fusion —, the best marked; null when [png] as received is (a tie
     * keeps the picture as received).
     */
    fun pourPlanche(galerie: File, png: String, note: Int, groupe: ClassementSstv.Groupe?): Pair<File, Fiche>? {
        val copies = groupe?.images?.map { it.fileName }?.takeIf { png in it } ?: listOf(png)
        val versions = copies.mapNotNull { corrigee(galerie, it) } + listOfNotNull(groupe?.takeIf { it.images.size > 1 }?.let { fusion(galerie, it.id) })
        val f = versions.filter { it.noteApres > note }.maxByOrNull { it.noteApres } ?: return null
        return File(dossier(galerie), f.fichier) to f
    }

    /** What a box shows: the processed picture and its fiche (null: [png] as received); [repli] when the version chosen is not there (Auto shown). */
    class PourCase(val fichier: File?, val fiche: Fiche?, val repli: Boolean = false)

    /**
     * What box of picture [png] shows when [version] was chosen for it:
     * [Planche.Version.RECUE] the picture as received, [Planche.Version.CORRIGEE]
     * its corrected version, [Planche.Version.FUSION] its group's fusion;
     * [Planche.Version.AUTO] — and a version chosen that is not there (deleted,
     * never made): [pourPlanche] when the sheet takes the best ([auto]), else
     * as received, [PourCase.repli] then saying so.
     */
    fun pourCase(galerie: File, png: String, note: Int, groupe: ClassementSstv.Groupe?, version: Planche.Version, auto: Boolean): PourCase {
        fun parDefaut(repli: Boolean) = (if (auto) pourPlanche(galerie, png, note, groupe) else null)
            ?.let { PourCase(it.first, it.second, repli) } ?: PourCase(null, null, repli)
        fun de(f: Fiche?) = f?.let { PourCase(File(dossier(galerie), it.fichier), it) }
        return when (version) {
            Planche.Version.AUTO -> parDefaut(false)
            Planche.Version.RECUE -> PourCase(null, null)
            Planche.Version.CORRIGEE -> de(corrigee(galerie, png)) ?: parDefaut(true)
            Planche.Version.FUSION -> de(groupe?.takeIf { g -> g.images.size > 1 && g.images.any { it.fileName == png } }?.let { fusion(galerie, it.id) }) ?: parDefaut(true)
        }
    }

    // ------------------------------------------------------------ making

    private fun garde(galerie: File, nom: String, px: IntArray, w: Int, h: Int, f: Fiche): Boolean {
        val d = dossier(galerie); d.mkdirs()
        val png = File(d, nom)
        if (!ecrit(png, px, w, h)) return false
        return runCatching { meta(png).writeText(encode(f), Charsets.UTF_8) }.isSuccess
    }

    /** One version of a picture: its pixels, rows, and what decoding it again found. */
    class Version(val px: IntArray, val w: Int, val h: Int, val lignes: Int, val methode: Methode, val r: RedecodageSstv.Resultat? = null)

    /** Picture [s] decoded again from its own sound, cleaned as live; null without a sound or a picture in it. */
    fun duSon(galerie: File, s: SstvMeta.SstvShot): Version? {
        val mode = SstvMode.byName(s.mode) ?: return null
        val wav = SstvSon.fichier(File(galerie, s.fileName))
        if (!wav.isFile) return null
        val (pcm, rate) = SstvSon.litWav(wav) ?: return null
        val r = runCatching { RedecodageSstv.decode(pcm, rate, mode) }.getOrNull() ?: return null
        val px = SstvNettoyage.nettoie(r.pixels, mode.width, mode.height, r.lignes)
        return Version(px, mode.width, mode.height, r.lignes, Methode.SON, r)
    }

    /** The picture [s] has a sound to decode again. */
    fun aUnSon(galerie: File, s: SstvMeta.SstvShot): Boolean =
        SstvMode.byName(s.mode) != null && SstvSon.fichier(File(galerie, s.fileName)).isFile

    /**
     * Picture [s] corrected and kept: [methode] SON (from its sound), IMAGE
     * (its specks only), or null for the best of the two — the sound unless
     * it marks clearly worse (a sound cut short, another picture in it).
     */
    fun corrige(galerie: File, s: SstvMeta.SstvShot, methode: Methode? = null): Issue {
        val brute = charge(File(galerie, s.fileName)) ?: return Issue(null, ILLISIBLE)
        val mode = SstvMode.byName(s.mode)
        val hm = mode?.height ?: 0
        val avant = note(brute.px, brute.w, brute.h, hm, s.complete)
        val image = if (methode != Methode.SON) CorrectionSstv.corrige(brute.px, brute.w, brute.h) else null
        val son = if (methode != Methode.IMAGE) duSon(galerie, s) else null
        if (methode == Methode.SON && son == null) return Issue(null, if (aUnSon(galerie, s)) RIEN_DANS_LE_SON else SANS_SON)
        val sonPx = son?.let { CorrectionSstv.corrige(it.px, it.w, it.h, it.lignes, CorrectionSstv.Options(bruitHz = it.r?.bruitHz ?: 0.0)) }
        val noteSon = sonPx?.let { note(it, son.w, son.h, hm, son.lignes >= son.h) } ?: -1
        val noteImage = image?.let { note(it, brute.w, brute.h, hm, s.complete) } ?: -1
        val prendSon = sonPx != null && (methode == Methode.SON || noteSon >= noteImage - 3)
        val nom = nomCorrigee(s.fileName)
        val f = if (prendSon) {
            val r = son!!.r!!
            val h = son.lignes.coerceIn(1, son.h)
            if (!garde(galerie, nom, sonPx!!.copyOf(son.w * h), son.w, h, Fiche(s.fileName, nom, Methode.SON, avant, noteSon, horloge(),
                    derivePpm = Math.round(r.derivePpm).toInt(), bruitHz = Math.round(r.bruitHz).toInt(), gain = r.gain))) return Issue(null, ECRITURE)
            corrigee(galerie, s.fileName)
        } else {
            if (!garde(galerie, nom, image!!, brute.w, brute.h, Fiche(s.fileName, nom, Methode.IMAGE, avant, noteImage, horloge()))) return Issue(null, ECRITURE)
            corrigee(galerie, s.fileName)
        }
        return Issue(f)
    }

    /**
     * Picture [s] decoded again from its own sound and corrected, kept only
     * when it marks better than as received — for the automatic re-decoding at
     * a picture's end ([RedecodageAuto]). Never replaces a corrected version
     * already there (one made by hand stays); the picture as received is never
     * touched. Its fiche, else why not ([DEJA], [PAS_MIEUX], [SANS_SON],
     * [RIEN_DANS_LE_SON], [ILLISIBLE], [ECRITURE]).
     */
    fun corrigeSiMieux(galerie: File, s: SstvMeta.SstvShot): Issue {
        if (corrigee(galerie, s.fileName) != null) return Issue(null, DEJA)
        val brute = charge(File(galerie, s.fileName)) ?: return Issue(null, ILLISIBLE)
        val hm = SstvMode.byName(s.mode)?.height ?: 0
        val avant = note(brute.px, brute.w, brute.h, hm, s.complete)
        val son = duSon(galerie, s) ?: return Issue(null, if (aUnSon(galerie, s)) RIEN_DANS_LE_SON else SANS_SON)
        val px = CorrectionSstv.corrige(son.px, son.w, son.h, son.lignes, CorrectionSstv.Options(bruitHz = son.r?.bruitHz ?: 0.0))
        val apres = note(px, son.w, son.h, hm, son.lignes >= son.h)
        if (apres <= avant) return Issue(null, PAS_MIEUX)
        val r = son.r!!
        val h = son.lignes.coerceIn(1, son.h)
        val nom = nomCorrigee(s.fileName)
        if (corrigee(galerie, s.fileName) != null) return Issue(null, DEJA)
        if (!garde(galerie, nom, px.copyOf(son.w * h), son.w, h, Fiche(s.fileName, nom, Methode.SON, avant, apres, horloge(),
                derivePpm = Math.round(r.derivePpm).toInt(), bruitHz = Math.round(r.bruitHz).toInt(), gain = r.gain, auto = true))) return Issue(null, ECRITURE)
        return Issue(corrigee(galerie, s.fileName))
    }

    /**
     * The copies of group [g] put together and kept: each decoded again from
     * its sound when it has one (else as received), the best first, then
     * its specks taken off. Null fiche when the group has a single copy.
     */
    fun fusionne(galerie: File, g: ClassementSstv.Groupe): Issue {
        if (g.images.size < 2) return Issue(null, UNE_COPIE)
        val hm = SstvMode.byName(g.meilleure.mode)?.height ?: 0
        class C(val nom: String, val copie: FusionSstv.Copie, val note: Int, val son: Boolean)
        val cs = g.images.mapNotNull { s ->
            val v = duSon(galerie, s)
            if (v != null) C(s.fileName, FusionSstv.Copie(v.px, v.w, v.h, v.lignes), note(v.px, v.w, v.h, hm, v.lignes >= v.h), true)
            else charge(File(galerie, s.fileName))?.let { p -> C(s.fileName, FusionSstv.Copie(p.px, p.w, p.h, p.h), note(p.px, p.w, p.h, hm, s.complete), false) }
        }.sortedByDescending { it.note }
        if (cs.size < 2) return Issue(null, ILLISIBLE)
        val r = FusionSstv.fusionne(cs.map { it.copie })
        val px = CorrectionSstv.corrige(r.px, r.w, r.h, r.lignes)
        val h = r.lignes.coerceIn(1, r.h)
        val apres = note(px, r.w, r.h, hm, r.lignes >= r.h)
        val avant = g.images.maxOf { s -> g.notes[s.fileName] ?: -1 }
        val gardees = cs.filterIndexed { i, _ -> r.calages[i] != null }
        val nom = nomFusion(g.id)
        if (!garde(galerie, nom, px.copyOf(r.w * h), r.w, h, Fiche(g.id, nom, Methode.FUSION, avant, apres, horloge(),
                gardees.map { it.nom }, gardees.count { it.son }))) return Issue(null, ECRITURE)
        return Issue(fusion(galerie, g.id))
    }

    /** Picture [png]'s corrected version taken away (the picture deleted, or asked). */
    fun oublie(galerie: File, png: String) { val f = File(dossier(galerie), nomCorrigee(png)); runCatching { meta(f).delete(); f.delete() } }

    /** Group [id]'s fused picture taken away. */
    fun oublieFusion(galerie: File, id: String) { val f = File(dossier(galerie), nomFusion(id)); runCatching { meta(f).delete(); f.delete() } }
}
