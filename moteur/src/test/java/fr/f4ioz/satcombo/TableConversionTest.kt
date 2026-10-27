/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.BandeConversion
import fr.f4ioz.satcombo.domain.Convertisseur
import fr.f4ioz.satcombo.domain.TableConversion
import fr.f4ioz.satcombo.domain.UsageConversion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TableConversionTest {

    private val lnbCle = BandeConversion("LNB → clé", 10_489_000_000L, 10_500_000_000L, 9_750_000_000L, sdr = true)
    private val down2m = BandeConversion("→ 2 m", 10_489_000_000L, 10_500_000_000L, 10_344_973_000L, catRx = true)
    private val up432 = BandeConversion("2400 ← 432", 2_400_000_000L, 2_450_000_000L, 1_968_000_000L, catTx = true)
    private val table = listOf(lnbCle, down2m, up432)

    @Test fun `first matching band per device`() {
        assertEquals(739_750_000L, TableConversion.versAppareil(table, 10_489_750_000L, UsageConversion.SDR))
        assertEquals(144_777_000L, TableConversion.versAppareil(table, 10_489_750_000L, UsageConversion.CAT_RX))
        assertEquals(432_050_000L, TableConversion.versAppareil(table, 2_400_050_000L, UsageConversion.CAT_TX))
        // The same sky band, two lines for the same device: the first wins.
        val deux = listOf(down2m, down2m.copy(nom = "→ 70 cm", olHz = 10_057_500_000L))
        assertEquals("→ 2 m", TableConversion.bande(deux, 10_489_750_000L, UsageConversion.CAT_RX)?.nom)
        assertEquals("→ 70 cm", TableConversion.bande(listOf(deux[0].copy(actif = false), deux[1]), 10_489_750_000L, UsageConversion.CAT_RX)?.nom)
    }

    @Test fun `no band, the device works at RF`() {
        assertNull(TableConversion.bande(table, 145_800_000L, UsageConversion.CAT_RX))
        assertEquals(145_800_000L, TableConversion.versAppareil(table, 145_800_000L, UsageConversion.CAT_RX))
        // The LNB is not in front of the rig's TX.
        assertEquals(10_489_750_000L, TableConversion.versAppareil(table, 10_489_750_000L, UsageConversion.CAT_TX))
    }

    @Test fun `inverted spectrum`() {
        val inv = BandeConversion("inv", 10_489_000_000L, 10_500_000_000L, 11_250_000_000L, inverse = true, sdr = true)
        assertEquals(760_250_000L, inv.fi(10_489_750_000L))
        assertEquals(10_489_750_000L, inv.rf(760_250_000L))
        // Higher in the sky, lower at the device.
        assertTrue(inv.fi(10_490_000_000L) < inv.fi(10_489_500_000L))
        assertNull(inv.probleme())
        // The same LO without the flag gives a negative IF: said, not used.
        assertNotNull(inv.copy(inverse = false).probleme())
        assertFalse(inv.copy(inverse = false).couvre(10_489_750_000L))
    }

    @Test fun `reading back takes the satellite's band only`() {
        // A LEO on 145.95 MHz read through the QO-100 2 m converter would show 10 490.9 MHz.
        assertEquals(145_950_000L, TableConversion.depuisAppareil(table, 145_950_000L, UsageConversion.CAT_RX, rfAttendu = 145_950_000L))
        assertEquals(10_489_750_000L, TableConversion.depuisAppareil(table, 144_777_000L, UsageConversion.CAT_RX, rfAttendu = 10_489_750_000L))
        assertEquals(10_489_750_000L, TableConversion.depuisAppareil(table, 739_750_000L, UsageConversion.SDR))
        assertEquals(100_000_000L, TableConversion.depuisAppareil(table, 100_000_000L, UsageConversion.SDR))
        assertTrue(TableConversion.fiConnue(table, 144_777_000L, UsageConversion.CAT_RX))
        assertFalse(TableConversion.fiConnue(table, 29_000_000L, UsageConversion.CAT_RX))
    }

    @Test fun `28 MHz transverter`() {
        val t = TableConversion.PRESETS.first { it.nom.startsWith("Transverter 2 m") }.copy(actif = true)
        assertEquals(29_800_000L, TableConversion.versAppareil(listOf(t), 145_800_000L, UsageConversion.CAT_RX))
        assertEquals(29_800_000L, TableConversion.versAppareil(listOf(t), 145_800_000L, UsageConversion.CAT_TX))
        assertTrue(TableConversion.fiConnue(listOf(t), 29_000_000L, UsageConversion.CAT_TX))
    }

    @Test fun `presets are usable and only the dongle one is on`() {
        TableConversion.PRESETS.forEach { assertNull(it.nom, it.probleme()) }
        assertEquals(listOf(true), TableConversion.PRESETS.filter { it.actif }.map { it.sdr })
        // Nothing on by default reaches a LEO band.
        assertNull(TableConversion.bande(TableConversion.PRESETS, 145_800_000L, UsageConversion.CAT_RX))
        assertNull(TableConversion.bande(TableConversion.PRESETS, 435_000_000L, UsageConversion.CAT_TX))
    }

    @Test fun `migration keeps the single LO first`() {
        val rx = Convertisseur(actif = true, olHz = 10_344_973_000L, basHz = 10_489_000_000L, hautHz = 10_500_000_000L)
        val tx = Convertisseur(actif = true, olHz = 1_968_000_000L, basHz = 2_390_000_000L, hautHz = 2_450_000_000L)
        val t = TableConversion.migre(rx, rxSdr = false, rxCat = true, tx = tx)
        assertEquals(TableConversion.CHAINE_RX, t[0].chaine)
        assertEquals(10_344_973_000L, t[0].olHz)
        assertTrue(t[0].catRx && !t[0].sdr)
        assertEquals(TableConversion.CHAINE_TX, t[1].chaine)
        assertTrue(t[1].catTx)
        // The presets follow, off: nothing changes by itself.
        assertTrue(t.drop(2).none { it.actif })
        assertEquals(144_777_000L, TableConversion.versAppareil(t, 10_489_750_000L, UsageConversion.CAT_RX))
        // Never set before: the presets.
        assertEquals(TableConversion.PRESETS, TableConversion.migre(Convertisseur(olHz = 9_750_000_000L), true, false, Convertisseur()))
    }

    @Test fun `chain update touches its lines only`() {
        val perso = BandeConversion("perso", 144_000_000L, 146_000_000L, 116_000_000L, catRx = true, actif = false)
        val rx = Convertisseur(actif = true, olHz = 9_750_000_000L, basHz = 10_489_000_000L, hautHz = 10_500_000_000L)
        val t1 = TableConversion.avecChaine(listOf(perso), rx, true, false, Convertisseur())
        assertEquals(listOf(TableConversion.CHAINE_RX, ""), t1.map { it.chaine })
        // Renamed by the operator, then the chain's LO changes: the name stays.
        val t2 = TableConversion.avecChaine(listOf(t1[0].copy(nom = "mon LNB"), perso), rx.copy(olHz = 9_749_990_000L), true, false, Convertisseur())
        assertEquals("mon LNB", t2[0].nom)
        assertEquals(9_749_990_000L, t2[0].olHz)
        assertEquals(perso, t2[1])
        // The chain's converter turned off: its line goes.
        assertEquals(listOf(perso), TableConversion.avecChaine(t2, rx.copy(actif = false), true, false, Convertisseur()))
    }

    @Test fun `written and read back`() {
        val t = table + BandeConversion("a|b", 1L, 2L, 3L, inverse = true, actif = false, chaine = "rx")
        val lu = TableConversion.lit(TableConversion.ecrit(t))!!
        assertEquals(t.dropLast(1), lu.dropLast(1))
        assertEquals("a/b", lu.last().nom)
        assertEquals("rx", lu.last().chaine)
        assertNull(TableConversion.lit(""))
        assertEquals(emptyList<BandeConversion>(), TableConversion.lit("rien|du|tout"))
    }
}
