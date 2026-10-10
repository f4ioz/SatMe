/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.meteor.MeteorCarte
import fr.f4ioz.satcombo.meteor.MeteorImage
import fr.f4ioz.satcombo.meteor.MsuMr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Coastlines on the METEOR picture: the pass of the test recording
 * (METEOR-M2-4, 05/03/2026 from 00:21:03.695 UTC, 565 scans, going north
 * over Alaska), with elements of October 2026.
 */
class MeteorCarteTest {

    private val l1 = "1 59051U 24039A   26283.26249086 -.00000004  00000-0  18042-4 0  9990"
    private val l2 = "2 59051  98.7174 241.1900 0006701 188.4474 171.6592 14.22439544135621"
    /** First scan, as the packets give it: day 9195, 1 263 695 ms. */
    private val t0Paquets = 9195L * 86_400_000L + 1_263_695L
    private val orbite = MeteorCarte.orbite(l1, l2, MeteorCarte.utc(t0Paquets), 1231.0, 565)!!

    private fun px(latDeg: Double, lonDeg: Double) =
        MeteorCarte.pixel(orbite, MeteorCarte.ecef(Math.toRadians(latDeg), Math.toRadians(lonDeg)))

    @Test
    fun le_jour_des_paquets_tombe_le_5_mars() {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        assertEquals("2026-03-05 00:21:03.695", f.format(java.util.Date(MeteorCarte.utc(t0Paquets))))
    }

    /** Places measured with the same geometry by an independent computation. */
    @Test
    fun des_villes_tombent_a_leur_place() {
        for ((nom, lieu, attendu) in listOf(
            Triple("Kodiak", 57.79 to -152.40, 457.0 to 1931.3),
            Triple("Anchorage", 61.22 to -149.90, 314.2 to 2277.6),
            Triple("Nome", 64.50 to -165.41, 839.1 to 2819.9),
            Triple("Anadyr", 64.73 to 177.51, 1332.6 to 3230.6))) {
            val q = px(lieu.first, lieu.second)
            assertNotNull(nom, q)
            assertEquals("$nom colonne", attendu.first, q!![0], 3.0)
            assertEquals("$nom ligne", attendu.second, q[1], 3.0)
        }
    }

    @Test
    fun sous_le_satellite_c_est_le_milieu_de_la_ligne() {
        val p = orbite.pos[200]
        val r = kotlin.math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2])
        // Straight down, on the sphere through the ground: close enough to nadir.
        val g = DoubleArray(3) { p[it] / r * 6360.0 }
        val q = MeteorCarte.pixel(orbite, g)!!
        assertEquals(MsuMr.LARGEUR / 2.0, q[0], 15.0)
        assertEquals(1600.0, q[1], 3.0)
    }

    @Test
    fun ce_que_le_satellite_ne_voit_pas_n_a_pas_de_place() {
        assertNull("Paris", px(48.85, 2.35))
        assertNull("Antipodes", px(-60.0, 30.0))
        // Beyond the edge of the scan: 2000 km to the side of Kodiak.
        assertNull("Atlantique", px(45.0, -100.0))
    }

    @Test
    fun le_trait_est_retourne_avec_l_image_d_un_passage_montant() {
        val h = 565 * 8
        val droit = MeteorImage.Image(MsuMr.LARGEUR, h, IntArray(MsuMr.LARGEUR * h))
        val tourne = MeteorImage.Image(MsuMr.LARGEUR, h, IntArray(MsuMr.LARGEUR * h))
        val kodiak = floatArrayOf(-152.40f, 57.79f, -152.30f, 57.80f)
        assertEquals(1, MeteorCarte.trace(droit, orbite, listOf(kodiak), h, false, 1, 0xFFFF00, 255))
        assertEquals(1, MeteorCarte.trace(tourne, orbite, listOf(kodiak), h, true, 1, 0xFFFF00, 255))
        assertTrue(droit.pixels[1931 * MsuMr.LARGEUR + 457] != 0)
        assertTrue(tourne.pixels[(h - 1 - 1931) * MsuMr.LARGEUR + (MsuMr.LARGEUR - 1 - 457)] != 0)
    }

    @Test
    fun un_anneau_coupe_a_180_degres_ne_trace_pas_le_meridien() {
        val h = 565 * 8
        val img = MeteorImage.Image(MsuMr.LARGEUR, h, IntArray(MsuMr.LARGEUR * h))
        val coupe = floatArrayOf(180f, 64f, 180f, 66f, -180f, 66f)
        assertEquals(0, MeteorCarte.trace(img, orbite, listOf(coupe), h, true, 1, 0xFFFF00))
    }

    /**
     * The real picture, when its channels are on this computer
     * (~/SatMe-atelier/meteor/brut, written by [MeteorFichierTest] with
     * METEOR_PNG): drawn with the coasts, for the eye.
     */
    @Test
    fun la_vraie_image_avec_ses_cotes() {
        val brut = File(System.getProperty("user.home"), "SatMe-atelier/meteor/brut")
        val terres = File("src/main/res/raw/land.json")
        assumeTrue(File(brut, "canal-64.pgm").isFile && terres.isFile)
        fun pgm(n: Int): ByteArray = File(brut, "canal-$n.pgm").readBytes().let { b ->
            var nl = 0; var i = 0
            while (nl < 3) { if (b[i] == '\n'.code.toByte()) nl++; i++ }
            b.copyOfRange(i, b.size)
        }
        val msu = MsuMr()
        val v1 = pgm(64); val v2 = pgm(65)
        val h = v1.size / MsuMr.LARGEUR
        val pas = 2
        val w = MsuMr.LARGEUR / pas; val hh = h / pas
        val img = MeteorImage.Image(w, hh, IntArray(w * hh) { k ->
            val y = h - 1 - (k / w) * pas; val x = MsuMr.LARGEUR - 1 - (k % w) * pas
            val r = v2[y * MsuMr.LARGEUR + x].toInt() and 0xFF; val b = v1[y * MsuMr.LARGEUR + x].toInt() and 0xFF
            (0xFF shl 24) or (r shl 16) or (r shl 8) or b
        })
        // org.json is an empty shell off the phone: the rings read by hand.
        val traits = terres.readText().trim().removePrefix("[[").removeSuffix("]]").split("],[")
            .map { r -> r.split(',').map { it.toFloat() }.toFloatArray() }
        val n = MeteorCarte.trace(img, orbite, traits, h, true, pas, 0xFFE040)
        println("segments $n")
        assertTrue(n > 1000)
        val sortie = File(brut.parentFile, "cotes-kotlin.ppm")
        sortie.outputStream().buffered().use { o ->
            o.write("P6\n$w $hh\n255\n".toByteArray())
            for (v in img.pixels) { o.write((v ushr 16) and 0xFF); o.write((v ushr 8) and 0xFF); o.write(v and 0xFF) }
        }
    }
}
