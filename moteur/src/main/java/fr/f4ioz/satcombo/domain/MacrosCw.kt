/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import org.json.JSONArray
import org.json.JSONObject

/**
 * The CW keyer's memories, as on a contest keyer: a few texts with fields
 * filled at the moment of sending — our callsign, the other station's, our
 * locator, the report. Only text: sending one still needs the operator's
 * click and the transmitter's every guard.
 *
 * Fields: `{MOI}` our callsign, `{LUI}` the other station's (typed or
 * clicked in), `{LOC}` our locator, `{RST}` the report (599 by default).
 * Prosigns between angle brackets (`<AR>`, `<SK>`, `<BT>`, `<KN>`, `<AS>`)
 * are keyed as one character.
 */
object MacrosCw {
    data class Memoire(val nom: String, val texte: String) {
        fun json(): JSONObject = JSONObject().put("nom", nom).put("texte", texte)
    }

    /** How many memories there are (the page's buttons). */
    const val NOMBRE = 6
    const val TEXTE_MAX = 200

    val PAR_DEFAUT = listOf(
        Memoire("CQ", "CQ CQ CQ DE {MOI} {MOI} K"),
        Memoire("Réponse", "{LUI} DE {MOI} {MOI} <KN>"),
        Memoire("Rapport", "{LUI} DE {MOI} TU UR {RST} {RST} <BK>"),
        Memoire("Locator", "LOC {LOC} {LOC} <BK>"),
        Memoire("73", "{LUI} DE {MOI} TU 73 <SK>"),
        Memoire("QRZ?", "QRZ? DE {MOI} K"),
    )

    /** The prosigns accepted (keyed as one character). */
    val PROSIGNES = setOf("AR", "SK", "BT", "KN", "AS", "BK", "SN", "CT")

    /** [texte] cut down to what can be keyed: letters, digits, `/?.,=+-`, spaces and the prosigns. */
    fun nettoie(texte: String): String {
        val s = texte.uppercase()
        val b = StringBuilder()
        var i = 0
        while (i < s.length && b.length < TEXTE_MAX) {
            val c = s[i]
            if (c == '<') {
                val f = s.indexOf('>', i)
                val p = if (f > i) s.substring(i + 1, f) else ""
                // An unknown group dropped whole.
                if (f > i) { if (p in PROSIGNES) b.append('<').append(p).append('>'); i = f + 1; continue }
                i++; continue
            }
            if (c == '{') {
                // A field kept whole for [developpe].
                val f = s.indexOf('}', i)
                val p = if (f > i) s.substring(i + 1, f) else ""
                if (p in CHAMPS) { b.append('{').append(p).append('}'); i = f + 1; continue }
                i++; continue
            }
            if (c.isLetterOrDigit() && c.code < 128 || c in " /?.,=+-") b.append(c)
            i++
        }
        return b.toString().replace(Regex(" +"), " ").trim()
    }

    private val CHAMPS = setOf("MOI", "LUI", "LOC", "RST")

    /**
     * The memory [texte] ready to key: the fields filled. Null text and why
     * when a field is needed and empty (no callsign yet, no other station).
     */
    fun developpe(texte: String, moi: String, lui: String, locator: String, rst: String = "599"): Pair<String?, String?> {
        val m = indicatif(moi)
        val l = indicatif(lui)
        val loc = locator.trim().uppercase().take(6)
        val r = rst.trim().uppercase().filter { it.isLetterOrDigit() }.take(3).ifBlank { "599" }
        val t = nettoie(texte)
        if ("{MOI}" in t && m.isBlank()) return null to "indicatif de la station manquant (Réglages › Station)"
        if ("{LUI}" in t && l.isBlank()) return null to "indicatif du correspondant à saisir"
        if ("{LOC}" in t && loc.isBlank()) return null to "locator de la station manquant (Réglages › Station)"
        val s = t.replace("{MOI}", m).replace("{LUI}", l).replace("{LOC}", loc).replace("{RST}", r)
        return if (s.isBlank()) null to "mémoire vide" else s to null
    }

    /** A callsign as keyed: upper case, letters, digits and slashes. */
    fun indicatif(brut: String): String = brut.trim().uppercase().filter { it in 'A'..'Z' || it in '0'..'9' || it == '/' }.take(16)

    fun lisListe(texte: String?): List<Memoire> {
        val l = runCatching {
            val a = JSONArray(texte ?: return PAR_DEFAUT)
            (0 until minOf(a.length(), NOMBRE)).map { i ->
                val o = a.optJSONObject(i)
                Memoire(o?.optString("nom").orEmpty().trim().take(16).ifBlank { "M" + (i + 1) }, nettoie(o?.optString("texte").orEmpty()))
            }
        }.getOrNull() ?: return PAR_DEFAUT
        return l + PAR_DEFAUT.drop(l.size)
    }

    fun ecrisListe(l: List<Memoire>): String = JSONArray(l.take(NOMBRE).map { it.json() }).toString()

    /** Memory [n] (0-based) changed in [l]; the others kept. */
    fun change(l: List<Memoire>, n: Int, nom: String, texte: String): List<Memoire> {
        if (n !in 0 until NOMBRE) return l
        val m = l.toMutableList()
        while (m.size < NOMBRE) m += PAR_DEFAUT[m.size]
        m[n] = Memoire(nom.trim().take(16).ifBlank { "M" + (n + 1) }, nettoie(texte))
        return m
    }
}
