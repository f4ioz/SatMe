/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.aprs

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** On demand (`-Dsatme.aprs=…wav`): what the parser makes of real traffic, by type and place. */
class AprsReelTest {
    @Test
    fun contenu_des_enregistrements() {
        val fichiers = (System.getProperty("satme.aprs") ?: "").split(',').filter { it.isNotBlank() }
        assumeTrue(fichiers.isNotEmpty())
        for (chemin in fichiers) {
            val b = File(chemin).readBytes()
            val bb = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val f = bb.getInt(24)
            val pcm = ShortArray((b.size - 44) / 2) { bb.getShort(44 + it * 2) }
            val paquets = ArrayList<Paquet>()
            AfskDemodulateur(f) { paquets += Aprs.lit(it, 0) }.traite(pcm)
            println("APRS ${File(chemin).name} : " + paquets.groupingBy { it.type }.eachCount())
            val micE = paquets.filter { it.etatMicE != null }
            println("  Mic-E ${micE.size} : lat ${micE.minOfOrNull { it.lat!! }}..${micE.maxOfOrNull { it.lat!! }}, " +
                "lon ${micE.minOfOrNull { it.lon!! }}..${micE.maxOfOrNull { it.lon!! }}")
            val pos = paquets.filter { it.lat != null && it.etatMicE == null }
            println("  autres positions ${pos.size} : lat ${pos.minOfOrNull { it.lat!! }}..${pos.maxOfOrNull { it.lat!! }}, " +
                "lon ${pos.minOfOrNull { it.lon!! }}..${pos.maxOfOrNull { it.lon!! }}")
            micE.take(4).forEach { println("  ${it.source} ${"%.4f".format(it.lat)} ${"%.4f".format(it.lon)} ${it.vitesseKmh} km/h ${it.cap}° ${it.etatMicE}  « ${it.commentaire.take(30)} »") }
            paquets.filter { it.type == TypeAprs.MESSAGE }.take(3).forEach { println("  msg ${it.source} → ${it.destinataire} : ${it.message}") }
        }
    }
}
