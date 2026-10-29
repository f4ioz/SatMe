/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import java.text.Normalizer

/**
 * The commands the voice assistant, Home Assistant and the owner's web site
 * may give: a short, closed list of questions (read-only) and of safe
 * actions (receive only, sound). Shared by [CommandesPc] and everything that
 * feeds it.
 *
 * **Nothing here can transmit.** A request that sounds like a transmission
 * or a rotor move (PTT, CAT TX, DATV / SSTV / FT8 / Pluto transmission, «
 * tourne l'antenne ») is recognised first and becomes [Intention.EMISSION],
 * which only ever answers that it must be done on the station's screen. An
 * action is carried out only when it is in [ACTIONS_SURES]; any other id
 * received (from the site, Home Assistant…) is refused.
 */
enum class Intention(val id: String, val action: Boolean = false) {
    PROCHAIN_PASSAGE("prochain_passage"),
    VISIBLES("visibles"),
    AUTOPILOTE_ETAT("autopilote_etat"),
    DERNIERE_SSTV("derniere_sstv"),
    DATV_ETAT("datv_etat"),
    BALISE("balise"),
    ROTOR("rotor"),
    PROCHAIN_AOS("prochain_aos"),
    DERNIER_QSO("dernier_qso"),
    AIDE("aide"),
    AUTOPILOTE_ON("autopilote_on", true),
    AUTOPILOTE_OFF("autopilote_off", true),
    SSTV_ON("sstv_on", true),
    SSTV_OFF("sstv_off", true),
    DATV_ON("datv_on", true),
    DATV_OFF("datv_off", true),
    SON_MUET("son_muet", true),
    SON_REMET("son_remet", true),
    /** A transmission or a rotor move asked: refused, always. */
    EMISSION("emission"),
    INCONNU("inconnu");

    companion object {
        fun de(id: String?): Intention? = values().firstOrNull { it.id == id }

        /** The questions answered from the station's state. */
        val QUESTIONS: List<Intention> = listOf(PROCHAIN_PASSAGE, VISIBLES, AUTOPILOTE_ETAT, DERNIERE_SSTV, DATV_ETAT, BALISE, ROTOR,
            PROCHAIN_AOS, DERNIER_QSO, AIDE)

        /** The only actions that may be carried out from a voice, Home Assistant or the site: none of them transmits. */
        val ACTIONS_SURES: List<Intention> = listOf(AUTOPILOTE_ON, AUTOPILOTE_OFF, SSTV_ON, SSTV_OFF, DATV_ON, DATV_OFF, SON_MUET, SON_REMET)
    }
}

/** A sentence understood: its intention, the satellite named (name, NORAD number) if any. */
data class Analyse(val intention: Intention, val satellite: Pair<String, Int>? = null, val texte: String = "")

/**
 * From words to an [Intention]: accents, case and punctuation ignored,
 * numbers said in words read as digits (« AO quatre-vingt-onze » → ao 91),
 * a letter wrong or missing forgiven in the longer words (« autopilotte »).
 */
object Phrases {

    /** Lowercase, no accents, words of letters and digits only, numbers in words turned into digits. */
    fun normalise(s: String): String {
        val sansAccents = Normalizer.normalize(s.replace("œ", "oe").replace("Œ", "OE").replace("æ", "ae"), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        val mots = sansAccents.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim().split(' ').filter { it.isNotEmpty() }
        return nombres(mots).joinToString(" ")
    }

    /** A name as a key: [normalise], then letters and digits only, together (« AO-91 » → ao91, « ISS (ZARYA) » → isszarya). */
    fun cle(s: String): String = normalise(s).replace(" ", "")

    // ------------------------------------------------------------------ numbers in words

    private val UNITES_FR = mapOf("zero" to 0, "un" to 1, "une" to 1, "deux" to 2, "trois" to 3, "quatre" to 4, "cinq" to 5, "six" to 6, "sept" to 7,
        "huit" to 8, "neuf" to 9, "dix" to 10, "onze" to 11, "douze" to 12, "treize" to 13, "quatorze" to 14, "quinze" to 15, "seize" to 16)
    private val DIZAINES_FR = mapOf("vingt" to 20, "vingts" to 20, "trente" to 30, "quarante" to 40, "cinquante" to 50, "soixante" to 60, "septante" to 70,
        "huitante" to 80, "octante" to 80, "nonante" to 90)
    private val UNITES_EN = mapOf("zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8,
        "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16,
        "seventeen" to 17, "eighteen" to 18, "nineteen" to 19)
    private val DIZAINES_EN = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90)

