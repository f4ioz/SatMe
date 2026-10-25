/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

import fr.f4ioz.satcombo.sstv.Planche.Champ
import fr.f4ioz.satcombo.sstv.Planche.Modele
import fr.f4ioz.satcombo.sstv.Planche.Texte
import fr.f4ioz.satcombo.sstv.Planche.Zone
import fr.f4ioz.satcombo.sstv.PlancheStyle.Style

/**
 * « Titres stylés »: in one click, the title of a sheet dressed as the
 * artwork of an ARISS event — a big chrome word, a neon line between its
 * glowing rules, the series' number in chrome; retro neon; a gold badge.
 *
 * A title is a few plain texts of the sheet ([Texte.groupe] says they go
 * together): each stays editable (its words, colour, font, place); applying
 * another replaces them where they were. Their words come from the sheet's
 * event when it has one: its name, its series' number.
 */
object PlancheTitres {

    /** A styled title: [id] (what its texts' [Texte.groupe] says), its name a key of the string tables. */
    class Titre(val id: String, val cle: String)

    val LISTE: List<Titre> = listOf(
        Titre("ariss", "planche_titre_ariss"),
        Titre("neon", "planche_titre_neon"),
        Titre("ecusson", "planche_titre_ecusson"),
        Titre("chrome", "planche_titre_chrome"))

    /**
     * What a title says: [numero] the series' (« 33 », "" none known),
     * [evenement] the event's name, [serie] the word « SÉRIE » in the
     * sheet's language.
     */
    data class Infos(val numero: String = "", val evenement: String = "", val serie: String = "SERIES")

    /** The series' number in an event's name (« ARISS SSTV Series 33 » → « 33 »); "" none. */
    fun numero(nom: String?): String {
        if (nom.isNullOrBlank()) return ""
        Regex("(?i)(?:series|s[ée]rie)\\s*#?\\s*(\\d{1,4})").find(nom)?.let { return it.groupValues[1] }
        return Regex("(\\d{1,3})\\s*$").find(nom.trim())?.groupValues?.get(1) ?: ""
    }

    /** What the title says for [m]: its event's name and number. */
    fun infos(m: Modele, serie: String): Infos =
        Infos(numero(m.evenement?.nom), m.evenement?.nom.orEmpty(), serie)

    private const val BLEU_NEON = 0xFF2EC5FF.toInt()
    private const val CYAN = 0xFF00E5FF.toInt()
    private const val ROSE = 0xFFFF3FD2.toInt()
    private const val ACIER_BLEU = 0xFF6FAEFF.toInt()
    private const val BLANC = 0xFFFFFFFF.toInt()

    /** A line of a title, placed in fractions of its block, both when stacked ([pile]) and in a row ([rang]). */
    private class Ligne(val t: Texte, val pile: Zone, val rang: Zone)

