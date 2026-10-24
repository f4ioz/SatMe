/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.station

import fr.f4ioz.satcombo.station.Montage.Chaine
import fr.f4ioz.satcombo.station.Montage.Genre
import org.json.JSONArray
import org.json.JSONObject

/**
 * The station's plans: several drawings of the same station (« SatMe-Node
 * déporté », « IC-9700 satellites »…), one shown, and the choice of the one
 * that draws what works now.
 *
 * Kept in one file (`montage.json`): the first plan where the single chain
 * always was (an older SatMe reads it as before), the others beside it.
 * Display only, like [Montage]: a plan changes no setting.
 */
object PlansMontage {

    /** One drawing of the station: [id] short and plain ("p1"), its name the chain's. */
    data class Plan(val id: String, val chaine: Chaine) {
        val nom: String get() = chaine.nom
    }

    /** All the plans; [affiche] the one the owner chose; [suivre] « Afficher le plan de ce qui tourne ». */
    data class Plans(val plans: List<Plan>, val affiche: String, val suivre: Boolean = true) {
        fun plan(id: String?): Plan? = plans.firstOrNull { it.id == id }
        /** The owner's plan (the first when [affiche] is gone). */
        val choisi: Plan get() = plan(affiche) ?: plans.first()
        /** [id]'s chain replaced by [c] (its name kept when [c] has none). */
        fun change(id: String, c: Chaine): Plans = copy(plans = plans.map { if (it.id == id) it.copy(chaine = c) else it })
    }

    const val VERSION = 2
    const val MAX = 20
    /** The first plan's id (the single chain of the older files). */
    const val PREMIER = "p1"

    // ------------------------------------------------------------------ file

    /**
     * The plans read back: a file of one chain (version 1, or none) as one
     * plan; version 2 as written by [ecrit]. Never empty.
     */
    fun lit(texte: String?): Plans {
        val o = runCatching { JSONObject(texte ?: "") }.getOrNull() ?: return Plans(listOf(Plan(PREMIER, Chaine())), PREMIER)
        val premier = Plan(o.optString("id").takeIf { Montage.ID.matches(it) } ?: PREMIER, Montage.lit(o))
        val l = arrayListOf(premier)
        val a = o.optJSONArray("plans") ?: JSONArray()
        for (i in 0 until minOf(a.length(), MAX * 2)) {
            if (l.size >= MAX) break
            val x = a.optJSONObject(i) ?: continue
            val id = x.optString("id")
            if (!Montage.ID.matches(id) || l.any { it.id == id }) continue
            l += Plan(id, Montage.lit(x))
        }
        val aff = o.optString("affiche").takeIf { id -> l.any { it.id == id } } ?: premier.id
        return Plans(l, aff, o.optBoolean("suivre", true))
    }

    /** The file: the first plan at the top (as version 1 wrote its chain), the others in `plans`. */
    fun ecrit(p: Plans): JSONObject {
        val o = Montage.ecrit(p.plans.first().chaine).put("v", VERSION).put("id", p.plans.first().id)
        o.put("plans", JSONArray(p.plans.drop(1).map { Montage.ecrit(it.chaine).put("id", it.id) }))
        return o.put("affiche", p.affiche).put("suivre", p.suivre)
    }

    /** The list for the page: id, name, how many pieces and cables. */
    fun json(p: Plans): JSONArray = JSONArray(p.plans.map { JSONObject().put("id", it.id).put("nom", nomVu(it)).put("elements", it.chaine.elements.size).put("liens", it.chaine.liens.size) })

    /** The name shown: the owner's, else « Plan n ». */
    fun nomVu(p: Plan): String = p.nom.ifBlank { "Plan " + p.id.removePrefix("p") }

    /** A new plan's id, not yet in [p]. */
    fun nouvelId(p: Plans): String {
        var i = p.plans.size + 1
        while (p.plans.any { it.id == "p$i" }) i++
        return "p$i"
    }

    /** [nom] made unique among [p]'s names (« IC-9700 satellites (2) »). */
    fun nomLibre(p: Plans, nom: String, sauf: String? = null): String {
        val pris = p.plans.filter { it.id != sauf }.map { nomVu(it).lowercase() }.toSet()
        if (nom.lowercase() !in pris) return nom
        var i = 2
        while ("$nom ($i)".lowercase() in pris) i++
        return "$nom ($i)"
    }

    // ------------------------------------------------------------------ what each plan covers

