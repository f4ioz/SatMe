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
import fr.f4ioz.satcombo.station.Montage.Port
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
 *
 * Once the owner has moved a piece ([Element.place]), the drawing is his:
 * each piece where he put it (those not yet placed in a row below), the
 * cables from port to port with square corners.
 */
object DispositionMontage {

    /** Room for the name under each drawing. */
    const val ETIQUETTE = 18
    const val MARGE = 24
    private const val ECART_H = 140
    private const val ECART_V = 34
    private const val PAS_VOIE = 10

    /** Where a port is on the drawing, and on which side of its piece: 'g' left, 'd' right, 'b' below. */
    data class PortPlace(val id: String, val x: Int, val y: Int, val cote: Char)

    data class Boite(val id: String, val x: Int, val y: Int, val l: Int, val h: Int, val colonne: Int, val ports: List<PortPlace> = emptyList()) {
        val bas get() = y + h
        val milieuY get() = y + (h - ETIQUETTE) / 2
    }

    /** A cable drawn: its corners, where its label goes, its two ends. */
    data class Trace(val id: String, val points: List<Pair<Int, Int>>, val etiquette: Pair<Int, Int>?,
                     val de: Pair<Int, Int>, val vers: Pair<Int, Int>, val largeurEt: Int = 0)

    data class Plan(val largeur: Int, val hauteur: Int, val boites: List<Boite>, val traces: List<Trace>, val libre: Boolean = false) {
        fun boite(id: String) = boites.firstOrNull { it.id == id }
    }

    /** The genre's place, the phone after the computer. */
    private fun base(e: Element) = Montage.modele(e)?.genre?.colonne ?: 3

