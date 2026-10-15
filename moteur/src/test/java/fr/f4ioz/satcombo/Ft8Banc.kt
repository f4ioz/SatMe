/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.Ft4
import fr.f4ioz.satcombo.domain.Ft8
import fr.f4ioz.satcombo.domain.Ft8Signal
import fr.f4ioz.satcombo.domain.Ldpc
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The FT8 bench: whole 15 s slots built from our own generator, with the
 * truth known — several stations, real LDPC parity, SNR in 2500 Hz, time
 * offsets, overlaps, a LEO pass's Doppler slope, pure noise.
 *
 * Everything is seeded: a slot is the same on every run, so a number that
 * moves is the decoder moving.
 */
object Ft8Banc {

    /** One transmission in a slot. */
    data class Station(
        val message: BooleanArray,
        /** What the decoder must print (an unknown hashed callsign as `<...>`), or null when it must not be shown. */
        val attendu: String?,
        val frequenceHz: Double,
        val rapportDb: Double,
        /** Offset from the nominal start (0.5 s into the slot), seconds. */
        val dtS: Double = 0.0,
        /** This station's own drift, Hz/s, on top of the slot's. */
        val penteHzS: Double = 0.0,
    )

    data class Scenario(
        val nom: String,
        val stations: List<Station>,
        /** Drift shared by every station (the satellite's), Hz/s, zero at mid-slot. */
        val penteCommuneHzS: Double = 0.0,
        /** Whether the decoder is told [penteCommuneHzS] (ephemeris known). */
        val penteConnue: Boolean = false,
        val graine: Long = 1,
        val mode: Ft8Signal.Mode = Ft8Signal.FT8,
        /** Narrowband carriers (Hz) in the slot, the usual bait for false decodes. */
        val porteuses: List<Double> = emptyList(),
        /** My callsign and my partner's, for decoding with what is expected. */
        val moi: String? = null,
        val partenaire: String? = null,
        val rapportsEchanges: Boolean = false,
    ) {
        val attendus: Set<String> get() = stations.mapNotNull { it.attendu }.toSet()
    }

    /** Unit noise; signals are scaled to it. */
    private const val SIGMA = 1.0

    /** The 28-bit field of a standard callsign or of a token. */
    fun c28(indicatif: String): Long = when (indicatif) {
        "DE" -> 0L; "QRZ" -> 1L; "CQ" -> 2L
        else -> Ft8.indicatifVers28(indicatif) ?: error("indicatif non standard : $indicatif")
    }

    /** A type 1 message. [bout]: a grid, "+05"/"-12", "R-12", "RRR", "RR73", "73" or "". */
    fun message(appele: String, appelant: String, bout: String, hacheAppelant: Long? = null): BooleanArray {
        val m = BooleanArray(Ft8.BITS_MESSAGE)
        Ft8.ecritEntier(m, 0, 28, c28(appele))
        // A hashed callsign: tokens and hashes come before the callsigns.
        Ft8.ecritEntier(m, 29, 28, hacheAppelant?.let { 2_063_592L + it } ?: c28(appelant))
        var r = false
        val g15 = when {
            bout.isEmpty() -> 32_401
            bout == "RRR" -> 32_402
            bout == "RR73" -> 32_403
            bout == "73" -> 32_404
            bout.length == 4 && bout[0].isLetter() && bout[2].isDigit() ->
                (bout[0] - 'A') * 1800 + (bout[1] - 'A') * 100 + (bout[2] - '0') * 10 + (bout[3] - '0')
            else -> {
                r = bout.startsWith("R")
                32_400 + 35 + bout.removePrefix("R").toInt()
            }
        }
        if (r) m[58] = true
        Ft8.ecritEntier(m, 59, 15, g15.toLong())
        Ft8.ecritEntier(m, 74, 3, 1L)
        return m
    }

    /** What the decoder prints for a message (its own unpacking, the truth for the bench). */
    fun texte(m: BooleanArray): String? = Ft8.deplie(m)?.brut

    /** The channel tones of a message, real parity included. */
    fun tons(m: BooleanArray, mode: Ft8Signal.Mode = Ft8Signal.FT8): IntArray =
        if (mode.tons == 4) Ft4.bitsVersSymboles(Ldpc.encode(Ft4.avecControle(m)))
        else Ft8.bitsVersSymboles(Ldpc.encode(Ft8.avecControle(m)))

    /** A station sending a type 1 message. */
    fun station(appele: String, appelant: String, bout: String, f: Double, snr: Double,
                dt: Double = 0.0, pente: Double = 0.0): Station {
        val m = message(appele, appelant, bout)
        return Station(m, texte(m), f, snr, dt, pente)
    }

