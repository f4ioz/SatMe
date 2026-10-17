/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

import fr.f4ioz.satcombo.cw.DecodeurCw
import fr.f4ioz.satcombo.datv.Emetteur
import fr.f4ioz.satcombo.datv.PlanInterieur
import fr.f4ioz.satcombo.domain.Qo100
import fr.f4ioz.satcombo.domain.SignalQo100
import fr.f4ioz.satcombo.sdr.ModulateurAudio
import fr.f4ioz.satcombo.sdr.ModulationAudio
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * What is sent in one SSTV transmission, as audio: the pictures one after
 * the other (VIS header each, [pauseMs] of silence between), then the
 * callsign in CW if asked ([cw], a keyed tone, 5 ms ramps). Ends by itself:
 * [invoke] gives 0 once everything is out.
 */
class ProgrammeSstv(
    private val images: List<Pair<SstvMode, IntArray>>,
    val debitAudio: Int,
    /** The text keyed at the end ("DE F4IOZ"), or null. */
    private val cw: String? = null,
    private val cwWpm: Int = 20,
    private val cwHz: Double = 800.0,
    private val pauseMs: Double = 1_000.0,
) : (ShortArray) -> Int {
    init { require(images.isNotEmpty()) }

    private val manip = cw?.takeIf { it.isNotBlank() }?.let { DecodeurCw.manipulation(it, cwWpm) }.orEmpty()
    private val spms = debitAudio / 1000.0
    private val pause = (pauseMs * spms).roundToInt()
    private val cwEchantillons = if (manip.isEmpty()) 0 else (pauseMs * spms).roundToInt() + manip.sumOf { (it.second * spms).roundToInt() }

    /** Every sample of the transmission. */
    val totalEchantillons: Long = images.sumOf { (m, _) -> SstvEncoder.Source(m, IntArray(m.width * m.height), debitAudio).totalSamples.toLong() } +
        pause.toLong() * (images.size - 1) + cwEchantillons

    val secondes: Double get() = totalEchantillons / debitAudio.toDouble()

    @Volatile var envoyes = 0L; private set
    /** The picture being sent (0-based); [images] size once in the CW. */
    @Volatile var image = 0; private set
    val progres: Double get() = if (totalEchantillons <= 0) 1.0 else (envoyes.toDouble() / totalEchantillons).coerceIn(0.0, 1.0)

    private var source: SstvEncoder.Source? = null
    private var silence = 0
    private var cwElement = 0
    private var cwDansElement = 0
    private var cwPhase = 0.0
    private var cwDebut = true

    override fun invoke(buf: ShortArray): Int {
        var o = 0
        while (o < buf.size) {
            if (silence > 0) {
                val n = minOf(silence, buf.size - o)
                buf.fill(0, o, o + n); o += n; silence -= n; continue
            }
            if (image < images.size) {
                val s = source ?: SstvEncoder.Source(images[image].first, images[image].second, debitAudio).also { source = it }
                val tmp = ShortArray(buf.size - o)
                val n = s.read(tmp)
                if (n <= 0) {
                    source = null; image++
                    if (image < images.size) silence = pause
                    continue
                }
                // The encoder's level (26 000) to full scale: full deviation in FM.
                for (k in 0 until n) buf[o + k] = (tmp[k] * PLEIN_SUR_CODEUR).roundToInt().coerceIn(-32767, 32767).toShort()
                o += n; continue
            }
            if (cwDebut && manip.isNotEmpty()) { cwDebut = false; silence = pause; continue }
            if (cwElement >= manip.size) break
            val (bas, ms) = manip[cwElement]
            val len = (ms * spms).roundToInt()
            val n = minOf(len - cwDansElement, buf.size - o)
            for (k in 0 until n) {
                val t = cwDansElement + k
                buf[o + k] = if (!bas) 0 else {
                    val r = RAMPE_MS * spms
                    val env = when {
                        t < r -> 0.5 - 0.5 * cos(PI * t / r)
                        len - t < r -> 0.5 - 0.5 * cos(PI * (len - t) / r)
                        else -> 1.0
                    }
                    cwPhase += 2 * PI * cwHz / debitAudio
                    if (cwPhase > 2 * PI) cwPhase -= 2 * PI
                    (sin(cwPhase) * env * NIVEAU_CW).roundToInt().toShort()
                }
            }
            o += n; cwDansElement += n
            if (cwDansElement >= len) { cwElement++; cwDansElement = 0 }
        }
        envoyes += o
        return o
    }

    companion object {
        const val PLEIN_SUR_CODEUR = 32_700.0 / 26_000.0
        const val NIVEAU_CW = 29_000.0
        const val RAMPE_MS = 5.0
    }
}

