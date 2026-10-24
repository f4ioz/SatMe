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
import fr.f4ioz.satcombo.domain.CampagneSstv
import fr.f4ioz.satcombo.i18n.I18n
import fr.f4ioz.satcombo.i18n.Lang
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.i18n.tf
import fr.f4ioz.satcombo.station.Montage
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * An SSTV event's report: what the event was (series, dates, the station),
 * the sheet, the slides 1..12 (received or not, the best mark, how many
 * copies), each pass of the satellite during it (its time, its peak, where
 * it rose and set, the slides the timetable expected, those received, and
 * what became of it), drawn as sky plots and elevation profiles with the
 * pictures on their track, and the station's receive chain if asked.
 *
 * Gathered by the platform (the PC or the phone: pictures, passes,
 * positions, the sheet drawn), built here ([construit]) and written as one
 * HTML file ([html]): its pictures inside, its charts inline SVG, no font,
 * script or picture fetched — it opens anywhere, offline, and prints to PDF
 * from the browser.
 *
 * Receive only: nothing is sent to make it.
 */
object RapportEvenement {

    data class Station(val indicatif: String, val locator: String, val nom: String = "")

    /** Where the satellite was: az, el in degrees. */
    data class Point(val tMs: Long, val az: Double, val el: Double)

    /** A picture of the event: its slide, its mark, where the satellite was while it came. */
    data class Image(
        val nom: String, val tMs: Long, val debutMs: Long, val diapo: Int, val total: Int,
        val origine: ClassementSstv.Origine, val note: Int, val complete: Boolean,
        /** The satellite from the picture's start to its end (empty: unknown). */
        val trace: List<Point> = emptyList(),
        /** AOS of the pass it came on, 0 none. */
        val passageMs: Long = 0L,
    )

    /** What became of a pass. */
    enum class Statut { RECU, RECU_BAS, ATTENDU_RIEN, BAS, A_VENIR }

    data class Passage(
        val aosMs: Long, val losMs: Long, val elMax: Double, val azAos: Double, val azLos: Double,
        val trace: List<Point>,
        /** Slides the timetable expected whole above the minimum elevation, and those cut. */
        val prevues: List<Int>, val coupees: List<Int>,
        /** The slides' slots on air during the pass (number, start, end), for the profile. */
        val creneaux: List<HoraireDiapos.Prevue>,
        val images: List<Image>,
        val statut: Statut,
        /** The autopilot's word on it (« done », « set aside: … »), "" none. */
        val pilote: String = "",
        val incertitudeS: Double = 0.0,
    ) {
        val recues: List<Int> get() = images.filter { it.diapo > 0 }.map { it.diapo }.distinct().sorted()
    }

    data class Diapo(
        val n: Int, val origine: ClassementSstv.Origine,
        /** The best picture of the slide, null not received. */
        val meilleure: String?, val note: Int, val copies: Int,
        /** Passes it came on. */
        val passages: Int,
        /** Passes gone by that should have sent it whole (for a slide not received). */
        val attendue: List<Passage> = emptyList(),
    ) {
        val recue: Boolean get() = meilleure != null
    }

    /** How the passes' geometry was had: the elements' epoch, worked out again (or not at all). */
    data class Geometrie(val recalculee: Boolean, val epoqueMs: Long? = null)

    /** One piece of the receive chain: its kind (« Antennes »), its name. */
    data class Etape(val genre: String, val nom: String)

    data class Stats(
        val passages: Int, val passagesHauts: Int, val passagesRecus: Int, val passagesRates: Int, val passagesAVenir: Int,
        val images: Int, val completes: Int, val horsPassage: Int,
        val diapos: Int, val diaposEstimees: Int, val noteMoyenne: Int, val noteMax: Int,
        val premiereMs: Long, val derniereMs: Long,
    )

    data class Rapport(
        val evenement: EvenementSstv.Evenement,
        val station: Station,
        val faitMs: Long,
        val elMin: Double,
        val diapos: List<Diapo>,
        val passages: List<Passage>,
        val images: List<Image>,
        val stats: Stats,
        val horaire: HoraireDiapos.Modele?,
        val geometrie: Geometrie,
        /** The receive chain, in columns (antennas, … the computer); null not asked. */
        val chaine: List<List<Etape>>? = null,
        /** The chain's name (the plan's), and what the autopilot set up for each pass. */
        val nomChaine: String = "",
        val pilote: List<String> = emptyList(),
        /** The sheet drawn (JPEG or PNG), its name. */
        val planche: ByteArray? = null,
        val nomPlanche: String = "",
        /** Small pictures (JPEG) by name: each slide's best. */
        val vignettes: Map<String, ByteArray> = emptyMap(),
        val logiciel: String = "SatMe",
    )

    // ------------------------------------------------------------ building

    /** A pass's track every [pasMs] from AOS to LOS: by [positions] when given, else spread over the pass's own samples. */
    private fun trace(p: SatPass, positions: ((List<Long>) -> List<Pair<Double, Double>>?)?, pasMs: Long = 15_000L): List<Point> {
        val ts = ArrayList<Long>()
        var t = p.aosEpochMs
        while (t < p.losEpochMs) { ts += t; t += pasMs }
        ts += p.losEpochMs
        positions?.let { f -> runCatching { f(ts) }.getOrNull()?.takeIf { it.size == ts.size }?.let { l -> return ts.zip(l).map { (t, a) -> Point(t, a.first, a.second) } } }
        val tr = p.track
        if (tr.isEmpty()) return listOf(Point(p.aosEpochMs, p.aosAzimuthDeg, 0.0), Point(p.losEpochMs, p.losAzimuthDeg, 0.0))
        val d = (p.losEpochMs - p.aosEpochMs).toDouble()
        return tr.mapIndexed { i, (az, el) -> Point(p.aosEpochMs + (d * (i + 0.5) / tr.size).toLong(), az, el) }
    }

