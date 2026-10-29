/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.domain.JournalPassage
import fr.f4ioz.satcombo.domain.JournalPassage.Marque
import fr.f4ioz.satcombo.domain.JournalPassage.TypeMarque
import fr.f4ioz.satcombo.pc.RenduPc.texte
import fr.f4ioz.satcombo.sstv.Gif
import java.awt.BasicStroke
import java.awt.Color
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The journal's session as a video: one satellite's passes one after
 * another on the globe, as the page's « Session » plays them — or a single
 * pass, the same way (a session of one). Each pass from some minutes before
 * its AOS to some after its LOS ([Session.avant], [Session.apres], 30 s by
 * default: the satellite seen coming), drawn as the pass's own video draws it
 * ([RenduPc.image], its overlays); between two, a short turn of the globe
 * with the next one's card (« passages ») or the time running on, faster
 * outside the passes (« continu »). Three paces, eased from one to the
 * next: outside, in a pass, and slow while a picture is being received (it
 * is seen being drawn) or a contact is going on. Under the caption the pass
 * and the pictures received so far, a strip of them when asked; the
 * session's card first, its closing card last ([BilanPc]). Two zooms, eased
 * from one to the other as the pace is: from afar outside the passes, near
 * in a pass ([zoom]). Other satellites going round too when chosen. With
 * the sound asked, a contact heard is played in real time with its sound,
 * the rest silent ([son]); also as a GIF, or one moment as a picture.
 */
object SessionRenduPc {

    /** Frames a second: the globe turns smoothly enough between two passes. */
    const val FPS = 8
    /** Before the AOS and after the LOS, by default (ms); at most [MARGE_MAX_MS]. */
    const val MARGE_MS = 30_000L
    const val MARGE_MAX_MS = 30 * 60_000L
    /** The turn between two passes (« passages »), by default, in seconds. */
    const val TRANSITION_S = 1.5
    /** The old « entre » by default: outside the passes this many times « vitesse ». */
    const val HORS = 10.0
    /** A picture being received, a contact going on: slowed from this long before to this long after (ms). */
    const val AUTOUR_MS = 3_000L
    /** A contact without a span: this long around its moment (ms). */
    const val CONTACT_MS = 20_000L
    /**
     * How fast the pace may change: by e per this many seconds of video (log-linear) — from a
     * pass's ×20 to a picture's ×3 in about 1 s, from ×1000 outside to ×20 in about 2 s.
     */
    const val EASE = 2.0
    /**
     * The same outside the passes (before the AOS, after the LOS, between two — the time where I am not under the
     * satellite): much quicker, so a few minutes of margin reach « Hors passage » (from ×20 to ×1000 in about 100 s of
     * real time); inside AOS → LOS the gentle [EASE] keeps, so nothing there is jumped over.
     */
    const val EASE_HORS = 10.0
    /** The zooms by default (the globe's size, 1: 85 % of the view's height): outside the passes, in a pass. */
    const val Z_HORS = 0.9
    const val Z_PASSAGE = 1.8
    /** From one zoom to the other in about this many seconds of video, before and after a pass. */
    const val ZOOM_S = 1.5

    /**
     * « Son accéléré »: a recording is heard up to this pace (×4, the page's « Vitesse » included), sped up with its pitch
     * kept (the page: playbackRate with preservesPitch; the video: ffmpeg's atempo); faster, it is muted.
     */
    const val SON_MAX = 4.0

    /** The turn's frames for [secondes]. */
    fun tourne(secondes: Double) = Math.round(secondes * FPS).toInt().coerceAtLeast(1)

    /** The three paces (real time per video time): outside the passes, in a pass, while a picture or a contact comes. */
    data class Vitesses(val hors: Double = 1000.0, val passage: Double = 20.0, val image: Double = 3.0)

    /**
     * The request's words: the passes (ids, in the page's order), the mode, the paces ([v]), the
     * title, the strip, the turn's length ([transition] s, « passages »).
     */
    data class Parametres(val ids: List<String>, val mode: String, val v: Vitesses, val titre: String?, val vignettes: Boolean,
                          val transition: Double = TRANSITION_S, val zHors: Double = Z_HORS, val zPassage: Double = Z_PASSAGE,
                          /** The companions (satellites' names), their footprints or not. */
                          val autres: List<String> = emptyList(), val tachesAutres: Boolean = false,
                          /** What is made: « video », « gif », « image » (the moment [instant]), « son »; the contacts' sound or not. */
                          val quoi: String = "video", val instant: Long? = null, val son: Boolean = false,
                          /** Before each AOS, after each LOS (ms). */
                          val avant: Long = MARGE_MS, val apres: Long = MARGE_MS,
                          /** The page's « Vitesse » (×1, ×2, ×4): every pace times this, as the preview plays it ([LECTURE_MAX] at most). */
                          val lecture: Double = 1.0,
                          /** The globe's camera chosen on the page ([GlobeRenduPc.CAMERAS_SKED]: « espace » by default). */
                          val camera: String = "espace",
                          /** « Durée du bilan »: how long the closing card stays (s, [BilanPc.duree]). */
                          val bilan: Double = BilanPc.DUREE_S,
                          /** « Son accéléré » ([SON_MAX]): on unless refused. */
                          val sonAccelere: Boolean = true)

    /** The page's « Vitesse » at most (and 1/[LECTURE_MAX] at least). */
    const val LECTURE_MAX = 16.0

    /** A margin (ms) from « margeAvant » / « margeApres », else « avant » / « apres »: 0 to 30 min, 30 s when missing or wrong. */
    fun marge(p: (String) -> String, nom: String, ancien: String): Long =
        (p(nom).trim().ifEmpty { p(ancien).trim() }).toDoubleOrNull()?.takeIf { it.isFinite() }?.toLong()?.coerceIn(0L, MARGE_MAX_MS) ?: MARGE_MS

