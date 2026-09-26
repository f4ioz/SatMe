/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import fr.f4ioz.satcombo.i18n.I18n
import fr.f4ioz.satcombo.location.Maidenhead
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone

/**
 * "Photo QRV": burns the station identity into a picture of the operating site
 * — big Maidenhead locator, callsign, and optionally the date, the distance to
 * the neighbouring grid squares, the exact coordinates and a small SatMe mark.
 *
 * Everything is drawn on a copy of the bitmap, so the original picture is never
 * touched.
 */
object QthPhoto {

    /** What to stamp on the picture. */
    data class Options(
        val callsign: String = "",
        val locator: String = "",
        val latDeg: Double = 0.0,
        val lonDeg: Double = 0.0,
        val satName: String = "",
        val showCallsign: Boolean = true,
        val showDate: Boolean = true,
        val showGrids: Boolean = true,
        val showCoords: Boolean = false,
        val showLogo: Boolean = true,
        val showSat: Boolean = false,
        val showPolar: Boolean = false,
        /**
         * La silhouette du pays, posée sur la photo.
         *
         * Elle vit ici et non dans un écran séparé : une carte QRV est une
         * photo avec des choses dessus, et la carte est une chose de plus — au
         * même titre que l'indicatif, le drapeau ou le tracé polaire. Un second
         * module aurait produit deux images concurrentes là où l'opérateur n'en
         * veut qu'une.
         */
        val showCarte: Boolean = false,
        /** La ligne POTA : écusson, référence, nom du parc. */
        val showPota: Boolean = false,
        val potaRef: String = "",
        val potaNom: String = "",
        /** Le nom du parc sous la référence — on peut n'en garder que le numéro. */
        val potaNomAffiche: Boolean = true,
        /** Les fréquences du passage, en MHz. 0 = rien à écrire. */
        val showQrg: Boolean = false,
        val upMHz: Double = 0.0,
        val downMHz: Double = 0.0,
        val qrgScale: Float = 1f,
        /** La fréquence annoncée, saisie à la main. Facultative. */
        val qrgTexte: String = "",
        /** Taille de la ligne date + fréquence. */
        val passScale: Float = 1f,
        /** Taille de la ligne POTA (1 = référence) et hauteur au-dessus du bas. */
        val potaTaille: Float = 1f,
        val potaMonte: Float = 0f,
        /** Les anneaux à tracer, en lat, lon aplatis. Vides = rien à dessiner. */
        val carteAnneaux: List<DoubleArray> = emptyList(),
        /** Part de la largeur occupée par la carte, 0,15 à 0,9. */
        val carteTaille: Float = 0.42f,
        /** Position du centre de la carte, en part de l'image. */
        val carteX: Float = 0.5f,
        val carteY: Float = 0.52f,
        /** Ce qu'on met dedans : "DRAPEAU", "UNI", ou vide pour translucide. */
        val carteRemplissage: String = "DRAPEAU",
        val carteCouleur: Int = 0x66FFFFFF,
        /**
         * La zone POTA en silhouette : mêmes réglages de taille et de
         * position que la carte du pays, mais l'emprise est celle du parc —
         * comme un zoom sur la zone. « ZONE » dans [carteContenu] la choisit,
         * « PAYS » (défaut) garde le pays.
         */
        val carteContenu: String = "PAYS",
        val zoneAnneaux: List<DoubleArray> = emptyList(),
        /**
         * Les villes à écrire autour de la zone, avec leur position. Un
         * contour seul ne dit pas où l'on est ; trois noms de communes, si.
         */
        val villes: List<Triple<String, Double, Double>> = emptyList(),
        /** Le bord de la carte se fond dans la photo au lieu de s'y découper. */
        val carteFondu: Boolean = true,
        /** Pass arc (az, el) sampled AOS→LOS — drawn top-right when [showPolar]. */
        val track: List<Pair<Double, Double>> = emptyList(),
        val useUtc: Boolean = false,
        val timeMs: Long = System.currentTimeMillis(),
        /** Neighbouring squares within the configured distance ("JN18cw", …). */
        val nearLocators: List<String> = emptyList(),
        /** AOS of the pass being worked, always printed in UTC. 0 = unknown. */
        val passMs: Long = 0L,
        val passElDeg: Int = 0,
        val showPass: Boolean = true,
        /** Size of the polar plot, 1.0 = reference size. */
        val polarScale: Float = 1f,
        /** Taille du nom du satellite écrit sous le tracé, 1.0 = taille de référence. */
        val satLabelScale: Float = 1f,
        /** True = print the 4-character square only ("JN18"). */
        val loc4: Boolean = false,
        /** Altitude of the spot in metres. Null = unknown, nothing printed. */
        val altM: Double? = null,
        /** Colour of the callsign, ARGB. Defaults to the amber used elsewhere. */
        /** Combien des huit carrés voisins sont écrits, du plus proche. */
        val nearCount: Int = 4,
        /** Code du drapeau placé devant l'indicatif, vide = aucun. */
        val flagLeft: String = "",
        /** Codes des drapeaux placés à droite de l'indicatif. */
        val flagsRight: List<String> = emptyList(),
        val callColor: Int = 0xFFFFC65C.toInt(),
        /** Size of the callsign, 1.0 = reference size. */
        val callScale: Float = 1f,
        /** The app icon, used as the SatMe mark. Null = drawn glyph. */
        val logoIcon: Bitmap? = null,
        /** Système d'unités des distances et de l'altitude imprimées. */
        val units: String = Units.METRIC
    )

