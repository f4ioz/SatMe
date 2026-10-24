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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * A sheet of received SSTV pictures: a background (imported — the template
 * of an ARISS series — or generic), boxes the pictures go into in the order
 * chosen, and texts (callsign, name @ locator, dates, title).
 *
 * Everything is placed in fractions of the background (0..1), so the same
 * template draws the same at any size: the preview and the export.
 */
object Planche {

    /** A place on the sheet, in fractions of its width and height. */
    data class Zone(val x: Float, val y: Float, val w: Float, val h: Float) {
        fun contient(px: Float, py: Float) = px in x..(x + w) && py in y..(y + h)
        fun bornee(): Zone {
            val ww = w.coerceIn(0.02f, 1f); val hh = h.coerceIn(0.02f, 1f)
            return Zone(x.coerceIn(0f, 1f - ww), y.coerceIn(0f, 1f - hh), ww, hh)
        }
    }

    enum class Champ { INDICATIF, NOM_LOCATOR, DATES, TITRE, LIBRE }

    data class Texte(
        val champ: Champ,
        val zone: Zone,
        val couleur: Int = BLANC,
        /** Box behind the text; 0 = none. */
        val fond: Int = 0,
        /** The text itself, for [Champ.LIBRE]. */
        val libre: String = ""
    )

    data class Modele(
        val id: String,
        val nom: String,
        /** Imported background, file name in the sheets folder; "" = generic. */
        val fond: String = "",
        /** Width / height of a generic background. */
        val ratio: Float = 1.414f,
        val cases: List<Zone> = emptyList(),
        val textes: List<Texte> = emptyList(),
        /** A line under each picture: mode, time received, callsign. */
        val legende: Boolean = false,
        /** "n/N" on each box. */
        val numeros: Boolean = false,
        val titre: String = "",
        /** Picture in each box: box index → PNG name in the gallery. */
        val images: Map<Int, String> = emptyMap(),
        /**
         * The template's own boxes while the sheet is fitted to the pictures
         * chosen ([ajuste]): one box per picture. Empty = the template's layout.
         */
        val origine: List<Zone> = emptyList(),
        /** The texts where the template had them, when fitting moved them aside. */
        val textesOrigine: List<Texte> = emptyList(),
        /**
         * The background of a generic sheet ([PlancheDecor]): "" the SatMe
         * starry sky, a built-in one's id, or a picture's file name in the
         * sheets folder — always fitted to the sheet (cover), never stretched.
         */
        val decor: String = "",
        /** Patches laid over the sheet, at most [ECUSSONS_MAX], the last on top. */
        val ecussons: List<Ecusson> = emptyList(),
        /** Each picture shown as its fused or corrected version when there is one ([CorrectionsGalerie.pourPlanche]), marked so. */
        val corrigees: Boolean = false
    ) {
        /** Fitted to the pictures chosen, not the template's boxes. */
        val ajustee: Boolean get() = origine.isNotEmpty()
    }

    /**
     * A patch (a mission badge…): a picture of the sheets folder over the
     * sheet. [zone] keeps the picture's shape; [sansContour]: its plain
     * surround made transparent ([PlancheDecor.detoure]).
     */
    data class Ecusson(val fichier: String, val zone: Zone, val sansContour: Boolean = false)

    const val ECUSSONS_MAX = 3

    const val BLANC = 0xFFFFFFFF.toInt()
    const val NOIR = 0xFF000000.toInt()
    const val MARINE = 0xFF0B2D5B.toInt()
    const val JAUNE = 0xFFFFC21A.toInt()
    const val CYAN = 0xFF00E5FF.toInt()
    const val ROUGE = 0xFFE5484D.toInt()
    const val VOILE_BLANC = 0xE6F2F4F8.toInt()
    const val VOILE_NOIR = 0x99000000.toInt()

    val COULEURS = listOf(BLANC, NOIR, MARINE, JAUNE, CYAN, ROUGE)
    val FONDS = listOf(0, VOILE_BLANC, VOILE_NOIR)

    // ------------------------------------------------------------ layouts

    /** [n] boxes in a grid over [zone], 4:3 boxes as SSTV pictures are. */
    fun grille(n: Int, zone: Zone = Zone(0.33f, 0.03f, 0.65f, 0.94f), ratioPlanche: Float = 1.414f): List<Zone> {
        if (n <= 0) return emptyList()
        // The column count that makes the boxes closest to 4:3.
        var meilleur = 1; var ecart = Float.MAX_VALUE
        for (c in 1..n) {
            val r = (n + c - 1) / c
            val wc = zone.w * ratioPlanche / c; val hc = zone.h / r
            val e = abs(wc / hc - 4f / 3f)
            if (e < ecart) { ecart = e; meilleur = c }
        }
        val c = meilleur; val r = (n + c - 1) / c
        val marge = 0.08f
        val cw = zone.w / c; val ch = zone.h / r
        // Boxes at 4:3 inside their cell.
        var bw = cw * (1 - marge); var bh = bw * ratioPlanche * 3f / 4f
        if (bh > ch * (1 - marge)) { bh = ch * (1 - marge); bw = bh * 4f / 3f / ratioPlanche }
        return (0 until n).map { i ->
            val col = i % c; val lig = i / c
            Zone(zone.x + col * cw + (cw - bw) / 2, zone.y + lig * ch + (ch - bh) / 2, bw, bh)
        }
    }

