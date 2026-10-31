/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import fr.f4ioz.satcombo.data.Adif
import fr.f4ioz.satcombo.data.LogEntry
import org.json.JSONArray
import org.json.JSONObject

/**
 * The log checked before its ADIF leaves: what LoTW, eQSL or Wavelog would
 * refuse, and what merely looks wrong.
 *
 * Those services reject a contact, or a whole file, without saying which line
 * was at fault. Better to say it here, contact by contact, in plain French,
 * where the operator can fix it with one click.
 *
 * Pure: the contacts and the time in, the anomalies out. The export itself is
 * not changed by this check (it already leaves out what is malformed); the
 * check says what was left out and why.
 */
object ControleAdif {

    enum class Gravite {
        /** LoTW, eQSL or Wavelog would refuse the contact. */
        BLOQUANT,
        /** Exported, but probably wrong, or fixed on the way out. */
        AVERTISSEMENT
    }

    /**
     * One problem with one contact. [t] is the contact's key (its time),
     * [code] a stable word for tests and the page, [auto] true when the export
     * fixes it by itself.
     */
    data class Anomalie(
        val t: Long, val indicatif: String, val gravite: Gravite,
        val code: String, val message: String, val auto: Boolean = false,
    )

    /** Two contacts with the same station on the same satellite closer than this: one too many. */
    const val DOUBLON_MS = 120_000L

    /** A clock a little ahead is not a future date. */
    private const val AVANCE_TOLEREE_MS = 5 * 60_000L

    /**
     * A plausible amateur callsign: an optional prefix ("EA8/"), a prefix with
     * at least one digit, a suffix of letters, an optional "/P", "/M", "/QRP"…
     * Unusual but valid calls exist (special events), so a mismatch is only
     * a warning.
     */
    private val INDICATIF = Regex("^([A-Z0-9]{1,4}/)?[A-Z0-9]{1,3}[0-9]{1,4}[A-Z]{1,5}(/[A-Z0-9]{1,4})?$")

    /** What no service takes in a CALL field. */
    private val CARACTERES_INDICATIF = Regex("^[A-Z0-9/]+$")

