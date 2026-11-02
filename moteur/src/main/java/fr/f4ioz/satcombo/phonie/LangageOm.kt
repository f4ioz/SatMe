/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.phonie

/**
 * Ham radio's language in a transcript: what the recogniser is told before
 * it listens ([prompt], [motsCles]: Whisper's « initial_prompt » and
 * « hotwords », no training), and its text written as a ham writes it
 * ([corrige]):
 *
 * - spelling runs in letters: « Foxtrot quatre India Oscar Zoulou » → F4IOZ,
 *   « Charlie Québec » → CQ, « Juliet November one eight » → JN18; a lone
 *   word of the alphabet in a sentence stays a word (« un hôtel »);
 * - reports in figures: « cinq neuf », « five by nine » → 59;
 * - the Q code and the usual words: « Q.R.Z. », « cul est elle » → QRZ, QSL;
 *   « seventy-three » → 73, « pile up » → pile-up, « QO 100 » → QO-100;
 * - a personal vocabulary (a text file the user edits): words to suggest,
 *   corrections of what the engine keeps getting wrong.
 *
 * Text only: nothing heard is ever an order (no command, no transmission).
 * Shared by SatMe-PC and TellusMe; [AnalysePhonie] then finds the callsigns.
 */
object LangageOm {

    /** The user's own words ([mots]: suggested and written so) and corrections (heard → written). */
    data class Vocabulaire(val mots: List<String> = emptyList(), val corrections: List<Pair<String, String>> = emptyList()) {
        companion object { val VIDE = Vocabulaire() }
    }

    /** The personal vocabulary file as written the first time: its explanation, no entry. */
    val MODELE = """
        |# Vocabulaire personnel de « Phonie → texte »
        |#
        |# Relu à chaque passage transcrit : l'enregistrer suffit, rien à redémarrer.
        |# Les lignes qui commencent par # ne comptent pas.
        |#
        |# Un mot ou une expression par ligne : soufflé au moteur avant qu'il écoute,
        |# puis remis dans cette écriture quand il l'écrit autrement. Par exemple :
        |#   F5SIM
        |#   Radio-Club de la Sarthe
        |#
        |# Une correction par ligne, « ce que le moteur écrit => ce qu'il faut écrire » :
        |#   cul est sel => QSL
        |#   Aude 100 => QO-100
        |#
        |# Rien de ce qui est entendu ne commande quoi que ce soit : c'est du texte.
        |""".trimMargin()

    /** [texte] (the file's content) read: comments and blank lines left out. */
    fun litVocabulaire(texte: String): Vocabulaire {
        val mots = ArrayList<String>()
        val corrections = ArrayList<Pair<String, String>>()
        for (brute in texte.lineSequence()) {
            val l = brute.trim()
            if (l.isEmpty() || l.startsWith("#")) continue
            val i = l.indexOf("=>")
            if (i >= 0) {
                val de = l.substring(0, i).trim()
                if (de.isNotEmpty()) corrections += de.take(120) to l.substring(i + 2).trim().take(120)
            } else mots += l.take(60)
        }
        return Vocabulaire(mots.distinct().take(MAX_MOTS), corrections.take(MAX_CORRECTIONS))
    }

    // ------------------------------------------------------------------ before listening

