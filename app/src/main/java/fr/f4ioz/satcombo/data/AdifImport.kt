/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.data

import fr.f4ioz.satcombo.domain.Indicatifs
import java.util.Calendar
import java.util.TimeZone

/**
 * Reads an ADIF file and keeps only what helps guessing.
 *
 * The "from Wavelog" half of the round trip, deliberately thin: **QSOs are
 * not imported**, only a prediction index — callsign, grid square, contact
 * count, last date. Full contacts would be a second source of truth to
 * reconcile; the reference log stays local.
 *
 * The parser is deliberately tolerant: third-party ADIF always has something
 * odd (wrong field length, chatty header, odd case). What cannot be read is
 * counted and skipped, never fatal.
 */
object AdifImport {

    /** What an import produced, and what it left out. */
    class Bilan(
        val contacts: List<Indicatifs.Contact>,
        val enregistrementsLus: Int,
        val sansIndicatif: Int,
        val sansDate: Int,
    ) {
        val retenus: Int get() = contacts.size

        /**
         * Number of **distinct callsigns**, not contacts: the only figure that
         * says what the keypad gained. 38 QSOs with one station add one entry.
         */
        val indicatifs: Int get() = contacts.distinctBy { it.indicatif }.size

        /** Dropped for lack of callsign or date: what was lost, and why. */
        val ecartes: Int get() = sansIndicatif + sansDate
    }

    /**
     * An ADIF field: `<TAG:length>value`, optional type ignored. The length is
     * authoritative, even when the value contains spaces or angle brackets.
     */
    private val CHAMP = Regex("<([A-Za-z0-9_]+):(\\d+)(?::[A-Za-z])?>", RegexOption.IGNORE_CASE)

    /** Splits a record into tag → value pairs. */
    fun champs(enregistrement: String): Map<String, String> {
        val out = HashMap<String, String>()
        var i = 0
        while (true) {
            val m = CHAMP.find(enregistrement, i) ?: break
            val nom = m.groupValues[1].uppercase()
            val longueur = m.groupValues[2].toIntOrNull() ?: 0
            val debut = m.range.last + 1
            // Length past the end of text: take what remains rather than throw.
            // A truncated callsign beats a lost import.
            val fin = (debut + longueur).coerceAtMost(enregistrement.length)
            if (longueur > 0) out[nom] = enregistrement.substring(debut, fin)
            i = fin
        }
        return out
    }

    /**
     * ADIF date and time to an instant. `QSO_DATE` has eight digits, `TIME_ON`
     * six or four. Always UTC by definition: the phone's time zone would shift
     * the whole history by an hour or two.
     */
    fun instant(date: String, heure: String): Long? {
        val d = date.trim()
        if (d.length != 8 || !d.all { it.isDigit() }) return null
        val brut = heure.trim()
        if (brut.length != 4 && brut.length != 6) return null
        if (!brut.all { it.isDigit() }) return null
        val h = brut.padEnd(6, '0')
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        c.clear()
        c.set(
            d.substring(0, 4).toInt(),
            d.substring(4, 6).toInt() - 1,
            d.substring(6, 8).toInt(),
            h.substring(0, 2).toInt(),
            h.substring(2, 4).toInt(),
            h.substring(4, 6).toInt(),
        )
        return c.timeInMillis
    }

    /**
     * Reads a whole file.
     *
     * @param satellitesSeulement keep only `PROP_MODE=SAT` contacts. True by
     *   default: the index serves during a pass, and VHF contest stations would
     *   only push down the ones you will actually hear.
     */
    fun lit(
        texte: String,
        filtre: String = fr.f4ioz.satcombo.domain.FiltreMoisson.SAT,
    ): Bilan {
        // The header ends with <EOH> when present; without one, it is all body.
        val corps = texte.split(Regex("<EOH>", RegexOption.IGNORE_CASE)).let {
            if (it.size > 1) it.drop(1).joinToString("<EOH>") else it[0]
        }

        val out = ArrayList<Indicatifs.Contact>()
        var lus = 0
        var sansIndicatif = 0
        var sansDate = 0

        corps.split(Regex("<EOR>", RegexOption.IGNORE_CASE)).forEach { bloc ->
            if (!bloc.contains('<')) return@forEach
            val f = champs(bloc)
            if (f.isEmpty()) return@forEach
            lus++

            // The operator's filter. Wavelog can only filter the satellite band
            // on its side; everything else is filtered here.
            if (!fr.f4ioz.satcombo.domain.FiltreMoisson.retient(
                    filtre, f["PROP_MODE"].orEmpty(),
                    f["MODE"].orEmpty(), f["BAND"].orEmpty())
            ) return@forEach

            val call = f["CALL"].orEmpty().trim().uppercase()
            if (call.isEmpty()) { sansIndicatif++; return@forEach }

            val quand = instant(f["QSO_DATE"].orEmpty(), f["TIME_ON"].orEmpty())
            if (quand == null) { sansDate++; return@forEach }

            out.add(
                Indicatifs.Contact(
                    indicatif = call,
                    locator = f["GRIDSQUARE"].orEmpty().trim().uppercase(),
                    quandMs = quand,
                    satellite = f["SAT_NAME"].orEmpty().trim(),
                    // Nearly every contact in a real log carries a name: the most
                    // useful field after the grid square, read at a glance mid-pass.
                    nom = f["NAME"].orEmpty().trim(),
                )
            )
        }
        return Bilan(out, lus, sansIndicatif, sansDate)
    }
}
