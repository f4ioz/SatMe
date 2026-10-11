/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.aprs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/** AX.25, HDLC and the AFSK modem, there and back; real recordings on demand. */
class AfskTest {

    private val trame = Trame(Adresse("APRS"), Adresse("F4IOZ", 7),
        listOf(Adresse("ARISS")), ":ANSRVR   :CQ HOTG Bonjour de JN18{01".toByteArray())

    private fun decode(pcm: ShortArray, frequence: Int): List<Trame> {
        val recues = ArrayList<Trame>()
        AfskDemodulateur(frequence) { recues += it }.traite(pcm)
        return recues
    }

    @Test
    fun ax25_aller_retour_et_fcs() {
        val o = Ax25.encode(trame)
        assertEquals(trame.tnc2(), Ax25.decode(o)!!.tnc2())
        assertEquals("F4IOZ-7>APRS,ARISS::ANSRVR   :CQ HOTG Bonjour de JN18{01", trame.tnc2())
        o[20] = (o[20].toInt() xor 4).toByte()
        assertNull(Ax25.decode(o))  // one bit wrong: dropped
        // Known X.25 check value: "123456789" → 0x906E.
        assertEquals(0x906E, Ax25.crc("123456789".toByteArray()))
    }

    @Test
    fun relais_repete_lu() {
        val t = Trame(Adresse("APRS"), Adresse("F4IOZ"), listOf(Adresse("RS0ISS", 0, true), Adresse("WIDE2", 1)),
            "=4852.55N/00219.00E-".toByteArray())
        assertEquals("F4IOZ>APRS,RS0ISS*,WIDE2-1:=4852.55N/00219.00E-", Ax25.decode(Ax25.encode(t))!!.tnc2())
    }

    @Test
    fun modem_a_toutes_les_frequences() {
        for (f in listOf(8000, 11025, 16000, 22050, 44100, 48000)) {
            val r = decode(Afsk.module(listOf(Ax25.encode(trame)), f), f)
            assertEquals("à $f Hz", listOf(trame.tnc2()), r.map { it.tnc2() })
        }
    }

    @Test
    fun plusieurs_trames_a_la_suite() {
        val trames = (1..5).map { i ->
            Trame(Adresse("APRS"), Adresse("F4IOZ", i), emptyList(), ">essai $i".toByteArray())
        }
        val r = decode(Afsk.module(trames.map { Ax25.encode(it) }, 44100), 44100)
        assertEquals(trames.map { it.tnc2() }, r.map { it.tnc2() })
    }

    /** White noise at a given signal-to-noise ratio (over the whole audio band). */
    private fun bruite(pcm: ShortArray, snrDb: Double, graine: Int): ShortArray {
        val rnd = Random(graine)
        val p = pcm.sumOf { it.toDouble() * it } / pcm.size
        val sigma = kotlin.math.sqrt(p / Math.pow(10.0, snrDb / 10))
        return ShortArray(pcm.size) { (pcm[it] + rnd.nextGaussian() * sigma).coerceIn(-32768.0, 32767.0).toInt().toShort() }
    }

    private fun Random.nextGaussian(): Double {
        var u: Double; var v: Double; var s: Double
        do { u = nextDouble() * 2 - 1; v = nextDouble() * 2 - 1; s = u * u + v * v } while (s >= 1 || s == 0.0)
        return u * kotlin.math.sqrt(-2 * kotlin.math.ln(s) / s)
    }

    @Test
    fun avec_du_bruit() {
        val f = 22050
        val propre = Afsk.module(listOf(Ax25.encode(trame)), f, niveau = 0.3)
        var ok = 0
        for (g in 1..20) if (decode(bruite(propre, 6.0, g), f).isNotEmpty()) ok++
        assertTrue("$ok/20 à 6 dB", ok >= 18)
    }

    /** First-order de-emphasis (6 dB/octave from 300 Hz): the space tone arrives weaker. */
    private fun desaccentue(pcm: ShortArray, f: Int): ShortArray {
        val a = kotlin.math.exp(-2 * Math.PI * 300.0 / f)
        var y = 0.0
        val out = DoubleArray(pcm.size) { i -> y = (1 - a) * pcm[i] + a * y; y }
        val m = out.maxOf { kotlin.math.abs(it) }
        return ShortArray(pcm.size) { (out[it] / m * 16000).toInt().toShort() }
    }

    @Test
    fun audio_desaccentue_comme_a_la_sortie_d_un_poste() {
        val f = 44100
        val r = decode(desaccentue(Afsk.module(listOf(Ax25.encode(trame)), f), f), f)
        assertEquals(listOf(trame.tnc2()), r.map { it.tnc2() })
    }

    /** On demand: `-Dsatme.aprs=a.wav,b.wav` — frames found in real recordings. */
    @Test
    fun enregistrements_reels() {
        val fichiers = (System.getProperty("satme.aprs") ?: "").split(',').filter { it.isNotBlank() }
        assumeTrue(fichiers.isNotEmpty())
        for (chemin in fichiers) {
            val (pcm, f) = litWav(File(chemin))
            val debut = System.nanoTime()
            val r = decode(pcm, f)
            val s = (System.nanoTime() - debut) / 1e9
            println("APRS ${File(chemin).name} : ${r.size} trames en %.1f s (audio %.0f s à $f Hz)"
                .format(s, pcm.size.toDouble() / f))
            File("$chemin.satme.txt").writeText(r.joinToString("\n") { it.tnc2() } + "\n")
        }
    }

    private fun litWav(f: File): Pair<ShortArray, Int> {
        val b = f.readBytes()
        val bb = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        var i = 12
        var frequence = 0; var voies = 1
        while (i + 8 <= b.size) {
            val id = String(b, i, 4); val taille = bb.getInt(i + 4)
            if (id == "fmt ") { voies = bb.getShort(i + 10).toInt(); frequence = bb.getInt(i + 12) }
            if (id == "data") {
                val n = minOf(taille, b.size - i - 8) / 2 / voies
                return ShortArray(n) { bb.getShort(i + 8 + it * 2 * voies) } to frequence
            }
            i += 8 + taille + (taille and 1)
        }
        error("pas de données dans $f")
    }
}
