/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.domain.DecodeursMultiples
import fr.f4ioz.satcombo.telemesure.DecodeurTelemesure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** BPSK telemetry on SatMe-PC: off by default, the beacon channel in one click, the test mode, frames kept locally. */
class TelemesurePcTest {

    private fun stockage() = StockageFichier(File.createTempFile("tlm", ".properties").also { it.delete(); it.deleteOnExit() })

    @Test fun `coupe par defaut, rien ne decode`() {
        val dir = Files.createTempDirectory("tlm").toFile()
        val t = TelemesurePc(stockage(), dir, CanauxPc(stockage()))
        assertFalse(t.leoActif)
        t.ecouteSdr(ShortArray(44_100) { 1000 }, 44_100)
        val j = t.json()
        assertFalse(j.getJSONObject("leo").has("etat"))
        assertFalse(j.getJSONObject("balise").getBoolean("canal"))
        assertEquals(DecodeursMultiples.CADRAN_BALISE_HZ, j.getJSONObject("balise").getLong("cadran"))
    }

    @Test fun `la balise en un clic, une seule`() {
        val k = CanauxPc(stockage())
        val t = TelemesurePc(stockage(), Files.createTempDirectory("tlm").toFile(), k)
        assertTrue(t.action("/api/telemesure/balise", mapOf("on" to "1")).getBoolean("ok"))
        assertTrue(t.action("/api/telemesure/balise", mapOf("on" to "1")).getBoolean("ok"))
        assertTrue(k.actif)
        assertEquals(listOf(DecodeursMultiples.Decodeur.BALISE), k.liste.map { it.decodeur })
        assertEquals(10_489_748_500L, k.liste[0].cadranHz)
        assertTrue(t.json().getJSONObject("balise").getBoolean("canal"))
        t.action("/api/telemesure/balise", mapOf("on" to "0"))
        assertTrue(k.liste.isEmpty())
    }

    @Test fun `l'essai passe par le meme decodeur et n'est pas garde`() {
        val dir = Files.createTempDirectory("tlm").toFile()
        val t = TelemesurePc(stockage(), dir, CanauxPc(stockage()))
        assertTrue(t.lanceEssai(DecodeurTelemesure.Profil.FUNCUBE))
        val fin = System.currentTimeMillis() + 60_000
        while (t.json().getJSONObject("essai").getBoolean("enCours") && System.currentTimeMillis() < fin) Thread.sleep(100)
        val e = t.json().getJSONObject("essai")
        val l = e.getJSONArray("trames")
        assertEquals(2, l.length())
        val f = l.getJSONObject(1)
        assertTrue(f.getBoolean("essai"))
        assertEquals("FM1", f.getString("genre"))
        assertTrue(f.getString("message").startsWith("Essai SatMe"))
        assertFalse(File(dir, "telemesure").exists())
    }

    @Test fun `une trame de la balise est ecrite dans le dossier du jour`() {
        val dir = Files.createTempDirectory("tlm").toFile()
        val k = CanauxPc(stockage())
        TelemesurePc(stockage(), dir, k)
        val d = ByteArray(256) { 'A'.code.toByte() }
        k.auTrame(DecodeurTelemesure.Trame(1_792_000_000_000L, "fec", d, true, 3, 40, 9.5))
        val f = File(dir, "telemesure").listFiles()!!.single()
        assertTrue(f.name, f.name.startsWith("qo100-"))
        val o = org.json.JSONObject(f.readLines().single())
        assertEquals("QO-100", o.getString("satellite"))
        assertEquals("41".repeat(256), o.getString("hex"))
    }
}
