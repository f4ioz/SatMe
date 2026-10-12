/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.aprs.Adresse
import fr.f4ioz.satcombo.aprs.Afsk
import fr.f4ioz.satcombo.aprs.Ax25
import fr.f4ioz.satcombo.aprs.Trame
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** APRS heard through the PC's sound: decoded, kept as the phone keeps it, read back. */
class AprsPcTest {

    private val dossier = File(System.getProperty("java.io.tmpdir"), "satme-aprs-" + System.nanoTime())
    @After fun fin() { dossier.deleteRecursively() }

    private var t = 1_791_800_000_000L

    @Test
    fun une_trame_par_l_iss_est_decodee_gardee_et_relue() {
        val trame = Trame(Adresse.de("APRS"), Adresse.de("F4IOZ-7"),
            listOf(Adresse("RS0ISS", repete = true), Adresse.de("WIDE2-1")), "=4852.55N/00219.00E-SatMe".toByteArray())
        val a = AprsPc(dossier, { "ISS" }, { t })
        val son = Afsk.module(listOf(Ax25.encode(trame)), 48_000)
        // In blocks of 2048, as the card hands them out.
        var i = 0
        while (i < son.size) { val n = minOf(2048, son.size - i); a.ecoute(son.copyOfRange(i, i + n), n, 48_000); i += n }
        assertEquals(1, a.paquets.size)
        val p = a.paquets.single()
        assertEquals("F4IOZ-7", p.source)
        assertTrue(p.viaIss)
        assertEquals(48.875, p.lat!!, 0.01)
        // Heard again 2 s later: kept once; read back by a new start, as the phone would.
        t += 2_000
        assertTrue(!a.ajoute(trame, t))
        val relu = AprsPc(dossier, { "ISS" }, { t })
        assertEquals(1, relu.paquets.size)
        assertEquals("F4IOZ-7", relu.paquets.single().source)
        assertTrue(File(dossier, "2026-10-12.txt").readText().contains("\tISS\t"))
    }
}
