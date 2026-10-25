/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

import java.io.InputStream
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * What a sheet is laid on and what is laid over it: the backgrounds SatMe
 * offers (drawn, or NASA pictures in the public domain), a picture fitted to
 * the sheet without being stretched, and a patch's plain surround made
 * transparent. The drawing itself is the platform's (PlancheRendu,
 * PlancheRenduPc); what is decided here is the same for both.
 */
object PlancheDecor {

    /**
     * A built-in background: a gradient from top to bottom ([couleurs] at
     * [positions]), soft clouds of colour on it ([nuages]), stars or not, the
     * Earth's arc at the bottom ([horizon]) — or a picture ([ressource], in
     * this package's `decors/`), with where it comes from ([source]).
     */
    class Decor(
        val id: String,
        /** Its name, a key of the string tables. */
        val cle: String,
        val couleurs: IntArray = intArrayOf(),
        val positions: FloatArray = floatArrayOf(),
        val etoiles: Boolean = false,
        val ressource: String? = null,
        val source: String = "",
        val nuages: List<Nuage> = emptyList(),
        val horizon: Horizon? = null
    )

    /**
     * A cloud of colour (a nebula's): a disc fading out from its centre
     * ([x], [y] in fractions of the sheet), [r] in fractions of its width.
     */
    class Nuage(val x: Float, val y: Float, val r: Float, val couleur: Int)

    /**
     * The Earth seen from orbit: a disc of [rayon] sheet widths whose top
     * reaches [haut] (a fraction of the height) in the middle, [sol] dark
     * and lit at its edge, a glow of atmosphere [halo] round it.
     */
    class Horizon(val haut: Float, val rayon: Float, val sol: Int, val halo: Int) {
        /** The halo's reach beyond the disc, in its radius. */
        val epaisseur: Float get() = 0.045f
        /** The colours of the disc from its centre (the night side) to its lit edge. */
        fun disque(): Pair<IntArray, FloatArray> = intArrayOf(sol, sol, PlancheStyle.melange(sol, halo, 0.35f), PlancheStyle.melange(halo, 0xFFFFFFFF.toInt(), 0.35f)) to
            floatArrayOf(0f, 0.93f, 0.985f, 1f)
        /** The halo's colours from the disc's edge outward. */
        fun lueur(): Pair<IntArray, FloatArray> {
            val r = 1f / (1f + epaisseur)
            return intArrayOf(PlancheStyle.alpha(halo, 0), PlancheStyle.alpha(halo, 0), PlancheStyle.alpha(halo, 230), PlancheStyle.alpha(halo, 90), PlancheStyle.alpha(halo, 0)) to
                floatArrayOf(0f, r - 0.002f, r, r + (1 - r) * 0.35f, 1f)
        }
    }

    /** The SatMe starry sky, the background of a generic sheet since the first one. */
    val ETOILES = Decor("", "planche_decor_etoiles",
        intArrayOf(0xFF02040A.toInt(), 0xFF0A1630.toInt(), 0xFF123A6B.toInt()), floatArrayOf(0f, 0.7f, 1f), etoiles = true)

