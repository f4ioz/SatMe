/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The PC's catalogue: AMSAT's bulletin, and from SatNOGS only what AMSAT lacks (the METEOR-M). */
class CataloguePcTest {

    private val dossier = File(System.getProperty("java.io.tmpdir"), "catalogue-" + System.nanoTime()).apply { mkdirs() }
    @After fun fin() { dossier.deleteRecursively() }

    @Test
    fun les_meteor_de_satnogs_completent_amsat_sans_le_remplacer() {
        val sn = Catalogue.lisTleSatnogs("""[{"tle0":"0 METEOR M2-4","tle1":"1 59051U 24039A   26283.26249086 -.00000004  00000-0  18042-4 0  9990","tle2":"2 59051  98.7174 241.1900 0006701 188.4474 171.6592 14.22439544135621","norad_cat_id":59051},
            {"tle0":"0 ISS (ZARYA)","tle1":"1 25544U 98067A   26284.17638745  .00009430  00000-0  18014-3 0  9996","tle2":"2 25544  51.6312  86.0933 0006713 248.1316 111.8959 15.48821882589734","norad_cat_id":25544}]""")
        assertEquals(listOf("METEOR M2-4", "ISS (ZARYA)"), sn.map { it.name })
        File(dossier, "amsat-tle.txt").writeText("""ISS
1 25544U 98067A   26284.17638745  .00009430  00000-0  18014-3 0  9996
2 25544  51.6312  86.0933 0006713 248.1316 111.8959 15.48821882589734
""")
        File(dossier, "satnogs-tle.txt").writeText(sn.joinToString("") { it.name + "\n" + it.line1 + "\n" + it.line2 + "\n" })
        val l = Catalogue(dossier, enLigne = false).satellites()
        // AMSAT's name for the ISS stays; the METEOR comes from SatNOGS.
        assertEquals(listOf("ISS", "METEOR M2-4"), l.map { it.name })
        assertTrue(fr.f4ioz.satcombo.meteor.RecepteurMeteor.emetLrpt(l[1].catalogNumber, l[1].name))
        assertTrue(Catalogue.interdit("https://celestrak.org/NORAD/elements/gp.php"))
    }
}
