/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.cat.icomreseau

import fr.f4ioz.satcombo.cat.CivController
import fr.f4ioz.satcombo.data.SettingsStore
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Transmitting over the network: refused unless the owner allowed it; then
 * PTT only (tuner, CW, voice memories still refused), the TX audio stream
 * asked at login and its packets as designed.
 */
class EmissionReseauTest {

    private fun civ(vararg o: Int) = byteArrayOf(0xFE.toByte(), 0xFE.toByte(), 0xA2.toByte(), 0xE0.toByte()) +
        o.map { it.toByte() }.toByteArray() + byteArrayOf(0xFD.toByte())

    private val radios = ArrayList<FausseRadioIcom>()
    @After fun fin() { radios.forEach { it.arrete() } }

    private fun attends(ms: Long = 3_000, ok: () -> Boolean): Boolean {
        val fin = System.currentTimeMillis() + ms
        while (!ok() && System.currentTimeMillis() < fin) Thread.sleep(10)
        return ok()
    }

    @Test fun garde_sans_le_reglage_refuse_le_ptt() {
        assertNotNull(GardeEmission.refus(civ(0x1C, 0x00, 0x01)))
        assertNotNull(GardeEmission.refus(civ(0x1C, 0x00, 0x01), pttAutorise = false))
        assertNotNull(GardeEmission.refus(civ(0x29, 0x01, 0x1C, 0x00, 0x01)))
    }

    @Test fun garde_avec_le_reglage_laisse_le_ptt_et_rien_d_autre() {
        assertNull(GardeEmission.refus(civ(0x1C, 0x00, 0x01), pttAutorise = true))
        assertNull(GardeEmission.refus(civ(0x1C, 0x00, 0x00), pttAutorise = true))
        assertNull(GardeEmission.refus(civ(0x29, 0x01, 0x1C, 0x00, 0x01), pttAutorise = true))
        // Tuner, CW, voice memory: still refused.
        assertNotNull(GardeEmission.refus(civ(0x1C, 0x01, 0x02), pttAutorise = true))
        assertNotNull(GardeEmission.refus(civ(0x17, 0x43, 0x51), pttAutorise = true))
        assertNotNull(GardeEmission.refus(civ(0x28, 0x00, 0x01), pttAutorise = true))
        assertNotNull(GardeEmission.refusDans(civ(0x1C, 0x00, 0x01) + civ(0x17, 0x41), pttAutorise = true))
        // The power level is no PTT: it passes either way.
        assertNull(GardeEmission.refus(civ(0x14, 0x0A, 0x01, 0x28)))
    }

    private fun reglages(r: FausseRadioIcom) = SettingsStore(StockageMemoire()).apply {
        icomHote = "127.0.0.1"; icomPortControle = r.portControle; icomPortCiv = r.portCiv; icomPortAudio = r.portAudio
        icomUtilisateur = "x"; icomUtilisateurCode = "74496a33"; icomMdp = "y"; icomMdpCode = "262222745336364d"
    }

    @Test fun sans_le_reglage_ni_ptt_ni_son_d_emission() = runBlocking {
        val r = FausseRadioIcom(CodageIdentifiants.hex("74496a33")!!, CodageIdentifiants.hex("262222745336364d")!!).also { radios += it }
        val poste = PosteIcomReseau(reglages(r))
        assertNull(poste.prends(PosteIcomReseau.Usage.CAT))
        assertEquals(false, r.txActifDemande)
        val cat = CivController().apply { pacingMs = 20L; attach(poste.lienCiv()) }
        assertFalse(cat.setTransmit(true))
        assertNotNull(poste.sonTx(ShortArray(960), 960, 48_000))
        Thread.sleep(200)
        assertTrue(r.commandesPtt.isEmpty())
        assertTrue(r.audioTx.isEmpty())
        cat.close()
    }

    @Test fun avec_le_reglage_le_ptt_et_le_son_passent() = runBlocking {
        val r = FausseRadioIcom(CodageIdentifiants.hex("74496a33")!!, CodageIdentifiants.hex("262222745336364d")!!).also { radios += it }
        val autorise = AtomicBoolean(true)
        val poste = PosteIcomReseau(reglages(r), emissionAutorisee = { autorise.get() })
        assertNull(poste.prends(PosteIcomReseau.Usage.CAT))
        // The TX stream asked at login: 16-bit at the receive rate.
        assertEquals(true, r.txActifDemande)
        assertEquals(FormatTxReseau.CODEC_TX, r.codecTxDemande)
        assertEquals(16_000, r.tauxTxDemande)
        assertTrue(poste.sonTxDemande)
        val cat = CivController().apply { pacingMs = 20L; attach(poste.lienCiv()) }
        assertTrue(cat.setTransmit(true))
        assertTrue(attends { r.emet })
        // 100 ms of a 1 kHz tone at 48 kHz: 5 packets of 20 ms at 16 kHz.
        val tone = ShortArray(4800) { (8000 * Math.sin(2 * Math.PI * 1000 * it / 48_000.0)).toInt().toShort() }
        assertNull(poste.sonTx(tone, tone.size, 48_000))
        assertTrue(attends { r.audioTx.size >= 4 })
        val p = r.audioTx.first()
        assertEquals(FormatTxReseau.MARQUE_DEFAUT, Paquets.u16be(p, 0x10))
        assertEquals(640, Paquets.u16be(p, Paquets.A_LONGUEUR))
        assertEquals(Paquets.AUDIO_ENTETE + 640, p.size)
        assertEquals(1, Paquets.u16be(r.audioTx[1], Paquets.A_SEQ) - Paquets.u16be(r.audioTx[0], Paquets.A_SEQ))
        assertTrue(cat.setTransmit(false))
        assertTrue(attends { !r.emet })
        // The setting cleared: PTT refused at once (its release still passes).
        autorise.set(false)
        assertFalse(cat.setTransmit(true))
        assertTrue(cat.setTransmit(false))
        assertEquals(listOf(true, false, false), r.commandesPtt.toList())
        cat.close()
        assertTrue(attends { r.jetonRendu })
    }

    @Test fun paquetiseur_coupe_en_paquets_de_20_ms() {
        val p = PaquetiseurTx(16_000)
        assertEquals(640, p.octets)
        // 30 ms then 30 ms at 48 kHz: one packet, then two (60 ms less the filter's delay).
        val a = p.pousse(ShortArray(1440) { 1000 }, 1440, 48_000)
        val b = p.pousse(ShortArray(1440) { 1000 }, 1440, 48_000)
        assertTrue(a.size + b.size in 2..3)
        assertTrue((a + b).all { it.size == 640 })
        // Big-endian when the radio's receive stream says so.
        val g = PaquetiseurTx(8_000, FormatPcm16.GROS, 320).pousse(ShortArray(4800) { 16384 }, 4800, 48_000).last()
        assertTrue((g[0].toInt() and 0xFF) in 0x3E..0x41)
    }
}