    /**
     * Whisper's « initial_prompt »: a few words of a contact in [langue] (as if
     * just heard), so it writes the Q code, figures and spellings as hams do.
     * Language unknown: the codes alone, which every language uses. [moi]:
     * the station's callsign, spelt too.
     */
    fun prompt(langue: String, moi: String = "", v: Vocabulaire = Vocabulaire.VIDE): String {
        val l = langue.lowercase().take(2)
        val ind = moi.trim().uppercase().takeIf { AnalysePhonie.plausible(it) { true } || CORPS_LIBRE.matches(it) }
        val epele = ind?.let { epelle(it, l) }
        val de = if (ind != null) " de $ind, $epele" else ""
        val base = when (l) {
            "fr" -> "QSO radioamateur en phonie. CQ CQ$de. QRZ ? Bien reçu, QSL. Report 59, cinq neuf. QTH, locator. 73, à vous."
            "en" -> "Amateur radio QSO. CQ CQ$de. QRZ? Roger, QSL. You are 59, five nine. QTH, locator. 73, over."
            "es" -> "QSO de radioaficionados. CQ CQ$de. ¿QRZ? QSL. Reporte 59, cinco nueve. QTH, locator. 73, cambio."
            "de" -> "Amateurfunk-QSO. CQ CQ$de. QRZ? QSL. Rapport 59. QTH, Locator. 73."
            "it" -> "QSO radioamatoriale. CQ CQ$de. QRZ? QSL. Rapporto 59. QTH, locator. 73."
            else -> "CQ CQ" + (ind?.let { " de $it" } ?: "") + ". QRZ? QSL. QSO. 59. 73."
        }
        val perso = v.mots.take(20).joinToString(", ")
        return (base + if (perso.isNotEmpty()) " $perso." else "").take(MAX_PROMPT)
    }

    /** Whisper's « hotwords »: the codes heard most, the station's callsign, the user's words. */
    fun motsCles(moi: String = "", v: Vocabulaire = Vocabulaire.VIDE): String =
        (listOf("CQ", "QRZ", "QSL", "QSO", "QTH", "QSY", "73", "59", "QO-100") + listOfNotNull(moi.trim().uppercase().ifBlank { null }) + v.mots.take(20))
            .distinct().joinToString(" ").take(MAX_PROMPT / 2)

    /**
     * Whisper on near-silence sometimes says its prompt back: a segment of
     * four words or more found word for word in [prompt] was not heard.
     */
    fun recopieDuPrompt(texte: String, prompt: String): Boolean {
        if (prompt.isBlank()) return false
        val t = FiltrePhonie.normalise(texte)
        return t.split(' ').count { it.isNotEmpty() } >= 4 && " ${FiltrePhonie.normalise(prompt)} ".contains(" $t ")
    }

    /** [indicatif] said with the ITU alphabet, figures in [langue] (« Foxtrot quatre India Oscar Zulu »). */
    fun epelle(indicatif: String, langue: String = "en"): String {
        val chiffres = CHIFFRES_DITS[langue] ?: CHIFFRES_DITS["en"]!!
        return indicatif.uppercase().mapNotNull { c ->
            when {
                c in 'A'..'Z' -> NOMS_OTAN[c - 'A']
                c in '0'..'9' -> chiffres[c - '0']
                c == '/' -> if (langue == "fr") "barre" else "stroke"
                else -> null
            }
        }.joinToString(" ")
    }

    // ------------------------------------------------------------------ after: the text written as a ham writes it

    /**
     * [texte] as the engine wrote it, in ham radio's writing. [langue]: the
     * over's (ISO 639-1; empty: any). The raw text is the caller's to keep.
     */
    fun corrige(texte: String, langue: String = "", v: Vocabulaire = Vocabulaire.VIDE): String {
        if (texte.isBlank()) return texte
        val l = langue.lowercase().take(2)
        var t = texte
        t = correctionsPerso(t, v.corrections)
        t = phrases(t)
        t = codesEpeles(t)
        t = epellations(t, l)
        t = formes(t, v.mots)
        return t.replace(Regex("[ \\t]{2,}"), " ").trim()
    }

    // ------------------------------------------------------------------ 1. the user's corrections

    private fun correctionsPerso(t: String, c: List<Pair<String, String>>): String =
        c.fold(t) { x, (de, vers) -> runCatching { motif(de).replace(x, Regex.escapeReplacement(vers)) }.getOrDefault(x) }

    /** [expression] found whole, any case, its words apart by spaces, hyphens, dots or apostrophes. */
    private fun motif(expression: String): Regex {
        val mots = expression.trim().split(Regex("[\\s\\-.'’]+")).filter { it.isNotEmpty() }
        return Regex(AVANT + mots.joinToString("[\\s\\-.'’]+") { Regex.escape(it) } + APRES, RegexOption.IGNORE_CASE)
    }

    // ------------------------------------------------------------------ 2. fixed phrases

