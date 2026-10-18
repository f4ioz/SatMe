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
import fr.f4ioz.satcombo.station.Montage.Element
import fr.f4ioz.satcombo.station.Montage.Genre
import fr.f4ioz.satcombo.station.Montage.Lien
import fr.f4ioz.satcombo.station.Montage.Sens
import fr.f4ioz.satcombo.station.Montage.Signal
import fr.f4ioz.satcombo.station.Montage.Voie
import org.json.JSONArray
import org.json.JSONObject

/**
 * The chain laid out as a drawing, left to right: the antennas first, then
 * what sits at the mast (LNB, amplifier), the converters, the radios, the
 * boxes around the computer, the computer and the phone last. In each
 * column the reception path above, the transmission below; the cables
 * orthogonal, in the gaps between the columns (or under the drawing when a
 * cable would cross a piece of hardware).
 */
object DispositionMontage {

    /** Room for the name under each drawing. */
    const val ETIQUETTE = 18
    const val MARGE = 24
    private const val ECART_H = 120
    private const val ECART_V = 34
    private const val PAS_VOIE = 10

    data class Boite(val id: String, val x: Int, val y: Int, val l: Int, val h: Int, val colonne: Int) {
        val bas get() = y + h
        val milieuY get() = y + (h - ETIQUETTE) / 2
    }

    /** A cable drawn: its corners, where its label goes, its two ends. */
    data class Trace(val id: String, val points: List<Pair<Int, Int>>, val etiquette: Pair<Int, Int>,
                     val de: Pair<Int, Int>, val vers: Pair<Int, Int>)

    data class Plan(val largeur: Int, val hauteur: Int, val boites: List<Boite>, val traces: List<Trace>) {
        fun boite(id: String) = boites.firstOrNull { it.id == id }
    }

    /** The genre's place, the phone after the computer. */
    private fun base(e: Element) = Montage.modele(e)?.genre?.colonne ?: 3

    /** An element's paths: 0 reception, 1 both, 2 transmission, 3 none (data, rotor). */
    private fun rangVoie(e: Element, liens: List<Lien>): Int {
        val m = Montage.modele(e) ?: return 3
        val relies = liens.flatMap { listOf(it.de, it.vers) }.filter { it.element == e.id }.map { it.port }.toSet()
        val rf = m.ports.filter { it.signal == Signal.RF && (relies.isEmpty() || it.id in relies) }
        if (rf.isEmpty()) return 3
        val rx = rf.any { it.voie.recoit }; val tx = rf.any { it.voie.emet }
        return when { rx && tx -> 1; rx -> 0; tx -> 2; else -> 3 }
    }

    /**
     * Which end of [l] stands to the left (nearer the antenna), or null when
     * nothing says. A genre further from the antenna stands right; between
     * two of the same, the radio's way: in reception the output is nearer the
     * antenna, in transmission the input.
     */
    private fun gauche(c: Chaine, l: Lien): String? {
        val a = c.element(l.de.element) ?: return null
        val b = c.element(l.vers.element) ?: return null
        val ba = base(a); val bb = base(b)
        if (ba != bb) return if (ba < bb) a.id else b.id
        val pa = Montage.port(c, l.de) ?: return null
        val pb = Montage.port(c, l.vers) ?: return null
        if (pa.signal != Signal.RF || pb.signal != Signal.RF) return null
        val voie = if (pa.voie == Voie.TX || pb.voie == Voie.TX) Voie.TX else Voie.RX
        val proche = if (voie == Voie.RX) Sens.SORTIE else Sens.ENTREE
        return when {
            pa.sens == proche && pb.sens != proche -> a.id
            pb.sens == proche && pa.sens != proche -> b.id
            pa.sens == Sens.BIDIR && pb.sens != Sens.BIDIR -> a.id
            pb.sens == Sens.BIDIR && pa.sens != Sens.BIDIR -> b.id
            else -> null
        }
    }

