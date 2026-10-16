/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.plateforme.StockageMemoire
import fr.f4ioz.satcombo.sdr.Dsp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.File
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.random.Random

/** The transponder's signals judged on the PC, from a fake dongle on QO-100 showing one carrier. */
class SignauxQo100PcTest {

    private val dossier = File(System.getProperty("java.io.tmpdir"), "satme-signaux-" + System.nanoTime()).apply { mkdirs() }
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val serveur = ServerSocket(0)
    @Volatile private var fini = false
    @Volatile private var pllFaux = 0L
    /** 10 489.760 MHz through a 9 750 MHz LNB. */
    private val porteuseHz = 739_760_000L

    @After fun fin() { fini = true; runCatching { serveur.close() }; portee.cancel(); dossier.deleteRecursively() }

    private fun attends(ms: Long, quoi: () -> Boolean) {
        val fin = System.currentTimeMillis() + ms
        while (!quoi()) { check(System.currentTimeMillis() < fin) { "trop long" }; Thread.sleep(100) }
    }

    private fun fausseParabole() = thread(isDaemon = true) {
        runCatching {
            serveur.accept().use { c ->
                val o = c.getOutputStream()
                o.write("RTL0".toByteArray() + byteArrayOf(0, 0, 0, 5, 0, 0, 0, 29)); o.flush()
                thread(isDaemon = true) {
                    val e = DataInputStream(c.getInputStream())
                    runCatching { while (true) { val k = e.readUnsignedByte(); val v = e.readInt(); if (k == 0x01) pllFaux = v.toLong() and 0xFFFFFFFFL } }
                }
                val r = Random(2)
                var phase = 0.0
                val bloc = ByteArray(16_384)
                while (!fini) {
                    val pas = 2 * Math.PI * (porteuseHz - pllFaux).toDouble() / Dsp.RTL_RATE
                    for (i in 0 until bloc.size / 2) {
                        phase += pas
                        bloc[2 * i] = (127.5 + 4 * Math.cos(phase) + r.nextDouble(-6.0, 6.0)).toInt().toByte()
                        bloc[2 * i + 1] = (127.5 + 4 * Math.sin(phase) + r.nextDouble(-6.0, 6.0)).toInt().toByte()
                    }
                    o.write(bloc); Thread.sleep(7)
                }
            }
        }
    }

    @Test fun rien_sans_page_puis_la_porteuse_reconnue() {
        val st = StationPc(StockageMemoire(), Catalogue(dossier, enLigne = false), PortsSeriePc(), portee)
        st.settings.manualLocator = "JN18FS"
        st.demarre()
        st.settings.sdrAudio = false
        st.qo100.poseOl(true, 9_750_000_000L)
        st.qo100.descente(10_489_700_000L)
        st.qo100.aLaCle(true)
        val sdr = SdrPc(st, SonPc(), StockageMemoire(), portee)
        sdr.lance = false; sdr.port = serveur.localPort
        val q = Qo100Pc(st, sdr)
        fausseParabole()
        assertTrue(sdr.demarre())
        attends(8_000) { sdr.etat.value.actif && pllFaux > 0 }
        Thread.sleep(500)
        // No page asked: nothing analysed.
        assertFalse(q.signaux.json().getBoolean("actif"))
        assertEquals(0, q.signaux.json().getJSONArray("signaux").length())
        // A page polling: the carrier found and judged, at its sky frequency.
        attends(25_000) {
            q.signaux.demande()
            val l = q.signaux.json().getJSONArray("signaux")
            (0 until l.length()).any { val s = l.getJSONObject(it); s.getString("classe") == "porteuse" && Math.abs(s.getLong("hz") - 10_489_760_000L) < 100 }
        }
        assertTrue(q.signaux.json().getBoolean("actif"))
        sdr.arrete()
    }
}
