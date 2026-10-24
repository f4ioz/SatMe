/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

import fr.f4ioz.satcombo.domain.ActivitesAVenir
import fr.f4ioz.satcombo.domain.CampagneSstv
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The SSTV event a sheet is about: an autopilot campaign (Agenda › SSTV),
 * an activity announced (« Activités à venir », an ARISS series), or — with
 * neither — the run of pictures of one satellite received a few days apart
 * (the series as the gallery saw it).
 *
 * The sheet keeps its event whole ([ecrit]): the campaign may be deleted,
 * the announcement gone from the feeds, the sheet still says what it was.
 * Chosen by itself ([choisit]) from the pictures' times, changed by hand.
 */
object EvenementSstv {

    enum class Genre { CAMPAGNE, ACTIVITE, IMAGES }

    data class Evenement(
        /** « c:<campaign id> », « a:<activity> », « i:<sat>:<day> »: stable, plain. */
        val id: String,
        val genre: Genre,
        val nom: String,
        val satNom: String,
        /** NORAD number, 0 unknown (no pass can be worked out then). */
        val catnum: Int,
        val debutMs: Long,
        val finMs: Long,
        /** Seconds between two slides, as announced. */
        val periodeS: Double = HoraireDiapos.PERIODE_ANNONCEE_S,
        val nb: Int = 12,
    ) {
        /** As the series' card and timetable take it. */
        fun serie(): SerieSstv.Serie = SerieSstv.Serie(nom, satNom, catnum, debutMs, finMs, periodeS, nb)
    }

    /** Two pictures of one satellite this far apart are two runs (an ARISS week has a pass every 90 min). */
    const val ECART_MS = 2 * 86_400_000L

    private fun propre(s: String, n: Int) = s.filter { it >= ' ' && it != ';' && it != '<' && it != '>' }.trim().take(n)

    private fun cle(s: String): String = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
        .replace(Regex("[^A-Za-z0-9-]+"), "-").trim('-').take(48)

    private fun normal(s: String) = s.uppercase().replace(Regex("\\(.*?\\)"), "").replace(Regex("[^A-Z0-9]"), "")