    private fun lignes(id: String, i: Infos): List<Ligne> {
        fun libre(s: String, style: Style, police: String, c: Int = BLANC, esp: Float = 0f, filets: Boolean = false, c2: Int = 0) =
            Texte(Champ.LIBRE, Zone(0f, 0f, 1f, 1f), c, 0, s, police, style, c2, false, esp, filets, id)
        val num = i.numero
        // The event's name, its number left to the big figures.
        val nom = (if (num.isNotEmpty()) i.evenement.replace(Regex("\\s*#?\\s*" + Regex.escape(num) + "\\s*$"), "") else i.evenement).trim()
        return when (id) {
            "ariss" -> if (num.isNotEmpty()) listOf(
                Ligne(libre("SSTV", Style.CHROME, "exo2"), Zone(0.04f, 0f, 0.92f, 0.42f), Zone(0f, 0.05f, 0.40f, 0.90f)),
                Ligne(libre(i.serie.uppercase(), Style.NEON, "exo2", BLEU_NEON, 0.14f, true), Zone(0.10f, 0.42f, 0.80f, 0.19f), Zone(0.40f, 0.26f, 0.30f, 0.48f)),
                Ligne(libre(num, Style.CHROME, "exo2", ACIER_BLEU), Zone(0.18f, 0.61f, 0.64f, 0.39f), Zone(0.70f, 0.05f, 0.30f, 0.90f)))
            else listOf(
                Ligne(libre("SSTV", Style.CHROME, "exo2"), Zone(0.04f, 0f, 0.92f, 0.62f), Zone(0f, 0.05f, 0.55f, 0.90f)),
                Ligne(libre(i.serie.uppercase(), Style.NEON, "exo2", BLEU_NEON, 0.14f, true), Zone(0.10f, 0.64f, 0.80f, 0.32f), Zone(0.57f, 0.22f, 0.43f, 0.56f)))
            "neon" -> listOf(
                Ligne(libre(num.ifEmpty { "SSTV" }, Style.NEON, "monoton", CYAN), Zone(0.05f, 0f, 0.90f, 0.68f), Zone(0f, 0.04f, 0.42f, 0.92f)),
                Ligne(libre(nom.ifBlank { "ARISS · SSTV" }.uppercase(), Style.NEON, "audiowide", ROSE, 0.06f), Zone(0f, 0.72f, 1f, 0.26f), Zone(0.44f, 0.30f, 0.56f, 0.40f)))
            "ecusson" -> listOf(
                Ligne(libre(nom.ifBlank { "ARISS · SSTV" }.uppercase(), Style.OR, "michroma", BLANC, 0.08f), Zone(0f, 0f, 1f, 0.24f), Zone(0f, 0.28f, 0.58f, 0.44f)),
                Ligne(libre(num.ifEmpty { "SSTV" }, Style.OR, "orbitron"), Zone(0.10f, 0.27f, 0.80f, 0.73f), Zone(0.60f, 0.02f, 0.40f, 0.96f)))
            // One line: the sheet's own title (« Titre de la planche »), in chrome.
            "chrome" -> listOf(Ligne(Texte(Champ.TITRE, Zone(0f, 0f, 1f, 1f), BLANC, 0, "", "exo2", Style.CHROME, groupe = id),
                Zone(0f, 0.1f, 1f, 0.8f), Zone(0f, 0.1f, 1f, 0.8f)))
            else -> emptyList()
        }
    }

    private fun touche(a: Zone, b: Zone) = a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h

    /** Where a title goes ([bloc]), and the sheet's other texts — moved down when it pushed them. */
    class Place(val bloc: Zone, val textes: List<Texte>)

    private const val PAS = 0.005f
    private const val MARGE = 0.012f
    private fun gonfle(c: Zone) = Zone(c.x - MARGE, c.y - MARGE, c.w + 2 * MARGE, c.h + 2 * MARGE)
    private fun dans(c: Zone) = c.x >= 0.01f && c.y >= 0.01f && c.x + c.w <= 0.99f && c.y + c.h <= 0.99f

    /**
     * Where a title goes on [m]: where the last one was, else the sheet's
     * title text, else a band at the top. Grown into the free room round it
     * (no box, no other text): up, then down — the texts under it pushed
     * down while they have room —, then both sides alike; to a block at most
     * 0.8 as high as wide, and not stretched thin.
     */
    fun place(m: Modele): Place {
        val reste = m.textes.filter { it.groupe.isEmpty() && it.champ != Champ.TITRE }
        Planche.englobe(m.textes.filter { it.groupe.isNotEmpty() }.map { it.zone })?.let { return Place(it, reste) }
        var z = m.textes.firstOrNull { it.champ == Champ.TITRE }?.zone ?: return Place(Zone(0.22f, 0.02f, 0.56f, 0.2f), reste)
        var autres = reste
        fun gene(c: Zone, l: List<Texte>) = (m.cases.map { it } + l.map { it.zone }).any { touche(it, gonfle(c)) }
        fun haut(c: Zone) = c.h <= c.w * m.ratio * 0.8f
        while (true) { val c = Zone(z.x, z.y - PAS, z.w, z.h + PAS); if (haut(c) && dans(c) && !gene(c, autres)) z = c else break }
        while (true) {
            val c = Zone(z.x, z.y, z.w, z.h + PAS)
            if (!haut(c) || !dans(c) || m.cases.any { touche(it, gonfle(c)) }) break
            autres = pousse(c, autres, m.cases) ?: break
            z = c
        }
        while (true) { val c = Zone(z.x - PAS, z.y, z.w + 2 * PAS, z.h); if (c.w * m.ratio <= c.h * 4.2f && dans(c) && !gene(c, autres)) z = c else break }
        return Place(z, autres)
    }