    /** Numbers said in words that hams write in figures, any case. */
    private val PHRASES: List<Pair<Regex, String>> = listOf(
        "soixante treize" to "73", "seventy three" to "73", "setenta y tres" to "73", "dreiundsiebzig" to "73", "settantatre" to "73",
        "quatre vingt huit" to "88", "quatre vingts huit" to "88", "eighty eight" to "88", "ochenta y ocho" to "88", "achtundachtzig" to "88",
        "kiselle" to "QSL", "kisselle" to "QSL", "cul est sel" to "QSL", "cul et sel" to "QSL", "cul est celle" to "QSL", "q est sel" to "QSL", "qu est sel" to "QSL",
    ).map { (de, vers) -> motif(de) to vers }

    private fun phrases(t: String): String = PHRASES.fold(t) { x, (r, v) -> r.replace(x, v) }

    // ------------------------------------------------------------------ 3. the Q code said letter by letter

    /** The Q codes heard in ham speech. */
    val CODES_Q = setOf("QRA", "QRB", "QRG", "QRK", "QRL", "QRM", "QRN", "QRO", "QRP", "QRQ", "QRS", "QRT", "QRU", "QRV", "QRX",
        "QRZ", "QSA", "QSB", "QSD", "QSK", "QSL", "QSO", "QSP", "QSY", "QTC", "QTH", "QTR")

    /** How the letters' names come out of the engine (fr, en), lower case without accents: « air » R, « zed » Z… */
    private val NOMS_LETTRES: Map<String, String> = mapOf(
        "a" to "a", "b" to "b be bee", "c" to "c ce see", "d" to "d dee", "g" to "g ge gee", "h" to "h ache hache aitch",
        "k" to "k ka kay", "l" to "l el elle ell", "m" to "m em emme aime", "n" to "n en enne haine", "o" to "o oh eau au",
        "p" to "p pe pee", "q" to "q cu ku cue queue cul kyu", "r" to "r er erre air aire are ar", "s" to "s es esse est ess",
        "t" to "t te the tee tea", "u" to "u you", "v" to "v ve vee", "x" to "x ix ixe ex", "y" to "y igrec why", "z" to "z zed zede zee",
        // Two letters heard as one word.
        "sl" to "sel cel celle", "so" to "esso", "rz" to "airzed",
    ).flatMap { (l, mots) -> mots.split(' ').map { it to l.uppercase() } }.toMap()

    private val MOT_SIMPLE = Regex("[\\p{L}\\p{Nd}]+")

    /** « Q.R.Z. », « Q R Z », « cul est elle », « cue ess ell »: a Q and two letters' names making a code. */
    private fun codesEpeles(t: String): String {
        val mots = MOT_SIMPLE.findAll(t).toList()
        val sortie = StringBuilder()
        var pris = 0
        var k = 0
        while (k < mots.size) {
            val q = mots[k]
            if (NOMS_LETTRES[FiltrePhonie.normalise(q.value)] != "Q") { k++; continue }
            var trouve: Pair<Int, String>? = null
            var lettres = "Q"
            var j = k + 1
            while (j < mots.size && j <= k + 2 && lettres.length < 3) {
                // Only spaces, dots, hyphens, apostrophes between: « Q.R.Z. », « qu'est-elle ».
                if (!t.substring(mots[j - 1].range.last + 1, mots[j].range.first).matches(Regex("[\\s.\\-'’]*"))) break
                lettres += NOMS_LETTRES[FiltrePhonie.normalise(mots[j].value)] ?: break
                if (lettres.length == 3 && lettres in CODES_Q) trouve = j to lettres
                j++
            }
            if (trouve == null) { k++; continue }
            sortie.append(t, pris, q.range.first).append(trouve.second)
            pris = mots[trouve.first].range.last + 1
            // « Q.R.Z. »: its last dot too.
            if (t.substring(q.range.last + 1, mots[trouve.first].range.first).contains('.') && t.getOrNull(pris) == '.') pris++
            k = trouve.first + 1
        }
        return sortie.append(t, pris, t.length).toString()
    }

    // ------------------------------------------------------------------ 4. spelling runs

