/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.phonie

import fr.f4ioz.satcombo.domain.Dxcc
import org.json.JSONObject

/**
 * What a transcribed over holds: callsigns (written « SP5ABC », or spelled
 * « Sierra Papa five Alfa Bravo Charlie », « Fox quatre Italie Oscar
 * Zoulou »), grid squares (« JN18 », « Juliett November one eight »),
 * reports (« cinq neuf », « five by nine », « 59 »).
 *
 * The text is cut into words; each word that can stand for letters or
 * digits (a spelling word of the ITU alphabet or a national one, a number
 * word in the over's language, a lone capital, a short code) is a piece,
 * and consecutive pieces are read together. A callsign must look like one
 * and have a country's prefix.
 */
object AnalysePhonie {

    enum class Genre { INDICATIF, CARRE, REPORT }

    /** What was found: its kind, its value, where it is in the text (start inclusive, end exclusive), how sure (callsigns 1…4), in the log. */
    data class Element(val genre: Genre, val valeur: String, val debut: Int, val fin: Int, val score: Int = 0, val connu: Boolean = false) {
        fun json(): JSONObject = JSONObject().put("g", genre.name).put("v", valeur).put("d", debut).put("f", fin).put("s", score).put("k", connu)
        companion object {
            fun depuis(j: JSONObject) = Element(runCatching { Genre.valueOf(j.optString("g")) }.getOrDefault(Genre.INDICATIF),
                j.optString("v"), j.optInt("d"), j.optInt("f"), j.optInt("s"), j.optBoolean("k"))
        }
    }

    /**
     * The elements of [texte], in order. [langue]: the over's language
     * (ISO 639-1, as the engine found it; empty: try them all). [connus]:
     * the log's callsigns (their base). [vus]: callsigns already heard
     * lately (a second time makes one surer).
     */
    fun analyse(texte: String, langue: String = "", connus: Set<String> = emptySet(), vus: Set<String> = emptySet()): List<Element> {
        val mots = MOT.findAll(texte).map { Mot(it.range.first, it.range.last + 1, it.value, FiltrePhonie.normalise(it.value).replace(" ", "")) }.toList()
        val pieces = pieces(mots, langue.lowercase().take(2))
        val sortie = ArrayList<Element>()
        var i = 0
        while (i < pieces.size) {
            if (pieces[i] == null) { i++; continue }
            var j = i
            while (j + 1 < pieces.size && pieces[j + 1] != null) j++
            sortie += lisSuite(pieces.subList(i, j + 1).map { it!! }, mots, texte)
            i = j + 1
        }
        // How sure each callsign is: its country (always), a word before it (DE, ici…), said twice, in the log.
        val compte = sortie.filter { it.genre == Genre.INDICATIF }.groupingBy { base(it.valeur) }.eachCount()
        return sortie.map { e ->
            if (e.genre != Genre.INDICATIF) return@map e
            val b = base(e.valeur)
            var s = 1
            val avant = mots.lastOrNull { it.fin <= e.debut }?.n
            if (avant != null && avant in AVANT) s++
            if ((compte[b] ?: 0) >= 2 || b in vus) s++
            val connu = b in connus
            if (connu) s++
            e.copy(score = s.coerceAtMost(4), connu = connu)
        }
    }

    /** The callsigns found, each once, surest first. */
    fun indicatifs(l: List<Element>): List<Element> =
        l.filter { it.genre == Genre.INDICATIF }.groupBy { it.valeur }.map { (_, v) -> v.maxBy { it.score } }.sortedByDescending { it.score }

    /** A callsign's body (EA5/F4IOZ/P → F4IOZ). */
    fun base(indicatif: String): String = fr.f4ioz.satcombo.cw.AnalyseCw.base(indicatif) ?: indicatif

    // ------------------------------------------------------------------ words to pieces

    private class Mot(val debut: Int, val fin: Int, val brut: String, val n: String)
    /** What a word stands for: [texte] (letters, digits, "/"), from word [de] to word [a]; [lien]: "and"/"by" between two digits. */
    private class Piece(val texte: String, val de: Int, val a: Int, val chiffres: Boolean, val ecrit: Boolean, val lien: Boolean = false)

    private val MOT = Regex("[\\p{L}\\p{Nd}/]+")

