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
import kotlin.math.abs
import kotlin.math.max

/**
 * The pass journal (SatMe 21, Alpha 4): what happened during a pass the
 * operator followed, kept on the phone only.
 *
 * Stored per pass: the trajectory really followed (with what the rig and the
 * mast did), the recording, the profile and the station test. Linked at
 * reading time, not copied: the contacts of the log, the SSTV pictures and the
 * APRS frames of that time — each of those already keeps its own time.
 *
 * Pure: the ViewModel feeds [Collecte] every second; nothing here transmits,
 * moves or sends anything.
 */
object JournalPassage {

    /** One point of the pass as it went: where the satellite was, what the station did. */
    data class Point(
        val tMs: Long,
        val az: Double,
        val el: Double,
        /** Frequencies read from the rig (sky), when CAT is connected. */
        val dlHz: Long? = null,
        val ulHz: Long? = null,
        /** Where the mast really pointed, when a rotator answers. */
        val rotorAz: Double? = null,
        val rotorEl: Double? = null
    )

    data class Entree(
        val catnum: Int,
        val satName: String,
        /** The first and last moments followed above the horizon. */
        val debutMs: Long,
        val finMs: Long,
        val locator: String = "",
        val profil: String = "",
        val transpondeur: String = "",
        /** Recordings made during the pass (MP3 names). */
        val enregistrements: List<String> = emptyList(),
        /** The last station test before or during the pass: domain → level. */
        val test: Map<String, String> = emptyMap(),
        val testMs: Long = 0L,
        val points: List<Point> = emptyList(),
        /**
         * Rebuilt afterwards from the log, the pictures, the frames or a
         * recording: the trajectory is the predicted one (today's elements),
         * not one followed — no rig, no mast.
         */
        val reconstitue: Boolean = false,
        /** SSTV pictures set aside for this pass (file names). */
        val masquees: Set<String> = emptySet(),
        /**
         * Kept by itself, its page not open: [AUTO_SSTV] (automatic SSTV),
         * [AUTO_CAT] (every pass under CAT), [AUTO_FOND] (a recording started
         * by hand, the page left). Empty: followed on its page.
         */
        val auto: String = ""
    ) {
        val id: String get() = "${catnum}_$debutMs"
        val elMax: Double get() = points.maxOfOrNull { it.el } ?: 0.0
        val dureeMs: Long get() = finMs - debutMs
        val avecCat: Boolean get() = points.any { it.dlHz != null }
        val avecRotor: Boolean get() = points.any { it.rotorAz != null }
    }

    // ------------------------------------------------------------ collecting

    /** A point every [PAS_MS], or sooner when the satellite moved more than [PAS_DEG]. */
    const val PAS_MS = 5_000L
    const val PAS_DEG = 3.0
    /** Shorter than this, followed: not a pass worth keeping (a glance at the page). */
    const val DUREE_MIN_MS = 60_000L
    const val AUTO_SSTV = "SSTV"
    const val AUTO_CAT = "CAT"
    const val AUTO_FOND = "FOND"

    /** Below the horizon this long: the pass is over. */
    const val FIN_APRES_MS = 30_000L

    /** What the station is doing at that second, read from the ViewModel. */
    data class Etat(
        val dlHz: Long? = null, val ulHz: Long? = null,
        val rotorAz: Double? = null, val rotorEl: Double? = null,
        val enregistrement: String? = null,
        val transpondeur: String = "",
        val locator: String = "",
        val profil: String = "",
        /** Recording in the background, by whom (see [Entree.auto]). */
        val auto: String = ""
    )

    /**
     * Gathers a pass second by second, for the satellite followed. Returns the
     * finished entry when the pass is over (satellite down for
     * [FIN_APRES_MS], or another satellite followed), else null.
     */
    class Collecte {
        private var en: Entree? = null
        private var dernierPoint: Point? = null
        private var sousHorizonDepuis = 0L

        val enCours: Entree? get() = en

