/*
 * TellusMe — the SatMe ecosystem's terrestrial station
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.tellus

import fr.f4ioz.satcombo.sdr.hpsdr.GardeHpsdr
import fr.f4ioz.satcombo.sdr.hpsdr.HpsdrDecouverte
import fr.f4ioz.satcombo.sdr.hpsdr.HpsdrProtocole
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionBrickTest {
    private val radio = SimulateurBrick()

    @After fun fin() = radio.ferme()

    private fun attends(ms: Long = 5000, c: () -> Boolean): Boolean {
        val fin = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < fin) { if (c()) return true; Thread.sleep(20) }
        return c()
    }

    @Test fun decouverte_sur_le_loopback() {
        val r = HpsdrDecouverte.cherche("127.0.0.1", radio.port, diffusion = false, attenteMs = 1500)
        assertTrue(r.enLigne)
        assertEquals("127.0.0.1", r.ip)
        assertEquals(radio.port, r.port)
        assertTrue(r.carte.startsWith("Hermes"))
        assertEquals("3.3", r.firmware)
    }

    @Test fun recoit_le_spectre_suit_la_frequence_et_s_arrete() {
        val s = SessionBrick("127.0.0.1", radio.port)
        s.regle(hz = 7_190_000L, nouveauDebit = 48_000)
        assertTrue(s.assure())
        assertTrue("le spectre arrive", attends { s.instantane() != null })
        assertTrue(attends { radio.freq == 7_190_000L })
        // The AM carrier at 7.200 MHz, 10 kHz above the centre: right of the middle, where it belongs.
        Thread.sleep(300)
        val bins = s.instantane(512)!!.getJSONArray("bins")
        var imax = 0
        for (i in 0 until bins.length()) if (bins.getInt(i) > bins.getInt(imax)) imax = i
        val attendu = 256 + Math.round(10_000.0 / 48_000 * 512).toInt()
        assertTrue("pic en $imax, attendu vers $attendu", kotlin.math.abs(imax - attendu) <= 3)
        // Retuned and a wider span: the next EP2 frames carry them.
        s.regle(hz = 14_074_000L, nouveauDebit = 96_000)
        assertTrue(attends { radio.freq == 14_074_000L && radio.debit == 96_000 })
        s.arrete(); s.attendsArret()
        assertTrue("la radio a reçu l'ordre d'arrêt", attends { !radio.enFlux })
        assertFalse(s.enMarche)
    }

    @Test fun le_son_demodule_arrive() {
        val s = SessionBrick("127.0.0.1", radio.port)
        s.regle(hz = 7_200_000L)
        s.regleAudio(on = true, mode = "AM")
        assertTrue(s.assure())
        assertTrue(attends { s.instantane() != null })
        Thread.sleep(500)
        val pcm = s.audioRecu()
        assertTrue("du son à 12 kHz : ${pcm.size} octets", pcm.size > 2000)
        var crete = 0
        for (k in 0 until pcm.size / 2) crete = maxOf(crete, kotlin.math.abs(((pcm[2 * k + 1].toInt()) shl 8) or (pcm[2 * k].toInt() and 0xFF)))
        assertTrue("non silencieux", crete > 1000)
        s.arrete(); s.attendsArret()
    }

    @Test fun rien_d_envoye_ne_peut_emettre() {
        val s = SessionBrick("127.0.0.1", radio.port)
        for (f in listOf(1_840_000L, 7_074_000L, 28_500_000L, 50_313_000L)) {
            s.regle(hz = f)
            assertTrue(s.assure())
            attends(1500) { radio.freq == f }
        }
        s.arrete(); s.attendsArret()
        attends { !radio.enFlux }
        val recus = radio.recus.toList()
        assertTrue(recus.size > 20)
        assertEquals(0L, s.refuses)
        for (d in recus) {
            assertTrue("datagramme permis", GardeHpsdr.permis(d))
            if ((d[2].toInt() and 0xFF) == 0x01) for (k in 0..1) {
                val o = 8 + k * 512
                assertEquals("MOX à 0", 0, d[o + 3].toInt() and 0x01)
                assertTrue("charge TX nulle", (o + 8 until o + 512).all { d[it].toInt() == 0 })
            }
            if ((d[2].toInt() and 0xFF) == 0x04) assertTrue("flux IQ seul", d[3].toInt() == 0x00 || d[3].toInt() == 0x01)
        }
    }

    @Test fun la_garde_refuse_mox_charge_et_large_bande() {
        val ok = HpsdrProtocole.ep2(5, 0, 7_074_000)
        assertTrue(GardeHpsdr.permis(ok))
        assertTrue(GardeHpsdr.permis(HpsdrProtocole.metis(true)))
        assertTrue(GardeHpsdr.permis(HpsdrProtocole.metis(false)))
        assertTrue(GardeHpsdr.permis(HpsdrDecouverte.paquet()))
        assertFalse(GardeHpsdr.permis(ok.copyOf().also { it[8 + 3] = 0x01 }))            // MOX
        assertFalse(GardeHpsdr.permis(ok.copyOf().also { it[520 + 3] = 0x03 }))          // MOX on the frequency word
        assertFalse(GardeHpsdr.permis(ok.copyOf().also { it[8 + 3] = 0x12 }))            // drive level word
        assertFalse(GardeHpsdr.permis(ok.copyOf().also { it[8 + 100] = 0x10 }))          // TX audio/IQ
        assertFalse(GardeHpsdr.permis(ok.copyOf().also { it[8 + 6] = 0x04 }))            // duplex bit set
        assertFalse(GardeHpsdr.permis(HpsdrProtocole.metis(true).also { it[3] = 0x03 })) // wide-band stream
        assertFalse(GardeHpsdr.permis(ByteArray(10)))
    }

    @Test fun ep6_aller_retour() {
        val iq = FloatArray(252) { (it * 1000 - 120_000).toFloat() }
        val back = HpsdrProtocole.ep6(HpsdrProtocole.trameEp6(1, iq))
        assertEquals(252, back.size)
        for (i in iq.indices) assertEquals(iq[i], back[i], 0f)
        val ep2 = HpsdrProtocole.ep2(1, 2, 14_074_000)
        assertEquals(14_074_000L, HpsdrProtocole.frequenceEp2(ep2))
        assertEquals(192_000, HpsdrProtocole.debitEp2(ep2))
        assertNotNull(HpsdrDecouverte.reponse(HpsdrDecouverte.reponseRadio(ByteArray(6), 33, 1), 60, "x"))
    }
}