    /**
     * Columns and box width (in sheet heights) of the grid where [n] boxes of
     * [aspect] are largest within [zone], [ecart] of a box between them.
     */
    private fun meilleure(n: Int, zone: Zone, ratioPlanche: Float, aspect: Float, ecart: Float): Pair<Int, Float> {
        val zw = zone.w * ratioPlanche; val zh = zone.h
        var c = 1; var b = 0f
        for (cc in 1..n) {
            val rr = (n + cc - 1) / cc
            val bb = minOf(zw / (cc + ecart * (cc - 1)), zh / (rr / aspect + ecart * (rr - 1)))
            if (bb > b * 1.0001f) { b = bb; c = cc }
        }
        return c to b
    }

    /**
     * [n] boxes as large as [zone] allows, [aspect] (width / height) kept:
     * the column count that makes them largest, the block centred, the last
     * row centred too — no empty box.
     */
    fun grilleOptimale(n: Int, zone: Zone, ratioPlanche: Float = 1.414f, aspect: Float = 4f / 3f, ecart: Float = 0.06f): List<Zone> {
        if (n <= 0 || zone.w <= 0f || zone.h <= 0f) return emptyList()
        val (c, b) = meilleure(n, zone, ratioPlanche, aspect, ecart)
        val r = (n + c - 1) / c
        val zw = zone.w * ratioPlanche
        val bh = b / aspect; val e = b * ecart
        val y0 = zone.y + (zone.h - (r * bh + (r - 1) * e)) / 2
        return (0 until n).map { i ->
            val lig = i / c; val col = i % c
            val k = if (lig == r - 1) n - lig * c else c
            val x = zone.x * ratioPlanche + (zw - (k * b + (k - 1) * e)) / 2 + col * (b + e)
            Zone(x / ratioPlanche, y0 + lig * (bh + e), b / ratioPlanche, bh)
        }
    }

    /** Width / height of a box: a 4:3 picture, and the line under it when there is one. */
    fun aspectCase(legende: Boolean): Float = if (legende) 4f / 3f * (1 - BANDE) else 4f / 3f

    /** The share of a box the line under the picture takes. */
    const val BANDE = 0.09f

    /** The rectangle round [zones]; null when there are none. */
    fun englobe(zones: List<Zone>): Zone? {
        if (zones.isEmpty()) return null
        val x0 = zones.minOf { it.x }; val y0 = zones.minOf { it.y }
        return Zone(x0, y0, zones.maxOf { it.x + it.w } - x0, zones.maxOf { it.y + it.h } - y0)
    }

    private fun touche(a: Zone, b: Zone) = a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h

    /** Twelve boxes round the edge of a 4×4 grid, the middle left for the title. */
    fun tour(ratioPlanche: Float = 1.414f): List<Zone> {
        val tout = grille(16, Zone(0.01f, 0.02f, 0.98f, 0.96f), ratioPlanche)
        // Reading order round the edge: top row, right side, bottom row backwards, left side upwards.
        val ordre = listOf(0, 1, 2, 3, 7, 11, 15, 14, 13, 12, 8, 4)
        return ordre.map { tout[it] }
    }

    fun genereGrille(id: String, nom: String, n: Int = 12): Modele = Modele(
        id = id, nom = nom, cases = grille(n), numeros = true, legende = true,
        textes = listOf(
            Texte(Champ.TITRE, Zone(0.02f, 0.05f, 0.29f, 0.16f), JAUNE),
            Texte(Champ.DATES, Zone(0.02f, 0.22f, 0.29f, 0.06f), BLANC),
            Texte(Champ.INDICATIF, Zone(0.04f, 0.55f, 0.20f, 0.09f), MARINE, VOILE_BLANC),
            Texte(Champ.NOM_LOCATOR, Zone(0.04f, 0.68f, 0.25f, 0.09f), MARINE, VOILE_BLANC)))