    /** What [c] draws of the activity: the roles of [a] it has a piece for, its score (see [couverture]). */
    data class Couverture(val roles: Set<String>, val score: Double)

    /**
     * How well [c] draws [a]: one point for each role receiving or
     * transmitting it has a piece for, a quarter for each piece only linked
     * (the phone, a program), a tenth at most for the share of its pieces lit
     * (a smaller plan of the same thing wins).
     */
    fun couverture(c: Chaine, a: ActiviteMontage.Activite): Couverture {
        val ont = c.elements.flatMap { Montage.modele(it)?.roles ?: emptySet() }.toSet()
        val roles = (a.rx + a.tx).filter { it in ont }.toSet()
        if (roles.isEmpty()) return Couverture(emptySet(), 0.0)
        val lies = a.relies.count { it in ont }
        val al = ActiviteMontage.allume(c, a)
        val part = if (c.elements.isEmpty()) 0.0 else al.enOeuvre().size.toDouble() / c.elements.size
        return Couverture(roles, roles.size + 0.25 * lies + 0.1 * part)
    }

    /**
     * The plan shown now: [plan] null for the chain detected live; [epingle]
     * the owner's choice held while something works; [suit] whether it was
     * chosen from the activity; [roles] what of it the plan draws.
     */
    data class Choix(val plan: String?, val suit: Boolean, val epingle: Boolean = false, val roles: Set<String> = emptySet(), val raison: String = "") {
        val detectee get() = plan == null
    }

    /**
     * « Afficher le plan de ce qui tourne »: the saved plan that best draws
     * the activity, held [delaiMs] before giving way to a better one (no
     * flip-flop on a passing activity), kept [finMs] after the activity ends.
     * A plan chosen by hand ([epingle]) stays until the toggle is used again
     * or the activity ends.
     */
    class Suivi(private val delaiMs: Long = 10_000L, private val finMs: Long = 30_000L, private val marge: Double = 0.5) {
        /** The plan shown from the activity ("" the chain detected), null none yet. */
        @Volatile var courant: String? = null; private set
        @Volatile var epingle: String? = null; private set
        private var candidat: String? = null
        private var candidatDepuis = 0L
        private var dernierActif: Long? = null

        /** The owner chose [id] by hand: held while something works. */
        @Synchronized fun epingle(id: String) { epingle = id }

        /** [id] shown at once from the activity (a chain detected just kept as a plan). */
        @Synchronized fun montre(id: String) { courant = id; candidat = null }

        /** The toggle used: the choice by hand forgotten, the activity read afresh. */
        @Synchronized fun relache() { epingle = null; courant = null; candidat = null }

        @Synchronized fun choisit(p: Plans, a: ActiviteMontage.Activite, t: Long): Choix {
            val choisi = p.choisi.id
            if (!p.suivre) { relache(); return Choix(choisi, false, raison = "plan choisi") }
            val actif = !a.vide
            if (actif) dernierActif = t
            val fini = !actif && dernierActif.let { it == null || t - it >= finMs }
            if (fini) { relache(); dernierActif = null; return Choix(choisi, false, raison = "rien ne tourne") }
            epingle?.let { e ->
                if (p.plan(e) != null) return Choix(e, false, true, if (actif) couverture(p.plan(e)!!.chaine, a).roles else emptySet(), "choisi à la main")
                epingle = null
            }
            // A pause in the activity: what was shown stays.
            if (!actif) return courant?.let { k -> if (k == "") Choix(null, true, raison = "chaîne détectée") else p.plan(k)?.let { Choix(it.id, true, raison = "ce qui tournait") } }
                ?: Choix(choisi, false, raison = "rien ne tourne")
            val notes = p.plans.associate { it.id to couverture(it.chaine, a) }
            // The best: ties to the plan shown, then the owner's, then the list's order.
            val ordre = listOfNotNull(courant?.takeIf { it.isNotEmpty() }, choisi) + p.plans.map { it.id }
            val meilleur = ordre.distinct().filter { it in notes }.maxByOrNull { notes[it]!!.score }!!
            val cible = if (notes[meilleur]!!.score >= 1.0) meilleur else ""
            val k = courant?.takeIf { it == "" || p.plan(it) != null }
            val ici = k?.let { if (it == "") 0.0 else notes[it]!!.score } ?: -1.0
            when {
                // None yet, or the one shown draws nothing of it: at once.
                k == null || ici < 1.0 -> { courant = cible; candidat = null }
                cible == k -> candidat = null
                notes[cible]!!.score < ici + marge -> candidat = null
                candidat != cible -> { candidat = cible; candidatDepuis = t }
                t - candidatDepuis >= delaiMs -> { courant = cible; candidat = null }
            }
            val c = courant!!
            return if (c == "") Choix(null, true, raison = "aucun plan ne dessine ce qui tourne")
                else Choix(c, true, roles = notes[c]!!.roles, raison = "dessine ce qui tourne")
        }
    }