        fun suit(catnum: Int, satName: String, tMs: Long, az: Double, el: Double, e: Etat): Entree? {
            var fini: Entree? = null
            val cur = en
            if (cur != null && cur.catnum != catnum) fini = ferme()
            if (el < 0) {
                val c = en ?: return fini
                if (sousHorizonDepuis == 0L) sousHorizonDepuis = tMs
                if (tMs - sousHorizonDepuis >= FIN_APRES_MS) return ferme() ?: fini
                en = c
                return fini
            }
            sousHorizonDepuis = 0L
            var c = en ?: Entree(catnum, satName, tMs, tMs, e.locator, e.profil, e.transpondeur)
            val p = Point(tMs, az, el, e.dlHz, e.ulHz, e.rotorAz, e.rotorEl)
            val d = dernierPoint
            val garde = d == null || tMs - d.tMs >= PAS_MS ||
                abs(az - d.az).let { minOf(it, 360 - it) } >= PAS_DEG || abs(el - d.el) >= PAS_DEG
            if (garde) { c = c.copy(points = c.points + p); dernierPoint = p }
            if (e.enregistrement != null && e.enregistrement !in c.enregistrements)
                c = c.copy(enregistrements = c.enregistrements + e.enregistrement)
            if (c.transpondeur.isBlank() && e.transpondeur.isNotBlank()) c = c.copy(transpondeur = e.transpondeur)
            if (c.auto.isBlank() && e.auto.isNotBlank()) c = c.copy(auto = e.auto)
            en = c.copy(finMs = tMs)
            return fini
        }

        /** The last station test, kept with the pass it prepared. */
        fun test(resultat: Map<String, String>, tMs: Long) {
            en?.let { en = it.copy(test = resultat, testMs = tMs) } ?: run { testEnAttente = resultat to tMs }
        }
        private var testEnAttente: Pair<Map<String, String>, Long>? = null

        /**
         * Hands the pass being gathered to [autre] (its page closed, or opened,
         * mid-pass), so the piece is not lost to the minimum length. False when
         * [autre] follows another satellite: then close it as usual.
         */
        fun cedeA(autre: Collecte): Boolean {
            val c = en ?: return true
            val o = autre.en
            if (o != null && o.catnum != c.catnum) return false
            autre.en = if (o == null) c else fusionne(o, c)
            autre.dernierPoint = autre.en?.points?.lastOrNull()
            autre.sousHorizonDepuis = 0L
            if (autre.testEnAttente == null) autre.testEnAttente = testEnAttente
            en = null; dernierPoint = null; sousHorizonDepuis = 0L; testEnAttente = null
            return true
        }

        /** Ends the pass followed; the entry if it is worth keeping. */
        fun ferme(): Entree? {
            val c = en
            en = null; dernierPoint = null; sousHorizonDepuis = 0L
            if (c == null || c.dureeMs < DUREE_MIN_MS || c.points.size < 3) return null
            // A test made in the hour before the pass prepared it.
            val t = testEnAttente
            testEnAttente = null
            return if (c.test.isEmpty() && t != null && c.debutMs - t.second in 0..3_600_000L)
                c.copy(test = t.first, testMs = t.second) else c
        }
    }

    // --------------------------------------------------------------- linking

    /** The window of a pass, a little wider: a contact logged just after LOS still belongs to it. */
    fun fenetre(e: Entree, margeMs: Long = 120_000L): LongRange = (e.debutMs - margeMs)..(e.finMs + margeMs)

    /** Same satellite? Names differ by source ("ISS (ZARYA)", "ISS"): compared on the short form. */
    /** A satellite's name reduced to compare: "ISS (ZARYA)" → "ISS", "SO-50" → "SO50". */
    fun nomNormalise(s: String) = s.uppercase().substringBefore(" (").replace(Regex("[^A-Z0-9]"), "")