    private enum class Sorte { OTAN, LETTRE, CHIFFRE, ECRIT, BARRE, LIEN }

    /** A word (or two: « fox trot ») standing for [valeur]; [debut], [fin]: where in the text. */
    private class Jeton(val debut: Int, val fin: Int, val valeur: String, val sorte: Sorte, val n: String) {
        val chiffres: Boolean get() = sorte == Sorte.CHIFFRE || (sorte == Sorte.ECRIT && valeur.all { it.isDigit() })
        val dit: Boolean get() = sorte != Sorte.ECRIT
    }

    private val MOT = Regex("[\\p{L}\\p{Nd}/]+")
    private val SEPARATION = Regex("[\\s,\\-–.]*")
    private val ESPACE = Regex("[\\s\\-]*")
    /** Words joining two figures of a report (« five by nine », « cinq sur neuf »). */
    private val LIENS = AnalysePhonie.LIENS + setOf("sur")
    /** Pairs of the alphabet that are words together. */
    private val EXCEPTIONS = setOf("alfaromeo", "alpharomeo")

    private fun jeton(m: MatchResult, langue: String): Jeton? {
        val b = m.value
        val n = FiltrePhonie.normalise(b).replace(" ", "")
        val d = m.range.first; val f = m.range.last + 1
        if (n.isEmpty() || n in AnalysePhonie.ARRET) return null
        if (n in AnalysePhonie.BARRE) return Jeton(d, f, "/", Sorte.BARRE, n)
        if (n == "portable" || n == "portabel") return Jeton(d, f, "/P", Sorte.BARRE, n)
        if (n == "mobile") return Jeton(d, f, "/M", Sorte.BARRE, n)
        if (n in LIENS && b.all { it.isLowerCase() }) return Jeton(d, f, "", Sorte.LIEN, n)
        AnalysePhonie.otan(n)?.let { return Jeton(d, f, it.toString(), Sorte.OTAN, n) }
        AnalysePhonie.chiffre(n, langue)?.let { return Jeton(d, f, it.toString(), Sorte.CHIFFRE, n) }
        AnalysePhonie.lettre(n, langue)?.let { return Jeton(d, f, it.toString(), Sorte.LETTRE, n) }
        return when {
            b.all { it.isDigit() } && b.length <= 2 -> Jeton(d, f, b, Sorte.ECRIT, n)
            b.length == 1 && b[0] in 'A'..'Z' -> Jeton(d, f, b, Sorte.ECRIT, n)
            b.length in 2..12 && Regex("^[A-Z0-9/]+$").matches(b) && b.any { it.isDigit() } && b.any { it.isLetter() } -> Jeton(d, f, b, Sorte.ECRIT, n)
            b.length in 2..4 && b.all { it in 'A'..'Z' } -> Jeton(d, f, b, Sorte.ECRIT, n)
            else -> null
        }
    }

    /** The text's words as [Jeton]s, null for a word that stands for nothing (it ends a run). */
    private fun jetons(t: String, langue: String): List<Jeton?> {
        val mots = MOT.findAll(t).toList()
        val l = ArrayList<Jeton?>()
        var k = 0
        while (k < mots.size) {
            val m = mots[k]
            val s = mots.getOrNull(k + 1)
            // Two words for one letter: « X ray », « Fox-trot ».
            if (s != null && t.substring(m.range.last + 1, s.range.first).matches(ESPACE)) {
                val n1 = FiltrePhonie.normalise(m.value).replace(" ", "")
                val c = AnalysePhonie.otan(n1 + FiltrePhonie.normalise(s.value).replace(" ", ""))
                if (c != null && (AnalysePhonie.otan(n1) == null || jeton(s, langue) == null)) {
                    l += Jeton(m.range.first, s.range.last + 1, c.toString(), Sorte.OTAN, n1); k += 2; continue
                }
            }
            l += jeton(m, langue)
            k++
        }
        return l
    }

    private class Remplace(val debut: Int, val fin: Int, val texte: String)

