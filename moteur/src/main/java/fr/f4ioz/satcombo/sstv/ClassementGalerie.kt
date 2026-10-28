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
import java.util.concurrent.ConcurrentHashMap

/**
 * A gallery sorted ([ClassementSstv]) and kept so: each picture measured
 * once (by name and date), in the background — a week of ISS passes is
 * a hundred pictures, a few seconds on a phone — the groups worked out
 * again when the gallery or what the hand set changes. The phone and
 * SatMe-PC each give [charge] (their own way of reading a PNG).
 */
class ClassementGalerie(private val charge: (File) -> Pixels?) {

    class Pixels(val px: IntArray, val w: Int, val h: Int)

    /** Where the sorting stands: the groups, the measures, how many pictures are still to measure. */
    class Etat(val groupes: List<ClassementSstv.Groupe>, val analyses: Map<String, ClassementSstv.Analyse>, val restent: Int)

    private val cache = ConcurrentHashMap<String, ClassementSstv.Analyse>()
    @Volatile private var dernier: Pair<String, Etat>? = null
    @Volatile private var travail: Thread? = null

    private fun cle(f: File) = f.name + "@" + f.lastModified() + "@" + f.length()

    /** One picture measured (and its footer read), kept; null when it cannot be read. */
    fun analyse(f: File, s: SstvMeta.SstvShot): ClassementSstv.Analyse? {
        val k = cle(f)
        cache[k]?.let { return it }
        val p = runCatching { charge(f) }.getOrNull() ?: return null
        val a = runCatching {
            val hm = SstvMode.byName(s.mode)?.height ?: 0
            ClassementSstv.avecLu(ClassementSstv.analyse(p.px, p.w, p.h, hm, s.complete), LectureDiapo.litTout(p.px, p.w, p.h))
        }.getOrNull() ?: return null
        if (cache.size > 5000) cache.clear()
        cache[k] = a
        return a
    }

    /**
     * The gallery [shots] of [dossier] as sorted now. Pictures not measured
     * yet are measured in the background ([Etat.restent] says how many;
     * ask again) — or here when [attendre].
     */
    fun etat(dossier: File, shots: List<SstvMeta.SstvShot>, attendre: Boolean = false): Etat {
        val fichiers = shots.map { File(dossier, it.fileName) to it }
        val manquent = fichiers.filter { (f, _) -> cache[cle(f)] == null && f.isFile }
        if (manquent.isNotEmpty()) {
            if (attendre) manquent.forEach { (f, s) -> analyse(f, s) }
            else lance(manquent)
        }
        val analyses = HashMap<String, ClassementSstv.Analyse>()
        for ((f, s) in fichiers) cache[cle(f)]?.let { analyses[s.fileName] = it }
        val restent = fichiers.count { (f, s) -> f.isFile && analyses[s.fileName] == null }
        val signature = shots.joinToString("|") { it.fileName + ":" + it.groupe + ":" + it.diapo + ":" + it.diapos + ":" + it.satName + ":" + it.complete } + "#" + analyses.size
        dernier?.let { (k, e) -> if (k == signature) return e }
        val e = Etat(ClassementSstv.groupe(shots, analyses), analyses, restent)
        dernier = signature to e
        return e
    }

    @Synchronized private fun lance(l: List<Pair<File, SstvMeta.SstvShot>>) {
        if (travail?.isAlive == true) return
        travail = Thread({ for ((f, s) in l) analyse(f, s) }, "classement-sstv").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
    }

    /** The sheet's signatures with each picture's group and mark (copies are then those of one group). */
    fun signatures(base: Map<String, Planche.Signature>, e: Etat): Map<String, Planche.Signature> {
        val parNom = HashMap<String, Pair<String, Int>>()
        for (g in e.groupes) for (s in g.images) parNom[s.fileName] = g.id to (g.notes[s.fileName] ?: -1)
        return base.mapValues { (n, sig) -> parNom[n]?.let { (g, note) -> sig.avec(g, note) } ?: sig }
    }