    /** Where the satellite was between [de] and [a], on [tr] (a few points). */
    private fun entre(tr: List<Point>, de: Long, a: Long): List<Point> {
        if (tr.size < 2 || a <= de) return emptyList()
        fun en(t: Long): Point {
            val i = tr.indexOfFirst { it.tMs >= t }
            if (i <= 0) return tr.first().copy(tMs = t)
            val p = tr[i - 1]; val q = tr[i]
            val k = ((t - p.tMs).toDouble() / (q.tMs - p.tMs).coerceAtLeast(1)).coerceIn(0.0, 1.0)
            // Azimuth the short way round (a pass over the north crosses 0°).
            var dz = q.az - p.az; if (dz > 180) dz -= 360; if (dz < -180) dz += 360
            return Point(t, (p.az + dz * k + 360) % 360, p.el + (q.el - p.el) * k)
        }
        val d0 = de.coerceIn(tr.first().tMs, tr.last().tMs); val a0 = a.coerceIn(tr.first().tMs, tr.last().tMs)
        if (a0 <= d0) return emptyList()
        return (0..4).map { en(d0 + (a0 - d0) * it / 4) }
    }

    /**
     * The report of [p] (the event's pictures sorted, numbered with the
     * timetable's estimates): [passes] the satellite's during the event
     * (from 0°), [positions] where it was at given times (az, el; null: the
     * passes' own samples), [traces] the autopilot's history.
     */
    fun construit(
        evenement: EvenementSstv.Evenement,
        p: SerieSstv.Prevision,
        passes: List<SatPass>,
        station: Station,
        elMin: Double,
        maintenant: Long,
        positions: ((List<Long>) -> List<Pair<Double, Double>>?)? = null,
        geometrie: Geometrie = Geometrie(positions != null),
        traces: List<CampagneSstv.Trace> = emptyList(),
        chaine: List<List<Etape>>? = null,
        nomChaine: String = "",
        pilote: List<String> = emptyList(),
        planche: ByteArray? = null,
        nomPlanche: String = "",
        vignette: (String) -> ByteArray? = { null },
        analyses: Map<String, ClassementSstv.Analyse> = emptyMap(),
    ): Rapport {
        val nb = evenement.nb
        val m = p.modele
        val groupeDe = HashMap<String, ClassementSstv.Groupe>()
        for (g in p.groupes) for (s in g.images) groupeDe[s.fileName] = g
        val ps = passes.filter { it.losEpochMs > evenement.debutMs - 3_600_000L && it.aosEpochMs < evenement.finMs + 3_600_000L }
            .distinctBy { it.aosEpochMs / 60_000L }.sortedBy { it.aosEpochMs }
        val traces2 = ps.associateWith { trace(it, positions) }
        // Each picture: its pass, where the satellite was from its start to its end.
        val shots = p.groupes.flatMap { it.images }.distinctBy { it.fileName }.sortedBy { it.timeMs }
        val images = shots.map { s ->
            val g = groupeDe[s.fileName]
            val debut = ClassementSstv.debut(s, analyses[s.fileName])
            val pass = ps.firstOrNull { s.timeMs in (it.aosEpochMs - 60_000L)..(it.losEpochMs + 120_000L) }
            Image(s.fileName, s.timeMs, debut, g?.diapo ?: 0, g?.total ?: nb, g?.origine ?: ClassementSstv.Origine.AUCUNE,
                g?.notes?.get(s.fileName) ?: -1, s.complete, pass?.let { entre(traces2.getValue(it), debut, s.timeMs) }.orEmpty(), pass?.aosEpochMs ?: 0L)
        }
        val passages = ps.map { sp ->
            // The timetable as it stood then: the stretch the pass falls in (the loop restarted on board since, maybe).
            val hp = m?.let { HoraireDiapos.passage(HoraireDiapos.a(it, sp.aosEpochMs), sp, elMin) }
            val im = images.filter { it.passageMs == sp.aosEpochMs }
            val statut = when {
                im.isNotEmpty() -> if (sp.maxElevationDeg >= elMin) Statut.RECU else Statut.RECU_BAS
                sp.losEpochMs > maintenant -> Statut.A_VENIR
                sp.maxElevationDeg >= elMin -> Statut.ATTENDU_RIEN
                else -> Statut.BAS
            }
            val tr = traces.lastOrNull { it.catnum == sp.catalogNumber && kotlin.math.abs(it.aosMs - sp.aosEpochMs) <= CampagneSstv.TOLERANCE_MS }
            Passage(sp.aosEpochMs, sp.losEpochMs, sp.maxElevationDeg, sp.aosAzimuthDeg, sp.losAzimuthDeg, traces2.getValue(sp),
                hp?.entieres.orEmpty(), hp?.prevues?.filter { !it.entiere }?.map { it.diapo }.orEmpty(), hp?.prevues.orEmpty(), im, statut,
                tr?.let { statutPilote(it) }.orEmpty(), hp?.incertitudeS ?: 0.0)
        }
        // The slides: the best of each ([Planche.ordreParDiapo], its places kept), what should have brought the missing ones.
        val ordre = Planche.ordreParDiapo(p.groupes, nb, vides = true)
        val diapos = (1..nb).map { n ->
            val pd = ordre.getOrNull(n - 1)?.takeIf { it.diapo == n }
            val grs = p.groupes.filter { it.diapo == n && it.origine != ClassementSstv.Origine.AUCUNE }
            val noms = grs.flatMap { g -> g.images.map { it.fileName } }.toSet()
            if (pd == null) Diapo(n, ClassementSstv.Origine.AUCUNE, null, -1, 0, 0,
                passages.filter { it.losMs <= maintenant && n in it.prevues })
            else Diapo(n, pd.origine, pd.fichier, pd.note, noms.size, images.filter { it.nom in noms && it.passageMs > 0 }.map { it.passageMs }.distinct().size)
        }
        val notes = images.map { it.note }.filter { it >= 0 }
        val stats = Stats(
            passages = passages.size, passagesHauts = passages.count { it.elMax >= elMin },
            passagesRecus = passages.count { it.images.isNotEmpty() }, passagesRates = passages.count { it.statut == Statut.ATTENDU_RIEN },
            passagesAVenir = passages.count { it.statut == Statut.A_VENIR },
            images = images.size, completes = images.count { it.complete }, horsPassage = images.count { it.passageMs == 0L },
            diapos = diapos.count { it.recue }, diaposEstimees = diapos.count { it.recue && it.origine == ClassementSstv.Origine.ESTIME },
            noteMoyenne = if (notes.isEmpty()) -1 else notes.average().roundToInt(), noteMax = notes.maxOrNull() ?: -1,
            premiereMs = images.minOfOrNull { it.tMs } ?: 0L, derniereMs = images.maxOfOrNull { it.tMs } ?: 0L)
        val vignettes = diapos.mapNotNull { d -> d.meilleure?.let { n -> runCatching { vignette(n) }.getOrNull()?.let { n to it } } }.toMap()
        return Rapport(evenement, station, maintenant, elMin, diapos, passages, images, stats, m, geometrie, chaine, nomChaine, pilote,
            planche, nomPlanche, vignettes)
    }

