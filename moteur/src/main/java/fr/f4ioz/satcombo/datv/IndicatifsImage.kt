/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv

import fr.f4ioz.satcombo.cw.AnalyseCw
import fr.f4ioz.satcombo.domain.Dxcc
import fr.f4ioz.satcombo.domain.Qo100

/**
 * Callsigns written in a received DATV picture, as text recognition reads
 * them: the words that make a plausible callsign (a country's prefix, a
 * letter before the area digit, not a grid square nor a frequency), kept
 * only when the same one comes back in consecutive reads.
 *
 * Text recognition confuses look-alike signs (4 read A, 0 read O…): a word
 * that is not a callsign is tried with them swapped. One that then matches
 * a callsign vouched for (the station's service name, its card in the
 * stream, the log, ours) is taken at once; any other repair waits for the
 * vote, and only the narrowest one is tried (one look-alike sign where the
 * area digit goes, the result of the usual shape and a known prefix).
 */
object IndicatifsImage {

    private val CARRE = Regex("^[A-R]{2}[0-9]{2}(?:[A-X]{2})?$")
    /** A callsign's usual shape: a 1–2 sign prefix, the area digit, 1–4 letters. */
    private val PROPRE = Regex("^(?:[A-Z]{1,2}|[A-Z][0-9]|[0-9][A-Z])[0-9][A-Z]{1,4}$")

    /** The look-alike signs, both ways. */
    private val SOSIES: Map<Char, Set<Char>> = HashMap<Char, MutableSet<Char>>().apply {
        for (p in listOf("A4", "O0", "I1", "L1", "S5", "B8", "Z2", "G6", "T7", "Q0", "D0", "Q9", "I/", "1/")) {
            getOrPut(p[0]) { HashSet() } += p[1]; getOrPut(p[1]) { HashSet() } += p[0]
        }
    }

    /** Small letters shaped as their capitals: read either way, they say nothing of the case. */
    private val NEUTRES = setOf('c', 'o', 's', 'u', 'v', 'w', 'x', 'z', 'p', 'k')

    /** Words often written on a picture, never repaired into a callsign. */
    private val MOTS = setOf("SATME", "DATV", "VIDEO", "AUDIO", "CAMERA", "STUDIO", "HELLO", "TEST", "TESTS", "BEACON",
        "BALISE", "SALUT", "BONJOUR", "MERCI", "STATION", "ANTENNE", "ANTENNA", "SIGNAL", "IMAGE", "IMAGES", "PICTURE",
        "DIRECT", "SATELLITE", "OSCAR", "PLUTO", "MINITIOUNER", "TIOUNER", "CQCQ")

    /** Who vouches for a callsign read, strongest first. */
    enum class Garant(val code: String) { SERVICE("service"), CARTE("carte"), CARNET("carnet"), MOI("moi") }

    /** The callsigns expected on this picture: the station's [service] name, its [carte]'s, the [carnet]'s, ours ([moi]). */
    class Connus(service: String = "", carte: String = "", carnet: Collection<String> = emptySet(), moi: String = "") {
        /** Base → its strongest voucher. */
        internal val bases: Map<String, Garant> = LinkedHashMap<String, Garant>().apply {
            fun ajoute(texte: String, g: Garant) {
                for (mot in texte.uppercase().split(Regex("[^A-Z0-9/]+"))) AnalyseCw.base(mot)?.let { putIfAbsent(it, g) }
            }
            ajoute(service, Garant.SERVICE); ajoute(carte, Garant.CARTE)
            carnet.forEach { ajoute(it, Garant.CARNET) }
            ajoute(moi, Garant.MOI)
        }
    }

    /** One callsign read: as shown, the [mot] read, its voucher (null: the vote decides), the signs swapped. */
    data class Lu(val indicatif: String, val mot: String, val garant: Garant?, val corrections: Int)

