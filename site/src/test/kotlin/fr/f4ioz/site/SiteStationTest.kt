/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site

import fr.f4ioz.site.securite.Jetons
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files

/** The station switched on: visitors still see « réservé » and nothing of the LAN; the operator sees the radio (a fake one on the loopback). */
class SiteStationTest {

    @Test fun `with the station on, only the admin sees the radio`() {
        val radio = DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        Thread {
            val b = ByteArray(128)
            while (!radio.isClosed) {
                val p = DatagramPacket(b, b.size)
                runCatching { radio.receive(p) }.onFailure { return@Thread }
                if (p.length == 63) { val r = ByteArray(60); r[0] = 0xEF.toByte(); r[1] = 0xFE.toByte(); r[2] = 2; for (i in 3..8) r[i] = 0x42; r[9] = 32; r[10] = 1
                    radio.send(DatagramPacket(r, r.size, p.socketAddress)) }
            }
        }.apply { isDaemon = true; start() }
        val dossier = Files.createTempDirectory("site-station").toFile()
        val site = Site(Config(urlBase = "https://f4ioz.example", port = 0, donnees = File(dossier, "donnees"), contenu = File(dossier, "contenu"), horsLigne = true,
            adminMotDePasse = Jetons.hacheMotDePasse("mot-de-passe-essai", 20_000), cookiesSecurises = false,
            station = true, stationHote = "127.0.0.1", stationPort = radio.localPort, stationDiffusion = false, stationWsPort = 0))
        val port = site.demarre()
        val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
        fun get(c: String, cookie: String? = null): HttpResponse<String> {
            val b = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$c")).GET(); if (cookie != null) b.header("Cookie", cookie)
            return client.send(b.build(), HttpResponse.BodyHandlers.ofString())
        }
        try {
            val publique = get("/station")
            assertTrue(publique.body().contains("Réservé"))
            assertFalse(publique.body().contains("127.0.0.1")); assertFalse(publique.body().contains("42:42"))
            assertEquals(403, get("/api/sdr/status").statusCode())
            val r = client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/admin/connexion")).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("mot_de_passe=mot-de-passe-essai")).build(), HttpResponse.BodyHandlers.ofString())
            val c = r.headers().firstValue("set-cookie").get().substringBefore(';')
            val p = get("/station", c)
            assertTrue(p.body().contains("pan-canvas")); assertTrue(p.body().contains("js/station.js"))
            assertEquals("private, no-store", p.headers().firstValue("cache-control").get())
            val s = JSONObject(get("/api/sdr/status", c).body())
            assertTrue(s.getBoolean("online")); assertEquals("42:42:42:42:42:42", s.getString("mac")); assertEquals("Hermes (ANAN-10/10E)", s.getString("board"))
        } finally { site.arrete(); radio.close(); dossier.deleteRecursively() }
    }
}
