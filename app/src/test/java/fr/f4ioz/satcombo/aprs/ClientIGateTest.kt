/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.aprs

import fr.f4ioz.satcombo.data.SettingsStore
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The phone's receive-only iGate against a fake APRS-IS server on
 * 127.0.0.1 — never the real network. Fictitious callsigns only.
 */
class ClientIGateTest {

    /** A tiny APRS-IS server: greets, answers the login, keeps every line the client writes, sends a packet of its own. */
    private class FauxServeur(private val verifie: Boolean = true) {
        val serveur = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        val lignes = CopyOnWriteArrayList<String>()
        val connexions = AtomicInteger()
        private val fil = Thread {
            while (!serveur.isClosed) {
                val s = runCatching { serveur.accept() }.getOrNull() ?: break
                connexions.incrementAndGet()
                Thread {
                    runCatching {
                        s.use { c ->
                            val o = c.getOutputStream()
                            o.write("# aprsc 2.1 FAUX\r\n".toByteArray())
                            c.getInputStream().bufferedReader(Charsets.ISO_8859_1).forEachLine { l ->
                                lignes += l
                                if (l.startsWith("user ")) {
                                    val ind = l.split(' ')[1]
                                    o.write(("# logresp $ind " + (if (verifie) "verified" else "unverified") + ", server T2FAUX\r\n").toByteArray())
                                    // What a server may send: thrown away by the client, never to a radio.
                                    o.write("Q0AAA>APRS,TCPIP*,qAC,T2FAUX:>depuis Internet\r\n".toByteArray())
                                }
                                o.flush()
                            }
                        }
                    }
                }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true; start() }
        val port: Int get() = serveur.localPort
        fun ferme() { runCatching { serveur.close() } }
    }

    private val serveurs = ArrayList<FauxServeur>()
    private val clients = ArrayList<ClientIGate>()

    @After fun range() { clients.forEach { it.arrete() }; serveurs.forEach { it.ferme() } }

    private fun t(src: String, chemin: String, info: String, dst: String = "APRS") =
        Trame(Adresse.de(dst), Adresse.de(src), chemin.split(',').filter { it.isNotBlank() }.map { Adresse.de(it) },
            info.toByteArray(Charsets.ISO_8859_1))

    private fun attends(ms: Long = 5_000, f: () -> Boolean): Boolean {
        val fin = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < fin) { if (f()) return true; Thread.sleep(20) }
        return f()
    }

    /** Only the loopback: a test that would reach the real APRS-IS fails instead. */
    private val local: (String, Int) -> Socket = { h, p ->
        require(h == "127.0.0.1") { "hors de la boucle locale : $h" }
        Socket().apply { connect(InetSocketAddress(h, p), 2_000) }
    }

    private fun client(r: () -> ClientIGate.Reglages) =
        ClientIGate(r, ouvre = local, version = { "essai" }).also { clients += it }

    @Test
    fun `coupe d'origine`() {
        val s = SettingsStore(StockageMemoire())
        assertFalse(s.igateActif)
        assertEquals("", s.igateServeur)
        assertEquals("", s.igateIndicatif)
    }

    @Test
    fun `coupe, il ne se connecte pas et ne transmet rien`() {
        val srv = FauxServeur().also { serveurs += it }
        val c = client { ClientIGate.Reglages(false, "127.0.0.1:${srv.port}", "F0SIM-10", "12345") }
        c.recoit(t("Q1AAA", "WIDE1-1*", ">bonjour"))
        Thread.sleep(800)
        assertEquals(0, srv.connexions.get())
        assertEquals("coupe", c.etat.value.phase)
        assertEquals(0, c.etat.value.transmis)
    }

    @Test
    fun `sans indicatif ni code, rien ne part`() {
        val srv = FauxServeur().also { serveurs += it }
        val c = client { ClientIGate.Reglages(true, "127.0.0.1:${srv.port}", "F0SIM-10", "") }
        assertTrue(attends { c.etat.value.phase == "manque" })
        assertEquals("passcode", c.etat.value.manque)
        assertEquals(0, srv.connexions.get())
    }

