/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.station

import fr.f4ioz.satcombo.station.Montage.Bout
import fr.f4ioz.satcombo.station.Montage.Cable
import fr.f4ioz.satcombo.station.Montage.Chaine
import fr.f4ioz.satcombo.station.Montage.Element
import fr.f4ioz.satcombo.station.Montage.Lien

/**
 * The edits of the « Montage » drawing, one gesture each: a piece dropped
 * from the palette, a cable drawn from port to port, a piece moved, a
 * cable's kind changed… Each gives a new chain (cleaned as any other), or
 * says why not and leaves it as it was. [Historique] keeps the chains
 * before, for « Annuler » and « Rétablir ».
 *
 * A drawing only: an edit changes no setting of the station.
 */
object EditionMontage {

    sealed class Op {
        /** A piece added; at [x], [y] on the drawing when dropped there. */
        data class Ajoute(val modele: String, val x: Int? = null, val y: Int? = null, val options: Map<String, String> = emptyMap()) : Op()
        data class Retire(val id: String) : Op()
        /** A cable from port to port; [cable] null: the one the two ports call for. */
        data class Relie(val de: Bout, val vers: Bout, val cable: Cable? = null, val note: String = "") : Op()
        data class Delie(val id: String) : Op()
        /** A cable's kind ([cable]) or label ([note]) changed, null: kept. */
        data class Cablage(val id: String, val cable: Cable? = null, val note: String? = null) : Op()
        data class Deplace(val id: String, val x: Int, val y: Int) : Op()
        data class Renomme(val id: String, val nom: String) : Op()
        /** One of a piece's options set (a reference input fitted, an output's frequency…). */
        data class Regle(val id: String, val option: String, val valeur: String) : Op()
        /** A piece swapped for another model (a proposal's converter for the owner's own): its cables kept where its ports still are. */
        data class Modele(val id: String, val modele: String) : Op()
        /** The piece drawn by its photo cut out ([on]), or by SatMe's drawing. */
        data class Photo(val id: String, val on: Boolean) : Op()
        /** The places the owner gave forgotten: the automatic layout again. */
        object DispositionAuto : Op()
        /** A 2 m / 70 cm antenna added to the rig [id], cabled to its free receiving ports of those bands. */
        data class Antenne(val id: String) : Op()
    }

    /** The receiving antenna ports of the rig [id] in the 2 m and 70 cm bands with no cable yet. */
    fun antennesLibres(c: Chaine, id: String): List<Montage.Port> {
        val e = c.element(id) ?: return emptyList()
        if (Montage.modele(e)?.genre != Montage.Genre.POSTE) return emptyList()
        return Montage.ports(e).filter { p ->
            p.signal == Montage.Signal.RF && p.voie.recoit && p.sens != Montage.Sens.SORTIE && (p.mhz ?: 0) in 100..999 &&
                c.liens.none { (it.de.element == id && it.de.port == p.id) || (it.vers.element == id && it.vers.port == p.id) }
        }
    }

    /** The antenna that suits the rig [id]: a dual-band Yagi for separate 2 m and 70 cm ports, a single-band Yagi for one, a vertical for a shared port. */
    fun antennePourPoste(c: Chaine, id: String): String? {
        val e = c.element(id) ?: return null
        val ports = Montage.ports(e).filter { it.signal == Montage.Signal.RF && it.voie.recoit && (it.mhz ?: 0) in 100..999 }
        val deux = ports.any { it.mhz!! < 300 }; val soixante = ports.any { it.mhz!! >= 300 }
        return when {
            ports.isEmpty() -> null
            ports.size == 1 && ports.single().id == "ant" -> "colineaire"
            deux && soixante -> "yagi-bibande"
            deux -> "yagi-2m"
            else -> "yagi-70cm"
        }
    }

    /** What an edit gives: the new chain, the id of what it added, or why it could not be (the chain unchanged). */
    data class Resultat(val chaine: Chaine, val id: String? = null, val erreur: String? = null, val dit: String = "") {
        val ok get() = erreur == null
    }

    private fun non(c: Chaine, pourquoi: String) = Resultat(c, erreur = pourquoi)

    /**
     * Every piece put where the automatic layout draws it: the first move in
     * an automatic drawing keeps the others where they were seen.
     */
    fun fige(c: Chaine): Chaine {
        if (c.elements.any { it.place }) return c
        val p = DispositionMontage.dispose(c)
        return c.copy(elements = c.elements.map { e -> p.boite(e.id)?.let { b -> e.copy(x = b.x, y = b.y) } ?: e })
    }

