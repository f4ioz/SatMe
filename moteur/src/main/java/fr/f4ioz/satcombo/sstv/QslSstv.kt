/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

import fr.f4ioz.satcombo.data.SatPass
import fr.f4ioz.satcombo.domain.ActivitesAVenir
import fr.f4ioz.satcombo.domain.CampagneSstv
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.i18n.tf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * A received SSTV picture « signed » as a QSL card: the picture, and a
 * banner — below it or laid over its foot — with who received it (callsign,
 * locator), when (UTC), what (satellite, frequency, mode), how high the
 * satellite was, the series and the slide, « SatMe » if wanted.
 *
 * Here the layout, in pixels, and its words; the phone (Canvas) and
 * SatMe-PC (Java2D) draw it. The original picture is never touched: the QSL
 * is a new file, made on demand.
 */
object QslSstv {

    enum class Disposition { BANDEAU, INCRUSTE }

    /** What the card says. */
    data class Infos(
        val indicatif: String,
        val locator: String,
        val quandMs: Long,
        val satNom: String,
        /** Sky Hz, 0 unknown. */
        val freqHz: Long = 0L,
        val modulation: String = "FM",
        val mode: String = "",
        /** The satellite's elevation when received, and its peak on that pass; null unknown. */
        val elDeg: Double? = null,
        val elMaxDeg: Double? = null,
        val serie: String = "",
        val diapo: Int = 0,
        val diapos: Int = 0,
        val satme: Boolean = true,
    )

    enum class Align { GAUCHE, DROITE }

    /** A line of text: its top-left (or top-right) corner, size (px), weight, colour (ARGB), widest it may be. */
    data class Texte(val texte: String, val x: Float, val y: Float, val taille: Float, val gras: Boolean,
                     val couleur: Int, val maxL: Float, val align: Align = Align.GAUCHE)

    data class Rect(val x: Float, val y: Float, val l: Float, val h: Float, val couleur: Int)

    /** The card: its size, where the picture goes (scaled, never cropped), the backgrounds then the texts. */
    data class Mise(val largeur: Int, val hauteur: Int, val image: Rect, val fonds: List<Rect>, val textes: List<Texte>)

    /** The card is at least this wide (a Robot 36 picture doubled). */
    const val LARGEUR_MIN = 640

    private const val ENCRE = 0xFFF4F1E8.toInt()
    private const val ENCRE_2 = 0xFFB9C3CF.toInt()
    private const val ACCENT = 0xFFFFC857.toInt()
    private const val FOND = 0xFF0E1A2B.toInt()
    private const val FOND_INCRUSTE = 0xB80E1A2B.toInt()
    private const val FILET = 0xFFFFC857.toInt()

    private fun utc(t: Long, motif: String) = SimpleDateFormat(motif, Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(t))

    /** « 437,550 MHz FM » (the language's decimal sign). */
    fun frequence(hz: Long, modulation: String): String =
        if (hz <= 0) "" else String.format(fr.f4ioz.satcombo.i18n.I18n.locale(), "%.3f MHz", hz / 1e6) + if (modulation.isNotBlank()) " " + modulation else ""

    /** The card's lines: the callsign and locator; when and what; how high, the series and slide. */
    fun lignes(i: Infos): List<String> {
        val l1 = listOf(i.indicatif.trim().uppercase(), i.locator.trim().uppercase()).filter { it.isNotBlank() }.joinToString("  ·  ")
        val l2 = listOf(if (i.quandMs > 0) utc(i.quandMs, "dd/MM/yyyy HH:mm:ss") + " UTC" else "", i.satNom, frequence(i.freqHz, i.modulation), i.mode)
            .filter { it.isNotBlank() }.joinToString("  ·  ")
        val el = when {
            i.elDeg != null && i.elMaxDeg != null -> tf("qsl_el_max", Math.round(i.elDeg), Math.round(i.elMaxDeg))
            i.elDeg != null -> tf("qsl_el", Math.round(i.elDeg))
            i.elMaxDeg != null -> tf("qsl_el_pic", Math.round(i.elMaxDeg))
            else -> ""
        }
        val diapo = if (i.diapo > 0) tf("qsl_diapo", i.diapo, if (i.diapos > 0) i.diapos else 12) else ""
        val l3 = listOf(el, i.serie, diapo).filter { it.isNotBlank() }.joinToString("  ·  ")
        return listOf(l1, l2, l3)
    }

