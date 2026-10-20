/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.telemesure

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URLDecoder

/** The SiDS form, posted to a fake server on this machine (never to SatNOGS DB from the tests). */
class SidsTest {

    @Test fun champsEtPositionArrondie() {
        val e = Sids.Envoi(99999, "f4ioz", 1_792_000_000_123L, "a0b1", 48.8566, -2.3522)
        val c = Sids.champs(e).toMap()
        assertEquals("99999", c["noradID"])
        assertEquals("F4IOZ", c["source"])
        assertEquals("2026-10-14T17:46:40.123Z", c["timestamp"])
        assertEquals("A0B1", c["frame"])
        assertEquals("longLat", c["locator"])
        assertEquals("48.9N", c["latitude"])
        assertEquals("2.4W", c["longitude"])
    }

    @Test fun envoiAuFauxServeurEtReprise() {
        val recus = java.util.concurrent.CopyOnWriteArrayList<Map<String, String>>()
        val refuse = java.util.concurrent.atomic.AtomicBoolean(true)
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/api/telemetry/") { x ->
            val corps = x.requestBody.readBytes().toString(Charsets.UTF_8)
            val ok = x.requestMethod == "POST" && !refuse.get() &&
                x.requestHeaders.getFirst("Content-Type").orEmpty().startsWith("application/x-www-form-urlencoded")
            if (ok) recus += corps.split('&').associate { kv -> kv.substringBefore('=') to URLDecoder.decode(kv.substringAfter('='), "UTF-8") }
            val r = if (ok) "{}".toByteArray() else "non".toByteArray()
            x.sendResponseHeaders(if (ok) 201 else 503, r.size.toLong()); x.responseBody.use { it.write(r) }
        }
        s.start()
        try {
            val sids = Sids("http://127.0.0.1:" + s.address.port + "/api/telemetry/")
            sids.ajoute(Sids.Envoi(40931, "F4IOZ", 1_792_000_000_000L, "C0FFEE", 48.85, 2.35))
            sids.ajoute(Sids.Envoi(40931, "F4IOZ", 1_792_000_001_000L, "BEEF", 48.85, 2.35))
            // The server refuses: nothing lost, tried again later.
            assertEquals(0, sids.vide())
            assertEquals(2, sids.enAttente)
            refuse.set(false)
            assertEquals(2, sids.vide())
            assertEquals(0, sids.enAttente)
            assertEquals(2, recus.size)
            assertEquals("C0FFEE", recus[0]["frame"])
            assertEquals("40931", recus[0]["noradID"])
            assertEquals("F4IOZ", recus[1]["source"])
            assertTrue(recus[1]["timestamp"]!!.endsWith("Z"))
        } finally { s.stop(0) }
    }
}