    fun applique(c: Chaine, op: Op): Resultat = when (op) {
        is Op.Ajoute -> {
            val m = CatalogueMateriel.modele(op.modele)
            when {
                m == null -> non(c, "appareil inconnu")
                c.elements.size >= Montage.ELEMENTS_MAX -> non(c, "${Montage.ELEMENTS_MAX} appareils au plus")
                else -> {
                    val id = Montage.nouvelId(c, "e")
                    val base = if (op.x != null && op.y != null) fige(c) else c
                    val e = Element(id, m.id, "", op.options, op.x?.coerceAtLeast(0), op.y?.coerceAtLeast(0))
                    Resultat(Montage.nettoie(base.copy(elements = base.elements + e)), id, dit = "Ajouté : " + m.nom)
                }
            }
        }
        is Op.Retire -> {
            val e = c.element(op.id)
            if (e == null) non(c, "appareil inconnu")
            else Resultat(c.copy(elements = c.elements - e, liens = c.liens.filter { it.de.element != e.id && it.vers.element != e.id }), dit = "Retiré : " + Montage.nom(e))
        }
        is Op.Relie -> {
            val a = Montage.port(c, op.de); val b = Montage.port(c, op.vers)
            val k = op.cable ?: if (a != null && b != null) Montage.cableParDefaut(a, b) else null
            val r = when {
                c.liens.size >= Montage.LIENS_MAX -> "${Montage.LIENS_MAX} câbles au plus"
                a == null || b == null -> "port inconnu"
                k == null -> "« ${a.nom} » et « ${b.nom} » ne se relient pas"
                else -> Montage.refus(c, op.de, op.vers, k)
            }
            if (r != null) non(c, r)
            else {
                val id = Montage.nouvelId(c, "l")
                Resultat(Montage.nettoie(c.copy(liens = c.liens + Lien(id, op.de, op.vers, k!!, op.note))), id, dit = "Câble ajouté : " + k.nom)
            }
        }
        is Op.Delie -> if (c.liens.none { it.id == op.id }) non(c, "câble inconnu") else Resultat(c.copy(liens = c.liens.filter { it.id != op.id }), dit = "Câble retiré")
        is Op.Cablage -> {
            val l = c.liens.firstOrNull { it.id == op.id }
            val k = op.cable ?: l?.cable
            val r = when {
                l == null || k == null -> "câble inconnu"
                else -> Montage.refus(c, l.de, l.vers, k, sauf = l.id)
            }
            if (r != null) non(c, r)
            else Resultat(Montage.nettoie(c.copy(liens = c.liens.map { if (it.id == op.id) it.copy(cable = k!!, note = op.note ?: it.note) else it })), op.id)
        }
        is Op.Deplace -> {
            if (c.element(op.id) == null) non(c, "appareil inconnu")
            else {
                val f = fige(c)
                Resultat(Montage.nettoie(f.copy(elements = f.elements.map { if (it.id == op.id) it.copy(x = op.x.coerceAtLeast(0), y = op.y.coerceAtLeast(0)) else it })), op.id)
            }
        }
        is Op.Renomme -> if (c.element(op.id) == null) non(c, "appareil inconnu")
            else Resultat(Montage.nettoie(c.copy(elements = c.elements.map { if (it.id == op.id) it.copy(nom = op.nom) else it })), op.id)
        is Op.Regle -> {
            val e = c.element(op.id); val m = e?.let { Montage.modele(it) }
            val o = m?.options?.firstOrNull { it.id == op.option }
            when {
                e == null || m == null -> non(c, "appareil inconnu")
                o == null || !o.admet(op.valeur) -> non(c, "réglage inconnu")
                else -> {
                    val n = Montage.nettoie(c.copy(elements = c.elements.map { if (it.id == op.id) it.copy(options = it.options + (o.id to op.valeur)) else it }))
                    val perdus = c.liens.size - n.liens.size
                    Resultat(n, op.id, dit = o.nom + " : " + (o.valeurs.firstOrNull { it.first == op.valeur }?.second ?: op.valeur) +
                        (if (perdus > 0) " ($perdus câble" + (if (perdus > 1) "s" else "") + " retiré" + (if (perdus > 1) "s" else "") + " : son port n'existe plus)" else ""))
                }
            }
        }
        is Op.Modele -> {
            val e = c.element(op.id); val m = CatalogueMateriel.modele(op.modele)
            if (e == null || m == null) non(c, "appareil inconnu")
            else {
                val n = Montage.nettoie(c.copy(elements = c.elements.map { if (it.id == op.id) it.copy(modele = m.id, options = emptyMap()) else it }))
                val perdus = c.liens.size - n.liens.size
                Resultat(n, op.id, dit = "Remplacé par : " + m.nom + (if (perdus > 0) " ($perdus câble" + (if (perdus > 1) "s" else "") + " sans port retiré" + (if (perdus > 1) "s" else "") + ")" else ""))
            }
        }
        is Op.Photo -> if (c.element(op.id) == null) non(c, "appareil inconnu")
            else Resultat(c.copy(elements = c.elements.map { if (it.id == op.id) it.copy(photo = op.on) else it }), op.id,
                dit = if (op.on) "Photo à la place du dessin" else "Dessin de SatMe")
        Op.DispositionAuto -> Resultat(c.copy(elements = c.elements.map { it.copy(x = null, y = null) }), dit = "Disposition automatique")
        is Op.Antenne -> {
            val libres = antennesLibres(c, op.id)
            val m = antennePourPoste(c, op.id)?.let { CatalogueMateriel.modele(it) }
            if (c.element(op.id) == null || m == null) non(c, "pas un poste 2 m / 70 cm")
            else if (libres.isEmpty()) non(c, "les prises d'antenne 2 m / 70 cm du poste ont déjà leur câble")
            else {
                val a = applique(c, Op.Ajoute(m.id))
                var n = a.chaine; val relies = ArrayList<String>()
                for (p in libres) {
                    // The antenna's port of the same band (a dual-band Yagi has one each; the others one for all).
                    val q = m.ports.filter { it.signal == Montage.Signal.RF }.minByOrNull { kotlin.math.abs((it.mhz ?: 0) - (p.mhz ?: 0)) } ?: continue
                    if (n.liens.any { (it.de.element == a.id && it.de.port == q.id) || (it.vers.element == a.id && it.vers.port == q.id) }) continue
                    val r = applique(n, Op.Relie(Bout(a.id!!, q.id), Bout(op.id, p.id), Cable.coax(p.connecteur)))
                    if (r.ok) { n = r.chaine; relies += p.nom }
                }
                Resultat(n, a.id, dit = "Ajoutée : " + m.nom + (if (relies.isNotEmpty()) ", reliée à " + relies.joinToString(" et ") { "« $it »" } else ""))
            }
        }
    }

