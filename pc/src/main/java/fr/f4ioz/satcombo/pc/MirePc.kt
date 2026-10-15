/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.sstv.ReceptionSim
import fr.f4ioz.satcombo.sstv.SstvConditions
import fr.f4ioz.satcombo.sstv.SstvEncoder
import fr.f4ioz.satcombo.sstv.SstvMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.io.RandomAccessFile
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

/**
 * The SSTV test card, as the phone's: a reference picture sent in the mode
 * chosen, to try a decoder without a satellite — this PC's, a phone's, or a
 * whole radio chain. Played on a sound output chosen, or written to a WAV;
 * reception conditions simulated when asked (hiss, fading, dropouts, the
 * pass's profile). Nothing is keyed: the sound goes where the output goes.
 */
class MirePc(private val dossierExports: File, private val horloge: () -> Long = System::currentTimeMillis) {

    data class Etat(val joue: Boolean = false, val mode: String = "", val progres: Float = 0f, val sortie: String = "", val erreur: String = "")

    private val _etat = MutableStateFlow(Etat())
    val etat: StateFlow<Etat> = _etat.asStateFlow()
    @Volatile private var arret = false
    @Volatile private var fil: Thread? = null

    /** The outputs a 16-bit mono line can play on (the system's default first, as ""). */
    fun sorties(): List<Pair<String, String>> = listOf("" to "sortie par défaut") + runCatching {
        AudioSystem.getMixerInfo().filter { info ->
            AudioSystem.getMixer(info).isLineSupported(DataLine.Info(SourceDataLine::class.java, FORMAT))
        }.map { it.name to it.description }
    }.getOrDefault(emptyList())

    /** The card for [mode], at the mode's size: bars, grey steps, checkers, edges, the call and square. */
    fun image(mode: SstvMode, indicatif: String, locator: String): BufferedImage = carte(mode.width, mode.height, indicatif, locator, mode.name)

    private fun pixels(im: BufferedImage) = im.getRGB(0, 0, im.width, im.height, null, 0, im.width)

    /** Plays the card on [sortie] ("" = the default output), in the background; another one stops the first. */
    fun joue(mode: SstvMode, c: SstvConditions, sortie: String, indicatif: String, locator: String) {
        arrete()
        arret = false
        _etat.value = Etat(joue = true, mode = mode.name, sortie = sortie)
        fil = Thread({
            var ligne: SourceDataLine? = null
            try {
                val info = DataLine.Info(SourceDataLine::class.java, FORMAT)
                val mixer = sortie.takeIf { it.isNotBlank() }?.let { n -> AudioSystem.getMixerInfo().firstOrNull { it.name == n } }
                ligne = (if (mixer != null) AudioSystem.getMixer(mixer).getLine(info) else AudioSystem.getLine(info)) as SourceDataLine
                ligne.open(FORMAT, RATE / 4 * 2); ligne.start()
                val src = SstvEncoder.Source(mode, pixels(image(mode, indicatif, locator)), RATE)
                val sim = if (c.active) ReceptionSim(c, RATE, src.totalSamples) else null
                val buf = ShortArray(2048); val octets = ByteArray(4096)
                while (!arret) {
                    val n = src.read(buf); if (n <= 0) break
                    sim?.apply(buf, n)
                    for (i in 0 until n) { octets[2 * i] = buf[i].toInt().toByte(); octets[2 * i + 1] = (buf[i].toInt() shr 8).toByte() }
                    ligne.write(octets, 0, 2 * n)
                    _etat.value = _etat.value.copy(progres = src.progress)
                }
                if (!arret) ligne.drain()
                _etat.value = _etat.value.copy(joue = false, progres = if (arret) _etat.value.progres else 1f)
            } catch (e: Exception) {
                _etat.value = Etat(erreur = "sortie son impossible : " + (e.message ?: e.javaClass.simpleName))
            } finally {
                ligne?.let { runCatching { it.stop(); it.close() } }
            }
        }, "MirePc").apply { isDaemon = true; start() }
    }

    fun arrete() {
        arret = true
        fil?.join(1500); fil = null
        if (_etat.value.joue) _etat.value = _etat.value.copy(joue = false)
    }

