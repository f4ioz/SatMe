/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.aprs

/**
 * A receive-only iGate's rules: which frames heard on the air may go to
 * APRS-IS, and the line that goes. Written from the public description of
 * an iGate's job (aprs-is.net, « IGate details »):
 *
 * - never a frame whose path says NOGATE or RFONLY (the sender asked);
 * - never one that has already been through the Internet (TCPIP, TCPXX in
 *   the path, a q construct): it would loop back;
 * - never a general query (`?…`): it asks the stations in radio range;
 * - a third-party frame (`}…`) is unwrapped and judged again, the inner
 *   frame gated when it passes;
 * - the information field stops at the first CR or LF;
 * - the same frame (source, destination, information) once in 30 s;
 * - the path kept as heard (a frame through a satellite keeps it), with
 *   `qAR,<our call>` at its end.
 *
 * Pure: frames in, lines and refusals out. Only RF → IS: nothing here, nor
 * anywhere in SatMe, sends what APRS-IS says to the air.
 */
object IGate {

    /** Why a frame is not gated. [code] is stable (tests, the page), [texte] said to the operator. */
    enum class Refus(val code: String, val texte: String) {
        NOGATE("nogate", "NOGATE ou RFONLY dans le chemin"),
        INTERNET("internet", "déjà passé par Internet (TCPIP, TCPXX, q…)"),
        REQUETE("requete", "requête générale (?)"),
        TIERS("tiers", "trame de tiers illisible"),
        VIDE("vide", "trame vide"),
        DOUBLON("doublon", "doublon (même trame il y a moins de 30 s)"),
        HORS_LIGNE("hors-ligne", "pas connecté à APRS-IS"),
        NON_VERIFIE("non-verifie", "connexion non vérifiée (passcode refusé)"),
        FILE_PLEINE("file-pleine", "trop de trames d'un coup"),
    }

    /** A frame's header as text, for a third-party frame's inner one: source, destination, path. */
    private data class Entete(val source: String, val destination: String, val chemin: List<String>, val info: String)

    private fun entete(t: Trame) = Entete(t.source.toString(), t.destination.toString(), t.relais.map { it.toString() }, t.texte)

    /** « SRC>DST,PATH:info » read back (a third-party frame's content). */
    private fun lit(ligne: String): Entete? {
        val deux = ligne.indexOf(':')
        if (deux <= 0) return null
        val tete = ligne.substring(0, deux)
        val sup = tete.indexOf('>')
        if (sup <= 0) return null
        val source = tete.substring(0, sup)
        val reste = tete.substring(sup + 1).split(',')
        if (reste.isEmpty() || reste[0].isBlank()) return null
        val ok = Regex("^[A-Za-z0-9]{1,9}(-[A-Za-z0-9]{1,2})?\\*?$")
        if (!ok.matches(source) || reste.any { !ok.matches(it) }) return null
        return Entete(source, reste[0], reste.drop(1), ligne.substring(deux + 1))
    }

    private fun nom(a: String) = a.removeSuffix("*").substringBefore('-').uppercase()

    /** The verdict on one header; the gated header when it passes (a third-party frame: its inner one). */
    private fun juge(e: Entete, profondeur: Int = 0): Pair<Entete?, Refus?> {
        val noms = (e.chemin + e.destination).map(::nom)
        if (noms.any { it == "NOGATE" || it == "RFONLY" }) return null to Refus.NOGATE
        if (noms.any { it == "TCPIP" || it == "TCPXX" } || e.chemin.any { it.startsWith("q") }) return null to Refus.INTERNET
        val info = e.info.substringBefore('\r').substringBefore('\n')
        if (info.isEmpty()) return null to Refus.VIDE
        if (info.startsWith("?")) return null to Refus.REQUETE
        if (info.startsWith("}")) {
            if (profondeur >= 3) return null to Refus.TIERS
            val dedans = lit(info.substring(1)) ?: return null to Refus.TIERS
            return juge(dedans, profondeur + 1)
        }
        return e.copy(info = info) to null
    }

