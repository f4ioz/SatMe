/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.plateforme.Stockage
import org.json.JSONObject
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import javax.imageio.ImageIO

/**
 * Text and the SatMe mark over the transmitted DATV picture: the callsign,
 * the locator, a line of free text, the UTC time, in a corner or a strip.
 *
 * Drawn here (Java2D, the JVM's own fonts) into a transparent PNG at the
 * stream's picture size, which ffmpeg lays over the picture with `overlay`:
 * no font to find for ffmpeg, the same on Linux and Windows. A still PNG
 * looped when the time is off; with the time on, a fresh PNG twice a second
 * into ffmpeg's standard input (`image2pipe`), as ffmpeg reads a looped
 * file only once. Nothing of it touches the stream's rate.
 */
class IncrustationDatvPc(private val stockage: Stockage, private val horloge: () -> Long = System::currentTimeMillis) {

    /** Where the box goes. */
    enum class Position(val cle: String, val libelle: String) {
        HAUT_GAUCHE("haut-gauche", "en haut à gauche"), HAUT_DROITE("haut-droite", "en haut à droite"),
        BAS_GAUCHE("bas-gauche", "en bas à gauche"), BAS_DROITE("bas-droite", "en bas à droite"),
        BANDEAU("bandeau", "bandeau en bas");
        companion object { fun de(c: String?) = entries.firstOrNull { it.cle == c } ?: BAS_GAUCHE }
    }

    /** The text's height, a share of the picture's. */
    enum class Taille(val cle: String, val part: Float) {
        PETIT("petit", 0.05f), MOYEN("moyen", 0.065f), GRAND("grand", 0.085f);
        companion object { fun de(c: String?) = entries.firstOrNull { it.cle == c } ?: MOYEN }
    }

    data class Reglage(
        val indicatif: Boolean = false,
        val locator: Boolean = false,
        val texte: String = "",
        val heure: Boolean = false,
        val logo: Boolean = false,
        val position: Position = Position.BAS_GAUCHE,
        val taille: Taille = Taille.MOYEN,
        /** A dark, half-transparent box behind the text. */
        val fond: Boolean = true,
    )

    // ------------------------------------------------------------ settings

    var reglage: Reglage
        get() = Reglage(
            indicatif = stockage.getBoolean("datv_inc_indicatif", false),
            locator = stockage.getBoolean("datv_inc_locator", false),
            texte = stockage.getString("datv_inc_texte", "") ?: "",
            heure = stockage.getBoolean("datv_inc_heure", false),
            logo = stockage.getBoolean("datv_inc_logo", false),
            position = Position.de(stockage.getString("datv_inc_position", "")),
            taille = Taille.de(stockage.getString("datv_inc_taille", "")),
            fond = stockage.getBoolean("datv_inc_fond", true))
        set(r) {
            stockage.edit().putBoolean("datv_inc_indicatif", r.indicatif).putBoolean("datv_inc_locator", r.locator)
                .putString("datv_inc_texte", nettoie(r.texte)).putBoolean("datv_inc_heure", r.heure).putBoolean("datv_inc_logo", r.logo)
                .putString("datv_inc_position", r.position.cle).putString("datv_inc_taille", r.taille.cle).putBoolean("datv_inc_fond", r.fond).apply()
        }

    /** The page's settings ([q]: what it sent; the rest kept). */
    fun regle(q: Map<String, String>) {
        val r = reglage
        fun b(k: String, d: Boolean) = q[k]?.let { it == "1" } ?: d
        reglage = r.copy(indicatif = b("indicatif", r.indicatif), locator = b("locator", r.locator), texte = q["texte"] ?: r.texte,
            heure = b("heure", r.heure), logo = b("logo", r.logo), position = q["position"]?.let { Position.de(it) } ?: r.position,
            taille = q["taille"]?.let { Taille.de(it) } ?: r.taille, fond = b("fond", r.fond))
    }

    fun json(): JSONObject {
        val r = reglage
        return JSONObject().put("ok", true).put("indicatif", r.indicatif).put("locator", r.locator).put("texte", r.texte).put("heure", r.heure)
            .put("logo", r.logo).put("position", r.position.cle).put("taille", r.taille.cle).put("fond", r.fond).put("texteMax", TEXTE_MAX)
    }