    fun memeSatellite(a: String, b: String): Boolean {
        val x = nomNormalise(a); val y = nomNormalise(b)
        return x.isNotEmpty() && (x == y || x.startsWith(y) || y.startsWith(x))
    }

    /** What happened, counted: for the list of passes and the card to share. */
    data class Bilan(
        val elMax: Double,
        val dureeMs: Long,
        val qsos: Int,
        val images: Int,
        val trames: Int,
        val tramesViaIss: Int
    )

    fun bilan(e: Entree, qsosTemps: List<Long>, imagesTemps: List<Long>, tramesTemps: List<Pair<Long, Boolean>>): Bilan {
        val f = fenetre(e)
        return Bilan(
            elMax = e.elMax, dureeMs = e.dureeMs,
            qsos = qsosTemps.count { it in f },
            images = imagesTemps.count { it in f },
            trames = tramesTemps.count { it.first in f },
            tramesViaIss = tramesTemps.count { it.first in f && it.second })
    }

    /** The point of the pass at [tMs] (the last one before), for the replay. */
    fun pointA(e: Entree, tMs: Long): Point? = e.points.lastOrNull { it.tMs <= tMs } ?: e.points.firstOrNull()

    // ---------------------------------------------------------------- replay

    /**
     * The moment heard at [posMs] into a recording started at [debutEnregMs]
     * (its name) with a spoken header of [annonceMs] before the pass audio.
     */
    fun heureDuSon(debutEnregMs: Long, annonceMs: Long, posMs: Long): Long = debutEnregMs - annonceMs + posMs

    /** And back: where in the recording the moment [tMs] is heard. */
    fun posDuSon(debutEnregMs: Long, annonceMs: Long, tMs: Long): Long = tMs - debutEnregMs + annonceMs

    /** How far the mast was from the satellite, on average (azimuth, elevation), when it answered. */
    fun ecartMat(e: Entree): Pair<Double, Double>? {
        val p = e.points.filter { it.rotorAz != null && it.rotorEl != null }
        if (p.isEmpty()) return null
        val daz = p.map { abs(it.az - it.rotorAz!!).let { d -> minOf(d, 360 - d) } }.average()
        val del = p.map { abs(it.el - it.rotorEl!!) }.average()
        return daz to del
    }

    /** The downlink as the rig was tuned: first, lowest, highest (Hz). */
    fun descente(e: Entree): Triple<Long, Long, Long>? {
        val f = e.points.mapNotNull { it.dlHz }
        if (f.isEmpty()) return null
        return Triple(f.first(), f.min(), f.max())
    }

    // ---------------------------------------------------- marks on the pass

    enum class TypeMarque { QSO, APRS, SSTV }

    /**
     * Something that happened during the pass, to put on its trajectory: a
     * contact (a point), an APRS frame (a point), an SSTV picture (the part of
     * the trajectory it took to arrive). [lat]/[lon]: where on Earth, when known
     * (the other station's locator, the APRS position).
     */
    data class Marque(
        val type: TypeMarque, val debutMs: Long, val finMs: Long, val texte: String,
        val lat: Double? = null, val lon: Double? = null, val viaIss: Boolean = false,
        /** The picture itself (SSTV), shown a moment when it has arrived. */
        val fichier: String? = null,
        /** What was said: the contact's mode and reports, the APRS comment. */
        val details: String = ""
    )

    // ------------------------------------------------------- accelerated replay

    /** A stretch of the replay: pass time [deMs, aMs) played [vitesse] times faster. */
    data class Segment(val deMs: Long, val aMs: Long, val vitesse: Int)

