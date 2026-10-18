/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tune requests by UDP, the level meter, the wide band chat's protocol. */
class OptionsDatvTest {
    private val actuel = ReglageDatv(cielKhz = 10_491_500L, srKs = 1500, olKhz = 9_750_000L, entreeB = false, lnb = "v")

    @Test fun `accord au format de SatMe`() {
        val r = CommandeAccord.lis("FREQ=10494750;SR=333;OL=9749990;ENTREE=B", actuel).reglage!!
        assertEquals(10_494_750L, r.cielKhz); assertEquals(333, r.srKs); assertEquals(9_749_990L, r.olKhz); assertTrue(r.entreeB)
        // The LNB supply as it was: never set by UDP.
        assertEquals("v", r.lnb)
        // Keys in any case, the oscillator and input kept, MHz with a point, new lines.
        val m = CommandeAccord.lis("sr=1500\nfreq=10494.75\n", actuel).reglage!!
        assertEquals(10_494_750L, m.cielKhz); assertEquals(9_750_000L, m.olKhz); assertFalse(m.entreeB)
        assertEquals(10_497_000L, CommandeAccord.lis("  FREQ=10497000 ; SR=2000\u0000", actuel).reglage!!.cielKhz)
    }

    @Test fun `accord au format courant des outils DATV`() {
        val r = CommandeAccord.lis("[GlobalMsg],Freq=10491500,Offset=9750000,Doppler=0,Srate=1500,WideScan=0,LowSR=0,DVBmode=Auto,FPlug=B,Voltage=18,22kHz=Off", actuel).reglage!!
        assertEquals(10_491_500L, r.cielKhz); assertEquals(1500, r.srKs); assertEquals(9_750_000L, r.olKhz); assertTrue(r.entreeB)
        assertEquals("v", r.lnb)
        // Without offset: Freq is the intermediate frequency, our oscillator added.
        val fi = CommandeAccord.lis("[GlobalMsg],Freq=744750,Offset=0,Srate=333", actuel).reglage!!
        assertEquals(10_494_750L, fi.cielKhz); assertEquals(9_750_000L, fi.olKhz)
    }

    @Test fun `accords refuses avec une raison`() {
        for (t in listOf("", "FREQ=10494750", "SR=1500", "FREQ=abc;SR=1500", "FREQ=10494750;SR=1500;ENTREE=C", "FREQ=10494750;SR=1500;OL=x",
                "FREQ=50000000;SR=1500", "FREQ=10494750;SR=5", "[GlobalMsg],Freq=10491500", "[GlobalMsg],Srate=1500", "X".repeat(600))) {
            val r = CommandeAccord.lis(t, actuel)
            assertNull(t, r.reglage); assertTrue(t, r.erreur.isNotEmpty())
        }
    }

    @Test fun `une demande toutes les 2 s, 10 par minute`() {
        val l = LimiteDebit(2_000L, 10)
        assertTrue(l.accepte(0)); assertFalse(l.accepte(1_999)); assertTrue(l.accepte(2_000))
        var t = 2_000L; var n = 2
        while (t < 56_000L) { t += 2_000L; if (l.accepte(t)) n++ }
        assertEquals(10, n)
        // A minute after the first, room again.
        assertTrue(l.accepte(62_000L))
    }

    @Test fun `vumetre crete tenue puis chute, mono et stereo`() {
        val v = NiveauxSon(tenueMs = 1_000L, chuteDbParS = 20.0)
        val plein = ShortArray(200) { if (it % 2 == 0) 16384 else -16384 }
        v.mesure(plein, 200, 1, 0)
        assertEquals(-6.0, v.gauche.crete, 0.1); assertEquals(-6.0, v.gauche.rms, 0.1); assertTrue(v.mono)
        // Quieter: the peak falls, its hold stays a second, then falls 20 dB a second.
        val faible = ShortArray(200) { 1638 }
        v.mesure(faible, 200, 1, 500)
        assertEquals(-26.0, v.gauche.crete, 0.1); assertEquals(-6.0, v.gauche.tenue, 0.1)
        v.mesure(faible, 200, 1, 1_500)
        assertEquals(-16.0, v.gauche.tenue, 0.1)
        // Stereo, the right channel silent.
        val st = ShortArray(400) { if (it % 2 == 0) 32767 else 0 }
        v.mesure(st, 200, 2, 3_000)
        assertFalse(v.mono); assertEquals(0.0, v.gauche.crete, 0.01); assertEquals(NiveauxSon.PLANCHER, v.droite.crete, 0.0)
        val j = v.json(3_100)
        assertTrue(j.getBoolean("frais")); assertEquals(0.0, j.getJSONObject("g").getDouble("crete"), 0.01)
        // Nothing for a second: silent.
        assertEquals(NiveauxSon.PLANCHER, v.json(5_000).getJSONObject("g").getDouble("crete"), 0.0)
    }

