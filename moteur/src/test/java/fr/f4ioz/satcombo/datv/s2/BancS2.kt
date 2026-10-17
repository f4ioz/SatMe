/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv.s2

import fr.f4ioz.satcombo.datv.AnalyseTs
import fr.f4ioz.satcombo.sdr.BladerfSdr
import fr.f4ioz.satcombo.sdr.Fft
import fr.f4ioz.satcombo.sdr.PlutoSdr
import fr.f4ioz.satcombo.sdr.SourceIq
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10

/**
 * A bench by hand, off the tests (receive only): the software receiver on
 * a real SDR, one status line a second, the stream to a file; or an
 * averaged spectrum.
 *
 *   recois <pluto|bladerf> <MHz> <kS/s> <secondes> <fichier.ts> [gain dB]
 *   spectre <pluto|bladerf> <MHz> <MS/s> <secondes> [gain dB]
 */
object BancS2 {
    private fun ouvre(q: String): SourceIq = when (q) {
        "pluto" -> PlutoSdr.connecte()
        else -> BladerfSdr.ouvre("/usr/bin/bladeRF-cli")
    }

    @JvmStatic
    fun main(a: Array<String>) {
        when (a[0]) {
            "recois" -> recois(a[1], a[2].toDouble(), a[3].toInt(), a[4].toDouble(), File(a[5]), a.getOrNull(6)?.toInt())
            "spectre" -> spectre(a[1], a[2].toDouble(), a[3].toDouble(), a[4].toDouble(), a.getOrNull(5)?.toInt())
        }
        System.exit(0)
    }

    private fun recois(q: String, mhz: Double, ks: Int, secondes: Double, f: File, gain: Int?) {
        val src = ouvre(q)
        val fs = RecepteurS2.debitPour(ks * 1000)
        src.setSampleRate(fs); src.setCenterFreq(Math.round(mhz * 1e6)); src.setGain(gain?.let { it * 10 })
        val analyse = AnalyseTs()
        val sortie = f.outputStream().buffered(1 shl 20)
        val rx = RecepteurS2(fs.toDouble(), ks * 1000.0, { b, n -> sortie.write(b, 0, n); analyse.ajoute(b, n) })
        val t0 = System.currentTimeMillis()
        var dernier = t0
        var premierVerrou = -1L
        val b = ShortArray(32_768)
        var cpu = 0L
        var puissance = 0.0; var np = 0L
        while (System.currentTimeMillis() - t0 < secondes * 1000) {
            val n = src.read16(b, 500)
            if (n < 0) { println("{\"erreur\":\"plus d'échantillons\"}"); break }
            for (k in 0 until n) { val v = b[k] / 32768.0; puissance += v * v }; np += n / 2
            val c0 = System.nanoTime()
            if (n > 0) rx.echantillons(b, n)
            cpu += System.nanoTime() - c0
            val t = System.currentTimeMillis()
            val e = rx.etat()
            if (premierVerrou < 0 && e.tramesDonnees > 0) premierVerrou = t - t0
            if (t - dernier >= 1000) {
                val ts = analyse.bilan()
                println("{\"t\":${(t - t0) / 1000.0},\"etape\":${e.etape},\"mer\":${e.merDb?.let { "%.2f".format(java.util.Locale.US, it) }},\"modcod\":${e.modcod}," +
                    "\"decalage\":${Math.round(e.decalageHz)},\"rs\":${Math.round(e.rsMesure)},\"trames\":${e.tramesDonnees},\"echecs\":${e.ldpcEchecs}," +
                    "\"sautees\":${e.tramesSautees},\"paquets\":${e.paquets},\"faux\":${e.paquetsFaux},\"cc\":${ts.erreursCc},\"it\":${"%.1f".format(java.util.Locale.US, e.iterationsMoyennes)}," +
                    "\"verrou_ms\":$premierVerrou,\"cpu_frontal\":${"%.2f".format(java.util.Locale.US, cpu / 1e6 / (t - dernier))},\"niveau_dbfs\":${"%.1f".format(java.util.Locale.US, 10 * log10(puissance / np.coerceAtLeast(1) + 1e-30))}," +
                    "\"perdus\":${src.perdus}}")
                dernier = t; cpu = 0; puissance = 0.0; np = 0
            }
        }
        rx.ferme(); sortie.close(); src.close()
    }

    private fun spectre(q: String, mhz: Double, msps: Double, secondes: Double, gain: Int?) {
        val src = ouvre(q)
        val fs = (msps * 1e6).toInt()
        src.setSampleRate(fs); src.setCenterFreq(Math.round(mhz * 1e6)); src.setGain(gain?.let { it * 10 })
        val n = 4096
        val moy = DoubleArray(n)
        val re = DoubleArray(n); val im = DoubleArray(n)
        val w = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / n) }
        val b = ShortArray(2 * n)
        var blocs = 0
        val t0 = System.currentTimeMillis()
        val tampon = ShortArray(65_536); var plein = 0
        while (System.currentTimeMillis() - t0 < secondes * 1000) {
            val m = src.read16(tampon, 500)
            if (m < 0) break
            var k = 0
            while (k < m) {
                val c = minOf(m - k, b.size - plein)
                System.arraycopy(tampon, k, b, plein, c); plein += c; k += c
                if (plein == b.size) {
                    for (j in 0 until n) { re[j] = b[2 * j] * w[j]; im[j] = b[2 * j + 1] * w[j] }
                    Fft.transform(re, im)
                    for (j in 0 until n) moy[j] += re[j] * re[j] + im[j] * im[j]
                    blocs++; plein = 0
                }
            }
        }
        val db = DoubleArray(n) { j -> 10 * log10(moy[(j + n / 2) % n] / blocs.coerceAtLeast(1) + 1e-30) }
        val trie = db.sorted()
        val bruit = trie[n / 2]
        println("{\"blocs\":$blocs,\"perdus\":${src.perdus},\"bruit_db\":${"%.1f".format(java.util.Locale.US, bruit)}}")
        // Averaged by 64 bins (about fs/64 each), the strongest bin of each.
        for (g in 0 until n / 64) {
            var mx = -999.0; var arg = 0
            var s = 0.0
            for (j in g * 64 until (g + 1) * 64) { s += db[j]; if (db[j] > mx) { mx = db[j]; arg = j } }
            val f = mhz + (arg - n / 2) * msps / n
            println("%.3f MHz  moy %+.1f dB  pic %+.1f dB à %.4f MHz".format(java.util.Locale.US, mhz + ((g + 0.5) * 64 - n / 2) * msps / n, s / 64 - bruit, mx - bruit, f))
        }
        src.close()
    }
}
