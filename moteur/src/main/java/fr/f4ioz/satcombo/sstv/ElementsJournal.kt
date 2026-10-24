/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

import fr.f4ioz.satcombo.domain.CadreSstv
import fr.f4ioz.satcombo.domain.JournalPassage
import fr.f4ioz.satcombo.domain.OptionsJournal
import fr.f4ioz.satcombo.domain.OptionsJournal.Element
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.i18n.tf
import fr.f4ioz.satcombo.sstv.RapportPdf.ecrit
import fr.f4ioz.satcombo.station.DessinMontage
import fr.f4ioz.satcombo.station.SceneMontage
import kotlin.math.ceil

/**
 * The event report's pieces in the journal's pictures and videos: the
 * montage's drawing (or the simple chain where there is no montage, the
 * phone), the slides of the series with this pass's own picked out, the
 * pass's sky with its pictures on the track, its elevation with the slides'
 * slots, the figures, the event's sheet, SatMe's logo.
 *
 * Drawn here once for every screen: on the platform's canvas through
 * [RapportPdf.Toile] (Java2D on SatMe-PC, the phone's Canvas, SVG for the
 * page's preview), the charts by the report's own code ([RapportPdf.polaire],
 * [RapportPdf.profil], [RapportPdf.scene]) — the report's light cards laid
 * over the replay, or alone on a card of the video ([carte]). Pure: nothing
 * read, nothing fetched.
 */
object ElementsJournal {

    /** What the panels draw; null: not here (the panel is not offered). */
    class Donnees(
        /** The event's report, when the pass belongs to one (the slides, the figures). */
        val rapport: RapportEvenement.Rapport? = null,
        /** This pass: the event's, else made from the journal's own track. */
        val passage: RapportEvenement.Passage? = null,
        val elMin: Double = 0.0,
        /** The montage drawn for the pass's reception (SatMe-PC); else [chaine], the simple one. */
        val montage: DessinMontage.Dessin? = null,
        val chaine: List<List<RapportEvenement.Etape>>? = null,
        val nomChaine: String = "",
        /** The event's sheet drawn (JPEG or PNG). */
        val planche: ByteArray? = null,
        val nomPlanche: String = "",
        val logo: ByteArray? = null,
    )

    fun disponible(k: Element, d: Donnees): Boolean = when (k) {
        Element.MONTAGE -> d.montage != null || !d.chaine.isNullOrEmpty()
        Element.DIAPOS, Element.STATS -> d.rapport != null
        Element.CIEL, Element.PROFIL -> (d.passage?.trace?.size ?: 0) >= 2
        Element.PLANCHE -> d.planche != null
        Element.LOGO -> d.logo != null
    }

    /** Its word on the page and atop its card. */
    fun nom(k: Element): String = t("pj_el_" + k.code)

    /**
     * The pass as the report draws it when no event holds it: the journal's
     * own track, the pictures on it ([images]: each with its mark, its
     * slide when known) — its sky and elevation, without the timetable's slots.
     */
    fun passageDuJournal(e: JournalPassage.Entree, images: List<Pair<SstvMeta.SstvShot, Pair<Int, Int>>>): RapportEvenement.Passage {
        val trace = e.points.map { RapportEvenement.Point(it.tMs, it.az, it.el) }
        val imgs = images.sortedBy { it.first.timeMs }.map { (s, nd) ->
            val duree = SstvMode.byName(s.mode)?.let { (it.frameSeconds * 1000).toLong() } ?: 60_000L
            val de = s.timeMs - duree
            val bout = JournalPassage.segment(e, de, s.timeMs).filter { it.tMs >= de - JournalPassage.PAS_MS }
                .map { RapportEvenement.Point(it.tMs, it.az, it.el) }
            RapportEvenement.Image(s.fileName, s.timeMs, de, nd.second, 12, ClassementSstv.Origine.AUCUNE, nd.first, s.complete, bout, e.debutMs)
        }
        return RapportEvenement.Passage(e.debutMs, e.finMs, e.elMax, e.points.firstOrNull()?.az ?: 0.0, e.points.lastOrNull()?.az ?: 0.0,
            trace, emptyList(), emptyList(), emptyList(), imgs,
            if (imgs.isNotEmpty()) RapportEvenement.Statut.RECU else RapportEvenement.Statut.BAS)
    }

