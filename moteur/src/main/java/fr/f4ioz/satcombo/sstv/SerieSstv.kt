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
import fr.f4ioz.satcombo.domain.ActivitesAVenir
import fr.f4ioz.satcombo.domain.CampagneSstv

/**
 * The SSTV series under way (an ARISS week): its window and cadence, from
 * the upcoming activities (exact dates, « 36 s on / 2 min off ») or else
 * an Agenda › SSTV campaign; its pictures in the gallery, and what the
 * timetable ([HoraireDiapos]) says of the passes to come.
 */
object SerieSstv {

    data class Serie(
        val nom: String, val satNom: String, val catnum: Int,
        val debutMs: Long, val finMs: Long,
        /** Seconds between two slides, as announced. */
        val periodeS: Double = HoraireDiapos.PERIODE_ANNONCEE_S,
        val nb: Int = 12,
    ) {
        fun enCours(t: Long) = t in debutMs..finMs
    }

    /** After its end a series is still shown this long (the last pictures sorted, the sheet made). */
    const val APRES_MS = 3 * 86_400_000L

    /**
     * The series to show at [t]: an SSTV activity with its satellite and
     * dates (under way first, else the last ended less than [APRES_MS] ago),
     * else such a campaign.
     */
    fun choisit(activites: List<ActivitesAVenir.Activite>, campagnes: List<CampagneSstv.Campagne>, t: Long): Serie? {
        val a = activites.filter { it.type == ActivitesAVenir.Type.SSTV && it.catnum > 0 && it.precision != ActivitesAVenir.Precision.INCONNUE && it.debutMs <= t && it.finMs + APRES_MS >= t }
            .sortedWith(compareByDescending<ActivitesAVenir.Activite> { it.enCours(t) }.thenBy { it.precision.ordinal }.thenByDescending { it.finMs })
            .firstOrNull()
        if (a != null) return Serie(a.titre.take(80), a.satNom, a.catnum, a.debutMs, a.finMs, HoraireDiapos.periode(a.horaire))
        val c = campagnes.filter { it.catnum > 0 && it.debutMs <= t && it.finMs + APRES_MS >= t }
            .sortedWith(compareByDescending<CampagneSstv.Campagne> { t in it.debutMs..it.finMs }.thenByDescending { it.finMs }).firstOrNull()
            ?: return null
        return Serie(c.nom.take(80), c.satNom, c.catnum, c.debutMs, c.finMs, HoraireDiapos.periode(c.horaire))
    }

    private fun normal(s: String) = s.uppercase().replace(Regex("\\(.*?\\)"), "").replace(Regex("[^A-Z0-9]"), "")

    /** Is [s] a picture of [serie]: its satellite, during it (an hour either side)? */
    fun dans(serie: Serie, s: SstvMeta.SstvShot): Boolean {
        val a = normal(s.satName); val b = normal(serie.satNom)
        if (a.isEmpty() || b.isEmpty() || !(a.startsWith(b) || b.startsWith(a))) return false
        return s.timeMs in (serie.debutMs - 3_600_000L)..(serie.finMs + 3_600_000L)
    }

    /** What the page and the phone show of a series. */
    data class Prevision(
        val serie: Serie,
        /** The series' groups (its pictures), by slide. */
        val groupes: List<ClassementSstv.Groupe>,
        val modele: HoraireDiapos.Modele?,
        val tableau: HoraireDiapos.Tableau?,
        /** Slides received (numbered groups). */
        val recues: Set<Int>,
        /** The minimum elevation the slides are counted at. */
        val elMin: Double,
        /** The series' pictures, oldest first, each with the pass it was received on (AOS), 0 none. */
        val images: List<Pair<SstvMeta.SstvShot, Long>>,
    )

    /**
     * The series' pictures sorted, its timetable fitted on the numbered ones
     * (the groups numbered by hand or read; those numbered by the estimate
     * then too), the passes to come [passes] (the satellite's, any) with
     * their slides above [elMin].
     */
    fun prevision(serie: Serie, e: ClassementGalerie.Etat, passes: List<SatPass>, passesFaites: List<SatPass>, elMin: Double, t: Long): Prevision {
        val groupes = e.groupes.mapNotNull { g ->
            val l = g.images.filter { dans(serie, it) }
            if (l.isEmpty()) null else g.copy(images = l)
        }
        val m = HoraireDiapos.ajuste(ClassementSstv.reperes(groupes, e.analyses), serie.nb, serie.periodeS)
        val numerotes = ClassementSstv.estime(groupes, m, { ClassementSstv.debut(it, e.analyses[it.fileName]) })
            .sortedWith(compareBy<ClassementSstv.Groupe> { if (it.diapo > 0) it.diapo else 1000 }.thenBy { g -> g.images.minOf { it.timeMs } })
        val recues = numerotes.filter { it.diapo > 0 && it.origine != ClassementSstv.Origine.ESTIME }.map { it.diapo }.toSet() +
            numerotes.filter { it.origine == ClassementSstv.Origine.ESTIME }.map { it.diapo }.toSet()
        val avenir = passes.filter { it.losEpochMs > t && it.aosEpochMs < serie.finMs && it.catalogNumber.let { c -> c == 0 || c == serie.catnum } }
        val tab = m?.let { HoraireDiapos.tableau(it, avenir, elMin, recues, t) }
        val faites = (passesFaites + passes).distinctBy { it.aosEpochMs / 60_000L }
        val images = groupes.flatMap { it.images }.sortedBy { it.timeMs }.map { s ->
            s to (faites.firstOrNull { s.timeMs in (it.aosEpochMs - 60_000L)..(it.losEpochMs + 120_000L) }?.aosEpochMs ?: 0L)
        }
        return Prevision(serie, numerotes, m, tab, recues, elMin, images)
    }

    /** The minimum elevation slides are counted at: the owner's, 5° at least. */
    fun elMin(reglage: Double): Double = maxOf(5.0, reglage)
}