    /** Hauteur d'un drapeau, en fraction de la taille de l'indicatif. */
    private const val FLAG_H = 0.72f

    /** Blanc entre un drapeau et l'indicatif, même unité. */
    private const val FLAG_GAP = 0.22f

    /** Une flèche par direction, pour lire le voisinage sans légende. */
    private val ARROWS = mapOf(
        "N" to "\u2191", "S" to "\u2193", "E" to "\u2192", "W" to "\u2190",
        "NE" to "\u2197", "NW" to "\u2196", "SE" to "\u2198", "SW" to "\u2199")

    private const val MAX_EDGE = 2560          // downscale huge camera shots
    private val CYAN = 0xFF38E1D4.toInt()
    private val AMBER = 0xFFFFC65C.toInt()
    private val WHITE = 0xFFFFFFFF.toInt()
    private val DIM = 0xFFC9D6E8.toInt()

    // ---------------------------------------------------------------- loading

    /**
     * The whole EXIF orientation table, not only the three plain rotations.
     * A landscape shot is where the exotic tags show up: several camera apps
     * write TRANSPOSE or a mirrored flag instead of a rotation, and a picture
     * we did not know how to straighten came back sideways or mirrored.
     * Returns null when the picture is already the right way up.
     */
    private fun orientMatrix(tag: Int): Matrix? = when (tag) {
        android.media.ExifInterface.ORIENTATION_ROTATE_90 ->
            Matrix().apply { postRotate(90f) }
        android.media.ExifInterface.ORIENTATION_ROTATE_180 ->
            Matrix().apply { postRotate(180f) }
        android.media.ExifInterface.ORIENTATION_ROTATE_270 ->
            Matrix().apply { postRotate(270f) }
        android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL ->
            Matrix().apply { postScale(-1f, 1f) }
        android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL ->
            Matrix().apply { postScale(1f, -1f) }
        android.media.ExifInterface.ORIENTATION_TRANSPOSE ->
            Matrix().apply { postRotate(90f); postScale(-1f, 1f) }
        android.media.ExifInterface.ORIENTATION_TRANSVERSE ->
            Matrix().apply { postRotate(270f); postScale(-1f, 1f) }
        else -> null
    }