    /** Builds the slot's audio, at the mode's internal rate. */
    fun audio(s: Scenario): FloatArray {
        val mode = s.mode
        val dureeTranche = if (mode.tons == 4) 7.5 else 15.0
        val n = (dureeTranche * mode.cadenceHz).toInt()
        val alea = java.util.Random(s.graine)
        val sortie = FloatArray(n) { (SIGMA * alea.nextGaussian()).toFloat() }
        // Noise power over the whole band (half the rate) referred to 2500 Hz.
        val bruit2500 = SIGMA * SIGMA * 2500.0 / (mode.cadenceHz / 2.0)
        val milieuTranche = dureeTranche / 2.0
        for (st in s.stations) {
            val amplitude = sqrt(2.0 * bruit2500 * 10.0.pow(st.rapportDb / 10.0)).toFloat()
            val debut = 0.5 + st.dtS
            // The slot's drift is zero at mid-slot; the generator's at mid-transmission.
            val decalageHz = s.penteCommuneHzS * (debut + mode.dureeS / 2.0 - milieuTranche)
            val sig = Ft8Signal.synthetise(tons(st.message, mode), mode, st.frequenceHz + decalageHz,
                decalageS = debut, dureeTotaleS = dureeTranche, amplitude = amplitude,
                penteHzS = s.penteCommuneHzS + st.penteHzS)
            for (i in 0 until minOf(n, sig.size)) sortie[i] += sig[i]
        }
        for (f in s.porteuses) {
            val a = sqrt(2.0 * bruit2500 * 10.0.pow(10.0 / 10.0))
            for (i in 0 until n) sortie[i] += (a * kotlin.math.sin(2 * Math.PI * f * i / mode.cadenceHz)).toFloat()
        }
        return sortie
    }

    // ------------------------------------------------------------ corpus

    private val INDICATIFS = listOf(
        "F4IOZ", "F5RRO", "G0ABC", "DL1XYZ", "EA4AB", "ON4KST", "PA3GAN", "I2ABC",
        "OK1DEF", "SP9XYZ", "HB9ABC", "W1AW", "K9AN", "JA1XYZ", "VK2ABC", "ZL1AB",
        "LU1ABC", "PY2XYZ", "UA3ABC", "OH2BH", "SM5ABC", "LA9XYZ", "OZ1ABC", "EI2AB",
        "CT1ABC", "IK0XYZ", "S51AB", "9A2XYZ", "YO3ABC", "LZ1AB", "SV1ABC", "4X1AB")
    private val CARRES = listOf("JN18", "IN77", "JO22", "IO91", "KN45", "FN31", "EM10", "QF56", "JO65", "KP20")

    /** Reproducible messages of every kind seen on the band. */
    private fun messages(graine: Long, combien: Int): List<Triple<String, String, String>> {
        val r = java.util.Random(graine)
        val appelants = INDICATIFS.shuffled(r)
        return List(combien) { i ->
            val moi = appelants[i % appelants.size]
            val autre = appelants[(i + 7) % appelants.size]
            when (r.nextInt(6)) {
                0 -> Triple("CQ", moi, CARRES[r.nextInt(CARRES.size)])
                1 -> Triple(autre, moi, CARRES[r.nextInt(CARRES.size)])
                2 -> Triple(autre, moi, "%+03d".format(-24 + r.nextInt(30)))
                3 -> Triple(autre, moi, "R" + "%+03d".format(-24 + r.nextInt(30)))
                4 -> Triple(autre, moi, "RR73")
                else -> Triple(autre, moi, "73")
            }
        }
    }

    /** [combien] stations at SNR in [snrs], spread over the band, DT in ±[dtMax]. */
    private fun bande(graine: Long, combien: Int, snrs: ClosedFloatingPointRange<Double>,
                      dtMax: Double = 0.6, pentes: Double = 0.0,
                      basse: Double = 300.0, haute: Double = 2700.0): List<Station> {
        val r = java.util.Random(graine * 31 + 7)
        val pas = (haute - basse) / combien
        return messages(graine, combien).mapIndexed { i, (a, b, c) ->
            val f = basse + i * pas + r.nextDouble() * (pas - 60.0).coerceAtLeast(0.0)
            val snr = snrs.start + r.nextDouble() * (snrs.endInclusive - snrs.start)
            val dt = -0.4 * dtMax / 0.6 + r.nextDouble() * dtMax * (1.0 + 0.4 / 0.6)
            val p = if (pentes == 0.0) 0.0 else (r.nextDouble() * 2 - 1) * pentes
            station(a, b, c, f, snr, dt, p)
        }
    }

