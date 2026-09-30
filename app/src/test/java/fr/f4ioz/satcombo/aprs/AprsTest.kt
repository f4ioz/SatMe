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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** APRS content, with the examples of the APRS 1.01 specification. */
class AprsTest {

    private fun trame(info: String, dest: String = "APRS", relais: List<Adresse> = emptyList(), de: String = "F4IOZ") =
        Trame(Adresse.de(dest), Adresse.de(de), relais, info.toByteArray(Charsets.ISO_8859_1))

    private fun lit(info: String, dest: String = "APRS", relais: List<Adresse> = emptyList(), de: String = "F4IOZ") =
        Aprs.lit(trame(info, dest, relais, de), 0)

    @Test
    fun position_claire() {
        val p = lit("!4903.50N/07201.75W-Test 001234")
        assertEquals(TypeAprs.POSITION, p.type)
        assertEquals(49.058333, p.lat!!, 1e-5)
        assertEquals(-72.029167, p.lon!!, 1e-5)
        assertEquals("/-", p.symbole)
        assertEquals("Test 001234", p.commentaire)
    }

    @Test
    fun position_avec_heure_et_ambiguite() {
        val p = lit("@092345z4903.  N/07201.  W>")
        assertEquals(49.0 + 3.55 / 60, p.lat!!, 1e-4)
        assertEquals("/>", p.symbole)
    }

    @Test
    fun position_compressee() {
        val p = lit("=/5L!!<*e7>7P[")
        assertEquals(49.5, p.lat!!, 1e-4)
        assertEquals(-72.75, p.lon!!, 1e-4)
        assertEquals("/>", p.symbole)
    }

    @Test
    fun mic_e_de_la_specification() {
        // The destination's 5th character ('6') carries no +100° offset: 12° W,
        // as direwolf reads it. The offset itself is checked on real traffic
        // (WA8LMF CD, southern California: 114-120° W).
        val p = lit("`(_fn\"Oj/", dest = "S32U6T")
        assertEquals(TypeAprs.POSITION, p.type)
        assertEquals(33 + 25.64 / 60, p.lat!!, 1e-5)
        assertEquals(-(12 + 7.74 / 60), p.lon!!, 1e-5)
        assertEquals(-(112 + 7.74 / 60), lit("`(_fn\"Oj/", dest = "S32UPT").lon!!, 1e-5)
        assertEquals((20 * 1.852).toInt(), p.vitesseKmh)
        assertEquals(251, p.cap)
        assertEquals("/j", p.symbole)
        assertEquals("Returning", p.etatMicE)  // S, 3, 2: message bits 1-0-0
    }

    @Test
    fun altitudes() {
        assertEquals((1234 * 0.3048).toInt(), lit("!4903.50N/07201.75W-Maison /A=001234").altitudeM)
        assertEquals("Maison", lit("!4903.50N/07201.75W-Maison /A=001234").commentaire)
        val m = lit("`(_fn\"Oj/]\"7q}Lost in the West!", dest = "S32U6T")
        assertEquals("Lost in the West!", m.commentaire)
        assertEquals((1 * 91 * 91 + 22 * 91 + 80) - 10000, m.altitudeM)  // "\"7q" in base 91
    }

    @Test
    fun message_hotg_et_accuse() {
        val m = lit(":ANSRVR   :CQ HOTG Bonjour de JN18{12")
        assertEquals(TypeAprs.MESSAGE, m.type)
        assertEquals("ANSRVR", m.destinataire)
        assertEquals("CQ HOTG Bonjour de JN18", m.message)
        assertEquals("12", m.idMessage)
        assertTrue(m.hotg)
        val a = lit(":F4IOZ    :ack12", de = "ANSRVR")
        assertEquals(TypeAprs.ACCUSE, a.type)
        assertEquals("12", a.idMessage)
        assertFalse(lit(":F5RRO    :salut{3").hotg)
    }

    @Test
    fun statut_objet_nmea() {
        assertEquals("QRV sur l'ISS", lit(">QRV sur l'ISS\r").commentaire)
        val o = lit(";LEADER   *092345z4903.50N/07201.75W>088/036")
        assertEquals(TypeAprs.OBJET, o.type); assertEquals("LEADER", o.nom); assertEquals(49.058, o.lat!!, 1e-3)
        val g = lit("\$GPRMC,063909,A,3349.4302,N,11700.3721,W,43.022,89.3,291099,13.6,E*52")
        assertEquals(33 + 49.4302 / 60, g.lat!!, 1e-6)
    }

    @Test
    fun via_iss() {
        val p = lit("=4852.55N/00219.00E-", relais = listOf(Adresse("RS0ISS", 0, true)))
        assertTrue(p.viaIss)
        assertFalse(lit("=4852.55N/00219.00E-", relais = listOf(Adresse("ARISS"))).viaIss)  // not yet repeated
    }

    @Test
    fun illisible_garde_en_texte() {
        val p = lit("!garbage")
        assertEquals(TypeAprs.AUTRE, p.type)
        assertEquals("!garbage", p.commentaire)
        assertNull(p.lat)
    }
}
