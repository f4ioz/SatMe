/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.data

import org.json.JSONObject

/**
 * The contest and portable fields of a contact (POTA, WWFF, SOTA, IOTA,
 * serial numbers, exchanges), listed **once**.
 *
 * Disk, ADIF export, ADIF import, the edit form and the online log all go
 * through this table: a field added here reaches all of them, and none can
 * forget one. Each field is named by its ADIF 3.1.6 tag, which is also its
 * key on disk (inside the contact's `"x"` object): no second vocabulary.
 *
 * All empty by default; an empty field is written nowhere.
 */
object ChampsAdif {

    /**
     * One field: its ADIF tag, how to read it from a contact, how to set it.
     * [entier]: an Integer of the ADIF (STX, SRX, CQZ, ITUZ).
     */
    class Champ(
        val tag: String,
        val lit: (LogEntry) -> String,
        val pose: (LogEntry, String) -> LogEntry,
        val entier: Boolean = false,
    )

    private fun texte(tag: String, lit: (LogEntry) -> String, pose: (LogEntry, String) -> LogEntry, majuscules: Boolean = false) =
        Champ(tag, lit, { e, v -> pose(e, v.trim().let { if (majuscules) it.uppercase() else it }) })

    private fun entier(tag: String, lit: (LogEntry) -> Int?, pose: (LogEntry, Int?) -> LogEntry) =
        Champ(tag, { lit(it)?.toString().orEmpty() }, { e, v -> pose(e, v.trim().toIntOrNull()) }, entier = true)

    val TOUS: List<Champ> = listOf(
        texte("CONTEST_ID", { it.contestId }, { e, v -> e.copy(contestId = v) }),
        entier("STX", { it.stx }, { e, v -> e.copy(stx = v) }),
        texte("STX_STRING", { it.stxString }, { e, v -> e.copy(stxString = v) }),
        entier("SRX", { it.srx }, { e, v -> e.copy(srx = v) }),
        texte("SRX_STRING", { it.srxString }, { e, v -> e.copy(srxString = v) }),
        texte("STATION_CALLSIGN", { it.stationCallsign }, { e, v -> e.copy(stationCallsign = v) }, true),
        texte("OPERATOR", { it.operateur }, { e, v -> e.copy(operateur = v) }, true),
        texte("MY_SIG", { it.mySig }, { e, v -> e.copy(mySig = v) }),
        texte("MY_SIG_INFO", { it.mySigInfo }, { e, v -> e.copy(mySigInfo = v) }),
        texte("SIG", { it.sig }, { e, v -> e.copy(sig = v) }),
        texte("SIG_INFO", { it.sigInfo }, { e, v -> e.copy(sigInfo = v) }),
        texte("MY_POTA_REF", { it.myPotaRef }, { e, v -> e.copy(myPotaRef = v) }, true),
        texte("POTA_REF", { it.potaRef }, { e, v -> e.copy(potaRef = v) }, true),
        texte("MY_WWFF_REF", { it.myWwffRef }, { e, v -> e.copy(myWwffRef = v) }, true),
        texte("WWFF_REF", { it.wwffRef }, { e, v -> e.copy(wwffRef = v) }, true),
        texte("MY_SOTA_REF", { it.mySotaRef }, { e, v -> e.copy(mySotaRef = v) }, true),
        texte("SOTA_REF", { it.sotaRef }, { e, v -> e.copy(sotaRef = v) }, true),
        texte("MY_IOTA", { it.myIota }, { e, v -> e.copy(myIota = v) }, true),
        texte("IOTA", { it.iota }, { e, v -> e.copy(iota = v) }, true),
        entier("CQZ", { it.cqz }, { e, v -> e.copy(cqz = v) }),
        entier("ITUZ", { it.ituz }, { e, v -> e.copy(ituz = v) }),
        texte("PRECEDENCE", { it.precedence }, { e, v -> e.copy(precedence = v) }, true),
        texte("CHECK", { it.check }, { e, v -> e.copy(check = v) }),
        texte("CLASS", { it.classe }, { e, v -> e.copy(classe = v) }, true),
        texte("ARRL_SECT", { it.arrlSect }, { e, v -> e.copy(arrlSect = v) }, true),
        texte("STATE", { it.state }, { e, v -> e.copy(state = v) }, true),
        texte("CNTY", { it.cnty }, { e, v -> e.copy(cnty = v) }),
        texte("MY_STATE", { it.myState }, { e, v -> e.copy(myState = v) }, true),
    )

    private val PAR_TAG = TOUS.associateBy { it.tag }

    val TAGS: Set<String> = PAR_TAG.keys

    /** The fields set on [e], in table order (tag → value). */
    fun valeurs(e: LogEntry): Map<String, String> =
        TOUS.mapNotNull { c -> c.lit(e).takeIf { it.isNotBlank() }?.let { c.tag to it } }.toMap(LinkedHashMap())

