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
class AprsPc(private val dossier: File, private val satellite: () -> String, private val horloge: () -> Long = System::currentTimeMillis) {

    @Volatile var paquets: List<Paquet> = emptyList()
        private set
    @Volatile private var demod: AfskDemodulateur? = null
    @Volatile private var rateDemod = 0
    /** Frames since the start: the page's "new" counter. */
    @Volatile var recues = 0
        private set

    private val jour = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    init { charge() }

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

    /** A block of the card's sound. */
    fun ecoute(pcm: ShortArray, n: Int, rate: Int) {
        val d = demod?.takeIf { rateDemod == rate } ?: AfskDemodulateur(rate) { t -> ajoute(t, horloge()) }.also { demod = it; rateDemod = rate }
        runCatching { d.traite(pcm, n) }
    }

    @Synchronized fun ajoute(t: Trame, quand: Long): Boolean {
        if (paquets.any { it.trame == t && kotlin.math.abs(it.quand - quand) < 5_000 }) return false
        val p = Aprs.lit(t, quand)
        runCatching {
            dossier.mkdirs()
            File(dossier, jour.format(Date(quand)) + ".txt").appendText(
                "$quand\t${satellite().replace('\t', ' ')}\t" + Base64.getEncoder().encodeToString(Ax25.encode(t)) + "\n")
        }
        paquets = (listOf(p) + paquets).take(MAX)
        recues++
        return true
    }

    /** A frame this station has just sent: shown and kept with the others, marked as sent (as on the phone). */
    @Synchronized fun ajouteEmis(t: Trame, quand: Long) {
        val p = Aprs.lit(t, quand).copy(emis = true)
        runCatching {
            dossier.mkdirs()
            File(dossier, jour.format(Date(quand)) + ".txt").appendText("$quand\t$EMIS\t" + Base64.getEncoder().encodeToString(Ax25.encode(t)) + "\n")
        }
        paquets = (listOf(p) + paquets).take(MAX)
    }

    companion object {
        const val MAX = 2000
        /** In the file's satellite column: this line was sent, not heard (the phone's mark). */
        const val EMIS = "↑"
    }
}