    /** The callsigns of a recognised [texte], each once, in order, repaired when needed. */
    fun lis(texte: String, connus: Connus = Connus()): List<Lu> {
        val vus = LinkedHashMap<String, Lu>()
        for (ligne in texte.lines()) {
            val mots = ligne.split(Regex("[^A-Za-z0-9/]+")).map { it.trim('/') }
            // A sentence (three words or more): its words are words, never repaired.
            val phrase = mots.count { it.length >= 3 && it.all(Char::isLetter) } >= 3
            for (m in mots) {
                if (m.length < 4 || m.length > 14) continue
                // Written in small letters (Hello, SatMe): a word, never repaired into a callsign.
                val minuscules = m.any { it.isLowerCase() && it !in NEUTRES }
                val lu = lisMot(m.uppercase(), connus, !minuscules && !phrase) ?: continue
                val avant = vus[lu.indicatif]
                if (avant == null || (avant.garant == null && lu.garant != null) || (avant.garant == lu.garant && lu.corrections < avant.corrections)) {
                    vus[lu.indicatif] = lu
                }
            }
        }
        // The same word read two ways in one read (G4XYZ, GA4XYZ): the better one only.
        val lus = vus.values.toList()
        fun rang(l: Lu) = (if (l.garant != null) 0 else 4) + (if (bon(l.indicatif)) 0 else 2) + minOf(l.corrections, 1)
        return lus.filter { l -> lus.none { o -> o !== l && rang(o) < rang(l) && voisins(o.indicatif, l.indicatif) } }
    }

    /** The plausible callsigns of a recognised [texte], each once, in order. */
    fun candidats(texte: String, connus: Connus = Connus()): List<String> = lis(texte, connus).map { it.indicatif }

    /** One word [m] (upper case) as a callsign: matched to one vouched for, as read, or repaired; null when none. */
    private fun lisMot(m: String, connus: Connus, reparable: Boolean): Lu? {
        vers(m, connus)?.let { return it }
        val brut = plausible(m)
        if (brut != null) {
            if (bon(brut)) return Lu(brut, m, null, 0)
            // Odd-shaped and one look-alike away from a grid square (JNI8FS): the square.
            if (m.indices.any { k -> SOSIES[m[k]].orEmpty().any { s -> CARRE.matches(m.substring(0, k) + s + m.substring(k + 1)) } }) return null
            // Odd-shaped (SPS5XYZ, F4I0Z, F4AIOZ): a sign read twice dropped, or a digit back to its letter, when that alone makes it usual.
            unique(dedouble(m) + repare(m, versLettre = true))?.let { return Lu(it, m, null, 1) }
            return Lu(brut, m, null, 0)
        }
        if (m in MOTS || !reparable) return null
        return unique(repare(m, versLettre = false) + dedouble(m) + prefixeChiffre(m))?.let { Lu(it, m, null, 1) }
    }

    /**
     * [m] as one of the callsigns vouched for: the same, or with one or two
     * look-alike signs swapped (a sign read twice counting as one); the
     * strongest voucher, then the fewest changes; null when none.
     */
    private fun vers(m: String, connus: Connus): Lu? {
        if (connus.bases.isEmpty()) return null
        val morceaux = m.split('/')
        var mieux: Lu? = null
        for ((i, p) in morceaux.withIndex()) {
            val essais = listOf(p to 0) + dedoubles(p).map { it to 1 }
            for ((base, g) in connus.bases) {
                for ((q, n0) in essais) {
                    if (base.length != q.length) continue
                    var n = n0
                    for (k in q.indices) {
                        if (q[k] == base[k]) continue
                        if (SOSIES[q[k]]?.contains(base[k]) != true) { n = 99; break }
                        n++
                    }
                    if (n > 2 || (n > 0 && 2 * n >= base.length)) continue
                    val c = plausible(morceaux.toMutableList().also { it[i] = base }.joinToString("/")) ?: continue
                    if (garant(c, connus) != g) continue
                    val avant = mieux
                    if (avant == null || g.ordinal < avant.garant!!.ordinal || (g == avant.garant && n < avant.corrections)) mieux = Lu(c, m, g, n)
                }
            }
        }
        return mieux
    }

