/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import fr.f4ioz.satcombo.sstv.SstvMode
import java.util.Calendar
import java.util.TimeZone

/**
 * « Importer une affiche »: an SSTV event's flyer, read as text (OCR), turned
 * into a campaign's form for the owner to check — the frequency, the SSTV
 * mode, the dates (English or French month names), the satellite, the
 * schedule, the event's page. Nothing is decided here: a proposal.
 */
object AfficheSstv {

    data class Proposition(
        val nom: String = "",
        val satNom: String = "",
        val catnum: Int = 0,
        val freqHz: Long = 0L,
        val modeSstv: String = CampagneSstv.AUTO,
        val debutMs: Long = 0L,
        val finMs: Long = 0L,
        /** Times not given (« to be determined »): whole days. */
        val aConfirmer: Boolean = false,
        val horaire: String = "",
        val lien: String = "",
        /** What was found, said for the owner. */
        val trouve: List<String> = emptyList(),
    )

    private val MOIS: Map<String, Int> = mapOf(
        "january" to 0, "jan" to 0, "janvier" to 0, "janv" to 0,
        "february" to 1, "feb" to 1, "fevrier" to 1, "février" to 1, "fevr" to 1, "févr" to 1,
        "march" to 2, "mar" to 2, "mars" to 2,
        "april" to 3, "apr" to 3, "avril" to 3, "avr" to 3,
        "may" to 4, "mai" to 4,
        "june" to 5, "jun" to 5, "juin" to 5,
        "july" to 6, "jul" to 6, "juillet" to 6, "juil" to 6,
        "august" to 7, "aug" to 7, "aout" to 7, "août" to 7,
        "september" to 8, "sep" to 8, "sept" to 8, "septembre" to 8,
        "october" to 9, "oct" to 9, "octobre" to 9,
        "november" to 10, "nov" to 10, "novembre" to 10,
        "december" to 11, "dec" to 11, "décembre" to 11, "decembre" to 11, "déc" to 11)

    private val JOURS: Map<String, Int> = mapOf(
        "sunday" to Calendar.SUNDAY, "dimanche" to Calendar.SUNDAY, "monday" to Calendar.MONDAY, "lundi" to Calendar.MONDAY,
        "tuesday" to Calendar.TUESDAY, "mardi" to Calendar.TUESDAY, "wednesday" to Calendar.WEDNESDAY, "mercredi" to Calendar.WEDNESDAY,
        "thursday" to Calendar.THURSDAY, "jeudi" to Calendar.THURSDAY, "friday" to Calendar.FRIDAY, "vendredi" to Calendar.FRIDAY,
        "saturday" to Calendar.SATURDAY, "samedi" to Calendar.SATURDAY)

    private val NOMS_MOIS = MOIS.keys.sortedByDescending { it.length }.joinToString("|")
    private val NOMS_JOURS = JOURS.keys.joinToString("|")

    /** « Thursday, October 22 », « October 22, 2026 », « jeudi 22 octobre 2026 », « 22 Oct » — with a time when there is one. */
    private val DATE = Regex(
        "(?:\\b($NOMS_JOURS)\\b[,.]?\\s*)?" +
            "(?:\\b($NOMS_MOIS)\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?\\b|\\b(\\d{1,2})(?:er|st|nd|rd|th)?\\s+($NOMS_MOIS)\\b\\.?)" +
            "(?:[,\\s]+(\\d{4})\\b)?" +
            "(?:[,\\s]*(?:at|à|a|@|from|de|vers)?\\s*(\\d{1,2})[:h](\\d{2})\\s*(UTC|GMT|Z)?)?",
        RegexOption.IGNORE_CASE)

    /** The SSTV modes as flyers write them. */
    private val MODES: List<Pair<Regex, String>> = listOf(
        "robot\\s*-?\\s*36" to "Robot 36", "robot\\s*-?\\s*72" to "Robot 72",
        "pd\\s*-?\\s*120" to "PD 120", "pd\\s*-?\\s*180" to "PD 180", "pd\\s*-?\\s*90" to "PD 90", "pd\\s*-?\\s*50" to "PD 50",
        "pd\\s*-?\\s*160" to "PD 160", "pd\\s*-?\\s*240" to "PD 240", "pd\\s*-?\\s*290" to "PD 290",
        "martin\\s*-?\\s*(?:m\\s*)?1" to "Martin M1", "martin\\s*-?\\s*(?:m\\s*)?2" to "Martin M2",
        "scottie\\s*-?\\s*(?:s\\s*)?1" to "Scottie S1", "scottie\\s*-?\\s*(?:s\\s*)?2" to "Scottie S2", "scottie\\s*-?\\s*dx" to "Scottie DX",
    ).map { (r, n) -> Regex("\\b$r\\b", RegexOption.IGNORE_CASE) to n }

    /** « du 22 au 25 octobre 2026 », « 22-25 Oct », « October 22-25, 2026 ». */
    private val PLAGE = Regex("(?:\\b(\\d{1,2})\\s*(?:au|à|-|to)\\s*(\\d{1,2})\\s+($NOMS_MOIS)\\b\\.?|\\b($NOMS_MOIS)\\.?\\s+(\\d{1,2})\\s*(?:-|to|through|au)\\s*(\\d{1,2})\\b)(?:[,\\s]+(\\d{4})\\b)?",
        RegexOption.IGNORE_CASE)