    /** The card for a picture [w]×[h], laid out [d]. */
    fun mise(w: Int, h: Int, i: Infos, d: Disposition): Mise {
        require(w > 0 && h > 0)
        val k = maxOf(1f, LARGEUR_MIN.toFloat() / w)
        val lw = w * k; val lh = h * k
        val (l1, l2, l3) = lignes(i)
        val marge = lw * 0.035f
        val t1 = lw * 0.058f; val t2 = lw * 0.030f; val t3 = lw * 0.027f
        val interligne = 1.32f
        val hb = marge * 1.6f + t1 * interligne + t2 * interligne + (if (l3.isNotBlank()) t3 * interligne else 0f)
        val total = if (d == Disposition.BANDEAU) lh + hb else lh
        val y0 = if (d == Disposition.BANDEAU) lh else lh - hb
        val fonds = ArrayList<Rect>()
        fonds += Rect(0f, y0, lw, hb, if (d == Disposition.BANDEAU) FOND else FOND_INCRUSTE)
        fonds += Rect(0f, y0, lw, maxOf(2f, lw * 0.004f), FILET)
        val textes = ArrayList<Texte>()
        val large = lw - 2 * marge
        var y = y0 + marge * 0.8f
        val sig = if (i.satme) t("qsl_signe") else ""
        val lSig = if (sig.isNotBlank()) large * 0.22f else 0f
        textes += Texte(l1, marge, y, t1, true, ACCENT, large - lSig)
        if (sig.isNotBlank()) textes += Texte(sig, lw - marge, y + t1 * 0.35f, t3, false, ENCRE_2, lSig, Align.DROITE)
        y += t1 * interligne
        textes += Texte(l2, marge, y, t2, false, ENCRE, large)
        y += t2 * interligne
        if (l3.isNotBlank()) textes += Texte(l3, marge, y, t3, false, ENCRE_2, large)
        return Mise(Math.round(lw), Math.round(total), Rect(0f, 0f, lw, lh, 0), fonds, textes)
    }

    // ------------------------------------------------------------------ what is known

    /**
     * The card's facts for [s]: its own sidecar first (callsign and locator
     * there, else the station's [indicatif] and [locator]), the frequency
     * from the SSTV activity or campaign of its satellite at that time, the
     * elevations from the pass it was received on.
     */
    fun infos(s: SstvMeta.SstvShot, indicatif: String, locator: String, passages: List<SatPass>,
              activites: List<ActivitesAVenir.Activite>, campagnes: List<CampagneSstv.Campagne>,
              serie: String = "", diapo: Int = s.diapo, diapos: Int = s.diapos, satme: Boolean = true): Infos {
        val p = passageDe(s, passages)
        val (hz, mod) = frequenceDe(s, activites, campagnes)
        return Infos(s.callsign.ifBlank { indicatif }, s.locator.ifBlank { locator }, s.timeMs, s.satName, hz, mod,
            SstvMode.byName(s.mode)?.name ?: s.mode,
            p?.let { AlerteDiapo.elevation(it, s.timeMs + 18_000L) }, p?.maxElevationDeg, serie, diapo, diapos, satme)
    }

    /** The pass [s] was received on (AOS − 1 min to LOS + 2 min), null none. */
    fun passageDe(s: SstvMeta.SstvShot, l: List<SatPass>): SatPass? =
        l.filter { s.timeMs in (it.aosEpochMs - 60_000L)..(it.losEpochMs + 120_000L) }.minByOrNull { kotlin.math.abs((it.aosEpochMs + it.losEpochMs) / 2 - s.timeMs) }

    private fun normal(x: String) = x.uppercase().replace(Regex("\\(.*?\\)"), "").replace(Regex("[^A-Z0-9]"), "")

    /** The downlink [s] came on: an SSTV activity of its satellite then, else a campaign; 0 unknown. */
    fun frequenceDe(s: SstvMeta.SstvShot, activites: List<ActivitesAVenir.Activite>, campagnes: List<CampagneSstv.Campagne>): Pair<Long, String> {
        val n = normal(s.satName)
        fun meme(x: String) = n.isNotEmpty() && normal(x).let { it.isNotEmpty() && (it.startsWith(n) || n.startsWith(it)) }
        val a = activites.firstOrNull { it.type == ActivitesAVenir.Type.SSTV && it.freqHz > 0 && meme(it.satNom) &&
            s.timeMs in (it.debutMs - 3_600_000L)..(it.finMs + 3_600_000L) }
        if (a != null) return a.freqHz to a.modulation
        val c = campagnes.firstOrNull { it.freqHz > 0 && meme(it.satNom) && s.timeMs in (it.debutMs - 3_600_000L)..(it.finMs + 3_600_000L) }
        if (c != null) return c.freqHz to c.modulation
        return 0L to "FM"
    }

    /** The QSL file's name: the picture's, « _QSL », its format. */
    fun nomFichier(png: String, jpeg: Boolean): String = png.removeSuffix(".png") + "_QSL" + if (jpeg) ".jpg" else ".png"
}
