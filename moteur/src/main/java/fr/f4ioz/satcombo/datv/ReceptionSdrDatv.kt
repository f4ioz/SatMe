/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv

import fr.f4ioz.satcombo.datv.s2.EtatS2
import fr.f4ioz.satcombo.datv.s2.RecepteurS2
import fr.f4ioz.satcombo.sdr.SourceIq

/**
 * DATV received by an SDR (ADALM-PLUTO, bladeRF) and SatMe's own DVB-S2
 * receiver ([RecepteurS2]) instead of a MiniTiouner: the SDR tuned to the
 * intermediate frequency at a rate fit for the symbol rate, its samples
 * read on the caller's thread, the transport stream to [sortieTs], the
 * status in the MiniTiouner's words ([EtatDatv]) so the DATV tab works
 * the same.
 */
class ReceptionSdrDatv(
    private val source: SourceIq,
    val reglage: ReglageDatv,
    sortieTs: (ByteArray) -> Unit,
    fils: Int = RecepteurS2.filsParDefaut(),
) {
    val fs = RecepteurS2.debitPour(reglage.srKs * 1000)
    val recepteur = RecepteurS2(fs.toDouble(), reglage.srKs * 1000.0, { b, n -> sortieTs(b.copyOf(n)) }, fils)

    /** Why the SDR stopped giving samples, when it did. */
    @Volatile var erreur: String? = null; private set

    /** The SDR set: rate, the intermediate frequency, its AGC. */
    fun regle() {
        source.setSampleRate(fs)
        source.setCenterFreq(reglage.fiKhz * 1000L)
        source.setGain(null)
    }

    /** Reads and demodulates while [continuer] holds (a blocking loop); false when the SDR went away. */
    fun boucle(continuer: () -> Boolean): Boolean {
        val b = ShortArray(32_768)
        while (continuer()) {
            val n = source.read16(b, 500)
            if (n < 0) { erreur = "le récepteur ne donne plus d'échantillons"; return false }
            if (n > 0) recepteur.echantillons(b, n)
        }
        return true
    }

    fun ferme() { recepteur.ferme() }

    fun etatS2(): EtatS2 = recepteur.etat()

    /** The constellation's last points as the MiniTiouner gives them (signed bytes, ±90 for a unit symbol). */
    fun points(): List<Pair<Int, Int>> {
        val p = recepteur.points()
        return (0 until p.size / 2).map { k -> (p[2 * k] * 90).toInt().coerceIn(-127, 127) to (p[2 * k + 1] * 90).toInt().coerceIn(-127, 127) }
    }

    companion object {
        /** [s] in the MiniTiouner's terms, on top of [ici] (what this side keeps: rate, recording…). */
        fun etatDatv(s: EtatS2, ici: EtatDatv, r: ReglageDatv): EtatDatv = ici.copy(
            etat = when (s.etape) { 2 -> 4; 1 -> 2; else -> 1 },
            fiTrouveeKhz = if (s.etape == 2) r.fiKhz + Math.round(s.decalageHz / 1000.0) else 0L,
            srTrouveKs = if (s.etape == 2) Math.round(s.rsMesure / 1000.0).toInt() else 0,
            merDb = if (s.etape == 2) s.merDb?.let { Math.round(it * 10) / 10.0 } else null,
            modcod = if (s.etape == 2) Datv.modcod(s.modcod, 4) else "",
            trames = s.courtes, pilotes = s.pilotes,
            ldpc = (s.ldpcEchecs and 0xFFFF).toInt(), ldpcTotal = s.ldpcEchecs,
            bch = (s.bchCorriges and 0xF).toInt(), bchTotal = s.bchCorriges,
            bchNonCorrige = s.ldpcEchecs > 0 && s.etape == 2 && ici.ldpcTotal != s.ldpcEchecs)

        /** Why [r] cannot be received by an SDR, or null. */
        fun refus(r: ReglageDatv): String? = when {
            r.fiKhz !in Datv.FI_SDR_MIN_KHZ..Datv.FI_SDR_MAX_KHZ ->
                "fréquence intermédiaire hors de portée du récepteur : %.3f MHz (ciel %.3f − OL %.3f), il faut 70 à 6000 MHz".format(
                    java.util.Locale.FRANCE, r.fiKhz / 1000.0, r.cielKhz / 1000.0, r.olKhz / 1000.0)
            r.srKs !in 33..2000 -> "débit de symboles hors de portée du démodulateur logiciel : ${r.srKs} kS/s (33 à 2000)"
            else -> null
        }
    }
}