    private val FREQ = Regex("\\b(\\d{2,5}[.,]\\d{1,4})\\s*[MＭ]\\s*[Hh][Zz]\\b")
    private val FREQ_KHZ = Regex("\\b(\\d{5,8})\\s*[kK]\\s*[Hh][Zz]\\b")
    /** A link, with the spaces an OCR may slip in after « https: ». */
    private val LIEN = Regex("https?\\s?:\\s?//\\s?[^\\s<>\"']+", RegexOption.IGNORE_CASE)
    private val A_CONFIRMER = Regex("to be (?:determined|announced|confirmed)|\\bTBD\\b|\\bTBA\\b|à (?:confirmer|déterminer|préciser)|a (?:confirmer|determiner)", RegexOption.IGNORE_CASE)
    private val HORAIRE = Regex("(\\d+)\\s*(sec(?:onds?)?|s)\\b\\.?\\s*on\\s*[/,-]?\\s*(\\d+(?:[.,]\\d+)?)\\s*(min(?:utes?)?|m)\\b\\.?\\s*off", RegexOption.IGNORE_CASE)
    private val DEBUT = Regex("\\b(start|starts|begin|begins|from|début|debut|du|commence)\\b", RegexOption.IGNORE_CASE)
    private val FIN = Regex("\\b(end|ends|until|through|fin|au|jusqu)\\b", RegexOption.IGNORE_CASE)

    private class Date(val ligne: Int, val mois: Int, val jour: Int, val annee: Int?, val jourSemaine: Int?,
                       val h: Int?, val m: Int?, val utc: Boolean)

