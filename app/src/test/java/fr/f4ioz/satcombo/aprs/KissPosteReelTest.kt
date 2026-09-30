/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.aprs

import fr.f4ioz.satcombo.cat.SerialLink
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

/**
 * On demand, with a real radio on the PC: `-Dsatme.kiss=/dev/ttyUSB0`
 * (port set beforehand: `stty -F /dev/ttyUSB0 9600 raw -echo`). Switches
 * it to KISS and back — never transmits anything.
 */
class KissPosteReelTest {
    @Test
    fun passer_en_kiss_et_revenir() = runBlocking {
        val dev = System.getProperty("satme.kiss") ?: ""
        assumeTrue(dev.isNotBlank() && File(dev).exists())
        val f = RandomAccessFile(dev, "rw")
        val lien = object : SerialLink {
            override fun write(bytes: ByteArray, timeoutMs: Int): Boolean { f.write(bytes); return true }
            override fun read(buf: ByteArray, timeoutMs: Int): Int {
                val fin = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < fin) {
                    val dispo = runCatching { java.io.FileInputStream(f.fd).available() }.getOrDefault(0)
                    if (dispo > 0) return f.read(buf, 0, minOf(dispo, buf.size))
                    Thread.sleep(20)
                }
                return 0
            }
            override fun close() = f.close()
        }
        TncKiss.attache(null, lien, dev, 9600)
        assertTrue("passage en KISS", TncKiss.passeEnKiss())
        val e = TncKiss.etat.value
        println("KISS ${dev} : fréquence lue ${e.frequenceHz} Hz, bande ${e.bande}, erreur ${e.erreur}")
        assertTrue(e.initialise)
        TncKiss.deconnecte()
        // Back in normal mode: the radio answers its PC commands again.
        val g = RandomAccessFile(dev, "rw")
        g.write("FO 0\r".toByteArray()); Thread.sleep(800)
        val r = ByteArray(200); val n = java.io.FileInputStream(g.fd).available().let { if (it > 0) g.read(r, 0, minOf(it, 200)) else 0 }
        val rep = String(r, 0, n)
        println("après déconnexion, FO 0 → $rep")
        g.close()
        assertTrue(rep.startsWith("FO 0,"))
        assertEquals(e.frequenceHz, Kiss.frequenceFo(rep))
    }

    /** On demand: `-Dsatme.kissbrut=capture.bin` — raw bytes a real radio sent in KISS, through SatMe's chain. */
    @Test
    fun capture_brute_d_un_vrai_poste() {
        val chemin = System.getProperty("satme.kissbrut") ?: ""
        assumeTrue(chemin.isNotBlank() && File(chemin).exists())
        val paquets = ArrayList<Paquet>()
        var brutes = 0
        KissDecodeur { o -> brutes++; Ax25.decodeSansFcs(o)?.let { paquets += Aprs.lit(it, 0) } }
            .octets(File(chemin).readBytes())
        println("KISS brut : $brutes trame(s), ${paquets.size} lue(s)")
        paquets.forEach { p ->
            println("  ${p.trame.tnc2().map { if (it.code < 32) '·' else it }.joinToString("")}")
            println("     → ${p.type}" + (p.lat?.let { " %.4f %.4f".format(java.util.Locale.US, it, p.lon) } ?: "") +
                (p.message?.let { " « $it »" } ?: ""))
        }
        assertEquals(brutes, paquets.size)
    }

    /**
     * TRANSMITS one frame through a real KISS radio, on demand only:
     * `-Dsatme.kiss=/dev/ttyUSB0 -Dsatme.kiss.emission=OUI`. Low power on band
     * A, frequency read and checked by SatMe's own rules, one message to the
     * author's own callsign, no path; power put back afterwards.
     */
    @Test
    fun emission_d_une_trame() = runBlocking {
        val dev = System.getProperty("satme.kiss") ?: ""
        assumeTrue(dev.isNotBlank() && File(dev).exists() && System.getProperty("satme.kiss.emission") == "OUI")
        val f = RandomAccessFile(dev, "rw")
        fun dispo() = runCatching { java.io.FileInputStream(f.fd).available() }.getOrDefault(0)
        fun cat(c: String): String {
            while (dispo() > 0) f.read(ByteArray(dispo()))
            f.write((c + "\r").toByteArray()); Thread.sleep(700)
            val b = ByteArray(256); val n = if (dispo() > 0) f.read(b, 0, minOf(dispo(), 256)) else 0
            return String(b, 0, n).trim()
        }
        val puissanceAvant = cat("PC 0")
        // TH-D72: 0 = high (5 W), 1 = low, 2 = extra low. Low unless asked otherwise.
        val niveau = (System.getProperty("satme.kiss.puissance") ?: "1").takeIf { it in setOf("0", "1", "2") } ?: "1"
        println("puissance avant : $puissanceAvant ; réglée : ${cat("PC 0,$niveau")}")
        val lien = object : SerialLink {
            override fun write(bytes: ByteArray, timeoutMs: Int): Boolean { f.write(bytes); return true }
            override fun read(buf: ByteArray, timeoutMs: Int): Int {
                val fin = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < fin) {
                    val d = dispo()
                    if (d > 0) return f.read(buf, 0, minOf(d, buf.size))
                    Thread.sleep(20)
                }
                return 0
            }
            override fun close() {}
        }
        try {
            TncKiss.attache(null, lien, dev, 9600)
            assertTrue(TncKiss.passeEnKiss())
            val hz = TncKiss.etat.value.frequenceHz
            println("fréquence lue : $hz Hz, bande ${TncKiss.etat.value.bande}")
            val chemin = (System.getProperty("satme.kiss.chemin") ?: "").split(',').filter { it.isNotBlank() }
            // A position ("lat,lon") when asked — radios that only list stations show that — else a message.
            val pos = (System.getProperty("satme.kiss.position") ?: "").split(',').mapNotNull { it.toDoubleOrNull() }
            val info = if (pos.size == 2) AprsEmission.position(pos[0], pos[1], "/-", "Essai SatMe KISS TH-D72")
                else AprsEmission.message(System.getProperty("satme.kiss.dest") ?: "F4IOZ", "Essai SatMe KISS TH-D72",
                    System.getProperty("satme.kiss.numero") ?: "1")
            val trame = AprsEmission.trame("F4IOZ-7", chemin, info)
            val t0 = System.currentTimeMillis()
            TncKiss.observateur = { t ->
                val relayee = t.source == trame.source && t.texte == trame.texte
                println("  +%.1f s reçue%s : %s".format(java.util.Locale.US, (System.currentTimeMillis() - t0) / 1000.0,
                    if (relayee) " (NOTRE TRAME, RELAYÉE)" else "",
                    t.tnc2().map { if (it.code < 32) '·' else it }.joinToString("")))
            }
            val refus = AprsEmission.refus("F4IOZ", System.currentTimeMillis(), 0, hz, 0x05, false, false)
            println("contrôles : ${refus ?: "ok"}")
            assertEquals(null, refus)
            println("émission : ${trame.tnc2()}")
            assertTrue(TncKiss.envoie(trame))
            Thread.sleep(1000L * ((System.getProperty("satme.kiss.ecoute") ?: "30").toIntOrNull() ?: 30).coerceIn(5, 300))
            TncKiss.observateur = null
            println("reçues pendant l'essai : ${TncKiss.etat.value.recues}, émises : ${TncKiss.etat.value.envoyees}")
        } finally {
            TncKiss.deconnecte()
            Thread.sleep(500)
            val remise = Regex("PC 0,(\\d)").find(puissanceAvant)?.groupValues?.get(1)
            if (remise != null) println("puissance remise : ${cat("PC 0,$remise")}")
            println("état final : ${cat("FO 0")}")
            f.close()
        }
    }
}