    /** A repair of a word: the callsign and its rank (0: the closest look-alikes). */
    private data class Reparation(val indicatif: String, val rang: Int)

    /** The one best repair among [r] (the lowest rank), null when none or several. */
    private fun unique(r: List<Reparation>): String? {
        val meilleur = r.minOfOrNull { it.rang } ?: return null
        return r.filter { it.rang == meilleur }.map { it.indicatif }.distinct().singleOrNull()
    }

    /** [p] with one of two neighbour signs dropped, when they are the same or look-alikes (SPS5XYZ, F4AIOZ: a sign read twice). */
    private fun dedoubles(p: String): Set<String> {
        val r = LinkedHashSet<String>()
        for (k in 0 until p.length - 1) {
            if (p[k] != p[k + 1] && SOSIES[p[k]]?.contains(p[k + 1]) != true) continue
            r += p.removeRange(k, k + 1); r += p.removeRange(k + 1, k + 2)
        }
        return r
    }

    /** [m]'s base with a sign read twice dropped, the result usual. */
    private fun dedouble(m: String): List<Reparation> = autreBase(m) { p -> dedoubles(p).map { it to 0 } }

    /** A letter read for the digit opening a prefix (QA1AA → 9A1AA). */
    private fun prefixeChiffre(m: String): List<Reparation> = autreBase(m) { p ->
        if (p.none { it.isDigit() } || !p[0].isLetter()) emptyList()
        else SOSIES[p[0]].orEmpty().filter { it.isDigit() }.map { (it + p.substring(1)) to 0 }
    }

    /** [variantes] of [m]'s longest piece (its base), those that make a usual callsign. */
    private fun autreBase(m: String, variantes: (String) -> List<Pair<String, Int>>): List<Reparation> {
        val morceaux = m.split('/')
        val i = morceaux.indices.maxByOrNull { morceaux[it].length } ?: return emptyList()
        return variantes(morceaux[i]).mapNotNull { (q, rang) ->
            if (!bonneBase(q)) null
            else plausible(morceaux.toMutableList().also { it[i] = q }.joinToString("/"))?.let { Reparation(it, rang) }
        }
    }

    /**
     * The narrowest repair of [m]: one sign of its base swapped. A letter to
     * its look-alike digit where the area digit goes, in a letters-only word
     * (FAIOZ → F4IOZ); or ([versLettre]) a digit back to its letter (F4I0Z →
     * F4IOZ).
     */
    private fun repare(m: String, versLettre: Boolean): List<Reparation> = autreBase(m) { p ->
        if (p.length !in 5..7 || (!versLettre && p.any { !it.isLetter() })) return@autreBase emptyList()
        val r = ArrayList<Pair<String, Int>>()
        for (k in if (versLettre) p.indices else 1..2) {
            // A letter for the area digit: only the shapes closest to a digit (I, O, L there are mostly words).
            if (!versLettre && p[k] !in (if (k == 1) "ASBZGT" else "ASBZ")) continue
            for (s in SOSIES[p[k]].orEmpty()) {
                if (!s.isLetterOrDigit() || s.isDigit() == versLettre) continue
                r += (p.substring(0, k) + s + p.substring(k + 1)) to (if ("${p[k]}$s" in LOINTAINS || "$s${p[k]}" in LOINTAINS) 1 else 0)
            }
        }
        r
    }

    /** Look-alikes less often confused (0 is far more often O than Q or D). */
    private val LOINTAINS = setOf("L1", "Q0", "D0")

    /** A base of the usual shape, a prefix of the table's own, a 2–3 letter suffix: what a repair must give. */
    private fun bonneBase(q: String): Boolean {
        if (!PROPRE.matches(q)) return false
        val chiffre = q.indexOfLast { it.isDigit() }
        return q.length - chiffre - 1 in 2..3 && Dxcc.prefixe(q.substring(0, chiffre))
    }

    /** A callsign whose base looks right ([bonneBase]): taken as read. */
    private fun bon(indicatif: String): Boolean = AnalyseCw.base(indicatif)?.let { b ->
        bonneBase(b) || (PROPRE.matches(b) && dedoubles(b).none { bonneBase(it) })
    } == true