    fun genereTour(id: String, nom: String): Modele = Modele(
        id = id, nom = nom, cases = tour(), legende = true,
        textes = listOf(
            Texte(Champ.TITRE, Zone(0.28f, 0.30f, 0.44f, 0.13f), BLANC),
            Texte(Champ.DATES, Zone(0.30f, 0.45f, 0.40f, 0.06f), JAUNE),
            Texte(Champ.INDICATIF, Zone(0.34f, 0.55f, 0.28f, 0.08f), CYAN),
            Texte(Champ.NOM_LOCATOR, Zone(0.32f, 0.64f, 0.36f, 0.08f), BLANC)))

    /**
     * An imported template: its own boxes when found, and the callsign then
     * the name @ locator in its own frames when found (written on their
     * colour, over "Your Callsign"); else where an ARISS template has them.
     */
    fun genereImporte(id: String, nom: String, fond: String, ratio: Float, cases: List<Zone>,
                      cadres: List<Pair<Zone, Int>> = emptyList()): Modele {
        val ind = cadres.getOrNull(0)
        val nl = cadres.getOrNull(1)
        return Modele(
            id = id, nom = nom, fond = fond, ratio = ratio,
            cases = cases.ifEmpty { grille(12, ratioPlanche = ratio) },
            textes = listOf(
                Texte(Champ.INDICATIF, ind?.first ?: Zone(0.08f, 0.66f, 0.20f, 0.06f), MARINE, ind?.second ?: 0),
                Texte(Champ.NOM_LOCATOR, nl?.first ?: Zone(0.08f, 0.775f, 0.20f, 0.06f), MARINE, nl?.second ?: 0)))
    }

    // ------------------------------------------------------- fitting

    /** The pictures on the sheet, in the order of their boxes. */
    fun choisies(m: Modele): List<String> = m.images.toSortedMap().values.distinct()

    /** The texts at their template places, what was changed on them since kept. */
    private fun textesDuModele(m: Modele): List<Texte> =
        if (m.textesOrigine.isNotEmpty() && m.textesOrigine.size == m.textes.size)
            m.textes.mapIndexed { i, t -> t.copy(zone = m.textesOrigine[i].zone) }
        else m.textes

    /**
     * The sheet holding exactly [fichiers], in that order: one box each, in
     * the grid that makes them largest where the template's pictures go —
     * around its texts; on a generic background the texts may move to a side
     * when the pictures gain much by it. The template's boxes are kept
     * ([Modele.origine]) to go back to ([duModele]). None = the template's layout.
     */
    fun ajuste(m: Modele, fichiers: List<String>): Modele {
        val l = fichiers.distinct()
        if (l.isEmpty()) return duModele(m).copy(images = emptyMap())
        val origine = m.origine.ifEmpty { m.cases }
        val textes = textesDuModele(m)
        val r = m.ratio; val a = aspectCase(m.legende); val n = l.size; val ecart = 0.06f
        val generique = m.fond.isBlank()
        // Where the pictures go: the whole sheet on a generic background, else where the template has its boxes.
        val zone = (if (generique) null else englobe(origine)) ?: Zone(0.02f, 0.02f, 0.96f, 0.96f)
        // Candidates: the place, the texts there, the gain a move must bring to be chosen.
        val essais = ArrayList<Triple<Zone, List<Texte>, Float>>()
        val genants = textes.filter { touche(it.zone, zone) }
        if (genants.isEmpty()) essais += Triple(zone, textes, 1f) else {
            val u = englobe(genants.map { it.zone })!!; val mg = 0.015f
            val gauche = u.x - mg - zone.x; val droite = zone.x + zone.w - (u.x + u.w + mg)
            val haut = u.y - mg - zone.y; val bas = zone.y + zone.h - (u.y + u.h + mg)
            listOf(Zone(zone.x, zone.y, gauche, zone.h), Zone(u.x + u.w + mg, zone.y, droite, zone.h),
                Zone(zone.x, zone.y, zone.w, haut), Zone(zone.x, u.y + u.h + mg, zone.w, bas))
                .filter { it.w > 0.05f && it.h > 0.05f }.forEach { essais += Triple(it, textes, 1f) }
        }
        val bloc = englobe(textes.map { it.zone })
        if (generique && bloc != null) {
            // The texts together, as placed, moved to one side (made smaller if need be).
            val mg = 0.02f
            fun place(s: Float, x: Float, y: Float) = textes.map { t ->
                t.copy(zone = Zone(x + (t.zone.x - bloc.x) * s, y + (t.zone.y - bloc.y) * s, t.zone.w * s, t.zone.h * s))
            }
            val sv = minOf(1f, 0.32f / bloc.w, 0.96f / bloc.h); val bw = bloc.w * sv; val yv = 0.5f - bloc.h * sv / 2
            val sh = minOf(1f, 0.30f / bloc.h, 0.96f / bloc.w); val bh = bloc.h * sh; val xh = 0.5f - bloc.w * sh / 2
            // Never so small the texts no longer read; worth it only for much larger pictures.
            if (sv >= 0.6f) {
                essais += Triple(Zone(2 * mg + bw, mg, 1 - 3 * mg - bw, 1 - 2 * mg), place(sv, mg, yv), 1.25f)
                essais += Triple(Zone(mg, mg, 1 - 3 * mg - bw, 1 - 2 * mg), place(sv, 1 - mg - bw, yv), 1.25f)
            }
            if (sh >= 0.6f) {
                essais += Triple(Zone(mg, 2 * mg + bh, 1 - 2 * mg, 1 - 3 * mg - bh), place(sh, xh, mg), 1.25f)
                essais += Triple(Zone(mg, mg, 1 - 2 * mg, 1 - 3 * mg - bh), place(sh, xh, 1 - mg - bh), 1.25f)
            }
        }
        if (essais.isEmpty()) essais += Triple(zone, textes, 1f)
        val (z, tx, _) = essais.maxByOrNull { (z, _, gain) -> meilleure(n, z, r, a, ecart).second / gain }!!
        val bouges = tx !== textes
        return m.copy(cases = grilleOptimale(n, z, r, a, ecart), images = l.withIndex().associate { it.index to it.value },
            origine = origine, textes = tx, textesOrigine = if (bouges) textes else emptyList())
    }