    private fun epellations(t: String, langue: String): String {
        val j = jetons(t, langue)
        val remplace = ArrayList<Remplace>()
        // Runs: tokens one after the other, nothing but spaces, commas, hyphens or dots between
        // (and only spaces next to a callsign already written).
        var i = 0
        while (i < j.size) {
            if (j[i] == null) { i++; continue }
            var f = i
            while (f + 1 < j.size && j[f + 1] != null && suit(t, j[f]!!, j[f + 1]!!)) f++
            remplace += lisSuite(j.subList(i, f + 1).map { it!! }, t)
            i = f + 1
        }
        val sb = StringBuilder(t)
        for (r in remplace.sortedByDescending { it.debut }) sb.replace(r.debut, r.fin, r.texte)
        return sb.toString()
    }

    private fun suit(t: String, a: Jeton, b: Jeton): Boolean {
        val entre = t.substring(a.fin, b.debut)
        if (!entre.matches(SEPARATION)) return false
        // « 5-9 », « 1-8 »: figures the engine wrote apart.
        if (!a.dit && !b.dit && a.chiffres && b.chiffres && entre.trim() == "-") return true
        return (a.dit && b.dit) || entre.isBlank()
    }

    private fun lisSuite(p: List<Jeton>, t: String): List<Remplace> {
        val sortie = ArrayList<Remplace>()
        var i = 0
        dehors@ while (i < p.size) {
            for (f in minOf(p.size - 1, i + 14) downTo i + 1) {
                val v = valeur(p.subList(i, f + 1), t) ?: continue
                sortie += Remplace(p[i].debut, p[f].fin, v)
                i = f + 1
                continue@dehors
            }
            i++
        }
        return sortie
    }

    /** What run [p] (two tokens or more) is written as, or null when it is no spelling. */
    private fun valeur(p: List<Jeton>, t: String): String? {
        val premier = p.first(); val dernier = p.last()
        if (premier.sorte == Sorte.LIEN || dernier.sorte == Sorte.LIEN || premier.sorte == Sorte.BARRE) return null
        // A link only between two figures.
        for (k in p.indices) if (p[k].sorte == Sorte.LIEN && !(p.getOrNull(k - 1)?.chiffres == true && p.getOrNull(k + 1)?.chiffres == true)) return null
        val s = p.joinToString("") { it.valeur }
        val dits = p.count { it.dit && it.sorte != Sorte.LIEN && it.sorte != Sorte.BARRE }
        // Figures only: a report (R 1–5, S 1–9, T 1–9), the usual ones, or any after « report ».
        if (p.all { it.chiffres || it.sorte == Sorte.LIEN }) {
            if (p.count { it.chiffres } < 2 || !Regex("^[1-5][1-9][1-9]?$").matches(s)) return null
            return s.takeIf { it in AnalysePhonie.HABITUELS || apresRapport(t, premier.debut) }
        }
        // A grid square.
        if (AnalysePhonie.CARRE.matches(s) && dits >= 1) return s
        // A callsign with its country (a 4-letter suffix only when written).
        if (AnalysePhonie.plausible(s) { suffixe -> !dernier.dit && dernier.valeur.endsWith(suffixe) }) return s
        // Letters of the alphabet: two different at least, at both ends, nothing but the alphabet, figures, strokes.
        if (premier.sorte != Sorte.OTAN || !(dernier.sorte == Sorte.OTAN || dernier.sorte == Sorte.BARRE && p.any { it.chiffres })) return null
        if (p.any { it.sorte == Sorte.LETTRE || it.sorte == Sorte.LIEN }) return null
        val otan = p.filter { it.sorte == Sorte.OTAN }
        if (otan.map { it.valeur }.distinct().size < 2) return null
        if (p.size == 2 && !t.substring(premier.fin, dernier.debut).matches(ESPACE)) return null
        if (p.joinToString("") { it.n } in EXCEPTIONS) return null
        return codes(s)
    }

    /** Letters that are codes one after the other (« CQCQ », « CQDX ») written apart. */
    private fun codes(s: String): String {
        val r = Regex("(CQ|DX|QRZ|QSL|QSO|QRT|QRV|QSY|QTH|QRM|QRN|QSB|QRP|QRO|QRX)")
        return if (s.length > 3 && r.replace(s, "").isEmpty()) r.findAll(s).joinToString(" ") { it.value } else s
    }