    /** [a] and [b] the same word read two ways: one look-alike swapped, or one sign read twice. */
    private fun voisins(a: String, b: String): Boolean {
        if (a.length == b.length) {
            val d = a.indices.filter { a[it] != b[it] }
            return d.size == 1 && SOSIES[a[d[0]]]?.contains(b[d[0]]) == true
        }
        val (court, long) = if (a.length < b.length) a to b else b to a
        return long.length == court.length + 1 && court in dedoubles(long)
    }


    private fun garant(indicatif: String, connus: Connus): Garant? = AnalyseCw.base(indicatif)?.let { connus.bases[it] }

    /** [texte] with the words read as callsigns written as repaired. */
    fun corrige(texte: String, lus: List<Lu>): String {
        val par = lus.filter { it.mot != it.indicatif }.associate { it.mot to it.indicatif }
        if (par.isEmpty()) return texte
        return Regex("[A-Za-z0-9/]+").replace(texte) { r ->
            val m = r.value.trim('/')
            par[m.uppercase()]?.let { r.value.replace(m, it) } ?: r.value
        }
    }

    /** [mot] as a callsign (slashes kept), or null when it is not one. */
    fun plausible(mot: String): String? {
        val m = mot.uppercase()
        val base = AnalyseCw.base(m) ?: return null
        if (base.length < 4 || base.length > 7 || CARRE.matches(base)) return null
        // A real prefix has a letter before the area digit: 10GHZ, 2400MHZ are not callsigns.
        val chiffre = base.indexOfLast { it.isDigit() }
        if (chiffre <= 0 || base.substring(0, chiffre).none { it.isLetter() }) return null
        if (Dxcc.entite(m) == null) return null
        return m
    }

    /** True when the station's service name (LongMynd's, often its callsign) holds [indicatif]. */
    fun accord(indicatif: String, service: String): Boolean {
        val b = AnalyseCw.base(indicatif) ?: return false
        return service.uppercase().split(Regex("[^A-Z0-9/]+")).any { AnalyseCw.base(it) == b }
    }

    /** True when [cielKhz] is in QO-100's wide band transponder (its downlink). */
    fun qo100(cielKhz: Long): Boolean = cielKhz in 10_490_500L..10_500_000L

    /** QO-100's uplink for a downlink [cielKhz]. */
    fun monteeKhz(cielKhz: Long): Long = cielKhz - Qo100.DECALAGE_HZ / 1000L
}

/**
 * The reads' vote: a candidate is shown once seen in [requis] consecutive
 * reads (a read without it starts its count again); one vouched for at once.
 */
class VoteIndicatifs(private val requis: Int = 2, private val garde: Int = 5) {

    /** A callsign confirmed: how many reads in a row, when first and last confirmed. */
    data class Lu(val indicatif: String, val lectures: Int, val premierMs: Long, val dernierMs: Long)

    private val suite = HashMap<String, Int>()
    private val confirmes = ArrayList<Lu>()

    /** One read's candidates at [t], [surs] among them shown at once; the callsigns confirmed by this read. */
    @Synchronized
    fun lecture(candidats: Collection<String>, t: Long, surs: Collection<String> = emptySet()): List<String> {
        val c = candidats.toSet() + surs
        suite.keys.retainAll(c)
        val ok = ArrayList<String>()
        for (x in c) {
            val n = (suite[x] ?: 0) + 1
            suite[x] = n
            if (n < requis && x !in surs) continue
            ok += x
            val i = confirmes.indexOfFirst { it.indicatif == x }
            val avant = if (i >= 0) confirmes.removeAt(i) else null
            confirmes.add(0, Lu(x, n, avant?.premierMs ?: t, t))
        }
        while (confirmes.size > garde) confirmes.removeAt(confirmes.size - 1)
        return ok
    }

    /** The confirmed callsigns, last confirmed first. */
    @Synchronized fun confirmes(): List<Lu> = confirmes.toList()

    @Synchronized fun oublie() { suite.clear(); confirmes.clear() }

