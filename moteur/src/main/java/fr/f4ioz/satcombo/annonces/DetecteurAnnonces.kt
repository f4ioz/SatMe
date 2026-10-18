/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.annonces

import fr.f4ioz.satcombo.annonces.Annonces.Evenement
import fr.f4ioz.satcombo.annonces.Annonces.Sorte

/**
 * The station seen once a second, compared with the second before: what
 * changed is what is said. The first look only sets the scene — starting
 * SatMe in the middle of a pass or of a transmission announces nothing.
 */
class DetecteurAnnonces {

    data class Passage(val satellite: String, val aosMs: Long, val losMs: Long, val elMax: Int)

    /** A decode heard: [cle] unique for it (slot + text…). */
    data class Decode(val cle: String, val appelant: String, val appele: String, val mode: String, val rapportDb: Int? = null)

    /** What the station is doing now, read from its objects. */
    data class Instantane(
        val ms: Long,
        val moi: String = "",
        /** The followed satellite's pass now or next. */
        val passage: Passage? = null,
        /** What transmits ("DATV", "SSTV", "correction I/Q"), with its sky frequency when known. */
        val emissions: Map<String, Long?> = emptyMap(),
        val leila: Boolean = false,
        val sstvGardees: Int = 0,
        val sstvMode: String = "",
        /** FT8/FT4 decodes and CW lines heard lately. */
        val decodes: List<Decode> = emptyList(),
        val datvActif: Boolean = false,
        val datvVerrou: Boolean = false,
        val datvHz: Long? = null,
        val datvMerDb: Int? = null,
        /** The callsign read in the DATV picture lately ("" none). */
        val datvIndicatif: String = "",
        /** Aircraft scatter windows open now (flight callsign or ICAO address). */
        val avions: Map<String, Int?> = emptyMap(),
        val rotorConnecte: Boolean = false,
        val rotorButee: Boolean = false,
        val rotorStatut: String = "",
        val sdrActif: Boolean = false,
        val sdrErreur: String = "",
        val molettePasHz: Long? = null,
    )

    private var avant: Instantane? = null
    /** Each pass's stage reached (1 told it comes, 2 risen); found again though its AOS moved a little (passes computed again). */
    private class Etape(val satellite: String, var aosMs: Long, var n: Int)
    private val etapes = ArrayList<Etape>()
    private var enCours: Passage? = null
    private var emissionDepuis = 0L
    private var rappelMs = 0L
    private val decodesVus = LinkedHashSet<String>()
    private val datvVus = HashMap<String, Long>()