    /**
     * « Relancer l'analyse »: the pictures of [shots] measured again, their
     * footer read again, the groups worked out afresh (in the background:
     * [etat] says how many are left). What the hand set — a slide's number,
     * a group — is kept, unless [oublieMain]: then taken off them too.
     * How many pictures are to be measured.
     */
    fun relance(dossier: File, shots: List<SstvMeta.SstvShot>, oublieMain: Boolean = false): Int {
        var n = 0
        for (s in shots) {
            val f = File(dossier, s.fileName)
            cache.remove(cle(f))
            if (!f.isFile) continue
            n++
            if (oublieMain && (s.diapo > 0 || s.diapos > 0 || s.groupe.isNotEmpty()))
                SstvMeta.change(f) { it.copy(diapo = 0, diapos = 0, groupe = "") }
        }
        dernier = null
        return n
    }

    /** How many of [shots] are measured now (for « Relancer l'analyse »'s progress). */
    fun mesurees(dossier: File, shots: List<SstvMeta.SstvShot>): Int = shots.count { cache[cle(File(dossier, it.fileName))] != null }

    // ------------------------------------------------------------ by hand

    /**
     * Slide [n] of [total] set on [nom] and the pictures of its group (0
     * takes the number off them). False when [nom] is not in the gallery.
     */
    fun numerote(dossier: File, shots: List<SstvMeta.SstvShot>, nom: String, n: Int, total: Int = 12): Boolean {
        if (shots.none { it.fileName == nom } || n !in 0..99 || total !in 1..99) return false
        val g = etat(dossier, shots).groupes.firstOrNull { gr -> gr.images.any { it.fileName == nom } }
        val noms = g?.images?.map { it.fileName } ?: listOf(nom)
        var ok = true
        for (x in noms) ok = SstvMeta.changeDiapo(File(dossier, x), n, total) && ok
        dernier = null
        return ok
    }

    /**
     * [nom] moved: into the group [vers] (its id — the group is then kept
     * as it is, its pictures named to it), [SEULE] alone, "" back to sorting
     * by itself. A number set by hand follows the group it goes into.
     */
    fun deplace(dossier: File, shots: List<SstvMeta.SstvShot>, nom: String, vers: String): Boolean {
        if (shots.none { it.fileName == nom }) return false
        val png = File(dossier, nom)
        val groupes = etat(dossier, shots).groupes
        // The group it leaves stays as it is (its copies do not follow it by their look).
        if (vers.isNotEmpty()) groupes.firstOrNull { gr -> gr.images.any { it.fileName == nom } }?.let { avant ->
            val cle = avant.id.removeSuffix(".png").takeLast(100)
            for (x in avant.images) if (x.fileName != nom && x.groupe.isEmpty()) SstvMeta.changeGroupe(File(dossier, x.fileName), cle)
        }
        val ok = when (vers) {
            "" -> SstvMeta.changeGroupe(png, "")
            SEULE -> SstvMeta.change(png) { it.copy(groupe = "seule-" + nom.removeSuffix(".png").takeLast(60), diapo = 0, diapos = 0) }
            else -> {
                val g = groupes.firstOrNull { it.id == vers } ?: return false
                val cle = vers.removeSuffix(".png").takeLast(100)
                for (x in g.images) if (x.groupe != cle) SstvMeta.changeGroupe(File(dossier, x.fileName), cle)
                SstvMeta.change(png) { it.copy(groupe = cle, diapo = g.diapo.takeIf { g.origine == ClassementSstv.Origine.MAIN } ?: 0,
                    diapos = if (g.origine == ClassementSstv.Origine.MAIN) g.total else 0) }
            }
        }
        dernier = null
        return ok
    }

    companion object {
        /** [deplace] to a group of its own. */
        const val SEULE = "seule"
    }
}