    private fun pieces(mots: List<Mot>, langue: String): List<Piece?> {
        val l = ArrayList<Piece?>()
        var k = 0
        while (k < mots.size) {
            val m = mots[k]
            // Two words for one letter: "x ray", "x-ray".
            val deux = mots.getOrNull(k + 1)?.let { s -> lettre(m.n + s.n, langue)?.takeIf { lettre(m.n, langue) == null } }
            if (deux != null) { l += Piece(deux.toString(), k, k + 1, false, false); k += 2; continue }
            l += piece(m, k, langue)
            k++
        }
        // "and", "by" between two digits only (five by nine); otherwise it ends the run.
        for (i in l.indices) {
            val p = l[i] ?: continue
            if (!p.lien) continue
            val a = l.getOrNull(i - 1); val b = l.getOrNull(i + 1)
            if (!(a != null && b != null && a.chiffres && b.chiffres && !a.lien && !b.lien)) l[i] = null
        }
        return l
    }

    private fun piece(m: Mot, k: Int, langue: String): Piece? {
        val n = m.n
        val b = m.brut
        if (n in ARRET) return null
        if (n == "portable" || n == "portabel") return Piece("/P", k, k, false, false)
        if (n in LIENS && b.all { it.isLowerCase() }) return Piece("", k, k, false, false, lien = true)
        if (n in BARRE) return Piece("/", k, k, false, false)
        lettre(n, langue)?.let { return Piece(it.toString(), k, k, false, false) }
        chiffre(n, langue)?.let { return Piece(it.toString(), k, k, true, false) }
        return when {
            b.all { it.isDigit() } && b.length <= 4 -> Piece(b, k, k, true, true)
            b.length == 1 && b[0] in 'A'..'Z' -> Piece(b, k, k, false, true)
            Regex("^\\d/\\d$").matches(b) -> Piece(b.replace("/", ""), k, k, true, true)
            b.length in 2..12 && Regex("^[A-Za-z0-9/]+$").matches(b) && b.any { it.isDigit() } && b.any { it.isLetter() } -> Piece(b.uppercase(), k, k, false, true)
            b.length in 2..4 && b.all { it in 'A'..'Z' } -> Piece(b, k, k, false, true)
            else -> null
        }
    }

    // ------------------------------------------------------------------ pieces read together

    private val CARRE = Regex("^[A-R]{2}[0-9]{2}(?:[A-X]{2})?$")
    private val CORPS = Regex("^[A-Z0-9]{1,3}[0-9][A-Z]{1,4}$")

    private fun lisSuite(p: List<Piece>, mots: List<Mot>, texte: String): List<Element> {
        val l = ArrayList<Element>()
        var i = 0
        fun texteDe(a: Int, b: Int) = (a..b).joinToString("") { p[it].texte }
        fun element(g: Genre, v: String, a: Int, b: Int) = Element(g, v, mots[p[a].de].debut, mots[p[b].a].fin)
        while (i < p.size) {
            if (p[i].lien || p[i].texte == "/") { i++; continue }
            val fin = minOf(p.size - 1, i + 11)
            // A square first: JN18 is no callsign.
            val carre = (fin downTo i).firstOrNull { j -> CARRE.matches(texteDe(i, j)) && !p[j].lien }
            if (carre != null) { l += element(Genre.CARRE, texteDe(i, carre), i, carre); i = carre + 1; continue }
            // A report: digits only, R 3–5, S 1–9 (T 1–9); said after "report" or one of the usual ones.
            val chiffres = (i..fin).takeWhile { p[it].chiffres || p[it].lien }.lastOrNull()
            if (chiffres != null) {
                val v = texteDe(i, chiffres)
                if (Regex("^[3-5][1-9][1-9]?$").matches(v) && (v in HABITUELS || apresRapport(mots, p[i].de))) {
                    l += element(Genre.REPORT, v, i, chiffres); i = chiffres + 1; continue
                }
            }
            val indicatif = (fin downTo i).firstOrNull { j -> !p[j].lien && indicatifPlausible(texteDe(i, j), p[j]) }
            if (indicatif != null) { l += element(Genre.INDICATIF, texteDe(i, indicatif), i, indicatif); i = indicatif + 1; continue }
            i++
        }
        return l
    }

    /** Looks like a callsign, with a country; a 4-letter suffix only written whole. */
    private fun indicatifPlausible(s: String, derniere: Piece): Boolean {
        if (s.length < 3 || s.length > 14 || s.startsWith("/") || s.endsWith("/")) return false
        val morceaux = s.split('/')
        if (morceaux.size > 3) return false
        val corps = morceaux.maxBy { it.length }
        if (!CORPS.matches(corps)) return false
        val suffixe = corps.takeLastWhile { it.isLetter() }
        if (suffixe.length == 4 && !(derniere.ecrit && derniere.texte.endsWith(suffixe))) return false
        val prefixe = corps.dropLast(suffixe.length + 1)
        if (prefixe.none { it.isLetter() } && !(prefixe.length == 1 && prefixe[0].isDigit() && corps.length >= 4 && corps[1].isLetter())) return false
        return Dxcc.entite(s) != null
    }

