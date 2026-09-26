/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import kotlin.math.exp
import kotlin.math.ln

/**
 * Callsigns: recognise them, guess them, never refuse them.
 *
 * A busy RS-44 pass gives a contact every seventy seconds. Typing a callsign
 * on the system keyboard takes fifteen to twenty, so entries got validated
 * empty and callsigns were lost. Three characters and a suggestion: four taps.
 *
 * **Nothing here blocks an entry.** Plausibility is a colour, not a gate. The
 * callsign the format rejects is exactly the rare DX you put the antenna up
 * for, and a logbook that refuses it has missed the point.
 */
object Indicatifs {

    /** Suffixes cycled by the single suffix key. */
    val SUFFIXES: List<String> = listOf("", "/P", "/M", "/MM")

    /** Next suffix in the cycle, for the "/" key. */
    fun suffixeSuivant(actuel: String): String {
        val i = SUFFIXES.indexOf(actuel.uppercase())
        return if (i < 0) SUFFIXES[1] else SUFFIXES[(i + 1) % SUFFIXES.size]
    }

    /**
     * Splits a callsign from its operating suffix.
     *
     * Only operating suffixes are detached. `FG/F4IOZ` has a prefix, not a
     * suffix: another country, so another callsign, and merging it with
     * `F4IOZ` would mix two entities in the statistics.
     */
    fun separe(brut: String): Pair<String, String> {
        val net = brut.trim().uppercase()
        if (net.isEmpty()) return "" to ""
        // Look at the LAST slash, not the number of pieces. Requiring exactly
        // two pieces let `LA/DF2ET/P` (country prefix AND suffix) through
        // whole: the /P went unnoticed and the portable station inherited the
        // home grid square — the one square known to be wrong.
        val i = net.lastIndexOf('/')
        if (i <= 0) return net to ""
        val queue = net.substring(i)
        return if (queue in SUFFIXES) net.substring(0, i) to queue else net to ""
    }

    /**
     * Where a typed letter goes.
     *
     * Two gestures produce a slash, with opposite meanings:
     *
     * - The `/P /M` key **sets an operating suffix**. It stays at the end and
     *   later letters complete the callsign in front of it: on `F4IOZ/P`, an
     *   `X` gives `F4IOZX/P`. This lets you set portable as soon as you hear
     *   it.
     * - The `/` key **opens a country prefix**. What follows is part of the
     *   callsign and is written in the order heard.
     *
     * Both produce the same text, so only [suffixePose] can tell them apart.
     * Reading the text alone, `DL/P` looks like `DL` portable, and typing
     * `DL/PA3GAN` gave `DLA3GAN/P`.
     */
    fun ajoute(saisie: String, lettre: Char, suffixePose: Boolean): String {
        if (!suffixePose) return saisie + lettre
        // A suffix set on an empty field is alone, and `separe` returns it as
        // the base (right for text read cold). Here the key just set it, so
        // without this case you got `/PF4IOZ`.
        if (saisie.uppercase() in SUFFIXES) return lettre + saisie
        val (b, suf) = separe(saisie)
        return b + lettre + suf
    }

    fun base(brut: String): String = separe(brut).first

    fun suffixe(brut: String): String = separe(brut).second

    /**
     * The key a callsign is stored under.
     *
     * Base plus operating suffix (a country prefix stays in the base), so home
     * and /P each get their own entry. Typing `F4H` still finds `F4HRJ/P`.
     */
    fun cle(brut: String): String = separe(brut).let { it.first + it.second }

    /**
     * The prefix in the radio sense: everything up to and including the last
     * digit.
     *
     * "Letters then digits" fails on prefixes starting with a digit (9A, 2E,
     * 3DA): it returned "9" for 9A3XYZ. The last digit works in both cases.
     *
     * Used to guess the entity and judge plausibility, never to forbid.
     */
    fun prefixe(brut: String): String {
        val b = base(brut)
        val dernierChiffre = b.indexOfLast { it.isDigit() }
        return if (dernierChiffre < 0) b else b.take(dernierChiffre + 1)
    }

