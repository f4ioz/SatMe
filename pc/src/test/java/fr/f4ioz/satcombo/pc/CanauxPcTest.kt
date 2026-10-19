/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** « Décodeurs multiples »: off by default, the page's gestures, the sound heard, the list kept. */
class CanauxPcTest {

    private fun stockage() = StockageFichier(File.createTempFile("canaux", ".properties").also { it.delete(); it.deleteOnExit() })

    @Test fun `eteint par defaut, rien ne tourne`() {
        val k = CanauxPc(stockage())
        assertFalse(k.actif)
        assertTrue(k.cibles(10_489_750_000.0 to 1_058_400.0).isEmpty())
        assertFalse(k.json().getBoolean("option"))
    }

    @Test fun `ajouter sur un signal, la regle des uniques, retirer`() {
        val st = stockage()
        val k = CanauxPc(st)
        val r = k.action("/api/canaux/ajoute", mapOf("hz" to "10489525000", "classe" to "cw"))
        assertTrue(r.toString(), r.getBoolean("ok"))
        // Adding turns the option on.
        assertTrue(k.actif)
        assertEquals(10_489_524_300L, k.liste[0].cadranHz)
        assertTrue(k.action("/api/canaux/ajoute", mapOf("hz" to "10489701500", "decodeur" to "phonie")).getBoolean("ok"))
        val non = k.action("/api/canaux/ajoute", mapOf("hz" to "10489801500", "decodeur" to "phonie"))
        assertFalse(non.getBoolean("ok"))
        assertTrue(non.getString("erreur").contains("un seul"))
        // Kept, and read back by a fresh one.
        assertEquals(k.liste, CanauxPc(st).liste)
        k.action("/api/canaux/retire", mapOf("id" to "1"))
        assertEquals(listOf("phonie"), k.liste.map { it.decodeur.cle })
    }

    @Test fun `les cibles suivent l'axe du recepteur`() {
        val k = CanauxPc(stockage())
        k.action("/api/canaux/prereglage", mapOf("nom" to "ft8-cw-phonie", "ecoute" to "10489720000"))
        val c = k.cibles(10_489_700_000.0 to 1_058_400.0)
        assertEquals(listOf(-160_000.0, -178_700.0, 20_000.0), c.map { it.decalageHz })
        // Behind an inverting converter the band runs the other way.
        assertEquals(160_000.0, k.cibles(10_489_700_000.0 to -1_058_400.0)[0].decalageHz!!, 0.0)
        assertTrue(k.cibles(null).isEmpty())
    }

    @Test fun `le canal ecoute remplace le son principal, une fois par bloc`() {
        val k = CanauxPc(stockage())
        k.action("/api/canaux/prereglage", mapOf("nom" to "ft8-ft4"))
        val pcm = ShortArray(100) { it.toShort() }
        k.recoit(1, pcm, 100)
        assertNull(k.prendEcoute())
        k.action("/api/canaux/ecoute", mapOf("id" to "2", "on" to "1"))
        k.recoit(1, pcm, 100); k.recoit(2, pcm, 60)
        val e = k.prendEcoute()!!
        assertEquals(60, e.second)
        assertEquals(59.toShort(), e.first[59])
        assertNull(k.prendEcoute())
        assertTrue(k.tientSon())
    }

    @Test fun `le canal SSTV tient le decodeur et suit le calage automatique`() {
        val k = CanauxPc(stockage())
        assertFalse(k.tientSstv())
        k.action("/api/canaux/ajoute", mapOf("hz" to "10489600000", "decodeur" to "sstv"))
        assertTrue(k.tientSstv())
        assertEquals(10_489_598_100L, k.cadranSstv())
        k.decaleSstv(-120)
        assertEquals(10_489_597_980L, k.cadranSstv())
        // The option off: the main receiver feeds the decoder again.
        k.action("/api/canaux/option", mapOf("on" to "0"))
        assertFalse(k.tientSstv())
    }

    @Test fun `un geste inconnu est refuse`() {
        val k = CanauxPc(stockage())
        assertFalse(k.action("/api/canaux/emet", emptyMap()).getBoolean("ok"))
        assertFalse(k.action("/api/canaux/prereglage", mapOf("nom" to "x")).getBoolean("ok"))
    }

    @Test fun `un canal RTTY ecrit des lignes horodatees, l'indicatif marque`() {
        val k = CanauxPc(stockage(), horloge = { 1_700_000_000_000L })
        k.indicatif = { "F4IOZ" }
        assertTrue(k.action("/api/canaux/ajoute", mapOf("hz" to "10489551500", "classe" to "rtty")).getBoolean("ok"))
        assertEquals(fr.f4ioz.satcombo.domain.DecodeursMultiples.Decodeur.RTTY, k.liste[0].decodeur)
        assertEquals(10_489_550_000L, k.liste[0].cadranHz)
        val a = fr.f4ioz.satcombo.clavier.SignauxEssai.audioRtty(fr.f4ioz.satcombo.clavier.SignauxEssai.Rtty("RYRY CQ DE F4IOZ K"),
            fr.f4ioz.satcombo.sdr.Dsp.AUDIO_RATE, 1_500.0, 6.0, 0.3)
        val pcm = ShortArray(a.size) { (a[it] * 32_767).toInt().toShort() }
        var i = 0
        while (i < pcm.size) { val n = minOf(4_410, pcm.size - i); k.recoit(1, pcm.copyOfRange(i, i + n), n); i += n }
        val c = k.json().getJSONArray("canaux").getJSONObject(0)
        val l = c.getJSONArray("lignes").getJSONObject(0)
        assertTrue(l.toString(), l.getString("texte").contains("CQ DE F4IOZ"))
        assertEquals(1_700_000_000_000L, l.getLong("t"))
        assertTrue(l.getJSONArray("ind").getJSONArray(0).getBoolean(2))
    }

    @Test fun `le decodeur PSK31 RTTY du son principal, coupe d'origine`() {
        val st = stockage()
        val c = ClavierPc(st, { 1_700_000_000_000L })
        assertFalse(c.actif)
        c.action(mapOf("on" to "1", "mode" to "RTTY", "baud" to "50", "shift" to "425", "inverse" to "1"))
        assertTrue(c.actif)
        val r = ClavierPc(st).reglagesRtty
        assertEquals(50.0, r.baud, 0.0); assertEquals(425.0, r.shiftHz, 0.0); assertTrue(r.inverse)
        assertEquals(1_500.0, c.centre(), 0.0)
        val a = fr.f4ioz.satcombo.clavier.SignauxEssai.audioRtty(fr.f4ioz.satcombo.clavier.SignauxEssai.Rtty("RYRY CQ DE F4IOZ K", r),
            8_000, 1_500.0, 6.0, 0.3)
        val pcm = ShortArray(a.size) { (a[it] * 32_767).toInt().toShort() }
        c.ecoute(pcm, pcm.size, 8_000)
        assertTrue(c.journal.texte(), c.journal.texte().contains("CQ DE F4IOZ"))
        assertEquals("RTTY", c.json("F4IOZ").getString("mode"))
    }
}
