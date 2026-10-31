/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SatPC32's Doppler.SQF and Gpredict's .trsp: read, written, round trips, names matched to the catalogue. */
class FichiersFrequencesTest {

    private val F = FichiersFrequences

    private val sqf = """
        ; a comment
        AO-7,145950,432150,USB,LSB,REV,0,0,Mode B
        ISS,145800,145990,FM,FM,NOR,0,0,V/U voix
        SO-50,436795.5,145850,FM,FM,NOR,-200,0,Mode V/U FM,67.0
        RS-44,435640,145965,USB,LSB,REV,0,0,Lineaire
        XX-99,435000,0,FM,FM,NOR,0,0,inconnu
        cassée,abc,145000,FM,FM,NOR
        AO-91,145960,435250,FM,FM,SIDEWAYS,0,0,mauvais sens
    """.trimIndent()

    @Test
    fun le_doppler_sqf_lu() {
        val l = F.lisSqf(sqf)
        assertEquals(5, l.lus.size)
        val ao7 = l.lus[0]
        assertEquals("AO-7", ao7.nom); assertEquals(145_950_000L, ao7.descenteHz); assertEquals(432_150_000L, ao7.monteeHz)
        assertEquals("USB", ao7.modeDescente); assertEquals("LSB", ao7.modeMontee); assertTrue(ao7.inverse); assertEquals("Mode B", ao7.remarque)
        val so50 = l.lus[2]
        assertEquals(436_795_500L, so50.descenteHz); assertEquals(-200L, so50.decalageRxHz); assertEquals(67.0, so50.ctcss!!, 0.0)
        assertNull(l.lus[4].monteeHz)
        assertEquals(listOf(7, 8), l.illisibles.map { it.first })
    }

    @Test
    fun aller_retour_sqf() {
        val tp = Transmitter("Mode U/V linéaire", "LSB", 435_030_000L, 435_060_000L, 145_925_000L, 145_955_000L, true, true, "Transponder")
        val fm = Transmitter("Mode V/U FM", "FM", 145_850_000L, null, 436_795_000L, null, false, true, "Transceiver")
        val bal = Transmitter("Balise, CW", "CW", null, null, 145_888_000L, null, false, true, "Transmitter")
        val texte = F.ecritSqf(listOf("FO-29" to listOf(tp), "SO-50" to listOf(fm, bal)))
        assertTrue(texte.contains("\r\n"))
        assertTrue(texte.contains("FO-29,145940,435045,USB,LSB,REV,0,0,Mode U/V linéaire\r\n"))
        assertTrue(texte.contains("SO-50,436795,145850,FM,FM,NOR,0,0,Mode V/U FM\r\n"))
        // A comma in a remark would split the line: it is taken out.
        assertTrue(texte.contains("SO-50,145888,0,CW,CW,NOR,0,0,Balise  CW\r\n"))
        val relu = F.lisSqf(texte)
        assertTrue(relu.illisibles.isEmpty())
        assertEquals(3, relu.lus.size)
        val e = F.emetteur(relu.lus[1])
        assertEquals(436_795_000L, e.downlinkLowHz); assertEquals(145_850_000L, e.uplinkLowHz); assertEquals("FM", e.mode)
        assertEquals("Mode V/U FM (SatPC32)", e.description); assertEquals(F.TYPE_SQF, e.type); assertFalse(e.invert)
        // Exported again: the same line, the origin not doubled.
        assertEquals(F.texteSqf(relu.lus[1]), F.texteSqf(F.ligneSqf("SO-50", e)!!))
        assertTrue(F.emetteur(relu.lus[0]).invert)
        // An inverting transponder read back from its single line is written as it came: REV, USB down, LSB up.
        val ao7 = F.lisSqf("AO-7,145950,432150,USB,LSB,REV,0,0,Mode B").lus.single()
        assertEquals("AO-7,145950,432150,USB,LSB,REV,0,0,Mode B", F.texteSqf(F.ligneSqf("AO-7", F.emetteur(ao7))!!))
    }

    @Test
    fun aller_retour_trsp() {
        val l = listOf(
            Transmitter("Mode V/U FM [voix]", "FM", 145_990_000L, null, 437_800_000L, null, false, true, "Transceiver"),
            Transmitter("Linéaire", "LSB", 435_030_000L, 435_060_000L, 145_925_000L, 145_955_000L, true, true, "Transponder"),
            Transmitter("APRS", "AFSK", 145_825_000L, null, 145_825_000L, null, false, true, "Transceiver", baud = 1200.0),
            Transmitter("APRS", "GMSK", null, null, 437_550_000L, null, false, true, "Transmitter", baud = 9600.0),
        )
        val t = F.ecritTrsp(l)
        assertTrue(t.contains("[Mode V/U FM (voix)]\nUP_LOW=145990000\nDOWN_LOW=437800000\nINVERT=false\nMODE=FM\n"))
        assertTrue(t.contains("[Linéaire]\nUP_LOW=435030000\nUP_HIGH=435060000\nDOWN_LOW=145925000\nDOWN_HIGH=145955000\nINVERT=true\n"))
        assertTrue(t.contains("[APRS 2]"))
        val r = F.lisTrsp(t)
        assertTrue(r.illisibles.isEmpty())
        assertEquals(4, r.lus.size)
        r.lus.zip(l).forEach { (a, b) ->
            assertEquals(b.uplinkLowHz, a.uplinkLowHz); assertEquals(b.uplinkHighHz, a.uplinkHighHz)
            assertEquals(b.downlinkLowHz, a.downlinkLowHz); assertEquals(b.downlinkHighHz, a.downlinkHighHz)
            assertEquals(b.invert, a.invert); assertEquals(b.mode, a.mode); assertEquals(b.baud, a.baud)
            assertEquals(F.TYPE_TRSP, a.type)
        }
        assertEquals("Linéaire (Gpredict)", r.lus[1].description)
        assertTrue(r.lus[1].isTransponder)
        // Written again from what was read: the same file.
        assertEquals(t, F.ecritTrsp(r.lus))
        assertEquals(25544, F.numeroDuFichier("/home/x/.config/Gpredict/trsp/25544.trsp"))
        assertEquals(43017, F.numeroDuFichier("C:\\gp\\43017.TRSP"))
        assertNull(F.numeroDuFichier("iss.trsp"))
        // A section without any frequency is reported, a stray line too.
        val m = F.lisTrsp("UP_LOW=1\n[Vide]\nMODE=FM\n[Bon]\nDOWN_LOW=145800000\n")
        assertEquals(1, m.lus.size)
        assertEquals(2, m.illisibles.size)
    }

    @Test
    fun les_noms_sont_associes_au_catalogue() {
        val cat = listOf("AO-07" to 7530, "ISS (ZARYA)" to 25544, "SO-50" to 27607, "RS-44" to 44909, "AO-91" to 43017)
        val i = F.associe(F.lisSqf(sqf), cat)
        assertEquals(setOf(7530, 25544, 27607, 44909), i.parSatellite.keys)
        assertEquals(4, i.nombre)
        assertEquals(mapOf("XX-99" to 1), i.inconnus)
        assertEquals(2, i.illisibles.size)
        assertEquals(145_800_000L, i.parSatellite.getValue(25544).single().downlinkLowHz)
    }
}