    /** The autopilot's word on a pass. */
    fun statutPilote(tr: CampagneSstv.Trace): String = t("rev_pilote_" + tr.statut.name.lowercase()) +
        (if (tr.raison.isNotBlank()) " : " + tr.raison.take(80) else "")

    /** The chain in columns, as the montage draws it (antennas on the left … the computer, the phone). */
    fun etapes(c: Montage.Chaine): List<List<Etape>> = c.elements.mapNotNull { e -> Montage.modele(e)?.let { it.genre to e } }
        .filter { (g, _) -> g != Montage.Genre.BOITE }
        .groupBy { it.first.colonne }.toSortedMap().values.map { l -> l.map { (g, e) -> Etape(g.nom, Montage.nomCourt(e)) } }

    /** What the autopilot sets up for each pass of [c]: the rig, the sound, the mast, the recording. */
    fun pilote(c: CampagneSstv.Campagne): List<String> {
        val h = c.chaine
        return listOfNotNull(
            if (h.profil.isNotBlank()) tf("rev_pilote_profil", h.profil) else null,
            if (h.poste) t("rev_pilote_poste") else null,
            when (h.source) { CampagneSstv.POSTE -> t("rev_pilote_son_poste"); CampagneSstv.SDR -> t("rev_pilote_son_sdr"); "" -> null; else -> tf("rev_pilote_son_carte", h.source) },
            if (h.rotor) t("rev_pilote_rotor") else null,
            if (h.enregistrer) t("rev_pilote_enregistre") else null,
            tf("rev_pilote_frequence", if (c.freqHz > 0) String.format(Locale.US, "%.3f MHz %s", c.freqHz / 1e6, c.modulation) else c.emetteur.ifBlank { "—" }),
            tf("rev_pilote_mode", c.modeSstv))
    }

    // ------------------------------------------------------------ writing

