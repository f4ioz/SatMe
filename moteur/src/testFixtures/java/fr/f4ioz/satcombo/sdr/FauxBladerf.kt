/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sdr

import java.io.File
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A fake `bladeRF-cli` (a shell script) in a folder of its own: the lines
 * it reads and the samples it takes land in it; it gives a tone when asked
 * to receive. No radio is ever touched.
 */
class FauxBladerf(mode: String = "normal", limite: Long = 0L) : AutoCloseable {
    val dossier: File = Files.createTempDirectory("satme-faux-bladerf").toFile()
    val cli = File(dossier, "bladeRF-cli")
    /** Where the code under test makes its pipes. */
    val tubes = File(dossier, "tubes")

    init {
        cli.writeBytes(FauxBladerf::class.java.getResourceAsStream("faux-bladerf.sh")!!.readBytes())
        cli.setExecutable(true)
        File(dossier, "mode").writeText(mode)
        if (limite > 0) File(dossier, "limite").writeText(limite.toString())
        // 8192 pairs of a tone at an eighth of the rate, amplitude 1000 (12-bit), SC16 Q11.
        val n = 8192
        val b = ByteArray(n * 4)
        for (k in 0 until n) {
            val i = (1000 * cos(PI / 4 * k)).roundToInt(); val q = (1000 * sin(PI / 4 * k)).roundToInt()
            b[4 * k] = i.toByte(); b[4 * k + 1] = (i shr 8).toByte(); b[4 * k + 2] = q.toByte(); b[4 * k + 3] = (q shr 8).toByte()
        }
        File(dossier, "rx.bin").writeBytes(b)
    }

    var mode: String
        get() = File(dossier, "mode").readText().trim()
        set(v) = File(dossier, "mode").writeText(v)

    /** Every line it read, in order. */
    val commandes: List<String> get() = File(dossier, "commandes").takeIf { it.isFile }?.readLines().orEmpty()
    val args: List<String> get() = File(dossier, "args").takeIf { it.isFile }?.readLines().orEmpty()
    val compte: Long get() = File(dossier, "compte").takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull() ?: 0L
    /** Its TX "on" after `tx start`, "off" after `tx stop` or its end. */
    val txActif: String get() = File(dossier, "tx_actif").takeIf { it.isFile }?.readText()?.trim().orEmpty()
    val quitte: Boolean get() = File(dossier, "quit").isFile
    val iq: ByteArray get() = File(dossier, "iq").takeIf { it.isFile }?.readBytes() ?: ByteArray(0)

    override fun close() { dossier.deleteRecursively() }
}