    /** Fitted again to the same pictures (after the line under them was switched, say). */
    fun reajuste(m: Modele): Modele = if (m.ajustee) ajuste(m, choisies(m)) else m

    /** Back to the template's boxes and texts, the pictures chosen in its first boxes. */
    fun duModele(m: Modele): Modele {
        if (!m.ajustee) return m
        return m.copy(cases = m.origine, origine = emptyList(), textes = textesDuModele(m), textesOrigine = emptyList(),
            images = choisies(m).take(m.origine.size).withIndex().associate { it.index to it.value })
    }

    /** As many pictures as the template has boxes, at most. */
    fun nombreMax(m: Modele): Int = m.origine.ifEmpty { m.cases }.size

    /**
     * A picture put on a fitted sheet, or taken off it if it is there; the
     * sheet fitted again. Unchanged when it is full ([nombreMax]).
     */
    fun bascule(m: Modele, fichier: String): Modele {
        val l = choisies(m)
        return when {
            fichier in l -> ajuste(m, l - fichier)
            l.size >= nombreMax(m) -> m
            else -> ajuste(m, l + fichier)
        }
    }

    /** The pictures of boxes [i] and [j] swapped. */
    fun echange(m: Modele, i: Int, j: Int): Modele {
        if (i !in m.cases.indices || j !in m.cases.indices) return m
        val im = m.images.toMutableMap()
        val a = im.remove(i); val b = im.remove(j)
        if (a != null) im[j] = a
        if (b != null) im[i] = b
        return m.copy(images = im)
    }

    /**
     * The sheet fitted to [n] pictures: those already on it kept in their
     * order, the others taken from [images] as [remplitParReception] would,
     * a copy of one already there never. 0 = the template's own layout.
     */
    fun avecNombre(m: Modele, n: Int, images: List<SstvMeta.SstvShot>, signatures: Map<String, Signature> = emptyMap()): Modele {
        if (n <= 0) return duModele(m)
        val deja = choisies(m).take(n)
        if (deja.size >= n) return ajuste(m, deja)
        val plus = distinctes(images.filter { it.fileName !in deja }, signatures, deja.mapNotNull { signatures[it] })
            .take(n - deja.size).sortedBy { it.timeMs }.map { it.fileName }
        return ajuste(m, deja + plus)
    }

    /** "Fill": the template's boxes, or as many pictures as the fitted sheet holds, the best of [images]. */
    fun remplit(m: Modele, images: List<SstvMeta.SstvShot>, signatures: Map<String, Signature> = emptyMap()): Modele =
        if (m.ajustee) avecNombre(m.copy(images = emptyMap()), m.cases.size, images, signatures)
        else m.copy(images = remplitParReception(m.cases.size, images, signatures))

    // ------------------------------------------------------- same picture

    /**
     * What a picture looks like, small: to tell two copies of one slide of
     * a series (received on two passes, decoded twice) from two slides.
     * [gris]: 16×12 grey; [bruit]: the share of it the
     * decoder's green noise covers. With the gallery sorted
     * ([ClassementSstv]): [groupe], the slide's group (copies are those of
     * one group), and [note], its mark (0–100, -1 not marked).
     */
    class Signature(val gris: IntArray, val bruit: Float, val groupe: String = "", val note: Int = -1) {
        fun avec(groupe: String, note: Int) = Signature(gris, bruit, groupe, note)
    }

