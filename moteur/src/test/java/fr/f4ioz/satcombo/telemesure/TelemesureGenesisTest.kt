/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.telemesure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * HADES-SA's telemetry made readable, on frames of the satellite as heard (their data after the type byte, CRC
 * checked) — values checked against AMSAT-EA's published layouts — and the journal's archive of what was heard.
 */
class TelemesureGenesisTest {

    private fun v(l: TelemesureGenesis.Lecture, cle: String) = l.champ(cle)!!.v!!

    @Test fun energie() {
        val l = TelemesureGenesis.lis(1, "7A8B1F015A5D406E6600FBB26C13D2E3433D0FD101B46D2000000000")!!
        assertEquals(18_844_538L, l.horloge)
        assertEquals(3969.0, v(l, "vbat"), 0.0)
        assertEquals(3908.0, v(l, "vbat2"), 0.0)
        assertEquals(-76.0, v(l, "ibat"), 0.0)
        assertEquals(4008.0, v(l, "vbus1"), 0.0)
        assertEquals(3912.0, v(l, "vbus2"), 0.0)
        assertEquals(3920.0, v(l, "vbus3"), 0.0)
        assertEquals(2845.0, v(l, "vcpu"), 0.0)
        assertEquals(18.0, v(l, "icpu"), 0.0)
        assertEquals(109.0, v(l, "ipl"), 0.0)
        assertEquals(180.0, v(l, "pa"), 0.0)
        assertEquals(204.0, v(l, "psol"), 0.0)
        assertTrue(TelemesureGenesis.resume(l, false), TelemesureGenesis.resume(l, false).contains("3969 mV"))
    }

    @Test fun temperatures() {
        val l = TelemesureGenesis.lis(2, "668B1F01FF747978FF6F7879FF7D")!!
        assertEquals(18_844_518L, l.horloge)
        assertNull(l.champ("pa")!!.v)
        assertEquals(18.0, v(l, "pb"), 0.0)
        assertEquals(15.5, v(l, "eps"), 0.0)
        assertEquals(22.5, v(l, "cpu"), 0.0)
        assertNull(l.champ("rx")!!.v)
        assertEquals("°C", l.champ("cpu")!!.u)
        // The unused fifth sensor is not shown.
        assertEquals(9, l.champs.size)
        assertEquals("CPU 22.5 °C · EPS 15.5 °C · TX 20 °C · panneau B 18 °C", TelemesureGenesis.resume(l, false))
    }

    @Test fun etat() {
        val l = TelemesureGenesis.lis(3, "128A1F01108A1F010100494D0045001BFF00008400FFFFFFFFFF0064380012250000452C0090")!!
        assertEquals("pleine (≥ 3925 mV)", l.champ("batterie")!!.texte(false))
        assertEquals("off", l.champ("transpondeur")!!.texte(true))
        assertEquals("déployées", l.champ("antennes")!!.texte(false))
        assertEquals("mise sous tension", l.champ("reset")!!.texte(false))
        assertEquals(144.0, v(l, "image"), 0.0)
        assertEquals(56.0, v(l, "pTlm"), 0.0)
        assertEquals(18_844_176.0, v(l, "allume"), 0.0)
    }

    @Test fun plagesEtAntennesEtSerie() {
        val p = TelemesureGenesis.lis(5, "B68B1F01FF2A2E2EFF3A3022FF28FF9D8D8AFF788386008F")!!
        assertEquals(-19.0, v(p, "pb_min"), 0.0)
        assertEquals(38.5, v(p, "pb_max"), 0.0)
        assertEquals(31.5, v(p, "cpu_max"), 0.0)
        val a = TelemesureGenesis.lis(8, "340FF8FF7404000088E0380FFCFF912498161E018C00000000004CD4")!!
        assertEquals(3892.0, v(a, "voc"), 0.0)
        assertEquals(-8.0, v(a, "dv"), 0.0)
        assertEquals(1140.0, v(a, "ibr"), 0.0)
        assertEquals(140.0, v(a, "duree"), 0.0)
        assertEquals(76.0, v(a, "compteur"), 0.0)
        val s = TelemesureGenesis.lis(14, "3A8A1F01037D7C7E7F7F7E7A736C645D56524C46424044484C50555B6062666A6E7478")!!
        val serie = s.serie!!
        assertEquals(3, serie.variable)
        assertEquals(30, serie.valeurs.size)
        assertEquals(22.5, serie.valeurs.first()!!, 0.0)
        assertEquals(20.0, serie.valeurs.last()!!, 0.0)
        assertEquals(-8.0, v(s, "min"), 0.0)
        val j = TelemesureGenesis.json(s, true)
        assertEquals("Time series", j.getString("nom"))
        assertEquals(30, j.getJSONObject("serie").getJSONArray("v").length())
    }

    @Test fun bbsEtTaillesInconnues() {
        val b = TelemesureGenesis.lis(15, "2D".repeat(30) + "4E6F2064617461".repeat(5) + "0000000000")!!
        assertEquals(5, b.bbs.size)
        assertEquals("", b.bbs[0].indicatif)
        assertEquals("No data", b.bbs[0].message)
        assertEquals(0.0, v(b, "messages"), 0.0)
        // A frame of another size, or of a type not read: nothing made up.
        assertNull(TelemesureGenesis.lis(2, "668B1F01"))
        assertNull(TelemesureGenesis.lis(12, ByteArray(61)))
        // Ten INA channels in the extended energy.
        val e = TelemesureGenesis.lis(9, ByteArray(120) { if (it == 0) 0x6C else if (it == 1) 0x07 else 0 })!!
        assertEquals(1900.0, v(e, "spa_v"), 0.0)
        assertEquals(40, e.champs.size)
    }

    private fun dossier(): File = Files.createTempDirectory("genesis").toFile()

    @Test fun archiveSansDoublon() {
        val d = dossier()
        val a = ArchiveGenesis(d)
        val t0 = 1_793_700_000_000L
        val donnees = AssemblageSsdv.deHex("668B1F01FF747978FF6F7879FF7D")!!
        assertTrue(a.trame(t0, "HADES-SA (SpinnyONE)", 68446, 2, donnees, "radio"))
        // The same frame read again from the recording a moment later: kept once.
        assertTrue(!a.trame(t0 + 800, "HADES-SA (SpinnyONE)", 68446, 2, donnees, "enregistrement x.wav"))
        assertTrue(a.ssdv(t0 + 5_000, "HADES-SA (SpinnyONE)", 68446, "photo-144", 3, "radio"))
        assertTrue(!a.ssdv(t0 + 6_000, "HADES-SA (SpinnyONE)", 68446, "photo-144", 3, "radio"))
        // The next pass, 95 min later: the same packet heard again is that pass's.
        assertTrue(a.ssdv(t0 + 95 * 60_000L, "HADES-SA (SpinnyONE)", 68446, "photo-144", 3, "radio"))
        // A new start reads the files: still no duplicate.
        val b = ArchiveGenesis(d)
        assertTrue(!b.trame(t0 + 1_000, "HADES-SA (SpinnyONE)", 68446, 2, donnees, "radio"))
        val l = b.entre(t0 - 60_000, t0 + 60_000, "HADES-SA")
        assertEquals(listOf(2, Genesis.TYPE_SSDV), l.map { it.type })
        assertEquals("photo-144", l[1].cle)
        assertEquals(3, l[1].numero)
        // Another satellite's window: nothing.
        assertTrue(b.entre(t0 - 60_000, t0 + 60_000, "ISS").isEmpty())
        assertEquals(3, b.entre(t0 - 60_000, t0 + 100 * 60_000L, "", 68446).size)
    }
}