    /**
     * Numbers 0–99 said in words, in French or English, turned into digits. « un » / « une » alone stay words
     * (« une image »): only within a number (« vingt et un ») or after a letter-and-digit name they count.
     */
    internal fun nombres(mots: List<String>): List<String> {
        val r = ArrayList<String>()
        var i = 0
        fun mot(k: Int) = mots.getOrNull(k)
        while (i < mots.size) {
            val m = mots[i]
            // French tens, with « et un », « et onze », a unit, or « vingt » after « quatre » (quatre-vingt…).
            if (m == "quatre" && (mot(i + 1) == "vingt" || mot(i + 1) == "vingts")) {
                var v = 80; var j = i + 2
                val u = mot(j)?.let { UNITES_FR[it] }
                if (mot(j) == "dix") { v += 10; j++; mot(j)?.let { UNITES_FR[it] }?.takeIf { it in 1..9 }?.let { v += it; j++ } }
                else if (u != null && u in 1..16) { v += u; j++ }
                r += v.toString(); i = j; continue
            }
            val d = DIZAINES_FR[m]
            if (d != null) {
                var v = d; var j = i + 1
                if (mot(j) == "et" && (mot(j + 1) == "un" || mot(j + 1) == "une" || mot(j + 1) == "onze")) { v += if (mot(j + 1) == "onze") 11 else 1; j += 2 }
                else if (d == 60 && mot(j) == "dix") { v += 10; j++; mot(j)?.let { UNITES_FR[it] }?.takeIf { it in 1..9 }?.let { v += it; j++ } }
                else if (d == 60 && (mot(j)?.let { UNITES_FR[it] } ?: -1) in 11..16) { v += UNITES_FR.getValue(mot(j)!!); j++ }
                else mot(j)?.let { UNITES_FR[it] }?.takeIf { it in 1..9 && mot(j) != "une" }?.let { v += it; j++ }
                r += v.toString(); i = j; continue
            }
            val de = DIZAINES_EN[m]
            if (de != null) {
                var v = de; var j = i + 1
                mot(j)?.let { UNITES_EN[it] }?.takeIf { it in 1..9 }?.let { v += it; j++ }
                r += v.toString(); i = j; continue
            }
            // A unit: a digit only next to a number or a short name (« so cinquante », « dix-sept », « ao seven »).
            val u = UNITES_FR[m] ?: UNITES_EN[m]
            if (u != null && ((m != "un" && m != "une" && m != "one" && m != "neuf") || dansUnNom(r))) {
                if (m == "dix" && (mot(i + 1)?.let { UNITES_FR[it] } ?: -1) in 7..9) { r += (10 + UNITES_FR.getValue(mot(i + 1)!!)).toString(); i += 2; continue }
                // « neuf » (new) and « un » (a) stay words, but after a satellite-like prefix (« ao neuf »).
                r += u.toString(); i++; continue
            }
            r += m; i++
        }
        return r
    }

    /** The previous word is a satellite-like prefix (two or three letters): a unit after it is a digit. */
    private fun dansUnNom(avant: List<String>): Boolean = avant.lastOrNull()?.let { it.length in 1..3 && it.all { c -> c in 'a'..'z' } && it !in setOf("le", "la", "de", "du", "un", "et", "a", "l", "d", "en", "on", "is", "the") } == true

    // ------------------------------------------------------------------ word matching

