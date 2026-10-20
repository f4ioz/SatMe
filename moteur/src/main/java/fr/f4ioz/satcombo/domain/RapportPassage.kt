/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A pass's observation report: what was followed (times, angles, the
 * station), what was heard (the waterfall, the level, the elevation), what
 * came out of it (pictures, frames, decodes, the voice written down,
 * contacts), the files kept, and a quality summary. Gathered by the
 * platform (the PC reads its folders), written here as one HTML file whose
 * pictures are inside, and as a ZIP with the sound and the logs beside it.
 */
object RapportPassage {

    data class Point(val tMs: Long, val az: Double, val el: Double)
    data class Niveau(val tMs: Long, val v: Double)
    /** Something decoded or done at [tMs]: [genre] one of [GENRES]' keys. */
    data class Evenement(val tMs: Long, val genre: String, val texte: String, val details: String = "")
    data class Image(val tMs: Long, val legende: String, val type: String, val octets: ByteArray, val nom: String)
    data class Contact(val tMs: Long, val indicatif: String, val locator: String = "", val rstEnvoye: String = "", val rstRecu: String = "",
                       val mode: String = "", val nom: String = "")
    /** A file kept with the pass; [url] where the page finds it (null: named only). */
    data class Fichier(val nom: String, val taille: Long, val url: String? = null, val debutMs: Long = 0L, val dureeMs: Long = 0L)
    data class Materiel(val genre: String, val nom: String)

    data class Rapport(
        val id: String, val satellite: String, val catnum: Int, val debutMs: Long, val finMs: Long,
        val locator: String = "", val indicatif: String = "", val transpondeur: String = "",
        val points: List<Point> = emptyList(),
        val station: List<Materiel> = emptyList(),
        /** The planetary Kp nearest the pass (space weather, when its copies hold one). */
        val kp: Double? = null, val kpMs: Long? = null,
        val niveaux: List<Niveau> = emptyList(),
        /** "S" (the rig's S-meter) or "dB". */
        val niveauUnite: String = "dB",
        val niveauSource: String = "",
        val cascade: SpectrePassage.Bande? = null,
        val evenements: List<Evenement> = emptyList(),
        val images: List<Image> = emptyList(),
        val contacts: List<Contact> = emptyList(),
        val tramesTelemesure: Int = 0,
        val tramesTelemesureValides: Int = 0,
        val sons: List<Fichier> = emptyList(),
        val iq: List<Fichier> = emptyList(),
        val doppler: List<Fichier> = emptyList(),
        val reconstitue: Boolean = false,
        val faitMs: Long = 0L,
        val logiciel: String = "SatMe",
    ) {
        val tca: Point? get() = points.maxByOrNull { it.el }
        val elMax: Double get() = tca?.el ?: 0.0
    }

    /** What each kind of event is called, and its colour on the report. */
    val GENRES = linkedMapOf(
        "QSO" to ("Contact" to "#e0459a"), "SSTV" to ("SSTV" to "#f08a24"), "APRS" to ("APRS" to "#3f86f2"),
        "FT8" to ("FT8/FT4" to "#16a37f"), "CW" to ("CW" to "#9c6ade"), "TLM" to ("Télémesure" to "#7a8b99"),
        "PHONIE" to ("Phonie" to "#c9a400"))

    // ------------------------------------------------------------ the quality

    data class Qualite(val score: Int, val libelle: String,
                       /** Share of the pass with a signal (0..1), null when nothing measured it. */
                       val partSignal: Double?, val snrPicDb: Double?, val decodes: Int, val mesure: String)

    /** Above the noise by this much: a signal. */
    const val SEUIL_DB = 6.0

