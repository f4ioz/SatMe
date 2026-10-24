/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

import fr.f4ioz.satcombo.i18n.I18n
import fr.f4ioz.satcombo.i18n.Lang
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.i18n.tf
import fr.f4ioz.satcombo.sstv.RapportEvenement.Statut
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The event's report as a PDF: the same report as the page
 * ([RapportEvenement.html]), laid out here on A4 pages, drawn by the
 * platform (PDFBox on the PC, the phone's own PdfDocument) through [Toile]:
 * one layout, whichever screen made it.
 *
 * Portrait A4, 12 mm margins; on each page the event's name and « page n/N »
 * above, the station and the day below. A card never splits across pages,
 * save the long ones (the slides, the table, the skies, the chain) which
 * break between their rows, their title (and the table's head) said again.
 * The charts are drawn as lines, the pictures as they came (JPEG).
 *
 * A pure function of [RapportEvenement.Rapport]: nothing read, nothing
 * fetched, nothing sent.
 */
object RapportPdf {

    /** A4 in points (1/72 in). */
    const val LARGEUR = 595f
    const val HAUTEUR = 842f
    private const val MARGE = 34f
    private const val W = LARGEUR - 2 * MARGE
    /** A card's padding, the gap between cards. */
    private const val P = 9f
    private const val ECART = 9f
    /** Where the content begins and ends (the running head and foot outside). */
    private const val HAUT = MARGE + 20f
    private const val BAS = HAUTEUR - MARGE - 14f
    private const val WC = W - 2 * P

    /** What the platform measures: a text's width, a picture's size. */
    interface Mesure {
        fun largeur(s: String, taille: Float, gras: Boolean = false): Float
        /** A picture's size in pixels (JPEG or PNG), null unreadable. */
        fun taille(image: ByteArray): Pair<Int, Int>?
    }

    /** A page to draw on, in points from its top left corner; colours ARGB, opaque. */
    interface Toile {
        /** [s] from [x] on its baseline [y]. */
        fun texte(s: String, x: Float, y: Float, taille: Float, couleur: Int, gras: Boolean = false)
        /** A line through [xy] (x0, y0, x1, y1…), closed and filled with [fond] when given. */
        fun ligne(xy: FloatArray, couleur: Int?, epaisseur: Float, pointilles: FloatArray? = null, rond: Boolean = false,
                  ferme: Boolean = false, fond: Int? = null)
        fun rect(x: Float, y: Float, w: Float, h: Float, fond: Int?, trait: Int? = null, epaisseur: Float = 0.75f,
                 rayon: Float = 0f, pointilles: FloatArray? = null)
        fun cercle(x: Float, y: Float, r: Float, fond: Int?, trait: Int? = null, epaisseur: Float = 0.75f)
        /** A picture (JPEG or PNG) stretched to the box; nothing when it cannot be read. */
        fun image(octets: ByteArray, x: Float, y: Float, w: Float, h: Float)
    }

    /** The platform's document: each [page] a new A4 page, the one before finished. */
    interface Document : Mesure {
        fun page(): Toile
    }

    // The page's colours (the report's light theme; translucent ones mixed on white).
    private const val ENCRE = 0xFF1D2329.toInt()
    private const val GRIS = 0xFF5D6873.toInt()
    private const val TRAIT = 0xFFD9DCD6.toInt()
    private const val GRILLE = 0xFFE4E6E1.toInt()
    private const val CARTE = 0xFFFCFCFB.toInt()
    private const val FOND = 0xFFF4F5F2.toInt()
    private const val BON = 0xFF0CA30C.toInt()
    private const val RATE = 0xFFD03B3B.toInt()
    private const val AVENIR = 0xFF8A94A0.toInt()
    private const val MUET = 0xFFC3C8CC.toInt()
    private const val S1 = 0xFF2A78D6.toInt()
    private const val RECU = 0xFFEB6834.toInt()
    private const val TETE = 0xFF123F7A.toInt()
    private const val TETE_SOUS = 0xFFD6E4F5.toInt()
    private const val AMBRE = 0xFFFFC21A.toInt()
    private const val BLANC = 0xFFFFFFFF.toInt()
    private const val NOIR_BADGE = 0xFF2B2F33.toInt()

    /** [c] at opacity [a] over white. */
    private fun mix(c: Int, a: Double): Int {
        fun k(sh: Int) = (255 - ((255 - (c shr sh and 0xFF)) * a)).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (k(16) shl 16) or (k(8) shl 8) or k(0)
    }

    private fun couleur(s: Statut) = when (s) {
        Statut.RECU -> BON; Statut.RECU_BAS -> S1; Statut.ATTENDU_RIEN -> RATE; Statut.BAS -> MUET; Statut.A_VENIR -> AVENIR
    }

    private val TIRETS = floatArrayOf(3f, 2.2f)

    /** Draws [r] on [d]'s pages; the number of pages. */
    fun dessine(r: RapportEvenement.Rapport, d: Document): Int {
        val mise = Mise(r, d)
        val pages = pagine(mise.blocs())
        pages.forEachIndexed { i, places ->
            val tl = d.page()
            mise.entete(tl, i + 1, pages.size)
            // The cards' frames behind their blocks: one per card and page.
            var k = 0
            while (k < places.size) {
                val c = places[k].b.carte
                var j = k
                while (j + 1 < places.size && places[j + 1].b.carte == c) j++
                if (c != 0) {
                    val y0 = places[k].y - P; val y1 = places[j].y + places[j].b.h + P
                    tl.rect(MARGE, y0, W, y1 - y0, CARTE, TRAIT, 0.75f, 7f)
                }
                k = j + 1
            }
            for (pl in places) pl.b.dessin(tl, pl.y)
        }
        return pages.size
    }

    /** A piece of the layout: [h] high, in card [carte] (0: no frame), kept with the next one, said again atop a new page ([reprise]). */
    private class Bloc(val h: Float, val carte: Int, val gardeSuivant: Boolean = false, val reprise: Bloc? = null,
                       val dessin: Toile.(y: Float) -> Unit)

    private class Place(val b: Bloc, val y: Float)

    /** The blocks spread over pages: none cut, a card broken only between its blocks. */
    private fun pagine(blocs: List<Bloc>): List<List<Place>> {
        val pages = mutableListOf(mutableListOf<Place>())
        var y = HAUT
        var ouverte = 0
        for ((i, b) in blocs.withIndex()) {
            if (ouverte != 0 && b.carte != ouverte) { y += P; ouverte = 0 }
            val page = pages.last()
            var debut = y + if (ouverte == 0) (if (page.isEmpty()) 0f else ECART) + (if (b.carte != 0) P else 0f) else 0f
            var besoin = b.h
            var j = i
            while (blocs[j].gardeSuivant && j + 1 < blocs.size) { j++; besoin += blocs[j].h }
            if (b.carte != 0) besoin += P
            // A card that holds on one page is never cut: all of it on the next one.
            if (ouverte == 0 && b.carte != 0) {
                var tout = P
                var k = i
                while (k < blocs.size && blocs[k].carte == b.carte) { tout += blocs[k].h; k++ }
                if (P + tout <= BAS - HAUT) besoin = maxOf(besoin, tout)
            }
            if (debut + besoin > BAS && page.isNotEmpty()) {
                val suite = ouverte != 0 && b.carte == ouverte
                pages += mutableListOf<Place>()
                debut = HAUT + if (b.carte != 0) P else 0f
                if (suite && b.reprise != null) { pages.last() += Place(b.reprise, debut); debut += b.reprise.h }
            }
            pages.last() += Place(b, debut)
            y = debut + b.h
            ouverte = b.carte
        }
        return pages.filter { it.isNotEmpty() }.ifEmpty { listOf(emptyList()) }
    }

    /** The report laid out with the platform's measures. */
    private class Mise(val r: RapportEvenement.Rapport, val m: Mesure) {
        private var cartes = 0
        private fun carte() = ++cartes

        private fun l(s: String, taille: Float, gras: Boolean = false) = m.largeur(s, taille, gras)

        /** [s] anchored left (0), in the middle (1) or right (2) of [x]. */
        fun Toile.ecrit(s: String, x: Float, y: Float, taille: Float, couleur: Int = ENCRE, gras: Boolean = false, ancre: Int = 0) {
            if (s.isEmpty()) return
            val dx = when (ancre) { 1 -> l(s, taille, gras) / 2; 2 -> l(s, taille, gras); else -> 0f }
            texte(s, x - dx, y, taille, couleur, gras)
        }

        /** [s] cut to fit [w], « … » at its end. */
        fun coupe(s: String, w: Float, taille: Float, gras: Boolean = false): String {
            if (l(s, taille, gras) <= w) return s
            var n = s.length
            while (n > 1 && l(s.take(n) + "…", taille, gras) > w) n--
            return s.take(n).trimEnd() + "…"
        }

        /** [s] in lines of [w] at most. */
        fun lignes(s: String, w: Float, taille: Float, gras: Boolean = false): List<String> {
            val out = ArrayList<String>()
            for (para in s.split('\n')) {
                var ligne = ""
                for (mot in para.split(' ').filter { it.isNotEmpty() }) {
                    val essai = if (ligne.isEmpty()) mot else "$ligne $mot"
                    if (l(essai, taille, gras) <= w || ligne.isEmpty() && l(mot, taille, gras) <= w) { ligne = essai; continue }
                    if (ligne.isNotEmpty()) out += ligne
                    // A word longer than the line: cut where it must.
                    var reste = mot
                    while (l(reste, taille, gras) > w && reste.length > 1) {
                        var n = reste.length - 1
                        while (n > 1 && l(reste.take(n), taille, gras) > w) n--
                        out += reste.take(n); reste = reste.drop(n)
                    }
                    ligne = reste
                }
                out += ligne
            }
            return out
        }

        private fun interligne(taille: Float) = taille * 1.32f

        /** A paragraph of card [carte], [w] wide from [x]. */
        fun paragraphe(s: String, carte: Int, taille: Float = 8f, couleur: Int = ENCRE, gras: Boolean = false,
                       x: Float = MARGE + P, w: Float = WC, avant: Float = 0f): Bloc {
            val ls = lignes(s, w, taille, gras)
            val ih = interligne(taille)
            return Bloc(avant + ls.size * ih, carte) { y ->
                ls.forEachIndexed { i, t -> ecrit(t, x, y + avant + taille + i * ih, taille, couleur, gras) }
            }
        }

        /** A card's title, and its grey aside. */
        fun titre(s: String, carte: Int, aside: String = "", suite: Boolean = false): Bloc {
            val a = (if (aside.isNotBlank()) "· $aside" else "") + if (suite) " " + t("rev_pdf_suite") else ""
            return Bloc(17f, carte, gardeSuivant = true) { y ->
                ecrit(s, MARGE + P, y + 11f, 11f, ENCRE, true)
                if (a.isNotBlank()) ecrit(coupe(a.trim(), WC - l(s, 11f, true) - 5f, 9f), MARGE + P + l(s, 11f, true) + 5f, y + 11f, 9f, GRIS)
            }
        }

        /** Two blocks as one (the title and the table's head said again). */
        fun ensemble(a: Bloc, b: Bloc): Bloc = Bloc(a.h + b.h, a.carte, gardeSuivant = true) { y -> a.dessin(this, y); b.dessin(this, y + a.h) }

        /** The running head and foot of page [n] of [total]. */
        fun entete(tl: Toile, n: Int, total: Int) = with(tl) {
            val e = r.evenement
            val droite = tf("rev_pdf_page", n, total)
            ecrit(droite, LARGEUR - MARGE, MARGE + 7f, 7.5f, GRIS, ancre = 2)
            ecrit(coupe(tf("rev_titre_page", e.nom) + " · " + e.satNom, W - l(droite, 7.5f) - 12f, 7.5f, true), MARGE, MARGE + 7f, 7.5f, GRIS, true)
            ligne(floatArrayOf(MARGE, MARGE + 11f, LARGEUR - MARGE, MARGE + 11f), TRAIT, 0.6f)
            val pied = HAUTEUR - MARGE + 2f
            ligne(floatArrayOf(MARGE, pied - 9f, LARGEUR - MARGE, pied - 9f), TRAIT, 0.6f)
            val st = listOf(r.station.indicatif, r.station.nom, r.station.locator).filter { it.isNotBlank() }.joinToString(" · ")
            ecrit(st, MARGE, pied, 7f, GRIS)
            ecrit(r.logiciel + " · " + tf("rev_fait_le", RapportEvenement.jour(r.faitMs) + " " + RapportEvenement.hm(r.faitMs) + " UTC"),
                LARGEUR - MARGE, pied, 7f, GRIS, ancre = 2)
        }

        fun blocs(): List<Bloc> {
            val out = ArrayList<Bloc>()
            out += tete()
            out += tuiles()
            r.planche?.let { out += planche(it) }
            out += diapos()
            out += chronologie()
            out += tableau()
            out += ciels()
            out += horaire()
            r.chaine?.let { out += chaine(it) }
            out += pied()
            return out
        }

        // ---------------------------------------------------------- the header, the numbers

        private fun tete(): Bloc {
            val e = r.evenement
            val wd = 150f
            val wg = W - 28f - wd
            val titre = lignes(e.nom, wg, 17f, true)
            val sous = lignes(e.satNom + (if (e.catnum > 0) " · NORAD ${e.catnum}" else "") + " · " +
                RapportEvenement.jour(e.debutMs) + " " + RapportEvenement.hm(e.debutMs) + " → " + RapportEvenement.jour(e.finMs) + " " +
                RapportEvenement.hm(e.finMs) + " UTC", wg, 8.5f)
            val h = 14f + 10f + titre.size * 20f + sous.size * 11f + 8f
            return Bloc(maxOf(h, 66f), 0) { y ->
                rect(MARGE, y, W, maxOf(h, 66f), TETE, rayon = 9f)
                ecrit(t("rev_genre_" + EvenementSstv.genreCle(e.genre)).uppercase(), MARGE + 14f, y + 18f, 7.5f, AMBRE, true)
                titre.forEachIndexed { i, s -> ecrit(s, MARGE + 14f, y + 38f + i * 20f, 17f, BLANC, true) }
                sous.forEachIndexed { i, s -> ecrit(s, MARGE + 14f, y + 38f + (titre.size - 1) * 20f + 15f + i * 11f, 8.5f, TETE_SOUS) }
                val xd = LARGEUR - MARGE - 14f
                var yd = y + 22f
                if (r.station.indicatif.isNotBlank()) { ecrit(r.station.indicatif, xd, yd + 4f, 15f, BLANC, true, ancre = 2); yd += 17f }
                val qui = listOf(r.station.nom, r.station.locator).filter { it.isNotBlank() }.joinToString(" · ")
                if (qui.isNotBlank()) { ecrit(coupe(qui, wd, 8.5f), xd, yd, 8.5f, TETE_SOUS, ancre = 2); yd += 11f }
                ecrit(tf("rev_fait_le", RapportEvenement.jour(r.faitMs) + " " + RapportEvenement.hm(r.faitMs) + " UTC"), xd, yd, 8.5f, TETE_SOUS, ancre = 2)
            }
        }

        private fun tuiles(): Bloc {
            val s = r.stats; val e = r.evenement
            val l = listOf(
                Triple("${s.diapos}/${e.nb}", t("rev_t_diapos"), if (s.diaposEstimees > 0) tf("rev_t_dont_estimees", s.diaposEstimees) else ""),
                Triple(s.images.toString(), t("rev_t_images"), tf("rev_t_completes", s.completes)),
                Triple("${s.passagesRecus}/${s.passagesHauts}", tf("rev_t_passages", RapportEvenement.el(r.elMin)), if (s.passagesRates > 0) tf("rev_t_rates", s.passagesRates) else ""),
                Triple(if (s.noteMax >= 0) "${s.noteMax}" else "—", t("rev_t_meilleure"), if (s.noteMoyenne >= 0) tf("rev_t_moyenne", s.noteMoyenne) else ""))
            val g = 8f
            val wt = (W - 3 * g) / 4
            val textes = l.map { (_, a, b) -> lignes(a, wt - 18f, 7.5f) to (if (b.isBlank()) emptyList() else lignes(b, wt - 18f, 7.5f)) }
            val h = 10f + 20f + textes.maxOf { (a, b) -> (a.size + b.size) * 9.5f } + 6f
            return Bloc(h, 0) { y ->
                l.forEachIndexed { i, (v, _, _) ->
                    val x = MARGE + i * (wt + g)
                    rect(x, y, wt, h, CARTE, TRAIT, 0.75f, 7f)
                    ecrit(v, x + 9f, y + 25f, 18f, ENCRE, true)
                    val (a, b) = textes[i]
                    var yy = y + 38f
                    a.forEach { ecrit(it, x + 9f, yy, 7.5f, GRIS); yy += 9.5f }
                    b.forEach { ecrit(it, x + 9f, yy, 7.5f, GRIS); yy += 9.5f }
                }
            }
        }

        // ---------------------------------------------------------- the sheet

        private fun planche(o: ByteArray): List<Bloc> {
            val c = carte()
            val (iw, ih) = m.taille(o) ?: (4 to 3)
            var w = WC; var h = w * ih / iw
            val max = BAS - HAUT - 2 * P - 17f - 2f
            if (h > max) { w *= max / h; h = max }
            return listOf(titre(t("rev_planche"), c, r.nomPlanche), Bloc(h + 2f, c) { y ->
                val x = MARGE + P + (WC - w) / 2
                rect(x, y + 2f, w, h, GRILLE, rayon = 0f)
                image(o, x, y + 2f, w, h)
            })
        }

        // ---------------------------------------------------------- the slides

        private fun diapos(): List<Bloc> {
            val c = carte()
            val nb = r.evenement.nb
            val n = 6
            val g = 7f
            val wd = (WC - (n - 1) * g) / n
            val hi = wd * 3 / 4
            val tc = 6.6f
            val ih = interligne(tc)
            val out = arrayListOf(titre(t("rev_diapos"), c))
            fun legende(d: RapportEvenement.Diapo): List<Pair<String, Int>> =
                if (d.recue) listOf((if (d.note >= 0) "${d.note}/100" else "—") + " · " + tf("rev_copies", d.copies) to ENCRE) +
                    lignes(RapportEvenement.origineLib(d.origine) + " · " + tf("rev_sur_passages", d.passages), wd, tc).map { it to GRIS }
                else listOf("✗ " + t("rev_non_recue") to RATE) +
                    lignes(if (d.attendue.isEmpty()) t("rev_jamais_attendue") else tf("rev_attendue", d.attendue.take(3).joinToString(", ") {
                        RapportEvenement.jc(it.aosMs) + " " + RapportEvenement.hm(it.aosMs) + " (" + it.elMax.roundToInt() + "°)" }), wd, tc).map { it to GRIS }
            val rangs = r.diapos.chunked(n)
            rangs.forEachIndexed { k, rang ->
                val leg = rang.map { legende(it) }
                val h = hi + 4f + leg.maxOf { it.size } * ih + (if (k < rangs.size - 1) 6f else 0f)
                out += Bloc(h, c) { y ->
                    rang.forEachIndexed { i, d ->
                        val x = MARGE + P + i * (wd + g)
                        val v = d.meilleure?.let { r.vignettes[it] }
                        val ti = v?.let { m.taille(it) }
                        if (v != null && ti != null) {
                            rect(x, y, wd, hi, GRILLE, rayon = 4f)
                            // Kept whole in its 4:3 box (SSTV pictures are 4:3 or 5:4).
                            var w = wd; var hh = wd * ti.second / ti.first
                            if (hh > hi) { w *= hi / hh; hh = hi }
                            image(v, x + (wd - w) / 2, y + (hi - hh) / 2, w, hh)
                        } else {
                            rect(x, y, wd, hi, null, MUET, 1f, 4f, TIRETS)
                            if (!d.recue) ecrit(t("rev_non_recue"), x + wd / 2, y + hi / 2 + 2.5f, 7f, GRIS, ancre = 1)
                        }
                        val num = (if (d.origine == ClassementSstv.Origine.ESTIME) "≈${d.n}" else "${d.n}") + "/$nb"
                        val lw = l(num, 7.5f, true) + 7f
                        rect(x + 3f, y + 3f, lw, 11f, if (d.origine == ClassementSstv.Origine.ESTIME) GRIS else NOIR_BADGE, rayon = 3f)
                        if (d.origine == ClassementSstv.Origine.MAIN) rect(x + 3f, y + 12.5f, lw, 1.5f, AMBRE)
                        ecrit(num, x + 6.5f, y + 11.4f, 7.5f, BLANC, true)
                        leg[i].forEachIndexed { j, (s, col) -> ecrit(coupe(s, wd, tc, j == 0), x, y + hi + 4f + tc + j * ih, tc, col, j == 0) }
                    }
                }
            }
            out += paragraphe(t("rev_aide_origine"), c, 7f, GRIS, avant = 4f)
            return out
        }

        // ---------------------------------------------------------- the timeline

        private fun chronologie(): List<Bloc> {
            val c = carte()
            val e = r.evenement
            val Wg = WC; val Hg = 132f; val G = 24f; val D = 6f; val Hh = 6f; val B = 20f
            val t0 = minOf(e.debutMs, r.passages.minOfOrNull { it.aosMs } ?: e.debutMs)
            val t1 = maxOf(e.finMs, r.passages.maxOfOrNull { it.losMs } ?: e.finMs, t0 + 3_600_000L)
            val graphe = Bloc(Hg, c) { y0 ->
                val ox = MARGE + P
                fun x(t: Long) = ox + G + (Wg - G - D) * (t - t0).toFloat() / (t1 - t0)
                fun y(el: Double) = y0 + Hg - B - (Hg - B - Hh) * el.coerceIn(0.0, 90.0).toFloat() / 90f
                // The event's window, the grid: elevations, days.
                rect(x(e.debutMs), y0 + Hh, (x(e.finMs) - x(e.debutMs)).coerceAtLeast(1f), Hg - B - Hh, mix(S1, 0.06))
                for (el in listOf(0, 30, 60, 90)) {
                    ligne(floatArrayOf(ox + G, y(el.toDouble()), ox + Wg - D, y(el.toDouble())), GRILLE, 0.6f)
                    ecrit("$el°", ox + G - 4f, y(el.toDouble()) + 2.3f, 6.5f, GRIS, ancre = 2)
                }
                var j = (t0 / 86_400_000L + 1) * 86_400_000L
                val pas = if (t1 - t0 > 10 * 86_400_000L) 2 * 86_400_000L else 86_400_000L
                while (j < t1) {
                    ligne(floatArrayOf(x(j), y0 + Hh, x(j), y0 + Hg - B), GRILLE, 0.6f)
                    ecrit(RapportEvenement.jc(j), x(j), y0 + Hg - B + 9f, 6.5f, GRIS, ancre = 1)
                    j += pas
                }
                ligne(floatArrayOf(ox + G, y(r.elMin), ox + Wg - D, y(r.elMin)), GRIS, 0.6f, TIRETS)
                val w = 3f
                for (p in r.passages) {
                    val xm = x((p.aosMs + p.losMs) / 2)
                    val col = if (p.statut == Statut.A_VENIR) mix(couleur(p.statut), 0.45) else couleur(p.statut)
                    rect(xm - w / 2, y(p.elMax), w, (y(0.0) - y(p.elMax)).coerceAtLeast(0.8f), col, rayon = 1f)
                }
                for (i in r.images) {
                    val pt = i.trace.getOrNull(i.trace.size / 2) ?: continue
                    cercle(x(i.tMs), y(pt.el), 2.4f, RECU, CARTE, 1f)
                }
                ecrit(t("rev_axe_temps"), ox + G, y0 + Hg - 2f, 6.5f, GRIS)
            }
            // The legend: each state seen, the minimum elevation.
            val items = listOf(Statut.RECU, Statut.RECU_BAS, Statut.ATTENDU_RIEN, Statut.BAS, Statut.A_VENIR)
                .filter { st -> r.passages.any { it.statut == st } }
                .map { st -> (RapportEvenement.icone(st) + " " + RapportEvenement.libelle(st)) to couleur(st) } +
                listOf(tf("rev_seuil", RapportEvenement.el(r.elMin)) to 0)
            val tl = 7.2f
            val pos = ArrayList<Triple<Float, Int, Int>>()   // x, row, item
            var x = 0f; var rang = 0
            items.forEachIndexed { k, (s, _) ->
                val wi = 12f + l(s, tl) + 12f
                if (x > 0 && x + wi > WC) { x = 0f; rang++ }
                pos += Triple(x, rang, k); x += wi
            }
            val legende = Bloc(4f + (rang + 1) * 11f, c) { y ->
                for ((xx, rg, k) in pos) {
                    val (s, col) = items[k]
                    val bx = MARGE + P + xx; val by = y + 4f + rg * 11f
                    if (col != 0) rect(bx, by + 1.5f, 7f, 7f, col, rayon = 1.5f)
                    else ligne(floatArrayOf(bx, by + 5f, bx + 9f, by + 5f), GRIS, 0.9f, TIRETS)
                    ecrit(s, bx + 12f, by + 7.8f, tl, GRIS)
                }
            }
            return listOf(titre(t("rev_chronologie"), c), graphe, legende)
        }

        // ---------------------------------------------------------- the table of the passes

        /** The chips of the slides: their width, so that they wrap. */
        private val tp = 7f
        private fun puce(n: String) = l(n, tp, true) + 6f

        /** Chips in [w]: each one's (x, row). */
        private fun puces(l: List<String>, w: Float): List<Pair<Float, Int>> {
            var x = 0f; var rg = 0
            return l.map { s ->
                val pw = puce(s)
                if (x > 0 && x + pw > w) { x = 0f; rg++ }
                (x to rg).also { x += pw + 2f }
            }
        }

        private fun tableau(): List<Bloc> {
            val c = carte()
            val ti = titre(t("rev_passages"), c, "(${r.passages.size})")
            if (r.passages.isEmpty()) return listOf(ti, paragraphe(t("rev_aucun_passage"), c, 8f, GRIS))
            // The columns: their widths, their heads.
            val cols = floatArrayOf(78f, 34f, 98f, 122f, 82f).let { it + (WC - it.sum()) }
            val xs = FloatArray(cols.size) { i -> MARGE + P + cols.take(i).sum() }
            val heads = listOf("rev_c_utc", "rev_c_el", "rev_c_az", "rev_c_prevues", "rev_c_recues", "rev_c_statut").map { t(it) }
            val ht = 7.2f
            val hl = heads.mapIndexed { i, s -> lignes(s, cols[i] - 6f, ht, true) }
            val hh = hl.maxOf { it.size } * interligne(ht) + 6f
            val tete = Bloc(hh, c, gardeSuivant = true) { y ->
                hl.forEachIndexed { i, ls -> ls.forEachIndexed { k, s -> ecrit(s, xs[i] + 3f, y + ht + k * interligne(ht), ht, GRIS, true) } }
                ligne(floatArrayOf(MARGE + P, y + hh - 1f, MARGE + P + WC, y + hh - 1f), TRAIT, 0.75f)
            }
            val out = arrayListOf(ti, tete)
            val reprise = ensemble(titre(t("rev_passages"), c, "(${r.passages.size})", suite = true), tete)
            val tc = 7.5f
            val ih = interligne(tc)
            for ((k, p) in r.passages.withIndex()) {
                val utc = RapportEvenement.jc(p.aosMs) + " " + RapportEvenement.hm(p.aosMs) + "–" + RapportEvenement.hm(p.losMs)
                val az = "${RapportEvenement.deg(p.azAos)}° ${RapportEvenement.rose(p.azAos)} → ${RapportEvenement.deg(p.azLos)}° ${RapportEvenement.rose(p.azLos)}"
                val prevues = p.prevues.map { "$it" to (it in p.recues) } + p.coupees.map { "($it)" to false }
                val pp = puces(prevues.map { it.first }, cols[3] - 6f)
                val recues = p.recues.map { "$it" }
                val pr = puces(recues, cols[4] - 6f)
                val sans = p.images.count { it.diapo <= 0 }
                val apres = (if (sans > 0) "+$sans ? " else "") + (if (p.images.isNotEmpty()) tf("rev_n_images", p.images.size) else "")
                val apresL = if (apres.isBlank()) emptyList() else lignes(apres.trim(), cols[4] - 6f, 6.8f)
                val statut = lignes(RapportEvenement.icone(p.statut) + " " + RapportEvenement.libelle(p.statut), cols[5] - 15f, tc, true)
                val pilote = if (p.pilote.isNotBlank()) lignes(tf("rev_pilote", p.pilote), cols[5] - 6f, 6.8f) else emptyList()
                val hc = 12f
                val h = 5f + maxOf(
                    ih, lignes(az, cols[2] - 6f, tc).size * ih,
                    if (pp.isEmpty()) ih else (pp.maxOf { it.second } + 1) * hc,
                    (if (pr.isEmpty()) 0f else (pr.maxOf { it.second } + 1) * hc) + apresL.size * 9f + (if (pr.isEmpty() && apresL.isEmpty()) ih else 0f),
                    statut.size * ih + pilote.size * 9f) + 4f
                out += Bloc(h, c, reprise = reprise) { y ->
                    val yb = y + 5f + tc
                    ecrit(utc, xs[0] + 3f, yb, tc)
                    ecrit("${p.elMax.roundToInt()}°", xs[1] + 3f, yb, tc, ENCRE, true)
                    lignes(az, cols[2] - 6f, tc).forEachIndexed { i, s -> ecrit(s, xs[2] + 3f, yb + i * ih, tc) }
                    fun chips(x0: Float, l: List<Pair<String, Boolean>>, pos: List<Pair<Float, Int>>, part: (String) -> Boolean) {
                        l.forEachIndexed { i, (s, ok) ->
                            val (px, rg) = pos[i]
                            val bx = x0 + 3f + px; val by = y + 4f + rg * hc
                            rect(bx, by, puce(s), 10f, if (ok) mix(BON, 0.13) else null, if (ok) mix(BON, 0.55) else TRAIT, 0.7f, 2.5f,
                                if (part(s)) TIRETS else null)
                            ecrit(s, bx + puce(s) / 2, by + 7.4f, tp, if (part(s)) GRIS else ENCRE, ok, ancre = 1)
                        }
                    }
                    if (r.horaire == null && prevues.isEmpty()) ecrit("—", xs[3] + 3f, yb, tc, GRIS)
                    chips(xs[3], prevues, pp) { it.startsWith("(") }
                    if (p.images.isEmpty()) ecrit("—", xs[4] + 3f, yb, tc, GRIS)
                    chips(xs[4], recues.map { it to true }, pr) { false }
                    val ya = y + 4f + (if (pr.isEmpty()) 0f else (pr.maxOf { it.second } + 1) * hc) + 6.5f + (if (p.images.isEmpty()) ih else 0f)
                    apresL.forEachIndexed { i, s -> ecrit(s, xs[4] + 3f, ya + i * 9f, 6.8f, GRIS) }
                    cercle(xs[5] + 6.5f, yb - 2.6f, 3.2f, couleur(p.statut))
                    statut.forEachIndexed { i, s -> ecrit(s, xs[5] + 13f, yb + i * ih, tc, ENCRE, true) }
                    pilote.forEachIndexed { i, s -> ecrit(s, xs[5] + 3f, yb + statut.size * ih + i * 9f - 1f, 6.8f, GRIS) }
                    if (k < r.passages.size - 1) ligne(floatArrayOf(MARGE + P, y + h - 0.5f, MARGE + P + WC, y + h - 0.5f), GRILLE, 0.6f)
                }
            }
            return out
        }

        // ---------------------------------------------------------- each pass's sky

        private fun ciels(): List<Bloc> {
            val vus = r.passages.filter { it.elMax >= r.elMin || it.images.isNotEmpty() }
            if (vus.isEmpty()) return emptyList()
            val c = carte()
            val n = 3
            val g = 8f
            val wc = (WC - (n - 1) * g) / n
            val k = (wc - 10f) / 220f
            val hp = 220f * k; val hpr = 110f * k
            val tc = 7.2f
            val out = arrayListOf(titre(t("rev_ciel"), c))
            val reprise = titre(t("rev_ciel"), c, suite = true)
            val rangs = vus.chunked(n)
            rangs.forEachIndexed { ir, rang ->
                val tetes = rang.map { p ->
                    lignes(RapportEvenement.jc(p.aosMs) + " " + RapportEvenement.hm(p.aosMs) + " UTC · " + p.elMax.roundToInt() + "°", wc - 10f, tc, true) to
                        lignes(RapportEvenement.icone(p.statut) + " " + RapportEvenement.libelle(p.statut), wc - 19f, tc)
                }
                val ht = tetes.maxOf { (a, b) -> (a.size + b.size) * interligne(tc) } + 3f
                val hc = 5f + ht + hp + hpr + 5f
                out += Bloc(hc + if (ir < rangs.size - 1) g else 0f, c, reprise = reprise) { y ->
                    rang.forEachIndexed { i, p ->
                        val x = MARGE + P + i * (wc + g)
                        rect(x, y, wc, hc, null, GRILLE, 0.75f, 6f)
                        val (a, b) = tetes[i]
                        var yy = y + 5f + tc
                        a.forEach { ecrit(it, x + 5f, yy, tc, ENCRE, true); yy += interligne(tc) }
                        cercle(x + 8.5f, yy - 2.5f, 3f, couleur(p.statut))
                        b.forEach { ecrit(it, x + 14f, yy, tc, ENCRE); yy += interligne(tc) }
                        polaire(this, p, x + 5f, y + 5f + ht, k)
                        profil(this, p, x + 5f, y + 5f + ht + hp, k)
                    }
                }
            }
            out += paragraphe(t("rev_aide_ciel"), c, 7f, GRIS, avant = 4f)
            return out
        }

        /** A pass seen from below, as the page's ([RapportEvenement.polaire]): its 220-wide drawing at scale [k] from ([ox], [oy]). */
        private fun polaire(tl: Toile, p: RapportEvenement.Passage, ox: Float, oy: Float, k: Float) = with(tl) {
            val R = 92.0; val C = 110.0
            fun X(v: Double) = ox + (v * k).toFloat()
            fun Y(v: Double) = oy + (v * k).toFloat()
            fun xy(az: Double, el: Double): Pair<Float, Float> {
                val rr = R * (90 - el.coerceIn(0.0, 90.0)) / 90
                val a = Math.toRadians(az)
                return X(C + rr * sin(a)) to Y(C - rr * cos(a))
            }
            for (el in listOf(0, 30, 60)) cercle(X(C), Y(C), (R * (90 - el) / 90 * k).toFloat(), null, GRILLE, 0.7f)
            if (r.elMin > 0) cercle(X(C), Y(C), (R * (90 - r.elMin) / 90 * k).toFloat(), null, GRIS, 0.6f)
            ligne(floatArrayOf(X(C - R), Y(C), X(C + R), Y(C)), GRILLE, 0.7f)
            ligne(floatArrayOf(X(C), Y(C - R), X(C), Y(C + R)), GRILLE, 0.7f)
            val ta = 11f * k; val tp = 8.5f * k
            val o = if (I18n.current() == Lang.EN) "W" else "O"
            ecrit("N", X(C), Y(12.0), ta, GRIS, ancre = 1); ecrit("E", X(C + R + 10), Y(C + 4), ta, GRIS, ancre = 1)
            ecrit("S", X(C), Y(C + R + 15), ta, GRIS, ancre = 1); ecrit(o, X(C - R - 10), Y(C + 4), ta, GRIS, ancre = 1)
            ecrit("30°", X(C + 3), Y(C - R * 60 / 90 + 10), tp, GRIS); ecrit("60°", X(C + 3), Y(C - R * 30 / 90 + 10), tp, GRIS)
            val pts = p.trace.filter { it.el >= -1 }
            if (pts.size >= 2) {
                ligne(pts.flatMap { val (x, y) = xy(it.az, it.el); listOf(x, y) }.toFloatArray(), GRIS, 2f * k, rond = true)
                val (ax, ay) = xy(pts.first().az, pts.first().el); val (lx, ly) = xy(pts.last().az, pts.last().el)
                cercle(ax, ay, 4f * k, BON, CARTE, 1.4f * k)
                rect(lx - 3.5f * k, ly - 3.5f * k, 7f * k, 7f * k, RATE, CARTE, 1.4f * k)
            }
            for (i in p.images) {
                if (i.trace.size < 2) continue
                ligne(i.trace.flatMap { val (x, y) = xy(it.az, it.el); listOf(x, y) }.toFloatArray(), RECU, 5f * k,
                    if (i.complete) null else floatArrayOf(3f * k, 3f * k), rond = i.complete)
                val mi = i.trace[i.trace.size / 2]; val (mx, my) = xy(mi.az, mi.el)
                val etiq = if (i.diapo > 0) (if (i.origine == ClassementSstv.Origine.ESTIME) "≈" else "") + i.diapo else "?"
                cercle(mx, my, 8f * k, CARTE, RECU, 1.4f * k)
                ecrit(etiq, mx, my + 3.2f * k, 9f * k, ENCRE, true, ancre = 1)
            }
        }

        /** A pass's elevation against time, as the page's ([RapportEvenement.profil]): 220 × 110 at scale [k]. */
        private fun profil(tl: Toile, p: RapportEvenement.Passage, ox: Float, oy: Float, k: Float) = with(tl) {
            val W = 220.0; val H = 110.0; val G = 26.0; val D = 6.0; val Hh = 6.0; val B = 20.0
            val t0 = p.aosMs; val t1 = maxOf(p.losMs, p.aosMs + 60_000L)
            fun x(t: Long) = ox + ((G + (W - G - D) * ((t - t0).toDouble() / (t1 - t0)).coerceIn(0.0, 1.0)) * k).toFloat()
            val haut = maxOf(30.0, ((p.elMax + 9) / 10).toInt() * 10.0).coerceAtMost(90.0)
            fun y(el: Double) = oy + ((H - B - (H - B - Hh) * el.coerceIn(0.0, haut) / haut) * k).toFloat()
            fun X(v: Double) = ox + (v * k).toFloat()
            val tp = 8.5f * k
            val recues = p.images.filter { it.diapo > 0 }.map { it.diapo }.toSet()
            for (c in p.creneaux) {
                val x0 = x(c.debutMs); val x1 = x(c.finMs)
                if (x1 - x0 < 0.4f) continue
                val ok = c.diapo in recues
                rect(x0, y(haut), x1 - x0, y(0.0) - y(haut), if (ok) mix(BON, 0.22) else if (!c.entiere) mix(S1, 0.05) else mix(S1, 0.10))
                ecrit(c.diapo.toString(), (x0 + x1) / 2, y(haut) + 8.5f * k, tp, GRIS, ancre = 1)
            }
            for (el in listOf(0.0, haut / 2, haut)) {
                ligne(floatArrayOf(X(G), y(el), X(W - D), y(el)), GRILLE, 0.6f)
                ecrit("${el.roundToInt()}°", X(G - 3), y(el) + 3f * k, tp, GRIS, ancre = 2)
            }
            if (r.elMin in 0.0..haut) ligne(floatArrayOf(X(G), y(r.elMin), X(W - D), y(r.elMin)), GRIS, 0.6f, TIRETS)
            if (p.trace.size >= 2) ligne(p.trace.flatMap { listOf(x(it.tMs), y(it.el)) }.toFloatArray(), GRIS, 2f * k, rond = true)
            for (i in p.images) if (i.trace.size >= 2)
                ligne(i.trace.flatMap { listOf(x(it.tMs), y(it.el)) }.toFloatArray(), RECU, 5f * k,
                    if (i.complete) null else floatArrayOf(3f * k, 3f * k), rond = i.complete)
            ecrit(RapportEvenement.hm(p.aosMs), X(G), oy + ((H - 5) * k).toFloat(), tp, GRIS)
            ecrit(RapportEvenement.hm(p.losMs) + " UTC", X(W - D), oy + ((H - 5) * k).toFloat(), tp, GRIS, ancre = 2)
        }

        // ---------------------------------------------------------- the timetable, the chain, the foot

        private fun horaire(): List<Bloc> {
            val c = carte()
            val s = r.stats
            val m = r.horaire
            val out = arrayListOf(titre(t("rev_horaire"), c))
            var texte = if (m == null) t("rev_sans_horaire")
                else if (m.ajustee) tf("rev_horaire_ajuste", RapportEvenement.n2(m.periodeS), RapportEvenement.n2(m.sigmaPeriodeS), m.reperes, RapportEvenement.n1(m.residuS))
                else tf("rev_horaire_annonce", RapportEvenement.n1(m.periodeS))
            if (m != null && m.ruptures.isNotEmpty())
                texte += " " + tf("rev_ruptures", m.ruptures.size, m.ruptures.joinToString(", ") { RapportEvenement.jc(it) + " " + RapportEvenement.hm(it) })
            out += paragraphe(texte, c)
            out += paragraphe(t("rev_aide_horaire"), c, 7.5f, GRIS, avant = 3f)
            if (s.premiereMs > 0) out += paragraphe(tf("rev_premiere_derniere", RapportEvenement.jour(s.premiereMs) + " " + RapportEvenement.hm(s.premiereMs),
                RapportEvenement.jour(s.derniereMs) + " " + RapportEvenement.hm(s.derniereMs)), c, avant = 3f)
            if (s.horsPassage > 0) out += paragraphe(tf("rev_hors_passage", s.horsPassage), c, 7.5f, GRIS, avant = 3f)
            return out
        }

        private fun chaine(ch: List<List<RapportEvenement.Etape>>): List<Bloc> {
            val c = carte()
            val out = arrayListOf(titre(t("rev_chaine"), c, r.nomChaine))
            if (ch.isEmpty()) out += paragraphe(t("rev_chaine_vide"), c, 8f, GRIS)
            else {
                // The columns, the software at the end; in rows when too wide.
                val cols = ch + listOf(listOf(RapportEvenement.Etape(t("rev_logiciel"), "SatMe")))
                val hp = 24f; val gp = 4f; val fl = 16f
                val larg = cols.map { col -> (col.maxOf { maxOf(l(it.nom, 8f, true), l(it.genre.uppercase(), 6f)) } + 14f).coerceIn(70f, WC) }
                val rangs = ArrayList<List<Int>>()
                var cur = ArrayList<Int>(); var x = 0f
                for (i in cols.indices) {
                    val w = larg[i] + if (cur.isEmpty()) 0f else fl
                    if (cur.isNotEmpty() && x + w > WC) { rangs += cur; cur = ArrayList(); x = 0f }
                    cur += i; x += larg[i] + if (cur.size > 1) fl else 0f
                }
                if (cur.isNotEmpty()) rangs += cur
                for (rg in rangs) {
                    val h = rg.maxOf { i -> cols[i].size * hp + (cols[i].size - 1) * gp } + 9f
                    out += Bloc(h, c) { y0 ->
                        val y = y0 + 3f
                        var xx = MARGE + P
                        rg.forEachIndexed { k, i ->
                            if (k > 0) { ecrit("→", xx + fl / 2, y + (h - 9f) / 2 + 3.5f, 11f, GRIS, ancre = 1); xx += fl }
                            val col = cols[i]
                            val hcol = col.size * hp + (col.size - 1) * gp
                            var yy = y + (h - 9f - hcol) / 2
                            for (e in col) {
                                rect(xx, yy, larg[i], hp, FOND, if (i == cols.size - 1) S1 else TRAIT, 0.75f, 5f)
                                ecrit(coupe(e.genre.uppercase(), larg[i] - 12f, 6f), xx + 6f, yy + 9f, 6f, GRIS)
                                ecrit(coupe(e.nom, larg[i] - 12f, 8f, true), xx + 6f, yy + 19f, 8f, ENCRE, true)
                                yy += hp + gp
                            }
                            xx += larg[i]
                        }
                    }
                }
            }
            if (r.pilote.isNotEmpty()) {
                out += paragraphe(t("rev_pilote_titre"), c, 8.5f, ENCRE, true, avant = 6f)
                for (s in r.pilote) out += paragraphe("•  $s", c, 8f, avant = 1f)
            }
            return out
        }

        private fun pied(): Bloc {
            val e = r.evenement
            val geo = when {
                !r.geometrie.recalculee -> t("rev_geo_absente")
                r.geometrie.epoqueMs != null -> tf("rev_geo_recalculee", RapportEvenement.jour(r.geometrie.epoqueMs) + " " + RapportEvenement.hm(r.geometrie.epoqueMs) + " UTC",
                    RapportEvenement.n1(kotlin.math.abs((e.debutMs + e.finMs) / 2 - r.geometrie.epoqueMs) / 86_400_000.0))
                else -> t("rev_geo_recalculee_sans")
            }
            val a = paragraphe(geo, 0, 7.2f, GRIS, x = MARGE, w = W)
            val b = paragraphe(tf("rev_pied_pdf", r.logiciel, RapportEvenement.jour(r.faitMs) + " " + RapportEvenement.hm(r.faitMs) + " UTC"), 0, 7.2f, GRIS, x = MARGE, w = W, avant = 4f)
            return Bloc(a.h + b.h, 0) { y -> a.dessin(this, y); b.dessin(this, y + a.h) }
        }
    }
}
