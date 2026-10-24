/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

import fr.f4ioz.satcombo.data.SatPass
import fr.f4ioz.satcombo.domain.ActivitesAVenir
import fr.f4ioz.satcombo.station.Montage
import fr.f4ioz.satcombo.station.PlansMontage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.ZipInputStream

class QslPackTest {

    private val t = 1_792_830_976_062L   // 24/10/2026 08:36:16 UTC
    private val shot = SstvMeta.SstvShot("SatMe_SSTV_ISS_20261024_083616Z_Robot36.png", "ISS", t, mode = "Robot 36",
        locator = "JN18FS", callsign = "F4IOZ", source = "live", recording = "SatMe_ISS_20261024_083344Z.wav", diapo = 5, diapos = 12)
    private val pass = SatPass("ISS", 25544, t - 180_000L, t + 400_000L, 17.2, 200.0, 60.0, false, false,
        (0 until 10).map { 0.0 to 17.0 * Math.sin(Math.PI * (it + 0.5) / 10) })
    private val act = ActivitesAVenir.Activite("a", ActivitesAVenir.Type.SSTV, "ISS", 25544, t - 86_400_000L, t + 86_400_000L,
        freqHz = 437_550_000L, mode = "Robot 36", titre = "ARISS SSTV Series 34", sources = emptyList())

    private fun infos() = QslSstv.infos(shot, "XX0XX", "AA00AA", listOf(pass), listOf(act), emptyList(), "ARISS SSTV Series 34")

    @Test fun la_qsl_dit_tout_et_tient_dans_la_carte() {
        val i = infos()
        assertEquals("F4IOZ", i.indicatif); assertEquals("JN18FS", i.locator); assertEquals(437_550_000L, i.freqHz)
        val (l1, l2, l3) = QslSstv.lignes(i)
        assertTrue(l1, l1.contains("F4IOZ") && l1.contains("JN18FS"))
        assertTrue(l2, l2.contains("24/10/2026 08:36:16 UTC") && l2.contains("ISS") && l2.contains("437") && l2.contains("Robot 36"))
        assertTrue(l3, l3.contains("5/12") && l3.contains("Series 34") && l3.contains("17"))
        for (d in QslSstv.Disposition.entries) {
            val m = QslSstv.mise(320, 240, i, d)
            assertEquals(640, m.largeur)
            assertEquals(480f, m.image.h, 0.01f)
            if (d == QslSstv.Disposition.BANDEAU) assertTrue(m.hauteur > 480) else assertEquals(480, m.hauteur)
            for (x in m.textes) {
                assertTrue(x.y >= 0 && x.y + x.taille <= m.hauteur + 1)
                assertTrue(x.maxL > 0 && x.maxL <= m.largeur)
            }
        }
        assertEquals("SatMe_SSTV_ISS_20261024_083616Z_Robot36_QSL.jpg", QslSstv.nomFichier(shot.fileName, true))
    }

    @Test fun le_pack_garde_l_original_intact_et_resume_ce_que_le_formulaire_demande() {
        val original = ByteArray(5000) { (it * 31).toByte() }
        val copie = original.copyOf()
        val qsl = ByteArray(100) { 7 }
        val plans = PlansMontage.Plans(listOf(
            PlansMontage.Plan("p1", Montage.Chaine(listOf(Montage.Element("e1", "parabole-qo100"), Montage.Element("e3", "minitiouner")), nom = "QO-100")),
            PlansMontage.Plan("p2", Montage.Chaine(listOf(Montage.Element("e1", "ic9700"), Montage.Element("e2", "pc")), nom = "LEO"))), "p1")
        val materiel = PackAriss.materiel(plans, "IC-9700")
        assertEquals(listOf("Icom IC-9700"), materiel)
        val e = PackAriss.Entree(shot, original, 320, 240, qsl, QslSstv.nomFichier(shot.fileName, false), infos(), pass, 94)
        val zip = PackAriss.zip(listOf(e), materiel, t + 3_600_000L)
        val vus = HashMap<String, ByteArray>()
        ZipInputStream(zip.inputStream()).use { z -> while (true) { val x = z.nextEntry ?: break; vus[x.name] = z.readBytes() } }
        assertArrayEquals(copie, vus["original/" + shot.fileName])
        assertArrayEquals(copie, original)
        assertArrayEquals(qsl, vus["qsl/SatMe_SSTV_ISS_20261024_083616Z_Robot36_QSL.png"])
        val txt = String(vus.entries.first { it.key.endsWith(".txt") }.value, Charsets.UTF_8)
        for (s in listOf("F4IOZ", "JN18FS", "Europe", "2026-10-24", "08:36:16 UTC", "ISS", "437", "Robot 36", "Series 34", "5/12", "Icom IC-9700",
                PackAriss.FORMULAIRE, PackAriss.REGLEMENT)) assertTrue(s, txt.contains(s))
        assertTrue(vus.containsKey("resume.html"))
        assertEquals("Europe", PackAriss.region("JN18FS"))
        assertEquals("North America", PackAriss.region("FN31"))
        assertEquals("SatMe_ARISS_F4IOZ_20261024_0936.zip", PackAriss.nomFichier("f4ioz", t + 3_600_000L))
    }
}
