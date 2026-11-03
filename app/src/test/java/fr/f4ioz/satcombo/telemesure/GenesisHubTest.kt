/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.telemesure

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import fr.f4ioz.satcombo.data.SettingsStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * GENESIS on the phone: a synthetic GENESIS WAV read back (telemetry made
 * readable, SSDV packets kept), a picture put together over two passes and
 * read back from disk, the choice of the decoder by the satellite.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GenesisHubTest {

    private lateinit var app: Application

    @Before fun avant() {
        app = ApplicationProvider.getApplicationContext()
        GenesisHub.oublie()
        GenesisHub.profilSatellite = { null }
    }

    @After fun apres() { GenesisHub.oublie(); GenesisHub.profilSatellite = { null } }

    private fun wav(f: File, son: ShortArray, rate: Int) {
        val b = ByteBuffer.allocate(44 + son.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + son.size * 2).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(son.size * 2)
        son.forEach { b.putShort(it) }
        f.writeBytes(b.array())
    }

    private fun enregistrement(nom: String, garde: (Int) -> Boolean): File {
        val d = File(app.cacheDir, "wav").apply { mkdirs() }
        return File(d, nom).also { wav(it, GenesisHub.signalEssai(RecepteurGenesis.Profil.G800, 44_100, 7, graine = 3L, garde = garde), 44_100) }
    }

    @Test
    fun `un WAV GENESIS synthetique donne la telemesure lisible et l'image`() {
        SettingsStore(app).genesisProfil = "800"
        val f = enregistrement("SatMe_HADES-SA_20261102_101500Z.wav") { it < 6 }
        val n = GenesisHub.decodeFichier(app, f)
        assertTrue("trouvés : $n", n >= 7)

        // The telemetry frame, kept with its satellite and made readable with its units.
        val trames = GenesisHub.trames(app, JOUR, JOUR + 86_400_000L).filter { it.type == 2 }
        assertEquals(1, trames.size)
        assertEquals("HADES-SA", trames[0].sat)
        val l = TelemesureGenesis.lis(trames[0].type, trames[0].hex)!!
        assertEquals("°C", l.champ("cpu")!!.u)
        assertEquals(22.5, l.champ("cpu")!!.v!!, 0.0)

        // The SSDV packets, in one picture, credited to that recording.
        val ph = GenesisHub.assemblage(app).photos().single()
        assertEquals(6, ph.recus)
        assertFalse(ph.complete)
        assertTrue(ph.passages.single().source.startsWith(AssemblageSsdv.SOURCE_ENREGISTREMENT))
        assertEquals(GenesisHub.etat.value.trouves, n)
    }

    @Test
    fun `une image partielle reunie sur deux passages, relue du disque`() {
        SettingsStore(app).genesisProfil = "800"
        // Two passes 95 min apart, each with its share of the packets (some missing from both).
        val a = enregistrement("SatMe_HADES-SA_20261102_101500Z.wav") { it % 2 == 0 && it < 12 }
        val b = enregistrement("SatMe_HADES-SA_20261102_115000Z.wav") { it % 3 == 1 && it < 12 }
        GenesisHub.decodeFichier(app, a)
        GenesisHub.decodeFichier(app, b)

        val ph = GenesisHub.assemblage(app).photos().single()
        val attendus = (0 until 12).count { it % 2 == 0 || it % 3 == 1 }
        assertEquals(attendus, ph.recus)
        assertEquals(2, ph.contributeurs().size)
        assertFalse(ph.complete)
        // Some packets missing: the strip says where.
        assertTrue(ph.bande().any { it.third == -1 })

        // Kept on disk: a new start reads the same picture back, with its two passes.
        val relue = AssemblageSsdv(GenesisHub.dossier(app)).photos().single()
        assertEquals(attendus, relue.recus)
        assertEquals(2, relue.contributeurs().size)
        assertEquals(ph.id, relue.id)
    }

    @Test
    fun `le decodeur suit le satellite`() {
        val reg = SettingsStore(app)
        assertEquals("SATELLITE", reg.genesisMode)
        // A satellite with no GENESIS transmitter: nothing decoded.
        GenesisHub.profilSatellite = { 12345 to null }
        GenesisHub.startLive(app, 44_100, "Q-SAT")
        assertTrue(attends { GenesisHub.etat.value.decision == "non" })
        assertFalse(GenesisHub.etat.value.ecoute)
        // One whose SatNOGS transmitter says GENESIS: both rates at once.
        GenesisHub.profilSatellite = { 68446 to RecepteurGenesis.Profil.AUTO }
        GenesisHub.startLive(app, 44_100, "HADES-SA")
        assertTrue(attends { GenesisHub.etat.value.ecoute })
        assertEquals("satellite", GenesisHub.etat.value.decision)
        assertEquals("auto", GenesisHub.etat.value.profil)
        GenesisHub.stopLive()
        assertFalse(GenesisHub.etat.value.ecoute)
        // Switched off by the operator: never.
        reg.genesisMode = "COUPE"
        GenesisHub.startLive(app, 44_100, "HADES-SA")
        assertEquals("coupe", GenesisHub.etat.value.decision)
        // Always: even a satellite SatNOGS does not know.
        reg.genesisMode = "TOUJOURS"; reg.genesisProfil = "200"
        GenesisHub.profilSatellite = { null }
        GenesisHub.startLive(app, 44_100, "Q-SAT")
        assertTrue(attends { GenesisHub.etat.value.ecoute })
        assertEquals("200", GenesisHub.etat.value.profil)
        assertNotNull(GenesisHub.recepteur(app))
        GenesisHub.stopLive()
    }

    /** 2 November 2026, 00:00 UTC: the day of the recordings. */
    private val JOUR = 1_793_577_600_000L

    private fun attends(ms: Long = 5_000, f: () -> Boolean): Boolean {
        val fin = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < fin) { if (f()) return true; Thread.sleep(20) }
        return f()
    }
}