    private fun apresRapport(mots: List<Mot>, k: Int): Boolean =
        (maxOf(0, k - 3) until k).any { mots[it].n in RAPPORT }

    // ------------------------------------------------------------------ the alphabets

    private fun lettre(n: String, langue: String): Char? =
        OTAN[n] ?: NATIONAUX[langue]?.get(n) ?: (if (langue.isEmpty() || langue !in NATIONAUX) NATIONAUX.values.firstNotNullOfOrNull { it[n] } else null) ?: RADIO[n]

    private fun chiffre(n: String, langue: String): Char? =
        CHIFFRES[langue]?.get(n) ?: CHIFFRES["en"]!![n] ?: ITU[n]
            ?: (if (langue.isEmpty() || langue !in CHIFFRES) CHIFFRES.values.firstNotNullOfOrNull { it[n] } else null)

    private fun table(vararg l: Pair<String, String>): Map<String, Char> =
        l.flatMap { (lettres, mots) -> mots.split(' ').map { it to lettres[0] } }.toMap()

    /** The ITU / NATO alphabet, with the spellings heard in French. */
    private val OTAN = table("a" to "alfa alpha", "b" to "bravo", "c" to "charlie charly", "d" to "delta", "e" to "echo",
        "f" to "foxtrot fox foxtrott", "g" to "golf", "h" to "hotel", "i" to "india", "j" to "juliett juliet juliette", "k" to "kilo",
        "l" to "lima", "m" to "mike maik", "n" to "november novembre", "o" to "oscar", "p" to "papa", "q" to "quebec",
        "r" to "romeo", "s" to "sierra", "t" to "tango", "u" to "uniform uniforme", "v" to "victor", "w" to "whiskey whisky",
        "x" to "xray", "y" to "yankee yanki", "z" to "zulu zoulou").mapValues { it.value.uppercaseChar() }

    /** Countries and words heard on the air instead of the alphabet's. */
    private val RADIO = table("a" to "america amerique amsterdam", "b" to "boston baltimore", "c" to "canada", "d" to "denmark danemark",
        "e" to "england", "f" to "florida", "g" to "germany", "h" to "honolulu", "i" to "italy italie italia", "j" to "japan japon",
        "k" to "kilowatt", "l" to "london", "m" to "mexico mexique", "n" to "norway norvege", "o" to "ontario", "p" to "portugal pacific",
        "s" to "santiago", "t" to "tokyo", "u" to "united", "v" to "venezuela", "w" to "washington", "y" to "yokohama", "z" to "zanzibar")
        .mapValues { it.value.uppercaseChar() }

    private val NATIONAUX: Map<String, Map<String, Char>> = mapOf(
        "fr" to table("a" to "anatole", "b" to "berthe", "c" to "celestin", "d" to "desire", "e" to "eugene", "f" to "francois",
            "g" to "gaston", "h" to "henri", "i" to "irma", "j" to "joseph", "k" to "kleber", "l" to "louis", "m" to "marcel",
            "n" to "nicolas", "p" to "pierre", "q" to "quintal", "r" to "raoul", "s" to "suzanne", "t" to "therese",
            "u" to "ursule", "w" to "william", "x" to "xavier", "y" to "yvonne", "z" to "zoe"),
        "de" to table("a" to "anton", "b" to "berta", "c" to "casar caesar charlotte", "d" to "dora", "e" to "emil",
            "f" to "friedrich", "g" to "gustav", "h" to "heinrich", "i" to "ida", "j" to "julius", "k" to "kaufmann konrad",
            "l" to "ludwig", "m" to "martha marta", "n" to "nordpol", "o" to "otto", "p" to "paula", "q" to "quelle",
            "r" to "richard", "s" to "samuel siegfried", "t" to "theodor", "u" to "ulrich", "v" to "viktor", "w" to "wilhelm",
            "x" to "xanthippe", "y" to "ypsilon", "z" to "zacharias zeppelin"),
        "es" to table("a" to "antonio", "b" to "barcelona", "c" to "carmen", "d" to "dolores", "e" to "enrique", "f" to "francia",
            "g" to "gerona", "h" to "historia", "i" to "ines", "j" to "jose", "l" to "lorenzo", "m" to "madrid", "n" to "navarra",
            "o" to "oviedo", "p" to "paris", "q" to "querido", "r" to "ramon", "s" to "sabado", "t" to "tarragona", "u" to "ulises",
            "v" to "valencia", "x" to "xiquena", "y" to "yegua", "z" to "zaragoza"),
        "it" to table("a" to "ancona", "b" to "bologna bari", "c" to "como", "d" to "domodossola", "e" to "empoli", "f" to "firenze",
            "g" to "genova", "i" to "imola", "j" to "jolly", "k" to "kappa", "l" to "livorno", "m" to "milano", "n" to "napoli",
            "o" to "otranto", "p" to "palermo", "q" to "quarto", "r" to "roma", "s" to "savona", "t" to "torino", "u" to "udine",
            "v" to "venezia", "x" to "xeres", "y" to "yacht york", "z" to "zara"),
        "pl" to table("a" to "adam", "b" to "barbara", "c" to "celina", "d" to "dorota", "e" to "ewa", "f" to "franciszek",
            "g" to "grazyna", "h" to "henryk", "i" to "irena", "j" to "jozef", "k" to "karol", "l" to "ludwik", "m" to "marek",
            "n" to "natalia", "o" to "olga", "p" to "pawel", "r" to "roman", "s" to "stefan", "t" to "tadeusz", "u" to "urszula",
            "w" to "wanda", "x" to "xawery", "y" to "ypsylon", "z" to "zygmunt"),
    ).mapValues { (_, t) -> t.mapValues { it.value.uppercaseChar() } }