    fun parametres(p: (String) -> String): Parametres {
        val mode = p("mode").trim().takeIf { it in setOf("passages", "continu") } ?: "passages"
        fun nombre(k: String) = p(k).trim().toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
        // The old words when the new ones are missing: vPassage = vitesse, vHors = vitesse × entre.
        val vitesse = nombre("vitesse")
        val entre = p("entre").trim().toDoubleOrNull()?.takeIf { it.isFinite() }?.coerceIn(1.0, 100.0) ?: HORS
        val d = Vitesses()
        return Parametres(
            ids = p("ids").split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(1000),
            mode = mode,
            v = Vitesses(hors = (nombre("vHors") ?: vitesse?.let { it * entre } ?: d.hors).coerceIn(1.0, 20_000.0),
                passage = (nombre("vPassage") ?: vitesse ?: d.passage).coerceIn(1.0, 500.0),
                image = (nombre("vImage") ?: d.image).coerceIn(0.5, 100.0)),
            titre = p("titre").trim().take(80).ifBlank { null },
            vignettes = p("vignettes").trim() == "1",
            transition = p("transition").trim().toDoubleOrNull()?.takeIf { it.isFinite() }?.coerceIn(0.5, 6.0) ?: TRANSITION_S,
            zHors = nombre("zHors")?.coerceIn(0.5, 3.0) ?: Z_HORS, zPassage = nombre("zPassage")?.coerceIn(0.5, 3.0) ?: Z_PASSAGE,
            autres = p("autres").split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(GlobeRenduPc.COULEURS_AUTRES.size),
            tachesAutres = p("tachesAutres").trim() == "1",
            quoi = p("quoi").trim().takeIf { it in setOf("video", "gif", "image", "son") } ?: "video",
            instant = p("t").trim().toLongOrNull(), son = p("avecSon").trim() == "1",
            avant = marge(p, "margeAvant", "avant"), apres = marge(p, "margeApres", "apres"),
            lecture = p("lecture").trim().toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }?.coerceIn(1 / LECTURE_MAX, LECTURE_MAX) ?: 1.0,
            camera = GlobeRenduPc.cameraDemandee(p("cam")), bilan = BilanPc.duree(p),
            sonAccelere = p("sonAccelere").trim() != "0")
    }

    /** What a session's video shows: its passes' scenes (sorted by time), how it plays, the cards asked. */
    class Session(val scenes: List<RenduPc.Scene>, val mode: String, val v: Vitesses = Vitesses(), val titre: String? = null, val vignettes: Boolean = false,
                  val ouverture: Boolean = true, val recap: Boolean = true, val transition: Double = TRANSITION_S,
                  val zHors: Double = Z_HORS, val zPassage: Double = Z_PASSAGE,
                  val autres: List<GlobeRenduPc.Autre> = emptyList(), val tachesAutres: Boolean = false, val son: Boolean = false,
                  val avant: Long = MARGE_MS, val apres: Long = MARGE_MS,
                  /**
                   * The stretch chosen on the page (de..a): each window cut to it — the passes given must reach it
                   * ([touche]); played in real time with its sound, as a pass's stretch always was.
                   */
                  val morceau: LongRange? = null,
                  /** The accelerated replay: its stretches' paces (1: real time with the sound, else faster), in place of the paces in the passes. */
                  accelere: List<JournalPassage.Segment>? = null,
                  /** Every pace times this (the page's « Vitesse »). */
                  val lecture: Double = 1.0,
                  /** The globe's camera ([GlobeRenduPc.centreCamera]). */
                  val camera: String = "espace",
                  /** How long the closing card stays (s): « Durée du bilan ». */
                  val bilan: Double = BilanPc.DUREE_S,
                  /** « Son accéléré »: the recordings heard sped up to [SON_MAX], their pitch kept; the passes recorded slowed to it. */
                  val sonAccelere: Boolean = true,
                  /** Where a recording is (real time); null: each pass with recordings, AOS to LOS. */
                  enregistres: List<LongRange>? = null,
                  /** Where something is heard in the recordings (their measured activity); null: the whole recordings. */
                  val entendus: List<LongRange>? = null) {
        val fenetres: List<LongRange> = scenes.map { (it.e.debutMs - avant.coerceIn(0L, MARGE_MAX_MS))..(it.e.finMs + apres.coerceIn(0L, MARGE_MAX_MS)) }
            .map { f -> morceau?.let { m -> maxOf(f.first, m.first)..minOf(f.last, m.last) } ?: f }
        /**
         * The paces imposed in the windows (the stretch's real time, or the accelerated replay's); null: the three paces.
         * Skipping the silences, a normal stretch runs in real time to be heard — with the sound asked and a recording
         * there; else at the pace of a picture or a contact ([Vitesses.image], vitesse 0 here): never slower than needed.
         */
        val imposes: List<JournalPassage.Segment>? = morceau?.let { listOf(JournalPassage.Segment(it.first, it.last, 1)) } ?: accelere?.map { g ->
            val entendu = son && scenes.any { sc -> sc.e.enregistrements.isNotEmpty() && g.deMs <= sc.e.finMs + apres && g.aMs >= sc.e.debutMs - avant }
            if (g.vitesse <= 1 && !entendu) JournalPassage.Segment(g.deMs, g.aMs, 0) else g
        }?.sortedBy { it.deMs }
        /** Where a recording can be heard: the sound asked, the recordings' spans. */
        val enregistres: List<LongRange> = if (!son) emptyList()
            else enregistres ?: scenes.filter { it.e.enregistrements.isNotEmpty() }.map { it.e.debutMs..it.e.finMs }
        /**
         * Where the pace is held down to [SON_MAX] to be heard (« Son accéléré », no stretch imposed: the silences skipped
         * keep their own paces, a stretch chosen runs in real time): where something is heard in them (a recording's
         * silence keeps the paces chosen, unheard), else, the heard parts unknown, the recordings' spans.
         */
        val plafonds: List<LongRange> get() = if (sonAccelere && imposes == null) entendus ?: enregistres else emptyList()
        /** The passes themselves, AOS to LOS: the zoom comes near around them (the satellite seen coming from afar in the margin). */
        val passages: List<LongRange> = scenes.map { it.e.debutMs..it.e.finMs }
        /** A single pass: its own title, cards and summary, its own caption (as its video has them), the session's pace and zooms. */
        val seul get() = scenes.size == 1
        /** The pictures of the session, each once (a picture tied to two passes close together), by the time it was received. */
        val images: List<Pair<Marque, BufferedImage>> by lazy { scenes.flatMap { sc ->
            sc.marques.filter { it.type == TypeMarque.SSTV && it.fichier != null }.mapNotNull { m -> sc.images[m.fichier]?.let { m to it } }
        }.distinctBy { it.first.fichier }.sortedBy { it.first.finMs } }
        /** When the pace slows to [Vitesses.image]. */
        val actifs: List<LongRange> = actifs(scenes)
        /** With the sound: when a contact is heard (or a real-time stretch imposed), played in real time. */
        val ecoute: List<LongRange> = if (!son) emptyList() else imposes?.filter { it.vitesse == 1 }?.map { it.deMs..it.aMs } ?: ecoute(scenes)
        /** The first scene's: its format (16:9, 9:16 or 3:4) and resolution for every frame. */
        val format: RenduPc.Scene? get() = scenes.firstOrNull()
        val paysage get() = scenes.firstOrNull()?.paysage ?: true
        val resolution get() = scenes.firstOrNull()?.resolution ?: "M"
        init {
            // A picture received stays shown as long as in a pass's video at the pass's pace (the fastest imposed one).
            val k = imposes?.maxOfOrNull { it.vitesse }?.toDouble()?.coerceAtLeast(1.0)?.let { if (imposes.any { it.vitesse <= 0 }) maxOf(it, v.image) else it } ?: v.passage
            scenes.forEach { it.flashMs = (it.flashS * 1000.0 * k).toLong() }
        }
    }

    /** Does pass [e]'s window (from [avant] ms before its AOS to [apres] ms after its LOS) reach the stretch [m] (none: always)? */
    fun touche(e: JournalPassage.Entree, avant: Long, apres: Long, m: LongRange?): Boolean =
        m == null || (e.debutMs - avant.coerceIn(0L, MARGE_MAX_MS) <= m.last && e.finMs + apres.coerceIn(0L, MARGE_MAX_MS) >= m.first)

    /**
     * When something is coming: each SSTV picture being received (3 s before to 3 s after),
     * each contact of a pass with its sound recorded (its span and 3 s, or 20 s around it).
     */
    fun actifs(scenes: List<RenduPc.Scene>): List<LongRange> = scenes.flatMap { sc ->
        sc.marques.mapNotNull { m ->
            when {
                m.type == TypeMarque.SSTV -> (m.debutMs - AUTOUR_MS)..(m.finMs + AUTOUR_MS)
                m.type == TypeMarque.QSO && sc.e.enregistrements.isNotEmpty() ->
                    if (m.finMs > m.debutMs) (m.debutMs - AUTOUR_MS)..(m.finMs + AUTOUR_MS) else (m.debutMs - CONTACT_MS / 2)..(m.debutMs + CONTACT_MS / 2)
                else -> null
            }
        }
    }.sortedBy { it.first }

    /** The contacts heard (their pass has its sound): their span and 3 s, or 20 s around them. */
    fun ecoute(scenes: List<RenduPc.Scene>): List<LongRange> = scenes.flatMap { sc ->
        if (sc.e.enregistrements.isEmpty()) emptyList()
        else sc.marques.filter { it.type == TypeMarque.QSO }.map { m ->
            if (m.finMs > m.debutMs) (m.debutMs - AUTOUR_MS)..(m.finMs + AUTOUR_MS) else (m.debutMs - CONTACT_MS / 2)..(m.debutMs + CONTACT_MS / 2)
        }
    }.sortedBy { it.first }

    /** A frame of the session: in a pass ([i], at [t]), between two (« continu », after [i]), or turning to pass [i] (« passages »). */
    sealed class Trame {
        abstract val t: Long
        data class Passe(val i: Int, override val t: Long) : Trame()
        data class Entre(val i: Int, override val t: Long, val k: Double) : Trame()
        data class Tourne(val i: Int, override val t: Long, val k: Double) : Trame()
    }

    /**
     * The pace at each moment: the timeline cut into stretches each with its pace (« Hors passage » outside AOS → LOS —
     * the margins before the AOS and after the LOS, between two passes in « continu » —, « Pendant le passage » from
     * the AOS to the LOS, a picture or a contact coming), and the pace at [t] the lowest of each stretch's plus its
     * distance weighed by [EASE] per second inside the passes, [EASE_HORS] outside: it slows down before reaching a
     * slower stretch and speeds up after it, gently in a pass, quickly outside.
     */
    class Allure(fenetres: List<LongRange>, actifs: List<LongRange>, continu: Boolean, val v: Vitesses, ecoute: List<LongRange> = emptyList(),
                 /** Imposed in the windows: each stretch's pace (the accelerated replay, a stretch in real time); elsewhere « Pendant le passage » in a pass, « Hors passage » in the margins. */
                 imposes: List<JournalPassage.Segment>? = null,
                 /** Every pace times this (the page's « Vitesse »): above 1, nothing runs in real time. */
                 val lecture: Double = 1.0,
                 /**
                  * Where the pace is held to [SON_MAX] with the « Vitesse » ([Session.plafonds]: a recording heard sped up):
                  * at most [SON_MAX] / [lecture] there — none when the « Vitesse » itself goes past [SON_MAX].
                  */
                 plafonds: List<LongRange> = emptyList(),
                 /**
                  * The passes themselves, AOS to LOS ([Session.passages]): « Pendant le passage » and the gentle [EASE]
                  * inside, « Hors passage » and [EASE_HORS] outside; the heard sound held to [SON_MAX] only inside.
                  * Null: the windows themselves (each a pass without margins).
                  */
                 passages: List<LongRange>? = null) {
        /** The passes merged (from, to exclusive), sorted, and the length of passes before each. */
        private val pDe: LongArray; private val pA: LongArray; private val pAvant: LongArray
        /** Where the distances are counted from (precision). */
        private val origine: Long = fenetres.minOfOrNull { it.first } ?: 0L
        /** The stretches (from, to, pace before [lecture]): the page shades them on its timeline. */
        val morceaux: List<Triple<Long, Long, Double>>
        /** Each stretch's ends on the weighed scale ([cout]). */
        private val cDe: DoubleArray; private val cA: DoubleArray
        init {
            val fusion = ArrayList<LongArray>()
            (passages ?: fenetres).filter { it.last >= it.first }.sortedBy { it.first }.forEach { r ->
                val d = fusion.lastOrNull()
                if (d != null && r.first <= d[1]) d[1] = maxOf(d[1], r.last + 1) else fusion += longArrayOf(r.first, r.last + 1)
            }
            pDe = LongArray(fusion.size) { fusion[it][0] }; pA = LongArray(fusion.size) { fusion[it][1] }
            pAvant = LongArray(fusion.size); for (k in 1 until fusion.size) pAvant[k] = pAvant[k - 1] + (pA[k - 1] - pDe[k - 1])
            val bornes = sortedSetOf<Long>()
            fenetres.forEach { bornes += it.first; bornes += it.last + 1 }
            // AOS and LOS: « Hors passage » before, « Pendant le passage » from one to the other.
            for (k in pDe.indices) for (x in longArrayOf(pDe[k], pA[k])) if (fenetres.any { x > it.first && x <= it.last }) bornes += x
            val dedans = (if (imposes != null) imposes.map { it.deMs..it.aMs } else actifs + ecoute)
            (dedans + plafonds).forEach { a -> if (fenetres.any { a.first <= it.last && a.last >= it.first }) { bornes += a.first; bornes += a.last + 1 } }
            val plafond = if (plafonds.isEmpty() || lecture > SON_MAX) Double.MAX_VALUE else SON_MAX / lecture
            val l = ArrayList<Triple<Long, Long, Double>>()
            val b = bornes.toList()
            for (k in 0 until b.size - 1) {
                val m = (b[k] + b[k + 1]) / 2
                val dans = fenetres.any { m in it }
                val passe = dansPasse(m)
                val base = if (passe) v.passage else v.hors
                val r = when {
                    !dans -> if (continu) v.hors else continue
                    // The silences skipped run at their speed in the pass; in the margins « Hors passage » (what is heard there keeps its real time).
                    imposes != null -> imposes.firstOrNull { m in it.deMs..it.aMs }?.takeIf { passe || it.vitesse <= 1 }?.vitesse?.let { if (it <= 0) v.image else it.toDouble().coerceAtLeast(1.0) } ?: base
                    // A contact heard: in real time (its sound only plays there, never slowed nor sped up).
                    ecoute.any { m in it } -> 1.0; actifs.any { m in it } -> v.image; else -> base }.let { r0 ->
                        // The sound held to ×4 only from the AOS to the LOS: in the margins « Hors passage » wins.
                        if (dans && passe && plafonds.any { m in it }) minOf(r0, plafond) else r0 }
                val der = l.lastOrNull()
                if (der != null && der.third == r && der.second == b[k]) l[l.size - 1] = Triple(der.first, b[k + 1], r) else l += Triple(b[k], b[k + 1], r)
            }
            morceaux = l
            cDe = DoubleArray(l.size) { cout(l[it].first) }; cA = DoubleArray(l.size) { cout(l[it].second) }
        }
        /** Is [t] in a pass (AOS to LOS)? */
        fun dansPasse(t: Long): Boolean {
            var lo = 0; var hi = pDe.size - 1
            while (lo <= hi) { val k = (lo + hi) ushr 1; if (t < pDe[k]) hi = k - 1 else if (t >= pA[k]) lo = k + 1 else return true }
            return false
        }
        /** How long of the passes lies before [t] (ms). */
        private fun avantDans(t: Long): Long {
            var lo = 0; var hi = pDe.size - 1; var k = -1
            while (lo <= hi) { val m = (lo + hi) ushr 1; if (pDe[m] <= t) { k = m; lo = m + 1 } else hi = m - 1 }
            return if (k < 0) 0L else pAvant[k] + (minOf(t, pA[k]) - pDe[k])
        }
        /** [t] on the weighed scale: [EASE] per second inside the passes, [EASE_HORS] outside (the pace's change from there to here). */
        fun cout(t: Long): Double { val dedans = avantDans(t); return (EASE * dedans + EASE_HORS * (t - origine - dedans)) / 1000.0 }
        /** Real time per video time at [t]. */
        fun a(t: Long): Double {
            var r = Double.MAX_VALUE
            val c = cout(t); val c1 = cout(t + 1)
            for (k in morceaux.indices) {
                val (de, a, v) = morceaux[k]
                val d = if (t < de) cDe[k] - c else if (t >= a) c1 - cA[k] else 0.0
                r = minOf(r, v + d)
            }
            return (if (r == Double.MAX_VALUE) v.passage else r) * lecture
        }
        /** Real time there: its sound is heard (the page and the video, the same rule). */
        fun reel(t: Long): Boolean = kotlin.math.abs(a(t) - 1.0) < 1e-4
        /** Its sound heard at [t]: in real time, or sped up to [SON_MAX] with « Son accéléré » ([accelere]). */
        fun entendu(t: Long, accelere: Boolean): Boolean = if (accelere) a(t) <= SON_MAX + 1e-4 else reel(t)
    }

    /** Every frame of the session's passes, [fps] a second, each moment at its pace ([Allure]). */
    fun plan(fenetres: List<LongRange>, actifs: List<LongRange>, mode: String, v: Vitesses, fps: Int = FPS, transition: Double = TRANSITION_S,
             ecoute: List<LongRange> = emptyList(), imposes: List<JournalPassage.Segment>? = null, lecture: Double = 1.0,
             plafonds: List<LongRange> = emptyList(), passages: List<LongRange>? = null): List<Trame> {
        if (fenetres.isEmpty()) return emptyList()
        val allure = Allure(fenetres, actifs, mode == "continu", v, ecoute, imposes, lecture, plafonds, passages)
        fun pas0(t: Long) = (1000.0 / fps * allure.a(t)).toLong().coerceAtLeast(1)
        // Outside the passes the pace changes quicker than a frame ([EASE_HORS]): a frame's step never more than twice the
        // step where it lands — slowing down before the AOS, it never jumps into the pass (nor over a slower stretch).
        fun pas(t: Long): Long {
            val p = pas0(t)
            if (2 * pas0(t + p) >= p) return p
            var lo = 1L; var hi = p
            while (hi - lo > 1) { val m = (lo + hi) / 2; if (2 * pas0(t + m) >= m) lo = m else hi = m }
            return lo
        }
        val l = ArrayList<Trame>()
        if (mode == "continu") {
            val fin = fenetres.maxOf { it.last }
            var t = fenetres.first().first
            while (true) {
                val i = fenetres.indexOfFirst { t in it }
                if (i >= 0) { l += Trame.Passe(i, t); if (t >= fin) break; t = minOf(t + pas(t), fin); continue }
                val avant = fenetres.indexOfLast { it.last < t }
                val apres = fenetres.indexOfFirst { it.first > t }
                if (avant < 0 || apres < 0) break
                val de = fenetres[avant].last; val a = fenetres[apres].first
                l += Trame.Entre(avant, t, (t - de).toDouble() / (a - de).coerceAtLeast(1))
                t = minOf(t + pas(t), a)
            }
        } else {
            fenetres.forEachIndexed { i, f ->
                val n = tourne(transition)
                if (i > 0) for (k in 1..n) l += Trame.Tourne(i, f.first, k.toDouble() / (n + 1))
                var t = f.first
                while (t < f.last) { l += Trame.Passe(i, t); t += pas(t) }
                l += Trame.Passe(i, f.last)
            }
        }
        return l
    }

    fun plan(s: Session): List<Trame> = plan(s.fenetres, s.actifs, s.mode, s.v, FPS, s.transition, s.ecoute, s.imposes, s.lecture, s.plafonds, s.passages)
    fun allure(s: Session) = Allure(s.fenetres, s.actifs, s.mode == "continu", s.v, s.ecoute, s.imposes, s.lecture, s.plafonds, s.passages)

    // ---------------------------------------------------------------- the timeline, the page's and the video's

    /**
     * The video's timeline, frame by frame: [debut] frames of cards, then [plan] (each frame its real time), then
     * [fin] frames of cards, [fps] a second. The journal page plays its preview on it (« /chronologie ») and the
     * video is made from it: both the same, whatever the paces, the margins, the accelerated replay, the « Vitesse ».
     */
    class Chronologie(val fps: Int, val debut: Int, val plan: List<Trame>, val fin: Int, val allure: Allure) {
        val trames get() = debut + plan.size + fin
        /** How long the video lasts (s). */
        val secondes get() = trames.toDouble() / fps
        /** How long the passes play in it, the cards left out (s). */
        val secondesPassages get() = plan.size.toDouble() / fps
        /**
         * The real time shown [s] seconds into the passes' part (the cards left out): between two frames of a pass, in
         * proportion; on a turn to the next pass, its window's start; null past the end.
         */
        fun reel(s: Double): Long? {
            if (plan.isEmpty() || s < 0) return plan.firstOrNull()?.t
            val f = s * fps; val k = f.toInt()
            if (k >= plan.size) return null
            val a = plan[k]; val b = plan.getOrNull(k + 1) ?: return a.t
            if (a is Trame.Tourne || b is Trame.Tourne || b.t < a.t) return a.t
            return a.t + Math.round((b.t - a.t) * (f - k))
        }
        /** Seconds into the passes' part where the real time [t] is first shown (the inverse of [reel]); the end when past. */
        fun video(t: Long): Double {
            if (plan.isEmpty() || t <= plan[0].t) return 0.0
            // In order (two windows overlapping, long margins: the first time it is shown).
            val k = plan.indexOfFirst { it.t >= t }.takeIf { it > 0 } ?: return (plan.size - 1).toDouble() / fps
            val a = plan[k - 1]; val b = plan[k]
            return (k - 1 + if (b.t > a.t && a !is Trame.Tourne && b !is Trame.Tourne) (t - a.t).toDouble() / (b.t - a.t) else 1.0) / fps
        }
    }

    /**
     * The timeline as the journal page reads it ([fr.f4ioz.satcombo.demo.ChronologieJs]): its frames a second, the
     * cards' frames before (« debut ») and after (« fin »), its length (« secondes »), the first frame's real time
     * (« t0 ») and each next one's step (« dt », ms), each frame's pass (« i ») and kind (« k »: « p » in a pass, « e »
     * between two, « t » turning to the next), the paces' stretches (« pieces »: from, to, pace with the « Vitesse »).
     */
    fun json(c: Chronologie, s: Session): String = buildString {
        val l = c.plan
        append("{\"ok\":true,\"fps\":").append(c.fps).append(",\"debut\":").append(c.debut).append(",\"fin\":").append(c.fin)
        append(",\"secondes\":").append("%.3f".format(Locale.US, c.secondes)).append(",\"lecture\":").append(s.lecture)
        append(",\"camera\":\"").append(s.camera.filter { it.isLetter() }).append("\",\"son\":").append(s.son)
        append(",\"sonAccelere\":").append(s.sonAccelere).append(",\"sonMax\":").append(SON_MAX)
        append(",\"t0\":").append(l.firstOrNull()?.t ?: 0)
        append(",\"dt\":[")
        for (k in 1 until l.size) { if (k > 1) append(','); append(l[k].t - l[k - 1].t) }
        append("],\"i\":[")
        l.forEachIndexed { k, tr -> if (k > 0) append(','); append(tr.i()) }
        append("],\"k\":\"")
        l.forEach { append(when (it) { is Trame.Passe -> 'p'; is Trame.Entre -> 'e'; is Trame.Tourne -> 't' }) }
        append("\",\"pieces\":[")
        c.allure.morceaux.forEachIndexed { k, (d, f, v) -> if (k > 0) append(','); append('[').append(d).append(',').append(f).append(',').append(v * s.lecture).append(']') }
        append("]}")
    }
    private fun Trame.i() = when (this) { is Trame.Passe -> i; is Trame.Entre -> i; is Trame.Tourne -> i }

    fun chronologie(s: Session): Chronologie =
        Chronologie(FPS, debut(s, 1, 1).sumOf { it.second }, plan(s), fin(s, 1, 1).sumOf { it.second }, allure(s))

    // ---------------------------------------------------------------- the zoom

    /**
     * How much of the pass's zoom at [t] (1 in a pass, AOS to LOS — [Session.passages] —, 0 far from it): by the
     * video's seconds still to go to the nearest pass at its pace slowing down ([Allure]: outside the passes its pace
     * plus [EASE_HORS] per second away, « Hors passage » at most), over [ZOOM_S] — the camera comes near before the
     * AOS, goes away after the LOS, however fast the time runs between. The journal page works it out the same way.
     */
    fun partPassage(fenetres: List<LongRange>, t: Long, vPassage: Double, vHors: Double = Double.MAX_VALUE): Double {
        if (fenetres.isEmpty()) return 1.0
        val d = fenetres.minOf { f -> if (t < f.first) f.first - t else if (t > f.last) t - f.last else 0L } / 1000.0
        return (1 - secondesJusquA(d, vPassage, vHors) / ZOOM_S).coerceIn(0.0, 1.0)
    }

    /** The video's seconds over [d] s of real time before a pass at [vPassage], the pace rising by [EASE_HORS] a second, [vHors] at most. */
    fun secondesJusquA(d: Double, vPassage: Double, vHors: Double): Double {
        if (vHors <= vPassage) return d / vHors
        val rampe = (vHors - vPassage) / EASE_HORS
        return if (d <= rampe) kotlin.math.ln((vPassage + EASE_HORS * d) / vPassage) / EASE_HORS
        else kotlin.math.ln(vHors / vPassage) / EASE_HORS + (d - rampe) / vHors
    }

    /** The globe's size from the part of the pass's zoom [k]: eased, from [zHors] to [zPassage] (geometrically, as a camera comes nearer). */
    fun zoom(zHors: Double, zPassage: Double, k: Double): Double = zHors * Math.pow(zPassage / zHors, doux(k))

    /**
     * The globe's size for a frame: as [partPassage] says from the passes' AOS and LOS; turning from one pass to the
     * next (« passages »), out and back in to where the next window starts (far still when its margin is long).
     */
    fun zoom(s: Session, tr: Trame): Double = zoom(s.zHors, s.zPassage, when (tr) {
        is Trame.Tourne -> (1 - sin(PI * tr.k)) * partPassage(s.passages,
            if (tr.k < 0.5) s.fenetres[tr.i - 1].last else s.fenetres[tr.i].first, s.v.passage, s.v.hors)
        else -> partPassage(s.passages, tr.t, s.v.passage, s.v.hors)
    })

    // ---------------------------------------------------------------- frames

    private fun Session.tz(): TimeZone = scenes.firstOrNull()?.fuseau ?: LegendePc.UTC
    private fun Session.k(): Float = scenes.firstOrNull()?.police ?: 1f
    private fun quand(s: Session, t: Long) = LegendePc.format("EEE d MMM HH:mm", s.tz(), t) + " " + LegendePc.zone(s.tz(), t)
    private fun jour(s: Session, t: Long) = LegendePc.format("d MMM yyyy", s.tz(), t)
    private fun hm(s: Session, t: Long) = LegendePc.format("dd/MM HH:mm", s.tz(), t)
    fun recues(n: Int) = when (n) { 0 -> "aucune image reçue"; 1 -> "1 image reçue"; else -> "$n images reçues" }

    private fun slerp(a: DoubleArray, b: DoubleArray, k: Double): DoubleArray {
        val d = (a[0] * b[0] + a[1] * b[1] + a[2] * b[2]).coerceIn(-1.0, 1.0)
        val ang = acos(d)
        val v = if (ang < 1e-6 || ang > PI - 1e-3) DoubleArray(3) { a[it] + (b[it] - a[it]) * k }
        else { val s = sin(ang); DoubleArray(3) { a[it] * sin((1 - k) * ang) / s + b[it] * sin(k * ang) / s } }
        val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).takeIf { it > 1e-9 } ?: return b
        return DoubleArray(3) { v[it] / l }
    }
    private fun doux(k: Double) = (1 - cos(PI * k.coerceIn(0.0, 1.0))) / 2

    /** One frame of the session [w]×[h]. */
    fun image(s: Session, tr: Trame, w: Int, h: Int): BufferedImage {
        // Where the globe looks: as the page's camera (its choice: the satellite followed, my station, both).
        fun c(k: Int, t: Long) = GlobeRenduPc.centreCamera(s.scenes[k], t, s.camera)
        val (i, centre) = when (tr) {
            is Trame.Passe -> tr.i to (if (s.camera == "espace") null else c(tr.i, tr.t))
            is Trame.Entre -> tr.i to slerp(c(tr.i, s.fenetres[tr.i].last), c(tr.i + 1, s.fenetres[tr.i + 1].first), doux(tr.k))
            is Trame.Tourne -> tr.i to slerp(c(tr.i - 1, s.fenetres[tr.i - 1].last), c(tr.i, s.fenetres[tr.i].first), doux(tr.k))
        }
        val sc = s.scenes[i]
        val dp = RenduPc.echelle(w, h)
        val bande = 44 * dp
        sc.margeBas = if (s.vignettes) bande + 8 * dp else 0f
        // Which pass, the pictures so far: in the caption, on the right.
        val k = if (tr is Trame.Entre) tr.i + 2 else i + 1
        val vues = s.images.filter { it.first.finMs <= tr.t }
        sc.legendeSession = if (s.seul) null else Triple("Passage $k / ${s.scenes.size}", recues(vues.size), vues.isNotEmpty())
        val haut = RenduPc.hautVue(w, h, sc).toFloat()
        sc.centreImpose = centre; sc.gzImpose = zoom(s, tr).toFloat(); sc.autres = s.autres; sc.tachesAutres = s.tachesAutres
        val b = try { RenduPc.image(w, h, sc, tr.t) } finally { sc.centreImpose = null; sc.gzImpose = null; sc.autres = emptyList() }
        val g = RenduPc.g2(b)
        if (s.vignettes && vues.isNotEmpty()) vignettes(g, vues, w.toFloat(), haut - bande - 6 * dp, bande, dp)
        if (tr is Trame.Tourne) carteTourne(g, s, tr, w.toFloat(), haut, dp)
        g.dispose()
        return b
    }

    /** The pictures received so far, small, along the bottom of the view, the newest on the right. */
    private fun vignettes(g: java.awt.Graphics2D, l: List<Pair<Marque, BufferedImage>>, w: Float, y: Float, hv: Float, dp: Float) {
        var x = w - 8 * dp
        for ((m, bm) in l.asReversed()) {
            // Its own proportions (320×256, 320×240, 640×496…), never stretched.
            val lv = hv * bm.width / bm.height.coerceAtLeast(1)
            x -= lv
            if (x < 8 * dp - lv * 0.5f) break
            g.drawImage(BilanPc.echelonne(bm, lv.toInt().coerceAtLeast(1), hv.toInt().coerceAtLeast(1)), x.toInt(), y.toInt(), null)
            g.color = RenduPc.SSTV; g.stroke = BasicStroke(1.5f * dp); g.draw(Rectangle2D.Float(x, y, lv, hv))
            val f = RenduPc.police(8 * dp, true); g.font = f
            val s = SimpleDateFormat("HH:mm", Locale.FRENCH).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(m.finMs))
            val lt = g.fontMetrics.stringWidth(s) + 4 * dp
            g.color = Color(10, 15, 24, 190); g.fill(Rectangle2D.Float(x + 1, y + hv - 11 * dp, lt, 10 * dp))
            g.texte(s, x + 2 * dp + 1, y + hv - 3 * dp, Color.WHITE, f)
            x -= 5 * dp
        }
    }

    /** The next pass's card while the globe turns to it: « Passage 3 / 9 · ven. 24 oct. 07:19 ». */
    private fun carteTourne(g: java.awt.Graphics2D, s: Session, tr: Trame.Tourne, w: Float, haut: Float, dp: Float) {
        val a = sin(PI * tr.k).coerceIn(0.0, 1.0).let { minOf(1.0, it * 1.6) }
        val e = s.scenes[tr.i].e
        val l1 = "Passage ${tr.i + 1} / ${s.scenes.size}"
        val l2 = quand(s, e.debutMs) + " · %.0f°".format(e.elMax)
        // At the asked scale, as wide as the view allows.
        var k = s.k()
        fun f1() = RenduPc.police(24 * dp * k, true)
        fun f2() = RenduPc.police(14 * dp * k)
        while (k > 0.5f && maxOf(g.getFontMetrics(f1()).stringWidth(l1), g.getFontMetrics(f2()).stringWidth(l2)) + 40 * dp * k > w - 16 * dp) k -= 0.05f
        val f1 = f1(); val f2 = f2()
        g.font = f1; val lw1 = g.fontMetrics.stringWidth(l1); g.font = f2; val lw2 = g.fontMetrics.stringWidth(l2)
        val lw = maxOf(lw1, lw2) + 40 * dp * k; val lh = 72 * dp * k
        val x = (w - lw) / 2; val y = haut / 2 - lh / 2
        g.color = Color(10, 15, 24, (235 * a).toInt()); g.fill(RoundRectangle2D.Float(x, y, lw, lh, 18 * dp, 18 * dp))
        g.color = Color(0, 229, 255, (230 * a).toInt()); g.stroke = BasicStroke(2f * dp); g.draw(RoundRectangle2D.Float(x, y, lw, lh, 18 * dp, 18 * dp))
        g.texte(l1, w / 2, y + 32 * dp * k, Color(255, 255, 255, (255 * a).toInt()), f1, centre = true)
        g.texte(l2, w / 2, y + 56 * dp * k, Color(205, 215, 230, (255 * a).toInt()), f2, centre = true)
    }

    /** The session's card: its title (or the satellite and the dates), its passes and pictures, the station. */
    fun titre(s: Session, w: Int, h: Int): BufferedImage {
        val b = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = RenduPc.g2(b)
        val dp = RenduPc.echelle(w, h)
        g.color = RenduPc.FOND; g.fillRect(0, 0, w, h)
        val e0 = s.scenes.first().e; val e1 = s.scenes.last().e
        val titre = s.titre ?: "Session SSTV · ${e0.satName}"
        // At the asked scale, each line smaller if it would not fit across.
        val k = s.k()
        fun ajuste(t: String, px: Float, gras: Boolean): java.awt.Font {
            var fs = px * k
            while (g.getFontMetrics(RenduPc.police(fs, gras)).stringWidth(t) > w - 24 * dp && fs > 9 * dp) fs -= dp
            return RenduPc.police(fs, gras)
        }
        var y = h * 0.30f - (k - 1) * 50 * dp
        g.texte(titre, w / 2f, y, Color.WHITE, ajuste(titre, 34f * dp, true), centre = true); y += 40 * dp * k
        val clair = Color(205, 215, 230)
        if (s.titre != null) { g.texte(e0.satName, w / 2f, y, RenduPc.CYAN, ajuste(e0.satName, 20 * dp, true), centre = true); y += 30 * dp * k }
        val dates = jour(s, e0.debutMs) + " → " + jour(s, e1.finMs)
        g.texte(dates, w / 2f, y, clair, ajuste(dates, 17 * dp, false), centre = true); y += 28 * dp * k
        val n = s.scenes.size
        val nb = (if (n == 1) "1 passage" else "$n passages") + " · " + recues(s.images.size)
        g.texte(nb, w / 2f, y, RenduPc.SSTV, ajuste(nb, 17 * dp, true), centre = true); y += 46 * dp * k
        val sc = s.scenes.first()
        listOf(sc.indicatif, e0.locator).filter { it.isNotBlank() }.joinToString(" · ").takeIf { it.isNotBlank() }?.let {
            g.texte(it, w / 2f, y, RenduPc.CYAN, ajuste(it, 20 * dp, true), centre = true)
        }
        MarquePc.dessine(g, w / 2f, h - 26 * dp, 5.2f * dp, centre = true, couleur = RenduPc.CYAN)
        g.dispose()
        return b
    }

    /** The closing card ([BilanPc]): the passes, their figures, their track in the sky, their pictures near their own size. */
    fun bilan(s: Session, w: Int, h: Int): BufferedImage = BilanPc.image(w, h, BilanPc.donnees(s.scenes, s.titre))

    /**
     * Before the passes, each drawing with its frames: the session's card 3 s; a single pass, its own title 3 s
     * and the report's opening cards 4 s each, as its video has them.
     */
    fun debut(s: Session, w: Int, h: Int): List<Pair<() -> BufferedImage, Int>> {
        val sc = s.scenes.firstOrNull() ?: return emptyList()
        val titre: List<Pair<() -> BufferedImage, Int>> = if (!s.ouverture) emptyList()
            else listOf((if (s.seul) { -> RenduPc.titre(w, h, sc) } else { -> titre(s, w, h) }) to 3 * FPS)
        return titre + if (s.seul) RenduPc.ouvertureVideo(w, h, sc, 4 * FPS) else emptyList()
    }

    /**
     * After the passes: the closing card ([bilan], « Durée du bilan », 7 s by default) when asked; a single pass, then
     * the report's cards 4 s each, as its video. In the timeline: its length is the video's.
     */
    fun fin(s: Session, w: Int, h: Int): List<Pair<() -> BufferedImage, Int>> {
        val l = ArrayList<Pair<() -> BufferedImage, Int>>()
        if (s.recap) l += { -> bilan(s, w, h) } to Math.round(s.bilan.coerceIn(BilanPc.DUREE_MIN_S, BilanPc.DUREE_MAX_S) * FPS).toInt()
        if (s.seul) s.scenes[0].cartesFin.forEach { k -> l += { -> RenduPc.carte(w, h, s.scenes[0], k) } to 4 * FPS }
        return l
    }

    /** How many frames the video holds: the card, the passes, the closing card. */
    fun trames(s: Session): Int = chronologie(s).trames

    /** The session as an MP4 (H.264, with [son]'s sound as AAC when given) through ffmpeg; null without it or when it failed. */
    fun video(s: Session, sortie: File, son: Son? = null, travail: File? = null, progres: (Float) -> Unit): File? {
        val ff = RenduPc.ffmpeg() ?: return null
        if (s.scenes.isEmpty()) return null
        val (w, h) = s.format?.let { RenduPc.tailleVideo(it) } ?: RenduPc.tailleVideo(s.paysage, s.resolution)
        // The timeline the page showed: the same frames, the same length.
        val c = chronologie(s)
        val plan = c.plan
        val avantP = debut(s, w, h); val apresP = fin(s, w, h)
        val total = c.trames
        val wav = if (son != null && travail != null) File(travail, "session-" + System.nanoTime() + ".wav").also { RenduPc.ecritWav(source(s, plan, son), son.rate, it) } else null
        try {
            return RenduPc.encode(ff, w, h, FPS, total, wav, sortie) { ecris ->
                var fait = 0
                for ((dessin, n) in avantP) { val img = dessin(); repeat(n) { ecris(img); progres(++fait / total.toFloat()) } }
                for (tr in plan) { ecris(image(s, tr, w, h)); progres(++fait / total.toFloat()) }
                for ((dessin, n) in apresP) { val img = dessin(); repeat(n) { ecris(img); progres(++fait / total.toFloat()) } }
            }
        } finally { wav?.delete() }
    }

    // ---------------------------------------------------------------- the sound

    /**
     * The passes' sounds, each from its window's start ([debuts], ms) at its rate; [rate]: the
     * video's. A pass without its sound is not in [sons].
     */
    class Son(val sons: Map<Int, fr.f4ioz.satcombo.domain.SonPassage>, val debuts: Map<Int, Long>, val rate: Int)

    /**
     * The session's sound as its video plays it: silence for its card, then, frame by frame, the pass's sound where it
     * is heard ([Allure.entendu]): in real time as it was (a contact heard, the accelerated replay's normal stretches,
     * a stretch chosen), sped up to [SON_MAX] with its pitch kept (« Son accéléré », ffmpeg's atempo — [atempo]),
     * silence elsewhere (the time running faster) and on the closing card. The frames heard are taken in runs of
     * one pass at about one pace (within [PAS_TEMPO]), each run's sound made to its exact length.
     */
    fun source(s: Session, plan: List<Trame>, son: Son, ff: String? = RenduPc.ffmpeg()): fr.f4ioz.satcombo.domain.SourcePcm {
        val nTitre = debut(s, 1, 1).sumOf { it.second }
        val pistes = pistes(s, plan, son, ff)
        val r = son.rate.toLong()
        fun debut(n: Int) = n * r / FPS
        val nb = nTitre + plan.size
        var n = 0; var dans = 0
        var cur = ShortArray(0)
        fun prepare(k: Int) {
            val l = (debut(k + 1) - debut(k)).toInt()
            cur = pistes[k - nTitre]?.takeIf { it.size == l } ?: ShortArray(l)
        }
        prepare(0)
        return fr.f4ioz.satcombo.domain.SourcePcm { buf ->
            var k = 0
            while (k < buf.size && n < nb) {
                val c = minOf(buf.size - k, cur.size - dans)
                System.arraycopy(cur, dans, buf, k, c); k += c; dans += c
                if (dans >= cur.size) { n++; dans = 0; if (n < nb) prepare(n) }
            }
            k
        }
    }

    /** Frames heard together, at about one pace: their sound made at once (the pace easing in and out cut in steps this fine). */
    const val PAS_TEMPO = 0.04

    /** Which frames of [plan] are heard, and each run of them: the pass, the frames (plan's indices), its pace (real per video time). */
    data class Course(val i: Int, val de: Int, val a: Int, val allure: Double)

    /** The frame [k]'s real span (ms): to the next frame of the same pass, else one frame at its pace. */
    private fun duree(plan: List<Trame>, k: Int, allure: Allure): Long {
        val tr = plan[k]; val nx = plan.getOrNull(k + 1)
        return if (nx is Trame.Passe && tr is Trame.Passe && nx.i == tr.i && nx.t > tr.t) nx.t - tr.t
        else Math.round(1000.0 / FPS * allure.a(tr.t)).coerceAtLeast(1)
    }

    /** The runs of frames heard ([source]): only in a pass with its sound, where [Allure.entendu] says so. */
    fun courses(s: Session, plan: List<Trame>, avecSon: (Int) -> Boolean): List<Course> {
        if (!s.son) return emptyList()
        val allure = allure(s)
        val l = ArrayList<Course>()
        var c: Course? = null
        for (k in plan.indices) {
            val tr = plan[k] as? Trame.Passe
            val ok = tr != null && avecSon(tr.i) && s.enregistres.any { tr.t in it } && allure.entendu(tr.t, s.sonAccelere)
            if (!ok) { c?.let { l += it }; c = null; continue }
            val p = duree(plan, k, allure) * FPS / 1000.0
            val cc = c
            c = if (cc != null && cc.i == tr!!.i && cc.a == k - 1 && kotlin.math.abs(p / cc.allure - 1) <= PAS_TEMPO) cc.copy(a = k)
            else { cc?.let { l += it }; Course(tr!!.i, k, k, p) }
        }
        c?.let { l += it }
        return l
    }

    /** Each frame heard's samples at the video's rate (plan's index → samples), as [source] hands them out. */
    private fun pistes(s: Session, plan: List<Trame>, son: Son, ff: String?): Map<Int, ShortArray> {
        val nTitre = debut(s, 1, 1).sumOf { it.second }
        val r = son.rate.toLong()
        fun debut(n: Int) = n * r / FPS
        val allure = allure(s)
        val out = HashMap<Int, ShortArray>()
        for (c in courses(s, plan) { son.sons.containsKey(it) && son.debuts.containsKey(it) }) {
            val sp = son.sons[c.i] ?: continue; val de = son.debuts[c.i] ?: continue
            val t0 = plan[c.de].t; val t1 = plan[c.a].t + duree(plan, c.a, allure)
            val l = (debut(nTitre + c.a + 1) - debut(nTitre + c.de)).toInt()
            val m = ((t1 - t0) * sp.rate / 1000).toInt().coerceAtLeast(1)
            val src = ShortArray(m); sp.lis(maxOf(0L, (t0 - de) * sp.rate / 1000), src, m)
            // As long as the frames: in real time the nearest sample (no pitch changed); faster, atempo's.
            val tempo = (m.toDouble() / sp.rate) / (l.toDouble() / r)
            val piste = when {
                kotlin.math.abs(tempo - 1) < 0.02 -> ShortArray(l) { src[(it.toLong() * m / l).toInt().coerceIn(0, m - 1)] }
                ff == null -> ShortArray(l)
                else -> tempo(ff, src, sp.rate, son.rate, tempo, l)
            }
            var pos = 0
            for (k in c.de..c.a) {
                val n = (debut(nTitre + k + 1) - debut(nTitre + k)).toInt()
                out[k] = piste.copyOfRange(pos, minOf(pos + n, piste.size)).let { if (it.size == n) it else it.copyOf(n) }
                pos += n
            }
        }
        return out
    }

    /**
     * ffmpeg's atempo filters for [tempo] (each takes 0.5 to 2): 3 → « atempo=2.0,atempo=1.5 », 4 →
     * « atempo=2.0,atempo=2.0 », 0.3 → « atempo=0.5,atempo=0.6 ».
     */
    fun atempo(tempo: Double): String {
        val l = ArrayList<Double>()
        var t = tempo.coerceIn(0.01, 100.0)
        while (t > 2.0 + 1e-9) { l += 2.0; t /= 2.0 }
        while (t < 0.5 - 1e-9) { l += 0.5; t /= 0.5 }
        l += t
        return l.joinToString(",") { "atempo=" + String.format(Locale.US, "%.6f", it).trimEnd('0').let { s -> if (s.endsWith('.')) s + "0" else s } }
    }

    /** [src] ([de] samples a second) sped up by [tempo] with its pitch kept (ffmpeg), at [vers] samples a second, [l] long. */
    fun tempo(ff: String, src: ShortArray, de: Int, vers: Int, tempo: Double, l: Int): ShortArray {
        val p = ProcessBuilder(ff, "-loglevel", "error", "-f", "s16le", "-ar", "$de", "-ac", "1", "-i", "-",
            "-af", atempo(tempo) + ",aresample=$vers", "-f", "s16le", "-ar", "$vers", "-ac", "1", "-").start()
        val ecrit = Thread {
            runCatching {
                val b = java.nio.ByteBuffer.allocate(src.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                src.forEach { b.putShort(it) }
                p.outputStream.use { it.write(b.array()) }
            }
        }.apply { isDaemon = true; start() }
        Thread { runCatching { p.errorStream.readBytes() } }.apply { isDaemon = true; start() }
        val lu = runCatching { p.inputStream.readBytes() }.getOrDefault(ByteArray(0))
        ecrit.join(2000); p.waitFor()
        val r = ShortArray(l)
        val bb = java.nio.ByteBuffer.wrap(lu).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until minOf(l, lu.size / 2)) r[i] = bb.getShort(i * 2)
        return r
    }

    /** [a], then [b] once [a] has ended. */
    fun suite(a: fr.f4ioz.satcombo.domain.SourcePcm, b: fr.f4ioz.satcombo.domain.SourcePcm): fr.f4ioz.satcombo.domain.SourcePcm {
        var premier = true
        return fr.f4ioz.satcombo.domain.SourcePcm { buf ->
            var n = 0
            if (premier) { n = a.lis(buf); if (n > 0) return@SourcePcm n; premier = false }
            b.lis(buf)
        }
    }

    /** [src] at [de] samples a second handed out at [vers] (the nearest sample: a recording's own rate to another's). */
    fun auTaux(src: fr.f4ioz.satcombo.domain.SourcePcm, de: Int, vers: Int): fr.f4ioz.satcombo.domain.SourcePcm {
        if (de == vers || de <= 0 || vers <= 0) return src
        val lu = ShortArray(4096); var n = 0; var base = 0L; var fin = false
        var k = 0L
        return fr.f4ioz.satcombo.domain.SourcePcm { buf ->
            var i = 0
            while (i < buf.size) {
                val pos = k * de / vers
                while (!fin && pos >= base + n) { base += n; n = src.lis(lu); if (n <= 0) { n = 0; fin = true } }
                if (pos >= base + n) break
                buf[i++] = lu[(pos - base).toInt()]; k++
            }
            i
        }
    }

    // ---------------------------------------------------------------- a GIF, a picture

    /** The frame showing the moment [t]: in the pass round it, or between two. */
    fun trameA(s: Session, t: Long): Trame {
        val f = s.fenetres
        val i = f.indexOfFirst { t in it }
        if (i >= 0) return Trame.Passe(i, t)
        val avant = f.indexOfLast { it.last < t }; val apres = f.indexOfFirst { it.first > t }
        if (avant < 0) return Trame.Passe(0, f.first().first)
        if (apres < 0) return Trame.Passe(f.size - 1, f.last().last)
        val de = f[avant].last; val a = f[apres].first
        return Trame.Entre(avant, t, (t - de).toDouble() / (a - de).coerceAtLeast(1))
    }

    /** The moment [t] of the session as a PNG picture, at the size of a pass's. */
    fun png(s: Session, t: Long, sortie: File): File {
        val (w, h) = s.format?.let { RenduPc.tailleImage(it) } ?: (1920 to 1080)
        javax.imageio.ImageIO.write(image(s, trameA(s, t), w, h), "png", sortie)
        return sortie
    }

    /** At most this many frames in a GIF (the session's frames taken one in so many). */
    const val GIF_MAX = 700

    /** The session as an animated GIF, as its video plays it (fewer frames, each longer), looping. */
    fun gif(s: Session, sortie: File, progres: (Float) -> Unit): File? {
        if (s.scenes.isEmpty()) return null
        val (w, h) = s.format?.let { RenduPc.tailleGif(it) } ?: (640 to 360)
        val plan = plan(s)
        val pas = ceil(plan.size / GIF_MAX.toDouble()).toInt().coerceAtLeast(1)
        // A GIF's delay in hundredths of a second, 2 at least (the browsers' floor).
        val delai = maxOf(2, Math.round(100.0 * pas / FPS).toInt())
        val choisies = plan.indices.filter { it % pas == 0 || it == plan.size - 1 }
        fun px(img: BufferedImage) = img.getRGB(0, 0, w, h, null, 0, w)
        // The card(s) before and after, each held as long as in the video (in hundredths of a second).
        val avantP = debut(s, w, h).map { (d, n) -> px(d()) to n * 100 / FPS }
        val apresP = fin(s, w, h).map { (d, n) -> px(d()) to n * 100 / FPS }
        var pourPalette = px(image(s, plan[plan.size / 2], w, h))
        avantP.firstOrNull()?.let { pourPalette += it.first }; apresP.firstOrNull()?.let { pourPalette += it.first }; apresP.lastOrNull()?.let { pourPalette += it.first }
        val pal = Gif.palette(pourPalette, listOf(0x0A0F18, 0x111B29, 0xFFFFFF, RenduPc.CYAN.rgb and 0xFFFFFF, RenduPc.SSTV.rgb and 0xFFFFFF, RenduPc.ISS.rgb and 0xFFFFFF))
        sortie.outputStream().buffered().use { o ->
            val g = Gif.Ecrivain(o, w, h, pal)
            avantP.forEach { (pixels, cs) -> g.trame(pixels, 0, h, cs) }
            var avant: IntArray? = null
            choisies.forEachIndexed { n, k ->
                val cur = px(image(s, plan[k], w, h))
                val prev = avant
                var y0 = 0; var y1 = h
                if (prev != null) {
                    fun egale(y: Int): Boolean { val b = y * w; for (x in 0 until w) if (prev[b + x] != cur[b + x]) return false; return true }
                    while (y0 < h && egale(y0)) y0++
                    while (y1 > y0 && egale(y1 - 1)) y1--
                    if (y0 >= y1) { y0 = 0; y1 = 1 }
                }
                g.trame(cur, y0, y1, delai)
                avant = cur
                progres((n + 1f) / choisies.size)
            }
            apresP.forEach { (pixels, cs) -> g.trame(pixels, 0, h, cs) }
            g.fin()
        }
        return sortie
    }
}