    /**
     * The drawing of [chaine]; [garder] the elements to draw (the simplified
     * view), all of them when null.
     */
    fun dispose(chaine: Chaine, garder: Set<String>? = null): Plan {
        val elements = chaine.elements.filter { garder == null || it.id in garder }
        val ids = elements.map { it.id }.toSet()
        val liens = chaine.liens.filter { it.de.element in ids && it.vers.element in ids }
        val c = Chaine(elements, liens)
        if (elements.isEmpty()) return Plan(2 * MARGE, 2 * MARGE, emptyList(), emptyList())
        val taille = elements.associate { e -> e.id to (Montage.modele(e)!!.let { it.l to it.h + ETIQUETTE }) }

        // ---- columns: the genre's place at least, right of what stands left of it
        val avant = HashMap<String, MutableSet<String>>()
        for (l in liens) {
            val g = gauche(c, l) ?: continue
            val d = if (g == l.de.element) l.vers.element else l.de.element
            avant.getOrPut(d) { HashSet() }.add(g)
        }
        val col = HashMap<String, Int>()
        elements.forEach { col[it.id] = base(it) }
        repeat(elements.size + 1) {
            var bouge = false
            for (e in elements) {
                val v = maxOf(base(e), (avant[e.id] ?: emptySet()).maxOfOrNull { (col[it] ?: 0) + 1 } ?: 0)
                if (v != col[e.id] && v <= 40) { col[e.id] = v; bouge = true }
            }
            if (!bouge) return@repeat
        }
        // The computer and the phone always last.
        val genre = elements.associate { it.id to Montage.modele(it)!!.genre }
        val autres = elements.filter { genre[it.id] != Genre.ORDINATEUR && genre[it.id] != Genre.TELEPHONE }.maxOfOrNull { col[it.id]!! } ?: -1
        elements.filter { genre[it.id] == Genre.ORDINATEUR }.forEach { col[it.id] = maxOf(col[it.id]!!, autres + 1) }
        val pc = elements.filter { genre[it.id] == Genre.ORDINATEUR }.maxOfOrNull { col[it.id]!! } ?: autres
        elements.filter { genre[it.id] == Genre.TELEPHONE }.forEach { col[it.id] = maxOf(col[it.id]!!, pc + 1) }
        // Empty columns removed.
        val utilisees = col.values.toSortedSet().toList()
        elements.forEach { col[it.id] = utilisees.indexOf(col[it.id]!!) }
        val nCol = utilisees.size

        // ---- a cable crossing columns keeps its own place in each of them (a waypoint, "relais"):
        //      the hardware of those columns is placed around it, never on it.
        val rang = HashMap<String, Int>()
        elements.forEach { rang[it.id] = rangVoie(it, liens) }
        val relais = HashMap<String, List<String>>()   // link -> its waypoints, left to right
        val colRelais = HashMap<String, Int>()
        for (l in liens) {
            val ca = col[l.de.element]!!; val cb = col[l.vers.element]!!
            if (kotlin.math.abs(ca - cb) < 2) continue
            val (g, d) = if (ca < cb) ca to cb else cb to ca
            val pa = Montage.port(c, l.de); val pb = Montage.port(c, l.vers)
            val rf = pa?.signal == Signal.RF && pb?.signal == Signal.RF
            val r = when {
                !rf -> 3
                pa!!.voie == Voie.TX || pb!!.voie == Voie.TX -> 2
                pa.voie == Voie.RX || pb.voie == Voie.RX -> 0
                else -> 1
            }
            relais[l.id] = (g + 1 until d).map { k -> "~${l.id}~$k".also { colRelais[it] = k; rang[it] = r } }
        }
        // Who each one is cabled to (the waypoints in the chain of their cable).
        val voisins = HashMap<String, MutableList<String>>()
        fun voisin(a: String, b: String) { voisins.getOrPut(a) { ArrayList() }.add(b); voisins.getOrPut(b) { ArrayList() }.add(a) }
        for (l in liens) {
            val r = relais[l.id]
            if (r == null) { voisin(l.de.element, l.vers.element); continue }
            val gauche = if (col[l.de.element]!! < col[l.vers.element]!!) l.de.element else l.vers.element
            val droite = if (gauche == l.de.element) l.vers.element else l.de.element
            (listOf(gauche) + r + droite).zipWithNext().forEach { (a, b) -> voisin(a, b) }
        }
        val estRelais = { id: String -> id.startsWith("~") }
        val hRelais = 10
        fun haut(id: String) = if (estRelais(id)) hRelais else taille[id]!!.second
        fun centre(id: String, yy: Int) = yy + if (estRelais(id)) hRelais / 2 else (taille[id]!!.second - ETIQUETTE) / 2
        val ordre = HashMap<String, Int>()
        elements.forEachIndexed { i, e -> ordre[e.id] = i }
        colRelais.keys.forEachIndexed { i, id -> ordre[id] = elements.size + i }
        val colonnes = (0 until nCol).map { k -> elements.filter { col[it.id] == k }.map { it.id } + colRelais.filterValues { it == k }.keys.sortedBy { ordre[it] } }

        // ---- in each column: reception above, transmission below, each near what it is cabled to
        val y = HashMap<String, Int>()
        fun place(k: Int, places: Set<String>) {
            val souhait = colonnes[k].associateWith { id ->
                val v = voisins[id].orEmpty().filter { it in places && it in y }
                if (v.isEmpty()) null else v.map { centre(it, y[it]!!) }.average()
            }
            val tri = colonnes[k].sortedWith(compareBy<String>({ rang[it] }, { souhait[it] ?: Double.MAX_VALUE }, { ordre[it] }))
            var curseur = MARGE
            for (id in tri) {
                val h = haut(id)
                val voulu = souhait[id]?.let { (it - (centre(id, 0))).toInt() } ?: curseur
                y[id] = maxOf(curseur, voulu)
                curseur = y[id]!! + h + (if (estRelais(id)) ECART_V / 2 else ECART_V)
            }
        }
        // Left to right, then back (the antennas lined up on what they feed), then once more.
        for (k in 0 until nCol) place(k, colonnes.take(k).flatten().toSet())
        for (k in nCol - 2 downTo 0) place(k, colonnes.drop(k + 1).flatten().toSet())
        for (k in 1 until nCol) place(k, colonnes.take(k).flatten().toSet())
        val haut0 = y.values.min()
        y.keys.toList().forEach { y[it] = y[it]!! - haut0 + MARGE }

        // ---- x: each column as wide as its widest drawing
        val largeurCol = (0 until nCol).map { k -> colonnes[k].filterNot(estRelais).maxOfOrNull { taille[it]!!.first } ?: 0 }
        val xCol = IntArray(nCol)
        for (k in 1 until nCol) xCol[k] = xCol[k - 1] + largeurCol[k - 1] + ECART_H
        for (k in 0 until nCol) xCol[k] += MARGE
        val boites = elements.map { e ->
            val k = col[e.id]!!; val (l, h) = taille[e.id]!!
            Boite(e.id, xCol[k] + (largeurCol[k] - l) / 2, y[e.id]!!, l, h, k)
        }
        val parId = boites.associateBy { it.id }

        // ---- ports: on the side facing the other end, in the order of the heights they go to
        data class Ancre(val lien: String, val element: String, val droite: Boolean, val autreY: Int)
        val ancres = ArrayList<Ancre>()
        for (l in liens) {
            val a = parId[l.de.element]!!; val b = parId[l.vers.element]!!
            val r = relais[l.id]
            // The height the cable heads for from each end: its first waypoint, else the other piece.
            val versA = if (r != null) centre(if (a.colonne < b.colonne) r.first() else r.last(), y[if (a.colonne < b.colonne) r.first() else r.last()]!!) else b.milieuY
            val versB = if (r != null) centre(if (a.colonne < b.colonne) r.last() else r.first(), y[if (a.colonne < b.colonne) r.last() else r.first()]!!) else a.milieuY
            ancres += Ancre(l.id, a.id, b.colonne >= a.colonne, versA)
            ancres += Ancre(l.id, b.id, a.colonne > b.colonne || a.colonne == b.colonne, versB)
        }
        val position = HashMap<Pair<String, String>, Pair<Int, Int>>()
        for ((cle, groupe) in ancres.groupBy { it.element to it.droite }) {
            val b = parId[cle.first]!!
            val tri = groupe.sortedWith(compareBy({ it.autreY }, { it.lien }))
            val hd = b.h - ETIQUETTE
            tri.forEachIndexed { i, a ->
                val py = b.y + hd * (i + 1) / (tri.size + 1)
                position[a.lien to a.element] = (if (cle.second) b.x + b.l else b.x) to py
            }
        }

        // ---- routes: level runs at the ports' and the waypoints' heights, the turns in the gaps between columns
        fun couloir(k: Int) = xCol[k] + largeurCol[k] + ECART_H / 2
        class Route(val lien: Lien, val niveaux: List<Int>, val canaux: List<Int>, val pa: Pair<Int, Int>, val pb: Pair<Int, Int>)
        val routes = liens.map { l ->
            var pa = position[l.id to l.de.element]!!; var pb = position[l.id to l.vers.element]!!
            var ca = col[l.de.element]!!; var cb = col[l.vers.element]!!
            if (ca > cb) { val t = pa; pa = pb; pb = t; val u = ca; ca = cb; cb = u }
            val r = relais[l.id].orEmpty()
            when {
                ca == cb -> Route(l, listOf(pa.second, pb.second), listOf(ca), pa, pb)
                else -> Route(l, listOf(pa.second) + r.map { centre(it, y[it]!!) } + pb.second, (ca until cb).toList(), pa, pb)
            }
        }
        // The vertical runs sharing a gap spread apart, the topmost on the left.
        val parCanal = HashMap<Int, MutableList<Pair<String, Int>>>()
        for (r in routes) r.canaux.forEachIndexed { i, k ->
            val y0 = r.niveaux[i]; val y1 = r.niveaux[minOf(i + 1, r.niveaux.size - 1)]
            if (y0 != y1) parCanal.getOrPut(k) { ArrayList() }.add(r.lien.id to minOf(y0, y1))
        }
        val decalage = HashMap<Pair<String, Int>, Int>()
        for ((k, l) in parCanal) {
            val tri = l.sortedWith(compareBy({ it.second }, { it.first }))
            val ecart = minOf(PAS_VOIE, (ECART_H - 30) / maxOf(1, tri.size))
            tri.forEachIndexed { i, (id, _) -> decalage[id to k] = ((i - (tri.size - 1) / 2.0) * ecart).toInt() }
        }
        val etiquettes = ArrayList<Pair<Int, Int>>()
        val traces = routes.map { r ->
            val pts = ArrayList<Pair<Int, Int>>()
            pts += r.pa
            r.canaux.forEachIndexed { i, k ->
                val x = couloir(k) + (decalage[r.lien.id to k] ?: 0)
                pts += x to r.niveaux[i]
                pts += x to r.niveaux[minOf(i + 1, r.niveaux.size - 1)]
            }
            pts += r.pb
            // Repeated points and straight-through corners dropped.
            val net = pts.fold(ArrayList<Pair<Int, Int>>()) { acc, p -> if (acc.lastOrNull() != p) acc.add(p); acc }
            val propre = ArrayList<Pair<Int, Int>>()
            for (p in net) {
                if (propre.size >= 2) {
                    val a = propre[propre.size - 2]; val b = propre.last()
                    if ((a.first == b.first && b.first == p.first) || (a.second == b.second && b.second == p.second)) propre.removeAt(propre.size - 1)
                }
                propre += p
            }
            val et = etiquette(propre, boites, etiquettes)
            etiquettes += et
            // The ends given back as the link has them (from, to).
            Trace(r.lien.id, propre, et, position[r.lien.id to r.lien.de.element]!!, position[r.lien.id to r.lien.vers.element]!!)
        }
        val basDessin = boites.maxOf { it.bas }
        val largeur = (boites.maxOf { it.x + it.l }).coerceAtLeast(traces.flatMap { it.points }.maxOfOrNull { it.first } ?: 0) + MARGE
        val hauteur = maxOf(basDessin, traces.flatMap { it.points }.maxOfOrNull { it.second } ?: 0) + MARGE
        return Plan(largeur, hauteur, boites, traces)
    }

