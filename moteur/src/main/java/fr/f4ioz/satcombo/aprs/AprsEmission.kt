/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.aprs

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/**
 * What the rig must offer to send one APRS frame: read back where it is,
 * key and unkey. The IC-9700 over CI-V in the app; a fake in the tests.
 */
interface PosteAprs {
    suspend fun frequence(): Long?
    /** CI-V mode byte (0x05 = FM; FM-D reads as FM). */
    suspend fun mode(): Int?
    /** Satellite mode on: the rig would transmit on SUB, not where we read. */
    suspend fun modeSatellite(): Boolean?
    suspend fun emission(on: Boolean): Boolean
    suspend fun enEmission(): Boolean?
}

/**
 * Building APRS frames and sending one, carefully.
 *
 * A transmission goes out only if: a callsign is set; the rig is in FM,
 * outside satellite mode, on the ISS digipeater (145.825 MHz give or take
 * the Doppler) or on terrestrial APRS 144.800 MHz; it is not already
 * transmitting; and the last frame is at least [ECART_MIN_MS] old. Then:
 * key, the audio, unkey — and unkey again, checked, whatever happened.
 */
object AprsEmission {

    /** Destination = software identifier; "APZ" is the experimental block. */
    const val TOCALL = "APZSME"

    /** 145.825 MHz ± 10 kHz (ISS Doppler at 2 m is ±3.5 kHz), and 144.800 MHz ± 10 kHz. */
    val FENETRES: List<LongRange> = listOf(145_815_000L..145_835_000L, 144_790_000L..144_810_000L)

    /** One frame per 20 s at most: the ISS digipeater is shared by a whole continent. */
    const val ECART_MIN_MS = 20_000L

    /** An APRS message line is at most 67 characters. */
    const val LONGUEUR_MESSAGE = 67

    /** APRS text in plain ASCII: accents taken off, the reserved | ~ { removed. */
    fun ascii(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
            .map { if (it.code in 32..126 && it !in "|~{") it else ' ' }.joinToString("").trim()

    fun message(destinataire: String, texte: String, id: String?): String =
        ":" + ascii(destinataire).uppercase().padEnd(9).take(9) + ":" +
            ascii(texte).take(LONGUEUR_MESSAGE) + (id?.let { "{$it" } ?: "")

    fun statut(texte: String): String = ">" + ascii(texte).take(62)

    /** "=4852.55N/00219.00E-comment": position without timestamp, messaging capable. */
    fun position(lat: Double, lon: Double, symbole: String, commentaire: String): String {
        fun dm(v: Double, deg: Int) = abs(v).let { a ->
            val d = a.toInt(); val m = (a - d) * 60
            "%0${deg}d%05.2f".format(Locale.US, d, if (m >= 59.995) 59.99 else m)
        }
        val table = symbole.getOrElse(0) { '/' }; val code = symbole.getOrElse(1) { '-' }
        return "=" + dm(lat, 2) + (if (lat < 0) "S" else "N") + table +
            dm(lon, 3) + (if (lon < 0) "W" else "E") + code + ascii(commentaire).take(43)
    }

    fun trame(source: String, chemin: List<String>, info: String): Trame =
        Trame(Adresse(TOCALL), Adresse.de(source), chemin.filter { it.isNotBlank() }.map { Adresse.de(it) },
            info.toByteArray(Charsets.ISO_8859_1))

    /** Why not transmit now, or null when everything allows it. */
    fun refus(indicatif: String, maintenantMs: Long, derniereMs: Long,
              frequenceHz: Long?, mode: Int?, satellite: Boolean?, enEmission: Boolean?): String? = when {
        indicatif.isBlank() || indicatif.uppercase().startsWith("N0CALL") -> "indicatif"
        maintenantMs - derniereMs < ECART_MIN_MS -> "ecart"
        frequenceHz == null || mode == null -> "poste_muet"
        satellite == true -> "mode_satellite"
        mode != 0x05 -> "mode"
        FENETRES.none { frequenceHz in it } -> "frequence"
        enEmission == true -> "deja_en_emission"
        else -> null
    }

    /**
     * Keys, lets [joue] send the audio (it returns once played), unkeys.
     * Unkeying runs even if the audio fails or the coroutine is cancelled,
     * and is checked by reading the state back, up to three times.
     *
     * @return null when sent, else a reason key.
     */
    suspend fun emet(poste: PosteAprs, dureeAudioMs: Long, joue: suspend () -> Boolean): String? {
        if (!poste.emission(true)) {
            withContext(NonCancellable) { poste.emission(false) }
            return "ptt_refuse"
        }
        var joueOk = false
        try {
            delay(150)  // the rig settles on transmit; the frame starts with ~270 ms of flags anyway
            joueOk = withTimeoutOrNull(dureeAudioMs + 3_000) { joue() } ?: false
            delay(60)
        } finally {
            withContext(NonCancellable) {
                repeat(3) {
                    poste.emission(false)
                    delay(100)
                    if (poste.enEmission() != true) return@withContext
                }
            }
        }
        if (poste.enEmission() == true) return "reste_en_emission"
        return if (joueOk) null else "audio"
    }
}
