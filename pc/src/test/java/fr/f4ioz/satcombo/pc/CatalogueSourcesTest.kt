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
import fr.f4ioz.satcombo.data.Sources
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress

/** The PC's orbital elements as on the phone: the sources chosen, the SatMe GP server first or only, never CelesTrak itself. */
class CatalogueSourcesTest {

    private val dossier = File(System.getProperty("java.io.tmpdir"), "satme-sources-" + System.nanoTime()).apply { mkdirs() }
    private val serveur = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val demandes = java.util.concurrent.CopyOnWriteArrayList<String>()
    @After fun fin() { serveur.stop(0); dossier.deleteRecursively() }

    private fun res(n: String) = javaClass.getResource("/gp/$n")!!.readText()

    /** A GP server knowing the ISS (AMSAT's group), Geoscan-2 (by number) and Geoscan-4 under SatNOGS' raw name. */
    private fun catalogue(): Catalogue {
        serveur.createContext("/") { x ->
            val corps = when (x.requestURI.path) {
                "/gp/amsat.json" -> res("catnr-25544.json")
                "/gp/catnr/64890.json" -> res("catnr-64890.json")
                "/gp/catnr/64892.json" -> res("objet-64892.json")
                "/gp/catnr/25544.json" -> res("catnr-25544.json")
                else -> null
            }
            val b = (corps ?: "[]").toByteArray()
            x.sendResponseHeaders(if (corps == null && !x.requestURI.path.startsWith("/gp/catnr/")) 404 else 200, b.size.toLong()); x.responseBody.use { it.write(b) }
        }
        serveur.start()
        // Anything but this server: refused here, and written down (nothing leaves the machine).
        val client = OkHttpClient.Builder().addInterceptor { ch ->
            val u = ch.request().url.toString(); demandes += u
            if (ch.request().url.host == "127.0.0.1") ch.proceed(ch.request())
            else Response.Builder().request(ch.request()).protocol(Protocol.HTTP_1_1).code(503).message("hors test").body("".toResponseBody()).build()
        }.build()
        return Catalogue(dossier, client)
    }

    private val ici get() = "http://127.0.0.1:${serveur.address.port}"

    @Test
    fun le_serveur_seul_avec_les_satellites_d_images() {
        val c = catalogue()
        c.choix = Catalogue.Choix(setOf("amsat_gp", Sources.IMAGES, "amateur"), ici, seul = true)
        val dit = c.rafraichis()
        assertTrue(dit, dit.startsWith("éléments à jour"))
        assertTrue("server only", demandes.all { it.startsWith(ici) })
        val noms = c.satellites().map { it.name }
        assertTrue(noms.toString(), "GEOSCAN 2 (RS92S2)" in noms)
        assertTrue("SatNOGS' raw name replaced", "Geoscan-4" in noms)
        assertTrue(noms.any { it.startsWith("ISS") })
        assertFalse(c.choixChange())
        c.choix = c.choix.copy(sources = setOf("amsat_gp"))
        assertTrue("a source less: download again", c.choixChange())
    }

    @Test
    fun celestrak_jamais_meme_sans_serveur() {
        val c = catalogue()
        c.choix = Catalogue.Choix(setOf("amsat_gp", "amateur", "stations", Sources.SATNOGS), "", seul = false)
        c.rafraichis()
        assertTrue(demandes.isNotEmpty())
        assertTrue(demandes.toString(), demandes.none { it.contains("celestrak", true) })
        assertTrue(demandes.any { it.contains("amsat.org") })
    }

    @Test
    fun le_serveur_d_abord_la_source_ensuite() {
        val c = catalogue()
        c.choix = Catalogue.Choix(setOf("amsat_gp"), ici, seul = false)
        c.rafraichis()
        // AMSAT's TLE group is unknown here (404): its own address is tried next.
        val i = demandes.indexOfFirst { it.startsWith("$ici/gp/amsat_tle.json") }
        assertTrue(i >= 0 && demandes.drop(i + 1).any { it.contains("amsat.org/tle") })
        assertEquals(1, c.satellites().count { it.catalogNumber == 25544 })
    }
}
