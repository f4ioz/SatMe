/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv

/**
 * A tune request received by UDP, as text (receive-only: nothing is ever
 * answered, nothing transmitted, the LNB supply left as it is).
 *
 * - SatMe's: `FREQ=10494750;SR=1500;OL=9750000;ENTREE=B` — the sky
 *   frequency in kHz (or in MHz with a point: `10494.75`), the symbol rate
 *   in kS/s, the LNB's oscillator in kHz (optional, kept otherwise), the
 *   input A or B (optional). Keys in any case and order, `;` or new lines
 *   between them.
 * - The common one among DATV tools: `[GlobalMsg],Freq=10491500,Offset=9750000,Srate=1500,FPlug=A,…`
 *   — Freq in kHz, Offset the oscillator in kHz (0: Freq is already the
 *   intermediate frequency), Srate in kS/s; the other keys (supply, 22 kHz…)
 *   ignored.
 */
object CommandeAccord {

    /** The settings asked, or why not. */
    data class Resultat(val reglage: ReglageDatv?, val erreur: String = "")

    fun lis(texte: String, actuel: ReglageDatv): Resultat {
        val t = texte.trim().trimEnd('\u0000').trim()
        if (t.isEmpty()) return Resultat(null, "commande vide")
        if (t.length > 512) return Resultat(null, "commande trop longue")
        return if (t.startsWith("[GlobalMsg]", ignoreCase = true)) globale(t, actuel) else satme(t, actuel)
    }

    private fun paires(t: String, separateurs: Regex): Map<String, String> = t.split(separateurs)
        .mapNotNull { p -> p.indexOf('=').takeIf { it > 0 }?.let { i -> p.substring(0, i).trim().uppercase() to p.substring(i + 1).trim() } }
        .toMap()

    /** kHz from "10494750" (kHz) or "10494.75" (MHz); null when unreadable. */
    fun khz(v: String?): Long? {
        val s = v?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if ('.' in s) s.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }?.let { Math.round(it * 1000) }
        else s.toLongOrNull()?.takeIf { it > 0 }
    }

    private fun satme(t: String, actuel: ReglageDatv): Resultat {
        val p = paires(t, Regex("[;\\n\\r]+"))
        val ciel = khz(p["FREQ"]) ?: return Resultat(null, "FREQ manquante ou illisible")
        val sr = p["SR"]?.toIntOrNull() ?: return Resultat(null, "SR manquant ou illisible")
        val ol = if (p.containsKey("OL")) khz(p["OL"]) ?: return Resultat(null, "OL illisible") else actuel.olKhz
        val entree = when (p["ENTREE"]?.uppercase()) { null -> actuel.entreeB; "A" -> false; "B" -> true; else -> return Resultat(null, "ENTREE : A ou B") }
        return valide(actuel.copy(cielKhz = ciel, srKs = sr, olKhz = ol, entreeB = entree))
    }

    private fun globale(t: String, actuel: ReglageDatv): Resultat {
        val p = paires(t, Regex(","))
        val f = p["FREQ"]?.toLongOrNull()?.takeIf { it > 0 } ?: return Resultat(null, "Freq manquante ou illisible")
        val sr = p["SRATE"]?.toIntOrNull() ?: return Resultat(null, "Srate manquant ou illisible")
        val offset = p["OFFSET"]?.toLongOrNull() ?: 0L
        val (ciel, ol) = when {
            offset > 0 -> f to offset
            // No offset: Freq is the intermediate frequency, our oscillator added.
            f < 3_000_000L -> f + actuel.olKhz to actuel.olKhz
            else -> f to actuel.olKhz
        }
        val entree = when (p["FPLUG"]?.uppercase()) { "A" -> false; "B" -> true; else -> actuel.entreeB }
        return valide(actuel.copy(cielKhz = ciel, srKs = sr, olKhz = ol, entreeB = entree))
    }

    private fun valide(r: ReglageDatv): Resultat = r.refus()?.let { Resultat(null, it) } ?: Resultat(r)
}

/**
 * At most one accepted every [intervalleMs] (a retune restarts the
 * receiver), and no more than [parMinute] in a minute.
 */
class LimiteDebit(private val intervalleMs: Long = 2_000L, private val parMinute: Int = 10) {
    private val acceptes = ArrayDeque<Long>()

    @Synchronized
    fun accepte(t: Long): Boolean {
        while (acceptes.isNotEmpty() && t - acceptes.first() >= 60_000L) acceptes.removeFirst()
        if (acceptes.isNotEmpty() && t - acceptes.last() < intervalleMs) return false
        if (acceptes.size >= parMinute) return false
        acceptes.addLast(t)
        return true
    }
}