    val INTEGRES: List<Decor> = listOf(
        ETOILES,
        Decor("crepuscule", "planche_decor_crepuscule",
            intArrayOf(0xFF04050D.toInt(), 0xFF141A44.toInt(), 0xFF4A2660.toInt(), 0xFFC8643A.toInt()),
            floatArrayOf(0f, 0.55f, 0.84f, 1f), etoiles = true),
        Decor("abysse", "planche_decor_abysse",
            intArrayOf(0xFF020B12.toInt(), 0xFF07303F.toInt(), 0xFF0C5B66.toInt()), floatArrayOf(0f, 0.65f, 1f)),
        Decor("ardoise", "planche_decor_ardoise",
            intArrayOf(0xFF0C0E12.toInt(), 0xFF1E232B.toInt(), 0xFF2E3540.toInt()), floatArrayOf(0f, 0.6f, 1f)),
        Decor("nebuleuse", "planche_decor_nebuleuse",
            intArrayOf(0xFF04030B.toInt(), 0xFF0C0820.toInt(), 0xFF140A28.toInt()), floatArrayOf(0f, 0.6f, 1f), etoiles = true,
            nuages = listOf(
                Nuage(0.22f, 0.30f, 0.42f, 0x66C2287F), Nuage(0.72f, 0.24f, 0.46f, 0x5A2E5BFF),
                Nuage(0.52f, 0.70f, 0.50f, 0x4C7B3CFF), Nuage(0.10f, 0.86f, 0.30f, 0x3C00B8D4),
                Nuage(0.88f, 0.78f, 0.26f, 0x40FF6A3D), Nuage(0.40f, 0.42f, 0.22f, 0x30FFB0E8))),
        Decor("orbite", "planche_decor_orbite",
            intArrayOf(0xFF010309.toInt(), 0xFF041026.toInt(), 0xFF0A2350.toInt()), floatArrayOf(0f, 0.55f, 1f), etoiles = true,
            horizon = Horizon(0.80f, 1.5f, 0xFF041634.toInt(), 0xFF46B4FF.toInt())),
        Decor("nasa_aurore", "planche_decor_aurore", ressource = "aurore.jpg",
            source = "NASA, iss066e146457 — images.nasa.gov/details/iss066e146457"),
        Decor("nasa_limbe", "planche_decor_limbe", ressource = "limbe.jpg",
            source = "NASA, iss062e148363 — images.nasa.gov/details/iss062e148363"),
        Decor("nasa_lever", "planche_decor_lever", ressource = "lever.jpg",
            source = "NASA, iss071e439624 — images.nasa.gov/details/iss071e439624"),
        Decor("nasa_lagune", "planche_decor_lagune", ressource = "lagune.jpg",
            source = "NASA, ESA — images.nasa.gov/details/GSFC_20171208_Archive_e001955"),
        Decor("nasa_iss_espace", "planche_decor_iss_espace", ressource = "iss_espace.jpg",
            source = "NASA, s131e011050 — images.nasa.gov/details/s131e011050"),
        Decor("nasa_iss_terre", "planche_decor_iss_terre", ressource = "iss_terre.jpg",
            source = "NASA, s132e012208 — images.nasa.gov/details/s132e012208"),
        Decor("nasa_panneaux", "planche_decor_panneaux", ressource = "panneaux.jpg",
            source = "NASA, s118e07242 — images.nasa.gov/details/s118e07242"),
        Decor("nasa_nuit", "planche_decor_nuit", ressource = "nuit.jpg",
            source = "NASA, iss029e012564 — images.nasa.gov/details/iss029e012564"),
        Decor("nasa_papillon", "planche_decor_papillon", ressource = "papillon.jpg",
            source = "NASA/JPL-Caltech, PIA23121 — images.nasa.gov/details/PIA23121"))

    /** The built-in background [id]; null for a file (or nothing known). */
    fun integre(id: String): Decor? = INTEGRES.firstOrNull { it.id == id }

    /** The picture of a built-in background, null when it is drawn. */
    fun ouvre(d: Decor): InputStream? = d.ressource?.let { PlancheDecor::class.java.getResourceAsStream("decors/$it") }

    /** A plain file name of the sheets folder, nothing that could lead elsewhere. */
    fun nomFichier(nom: String): Boolean =
        nom.length in 1..80 && nom.matches(Regex("[A-Za-z0-9_-][A-Za-z0-9_.-]*")) && ".." !in nom

    // ------------------------------------------------------- fitting

    /** A rectangle in pixels. */
    data class Rect(val x: Int, val y: Int, val w: Int, val h: Int)

    /**
     * The part of a [sw]×[sh] picture that fills a [dw]×[dh] place without
     * being stretched (cover): as large as it can be, centred, its shape that
     * of the place — what is left over is cut off, on both sides equally.
     */
    fun couvre(sw: Int, sh: Int, dw: Float, dh: Float): Rect {
        if (sw <= 0 || sh <= 0 || dw <= 0f || dh <= 0f) return Rect(0, 0, sw.coerceAtLeast(0), sh.coerceAtLeast(0))
        val r = dw / dh
        return if (sw.toFloat() / sh > r) {
            val w = (sh * r).toInt().coerceIn(1, sw); Rect((sw - w) / 2, 0, w, sh)
        } else {
            val h = (sw / r).toInt().coerceIn(1, sh); Rect(0, (sh - h) / 2, sw, h)
        }
    }

    // ------------------------------------------------------- surround

    private fun canal(c: Int, d: Int) = (c shr d) and 0xFF