    fun qualite(r: Rapport): Qualite {
        val dans = { t: Long -> t in r.debutMs..r.finMs }
        var part: Double? = null; var snr: Double? = null; var mesure = ""
        val c = r.cascade
        val rap = c?.let { SpectrePassage.rapports(it).filter { p -> dans(p.first) } }.orEmpty()
        if (rap.size >= 3) {
            part = rap.count { it.second >= SEUIL_DB }.toDouble() / rap.size
            snr = rap.maxOf { it.second }
            mesure = if (c?.source == SpectrePassage.SOURCE_IQ) "cascade I/Q" else "spectres notés"
        } else {
            val n = r.niveaux.filter { dans(it.tMs) }
            if (n.size >= 3) {
                val tri = n.map { it.v }.sorted()
                val bruit = tri[tri.size / 10]
                // The rig's S-meter reads on its own scale (S units or a raw count): no dB from it,
                // a signal is a tenth of its span over the noise (at least one step).
                val s = r.niveauUnite == "S"
                val seuil = if (s) max(1.0, (tri[tri.size * 95 / 100] - bruit) / 10) else SEUIL_DB
                part = n.count { it.v - bruit >= seuil }.toDouble() / n.size
                snr = if (s) null else tri.last() - bruit
                mesure = r.niveauSource.ifBlank { if (s) "S-mètre" else "niveau" }
            }
        }
        val decodes = r.images.size + r.contacts.size + r.tramesTelemesure + r.evenements.count { it.genre !in setOf("QSO", "SSTV") }
        // Weighed over what is known: the signal's time, its peak, what came out of it.
        var somme = 0.0; var poids = 0.0
        part?.let { somme += 45 * it; poids += 45 }
        snr?.let { somme += 30 * min(1.0, max(0.0, it) / 30.0); poids += 30 }
        somme += 25 * min(1.0, decodes / 5.0); poids += 25
        val score = (100 * somme / poids).roundToInt().coerceIn(0, 100)
        val lib = when { score >= 75 -> "Excellent"; score >= 50 -> "Bon"; score >= 25 -> "Moyen"; else -> "Faible" }
        return Qualite(score, lib, part, snr, decodes, mesure)
    }

    // ------------------------------------------------------------ the HTML

    /**
     * Where the page finds what it does not hold: [barre] the buttons above
     * (the PC's page; null in a file); [lecteurs] true when the sound files
     * can be played from their [Fichier.url].
     */
    class Liens(val barre: String? = null, val lecteurs: Boolean = true)