    /** The edit distance between two short words. */
    fun distance(a: String, b: String): Int {
        if (a == b) return 0
        val p = IntArray(b.length + 1) { it }
        val c = IntArray(b.length + 1)
        for (i in 1..a.length) {
            c[0] = i
            for (j in 1..b.length) c[j] = minOf(c[j - 1] + 1, p[j] + 1, p[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            System.arraycopy(c, 0, p, 0, c.size)
        }
        return p[b.length]
    }

    /**
     * [mot] is [racine]: it starts with it (stems: « emet » for émettre, émets…), or differs by one letter
     * (two for long words) when both are long enough to tell.
     */
    fun ressemble(mot: String, racine: String): Boolean {
        if (mot == racine) return true
        // Short stems are whole words: « son » is not « sont », « aos » not « aoste ».
        if (racine.length >= 4 && mot.startsWith(racine)) return true
        if (racine.length < 5 || mot.length < 4) return false
        return distance(mot, racine) <= if (racine.length >= 8) 2 else 1
    }

    /** One of [racines] is among [mots]. */
    fun contient(mots: List<String>, vararg racines: String): Boolean = racines.any { r ->
        if (' ' in r) contientSuite(mots, r.split(' ')) else mots.any { ressemble(it, r) }
    }

    private fun contientSuite(mots: List<String>, suite: List<String>): Boolean =
        (0..mots.size - suite.size).any { i -> suite.indices.all { k -> ressemble(mots[i + k], suite[k]) } }

    // ------------------------------------------------------------------ the vocabulary

    /** Transmitting, in either language: never carried out. */
    private val EMETTRE = arrayOf("emet", "emis", "emiss", "transmet", "transmit", "transmis", "ptt", "tx", "cq", "keyer", "key up", "key the",
        "passe en emission", "go on air", "on the air", "sur l air", "lance un appel", "fais un appel", "call cq", "push to talk",
        "alternat", "parle sur", "parler sur", "talk on", "envoie une image", "envoie l image", "send a picture", "send an image", "send the picture",
        "puissance", "power up", "pluto", "bladerf", "hackrf", "porteuse", "carrier", "tune up", "qsy")

    /** Moving the antenna: refused too (only the auto-pilot, set up on the screen, moves it). */
    private val BOUGER = arrayOf("tourne", "oriente", "pointe vers", "pointe sur", "bouge", "deplace", "rotate", "turn the", "turn to",
        "point the", "point at", "point to", "move the", "park", "parque", "gare l")
    private val ANTENNE = arrayOf("antenne", "antenna", "rotor", "mat", "mast", "parabole", "dish")
    private val ANGLES = arrayOf("azimut", "azimuth", "elevation")

    private val ALLUMER = arrayOf("active", "activer", "allume", "demarre", "lance", "mets en route", "met en route", "mettre en route",
        "enclenche", "commence", "reprends", "enable", "start", "turn on", "switch on", "begin", "resume", "launch")
    private val ETEINDRE = arrayOf("desactive", "coupe", "arrete", "eteins", "eteint", "stoppe", "stop", "termine", "ferme", "suspend",
        "disable", "turn off", "switch off", "shut", "end the", "halt")

    private val AUTOPILOTE = arrayOf("autopilote", "autopilot", "auto pilote", "auto pilot", "pilote automatique", "campagne", "campaign")
    private val SSTV = arrayOf("sstv", "s s t v", "image ssdv")
    private val DATV = arrayOf("datv", "d a t v", "television", "tele numerique", "minitiouner", "dvb")

    /**
     * What [texte] asks, [satellites] being the catalogue (name, NORAD number) to find a satellite named in it.
     * The order matters: a transmission or a rotor move first, whatever else is said.
     */
    fun analyse(texte: String, satellites: List<Pair<String, Int>> = emptyList()): Analyse {
        val n = normalise(texte)
        val mots = n.split(' ').filter { it.isNotEmpty() }
        if (mots.isEmpty()) return Analyse(Intention.INCONNU, texte = texte)
        val sat = satellite(mots, satellites)
        val i = intention(mots, sat != null, question(texte, mots))
        return Analyse(i, sat.takeIf { i == Intention.PROCHAIN_PASSAGE }, texte)
    }

    /** True when [texte] asks for a transmission or a rotor move (refused, always). */
    fun demandeEmission(texte: String): Boolean = normalise(texte).split(' ').filter { it.isNotEmpty() }.let { emission(it) }

    private fun emission(mots: List<String>): Boolean {
        // « réception » never: « tx » only as a word of its own.
        if (mots.any { it == "tx" || it == "ptt" || it == "cq" }) return true
        if (contient(mots, *EMETTRE.filter { it != "tx" && it != "ptt" && it != "cq" }.toTypedArray())) return true
        if (contient(mots, *BOUGER) && (contient(mots, *ANTENNE) || contient(mots, *ANGLES))) return true
        // FT8 / FT4 is not among the safe actions: a gesture on it is one for the screen.
        if (contient(mots, "ft8", "ft4", "ft 8", "ft 4") && (contient(mots, *ALLUMER) || contient(mots, "repond", "answer", "reply"))) return true
        return false
    }

    /**
     * A question about a state (« est-ce que la DATV est coupée ? »), not an order: its verbs are no gesture.
     * A polite order stays one (« peux-tu couper le son ? »).
     */
    internal fun question(brut: String, mots: List<String>): Boolean {
        val poli = contient(mots, "peux tu", "pourrais tu", "veux tu", "tu peux", "can you", "could you", "would you", "please", "s il te plait", "s il vous plait")
        if (poli) return false
        val debut = mots.take(3).joinToString(" ")
        val interrogatif = listOf("est ce", "est il", "est elle", "quel", "quelle", "quels", "quelles", "comment", "ou ", "pourquoi", "combien",
            "is ", "are ", "was ", "what", "which", "how", "where", "does ", "do ", "did ").any { (debut + " ").startsWith(it) }
        return brut.trim().endsWith("?") || interrogatif || contient(mots, "etat", "status", "state")
    }

    private fun intention(mots: List<String>, satNomme: Boolean, question: Boolean = false): Intention {
        if (emission(mots)) return Intention.EMISSION
        val on = !question && contient(mots, *ALLUMER)
        val off = !question && contient(mots, *ETEINDRE)
        val geste = on || off
        fun choix(i: Intention, a: Intention, b: Intention): Intention = when {
            off -> b
            on -> a
            else -> i
        }
        // The sound: cut or back (the receivers' sound on this PC).
        val son = contient(mots, "son", "sound", "audio", "volume", "haut parleur", "speaker")
        if (!question && contient(mots, "muet", "mute", "silence", "chut") && !contient(mots, "unmute")) return Intention.SON_MUET
        if (!question && contient(mots, "unmute")) return Intention.SON_REMET
        if (son && contient(mots, "remet", "remets", "rallume", "retabli", "back", "restore")) return Intention.SON_REMET
        if (son && off) return Intention.SON_MUET
        if (son && on) return Intention.SON_REMET

        if (contient(mots, *AUTOPILOTE)) return choix(Intention.AUTOPILOTE_ETAT, Intention.AUTOPILOTE_ON, Intention.AUTOPILOTE_OFF)

        val sstv = contient(mots, *SSTV)
        val image = contient(mots, "image", "picture", "photo")
        val dernier = contient(mots, "dernier", "derniere", "last", "latest", "recent")
        if (sstv || (image && dernier)) {
            if (geste && !dernier) return choix(Intention.DERNIERE_SSTV, Intention.SSTV_ON, Intention.SSTV_OFF)
            return Intention.DERNIERE_SSTV
        }
        if (contient(mots, *DATV)) return if (geste) choix(Intention.DATV_ETAT, Intention.DATV_ON, Intention.DATV_OFF) else Intention.DATV_ETAT
        if (contient(mots, "balise", "beacon")) return Intention.BALISE
        if (contient(mots, "qso", "contact", "q s o", "carnet", "logbook")) return Intention.DERNIER_QSO
        if (contient(mots, *ANTENNE) || contient(mots, "pointe", "pointing", "position de l antenne", "where is the antenna")) return Intention.ROTOR
        if (contient(mots, "aos", "a o s", "acquisition")) return Intention.PROCHAIN_AOS
        if (contient(mots, "visible", "visibles", "en vue", "in view", "au dessus", "overhead", "dans le ciel", "in the sky", "up now", "en ce moment")
            && !contient(mots, "prochain", "next") && !satNomme) return Intention.VISIBLES
        if (contient(mots, "passage", "passe", "pass", "passes", "quand", "when", "prochain", "next", "orbite")) {
            // « le prochain satellite » with no satellite named: the next AOS of all.
            if (!satNomme && !contient(mots, "passage", "pass", "favori", "favorite") && contient(mots, "satellite", "sat")) return Intention.PROCHAIN_AOS
            return Intention.PROCHAIN_PASSAGE
        }
        if (satNomme) return Intention.PROCHAIN_PASSAGE
        if (contient(mots, "aide", "help", "que sais tu", "what can you", "commandes", "commands")) return Intention.AIDE
        return Intention.INCONNU
    }

    // ------------------------------------------------------------------ satellites

    /** A satellite's keys: its name, without what is in brackets, what is in brackets, and the usual names of a few. */
    fun clesDe(nom: String, catnum: Int): Set<String> {
        val s = LinkedHashSet<String>()
        s += cle(nom)
        s += cle(nom.replace(Regex("\\(.*?\\)"), ""))
        Regex("\\((.*?)\\)").findAll(nom).forEach { s += cle(it.groupValues[1]) }
        // « AO-91 (FOX-1B) » also as « fox 1b »; « RS-44 » as « rs44 ».
        ALIAS[catnum]?.forEach { s += cle(it) }
        return s.filter { it.length >= 2 && it.any { c -> c in 'a'..'z' } || it.length >= 3 }.toSet()
    }

    /** What people call a few satellites. */
    private val ALIAS = mapOf(
        25544 to listOf("ISS", "station spatiale", "station spatiale internationale", "space station", "international space station", "zarya"),
        43700 to listOf("QO-100", "QO 100", "Es'hail", "eshail", "Es'hail 2", "oscar 100", "geostationnaire"),
        27607 to listOf("SO-50", "saudisat"),
        43017 to listOf("AO-91", "fox 1b"),
        44909 to listOf("RS-44", "dosaaf"),
        7530 to listOf("AO-7", "oscar 7"),
        24278 to listOf("FO-29", "jas 2"),
    )

    /**
     * The satellite named in [mots]: the longest run of up to four words (joined, « ao 91 » → ao91) that is one of a
     * satellite's keys, or one letter away from a key of 5 letters or more.
     */
    fun satellite(mots: List<String>, satellites: List<Pair<String, Int>>): Pair<String, Int>? {
        if (satellites.isEmpty()) return null
        val index = HashMap<String, Pair<String, Int>>()
        satellites.forEach { s -> clesDe(s.first, s.second).forEach { k -> if (k !in index || s.second == 25544) index[k] = s } }
        // Words that are never a satellite's name by themselves.
        val vides = setOf("le", "la", "les", "de", "du", "des", "l", "d", "un", "une", "et", "the", "of", "a", "est", "is", "sat", "satellite")
        var meilleur: Pair<String, Int>? = null
        var longueur = 0
        for (i in mots.indices) {
            for (n in minOf(5, mots.size - i) downTo 1) {
                val bloc = mots.subList(i, i + n)
                // « a o 91 » is AO-91; « a » or « l » alone is no satellite.
                if (bloc.first() in vides && (n == 1 || bloc.first() !in setOf("a", "l", "d"))) continue
                if (bloc.last() in vides) continue
                val k = bloc.joinToString("")
                if (k.length < 2 || k.length <= longueur) continue
                val trouve = index[k] ?: if (k.length >= 5) index.entries.firstOrNull { (cle, _) -> cle.length >= 5 && distance(cle, k) <= 1 }?.value else null
                if (trouve != null) { meilleur = trouve; longueur = k.length; break }
            }
        }
        return meilleur
    }
}