    // ------------------------------------------------------------ sizes

    /** A panel's own size in units ([dessine] scales it to its box). */
    private class Taille(val l: Float, val h: Float)

    private const val TITRE = 17f
    private const val P = 6f

    private fun montageScene(d: Donnees): SceneMontage.Scene? = d.montage?.let { m ->
        val s = DessinMontage.scene(m)
        // Too long to read side by side: the functions under the hardware.
        if (s.l / s.h > 4.2) DessinMontage.scene(m, empile = true) else s
    }

    // The simple chain's pieces (the report's « chaîne »), SatMe at the end.
    private const val PIECE_L = 112f
    private const val PIECE_H = 26f
    private const val FLECHE = 16f
    private fun colonnes(d: Donnees): List<List<RapportEvenement.Etape>> =
        d.chaine.orEmpty() + listOf(listOf(RapportEvenement.Etape(t("rev_logiciel"), "SatMe")))

    private fun nbDiapos(d: Donnees) = d.rapport?.diapos?.size?.coerceAtLeast(1) ?: 12
    private fun colonnesDiapos(n: Int) = if (n <= 6) n else if (n <= 12) 6 else 8

    private fun taille(k: Element, d: Donnees, m: RapportPdf.Mesure): Taille = when (k) {
        Element.CIEL -> Taille(220f + 2 * P, 220f + TITRE + P)
        Element.PROFIL -> Taille(220f + 2 * P, 110f + TITRE + P)
        Element.STATS -> Taille(4 * 92f + 3 * 6f + 2 * P, 56f + 2 * P)
        Element.DIAPOS -> {
            val n = nbDiapos(d); val c = colonnesDiapos(n); val r = ceil(n / c.toDouble()).toInt()
            Taille(c * 60f + (c - 1) * 5f + 2 * P, TITRE + r * 45f + (r - 1) * 8f + P)
        }
        Element.MONTAGE -> montageScene(d)?.let { s -> Taille(s.l.toFloat() + 2 * P, s.h.toFloat() + TITRE + P) } ?: run {
            val cols = colonnes(d)
            Taille(cols.size * PIECE_L + (cols.size - 1) * FLECHE + 2 * P, TITRE + cols.maxOf { it.size } * (PIECE_H + 4f) + P)
        }
        Element.PLANCHE -> d.planche?.let { m.taille(it) }?.let { (w, h) -> Taille(300f, 300f * h / w.coerceAtLeast(1)) } ?: Taille(300f, 212f)
        Element.LOGO -> Taille(100f, 100f)
    }

    /** Width for one high: the box keeps it wherever it is placed. */
    fun ratio(k: Element, d: Donnees, m: RapportPdf.Mesure): Float = taille(k, d, m).let { it.l / it.h }

    // ------------------------------------------------------------ over the replay

    /** One panel laid over the view: which, where (pixels of the view). */
    data class Incruste(val k: Element, val place: CadreSstv.Place)

    /**
     * The panels [o] lays over a view [w]×[h]: those asked that have
     * something to draw, each where the mouse put it (else its own place),
     * its shape kept — the logo last, on top.
     */
    fun incrustations(o: OptionsJournal, d: Donnees, w: Float, h: Float, m: RapportPdf.Mesure): List<Incruste> =
        o.incrustes.filter { disponible(it, d) }.map { k -> Incruste(k, o.cadre(k).place(w, h, ratio(k, d, m), k.min)) }

    /** Those panels drawn, the view's top left at ([ox], [oy]). */
    fun dessineIncrustations(tl: RapportPdf.Toile, m: RapportPdf.Mesure, o: OptionsJournal, d: Donnees, w: Float, h: Float,
                             ox: Float = 0f, oy: Float = 0f): List<Incruste> {
        val l = incrustations(o, d, w, h, m)
        for (i in l) dessine(tl, m, i.k, d, ox + i.place.x, oy + i.place.y, i.place.l, i.place.h)
        return l
    }

    // ------------------------------------------------------------ a card of its own