/**
 * The callsign written over the picture, as every transmission must carry
 * it: a dark band across the top, the callsign in white, centred, in a
 * 5×7 dot font scaled to the picture (no font needed: the phone draws it
 * the same).
 */
object IndicatifSstv {
    /** The band's height for a picture [h] high. */
    fun hauteurBande(h: Int): Int = maxOf(12, h / 10)

    /** [px] (RGB, [w]×[h]) with the band and [texte] over its top. */
    fun incruste(px: IntArray, w: Int, h: Int, texte: String): IntArray {
        require(px.size == w * h)
        val t = texte.trim().uppercase().filter { it in GLYPHES }.ifEmpty { return px.copyOf() }
        val out = px.copyOf()
        val bande = hauteurBande(h)
        for (y in 0 until bande) for (x in 0 until w) out[y * w + x] = FOND
        val e = echelle(w, h, t.length)
        val largeur = t.length * 6 * e - e
        val x0 = (w - largeur) / 2
        val y0 = (bande - 7 * e) / 2
        t.forEachIndexed { i, c ->
            val g = GLYPHES.getValue(c)
            for (r in 0 until 7) for (col in 0 until 5) if (g[r * 5 + col] == '#') {
                for (dy in 0 until e) for (dx in 0 until e) {
                    val x = x0 + i * 6 * e + col * e + dx; val y = y0 + r * e + dy
                    if (x in 0 until w && y in 0 until h) out[y * w + x] = ENCRE
                }
            }
        }
        return out
    }

    /** Dot size: the text inside the band and the width. */
    fun echelle(w: Int, h: Int, n: Int): Int = minOf((hauteurBande(h) - 2) / 7, (w - 8) / maxOf(1, n * 6)).coerceAtLeast(1)

    const val FOND = 0x101828
    const val ENCRE = 0xFFFFFF