    /**
     * [e] with the fields of [m] set (unknown tags ignored; a blank value
     * clears the field). An Integer field given something that is not one
     * (an STX of "5NN-001") is not lost: it goes to its _STRING twin when
     * that one is empty.
     */
    fun avec(e: LogEntry, m: Map<String, String>): LogEntry {
        var r = e
        m.forEach { (k, v) ->
            val c = PAR_TAG[k.uppercase()] ?: return@forEach
            r = c.pose(r, v)
            if (c.entier && v.isNotBlank() && c.lit(r).isEmpty()) {
                val jumeau = PAR_TAG[c.tag + "_STRING"]
                if (jumeau != null && jumeau.lit(r).isEmpty() && !m.containsKey(jumeau.tag)) r = jumeau.pose(r, v)
            }
        }
        return r
    }

    /** All of [source]'s fields onto [cible] (an edit that carries them). */
    fun reprend(source: LogEntry, cible: LogEntry): LogEntry =
        avec(cible, TOUS.associate { it.tag to it.lit(source) })

    /** The fields set, as the `"x"` object on disk; null when none is (nothing written). */
    fun json(e: LogEntry): JSONObject? {
        val v = valeurs(e)
        if (v.isEmpty()) return null
        val o = JSONObject()
        v.forEach { (k, s) -> if (PAR_TAG.getValue(k).entier) o.put(k, s.toInt()) else o.put(k, s) }
        return o
    }

    /** [e] with the fields of an `"x"` object read back. */
    fun deJson(e: LogEntry, o: JSONObject): LogEntry =
        avec(e, o.keys().asSequence().associateWith { o.optString(it) })

    /** The ADIF fields of a record (tag → value) that belong here, for the import. */
    fun deAdif(champs: Map<String, String>): Map<String, String> =
        champs.filterKeys { it in TAGS }.mapValues { it.value.trim() }.filterValues { it.isNotEmpty() }

    // ------------------------------------------------------------------ formats

    /** POTA: `xxxx-nnnnn[@yyyyyy]` — program of 1 to 4 characters, 4 or 5 digits, optional ISO 3166-2 location. */
    private val POTA = Regex("^[A-Z0-9]{1,4}-[0-9]{4,5}(@[A-Z]{2}-[A-Z0-9]{1,3})?$")

    /** WWFF: `xxFF-nnnn` — program of 1 to 4 characters, "FF-", 4 digits. */
    private val WWFF = Regex("^[A-Z0-9]{1,4}FF-[0-9]{4}$")

    /** SOTA: association (prefix, maybe with a subdivision) "/" region "-" number: `F/AB-123`, `W2/WE-003`. */
    private val SOTA = Regex("^[A-Z0-9]{1,4}/[A-Z0-9]{2}-[0-9]{3}$")

    /** IOTA: `CC-nnn`, a continent and three digits 001–999. */
    private val IOTA = Regex("^(AF|AN|AS|EU|NA|OC|SA)-(?!000)[0-9]{3}$")

    /** Each reference of a POTA list ("K-0817,K-4566@US-WY") is valid. */
    fun potaValide(s: String): Boolean =
        s.split(',').map { it.trim().uppercase() }.let { l -> l.isNotEmpty() && l.all { POTA.matches(it) } }

    fun wwffValide(s: String): Boolean = WWFF.matches(s.trim().uppercase())
    fun sotaValide(s: String): Boolean = SOTA.matches(s.trim().uppercase())
    fun iotaValide(s: String): Boolean = IOTA.matches(s.trim().uppercase())

    /** A POTA reference (or list) found in free text: a park typed as the activation's name. */
    fun potaDans(texte: String): String =
        Regex("\\b[A-Z0-9]{1,4}-[0-9]{4,5}(@[A-Z]{2}-[A-Z0-9]{1,3})?\\b").findAll(texte.uppercase())
            .map { it.value }.distinct().joinToString(",")

    /**
     * The contacts of a POTA activation of [refs]: MY_POTA_REF, MY_SIG=POTA,
     * MY_SIG_INFO, on each contact that does not already say its own
     * (one logged with another park keeps it). [refs] blank: unchanged.
     */
    fun enActivationPota(entries: List<LogEntry>, refs: String): List<LogEntry> {
        val r = refs.trim().uppercase()
        if (r.isEmpty()) return entries
        return entries.map { e ->
            if (e.myPotaRef.isNotBlank()) e
            else e.copy(myPotaRef = r,
                mySig = e.mySig.ifBlank { "POTA" },
                mySigInfo = if (e.mySig.isBlank() || e.mySig.equals("POTA", true)) e.mySigInfo.ifBlank { r } else e.mySigInfo)
        }
    }
}