    private const val FOND_VIDEO = 0xFF0A0F18.toInt()
    private const val GRIS_VIDEO = 0xFFA0AFC3.toInt()

    /**
     * Panel [k] alone on a frame [w]×[h] of the video: its name and [sous]
     * (the satellite, the day) above, as large as the frame holds below.
     * The platform adds its mark after.
     */
    fun carte(tl: RapportPdf.Toile, m: RapportPdf.Mesure, k: Element, d: Donnees, w: Float, h: Float, sous: String) {
        val u = minOf(w / 360f, h / 480f)
        tl.rect(0f, 0f, w, h, FOND_VIDEO)
        tl.ecrit(m, RapportPdf.coupe(m, nom(k), w - 28 * u, 22 * u, true), 14 * u, 34 * u, 22 * u, RapportPdf.BLANC, true)
        tl.ecrit(m, RapportPdf.coupe(m, sous, w - 28 * u, 13 * u), 14 * u, 54 * u, 13 * u, GRIS_VIDEO)
        val haut = 70 * u; val bas = 34 * u
        val r = ratio(k, d, m)
        var pw = w - 28 * u; var ph = pw / r
        if (ph > h - haut - bas) { ph = h - haut - bas; pw = ph * r }
        dessine(tl, m, k, d, (w - pw) / 2, haut + (h - haut - bas - ph) / 2, pw, ph)
    }

    // ------------------------------------------------------------ the panels

    /** Panel [k] in the box ([x], [y], [w]×[h]), at the scale its width gives. */
    fun dessine(tl: RapportPdf.Toile, m: RapportPdf.Mesure, k: Element, d: Donnees, x: Float, y: Float, w: Float, h: Float) {
        if (!disponible(k, d) || w <= 1f || h <= 1f) return
        val ta = taille(k, d, m)
        val e = minOf(w / ta.l, h / ta.h)
        // Centred in its box when the box is not its shape.
        val x0 = x + (w - ta.l * e) / 2; val y0 = y + (h - ta.h * e) / 2
        when (k) {
            Element.LOGO -> d.logo?.let { tl.image(it, x0, y0, ta.l * e, ta.h * e) }
            Element.PLANCHE -> d.planche?.let {
                tl.rect(x0, y0, ta.l * e, ta.h * e, RapportPdf.GRILLE, RapportPdf.TRAIT, 1f * e, 4f * e)
                tl.image(it, x0, y0, ta.l * e, ta.h * e)
            }
            else -> {
                tl.rect(x0, y0, ta.l * e, ta.h * e, RapportPdf.CARTE, RapportPdf.TRAIT, 0.8f * e, 7f * e)
                when (k) {
                    Element.CIEL -> ciel(tl, m, d, x0, y0, e)
                    Element.PROFIL -> profil(tl, m, d, x0, y0, e)
                    Element.STATS -> stats(tl, m, d, x0, y0, e)
                    Element.DIAPOS -> diapos(tl, m, d, x0, y0, e)
                    Element.MONTAGE -> montage(tl, m, d, x0, y0, e, ta.l)
                    else -> {}
                }
            }
        }
    }

    private fun titre(tl: RapportPdf.Toile, m: RapportPdf.Mesure, s: String, aside: String, x0: Float, y0: Float, e: Float, largeur: Float) {
        tl.ecrit(m, RapportPdf.coupe(m, s, (largeur - 2 * P) * e, 10f * e, true), x0 + P * e, y0 + 13f * e, 10f * e, RapportPdf.ENCRE, true)
        if (aside.isBlank()) return
        val lt = m.largeur(s, 10f * e, true) + 5f * e
        val reste = (largeur - 2 * P) * e - lt
        if (reste > 20f * e) tl.ecrit(m, RapportPdf.coupe(m, aside, reste, 8.5f * e), x0 + P * e + lt, y0 + 13f * e, 8.5f * e, RapportPdf.GRIS)
    }

    private fun quand(p: RapportEvenement.Passage) = RapportEvenement.jc(p.aosMs) + " " + RapportEvenement.hm(p.aosMs) + " UTC · " + Math.round(p.elMax) + "°"