    /** The [Signature] of a picture, from its pixels (any size, a small copy is enough). */
    fun signature(px: IntArray, w: Int, h: Int): Signature {
        fun gris(gw: Int, gh: Int): IntArray {
            val s = LongArray(gw * gh); val n = IntArray(gw * gh)
            for (y in 0 until h) {
                val gy = y * gh / h
                for (x in 0 until w) { val k = gy * gw + x * gw / w; s[k] += lum(px[y * w + x]).toLong(); n[k]++ }
            }
            return IntArray(gw * gh) { if (n[it] > 0) (s[it] / n[it]).toInt() else 0 }
        }
        val g = gris(16, 12)
        // Noise of a weak pass: the green speckle and streaks of the decoder, rare in a slide itself.
        var vert = 0
        for (c in px) {
            val r = (c shr 16) and 0xFF; val v = (c shr 8) and 0xFF; val b = c and 0xFF
            if (v > r + 50 && v > b + 50) vert++
        }
        return Signature(g, vert / px.size.coerceAtLeast(1).toFloat())
    }

    /**
     * Two copies of one picture? Rows compared, the median row judged: the
     * noise of a pass spoils a few rows, another slide most of them.
     */
    fun memeImage(a: Signature, b: Signature): Boolean {
        if (a.groupe.isNotEmpty() && b.groupe.isNotEmpty()) return a.groupe == b.groupe
        val lignes = FloatArray(12) { y -> (0 until 16).sumOf { x -> abs(a.gris[y * 16 + x] - b.gris[y * 16 + x]) } / 16f }
        lignes.sort()
        return (lignes[5] + lignes[6]) / 2 < SEUIL_MEME
    }

    private const val SEUIL_MEME = 16f

    /** Above this share of noise a picture is kept for last. */
    private const val SALE = 0.06f

    /**
     * One copy of each picture, the best first: complete before partial,
     * clean before spoilt by noise, then the earliest; of copies, the best
     * marked (else the cleanest). [deja]: pictures already chosen, whose
     * copies are left out.
     */
    private fun distinctes(images: List<SstvMeta.SstvShot>, signatures: Map<String, Signature>,
                           deja: List<Signature> = emptyList()): List<SstvMeta.SstvShot> {
        val gardees = ArrayList<SstvMeta.SstvShot>(); val vues = ArrayList(deja)
        fun note(s: SstvMeta.SstvShot) = signatures[s.fileName]?.note ?: -1
        for (s in images.sortedWith(compareBy<SstvMeta.SstvShot>({ !it.complete }, { -note(it) }, { signatures[it.fileName]?.bruit ?: 0f }, { it.timeMs }))) {
            val sig = signatures[s.fileName]
            if (sig != null && vues.any { memeImage(it, sig) }) continue
            gardees += s; if (sig != null) vues += sig
        }
        fun sale(s: SstvMeta.SstvShot): Boolean = signatures[s.fileName]?.let { if (it.note >= 0) it.note < NOTE_SALE else it.bruit > SALE } ?: false
        return gardees.sortedWith(compareBy({ !it.complete }, { sale(it) }, { it.timeMs }))
    }

    /** Below this mark a picture is kept for last. */
    private const val NOTE_SALE = 60

    // ------------------------------------------------------------ texts

    private val jour = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private val minute = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    /** "2025-05-05 – 2025-05-12", or a single day, from the pictures placed. */
    fun dates(temps: List<Long>): String {
        val t = temps.filter { it > 0L }.sorted()
        if (t.isEmpty()) return ""
        val a = jour.format(Date(t.first())); val b = jour.format(Date(t.last()))
        return if (a == b) a else "$a – $b"
    }

    /** The locator most pictures were received at. */
    fun locatorDominant(locators: List<String>): String =
        locators.filter { it.isNotBlank() }.groupingBy { it.uppercase().take(6) }.eachCount()
            .maxByOrNull { it.value }?.key ?: ""

    /** The line under a picture: "PD 120 | 2025/05/31 20:00 UTC | F4IOZ", and « corrigée » for a processed version ([SstvMeta.SstvShot.traitement]). */
    fun legende(s: SstvMeta.SstvShot, indicatif: String): String =
        listOf(s.mode, if (s.timeMs > 0) minute.format(Date(s.timeMs)) + " UTC" else "",
            indicatif.ifBlank { s.callsign }, s.traitement).filter { it.isNotBlank() }.joinToString(" | ")

    /**
     * Boxes filled in order of reception with the pictures given, one per box,
     * the partial ones last (a series is sent again and again: a complete copy
     * of a slide is worth more). With [signatures], one copy of each slide:
     * the cleanest — a box left empty rather than a slide twice.
     */
    fun remplitParReception(nbCases: Int, images: List<SstvMeta.SstvShot>,
                            signatures: Map<String, Signature> = emptyMap()): Map<Int, String> {
        val tri = distinctes(images, signatures).take(nbCases).sortedBy { it.timeMs }
        return tri.mapIndexed { i, s -> i to s.fileName }.toMap()
    }

