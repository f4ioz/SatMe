/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.aprs

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The AGWPE frames of a monitor that never transmits. Fictitious callsigns. */
class AgwpeTest {

    private val ui = Ax25.encodeSansFcs(Trame(Adresse.de("APRS"), Adresse.de("F4XYZ-9"), listOf(Adresse.de("WIDE1-1*")), ">essai".toByteArray()))

    @Test
    fun `l'entete fait 36 octets, petit-boutiste`() {
        val o = Agwpe.Trame(0, 'K', 0, "F4XYZ-9", "APRS", byteArrayOf(1, 2, 3)).octets()
        assertEquals(39, o.size)
        assertEquals('K'.code.toByte(), o[4])
        assertEquals("F4XYZ-9", String(o, 8, 7))
        assertEquals(3, o[28].toInt()); assertEquals(0, o[29].toInt())
        val l = Agwpe.Lecteur()
        // Cut anywhere: read back whole.
        assertTrue(l.octets(o.copyOfRange(0, 20)).isEmpty())
        val t = l.octets(o.copyOfRange(20, o.size)).single()
        assertEquals('K', t.genre); assertEquals("F4XYZ-9", t.de); assertEquals("APRS", t.a); assertArrayEquals(byteArrayOf(1, 2, 3), t.donnees)
    }

    @Test
    fun `tout ce qui emettrait est une emission refusee`() {
        "KMVCcvDd".forEach { assertEquals("$it", Agwpe.Genre.EMISSION, Agwpe.genre(it)) }
        "RGgXyY".forEach { assertEquals("$it", Agwpe.Genre.REPONSE, Agwpe.genre(it)) }
        assertEquals(Agwpe.Genre.BASCULE, Agwpe.genre('m')); assertEquals(Agwpe.Genre.BASCULE, Agwpe.genre('k'))
        // No answer is made for a frame to transmit.
        assertNull(Agwpe.reponse(Agwpe.Trame(0, 'K', 0, "", "", ByteArray(0)), "x"))
        assertNull(Agwpe.reponse(Agwpe.Trame(0, 'M', 0, "", "", ByteArray(0)), "x"))
    }

    @Test
    fun `version, ports, trame brute et moniteur`() {
        val r = Agwpe.reponse(Agwpe.Trame(0, 'R', 0, "", "", ByteArray(0)), "SatMe")!!
        assertEquals(8, r.donnees.size); assertEquals(2000, (r.donnees[0].toInt() and 0xFF) or ((r.donnees[1].toInt() and 0xFF) shl 8))
        assertTrue(String(Agwpe.reponse(Agwpe.Trame(0, 'G', 0, "", "", ByteArray(0)), "SatMe")!!.donnees).startsWith("1;Port1 SatMe;"))
        val k = Agwpe.brute(ui)
        assertEquals(0, k.donnees[0].toInt()); assertArrayEquals(ui, k.donnees.copyOfRange(1, k.donnees.size))
        val u = Agwpe.moniteur(ui, 0L)!!
        assertEquals('U', u.genre); assertEquals("F4XYZ-9", u.de); assertEquals(0xF0, u.pid)
        val texte = String(u.donnees, Charsets.ISO_8859_1)
        assertEquals(" 1:Fm F4XYZ-9 To APRS Via WIDE1-1* <UI pid=F0 Len=6 >[00:00:00]\r>essai\r", texte)
    }
}