    /**
     * Where the replay goes at normal speed, around each mark. A contact (a
     * voice QSO): from [avantMs] before, to [apresQsoMs] after — the talk goes
     * on. An SSTV picture: [AVANT_SSTV_MS] before it starts, to its end. An
     * APRS frame: [avantMs] before, [APRES_APRS_MS] after. Close ones are
     * joined: a gap shorter than [JOINT_MS] is not worth a rush.
     */
    fun plagesNormales(marques: List<Marque>, debutMs: Long, finMs: Long, avantMs: Long, apresQsoMs: Long): List<LongRange> {
        val brutes = marques.map { m ->
            val (avant, apres) = when (m.type) {
                TypeMarque.QSO -> avantMs to apresQsoMs
                TypeMarque.SSTV -> AVANT_SSTV_MS to 0L
                TypeMarque.APRS -> avantMs to APRES_APRS_MS
            }
            maxOf(debutMs, m.debutMs - avant) to minOf(finMs, m.finMs + apres)
        }
            .filter { it.second > it.first }.sortedBy { it.first }
        val out = ArrayList<LongRange>()
        var c: Pair<Long, Long>? = null
        for (b in brutes) {
            val cc = c
            c = if (cc != null && b.first <= cc.second + JOINT_MS) cc.first to maxOf(cc.second, b.second) else {
                cc?.let { out += it.first..it.second }; b
            }
        }
        c?.let { out += it.first..it.second }
        return out
    }
    const val JOINT_MS = 5_000L
    /** Before an SSTV picture: its header is there already, a second is enough. */
    const val AVANT_SSTV_MS = 1_000L
    const val APRES_APRS_MS = 3_000L

    /** The whole pass as stretches: normal speed in [plages], [rapide] times faster elsewhere. */
    fun segments(debutMs: Long, finMs: Long, plages: List<LongRange>, rapide: Int): List<Segment> {
        val out = ArrayList<Segment>()
        var t = debutMs
        for (p in plages) {
            val de = p.first.coerceIn(debutMs, finMs); val a = p.last.coerceIn(debutMs, finMs)
            if (a <= t) continue
            if (de > t) out += Segment(t, de, rapide)
            out += Segment(maxOf(t, de), a, 1)
            t = a
        }
        if (t < finMs) out += Segment(t, finMs, rapide)
        return out
    }

    /** How long the replay lasts (ms of playing). */
    fun dureeLecture(segments: List<Segment>): Long = segments.sumOf { (it.aMs - it.deMs) / it.vitesse }

    /** The pass time shown after [lectureMs] of playing. */
    fun instantALecture(segments: List<Segment>, lectureMs: Long): Long {
        var reste = lectureMs
        for (s in segments) {
            val d = (s.aMs - s.deMs) / s.vitesse
            if (reste < d) return s.deMs + reste * s.vitesse
            reste -= d
        }
        return segments.lastOrNull()?.aMs ?: 0L
    }

    /** At pass time [tMs], the stretch being played (null: outside the pass). */
    fun segmentA(segments: List<Segment>, tMs: Long): Segment? = segments.firstOrNull { tMs >= it.deMs && tMs < it.aMs }

    /** Who a station is, for its card during the replay: from the log, the frame, QRZ.com. */
    data class Fiche(
        val indicatif: String, val nom: String = "", val qth: String = "", val pays: String = "",
        val locator: String = ""
    )

    /** A callsign without its SSID or relay mark, as QRZ.com and the log know it. */
    fun indicatifDeBase(s: String) = s.substringBefore('-').trimEnd('*').trim().uppercase()

    /** Great-circle distance between two locators, km; null if either is unknown. */
    fun distanceKm(a: String, b: String): Int? {
        val p = fr.f4ioz.satcombo.location.Maidenhead.toLatLon(a.takeIf { it.length >= 4 } ?: return null) ?: return null
        val q = fr.f4ioz.satcombo.location.Maidenhead.toLatLon(b.takeIf { it.length >= 4 } ?: return null) ?: return null
        val la1 = Math.toRadians(p.first); val la2 = Math.toRadians(q.first); val dl = Math.toRadians(q.second - p.second)
        val c = kotlin.math.sin(la1) * kotlin.math.sin(la2) + kotlin.math.cos(la1) * kotlin.math.cos(la2) * kotlin.math.cos(dl)
        return (6371.0 * kotlin.math.acos(c.coerceIn(-1.0, 1.0))).toInt()
    }