    /** The measurement corpus. Names are stable: the floors refer to them. */
    fun corpus(): List<Scenario> {
        val l = ArrayList<Scenario>()
        l += Scenario("forts", bande(1, 10, 0.0..12.0), graine = 101)
        l += Scenario("moyens", bande(2, 12, -14.0..-8.0), graine = 102)
        l += Scenario("faibles", bande(3, 12, -20.0..-15.0), graine = 103)
        l += Scenario("tres_faibles", bande(4, 10, -22.0..-19.0), graine = 104)
        l += Scenario("bande_chargee", bande(5, 24, -18.0..6.0, dtMax = 1.0), graine = 105)
        l += Scenario("chevauchement", listOf(
            station("CQ", "F4IOZ", "JN18", 600.0, -6.0, 0.0),
            station("F4IOZ", "G0ABC", "IO91", 625.0, -9.0, 0.4),
            station("CQ", "DL1XYZ", "JO65", 1200.0, -4.0, -0.2),
            station("DL1XYZ", "EA4AB", "-12", 1218.0, -8.0, 0.6),
            station("CQ", "OK1DEF", "JN79", 1800.0, -10.0, 0.1),
            station("OK1DEF", "SP9XYZ", "R-07", 1830.0, -11.0, -0.4),
            station("CQ", "W1AW", "FN31", 2300.0, -2.0, 0.0),
            station("W1AW", "K9AN", "EN50", 2312.0, -3.0, 0.0)), graine = 106)
        l += Scenario("fort_masque_faible", listOf(
            station("CQ", "F5RRO", "JN18", 1000.0, 12.0, 0.0),
            station("F5RRO", "PA3GAN", "JO22", 1020.0, -12.0, 0.3),
            station("CQ", "HB9ABC", "JN47", 1500.0, 15.0, -0.1),
            station("HB9ABC", "I2ABC", "-15", 1540.0, -14.0, 0.2),
            station("CQ", "OH2BH", "KP20", 2000.0, 10.0, 0.5),
            station("OH2BH", "SM5ABC", "JO89", 1960.0, -10.0, 0.0)), graine = 107)
        for ((i, p) in listOf(-1.0, 1.0, -3.0, 3.0).withIndex()) {
            l += Scenario("doppler_connu_${p}Hz/s", bande(10L + i, 8, -16.0..0.0),
                penteCommuneHzS = p, penteConnue = true, graine = 110L + i)
        }
        l += Scenario("doppler_inconnu_1Hz/s", bande(20, 8, -14.0..0.0),
            penteCommuneHzS = 1.0, graine = 120)
        l += Scenario("pentes_propres_2Hz/s", bande(21, 8, -14.0..0.0, pentes = 2.0), graine = 121)
        l += Scenario("pente_connue_et_propre", bande(22, 8, -14.0..0.0, pentes = 1.5),
            penteCommuneHzS = 2.5, penteConnue = true, graine = 122)
        run {
            val hache1 = message("CQ", "", "JN18", hacheAppelant = 1_234_567L)
            val hache2 = message("F4IOZ", "", "-10", hacheAppelant = 3_456_789L)
            l += Scenario("indicatifs_composes", listOf(
                Station(hache1, "CQ <...> JN18", 800.0, -5.0, 0.0),
                Station(hache2, "F4IOZ <...> -10", 1400.0, -8.0, 0.2),
                station("CQ", "EA4AB", "IN80", 1100.0, -6.0, 0.1),
                station("CQ", "LZ1AB", "KN22", 1900.0, -12.0, -0.2),
                station("LZ1AB", "SV1ABC", "KM18", 2200.0, -9.0, 0.0)), graine = 130)
        }
        // A QSO with a weak partner (F4IOZ works EA4AB), with stations around.
        fun qso(nom: String, bout: String, snr: Double, echanges: Boolean, montre: Boolean, graine: Long) {
            val p = station("F4IOZ", "EA4AB", bout, 1310.0, snr, 0.1)
            l += Scenario(nom, listOf(if (montre) p else p.copy(attendu = null),
                station("CQ", "G0ABC", "IO91", 700.0, -8.0, 0.0),
                station("DL1XYZ", "OK1DEF", "-07", 1900.0, -12.0, 0.3)),
                graine = graine, moi = "F4IOZ", partenaire = "EA4AB", rapportsEchanges = echanges)
        }
        qso("qso_rapport", "-23", -23.0, false, true, 150)
        qso("qso_r_rapport", "R-21", -23.5, false, true, 151)
        qso("qso_rr73", "RR73", -23.0, true, true, 152)
        // Reports not yet exchanged: a RR73 must not be read by expectation.
        qso("qso_rr73_trop_tot", "RR73", -24.0, false, false, 153)
        for (g in 1..6) l += Scenario("bruit_$g", emptyList(), graine = 200L + g,
            moi = if (g % 2 == 0) "F4IOZ" else null, partenaire = "EA4AB", rapportsEchanges = true)
        l += Scenario("bruit_porteuses", emptyList(), graine = 210, porteuses = listOf(700.0, 1503.1, 2250.0))
        l += Scenario("ft4", bande(30, 8, -14.0..0.0, dtMax = 0.3), mode = Ft8Signal.FT4, graine = 140)
        return l
    }
}
