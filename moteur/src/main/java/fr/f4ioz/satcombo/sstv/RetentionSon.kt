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

/**
 * How long the sound of the SSTV passes is kept (« Enregistrer le son de
 * chaque passage SSTV »): N days, a size at most — both off by default, so
 * nothing is ever deleted until the owner says so.
 *
 * Conservative on purpose. Only an SSTV pass's recording is ever a
 * candidate: one a picture's sidecar names (`recording=`) or the SSTV
 * auto-pilot's history; never another satellite's voice pass. And never:
 * - one linked to a picture used in a sheet ([Planche]), or to a picture
 *   or recording marked « à garder »;
 * - one a passage journal entry names;
 * - one younger than [Reglages.jours] (or than a day, whatever the setting:
 *   a pass just made, its pictures maybe not sorted yet).
 * Each deletion is written to a log next to the recordings.
 */
object RetentionSon {

    data class Reglages(
        /** Kept this many days (0: for ever). */
        val jours: Int = 0,
        /** The SSTV recordings kept under this size in all, MB (0: no limit). */
        val maxMo: Int = 0,
    ) {
        val actif: Boolean get() = jours > 0 || maxMo > 0
    }

    /** Whatever the setting, a recording this young stays. */
    const val AGE_MIN_MS = 86_400_000L

    /** One recording looked at. */
    data class Fichier(val nom: String, val octets: Long, val modifieMs: Long)

    enum class Raison { PLANCHE, GARDEE, JOURNAL, RECENT, PAS_SSTV }

    data class Plan(
        /** To delete, oldest first. */
        val supprimer: List<Fichier>,
        /** Kept although old enough, and why. */
        val proteges: Map<String, Raison>,
        /** The SSTV recordings looked at, their size in all. */
        val sstv: Int, val octetsSstv: Long,
    ) {
        val octetsLiberes: Long get() = supprimer.sumOf { it.octets }
    }

    /** When [f] was recorded: the UTC time of its name, else its file date. */
    fun debut(f: Fichier): Long = SstvMeta.debutEnregistrement(f.nom).takeIf { it > 0 } ?: f.modifieMs

    /**
     * What [r] would delete among [fichiers] at [maintenant].
     * [sstv]: the recordings known as SSTV passes'; [liees]: each recording's
     * pictures (sidecars); [planches]: the pictures in sheets; [gardes]:
     * pictures and recordings marked « à garder »; [journal]: recordings the
     * passage journal names.
     */
    fun plan(r: Reglages, fichiers: List<Fichier>, sstv: Set<String>, liees: Map<String, List<String>>,
             planches: Set<String>, gardes: Set<String>, journal: Set<String>, maintenant: Long): Plan {
        val l = fichiers.filter { it.nom in sstv }.sortedBy { debut(it) }
        val proteges = LinkedHashMap<String, Raison>()
        if (!r.actif) return Plan(emptyList(), proteges, l.size, l.sumOf { it.octets })
        val libres = l.filter { f ->
            val images = liees[f.nom].orEmpty()
            val raison = when {
                images.any { it in planches } -> Raison.PLANCHE
                f.nom in gardes || images.any { it in gardes } -> Raison.GARDEE
                f.nom in journal -> Raison.JOURNAL
                maintenant - maxOf(debut(f), f.modifieMs) < maxOf(AGE_MIN_MS, r.jours * 86_400_000L) -> Raison.RECENT
                else -> null
            }
            if (raison != null) proteges[f.nom] = raison
            raison == null
        }
        val out = ArrayList<Fichier>()
        // Old enough: all those past the days (when days are set).
        if (r.jours > 0) out += libres
        // Too big: the oldest deletable first, until under the size (only those old enough for the size alone).
        if (r.maxMo > 0) {
            var total = l.sumOf { it.octets } - out.sumOf { it.octets }
            val max = r.maxMo * 1_048_576L
            for (f in libres) {
                if (total <= max) break
                if (f in out) continue
                out += f; total -= f.octets
            }
        }
        return Plan(out.sortedBy { debut(it) }, proteges, l.size, l.sumOf { it.octets })
    }

    /**
     * The recordings SSTV pictures name, each with its pictures: from the
     * sidecars of [images] ([SstvMeta.SstvShot.recording], a plain name).
     */
    fun liees(images: List<SstvMeta.SstvShot>): Map<String, List<String>> =
        images.filter { it.recording.isNotBlank() }.groupBy({ File(it.recording).name }, { it.fileName })

    /** The pictures sheets use (their boxes), from the sheets' models. */
    fun planches(modeles: List<Planche.Modele>): Set<String> = modeles.flatMap { it.images.values }.toSet()

    /**
     * Deletes [p]'s files in [dossier] (only plain names in it), each line
     * written to [journal] (« 2026-10-24T08:00Z supprimé SatMe_ISS_…wav (12 Mo) : plus de 30 jours »).
     * Its sidecars (.activite, .info) go with it. The number deleted.
     */
    fun applique(p: Plan, dossier: File, journal: File, raison: String, maintenant: Long): Int {
        var n = 0
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val lignes = StringBuilder()
        for (f in p.supprimer) {
            if (f.nom != File(f.nom).name || f.nom.startsWith(".")) continue
            val x = File(dossier, f.nom)
            if (!x.isFile) continue
            if (x.delete()) {
                n++
                val base = f.nom.substringBeforeLast('.')
                (dossier.listFiles { y -> y.isFile && y.name != f.nom && y.name.substringBeforeLast('.') == base && !y.name.endsWith(".wav") && !y.name.endsWith(".mp3") }
                    ?: emptyArray()).forEach { it.delete() }
                lignes.append(fmt.format(Date(maintenant))).append(" supprimé ").append(f.nom)
                    .append(" (").append(f.octets / 1_048_576L).append(" Mo) : ").append(raison).append('\n')
            }
        }
        if (lignes.isNotEmpty()) runCatching { journal.parentFile?.mkdirs(); journal.appendText(lignes.toString()) }
        return n
    }

    // ------------------------------------------------------------------ « à garder »

    /** The names marked « à garder » (one per line). */
    fun litGardes(f: File): Set<String> = runCatching { f.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toSet() }.getOrDefault(emptySet())

    fun marqueGarde(f: File, nom: String, garde: Boolean): Set<String> {
        val n = File(nom).name
        val s = litGardes(f).let { if (garde) it + n else it - n }
        runCatching { f.parentFile?.mkdirs(); f.writeText("# SatMe : images et enregistrements à garder (jamais supprimés par la rétention)\n" + s.joinToString("\n") + "\n") }
        return s
    }
}