    /** Under the satellite at a moment: where, and how high (for its footprint). */
    data class Sol(val tMs: Long, val lat: Double, val lon: Double, val altKm: Double)

    fun solA(sol: List<Sol>, tMs: Long): Sol? = sol.lastOrNull { it.tMs <= tMs } ?: sol.firstOrNull()

    /**
     * The satellite's footprint: the circle on Earth from which it is above
     * the horizon — angular radius acos(R / (R + h)) around the point under it.
     */
    fun empreinte(lat: Double, lon: Double, altKm: Double, n: Int = 90): List<Pair<Double, Double>> {
        val r = 6371.0
        val ang = kotlin.math.acos(r / (r + altKm.coerceAtLeast(1.0)))
        val la = Math.toRadians(lat); val lo = Math.toRadians(lon)
        return (0..n).map { i ->
            val cap = 2 * Math.PI * i / n
            val la2 = kotlin.math.asin(kotlin.math.sin(la) * kotlin.math.cos(ang) + kotlin.math.cos(la) * kotlin.math.sin(ang) * kotlin.math.cos(cap))
            val lo2 = lo + kotlin.math.atan2(kotlin.math.sin(cap) * kotlin.math.sin(ang) * kotlin.math.cos(la),
                kotlin.math.cos(ang) - kotlin.math.sin(la) * kotlin.math.sin(la2))
            Math.toDegrees(la2) to ((Math.toDegrees(lo2) + 540) % 360 - 180)
        }
    }

    // Web Mercator, as the map tiles: the world 0..1 both ways.
    fun mercX(lon: Double) = (lon + 180.0) / 360.0
    fun mercY(lat: Double): Double {
        val l = Math.toRadians(lat.coerceIn(-85.05, 85.05))
        return (1 - kotlin.math.ln(kotlin.math.tan(l) + 1 / kotlin.math.cos(l)) / Math.PI) / 2
    }

    /** A map view: centre (world units) and zoom level (the world is 256·2^zoom pixels). */
    data class VueCarte(val cx: Double = 0.5, val cy: Double = 0.5, val zoom: Double = 2.0)

    /** The view of a [w]×[h] map that shows all [points] (lat, lon), [part] of it filled. */
    fun cadreCarte(points: List<Pair<Double, Double>>, w: Double, h: Double, part: Double = 0.9, zoomMax: Double = 12.0): VueCarte {
        if (points.isEmpty() || w <= 0 || h <= 0) return VueCarte()
        val xs = points.map { mercX(it.second) }; val ys = points.map { mercY(it.first) }
        val dx = (xs.max() - xs.min()).coerceAtLeast(1e-4); val dy = (ys.max() - ys.min()).coerceAtLeast(1e-4)
        val z = minOf(kotlin.math.log2(w * part / (256 * dx)), kotlin.math.log2(h * part / (256 * dy))).coerceIn(1.0, zoomMax)
        return VueCarte((xs.max() + xs.min()) / 2, (ys.max() + ys.min()) / 2, z)
    }

    /** The ISS itself on APRS (RS0ISS, NA1SS…): on the trajectory, whatever position it gives. */
    fun estLeSatellite(source: String): Boolean =
        source.substringBefore('-').trimEnd('*').uppercase() in setOf("RS0ISS", "NA1SS", "ARISS", "APRSAT")

    /** The part of the trajectory between two moments (from the point just before). */
    fun segment(e: Entree, deMs: Long, aMs: Long): List<Point> {
        val dedans = e.points.filter { it.tMs in deMs..aMs }
        val avant = e.points.lastOrNull { it.tMs < deMs }
        return listOfNotNull(avant) + dedans
    }

    /** The sky as a unit disc: zenith at 0,0, horizon at radius 1, north up, east right. */
    fun ciel(az: Double, el: Double): Pair<Double, Double> {
        val d = 1 - el.coerceIn(0.0, 90.0) / 90.0
        val a = Math.toRadians(az)
        return d * kotlin.math.sin(a) to -d * kotlin.math.cos(a)
    }

