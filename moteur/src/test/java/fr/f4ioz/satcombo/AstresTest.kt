/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.Astres
import fr.f4ioz.satcombo.domain.BruitSolaire
import fr.f4ioz.satcombo.domain.Lune
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** The Sun and the Moon as rotor targets: the Moon's series against a published worked example, its parallax, the Sun as before. */
class AstresTest {

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()
    private fun ecartAngle(a: Double, b: Double) = abs(((a - b) % 360.0 + 540.0) % 360.0 - 180.0)

    /**
     * Meeus, Astronomical Algorithms, example 47.a: 1992 April 12, 0h TD —
     * λ 133.163°, β −3.229°, parallax 0.992°, RA 134.688°, Dec 13.768°.
     * The low-precision series is good to a few tenths of a degree.
     */
    @Test
    fun la_lune_du_12_avril_1992() {
        val t = ms("1992-04-12T00:00:00Z") - 59_000L
        val e = Lune.ecliptique(t)
        assertEquals(133.163, e[0], 0.3)
        assertEquals(-3.229, e[1], 0.2)
        assertEquals(0.992, e[2], 0.01)
        val q = Lune.equatoriale(t)
        assertTrue("RA ${q[0]}", ecartAngle(134.688, q[0]) < 0.4)
        assertEquals(13.768, q[1], 0.3)
        // 368 410 km: 57.8 Earth radii.
        assertEquals(57.76, q[2], 0.6)
    }

    /** Seen from the surface the Moon is lower than from the Earth's centre, by the parallax times the cosine of its elevation. */
    @Test
    fun la_parallaxe_abaisse_la_lune() {
        val lat = 48.85; val lon = 2.35
        for (iso in listOf("2026-11-01T02:00:00Z", "2026-11-05T20:00:00Z", "2026-11-20T15:00:00Z")) {
            val t = ms(iso)
            val (ra, dec, r) = Lune.equatoriale(t).let { Triple(it[0], it[1], it[2]) }
            val ha = Math.toRadians(Lune.tsmg(t) + lon - ra); val d = Math.toRadians(dec); val f = Math.toRadians(lat)
            val elGeo = Math.toDegrees(asin(sin(f) * sin(d) + cos(f) * cos(d) * cos(ha)))
            val azGeo = (Math.toDegrees(atan2(-cos(d) * sin(ha), cos(f) * sin(d) - sin(f) * cos(d) * cos(ha))) + 360) % 360
            val (az, el) = Lune.azElDeg(lat, lon, 0.0, t)
            val par = Math.toDegrees(asin(1.0 / r))
            assertEquals("$iso", elGeo - par * cos(Math.toRadians(elGeo)), el, 0.05)
            assertTrue("$iso az", ecartAngle(azGeo, az) < 0.3)
        }
    }

    @Test
    fun le_soleil_est_celui_du_bruit_solaire_et_la_refraction_releve_la_lune() {
        val t = ms("2026-06-21T12:00:00Z")
        assertEquals(BruitSolaire.soleil(48.85, 2.35, t), Astres.azEl(Astres.Astre.SOLEIL, 48.85, 2.35, 0.0, t))
        // Midsummer noon in Paris: the Sun high in the south.
        val (az, el) = Astres.azEl(Astres.Astre.SOLEIL, 48.85, 2.35, 0.0, t)
        assertEquals(180.0, az, 10.0); assertEquals(64.6, el, 1.0)
        val m = ms("2026-11-01T02:00:00Z")
        val brut = Lune.azElDeg(48.85, 2.35, 0.0, m).second
        assertEquals(brut + Astres.refraction(brut), Astres.azEl(Astres.Astre.LUNE, 48.85, 2.35, 0.0, m).second, 1e-9)
        assertEquals(0.0, Astres.refraction(-5.0), 0.0)
        assertTrue(Astres.refraction(0.0) in 0.4..0.6)
        assertEquals(Astres.Astre.LUNE, Astres.astre(" Lune "))
        assertNull(Astres.astre("mars"))
    }
}