    private fun ciel(tl: RapportPdf.Toile, m: RapportPdf.Mesure, d: Donnees, x0: Float, y0: Float, e: Float) {
        val p = d.passage ?: return
        titre(tl, m, nom(Element.CIEL), quand(p), x0, y0, e, 220f + 2 * P)
        RapportPdf.polaire(tl, m, p, d.elMin, x0 + P * e, y0 + TITRE * e, e)
    }

    private fun profil(tl: RapportPdf.Toile, m: RapportPdf.Mesure, d: Donnees, x0: Float, y0: Float, e: Float) {
        val p = d.passage ?: return
        titre(tl, m, nom(Element.PROFIL), quand(p), x0, y0, e, 220f + 2 * P)
        RapportPdf.profil(tl, m, p, d.elMin, x0 + P * e, y0 + TITRE * e, e)
    }

    /** The slides of the pass, received and expected; its best mark; the series' slides. */
    data class Chiffres(val recues: Int, val prevues: Int, val meilleure: Int, val serie: Int, val nb: Int, val images: Int)

    fun chiffres(d: Donnees): Chiffres? {
        val r = d.rapport ?: return null
        val p = d.passage
        val recues = p?.recues.orEmpty()
        return Chiffres(recues.size, (p?.prevues.orEmpty() + recues).distinct().size, p?.images?.maxOfOrNull { it.note } ?: -1,
            r.stats.diapos, r.evenement.nb, p?.images?.size ?: 0)
    }

    private fun stats(tl: RapportPdf.Toile, m: RapportPdf.Mesure, d: Donnees, x0: Float, y0: Float, e: Float) {
        val c = chiffres(d) ?: return
        val tuiles = listOf(
            Triple("${c.recues}/${c.prevues}", t("jel_t_passage"), ""),
            Triple(if (c.meilleure >= 0) "${c.meilleure}" else "—", t("rev_t_meilleure"), if (c.meilleure >= 0) "/100" else ""),
            Triple("${c.serie}/${c.nb}", t("jel_t_serie"), ""),
            Triple(c.images.toString(), t("jel_t_images"), ""))
        tuiles.forEachIndexed { i, (v, lib, suite) ->
            val x = x0 + (P + i * (92f + 6f)) * e; val y = y0 + P * e
            tl.rect(x, y, 92f * e, 56f * e, RapportPdf.FOND, RapportPdf.TRAIT, 0.7f * e, 5f * e)
            tl.ecrit(m, v, x + 7f * e, y + 26f * e, 20f * e, if (i == 0 && c.recues > 0) RapportPdf.RECU else RapportPdf.ENCRE, true)
            if (suite.isNotEmpty()) tl.ecrit(m, suite, x + 7f * e + m.largeur(v, 20f * e, true) + 2f * e, y + 26f * e, 9f * e, RapportPdf.GRIS)
            tl.ecrit(m, RapportPdf.coupe(m, lib, 80f * e, 7.6f * e), x + 7f * e, y + 44f * e, 7.6f * e, RapportPdf.GRIS)
        }
    }

