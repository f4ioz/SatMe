/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.clavier

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The text a PSK31 or RTTY decoder writes, in lines stamped with the UTC
 * time they began: a new line at a line feed, past [LARGEUR] characters
 * (at a space when there is one), or after [SILENCE_MS] of nothing.
 * Thread-safe; the decoder writes, the page reads.
 */
class JournalClavier(private val horloge: () -> Long = System::currentTimeMillis) {

    data class Ligne(val t: Long, val texte: String)

    private val lignes = ArrayDeque<Ligne>()
    private val courante = StringBuilder()
    private var debut = 0L
    private var dernierMs = 0L

    /** Bumps with every character: the page knows to draw again. */
    @Volatile var version = 0L
        private set

    @Synchronized fun ajoute(s: String) {
        val now = horloge()
        if (courante.isNotEmpty() && now - dernierMs > SILENCE_MS) coupe()
        for (ch in s) {
            if (ch == '\n') { if (courante.isNotBlank()) coupe() else courante.setLength(0); continue }
            if (courante.isEmpty()) { if (ch == ' ') continue; debut = now }
            courante.append(ch)
            if (courante.length >= LARGEUR) {
                val k = courante.lastIndexOf(" ")
                if (k > LARGEUR / 2) {
                    val reste = courante.substring(k + 1)
                    courante.setLength(k); coupe(); courante.append(reste); debut = now
                } else coupe()
            }
        }
        dernierMs = now
        version++
    }

    private fun coupe() {
        val t = courante.toString().trimEnd()
        if (t.isNotEmpty()) { lignes.addLast(Ligne(debut, t)); while (lignes.size > MAX_LIGNES) lignes.removeFirst() }
        courante.setLength(0)
    }

    /** Every line, the one in progress last. */
    @Synchronized fun lignes(): List<Ligne> =
        if (courante.isEmpty()) lignes.toList() else lignes.toList() + Ligne(debut, courante.toString())

    /** The last [n] characters, on one line (for a channel's summary). */
    @Synchronized fun fin(n: Int): String = lignes().joinToString(" ") { it.texte }.takeLast(n).trim()

    @Synchronized fun efface() { lignes.clear(); courante.setLength(0); version++ }

    /** All of it as a text file: « HH:MM:SS  text » per line, UTC. */
    fun texte(): String {
        val f = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return lignes().joinToString("") { f.format(Date(it.t)) + "Z  " + it.texte + "\n" }
    }

    /** The last [n] lines for the page: time, text, the callsigns in it ([moi] mine). */
    fun json(n: Int, moi: String): JSONArray = JSONArray(lignes().takeLast(n).map { l ->
        JSONObject().put("t", l.t).put("texte", l.texte)
            .put("ind", JSONArray(indicatifs(l.texte, moi).map { JSONArray().put(it.debut).put(it.fin).put(it.moi) }))
    })

    /** A callsign found in a line: [debut] until [fin] (excluded), and whether it is [moi]. */
    data class Indicatif(val debut: Int, val fin: Int, val moi: Boolean)

    companion object {
        const val LARGEUR = 80
        const val SILENCE_MS = 6_000L
        const val MAX_LIGNES = 300

        private val MOT = Regex("[A-Za-z0-9/]+")
        private val STRUCTURE = Regex("^(?:[A-Z0-9]{1,4}/)?([A-Z0-9]{0,2}[A-Z][0-9][A-Z0-9]{0,3}[A-Z])(?:/[A-Z0-9]{1,4})?$")
        private val CARRE = Regex("^[A-R]{2}[0-9]{2}(?:[A-X]{2})?$")

        /** The callsign-shaped words of [texte] (any case), [moi]'s marked. */
        fun indicatifs(texte: String, moi: String): List<Indicatif> {
            val base = moi.trim().uppercase().let { m -> STRUCTURE.find(m)?.groupValues?.get(1) ?: m }
            return MOT.findAll(texte).mapNotNull { m ->
                val w = m.value.uppercase()
                val b = STRUCTURE.find(w)?.groupValues?.get(1) ?: return@mapNotNull null
                if (CARRE.matches(w)) return@mapNotNull null
                Indicatif(m.range.first, m.range.last + 1, base.isNotEmpty() && b == base)
            }.toList()
        }
    }
}
