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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit conversions: trivial, and trivially inverted without anyone noticing.
 * A flipped factor does not crash anything, it just shows a wrong number --
 * and a wrong distance sends an operator looking for a balloon in the wrong
 * place.
 */
class UnitsTest {

    private fun n(s: String) = s.replace(',', '.')

    @Test
    fun normalizeFallsBackOnMetric() {
        assertEquals(Units.METRIC, Units.normalize(null))
        assertEquals(Units.METRIC, Units.normalize(""))
        assertEquals(Units.METRIC, Units.normalize("   "))
        assertEquals(Units.METRIC, Units.normalize("parsecs"))
        assertEquals(Units.IMPERIAL, Units.normalize(" Imperial "))
        assertEquals(Units.NAUTICAL, Units.normalize("NAUTICAL"))
    }

    @Test
    fun theThreeSystemsAreAllNormalisable() {
        Units.ALL.forEach { assertEquals(it, Units.normalize(it)) }
        assertEquals(3, Units.ALL.size)
        assertEquals(Units.ALL.size, Units.ALL.toSet().size)
    }

    @Test
    fun factorsAreTheOfficialOnes() {
        // The international foot is exactly 0.3048 m and the nautical mile
        // exactly 1852 m: definitions, not measurements.
        assertEquals(1.0, Units.FEET_PER_METER * 0.3048, 1e-12)
        assertEquals(1.0, Units.NM_PER_KM * 1.852, 1e-12)
        assertEquals(1.609344, 1.0 / Units.MILES_PER_KM, 1e-9)
        assertEquals(Units.NM_PER_KM * 3.6, Units.KNOTS_PER_MPS, 1e-12)
    }

    @Test
    fun aMarathonReadsRightInEverySystem() {
        val km = 42.195
        assertEquals("42.2 km", n(Units.distance(km, Units.METRIC)))
        assertEquals("26.2 mi", n(Units.distance(km, Units.IMPERIAL)))
        assertEquals("22.8 NM", n(Units.distance(km, Units.NAUTICAL)))
    }

    @Test
    fun shortDistancesSwitchToTheSmallUnit() {
        // Below a kilometre use metres, below a mile use feet: "0.3 km" and
        // "0.19 mi" do not read at a glance.
        assertTrue(Units.distance(0.3, Units.METRIC).endsWith(" m"))
        assertTrue(Units.distance(0.3, Units.IMPERIAL).endsWith(" ft"))
        assertTrue(Units.distance(0.3, Units.NAUTICAL).endsWith(" m"))
        assertEquals("300 m", Units.distance(0.3, Units.METRIC))
        assertEquals("984 ft", Units.distance(0.3, Units.IMPERIAL))
        // Above the threshold the long unit comes back.
        assertTrue(Units.distance(5.0, Units.IMPERIAL).endsWith(" mi"))
        assertTrue(Units.distance(5.0, Units.NAUTICAL).endsWith(" NM"))
    }

    @Test
    fun roundedDistancesHaveNoDecimal() {
        assertEquals("412 km", Units.distanceRound(412.4, Units.METRIC))
        assertEquals("256 mi", Units.distanceRound(412.4, Units.IMPERIAL))
        assertEquals("223 NM", Units.distanceRound(412.4, Units.NAUTICAL))
        Units.ALL.forEach {
            assertTrue(it, !Units.distanceRound(412.4, it).contains('.'))
            assertTrue(it, !Units.distanceRound(412.4, it).contains(','))
        }
    }

    @Test
    fun distanceValueKeepsTheRawNumber() {
        assertEquals(100.0, Units.distanceValue(100.0, Units.METRIC), 1e-9)
        assertEquals(62.1371, Units.distanceValue(100.0, Units.IMPERIAL), 1e-4)
        assertEquals(53.9957, Units.distanceValue(100.0, Units.NAUTICAL), 1e-4)
    }

    @Test
    fun altitudeIsInFeetOutsideTheMetricWorld() {
        assertEquals("1000 m", Units.altitude(1000.0, Units.METRIC))
        assertEquals("3281 ft", Units.altitude(1000.0, Units.IMPERIAL))
        // Nautical reads altitude in feet, like aviation.
        assertEquals("3281 ft", Units.altitude(1000.0, Units.NAUTICAL))
        assertEquals(Units.altitude(180.0, Units.METRIC), Units.shortDistance(180.0, Units.METRIC))
    }

    @Test
    fun speedsUseTheUsualUnitOfEachSystem() {
        val mps = 10.0
        assertEquals("36 km/h", Units.speed(mps, Units.METRIC))
        assertEquals("22 mph", Units.speed(mps, Units.IMPERIAL))
        assertEquals("19 kt", Units.speed(mps, Units.NAUTICAL))
    }

    @Test
    fun verticalSpeedKeepsItsSignBecauseThatIsTheBurst() {
        // Plus turning to minus is the balloon burst: the sign carries more
        // information than the value.
        assertTrue(Units.vertical(5.2, Units.METRIC).startsWith("+"))
        assertTrue(Units.vertical(-42.0, Units.METRIC).startsWith("-"))
        assertEquals("+5.2 m/s", n(Units.vertical(5.2, Units.METRIC)))
        assertTrue(Units.vertical(5.2, Units.IMPERIAL).endsWith(" ft/s"))
        assertTrue(Units.vertical(5.2, Units.NAUTICAL).endsWith(" ft/s"))
    }

    @Test
    fun unitLabelsMatchWhatIsPrinted() {
        Units.ALL.forEach {
            assertTrue(it, Units.distanceRound(400.0, it).endsWith(Units.distanceUnit(it)))
        }
    }

    @Test
    fun unknownSystemNeverThrowsAndAlwaysPrints() {
        listOf("", "  ", "furlongs", "METRIC").forEach {
            assertTrue(Units.distance(12.0, it).isNotBlank())
            assertTrue(Units.altitude(12.0, it).isNotBlank())
            assertTrue(Units.speed(12.0, it).isNotBlank())
            assertTrue(Units.vertical(12.0, it).isNotBlank())
        }
    }
}