    private fun apresRapport(t: String, debut: Int): Boolean =
        MOT_SIMPLE.findAll(t.substring(0, debut)).toList().takeLast(3).any { FiltrePhonie.normalise(it.value) in AnalysePhonie.RAPPORT }

    // ------------------------------------------------------------------ 5. the usual words' writing

    /** Words written in capitals (or so) by hams, any case heard. */
    private val FORMES: List<Pair<Regex, String>> = (listOf("CQ", "DX", "DXCC", "SSTV", "APRS", "SSB", "BLU", "CW", "RST", "CTCSS", "SDR",
        "FT8", "FT4", "SOTA", "POTA", "WWFF", "IOTA") .map { it to it } +
        listOf("pile up" to "pile-up", "pileup" to "pile-up", "rodger" to "roger", "qo 100" to "QO-100", "qo100" to "QO-100",
            "q o 100" to "QO-100", "qo cent" to "QO-100", "qo one hundred" to "QO-100", "qo cien" to "QO-100"))
        .map { (de, vers) -> motif(de) to vers }

    /** A Q code in any case. */
    private val CODE_Q = Regex(AVANT + "([Qq][A-Za-z]{2})" + APRES)
    /** A satellite written « AO 91 », « RS 44 », « SO-50 » (capitals only: « so 50 » is English). */
    private val SATELLITE = Regex(AVANT + "(AO|SO|FO|RS|IO|PO|LO|TO|HO|JO|XW|CAS|EO|UO|DO|NO|MO|GO)[\\s\\-]?(\\d{1,3})" + APRES)
    /** A callsign written in small letters (« f5sim »). */
    private val MINUSCULES = Regex(AVANT + "([a-z0-9/]*[0-9][a-z0-9/]*)" + APRES)

    private fun formes(t: String, mots: List<String>): String {
        var x = FORMES.fold(t) { y, (r, v) -> r.replace(y, v) }
        x = CODE_Q.replace(x) { m -> m.value.uppercase().takeIf { it in CODES_Q } ?: m.value }
        x = SATELLITE.replace(x) { m -> m.groupValues[1] + "-" + m.groupValues[2] }
        x = MINUSCULES.replace(x) { m -> m.value.uppercase().takeIf { m.value.any { it.isLetter() } && AnalysePhonie.plausible(it) } ?: m.value }
        // The user's words as the user writes them.
        for (mot in mots) if (mot.any { it.isLetterOrDigit() }) x = runCatching { motif(mot).replace(x, Regex.escapeReplacement(mot)) }.getOrDefault(x)
        return x
    }

    // ------------------------------------------------------------------ tables

    private const val AVANT = "(?<![\\p{L}\\p{Nd}])"
    private const val APRES = "(?![\\p{L}\\p{Nd}])"
    const val MAX_MOTS = 100
    const val MAX_CORRECTIONS = 500
    /** Characters: Whisper keeps half its 448 tokens' window for what comes before. */
    const val MAX_PROMPT = 600
    /** A callsign of the station not in the prefixes' table (a special one): still spelt. */
    private val CORPS_LIBRE = Regex("^[A-Z0-9]{1,3}[0-9][A-Z]{1,4}(/[A-Z0-9]{1,4})?$")

    private val NOMS_OTAN = listOf("Alfa", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot", "Golf", "Hotel", "India", "Juliett", "Kilo",
        "Lima", "Mike", "November", "Oscar", "Papa", "Quebec", "Romeo", "Sierra", "Tango", "Uniform", "Victor", "Whiskey", "X-ray",
        "Yankee", "Zulu")
    private val CHIFFRES_DITS = mapOf(
        "en" to listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "niner"),
        "fr" to listOf("zéro", "un", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf"),
        "es" to listOf("cero", "uno", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve"),
        "de" to listOf("null", "eins", "zwo", "drei", "vier", "fünf", "sechs", "sieben", "acht", "neun"),
        "it" to listOf("zero", "uno", "due", "tre", "quattro", "cinque", "sei", "sette", "otto", "nove"),
    )
}