    /** What the view shows: its centre on the unit sky and how much it is enlarged. */
    data class Cadre(val cx: Double = 0.0, val cy: Double = 0.0, val echelle: Double = 1.0)

    /**
     * The view that shows the whole trajectory, as large as it can be — a
     * low pass on the edge of the sky gets enlarged; the whole sky at most.
     */
    fun cadre(azEl: List<Pair<Double, Double>>, marge: Double = 0.2, max: Double = 8.0): Cadre {
        if (azEl.size < 2) return Cadre()
        val p = azEl.map { ciel(it.first, it.second) }
        val x0 = p.minOf { it.first }; val x1 = p.maxOf { it.first }
        val y0 = p.minOf { it.second }; val y1 = p.maxOf { it.second }
        val etendue = maxOf(x1 - x0, y1 - y0, 0.05) * (1 + marge)
        val e = (2.0 / etendue).coerceIn(1.0, max)
        return if (e <= 1.0) Cadre() else Cadre((x0 + x1) / 2, (y0 + y1) / 2, e)
    }

    // ------------------------------------------------- rebuilding past passes

    /** Where a past pass can be found again. */
    enum class Source { CARNET, SSTV, APRS, ENREGISTREMENT }

    /** Something that happened at a known time on a known satellite. */
    data class Evenement(
        val source: Source, val tMs: Long, val catnum: Int, val satName: String,
        val locator: String = "", val enregistrement: String? = null
    )

    /** A past pass found again, with what points to it. */
    data class Candidat(
        val catnum: Int, val satName: String, val aosMs: Long, val losMs: Long,
        val sources: Set<Source>, val nb: Int, val locator: String, val enregistrements: List<String>
    )

    /**
     * Events gathered into passes: [fenetre] gives the pass (AOS, LOS) of an
     * event's satellite around its time, or null (satellite unknown, or no
     * pass then). Passes already in the journal are left out. Returns the
     * passes found, newest first, and the number of events not placed.
     */
    fun regroupe(
        evts: List<Evenement>, fenetre: (Evenement) -> Pair<Long, Long>?, existants: List<Entree>,
        /**
         * The passes offered have one of these among their sources: a contact,
         * an SSTV picture or a recording. APRS frames alone (heard on the ISS,
         * often off a real pass) only complete the others.
         */
        exige: Set<Source>? = PRINCIPALES
    ): Pair<List<Candidat>, Int> {
        val trouves = ArrayList<Candidat>()
        var perdus = 0
        for (ev in evts.sortedBy { it.tMs }) {
            val i = trouves.indexOfFirst { it.catnum == ev.catnum && ev.tMs in (it.aosMs - 120_000L)..(it.losMs + 120_000L) }
            if (i >= 0) {
                val c = trouves[i]
                trouves[i] = c.copy(sources = c.sources + ev.source, nb = c.nb + 1,
                    locator = c.locator.ifBlank { ev.locator },
                    enregistrements = (c.enregistrements + listOfNotNull(ev.enregistrement)).distinct())
                continue
            }
            if (existants.any { it.catnum == ev.catnum && ev.tMs in (it.debutMs - 120_000L)..(it.finMs + 120_000L) }) continue
            val w = fenetre(ev)
            if (w == null) { perdus++; continue }
            if (existants.any { it.catnum == ev.catnum && it.debutMs < w.second && it.finMs > w.first }) continue
            // Found through the search around its time: the same pass as one already found.
            val j = trouves.indexOfFirst { it.catnum == ev.catnum && it.aosMs < w.second && it.losMs > w.first }
            if (j >= 0) {
                val c = trouves[j]
                trouves[j] = c.copy(sources = c.sources + ev.source, nb = c.nb + 1,
                    locator = c.locator.ifBlank { ev.locator },
                    enregistrements = (c.enregistrements + listOfNotNull(ev.enregistrement)).distinct())
                continue
            }
            trouves += Candidat(ev.catnum, ev.satName, w.first, w.second, setOf(ev.source), 1, ev.locator,
                listOfNotNull(ev.enregistrement))
        }
        return trouves.filter { exige == null || it.sources.any { s -> s in exige } }.sortedByDescending { it.aosMs } to perdus
    }