    /** What changed since the last look, as events (all kinds: the settings choose later). */
    fun compare(i: Instantane, minutesAvant: Int, rappelMinutes: Int): List<Evenement> {
        val a = avant
        avant = i
        val l = ArrayList<Evenement>()
        val t = i.ms
        // Passes: N minutes before, AOS, LOS of the followed satellite; never one long gone.
        i.passage?.let { p ->
            val avantMs = minutesAvant * 60_000L
            val connue = etapes.firstOrNull { it.satellite == p.satellite && Math.abs(it.aosMs - p.aosMs) < 120_000L }
            val faite = connue?.n ?: when {
                a == null || a.passage?.satellite != p.satellite -> when { t >= p.aosMs -> 2; t >= p.aosMs - avantMs -> 1; else -> 0 }
                else -> 0
            }
            var e = faite
            if (e < 1 && t >= p.aosMs - avantMs && t < p.aosMs) {
                l += Evenement(Sorte.AOS_PROCHE, p.satellite, nombre = Math.max(1, Math.round((p.aosMs - t) / 60_000.0).toInt()), elevation = p.elMax)
                e = 1
            }
            if (e < 2 && t >= p.aosMs) {
                if (t - p.aosMs < 60_000L && t < p.losMs) l += Evenement(Sorte.AOS, p.satellite, elevation = p.elMax,
                    nombre = Math.round((p.losMs - p.aosMs) / 60_000.0).toInt())
                e = 2
            }
            if (e >= 2 && t < p.losMs) enCours = p
            if (connue != null) { connue.n = e; connue.aosMs = p.aosMs } else etapes += Etape(p.satellite, p.aosMs, e)
            if (etapes.size > 50) etapes.removeAt(0)
        }
        enCours?.let { p ->
            if (i.passage?.satellite != p.satellite && i.passage != null) enCours = null
            else if (t >= p.losMs) {
                if (t - p.losMs < 60_000L) l += Evenement(Sorte.LOS, p.satellite)
                enCours = null
            }
        }
        if (a == null) {
            emissionDepuis = if (i.emissions.isNotEmpty()) t else 0L; rappelMs = t
            i.decodes.forEach { decodesVus += it.cle }
            if (i.datvIndicatif.isNotBlank()) datvVus[i.datvIndicatif] = t
            return l
        }
        // Transmissions: each one starting or stopping; the reminder while any goes on.
        for ((q, hz) in i.emissions) if (q !in a.emissions) l += Evenement(Sorte.EMISSION, q, hz = hz, debut = true)
        for (q in a.emissions.keys) if (q !in i.emissions) l += Evenement(Sorte.EMISSION, q, debut = false)
        if (i.emissions.isEmpty()) emissionDepuis = 0L
        else {
            if (emissionDepuis == 0L) { emissionDepuis = t; rappelMs = t }
            if (t - rappelMs >= rappelMinutes * 60_000L) {
                rappelMs = t
                l += Evenement(Sorte.RAPPEL_EMISSION, i.emissions.keys.joinToString(", "), nombre = ((t - emissionDepuis) / 60_000L).toInt())
            }
        }
        if (i.leila && !a.leila) l += Evenement(Sorte.LEILA)
        if (i.sstvGardees > a.sstvGardees) l += Evenement(Sorte.SSTV, mode = a.sstvMode.ifBlank { i.sstvMode })
        // Our callsign decoded: each decode once.
        val moi = base(i.moi)
        for (d in i.decodes) {
            if (!decodesVus.add(d.cle)) continue
            if (moi.isNotEmpty() && base(d.appele) == moi && d.appelant.isNotBlank() && base(d.appelant) != moi)
                l += Evenement(Sorte.APPEL, d.appelant, mode = d.mode, nombre = d.rapportDb)
        }
        while (decodesVus.size > 500) decodesVus.remove(decodesVus.first())
        if (i.datvActif && a.datvActif && i.datvVerrou != a.datvVerrou)
            l += Evenement(Sorte.DATV_VERROU, debut = i.datvVerrou, hz = i.datvHz, nombre = i.datvMerDb)
        // A station read in the picture: said once, again only after 10 minutes without it.
        if (i.datvIndicatif.isNotBlank()) {
            val vu = datvVus[i.datvIndicatif]
            if (vu == null || t - vu > 600_000L) l += Evenement(Sorte.DATV_STATION, i.datvIndicatif)
            datvVus[i.datvIndicatif] = t
        }
        for ((qui, min) in i.avions) if (qui !in a.avions) l += Evenement(Sorte.AVION, qui, nombre = min)
        if (i.rotorButee && !a.rotorButee) l += Evenement(Sorte.ROTOR, texte = "Rotor en butée")
        if (a.rotorConnecte && !i.rotorConnecte) l += Evenement(Sorte.ROTOR, texte = "Rotor perdu" + if (i.rotorStatut.isNotBlank()) " : " + i.rotorStatut else "")
        if (a.sdrActif && !i.sdrActif && i.sdrErreur.isNotBlank()) l += Evenement(Sorte.SDR, texte = i.sdrErreur)
        if (i.molettePasHz != null && a.molettePasHz != null && i.molettePasHz != a.molettePasHz) l += Evenement(Sorte.MOLETTE, hz = i.molettePasHz)
        return l
    }

    companion object {
        /** A callsign without its prefix or suffix: « F4IOZ/P » → « F4IOZ », « EA8/F4IOZ » → « F4IOZ ». */
        fun base(c: String): String {
            val p = c.trim().uppercase().trim('<', '>').split('/').filter { it.isNotEmpty() }
            return p.maxByOrNull { s -> (if (s.any { it.isDigit() } && s.any { it.isLetter() }) 100 else 0) + s.length } ?: ""
        }

        /** « F4IOZ DE DL1ABC » or « F4IOZ F4IOZ DE DL1ABC » in CW text: who calls [moi], else null. */
        fun appelCw(texte: String, moi: String): String? {
            val m = base(moi)
            if (m.isEmpty()) return null
            val r = Regex("""(?:^|\s)""" + Regex.escape(m) + """(?:/\w+)?(?:\s+""" + Regex.escape(m) + """(?:/\w+)?)*\s+DE\s+([A-Z0-9/]{3,})""")
            return r.findAll(texte.uppercase()).lastOrNull()?.groupValues?.get(1)?.takeIf { base(it) != m && it.any(Char::isDigit) }
        }
    }
}