    /** A label's room: about this wide, drawn just above its point. */
    private const val ETIQ_L = 96
    private const val ETIQ_H = 16

    /**
     * Where a cable's label goes: on a level run, in the longest stretch clear
     * of the drawings, away from the labels already placed; the middle of the
     * longest run when nothing is clear.
     */
    private fun etiquette(pts: List<Pair<Int, Int>>, boites: List<Boite>, deja: List<Pair<Int, Int>>): Pair<Int, Int> {
        val candidats = ArrayList<Triple<Int, Int, Int>>()   // length, x, y
        for ((u, v) in pts.zipWithNext()) {
            if (u.second != v.second) continue
            val y = u.second
            val a = minOf(u.first, v.first); val b = maxOf(u.first, v.first)
            val interdits = boites.filter { y > it.y - ETIQ_H - 6 && y < it.bas + 4 }.map { (it.x - 6) to (it.x + it.l + 6) }.sortedBy { it.first }
            var cur = a
            fun libre(x0: Int, x1: Int) { if (x1 - x0 >= 12) candidats += Triple(x1 - x0, (x0 + x1) / 2, y) }
            for ((i0, i1) in interdits) {
                if (i1 <= cur) continue
                if (i0 >= b) break
                if (i0 > cur) libre(cur, i0)
                cur = maxOf(cur, i1)
            }
            if (cur < b) libre(cur, b)
        }
        fun gene(x: Int, y: Int) = deja.any { kotlin.math.abs(it.first - x) < ETIQ_L && kotlin.math.abs(it.second - y) < ETIQ_H }
        candidats.sortByDescending { it.first }
        candidats.firstOrNull { !gene(it.second, it.third) }?.let { return it.second to it.third }
        candidats.firstOrNull()?.let { return it.second to it.third }
        val s = pts.zipWithNext().filter { it.first.second == it.second.second }.maxByOrNull { kotlin.math.abs(it.first.first - it.second.first) }
        return s?.let { (it.first.first + it.second.first) / 2 to it.first.second } ?: pts[pts.size / 2]
    }

    fun json(p: Plan): JSONObject = JSONObject().put("l", p.largeur).put("h", p.hauteur)
        .put("boites", JSONArray(p.boites.map { JSONObject().put("id", it.id).put("x", it.x).put("y", it.y).put("l", it.l).put("h", it.h).put("col", it.colonne) }))
        .put("traces", JSONArray(p.traces.map { t ->
            JSONObject().put("id", t.id).put("pts", JSONArray(t.points.map { JSONArray(listOf(it.first, it.second)) }))
                .put("et", JSONArray(listOf(t.etiquette.first, t.etiquette.second)))
                .put("de", JSONArray(listOf(t.de.first, t.de.second))).put("vers", JSONArray(listOf(t.vers.first, t.vers.second)))
        }))
}
