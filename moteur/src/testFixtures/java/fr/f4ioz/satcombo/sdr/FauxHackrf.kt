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

/**
 * A fake `hackrf_transfer` and `hackrf_info` (a shell script) in a folder
 * of their own: what they are given and what they read lands in it. No
 * radio is ever touched.
 */
class FauxHackrf(mode: String = "normal", limite: Long = 0L) : AutoCloseable {
    val dossier: File = Files.createTempDirectory("satme-faux-hackrf").toFile()
    val transfer = File(dossier, "hackrf_transfer")
    val info = File(dossier, "hackrf_info")

    init {
        val script = FauxHackrf::class.java.getResourceAsStream("faux-hackrf.sh")!!.readBytes()
        for (f in listOf(transfer, info)) { f.writeBytes(script); f.setExecutable(true) }
        File(dossier, "mode").writeText(mode)
        if (limite > 0) File(dossier, "limite").writeText(limite.toString())
    }

    var mode: String
        get() = File(dossier, "mode").readText().trim()
        set(v) = File(dossier, "mode").writeText(v)

    /** The last command line's arguments (after the program). */
    val args: List<String> get() = File(dossier, "args").takeIf { it.isFile }?.readText()?.trim()?.split(' ').orEmpty()
    val compte: Long get() = File(dossier, "compte").takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull() ?: 0L
    /** It saw the end of its input (rather than being killed). */
    val finEntree: Boolean get() = File(dossier, "fin").isFile
    val iq: ByteArray get() = File(dossier, "iq").takeIf { it.isFile }?.readBytes() ?: ByteArray(0)

    override fun close() { dossier.deleteRecursively() }
}
