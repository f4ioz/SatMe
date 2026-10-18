/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.domain.Qo100
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket

/** The frequency for logging programs: what is read, the SDR retuned only when allowed, the socket. */
class RigctldPcTest {

    private val dossier = File(System.getProperty("java.io.tmpdir"), "satme-rigctld-" + System.nanoTime()).apply { mkdirs() }
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var rig: RigctldPc? = null

    @After fun fin() { rig?.arrete(); portee.cancel(); dossier.deleteRecursively() }

    private fun monte(emet: () -> Boolean = { false }): Triple<StationPc, SdrPc, RigctldPc> {
        val stockage = StockageMemoire()
        val st = StationPc(stockage, Catalogue(dossier, enLigne = false), PortsSeriePc(), portee)
        st.settings.sdrAudio = false
        val sdr = SdrPc(st, SonPc(), stockage, portee).apply { lance = false; suitSatellite = false; manuelleHz = 145_800_000L; modeChoisi = "NFM" }
        return Triple(st, sdr, RigctldPc(st, sdr, reseau = false, emet = emet).also { rig = it })
    }

    @Test fun offParDefaut() {
        val (_, _, r) = monte()
        assertFalse(r.actif); assertFalse(r.accordPermis); assertFalse(r.montee); assertFalse(r.surReseau)
        assertEquals(4532, r.port)
        r.demarreSiActif()
        assertFalse(r.ecoute)
    }

    @Test fun leRecepteurSdr() {
        var tx = false
        val (_, _, r) = monte { tx }
        val l = r.lecture()
        assertEquals(145_800_000L, l.frequenceHz); assertEquals("FM", l.mode); assertNull(l.emissionHz); assertFalse(l.emet)
        tx = true
        assertTrue(r.lecture().emet)
    }

    @Test fun qo100SurLaCle() {
        val (st, sdr, r) = monte()
        st.qo100.aLaCle(true)
        st.qo100.descente(10_489_800_000L)
        sdr.decalageHz = 500
        val l = r.lecture()
        assertEquals(10_489_800_500L, l.frequenceHz)
        assertEquals(Qo100.monteeDepuisDescente(10_489_800_500L), l.emissionHz)
        r.regle(enMontee = true)
        assertEquals(2_400_300_500L, r.lecture().frequenceHz)
    }

    @Test fun accordSeulementPermisEtSurLeSdr() {
        val (st, sdr, r) = monte()
        assertEquals(-11, r.accorde(435_000_000L))
        assertEquals(145_800_000L, sdr.manuelleHz)
        r.regle(accord = true)
        assertTrue(r.lecture().accordPermis)
        assertEquals(0, r.accorde(435_000_000L))
        assertEquals(435_000_000L, sdr.manuelleHz)
        assertEquals(0, r.mode("USB", 2700))
        assertEquals("USB", sdr.modeEffectif()); assertEquals(2700, st.settings.sdrBandwidthHz)
        assertEquals(-1, r.accorde(10_000L))
        // QO-100 on the dongle: the downlink, an uplink turned into it.
        st.qo100.aLaCle(true)
        assertEquals(0, r.accorde(2_400_250_000L))
        assertEquals(10_489_750_000L, st.qo100.etat.value.descenteHz)
        // QO-100 on the rig: nothing moves.
        st.qo100.auPoste(true)
        assertFalse(r.lecture().accordPermis)
        assertEquals(-11, r.accorde(10_489_800_000L))
        assertEquals(10_489_750_000L, st.qo100.etat.value.descenteHz)
    }

    @Test fun pasLePortDuRotor() {
        val (st, _, r) = monte()
        st.settings.rotorLink = "ROTCTLD"; st.settings.rotorHost = "127.0.0.1"; st.settings.rotorPort = 4600
        r.regle(on = true, nouveauPort = 4600)
        assertFalse(r.ecoute)
        assertTrue(r.erreur.contains("rotor"))
    }

    /** As a logging program: the opening dump, then polling; this PC only. */
    @Test fun parLeReseau() {
        val (_, _, r) = monte()
        val libre = ServerSocket(0).use { it.localPort }
        r.regle(on = true, nouveauPort = libre)
        assertTrue(r.erreur, r.ecoute)
        Socket("127.0.0.1", libre).use { c ->
            val e = BufferedReader(InputStreamReader(c.getInputStream()))
            val o = c.getOutputStream()
            o.write("\\chk_vfo\n\\dump_state\n".toByteArray()); o.flush()
            assertEquals("0", e.readLine())
            while (e.readLine() != "done") Unit
            o.write("f\n+m\nT 1\nF 435000000\n".toByteArray()); o.flush()
            assertEquals("145800000", e.readLine())
            assertEquals("get_mode:", e.readLine()); assertEquals("Mode: FM", e.readLine()); e.readLine(); assertEquals("RPRT 0", e.readLine())
            assertEquals("RPRT -11", e.readLine())
            assertEquals("RPRT -11", e.readLine())
            assertEquals(1, r.json().getInt("clients"))
        }
        r.regle(on = false)
        assertFalse(r.ecoute)
    }
}