    /**
     * The overlay for a stream at [debitTs] from [source], its PNG written to
     * [png]; null when nothing is to be shown (the command then unchanged).
     * [direct]: the time kept running (PNGs into ffmpeg's input); a still
     * PNG otherwise (the preview).
     */
    fun pour(debitTs: Long, source: String, indicatif: String, locator: String, png: File, direct: Boolean = true): IncrustationTs? {
        val r = reglage
        if (!actif(r, source, indicatif, locator)) return null
        val (w, h) = EmissionDatvPc.taille(debitTs)
        png.parentFile?.mkdirs()
        ImageIO.write(rendu(w, h, r, indicatif, locator, source, horloge()), "png", png)
        // The clock card takes ffmpeg's input itself (and shows the time): the overlay then a still one.
        val enDirect = direct && r.heure && source != "horloge"
        return IncrustationTs(png, enDirect, { ms -> octets(rendu(w, h, r, indicatif, locator, source, ms)) }, horloge)
    }

    /** What ffmpeg gets: a still PNG, or PNGs written twice a second into its input ([alimente]). */
    class IncrustationTs(val png: File, val direct: Boolean, private val image: (Long) -> ByteArray, private val horloge: () -> Long = System::currentTimeMillis) {
        /**
         * Writes a picture each half second into [o] (ffmpeg's input) until
         * ffmpeg goes (the pipe closed) or [encore] says stop; each drawn
         * once a second.
         */
        fun alimente(o: OutputStream, encore: () -> Boolean = { true }) {
            var seconde = -1L
            var b = ByteArray(0)
            val t0 = System.nanoTime()
            var k = 0L
            try {
                while (encore()) {
                    val ms = horloge()
                    if (ms / 1000 != seconde) { seconde = ms / 1000; b = image(ms) }
                    o.write(b); o.flush()
                    k++
                    val attente = t0 / 1_000_000 + k * PERIODE_MS - System.nanoTime() / 1_000_000
                    if (attente > 0) Thread.sleep(attente)
                }
            } catch (_: java.io.IOException) {
            } catch (_: InterruptedException) {
            } finally { runCatching { o.close() } }
        }
    }