    /** What is enough, alone, for a past pass to be offered. */
    val PRINCIPALES = setOf(Source.CARNET, Source.SSTV, Source.ENREGISTREMENT)

    /** The journal entry of a pass found again, from its predicted track (time, az, el). */
    fun reconstitue(c: Candidat, piste: List<Triple<Long, Double, Double>>): Entree? {
        val haut = piste.filter { it.third >= 0 }
        if (haut.size < 3) return null
        return Entree(c.catnum, c.satName, haut.first().first, haut.last().first, c.locator,
            enregistrements = c.enregistrements, reconstitue = true,
            points = haut.map { Point(it.first, it.second, it.third) })
    }

    // --------------------------------------------------------------- storage

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", " ").replace("\n", "\\n")
    private fun unesc(s: String): String {
        val b = StringBuilder(); var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) { b.append(if (s[i + 1] == 'n') '\n' else s[i + 1]); i += 2 } else { b.append(c); i++ }
        }
        return b.toString()
    }
    private fun n(v: Long?) = v?.toString() ?: ""
    private fun d(v: Double?) = v?.let { "%.2f".format(java.util.Locale.US, it) } ?: ""

    /** One pass: header lines "key=value", then one line per point, tab-separated. */
    fun ecrit(e: Entree): String = buildString {
        append("passage=1\n")
        append("catnum=").append(e.catnum).append('\n')
        append("sat=").append(esc(e.satName)).append('\n')
        append("debut=").append(e.debutMs).append('\n')
        append("fin=").append(e.finMs).append('\n')
        append("locator=").append(esc(e.locator)).append('\n')
        append("profil=").append(esc(e.profil)).append('\n')
        append("transpondeur=").append(esc(e.transpondeur)).append('\n')
        e.enregistrements.forEach { append("enregistrement=").append(esc(it)).append('\n') }
        if (e.reconstitue) append("reconstitue=1\n")
        e.masquees.forEach { append("masquee=").append(esc(it)).append('\n') }
        if (e.auto.isNotBlank()) append("auto=").append(esc(e.auto)).append('\n')
        if (e.test.isNotEmpty()) {
            append("test=").append(e.testMs).append('\t')
                .append(e.test.entries.joinToString(",") { it.key + ":" + it.value }).append('\n')
        }
        for (p in e.points) {
            append("p=").append(p.tMs).append('\t').append(d(p.az)).append('\t').append(d(p.el)).append('\t')
                .append(n(p.dlHz)).append('\t').append(n(p.ulHz)).append('\t')
                .append(d(p.rotorAz)).append('\t').append(d(p.rotorEl)).append('\n')
        }
    }

    fun lit(texte: String): Entree? = runCatching {
        var e = Entree(0, "", 0L, 0L)
        val pts = ArrayList<Point>(); val enr = ArrayList<String>()
        for (l in texte.lineSequence()) {
            val k = l.substringBefore('=', ""); val v = l.substringAfter('=', "")
            when (k) {
                "catnum" -> e = e.copy(catnum = v.toInt())
                "sat" -> e = e.copy(satName = unesc(v))
                "debut" -> e = e.copy(debutMs = v.toLong())
                "fin" -> e = e.copy(finMs = v.toLong())
                "locator" -> e = e.copy(locator = unesc(v))
                "profil" -> e = e.copy(profil = unesc(v))
                "transpondeur" -> e = e.copy(transpondeur = unesc(v))
                "enregistrement" -> enr += unesc(v)
                "reconstitue" -> e = e.copy(reconstitue = v == "1")
                "masquee" -> e = e.copy(masquees = e.masquees + unesc(v))
                "auto" -> e = e.copy(auto = unesc(v))
                "test" -> {
                    val (t, r) = v.split('\t', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                    e = e.copy(testMs = t.toLong(), test = r.split(',').filter { ':' in it }
                        .associate { it.substringBefore(':') to it.substringAfter(':') })
                }
                "p" -> runCatching {
                    val c = v.split('\t')
                    pts += Point(c[0].toLong(), c[1].toDouble(), c[2].toDouble(), c[3].toLongOrNull(), c[4].toLongOrNull(),
                        c[5].toDoubleOrNull(), c[6].toDoubleOrNull())
                }
            }
        }
        if (e.catnum == 0 || e.debutMs == 0L) null else e.copy(points = pts, enregistrements = enr)
    }.getOrNull()

    /** The passes kept, one file each ("<catnum>_<start>.passage"), the newest [GARDE] only. */
    /** Two pieces of the same pass are one: the same satellite, overlapping (2 min of slack). */
    fun memePassage(a: Entree, b: Entree): Boolean =
        a.catnum == b.catnum && a.debutMs <= b.finMs + 120_000L && b.debutMs <= a.finMs + 120_000L

    /**
     * Two pieces of one pass (followed on its page, then in the background
     * when the page was left — or the other way round) made one: all their
     * points in time order, their recordings, what each knew.
     */
    fun fusionne(a: Entree, b: Entree): Entree {
        val points = (a.points + b.points).sortedBy { it.tMs }.distinctBy { it.tMs / 1000 }
        val test = if (a.testMs >= b.testMs) a else b
        return a.copy(
            debutMs = minOf(a.debutMs, b.debutMs), finMs = maxOf(a.finMs, b.finMs),
            locator = a.locator.ifBlank { b.locator }, profil = a.profil.ifBlank { b.profil },
            transpondeur = a.transpondeur.ifBlank { b.transpondeur },
            enregistrements = (a.enregistrements + b.enregistrements).distinct(),
            test = test.test, testMs = test.testMs, points = points,
            reconstitue = a.reconstitue && b.reconstitue, masquees = a.masquees + b.masquees,
            auto = a.auto.ifBlank { b.auto })
    }

    class Rangement(val dossier: File) {
        init { dossier.mkdirs() }
        /** Kept, merged with what is already kept of the same pass. */
        fun enregistre(e0: Entree) {
            var e = e0
            for (f in fichiers()) {
                val autre = lit(runCatching { f.readText() }.getOrDefault("")) ?: continue
                if (autre.id != e0.id && memePassage(autre, e)) { e = fusionne(e, autre); f.delete() }
            }
            File(dossier, e.id + ".passage").writeText(ecrit(e))
            val tous = fichiers()
            if (tous.size > GARDE) tous.take(tous.size - GARDE).forEach { it.delete() }
        }
        /** Oldest first. */
        private fun fichiers(): List<File> = (dossier.listFiles { f -> f.name.endsWith(".passage") } ?: emptyArray())
            .sortedBy { it.name.substringAfter('_').removeSuffix(".passage").toLongOrNull() ?: 0L }
        /** Newest first. */
        fun passages(): List<Entree> = fichiers().reversed().mapNotNull { lit(runCatching { it.readText() }.getOrDefault("")) }
        fun supprime(e: Entree) { File(dossier, e.id + ".passage").delete() }
    }

    /** Passes kept: a year of a keen operator, a few megabytes. */
    const val GARDE = 500

    /** Points of a pass, to check what was kept is meaningful (the replay needs a few). */
    fun couverture(e: Entree): Double {
        if (e.points.size < 2) return 0.0
        val trous = e.points.zipWithNext().sumOf { (a, b) -> max(0L, b.tMs - a.tMs - 3 * PAS_MS) }
        return 1.0 - trous.toDouble() / e.dureeMs.coerceAtLeast(1)
    }
}
