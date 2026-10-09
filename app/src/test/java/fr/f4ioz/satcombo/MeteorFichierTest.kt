/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.meteor.DemodOqpsk
import fr.f4ioz.satcombo.meteor.MsuMr
import fr.f4ioz.satcombo.meteor.Paquets
import fr.f4ioz.satcombo.meteor.Trames
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A real METEOR-M2-4 pass (complex float baseband, 187.5 kS/s), when it is on
 * this computer: a .wav file in ~/SatMe-atelier/meteor. Too big for the
 * repository; skipped elsewhere.
 *
 * Environment: METEOR_DEBUT / METEOR_DUREE (seconds), METEOR_TRACE (loops and
 * packets), METEOR_DUMP (soft symbols to a file), METEOR_PNG (a folder for
 * the channels as PGM pictures).
 */
class MeteorFichierTest {

    private fun fichier(): File? = File(System.getProperty("user.home"), "SatMe-atelier/meteor")
        .listFiles { f -> f.name.endsWith(".wav") }?.sortedBy { it.name }?.firstOrNull()

    @Test
    fun un_vrai_passage_donne_des_trames() {
        val f = fichier()
        assumeTrue(f != null)
        val trace = System.getenv("METEOR_TRACE") != null
        val debutS = (System.getenv("METEOR_DEBUT") ?: "380").toDouble()
        val dureeS = (System.getenv("METEOR_DUREE") ?: "60").toDouble()
        val fs = 187_500.0
        val demod = DemodOqpsk(fs)
        if (trace) demod.trace = { println(it) }
        val msu = MsuMr()
        val vus = java.util.TreeMap<Int, Int>()
        val paquets = Paquets { apid, _, d -> vus.merge(apid, 1, Int::plus); msu.paquet(apid, d) }
        val trames = Trames { paquets.trame(it) }
        if (trace) trames.diagnostic = { println(it) }
        val dump = System.getenv("METEOR_DUMP")?.let { java.io.FileOutputStream(it) }

        val raf = RandomAccessFile(f, "r")
        val bloc = 16384
        val buf = ByteArray(bloc * 8)
        val re = FloatArray(bloc); val im = FloatArray(bloc)
        val soft = ByteArray(bloc)
        val total = (raf.length() - 44) / 8
        val debut = (debutS * fs).toLong().coerceIn(0, total)
        raf.seek(44 + debut * 8)
        var restants = minOf((dureeS * fs).toLong(), total - debut)
        var s = 0
        while (restants > 0) {
            val n = minOf(bloc.toLong(), restants).toInt()
            raf.readFully(buf, 0, n * 8)
            val bb = ByteBuffer.wrap(buf, 0, n * 8).order(ByteOrder.LITTLE_ENDIAN)
            for (k in 0 until n) { re[k] = bb.float; im[k] = bb.float }
            val m = demod.traite(re, im, n, soft)
            dump?.write(soft, 0, m)
            trames.traite(soft, m)
            restants -= n
            if (++s % 115 == 0) println("t=%.0f s  f=%.0f Hz  raie=%.0f  qualité=%.1f dB  verrou=%s  ber=%.3f  trames=%d  ratées=%d".format(
                (debut + s.toLong() * bloc) / fs, demod.frequenceHz, demod.raieClarte, demod.qualiteDb,
                trames.verrouille, trames.tauxErreurs, trames.trames, trames.tramesRatees))
        }
        dump?.close()
        println("APID $vus trous=${paquets.trous} segments=${msu.segments} ratés=${msu.segmentsRates} période=${msu.periodeEtOrigine()}")
        println("FIN trames=${trames.trames} ratées=${trames.tramesRatees} corrigés=${trames.octetsCorriges}")
        System.getenv("METEOR_PNG")?.let { dir ->
            for (apid in msu.canaux.keys) {
                val (h, px) = msu.image(apid) ?: continue
                File(dir, "canal-$apid.pgm").outputStream().use {
                    it.write("P5\n${MsuMr.LARGEUR} $h\n255\n".toByteArray())
                    it.write(px)
                }
            }
        }
    }

    /**
     * The live path: the same signal as the dongle would hand it over —
     * 1 058 400 S/s, unsigned bytes, 30 kHz off centre — through the
     * decimator, at 264 600 S/s into the demodulator.
     */
    @Test
    fun la_chaine_de_la_cle_donne_les_memes_trames() {
        val f = fichier()
        assumeTrue(f != null)
        val fsIn = 187_500.0
        val fsCle = 1_058_400.0
        val decalage = 30_000.0
        val dec = fr.f4ioz.satcombo.meteor.Decimateur(fsCle, 4)
        val demod = DemodOqpsk(dec.fsSortie)
        val trames = Trames { }
        val raf = RandomAccessFile(f, "r")
        val secondes = 20.0
        val n = (secondes * fsIn).toInt()
        raf.seek(44 + (400 * fsIn).toLong() * 8)
        val buf = ByteArray(n * 8)
        raf.readFully(buf)
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        val xi = FloatArray(n); val xq = FloatArray(n)
        for (k in 0 until n) { xi[k] = bb.float; xq[k] = bb.float }
        // Level for 8 bits: the rms at about 0.25 of full scale.
        var p = 0.0
        for (k in 0 until n) p += xi[k] * xi[k] + xq[k] * xq[k]
        val g = 0.25 / kotlin.math.sqrt(p / n)
        val bloc = 16384
        val u8 = ByteArray(bloc)
        val re = FloatArray(bloc); val im = FloatArray(bloc)
        val soft = ByteArray(bloc)
        var t = 0.0
        val pas = fsIn / fsCle
        var phase = 0.0
        val dphi = 2 * Math.PI * decalage / fsCle
        var o = 0
        fun cubique(x: FloatArray, i: Int, mu: Double): Double {
            val y0 = x[(i - 1).coerceAtLeast(0)].toDouble(); val y1 = x[i].toDouble()
            val y2 = x[(i + 1).coerceAtMost(n - 1)].toDouble(); val y3 = x[(i + 2).coerceAtMost(n - 1)].toDouble()
            val a = -0.5 * y0 + 1.5 * y1 - 1.5 * y2 + 0.5 * y3
            val b = y0 - 2.5 * y1 + 2 * y2 - 0.5 * y3
            val c = -0.5 * y0 + 0.5 * y2
            return ((a * mu + b) * mu + c) * mu + y1
        }
        while (t < n - 3) {
            val i = t.toInt(); val mu = t - i
            val a = cubique(xi, i, mu) * g; val b = cubique(xq, i, mu) * g
            val c = kotlin.math.cos(phase); val s = kotlin.math.sin(phase)
            val yi = a * c - b * s; val yq = a * s + b * c
            u8[o++] = (yi * 127.5 + 127.5).toInt().coerceIn(0, 255).toByte()
            u8[o++] = (yq * 127.5 + 127.5).toInt().coerceIn(0, 255).toByte()
            if (o == bloc) {
                val m = dec.traiteU8(u8, o, decalage, re, im)
                val k = demod.traite(re, im, m, soft)
                trames.traite(soft, k)
                o = 0
            }
            phase += dphi; if (phase > Math.PI) phase -= 2 * Math.PI
            t += pas
        }
        println("CLE trames=${trames.trames} ratées=${trames.tramesRatees} qualité=${demod.qualiteDb}")
        // 20 s at 8.8 frames a second; the first second goes to locking on.
        org.junit.Assert.assertTrue("trames ${trames.trames}", trames.trames > 150)
    }
}
