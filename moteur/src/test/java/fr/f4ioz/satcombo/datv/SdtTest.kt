/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The SDT read from a transport stream: names, character tables, sections across packets, bad CRCs. */
class SdtTest {

    private fun flux(section: ByteArray, cc0: Int = 0): ByteArray {
        val p = CarteTs.paquets(section, LecteurSdt.PID)
        p.forEachIndexed { i, x -> x[3] = (0x10 or ((cc0 + i) and 15)).toByte() }
        return p.fold(ByteArray(0)) { a, b -> a + b }
    }

    @Test
    fun une_section_un_service() {
        val s = LecteurSdt.lis(LecteurSdt.section("F4IOZ", "F4IOZ-Paris"))!!
        assertEquals(1, s.size)
        assertEquals("F4IOZ", s[0].fournisseur)
        assertEquals("F4IOZ-Paris", s[0].nom)
        assertEquals(1, s[0].type)
    }

    @Test
    fun crc_faux_refuse() {
        val s = LecteurSdt.section("A", "B")
        s[s.size - 6] = (s[s.size - 6] + 1).toByte()
        assertNull(LecteurSdt.lis(s))
    }

    @Test
    fun lu_dans_le_flux_coupe_n_importe_ou() {
        val l = LecteurSdt()
        val nul = ByteArray(188) { if (it == 0) 0x47 else if (it == 1) 0x1F else if (it == 2) 0xFF.toByte() else 0 }
        val f = nul + flux(LecteurSdt.section("BATC", "G4XYZ")) + nul
        // In blocks of 100 bytes: packets cut across blocks.
        var i = 0
        while (i < f.size) { val n = minOf(100, f.size - i); l.bloc(f.copyOfRange(i, i + n)); i += n }
        assertEquals("G4XYZ", l.nom)
        assertEquals("BATC", l.fournisseur)
    }

    @Test
    fun section_sur_plusieurs_paquets() {
        val longNom = "X".repeat(120)
        val l = LecteurSdt()
        l.bloc(flux(LecteurSdt.section("P".repeat(100), longNom)))
        assertEquals(longNom, l.nom)
    }

    @Test
    fun tables_de_caracteres() {
        // 0x15: UTF-8; none: Latin; control codes dropped.
        val u = byteArrayOf(0x15) + "Émile".toByteArray(Charsets.UTF_8)
        assertEquals("Émile", LecteurSdt.texte(u, 0, u.size))
        val l = byteArrayOf('A'.code.toByte(), 0x86.toByte(), 'B'.code.toByte(), 0x87.toByte())
        assertEquals("AB", LecteurSdt.texte(l, 0, l.size))
        val iso = byteArrayOf(0x10, 0x00, 0x0F, 0xA4.toByte())
        assertEquals("€", LecteurSdt.texte(iso, 0, iso.size))
    }

    @Test
    fun autre_table_ignoree() {
        val s = LecteurSdt.section("A", "B")
        s[0] = 0x46
        assertNull(LecteurSdt.lis(s))
    }
}
