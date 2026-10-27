/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.domain.Dxcc
import fr.f4ioz.satcombo.domain.JournalFt
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.geom.RoundRectangle2D
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * The FT8/FT4 messages' panel of the journal's videos, as the page draws it
 * over the replay: where it was placed ([JournalFt.Cadre], shares of the
 * view), its font following its width ([JournalFt.mise], the page's numbers),
 * as visible as asked (the whole panel at [JournalFt.Affichage.opacite]).
 * Each row: the slot's time (UTC), the report, the message, the country's
 * code (the page shows its flag); a CQ green, my callsign red, a station of
 * the pass's contacts in the contacts' pink.
 */
object PanneauFtPc {

    val FOND = Color(5, 10, 16, 210)
    val TEXTE = Color(0xE8F0F7); val GRIS = Color(0x9AA7B4)
    val CQ = Color(0x7CE38B); val MOI = Color(0xFF6B6B); val QSO = Color(0xFF4FA3); val BORD = Color(0x3FE0C8)

    private fun hms(t: Long) = SimpleDateFormat("HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(java.util.Date(t))

    /** The colour of a row: mine first, then a contact's, then a CQ. */
    fun couleur(d: JournalFt.Decode, moi: String, qsos: Set<String>): Color = when {
        JournalFt.pourMoi(d, moi) -> MOI
        JournalFt.avecQso(d, qsos) -> QSO
        JournalFt.estCq(d) -> CQ
        else -> TEXTE
    }

    /** The panel's rectangle in a view [w]×[h] (px). */
    fun place(a: JournalFt.Affichage, w: Float, h: Float) = a.place.place(w, h)

    /** Draws the panel at [t] in a view [w]×[h]; nothing when hidden or fully transparent. */
    fun dessine(g: Graphics2D, l: List<JournalFt.Decode>, a: JournalFt.Affichage, t: Long, w: Float, h: Float, moi: String, qsos: Set<String>) {
        if (!a.affiche || a.opacite <= 0) return
        val pl = place(a, w, h)
        val m = JournalFt.mise(pl.l, pl.h)
        val avant = g.composite; val clip = g.clip
        g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a.opacite.coerceIn(0, 100) / 100f)
        val r = m.police * 0.5f
        g.color = FOND; g.fill(RoundRectangle2D.Float(pl.x, pl.y, pl.l, pl.h, r * 2, r * 2))
        g.color = Color(BORD.red, BORD.green, BORD.blue, 120)
        g.stroke = java.awt.BasicStroke(maxOf(1f, m.police / 12f)); g.draw(RoundRectangle2D.Float(pl.x, pl.y, pl.l, pl.h, r * 2, r * 2))
        g.clipRect(pl.x.toInt(), pl.y.toInt(), Math.ceil(pl.l.toDouble()).toInt(), Math.ceil(pl.h.toDouble()).toInt())
        // The title: the modes heard, how many messages so far, the rows' times in UTC.
        val vus = l.filter { JournalFt.instant(it) <= t }
        val modes = l.map { it.mode.uppercase() }.distinct().sorted().joinToString(" · ").ifEmpty { "FT8" }
        val fTitre = Font(Font.SANS_SERIF, Font.BOLD, 1).deriveFont(m.police * 0.95f)
        g.font = fTitre; g.color = BORD
        val x0 = pl.x + m.marge
        g.drawString("$modes · ${vus.size} · UTC", x0, pl.y + m.marge + m.titre * 0.72f)
        val fl = Font(Font.MONOSPACED, Font.PLAIN, 1).deriveFont(m.police)
        val fg = fl.deriveFont(Font.BOLD)
        var y = pl.y + m.marge + m.titre
        for (d in JournalFt.visibles(l, t, m.lignes, a.recentEnHaut)) {
            val c = couleur(d, moi, qsos)
            val code = d.appelant?.let { Dxcc.entite(it) }?.iso.orEmpty()
            g.font = fl
            val fm = g.fontMetrics
            val base = y + (m.ligne + fm.ascent - fm.descent) / 2
            val droite = pl.x + pl.l - m.marge
            var finTexte = droite
            if (code.isNotEmpty()) {
                val cw = fm.stringWidth(code)
                g.color = GRIS; g.drawString(code, droite - cw, base); finTexte = droite - cw - m.police * 0.6f
            }
            val debut = hms(d.tMs) + " " + "%3d".format(Locale.US, d.db) + " "
            g.color = GRIS; g.drawString(debut, x0, base)
            val xm = x0 + fm.stringWidth(debut)
            g.font = if (c == MOI) fg else fl; g.color = c
            g.drawString(coupe(d.message, finTexte - xm, g.fontMetrics), xm, base)
            y += m.ligne
        }
        g.clip = clip; g.composite = avant
    }

    /** [s] cut to [largeur] px, « … » at its end when it was. */
    private fun coupe(s: String, largeur: Float, fm: java.awt.FontMetrics): String {
        if (largeur <= 0f) return ""
        if (fm.stringWidth(s) <= largeur) return s
        var k = s.length
        while (k > 0 && fm.stringWidth(s.take(k) + "…") > largeur) k--
        return if (k == 0) "" else s.take(k) + "…"
    }
}