    /**
     * Where a series stands: the boxes filled, and the numbers (1-based) of
     * those still empty — during an ARISS week, what is left to receive.
     */
    fun suivi(m: Modele, existe: (String) -> Boolean = { true }): Pair<Int, List<Int>> {
        val pleines = m.cases.indices.filter { i -> m.images[i]?.let(existe) == true }
        return pleines.size to m.cases.indices.filter { it !in pleines }.map { it + 1 }
    }

    // ------------------------------------------------------- finding boxes

    /**
     * The boxes of an imported template: plain dark (or plain light) areas,
     * nearly rectangular, of a picture's size — the "NO IMAGE" boxes of an
     * ARISS template. In reading order: by rows, then left to right.
     */
    fun detecteCases(px: IntArray, w: Int, h: Int): List<Zone> {
        val trouvees = (composantes(px, w, h, sombre = true, 0.6f, 2.4f) +
            composantes(px, w, h, sombre = false, 0.6f, 2.4f)).map { it.first }
        val boites = trouvees.distinctBy { (it.x * 50).toInt() to (it.y * 50).toInt() }
        if (boites.isEmpty()) return emptyList()
        // Rows: tops closer than half a box height.
        val hMed = boites.map { it.h }.sorted()[boites.size / 2]
        val rangees = ArrayList<MutableList<Zone>>()
        for (b in boites.sortedBy { it.y }) {
            val r = rangees.lastOrNull()
            if (r != null && abs(r.first().y - b.y) < hMed / 2) r += b else rangees += mutableListOf(b)
        }
        return rangees.flatMap { r -> r.sortedBy { it.x } }
    }

