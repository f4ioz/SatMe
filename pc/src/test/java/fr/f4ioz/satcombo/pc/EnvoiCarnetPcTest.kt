/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import com.sun.net.httpserver.HttpServer
import fr.f4ioz.satcombo.data.LogEntry
import fr.f4ioz.satcombo.data.LogStore
import fr.f4ioz.satcombo.domain.EnvoiAuto
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress

/** Each new contact to the online log after its delay, on the PC as on the phone; and contacts deleted. */
class EnvoiCarnetPcTest {

    private val dossier = File(System.getProperty("java.io.tmpdir"), "satme-envoi-" + System.nanoTime()).apply { mkdirs() }
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val wavelog = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val recus = java.util.concurrent.CopyOnWriteArrayList<String>()
    @After fun fin() { wavelog.stop(0); portee.cancel(); dossier.deleteRecursively() }

    private var t = 1_791_800_000_000L
    private lateinit var st: StationPc

    private fun carnet(): CarnetPc {
        // A Wavelog that takes every contact but F5BAD's.
        wavelog.createContext("/api/qso") { x ->
            val corps = x.requestBody.readBytes().toString(Charsets.UTF_8)
            recus += corps
            val (code, rep) = if ("F5BAD" in corps) 400 to """{"status":"failed","messages":["bad grid"]}""" else 201 to """{"status":"created"}"""
            val b = rep.toByteArray(); x.sendResponseHeaders(code, b.size.toLong()); x.responseBody.use { it.write(b) }
        }
        wavelog.start()
        st = StationPc(StockageMemoire(), Catalogue(File(dossier, "cache"), enLigne = false), PortsSeriePc(), portee)
        st.settings.carnetUrl = "http://127.0.0.1:${wavelog.address.port}/"
        st.settings.carnetCle = "cle"; st.settings.carnetProfil = "1"; st.settings.callsign = "F4IOZ"
        return CarnetPc(st, LogStore(File(dossier, "qso_log.json"))) { t }
    }

    private fun contact(c: String, quand: Long) = LogEntry(quand, "ISS", 25544, 120.0, 20.0, myLocator = "JN18FS", callsign = c, theirLocator = "JN03", mode = "FM")

    /** One tick, and the upload it may start finished. */
    private fun tic(c: CarnetPc) {
        val avant = recus.size
        c.ticEnvoi()
        val fin = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < fin) { Thread.sleep(30); if (recus.size > avant) { Thread.sleep(150); break } }
        Thread.sleep(50)
    }

    @Test
    fun chaque_contact_part_apres_son_delai_un_refus_le_met_en_pause() {
        val c = carnet()
        c.store.add(contact("F1OLD", t - 60_000))         // before the option: never sent by it
        c.setAuto(true)
        c.store.add(contact("F5AAA", t + 1000)); c.store.add(contact("F5BAD", t + 2000))
        t += 30_000
        tic(c)
        assertEquals("still waiting its minute", 0, recus.size)
        assertEquals(EnvoiAuto.Etat.ATTENTE, c.etatEnvoi(c.store.load().first { it.callsign == "F5AAA" }))

        t += 40_000
        tic(c); tic(c); tic(c)
        val l = c.store.load().associateBy { it.callsign }
        assertTrue("taken", l.getValue("F5AAA").envoyeMs > 0)
        assertTrue("refused, held back", l.getValue("F5BAD").retenu && l.getValue("F5BAD").envoyeMs == 0L)
        assertTrue(c.refusEnvoi(l.getValue("F5BAD").timeMs).contains("bad grid"))
        assertEquals(0L, l.getValue("F1OLD").envoyeMs)
        assertEquals(2, recus.size)

        // Released, refused again: it does not go round in circles.
        c.retiens(l.getValue("F5BAD").timeMs, false)
        tic(c)
        assertEquals(3, recus.size)
        tic(c)
        assertEquals(3, recus.size)
    }

    @Test
    fun le_delai_choisi_et_l_option_coupee() {
        val c = carnet()
        c.setAuto(true)
        c.store.add(contact("F5AAA", t + 1000))
        st.settings.carnetAutoDelaiS = 300
        t += 120_000
        tic(c)
        assertEquals(0, recus.size)
        c.setAuto(false)
        t += 600_000
        tic(c)
        assertEquals("off: nothing leaves", 0, recus.size)
    }

    @Test
    fun une_selection_supprimee_d_un_coup() {
        val c = carnet()
        (1..5).forEach { c.store.add(contact("F5A$it", t + it * 1000L)) }
        assertEquals(3, c.supprime(setOf(t + 1000, t + 3000, t + 5000)))
        assertEquals(listOf("F5A2", "F5A4"), c.store.load().map { it.callsign }.sorted())
        assertEquals(0, c.supprime(setOf(42L)))
    }
}