    /** [indicatifs] no longer shown (their counts start again). */
    @Synchronized fun retire(indicatifs: Collection<String>) { confirmes.removeAll { it.indicatif in indicatifs }; suite.keys.removeAll(indicatifs.toSet()) }
}

/**
 * The callsigns read in the picture, kept to the station received now: a
 * new station (another frequency, another service name in the stream's
 * SDT, a lost lock) puts them away in a short [historique], and one not
 * read again for [oubliMs] goes there too. Each says how long ago it was
 * last read ([recentMs]: « just now »).
 */
class SuiviIndicatifs(
    val recentMs: Long = RECENT_MS,
    val oubliMs: Long = OUBLI_MS,
    private val historiqueMax: Int = 12,
    private val vote: VoteIndicatifs = VoteIndicatifs(),
) {
    /** One callsign put away: when last read, on which frequency and service. */
    data class Ancien(val indicatif: String, val premierMs: Long, val dernierMs: Long, val lectures: Int, val cielKhz: Long, val service: String)

    /** What tells one station from the next: the frequency tuned and the service name (empty until the SDT comes). */
    data class Contexte(val cielKhz: Long, val service: String)

    private var contexte: Contexte? = null
    private val historique = ArrayList<Ancien>()

    /** Changes with each new station: a read started before it is for the previous one ([lecture] drops it). */
    @Volatile var generation = 0L
        private set

    /**
     * The reception now: [c] null when not locked. A new frequency, or a
     * service name other than the one known (the first one coming is the
     * same station's), puts the callsigns away. True when they were.
     */
    @Synchronized
    fun contexte(c: Contexte?, t: Long): Boolean {
        val avant = contexte
        val meme = c != null && avant != null && c.cielKhz == avant.cielKhz &&
            (c.service.isEmpty() || avant.service.isEmpty() || c.service.equals(avant.service, ignoreCase = true))
        if (meme) {
            if (avant!!.service.isEmpty() && c!!.service.isNotEmpty()) contexte = c
            return false
        }
        if (c == null && avant == null) return false
        rangeTout()
        contexte = c
        generation++
        return true
    }

    /** One read made for the station [gen] at [t]: dropped when the station changed meanwhile. */
    @Synchronized
    fun lecture(candidats: Collection<String>, t: Long, surs: Collection<String> = emptySet(), gen: Long = generation): List<String> {
        if (gen != generation) return emptyList()
        return vote.lecture(candidats, t, surs)
    }

    /** The callsigns of the station received now, last read first; those not read for [oubliMs] put away first. */
    @Synchronized
    fun actuels(t: Long): List<VoteIndicatifs.Lu> {
        val vieux = vote.confirmes().filter { t - it.dernierMs > oubliMs }
        if (vieux.isNotEmpty()) { vieux.forEach { range(it) }; vote.retire(vieux.map { it.indicatif }) }
        return vote.confirmes()
    }

    /** The callsigns put away, last read first (none of those read now). */
    @Synchronized
    fun historique(t: Long): List<Ancien> {
        val maintenant = actuels(t).map { it.indicatif }.toSet()
        return historique.filter { it.indicatif !in maintenant }
    }

    /** All forgotten, the history too. */
    @Synchronized
    fun oublie() { vote.oublie(); historique.clear(); generation++ }

    private fun rangeTout() { vote.confirmes().forEach { range(it) }; vote.oublie() }

    private fun range(l: VoteIndicatifs.Lu) {
        val c = contexte
        historique.removeAll { it.indicatif == l.indicatif }
        historique.add(0, Ancien(l.indicatif, l.premierMs, l.dernierMs, l.lectures, c?.cielKhz ?: 0L, c?.service.orEmpty()))
        historique.sortByDescending { it.dernierMs }
        while (historique.size > historiqueMax) historique.removeAt(historique.size - 1)
    }

    companion object {
        /** Read again within this: « just now ». */
        const val RECENT_MS = 20_000L
        /** Not read again for this: put away. */
        const val OUBLI_MS = 300_000L
    }
}