    /** The edit a page asks by its parameters (`op=ajoute&m=…&x=…&y=…`, `op=relie&de=e1.sortie&vers=e2.entree&c=COAX_N`…), or null. */
    fun lit(p: (String) -> String): Op? {
        fun int(k: String) = p(k).toDoubleOrNull()?.takeIf { it.isFinite() }?.toInt()
        val id = p("id")
        return when (p("op")) {
            "ajoute" -> Op.Ajoute(p("m"), int("x"), int("y"))
            "retire" -> Op.Retire(id)
            "relie" -> {
                val de = Bout.lit(p("de")) ?: return null; val vers = Bout.lit(p("vers")) ?: return null
                Op.Relie(de, vers, Cable.de(p("c").ifBlank { null }), p("note"))
            }
            "delie" -> Op.Delie(id)
            "cable" -> Op.Cablage(id, Cable.de(p("c").ifBlank { null }), p("note").takeIf { p("avecNote") == "1" })
            "deplace" -> Op.Deplace(id, int("x") ?: return null, int("y") ?: return null)
            "renomme" -> Op.Renomme(id, p("nom"))
            "regle" -> Op.Regle(id, p("k"), p("v"))
            "modele" -> Op.Modele(id, p("m"))
            "photo" -> Op.Photo(id, p("on") == "1")
            "auto" -> Op.DispositionAuto
            "antenne" -> Op.Antenne(id)
            else -> null
        }
    }

    /**
     * The chains before each edit, for « Annuler » (Ctrl+Z) and « Rétablir »
     * (Ctrl+Y): [max] steps kept, the steps undone forgotten at a new edit.
     */
    class Historique(private val max: Int = 60) {
        private val avant = ArrayDeque<Chaine>()
        private val apres = ArrayDeque<Chaine>()

        val peutAnnuler get() = avant.isNotEmpty()
        val peutRefaire get() = apres.isNotEmpty()

        /** [ancienne] replaced by an edit (nothing kept when the edit changed nothing). */
        @Synchronized fun pousse(ancienne: Chaine, nouvelle: Chaine) {
            if (ancienne == nouvelle) return
            avant.addLast(ancienne)
            while (avant.size > max) avant.removeFirst()
            apres.clear()
        }

        /** The chain before [actuelle], or null when there is none. */
        @Synchronized fun annule(actuelle: Chaine): Chaine? {
            val c = avant.removeLastOrNull() ?: return null
            apres.addLast(actuelle)
            return c
        }

        /** The chain undone last, or null. */
        @Synchronized fun refait(actuelle: Chaine): Chaine? {
            val c = apres.removeLastOrNull() ?: return null
            avant.addLast(actuelle)
            return c
        }
    }
}
