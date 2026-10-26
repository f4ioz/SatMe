/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.datv.Datv
import fr.f4ioz.satcombo.datv.ReglageDatv
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** LongMynd's own settings, tolerant decoding, the local SDR's spectrum and its click to tune. */
class PointageDatvPcTest {

    @Test fun `largeur de recherche et attente de LongMynd`() {
        val r = ReglageDatv(cielKhz = 10_498_750, srKs = 125, olKhz = 9_750_000)
        // Off: the command line as before.
        assertEquals(listOf("l", "-i", "127.0.0.1", "1", "-I", "127.0.0.1", "2", "-r", "5000", "748750", "125"), DatvPc.commande("l", r, 1, 2))
        val c = DatvPc.commande("l", r.copy(demiLargeur = 0.6, attenteMs = 20_000), 1, 2)
        assertEquals(listOf("-r", "20000", "-S", "0.60"), c.subList(7, 11))
        // From the page or a node's request: the wrong values left out.
        assertEquals(0.6, Datv.demiLargeurDe("0,6"), 0.0); assertEquals(0.0, Datv.demiLargeurDe("9"), 0.0); assertEquals(0.0, Datv.demiLargeurDe(null), 0.0)
        assertEquals(10_000, Datv.attenteDe("10000")); assertEquals(0, Datv.attenteDe("100")); assertEquals(0, Datv.attenteDe("x"))
        // Through a node's status and back.
        val j = Datv.json(fr.f4ioz.satcombo.datv.EtatDatv(), r.copy(demiLargeur = 0.4, attenteMs = 30_000), emptyList())
        val l = Datv.reglageDe(JSONObject(j.toString()), ReglageDatv())
        assertEquals(0.4, l.demiLargeur, 0.0); assertEquals(30_000, l.attenteMs)
        // An older node says nothing of them: ours kept.
        assertEquals(0.6, Datv.reglageDe(JSONObject().put("ciel", 10_491_500L), ReglageDatv(demiLargeur = 0.6)).demiLargeur, 0.0)
    }

    @Test fun `decodage tolerant`() {
        val n = DatvPc.lecteurEntree(false)
        assertEquals(listOf("-fflags", "nobuffer"), n.take(2)); assertFalse("-err_detect" in n)
        val t = DatvPc.lecteurEntree(true)
        assertTrue(t.containsAll(listOf("nobuffer+discardcorrupt", "-err_detect", "ignore_err", "-ec", "guess_mvs+deblock")))
        assertEquals(listOf("-f", "mpegts"), t.takeLast(2))
    }

    @Test fun `les options gardees et le pointage a l'arret`() {
        val st = StockageMemoire()
        val d = DatvPc(st, File(System.getProperty("java.io.tmpdir")))
        assertFalse(d.caleAuto); assertFalse(d.decodageTolerant)
        d.caleAuto = true; d.decodageTolerant = true
        d.regleLongMynd(0.6, null)
        val e = DatvPc(st, File(System.getProperty("java.io.tmpdir")))
        assertTrue(e.caleAuto); assertTrue(e.decodageTolerant)
        assertEquals(0.6, e.reglage.demiLargeur, 0.0); assertEquals(0, e.reglage.attenteMs)
        val p = e.pointageJson()
        assertFalse(p.getBoolean("actif")); assertTrue(p.isNull("mer")); assertTrue(p.isNull("niveau"))
        assertTrue(p.getBoolean("balise"))
    }

    @Test fun `spectre du SDR local, rien sans l'option, la bande simulee, un clic qui accorde`() {
        var t = 1_000_000L
        val st = StockageMemoire()
        val d = DatvPc(st, File(System.getProperty("java.io.tmpdir")), horloge = { t })
        val s = SpectreSdrDatvPc(st, d, null, { k, sr -> if (sr == 333) "EA4ABC" else null }, { t })
        // Off: nothing opened.
        s.json(-1); s.boucle()
        assertEquals(0L, s.numero)
        // The simulated band: swept once the page asks, then stopped when the tab is gone.
        s.action(mapOf("source" to "essai", "on" to "1"))
        s.json(-1)
        val fil = Thread { s.boucle() }.apply { start() }
        val fin = System.currentTimeMillis() + 20_000
        while (s.numero < 3 && System.currentTimeMillis() < fin) Thread.sleep(50)
        t += SpectreSdrDatvPc.VU_MS + 1
        fil.join(10_000)
        assertFalse(fil.isAlive)
        t -= SpectreSdrDatvPc.VU_MS + 1
        val j = s.json(-1)
        assertTrue(j.getBoolean("frais"))
        assertEquals(fr.f4ioz.satcombo.datv.SpectreLocal.POINTS, j.getJSONArray("valeurs").length())
        val sg = j.getJSONArray("signaux")
        val l = (0 until sg.length()).map { sg.getJSONObject(it) }
        assertTrue(l.any { it.getBoolean("balise") && it.getInt("sr") == 1500 })
        val ea = l.first { it.getInt("sr") == 333 }
        assertEquals("EA4ABC", ea.getString("nom"))
        assertEquals("", j.getString("avertissement"))
        // The click: the page posts the signal's centre and rate to the ground station's strip's route, which tunes the receiver.
        val demandes = ArrayList<ReglageDatv>()
        val large = SpectreLargePc(st, d, { t }, { SpectreLargePc.Source({ null }, {}) }, { demandes += it; null })
        assertTrue(large.action("/api/datv/large/accorde", mapOf("khz" to ea.getLong("centre").toString(), "sr" to "333")).getBoolean("ok"))
        assertEquals(333, demandes.single().srKs)
        assertEquals(10_497_250.0, demandes.single().cielKhz.toDouble(), 30.0)
        // No SDR here: said, nothing opened.
        s.action(mapOf("source" to "sdr")); s.json(-1); s.boucle()
        assertEquals("pas de récepteur SDR ici", s.etat)
    }
}
