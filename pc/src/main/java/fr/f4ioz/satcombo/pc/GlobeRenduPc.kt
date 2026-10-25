/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.demo.ApiCarte
import fr.f4ioz.satcombo.demo.GlobeDonnees
import fr.f4ioz.satcombo.domain.JournalPassage
import fr.f4ioz.satcombo.domain.JournalPassage.TypeMarque
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RadialGradientPaint
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.geom.AffineTransform
import java.awt.geom.Area
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.awt.image.DataBufferInt
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The journal's globe, for its pictures, GIFs and videos: the Earth seen
 * from space (orthographic), turned to keep the station and the satellite
 * in view, lit by the Sun of the moment, in the page's six looks
 * ([fr.f4ioz.satcombo.demo.OrbitesJs] STYLES). Over it the ground track
 * (gone solid, to come dashed), the footprint, the satellite at its
 * height, the line to it while it is up, the station and the contacts.
 * The cartoon's world ([GlobeDonnees]): mountains, landmarks, boats,
 * whales, forests, penguins, cities.
 *
 * Java2D only: the coastline clipped in 3D at the horizon (the hidden
 * part closed along the limb), masks drawn flat, each pixel of the disc
 * coloured and lit.
 */
object GlobeRenduPc {

    val STYLES = listOf("cartoon", "realiste", "holo", "points", "radar", "vintage")
    const val DEFAUT = "cartoon"

    /** The globe asked by a request (`vue=globe`, `style=`): its look; null: the sky or the map. */
    fun styleDemande(vue: String, style: String): String? =
        if (vue.trim() != "globe") null else style.trim().lowercase().takeIf { it in STYLES } ?: DEFAUT

    /**
     * The page's choices for the globe: the cities ([villes] « toutes », « capitales », « aucune »),
     * the capitals' labels ([etiquettes] « nom », « drapeau », « nomdrapeau »), how much is drawn
     * ([detail] 0 nothing, 1 summits, landmarks and boats, 2 all the scenery), where the globe sits
     * ([gdx], [gdy] shares of the view's width and height from its middle) and how large ([gz], 1: 85 % of its height).
     */
    data class Options(val villes: String = "toutes", val etiquettes: String = "nom", val detail: Int = 1,
                       val gdx: Float = 0f, val gdy: Float = 0f, val gz: Float = 1f)

    /** The globe's choices from a request; a missing or wrong one keeps its default. */
    fun options(p: (String) -> String): Options {
        fun nombre(k: String, de: Float, a: Float, defaut: Float) = p(k).trim().toFloatOrNull()?.takeIf { it.isFinite() }?.coerceIn(de, a) ?: defaut
        return Options(
            villes = p("villes").trim().takeIf { it in setOf("toutes", "capitales", "aucune") } ?: "toutes",
            etiquettes = p("etiquettes").trim().takeIf { it in setOf("nom", "drapeau", "nomdrapeau") } ?: "nom",
            detail = p("detail").trim().toIntOrNull()?.coerceIn(0, 2) ?: 1,
            gdx = nombre("gdx", -0.5f, 0.5f, 0f), gdy = nombre("gdy", -0.5f, 0.5f, 0f), gz = nombre("gz", 0.5f, 3f, 1f))
    }

    private const val RE = 6371.0
    private const val D2R = PI / 180
    private val STATION = Color(0xFFB454)

    // ---------------------------------------------------------------- vectors

    private fun vec(lat: Double, lon: Double): DoubleArray {
        val a = lat * D2R; val b = lon * D2R
        return doubleArrayOf(cos(a) * cos(b), cos(a) * sin(b), sin(a))
    }
    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun norme(a: DoubleArray): DoubleArray {
        val l = sqrt(dot(a, a)); return if (l < 1e-12) doubleArrayOf(1.0, 0.0, 0.0) else doubleArrayOf(a[0] / l, a[1] / l, a[2] / l)
    }
    private fun lat(p: DoubleArray) = asin(p[2].coerceIn(-1.0, 1.0)) / D2R

    /** Where the view looks from ([c], unit), east and north on the screen, the disc's centre and radius (px). */
    class Camera(val c: DoubleArray, val ox: Float, val oy: Float, val r: Float) {
        val e: DoubleArray
        val n: DoubleArray
        init {
            val ex = -c[1]; val ey = c[0]
            val l = sqrt(ex * ex + ey * ey)
            e = if (l < 1e-6) doubleArrayOf(0.0, 1.0, 0.0) else doubleArrayOf(ex / l, ey / l, 0.0)
            n = doubleArrayOf(c[1] * e[2] - c[2] * e[1], c[2] * e[0] - c[0] * e[2], c[0] * e[1] - c[1] * e[0])
        }
        fun profondeur(p: DoubleArray) = dot(p, c)
        fun x(p: DoubleArray) = (ox + r * dot(p, e)).toFloat()
        fun y(p: DoubleArray) = (oy - r * dot(p, n)).toFloat()
        /** A point above the Earth ([p] in Earth radii) is seen unless the Earth is in front of it. */
        fun voit(p: DoubleArray): Boolean { val u = dot(p, e); val v = dot(p, n); return dot(p, c) >= 0 || u * u + v * v > 1.0 }
        /** A point of the surface: (x, y, cos of its angle to the centre). */
        fun ecran(lat: Double, lon: Double): Triple<Float, Float, Double> { val p = vec(lat, lon); return Triple(x(p), y(p), profondeur(p)) }
    }

    // ---------------------------------------------------------------- the coastline, clipped at the horizon

    /** A ring of the coastline on the sphere (xyz…, counter-clockwise seen from outside); [faux]: its edge from point i is no coast (the ±180° cut, the pole). */
    class Anneau(val p: DoubleArray, val faux: BooleanArray, val centre: DoubleArray, val rayon: Double)

    /** A ring from lon, lat, lon, lat… (degrees), made counter-clockwise, its long edges divided along the great circle. */
    fun anneau(ll: DoubleArray): Anneau? {
        var n = ll.size / 2
        if (n >= 2 && ll[0] == ll[2 * n - 2] && ll[1] == ll[2 * n - 1]) n--
        if (n < 3) return null
        var aire = 0.0
        for (i in 0 until n) { val j = (i + 1) % n; aire += ll[2 * i] * ll[2 * j + 1] - ll[2 * j] * ll[2 * i + 1] }
        val ordre = if (aire < 0) (n - 1 downTo 0).toList() else (0 until n).toList()
        val lo = DoubleArray(n) { ll[2 * ordre[it]] }; val la = DoubleArray(n) { ll[2 * ordre[it] + 1] }
        val pts = ArrayList<Double>(n * 4); val faux = ArrayList<Boolean>(n * 2)
        for (i in 0 until n) {
            val j = (i + 1) % n
            val f = (abs(lo[i]) >= 179.99 && abs(lo[j]) >= 179.99) || (la[i] <= -89.9 && la[j] <= -89.9)
            val a = vec(la[i], lo[i]); val b = vec(la[j], lo[j])
            val ang = acos(dot(a, b).coerceIn(-1.0, 1.0))
            val k = ceil(ang / (1.5 * D2R)).toInt().coerceAtLeast(1)
            for (s in 0 until k) {
                val q = if (s == 0) a else slerp(a, b, ang, s.toDouble() / k)
                pts += q[0]; pts += q[1]; pts += q[2]; faux += f
            }
        }
        val p = pts.toDoubleArray()
        val m = p.size / 3
        var sx = 0.0; var sy = 0.0; var sz = 0.0
        for (i in 0 until m) { sx += p[3 * i]; sy += p[3 * i + 1]; sz += p[3 * i + 2] }
        val c = norme(doubleArrayOf(sx, sy, sz))
        var mini = 1.0
        for (i in 0 until m) mini = min(mini, p[3 * i] * c[0] + p[3 * i + 1] * c[1] + p[3 * i + 2] * c[2])
        return Anneau(p, faux.toBooleanArray(), c, acos(mini.coerceIn(-1.0, 1.0)))
    }

    private fun slerp(a: DoubleArray, b: DoubleArray, ang: Double, t: Double): DoubleArray {
        if (ang < 1e-9) return a
        val s = sin(ang); val ka = sin((1 - t) * ang) / s; val kb = sin(t * ang) / s
        return doubleArrayOf(a[0] * ka + b[0] * kb, a[1] * ka + b[1] * kb, a[2] * ka + b[2] * kb)
    }

    private class Morceau {
        val x = ArrayList<Float>(); val y = ArrayList<Float>(); val faux = ArrayList<Boolean>()
        var entree = 0.0; var sortie = 0.0
        fun ajoute(px: Float, py: Float, f: Boolean) { x += px; y += py; faux += f }
    }