    /** An element's paths: 0 reception, 1 both, 2 transmission, 3 none (data, rotor). */
    private fun rangVoie(e: Element, liens: List<Lien>): Int {
        if (Montage.modele(e) == null) return 3
        val relies = liens.flatMap { listOf(it.de, it.vers) }.filter { it.element == e.id }.map { it.port }.toSet()
        val rf = Montage.ports(e).filter { it.signal == Signal.RF && (relies.isEmpty() || it.id in relies) }
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
     * How a layout is tried: [aux] the pull of the supply, reference and GPS
     * cables on where a piece stands (the radio and data ones pull 1),
     * [passes] the sweeps left and right that place the columns.
     */
    private data class Essai(val aux: Double, val passes: Int)
    private val ESSAIS = listOf(Essai(1.0, 3), Essai(0.25, 3), Essai(0.25, 6), Essai(0.0, 4), Essai(1.0, 6))

    /** A supply, a reference, a GPS antenna's cable: drawn, but it should not decide where the radios stand. */
    private fun auxiliaire(l: Lien) = l.cable.famille in setOf(Montage.Famille.ALIM, Montage.Famille.REFERENCE, Montage.Famille.GPS)

    /**
     * The drawing of [chaine]; [garder] the elements to draw (the simplified
     * view), all of them when null. Several placements are tried; the one
     * with the fewest crossings (cables over cables, over drawings, labels
     * over labels) is kept.
     */
    fun dispose(chaine: Chaine, garder: Set<String>? = null): Plan {
        val elements = chaine.elements.filter { garder == null || it.id in garder }
        val ids = elements.map { it.id }.toSet()
        val liens = chaine.liens.filter { it.de.element in ids && it.vers.element in ids }
        val c = Chaine(elements, liens)
        if (elements.isEmpty()) return Plan(2 * MARGE, 2 * MARGE, emptyList(), emptyList())
        if (elements.any { it.place }) return libre(c)
        var meilleur: Plan? = null; var note = Double.MAX_VALUE
        for (e in ESSAIS) {
            val p = automatique(c, e)
            val n = note(p, c)
            if (n < note - 1e-9) { meilleur = p; note = n }
        }
        return meilleur!!
    }

    /** What a drawing costs to read: crossings, cables over drawings, labels over each other, and a little for its size. */
    private fun note(p: Plan, c: Chaine): Double {
        val m = mesure(p, c)
        return m.croisements * 10.0 + m.traversees * 40.0 + m.etiquettes * 15.0 + (p.largeur + p.hauteur) * 0.002
    }

    /** What makes a drawing hard to read, counted: [croisements] two cables crossing, [traversees] a cable over a drawing, [etiquettes] labels overlapping. */
    data class Mesure(val croisements: Int, val traversees: Int, val etiquettes: Int)

    fun mesure(p: Plan, c: Chaine): Mesure {
        val segs = p.traces.map { t -> t.id to t.points.zipWithNext() }
        var croise = 0
        for (i in segs.indices) for (j in i + 1 until segs.size) {
            val la = c.liens.firstOrNull { it.id == segs[i].first }; val lb = c.liens.firstOrNull { it.id == segs[j].first }
            // Two cables from the same port (a supply, a computer's USB) share their start: not a crossing.
            val communs = if (la != null && lb != null) setOf(la.de, la.vers) intersect setOf(lb.de, lb.vers) else emptySet()
            for ((u, v) in segs[i].second) for ((a, b) in segs[j].second) if (communs.isEmpty() && coupe(u, v, a, b)) croise++
        }
        var traverse = 0
        for (t in p.traces) {
            val l = c.liens.firstOrNull { it.id == t.id } ?: continue
            for ((u, v) in t.points.zipWithNext()) for (b in p.boites) {
                if (b.id == l.de.element || b.id == l.vers.element) continue
                val x0 = minOf(u.first, v.first); val x1 = maxOf(u.first, v.first); val y0 = minOf(u.second, v.second); val y1 = maxOf(u.second, v.second)
                if (x0 < b.x + b.l && x1 > b.x && y0 < b.bas && y1 > b.y) traverse++
            }
        }
        val et = p.traces.mapNotNull { t -> t.etiquette?.let { Triple(it.first, it.second, t.largeurEt) } }
        var chevauche = 0
        for (i in et.indices) for (j in i + 1 until et.size) {
            val a = et[i]; val b = et[j]
            if (kotlin.math.abs(a.first - b.first) * 2 < a.third + b.third && kotlin.math.abs(a.second - b.second) < ETIQ_H) chevauche++
        }
        return Mesure(croise, traverse, chevauche)
    }

    /** Whether two orthogonal runs cross (a perpendicular crossing strictly inside both). */
    private fun coupe(u: Pair<Int, Int>, v: Pair<Int, Int>, a: Pair<Int, Int>, b: Pair<Int, Int>): Boolean {
        val h1 = u.second == v.second; val h2 = a.second == b.second
        if (h1 == h2) return false
        val (hu, hv, vu, vv) = if (h1) listOf(u, v, a, b) else listOf(a, b, u, v)
        val y = hu.second; val x = vu.first
        val x0 = minOf(hu.first, hv.first); val x1 = maxOf(hu.first, hv.first)
        val y0 = minOf(vu.second, vv.second); val y1 = maxOf(vu.second, vv.second)
        return x > x0 && x < x1 && y > y0 && y < y1
    }

    private fun automatique(c: Chaine, essai: Essai): Plan {
        val elements = c.elements; val liens = c.liens
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
        // The computer and the phone (and the programs beside it) always last.
        val genre = elements.associate { it.id to Montage.modele(it)!!.genre }
        val apres = setOf(Genre.TELEPHONE, Genre.BOITE)
        val autres = elements.filter { genre[it.id] != Genre.ORDINATEUR && genre[it.id] !in apres }.maxOfOrNull { col[it.id]!! } ?: -1
        elements.filter { genre[it.id] == Genre.ORDINATEUR }.forEach { col[it.id] = maxOf(col[it.id]!!, autres + 1) }
        val pc = elements.filter { genre[it.id] == Genre.ORDINATEUR }.maxOfOrNull { col[it.id]!! } ?: autres
        elements.filter { genre[it.id] in apres }.forEach { col[it.id] = maxOf(col[it.id]!!, pc + 1) }
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
        val voisins = HashMap<String, MutableList<Pair<String, Double>>>()
        fun voisin(a: String, b: String, k: Double) { voisins.getOrPut(a) { ArrayList() }.add(b to k); voisins.getOrPut(b) { ArrayList() }.add(a to k) }
        for (l in liens) {
            val k = if (auxiliaire(l)) essai.aux else 1.0
            val r = relais[l.id]
            if (r == null) { voisin(l.de.element, l.vers.element, k); continue }
            val gauche = if (col[l.de.element]!! < col[l.vers.element]!!) l.de.element else l.vers.element
            val droite = if (gauche == l.de.element) l.vers.element else l.de.element
            (listOf(gauche) + r + droite).zipWithNext().forEach { (a, b) -> voisin(a, b, k) }
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
                val v = voisins[id].orEmpty().filter { it.first in places && it.first in y }
                val poids = v.sumOf { it.second }
                // Only pulled by supplies and references (weight 0): the first of them all the same.
                if (v.isEmpty()) null else if (poids < 1e-9) centre(v.first().first, y[v.first().first]!!).toDouble()
                    else v.sumOf { centre(it.first, y[it.first]!!) * it.second } / poids
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
        // Left to right, then back (the antennas lined up on what they feed), then once more (and more in some tries).
        for (k in 0 until nCol) place(k, colonnes.take(k).flatten().toSet())
        for (passe in 1 until essai.passes) {
            if (passe % 2 == 1) for (k in nCol - 2 downTo 0) place(k, colonnes.drop(k + 1).flatten().toSet())
            else for (k in 1 until nCol) place(k, colonnes.take(k).flatten().toSet())
        }
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
        val brutes = routes.map { r ->
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
            // The ends given back as the link has them (from, to).
            Trace(r.lien.id, propre, null, position[r.lien.id to r.lien.de.element]!!, position[r.lien.id to r.lien.vers.element]!!)
        }
        val traces = etiquettes(c, brutes, boites)
        val basDessin = boites.maxOf { it.bas }
        val largeur = (boites.maxOf { it.x + it.l }).coerceAtLeast(traces.flatMap { it.points }.maxOfOrNull { it.first } ?: 0) + MARGE
        val hauteur = maxOf(basDessin, traces.flatMap { it.points }.maxOfOrNull { it.second } ?: 0) + MARGE
        // Every port's place: a cabled one where its cable ends, the others on their side.
        val bouts = HashMap<Pair<String, String>, Pair<Int, Int>>()
        for (l in liens) {
            bouts.putIfAbsent(l.de.element to l.de.port, position[l.id to l.de.element]!!)
            bouts.putIfAbsent(l.vers.element to l.vers.port, position[l.id to l.vers.element]!!)
        }
        val avecPorts = boites.map { b ->
            val e = c.element(b.id)!!
            b.copy(ports = placesPorts(e, b).map { p -> bouts[b.id to p.id]?.let { (x, y) -> p.copy(x = x, y = y, cote = if (x <= b.x) 'g' else if (x >= b.x + b.l) 'd' else p.cote) } ?: p })
        }
        return Plan(largeur, hauteur, avecPorts, traces)
    }

    /** The side of its piece a port stands on: radio in on the left, out on the right; references and supplies below; data towards the computer. */
    fun cote(g: Genre?, p: Port): Char = when {
        p.signal == Signal.REF || p.signal == Signal.DC || p.signal == Signal.GPS ->
            if (p.sens == Sens.SORTIE) 'd' else if (p.signal == Signal.GPS) 'g' else 'b'
        p.signal == Signal.MECA -> 'b'
        p.signal == Signal.RF -> when (p.sens) { Sens.ENTREE -> 'g'; Sens.SORTIE -> 'd'; Sens.BIDIR -> if (g == Genre.ANTENNE) 'd' else 'g' }
        g == Genre.ORDINATEUR -> if (p.connecteur == "Internet") 'd' else 'g'
        g == Genre.TELEPHONE || g == Genre.BOITE -> 'g'
        p.signal == Signal.COMMANDE && p.sens == Sens.ENTREE -> 'b'
        else -> 'd'
    }

    /** [e]'s ports on the edges of its box [b], each side's spread evenly. */
    fun placesPorts(e: Element, b: Boite): List<PortPlace> {
        val g = Montage.modele(e)?.genre
        val hd = b.h - ETIQUETTE
        val ps = Montage.ports(e)
        val parCote = ps.groupBy { cote(g, it) }
        return ps.map { p ->
            val k = cote(g, p); val l = parCote[k]!!; val i = l.indexOf(p); val n = l.size
            when (k) {
                'g' -> PortPlace(p.id, b.x, b.y + hd * (i + 1) / (n + 1), 'g')
                'd' -> PortPlace(p.id, b.x + b.l, b.y + hd * (i + 1) / (n + 1), 'd')
                else -> PortPlace(p.id, b.x + b.l * (i + 1) / (n + 1), b.y + hd, 'b')
            }
        }
    }

    /** How far a cable leaves its port straight before it turns. */
    private const val SORTIE_PORT = 16

    /**
     * The owner's drawing: the pieces he placed where he put them, the others
     * in a row below; each cable from port to port, leaving straight then
     * turning square.
     */
    private fun libre(c: Chaine): Plan {
        val taille = c.elements.associate { e -> e.id to (Montage.modele(e)!!.let { it.l to it.h + ETIQUETTE }) }
        val placees = c.elements.filter { it.place }
        val droite = maxOf(900, placees.maxOf { it.x!! + taille[it.id]!!.first })
        var y0 = placees.maxOf { it.y!! + taille[it.id]!!.second } + ECART_V
        var x = MARGE; var hRang = 0
        val boites = c.elements.map { e ->
            val (l, h) = taille[e.id]!!
            if (e.place) Boite(e.id, e.x!!, e.y!!, l, h, base(e))
            else {
                if (x > MARGE && x + l > droite) { x = MARGE; y0 += hRang + ECART_V; hRang = 0 }
                Boite(e.id, x, y0, l, h, base(e)).also { x += l + 40; hRang = maxOf(hRang, h) }
            }
        }.map { b -> b.copy(ports = placesPorts(c.element(b.id)!!, b)) }
        val parId = boites.associateBy { it.id }
        val traces = etiquettes(c, c.liens.mapNotNull { l ->
            val a = parId[l.de.element]?.ports?.firstOrNull { it.id == l.de.port } ?: return@mapNotNull null
            val b = parId[l.vers.element]?.ports?.firstOrNull { it.id == l.vers.port } ?: return@mapNotNull null
            Trace(l.id, equerre(a, b), null, a.x to a.y, b.x to b.y)
        }, boites)
        val tous = boites.flatMap { listOf(it.x + it.l to it.bas) } + traces.flatMap { it.points }
        return Plan(tous.maxOf { it.first } + MARGE, tous.maxOf { it.second } + MARGE, boites, traces, libre = true)
    }

    /** A cable between two ports: straight out of each, one square turn between (two when they face each other). */
    fun equerre(a: PortPlace, b: PortPlace): List<Pair<Int, Int>> {
        fun sort(p: PortPlace) = when (p.cote) { 'g' -> (p.x - SORTIE_PORT) to p.y; 'd' -> (p.x + SORTIE_PORT) to p.y; else -> p.x to (p.y + SORTIE_PORT) }
        val a1 = sort(a); val b1 = sort(b)
        val face = (a.cote == 'd' && b.cote == 'g' && a1.first <= b1.first) || (a.cote == 'g' && b.cote == 'd' && a1.first >= b1.first)
        val pts = if (face) {
            val mx = (a.x + b.x) / 2
            listOf(a.x to a.y, mx to a.y, mx to b.y, b.x to b.y)
        } else if (a.cote == 'b' && b.cote == 'b') {
            val yb = maxOf(a1.second, b1.second)
            listOf(a.x to a.y, a.x to yb, b.x to yb, b.x to b.y)
        } else if (b.cote == 'b') {
            listOf(a.x to a.y, a1, b.x to a1.second, b.x to b.y)
        } else {
            listOf(a.x to a.y, a1, a1.first to b1.second, b1, b.x to b.y)
        }
        // Repeated points and straight-through corners dropped.
        val net = pts.fold(ArrayList<Pair<Int, Int>>()) { acc, p -> if (acc.lastOrNull() != p) acc.add(p); acc }
        val propre = ArrayList<Pair<Int, Int>>()
        for (p in net) {
            if (propre.size >= 2) {
                val u = propre[propre.size - 2]; val v = propre.last()
                if ((u.first == v.first && v.first == p.first) || (u.second == v.second && v.second == p.second)) propre.removeAt(propre.size - 1)
            }
            propre += p
        }
        return propre
    }

    /** A label's height, drawn just above its point. */
    private const val ETIQ_H = 16

    /**
     * About how wide the page draws [l]'s label (its band and connector, a
     * reference's frequency, the cable's kind, the owner's note before), 0
     * when it has none; [vif] true when the live frequency may replace the band.
     */
    fun largeurEtiquette(c: Chaine, l: Lien): Int {
        val pa = Montage.port(c, l.de); val pb = Montage.port(c, l.vers)
        val f = l.cable.famille
        val texte = when (f) {
            Montage.Famille.COAX -> 15   // « 739 MHz FI · SMA », « 10489,750 MHz · F » once lit
            Montage.Famille.REFERENCE -> 6 + 2
            Montage.Famille.DIRECT -> 0
            else -> CABLES_COURTS[l.cable] ?: 0
        }
        val n = texte + (if (l.note.isNotBlank()) l.note.length + 3 else 0)
        if (n == 0) return 0
        val vif = f == Montage.Famille.COAX && listOfNotNull(pa?.mhz, pb?.mhz).isNotEmpty()
        return (maxOf(n, if (vif) 18 else 0) * 5.6 + 10).toInt()
    }

    /** The cables' kinds as the page writes them (« USB », « DC »…). */
    private val CABLES_COURTS = mapOf(Montage.Cable.USB to 3, Montage.Cable.ETHERNET to 6, Montage.Cable.WIFI to 5, Montage.Cable.AUDIO to 5,
        Montage.Cable.CAT to 3, Montage.Cable.PTT to 3, Montage.Cable.ROTOR to 5, Montage.Cable.ALIM to 2, Montage.Cable.GPS to 3, Montage.Cable.INTERNET to 8)

    /** A label the drawing must show (a band, a reference's frequency, the owner's note); the others (« USB », « DC ») only where there is room. */
    private fun necessaire(l: Lien) = l.note.isNotBlank() || l.cable.famille == Montage.Famille.COAX || l.cable.famille == Montage.Famille.REFERENCE

    /**
     * Every cable's label placed: on a level run, in a stretch clear of the
     * drawings and of the ports, wide enough for it, away from the labels
     * already placed and from the other cables' vertical runs; the needed
     * ones first. A needed label with no clear room goes in the middle of its
     * longest run; another one is left out (the cable's colour says it).
     */
    private fun etiquettes(c: Chaine, traces: List<Trace>, boites: List<Boite>): List<Trace> {
        val liens = c.liens.associateBy { it.id }
        val places = ArrayList<IntArray>()   // x0, y0, x1, y1 of the labels placed
        val verticales = traces.associate { t -> t.id to t.points.zipWithNext().filter { it.first.first == it.second.first } }
        val horizontales = traces.associate { t -> t.id to t.points.zipWithNext().filter { it.first.second == it.second.second } }
        val ports = boites.flatMap { b -> b.ports.map { it.x to it.y } } + traces.flatMap { listOf(it.de, it.vers) }
        val ordre = traces.sortedWith(compareBy<Trace>({ liens[it.id]?.let { l -> if (necessaire(l)) 0 else 1 } ?: 2 }, { -(liens[it.id]?.let { l -> largeurEtiquette(c, l) } ?: 0) }))
        val resultat = HashMap<String, Trace>()
        for (t in ordre) {
            val l = liens[t.id]
            val w = l?.let { largeurEtiquette(c, it) } ?: 0
            if (l == null || w == 0) { resultat[t.id] = t.copy(etiquette = null, largeurEt = 0); continue }
            data class Choix(val x: Int, val y: Int, val cout: Double)
            val choix = ArrayList<Choix>()
            val longueurTotale = t.points.zipWithNext().sumOf { kotlin.math.abs(it.first.first - it.second.first) + kotlin.math.abs(it.first.second - it.second.second) }.coerceAtLeast(1)
            var parcouru = 0
            for ((u, v) in t.points.zipWithNext()) {
                val longueur = kotlin.math.abs(u.first - v.first) + kotlin.math.abs(u.second - v.second)
                if (u.second == v.second) {
                    val y = u.second
                    val sa = minOf(u.first, v.first); val sb = maxOf(u.first, v.first)
                    // The label may run past this run over the cable's own turns, within the cable's width.
                    val a = t.points.minOf { it.first }; val b = t.points.maxOf { it.first }
                    // What the label may not cover: the drawings (and their names) at that height, the ports just below it.
                    val interdits = (boites.filter { y > it.y - ETIQ_H - 6 && y < it.bas + 4 }.map { (it.x - 6) to (it.x + it.l + 6) } +
                        ports.filter { it.second in (y - ETIQ_H - 4)..(y + 6) }.map { (it.first - 5) to (it.first + 5) }).sortedBy { it.first }
                    val libres = ArrayList<Pair<Int, Int>>()
                    var cur = a
                    for ((i0, i1) in interdits) {
                        if (i1 <= cur) continue
                        if (i0 >= b) break
                        if (i0 > cur) libres += cur to i0
                        cur = maxOf(cur, i1)
                    }
                    if (cur < b) libres += cur to b
                    for ((x0, x1) in libres) {
                        // Room enough, and over this run.
                        if (x1 - x0 < w + 6 || minOf(x1, sb) - maxOf(x0, sa) < 12) continue
                        val lo = x0 + w / 2 + 3; val hi = x1 - w / 2 - 3
                        // A few places: over the run's middle, then towards each end of the room.
                        val xs = listOf(((maxOf(x0, sa) + minOf(x1, sb)) / 2).coerceIn(lo, hi), lo, hi).distinct()
                        for (x in xs) {
                            val r = intArrayOf(x - w / 2 - 2, y - ETIQ_H - 2, x + w / 2 + 2, y - 2)
                            if (places.any { it[0] < r[2] && r[0] < it[2] && it[1] < r[3] && r[1] < it[3] }) continue
                            // Other cables under the label: worth avoiding, not forbidden.
                            val sous = traces.filter { it.id != t.id }.sumOf { o ->
                                verticales[o.id]!!.count { (p, q) -> p.first in r[0]..r[2] && minOf(p.second, q.second) < r[3] && maxOf(p.second, q.second) > r[1] } +
                                    horizontales[o.id]!!.count { (p, q) -> p.second in r[1]..r[3] && minOf(p.first, q.first) < r[2] && maxOf(p.first, q.first) > r[0] }
                            }
                            // Near the cable's middle reads best.
                            val milieu = kotlin.math.abs((parcouru + kotlin.math.abs(x - u.first)).toDouble() / longueurTotale - .5)
                            choix += Choix(x, y, sous * 3.0 + milieu)
                        }
                    }
                }
                parcouru += longueur
            }
            val pris = choix.minByOrNull { it.cout }
            val et = when {
                pris != null -> pris.x to pris.y
                necessaire(l) -> horizontales[t.id]!!.maxByOrNull { kotlin.math.abs(it.first.first - it.second.first) }
                    ?.let { (it.first.first + it.second.first) / 2 to it.first.second } ?: t.points[t.points.size / 2]
                else -> null
            }
            if (et != null) places += intArrayOf(et.first - w / 2 - 2, et.second - ETIQ_H - 2, et.first + w / 2 + 2, et.second - 2)
            resultat[t.id] = t.copy(etiquette = et, largeurEt = w)
        }
        return traces.map { resultat[it.id]!! }
    }

    fun json(p: Plan): JSONObject = JSONObject().put("l", p.largeur).put("h", p.hauteur).put("libre", p.libre)
        .put("boites", JSONArray(p.boites.map { b ->
            JSONObject().put("id", b.id).put("x", b.x).put("y", b.y).put("l", b.l).put("h", b.h).put("col", b.colonne)
                .put("ports", JSONArray(b.ports.map { JSONArray(listOf(it.id, it.x, it.y, it.cote.toString())) }))
        }))
        .put("traces", JSONArray(p.traces.map { t ->
            JSONObject().put("id", t.id).put("pts", JSONArray(t.points.map { JSONArray(listOf(it.first, it.second)) }))
                .put("et", t.etiquette?.let { JSONArray(listOf(it.first, it.second)) } ?: JSONObject.NULL).put("etL", t.largeurEt)
                .put("de", JSONArray(listOf(t.de.first, t.de.second))).put("vers", JSONArray(listOf(t.vers.first, t.vers.second)))
        }))
}