    private val utc = TimeZone.getTimeZone("UTC")
    private fun fmt(p: String, t: Long) = SimpleDateFormat(p, Locale.US).apply { timeZone = utc }.format(Date(t))
    private fun hm(t: Long) = fmt("HH:mm", t)
    private fun hms(t: Long) = fmt("HH:mm:ss", t)
    private fun jour(t: Long) = fmt("dd/MM/yyyy", t)
    private fun jc(t: Long) = fmt("dd/MM", t)
    private fun h(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun n1(v: Double) = String.format(Locale.US, "%.1f", v)
    private fun n2(v: Double) = String.format(Locale.US, "%.2f", v)
    /** An elevation: « 5 », « 7.5 ». */
    private fun el(v: Double) = if (v == Math.floor(v)) v.toInt().toString() else n1(v)
    private fun b64(o: ByteArray) = Base64.getEncoder().encodeToString(o)
    private fun type(o: ByteArray) = if (PlancheFichier.genre(o) == "png") "image/png" else "image/jpeg"
    private fun deg(v: Double) = ((v % 360 + 360) % 360).roundToInt().let { if (it == 360) 0 else it }
    /** « N », « NE »… of an azimuth. */
    private fun rose(az: Double): String = listOf("N", "NE", "E", "SE", "S", "SO", "O", "NO").let { l ->
        val r = if (I18n.current() == Lang.EN) listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW") else l
        r[(((az % 360 + 360) % 360 + 22.5) / 45).toInt() % 8]
    }

    /** The colour of a pass's or a slide's state (status colours, always beside an icon and a word). */
    private fun couleur(s: Statut) = when (s) {
        Statut.RECU -> "var(--bon)"; Statut.RECU_BAS -> "var(--s1)"; Statut.ATTENDU_RIEN -> "var(--rate)"
        Statut.BAS -> "var(--muet)"; Statut.A_VENIR -> "var(--avenir)"
    }
    private fun icone(s: Statut) = when (s) { Statut.RECU -> "✓"; Statut.RECU_BAS -> "✓"; Statut.ATTENDU_RIEN -> "✗"; Statut.BAS -> "–"; Statut.A_VENIR -> "◷" }
    private fun libelle(s: Statut) = t("rev_statut_" + s.name.lowercase())

    private fun origineLib(o: ClassementSstv.Origine) = when (o) {
        ClassementSstv.Origine.MAIN -> t("rev_origine_main"); ClassementSstv.Origine.LU -> t("rev_origine_lu")
        ClassementSstv.Origine.ESTIME -> t("rev_origine_estime"); ClassementSstv.Origine.AUCUNE -> t("rev_origine_aucune")
    }

    /** The file's name: `SatMe_Rapport_<event>_<day>.html`. */
    fun nomFichier(r: Rapport): String {
        val n = java.text.Normalizer.normalize(r.evenement.nom, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
            .replace(Regex("[^A-Za-z0-9-]+"), "-").trim('-').take(40).ifBlank { "SSTV" }
        return "SatMe_Rapport_${n}_${fmt("yyyyMMdd", r.evenement.debutMs)}.html"
    }

    fun html(r: Rapport): String = buildString {
        val e = r.evenement
        val s = r.stats
        val lang = if (I18n.current() == Lang.EN) "en" else "fr"
        append("<!doctype html><html lang=\"$lang\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        append("<title>").append(h(tf("rev_titre_page", e.nom))).append("</title><style>").append(STYLE).append("</style></head><body><main>")

        // ---- header
        append("<header class=\"tete\"><div class=\"tete-g\"><div class=\"genre\">").append(h(t("rev_genre_" + EvenementSstv.genreCle(e.genre)))).append("</div>")
        append("<h1>").append(h(e.nom)).append("</h1><div class=\"sous\">")
        append(h(e.satNom)).append(if (e.catnum > 0) " · NORAD ${e.catnum}" else "").append(" · ")
        append(h(jour(e.debutMs) + " " + hm(e.debutMs) + " → " + jour(e.finMs) + " " + hm(e.finMs) + " UTC"))
        append("</div></div><div class=\"tete-d\">")
        if (r.station.indicatif.isNotBlank()) append("<div class=\"ind\">").append(h(r.station.indicatif)).append("</div>")
        append("<div class=\"sous\">").append(h(listOf(r.station.nom, r.station.locator).filter { it.isNotBlank() }.joinToString(" · "))).append("</div>")
        append("<div class=\"sous\">").append(h(tf("rev_fait_le", jour(r.faitMs) + " " + hm(r.faitMs) + " UTC"))).append("</div></div></header>")

        // ---- the numbers
        append("<section class=\"tuiles\">")
        fun tuile(v: String, l: String, d: String = "") {
            append("<div class=\"tuile\"><b>").append(h(v)).append("</b><span>").append(h(l)).append("</span>")
            if (d.isNotBlank()) append("<i>").append(h(d)).append("</i>")
            append("</div>")
        }
        tuile("${s.diapos}/${e.nb}", t("rev_t_diapos"), if (s.diaposEstimees > 0) tf("rev_t_dont_estimees", s.diaposEstimees) else "")
        tuile(s.images.toString(), t("rev_t_images"), tf("rev_t_completes", s.completes))
        tuile("${s.passagesRecus}/${s.passagesHauts}", tf("rev_t_passages", el(r.elMin)), if (s.passagesRates > 0) tf("rev_t_rates", s.passagesRates) else "")
        tuile(if (s.noteMax >= 0) "${s.noteMax}" else "—", t("rev_t_meilleure"), if (s.noteMoyenne >= 0) tf("rev_t_moyenne", s.noteMoyenne) else "")
        append("</section>")

        // ---- the sheet
        if (r.planche != null) {
            append("<section class=\"carte\"><h2>").append(h(t("rev_planche"))).append(if (r.nomPlanche.isNotBlank()) " <span class=\"gris\">· " + h(r.nomPlanche) + "</span>" else "").append("</h2>")
            append("<img class=\"planche\" alt=\"").append(h(r.nomPlanche)).append("\" src=\"data:").append(type(r.planche)).append(";base64,").append(b64(r.planche)).append("\"></section>")
        }

        // ---- the slides
        append("<section class=\"carte\"><h2>").append(h(t("rev_diapos"))).append("</h2><div class=\"diapos\">")
        for (d in r.diapos) {
            append("<figure class=\"diapo").append(if (d.recue) "" else " manque").append("\">")
            val v = d.meilleure?.let { r.vignettes[it] }
            if (v != null) append("<img alt=\"\" src=\"data:").append(type(v)).append(";base64,").append(b64(v)).append("\">")
            else append("<div class=\"vide\">").append(if (d.recue) "" else h(t("rev_non_recue"))).append("</div>")
            append("<span class=\"num o-").append(d.origine.name.lowercase()).append("\" title=\"").append(h(origineLib(d.origine))).append("\">")
                .append(h(if (d.origine == ClassementSstv.Origine.ESTIME) "≈${d.n}" else "${d.n}")).append("/").append(e.nb).append("</span>")
            append("<figcaption>")
            if (d.recue) {
                append("<b>").append(if (d.note >= 0) "${d.note}/100" else "—").append("</b> · ").append(h(tf("rev_copies", d.copies)))
                append("<br><span class=\"gris\">").append(h(origineLib(d.origine))).append(" · ").append(h(tf("rev_sur_passages", d.passages))).append("</span>")
            } else {
                append("<span class=\"rate\">✗ ").append(h(t("rev_non_recue"))).append("</span><br><span class=\"gris\">")
                append(h(if (d.attendue.isEmpty()) t("rev_jamais_attendue") else tf("rev_attendue", d.attendue.take(3).joinToString(", ") { jc(it.aosMs) + " " + hm(it.aosMs) + " (" + it.elMax.roundToInt() + "°)" })))
                append("</span>")
            }
            append("</figcaption></figure>")
        }
        append("</div>")
        append("<p class=\"gris\">").append(h(t("rev_aide_origine"))).append("</p></section>")

        // ---- the timeline of the passes
        append("<section class=\"carte\"><h2>").append(h(t("rev_chronologie"))).append("</h2>")
        append(chronologie(r))
        append("<div class=\"legende\">")
        listOf(Statut.RECU, Statut.RECU_BAS, Statut.ATTENDU_RIEN, Statut.BAS, Statut.A_VENIR).filter { st -> r.passages.any { it.statut == st } }.forEach { st ->
            append("<span><i style=\"background:").append(couleur(st)).append("\"></i>").append(icone(st)).append(" ").append(h(libelle(st))).append("</span>")
        }
        append("<span><i class=\"seuil\"></i>").append(h(tf("rev_seuil", el(r.elMin)))).append("</span>")
        append("</div></section>")

        // ---- the table of the passes
        append("<section class=\"carte\"><h2>").append(h(t("rev_passages"))).append(" <span class=\"gris\">(").append(r.passages.size).append(")</span></h2>")
        if (r.passages.isEmpty()) append("<p class=\"gris\">").append(h(t("rev_aucun_passage"))).append("</p>")
        else {
            append("<div class=\"defile\"><table><thead><tr><th>").append(h(t("rev_c_utc"))).append("</th><th>").append(h(t("rev_c_el"))).append("</th><th>")
                .append(h(t("rev_c_az"))).append("</th><th>").append(h(t("rev_c_prevues"))).append("</th><th>").append(h(t("rev_c_recues")))
                .append("</th><th>").append(h(t("rev_c_statut"))).append("</th></tr></thead><tbody>")
            for (p in r.passages) {
                append("<tr><td class=\"t\">").append(jc(p.aosMs)).append(" ").append(hm(p.aosMs)).append("–").append(hm(p.losMs)).append("</td>")
                append("<td><b>").append(p.elMax.roundToInt()).append("°</b></td>")
                append("<td>").append(deg(p.azAos)).append("° ").append(rose(p.azAos)).append(" → ").append(deg(p.azLos)).append("° ").append(rose(p.azLos)).append("</td><td>")
                if (r.horaire == null) append("<span class=\"gris\">—</span>")
                p.prevues.forEach { n -> append("<span class=\"d").append(if (n in p.recues) " ok" else "").append("\">").append(n).append("</span>") }
                p.coupees.forEach { n -> append("<span class=\"d part\" title=\"").append(h(t("rev_coupee"))).append("\">(").append(n).append(")</span>") }
                append("</td><td>")
                if (p.images.isEmpty()) append("<span class=\"gris\">—</span>")
                p.recues.forEach { n -> append("<span class=\"d ok\">").append(n).append("</span>") }
                val sans = p.images.count { it.diapo <= 0 }
                if (sans > 0) append("<span class=\"gris\"> +").append(sans).append(" ?</span>")
                append(" <span class=\"gris\">").append(if (p.images.isNotEmpty()) h(tf("rev_n_images", p.images.size)) else "").append("</span></td>")
                append("<td><span class=\"statut\" style=\"--c:").append(couleur(p.statut)).append("\">").append(icone(p.statut)).append(" ").append(h(libelle(p.statut))).append("</span>")
                if (p.pilote.isNotBlank()) append("<br><span class=\"gris\">").append(h(tf("rev_pilote", p.pilote))).append("</span>")
                append("</td></tr>")
            }
            append("</tbody></table></div>")
        }
        append("</section>")

        // ---- each pass: its sky and its elevation (those high enough, or that brought something)
        val vus = r.passages.filter { it.elMax >= r.elMin || it.images.isNotEmpty() }
        if (vus.isNotEmpty()) {
            append("<section class=\"carte\"><h2>").append(h(t("rev_ciel"))).append("</h2><div class=\"passages\">")
            for (p in vus) {
                append("<div class=\"passage\"><div class=\"p-tete\"><b>").append(jc(p.aosMs)).append(" ").append(hm(p.aosMs)).append(" UTC</b> · ")
                    .append(p.elMax.roundToInt()).append("° <span class=\"statut\" style=\"--c:").append(couleur(p.statut)).append("\">").append(icone(p.statut)).append(" ")
                    .append(h(libelle(p.statut))).append("</span></div>")
                append(polaire(p, r.elMin)).append(profil(p, r.elMin))
                append("</div>")
            }
            append("</div><p class=\"gris\">").append(h(t("rev_aide_ciel"))).append("</p></section>")
        }

        // ---- the timetable
        append("<section class=\"carte\"><h2>").append(h(t("rev_horaire"))).append("</h2><p>")
        val m = r.horaire
        if (m == null) append(h(t("rev_sans_horaire")))
        else {
            append(h(if (m.ajustee) tf("rev_horaire_ajuste", n2(m.periodeS), n2(m.sigmaPeriodeS), m.reperes, n1(m.residuS))
                else tf("rev_horaire_annonce", n1(m.periodeS))))
            if (m.ruptures.isNotEmpty()) append(" ").append(h(tf("rev_ruptures", m.ruptures.size, m.ruptures.joinToString(", ") { jc(it) + " " + hm(it) })))
        }
        append("</p><p class=\"gris\">").append(h(t("rev_aide_horaire"))).append("</p>")
        if (s.premiereMs > 0) append("<p>").append(h(tf("rev_premiere_derniere", jour(s.premiereMs) + " " + hm(s.premiereMs), jour(s.derniereMs) + " " + hm(s.derniereMs)))).append("</p>")
        if (s.horsPassage > 0) append("<p class=\"gris\">").append(h(tf("rev_hors_passage", s.horsPassage))).append("</p>")
        append("</section>")

        // ---- the receive chain
        if (r.chaine != null) {
            append("<section class=\"carte\"><h2>").append(h(t("rev_chaine"))).append(if (r.nomChaine.isNotBlank()) " <span class=\"gris\">· " + h(r.nomChaine) + "</span>" else "").append("</h2>")
            if (r.chaine.isEmpty()) append("<p class=\"gris\">").append(h(t("rev_chaine_vide"))).append("</p>")
            else {
                append("<div class=\"chaine\">")
                r.chaine.forEachIndexed { i, col ->
                    if (i > 0) append("<div class=\"fleche\" aria-hidden=\"true\">→</div>")
                    append("<div class=\"col\">")
                    col.forEach { x -> append("<div class=\"piece\"><span>").append(h(x.genre)).append("</span><b>").append(h(x.nom)).append("</b></div>") }
                    append("</div>")
                }
                append("<div class=\"fleche\" aria-hidden=\"true\">→</div><div class=\"col\"><div class=\"piece satme\"><span>").append(h(t("rev_logiciel")))
                    .append("</span><b>SatMe</b></div></div></div>")
            }
            if (r.pilote.isNotEmpty()) {
                append("<h3>").append(h(t("rev_pilote_titre"))).append("</h3><ul>")
                r.pilote.forEach { append("<li>").append(h(it)).append("</li>") }
                append("</ul>")
            }
            append("</section>")
        }

        // ---- how it was worked out
        append("<footer><p>")
        append(h(when {
            !r.geometrie.recalculee -> t("rev_geo_absente")
            r.geometrie.epoqueMs != null -> tf("rev_geo_recalculee", jour(r.geometrie.epoqueMs) + " " + hm(r.geometrie.epoqueMs) + " UTC",
                n1(kotlin.math.abs((e.debutMs + e.finMs) / 2 - r.geometrie.epoqueMs) / 86_400_000.0))
            else -> t("rev_geo_recalculee_sans")
        }))
        append("</p><p>").append(h(tf("rev_pied", r.logiciel, jour(r.faitMs) + " " + hm(r.faitMs) + " UTC"))).append("</p></footer>")
        append("</main></body></html>")
    }

    // ------------------------------------------------------------ the charts

    /** Every pass of the event along its days: a bar to its peak, its state's colour; the pictures as dots. */
    private fun chronologie(r: Rapport): String = buildString {
        val e = r.evenement
        val W = 960.0; val H = 220.0; val G = 40.0; val D = 12.0; val Hh = 14.0; val B = 34.0
        val t0 = minOf(e.debutMs, r.passages.minOfOrNull { it.aosMs } ?: e.debutMs)
        val t1 = maxOf(e.finMs, r.passages.maxOfOrNull { it.losMs } ?: e.finMs, t0 + 3_600_000L)
        fun x(t: Long) = G + (W - G - D) * (t - t0).toDouble() / (t1 - t0)
        fun y(el: Double) = H - B - (H - B - Hh) * el.coerceIn(0.0, 90.0) / 90.0
        append("<svg class=\"chrono\" viewBox=\"0 0 ${W.toInt()} ${H.toInt()}\" role=\"img\" aria-label=\"").append(h(t("rev_chronologie"))).append("\">")
        // Grid: elevations, days.
        for (el in listOf(0, 30, 60, 90)) {
            append("<line class=\"grille\" x1=\"$G\" x2=\"${W - D}\" y1=\"${n1(y(el.toDouble()))}\" y2=\"${n1(y(el.toDouble()))}\"/>")
            append("<text class=\"ax\" x=\"${G - 6}\" y=\"${n1(y(el.toDouble()) + 4)}\" text-anchor=\"end\">$el°</text>")
        }
        var j = (t0 / 86_400_000L + 1) * 86_400_000L
        val pas = if (t1 - t0 > 10 * 86_400_000L) 2 * 86_400_000L else 86_400_000L
        while (j < t1) {
            append("<line class=\"grille\" x1=\"${n1(x(j))}\" x2=\"${n1(x(j))}\" y1=\"$Hh\" y2=\"${H - B}\"/>")
            append("<text class=\"ax\" x=\"${n1(x(j))}\" y=\"${H - B + 16}\" text-anchor=\"middle\">").append(jc(j)).append("</text>")
            j += pas
        }
        // The event's window.
        append("<rect class=\"fenetre\" x=\"${n1(x(e.debutMs))}\" y=\"$Hh\" width=\"${n1((x(e.finMs) - x(e.debutMs)).coerceAtLeast(1.0))}\" height=\"${n1(H - B - Hh)}\"/>")
        append("<line class=\"seuil\" x1=\"$G\" x2=\"${W - D}\" y1=\"${n1(y(r.elMin))}\" y2=\"${n1(y(r.elMin))}\"/>")
        for (p in r.passages) {
            val xm = x((p.aosMs + p.losMs) / 2)
            val w = 5.0
            append("<g><title>").append(h(jc(p.aosMs) + " " + hm(p.aosMs) + "–" + hm(p.losMs) + " UTC · " + p.elMax.roundToInt() + "° · " + libelle(p.statut) +
                (if (p.recues.isNotEmpty()) " · " + p.recues.joinToString(",") else ""))).append("</title>")
            append("<rect x=\"${n1(xm - w / 2)}\" y=\"${n1(y(p.elMax))}\" width=\"$w\" height=\"${n1((y(0.0) - y(p.elMax)).coerceAtLeast(1.0))}\" rx=\"2\" fill=\"")
                .append(couleur(p.statut)).append("\"").append(if (p.statut == Statut.A_VENIR) " fill-opacity=\".45\"" else "").append("/>")
            append("<rect x=\"${n1(xm - 8)}\" y=\"$Hh\" width=\"16\" height=\"${n1(H - B - Hh)}\" fill=\"transparent\"/></g>")
        }
        for (i in r.images) {
            val pt = i.trace.getOrNull(i.trace.size / 2) ?: continue
            append("<circle class=\"img\" cx=\"${n1(x(i.tMs))}\" cy=\"${n1(y(pt.el))}\" r=\"4\"><title>")
                .append(h(hms(i.tMs) + " UTC · " + (Planche.numeroDiapo(i.diapo, i.total, i.origine).ifBlank { "?" }) + (if (i.note >= 0) " · ${i.note}/100" else "")))
                .append("</title></circle>")
        }
        append("<text class=\"ax\" x=\"$G\" y=\"${H - 4}\">").append(h(t("rev_axe_temps"))).append("</text>")
        append("</svg>")
    }

    /** A pass seen from below: the sky as a disc (zenith in the middle, north up), its track, the pictures on it. */
    fun polaire(p: Passage, elMin: Double): String = buildString {
        val R = 92.0; val C = 110.0
        fun xy(az: Double, el: Double): Pair<Double, Double> {
            val rr = R * (90 - el.coerceIn(0.0, 90.0)) / 90
            val a = Math.toRadians(az)
            return (C + rr * sin(a)) to (C - rr * cos(a))
        }
        append("<svg class=\"polaire\" viewBox=\"0 0 220 220\" role=\"img\" aria-label=\"").append(h(t("rev_ciel"))).append("\">")
        for (el in listOf(0, 30, 60)) append("<circle class=\"grille\" cx=\"$C\" cy=\"$C\" r=\"${n1(R * (90 - el) / 90)}\"/>")
        if (elMin > 0) append("<circle class=\"seuil\" cx=\"$C\" cy=\"$C\" r=\"${n1(R * (90 - elMin) / 90)}\"/>")
        append("<line class=\"grille\" x1=\"${C - R}\" x2=\"${C + R}\" y1=\"$C\" y2=\"$C\"/><line class=\"grille\" y1=\"${C - R}\" y2=\"${C + R}\" x1=\"$C\" x2=\"$C\"/>")
        val o = if (I18n.current() == Lang.EN) "W" else "O"
        append("<text class=\"ax\" x=\"$C\" y=\"12\" text-anchor=\"middle\">N</text><text class=\"ax\" x=\"${C + R + 10}\" y=\"${C + 4}\" text-anchor=\"middle\">E</text>")
        append("<text class=\"ax\" x=\"$C\" y=\"${C + R + 15}\" text-anchor=\"middle\">S</text><text class=\"ax\" x=\"${C - R - 10}\" y=\"${C + 4}\" text-anchor=\"middle\">$o</text>")
        append("<text class=\"ax petit\" x=\"${C + 3}\" y=\"${n1(C - R * 60 / 90 + 10)}\">30°</text><text class=\"ax petit\" x=\"${C + 3}\" y=\"${n1(C - R * 30 / 90 + 10)}\">60°</text>")
        val pts = p.trace.filter { it.el >= -1 }
        if (pts.size >= 2) {
            append("<polyline class=\"trace\" points=\"").append(pts.joinToString(" ") { val (x, y) = xy(it.az, it.el); n1(x) + "," + n1(y) }).append("\"/>")
            val (ax, ay) = xy(pts.first().az, pts.first().el); val (lx, ly) = xy(pts.last().az, pts.last().el)
            append("<circle class=\"aos\" cx=\"${n1(ax)}\" cy=\"${n1(ay)}\" r=\"4\"><title>AOS ${hm(p.aosMs)} · ${deg(p.azAos)}°</title></circle>")
            append("<rect class=\"los\" x=\"${n1(lx - 3.5)}\" y=\"${n1(ly - 3.5)}\" width=\"7\" height=\"7\"><title>LOS ${hm(p.losMs)} · ${deg(p.azLos)}°</title></rect>")
        }
        for (i in p.images) {
            if (i.trace.size < 2) continue
            append("<g><title>").append(h(hms(i.debutMs) + "–" + hms(i.tMs) + " UTC · " + Planche.numeroDiapo(i.diapo, i.total, i.origine).ifBlank { "?" } +
                (if (i.note >= 0) " · ${i.note}/100" else ""))).append("</title>")
            append("<polyline class=\"recu").append(if (i.complete) "" else " partielle").append("\" points=\"")
                .append(i.trace.joinToString(" ") { val (x, y) = xy(it.az, it.el); n1(x) + "," + n1(y) }).append("\"/>")
            val mi = i.trace[i.trace.size / 2]; val (mx, my) = xy(mi.az, mi.el)
            val etiq = if (i.diapo > 0) (if (i.origine == ClassementSstv.Origine.ESTIME) "≈" else "") + i.diapo else "?"
            append("<circle class=\"pastille\" cx=\"${n1(mx)}\" cy=\"${n1(my)}\" r=\"8\"/><text class=\"pastille-t\" x=\"${n1(mx)}\" y=\"${n1(my + 3.5)}\" text-anchor=\"middle\">")
                .append(h(etiq)).append("</text></g>")
        }
        append("</svg>")
    }

    /** A pass's elevation against time: the slides' slots on air (expected, received), the minimum elevation. */
    fun profil(p: Passage, elMin: Double): String = buildString {
        val W = 220.0; val H = 110.0; val G = 26.0; val D = 6.0; val Hh = 6.0; val B = 20.0
        val t0 = p.aosMs; val t1 = maxOf(p.losMs, p.aosMs + 60_000L)
        fun x(t: Long) = G + (W - G - D) * ((t - t0).toDouble() / (t1 - t0)).coerceIn(0.0, 1.0)
        val haut = maxOf(30.0, ((p.elMax + 9) / 10).toInt() * 10.0).coerceAtMost(90.0)
        fun y(el: Double) = H - B - (H - B - Hh) * el.coerceIn(0.0, haut) / haut
        append("<svg class=\"profil\" viewBox=\"0 0 ${W.toInt()} ${H.toInt()}\" role=\"img\" aria-label=\"").append(h(t("rev_profil"))).append("\">")
        // The slots of the slides: light when expected, solid when received.
        val recues = p.images.filter { it.diapo > 0 }.map { it.diapo }.toSet()
        for (c in p.creneaux) {
            val x0 = x(c.debutMs); val x1 = x(c.finMs)
            if (x1 - x0 < 0.5) continue
            val ok = c.diapo in recues
            append("<g><title>").append(h(tf("rev_creneau", c.diapo, hms(c.debutMs), hms(c.finMs)) + if (ok) " · " + t("rev_statut_recu") else "")).append("</title>")
            append("<rect class=\"creneau").append(if (ok) " ok" else if (!c.entiere) " part" else "").append("\" x=\"${n1(x0)}\" y=\"$Hh\" width=\"${n1(x1 - x0)}\" height=\"${n1(H - B - Hh)}\"/>")
            append("<text class=\"ax petit\" x=\"${n1((x0 + x1) / 2)}\" y=\"${Hh + 9}\" text-anchor=\"middle\">").append(c.diapo).append("</text></g>")
        }
        for (el in listOf(0.0, haut / 2, haut)) {
            append("<line class=\"grille\" x1=\"$G\" x2=\"${W - D}\" y1=\"${n1(y(el))}\" y2=\"${n1(y(el))}\"/>")
            append("<text class=\"ax petit\" x=\"${G - 3}\" y=\"${n1(y(el) + 3)}\" text-anchor=\"end\">${el.roundToInt()}°</text>")
        }
        if (elMin in 0.0..haut) append("<line class=\"seuil\" x1=\"$G\" x2=\"${W - D}\" y1=\"${n1(y(elMin))}\" y2=\"${n1(y(elMin))}\"/>")
        if (p.trace.size >= 2) append("<polyline class=\"trace\" points=\"").append(p.trace.joinToString(" ") { n1(x(it.tMs)) + "," + n1(y(it.el)) }).append("\"/>")
        for (i in p.images) if (i.trace.size >= 2)
            append("<polyline class=\"recu").append(if (i.complete) "" else " partielle").append("\" points=\"").append(i.trace.joinToString(" ") { n1(x(it.tMs)) + "," + n1(y(it.el)) }).append("\"/>")
        append("<text class=\"ax petit\" x=\"$G\" y=\"${H - 5}\">").append(hm(p.aosMs)).append("</text>")
        append("<text class=\"ax petit\" x=\"${W - D}\" y=\"${H - 5}\" text-anchor=\"end\">").append(hm(p.losMs)).append(" UTC</text>")
        append("</svg>")
    }

    private val STYLE = """
:root{--fond:#f4f5f2;--carte:#fcfcfb;--encre:#1d2329;--gris:#5d6873;--trait:#d9dcd6;--grille:#e4e6e1;
--bon:#0ca30c;--rate:#d03b3b;--avenir:#8a94a0;--muet:#c3c8cc;--s1:#2a78d6;--trace:#5d6873;--recu:#eb6834;--fenetre:#2a78d60f}
@media (prefers-color-scheme:dark){:root:not([data-theme="light"]){--fond:#121416;--carte:#1a1a19;--encre:#e8ebe6;--gris:#a3acb5;--trait:#33373a;--grille:#2b2f31;
--avenir:#7d8792;--muet:#4a5056;--s1:#3987e5;--trace:#a3acb5;--recu:#d95926;--fenetre:#3987e512}}
*{box-sizing:border-box}
body{margin:0;background:var(--fond);color:var(--encre);font:14px/1.45 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
main{max-width:1040px;margin:0 auto;padding:20px 16px 40px}
.tete{display:flex;justify-content:space-between;gap:16px;flex-wrap:wrap;align-items:flex-end;padding:18px 20px;border-radius:14px;
background:linear-gradient(135deg,#0b2d5b,#123f7a 60%,#1b5aa5);color:#fff}
.tete h1{margin:2px 0 4px;font-size:24px;line-height:1.2}.tete .sous{color:#d6e4f5;font-size:13px}
.tete .genre{font-size:11px;letter-spacing:.08em;text-transform:uppercase;color:#ffc21a;font-weight:700}
.tete-d{text-align:right}.tete .ind{font-size:22px;font-weight:800;letter-spacing:.04em}
.tuiles{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:10px;margin:14px 0}
@media (max-width:640px){.tuiles{grid-template-columns:repeat(2,minmax(0,1fr))}.tete-d{text-align:left}}
.tuile{background:var(--carte);border:1px solid var(--trait);border-radius:12px;padding:12px 14px;display:flex;flex-direction:column}
.tuile b{font-size:26px;line-height:1.1;font-variant-numeric:tabular-nums}.tuile span{color:var(--gris);font-size:12px}.tuile i{font-style:normal;color:var(--gris);font-size:12px}
.carte{background:var(--carte);border:1px solid var(--trait);border-radius:12px;padding:14px 16px;margin:14px 0}
h2{font-size:16px;margin:0 0 10px}h3{font-size:14px;margin:12px 0 6px}
.gris{color:var(--gris)}.rate{color:var(--rate);font-weight:600}
img.planche{display:block;width:100%;height:auto;border-radius:8px}
.diapos{display:grid;grid-template-columns:repeat(6,minmax(0,1fr));gap:10px}
@media (max-width:760px){.diapos{grid-template-columns:repeat(3,minmax(0,1fr))}}
.diapo{margin:0;position:relative;font-size:12px}
.diapo img,.diapo .vide{display:block;width:100%;aspect-ratio:4/3;object-fit:cover;border-radius:6px;background:var(--grille)}
.diapo .vide{display:flex;align-items:center;justify-content:center;color:var(--gris);border:1.5px dashed var(--muet);background:transparent;font-size:11px}
.diapo.manque{opacity:.9}
.num{position:absolute;left:4px;top:4px;background:#000000b0;color:#fff;font-weight:800;font-size:12px;padding:0 6px;border-radius:5px}
.num.o-estime{font-style:italic;background:#5d6873d0}.num.o-main{box-shadow:inset 0 -2px 0 #ffc21a}
.diapo figcaption{padding:4px 2px 0;line-height:1.35}
.chrono{width:100%;height:auto;display:block}
.grille{stroke:var(--grille);stroke-width:1;fill:none}
.seuil{stroke:var(--gris);stroke-width:1;stroke-dasharray:4 3;fill:none}
.fenetre{fill:var(--fenetre)}
.ax{fill:var(--gris);font-size:11px}.ax.petit{font-size:9px}
.img{fill:var(--recu);stroke:var(--carte);stroke-width:2}
.legende{display:flex;flex-wrap:wrap;gap:14px;font-size:12px;color:var(--gris);margin-top:6px}
.legende i{display:inline-block;width:12px;height:12px;border-radius:3px;vertical-align:-2px;margin-right:5px}
.legende i.seuil{border-top:1.5px dashed var(--gris);height:0;border-radius:0;vertical-align:3px}
.defile{overflow-x:auto}
table{border-collapse:collapse;width:100%;font-size:13px}
th{text-align:left;color:var(--gris);font-weight:600;font-size:12px;border-bottom:1px solid var(--trait);padding:6px 8px}
td{border-bottom:1px solid var(--grille);padding:6px 8px;vertical-align:top}
td.t{white-space:nowrap;font-variant-numeric:tabular-nums}
.d{display:inline-block;min-width:22px;text-align:center;border-radius:4px;padding:0 4px;margin:1px;border:1px solid var(--trait);font-size:12px}
.d.ok{background:#0ca30c22;border-color:#0ca30c88;font-weight:700}.d.part{color:var(--gris);border-style:dashed}
.statut{white-space:nowrap;font-weight:600}.statut::before{content:"";display:inline-block;width:9px;height:9px;border-radius:50%;background:var(--c);margin-right:6px}
.passages{display:grid;grid-template-columns:repeat(auto-fill,minmax(230px,1fr));gap:12px}
.passage{border:1px solid var(--grille);border-radius:10px;padding:8px}
.p-tete{font-size:12px;margin-bottom:4px}
.polaire,.profil{width:100%;height:auto;display:block}
.trace{fill:none;stroke:var(--trace);stroke-width:2;stroke-linejoin:round;stroke-linecap:round}
.recu{fill:none;stroke:var(--recu);stroke-width:5;stroke-linecap:round;stroke-linejoin:round}.recu.partielle{stroke-dasharray:3 3}
.aos{fill:var(--bon);stroke:var(--carte);stroke-width:2}.los{fill:var(--rate);stroke:var(--carte);stroke-width:2}
.pastille{fill:var(--carte);stroke:var(--recu);stroke-width:2}.pastille-t{font-size:9px;font-weight:800;fill:var(--encre)}
.creneau{fill:var(--s1);fill-opacity:.10}.creneau.ok{fill:var(--bon);fill-opacity:.22}.creneau.part{fill-opacity:.05}
.chaine{display:flex;align-items:center;gap:8px;flex-wrap:wrap}
.chaine .col{display:flex;flex-direction:column;gap:6px}
.piece{border:1px solid var(--trait);border-radius:8px;padding:6px 10px;min-width:120px;background:var(--fond)}
.piece span{display:block;font-size:10px;color:var(--gris);text-transform:uppercase;letter-spacing:.04em}.piece.satme{border-color:var(--s1)}
.fleche{color:var(--gris);font-size:20px}
footer{color:var(--gris);font-size:12px;margin-top:18px}
@page{size:A4;margin:12mm}
@media print{body{background:#fff}main{max-width:none;padding:0}.carte,.tuile,.passage,.diapo,tr{break-inside:avoid}
.tete{-webkit-print-color-adjust:exact;print-color-adjust:exact}.carte{border-color:#ccc}.defile{overflow:visible}}
"""
}