    /**
     * A ring as seen: what is on the near side filled into [remplir] — a part
     * crossing the horizon closed along the limb (counter-clockwise, the land
     * on its left), never across the disc — and its coast into [tracer].
     */
    fun decoupe(a: Anneau, cam: Camera, remplir: Path2D.Float, tracer: Path2D.Float?) {
        val c = cam.c
        if (acos(dot(a.centre, c).coerceIn(-1.0, 1.0)) - a.rayon > PI / 2 + 1e-3) return
        val p = a.p; val n = p.size / 3
        val d = DoubleArray(n) { p[3 * it] * c[0] + p[3 * it + 1] * c[1] + p[3 * it + 2] * c[2] }
        fun px(i: Int) = (cam.ox + cam.r * (p[3 * i] * cam.e[0] + p[3 * i + 1] * cam.e[1] + p[3 * i + 2] * cam.e[2])).toFloat()
        fun py(i: Int) = (cam.oy - cam.r * (p[3 * i] * cam.n[0] + p[3 * i + 1] * cam.n[1] + p[3 * i + 2] * cam.n[2])).toFloat()
        var cache = -1
        for (i in 0 until n) if (d[i] < 0) { cache = i; break }
        if (cache < 0) {
            for (i in 0 until n) if (i == 0) remplir.moveTo(px(i), py(i)) else remplir.lineTo(px(i), py(i))
            remplir.closePath()
            if (tracer != null) {
                var leve = true
                for (k in 0..n) {
                    val i = k % n
                    if (k > 0 && a.faux[(i + n - 1) % n]) leve = true
                    if (leve) { tracer.moveTo(px(i), py(i)); leve = false } else tracer.lineTo(px(i), py(i))
                }
            }
            return
        }
        if (d.all { it < 0 }) return
        // Where an edge crosses the horizon: on the limb, and its angle there.
        fun limbe(i: Int, j: Int): DoubleArray {
            val t = d[i] / (d[i] - d[j])
            var x = p[3 * i] + t * (p[3 * j] - p[3 * i]); var y = p[3 * i + 1] + t * (p[3 * j + 1] - p[3 * i + 1]); var z = p[3 * i + 2] + t * (p[3 * j + 2] - p[3 * i + 2])
            val k = x * c[0] + y * c[1] + z * c[2]; x -= k * c[0]; y -= k * c[1]; z -= k * c[2]
            val u = x * cam.e[0] + y * cam.e[1] + z * cam.e[2]; val v = x * cam.n[0] + y * cam.n[1] + z * cam.n[2]
            val ang = atan2(v, u)
            return doubleArrayOf(cam.ox + cam.r * cos(ang), cam.oy - cam.r * sin(ang), ang)
        }
        val morceaux = ArrayList<Morceau>()
        var cur: Morceau? = null
        for (k in 0 until n) {
            val i = (cache + k) % n; val j = (i + 1) % n
            if (d[i] < 0 && d[j] >= 0) {
                val l = limbe(i, j)
                cur = Morceau().also { it.ajoute(l[0].toFloat(), l[1].toFloat(), true); it.entree = l[2]; it.ajoute(px(j), py(j), a.faux[i]) }
            } else if (d[i] >= 0 && d[j] >= 0) cur?.ajoute(px(j), py(j), a.faux[i])
            else if (d[i] >= 0 && d[j] < 0) {
                val l = limbe(i, j)
                cur?.let { it.ajoute(l[0].toFloat(), l[1].toFloat(), a.faux[i]); it.sortie = l[2]; morceaux += it }
                cur = null
            }
        }
        if (tracer != null) for (m in morceaux) {
            for (k in m.x.indices) if (k == 0 || m.faux[k]) tracer.moveTo(m.x[k], m.y[k]) else tracer.lineTo(m.x[k], m.y[k])
        }
        // Rejoined along the limb: from each exit, counter-clockwise to the nearest entry.
        val pris = BooleanArray(morceaux.size)
        fun ecart(de: Double, a2: Double): Double { var x = (a2 - de) % (2 * PI); if (x < 0) x += 2 * PI; return x }
        for (s in morceaux.indices) {
            if (pris[s]) continue
            var k = s; var premier = true
            while (true) {
                pris[k] = true
                val m = morceaux[k]
                for (q in m.x.indices) if (premier && q == 0) remplir.moveTo(m.x[q], m.y[q]) else remplir.lineTo(m.x[q], m.y[q])
                premier = false
                var suivant = s; var mieux = Double.MAX_VALUE
                for (o in morceaux.indices) { val e = ecart(m.sortie, morceaux[o].entree); if (e < mieux) { mieux = e; suivant = o } }
                val pas = ceil(mieux / 0.04).toInt()
                for (q in 1 until pas) { val ang = m.sortie + mieux * q / pas; remplir.lineTo((cam.ox + cam.r * cos(ang)).toFloat(), (cam.oy - cam.r * sin(ang)).toFloat()) }
                if (suivant == s || pris[suivant]) { remplir.closePath(); break }
                k = suivant
            }
        }
    }

    /** The coastline, simplified a little finer than the page's map, and flat (lon, lat…) for the land mask. */
    private class Terres(val anneaux: List<Anneau>, val plats: List<DoubleArray>)

    private val terres: Terres by lazy {
        val t = GlobeRenduPc::class.java.getResourceAsStream("/traits/land.json")?.bufferedReader()?.use { it.readText() }
        if (t == null) Terres(emptyList(), emptyList()) else {
            val a = org.json.JSONArray(ApiCarte.traits(t, 0.05))
            val plats = (0 until a.length()).map { k -> val l = a.getJSONArray(k); DoubleArray(l.length()) { l.getDouble(it) } }
            Terres(plats.mapNotNull { anneau(it) }, plats)
        }
    }

