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
        val images: Map<Int, String> = emptyMap()
    )

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

    /** The line under a picture: "PD 120 | 2025/05/31 20:00 UTC | F4IOZ". */
    fun legende(s: SstvMeta.SstvShot, indicatif: String): String =
        listOf(s.mode, if (s.timeMs > 0) minute.format(Date(s.timeMs)) + " UTC" else "",
            indicatif.ifBlank { s.callsign }).filter { it.isNotBlank() }.joinToString(" | ")

    /**
     * Boxes filled in order of reception with the pictures given, one per box,
     * the partial ones last (a series is sent again and again: a complete copy
     * of a slide is worth more).
     */
    fun remplitParReception(nbCases: Int, images: List<SstvMeta.SstvShot>): Map<Int, String> {
        val tri = images.sortedWith(compareBy({ !it.complete }, { it.timeMs }))
            .take(nbCases).sortedBy { it.timeMs }
        return tri.mapIndexed { i, s -> i to s.fileName }.toMap()
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
        m.textes.forEach { t ->
            append("texte=").append(t.champ.name).append(';').append(z(t.zone)).append(';')
                .append(t.couleur).append(';').append(t.fond).append(';').append(esc(t.libre).replace(";", ","))
                .append('\n')
        }
        m.images.toSortedMap().forEach { (i, f) -> append("image=").append(i).append(';').append(f).append('\n') }
    }

    fun lit(id: String, texte: String): Modele? = runCatching {
        var m = Modele(id = id, nom = id)
        val cases = ArrayList<Zone>(); val textes = ArrayList<Texte>(); val images = HashMap<Int, String>()
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
                "texte" -> runCatching {
                    val p = v.split(';')
                    textes += Texte(Champ.valueOf(p[0]), zone(p, 1), p[5].toInt(), p[6].toInt(),
                        unesc(p.getOrElse(7) { "" }))
                }
                "image" -> runCatching { images[v.substringBefore(';').toInt()] = v.substringAfter(';') }
            }
        }
        m.copy(cases = cases, textes = textes, images = images)
    }.getOrNull()

    /** The sheets folder: templates ("*.planche") and their backgrounds. */
    class Rangement(val dossier: File) {
        init { dossier.mkdirs() }
        fun modeles(): List<Modele> = (dossier.listFiles { f -> f.name.endsWith(".planche") } ?: emptyArray())
            .sortedBy { it.lastModified() }
            .mapNotNull { f -> lit(f.name.removeSuffix(".planche"), runCatching { f.readText() }.getOrDefault("")) }
        fun enregistre(m: Modele) { File(dossier, m.id + ".planche").writeText(ecrit(m)) }
        fun supprime(m: Modele) {
            File(dossier, m.id + ".planche").delete()
            if (m.fond.isNotBlank() && modeles().none { it.fond == m.fond }) File(dossier, m.fond).delete()
        }
        fun fond(m: Modele): File? = m.fond.takeIf { it.isNotBlank() }?.let { File(dossier, it) }?.takeIf { it.isFile }
        fun nouvelId(): String = "p" + System.currentTimeMillis().toString(36)
    }
}