    private val CHIFFRES: Map<String, Map<String, Char>> = mapOf(
        "en" to table("0" to "zero oh", "1" to "one", "2" to "two", "3" to "three tree", "4" to "four", "5" to "five fife",
            "6" to "six", "7" to "seven", "8" to "eight", "9" to "nine niner"),
        "fr" to table("0" to "zero", "1" to "un une", "2" to "deux", "3" to "trois", "4" to "quatre", "5" to "cinq",
            "6" to "six", "7" to "sept", "8" to "huit", "9" to "neuf"),
        "de" to table("0" to "null", "1" to "eins", "2" to "zwei zwo", "3" to "drei", "4" to "vier", "5" to "funf fuenf",
            "6" to "sechs", "7" to "sieben", "8" to "acht", "9" to "neun"),
        "es" to table("0" to "cero", "1" to "uno una", "2" to "dos", "3" to "tres", "4" to "cuatro", "5" to "cinco",
            "6" to "seis", "7" to "siete", "8" to "ocho", "9" to "nueve"),
        "it" to table("0" to "zero", "1" to "uno", "2" to "due", "3" to "tre", "4" to "quattro", "5" to "cinque",
            "6" to "sei", "7" to "sette", "8" to "otto", "9" to "nove"),
        "pl" to table("0" to "zero", "1" to "jeden jedynka", "2" to "dwa dwojka", "3" to "trzy trojka", "4" to "cztery czworka",
            "5" to "piec piatka", "6" to "szesc szostka", "7" to "siedem siodemka", "8" to "osiem osemka", "9" to "dziewiec dziewiatka"),
    )

    /** The ITU's figure words. */
    private val ITU = table("0" to "nadazero", "1" to "unaone", "2" to "bissotwo", "3" to "terrathree", "4" to "kartefour",
        "5" to "pantafive", "6" to "soxisix", "7" to "setteseven", "8" to "oktoeight", "9" to "novenine")

    /** Words that are no piece of a callsign, even in capitals. */
    private val ARRET = setOf("cq", "de", "qrz", "qsl", "qso", "qsy", "qth", "qrm", "qrn", "qsb", "ok", "tnx", "rst", "ssb", "fm", "usb",
        "lsb", "cw", "am", "pm", "utc", "qo", "lnb", "sdr", "dx", "hf", "vhf", "uhf", "ft8", "ft4", "73", "88", "nr", "op")
    private val LIENS = setOf("and", "by", "et", "und", "y", "e", "i", "na", "par")
    private val BARRE = setOf("stroke", "slash", "barre", "strich", "barra", "ukosnik")
    private val AVANT = setOf("de", "ici", "hier", "aqui", "qui", "tutaj", "cq", "from", "von", "da", "od", "this", "is", "calling", "appelle", "indicatif")
    private val RAPPORT = setOf("report", "rapport", "raport", "rapporto", "reporte", "signal", "signaux", "segnale", "senal", "rst", "rs", "bericht", "sygnal")
    private val HABITUELS = setOf("59", "57", "55", "599", "579", "559")
}