    /**
     * The flyer's [texte] read: [maintenant] and [tz] place a date without its
     * year (the next one to come, the weekday agreeing when given); [satellites]
     * (name, catalogue number) are looked for by name.
     */
    fun lis(texte: String, maintenant: Long, tz: TimeZone = TimeZone.getDefault(),
            satellites: List<Pair<String, Int>> = emptyList()): Proposition {
        val lignes = texte.replace('’', '\'').replace('–', '-').replace('—', '-').lines().map { it.trim() }.filter { it.isNotEmpty() }
        val tout = lignes.joinToString("\n")
        val trouve = ArrayList<String>()
        // The frequency: the first in MHz (or kHz), in the amateur satellite bands' range.
        val freq = FREQ.findAll(tout).mapNotNull { m -> m.groupValues[1].replace(',', '.').toDoubleOrNull()?.let { (it * 1e6).toLong() } }
            .firstOrNull { it in 20_000_000L..12_000_000_000L }
            ?: FREQ_KHZ.findAll(tout).mapNotNull { it.groupValues[1].toLongOrNull()?.times(1000) }.firstOrNull { it in 20_000_000L..12_000_000_000L }
            ?: 0L
        if (freq > 0) trouve += "fréquence " + String.format(java.util.Locale.FRANCE, "%.3f", freq / 1e6) + " MHz"
        val mode = MODES.mapNotNull { (r, n) -> r.find(tout)?.let { it.range.first to n } }.minByOrNull { it.first }?.second
            ?.takeIf { SstvMode.byName(it) != null } ?: CampagneSstv.AUTO
        if (mode != CampagneSstv.AUTO) trouve += "mode $mode"
        // The satellite: the ISS (ARISS events) first, else a known name.
        val (satNom, catnum) = when {
            Regex("\\bARISS\\b|\\bISS\\b|space station|station spatiale", RegexOption.IGNORE_CASE).containsMatchIn(tout) -> "ISS" to 25544
            else -> satellites.filter { it.first.length >= 4 }.sortedByDescending { it.first.length }
                .firstOrNull { (n, _) -> Regex("(?<![A-Za-z0-9])" + Regex.escape(n) + "(?![A-Za-z0-9])", RegexOption.IGNORE_CASE).containsMatchIn(tout) }
                ?: ("" to 0)
        }
        if (catnum > 0) trouve += "satellite $satNom"
        // The dates, each with its line.
        val dates = ArrayList<Date>()
        lignes.forEachIndexed { i, l ->
            for (m in DATE.findAll(l)) {
                val g = m.groupValues
                val moisNom = (g[2].ifBlank { g[5] }).lowercase().trimEnd('.')
                val mois = MOIS[moisNom] ?: continue
                val jour = (g[3].ifBlank { g[4] }).toIntOrNull()?.takeIf { it in 1..31 } ?: continue
                dates += Date(i, mois, jour, g[6].toIntOrNull(), JOURS[g[1].lowercase()], g[7].toIntOrNull(), g[8].toIntOrNull(), g[9].isNotBlank())
            }
        }
        // A range in one go (« du 22 au 25 octobre »): its two ends, when the lines did not give two dates.
        if (dates.size < 2) lignes.forEachIndexed { i, l ->
            val m = PLAGE.find(l) ?: return@forEachIndexed
            val g = m.groupValues
            val mois = MOIS[(g[3].ifBlank { g[4] }).lowercase().trimEnd('.')] ?: return@forEachIndexed
            val a = (g[1].ifBlank { g[5] }).toIntOrNull()?.takeIf { it in 1..31 } ?: return@forEachIndexed
            val b = (g[2].ifBlank { g[6] }).toIntOrNull()?.takeIf { it in 1..31 } ?: return@forEachIndexed
            dates.clear()
            dates += Date(i, mois, a, g[7].toIntOrNull(), null, null, null, false)
            dates += Date(i, mois, b, g[7].toIntOrNull(), null, null, null, false)
        }
        fun etiquette(d: Date, r: Regex) = r.containsMatchIn(lignes[d.ligne].substringBefore(':', lignes[d.ligne]).take(40)) ||
            r.containsMatchIn(lignes[d.ligne].take(12))
        val dDebut = dates.firstOrNull { etiquette(it, DEBUT) } ?: dates.firstOrNull()
        val dFin = dates.lastOrNull { it !== dDebut && etiquette(it, FIN) } ?: dates.lastOrNull { it !== dDebut } ?: dDebut
        val aConfirmer = A_CONFIRMER.containsMatchIn(tout) || (dDebut != null && dDebut.h == null)
        var debut = 0L; var fin = 0L
        if (dDebut != null) {
            val annee = annee(dDebut, maintenant, tz)
            debut = instant(dDebut, annee, tz, false)
            val f = dFin ?: dDebut
            var anneeFin = f.annee ?: annee
            if (f.annee == null && (f.mois < dDebut.mois)) anneeFin = annee + 1
            fin = instant(f, anneeFin, tz, true)
            if (fin <= debut) fin = debut + 86_400_000L - 1
            trouve += "période trouvée" + if (aConfirmer) " (horaire à confirmer : journées entières)" else ""
        }
        val horaire = HORAIRE.find(tout)?.let { m -> m.groupValues[1] + " s on / " + m.groupValues[3].replace('.', ',') + " min off" }
            ?: lignes.firstOrNull { Regex("^(schedule|horaire|cycle)\\b", RegexOption.IGNORE_CASE).containsMatchIn(it) }?.substringAfter(':')?.trim()?.trimEnd('.')?.take(80)
            ?: ""
        if (horaire.isNotBlank()) trouve += "horaire $horaire"
        val lien = LIEN.find(tout)?.value?.replace(" ", "")?.trimEnd('.', ',', ')')?.let { CampagneSstv.lienPropre(it) } ?: ""
        if (lien.isNotBlank()) trouve += "lien"
        // The name: the line saying SSTV with « series », « event »…, ARISS before it when the flyer is theirs.
        val ligneNom = lignes.firstOrNull { Regex("SSTV", RegexOption.IGNORE_CASE).containsMatchIn(it) && Regex("series|s[ée]rie|event|[ée]v[ée]nement|\\d", RegexOption.IGNORE_CASE).containsMatchIn(it) && it.length <= 50 }
            ?: lignes.firstOrNull { Regex("SSTV", RegexOption.IGNORE_CASE).containsMatchIn(it) && it.length <= 50 } ?: ""
        val nom = (if (Regex("\\bARISS\\b").containsMatchIn(tout) && !ligneNom.contains("ARISS", true) && ligneNom.isNotBlank()) "ARISS " else "") + ligneNom
        return Proposition(nom.take(60), satNom, catnum, freq, mode, debut, fin, aConfirmer, horaire, lien, trouve)
    }

    /** A date's year: given, else the next one to come (from a month ago), the weekday agreeing when said. */
    private fun annee(d: Date, maintenant: Long, tz: TimeZone): Int {
        d.annee?.let { return it }
        val c = Calendar.getInstance(tz).apply { timeInMillis = maintenant }
        val ici = c.get(Calendar.YEAR)
        val candidats = listOf(ici - 1, ici, ici + 1, ici + 2)
        fun jourDe(a: Int) = Calendar.getInstance(tz).apply { clear(); set(a, d.mois, d.jour) }
        val avenir = candidats.filter { jourDe(it).timeInMillis + 86_400_000L >= maintenant - 30L * 86_400_000L }
        return avenir.firstOrNull { d.jourSemaine == null || jourDe(it).get(Calendar.DAY_OF_WEEK) == d.jourSemaine } ?: avenir.firstOrNull() ?: ici
    }

    private fun instant(d: Date, annee: Int, tz: TimeZone, finDeJour: Boolean): Long {
        val zone = if (d.utc) TimeZone.getTimeZone("UTC") else tz
        val c = Calendar.getInstance(zone).apply { clear(); set(annee, d.mois, d.jour) }
        if (d.h != null && d.m != null) { c.set(Calendar.HOUR_OF_DAY, d.h.coerceIn(0, 23)); c.set(Calendar.MINUTE, d.m.coerceIn(0, 59)) }
        else if (finDeJour) { c.set(Calendar.HOUR_OF_DAY, 23); c.set(Calendar.MINUTE, 59) }
        return c.timeInMillis
    }
}