    /** The card as a WAV file (16 bits, 44.1 kHz), named as the phone names it. */
    fun wav(mode: SstvMode, c: SstvConditions, indicatif: String, locator: String): File {
        dossierExports.mkdirs()
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date(horloge()))
        val f = File(dossierExports, "SatMe_MIRE_" + mode.name.replace(Regex("[^A-Za-z0-9]"), "") + (if (c.active) "_sim" else "") + "_$stamp.wav")
        val src = SstvEncoder.Source(mode, pixels(image(mode, indicatif, locator)), RATE)
        val sim = if (c.active) ReceptionSim(c, RATE, src.totalSamples) else null
        RandomAccessFile(f, "rw").use { raf ->
            raf.setLength(0); raf.write(ByteArray(44))
            val buf = ShortArray(4096); val octets = ByteArray(8192); var total = 0L
            while (true) {
                val n = src.read(buf); if (n <= 0) break
                sim?.apply(buf, n)
                for (i in 0 until n) { octets[2 * i] = buf[i].toInt().toByte(); octets[2 * i + 1] = (buf[i].toInt() shr 8).toByte() }
                raf.write(octets, 0, 2 * n); total += 2 * n
            }
            raf.seek(0); raf.write(entete(total))
        }
        return f
    }

    private fun entete(donnees: Long): ByteArray {
        val b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt((36 + donnees).toInt()); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(1); b.putInt(RATE); b.putInt(RATE * 2); b.putShort(2); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(donnees.toInt())
        return b.array()
    }

    companion object {
        const val RATE = 44_100
        val FORMAT = AudioFormat(RATE.toFloat(), 16, 1, true, false)
        private val BARRES = intArrayOf(0xFFFFFF, 0xFFFF00, 0x00FFFF, 0x00FF00, 0xFF00FF, 0xFF0000, 0x0000FF, 0x000000)

        /** The card at [w]×[h]: bars, grey steps, checkers, edges, the call and square, [libelle] (the mode, or DATV). */
        fun carte(w: Int, h: Int, indicatif: String, locator: String, libelle: String): BufferedImage {
            val im = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val g = im.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.color = Color(0x0B1020); g.fillRect(0, 0, w, h)
            val bord = maxOf(4, w / 40); val coche = maxOf(4, h / 40)
            val barresBas = (h * 0.42f).toInt(); val grisBas = (h * 0.54f).toInt()
            val l = w - 2 * bord
            for (i in BARRES.indices) { g.color = Color(BARRES[i]); g.fillRect(bord + l * i / BARRES.size, coche, l * (i + 1) / BARRES.size - l * i / BARRES.size, barresBas - coche) }
            for (i in 0 until 16) { val v = i * 255 / 15; g.color = Color(v, v, v); g.fillRect(bord + l * i / 16, barresBas, l * (i + 1) / 16 - l * i / 16, grisBas - barresBas) }
            for (i in 0 until 16) {
                g.color = if (i % 2 == 0) Color.WHITE else Color.BLACK
                val x0 = bord + l * i / 16; val dx = l * (i + 1) / 16 - x0
                g.fillRect(x0, 0, dx, coche); g.fillRect(x0, h - coche, dx, coche)
            }
            g.color = Color(0x00C000); g.fillRect(0, 0, bord, h)
            g.color = Color(0xC00000); g.fillRect(w - bord, 0, bord, h)
            val haut = grisBas + h / 40
            val dispo = h - haut - coche
            val logo = (dispo * 0.72f).toInt().coerceAtLeast(24)
            val lx = bord + w / 24; val ly = haut + (dispo - logo) / 2
            MarquePc.icone?.let { g.drawImage(it, lx, ly, logo, logo, null) } ?: run {
                g.color = Color(0x3FE0C8); g.stroke = BasicStroke(maxOf(2f, logo / 12f)); g.drawOval(lx, ly, logo, logo)
            }
            val tx = lx + logo + w / 40
            g.color = Color.WHITE; g.font = Font(Font.SANS_SERIF, Font.BOLD, (dispo * 0.30f).toInt().coerceAtLeast(8))
            g.drawString("SatMe", tx, (haut + dispo * 0.30f).toInt())
            g.font = Font(Font.SANS_SERIF, Font.PLAIN, (dispo * 0.19f).toInt().coerceAtLeast(7))
            val ligne = listOf(indicatif.trim(), locator.trim()).filter { it.isNotEmpty() }.joinToString("  ")
            g.color = Color(0x9FE8FF); if (ligne.isNotEmpty()) g.drawString(ligne, tx, (haut + dispo * 0.56f).toInt())
            g.color = Color(0xFFC65C); g.drawString(libelle, tx, (haut + dispo * 0.82f).toInt())
            g.color = Color(0x8AA0C0); g.font = Font(Font.SANS_SERIF, Font.PLAIN, (dispo * 0.13f).toInt().coerceAtLeast(6))
            val dim = "${w}x$h"
            g.drawString(dim, w - bord - w / 40 - g.fontMetrics.stringWidth(dim), (h - coche - dispo * 0.06f).toInt())
            g.dispose()
            return im
        }

        /** The conditions from the page: noise SNR (dB, 0 = none), fading, dropouts, the pass's profile. */
        fun conditions(bruit: Int, fading: String, coupures: Boolean, profil: Boolean) = SstvConditions(
            snrDb = bruit.takeIf { it > 0 }?.toDouble(),
            fading = when (fading) { "LIGHT" -> SstvConditions.Fading.LIGHT; "STRONG" -> SstvConditions.Fading.STRONG; else -> SstvConditions.Fading.NONE },
            dropouts = coupures, passProfile = profil)
    }
}