    fun controle(entries: List<LogEntry>, maintenantMs: Long = System.currentTimeMillis()): List<Anomalie> {
        val out = ArrayList<Anomalie>()
        val tries = entries.sortedBy { it.timeMs }
        tries.forEachIndexed { i, e ->
            val call = e.callsign.trim().uppercase()
            fun a(g: Gravite, code: String, m: String, auto: Boolean = false) {
                out += Anomalie(e.timeMs, call, g, code, m, auto)
            }
            if (call.isEmpty()) {
                a(Gravite.AVERTISSEMENT, "sans-indicatif", "Sans indicatif : ce contact n'est pas exporté en ADIF.")
                return@forEachIndexed
            }
            if (!CARACTERES_INDICATIF.matches(call))
                a(Gravite.BLOQUANT, "indicatif-caracteres", "Indicatif « $call » : seuls lettres, chiffres et « / » sont acceptés.")
            else if (!INDICATIF.matches(call))
                a(Gravite.AVERTISSEMENT, "indicatif-douteux", "Indicatif « $call » : format inhabituel, à vérifier.")

            // Locators: a grid line goes to the VUCC fields by itself; a malformed one is left out.
            locator(e.myLocator, "MY_GRIDSQUARE", "MY_VUCC_GRIDS", "Votre locator", ::a)
            locator(e.theirLocator, "GRIDSQUARE", "VUCC_GRIDS", "Locator du correspondant", ::a)

            // Frequencies: no BAND, and LoTW cannot match the contact.
            if (e.uplinkMhz <= 0.0)
                a(Gravite.BLOQUANT, "montante-absente", "Fréquence montante absente : pas de BAND, LoTW refuse le contact.")
            else if (Adif.band(e.uplinkMhz).isEmpty())
                a(Gravite.BLOQUANT, "montante-hors-bande", "Fréquence montante ${mhz(e.uplinkMhz)} MHz hors des bandes amateur : pas de BAND.")
            if (e.downlinkMhz <= 0.0)
                a(Gravite.AVERTISSEMENT, "descendante-absente", "Fréquence descendante absente : pas de BAND_RX.")
            else if (Adif.band(e.downlinkMhz).isEmpty())
                a(Gravite.AVERTISSEMENT, "descendante-hors-bande", "Fréquence descendante ${mhz(e.downlinkMhz)} MHz hors des bandes amateur : pas de BAND_RX.")

            if (e.mode.isBlank())
                a(Gravite.BLOQUANT, "mode-absent", "Mode absent : LoTW et eQSL exigent MODE.")
            else if (Adif.modeOf(e.mode).first.isEmpty())
                a(Gravite.BLOQUANT, "mode-inconnu", "Mode « ${e.mode} » inconnu de l'ADIF : MODE vide à l'export.")

            val sat = e.satName.trim()
            if (sat.isNotEmpty() && sat != Adif.DATV_TERRESTRE && !NomSatellite.connuDeLotw(NomSatellite.satNameAdif(sat))) {
                val s = NomSatellite.suggestionLotw(sat)
                a(Gravite.AVERTISSEMENT, "satellite-lotw",
                    "Satellite « $sat » absent de la liste LoTW connue de SatMe" + (s?.let { " (LoTW l'appelle « $it »)" } ?: "") + ".")
            }

            val champs = listOf("nom" to e.nom, "QTH" to e.qth, "commentaire" to e.note)
                .filter { (_, v) -> v.any { it.code > 126 || (it.code < 32 && it != '\t') } }.map { it.first }
            if (champs.isNotEmpty())
                a(Gravite.AVERTISSEMENT, "non-ascii",
                    "Accents ou caractères spéciaux dans : ${champs.joinToString(", ")} (un .adi devrait rester en ASCII ; certains logiciels les déforment).")

            if (e.timeMs > maintenantMs + AVANCE_TOLEREE_MS)
                a(Gravite.AVERTISSEMENT, "date-future", "Date dans le futur : l'horloge était-elle juste ?")

            // The earlier twin, if any: the later one is the extra.
            val base = fr.f4ioz.satcombo.data.StatutsQsl.base(call)
            var j = i - 1
            while (j >= 0 && e.timeMs - tries[j].timeMs <= DOUBLON_MS) {
                val o = tries[j]
                if (fr.f4ioz.satcombo.data.StatutsQsl.base(o.callsign) == base &&
                    (o.satName.trim().equals(sat, true) || JournalPassage.memeSatellite(o.satName, sat))) {
                    val s = (e.timeMs - o.timeMs) / 1000
                    a(Gravite.AVERTISSEMENT, "doublon", "Doublon probable : même station, même satellite, $s s plus tôt.")
                    break
                }
                j--
            }
        }
        return out
    }

    private fun locator(l: String, champ: String, champGrille: String, quoi: String,
                        a: (Gravite, String, String, Boolean) -> Unit) {
        val v = l.trim()
        if (v.isEmpty()) return
        if (Adif.estUneGrille(v)) {
            val g = Adif.grilles(v)
            if (g.size == 2 || g.size == 4)
                a(Gravite.AVERTISSEMENT, "grille-dans-locator",
                    "$quoi « $v » est une ligne de carrés : corrigé à l'export ($champGrille, sans $champ).", true)
            else
                a(Gravite.AVERTISSEMENT, "grille-invalide",
                    "$quoi « $v » : une ligne de carrés doit en compter 2 ou 4 ; $champ et $champGrille omis à l'export.", false)
        } else if (!Adif.locatorValide(v))
            a(Gravite.AVERTISSEMENT, "locator-invalide", "$quoi « $v » n'est pas un locator valide : omis à l'export ($champ).", false)
    }

    private fun mhz(f: Double) = "%.3f".format(java.util.Locale.US, f)

    /** For the page: counts, then each anomaly (newest contact first). */
    fun json(l: List<Anomalie>, total: Int): JSONObject {
        val bloq = l.count { it.gravite == Gravite.BLOQUANT }
        return JSONObject().put("total", total)
            .put("bloquants", bloq).put("avertissements", l.size - bloq)
            .put("contacts", l.map { it.t }.distinct().size)
            .put("auto", l.count { it.auto })
            .put("anomalies", JSONArray(l.sortedWith(compareBy<Anomalie> { it.gravite }.thenByDescending { it.t }).map {
                JSONObject().put("t", it.t).put("c", it.indicatif).put("g", if (it.gravite == Gravite.BLOQUANT) "bloquant" else "avertissement")
                    .put("code", it.code).put("m", it.message).put("auto", it.auto)
            }))
    }
}