    /** Why [t] may not be gated, or null when it may (the 30 s duplicates aside: [Filtre]). */
    fun refus(t: Trame): Refus? = juge(entete(t)).second

    /**
     * The APRS-IS line for [t] heard by [igate] (« F4XYZ-10 »), without its
     * CR LF; null when the frame may not be gated.
     */
    fun ligne(t: Trame, igate: String): String? {
        val (e, r) = juge(entete(t))
        if (r != null || e == null) return null
        return buildString {
            append(e.source).append('>').append(e.destination)
            e.chemin.forEach { append(',').append(it) }
            append(",qAR,").append(igate.uppercase())
            append(':').append(e.info)
        }
    }

    /** The key of « the same frame » for the duplicates: source, destination, information (the path may differ). */
    fun cle(t: Trame): String? = juge(entete(t)).first?.let { it.source.removeSuffix("*") + ">" + it.destination + ":" + it.info }

    /** An iGate callsign as APRS-IS takes it: 3 to 9 letters and digits, an SSID of 1 or 2. */
    fun indicatifValide(c: String): Boolean = Regex("^[A-Z0-9]{3,9}(-[A-Z0-9]{1,2})?$").matches(c.trim().uppercase())

    /** A passcode as typed: 1 to 5 digits (0 to 32767). Never shown, never logged. */
    fun passcodeValide(p: String): Boolean = p.trim().let { s -> s.length in 1..5 && s.all { it.isDigit() } && s.toInt() <= 32767 }

    /** « host:port », port 14580 when left out; null when unreadable. */
    fun serveur(s: String): Pair<String, Int>? {
        val t = s.trim()
        if (t.isEmpty() || t.any { it.isWhitespace() }) return null
        val i = t.lastIndexOf(':')
        if (i < 0) return t to PORT
        val p = t.substring(i + 1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val h = t.substring(0, i).removePrefix("[").removeSuffix("]")
        return if (h.isEmpty()) null else h to p
    }

    /** The login line (without CR LF). Holds the passcode: never written anywhere but the socket. */
    fun connexion(indicatif: String, passcode: String, logiciel: String, version: String): String =
        "user ${indicatif.trim().uppercase()} pass ${passcode.trim()} vers ${logiciel.replace(' ', '-')} ${version.replace(' ', '-')}"

    /** What the server said to the login: true verified, false unverified, null not a login answer. */
    fun reponseConnexion(ligne: String): Boolean? {
        if (!ligne.startsWith("#") || !ligne.contains("logresp", ignoreCase = true)) return null
        return !ligne.contains("unverified", ignoreCase = true) && ligne.contains("verified", ignoreCase = true)
    }

    /** The server's name in its login answer (« server T2FRANCE »), or empty. */
    fun nomServeur(ligne: String): String = Regex("server\\s+(\\S+)", RegexOption.IGNORE_CASE).find(ligne)?.groupValues?.get(1).orEmpty()

    const val SERVEUR_DEFAUT = "euro.aprs2.net:14580"
    const val PORT = 14580
    /** The same frame within this is a duplicate. */
    const val DOUBLON_MS = 30_000L

    /** The 30 s duplicate filter: one per iGate. */
    class Filtre(private val dureeMs: Long = DOUBLON_MS) {
        private val vus = LinkedHashMap<String, Long>()

        /** The line to send for [t] heard at [quand], or why not. */
        @Synchronized fun passe(t: Trame, quand: Long, igate: String): Pair<String?, Refus?> {
            refus(t)?.let { return null to it }
            val cle = cle(t) ?: return null to Refus.TIERS
            val it = vus.entries.iterator()
            while (it.hasNext()) { if (quand - it.next().value >= dureeMs) it.remove() else break }
            vus[cle]?.let { if (quand - it < dureeMs) return null to Refus.DOUBLON }
            vus.remove(cle); vus[cle] = quand
            while (vus.size > 2000) vus.remove(vus.keys.first())
            return ligne(t, igate) to null
        }
    }
}
