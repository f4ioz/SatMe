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

/** « Messages »: to me whatever the SSID, the bulletins, the repeats counted once, via satellite marked, never my own. */
class MessagesAprsTest {

    private fun p(t: Long, src: String, info: String, chemin: List<String> = emptyList(), emis: Boolean = false) =
        Aprs.lit(Trame(Adresse.de("APRS"), Adresse.de(src), chemin.map { Adresse.de(it) }, info.toByteArray(Charsets.ISO_8859_1)), t).copy(emis = emis)

    @Test
    fun pour_moi_quel_que_soit_le_ssid() {
        assertTrue(MessagesAprs.pourMoi("F4XYZ", "f4xyz"))
        assertTrue(MessagesAprs.pourMoi("F4XYZ-7", "F4XYZ"))
        assertTrue(MessagesAprs.pourMoi("F4XYZ", "F4XYZ-9"))
        assertTrue(MessagesAprs.pourMoi(" f4xyz-10 ", "F4XYZ-9"))
        assertFalse(MessagesAprs.pourMoi("F4XYZA", "F4XYZ"))
        assertFalse(MessagesAprs.pourMoi("F4XY", "F4XYZ"))
        assertFalse(MessagesAprs.pourMoi("F4XYZ", ""))
        assertFalse(MessagesAprs.pourMoi(null, "F4XYZ"))
        assertTrue(MessagesAprs.bulletin("BLN1")); assertTrue(MessagesAprs.bulletin("BLNA")); assertTrue(MessagesAprs.bulletin("BLN1CLUB"))
        assertFalse(MessagesAprs.bulletin("BLN")); assertFalse(MessagesAprs.bulletin("F4XYZ")); assertFalse(MessagesAprs.bulletin(null))
    }

    @Test
    fun la_boite() {
        val l = listOf(
            p(1_000, "QF1ABC", ":F4XYZ-7  :Salut !{21"),
            p(31_000, "QF1ABC", ":F4XYZ-7  :Salut !{21", listOf("QF6DIG-2*")),        // the same, repeated
            p(2_000, "QF1ABC", ":QF8MOB-9 :Pas pour moi{3"),
            p(3_000, "QG7ISS", ":F4XYZ    :73 via l'ISS{7", listOf("RS0ISS*", "WIDE2-1")),
            p(4_000, "QF6DIG-2", ":BLN1CLUB :Réunion vendredi"),
            p(5_000, "QF1ABC", ":F4XYZ    :ack21"),                                     // an ack: not a message
            p(6_000, "F4XYZ-9", ":QF1ABC   :Oui !{4"),                                    // my own, heard back
            p(7_000, "F4XYZ", ":QF1ABC   :Envoyé d'ici{5", emis = true),
            p(8_000, "QF1ABC", "!4903.50N/00211.00E-Position"),
        )
        val b = MessagesAprs.boite(l, "F4XYZ")
        assertEquals(listOf("QF6DIG-2", "QG7ISS", "QF1ABC"), b.map { it.de })
        val salut = b.last()
        assertEquals(2, salut.fois)
        assertEquals(1_000L, salut.t)
        assertEquals("21", salut.id)
        assertEquals("Salut !", salut.texte)
        assertEquals(MessagesAprs.Genre.POUR_MOI, salut.genre)
        assertNull(salut.satellite)
        val iss = b[1]
        assertEquals("ISS", iss.satellite)
        assertEquals(MessagesAprs.Genre.POUR_MOI, iss.genre)
        assertEquals(MessagesAprs.Genre.BULLETIN, b[0].genre)
        // No callsign set: the bulletins only.
        assertEquals(listOf("QF6DIG-2"), MessagesAprs.boite(l, "").map { it.de })
        val o = MessagesAprs.json(b, "F4XYZ-9")
        assertEquals("F4XYZ", o.getString("moi"))
        assertEquals(2, o.getInt("pourMoi"))
        assertEquals(1, o.getInt("bulletins"))
        assertEquals("ISS", o.getJSONArray("messages").getJSONObject(1).getString("sat"))
    }

    @Test
    fun le_meme_bulletin_le_lendemain_est_un_nouveau_message() {
        val h = 3_600_000L
        val l = listOf(p(0, "QF6DIG-2", ":BLN1     :Net ce soir"), p(10 * 60_000L, "QF6DIG-2", ":BLN1     :Net ce soir"),
            p(24 * h, "QF6DIG-2", ":BLN1     :Net ce soir"), p(24 * h + 60_000L, "QF6DIG-2", ":BLN1     :Net ce soir"))
        val b = MessagesAprs.boite(l, "F4XYZ")
        assertEquals(2, b.size)
        assertEquals(listOf(2, 2), b.map { it.fois })
    }
}
