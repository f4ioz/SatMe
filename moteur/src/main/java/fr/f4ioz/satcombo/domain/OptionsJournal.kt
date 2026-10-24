/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import fr.f4ioz.satcombo.sstv.Planche

/**
 * What a pass's pictures and videos show of its SSTV pictures and of the
 * event's report, chosen on the journal's page and kept with the pass
 * ([JournalPassage.ecrit], lines an older SatMe skips):
 *  - which version of each picture: as received, corrected, fused, or the
 *    best marked (Auto) — [version] for the whole pass, [versions] picture
 *    by picture (its PNG name);
 *  - « corrigée » / « fusion » written on a processed picture ([marqueTraitees]),
 *    off as on the sheet: a clean picture, the information kept apart;
 *  - the report's panels ([Element]): laid over the replay where the mouse
 *    put them ([Reglage.incruste], [Reglage.cadre]), or a card of their
 *    own ([Reglage.carte]) — the montage's opening the video, the others
 *    closing it;
 *  - the montage's plan ([plan], "" the one drawing the pass's reception best).
 *
 * A pass with none of it kept reads as [DEFAUT]: the pictures as received,
 * nothing more — its videos as they always were.
 */
data class OptionsJournal(
    val version: Planche.Version = Planche.Version.RECUE,
    val versions: Map<String, Planche.Version> = emptyMap(),
    val marqueTraitees: Boolean = false,
    val elements: Map<Element, Reglage> = emptyMap(),
    val plan: String = "",
) {
    /** The report's pieces the journal can show (their words: `pj_el_<code>`). */
    enum class Element(val code: String, /** Where it lands at first, laid over the view: left, top, width (shares). */ val defaut: CadreSstv,
                       /** Its least width (share of the view's). */ val min: Float = CadreSstv.MIN) {
        MONTAGE("montage", CadreSstv(0.02f, 0.58f, 0.46f)),
        DIAPOS("diapos", CadreSstv(0.02f, 0.02f, 0.46f)),
        CIEL("ciel", CadreSstv(0.74f, 0.50f, 0.24f)),
        PROFIL("profil", CadreSstv(0.02f, 0.72f, 0.34f)),
        STATS("stats", CadreSstv(0.30f, 0.86f, 0.44f)),
        PLANCHE("planche", CadreSstv(0.62f, 0.02f, 0.36f)),
        LOGO("logo", CadreSstv(0.90f, 0.02f, 0.08f), min = 0.05f);
        companion object { fun de(c: String) = values().firstOrNull { it.code == c } }
    }

    /** A panel: laid over the replay ([incruste], at [cadre], null its own place), a card of its own ([carte]). */
    data class Reglage(val incruste: Boolean = false, val carte: Boolean = false, val cadre: CadreSstv? = null) {
        val actif: Boolean get() = incruste || carte
    }

    /** The version picture [png] shows: its own choice, else the pass's. */
    fun versionDe(png: String): Planche.Version = versions[png] ?: version

    fun reglage(k: Element): Reglage = elements[k] ?: Reglage()

    /** [k] laid over the replay: where (its own place when not moved). */
    fun cadre(k: Element): CadreSstv = reglage(k).cadre ?: k.defaut

    /** The panels laid over the replay, in drawing order (the logo last, on top). */
    val incrustes: List<Element> get() = Element.values().filter { elements[it]?.incruste == true }

    /** The cards: the montage's opening the video, the others closing it. */
    val cartesOuverture: List<Element> get() = listOf(Element.MONTAGE).filter { elements[it]?.carte == true }
    val cartesFin: List<Element> get() = Element.values().filter { it != Element.MONTAGE && elements[it]?.carte == true }

    val vide: Boolean get() = this == DEFAUT

    companion object {
        val DEFAUT = OptionsJournal()

        /** The lines kept in the pass's file, none for what is as by default. */
        fun ecrit(o: OptionsJournal): List<String> {
            val l = ArrayList<String>()
            if (o.version != DEFAUT.version) l += "sstv_version=" + o.version.code
            for ((png, v) in o.versions.toSortedMap()) l += "sstv_image=" + png.replace('\t', ' ').replace('\n', ' ') + "\t" + v.code
            if (o.marqueTraitees) l += "sstv_marque=1"
            for (k in Element.values()) {
                val r = o.elements[k] ?: continue
                if (r == Reglage()) continue
                l += "element=" + k.code + "\t" + (if (r.incruste) "i" else "") + (if (r.carte) "c" else "") + "\t" + (r.cadre?.texte() ?: "")
            }
            if (o.plan.isNotBlank()) l += "plan=" + o.plan.replace('\t', ' ').replace('\n', ' ')
            return l
        }

        /** One line of the pass's file read into [o]; [o] as it was when the line is not one of these. */
        fun lit(o: OptionsJournal, cle: String, v: String): OptionsJournal = when (cle) {
            "sstv_version" -> Planche.Version.de(v.trim())?.let { o.copy(version = it) } ?: o
            "sstv_image" -> {
                val png = v.substringBeforeLast('\t'); val c = Planche.Version.de(v.substringAfterLast('\t').trim())
                if (png.isBlank() || c == null || '\t' !in v) o else o.copy(versions = o.versions + (png to c))
            }
            "sstv_marque" -> o.copy(marqueTraitees = v.trim() == "1")
            "element" -> {
                val c = v.split('\t')
                val k = Element.de(c[0].trim())
                if (k == null) o else o.copy(elements = o.elements + (k to Reglage(
                    incruste = 'i' in c.getOrElse(1) { "" }, carte = 'c' in c.getOrElse(1) { "" },
                    cadre = CadreSstv.lit(c.getOrElse(2) { "" }, k.min))))
            }
            "plan" -> o.copy(plan = v.trim())
            else -> o
        }

        /** Two pieces of one pass made one: the one with choices kept, else the other's. */
        fun fusionne(a: OptionsJournal, b: OptionsJournal): OptionsJournal = if (!a.vide) a else b
    }
}