    private fun lum(c: Int) = (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8

    /**
     * The light, long frames of a template where the callsign and the name
     * go ("Your Callsign", "Your Name @ Grid"), top to bottom, with their
     * colour: the text is written on that colour, over the example.
     */
    fun detecteCadresTexte(px: IntArray, w: Int, h: Int): List<Pair<Zone, Int>> =
        // Big lettering covers half of them: judged on their edge, light all round.
        composantes(px, w, h, sombre = false, 2.4f, 9f, 0.002f, 0.08f, 0.2f, bord = 0.8f).sortedBy { it.first.y }

    private fun composantes(px: IntArray, w: Int, h: Int, sombre: Boolean, aspectMin: Float, aspectMax: Float,
                            partMin: Float = 0.004f, partMax: Float = 0.25f, rempli: Float = 0.80f,
                            bord: Float = 0f): List<Pair<Zone, Int>> {
        val masque = BooleanArray(w * h) { val l = lum(px[it]); if (sombre) l < 40 else l > 230 }
        val vu = BooleanArray(w * h)
        val file = IntArray(w * h)
        val out = ArrayList<Pair<Zone, Int>>()
        for (depart in 0 until w * h) {
            if (!masque[depart] || vu[depart]) continue
            var tete = 0; var queue = 0
            file[queue++] = depart; vu[depart] = true
            var x0 = w; var y0 = h; var x1 = 0; var y1 = 0; var n = 0
            var sr = 0L; var sg = 0L; var sb = 0L
            while (tete < queue) {
                val p = file[tete++]; n++
                val c = px[p]; sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF
                val x = p % w; val y = p / w
                if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
                if (x > 0 && masque[p - 1] && !vu[p - 1]) { vu[p - 1] = true; file[queue++] = p - 1 }
                if (x < w - 1 && masque[p + 1] && !vu[p + 1]) { vu[p + 1] = true; file[queue++] = p + 1 }
                if (y > 0 && masque[p - w] && !vu[p - w]) { vu[p - w] = true; file[queue++] = p - w }
                if (y < h - 1 && masque[p + w] && !vu[p + w]) { vu[p + w] = true; file[queue++] = p + w }
            }
            val bw = x1 - x0 + 1; val bh = y1 - y0 + 1
            val aire = bw.toFloat() * bh
            val part = aire / (w.toFloat() * h)
            val aspect = bw.toFloat() / bh
            // A box: a picture's size, rectangular (text inside leaves holes), not a strip.
            // The edge, one pixel inside the bounds: how much of it is in the area.
            fun bordRempli(): Float {
                if (bw < 6 || bh < 6) return 0f
                var dedans = 0; var tour = 0
                for (x in x0 + 1 until x1) { tour += 2; if (masque[(y0 + 1) * w + x]) dedans++; if (masque[(y1 - 1) * w + x]) dedans++ }
                for (y in y0 + 1 until y1) { tour += 2; if (masque[y * w + x0 + 1]) dedans++; if (masque[y * w + x1 - 1]) dedans++ }
                return dedans.toFloat() / tour
            }
            if (part in partMin..partMax && n / aire > rempli && aspect in aspectMin..aspectMax &&
                (bord <= 0f || bordRempli() >= bord))
                out += Zone(x0.toFloat() / w, y0.toFloat() / h, bw.toFloat() / w, bh.toFloat() / h) to
                    ((0xFF shl 24) or ((sr / n).toInt() shl 16) or ((sg / n).toInt() shl 8) or (sb / n).toInt())
        }
        return out
    }

    // ------------------------------------------------------------ patches

    /**
     * A patch [fichier] of [largeur]×[hauteur] pixels put on the sheet, a
     * sixth of its width, near the middle (each new one a little lower);
     * unchanged when it has [ECUSSONS_MAX] already.
     */
    fun ajouteEcusson(m: Modele, fichier: String, largeur: Int, hauteur: Int, ratioPlanche: Float = m.ratio): Modele {
        if (m.ecussons.size >= ECUSSONS_MAX || largeur <= 0 || hauteur <= 0) return m
        val w = 0.16f; val h = w * ratioPlanche * hauteur / largeur
        val k = m.ecussons.size * 0.05f
        val z = Zone(0.5f - w / 2 + k, 0.5f - h / 2 + k, w, h)
        return m.copy(ecussons = m.ecussons + Ecusson(fichier, ecussonBorne(z)))
    }

    /** [z] scaled by [facteur] round its centre, its shape kept, within the sheet. */
    fun echelle(z: Zone, facteur: Float): Zone {
        val f = facteur.coerceIn(0.03f / z.w.coerceAtLeast(0.001f), 1f / maxOf(z.w, z.h, 0.001f))
        val w = z.w * f; val h = z.h * f
        return ecussonBorne(Zone(z.x + (z.w - w) / 2, z.y + (z.h - h) / 2, w, h))
    }

    /** A patch's place within the sheet, its shape kept (no larger than the sheet). */
    fun ecussonBorne(z: Zone): Zone {
        val f = minOf(1f, 1f / maxOf(z.w, z.h, 0.001f))
        val w = (z.w * f).coerceAtLeast(0.005f); val h = (z.h * f).coerceAtLeast(0.005f)
        return Zone(z.x.coerceIn(0f, 1f - w), z.y.coerceIn(0f, 1f - h), w, h)
    }

    /**
     * A sheet received from a page, its background and patches checked:
     * a built-in background or a file there is ([existe]), patches that are
     * files there, [ECUSSONS_MAX] at most, within the sheet.
     */
    fun decorVerifie(m: Modele, existe: (String) -> Boolean): Modele = m.copy(
        decor = m.decor.takeIf { PlancheDecor.integre(it) != null || (PlancheDecor.nomFichier(it) && existe(it)) } ?: "",
        ecussons = m.ecussons.filter { PlancheDecor.nomFichier(it.fichier) && existe(it.fichier) }
            .take(ECUSSONS_MAX).map { it.copy(zone = ecussonBorne(it.zone)) })

    // ------------------------------------------------------------ storage

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", " ")
    private fun unesc(s: String): String {
        val b = StringBuilder(); var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) { b.append(if (s[i + 1] == 'n') '\n' else s[i + 1]); i += 2 } else { b.append(c); i++ }
        }
        return b.toString()
    }
    private fun z(z: Zone) = "${z.x};${z.y};${z.w};${z.h}"
    private fun zone(p: List<String>, i: Int) = Zone(p[i].toFloat(), p[i + 1].toFloat(), p[i + 2].toFloat(), p[i + 3].toFloat())

    fun ecrit(m: Modele): String = buildString {
        append("planche=1\n")
        append("nom=").append(esc(m.nom)).append('\n')
        append("fond=").append(esc(m.fond)).append('\n')
        append("ratio=").append(m.ratio).append('\n')
        append("legende=").append(if (m.legende) 1 else 0).append('\n')
        append("numeros=").append(if (m.numeros) 1 else 0).append('\n')
        append("titre=").append(esc(m.titre)).append('\n')
        m.cases.forEach { append("case=").append(z(it)).append('\n') }
        fun texte(cle: String, t: Texte) {
            append(cle).append('=').append(t.champ.name).append(';').append(z(t.zone)).append(';')
                .append(t.couleur).append(';').append(t.fond).append(';').append(esc(t.libre).replace(";", ","))
                .append('\n')
        }
        m.textes.forEach { texte("texte", it) }
        m.images.toSortedMap().forEach { (i, f) -> append("image=").append(i).append(';').append(f).append('\n') }
        // A sheet fitted to its pictures: the template's layout, to go back to (read by older versions as nothing).
        m.origine.forEach { append("origine=").append(z(it)).append('\n') }
        m.textesOrigine.forEach { texte("texteorigine", it) }
        // Background and patches (read by older versions as nothing).
        if (m.decor.isNotBlank()) append("decor=").append(esc(m.decor)).append('\n')
        m.ecussons.forEach { append("ecusson=").append(it.fichier).append(';').append(z(it.zone)).append(';')
            .append(if (it.sansContour) 1 else 0).append('\n') }
        if (m.corrigees) append("corrigees=1\n")
    }

    fun lit(id: String, texte: String): Modele? = runCatching {
        var m = Modele(id = id, nom = id)
        val cases = ArrayList<Zone>(); val textes = ArrayList<Texte>(); val images = HashMap<Int, String>()
        val origine = ArrayList<Zone>(); val textesOrigine = ArrayList<Texte>(); val ecussons = ArrayList<Ecusson>()
        fun texte(v: String): Texte {
            val p = v.split(';')
            return Texte(Champ.valueOf(p[0]), zone(p, 1), p[5].toInt(), p[6].toInt(), unesc(p.getOrElse(7) { "" }))
        }
        texte.lineSequence().forEach { l ->
            val k = l.substringBefore('=', ""); val v = l.substringAfter('=', "")
            when (k) {
                "nom" -> m = m.copy(nom = unesc(v))
                "fond" -> m = m.copy(fond = unesc(v))
                "ratio" -> m = m.copy(ratio = v.toFloatOrNull() ?: 1.414f)
                "legende" -> m = m.copy(legende = v == "1")
                "numeros" -> m = m.copy(numeros = v == "1")
                "titre" -> m = m.copy(titre = unesc(v))
                "case" -> runCatching { cases += zone(v.split(';'), 0) }
                "texte" -> runCatching { textes += texte(v) }
                "image" -> runCatching { images[v.substringBefore(';').toInt()] = v.substringAfter(';') }
                "origine" -> runCatching { origine += zone(v.split(';'), 0) }
                "texteorigine" -> runCatching { textesOrigine += texte(v) }
                "decor" -> m = m.copy(decor = unesc(v))
                "ecusson" -> runCatching { val p = v.split(';'); ecussons += Ecusson(p[0], zone(p, 1), p.getOrNull(5) == "1") }
                "corrigees" -> m = m.copy(corrigees = v == "1")
            }
        }
        m.copy(cases = cases, textes = textes, images = images, origine = origine, textesOrigine = textesOrigine,
            ecussons = ecussons)
    }.getOrNull()

    /** The sheets folder: templates ("*.planche"), their backgrounds and patches. */
    class Rangement(val dossier: File) {
        init { dossier.mkdirs() }
        fun modeles(): List<Modele> = (dossier.listFiles { f -> f.name.endsWith(".planche") } ?: emptyArray())
            .sortedBy { it.lastModified() }
            .mapNotNull { f -> lit(f.name.removeSuffix(".planche"), runCatching { f.readText() }.getOrDefault("")) }
        fun enregistre(m: Modele) { File(dossier, m.id + ".planche").writeText(ecrit(m)) }
        fun supprime(m: Modele) {
            File(dossier, m.id + ".planche").delete()
            if (m.fond.isNotBlank() && modeles().none { it.fond == m.fond }) File(dossier, m.fond).delete()
            nettoie()
        }
        fun fond(m: Modele): File? = m.fond.takeIf { it.isNotBlank() }?.let { File(dossier, it) }?.takeIf { it.isFile }
        fun nouvelId(): String = "p" + System.currentTimeMillis().toString(36)

        /** A picture of this folder by its name — only a plain name ([PlancheDecor.nomFichier]). */
        fun fichier(nom: String): File? = nom.takeIf { PlancheDecor.nomFichier(it) }?.let { File(dossier, it) }?.takeIf { it.isFile }
        /** The background picture chosen, when it is a file (not a built-in one). */
        fun decor(m: Modele): File? = if (PlancheDecor.integre(m.decor) != null) null else fichier(m.decor)
        /** A new file name for a background ("decor") or a patch ("ecusson"). */
        fun nouveauNom(genre: String, ext: String): String =
            genre + "_" + System.currentTimeMillis().toString(36) + (0..1295).random().toString(36) + "." + ext

        /**
         * Backgrounds and patches no sheet uses any more, deleted — not those
         * just written (a minute), whose sheet may not be saved yet.
         */
        fun nettoie() {
            val l = modeles()
            val utilises = l.flatMap { m -> m.ecussons.map { it.fichier } + m.decor }.toSet()
            (dossier.listFiles() ?: return).filter { f ->
                (f.name.startsWith("decor_") || f.name.startsWith("ecusson_")) && f.name !in utilises &&
                    System.currentTimeMillis() - f.lastModified() > 60_000L
            }.forEach { it.delete() }
        }
    }
}