    /** The land flat (lon, lat), 2048×1024: what is land (the dotted look, the scenery). */
    private val masquePlat: ByteArray by lazy {
        val w = 2048; val h = 1024
        val b = BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY)
        val g = b.createGraphics()
        val p = Path2D.Float(Path2D.WIND_NON_ZERO)
        for (l in terres.plats) {
            for (i in 0 until l.size / 2) { val x = ((l[2 * i] + 180) / 360 * w).toFloat(); val y = ((90 - l[2 * i + 1]) / 180 * h).toFloat(); if (i == 0) p.moveTo(x, y) else p.lineTo(x, y) }
            p.closePath()
        }
        g.color = Color.WHITE; g.fill(p); g.dispose()
        (b.raster.dataBuffer as DataBufferByte).data
    }
    fun surTerre(lat: Double, lon: Double): Boolean {
        val x = ((lon + 180) / 360 * 2048).toInt().coerceIn(0, 2047); val y = ((90 - lat) / 180 * 1024).toInt().coerceIn(0, 1023)
        return (masquePlat[y * 2048 + x].toInt() and 255) > 128
    }

    /** The dotted look's points, spread evenly (Fibonacci), those on land. */
    private val points: List<DoubleArray> by lazy {
        val n = 26000; val or = PI * (3 - sqrt(5.0))
        (0 until n).mapNotNull { i ->
            val z = 1 - (i / (n - 1.0)) * 2; val r = sqrt(1 - z * z); val th = or * i
            val p = doubleArrayOf(cos(th) * r, sin(th) * r, z)
            if (surTerre(lat(p), atan2(p[1], p[0]) / D2R)) p else null
        }
    }

    // ---------------------------------------------------------------- the Sun, the satellite, the camera

    /** Under the Sun at [tMs] (low precision: the day's shading). */
    fun soleil(tMs: Long): DoubleArray {
        val n = (tMs - 946_728_000_000L) / 86_400_000.0
        val l = (280.460 + 0.9856474 * n) % 360; val g = ((357.528 + 0.9856003 * n) % 360) * D2R
        val lam = (l + 1.915 * sin(g) + 0.020 * sin(2 * g)) * D2R
        val eps = (23.439 - 0.0000004 * n) * D2R
        val ra = atan2(cos(eps) * sin(lam), cos(lam)); val dec = asin(sin(eps) * sin(lam))
        val gmst = (280.46061837 + 360.98564736629 * n) % 360
        return vec(dec / D2R, ra / D2R - gmst)
    }

    /** Under the satellite at [t], between the track's points: where (unit) and how high (km). */
    fun sousSatellite(sol: List<JournalPassage.Sol>, t: Long): Pair<DoubleArray, Double>? {
        if (sol.isEmpty()) return null
        if (t <= sol.first().tMs) return vec(sol.first().lat, sol.first().lon) to sol.first().altKm
        if (t >= sol.last().tMs) return vec(sol.last().lat, sol.last().lon) to sol.last().altKm
        var lo = 0; var hi = sol.size - 1
        while (hi - lo > 1) { val m = (lo + hi) / 2; if (sol[m].tMs <= t) lo = m else hi = m }
        val a = sol[lo]; val b = sol[hi]
        val k = (t - a.tMs).toDouble() / (b.tMs - a.tMs).coerceAtLeast(1)
        val va = vec(a.lat, a.lon); val vb = vec(b.lat, b.lon)
        return norme(doubleArrayOf(va[0] + (vb[0] - va[0]) * k, va[1] + (vb[1] - va[1]) * k, va[2] + (vb[2] - va[2]) * k)) to a.altKm + (b.altKm - a.altKm) * k
    }

    /** Where the view looks at [t]: half way from the station to the satellite, smoothed over a few minutes (no jump). */
    fun centre(sc: RenduPc.Scene, t: Long): DoubleArray {
        val qth = sc.qth?.let { vec(it.first, it.second) }
        if (sc.sol.isEmpty()) return qth ?: vec(30.0, 0.0)
        var x = 0.0; var y = 0.0; var z = 0.0
        for (k in -8..8) {
            val w = exp(-(k / 4.0).pow(2))
            val s = sousSatellite(sc.sol, t + k * 15_000L)!!.first
            val m = if (qth == null) s else norme(doubleArrayOf(qth[0] + s[0], qth[1] + s[1], qth[2] + s[2]))
            x += w * m[0]; y += w * m[1]; z += w * m[2]
        }
        return norme(doubleArrayOf(x, y, z))
    }

    // ---------------------------------------------------------------- the looks

    private fun bandes(vararg s: Pair<Double, Int>): IntArray = IntArray(1801) { i ->
        val la = 90 - i / 10.0
        val k = s.indexOfFirst { it.first <= la }.let { if (it <= 0) 1 else it }.coerceAtMost(s.size - 1)
        val (l0, c0) = s[k - 1]; val (l1, c1) = s[k]
        val f = if (l0 == l1) 0.0 else ((l0 - la) / (l0 - l1)).coerceIn(0.0, 1.0)
        melange(c0, c1, f)
    }
    private fun melange(a: Int, b: Int, f: Double): Int {
        fun c(s: Int) = (((a shr s) and 255) * (1 - f) + ((b shr s) and 255) * f).toInt()
        return (c(16) shl 16) or (c(8) shl 8) or c(0)
    }

    /** A look: the page's colours ([fr.f4ioz.satcombo.demo.OrbitesJs] STYLES). */
    private class Look(
        val fond: Int, val etoiles: Int, val nuit: Int, val nuitA: Double, val encre: Int?,
        val mer: IntArray, val terre: IntArray?, val terreA: Double,
        val bord: Int?, val bordP: Double, val bordF: Double, val lueur: Int?,
        val cote: Int?, val coteL: Double,
    )

    private val LOOKS = mapOf(
        "realiste" to Look(0x000004, 0xffffff, 0x000414, .55, null,
            bandes(90.0 to 0x1d3d5c, 45.0 to 0x0b3463, 0.0 to 0x0c4d80, -45.0 to 0x0b3463, -90.0 to 0x1d3d5c),
            bandes(90.0 to 0xf2f6f8, 72.0 to 0xe3ebee, 64.0 to 0x6f7f5a, 55.0 to 0x3d6331, 44.0 to 0x567436, 36.0 to 0x8f8a4f, 28.0 to 0xc8a56b,
                20.0 to 0xbf9a5e, 12.0 to 0x4f7a30, 0.0 to 0x2d6526, -12.0 to 0x3c6f2b, -24.0 to 0xb99657, -32.0 to 0xa98f58, -44.0 to 0x557a3a,
                -56.0 to 0x6b7a5e, -63.0 to 0xe9eff2, -90.0 to 0xf4f7f9), 1.0, 0x5aa8ff, 3.0, 1.1, 0x3f8cff, 0x50aac8, .2),
        "cartoon" to Look(0x0d1b4b, 0xfff2a8, 0x140a46, .35, null, bandes(90.0 to 0x35bdf5, -90.0 to 0x35bdf5),
            bandes(90.0 to 0xffffff, 70.0 to 0xffffff, 64.0 to 0x8be06a, 42.0 to 0x7bd85b, 33.0 to 0xf4d77e, 20.0 to 0xf2cf6c, 11.0 to 0x5fc94a,
                -10.0 to 0x5fc94a, -22.0 to 0xf2cf6c, -32.0 to 0x9ddc62, -58.0 to 0x8be06a, -63.0 to 0xffffff, -90.0 to 0xffffff), 1.0,
            null, 0.0, 0.0, 0x7fd8ff, 0x173f2a, .55),
        "holo" to Look(0x00060c, 0x3fe0ff, 0x000000, 0.0, null, bandes(90.0 to 0x00121c, -90.0 to 0x00121c),
            bandes(90.0 to 0x3ce6ff, -90.0 to 0x3ce6ff), .10, 0x3fe8ff, 2.2, 1.4, 0x3fe8ff, 0x7ff4ff, .3),
        "points" to Look(0x03050d, 0x8fa8ff, 0x00000a, .45, null, bandes(90.0 to 0x050915, -90.0 to 0x050915), null, 0.0,
            0x5f86ff, 2.5, 1.2, 0x3f5fff, null, 0.0),
        "radar" to Look(0x000300, 0x3cff7a, 0x000000, .35, 0x5dff8f, bandes(90.0 to 0x04260f, -90.0 to 0x04260f),
            bandes(90.0 to 0x28ff6e, -90.0 to 0x28ff6e), .07, 0x3cff7a, 2.4, 1.1, 0x2fff66, 0x5dff8f, .25),
        "vintage" to Look(0x140e08, 0xf0d9a0, 0x3c230a, .30, 0x6b3f17, bandes(90.0 to 0xdcc79a, -90.0 to 0xdcc79a),
            bandes(90.0 to 0xefe0b8, -90.0 to 0xefe0b8), 1.0, null, 0.0, 0.0, 0xc9a874, 0x5a3a1c, .4),
    )

    /** A drawing's colour in a look: as drawn on the cartoon, toned down, inked or glowing on the others. */
    private fun teinte(style: String, rgb: Int, a: Int = 255): Color {
        val r = (rgb shr 16) and 255; val g = (rgb shr 8) and 255; val b = rgb and 255
        val l = (0.3 * r + 0.59 * g + 0.11 * b) / 255
        return when (style) {
            "cartoon" -> Color(r, g, b, a)
            "realiste" -> { val m = l * 255; Color((r * .75 + m * .25).toInt(), (g * .75 + m * .25).toInt(), (b * .75 + m * .25).toInt(), (a * .9).toInt()) }
            "vintage" -> { val c = melange(0x4a2e14, 0xf3e6c4, l.pow(0.8)); Color((c shr 16) and 255, (c shr 8) and 255, c and 255, (a * .92).toInt()) }
            "holo" -> Color(127, 244, 255, (a * (.25 + .6 * l)).toInt())
            "radar" -> Color(93, 255, 143, (a * (.25 + .6 * l)).toInt())
            else -> Color(143, 176, 255, (a * (.25 + .6 * l)).toInt())
        }
    }

    // ---------------------------------------------------------------- a frame

    /**
     * The globe of the pass at [instant] in a view [w]×[h]: the Earth, the
     * track, the footprint, the satellite, the station, the contacts. Where
     * the satellite is (share of the width), for the picture and the cards.
     */
    fun dessine(g: Graphics2D, w: Float, h: Float, sc: RenduPc.Scene, instant: Long?, dp: Float): Float {
        val style = sc.styleGlobe.takeIf { it in STYLES } ?: DEFAUT
        val lk = LOOKS.getValue(style)
        val t = instant ?: sc.e.points.maxByOrNull { it.el }?.tMs ?: sc.e.finMs
        val o = sc.optionsGlobe
        val cam = camera(sc, t, w, h)
        val soleil = soleil(t)
        fond(g, w, h, lk, style, dp)
        lueurExterieure(g, cam, lk, style)
        terre(g, cam, lk, style, soleil, dp, w, h, o.detail)
        if (style == "cartoon") { g.color = Color(0x0a1230); g.stroke = BasicStroke(max(2f, cam.r * 0.018f)); g.draw(Ellipse2D.Float(cam.ox - cam.r, cam.oy - cam.r, 2 * cam.r, 2 * cam.r)) }
        return dessus(g, w, cam, sc, t, style, lk, soleil, dp)
    }

    /** The view of the frame at [t] in a view [w]×[h]: where it looks, the globe moved and scaled as the page asks. */
    fun camera(sc: RenduPc.Scene, t: Long, w: Float, h: Float): Camera {
        val o = sc.optionsGlobe
        return Camera(centre(sc, t), w / 2 + o.gdx * w, h / 2 + o.gdy * h, 0.425f * h * o.gz)
    }

    private fun Graphics2D.lisse() {
        setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    }
    private fun couleur(rgb: Int, a: Int = 255) = Color((rgb shr 16) and 255, (rgb shr 8) and 255, rgb and 255, a.coerceIn(0, 255))

    /** Space: the look's colour, its stars where they always are. */
    private fun fond(g: Graphics2D, w: Float, h: Float, lk: Look, style: String, dp: Float) {
        g.color = couleur(lk.fond); g.fill(Rectangle2D.Float(0f, 0f, w, h))
        if (style == "cartoon") g.paint = RadialGradientPaint(w / 2, h / 2, max(w, h) * 0.7f, floatArrayOf(0f, 1f), arrayOf(Color(0x1b2f7a), Color(0x0a1438)))
        if (style == "cartoon") g.fill(Rectangle2D.Float(0f, 0f, w, h))
        val r = java.util.Random(7)
        val n = (w * h / (2600 * dp * dp / 4)).toInt().coerceIn(60, 900)
        repeat(n) {
            val x = r.nextFloat() * w; val y = r.nextFloat() * h; val a = 60 + r.nextInt(170); val s = (0.5f + r.nextFloat() * r.nextFloat() * 1.6f) * dp
            g.color = couleur(lk.etoiles, a)
            if (style == "cartoon" && r.nextInt(9) == 0) {
                val k = s * 2.6f; val p = Path2D.Float()
                p.moveTo(x, y - k); p.quadTo(x, y, x + k, y); p.quadTo(x, y, x, y + k); p.quadTo(x, y, x - k, y); p.quadTo(x, y, x, y - k); g.fill(p)
            } else g.fill(Ellipse2D.Float(x - s / 2, y - s / 2, s, s))
        }
    }

    private fun lueurExterieure(g: Graphics2D, cam: Camera, lk: Look, style: String) {
        val c = lk.lueur ?: return
        val r1 = cam.r * if (style == "cartoon") 1.06f else 1.10f
        val a = when (style) { "realiste" -> 150; "holo" -> 120; "points" -> 110; "radar" -> 90; "cartoon" -> 70; else -> 80 }
        g.paint = RadialGradientPaint(cam.ox, cam.oy, r1, floatArrayOf(cam.r / r1 * 0.97f, cam.r / r1, 1f), arrayOf(couleur(c, a), couleur(c, a), couleur(c, 0)))
        g.fill(Ellipse2D.Float(cam.ox - r1, cam.oy - r1, 2 * r1, 2 * r1))
    }

    /**
     * The Earth's disc: the land mask, what lies under the land (the
     * shallows, the grid) and over it (the coast, mountains, landmarks,
     * cities), then each pixel coloured by its latitude and lit.
     */
    private fun terre(g: Graphics2D, cam: Camera, lk: Look, style: String, soleil: DoubleArray, dp: Float, vw: Float, vh: Float, detail: Int) {
        // Only what of the disc is in the view (a large globe goes beyond it).
        val x0 = max(0, floor(cam.ox - cam.r - 2).toInt()); val y0 = max(0, floor(cam.oy - cam.r - 2).toInt())
        val dw = (min(ceil(vw.toDouble()).toInt(), ceil(cam.ox + cam.r + 2.0).toInt()) - x0).coerceAtLeast(1)
        val dh = (min(ceil(vh.toDouble()).toInt(), ceil(cam.oy + cam.r + 2.0).toInt()) - y0).coerceAtLeast(1)
        val r = cam.r.toDouble()
        val remplir = Path2D.Float(Path2D.WIND_NON_ZERO); val cote = Path2D.Float()
        for (a in terres.anneaux) decoupe(a, cam, remplir, cote)
        val unDeg = (r * D2R).toFloat()

        val masque = BufferedImage(dw, dh, BufferedImage.TYPE_BYTE_GRAY)
        if (lk.terre != null) masque.createGraphics().apply { lisse(); translate(-x0.toDouble(), -y0.toDouble()); color = Color.WHITE; fill(remplir); dispose() }
        val dessous = BufferedImage(dw, dh, BufferedImage.TYPE_INT_ARGB)
        dessous.createGraphics().apply {
            lisse(); translate(-x0.toDouble(), -y0.toDouble())
            val rond = BasicStroke(1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            fun trait(c: Color, l: Float, s: Shape) { color = c; stroke = BasicStroke(l, rond.endCap, rond.lineJoin); draw(s) }
            when (style) {
                "cartoon" -> { trait(Color(170, 240, 255, 130), unDeg * 2.8f, cote); trait(Color(210, 250, 255, 200), unDeg * 1.2f, cote) }
                "realiste" -> trait(Color(80, 170, 200, 90), max(1f, unDeg * 0.5f), cote)
                "vintage" -> {
                    grille(this, cam, 15, Color(110, 70, 30, 90), max(1f, unDeg * 0.25f), floatArrayOf(unDeg * 1.8f, unDeg * 0.9f))
                    for (i in 6 downTo 1) {
                        trait(Color(110, 80, 40, (255 * (.05 + .03 * (6 - i))).toInt()), i * unDeg * 0.86f, cote)
                        trait(couleur(0xdcc79a), max(0.5f, i * unDeg * 0.86f - unDeg * 0.4f), cote)
                    }
                }
                "holo" -> { grille(this, cam, 10, Color(60, 230, 255, 56), max(0.7f, unDeg * 0.15f), null); grille(this, cam, 30, Color(60, 230, 255, 102), max(1f, unDeg * 0.25f), null) }
                "radar" -> grille(this, cam, 15, Color(60, 255, 120, 64), max(0.8f, unDeg * 0.2f), floatArrayOf(unDeg * 1.2f, unDeg * 1.2f))
            }
            dispose()
        }
        val dessus = BufferedImage(dw, dh, BufferedImage.TYPE_INT_ARGB)
        dessus.createGraphics().apply {
            lisse(); translate(-x0.toDouble(), -y0.toDouble())
            lk.cote?.let { c ->
                if (style == "holo" || style == "radar") { color = couleur(c, 70); stroke = BasicStroke(max(2f, unDeg * 0.9f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND); draw(cote) }
                color = couleur(c, if (style == "realiste") 0 else 255)
                stroke = BasicStroke(max(0.8f, unDeg * lk.coteL.toFloat() * if (style == "cartoon") 0.75f else 1f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                if (style != "realiste") draw(cote)
            }
            if (style == "points") {
                color = couleur(0x8fb0ff); val s = max(1.3f, cam.r * 0.0085f)
                for (p in points) if (cam.profondeur(p) > 0.02) fill(Ellipse2D.Float(cam.x(p) - s / 2, cam.y(p) - s / 2, s, s))
            }
            if (detail > 0) monde(this, cam, style, dp, detail)
            dispose()
        }

        val m = (masque.raster.dataBuffer as DataBufferByte).data
        val s0 = (dessous.raster.dataBuffer as DataBufferInt).data
        val s1 = (dessus.raster.dataBuffer as DataBufferInt).data
        val img = BufferedImage(dw, dh, BufferedImage.TYPE_INT_ARGB)
        val out = (img.raster.dataBuffer as DataBufferInt).data
        val e = cam.e; val n = cam.n; val c = cam.c
        val hx = norme(doubleArrayOf(soleil[0] + c[0], soleil[1] + c[1], soleil[2] + c[2]))
        val mer = lk.mer; val terreL = lk.terre
        val nr = ((lk.nuit shr 16) and 255).toDouble(); val ng = ((lk.nuit shr 8) and 255).toDouble(); val nb = (lk.nuit and 255).toDouble()
        val br = ((lk.bord ?: 0) shr 16 and 255).toDouble(); val bg = ((lk.bord ?: 0) shr 8 and 255).toDouble(); val bb = ((lk.bord ?: 0) and 255).toDouble()
        val limite = 1 + 1.0 / r
        for (j in 0 until dh) {
            val v = -((y0 + j + 0.5) - cam.oy) / r
            for (i in 0 until dw) {
                val u = ((x0 + i + 0.5) - cam.ox) / r
                val q = u * u + v * v
                if (q > limite * limite) continue
                val rr = sqrt(q)
                val bordA = ((1 - rr) * r + 0.5).coerceIn(0.0, 1.0)
                if (bordA <= 0) continue
                val wz = sqrt(max(0.0, 1 - q))
                val px = u * e[0] + v * n[0] + wz * c[0]; val py = u * e[1] + v * n[1] + wz * c[1]; val pz = u * e[2] + v * n[2] + wz * c[2]
                val la = asin(pz.coerceIn(-1.0, 1.0)) / D2R
                val li = ((90 - la) * 10).toInt().coerceIn(0, 1800)
                val k = j * dw + i
                var oc = mer[li]
                var cr = ((oc shr 16) and 255).toDouble(); var cg = ((oc shr 8) and 255).toDouble(); var cb = (oc and 255).toDouble()
                if (style == "radar") { val f = 1 - 0.75 * rr * rr; cr *= f; cg = 12 + (cg - 12) * f; cb *= f }
                var a = s0[k] ushr 24
                if (a > 0) { val f = a / 255.0; val s = s0[k]; cr += (((s shr 16) and 255) - cr) * f; cg += (((s shr 8) and 255) - cg) * f; cb += ((s and 255) - cb) * f }
                val terreF = if (terreL == null) 0.0 else (m[k].toInt() and 255) / 255.0
                if (terreF > 0) {
                    oc = terreL!![li]; val f = terreF * lk.terreA
                    cr += (((oc shr 16) and 255) - cr) * f; cg += (((oc shr 8) and 255) - cg) * f; cb += ((oc and 255) - cb) * f
                }
                // The relief: lit or shaded slopes, the high ground browner, the highest summits white.
                val rel = relief
                if (rel != null && terreF > 0 && (style == "cartoon" || style == "realiste" || style == "vintage")) {
                    val ci = cellule(la, atan2(py, px) / D2R); val alt = rel.altitude[ci]; val om = rel.ombre[ci] * terreF
                    if (style == "cartoon" && alt > 600) {
                        // Clearly seen: the high ground brown, the summits white (the page's numbers).
                        val b = ((alt - 600) / 2400.0).coerceIn(0.0, 1.0) * 0.75 * terreF
                        cr += (186 - cr) * b; cg += (128 - cg) * b; cb += (72 - cb) * b
                        val n = ((alt - 3500) / 1200.0).coerceIn(0.0, 1.0) * 0.9 * terreF
                        cr += (255 - cr) * n; cg += (255 - cg) * n; cb += (255 - cb) * n
                    } else if (style == "realiste" && alt > 800) {
                        val b = ((alt - 800) / 2600.0).coerceIn(0.0, 1.0) * 0.55 * terreF
                        cr += (140 - cr) * b; cg += (112 - cg) * b; cb += (82 - cb) * b
                        val n = ((alt - 3800) / 1200.0).coerceIn(0.0, 1.0) * 0.8 * terreF
                        cr += (250 - cr) * n; cg += (252 - cg) * n; cb += (255 - cb) * n
                    }
                    val fo = 1 + om * (if (style == "cartoon") 0.45 else 0.35)
                    cr *= fo; cg *= fo; cb *= fo
                }
                if (style == "realiste" && la > 73.8) { val f = .95 * ((la - 73.8) / 9).coerceAtMost(1.0); cr += (240 - cr) * f; cg += (246 - cg) * f; cb += (250 - cb) * f }
                a = s1[k] ushr 24
                if (a > 0) { val f = a / 255.0; val s = s1[k]; cr += (((s shr 16) and 255) - cr) * f; cg += (((s shr 8) and 255) - cg) * f; cb += ((s and 255) - cb) * f }
                if (style == "realiste" || style == "vintage") {
                    val lo = atan2(py, px) / D2R
                    val gr = if (style == "realiste") 0.18 * 0.5 else 0.25 * 0.6
                    val b = (bruit(floor(lo * 5).toInt(), floor(la * 5).toInt()) - 0.5) * 2 * gr * 60
                    cr += b; cg += b; cb += b
                }
                if (style == "holo" && ((90 - la) / 0.703) % 1.0 < 0.25) { cr *= .82; cg *= .82; cb *= .82 }
                // The light.
                val ds = px * soleil[0] + py * soleil[1] + pz * soleil[2]
                val jour = lisse(-0.06, 0.06, ds)
                var f = 1.0
                when (style) {
                    "realiste" -> {
                        f = (0.36 + 0.78 * max(0.0, ds)).coerceAtMost(1.06)
                        val sp = max(0.0, px * hx[0] + py * hx[1] + pz * hx[2]).pow(18) * 0.35 * (1 - terreF) * jour
                        cr += 160 * sp; cg += 180 * sp; cb += 200 * sp
                    }
                    "cartoon" -> f = 0.72 + 0.14 * lisse(-0.035, -0.015, ds) + 0.14 * lisse(0.30, 0.32, ds)
                    "vintage" -> f = 0.86 + 0.18 * max(0.0, ds)
                }
                cr *= f; cg *= f; cb *= f
                val nuit = lk.nuitA * (1 - jour)
                if (nuit > 0) { cr += (nr - cr) * nuit; cg += (ng - cg) * nuit; cb += (nb - cb) * nuit }
                if (lk.bord != null) { val b = (1 - wz).pow(lk.bordP) * lk.bordF; cr += br * b; cg += bg * b; cb += bb * b }
                if (style == "vintage") { val b = .35 * ((rr - 0.55) / 0.45).coerceIn(0.0, 1.0).pow(1.5); cr += (90 - cr) * b; cg += (55 - cg) * b; cb += (20 - cb) * b }
                val ir = cr.toInt().coerceIn(0, 255); val ig = cg.toInt().coerceIn(0, 255); val ib = cb.toInt().coerceIn(0, 255)
                out[k] = ((bordA * 255).toInt() shl 24) or (ir shl 16) or (ig shl 8) or ib
            }
        }
        g.drawImage(img, x0, y0, null)
        // The cities' lights on the night side (the photographs' look).
        if (style == "realiste") for (v in GlobeDonnees.VILLES) {
            val p = vec(v.lat, v.lon); val z = cam.profondeur(p); if (z < 0.05) continue
            val nuit = 1 - lisse(-0.1, 0.0, dot(p, soleil)); if (nuit <= 0.05) continue
            val s = (if (v.rang == 1) 9f else 6f) * dp * 0.5f
            g.paint = RadialGradientPaint(cam.x(p), cam.y(p), s, floatArrayOf(0f, 1f), arrayOf(Color(255, 214, 120, (200 * nuit).toInt()), Color(255, 200, 90, 0)))
            g.fill(Ellipse2D.Float(cam.x(p) - s, cam.y(p) - s, 2 * s, 2 * s))
        }
    }

    private fun lisse(a: Double, b: Double, x: Double): Double { val k = ((x - a) / (b - a)).coerceIn(0.0, 1.0); return k * k * (3 - 2 * k) }
    private fun bruit(a: Int, b: Int): Double {
        var h = a * 374761393 + b * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        return ((h xor (h ushr 16)) and 0xffff) / 65535.0
    }

    /** Lines of the surface, cut where they go behind: a path of the visible runs. */
    private fun chemin(cam: Camera, pts: List<DoubleArray>, p: Path2D.Float = Path2D.Float()): Path2D.Float {
        var leve = true
        for (q in pts) {
            if (cam.profondeur(q) < 0) { leve = true; continue }
            if (leve) { p.moveTo(cam.x(q), cam.y(q)); leve = false } else p.lineTo(cam.x(q), cam.y(q))
        }
        return p
    }

    private fun grille(g: Graphics2D, cam: Camera, pas: Int, c: Color, l: Float, tirets: FloatArray?) {
        val p = Path2D.Float()
        var lo = -180
        while (lo < 180) { chemin(cam, (-90..90 step 2).map { vec(it.toDouble(), lo.toDouble()) }, p); lo += pas }
        var la = -90 + pas
        while (la < 90) { chemin(cam, (-180..180 step 2).map { vec(la.toDouble(), it.toDouble()) }, p); la += pas }
        g.color = c; g.stroke = if (tirets != null) BasicStroke(l, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, tirets, 0f) else BasicStroke(l)
        g.draw(p)
    }

    // ---------------------------------------------------------------- the world's drawings

    /** One drawing standing at a point of the surface: [dessin] in a box 100 high (its foot at 0, its top at −100), [haut] px, squeezed toward the limb. */
    private fun debout(g: Graphics2D, cam: Camera, lat: Double, lon: Double, haut: Float, dessin: (Pinceau) -> Unit, style: String) {
        val p = vec(lat, lon); val z = cam.profondeur(p)
        if (z < 0.06) return
        val x = cam.x(p); val y = cam.y(p)
        val phi = atan2((y - cam.oy).toDouble(), (x - cam.ox).toDouble())
        val k = haut / 100f * (0.55f + 0.45f * z.toFloat())
        val sauve = g.transform; val compo = g.composite
        g.translate(x.toDouble(), y.toDouble())
        g.rotate(phi); g.scale(0.4 + 0.6 * z, 1.0); g.rotate(-phi)
        g.scale(k.toDouble(), k.toDouble())
        if (z < 0.2) g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, ((z - 0.06) / 0.14).toFloat().coerceIn(0f, 1f))
        dessin(Pinceau(g, style, 1.1f / k))
        g.transform = sauve; g.composite = compo
    }

    /** The drawing's brush: the look's colours, the cartoon's dark outline [unite] px wide. */
    private class Pinceau(val g: Graphics2D, val style: String, val unite: Float) {
        val trait = style == "cartoon"
        fun c(rgb: Int, a: Int = 255) = teinte(style, rgb, a)
        fun f(rgb: Int, s: Shape, contour: Boolean = true) {
            g.color = c(rgb); g.fill(s)
            if (contour && trait) { g.color = Color(0x10, 0x18, 0x34, 220); g.stroke = BasicStroke(unite, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND); g.draw(s) }
            else if (contour && style != "realiste") { g.color = c(0x101010, 140); g.stroke = BasicStroke(unite * 0.7f); g.draw(s) }
        }
        fun l(rgb: Int, largeur: Float, s: Shape) { g.color = c(rgb); g.stroke = BasicStroke(largeur, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND); g.draw(s) }
    }
    private fun poly(vararg xy: Float) = Path2D.Float().apply { moveTo(xy[0], xy[1]); var i = 2; while (i < xy.size) { lineTo(xy[i], xy[i + 1]); i += 2 }; closePath() }
    private fun rond(x: Float, y: Float, r: Float) = Ellipse2D.Float(x - r, y - r, 2 * r, 2 * r)
    private fun rect(x: Float, y: Float, w: Float, h: Float) = Rectangle2D.Float(x, y, w, h)

    /** A mountain: its slope lit on the left, snow on the high ones. */
    private fun montagne(p: Pinceau, haut: Double) {
        p.f(0x9c7a56, poly(-62f, 0f, 0f, -100f, 62f, 0f))
        p.f(0xb8946a, poly(-62f, 0f, 0f, -100f, -8f, 0f), contour = false)
        if (haut >= 0.45) p.f(0xffffff, poly(-22f, -64f, 0f, -100f, 22f, -64f, 12f, -58f, 4f, -66f, -6f, -56f, -14f, -64f))
    }

    private fun monument(p: Pinceau, id: String) {
        when (id) {
            "tour_eiffel" -> {
                val a = Area(poly(-34f, 0f, -14f, -42f, -6f, -76f, -2f, -100f, 2f, -100f, 6f, -76f, 14f, -42f, 34f, 0f))
                a.subtract(Area(Path2D.Float().apply { moveTo(-20f, 0f); quadTo(0f, -40f, 20f, 0f); closePath() }))
                p.f(0x8a6a48, a); p.f(0x6e5236, rect(-17f, -44f, 34f, 5f)); p.f(0x6e5236, rect(-9f, -76f, 18f, 4f))
            }
            "liberte" -> {
                p.f(0x9a8f80, poly(-18f, 0f, -14f, -30f, 14f, -30f, 18f, 0f))
                p.f(0x5fae9a, poly(-11f, -30f, -7f, -72f, 7f, -72f, 11f, -30f))
                p.f(0x5fae9a, rond(0f, -78f, 7f))
                p.l(0x5fae9a, 6f, Line2D.Float(5f, -70f, 14f, -96f))
                p.f(0xffb52e, poly(10f, -96f, 14f, -108f, 18f, -96f))
                p.l(0x5fae9a, 2.5f, Line2D.Float(-6f, -84f, -10f, -90f)); p.l(0x5fae9a, 2.5f, Line2D.Float(0f, -86f, 0f, -93f)); p.l(0x5fae9a, 2.5f, Line2D.Float(6f, -84f, 10f, -90f))
            }
            "pyramides" -> {
                for ((dx, s) in listOf(-30f to 0.6f, 26f to 0.7f, 0f to 1f)) {
                    p.f(0xe8c170, poly(dx - 42f * s, 0f, dx, -90f * s, dx + 42f * s, 0f))
                    p.f(0xc9a052, poly(dx, -90f * s, dx + 42f * s, 0f, dx + 6f * s, 0f), contour = false)
                }
            }
            "big_ben" -> {
                p.f(0xc9a46a, rect(-12f, -78f, 24f, 78f)); p.f(0x55606a, poly(-14f, -78f, 0f, -104f, 14f, -78f))
                p.f(0xffffff, rond(0f, -62f, 9f)); p.l(0x222222, 2f, Line2D.Float(0f, -62f, 0f, -68f)); p.l(0x222222, 2f, Line2D.Float(0f, -62f, 5f, -62f))
                for (k in 0..3) p.f(0x9b7a46, rect(-8f, -44f + k * 11f, 16f, 4f), contour = false)
            }
            "taj_mahal" -> {
                p.f(0xf4f1ea, rect(-34f, -30f, 68f, 30f))
                p.f(0xf4f1ea, Path2D.Float().apply { moveTo(-20f, -30f); curveTo(-30f, -60f, -6f, -66f, 0f, -84f); curveTo(6f, -66f, 30f, -60f, 20f, -30f); closePath() })
                p.f(0xe0d9c8, rect(-44f, -66f, 6f, 66f)); p.f(0xe0d9c8, rect(38f, -66f, 6f, 66f))
                p.f(0x9aa7c9, Path2D.Float().apply { moveTo(-8f, 0f); lineTo(-8f, -14f); quadTo(0f, -24f, 8f, -14f); lineTo(8f, 0f); closePath() }, contour = false)
            }
            "opera_sydney" -> {
                p.f(0xd9c7a8, rect(-46f, -10f, 92f, 10f))
                for ((dx, s) in listOf(-26f to 0.7f, -6f to 1f, 16f to 0.85f, 34f to 0.6f))
                    p.f(0xffffff, Path2D.Float().apply { moveTo(dx - 16f * s, -10f); quadTo(dx - 6f * s, -70f * s, dx + 16f * s, -86f * s); quadTo(dx + 8f * s, -40f * s, dx + 14f * s, -10f); closePath() })
            }
            "christ" -> {
                p.f(0x6fbf5a, Path2D.Float().apply { moveTo(-40f, 0f); quadTo(0f, -40f, 40f, 0f); closePath() })
                p.f(0xeeeeee, rect(-6f, -84f, 12f, 64f)); p.f(0xeeeeee, rect(-36f, -80f, 72f, 9f)); p.f(0xeeeeee, rond(0f, -92f, 7f))
            }
            "fuji" -> {
                p.f(0x6b7fae, poly(-60f, 0f, -14f, -90f, 14f, -90f, 60f, 0f))
                p.f(0xffffff, poly(-26f, -66f, -14f, -90f, 14f, -90f, 26f, -66f, 16f, -60f, 8f, -70f, 0f, -60f, -8f, -70f, -16f, -60f))
            }
            "kremlin" -> {
                p.f(0xc0392b, Path2D.Float().apply { moveTo(-46f, 0f); lineTo(-46f, -30f); var x = -46f; while (x < 46f) { lineTo(x + 4f, -30f); lineTo(x + 4f, -36f); lineTo(x + 8f, -36f); lineTo(x + 8f, -30f); x += 8f }; lineTo(46f, 0f); closePath() })
                for ((dx, c) in listOf(-22f to 0x2e9e5b, 0f to 0xf2b632, 22f to 0x3a6fd8)) {
                    p.f(0xe9e3d6, rect(dx - 7f, -62f, 14f, 32f))
                    p.f(c, Path2D.Float().apply { moveTo(dx - 10f, -62f); curveTo(dx - 14f, -76f, dx - 2f, -78f, dx, -90f); curveTo(dx + 2f, -78f, dx + 14f, -76f, dx + 10f, -62f); closePath() })
                }
            }
            "colisee" -> {
                p.f(0xd8b98a, Path2D.Float().apply { moveTo(-46f, 0f); lineTo(-46f, -46f); quadTo(0f, -66f, 46f, -54f); lineTo(46f, 0f); closePath() })
                for (row in 0..2) for (k in 0..6) p.f(0x7a5b36, rect(-40f + k * 12.5f, -40f + row * 13f - (k * 1.2f), 6f, 8f), contour = false)
            }
            "golden_gate" -> {
                p.l(0xd0452c, 5f, Line2D.Float(-56f, -20f, 56f, -20f))
                for (dx in listOf(-28f, 28f)) { p.f(0xd0452c, rect(dx - 5f, -88f, 10f, 88f)); p.f(0xa83420, rect(dx - 6f, -70f, 12f, 4f), contour = false) }
                p.l(0xd0452c, 2.5f, Path2D.Float().apply { moveTo(-56f, -40f); quadTo(-42f, -50f, -28f, -86f); quadTo(0f, -24f, 28f, -86f); quadTo(42f, -50f, 56f, -40f) })
            }
            "moulin" -> {
                p.f(0x8a5a3c, poly(-16f, 0f, -10f, -60f, 10f, -60f, 16f, 0f)); p.f(0x5a3a26, poly(-14f, -60f, 0f, -74f, 14f, -60f))
                for (k in 0..3) {
                    val a = PI / 4 + k * PI / 2; val c = cos(a).toFloat(); val s = sin(a).toFloat()
                    p.f(0xf2ead8, poly(c * 6f - s * 5f, -66f + s * 6f + c * 5f, c * 50f - s * 7f, -66f + s * 50f + c * 7f, c * 50f + s * 7f, -66f + s * 50f - c * 7f, c * 6f + s * 5f, -66f + s * 6f - c * 5f))
                }
                p.f(0x333333, rond(0f, -66f, 4f))
            }
            "muraille" -> {
                p.f(0x7cbf5c, Path2D.Float().apply { moveTo(-56f, 0f); quadTo(-30f, -44f, 0f, -20f); quadTo(30f, -2f, 56f, -40f); lineTo(56f, 0f); closePath() }, contour = false)
                p.l(0xb08a5e, 9f, Path2D.Float().apply { moveTo(-52f, -10f); quadTo(-30f, -40f, 0f, -22f); quadTo(30f, -6f, 52f, -38f) })
                for (dx in listOf(-30f, 22f)) p.f(0xb08a5e, rect(dx - 6f, (if (dx < 0) -46f else -34f), 12f, 16f))
            }
            "moai" -> {
                p.f(0x7b7468, Path2D.Float().apply { moveTo(-16f, 0f); lineTo(-18f, -70f); quadTo(-16f, -96f, 4f, -94f); lineTo(14f, -80f); lineTo(10f, -64f); lineTo(16f, -54f); lineTo(10f, -40f); lineTo(16f, 0f); closePath() })
                p.f(0x3b362e, rect(-10f, -74f, 14f, 5f), contour = false)
            }
            "fusee" -> {
                p.f(0xff9f1c, poly(-7f, -12f, 0f, 8f, 7f, -12f), contour = false)
                p.f(0xd33b2c, poly(-9f, -14f, -20f, 0f, -20f, -10f, -9f, -30f)); p.f(0xd33b2c, poly(9f, -14f, 20f, 0f, 20f, -10f, 9f, -30f))
                p.f(0xf4f4f4, Path2D.Float().apply { moveTo(-9f, -12f); lineTo(-9f, -74f); quadTo(0f, -104f, 9f, -74f); lineTo(9f, -12f); closePath() })
                p.f(0xd33b2c, Path2D.Float().apply { moveTo(-9f, -74f); quadTo(0f, -104f, 9f, -74f); closePath() })
                p.f(0x4aa3ff, rond(0f, -56f, 4.5f))
            }
            "phare" -> {
                p.f(0xffffff, poly(-14f, 0f, -9f, -74f, 9f, -74f, 14f, 0f))
                for (k in 0..2) p.f(0xd33b2c, poly(-13.6f + k * 1.6f, -10f - k * 24f, -12.6f + k * 1.6f, -22f - k * 24f, 12.6f - k * 1.6f, -22f - k * 24f, 13.6f - k * 1.6f, -10f - k * 24f), contour = false)
                p.f(0x333a44, rect(-10f, -90f, 20f, 16f)); p.f(0xffe066, rect(-6f, -88f, 12f, 11f), contour = false)
                p.f(0x333a44, poly(-12f, -90f, 0f, -100f, 12f, -90f))
            }
        }
    }

    private fun decor(p: Pinceau, type: String) {
        when (type) {
            "bateau" -> {
                p.f(0xc0392b, Path2D.Float().apply { moveTo(-40f, -24f); lineTo(40f, -24f); lineTo(28f, 0f); lineTo(-30f, 0f); closePath() })
                p.l(0x5a3a26, 4f, Line2D.Float(0f, -24f, 0f, -96f))
                p.f(0xffffff, poly(4f, -92f, 4f, -30f, 40f, -30f)); p.f(0xf2ead8, poly(-4f, -84f, -4f, -30f, -30f, -30f))
            }
            "baleine" -> {
                p.f(0x3f6fb5, Path2D.Float().apply { moveTo(-44f, -10f); quadTo(-40f, -50f, 6f, -46f); quadTo(36f, -42f, 40f, -22f); lineTo(56f, -36f); lineTo(52f, -14f); lineTo(60f, 0f); lineTo(38f, -10f); quadTo(0f, 4f, -44f, -10f); closePath() })
                p.f(0xdde9f5, Path2D.Float().apply { moveTo(-40f, -12f); quadTo(-6f, 0f, 24f, -10f); quadTo(-8f, -16f, -40f, -12f); closePath() }, contour = false)
                p.f(0x10203a, rond(-26f, -28f, 3f), contour = false)
                p.l(0xbfe9ff, 4f, Path2D.Float().apply { moveTo(-20f, -48f); quadTo(-24f, -70f, -36f, -78f); moveTo(-20f, -48f); quadTo(-16f, -70f, -4f, -78f) })
            }
            "foret" -> {
                for ((dx, s) in listOf(-26f to 0.75f, 24f to 0.8f, 0f to 1f)) {
                    p.f(0x6b4a2e, rect(dx - 4f * s, -14f * s, 8f * s, 14f * s), contour = false)
                    p.f(0x2f8f3e, poly(dx - 22f * s, -14f * s, dx, -60f * s, dx + 22f * s, -14f * s))
                    p.f(0x3aa84a, poly(dx - 16f * s, -50f * s, dx, -96f * s, dx + 16f * s, -50f * s))
                }
            }
            "manchot" -> {
                p.f(0x1d2230, Path2D.Float().apply { moveTo(0f, -96f); curveTo(-30f, -90f, -30f, -10f, -14f, 0f); lineTo(14f, 0f); curveTo(30f, -10f, 30f, -90f, 0f, -96f); closePath() })
                p.f(0xffffff, Path2D.Float().apply { moveTo(0f, -70f); curveTo(-18f, -60f, -18f, -10f, -8f, -4f); lineTo(8f, -4f); curveTo(18f, -10f, 18f, -60f, 0f, -70f); closePath() }, contour = false)
                p.f(0xff9f1c, poly(-4f, -80f, 0f, -72f, 4f, -80f), contour = false)
                p.f(0xffffff, rond(-6f, -84f, 3f), contour = false); p.f(0xffffff, rond(6f, -84f, 3f), contour = false)
                p.f(0xff9f1c, Ellipse2D.Float(-16f, -4f, 12f, 6f), contour = false); p.f(0xff9f1c, Ellipse2D.Float(4f, -4f, 12f, 6f), contour = false)
            }
        }
    }

    /** A summit's size on the globe, 0.25 (1 000 m) to 1 (5 000 m and over): the same scale as the page's. */
    fun hauteurSommet(altitude: Int): Double = ((altitude - 900) / 4100.0).coerceIn(0.25, 1.0)

    /**
     * The relief (ETOPO1, NOAA: `relief.png` beside the page, 1440 × 720 cells of 0.25°, north at the top, sea at 0):
     * the land's height in metres, and its shading lit from the north-west (−1 in the shade … 1 in the light),
     * worked out as the page does.
     */
    private class Relief(val altitude: FloatArray, val ombre: FloatArray)
    private val relief: Relief? by lazy {
        runCatching {
            val img = javax.imageio.ImageIO.read(GlobeDonnees::class.java.getResourceAsStream("relief.png")!!)
            val w = 1440; val h = 720
            val a = FloatArray(w * h) { i -> val v = img.raster.getSample(i % w, i / w, 0); if (v >= 128) (v - 128) * 40f else 0f }
            val o = FloatArray(w * h)
            for (i in 1 until h - 1) {
                val la = 90 - i * 0.25; val dx = 0.25 * 111320 * max(0.05, cos(la * D2R)); val dy = 0.25 * 111320
                for (j in 0 until w) {
                    if (a[i * w + j] <= 0f) continue
                    val gx = (a[i * w + (j + 1) % w] - a[i * w + (j + w - 1) % w]) / (2 * dx) * EXAGERATION
                    val gy = (a[(i - 1) * w + j] - a[(i + 1) * w + j]) / (2 * dy) * EXAGERATION
                    val nz = 1 / sqrt(gx * gx + gy * gy + 1)
                    val hs = (-gx * -0.5 + -gy * 0.5 + 0.7071) * nz
                    o[i * w + j] = ((hs - 0.7071) / 0.35).coerceIn(-1.0, 1.0).toFloat()
                }
            }
            Relief(a, o)
        }.getOrNull()
    }
    private const val EXAGERATION = 12.0
    private fun cellule(la: Double, lo: Double): Int {
        val i = ((90 - la) / 0.25).toInt().coerceIn(0, 719); val j = (((lo + 180) / 0.25).toInt() % 1440 + 1440) % 1440
        return i * 1440 + j
    }

    /** The world drawn on the surface, back to front: mountains, scenery, landmarks, cities' dots (the cartoon's all of it, the others' lighter). */
    private fun monde(g: Graphics2D, cam: Camera, style: String, dp: Float, detail: Int) {
        val cartoon = style == "cartoon"
        val k = cam.r * (if (cartoon) 1f else 0.75f)
        val dessins = ArrayList<Pair<Float, () -> Unit>>()
        fun ajoute(lat: Double, lon: Double, haut: Float, d: (Pinceau) -> Unit) {
            val p = vec(lat, lon); if (cam.profondeur(p) < 0.06) return
            dessins += cam.y(p) to { debout(g, cam, lat, lon, haut, d, style) }
        }
        // The summits of the relief, sized by their height.
        for (s in GlobeDonnees.SOMMETS) {
            val h = hauteurSommet(s.altitude)
            ajoute(s.lat, s.lon, k * (0.018f + 0.04f * h.toFloat())) { montagne(it, h) }
        }
        if (style != "holo" && style != "radar" && style != "points") for (d in GlobeDonnees.DECOR.filter { detail >= 2 || it.type == "bateau" })
            ajoute(d.lat, d.lon, k * if (d.type == "foret") 0.06f else 0.048f) { decor(it, d.type) }
        for (m in GlobeDonnees.MONUMENTS) ajoute(m.lat, m.lon, k * 0.095f) { monument(it, m.id) }
        dessins.sortBy { it.first }
        dessins.forEach { it.second() }
    }

    // ---------------------------------------------------------------- over the globe

    private fun texteCerne(g: Graphics2D, s: String, x: Float, y: Float, c: Color, cerne: Color?, f: Font, l: Float) {
        g.font = f
        if (cerne != null) {
            val o = f.createGlyphVector(g.fontRenderContext, s).getOutline(x, y)
            g.color = cerne; g.stroke = BasicStroke(l, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND); g.draw(o)
        }
        g.color = c; g.drawString(s, x, y)
    }

    /** A name in its look's label, as on the page: the cartoon's white bubble, the old map's parchment, the others' dark box. Its box. */
    private fun etiquette(g: Graphics2D, s: String, x: Float, y: Float, c: Color, style: String, dp: Float, aDroite: Boolean = false, dessine: Boolean = true): Rectangle2D.Float {
        val f = if (style == "vintage") Font(Font.SERIF, Font.BOLD or Font.ITALIC, 1).deriveFont(12 * dp) else Font(Font.SANS_SERIF, Font.BOLD, 1).deriveFont(11.5f * dp)
        g.font = f
        val l = g.fontMetrics.stringWidth(s) + 12 * dp; val h = 18 * dp
        val bx = if (aDroite) x - l else x; val by = y - h / 2
        if (!dessine) return Rectangle2D.Float(bx, by, l, h)
        val boite = RoundRectangle2D.Float(bx, by, l, h, if (style == "cartoon") h else 4 * dp, if (style == "cartoon") h else 4 * dp)
        when (style) {
            "cartoon" -> { g.color = Color.WHITE; g.fill(boite); g.color = Color(0x0a1230); g.stroke = BasicStroke(1.6f * dp); g.draw(boite); g.color = Color(0x0a1230) }
            "vintage" -> { g.color = Color(239, 224, 184, 235); g.fill(boite); g.color = Color(0x6b3f17); g.stroke = BasicStroke(0.8f * dp); g.draw(boite); g.color = Color(0x4a2e14) }
            else -> { g.color = Color(0, 0, 0, 150); g.fill(boite); g.color = c }
        }
        g.drawString(s, bx + 6 * dp, by + h / 2 + g.fontMetrics.ascent * 0.36f)
        return Rectangle2D.Float(bx, by, l, h)
    }

    private fun dessus(g: Graphics2D, w: Float, cam: Camera, sc: RenduPc.Scene, t: Long, style: String, lk: Look, soleil: DoubleArray, dp: Float): Float {
        val boites = ArrayList<Rectangle2D.Float>()
        fun libre(b: Rectangle2D.Float) = boites.none { it.intersects(b) }
        val sous = sousSatellite(sc.sol, t)
        val qth = sc.qth?.let { vec(it.first, it.second) }
        val encre = lk.encre?.let { couleur(it) }
        val coulTrace = encre ?: RenduPc.CYAN
        val coulSat = RenduPc.ISS
        // Places kept for the station's and the satellite's names first.
        val satP = sous?.let { (s, alt) -> val k = 1 + alt / RE; doubleArrayOf(s[0] * k, s[1] * k, s[2] * k) }
        val satVu = satP != null && cam.voit(satP)

        // The cities' names where they leave room for the station's, the satellite's and the contacts'.
        val reserve = ArrayList<Rectangle2D.Float>()
        if (qth != null && cam.profondeur(qth) > 0.02) {
            reserve += Rectangle2D.Float(cam.x(qth) - 10 * dp, cam.y(qth) - 24 * dp, 20 * dp, 26 * dp)
            sc.etiquetteQth?.takeIf { it.isNotBlank() }?.let { reserve += etiquette(g, it, cam.x(qth) - 10 * dp, cam.y(qth) - 14 * dp, STATION, style, dp, aDroite = true, dessine = false) }
        }
        if (satP != null && satVu) {
            reserve += Rectangle2D.Float(cam.x(satP) - 16 * dp, cam.y(satP) - 16 * dp, 32 * dp, 32 * dp)
            reserve += etiquette(g, sc.e.satName, cam.x(satP) + 16 * dp, cam.y(satP) - 14 * dp, coulSat, style, dp, dessine = false)
        }
        for (m in sc.marques) if (m.type != TypeMarque.SSTV && m.debutMs <= t) {
            val la = m.lat; val lo = m.lon
            val p = if (la != null && lo != null) vec(la, lo) else sousSatellite(sc.sol, m.debutMs)?.first ?: continue
            if (cam.profondeur(p) > 0.02) reserve += etiquette(g, m.texte, cam.x(p) - 6 * dp, cam.y(p), coulSat, style, dp, dessine = false)
        }
        when (sc.optionsGlobe.villes) {
            "capitales" -> capitales(g, cam, style, dp, ArrayList(reserve), sc.optionsGlobe)
            "aucune" -> {}
            else -> villes(g, cam, style, dp, ArrayList(reserve))
        }

        // The footprint.
        if (sous != null) {
            val (s, alt) = sous
            val rho = acos(RE / (RE + alt.coerceAtLeast(1.0)))
            val e = norme(doubleArrayOf(-s[1], s[0], 0.0)).let { if (abs(s[2]) > 0.9999) doubleArrayOf(0.0, 1.0, 0.0) else it }
            val nn = doubleArrayOf(s[1] * e[2] - s[2] * e[1], s[2] * e[0] - s[0] * e[2], s[0] * e[1] - s[1] * e[0])
            // Built straight on the sphere, counter-clockwise seen from outside (east, then north).
            val p = DoubleArray(360)
            for (i in 0 until 120) { val f = 2 * PI * i / 120; for (k in 0..2) p[3 * i + k] = cos(rho) * s[k] + sin(rho) * (cos(f) * e[k] + sin(f) * nn[k]) }
            val an = Anneau(p, BooleanArray(120), s, rho)
            val fill = Path2D.Float(Path2D.WIND_NON_ZERO); val bord = Path2D.Float()
            decoupe(an, cam, fill, bord)
            val c = encre ?: coulSat
            g.color = Color(c.red, c.green, c.blue, if (style == "cartoon") 60 else 40); g.fill(fill)
            g.color = Color(c.red, c.green, c.blue, 210); g.stroke = BasicStroke(1.6f * dp); g.draw(bord)
        }

        // The ground track: gone solid, to come dashed.
        if (sc.sol.size >= 2) {
            fun pts(de: Long, a: Long): List<DoubleArray> {
                if (a <= de) return emptyList()
                val l = ArrayList<DoubleArray>()
                var x = de
                while (x < a) { sousSatellite(sc.sol, x)?.let { l += it.first }; x += 4_000L }
                sousSatellite(sc.sol, a)?.let { l += it.first }
                return l
            }
            val de = sc.sol.first().tMs; val a = sc.sol.last().tMs
            val tc = t.coerceIn(de, a)
            val ombre = BasicStroke(6f * dp, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            val venir = chemin(cam, pts(tc, a)); val passe = chemin(cam, pts(de, tc))
            g.color = Color(0, 0, 0, if (style == "cartoon") 90 else 50); g.stroke = ombre; g.draw(passe)
            g.color = Color(coulTrace.red, coulTrace.green, coulTrace.blue, 200)
            g.stroke = BasicStroke(2.6f * dp, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, floatArrayOf(7 * dp, 6 * dp), 0f); g.draw(venir)
            g.color = coulTrace; g.stroke = BasicStroke(3.6f * dp, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND); g.draw(passe)
            // Each SSTV picture as its stretch of the track.
            for (m in sc.marques.filter { it.type == TypeMarque.SSTV }) {
                if (m.debutMs > t) continue
                g.color = RenduPc.SSTV; g.stroke = BasicStroke(7f * dp, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.draw(chemin(cam, pts(m.debutMs, minOf(m.finMs, t))))
            }
        }

        // The line from the station to the satellite, while it is up.
        if (qth != null && satP != null && satVu && cam.profondeur(qth) > 0) {
            val haut = (satP[0] - qth[0]) * qth[0] + (satP[1] - qth[1]) * qth[1] + (satP[2] - qth[2]) * qth[2]
            if (haut > 0) {
                g.color = Color(STATION.red, STATION.green, STATION.blue, 225); g.stroke = BasicStroke(2f * dp, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.draw(Line2D.Float(cam.x(qth), cam.y(qth), cam.x(satP), cam.y(satP)))
            }
        }

        // The contacts, the frames heard: where they were (when known) and their callsign, from their moment.
        val gris = Color(122, 130, 142)
        for (m in sc.marques.filter { it.type != TypeMarque.SSTV }) {
            if (m.debutMs > t) continue
            val sm = sousSatellite(sc.sol, m.debutMs)?.first
            val mLat = m.lat; val mLon = m.lon
            val ici = if (mLat != null && mLon != null) vec(mLat, mLon) else sm ?: continue
            if (cam.profondeur(ici) < 0.02) continue
            val x = cam.x(ici); val y = cam.y(ici)
            if (mLat != null && mLon != null && sm != null && cam.profondeur(sm) > 0) {
                g.color = Color(255, 255, 255, 150); g.stroke = BasicStroke(1.2f * dp, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, floatArrayOf(3 * dp, 3 * dp), 0f)
                g.draw(Line2D.Float(x, y, cam.x(sm), cam.y(sm)))
            }
            val c = RenduPc.couleur(m)
            g.color = Color.WHITE; g.fill(rond(x, y, 6f * dp)); g.color = c; g.fill(rond(x, y, 4.5f * dp))
            var yy = y; var b: Rectangle2D.Float
            var essais = 0
            do {
                b = Rectangle2D.Float(x + 8 * dp, yy - 9 * dp, (m.texte.length * 7.5f + 12) * dp, 18 * dp)
                if (libre(b)) break; yy += 19 * dp
            } while (++essais < 5)
            boites += etiquette(g, m.texte, x + 8 * dp, yy, c, style, dp)
        }

        // The station.
        if (qth != null && cam.profondeur(qth) > 0.02) {
            val x = cam.x(qth); val y = cam.y(qth)
            g.color = Color(STATION.red, STATION.green, STATION.blue, 90); g.fill(rond(x, y, 10f * dp))
            val pin = Path2D.Float().apply { moveTo(x, y); curveTo(x - 9 * dp, y - 12 * dp, x - 7 * dp, y - 22 * dp, x, y - 22 * dp); curveTo(x + 7 * dp, y - 22 * dp, x + 9 * dp, y - 12 * dp, x, y); closePath() }
            g.color = STATION; g.fill(pin); g.color = Color(0x0a1230); g.stroke = BasicStroke(1.4f * dp); g.draw(pin)
            g.color = Color.WHITE; g.fill(rond(x, y - 15 * dp, 3f * dp))
            sc.etiquetteQth?.takeIf { it.isNotBlank() }?.let { boites += etiquette(g, it, x - 10 * dp, y - 14 * dp, STATION, style, dp, aDroite = true) }
        }

        // The satellite at its height, over the point under it.
        var satX = 0.5f
        if (sous != null && satP != null) {
            val s = sous.first
            val x = cam.x(satP); val y = cam.y(satP)
            satX = x / w
            if (cam.profondeur(s) > 0) {
                val sx = cam.x(s); val sy = cam.y(s)
                g.color = Color(coulSat.red, coulSat.green, coulSat.blue, 170); g.stroke = BasicStroke(1.2f * dp, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, floatArrayOf(2 * dp, 3 * dp), 0f)
                g.draw(Line2D.Float(sx, sy, x, y))
                g.color = Color(0, 0, 0, 120); g.fill(rond(sx, sy, 3.5f * dp)); g.color = coulSat; g.fill(rond(sx, sy, 2.2f * dp))
            }
            if (satVu) {
                val r1 = 16f * dp
                g.paint = RadialGradientPaint(x, y, r1, floatArrayOf(0f, 0.3f, 1f), arrayOf(Color(255, 255, 255, 230), Color(coulSat.red, coulSat.green, coulSat.blue, 170), Color(coulSat.red, coulSat.green, coulSat.blue, 0)))
                g.fill(rond(x, y, r1))
                // The craft: its body and two panels.
                val k = dp
                g.color = Color(0x1d3f8f); g.fill(Rectangle2D.Float(x - 13 * k, y - 3 * k, 8 * k, 6 * k)); g.fill(Rectangle2D.Float(x + 5 * k, y - 3 * k, 8 * k, 6 * k))
                g.color = Color(0x6f9cff); g.stroke = BasicStroke(0.8f * k)
                g.draw(Rectangle2D.Float(x - 13 * k, y - 3 * k, 8 * k, 6 * k)); g.draw(Rectangle2D.Float(x + 5 * k, y - 3 * k, 8 * k, 6 * k))
                g.color = coulSat; g.fill(Rectangle2D.Float(x - 4.5f * k, y - 4.5f * k, 9 * k, 9 * k))
                g.color = Color(0x0a1230); g.stroke = BasicStroke(1f * k); g.draw(Rectangle2D.Float(x - 4.5f * k, y - 4.5f * k, 9 * k, 9 * k))
                etiquette(g, sc.e.satName, x + 16 * dp, y - 14 * dp, coulSat, style, dp)
            }
        }
        return satX
    }

    /** The cities: the great ones always, the small ones near the middle of the view; a dot, its name if it finds room. */
    private fun villes(g: Graphics2D, cam: Camera, style: String, dp: Float, boites: MutableList<Rectangle2D.Float>) {
        val f = if (style == "vintage") Font(Font.SERIF, Font.ITALIC, 1).deriveFont(10.5f * dp) else Font(Font.SANS_SERIF, Font.BOLD, 1).deriveFont(9.5f * dp)
        g.font = f
        val fm = g.fontMetrics
        for (v in GlobeDonnees.VILLES.sortedBy { it.rang }) {
            val p = vec(v.lat, v.lon); val z = cam.profondeur(p)
            if (z < 0.15) continue
            if (v.rang == 3 && z < cos(16 * D2R)) continue
            val x = cam.x(p); val y = cam.y(p)
            val r = (if (v.rang == 1) 3.2f else if (v.rang == 2) 2.6f else 2f) * dp
            val l = fm.stringWidth(v.nom).toFloat()
            val b = Rectangle2D.Float(x + r + 2 * dp, y - fm.ascent * 0.9f, l, fm.ascent.toFloat() + 2)
            val place = boites.none { it.intersects(b) }
            // No room for its name: a small town goes altogether.
            if (!place && v.rang == 3) continue
            when (style) {
                "cartoon" -> { g.color = Color(0x0a1230); g.fill(rond(x, y, r + 1.2f * dp)); g.color = if (v.rang == 1) Color(0xff5a5f) else Color.WHITE; g.fill(rond(x, y, r)) }
                "vintage" -> { g.color = Color(0x5a3a1c); g.fill(rond(x, y, r)); g.color = Color(0xefe0b8); g.fill(rond(x, y, r * 0.45f)) }
                "realiste" -> { g.color = Color(255, 236, 190, 230); g.fill(rond(x, y, r * 0.8f)) }
                else -> { g.color = teinte(style, 0xffffff, 230); g.fill(rond(x, y, r * 0.8f)) }
            }
            if (!place) continue
            boites += b
            val alpha = (255 * ((z - 0.15) / 0.2).coerceIn(0.0, 1.0)).toInt()
            when (style) {
                "cartoon" -> texteCerne(g, v.nom, b.x, y + fm.ascent * 0.35f, Color(255, 255, 255, alpha), Color(10, 18, 48, alpha), f, 3f * dp)
                "vintage" -> texteCerne(g, v.nom, b.x, y + fm.ascent * 0.35f, Color(74, 46, 20, alpha), Color(239, 224, 184, alpha * 2 / 3), f, 2.4f * dp)
                "realiste" -> texteCerne(g, v.nom, b.x, y + fm.ascent * 0.35f, Color(240, 244, 250, alpha), Color(0, 0, 0, alpha / 2), f, 2.4f * dp)
                else -> texteCerne(g, v.nom, b.x, y + fm.ascent * 0.35f, teinte(style, 0xffffff, alpha), Color(0, 0, 0, alpha / 2), f, 2.4f * dp)
            }
        }
    }

    /** How many capitals by the detail asked: the 40 most populous, 100, all. */
    fun nombreCapitales(detail: Int): Int = when (detail) { 0 -> 40; 1 -> 100; else -> GlobeDonnees.CAPITALES.size }

    /**
     * The capitals, the most populous first: a dot, then the name, its
     * country's code on a small badge (the page's flag) or both, where it finds
     * room; the 40 largest keep their dot without it, the others go.
     */
    private fun capitales(g: Graphics2D, cam: Camera, style: String, dp: Float, boites: MutableList<Rectangle2D.Float>, o: Options) {
        val f = if (style == "vintage") Font(Font.SERIF, Font.ITALIC, 1).deriveFont(10.5f * dp) else Font(Font.SANS_SERIF, Font.BOLD, 1).deriveFont(9.5f * dp)
        val fb = Font(Font.SANS_SERIF, Font.BOLD, 1).deriveFont(7.5f * dp)
        g.font = f
        val fm = g.fontMetrics
        val fmb = g.getFontMetrics(fb)
        val nom = o.etiquettes != "drapeau"
        GlobeDonnees.CAPITALES.take(nombreCapitales(o.detail)).forEachIndexed { rangC, v ->
            val p = vec(v.lat, v.lon); val z = cam.profondeur(p)
            if (z < 0.15) return@forEachIndexed
            val x = cam.x(p); val y = cam.y(p)
            val grande = rangC < 40
            val r = (if (rangC < 15) 3.2f else if (grande) 2.6f else 2f) * dp
            val badge = o.etiquettes != "nom" && v.pays.isNotBlank()
            val lb = if (badge) fmb.stringWidth(v.pays) + 6 * dp else 0f
            val l = (if (nom) fm.stringWidth(v.nom).toFloat() else 0f) + (if (badge && nom) lb + 3 * dp else lb)
            if (l <= 0f) {
                // Neither name nor code (a flag asked of a capital without country): the dot alone.
                if (grande || boites.none { it.contains(x.toDouble(), y.toDouble()) }) point(g, x, y, r, style, rangC < 15)
                return@forEachIndexed
            }
            val b = Rectangle2D.Float(x + r + 2 * dp, y - fm.ascent * 0.9f, l, fm.ascent.toFloat() + 2)
            val place = boites.none { it.intersects(b) }
            if (!place && !grande) return@forEachIndexed
            point(g, x, y, r, style, rangC < 15)
            if (!place) return@forEachIndexed
            boites += b
            val alpha = (255 * ((z - 0.15) / 0.2).coerceIn(0.0, 1.0)).toInt()
            var tx = b.x
            if (badge) {
                val hb = fmb.ascent + 3 * dp
                val chip = RoundRectangle2D.Float(tx, y - hb / 2 - fm.ascent * 0.08f, lb, hb, hb * 0.6f, hb * 0.6f)
                if (style == "cartoon") {
                    g.color = Color(255, 255, 255, alpha); g.fill(chip)
                    g.color = Color(10, 18, 48, alpha); g.stroke = BasicStroke(1.1f * dp); g.draw(chip)
                } else { g.color = Color(16, 38, 92, alpha * 230 / 255); g.fill(chip) }
                g.font = fb
                g.color = if (style == "cartoon") Color(10, 18, 48, alpha) else Color(255, 255, 255, alpha)
                g.drawString(v.pays, tx + 3 * dp, (chip.y + chip.height / 2 + fmb.ascent * 0.38f))
                tx += lb + 3 * dp
            }
            if (nom) texteNom(g, v.nom, tx, y + fm.ascent * 0.35f, style, alpha, f, dp)
        }
    }

    private fun point(g: Graphics2D, x: Float, y: Float, r: Float, style: String, grande: Boolean) {
        when (style) {
            "cartoon" -> { g.color = Color(0x0a1230); g.fill(rond(x, y, r + 1.2f * r / 2.6f)); g.color = if (grande) Color(0xff5a5f) else Color.WHITE; g.fill(rond(x, y, r)) }
            "vintage" -> { g.color = Color(0x5a3a1c); g.fill(rond(x, y, r)); g.color = Color(0xefe0b8); g.fill(rond(x, y, r * 0.45f)) }
            "realiste" -> { g.color = Color(255, 236, 190, 230); g.fill(rond(x, y, r * 0.8f)) }
            else -> { g.color = teinte(style, 0xffffff, 230); g.fill(rond(x, y, r * 0.8f)) }
        }
    }

    private fun texteNom(g: Graphics2D, s: String, x: Float, y: Float, style: String, alpha: Int, f: Font, dp: Float) = when (style) {
        "cartoon" -> texteCerne(g, s, x, y, Color(255, 255, 255, alpha), Color(10, 18, 48, alpha), f, 3f * dp)
        "vintage" -> texteCerne(g, s, x, y, Color(74, 46, 20, alpha), Color(239, 224, 184, alpha * 2 / 3), f, 2.4f * dp)
        "realiste" -> texteCerne(g, s, x, y, Color(240, 244, 250, alpha), Color(0, 0, 0, alpha / 2), f, 2.4f * dp)
        else -> texteCerne(g, s, x, y, teinte(style, 0xffffff, alpha), Color(0, 0, 0, alpha / 2), f, 2.4f * dp)
    }
}