    private val jour = SimpleDateFormat("dd/MM", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    /**
     * The events SSTV pictures may belong to: the campaigns of the autopilot
     * (a satellite, a period) and the SSTV activities announced with their
     * satellite and dates.
     */
    fun liste(activites: List<ActivitesAVenir.Activite>, campagnes: List<CampagneSstv.Campagne>): List<Evenement> {
        val c = campagnes.filter { it.catnum > 0 && it.finMs > it.debutMs }.map {
            Evenement("c:" + cle(it.id), Genre.CAMPAGNE, propre(it.nom, 80), propre(it.satNom, 40), it.catnum, it.debutMs, it.finMs,
                HoraireDiapos.periode(it.horaire))
        }
        val a = activites.filter { it.type == ActivitesAVenir.Type.SSTV && it.catnum > 0 && it.precision != ActivitesAVenir.Precision.INCONNUE && it.finMs > it.debutMs }
            .map {
                Evenement("a:" + cle(it.id).ifBlank { it.debutMs.toString(36) }, Genre.ACTIVITE, propre(it.titre, 80), propre(it.satNom, 40), it.catnum,
                    it.debutMs, it.finMs, HoraireDiapos.periode(it.horaire))
            }
        return (c + a).distinctBy { it.id }
    }

    /**
     * The runs of pictures with no event of their own: one satellite's
     * pictures, less than [ECART_MS] apart. [catnumDe] names the satellite
     * (0 unknown).
     */
    fun depuisImages(images: List<SstvMeta.SstvShot>, catnumDe: (String) -> Int): List<Evenement> {
        val out = ArrayList<Evenement>()
        for ((sat, l) in images.filter { it.timeMs > 0 && it.satName.isNotBlank() }.groupBy { normal(it.satName) }) {
            if (sat.isEmpty()) continue
            val tri = l.sortedBy { it.timeMs }
            var debut = 0
            for (i in 1..tri.size) {
                if (i < tri.size && tri[i].timeMs - tri[i - 1].timeMs < ECART_MS) continue
                val de = tri[debut].timeMs; val a = tri[i - 1].timeMs
                val nom = tri[debut].satName
                out += Evenement("i:" + cle(nom) + ":" + (de / 86_400_000L), Genre.IMAGES,
                    propre(nom, 40) + " · SSTV " + jour.format(Date(de)) + (if (jour.format(Date(a)) != jour.format(Date(de))) "–" + jour.format(Date(a)) else ""),
                    propre(nom, 40), catnumDe(nom), de - 3_600_000L, a + 3_600_000L)
                debut = i
            }
        }
        return out
    }

    /** Is [s] a picture of [e]: its satellite, during it (an hour either side)? */
    fun dans(e: Evenement, s: SstvMeta.SstvShot): Boolean {
        val a = normal(s.satName); val b = normal(e.satNom)
        if (a.isEmpty() || b.isEmpty() || !(a.startsWith(b) || b.startsWith(a))) return false
        return s.timeMs in (e.debutMs - 3_600_000L)..(e.finMs + 3_600_000L)
    }

    /**
     * The event of [images] (a sheet's pictures, else the whole gallery): the
     * one holding most of them — a campaign of the autopilot before an
     * activity, before a run of pictures; of two alike, the shorter. With no
     * picture, the event under way at [t] (else the one ended last).
     */
    fun choisit(evenements: List<Evenement>, images: List<SstvMeta.SstvShot>, t: Long): Evenement? {
        if (evenements.isEmpty()) return null
        if (images.isNotEmpty()) {
            val compte = evenements.associateWith { e -> images.count { dans(e, it) } }
            return evenements.filter { (compte[it] ?: 0) > 0 }
                .sortedWith(compareByDescending<Evenement> { compte[it] ?: 0 }.thenBy { it.genre.ordinal }.thenBy { it.finMs - it.debutMs })
                .firstOrNull()
        }
        return evenements.sortedWith(compareByDescending<Evenement> { t in it.debutMs..it.finMs }.thenBy { if (it.debutMs > t) 1 else 0 }
            .thenByDescending { minOf(it.finMs, t) }.thenBy { it.genre.ordinal }).firstOrNull()
    }

    /** All the events: [l] ([liste]'s), and the runs of the gallery no event holds. */
    fun tous(l: List<Evenement>, images: List<SstvMeta.SstvShot>, catnumDe: (String) -> Int): List<Evenement> {
        val libres = images.filter { s -> l.none { dans(it, s) } }
        return (l + depuisImages(libres, catnumDe)).sortedByDescending { it.debutMs }
    }

    // ------------------------------------------------------------ the sheet's line

    /** One line of the sheet's file (`evenement=`): genre;catnum;start;end;slides;period;id;satellite;name. */
    fun ecrit(e: Evenement): String = listOf(e.genre.name, e.catnum, e.debutMs, e.finMs, e.nb, e.periodeS,
        e.id.replace(";", ","), propre(e.satNom, 40), propre(e.nom, 80)).joinToString(";")

    fun lit(v: String): Evenement? = runCatching {
        val p = v.split(';')
        if (p.size < 9) return null
        val id = p[6].trim()
        if (id.isEmpty() || id.length > 80) return null
        Evenement(id, Genre.valueOf(p[0]), p.drop(8).joinToString(",").take(80), p[7].take(40), p[1].toInt(), p[2].toLong(), p[3].toLong(),
            p[5].toDouble().takeIf { it in 40.0..1200.0 } ?: HoraireDiapos.PERIODE_ANNONCEE_S, p[4].toInt().coerceIn(1, 99))
    }.getOrNull()

    /** The kind of event, for the page: « Pilote auto », « Activité », « Images ». */
    fun genreCle(g: Genre): String = when (g) { Genre.CAMPAGNE -> "campagne"; Genre.ACTIVITE -> "activite"; Genre.IMAGES -> "images" }
}