    private fun g(vararg l: String) = l.joinToString("")
    val GLYPHES: Map<Char, String> = mapOf(
        'A' to g(".###.", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"),
        'B' to g("####.", "#...#", "#...#", "####.", "#...#", "#...#", "####."),
        'C' to g(".###.", "#...#", "#....", "#....", "#....", "#...#", ".###."),
        'D' to g("###..", "#..#.", "#...#", "#...#", "#...#", "#..#.", "###.."),
        'E' to g("#####", "#....", "#....", "####.", "#....", "#....", "#####"),
        'F' to g("#####", "#....", "#....", "####.", "#....", "#....", "#...."),
        'G' to g(".###.", "#...#", "#....", "#.###", "#...#", "#...#", ".####"),
        'H' to g("#...#", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"),
        'I' to g(".###.", "..#..", "..#..", "..#..", "..#..", "..#..", ".###."),
        'J' to g("..###", "...#.", "...#.", "...#.", "...#.", "#..#.", ".##.."),
        'K' to g("#...#", "#..#.", "#.#..", "##...", "#.#..", "#..#.", "#...#"),
        'L' to g("#....", "#....", "#....", "#....", "#....", "#....", "#####"),
        'M' to g("#...#", "##.##", "#.#.#", "#.#.#", "#...#", "#...#", "#...#"),
        'N' to g("#...#", "#...#", "##..#", "#.#.#", "#..##", "#...#", "#...#"),
        'O' to g(".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
        'P' to g("####.", "#...#", "#...#", "####.", "#....", "#....", "#...."),
        'Q' to g(".###.", "#...#", "#...#", "#...#", "#.#.#", "#..#.", ".##.#"),
        'R' to g("####.", "#...#", "#...#", "####.", "#.#..", "#..#.", "#...#"),
        'S' to g(".####", "#....", "#....", ".###.", "....#", "....#", "####."),
        'T' to g("#####", "..#..", "..#..", "..#..", "..#..", "..#..", "..#.."),
        'U' to g("#...#", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
        'V' to g("#...#", "#...#", "#...#", "#...#", "#...#", ".#.#.", "..#.."),
        'W' to g("#...#", "#...#", "#...#", "#.#.#", "#.#.#", "#.#.#", ".#.#."),
        'X' to g("#...#", "#...#", ".#.#.", "..#..", ".#.#.", "#...#", "#...#"),
        'Y' to g("#...#", "#...#", ".#.#.", "..#..", "..#..", "..#..", "..#.."),
        'Z' to g("#####", "....#", "...#.", "..#..", ".#...", "#....", "#####"),
        '0' to g(".###.", "#...#", "#..##", "#.#.#", "##..#", "#...#", ".###."),
        '1' to g("..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###."),
        '2' to g(".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####"),
        '3' to g("#####", "...#.", "..#..", "...#.", "....#", "#...#", ".###."),
        '4' to g("...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#."),
        '5' to g("#####", "#....", "####.", "....#", "....#", "#...#", ".###."),
        '6' to g("..##.", ".#...", "#....", "####.", "#...#", "#...#", ".###."),
        '7' to g("#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#..."),
        '8' to g(".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."),
        '9' to g(".###.", "#...#", "#...#", ".####", "....#", "...#.", ".##.."),
        '/' to g(".....", "....#", "...#.", "..#..", ".#...", "#....", "....."),
        '-' to g(".....", ".....", ".....", "#####", ".....", ".....", "....."),
        '.' to g(".....", ".....", ".....", ".....", ".....", ".##..", ".##.."),
        ' ' to g(".....", ".....", ".....", ".....", ".....", ".....", "....."),
    )
}

/** What the operator asks for one SSTV transmission. */
data class DemandeSstvRf(
    /** The dial frequency (the suppressed carrier in SSB, the centre in FM), Hz. */
    val frequenceHz: Long,
    val modulation: ModulationAudio,
    /** The attenuation asked (dB, ≤ 0), as the Pluto's. */
    val gainDb: Double,
    /** How long the programme lasts (s). */
    val secondes: Double,
    val indicatif: String,
    val emetteur: Emetteur,
    val deviationHz: Double = 5_000.0,
    /** The indoor test ([PlanInterieur]'s bands, −40 dB, 5 min). */
    val interieur: Boolean = false,
    /** Into a dummy load: any frequency, −40 dB. */
    val chargeFictive: Boolean = false,
    /** « vers un satellite », ticked by the operator: the satellite sub-bands allowed (QO-100 under its own rules). */
    val versSatellite: Boolean = false,
    /** The operator clicked and confirmed. */
    val confirme: Boolean = false,
)

/**
 * The rules every SSTV transmission obeys, whoever asks: an explicit
 * confirmation, a callsign, an amateur band (French allocations) with the
 * whole occupied band inside, the satellite sub-bands only when asked (the
 * ISS's 145.800 never), QO-100's narrow band only in SSB in its digital and
 * mixed segments, the indoor test and the dummy load as for DATV, a bounded
 * duration and gain. Only the ADALM-PLUTO and the bladeRF transmit.
 */
object ReglesSstvRf {
    data class Bande(val nom: String, val basHz: Long, val hautHz: Long)

    val BANDES = listOf(
        Bande("2 m", 144_000_000L, 146_000_000L),
        Bande("70 cm", 430_000_000L, 440_000_000L),
        Bande("23 cm", 1_240_000_000L, 1_300_000_000L),
        Bande("13 cm", 2_300_000_000L, 2_450_000_000L))

    /** Satellite sub-bands: only with « vers un satellite ». */
    val SATELLITES = listOf(
        Bande("2 m satellites", 145_800_000L, 146_000_000L),
        Bande("70 cm satellites", 435_000_000L, 438_000_000L),
        Bande("23 cm satellites", 1_260_000_000L, 1_270_000_000L),
        Bande("13 cm satellites (QO-100)", 2_400_000_000L, 2_450_000_000L))

    /** The ISS's downlink and its channel: never transmitted on. */
    const val ISS_HZ = 145_800_000L
    const val ISS_DEMI_CANAL_HZ = 12_500L

    const val GAIN_INTERIEUR_DB = PlanInterieur.GAIN_PLUTO_DB
    const val GAIN_CHARGE_DB = -40.0
    const val DUREE_INTERIEUR_MAX_S = 300
    const val DUREE_MAX_S = 900
    /** Kept beyond the programme before the transmitter stops by itself. */
    const val MARGE_S = 15

    /** The occupied band (Hz, low and high edges) for [d]. */
    fun occupe(d: DemandeSstvRf): Pair<Long, Long> {
        val (a, b) = ModulateurAudio.bandeOccupee(d.modulation, d.deviationHz)
        return (d.frequenceHz + a.toLong()) to (d.frequenceHz + b.toLong())
    }

    fun indicatifValide(ind: String): Boolean {
        val i = ind.trim()
        return i.length >= 3 && i.any { it.isDigit() } && i.any { it.isLetter() } && i.all { it.isLetterOrDigit() || it == '/' }
    }

    /** The longest the transmitter may run (s) for [d]. */
    fun dureeMaxS(d: DemandeSstvRf): Int = if (d.interieur) DUREE_INTERIEUR_MAX_S else DUREE_MAX_S

    /** The time given to the transmitter (ms): the programme and a margin, never above the limit. */
    fun dureeEmissionMs(d: DemandeSstvRf): Long = (minOf(d.secondes + MARGE_S, dureeMaxS(d).toDouble()) * 1000).toLong()

    /** The attenuation ceiling for [d] (dB). */
    fun plafondDb(d: DemandeSstvRf, gainMaxDb: Double): Double = when {
        d.interieur -> minOf(gainMaxDb, GAIN_INTERIEUR_DB)
        d.chargeFictive -> minOf(gainMaxDb, GAIN_CHARGE_DB)
        else -> minOf(gainMaxDb, 0.0)
    }

    /** Why [d] may not be sent, or null. */
    fun refus(d: DemandeSstvRf): String? {
        val (bas, haut) = occupe(d)
        return when {
            !d.confirme -> "émission non confirmée"
            !indicatifValide(d.indicatif) -> "indicatif manquant : le donner dans Réglages › Station avant toute émission"
            d.emetteur == Emetteur.HACKRF -> "SSTV : ADALM-PLUTO ou bladeRF seulement"
            d.interieur && d.chargeFictive -> "essai en intérieur ou test sur charge : l'un ou l'autre"
            d.gainDb.isNaN() -> "gain d'émission invalide"
            d.modulation == ModulationAudio.NBFM && d.deviationHz !in setOf(2_500.0, 5_000.0) -> "excursion FM : 2,5 ou 5 kHz"
            d.secondes <= 0.0 -> "rien à émettre"
            d.secondes + 1 > dureeMaxS(d) ->
                "le programme dure %d s : %d s au plus%s".format(Locale.FRANCE, d.secondes.roundToInt(), dureeMaxS(d),
                    if (d.interieur) " en intérieur (un mode plus court, ou moins d'images)" else "")
            d.emetteur == Emetteur.BLADERF && d.frequenceHz !in 47_000_000L..6_000_000_000L -> "fréquence hors de la plage du bladeRF"
            d.emetteur == Emetteur.PLUTO && d.frequenceHz !in 70_000_000L..6_000_000_000L -> "fréquence hors de la plage de l'ADALM-PLUTO"
            d.chargeFictive -> null
            iss(bas, haut) -> "145,800 MHz (ISS) : jamais d'émission sur cette fréquence"
            d.interieur -> interieur(d, bas, haut)
            else -> bande(d, bas, haut)
        }
    }

    private fun iss(bas: Long, haut: Long) = haut > ISS_HZ - ISS_DEMI_CANAL_HZ && bas < ISS_HZ + ISS_DEMI_CANAL_HZ

    private fun satellite(bas: Long, haut: Long): Bande? = SATELLITES.firstOrNull { haut > it.basHz && bas < it.hautHz }

    private fun interieur(d: DemandeSstvRf, bas: Long, haut: Long): String? {
        if (PlanInterieur.BANDES.none { bas >= it.basKhz * 1000 && haut <= it.hautKhz * 1000 })
            return "essai en intérieur : le signal entier (%s) doit tenir dans 1240–1300 MHz (23 cm) ou 2300–2450 MHz (13 cm)".format(Locale.FRANCE, plage(bas, haut))
        satellite(bas, haut)?.let { return "essai en intérieur : pas dans la sous-bande " + it.nom + " (" + plage(it.basHz, it.hautHz) + ")" }
        if (d.versSatellite) return "essai en intérieur : jamais vers un satellite"
        return null
    }

    private fun bande(d: DemandeSstvRf, bas: Long, haut: Long): String? {
        val b = BANDES.firstOrNull { bas >= it.basHz && haut <= it.hautHz }
            ?: return "hors des bandes amateur : le signal entier (%s) doit tenir dans 144–146, 430–440, 1240–1300 ou 2300–2450 MHz".format(Locale.FRANCE, plage(bas, haut))
        val s = satellite(bas, haut) ?: return null
        if (!d.versSatellite) return "sous-bande " + s.nom + " (" + plage(s.basHz, s.hautHz) + ") : seulement en cochant « vers un satellite »"
        if (b.nom == "13 cm") return qo100(d, bas, haut)
        return null
    }

    /**
     * QO-100's narrow band: SSB (USB) only, the whole signal in one digital
     * (2.7 kHz) or mixed segment, never the emergency, broadcast or beacon
     * ones; nothing else of 2400–2450 MHz (the wide band is DATV's).
     */
    fun qo100(d: DemandeSstvRf, bas: Long, haut: Long): String? {
        if (bas < Qo100.NB.monteeBasHz || haut > Qo100.NB.monteeHautHz)
            return "13 cm satellites : seulement le transpondeur à bande étroite de QO-100 (2400,005–2400,490 MHz) ; la bande large est celle de la DATV"
        if (d.modulation != ModulationAudio.USB) return "QO-100 : SSTV en BLU haute (USB) seulement"
        val s1 = Qo100.segment(Qo100.descenteDepuisMontee(bas))
        val s2 = Qo100.segment(Qo100.descenteDepuisMontee(haut))
        if (s1 == null || s1 != s2 || !segmentSstv(s1))
            return "QO-100 : le signal entier (%s, descente %s) doit tenir dans le segment numérique 2,7 kHz (montée 2400,080–2400,150) ou le segment mixte (2400,365–2400,490) — jamais l'urgence ni les balises"
                .format(Locale.FRANCE, plage(bas, haut), plage(Qo100.descenteDepuisMontee(bas), Qo100.descenteDepuisMontee(haut)))
        return null
    }

    fun segmentSstv(s: Qo100.Segment): Boolean =
        (s.usage == Qo100.Usage.NUMERIQUE && s.largeurMaxHz >= 2_700) || s.usage == Qo100.Usage.MIXTE

    /** Is [hz] (uplink) inside QO-100's narrow band? */
    fun versQo100(hz: Long): Boolean = hz in Qo100.NB.monteeBasHz..Qo100.NB.monteeHautHz

    /** What to know before transmitting [d], or null. */
    fun avertissement(d: DemandeSstvRf): String? {
        val (bas, haut) = occupe(d)
        return when {
            d.chargeFictive -> null
            d.versSatellite && versQo100(d.frequenceHz) ->
                "QO-100 : jamais plus fort que la balise. Recevoir sa propre descente (onglet SDR sur QO-100) pour comparer ; sinon commencer très bas."
            d.interieur -> PlanInterieur.avertissement((bas + haut) / 2000, 0, 0.0)
            d.modulation == ModulationAudio.NBFM && haut <= 146_000_000L && bas >= 144_000_000L && bas < 144_500_000L ->
                "144,0–144,5 MHz : CW, BLU et modes numériques étroits ; la FM plutôt au-dessus de 144,5 MHz"
            d.modulation == ModulationAudio.NBFM && bas >= 430_000_000L && haut <= 440_000_000L && bas < 432_500_000L ->
                "430–432,5 MHz : la FM plutôt plus haut ; 432,0–432,5 est réservé aux modes étroits"
            else -> null
        }
    }

    fun plage(bas: Long, haut: Long): String =
        "%s–%s MHz".format(Locale.FRANCE, mhz(bas), mhz(haut))

    private fun mhz(hz: Long) = "%.4f".format(Locale.FRANCE, hz / 1e6).trimEnd('0').let { if (it.endsWith(',')) it + "0" else it }
}

/**
 * Our level on QO-100 against the middle beacon's, from the narrow band's
 * signal list (SNR over the noise in 2.5 kHz): the closed loop when SatMe
 * hears its own downlink.
 */
object NiveauSstvQo100 {
    data class Mesure(val nousDb: Double?, val baliseDb: Double?) {
        /** Our level minus the beacon's (dB), when both are seen. */
        val ecartDb: Double? get() = if (nousDb != null && baliseDb != null) nousDb - baliseDb else null
    }

    /** Our signal: the strongest inside our downlink band (uplink [basHz]..[hautHz]); the beacon within 2 kHz of its centre. */
    fun mesure(signaux: List<SignalQo100>, basHz: Long, hautHz: Long): Mesure {
        val b = Qo100.descenteDepuisMontee(basHz) - 300
        val h = Qo100.descenteDepuisMontee(hautHz) + 300
        val nous = signaux.filter { it.hz in b..h }.maxOfOrNull { it.snrDb }
        val balise = signaux.filter { abs(it.hz - Qo100.BALISE_MEDIANE_HZ) <= 2_000 }.maxOfOrNull { it.snrDb }
        return Mesure(nous, balise)
    }
}