    @Test
    fun `RF vers IS seulement, avec les regles d'APRS-IS`() {
        val srv = FauxServeur().also { serveurs += it }
        val c = client { ClientIGate.Reglages(true, "127.0.0.1:${srv.port}", "F0SIM-10", "12345") }
        assertTrue("connexion vérifiée", attends { c.etat.value.phase == "connecte" })
        assertEquals("T2FAUX", c.etat.value.nomServeur)
        // The login: the iGate's call, its passcode, the software; no filter, no position.
        val login = srv.lignes.first()
        assertTrue(login, login.startsWith("user F0SIM-10 pass 12345 vers SatMe essai"))
        assertFalse(login, login.contains("filter"))

        c.recoit(t("Q1AAA-9", "WIDE1-1*,WIDE2-1", "!4852.00N/00220.00E>"))
        c.recoit(t("Q1BBB", "RS0ISS*,ARISS", "=4000.00N/00400.00W-"))
        // Refused: NOGATE, RFONLY, already through the Internet, a query.
        c.recoit(t("Q1CCC", "NOGATE", ">pas moi"))
        c.recoit(t("Q1CCC", "WIDE1-1*,RFONLY", ">pas moi non plus"))
        c.recoit(t("Q1DDD", "TCPIP*", ">deja sur Internet"))
        c.recoit(t("Q1GW", "", "}Q1EEE>APRS,TCPIP,Q1GW*:>par une passerelle"))
        c.recoit(t("Q1FFF", "", "?APRS?"))
        // The same frame again within 30 s, by another path: a duplicate.
        c.recoit(t("Q1AAA-9", "WIDE1-1*", "!4852.00N/00220.00E>"))

        assertTrue(attends { c.etat.value.transmis == 2 })
        Thread.sleep(500)
        val parties = srv.lignes.drop(1)
        assertEquals(listOf(
            "Q1AAA-9>APRS,WIDE1-1*,WIDE2-1,qAR,F0SIM-10:!4852.00N/00220.00E>",
            "Q1BBB>APRS,RS0ISS*,ARISS,qAR,F0SIM-10:=4000.00N/00400.00W-"), parties)
        val e = c.etat.value
        assertEquals(2, e.refuses["nogate"])
        assertEquals(2, e.refuses["internet"])
        assertEquals(1, e.refuses["requete"])
        assertEquals(1, e.refuses["doublon"])
        // What the server sent was read and thrown away: nothing written back, nothing counted.
        assertTrue(parties.none { it.contains("depuis Internet") })
        // The passcode is in no state shown.
        assertFalse(e.toString().contains("12345"))
    }

    @Test
    fun `non verifie, rien n'est transmis`() {
        val srv = FauxServeur(verifie = false).also { serveurs += it }
        val c = client { ClientIGate.Reglages(true, "127.0.0.1:${srv.port}", "F5SIM-10", "1") }
        assertTrue(attends { c.etat.value.phase == "non-verifie" })
        c.recoit(t("Q1AAA", "WIDE1-1*", ">bonjour"))
        Thread.sleep(600)
        assertEquals(1, srv.lignes.size)
        assertEquals(0, c.etat.value.transmis)
        assertEquals(1, c.etat.value.refuses["non-verifie"])
    }

    @Test
    fun `coupe en cours de route, la connexion est quittee`() {
        val srv = FauxServeur().also { serveurs += it }
        val actif = java.util.concurrent.atomic.AtomicBoolean(true)
        val c = client { ClientIGate.Reglages(actif.get(), "127.0.0.1:${srv.port}", "F0SIM-10", "12345") }
        assertTrue(attends { c.etat.value.phase == "connecte" })
        actif.set(false)
        assertTrue(attends { c.etat.value.phase == "coupe" })
        c.recoit(t("Q1AAA", "WIDE1-1*", ">apres"))
        Thread.sleep(300)
        assertEquals(1, srv.lignes.size)
    }
}
