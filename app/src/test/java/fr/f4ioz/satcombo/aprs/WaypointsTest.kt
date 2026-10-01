/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.aprs

import fr.f4ioz.satcombo.cat.SerialLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Stations a radio writes as waypoint lines (Yaesu FT3D, OUTPUT = WAY.P). */
class WaypointsTest {

    /** The line the author's FT3D wrote on 30/09/2026 for a frame SatMe sent through the TH-D72. */
    private val reelle = "\$GPWPL,4848.75,N,00227.50,E,F4IOZ-7*7F"

    @Test
    fun ligne_reelle_du_ft3d() {
        val s = Waypoints.lit(reelle)!!
        assertEquals("F4IOZ-7", s.nom)
        assertEquals(48 + 48.75 / 60, s.lat, 1e-9)
        assertEquals(2 + 27.50 / 60, s.lon, 1e-9)
        val p = Aprs.lit(Waypoints.trame(s, "FT3D"), 0)
        assertEquals("F4IOZ-7", p.source)
        assertEquals(48.8125, p.lat!!, 1e-4); assertEquals(2.4583, p.lon!!, 1e-4)
        assertEquals("FT3D", p.commentaire)
    }

    @Test
    fun abimee_ou_autre_phrase_refusee() {
        assertNull(Waypoints.lit(reelle.replace("*7F", "*00")))          // checksum
        assertNull(Waypoints.lit("\$GPRMC,063909,A,3349.4302,N,11700.3721,W,43.0,89.3,291099,,*52"))
        assertNull(Waypoints.lit("garbage"))
        assertEquals("F5RAV", Waypoints.lit("\$GPWPL,4911.50,N,00124.00,W,F5RAV")!!.nom)   // no checksum
        assertEquals(-(1 + 24.0 / 60), Waypoints.lit("\$GPWPL,4911.50,N,00124.00,W,F5RAV")!!.lon, 1e-9)
    }

    @Test
    fun format_kenwood() {
        val s = Waypoints.lit("\$PKWDWPL,150803,V,4237.14,N,07120.83,W,,,190316,,AC1AA-9,/-")!!
        assertEquals("AC1AA-9", s.nom); assertEquals(42 + 37.14 / 60, s.lat, 1e-9)
    }

    private class FauxPoste : SerialLink {
        val file = LinkedBlockingQueue<ByteArray>()
        override fun write(bytes: ByteArray, timeoutMs: Int) = true
        override fun read(buf: ByteArray, timeoutMs: Int): Int {
            val o = file.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS) ?: return 0
            o.copyInto(buf); return o.size
        }
        override fun close() {}
    }

    @Test
    fun recepteur_lignes_coupees_et_parasites() {
        val poste = FauxPoste()
        val vues = ArrayList<Waypoints.Station>()
        RecepteurWaypoints.observateur = { vues += it }
        RecepteurWaypoints.attache(null, poste, "FT3D", 9600)
        poste.file.put("\$GPWPL,4848.75,N,002".toByteArray())
        poste.file.put("27.50,E,F4IOZ-7*7F\r\n\$GPWPL,4911.50,N,00124.00,W,F5RAV\r\nbruit\r\n".toByteArray())
        val fin = System.currentTimeMillis() + 3000
        while (vues.size < 2 && System.currentTimeMillis() < fin) Thread.sleep(20)
        RecepteurWaypoints.deconnecte()
        RecepteurWaypoints.observateur = null
        assertEquals(listOf("F4IOZ-7", "F5RAV"), vues.map { it.nom })
        assertEquals(1, RecepteurWaypoints.etat.value.illisibles)
    }

    /** On demand: `-Dsatme.ft3d=/dev/ttyUSB1` — listens to a real FT3D (never writes to it). */
    @Test
    fun ecoute_d_un_vrai_ft3d() {
        val dev = System.getProperty("satme.ft3d") ?: ""
        assumeTrue(dev.isNotBlank() && File(dev).exists())
        val f = RandomAccessFile(dev, "r")
        fun dispo() = runCatching { java.io.FileInputStream(f.fd).available() }.getOrDefault(0)
        val lien = object : SerialLink {
            override fun write(bytes: ByteArray, timeoutMs: Int) = false
            override fun read(buf: ByteArray, timeoutMs: Int): Int {
                val fin = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < fin) {
                    val d = dispo(); if (d > 0) return f.read(buf, 0, minOf(d, buf.size)); Thread.sleep(20)
                }
                return 0
            }
            override fun close() = f.close()
        }
        val t0 = System.currentTimeMillis()
        RecepteurWaypoints.observateur = { s ->
            println("  +%d s : %s %.4f %.4f".format(java.util.Locale.US, (System.currentTimeMillis() - t0) / 1000, s.nom, s.lat, s.lon))
        }
        RecepteurWaypoints.attache(null, lien, dev, 9600)
        Thread.sleep(1000L * ((System.getProperty("satme.ft3d.ecoute") ?: "120").toIntOrNull() ?: 120))
        val e = RecepteurWaypoints.etat.value
        println("FT3D : ${e.recues} station(s), ${e.illisibles} ligne(s) illisible(s)")
        RecepteurWaypoints.deconnecte()
        RecepteurWaypoints.observateur = null
    }
}