    /**
     * Plausible callsign shape. Deliberately loose: a stricter rule would
     * reject valid callsigns (special event calls, two-digit prefixes).
     */
    fun plausible(brut: String): Boolean {
        val b = base(brut)
        if (b.length < 3) return false
        if (!b.all { it.isLetterOrDigit() }) return false
        if (b.none { it.isDigit() } || b.none { it.isLetter() }) return false
        val dernierChiffre = b.indexOfLast { it.isDigit() }
        if (dernierChiffre == b.length - 1) return false
        return b.drop(dernierChiffre + 1).all { it.isLetter() }
    }

    /** A grid square seen for a station, with what we know about it. */
    class LocatorVu(
        val locator: String,
        val contacts: Int,
        val dernierMs: Long,
    )

    /** What memory keeps about a station (one entry per base + suffix). */
    class Connu(
        val indicatif: String,
        val contacts: Int,
        val dernierMs: Long,
        val locators: List<LocatorVu> = emptyList(),
        val dernierSat: String = "",
        /** Operator name from the imported log: faster to recognise mid-pass. */
        val nom: String = "",
    ) {
        /**
         * The grid square to suggest: **the most frequent**, date only as a
         * tie-breaker.
         *
         * It used to be the most recent, and a handful of wrong entries was
         * enough to displace an established square (F5RRO: seventeen contacts
         * in JN18FR, a few mistyped JN33AF, and JN33AF was then suggested and
         * copied into every new contact). Frequency resists accidents; a
         * station that really moves wins over time.
         */
        val locatorPrincipal: String
            get() = locators.maxWithOrNull(
                compareBy<LocatorVu> { it.contacts }.thenBy { it.dernierMs }
            )?.locator.orEmpty()
    }

    /**
     * Where the grid square in a contact came from.
     *
     * Recorded at validation and impossible to rebuild later. When a station
     * says "I wasn't in JN18", this tells whether the memory lied or the
     * typing did.
     */
    enum class OrigineLocator { SAISI, PROPOSE, INCONNU }

    /**
     * Grid square to prefill, or empty when nothing should be suggested.
     *
     * `F4HRJ/P` must **never** inherit the square of `F4HRJ`: the /P says the
     * station has moved, so inheriting would log the one square known to be
     * wrong.
     */
    fun locatorPropose(connu: Connu?, brutSaisi: String): String {
        if (connu == null) return ""
        // The entry is the one for the exact suffix: a known F5RRO/P suggests
        // its own portable square, an unseen /P suggests nothing. Home and
        // portable squares no longer leak into each other.
        return connu.locatorPrincipal
    }

    // ------------------------------------------------------------ prediction

    /** Half-life of recency, in days. */
    private const val DEMI_VIE_JOURS = 120.0

    private const val JOUR_MS = 86_400_000.0

    /**
     * Score of a suggestion; higher ranks first.
     *
     * Three terms: contact count (logarithmic — the twentieth QSO with a
     * station says less than the second), recency (exponential decay), and a
     * bonus for a station last heard on the same satellite, the best
     * short-term hint during a pass.
     */
    fun note(connu: Connu, maintenantMs: Long, satActif: String = ""): Double {
        val frequence = ln(1.0 + connu.contacts)
        val jours = ((maintenantMs - connu.dernierMs).coerceAtLeast(0L)) / JOUR_MS
        val recence = exp(-jours / DEMI_VIE_JOURS)
        val memeSat = if (satActif.isNotBlank() &&
            connu.dernierSat.equals(satActif, ignoreCase = true)) 0.6 else 0.0
        return frequence + 2.0 * recence + memeSat
    }