    @Test fun `volume applique et borne`() {
        val p = shortArrayOf(1000, -1000, 30000, -30000)
        NiveauxSon.applique(p, 4, 2.0)
        assertEquals(listOf<Short>(2000, -2000, 32767, -32768), p.toList())
        NiveauxSon.applique(p, 2, 0.0)
        assertEquals(listOf<Short>(0, 0, 32767, -32768), p.toList())
        assertEquals(listOf<Short>(150, -5), NiveauxSon.mono(shortArrayOf(100, 200, -10, 0), 2).toList())
    }

    @Test fun `paquets socket io`() {
        val o = SocketIo.lis("0{\"sid\":\"x\",\"upgrades\":[],\"pingInterval\":25000,\"pingTimeout\":5000}")
        assertEquals(SocketIo.Paquet.Ouverture(25_000L, 5_000L), o)
        assertEquals(SocketIo.Paquet.Ping, SocketIo.lis("2"))
        assertEquals(SocketIo.Paquet.Connecte, SocketIo.lis("40{\"sid\":\"y\"}"))
        assertEquals(SocketIo.Paquet.Fermeture, SocketIo.lis("1"))
        assertEquals(SocketIo.Paquet.Deconnecte, SocketIo.lis("41"))
        assertEquals("bad", (SocketIo.lis("44{\"message\":\"bad\"}") as SocketIo.Paquet.Refus).pourquoi)
        val e = SocketIo.lis("42[\"viewers\",{\"num\":49}]") as SocketIo.Paquet.Evenement
        assertEquals("viewers", e.nom); assertEquals(49, (e.donnees as JSONObject).getInt("num"))
        assertEquals("message", (SocketIo.lis("4212[\"message\",{}]") as SocketIo.Paquet.Evenement).nom)
        assertEquals(SocketIo.Paquet.Autre, SocketIo.lis("42[pas du json"))
        assertEquals(SocketIo.Paquet.Autre, SocketIo.lis(""))
        assertEquals("42[\"setnick\",{\"nick\":\"F4IOZ\"}]", SocketIo.evenement("setnick", JSONObject().put("nick", "F4IOZ")))
    }

    @Test fun `le salon historique messages presents`() {
        val c = ChatLarge(max = 3)
        val h = JSONObject("{\"nicks\":[\"G4XAT\",\"\",\"DK0TE\"],\"history\":[" +
            "{\"time\":\"2026-10-17T08:22:04.563Z\",\"name\":\"G4SAQ Russ\",\"message\":\"Hi\"}," +
            "{\"time\":\"pas une date\",\"name\":\"X\",\"message\":\"perdu\"}," +
            "{\"time\":\"2026-10-17T08:23:12.758Z\",\"name\":\"Gareth\",\"message\":\"Yes\"}]}")
        assertTrue(c.recoit("history", h))
        assertEquals(listOf("G4XAT", "DK0TE"), c.nicks)
        assertEquals(listOf("Hi", "Yes"), c.liste().map { it.texte })
        val m = JSONObject("{\"time\":\"2026-10-17T08:24:00Z\",\"name\":\"A\",\"message\":\"un\"}")
        assertTrue(c.recoit("message", m))
        assertFalse(c.recoit("message", m))
        assertTrue(c.recoit("message", JSONObject("{\"time\":\"2026-10-17T08:25:00Z\",\"name\":\"B\",\"message\":\"deux\"}")))
        assertEquals(listOf("Yes", "un", "deux"), c.liste().map { it.texte })
        assertTrue(c.recoit("viewers", JSONObject().put("num", 12))); assertEquals(12, c.spectateurs)
        assertFalse(c.recoit("inconnu", JSONObject()))
        val n = c.numero
        assertFalse(c.json(n).has("messages")); assertTrue(c.json(n - 1).has("messages"))
    }

    @Test fun `pseudo texte et rapport`() {
        assertEquals("F4IOZ", ChatLarge.nick(" f4ioz "))
        assertEquals("F4IOZ/P", ChatLarge.nick("F4IOZ/P"))
        assertNull(ChatLarge.nick("")); assertNull(ChatLarge.nick("ABC"))
        assertEquals(20, ChatLarge.nick("F4IOZ" + "X".repeat(30))!!.length)
        assertEquals("bonjour à tous", ChatLarge.texte("  bonjour\u0000à tous ").first)
        assertNull(ChatLarge.texte("   ").first); assertNull(ChatLarge.texte("/nick X").first); assertNull(ChatLarge.texte("x".repeat(301)).first)
        assertEquals("SigReport: A71A/QARS - D5,6 (10,3 dB) - 1500 Ks - 10491,5", ChatLarge.rapport("A71A/QARS", 5.6, 10.3, 1500, 10_491_500L))
        assertEquals("SigReport: ? - 333 Ks - 10494,75", ChatLarge.rapport("", null, null, 333, 10_494_750L))
        assertNotNull(ChatLarge.rapport("F4IOZ", -1.2, 3.0, 2000, 10_497_000L).takeIf { it.endsWith("D-1,2 (3,0 dB) - 2000 Ks - 10497") })
    }
}
