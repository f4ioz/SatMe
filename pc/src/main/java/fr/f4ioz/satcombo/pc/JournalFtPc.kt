/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.domain.Ft8HorsLigne
import fr.f4ioz.satcombo.domain.JournalFt
import fr.f4ioz.satcombo.domain.JournalPassage
import fr.f4ioz.satcombo.domain.SonPassage
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The FT8 / FT4 messages of the journal's passes ([JournalFt]):
 *  - each slot decoded by the station ([recoit], the card's decoder) written
 *    at once to a day's file (`ft8-journal/AAAAMMJJ.tsv`, kept [GARDE_JOURS] days),
 *    with the satellite followed;
 *  - a pass's own kept beside it (`journal/<id>.ft`) when it closes ([fige]):
 *    they stay as long as the pass;
 *  - for a pass from before, what the bench's slots kept (the JSON files of `ft8-tranches`,
 *    three days, when their recording was on);
 *  - read again from the pass's recording with SatMe's decoder ([redecode]),
 *    added to what was heard live.
 */
class JournalFtPc(
    /** The journal's folder (`journal/`): the passes' `.ft` beside their `.passage`. */
    private val journal: File,
    /** The data folder: `ft8-journal/` and `ft8-tranches/` in it. */
    donnees: File = journal.absoluteFile.parentFile ?: journal,
    private val horloge: () -> Long = System::currentTimeMillis,
) {
    private val jours = File(donnees, "ft8-journal")
    private val tranches = File(donnees, "ft8-tranches")
    private val verrou = Any()

    private fun jour(t: Long) = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(t))

    /** One slot's messages, as the decoder gives them, of the satellite [catnum] followed (0: none). */
    fun recoit(l: List<Ft8Pc.Entendu>, catnum: Int) {
        if (l.isEmpty()) return
        ajoute(l.map { JournalFt.Decode(it.tranche, it.mode, it.rapportDb, it.decalageS, it.frequenceHz, it.texte, it.appelant, it.appele, it.locator, it.ap, catnum) })
    }

    /** Written to their day's file (the older days' files purged). */
    fun ajoute(l: List<JournalFt.Decode>) = synchronized(verrou) {
        runCatching {
            jours.mkdirs()
            l.groupBy { jour(it.tMs) }.forEach { (j, d) -> File(jours, "$j.tsv").appendText(JournalFt.ecritTout(d)) }
            val limite = jour(horloge() - GARDE_JOURS * 86_400_000L)
            jours.listFiles { f -> f.name.endsWith(".tsv") && f.name.removeSuffix(".tsv") < limite }?.forEach { it.delete() }
        }
        Unit
    }

    private fun fichier(e: JournalPassage.Entree) = File(journal, e.id + ".ft")

    /** What the days' files and the bench's slots hold of the pass's window. */
    private fun recents(e: JournalPassage.Entree): List<JournalFt.Decode> {
        val de = e.debutMs - MARGE_MS; val a = e.finMs + MARGE_MS
        val parJour = generateSequence(de - de % 86_400_000L) { it + 86_400_000L }.takeWhile { it <= a }
            .map { File(jours, jour(it) + ".tsv") }.filter { it.isFile }
            .flatMap { f -> runCatching { JournalFt.litTout(f.readText()) }.getOrDefault(emptyList()) }.toList()
        return JournalFt.fusionne(JournalFt.dans(parJour, de, a, e.catnum), JournalFt.dans(desTranches(de, a), de, a))
    }

    /** The bench's slots (`ft8-tranches`), for a pass heard when they were kept. */
    private fun desTranches(de: Long, a: Long): List<JournalFt.Decode> = runCatching {
        (tranches.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray()).flatMap { f ->
            val j = runCatching { JSONObject(f.readText()) }.getOrNull() ?: return@flatMap emptyList()
            val t = j.optLong("debut"); if (t !in de..a) return@flatMap emptyList()
            val mode = j.optString("mode", "FT8"); val l = j.optJSONArray("decodes") ?: return@flatMap emptyList()
            (0 until l.length()).mapNotNull { i ->
                val d = l.optJSONObject(i) ?: return@mapNotNull null
                val txt = d.optString("texte").trim().ifEmpty { return@mapNotNull null }
                val (qui, aqui, loc) = JournalFt.analyse(txt)
                JournalFt.Decode(t, mode, d.optInt("rapportDb"), d.optDouble("decalageS", 0.0), Math.round(d.optDouble("frequenceHz", 0.0)).toInt(),
                    txt, qui, aqui, loc, d.optBoolean("ap"))
            }
        }
    }.getOrDefault(emptyList())

    /** The pass's messages: those kept with it, and what was heard of its window since. */
    fun duPassage(e: JournalPassage.Entree): List<JournalFt.Decode> {
        val f = fichier(e)
        val gardes = if (f.isFile) runCatching { JournalFt.litTout(f.readText()) }.getOrDefault(emptyList()) else emptyList()
        // A pass closed long ago: what it kept is all there is (the days' files are gone).
        val r = if (e.finMs < horloge() - GARDE_JOURS * 86_400_000L) emptyList() else recents(e)
        return if (r.isEmpty()) gardes else JournalFt.fusionne(gardes, r)
    }

    /** The pass closed: its messages kept beside it, for as long as it is. */
    fun fige(e: JournalPassage.Entree) = synchronized(verrou) {
        runCatching {
            val l = duPassage(e)
            if (l.isNotEmpty()) { journal.mkdirs(); fichier(e).writeText(ENTETE + JournalFt.ecritTout(l)) }
        }
        Unit
    }

    /** Several added to the pass's (a re-reading, the demonstration's), each message of a slot once. */
    fun garde(e: JournalPassage.Entree, l: List<JournalFt.Decode>) = synchronized(verrou) {
        journal.mkdirs()
        fichier(e).writeText(ENTETE + JournalFt.ecritTout(JournalFt.fusionne(duPassage(e), l)))
    }

    /** The pass taken out of the journal: its messages too. */
    fun oublie(e: JournalPassage.Entree) { fichier(e).delete() }

    /**
     * The pass's recordings read again by SatMe's decoder in [mode] (FT8, FT4, or both: « FT4+FT8 »), the whole
     * pass ([stretch] over it). Returns how many messages came out (0: none, or no recording).
     */
    fun redecode(e: JournalPassage.Entree, morceaux: List<SonPassage.Morceau>, decodeur: SonPassage.Decodeur, mode: String,
                 progres: (Float) -> Unit = {}): Int {
        val de = morceaux.minOfOrNull { it.origine } ?: return 0
        val a = morceaux.maxOf { it.origine + it.dureeMs }
        return stretch(e, morceaux, decodeur, de, a, modes(mode), progres).size
    }

    /**
     * The stretch [deMs]..[aMs] (UTC) of the pass's recordings read again by SatMe's decoder ([Ft8HorsLigne]: the
     * sound cut on the UTC slots, a second's margin each side so a recording's start known to the second loses
     * nothing), in each of [modes]; what it finds kept with the pass ([garde]) and returned, slot by slot.
     */
    fun stretch(e: JournalPassage.Entree, morceaux: List<SonPassage.Morceau>, decodeur: SonPassage.Decodeur, deMs: Long, aMs: Long,
                modes: List<String>, progres: (Float) -> Unit = {}): List<JournalFt.Decode> {
        val pris = morceaux.filter { it.origine < aMs + MARGE_LECTURE_MS && it.origine + it.dureeMs > deMs - MARGE_LECTURE_MS }
        val trouves = ArrayList<JournalFt.Decode>()
        val etapes = (pris.size * modes.size).coerceAtLeast(1)
        pris.forEachIndexed { i, mc ->
            val (audio, cadence, origine) = lisMorceau(mc, decodeur, deMs - MARGE_LECTURE_MS, aMs + MARGE_LECTURE_MS) ?: return@forEachIndexed
            modes.forEachIndexed { k, m ->
                Ft8HorsLigne.decode(audio, cadence, origine, deMs, aMs, m) { progres(((i * modes.size + k + it) / etapes).coerceIn(0f, 0.99f)) }.forEach { x ->
                    trouves += JournalFt.Decode(x.trancheMs, x.mode, x.decode.rapportDb, Math.round(x.dtS * 100) / 100.0,
                        Math.round(x.decode.frequenceHz).toInt(), x.decode.message.brut, x.decode.message.appelant, x.decode.message.appele,
                        x.decode.message.locator, x.decode.ap, e.catnum)
                }
            }
        }
        val l = trouves.distinctBy { it.tMs.toString() + it.mode + it.message }.sortedWith(compareBy({ it.tMs }, { -it.db }))
        if (l.isNotEmpty()) garde(e, l)
        progres(1f)
        return l
    }

    companion object {
        /** A slot's margins and the decoder's: read this much of sound either side of the stretch asked. */
        const val MARGE_LECTURE_MS = 2_000L

        /** « FT4 », « FT8 », else both (FT4 first: what a LEO pass mostly carries). */
        fun modes(mode: String): List<String> = when (mode.trim().uppercase()) { "FT4" -> listOf("FT4"); "FT8" -> listOf("FT8"); else -> listOf("FT4", "FT8") }

        /**
         * A recording's sound between [deMs] and [aMs] (UTC), brought down by an integer factor to about 12 kHz:
         * the samples, their rate, the UTC time of the first; null when it holds nothing of it.
         */
        fun lisMorceau(mc: SonPassage.Morceau, decodeur: SonPassage.Decodeur, deMs: Long, aMs: Long): Triple<FloatArray, Int, Long>? {
            var facteur = 0; var cadence = 0
            var somme = 0; var dans = 0; var n = 0L
            var debut = -1L
            var sortie = FloatArray(0); var ecrits = 0
            runCatching {
                decodeur.decode(mc.f) { pcm, k, rate ->
                    if (facteur == 0) {
                        facteur = Math.round(rate / 12_000.0).toInt().coerceAtLeast(1); cadence = rate / facteur
                        val i0 = ((deMs - mc.origine).coerceAtLeast(0L) * cadence / 1000)
                        val i1 = ((aMs - mc.origine).coerceAtMost(mc.dureeMs + 1000L) * cadence / 1000)
                        if (i1 <= i0) return@decode false
                        debut = i0; sortie = FloatArray((i1 - i0).toInt())
                    }
                    for (i in 0 until k) {
                        somme += pcm[i]; dans++
                        if (dans < facteur) continue
                        val v = somme / facteur / 32768f; somme = 0; dans = 0
                        if (n >= debut && ecrits < sortie.size) sortie[ecrits++] = v
                        n++
                    }
                    ecrits < sortie.size
                }
            }
            if (debut < 0 || ecrits == 0) return null
            return Triple(sortie.copyOf(ecrits), cadence, mc.origine + debut * 1000 / cadence)
        }

        /** The days' files: a week and a half, time for a pass to close and keep its own. */
        const val GARDE_JOURS = 10L
        /** A message a little before the AOS or after the LOS still the pass's. */
        const val MARGE_MS = 60_000L
        private const val ENTETE = "# SatMe FT8/FT4: slot start (UTC ms), mode, dB, dt (s), Hz, message, from, to, square, ap, catnum\n"
    }
}