    /**
     * Suggestions for the text being typed.
     *
     * Three at most: beyond that the row needs reading instead of a glance.
     * Nothing under two characters — on one letter everything matches.
     */
    fun suggestions(
        saisie: String,
        memoire: List<Connu>,
        maintenantMs: Long,
        satActif: String = "",
        max: Int = 3,
    ): List<Connu> {
        val debut = base(saisie)
        if (debut.length < 2) return emptyList()
        return memoire.asSequence()
            .filter { it.indicatif.startsWith(debut) }
            .sortedWith(
                compareByDescending<Connu> { note(it, maintenantMs, satActif) }
                    .thenBy { it.indicatif }
            )
            .take(max)
            .toList()
    }

    /**
     * Characters that actually extend a known callsign.
     *
     * The keypad uses this to **highlight**, never to remove keys. A keypad
     * that hides unlikely keys forbids logging the never-worked rare DX.
     */
    fun suitesConnues(saisie: String, memoire: List<Connu>): Set<Char> {
        val debut = base(saisie)
        val out = HashSet<Char>()
        memoire.forEach { c ->
            if (c.indicatif.length > debut.length && c.indicatif.startsWith(debut)) {
                out.add(c.indicatif[debut.length])
            }
        }
        return out
    }

    /** State of a typed callsign, for the badge next to the field. */
    enum class Etat { DEJA_CONTACTE, PLAUSIBLE, INHABITUEL, VIDE }

    fun etat(saisie: String, memoire: List<Connu>): Etat {
        if (saisie.isBlank()) return Etat.VIDE
        // The green badge is about the operator, not the location: F4HRJ is
        // already worked even if only as F4HRJ/P. Memory keys carry the
        // suffix, so compare on the base.
        val b = base(saisie)
        if (memoire.any { base(it.indicatif) == b }) return Etat.DEJA_CONTACTE
        return if (plausible(saisie)) Etat.PLAUSIBLE else Etat.INHABITUEL
    }

    // ---------------------------------------------------------------- memory

    /**
     * Builds the memory from a list of contacts.
     *
     * Keeps callsign + grid square pairs, not one square per callsign: a
     * portable station changes square, and suggesting last year's is worse
     * than suggesting nothing.
     */
    fun memoire(contacts: List<Contact>): List<Connu> {
        class Acc {
            var n = 0
            var dernier = 0L
            var sat = ""
            var nom = ""
            val carres = HashMap<String, IntArray>()
            val carresDate = HashMap<String, Long>()
        }

        val map = HashMap<String, Acc>()
        contacts.forEach { c ->
            val k = cle(c.indicatif)
            if (k.isEmpty()) return@forEach
            val a = map.getOrPut(k) { Acc() }
            a.n++
            if (c.quandMs >= a.dernier) {
                a.dernier = c.quandMs
                if (c.satellite.isNotBlank()) a.sat = c.satellite
            }
            // Keep the first name seen, even on an older contact: logs don't
            // carry it on every line, and the latest often lacks it.
            if (a.nom.isBlank() && c.nom.isNotBlank()) a.nom = c.nom
            // The key carries the suffix, so F5RRO/P accumulates its own
            // squares without touching those of F5RRO.
            val g = c.locator.trim().uppercase()
            if (g.isNotEmpty()) {
                a.carres.getOrPut(g) { IntArray(1) }[0]++
                val d = a.carresDate[g] ?: 0L
                if (c.quandMs > d) a.carresDate[g] = c.quandMs
            }
        }
        return map.map { (k, a) ->
            Connu(
                indicatif = k,
                contacts = a.n,
                dernierMs = a.dernier,
                dernierSat = a.sat,
                nom = a.nom,
                locators = a.carres.map { (g, n) ->
                    LocatorVu(g, n[0], a.carresDate[g] ?: 0L)
                }.sortedByDescending { it.dernierMs },
            )
        }
    }

    /** The minimum a contact must carry to feed the memory. */
    class Contact(
        val indicatif: String,
        val locator: String,
        val quandMs: Long,
        val satellite: String = "",
        val nom: String = "",
    )
}
