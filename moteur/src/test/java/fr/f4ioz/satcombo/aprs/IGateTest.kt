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

/** The receive-only iGate's rules. Fictitious callsigns only. */
class IGateTest {

    private fun t(src: String, chemin: String, info: String, dst: String = "APRS") =
        Trame(Adresse.de(dst), Adresse.de(src), chemin.split(',').filter { it.isNotBlank() }.map { Adresse.de(it) },
            info.toByteArray(Charsets.ISO_8859_1))

    @Test
    fun `une trame entendue part avec qAR et son chemin tel quel`() {
        assertEquals("F4XYZ-9>APRS,WIDE1-1*,WIDE2-1,qAR,F4ABC-10:!4852.00N/00220.00E>",
            IGate.ligne(t("F4XYZ-9", "WIDE1-1*,WIDE2-1", "!4852.00N/00220.00E>"), "f4abc-10"))
    }

    /** Through the ISS or another satellite: the path heard is kept. */
    @Test
    fun `une trame recue par satellite garde son chemin`() {
        assertEquals("EA1XYZ>APRS,RS0ISS*,ARISS,qAR,F4ABC-10:=4000.00N/00400.00W-",
            IGate.ligne(t("EA1XYZ", "RS0ISS*,ARISS", "=4000.00N/00400.00W-"), "F4ABC-10"))
    }

    @Test
    fun `NOGATE et RFONLY ne passent pas`() {
        assertEquals(IGate.Refus.NOGATE, IGate.refus(t("F4XYZ", "NOGATE", ">test")))
        assertEquals(IGate.Refus.NOGATE, IGate.refus(t("F4XYZ", "WIDE1-1*,RFONLY", ">test")))
        assertNull(IGate.ligne(t("F4XYZ", "RFONLY", ">test"), "F4ABC-10"))
    }

    @Test
    fun `ce qui vient deja d'Internet ne repart pas`() {
        assertEquals(IGate.Refus.INTERNET, IGate.refus(t("F4XYZ", "TCPIP*", ">test")))
        assertEquals(IGate.Refus.INTERNET, IGate.refus(t("F4XYZ", "TCPXX*", ">test")))
        // A third-party frame put on the air by an IS → RF gateway: its inner path says TCPIP.
        assertEquals(IGate.Refus.INTERNET, IGate.refus(t("F4GW", "WIDE1-1", "}DL1XYZ>APRS,TCPIP,F4GW*:>bonjour")))
        assertEquals(IGate.Refus.INTERNET, IGate.refus(t("F4GW", "", "}DL1XYZ>APRS,qAC,T2TEST:>bonjour")))
    }

    @Test
    fun `une trame de tiers sans Internet part deballee`() {
        assertEquals("DL1XYZ>APRS,WIDE1-1*,qAR,F4ABC-10:>bonjour",
            IGate.ligne(t("F4GW", "", "}DL1XYZ>APRS,WIDE1-1*:>bonjour"), "F4ABC-10"))
        assertEquals(IGate.Refus.TIERS, IGate.refus(t("F4GW", "", "}n'importe quoi")))
    }

    @Test
    fun `requetes et trames vides refusees, information coupee au premier CR`() {
        assertEquals(IGate.Refus.REQUETE, IGate.refus(t("F4XYZ", "", "?APRS?")))
        assertEquals(IGate.Refus.VIDE, IGate.refus(t("F4XYZ", "", "")))
        assertEquals("F4XYZ>APRS,qAR,F4ABC-10:>un", IGate.ligne(t("F4XYZ", "", ">un\rdeux\nTROIS"), "F4ABC-10"))
    }

    @Test
    fun `la meme trame une fois en 30 s, quel que soit son chemin`() {
        val f = IGate.Filtre()
        val a = t("F4XYZ-9", "WIDE1-1*", ">test")
        val b = t("F4XYZ-9", "WIDE1-1*,WIDE2-1*", ">test")
        assertTrue(f.passe(a, 0L, "F4ABC-10").first != null)
        assertEquals(IGate.Refus.DOUBLON, f.passe(b, 10_000L, "F4ABC-10").second)
        assertEquals(IGate.Refus.DOUBLON, f.passe(a, 29_999L, "F4ABC-10").second)
        assertTrue(f.passe(a, 30_000L, "F4ABC-10").first != null)
        // Another text from the same station is not a duplicate.
        assertTrue(f.passe(t("F4XYZ-9", "WIDE1-1*", ">autre"), 30_001L, "F4ABC-10").first != null)
    }

    @Test
    fun `reglages lus`() {
        assertEquals("euro.aprs2.net" to 14580, IGate.serveur(IGate.SERVEUR_DEFAUT))
        assertEquals("127.0.0.1" to 14580, IGate.serveur("127.0.0.1"))
        assertNull(IGate.serveur("hote:99999")); assertNull(IGate.serveur("a b:1")); assertNull(IGate.serveur(""))
        assertTrue(IGate.indicatifValide("f4xyz-10")); assertFalse(IGate.indicatifValide("F4 XYZ")); assertFalse(IGate.indicatifValide("F4XYZ-123"))
        assertTrue(IGate.passcodeValide("123")); assertFalse(IGate.passcodeValide("-1")); assertFalse(IGate.passcodeValide("40000")); assertFalse(IGate.passcodeValide("abc"))
        assertEquals(true, IGate.reponseConnexion("# logresp F4ABC-10 verified, server T2TEST"))
        assertEquals(false, IGate.reponseConnexion("# logresp F4ABC-10 unverified, server T2TEST"))
        assertNull(IGate.reponseConnexion("# aprsc 2.1 1 Nov 2026 T2TEST"))
        assertEquals("T2TEST", IGate.nomServeur("# logresp F4ABC-10 verified, server T2TEST"))
    }
}