    // ------------------------------------------------------------------ the chain detected live

    /**
     * The chain working now, from SatMe's proposal [proposee] (see
     * [DetectionMontage.propose]): only its pieces lit by [a], a rig given
     * the antenna of the band it listens on when it has none. Null when
     * nothing of it works.
     */
    fun chaineVive(proposee: Chaine, a: ActiviteMontage.Activite): Chaine? {
        if (a.vide) return null
        val b = antennes(proposee, a)
        val al = ActiviteMontage.allume(b, a)
        val garde = al.enOeuvre()
        if (b.elements.none { it.id in garde && Montage.modele(it)?.genre != Genre.ORDINATEUR }) return null
        val c = Chaine(b.elements.filter { it.id in garde },
            b.liens.filter { it.de.element in garde && it.vers.element in garde && al.liens[it.id] != ActiviteMontage.Etat.REPOS }, "Chaîne détectée")
        return Montage.nettoie(c)
    }

    /** A rig receiving with no antenna drawn: the Yagi of the band it listens on (both bands when unknown). */
    private fun antennes(c: Chaine, a: ActiviteMontage.Activite): Chaine {
        var r = c
        val mhz = a.mhz["poste-rx"] ?: a.mhz["ciel-rx"]?.takeIf { it < 3000 }
        for (e in c.elements) {
            if (Montage.modele(e)?.genre != Genre.POSTE || (Montage.modele(e)?.roles.orEmpty() intersect a.rx).isEmpty()) continue
            val rf = Montage.ports(e).filter { it.signal == Montage.Signal.RF }
            if (r.liens.any { l -> rf.any { p -> (l.de.element == e.id && l.de.port == p.id) || (l.vers.element == e.id && l.vers.port == p.id) } }) continue
            val port = mhz?.let { f -> rf.filter { it.mhz != null && kotlin.math.abs(f - it.mhz) / it.mhz < 0.15 }.minByOrNull { kotlin.math.abs(f - it.mhz!!) } }
            val (modele, pa) = when {
                port != null && port.mhz!! < 300 -> "yagi-2m" to "ant"
                port != null && port.mhz!! < 600 -> "yagi-70cm" to "ant"
                port != null -> continue
                else -> "yagi-bibande" to ""
            }
            val id = Montage.nouvelId(r, "e")
            r = r.copy(elements = r.elements + Montage.Element(id, modele))
            val ports = if (port != null) listOf(pa to port.id) else listOf("ant144" to "ant144", "ant432" to "ant432")
            for ((de, vers) in ports) {
                if (rf.none { it.id == vers }) continue
                val x = Montage.port(r, Montage.Bout(id, de)) ?: continue
                val y = Montage.port(r, Montage.Bout(e.id, vers)) ?: continue
                val k = Montage.cableParDefaut(x, y) ?: continue
                if (Montage.refus(r, Montage.Bout(id, de), Montage.Bout(e.id, vers), k) == null)
                    r = r.copy(liens = r.liens + Montage.Lien(Montage.nouvelId(r, "l"), Montage.Bout(id, de), Montage.Bout(e.id, vers), k))
            }
        }
        return r
    }

    /** A name for the chain detected, kept as a plan: its rig or receiver and what it does (« IC-9700 · SSTV »). */
    fun nomPropose(c: Chaine, a: ActiviteMontage.Activite): String {
        val tete = c.elements.firstOrNull { Montage.modele(it)?.genre == Genre.POSTE }
            ?: c.elements.firstOrNull { Montage.modele(it)?.genre in setOf(Genre.RECEPTEUR_DATV, Genre.SDR) }
        val quoi = a.resume.firstOrNull()?.substringBefore(" :")?.takeIf { it.length in 2..30 }
        return listOfNotNull(tete?.let { Montage.nomCourt(it).removePrefix("Icom ").removePrefix("Kenwood ").removePrefix("Yaesu ") }, quoi)
            .joinToString(" · ").ifBlank { "Chaîne détectée" }
    }
}
