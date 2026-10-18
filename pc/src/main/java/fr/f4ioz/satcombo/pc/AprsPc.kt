/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.aprs.AfskDemodulateur
import fr.f4ioz.satcombo.aprs.Aprs
import fr.f4ioz.satcombo.aprs.Ax25
import fr.f4ioz.satcombo.aprs.Paquet
import fr.f4ioz.satcombo.aprs.Trame
import java.io.File
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * APRS heard through the PC's sound card (AFSK 1200 baud, the engine's
 * demodulator), kept as the phone keeps it: one file per UTC day
 * (`aprs/AAAA-MM-JJ.txt`, "ms⇥satellite⇥AX.25 in base64"), the same frame
 * heard twice within 5 s kept once, the last 7 days read back at start.
 */
class AprsPc(private val dossier: File, private val satellite: () -> String, private val horloge: () -> Long = System::currentTimeMillis,
             /** Who we are for the trophies: callsign, latitude, longitude (as the phone's AprsHub.joueur). */
             private val joueur: () -> Triple<String, Double?, Double?> = { Triple("", null, null) }) {

    @Volatile var paquets: List<Paquet> = emptyList()
        private set
    @Volatile private var demod: AfskDemodulateur? = null
    @Volatile private var rateDemod = 0
    /** Frames since the start: the page's "new" counter. */
    @Volatile var recues = 0
        private set

    private val jour = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    init { charge() }

    // ------------------------------------------------------------ the game, as the phone's

    @Volatile private var _trophees: fr.f4ioz.satcombo.aprs.Trophees.Etat? = null
    /** Read (or worked out) the first time they are wanted: by then the station knows where it is. */
    @get:Synchronized var trophees: fr.f4ioz.satcombo.aprs.Trophees.Etat
        get() = _trophees ?: run { _trophees = fr.f4ioz.satcombo.aprs.Trophees.Etat(); chargeTrophees(); _trophees!! }
        private set(v) { _trophees = v }
    /** What deserves a cheer, newest first, with its time (the page shows the recent ones). */
    @Volatile var evenements: List<Pair<Long, fr.f4ioz.satcombo.aprs.Trophees.Evenement>> = emptyList()
        private set
    /** Kept apart from the 7-day history: the history going does not take the trophies away. */
    private val fichierTrophees = File(dossier.parentFile ?: dossier, "aprs-trophees.txt")
    private val pays: (String) -> String? = { c -> fr.f4ioz.satcombo.domain.Dxcc.entite(fr.f4ioz.satcombo.aprs.AprsJeu.base(c))?.nom }

    /** Read; the first time, worked out from the history already here, silently. */
    private fun chargeTrophees() {
        if (fichierTrophees.exists()) {
            trophees = runCatching { fr.f4ioz.satcombo.aprs.Trophees.lit(fichierTrophees.readText()) }.getOrDefault(trophees); return
        }
        if (paquets.isEmpty()) return
        val (moi, lat, lon) = joueur()
        var e = fr.f4ioz.satcombo.aprs.Trophees.Etat()
        val vus = ArrayList<Paquet>()
        paquets.sortedBy { it.quand }.forEach { p -> vus += p; e = fr.f4ioz.satcombo.aprs.Trophees.avec(e, p, vus, moi, lat, lon, pays).first }
        trophees = e
        runCatching { fichierTrophees.writeText(fr.f4ioz.satcombo.aprs.Trophees.ecrit(e)) }
    }

    @Synchronized private fun note(p: Paquet) {
        val (moi, lat, lon) = joueur()
        val (e, ev) = fr.f4ioz.satcombo.aprs.Trophees.avec(trophees, p, paquets, moi, lat, lon, pays)
        if (e != trophees) { trophees = e; runCatching { fichierTrophees.writeText(fr.f4ioz.satcombo.aprs.Trophees.ecrit(e)) } }
        if (ev.isNotEmpty()) evenements = (ev.map { horloge() to it } + evenements).take(50)
    }

    private fun charge() {
        val depuis = horloge() - 7 * 86_400_000L
        paquets = (dossier.listFiles { f -> f.name.endsWith(".txt") } ?: emptyArray())
            .flatMap { f -> runCatching { f.readLines() }.getOrDefault(emptyList()).mapNotNull { lit(it) } }
            .filter { it.quand >= depuis }.sortedByDescending { it.quand }.take(MAX)
    }

    private fun lit(ligne: String): Paquet? = runCatching {
        val (ms, sat, b64) = ligne.split('\t', limit = 3)
        val t = Ax25.decode(Base64.getDecoder().decode(b64)) ?: return null
        Aprs.lit(t, ms.toLong()).copy(emis = sat == EMIS)
    }.getOrNull()

    /** True while the sound is not on the air (the SDR's test generator, a recording played back). */
    @Volatile var horsAntenne: () -> Boolean = { false }
    /** Frames decoded off the air: counted only, never kept, cheered nor put in a pass's journal. */
    @Volatile var essais = 0
        private set

    /** A block of the card's sound. */
    fun ecoute(pcm: ShortArray, n: Int, rate: Int) {
        val d = demod?.takeIf { rateDemod == rate } ?: AfskDemodulateur(rate) { t ->
            if (runCatching { horsAntenne() }.getOrDefault(false)) essais++ else ajoute(t, horloge())
        }.also { demod = it; rateDemod = rate }
        runCatching { d.traite(pcm, n) }
    }

    @Synchronized fun ajoute(t: Trame, quand: Long): Boolean {
        if (paquets.any { it.trame == t && kotlin.math.abs(it.quand - quand) < 5_000 }) return false
        trophees   // read (or worked out from the history) before this frame joins it: this one is cheered
        val p = Aprs.lit(t, quand)
        runCatching {
            dossier.mkdirs()
            File(dossier, jour.format(Date(quand)) + ".txt").appendText(
                "$quand\t${satellite().replace('\t', ' ')}\t" + Base64.getEncoder().encodeToString(Ax25.encode(t)) + "\n")
        }
        paquets = (listOf(p) + paquets).take(MAX)
        recues++
        runCatching { note(p) }
        return true
    }

    /** A frame this station has just sent: shown and kept with the others, marked as sent (as on the phone). */
    @Synchronized fun ajouteEmis(t: Trame, quand: Long) {
        trophees
        val p = Aprs.lit(t, quand).copy(emis = true)
        runCatching {
            dossier.mkdirs()
            File(dossier, jour.format(Date(quand)) + ".txt").appendText("$quand\t$EMIS\t" + Base64.getEncoder().encodeToString(Ax25.encode(t)) + "\n")
        }
        paquets = (listOf(p) + paquets).take(MAX)
        runCatching { note(p) }
    }

    companion object {
        const val MAX = 2000
        /** In the file's satellite column: this line was sent, not heard (the phone's mark). */
        const val EMIS = "↑"
    }
}
