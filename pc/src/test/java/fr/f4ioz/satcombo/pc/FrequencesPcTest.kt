/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.data.FichiersFrequences
import fr.f4ioz.satcombo.data.TleEntry
import fr.f4ioz.satcombo.data.Transmitter
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/** Réglages › SatPC32 / Gpredict: an import kept and given to the lists, exported again, the unknown reported. */
class FrequencesPcTest {

    private val iss = TleEntry("ISS (ZARYA)", "1 25544U 98067A   24001.50000000  .00016717  00000-0  30777-3 0  9990",
        "2 25544  51.6416 247.4627 0006703 130.5360 325.0288 15.49512407430303")
    private val so50 = TleEntry("SO-50", "1 27607U 02058C   24001.50000000  .00000000  00000-0  00000-0 0  9990",
        "2 27607  64.5555 100.0000 0050000 100.0000 260.0000 14.80000000000000")
    private val satnogs = mapOf(27607 to listOf(Transmitter("Mode V/U FM", "FM", 145_850_000L, null, 436_795_000L, null, false, true, "Transceiver")))

    private fun f(st: StockageMemoire, changes: MutableList<Set<Int>> = ArrayList()) =
        FrequencesPc(st, { listOf(iss, so50) }, { satnogs }, { setOf(25544, 27607) }, { 25544 }, { changes += it })

    @Test
    fun import_sqf_garde_et_rend() {
        val st = StockageMemoire()
        val changes = ArrayList<Set<Int>>()
        val fq = f(st, changes)
        val r = fq.importe("Doppler.SQF", "ISS,145800,145990,FM,FM,NOR,0,0,V/U voix\r\nZZ-1,435000,0,FM,FM,NOR,0,0,x\r\npas une ligne\r\n")
        assertTrue(r.getBoolean("ok"))
        assertEquals(1, r.getInt("n"))
        assertEquals("ZZ-1", r.getJSONArray("inconnus").getJSONObject(0).getString("nom"))
        assertEquals(3, r.getJSONArray("illisibles").getJSONObject(0).getInt("ligne"))
        assertEquals(listOf(setOf(25544)), changes)
        assertEquals(145_800_000L, fq.pour(25544).single().downlinkLowHz)
        // Kept: a new instance on the same settings has it.
        assertEquals(fq.pour(25544), f(st).pour(25544))
        // In the export, after SatNOGS DB's.
        val sqf = fq.sqf("favoris")
        assertTrue(sqf, sqf.contains("ISS (ZARYA),145800,145990,FM,FM,NOR,0,0,V/U voix\r\n"))
        assertTrue(sqf.contains("SO-50,436795,145850,FM,FM,NOR,0,0,Mode V/U FM\r\n"))
        assertFalse(fq.sqf("suivi").contains("SO-50"))
        fq.oublie()
        assertTrue(fq.pour(25544).isEmpty())
        assertTrue(f(st).pour(25544).isEmpty())
    }

    @Test
    fun import_trsp_par_son_nom_et_export_zip() {
        val fq = f(StockageMemoire())
        val texte = FichiersFrequences.ecritTrsp(satnogs.getValue(27607))
        val r = fq.importe("27607.trsp", texte)
        assertEquals("trsp", r.getString("format"))
        assertEquals(1, r.getInt("n"))
        assertEquals(2, fq.emetteurs(27607).size)
        assertFalse(fq.importe("iss.trsp", texte).getBoolean("ok"))
        assertEquals("n° 99999", fq.importe("99999.trsp", texte).getJSONArray("inconnus").getJSONObject(0).getString("nom"))
        val noms = ArrayList<String>()
        ZipInputStream(ByteArrayInputStream(fq.trspZip("tous"))).use { z -> while (true) { val e = z.nextEntry ?: break; noms += e.name } }
        assertEquals(listOf("27607.trsp"), noms)
        assertTrue(fq.trsp(27607).startsWith("[Mode V/U FM]\n"))
    }
}
