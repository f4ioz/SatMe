/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.cat.icomreseau

import fr.f4ioz.satcombo.cat.icomreseau.TraceEssai.Datagramme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A made-up capture of a host transmitting: what the analysis finds in it. */
class AnalyseTraceTxTest {

    private fun demande(tx: Boolean): ByteArray {
        val r = Paquets.Radio(ByteArray(16), "IC-9700", "", 0, 0xA2, 0, 0, 0)
        return Paquets.infoHote(2, 0x1111, 0x2222, 3, 9, 0x7777, r, ByteArray(16),
            Paquets.Flux(0x04, 16_000, 50002, 50003, txActif = tx, txCodec = 0x04, txTaux = 16_000, tamponTxMs = 150))
    }

    private fun audio(i: Int, marque: Int, octets: Int, grosBout: Boolean = false): ByteArray {
        val d = ByteArray(octets)
        for (k in 0 until octets / 2) {
            val v = (6000 * Math.sin(2 * Math.PI * 700 * (i * octets / 2 + k) / 16_000.0)).toInt()
            if (grosBout) { d[2 * k] = (v shr 8).toByte(); d[2 * k + 1] = v.toByte() } else { d[2 * k] = v.toByte(); d[2 * k + 1] = (v shr 8).toByte() }
        }
        return Paquets.nouvelAudio(i, 0x1111, 0x2222, i, d, marque)
    }

    private fun trace(controle: List<Datagramme>, son: List<Datagramme>) =
        TraceEssai.pcap(1, controle.map { TraceEssai.ethernet(TraceEssai.ipUdp(it)) } + son.map { TraceEssai.ethernet(TraceEssai.ipUdp(it, 50003)) })

    private val ici = Datagramme(false, Paquets.controle(Paquets.T_JE_SUIS_LA, 0, 0x2222, 0x1111))

    @Test fun capture_conforme_au_format_de_satme() {
        val t = trace(listOf(ici, Datagramme(true, demande(true))), (0 until 120).map { Datagramme(true, audio(it, FormatTxReseau.MARQUE_DEFAUT, 640)) })
        val b = AnalyseTraceTx.analyse(t, 50001, 50003)
        assertTrue(b.message, b.ok)
        assertEquals(120, b.paquets)
        assertEquals(FormatTxReseau.MARQUE_DEFAUT, b.marque)
        assertEquals(640, b.octets)
        assertTrue(b.longueurCoherente)
        assertTrue(b.compteSuit)
        val dm = b.demande
        assertNotNull(dm)
        assertTrue(dm!!.actif)
        assertEquals(0x04, dm.codec); assertEquals(16_000, dm.taux); assertEquals(150, dm.tamponMs)
        assertEquals(FormatPcm16.PETIT, b.format)
        assertTrue(b.conforme(16_000))
    }

    @Test fun autre_marque_autre_taille_gros_bout() {
        val t = trace(listOf(ici), (0 until 120).map { Datagramme(true, audio(it, 0x9781, 480, grosBout = true)) })
        val b = AnalyseTraceTx.analyse(t, 50001, 50003)
        assertTrue(b.ok)
        assertEquals(0x9781, b.marque)
        assertEquals(480, b.octets)
        assertEquals(FormatPcm16.GROS, b.format)
        assertFalse(b.conforme(16_000))
    }

    @Test fun rien_d_envoye() {
        assertFalse(AnalyseTraceTx.analyse(trace(listOf(ici), emptyList()), 50001, 50003).ok)
        assertFalse(AnalyseTraceTx.analyse(ByteArray(40), 50001, 50003).ok)
    }
}