    /** Decodes [uri] downscaled to [MAX_EDGE] and rotated per its EXIF tag. */
    /**
     * Decodes a picture we own on disk. Deliberately does NOT go through the
     * ContentResolver: on several handsets (MIUI in particular) the camera
     * result URI needs a media permission we do not ask for, and reading the
     * file we handed to the camera is always allowed.
     */
    fun loadFile(file: File): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        var sample = 1
        while (longest / sample > MAX_EDGE) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: return null
        val m = runCatching {
            orientMatrix(android.media.ExifInterface(file.absolutePath).getAttributeInt(
                android.media.ExifInterface.TAG_ORIENTATION,
                android.media.ExifInterface.ORIENTATION_NORMAL))
        }.getOrNull() ?: return bmp
        runCatching { Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true) }.getOrDefault(bmp)
    }.getOrNull()

    /**
     * The application icon as a bitmap, rounded like a launcher tile. This is
     * the very icon Google Play shows, so the mark burned into a shared picture
     * is the app's own identity rather than a look-alike.
     */
    fun appIcon(context: Context, sizePx: Int = 288): Bitmap? = runCatching {
        val d = context.packageManager.getApplicationIcon(context.packageName)
        val raw = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        Canvas(raw).also { cv ->
            d.setBounds(0, 0, sizePx, sizePx)
            d.draw(cv)
        }
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val rect = android.graphics.RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat())
        val radius = sizePx * 0.24f
        val mask = Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) }
        c.save()
        c.clipPath(mask)
        c.drawBitmap(raw, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true })
        c.restore()
        raw.recycle()
        out
    }.getOrNull()

    /**
     * The platform decoder, used as a fallback: it reads HEIC/AVIF and the
     * provider streams BitmapFactory refuses, and it applies the EXIF rotation
     * on its own — so the caller must NOT rotate again after this.
     */
    @androidx.annotation.RequiresApi(28)
    private fun decodeModern(context: Context, uri: Uri): Bitmap? = runCatching {
        val src = android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
        android.graphics.ImageDecoder.decodeBitmap(src) { dec, info, _ ->
            dec.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            dec.isMutableRequired = true
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > MAX_EDGE) {
                val k = MAX_EDGE.toFloat() / longest
                dec.setTargetSize(
                    (info.size.width * k).toInt().coerceAtLeast(1),
                    (info.size.height * k).toInt().coerceAtLeast(1)
                )
            }
        }
    }.getOrNull()

    fun load(context: Context, uri: Uri): Bitmap? {
        val cr = context.contentResolver
        // decodeStream returns null ON PURPOSE in inJustDecodeBounds mode: only
        // the stream may be null-tested here. Testing the decode result — as the
        // code used to — rejected every single picture before it was even read.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        }
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longest > 0 && longest / sample > MAX_EDGE) sample *= 2

        var rotate = true
        var bmp = runCatching {
            cr.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                })
            }
        }.getOrNull()

        if (bmp == null && Build.VERSION.SDK_INT >= 28) {
            bmp = decodeModern(context, uri)
            rotate = false                     // ImageDecoder already did it
        }
        val out = bmp ?: return null
        if (!rotate) return out

        val m = runCatching {
            cr.openInputStream(uri)?.use { input ->
                orientMatrix(android.media.ExifInterface(input).getAttributeInt(
                    android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_NORMAL))
            }
        }.getOrNull() ?: return out

        return runCatching {
            Bitmap.createBitmap(out, 0, 0, out.width, out.height, m, true)
        }.getOrDefault(out)
    }

    // ---------------------------------------------------------------- drawing

    /** Returns a new bitmap: [src] with the overlay burned in. */
    fun render(src: Bitmap, o: Options): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true) ?: src
        val c = Canvas(out)
        val w = out.width.toFloat()
        val h = out.height.toFloat()

        // Scale everything off the short edge so portrait and landscape shots
        // get visually identical lettering.
        val u = minOf(w, h) / 100f          // 1 unit = 1% of the short edge
        val pad = 4f * u

        val loc = o.locator.uppercase()
        val square = loc.take(4)
        val sub = if (!o.loc4 && loc.length >= 6) loc.substring(4, 6).uppercase() else ""

        // ---- collect the small lines under the locator ----
        val lines = ArrayList<String>()
        if (o.showDate) {
            val fmt = SimpleDateFormat("EEE dd/MM/yyyy HH:mm", I18n.locale())
                .apply { if (o.useUtc) timeZone = TimeZone.getTimeZone("UTC") }
            lines += fmt.format(Date(o.timeMs)) + "  " + (if (o.useUtc) "UTC" else "LOC")
        }
        if (o.showCoords) lines += "%.5f°  %.5f°".format(o.latDeg, o.lonDeg)
        // Altitude: only ever printed when the phone actually measured one.
        // A GPS fix carries it; a locator typed by hand does not, and writing
        // "0 m" over a summit activation would be worse than writing nothing.
        o.altM?.let { lines += "ALT " + Units.altitude(it, o.units) }
        if (o.showSat && o.satName.isNotBlank()) lines += o.satName
        // The pass itself: always UTC, because that is the only time a DX on
        // another continent can read without converting anything.
        if (o.showPass && o.passMs > 0L) {
            val pf = SimpleDateFormat("dd/MM/yyyy HH:mm", I18n.locale())
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
            // L'angle d'élévation a disparu : sur une annonce de passage à
            // venir, il n'apprend rien à celui qui lit — c'est l'heure et la
            // fréquence qu'il note. La fréquence, saisie à la main, tient sur
            // la même ligne : les deux informations d'un rendez-vous.
            lines += "AOS " + pf.format(Date(o.passMs)) + " UTC" +
                (if (o.qrgTexte.isNotBlank()) "   " + o.qrgTexte else "")
        }
        if (o.showGrids && o.nearCount > 0) {
            // Les carrés voisins, du plus proche au plus lointain : sur l'air on
            // annonce d'abord celui qu'on touche du pied. Le nombre se règle,
            // parce qu'un coin de quatre carrés en vaut quatre et qu'un plein
            // centre de carré n'en vaut aucun.
            val near = runCatching { Maidenhead.aroundSquares(o.latDeg, o.lonDeg) }
                .getOrNull().orEmpty().take(o.nearCount.coerceIn(0, 8))
            near.chunked(2).forEach { pair ->
                lines += pair.joinToString("   ") { a ->
                    (ARROWS[a.dir] ?: "") + " " + a.square + " " +
                        Units.distance(a.km, o.units)
                }
            }
        }

        // ---- paints ----
        // The callsign takes the operator's colour and size. Amber and 100 %
        // are only the defaults: on a snow field or a sunset the amber sinks
        // into the picture, and a callsign nobody can read is not a signature.
        val pCall = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = o.callColor
            textSize = 7.5f * u * o.callScale.coerceIn(0.6f, 2.5f)
            isFakeBoldText = true; letterSpacing = 0.06f
        }
        val pLoc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = CYAN; textSize = 16f * u; isFakeBoldText = true; letterSpacing = 0.02f
        }
        // The subsquare is part of the locator, not a footnote: "JN18XX" is
        // announced as one word, so it is lettered like the square and only the
        // colour still separates the two levels.
        val pSub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = WHITE; textSize = 16f * u; isFakeBoldText = true; letterSpacing = 0.02f
        }
        val pLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = DIM; textSize = 4.4f * u }

        // Neighbouring squares are announced on the air exactly like the main
        // one -- "JN18 or JN19" -- so they are lettered exactly like it too.
        // Up to three of them: a site pinned in the corner of four big squares
        // genuinely belongs to four locators. The whole line is then shrunk as
        // one block until it fits the frame, rather than running off the edge.
        val near = o.nearLocators.take(3)
        val pNear = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AMBER; textSize = pLoc.textSize; isFakeBoldText = true
            letterSpacing = 0.02f
        }
        val nearTxt = near.joinToString("") { " / " + it }
        val subGap = if (sub.isEmpty()) 0f else 0.6f * u
        // Le second drapeau accompagne le locator, pas l'indicatif : sur l'air on
        // annonce « JN18 » et la région d'où l'on émet dans la même phrase. Il se
        // pose donc juste à gauche du carré, à hauteur de lettre, et sa largeur
        // entre dans le calcul de rétrécissement pour que toute la ligne tienne.
        val fLoc = o.flagsRight.mapNotNull { Flags.byCode(it) }
        var locFlagUnit = 0f       // largeur des drapeaux, par unité de textSize
        fLoc.forEach { locFlagUnit += FLAG_H * it.ratio + FLAG_GAP }
        val naturalW = locFlagUnit * pLoc.textSize +
            pLoc.measureText(square) + subGap + pSub.measureText(sub) +
            pNear.measureText(nearTxt)
        val fit = ((w - 2f * pad) / naturalW).coerceAtMost(1f)
        if (fit < 1f) {
            pLoc.textSize *= fit; pSub.textSize *= fit; pNear.textSize *= fit
        }

        // The callsign now lives at the top-left corner, clear of the locator
        // line: on a four-square corner that line is long, and the two used to
        // collide and clip the callsign to its first letters.
        val showCall = o.showCallsign && o.callsign.isNotBlank()
        val locH = 17f * u * fit
        val linesH = lines.size * 6f * u
        val bandH = (locH + linesH + 2 * pad).coerceAtMost(h * 0.55f)

        // ---- readability scrim: transparent -> black towards the bottom ----
        val scrim = Paint().apply {
            shader = LinearGradient(0f, h - bandH * 1.35f, 0f, h,
                intArrayOf(0x00000000, 0x66000000, 0xC4000000.toInt()),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, h - bandH * 1.35f, w, h, scrim)

        // ---- text block, bottom-left ----
        var y = h - pad
        val tailleLigne = pLine.textSize
        for (line in lines.asReversed()) {
            // La ligne du passage — heure et fréquence annoncée — porte le
            // rendez-vous : elle se grossit seule, les autres gardent leur
            // taille.
            val estPassage = line.startsWith("AOS ")
            val kp = if (estPassage) o.passScale.coerceIn(0.6f, 2.5f) else 1f
            pLine.textSize = tailleLigne * kp
            c.drawText(line, pad, y, pLine)
            y -= 6f * u * kp
            pLine.textSize = tailleLigne
        }
        y -= 1.5f * u
        // Locator: bright 4-char square + dimmer subsquare, on one baseline,
        // précédé s'il existe du drapeau choisi en second.
        var lx = pad
        if (fLoc.isNotEmpty()) {
            val lfh = pLoc.textSize * FLAG_H
            val lgap = pLoc.textSize * FLAG_GAP
            fLoc.forEach { fl ->
                FlagDraw.draw(c, fl, lx, y - lfh, lfh)
                lx += FlagDraw.widthFor(fl, lfh) + lgap
            }
        }
        c.drawText(square, lx, y, pLoc)
        lx += pLoc.measureText(square)
        if (sub.isNotEmpty()) {
            lx += subGap * fit
            c.drawText(sub, lx, y, pSub)
            lx += pSub.measureText(sub)
        }
        if (nearTxt.isNotEmpty()) c.drawText(nearTxt, lx, y, pNear)

        // ---- callsign, top-left ----
        if (showCall) {
            val call = o.callsign.uppercase()
            // A callsign enlarged past the frame would be clipped to its first
            // letters, which is exactly the failure the top-left move fixed.
            // So the chosen size is a wish, and the frame has the last word.
            // Les drapeaux se posent à hauteur de lettre, pas en vignette
            // collée dans un coin : c'est une signature, elle se lit d'un bloc.
            // On mesure donc l'ensemble indicatif + drapeaux, et c'est ce bloc
            // entier qu'on rétrécit s'il déborde du cadre.
            val fLeft = Flags.byCode(o.flagLeft)
            var flagUnit = 0f          // largeur des drapeaux, par unité de textSize
            if (fLeft != null) flagUnit += FLAG_H * fLeft.ratio + FLAG_GAP
            val maxW = w - 2f * pad
            val callW = pCall.measureText(call) + flagUnit * pCall.textSize
            if (callW > maxW && callW > 0f) pCall.textSize *= maxW / callW
            // Its own little scrim, sized after the letters so it still covers
            // them whatever size was chosen: the top of a photo is often sky.
            val topH = pCall.textSize * 1.733f
            c.drawRect(0f, 0f, w, topH, Paint().apply {
                shader = LinearGradient(0f, 0f, 0f, topH,
                    intArrayOf(0xA6000000.toInt(), 0x40000000, 0x00000000),
                    floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            })
            val baseline = pad + pCall.textSize * 0.853f
            val fh = pCall.textSize * FLAG_H
            val gap = pCall.textSize * FLAG_GAP
            var fx = pad
            if (fLeft != null) {
                FlagDraw.draw(c, fLeft, fx, baseline - fh, fh)
                fx += FlagDraw.widthFor(fLeft, fh) + gap
            }
            c.drawText(call, fx, baseline, pCall)
        }

        // ---- pass polar plot, top-right ----
        if (o.showPolar && o.track.size > 1) {
            val r = 13f * u * o.polarScale.coerceIn(0.5f, 2.2f)
            drawPolar(c, w - pad - r, pad + r, r, o.track, u,
                label = if (o.satName.isNotBlank()) o.satName else "",
                labelScale = o.satLabelScale.coerceIn(0.5f, 2.5f))
        }

        // ---- la silhouette du pays ----
        //
        // Dessinée avant le logo et après les textes : elle ne doit masquer ni
        // l'indicatif ni le locator, qui sont la raison d'être de la photo.
        // « PAYS », « ZONE » ou « LES_DEUX » : le pays donne le repère large,
        // la zone le lieu exact. Les deux ensemble se dessinent l'un après
        // l'autre, la zone par-dessus — chacune avec son propre cadrage.
        if (o.showCarte) {
            when (o.carteContenu) {
                "ZONE" -> if (o.zoneAnneaux.isNotEmpty())
                    dessineCarte(c, w, h, u, o, o.zoneAnneaux)
                "LES_DEUX" -> {
                    if (o.carteAnneaux.isNotEmpty())
                        dessineCarte(c, w, h, u, o, o.carteAnneaux, villes = false)
                    if (o.zoneAnneaux.isNotEmpty())
                        dessineCarte(c, w, h, u, o, o.zoneAnneaux,
                            taille = o.carteTaille * 0.42f,
                            x = 0.76f, y = 0.30f)
                }
                else -> if (o.carteAnneaux.isNotEmpty())
                    dessineCarte(c, w, h, u, o, o.carteAnneaux, villes = false)
            }
        }

        // ---- la ligne POTA, en bas à droite au-dessus du logo ----
        if (o.showPota && o.potaRef.isNotBlank()) {
            dessinePota(c, w, h, u, o)
        }

        // ---- les fréquences du passage, sous l'indicatif ----
        //
        // Montée et descente : ce que le correspondant cherche à lire sur une
        // photo d'activation, avec le satellite. Elles suivent l'indicatif en
        // haut à gauche, là où l'œil va en premier.
        if (o.showQrg && (o.upMHz > 0.0 || o.downMHz > 0.0)) {
            val kq = o.qrgScale.coerceIn(0.5f, 2.5f)
            val pq = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 3.2f * u * kq
                typeface = android.graphics.Typeface.create(
                    android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
                textAlign = Paint.Align.LEFT
            }
            var y = pad + 13f * u * kq
            fun ligne(fleche: String, mhz: Double) {
                if (mhz <= 0.0) return
                val txt = "%s %.3f".format(java.util.Locale.US, fleche, mhz)
                pq.style = Paint.Style.STROKE
                pq.strokeWidth = pq.textSize * 0.16f
                pq.color = 0xB3000000.toInt()
                c.drawText(txt, pad, y, pq)
                pq.style = Paint.Style.FILL
                pq.color = android.graphics.Color.WHITE
                c.drawText(txt, pad, y, pq)
                y += 4.0f * u * kq
            }
            ligne("↑", o.upMHz)
            ligne("↓", o.downMHz)
        }

        // ---- small SatMe mark, bottom-right ----
        if (o.showLogo) drawMark(c, w - pad, h - pad, u, o.logoIcon)

        return out
    }

    /**
     * The pass as seen from the operating site: zenith at the centre, horizon on
     * the rim, north up. Drawn over a translucent disc so it stays readable on a
     * bright sky, with the AOS end filled and the LOS end hollow — same reading
     * as the plots inside the app.
     */
    private fun drawPolar(
        c: Canvas, cx: Float, cy: Float, r: Float,
        track: List<Pair<Double, Double>>, u: Float, label: String = "",
        labelScale: Float = 1f
    ) {
        c.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x8C000000.toInt()
        })
        val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x99FFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 0.28f * u
        }
        c.drawCircle(cx, cy, r, grid)
        grid.color = 0x55FFFFFF.toInt(); grid.strokeWidth = 0.2f * u
        c.drawCircle(cx, cy, r * 2f / 3f, grid)
        c.drawCircle(cx, cy, r / 3f, grid)
        c.drawLine(cx - r, cy, cx + r, cy, grid)
        c.drawLine(cx, cy - r, cx, cy + r, grid)

        val pN = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = WHITE; textSize = 2.6f * u; isFakeBoldText = true
            textAlign = Paint.Align.CENTER; alpha = 220
        }
        c.drawText("N", cx, cy - r + 2.6f * u, pN)
        c.drawText("S", cx, cy + r - 1f * u, pN)

        fun xy(az: Double, el: Double): Pair<Float, Float> {
            val rr = r * ((90.0 - el.coerceIn(0.0, 90.0)) / 90.0).toFloat()
            val a = Math.toRadians(az)
            return (cx + rr * kotlin.math.sin(a).toFloat()) to (cy - rr * kotlin.math.cos(a).toFloat())
        }

        val path = Path()
        track.forEachIndexed { i, (az, el) ->
            val (x, y) = xy(az, el)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = CYAN; style = Paint.Style.STROKE; strokeWidth = 0.5f * u
        })
        val (ax, ay) = xy(track.first().first, track.first().second)
        val (lx, ly) = xy(track.last().first, track.last().second)
        c.drawCircle(ax, ay, 0.85f * u, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CYAN })
        c.drawCircle(lx, ly, 0.85f * u, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AMBER; style = Paint.Style.STROKE; strokeWidth = 0.35f * u
        })

        // Which bird this arc belongs to, right under the disc.
        if (label.isNotBlank()) {
            val ts = 4.2f * u * labelScale.coerceIn(0.5f, 2.5f)
            val pLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = WHITE; textSize = ts; isFakeBoldText = true
                textAlign = Paint.Align.CENTER
            }
            val txt = label.take(18)
            val tw = pLabel.measureText(txt)
            // Le tracé est collé au bord droit : grossi, le nom en déborderait.
            // On recentre donc l'étiquette dans le cadre plutôt que de la couper.
            val half = tw / 2f + 1.4f * u
            val cw = c.width.toFloat()
            val lcx = if (cw > 2f * (half + 1.5f * u))
                cx.coerceIn(half + 1.5f * u, cw - half - 1.5f * u) else cx
            val baseY = cy + r + u + ts
            c.drawRoundRect(
                lcx - half, baseY - ts,
                lcx + half, baseY + 1.4f * u,
                1.2f * u, 1.2f * u,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x8C000000.toInt() })
            c.drawText(txt, lcx, baseY, pLabel)
        }
    }

    /**
     * Discreet "SatMe" signature, right-aligned. Uses the real application icon
     * — the one on the Play Store and on the launcher — so a shared picture
     * carries the same mark as the app itself; falls back on a drawn glyph when
     * the icon cannot be loaded.
     */
    /**
     * La silhouette du pays et le point du QTH.
     *
     * Le calcul de placement vient de [fr.f4ioz.satcombo.domain.Pays], éprouvé
     * au banc : longitude comprimée par le cosinus de la latitude, même échelle
     * dans les deux sens. Ici on ne fait que tracer.
     */
    /**
     * L'écusson POTA : un arbre stylisé sur pastille verte, la référence en
     * gras, le nom du parc en dessous.
     *
     * L'arbre est dessiné, pas copié : le logo officiel POTA est une marque,
     * et un pictogramme tracé s'agrandit sans pixeliser — même règle que les
     * drapeaux. Le nom est tronqué à trente caractères : « PLAGES, DUNES ET
     * LAGUNES DE LÉHAN À KERSAUZ » déborderait de la photo.
     */
    private fun dessinePota(c: Canvas, w: Float, h: Float, u: Float, o: Options) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val pad = 4f * u
        val k = o.potaTaille.coerceIn(0.6f, 2.5f)
        // La ligne monte d'autant de hauteurs d'écran que demandé : au-dessus
        // du logo par défaut, plus haut si la photo est chargée en bas.
        //
        // La ligne se pose AU-DESSUS du logo par construction. Le bloc POTA
        // descend jusqu'à `bas + 3,2 u·k` (le nom du parc) ; le logo monte
        // jusqu'à `h - pad - 4,6 u`. Sans soustraire la hauteur du nom, les
        // deux se chevauchaient — le nom du parc passait derrière « SatMe »,
        // et un curseur de réglage ne répare pas un défaut de construction.
        val hautLogo = if (o.showLogo) 4.6f * u else 0f
        val hNom = if (o.potaNom.isNotBlank() && o.potaNomAffiche) 3.2f * u * k else 0f
        val bas = h - pad - hautLogo - hNom - 1.2f * u -
            h * o.potaMonte.coerceIn(0f, 0.6f)

        // La pastille et son arbre.
        val r = 3.4f * u * k
        val cx = w - pad - r
        val cy = bas - r
        p.color = 0xFF2E7D32.toInt()
        c.drawCircle(cx, cy, r, p)
        p.color = android.graphics.Color.WHITE
        // Houppier : trois disques ; tronc : un rectangle.
        c.drawCircle(cx, cy - 0.7f * u * k, 1.1f * u * k, p)
        c.drawCircle(cx - 0.9f * u * k, cy + 0.1f * u * k, 0.9f * u * k, p)
        c.drawCircle(cx + 0.9f * u * k, cy + 0.1f * u * k, 0.9f * u * k, p)
        c.drawRect(cx - 0.25f * u * k, cy + 0.3f * u * k, cx + 0.25f * u * k, cy + 1.9f * u * k, p)

        // La référence, à gauche de la pastille.
        fun texte(t: String, x: Float, y: Float, taille: Float, gras: Boolean) {
            p.textSize = taille
            p.typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.MONOSPACE,
                if (gras) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            p.textAlign = Paint.Align.RIGHT
            p.style = Paint.Style.STROKE
            p.strokeWidth = taille * 0.16f
            p.color = 0xB3000000.toInt()
            c.drawText(t, x, y, p)
            p.style = Paint.Style.FILL
            p.color = android.graphics.Color.WHITE
            c.drawText(t, x, y, p)
        }
        texte(o.potaRef, cx - r - 1.5f * u, cy + 1.1f * u * k, 3.4f * u * k, true)
        if (o.potaNom.isNotBlank() && o.potaNomAffiche) {
            // Le nom tenait sur trente caractères, quelle que soit la taille
            // de la photo et du texte : « PLAGES, DUNES ET LAGUNES DE LE »,
            // coupé au milieu d'un mot. On mesure la largeur réelle et l'on
            // rogne sur le dernier mot entier, avec une ellipse pour dire que
            // la suite existe.
            val tailleNom = 2.2f * u * k
            val large = w - 2f * pad
            p.textSize = tailleNom
            p.typeface = android.graphics.Typeface.MONOSPACE
            var nom = o.potaNom
            if (p.measureText(nom) > large) {
                while (nom.isNotEmpty() && p.measureText("$nom…") > large) {
                    val esp = nom.trimEnd().lastIndexOf(' ')
                    nom = if (esp > 0) nom.substring(0, esp) else nom.dropLast(1)
                }
                nom = nom.trimEnd().trimEnd(',') + "…"
            }
            texte(nom, w - pad, bas + 3.2f * u * k, tailleNom, false)
        }
    }

    private fun dessineCarte(
        c: Canvas, w: Float, h: Float, u: Float, o: Options,
        anneaux: List<DoubleArray> = o.carteAnneaux,
        villes: Boolean = true,
        taille: Float = o.carteTaille,
        x: Float = o.carteX,
        y: Float = o.carteY,
    ) {
        val P = fr.f4ioz.satcombo.domain.Pays
        val boite = P.boite(anneaux)
        val cadreL = w * taille.coerceIn(0.10f, 0.9f)
        val cadreH = cadreL
        val cx = w * x.coerceIn(0.1f, 0.9f)
        val cy = h * y.coerceIn(0.1f, 0.9f)
        val pl = P.place(boite, (cx - cadreL / 2).toDouble(), (cy - cadreH / 2).toDouble(),
            cadreL.toDouble(), cadreH.toDouble())

        val chemin = android.graphics.Path()
        for (a in anneaux) {
            var i = 0
            while (i < a.size) {
                val x = pl.x(a[i + 1]).toFloat()
                val y = pl.y(a[i]).toFloat()
                if (i == 0) chemin.moveTo(x, y) else chemin.lineTo(x, y)
                i += 2
            }
            chemin.close()
        }

        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val bornes = android.graphics.RectF()
        chemin.computeBounds(bornes, true)

        // Le fondu : au lieu d'un bord net qui découpe la photo, le
        // remplissage s'efface vers les bords du cadre. Le calque séparé est
        // nécessaire — un dégradé appliqué directement mangerait la photo.
        val calqueBitmap = if (o.carteFondu)
            Bitmap.createBitmap(w.toInt(), h.toInt(), Bitmap.Config.ARGB_8888) else null
        val calque = calqueBitmap?.let { Canvas(it) }
        val cible = calque ?: c

        cible.save()
        cible.clipPath(chemin)
        when (o.carteRemplissage) {
            "DRAPEAU" -> {
                val f = fr.f4ioz.satcombo.data.Flags.ALL.firstOrNull { it.code == o.flagLeft }
                    ?: fr.f4ioz.satcombo.data.Flags.ALL.firstOrNull()
                if (f != null) {
                    // Le drapeau est tracé et non photographié : il s'agrandit
                    // sans pixeliser et ne coûte aucun fichier.
                    //
                    // Il est ajusté pour **couvrir** la silhouette, une seule
                    // fois et centré, comme une photo recadrée. Le répéter
                    // côte à côte donnait deux ou trois drapeaux dans un même
                    // pays, ce qui ne ressemble à rien ; l'étirer aux bornes
                    // aurait faussé ses proportions, qui font justement qu'on
                    // le reconnaît.
                    // Le drapeau épouse EXACTEMENT la boîte du pays.
                    //
                    // En le dimensionnant pour « couvrir », il débordait
                    // largement : un tricolore au format 3:2 posé sur une
                    // France presque carrée sortait du cadre de chaque côté, et
                    // l'on ne voyait plus que sa bande centrale — d'où une
                    // France toute blanche avec un filet de bleu et de rouge.
                    //
                    // Ajusté aux bornes, les trois bandes tombent sur les trois
                    // tiers de la largeur du pays, ce qui est ce qu'on attend
                    // d'un drapeau versé dans une silhouette.
                    val h = bornes.height()
                    val naturel = FlagDraw.widthFor(f, h)
                    cible.save()
                    cible.translate(bornes.left, bornes.top)
                    if (naturel > 0f) cible.scale(bornes.width() / naturel, 1f)
                    FlagDraw.draw(cible, f, 0f, 0f, h)
                    cible.restore()
                } else { p.color = o.carteCouleur; cible.drawRect(bornes, p) }
            }
            else -> { p.color = o.carteCouleur; cible.drawRect(bornes, p) }
        }
        cible.restore()

        p.style = Paint.Style.STROKE
        p.strokeWidth = 0.9f * u
        p.color = android.graphics.Color.WHITE
        p.setShadowLayer(1.6f * u, 0f, 0.5f * u, 0xAA000000.toInt())
        cible.drawPath(chemin, p)
        p.clearShadowLayer()




        // Les noms de villes, posés à leur vraie position dans le cadre. Ils
        // sont ce qui rend la carte lisible : un contour sans nom est une
        // tache, avec trois communes c'est un endroit.
        if (villes && o.villes.isNotEmpty()) {
            val pv = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 2.6f * u
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
            }
            // Les étiquettes déjà posées : on saute celles qui viendraient se
            // superposer. Deux noms l'un sur l'autre ne se lisent ni l'un ni
            // l'autre — mieux vaut en montrer trois lisibles que six empilés.
            val posees = ArrayList<android.graphics.RectF>()
            for ((nom, vlat, vlon) in o.villes) {
                val vx = pl.x(vlon).toFloat()
                val vy = pl.y(vlat).toFloat()
                if (vx < cx - cadreL / 2 || vx > cx + cadreL / 2) continue
                if (vy < cy - cadreH / 2 || vy > cy + cadreH / 2) continue
                val larg = pv.measureText(nom)
                val boite = android.graphics.RectF(
                    vx - larg / 2 - 0.6f * u, vy - 4.4f * u,
                    vx + larg / 2 + 0.6f * u, vy + 1.2f * u)
                if (posees.any { android.graphics.RectF.intersects(it, boite) }) continue
                posees.add(boite)
                pv.style = Paint.Style.FILL
                pv.color = 0xCC000000.toInt()
                cible.drawCircle(vx, vy, 0.7f * u, pv)
                pv.color = android.graphics.Color.WHITE
                pv.setShadowLayer(1.4f * u, 0f, 0.4f * u, 0xCC000000.toInt())
                cible.drawText(nom, vx, vy - 1.4f * u, pv)
                pv.clearShadowLayer()
            }
        }

        // Le point du QTH, à la position exacte.
        val px = pl.x(o.lonDeg).toFloat()
        val py = pl.y(o.latDeg).toFloat()
        p.style = Paint.Style.FILL
        p.color = android.graphics.Color.WHITE
        cible.drawCircle(px, py, 1.9f * u, p)
        p.color = 0xFFE02020.toInt()
        cible.drawCircle(px, py, 1.25f * u, p)

        // Tout est dessiné : le calque revient sur la photo à travers un
        // dégradé, plein au centre et transparent aux bords du cadre. Le
        // reversement DOIT venir en dernier — placé plus haut, il laissait les
        // villes et le point hors du fondu, donc découpés sur l'image.
        if (calque != null && calqueBitmap != null) {
            val degrade = Paint(Paint.ANTI_ALIAS_FLAG)
            degrade.shader = android.graphics.RadialGradient(
                cx, cy, maxOf(cadreL, cadreH) / 2f * 1.05f,
                intArrayOf(0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0x00FFFFFF),
                floatArrayOf(0f, 0.60f, 1f),
                android.graphics.Shader.TileMode.CLAMP)
            degrade.xfermode = android.graphics.PorterDuffXfermode(
                android.graphics.PorterDuff.Mode.DST_IN)
            calque.drawRect(0f, 0f, w, h, degrade)
            c.drawBitmap(calqueBitmap, 0f, 0f, null)
        }
    }

    private fun drawMark(c: Canvas, rightX: Float, baselineY: Float, u: Float, icon: Bitmap? = null) =
        AppMark.draw(c, rightX, baselineY, u, icon, Paint.Align.RIGHT,
            wordColor = WHITE, wordAlpha = 210, glyphColor = CYAN)

    // ----------------------------------------------------------------- output

    /** Writes [bmp] as a JPEG in the app cache and returns the file. */
    fun writeCache(context: Context, bmp: Bitmap, stamp: Long = System.currentTimeMillis()): File {
        val dir = File(context.cacheDir, "photos").apply { mkdirs() }
        val out = File(dir, "satme_qrv_$stamp.jpg")
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        return out
    }

    /** Copies [bmp] into the phone gallery (Pictures/SatMe). Null on failure. */
    fun saveToGallery(context: Context, bmp: Bitmap, displayName: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SatMe")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = runCatching {
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        }.getOrNull() ?: return null
        return runCatching {
            resolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            uri
        }.getOrNull()
    }

    fun uri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    // ---------------------------------------------------------------- capture

    /**
     * Camera intent that actually works across handsets.
     *
     * The stock `TakePicture` contract only puts EXTRA_OUTPUT in the intent; it
     * grants nothing. Several camera apps (Samsung and a few Xiaomi builds among
     * them) then fail silently to write into our FileProvider URI: they come
     * back with RESULT_OK and leave a zero-byte file, which is exactly the
     * "photo taken but nothing shown" symptom. So the write permission is
     * granted explicitly, both through the intent flags and, for the stubborn
     * ones, package by package.
     */
    fun captureIntent(context: Context, target: Uri): android.content.Intent {
        val intent = android.content.Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, target)
            addFlags(android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            @Suppress("DEPRECATION")
            val cams = context.packageManager.queryIntentActivities(intent, 0)
            for (info in cams) {
                context.grantUriPermission(
                    info.activityInfo.packageName, target,
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        return intent
    }

    /** True when the camera app really produced something at [file]. */
    fun hasContent(file: File): Boolean = file.exists() && file.length() > 1024L
}