    private fun diapos(tl: RapportPdf.Toile, m: RapportPdf.Mesure, d: Donnees, x0: Float, y0: Float, e: Float) {
        val r = d.rapport ?: return
        val n = r.diapos.size.coerceAtLeast(1); val col = colonnesDiapos(n)
        val ici = d.passage?.recues.orEmpty().toSet()
        val largeur = col * 60f + (col - 1) * 5f + 2 * P
        titre(tl, m, t("rev_diapos"), "${r.stats.diapos}/${r.evenement.nb}" + (if (ici.isNotEmpty()) " · " + tf("jel_ce_passage", ici.size) else ""),
            x0, y0, e, largeur)
        r.diapos.forEachIndexed { i, s ->
            val x = x0 + (P + (i % col) * 65f) * e; val y = y0 + (TITRE + (i / col) * 53f) * e
            val wd = 60f * e; val hd = 45f * e
            val v = s.meilleure?.let { r.vignettes[it] }
            val ti = v?.let { m.taille(it) }
            if (v != null && ti != null) {
                tl.rect(x, y, wd, hd, RapportPdf.GRILLE, rayon = 3f * e)
                var iw = wd; var ih = wd * ti.second / ti.first.coerceAtLeast(1)
                if (ih > hd) { iw *= hd / ih; ih = hd }
                tl.image(v, x + (wd - iw) / 2, y + (hd - ih) / 2, iw, ih)
            } else {
                tl.rect(x, y, wd, hd, null, RapportPdf.MUET, 1f * e, 3f * e, floatArrayOf(3f * e, 2.2f * e))
            }
            // This pass's slides picked out: a thick frame of the received colour.
            if (s.n in ici) tl.rect(x - 1.5f * e, y - 1.5f * e, wd + 3f * e, hd + 3f * e, null, RapportPdf.RECU, 2.6f * e, 4f * e)
            val num = (if (s.origine == ClassementSstv.Origine.ESTIME) "≈${s.n}" else "${s.n}") + "/${r.evenement.nb}"
            val lw = m.largeur(num, 7f * e, true) + 6f * e
            tl.rect(x + 2.5f * e, y + 2.5f * e, lw, 10f * e, if (s.n in ici) RapportPdf.RECU else RapportPdf.NOIR_BADGE, rayon = 2.5f * e)
            tl.ecrit(m, num, x + 5.5f * e, y + 10.2f * e, 7f * e, RapportPdf.BLANC, true)
            if (s.recue && s.note >= 0) tl.ecrit(m, "${s.note}", x + wd - 3f * e, y + hd - 3f * e, 7f * e, RapportPdf.BLANC, true, ancre = 2)
        }
    }

    private fun montage(tl: RapportPdf.Toile, m: RapportPdf.Mesure, d: Donnees, x0: Float, y0: Float, e: Float, largeur: Float) {
        val s = montageScene(d)
        if (s != null) {
            titre(tl, m, t("rev_chaine"), d.montage?.nom.orEmpty(), x0, y0, e, largeur)
            RapportPdf.scene(tl, m, s, x0 + P * e, y0 + TITRE * e, e)
            return
        }
        // No montage here: the simple chain, as the report draws it.
        titre(tl, m, t("rev_chaine"), d.nomChaine, x0, y0, e, largeur)
        val cols = colonnes(d)
        val hmax = cols.maxOf { it.size } * (PIECE_H + 4f) - 4f
        var x = x0 + P * e
        cols.forEachIndexed { i, col ->
            if (i > 0) { tl.ecrit(m, "→", x + FLECHE * e / 2, y0 + (TITRE + hmax / 2 + 4f) * e, 11f * e, RapportPdf.GRIS, ancre = 1); x += FLECHE * e }
            val hc = col.size * (PIECE_H + 4f) - 4f
            var y = y0 + (TITRE + (hmax - hc) / 2) * e
            for (p in col) {
                tl.rect(x, y, PIECE_L * e, PIECE_H * e, RapportPdf.FOND, if (i == cols.size - 1) RapportPdf.S1 else RapportPdf.TRAIT, 0.75f * e, 5f * e)
                tl.ecrit(m, RapportPdf.coupe(m, p.genre.uppercase(), (PIECE_L - 12f) * e, 6f * e), x + 6f * e, y + 9.5f * e, 6f * e, RapportPdf.GRIS)
                tl.ecrit(m, RapportPdf.coupe(m, p.nom, (PIECE_L - 12f) * e, 8f * e, true), x + 6f * e, y + 20f * e, 8f * e, RapportPdf.ENCRE, true)
                y += (PIECE_H + 4f) * e
            }
            x += PIECE_L * e
        }
    }

    // ------------------------------------------------------------ the page's preview

    /** Panel [k] as SVG, [largeur] units wide (the page's preview over the replay). */
    fun svg(k: Element, d: Donnees, largeur: Float = 480f): String? {
        if (!disponible(k, d)) return null
        val m0 = ToileSvg(1f, 1f)
        val r = ratio(k, d, m0)
        val h = largeur / r
        val tl = ToileSvg(largeur, h)
        dessine(tl, tl, k, d, 0f, 0f, largeur, h)
        return tl.svg(nom(k))
    }
}