    /** Where a title goes on [m] ([place]). */
    fun bloc(m: Modele): Zone = place(m).bloc

    /**
     * [l] with the texts [c] would touch moved down below it, and those they
     * then touch, within the sheet and clear of the boxes; null when there is
     * no room (or a text beside it, not under it).
     */
    private fun pousse(c: Zone, l: List<Texte>, cases: List<Zone>): List<Texte>? {
        val out = l.toMutableList()
        // What pushes: the block (-1), then each text moved.
        val file = ArrayDeque<Int>().apply { add(-1) }
        var n = 0
        while (file.isNotEmpty()) {
            if (++n > 60) return null
            val j = file.removeFirst()
            val o = if (j < 0) c else out[j].zone
            for (i in out.indices) {
                if (i == j) continue
                val t = out[i]
                if (!touche(t.zone, gonfle(o))) continue
                if (t.zone.y < o.y + o.h * 0.5f) return null
                val nz = t.zone.copy(y = o.y + o.h + MARGE + 0.0001f)
                if (nz.y + nz.h > 0.99f || cases.any { touche(it, nz) }) return null
                out[i] = t.copy(zone = nz); file.add(i)
            }
        }
        return out
    }

    /**
     * [m] with the styled title [id]: the texts of the one before taken
     * away (and the sheet's plain title, which this one takes the place
     * of), the new ones laid where [place] says — in a row when the block is
     * wide, else stacked. "aucun": the plain title back where the styled one was.
     */
    fun applique(m: Modele, id: String, infos: Infos): Modele {
        val p = place(m)
        val ou = p.bloc
        if (id == "aucun" || LISTE.none { it.id == id })
            return m.copy(textes = p.textes + Texte(Champ.TITRE, ou, Planche.JAUNE), textesOrigine = emptyList())
        val enRang = ou.w * m.ratio / ou.h > 3.2f
        val l = lignes(id, infos)
        val zs = l.map { if (enRang) it.rang else it.pile }
        // The lines fill the block exactly: applied again, a title finds the same block.
        val u = Planche.englobe(zs)!!
        val nouvelles = l.mapIndexed { i, x ->
            val z = zs[i]
            val fx = (z.x - u.x) / u.w; val fy = (z.y - u.y) / u.h
            x.t.copy(zone = Zone(ou.x + fx * ou.w, ou.y + fy * ou.h, z.w / u.w * ou.w, z.h / u.h * ou.h).bornee())
        }
        return m.copy(textes = p.textes + nouvelles, textesOrigine = emptyList())
    }

    /**
     * « Affiche ISS »: a stylish sheet in one click — the ISS over the Earth
     * (NASA), « SSTV SERIES n » in chrome and neon across the top, twelve
     * boxes in glowing frames, the callsign, name @ locator and dates along
     * the bottom.
     */
    fun genereAffiche(id: String, nom: String, infos: Infos = Infos()): Modele {
        val r = 1.414f
        val base = Modele(
            id = id, nom = nom, ratio = r, legende = true, numeros = false,
            decor = "nasa_iss_espace", cadre = PlancheStyle.Cadre.NEON, cadreCouleur = BLEU_NEON,
            cases = Planche.grilleOptimale(12, Zone(0.03f, 0.255f, 0.94f, 0.655f), r, Planche.aspectCase(true), 0.07f),
            textes = listOf(
                Texte(Champ.TITRE, Zone(0.16f, 0.015f, 0.68f, 0.225f), BLANC),
                Texte(Champ.INDICATIF, Zone(0.04f, 0.925f, 0.22f, 0.06f), BLANC, 0, "", "orbitron", Style.NEON, couleur2 = 0),
                Texte(Champ.NOM_LOCATOR, Zone(0.33f, 0.925f, 0.34f, 0.06f), BLANC, 0, "", "michroma"),
                Texte(Champ.DATES, Zone(0.72f, 0.925f, 0.24f, 0.06f), BLANC, 0, "", "michroma")))
        val m = applique(base, "ariss", infos)
        // The callsign glows in the frames' colour.
        return m.copy(textes = m.textes.map { if (it.champ == Champ.INDICATIF) it.copy(couleur = BLEU_NEON) else it })
    }
}