    companion object {
        const val TEXTE_MAX = 60
        /** One picture each half second into ffmpeg's input, with the time on. */
        const val PERIODE_MS = 500L

        fun nettoie(t: String): String = t.replace(Regex("[\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().take(TEXTE_MAX)

        /** The test cards show the callsign, the locator and the mark already: not twice. */
        fun surMire(source: String) = source == "mire" || source == "animee" || source == "horloge"

        fun actif(r: Reglage, source: String, indicatif: String, locator: String): Boolean =
            lignes(r, source, indicatif, locator, 0L).let { it.call.isNotEmpty() || it.loc.isNotEmpty() || it.logo || it.texte.isNotEmpty() || it.heure.isNotEmpty() }

        private class Lignes(val call: String, val loc: String, val logo: Boolean, val texte: String, val heure: String)

        private fun lignes(r: Reglage, source: String, indicatif: String, locator: String, ms: Long): Lignes {
            val m = surMire(source)
            return Lignes(if (r.indicatif && !m) indicatif.trim().uppercase() else "", if (r.locator && !m) locator.trim().uppercase() else "",
                r.logo && !m, nettoie(r.texte), if (r.heure && source != "horloge") heure(ms) else "")
        }

        fun heure(ms: Long): String = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss").withZone(java.time.ZoneOffset.UTC)
            .format(java.time.Instant.ofEpochMilli(ms)) + " UTC"

        fun octets(im: BufferedImage): ByteArray = ByteArrayOutputStream().also { ImageIO.write(im, "png", it) }.toByteArray()

        /** ffmpeg's input for the overlay (added after the others: their numbers unchanged). */
        fun entree(inc: IncrustationTs): List<String> =
            if (inc.direct) listOf("-f", "image2pipe", "-framerate", "2", "-i", "pipe:0")
            else listOf("-re", "-loop", "1", "-framerate", "1", "-i", inc.png.path)

        /** [chaine] (a filter chain on the picture, unlabelled) with input [k]'s overlay laid on it, as [v]. */
        fun filtre(chaine: String, k: Int, w: Int, h: Int): String =
            "$chaine[s];[$k:v]scale=$w:$h,format=rgba[o];[s][o]overlay=0:0,format=yuv420p[v]"

        /**
         * One frame of what the command [l] sends, as a JPEG into [sortie]:
         * the same inputs and filters, read as fast as they come, the sound
         * and the coding left out.
         */
        fun commandeApercu(l: List<String>, sortie: File): List<String> {
            val fin = l.indexOf("-c:v").takeIf { it > 0 } ?: l.size
            val a = ArrayList<String>()
            var i = 0
            while (i < fin) {
                val x = l[i]
                when {
                    x == "-re" -> i++
                    x == "-map" && i + 1 < fin && ":a:" in l[i + 1] -> i += 2
                    else -> { a += x; i++ }
                }
            }
            a += listOf("-frames:v", "1", "-q:v", "3", "-f", "image2", "-update", "1", "-y", sortie.path)
            return a
        }

        /**
         * The overlay at [w]×[h], transparent but for the box: callsign,
         * locator and mark on the first line, the text, then the time; a
         * strip along the bottom for [Position.BANDEAU].
         */
        fun rendu(w: Int, h: Int, r: Reglage, indicatif: String, locator: String, source: String, ms: Long): BufferedImage {
            val im = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            val l = lignes(r, source, indicatif, locator, ms)
            val g = im.createGraphics()
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                if (r.position == Position.BANDEAU) bandeau(g, w, h, r, l) else coin(g, w, h, r, l)
            } finally { g.dispose() }
            return im
        }

        /** A piece of a line: its text, font and colour, or the mark's tile. */
        private class Morceau(val texte: String, val font: Font, val couleur: Color, val tuile: Boolean = false)

        private fun largeur(g: Graphics2D, m: Morceau): Float =
            if (m.tuile) m.font.size2D * 1.15f else g.getFontMetrics(m.font).stringWidth(m.texte).toFloat()

        private fun largeur(g: Graphics2D, ligne: List<Morceau>, ecart: Float): Float =
            ligne.sumOf { largeur(g, it).toDouble() }.toFloat() + ecart * (ligne.size - 1).coerceAtLeast(0)

        private fun premiere(l: Lignes, t: Float): List<Morceau> {
            val p = ArrayList<Morceau>()
            if (l.logo) { p += Morceau("", police(true, t), Color.WHITE, tuile = true); if (l.call.isEmpty()) p += Morceau("SatMe", police(true, t), Color.WHITE) }
            if (l.call.isNotEmpty()) p += Morceau(l.call, police(true, t), Color.WHITE)
            if (l.loc.isNotEmpty()) p += Morceau(l.loc, police(false, t * 0.8f), Color(0xFFD27F))
            return p
        }

        private fun police(gras: Boolean, px: Float): Font = Font(Font.SANS_SERIF, if (gras) Font.BOLD else Font.PLAIN, 1).deriveFont(px.coerceAtLeast(6f))

        private fun coin(g: Graphics2D, w: Int, h: Int, r: Reglage, l: Lignes) {
            val marge = minOf(w, h) * 0.04f
            val dispo = w - 2 * marge
            var t = h * r.taille.part
            // The lines, smaller until the widest fits.
            var lignes: List<List<Morceau>>
            var ecart: Float
            var pad: Float
            while (true) {
                ecart = t * 0.35f; pad = t * 0.35f
                lignes = listOf(premiere(l, t),
                    if (l.texte.isNotEmpty()) listOf(Morceau(l.texte, police(false, t * 0.75f), Color.WHITE)) else emptyList(),
                    if (l.heure.isNotEmpty()) listOf(Morceau(l.heure, police(true, t * 0.7f), Color(0x9FE8FF))) else emptyList()).filter { it.isNotEmpty() }
                val large = lignes.maxOfOrNull { largeur(g, it, ecart) } ?: 0f
                if (large + 2 * pad <= dispo || t <= 8f) break
                t = maxOf(8f, t * 0.92f)
            }
            if (lignes.isEmpty()) return
            val hauteurs = lignes.map { li -> li.maxOf { it.font.size2D } * 1.25f }
            val bw = minOf(dispo, (lignes.maxOf { largeur(g, it, ecart) } + 2 * pad))
            val bh = hauteurs.sum() + pad * 1.2f
            val gauche = r.position == Position.HAUT_GAUCHE || r.position == Position.BAS_GAUCHE
            val haut = r.position == Position.HAUT_GAUCHE || r.position == Position.HAUT_DROITE
            val x0 = if (gauche) marge else w - marge - bw
            val y0 = if (haut) marge else h - marge - bh
            if (r.fond) boite(g, x0, y0, bw, bh, t)
            var y = y0 + pad * 0.6f
            for ((i, li) in lignes.withIndex()) {
                val lh = hauteurs[i]
                // Each line flush with the box's side nearest the picture's edge.
                val lw = largeur(g, li, ecart)
                var x = if (gauche) x0 + pad else x0 + bw - pad - lw
                for (m in li) {
                    dessine(g, m, x, y, lh, r.fond, x0 + bw - pad)
                    x += largeur(g, m) + ecart
                }
                y += lh
            }
        }

        private fun bandeau(g: Graphics2D, w: Int, h: Int, r: Reglage, l: Lignes) {
            var t = h * r.taille.part * 0.85f
            val marge = w * 0.03f
            var gauche: List<Morceau>; var milieu: Morceau?; var droite: Morceau?
            var ecart: Float
            while (true) {
                ecart = t * 0.5f
                gauche = premiere(l, t)
                milieu = l.texte.takeIf { it.isNotEmpty() }?.let { Morceau(it, police(false, t * 0.85f), Color.WHITE) }
                droite = l.heure.takeIf { it.isNotEmpty() }?.let { Morceau(it, police(true, t * 0.8f), Color(0x9FE8FF)) }
                val tout = largeur(g, gauche, ecart) + (milieu?.let { largeur(g, it) + 2 * ecart } ?: 0f) + (droite?.let { largeur(g, it) + 2 * ecart } ?: 0f)
                if (tout <= w - 2 * marge || t <= 8f) break
                t = maxOf(8f, t * 0.92f)
            }
            val bh = t * 1.7f
            val y0 = h - bh - h * 0.03f
            if (r.fond) { g.color = Color(0, 0, 0, 150); g.fill(java.awt.geom.Rectangle2D.Float(0f, y0, w.toFloat(), bh)) }
            val lh = bh
            var x = marge
            val fin = w - marge
            for (m in gauche) { dessine(g, m, x, y0, lh, r.fond, fin); x += largeur(g, m) + ecart }
            val dx = droite?.let { fin - largeur(g, it) }
            droite?.let { dessine(g, it, dx!!, y0, lh, r.fond, fin) }
            milieu?.let { m ->
                val debut = if (gauche.isEmpty()) marge else x + ecart
                val bout = (dx ?: fin) - ecart
                val mw = largeur(g, m)
                // Centred when there is room, else after the callsign.
                val cx = ((w - mw) / 2).coerceIn(debut, maxOf(debut, bout - mw))
                dessine(g, m, cx, y0, lh, r.fond, bout)
            }
        }

        private fun boite(g: Graphics2D, x: Float, y: Float, bw: Float, bh: Float, t: Float) {
            g.color = Color(0, 0, 0, 150)
            g.fill(RoundRectangle2D.Float(x, y, bw, bh, t * 0.5f, t * 0.5f))
        }

        /** [m] at [x], centred in the line from [y] of height [lh]; cut with "…" past [limite]. */
        private fun dessine(g: Graphics2D, m: Morceau, x: Float, y: Float, lh: Float, fond: Boolean, limite: Float) {
            if (m.tuile) {
                val cote = largeur(g, m)
                val yy = y + (lh - cote) / 2
                val i = MarquePc.icone
                if (i != null) {
                    val clip = g.clip
                    g.clip(RoundRectangle2D.Float(x, yy, cote, cote, cote * 0.48f, cote * 0.48f))
                    g.drawImage(i, x.toInt(), yy.toInt(), cote.toInt(), cote.toInt(), null)
                    g.clip = clip
                } else {
                    g.color = Color(0x3FE0C8); g.fill(java.awt.geom.Ellipse2D.Float(x, yy, cote, cote))
                }
                return
            }
            g.font = m.font
            val fm = g.fontMetrics
            var s = m.texte
            while (s.length > 1 && x + fm.stringWidth(s) > limite) s = s.dropLast(2) + "…"
            val base = y + (lh - (fm.ascent + fm.descent)) / 2 + fm.ascent
            if (!fond) {
                // No box: a dark outline keeps the text readable on any picture.
                val e = maxOf(1f, m.font.size2D / 14f)
                g.color = Color(0, 0, 0, 200)
                for (dx in listOf(-e, 0f, e)) for (dy in listOf(-e, 0f, e)) if (dx != 0f || dy != 0f) g.drawString(s, x + dx, base + dy)
            }
            g.composite = AlphaComposite.SrcOver
            g.color = m.couleur
            g.drawString(s, x, base)
        }
    }
}
