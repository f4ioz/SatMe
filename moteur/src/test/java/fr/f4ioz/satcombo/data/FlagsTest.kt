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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The flag catalogue is pure Kotlin, so testable without a Canvas — and that
 * is exactly where typos slip in that would leave a blank flag in the picker.
 */
class FlagsTest {

    private val KINDS = setOf(
        Flags.STRIPES_V, Flags.STRIPES_H, Flags.NORDIC, Flags.CROSS, Flags.DISC,
        Flags.TRIANGLE, Flags.UNION, Flags.CANTON_UNION, Flags.USA, Flags.GREECE,
        Flags.CANADA, Flags.LOZENGE, Flags.ERMINE, Flags.ERMINE_HOIST,
        Flags.STAR, Flags.CRESCENT)

    @Test
    fun codesAreUniqueAndUpperCase() {
        val codes = Flags.ALL.map { it.code }
        assertEquals(codes.size, codes.toSet().size)
        codes.forEach { assertEquals(it.uppercase(), it) }
        codes.forEach { assertTrue(it.isNotBlank()) }
    }

    @Test
    fun everyFlagHasAKnownKind() {
        Flags.ALL.forEach { assertTrue(it.code, it.kind in KINDS) }
    }

    @Test
    fun ratiosArePlausible() {
        // No real flag is taller than wide or three times longer than tall:
        // outside that range it is a typo.
        Flags.ALL.forEach {
            assertTrue(it.code, it.ratio >= 0.9f && it.ratio <= 3f)
        }
    }

    @Test
    fun everyFlagHasColours() {
        Flags.ALL.forEach {
            assertTrue(it.code, it.colors.isNotEmpty())
            // Opaque: a semi-transparent colour would let the photo show through.
            it.colors.forEach { c -> assertEquals(0xFF, (c ushr 24) and 0xFF) }
        }
    }

    @Test
    fun weightsMatchTheColourCountWhenGiven() {
        Flags.ALL.filter { it.kind == Flags.STRIPES_V || it.kind == Flags.STRIPES_H }
            .forEach {
                if (it.weights.isNotEmpty())
                    assertEquals(it.code, it.colors.size, it.weights.size)
            }
    }

    @Test
    fun byCodeIsForgivingAboutCaseAndSpaces() {
        assertEquals("FR", Flags.byCode(" fr ")?.code)
        assertEquals(Flags.BZH, Flags.byCode("bzh")?.code)
        assertNotNull(Flags.byCode(Flags.BIGOUDEN))
    }

    @Test
    fun byCodeReturnsNullOnNothing() {
        assertNull(Flags.byCode(""))
        assertNull(Flags.byCode(null))
        assertNull(Flags.byCode("   "))
        assertNull(Flags.byCode("ZZ"))
    }

    @Test
    fun bothBretonFlagsComeFirstAndEachHasItsOwnErmineLayout() {
        assertEquals(Flags.BZH, Flags.ALL[0].code)
        assertEquals(Flags.BIGOUDEN, Flags.ALL[1].code)
        // The Gwenn ha Du canton is less than half the flag height; the
        // Bigouden panel runs to the bottom. Two layouts, two kinds: mixing
        // them up once produced a wrong Bigouden flag.
        assertEquals(Flags.ERMINE, Flags.ALL[0].kind)
        assertEquals(Flags.ERMINE_HOIST, Flags.ALL[1].kind)
    }

    @Test
    fun theBigoudenPanelIsYellowAndItsErminesRed() {
        val f = Flags.byCode(Flags.BIGOUDEN)!!
        // band 1 red, band 2 yellow, yellow panel, red ermines.
        assertEquals(f.colors[1], f.colors[2])
        assertEquals(f.colors[0], f.colors[3])
        assertTrue(f.colors[0] != f.colors[1])
    }

    @Test
    fun ermineRowsOfSpreadsTheRemainderInstedOfALonelyLastRow() {
        val rows = Flags.ermineRowsOf(22, 3)
        assertEquals(22, rows.sum())
        assertTrue(rows.toString(), rows.all { it in 2..3 })
        assertEquals(listOf(3, 3, 3), Flags.ermineRowsOf(9, 3))
        assertEquals(listOf(1), Flags.ermineRowsOf(1, 3))
        assertTrue(Flags.ermineRowsOf(0, 3).isEmpty())
        assertTrue(Flags.ermineRowsOf(-2, 3).isEmpty())
        assertTrue(Flags.ermineRowsOf(10, 0).isEmpty())
        listOf(1, 2, 5, 11, 22, 30).forEach {
            assertEquals(it, Flags.ermineRowsOf(it, 3).sum())
            assertEquals(it, Flags.ermineRowsOf(it, 4).sum())
        }
    }

    @Test
    fun theCatalogueCoversTheUsualRadioNeighbours() {
        // Russia included: a French ham works Russian stations on RS-44 weekly.
        listOf("RU", "FR", "GB", "DE", "US", "JP", "BR", "IN", "TR", "NZ")
            .forEach { assertNotNull(it, Flags.byCode(it)) }
        assertTrue("catalogue trop court", Flags.ALL.size >= 45)
    }

    @Test
    fun ermineFlagsCarryFourColoursAndTheirBands() {
        // band 1, band 2, canton background, ermine spot colour.
        listOf(Flags.BZH, Flags.BIGOUDEN).forEach { code ->
            val f = Flags.byCode(code)!!
            assertEquals(code, 4, f.colors.size)
            assertTrue(code, f.spots > 0)
            assertTrue(code, f.weights.size >= 5)
        }
        assertEquals(9, Flags.byCode(Flags.BZH)!!.weights.size)
        assertEquals(11, Flags.byCode(Flags.BZH)!!.spots)
        assertEquals(5, Flags.byCode(Flags.BIGOUDEN)!!.weights.size)
        assertEquals(22, Flags.byCode(Flags.BIGOUDEN)!!.spots)
    }

    @Test
    fun ermineRowsAddUpToTheSpotCount() {
        assertEquals(listOf(4, 3, 4), Flags.ermineRows(11))
        assertEquals(listOf(5, 4, 5, 4, 4), Flags.ermineRows(22))
        assertEquals(11, Flags.ermineRows(11).sum())
        assertEquals(22, Flags.ermineRows(22).sum())
        listOf(1, 3, 7, 13, 30).forEach {
            assertEquals(it, Flags.ermineRows(it).sum())
        }
        assertTrue(Flags.ermineRows(0).isEmpty())
        assertTrue(Flags.ermineRows(-4).isEmpty())
    }

    @Test
    fun codesListMirrorsTheCatalogue() {
        assertEquals(Flags.ALL.size, Flags.CODES.size)
        Flags.CODES.forEach { assertNotNull(it, Flags.byCode(it)) }
    }

    @Test
    fun franceIsThereBecauseThatIsWhereTheAppIsWritten() {
        val fr = Flags.byCode("FR")!!
        assertEquals(Flags.STRIPES_V, fr.kind)
        assertEquals(3, fr.colors.size)
        assertEquals(1.5f, fr.ratio, 0.001f)
    }
}
