/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.domain.JournalPassage
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The caption under the journal's videos laid out: its texts in rows,
 * a row too long for the width going on below, the least useful ones left
 * out when the band has no room — never one over another. Its size
 * ([police]) and its time zone ([fuseau]) as the page asks; the pass's times
 * (AOS, TCA, LOS) and where the frame is in it.
 */
object LegendePc {

    /** The texts' scale asked (`police`, 0.6 to 2.5); 1 when missing or wrong. */
    fun police(p: (String) -> String): Float = p("police").trim().toFloatOrNull()?.takeIf { it.isFinite() }?.coerceIn(0.6f, 2.5f) ?: 1f

    val UTC: TimeZone = TimeZone.getTimeZone("UTC")

    /** The times' zone asked: `utc=0` the PC's own, else UTC (as before). */
    fun fuseau(p: (String) -> String): TimeZone = if (p("utc").trim() == "0") TimeZone.getDefault() else UTC

    /** The zone written after a time: « UTC », the local zone's short name (« CET »), else « heure locale ». */
    fun zone(tz: TimeZone, t: Long): String {
        if (tz.id == "UTC") return "UTC"
        val n = tz.getDisplayName(tz.inDaylightTime(Date(t)), TimeZone.SHORT, Locale.FRENCH)
        return if (n.isBlank() || n.startsWith("GMT") || n.startsWith("UTC")) "heure locale" else n
    }

    fun format(motif: String, tz: TimeZone, t: Long): String = SimpleDateFormat(motif, Locale.FRENCH).apply { timeZone = tz }.format(Date(t))

    private fun duree(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    /** Where [t] is in the pass: « AOS dans 0:42 », « AOS + 3:12 », « après LOS ». */
    fun relatif(e: JournalPassage.Entree, t: Long): String = when {
        t < e.debutMs -> "AOS dans " + duree(e.debutMs - t + 999)
        t <= e.finMs -> "AOS + " + duree(t - e.debutMs)
        else -> "après LOS"
    }

    /** The pass's times: AOS, TCA (with its highest elevation), LOS. */
    fun aosTcaLos(e: JournalPassage.Entree, tz: TimeZone): List<String> {
        val tca = e.points.maxByOrNull { it.el }?.tMs ?: ((e.debutMs + e.finMs) / 2)
        return listOf("AOS " + format("HH:mm:ss", tz, e.debutMs), "TCA " + format("HH:mm", tz, tca) + " (max %.0f°)".format(e.elMax),
            "LOS " + format("HH:mm:ss", tz, e.finMs))
    }

    enum class Sorte { TEXTE, TX, SMETRE, MARQUE }

    /**
     * A thing of the caption. [prio]: the larger, the sooner left out (0: never). [droite]: at the
     * row's right. [gabarit]: the widest it can be (its place the same all along the video).
     * [visible]: drawn at this moment (its place kept when not).
     */
    class Element(val sorte: Sorte, val texte: String, val font: Font, val couleur: Color, val prio: Int,
                  val droite: Boolean = false, val gabarit: String? = null, val visible: Boolean = true,
                  val valeur: Int? = null, val crete: Int? = null)

    /** Where an element goes in the band (its box, from the band's top). */
    class Place(val el: Element, val texte: String, val x: Float, val y: Float, val l: Float, val h: Float)

    /** The places, the band's height, the height the texts need ([naturelle], at most the band's). */
    class Mise(val places: List<Place>, val hauteur: Float, val naturelle: Float = hauteur)

    /**
     * The rows laid out in a band [largeur] wide, the texts at scale [k]: each row
     * on as many lines as it needs; within [hauteurMax] (null: as high as needed) by
     * leaving out the least useful; the last row at the band's bottom.
     */
    fun dispose(g: Graphics2D, rangs: List<List<Element>>, largeur: Float, dp: Float, k: Float, hauteurMax: Float?): Mise {
        val retires = HashSet<Element>()
        while (true) {
            val (places, h) = essai(g, rangs.map { r -> r.filter { it !in retires } }, largeur, dp, k)
            if (hauteurMax == null || h <= hauteurMax) {
                // The last row (the station, the mark) at the bottom.
                val reste = if (hauteurMax == null) 0f else hauteurMax - h
                val dernier = rangs.last().toSet()
                return Mise(places.map { p -> if (p.el in dernier && reste > 0) Place(p.el, p.texte, p.x, p.y + reste, p.l, p.h) else p }, hauteurMax ?: h, h)
            }
            val pire = rangs.flatten().filter { it !in retires && it.prio > 0 }.withIndex().maxWithOrNull(compareBy({ it.value.prio }, { it.index }))?.value
                ?: return Mise(places, h)
            retires += pire
        }
    }

    private fun essai(g: Graphics2D, rangs: List<List<Element>>, largeur: Float, dp: Float, k: Float): Pair<List<Place>, Float> {
        val marge = 14 * dp; val ecart = 12 * dp * k; val interligne = 3 * dp * k; val pad = 8 * dp * k
        val dispo = largeur - 2 * marge
        val places = ArrayList<Place>()
        var y = pad
        fun mesure(el: Element): Triple<String, Float, Float> = when (el.sorte) {
            Sorte.TX -> Triple(el.texte, 40 * dp * k, 22 * dp * k)
            Sorte.SMETRE -> Triple("", dispo, 24 * dp * k)
            Sorte.MARQUE -> { val u = marque(dp, k); Triple("", MarquePc.largeur(g, u), 6.4f * u) }
            Sorte.TEXTE -> {
                val fm = g.getFontMetrics(el.font)
                var s = el.texte
                var l = maxOf(fm.stringWidth(s), el.gabarit?.let { fm.stringWidth(it) } ?: 0).toFloat()
                if (l > dispo) { while (s.length > 1 && fm.stringWidth("$s…") > dispo) s = s.dropLast(1); s = "$s…"; l = fm.stringWidth(s).toFloat() }
                Triple(s, l, (fm.ascent + fm.descent).toFloat())
            }
        }
        for (rang in rangs) {
            if (rang.isEmpty()) continue
            var ligne = ArrayList<Pair<Element, Triple<String, Float, Float>>>()
            fun pose() {
                if (ligne.isEmpty()) return
                val lh = ligne.maxOf { it.second.third }
                var x = marge
                for ((el, m) in ligne.filter { !it.first.droite }) { places += Place(el, m.first, x, y + (lh - m.third) / 2, m.second, m.third); x += m.second + ecart }
                var xd = largeur - marge
                for ((el, m) in ligne.filter { it.first.droite }.asReversed()) { xd -= m.second; places += Place(el, m.first, xd, y + (lh - m.third) / 2, m.second, m.third); xd -= ecart }
                y += lh + interligne
                ligne = ArrayList()
            }
            for (el in rang) {
                val m = mesure(el)
                if (el.sorte == Sorte.SMETRE) { pose(); ligne += el to m; pose(); continue }
                val l = ligne.sumOf { it.second.second.toDouble() }.toFloat() + ecart * ligne.size
                if (ligne.isNotEmpty() && l + m.second > dispo) pose()
                ligne += el to m
            }
            pose()
        }
        return places to (y - interligne + pad)
    }

    /** The mark's unit in the caption: a little larger with the texts, not as much. */
    fun marque(dp: Float, k: Float) = 3.2f * dp * k.coerceIn(0.8f, 1.4f)
}