    /**
     * A patch's surround made transparent: the colour of its edge (when
     * nearly the same all round — the black or white a badge is scanned on)
     * taken away from the edges inwards, as far as it goes within
     * [tolerance]; what is left gets a soft edge. A round patch is cut to
     * its circle, the cleanest outline. A square picture with no plain edge
     * is cut to the circle within it. ARGB in, ARGB out; a picture already
     * transparent all round is given back as it is.
     */
    fun detoure(px: IntArray, w: Int, h: Int, tolerance: Int = 48): IntArray {
        val n = w * h
        if (w < 3 || h < 3 || px.size < n) return px.copyOf()
        val bord = ArrayList<Int>(2 * (w + h))
        for (x in 0 until w) { bord += x; bord += (h - 1) * w + x }
        for (y in 1 until h - 1) { bord += y * w; bord += y * w + w - 1 }
        if (bord.count { canal(px[it], 24) < 16 } > bord.size / 2) return px.copyOf()
        // The surround's colour: the median of the edge, channel by channel.
        fun mediane(d: Int) = bord.map { canal(px[it], d) }.sorted()[bord.size / 2]
        val rr = mediane(16); val rg = mediane(8); val rb = mediane(0)
        fun ecart(c: Int) = maxOf(abs(canal(c, 16) - rr), abs(canal(c, 8) - rg), abs(canal(c, 0) - rb))
        val uni = bord.count { ecart(px[it]) <= tolerance } >= bord.size * 0.6f
        val dehors = BooleanArray(n)
        if (uni) {
            val file = IntArray(n); var q = 0; var t = 0
            for (p in bord) if (!dehors[p] && ecart(px[p]) <= tolerance) { dehors[p] = true; file[q++] = p }
            while (t < q) {
                val p = file[t++]; val x = p % w; val y = p / w
                fun va(v: Int) { if (!dehors[v] && ecart(px[v]) <= tolerance) { dehors[v] = true; file[q++] = v } }
                if (x > 0) va(p - 1); if (x < w - 1) va(p + 1); if (y > 0) va(p - w); if (y < h - 1) va(p + w)
            }
        }
        // What is left: its bounds, and whether it is a disc.
        var x0 = w; var y0 = h; var x1 = -1; var y1 = -1; var reste = 0
        for (p in 0 until n) if (!dehors[p]) {
            reste++; val x = p % w; val y = p / w
            if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
        }
        if (reste == 0) return px.copyOf()
        val bw = x1 - x0 + 1; val bh = y1 - y0 + 1
        val carre = bw.toFloat() / bh in 0.9f..1.11f
        if (!uni) return if (w.toFloat() / h in 0.85f..1.18f) disque(px, w, h, 0, 0, w, h) else px.copyOf()
        if (carre && reste < n * 0.98f && estDisque(dehors, w, x0, y0, bw, bh, reste)) return disque(px, w, h, x0, y0, bw, bh)
        // Any other shape: transparent outside, a soft edge where it meets the surround.
        val out = IntArray(n)
        for (p in 0 until n) {
            if (dehors[p]) { out[p] = 0; continue }
            val x = p % w; val y = p / w
            var voisins = 0; var dedans = 0
            for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx; val yy = y + dy
                if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue
                voisins++; if (!dehors[yy * w + xx]) dedans++
            }
            var a = canal(px[p], 24)
            if (dedans < voisins) {
                // On the edge: as opaque as it is unlike the surround, and as much of it is inside.
                val parCouleur = ((ecart(px[p]) - tolerance) * 255 / tolerance.coerceAtLeast(1)).coerceIn(96, 255)
                val parVoisins = 255 * dedans / voisins
                a = a * minOf(parCouleur, (parVoisins + 255) / 2) / 255
            }
            out[p] = (a shl 24) or (px[p] and 0xFFFFFF)
        }
        return out
    }

    /** What is left fills the ellipse within its bounds, and little lies outside it. */
    private fun estDisque(dehors: BooleanArray, w: Int, x0: Int, y0: Int, bw: Int, bh: Int, reste: Int): Boolean {
        val part = reste.toFloat() / (bw.toFloat() * bh)
        if (part !in 0.70f..0.84f) return false
        val cx = x0 + bw / 2f; val cy = y0 + bh / 2f; val rx = bw / 2f; val ry = bh / 2f
        var hors = 0; var trou = 0; var dansDisque = 0
        for (y in y0 until y0 + bh) for (x in x0 until x0 + bw) {
            val dx = (x + 0.5f - cx) / rx; val dy = (y + 0.5f - cy) / ry
            val d = dx * dx + dy * dy
            val garde = !dehors[y * w + x]
            if (d > 1.1f && garde) hors++
            if (d < 0.8f) { dansDisque++; if (!garde) trou++ }
        }
        return hors < reste * 0.03f && trou < dansDisque * 0.03f
    }

    /** The picture cut to the ellipse within [x0], [y0], [bw]×[bh], its edge smoothed over a pixel. */
    private fun disque(px: IntArray, w: Int, h: Int, x0: Int, y0: Int, bw: Int, bh: Int): IntArray {
        val out = IntArray(w * h)
        val cx = x0 + bw / 2f; val cy = y0 + bh / 2f; val rx = bw / 2f; val ry = bh / 2f
        val r = (rx + ry) / 2
        for (y in 0 until h) for (x in 0 until w) {
            val dx = (x + 0.5f - cx) / rx; val dy = (y + 0.5f - cy) / ry
            val k = ((1f - sqrt(dx * dx + dy * dy)) * r + 0.5f).coerceIn(0f, 1f)
            val p = y * w + x
            val a = (canal(px[p], 24) * k).toInt()
            out[p] = (a shl 24) or (px[p] and 0xFFFFFF)
        }
        return out
    }
}
