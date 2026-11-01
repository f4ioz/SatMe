/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.aprs.Adresse
import fr.f4ioz.satcombo.aprs.Agwpe
import fr.f4ioz.satcombo.aprs.Ax25
import fr.f4ioz.satcombo.aprs.Kiss
import fr.f4ioz.satcombo.aprs.LienKiss
import fr.f4ioz.satcombo.aprs.Trame
import fr.f4ioz.satcombo.cat.SerialLink
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/** The KISS TCP and AGWPE servers, listening only, on 127.0.0.1 with free ports. Fictitious callsigns. */
class PartageTramesPcTest {

    private val fermer = ArrayList<AutoCloseable>()
    @After fun ferme() { fermer.reversed().forEach { runCatching { it.close() } } }

    private val ui = Ax25.encodeSansFcs(Trame(Adresse.de("APRS"), Adresse.de("F4XYZ-9"), listOf(Adresse.de("WIDE1-1*")), ">essai".toByteArray()))

    /** Both servers on, on free ports (0: the system picks). */
    private fun partage(): PartageTramesPc {
        val st = StockageMemoire()
        st.edit().putInt("pc_partage_kiss_port", 0).putInt("pc_partage_agw_port", 0).apply()
        return PartageTramesPc(st).also { p -> fermer += AutoCloseable { p.arrete() }; p.kissActif = true; p.agwActif = true }
    }

    private fun client(port: Int) = Socket(InetAddress.getLoopbackAddress(), port).also { it.soTimeout = 3000; fermer += it }

    private fun attend(ms: Long = 3000, quoi: () -> Boolean): Boolean {
        val fin = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < fin) { if (quoi()) return true; Thread.sleep(20) }
        return quoi()
    }

    private fun lit(e: InputStream, n: Int): ByteArray {
        val o = ByteArrayOutputStream(); val b = ByteArray(n)
        while (o.size() < n) { val k = e.read(b, 0, n - o.size()); if (k < 0) break; o.write(b, 0, k) }
        return o.toByteArray()
    }

    private fun agw(genre: Char) = Agwpe.Trame(0, genre, 0xF0, "F4XYZ", "APRS", ">emets".toByteArray()).octets()

    @Test
    fun `coupes d'origine, rien n'ecoute`() {
        val p = PartageTramesPc(StockageMemoire())
        p.demarre()
        assertFalse(p.kissActif); assertFalse(p.agwActif); assertFalse(p.reseau)
        assertEquals(8001, p.kissPort); assertEquals(8000, p.agwPort)
        assertEquals("", p.kiss.ecoute); assertEquals("", p.agw.ecoute)
        assertTrue(p.json().getBoolean("ecouteSeule"))
    }

    @Test
    fun `KISS TCP sur 127_0_0_1 donne les trames, refuse celles a emettre`() {
        val p = partage()
        assertTrue(p.kiss.ecoute.startsWith("127.0.0.1:"))
        val c = client(p.kiss.portOuvert)
        assertTrue(attend { p.kiss.clients.size == 1 })
        p.diffuse(ui)
        p.diffuse(ui)  // the same bytes twice within 2 s: once
        val attendu = Kiss.trame(ui)
        assertArrayEquals(attendu, lit(c.getInputStream(), attendu.size))
        // A data frame from the client is a frame to transmit: refused, counted.
        c.getOutputStream().write(Kiss.trame(ui)); c.getOutputStream().write(Kiss.trame(ui, port = 1)); c.getOutputStream().flush()
        assertTrue(attend { p.kiss.refusees.get() == 2 })
        assertEquals(1, p.partagees.get())
    }

    @Test
    fun `AGWPE en moniteur, trames K et U apres k et m, demandes d'emission refusees`() {
        val p = partage()
        val c = client(p.agw.portOuvert)
        val o = c.getOutputStream(); val e = c.getInputStream()
        o.write(Agwpe.Trame(0, 'R', 0, "", "", ByteArray(0)).octets()); o.flush()
        val r = lit(e, 36 + 8)
        assertEquals('R'.code.toByte(), r[4])
        o.write(Agwpe.Trame(0, 'k', 0, "", "", ByteArray(0)).octets()); o.write(Agwpe.Trame(0, 'm', 0, "", "", ByteArray(0)).octets()); o.flush()
        Thread.sleep(200)
        p.diffuse(ui)
        val k = Agwpe.brute(ui).octets(); val u = Agwpe.moniteur(ui, 0L)!!
        val recu = Agwpe.Lecteur().octets(lit(e, k.size + 36 + u.donnees.size))
        assertEquals(listOf('K', 'U'), recu.map { it.genre })
        assertArrayEquals(Agwpe.brute(ui).donnees, recu[0].donnees)
        "KMVCDvcd".forEach { o.write(agw(it)) }; o.flush()
        assertTrue(attend { p.agw.refusees.get() == 8 })
    }

    /**
     * **Nothing reaches a transmitter.** Wired as in SatMe-PC: a KISS radio
     * (a fake serial line that records every byte written to it) feeding
     * the APRS tab and the servers. Clients send everything a program would
     * send to transmit: not one byte goes to the radio, no frame is sent.
     */
    @Test
    fun `rien n'atteint l'emetteur`() {
        val ecrits = CopyOnWriteArrayList<ByteArray>()
        val entrants = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
        val cable = object : SerialLink {
            override fun write(bytes: ByteArray, timeoutMs: Int): Boolean { ecrits += bytes.copyOf(); return true }
            override fun read(buf: ByteArray, timeoutMs: Int): Int {
                val b = entrants.poll(timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS) ?: return 0
                b.copyInto(buf); return b.size
            }
            override fun close() {}
        }
        val dossier = kotlin.io.path.createTempDirectory("partage").toFile().also { d -> fermer += AutoCloseable { d.deleteRecursively() } }
        val aprs = AprsPc(dossier, { "" })
        val p = partage()
        aprs.surAx25 = { p.diffuse(it) }
        val lien = LienKiss { t -> aprs.ajoute(t, System.currentTimeMillis()) }
        lien.surOctets = { p.diffuse(it) }
        lien.attache(cable, "radio fictive", 9600)
        fermer += AutoCloseable { lien.deconnecte(sortirDuKiss = false) }

        val ck = client(p.kiss.portOuvert); val ca = client(p.agw.portOuvert)
        assertTrue(attend { p.kiss.clients.size == 1 && p.agw.clients.size == 1 })
        // The radio hears a frame: the clients get it.
        entrants += Kiss.trame(ui)
        assertTrue(attend { p.partagees.get() == 1 })
        // Everything a client could send to transmit.
        repeat(3) { ck.getOutputStream().write(Kiss.trame(ui)) }; ck.getOutputStream().flush()
        "KMVCDvcd".forEach { ca.getOutputStream().write(agw(it)) }; ca.getOutputStream().flush()
        assertTrue(attend { p.kiss.refusees.get() == 3 && p.agw.refusees.get() == 8 })
        Thread.sleep(300)
        assertEquals("aucun octet vers la radio", 0, ecrits.size)
        assertEquals(0, lien.etat.value.envoyees)
        assertTrue(aprs.paquets.none { it.emis })
        assertEquals(1, aprs.paquets.size)
    }
}