    private fun heure(t: Long) = SimpleDateFormat("HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(t))
    private fun jour(t: Long) = SimpleDateFormat("dd/MM/yyyy", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(t))
    private fun h(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun f1(v: Double) = String.format(Locale.US, "%.1f", v)
    private fun taille(o: Long) = when { o >= 1 shl 30 -> f1(o / 1073741824.0) + " Go"; o >= 1 shl 20 -> f1(o / 1048576.0) + " Mo"; else -> "${max(1, o / 1024)} ko" }
    private fun duree(ms: Long) = (ms / 1000).let { "${it / 60} min ${(it % 60).toString().padStart(2, '0')}" }
    private fun b64(o: ByteArray) = Base64.getEncoder().encodeToString(o)
    private fun aPoint(r: Rapport, t: Long): Point? = r.points.minByOrNull { kotlin.math.abs(it.tMs - t) }

    /** The cascade's picture (PNG), [largeur] columns over the pass. */
    fun pngCascade(b: SpectrePassage.Bande, largeur: Int = min(900, max(1, b.lignes.size))): ByteArray = Png.encode(SpectrePassage.image(b, largeur))

    fun html(r: Rapport, liens: Liens = Liens(), cascadePng: ByteArray? = r.cascade?.takeIf { it.lignes.size >= 2 }?.let { pngCascade(it) }): String {
        val q = qualite(r)
        val aos = r.points.firstOrNull(); val los = r.points.lastOrNull(); val tca = r.tca
        return buildString {
            append("<!doctype html><html lang=\"fr\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
            append("<title>").append(h("Rapport ${r.satellite} ${jour(r.debutMs)}")).append("</title><style>").append(STYLE).append("</style></head><body>")
            liens.barre?.let { append(it) }
            append("<main>")
            // ---- header
            append("<header class=\"tete\"><div><h1>").append(h(r.satellite)).append("</h1><div class=\"sous\">")
            append(h(jour(r.debutMs))).append(" · ").append(heure(r.debutMs)).append(" → ").append(heure(r.finMs)).append(" UTC · ").append(duree(r.finMs - r.debutMs))
            if (r.catnum > 0) append(" · NORAD ").append(r.catnum)
            if (r.reconstitue) append(" · <span class=\"gris\">trajectoire prévue (passage reconstitué)</span>")
            append("</div></div>")
            append("<div class=\"note n-").append(q.libelle.lowercase()).append("\" title=\"Qualité du passage\"><b>").append(q.score).append("</b><span>").append(q.libelle).append("</span></div></header>")
            append("<section class=\"grille\">")
            carte("Passage") {
                ligne("AOS", aos?.let { heure(it.tMs) + " UTC · az " + it.az.roundToInt() + "°" } ?: heure(r.debutMs) + " UTC")
                ligne("TCA", tca?.let { heure(it.tMs) + " UTC · az " + it.az.roundToInt() + "° · él " + f1(it.el) + "°" } ?: "—")
                ligne("LOS", los?.let { heure(it.tMs) + " UTC · az " + it.az.roundToInt() + "°" } ?: heure(r.finMs) + " UTC")
                ligne("Élévation max", f1(r.elMax) + "°")
                if (r.transpondeur.isNotBlank()) ligne("Transpondeur", r.transpondeur)
            }
            carte("Station") {
                if (r.indicatif.isNotBlank() || r.locator.isNotBlank()) ligne("Opérateur", listOf(r.indicatif, r.locator).filter { it.isNotBlank() }.joinToString(" · "))
                if (r.station.isEmpty()) append("<p class=\"gris\">Montage non décrit (Station › Montage).</p>")
                r.station.forEach { ligne(it.genre, it.nom) }
                r.kp?.let { k -> ligne("Kp", f1(k) + (r.kpMs?.let { " (" + heure(it) + " UTC)" } ?: "") + " · " + if (k >= 5) "orage géomagnétique" else if (k >= 4) "agité" else "calme") }
            }
            carte("Qualité du passage") {
                ligne("Note", "${q.score}/100 · ${q.libelle}")
                ligne("Temps avec signal", q.partSignal?.let { (it * 100).roundToInt().toString() + " %" + (if (q.mesure.isNotBlank()) " (" + q.mesure + ")" else "") } ?: "non mesuré")
                ligne("Rapport S/B max", q.snrPicDb?.let { f1(it) + " dB" } ?: "non mesuré")
                ligne("Décodages", q.decodes.toString())
            }
            append("</section>")
            // ---- the timeline: waterfall, level, elevation, events
            append("<section><h2>Chronologie</h2>")
            append(chronologie(r, cascadePng))
            append("<div class=\"legende\">")
            GENRES.filter { (k, _) -> r.evenements.any { it.genre == k } || (k == "QSO" && r.contacts.isNotEmpty()) || (k == "SSTV" && r.images.isNotEmpty()) }
                .forEach { (_, v) -> append("<span><i style=\"background:${v.second}\"></i>").append(h(v.first)).append("</span>") }
            append("<span><i class=\"tr-el\"></i>élévation</span>")
            if (r.niveaux.isNotEmpty()) append("<span><i class=\"tr-niv\"></i>").append(h(r.niveauSource.ifBlank { "niveau" })).append("</span>")
            append("</div>")
            if (r.cascade == null || r.cascade.lignes.size < 2)
                append("<p class=\"gris\">Pas de cascade : ni enregistrement I/Q, ni « Journal détaillé » pendant ce passage.</p>")
            else append("<p class=\"gris\">Cascade ").append(if (r.cascade.source == SpectrePassage.SOURCE_IQ) "calculée de l'enregistrement I/Q" else "des spectres notés pendant le passage")
                .append(", ").append(f1(r.cascade.largeurHz / 1000)).append(" kHz autour de la fréquence écoutée (Doppler suivi).</p>")
            append("</section>")
            // ---- what came out
            append("<section><h2>Décodé pendant le passage</h2>")
            if (r.images.isNotEmpty()) {
                append("<h3>Images SSTV</h3><div class=\"images\">")
                r.images.forEach { i ->
                    append("<figure><img alt=\"").append(h(i.legende)).append("\" src=\"data:").append(i.type).append(";base64,").append(b64(i.octets)).append("\">")
                    append("<figcaption>").append(heure(i.tMs)).append(" · ").append(h(i.legende)).append("</figcaption></figure>")
                }
                append("</div>")
            }
            fun table(genre: String, titre: String) {
                val l = r.evenements.filter { it.genre == genre }
                if (l.isEmpty()) return
                append("<h3>").append(h(titre)).append(" <span class=\"gris\">(").append(l.size).append(")</span></h3><table><tbody>")
                l.take(200).forEach { e ->
                    append("<tr><td class=\"t\">").append(heure(e.tMs)).append("</td><td>").append(h(e.texte)).append("</td><td class=\"gris\">").append(h(e.details)).append("</td></tr>")
                }
                if (l.size > 200) append("<tr><td></td><td class=\"gris\">… ").append(l.size - 200).append(" de plus</td><td></td></tr>")
                append("</tbody></table>")
            }
            table("APRS", "Trames APRS"); table("FT8", "FT8 / FT4"); table("CW", "CW")
            if (r.tramesTelemesure > 0) append("<h3>Télémesure</h3><p>").append(r.tramesTelemesure).append(" trame").append(if (r.tramesTelemesure > 1) "s" else "")
                .append(", dont ").append(r.tramesTelemesureValides).append(" valide").append(if (r.tramesTelemesureValides > 1) "s" else "").append(".</p>")
            table("PHONIE", "Phonie (transcription, extrait)")
            if (r.images.isEmpty() && r.tramesTelemesure == 0 && r.evenements.none { it.genre != "QSO" }) append("<p class=\"gris\">Rien de décodé.</p>")
            append("</section>")
            // ---- contacts
            append("<section><h2>Contacts</h2>")
            if (r.contacts.isEmpty()) append("<p class=\"gris\">Aucun contact noté au carnet pendant ce passage.</p>")
            else {
                append("<table><thead><tr><th>UTC</th><th>Indicatif</th><th>Locator</th><th>RST env./reçu</th><th>Mode</th><th>Élévation</th></tr></thead><tbody>")
                r.contacts.forEach { c ->
                    append("<tr><td class=\"t\">").append(heure(c.tMs)).append("</td><td><b>").append(h(c.indicatif)).append("</b>")
                    if (c.nom.isNotBlank()) append(" <span class=\"gris\">").append(h(c.nom)).append("</span>")
                    append("</td><td>").append(h(c.locator)).append("</td><td>").append(h(listOf(c.rstEnvoye, c.rstRecu).joinToString(" / ")))
                    append("</td><td>").append(h(c.mode)).append("</td><td>").append(aPoint(r, c.tMs)?.let { f1(it.el) + "°" } ?: "").append("</td></tr>")
                }
                append("</tbody></table>")
            }
            append("</section>")
            // ---- files
            append("<section><h2>Enregistrements et fichiers</h2>")
            if (r.sons.isEmpty() && r.iq.isEmpty() && r.doppler.isEmpty()) append("<p class=\"gris\">Aucun fichier gardé pour ce passage.</p>")
            r.sons.forEach { s ->
                append("<div class=\"fichier\"><span>♪ ").append(h(s.nom)).append(" <span class=\"gris\">").append(if (s.debutMs > 0) heure(s.debutMs) + " UTC · " else "")
                    .append(duree(s.dureeMs)).append("</span></span>")
                if (liens.lecteurs && s.url != null) append("<audio controls preload=\"none\" src=\"").append(h(s.url)).append("\"></audio>")
                else if (s.url != null) append("<a href=\"").append(h(s.url)).append("\">").append(h(s.nom)).append("</a>")
                append("</div>")
            }
            r.iq.forEach { f -> fichier("I/Q (SigMF)", f) }
            r.doppler.forEach { f -> fichier("Doppler / CAT (CSV)", f) }
            append("</section>")
            append("<footer>Rapport fait par ").append(h(r.logiciel)).append(if (r.faitMs > 0) " le " + jour(r.faitMs) + " à " + heure(r.faitMs) + " UTC" else "")
                .append(". Réception seulement : rien n'a été émis pour ce rapport.</footer>")
            append("</main></body></html>")
        }
    }

    private fun StringBuilder.carte(titre: String, corps: StringBuilder.() -> Unit) {
        append("<div class=\"carte\"><h2>").append(h(titre)).append("</h2><dl>"); corps(); append("</dl></div>")
    }
    private fun StringBuilder.ligne(k: String, v: String) { append("<dt>").append(h(k)).append("</dt><dd>").append(h(v)).append("</dd>") }
    private fun StringBuilder.fichier(genre: String, f: Fichier) {
        append("<div class=\"fichier\"><span>").append(h(genre)).append(" : ")
        if (f.url != null) append("<a href=\"").append(h(f.url)).append("\" download>").append(h(f.nom)).append("</a>") else append(h(f.nom))
        append(" <span class=\"gris\">").append(taille(f.taille)).append("</span></span></div>")
    }

    /** One SVG: the waterfall on top, the level and the elevation below, the events across both, on one time axis. */
    fun chronologie(r: Rapport, cascadePng: ByteArray?): String {
        val pres = { t: Long -> t >= r.debutMs - 300_000L && t <= r.finMs + 300_000L }
        val evts = r.evenements.filter { pres(it.tMs) } + r.images.filter { pres(it.tMs) }.map { Evenement(it.tMs, "SSTV", it.legende) } +
            r.contacts.filter { pres(it.tMs) }.map { Evenement(it.tMs, "QSO", it.indicatif, listOf(it.locator, it.mode).filter { s -> s.isNotBlank() }.joinToString(" · ")) }
        val c = r.cascade?.takeIf { it.lignes.size >= 2 && cascadePng != null }
        val t0 = minOf(r.debutMs, evts.minOfOrNull { it.tMs } ?: r.debutMs, c?.debutMs ?: r.debutMs)
        val t1 = max(t0 + 1000, maxOf(r.finMs, evts.maxOfOrNull { it.tMs } ?: r.finMs, c?.finMs ?: r.finMs))
        val W = 1000; val G = 56; val D = 44; val L = W - G - D
        val hC = if (c != null) 170 else 0
        val y0 = if (c != null) hC + 14 else 8
        val hN = 150
        val H = y0 + hN + 34
        fun x(t: Long) = G + L * (t - t0).toDouble() / (t1 - t0)
        fun n(v: Double) = String.format(Locale.US, "%.1f", v)
        return buildString {
            append("<svg class=\"chrono\" viewBox=\"0 0 $W $H\" role=\"img\" aria-label=\"Chronologie du passage\">")
            if (c != null) {
                append("<image x=\"${n(x(c.debutMs))}\" y=\"0\" width=\"${n(max(1.0, x(c.finMs) - x(c.debutMs)))}\" height=\"$hC\" preserveAspectRatio=\"none\" href=\"data:image/png;base64,")
                    .append(b64(cascadePng!!)).append("\"/>")
                append("<rect x=\"$G\" y=\"0\" width=\"$L\" height=\"$hC\" class=\"cadre\"/>")
                append("<text x=\"${G - 6}\" y=\"10\" class=\"ax\" text-anchor=\"end\">+${n(c.largeurHz / 2000)}k</text>")
                append("<text x=\"${G - 6}\" y=\"${hC / 2 + 4}\" class=\"ax\" text-anchor=\"end\">0</text>")
                append("<text x=\"${G - 6}\" y=\"$hC\" class=\"ax\" text-anchor=\"end\">−${n(c.largeurHz / 2000)}k</text>")
            }
            // The panel: elevation 0..90 (right), the level (left).
            append("<rect x=\"$G\" y=\"$y0\" width=\"$L\" height=\"$hN\" class=\"cadre\"/>")
            for (e in listOf(30, 60)) {
                val y = y0 + hN - hN * e / 90.0
                append("<line x1=\"$G\" x2=\"${G + L}\" y1=\"${n(y)}\" y2=\"${n(y)}\" class=\"grille\"/><text x=\"${G + L + 6}\" y=\"${n(y + 4)}\" class=\"ax\">$e°</text>")
            }
            val pts = r.points.filter { it.tMs in t0..t1 }
            if (pts.size >= 2) append("<polyline class=\"el\" points=\"").append(pts.joinToString(" ") { n(x(it.tMs)) + "," + n(y0 + hN - hN * max(0.0, it.el) / 90.0) }).append("\"/>")
            val niv = r.niveaux.filter { it.tMs in t0..t1 }
            if (niv.size >= 2) {
                val lo = niv.minOf { it.v }; val hi = max(lo + 1, niv.maxOf { it.v })
                fun yv(v: Double) = y0 + hN - 4 - (hN - 8) * (v - lo) / (hi - lo)
                // Gaps over 10 s break the line.
                val morceaux = ArrayList<MutableList<Niveau>>()
                niv.forEach { p -> if (morceaux.isEmpty() || p.tMs - morceaux.last().last().tMs > 10_000L) morceaux += mutableListOf(p) else morceaux.last() += p }
                morceaux.filter { it.size >= 2 }.forEach { m -> append("<polyline class=\"niv\" points=\"").append(m.joinToString(" ") { n(x(it.tMs)) + "," + n(yv(it.v)) }).append("\"/>") }
                val u = if (r.niveauUnite == "S") "" else " dB"
                append("<text x=\"${G - 6}\" y=\"${n(yv(hi) + 4)}\" class=\"ax\" text-anchor=\"end\">${n(hi)}$u</text>")
                append("<text x=\"${G - 6}\" y=\"${n(yv(lo))}\" class=\"ax\" text-anchor=\"end\">${n(lo)}$u</text>")
            } else append("<text x=\"${G + 8}\" y=\"${y0 + 16}\" class=\"ax\">pas de mesure de niveau</text>")
            // AOS, TCA, LOS.
            r.tca?.let { tc -> append("<line x1=\"${n(x(tc.tMs))}\" x2=\"${n(x(tc.tMs))}\" y1=\"$y0\" y2=\"${y0 + hN}\" class=\"tca\"/><text x=\"${n(x(tc.tMs) + 3)}\" y=\"${y0 + 12}\" class=\"ax\">TCA</text>") }
            // The events.
            val yHaut = if (c != null) 0 else y0
            for (e in evts.sortedBy { it.tMs }) {
                val col = GENRES[e.genre]?.second ?: "#888"
                val xx = n(x(e.tMs))
                append("<g><title>").append(h(heure(e.tMs) + " UTC · " + (GENRES[e.genre]?.first ?: e.genre) + " · " + e.texte + if (e.details.isNotBlank()) " · " + e.details else "")).append("</title>")
                append("<line x1=\"$xx\" x2=\"$xx\" y1=\"$yHaut\" y2=\"${y0 + hN}\" stroke=\"$col\" class=\"ev\"/>")
                append("<circle cx=\"$xx\" cy=\"${y0 + hN}\" r=\"4.5\" fill=\"$col\"/></g>")
            }
            // The time axis.
            val pas = listOf(5_000L, 10_000L, 30_000L, 60_000L, 120_000L, 300_000L, 600_000L).firstOrNull { (t1 - t0) / it <= 10 } ?: 900_000L
            var t = (t0 / pas + 1) * pas
            while (t < t1) {
                val xx = n(x(t))
                append("<line x1=\"$xx\" x2=\"$xx\" y1=\"${y0 + hN}\" y2=\"${y0 + hN + 5}\" class=\"axe\"/><text x=\"$xx\" y=\"${y0 + hN + 18}\" class=\"ax\" text-anchor=\"middle\">")
                    .append(if (pas < 60_000L) heure(t) else heure(t).substring(0, 5)).append("</text>")
                t += pas
            }
            append("<text x=\"${G + L}\" y=\"${y0 + hN + 32}\" class=\"ax\" text-anchor=\"end\">UTC</text>")
            append("</svg>")
        }
    }

    // ------------------------------------------------------------ the bundle

    /**
     * The ZIP: `rapport.html` (pictures inside, sounds and logs beside it,
     * relative links), then [fichiers] as (path in the ZIP, file).
     */
    fun zip(html: String, fichiers: List<Pair<String, File>>, o: OutputStream, extras: List<Pair<String, ByteArray>> = emptyList()) {
        ZipOutputStream(o).use { z ->
            z.putNextEntry(ZipEntry("rapport.html")); z.write(html.toByteArray(Charsets.UTF_8)); z.closeEntry()
            for ((nom, b) in extras) { z.putNextEntry(ZipEntry(nom)); z.write(b); z.closeEntry() }
            val vus = HashSet<String>()
            for ((chemin, f) in fichiers) {
                if (!f.isFile || !vus.add(chemin)) continue
                z.putNextEntry(ZipEntry(chemin).apply { time = f.lastModified() })
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
    }

    /** A file name for the report: satellite and start, UTC. */
    fun nomFichier(r: Rapport, ext: String): String = "SatMe_rapport_" + r.satellite.replace(Regex("[^A-Za-z0-9-]"), "-") + "_" +
        SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(r.debutMs)) + "Z." + ext

    private const val STYLE = """
:root { --fond:#f6f7f9; --carte:#ffffff; --texte:#1b2430; --gris:#6b7684; --bord:#d9dee5; --accent:#0a7ea4; --el:#0a7ea4; --niv:#d35400; --axe:#6b7684; }
@media (prefers-color-scheme: dark) { :root:not([data-theme="light"]) { --fond:#0f141a; --carte:#18202a; --texte:#e5ebf1; --gris:#93a0ae; --bord:#2a3542; --accent:#4cc3e6; --el:#4cc3e6; --niv:#ff9a4d; --axe:#93a0ae; } }
:root[data-theme="dark"] { --fond:#0f141a; --carte:#18202a; --texte:#e5ebf1; --gris:#93a0ae; --bord:#2a3542; --accent:#4cc3e6; --el:#4cc3e6; --niv:#ff9a4d; --axe:#93a0ae; }
* { box-sizing:border-box; }
body { margin:0; background:var(--fond); color:var(--texte); font:14px/1.45 system-ui, -apple-system, "Segoe UI", sans-serif; }
main { max-width:1100px; margin:0 auto; padding:16px; }
h1 { margin:0; font-size:26px; } h2 { font-size:15px; margin:0 0 8px; text-transform:uppercase; letter-spacing:.04em; color:var(--gris); }
h3 { font-size:14px; margin:14px 0 6px; }
section { background:var(--carte); border:1px solid var(--bord); border-radius:10px; padding:14px; margin:12px 0; }
section.grille { display:grid; grid-template-columns:repeat(auto-fit, minmax(260px, 1fr)); gap:12px; background:none; border:0; padding:0; }
.carte { background:var(--carte); border:1px solid var(--bord); border-radius:10px; padding:12px 14px; }
dl { display:grid; grid-template-columns:auto 1fr; gap:3px 12px; margin:0; } dt { color:var(--gris); } dd { margin:0; }
.tete { display:flex; justify-content:space-between; align-items:center; gap:12px; flex-wrap:wrap; }
.sous { color:var(--gris); margin-top:4px; }
.gris { color:var(--gris); }
.note { display:flex; flex-direction:column; align-items:center; border-radius:12px; padding:8px 18px; color:#fff; min-width:110px; }
.note b { font-size:30px; line-height:1; } .note span { font-size:12px; text-transform:uppercase; letter-spacing:.06em; }
.n-excellent { background:#138a5b; } .n-bon { background:#2f7fd1; } .n-moyen { background:#b7791f; } .n-faible { background:#8a4b4b; }
svg.chrono { width:100%; height:auto; display:block; }
svg .cadre { fill:none; stroke:var(--bord); } svg .grille { stroke:var(--bord); stroke-dasharray:3 4; }
svg .ax { fill:var(--axe); font-size:11px; } svg .axe { stroke:var(--axe); }
svg .el { fill:none; stroke:var(--el); stroke-width:2; } svg .niv { fill:none; stroke:var(--niv); stroke-width:1.4; }
svg .tca { stroke:var(--gris); stroke-dasharray:2 3; } svg .ev { stroke-width:1.2; opacity:.85; }
.legende { display:flex; flex-wrap:wrap; gap:12px; margin-top:6px; color:var(--gris); font-size:12px; }
.legende i { display:inline-block; width:10px; height:10px; border-radius:50%; margin-right:5px; vertical-align:-1px; }
.legende i.tr-el { border-radius:0; height:3px; background:var(--el); } .legende i.tr-niv { border-radius:0; height:3px; background:var(--niv); }
.images { display:flex; flex-wrap:wrap; gap:10px; }
figure { margin:0; width:240px; } figure img { width:100%; border-radius:6px; border:1px solid var(--bord); display:block; } figcaption { font-size:12px; color:var(--gris); }
table { width:100%; border-collapse:collapse; } td, th { text-align:left; padding:3px 8px 3px 0; border-bottom:1px solid var(--bord); vertical-align:top; }
th { color:var(--gris); font-weight:500; } td.t { white-space:nowrap; font-variant-numeric:tabular-nums; color:var(--gris); }
.fichier { display:flex; flex-wrap:wrap; align-items:center; gap:10px; padding:4px 0; } audio { height:32px; max-width:100%; }
a { color:var(--accent); }
footer { color:var(--gris); font-size:12px; margin:16px 0; }
.barre { position:sticky; top:0; z-index:2; display:flex; gap:8px; align-items:center; flex-wrap:wrap; padding:8px 16px; background:var(--carte); border-bottom:1px solid var(--bord); }
.barre button, .barre a.b { font:inherit; padding:6px 12px; border-radius:8px; border:1px solid var(--bord); background:var(--fond); color:var(--texte); cursor:pointer; text-decoration:none; }
.barre .msg { color:var(--gris); }
@media print { .barre { display:none; } section { break-inside:avoid; } body { background:#fff; } }
@media (max-width:600px) { main { padding:10px 16px; } .note { min-width:80px; } }
"""
}
