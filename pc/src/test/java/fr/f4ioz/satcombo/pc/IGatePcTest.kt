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
import fr.f4ioz.satcombo.aprs.IGate
import fr.f4ioz.satcombo.aprs.Trame
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The iGate against a **fake APRS-IS server on 127.0.0.1**: never the real
 * network. Fictitious callsigns; the passcode is a made-up number.
 */
class IGatePcTest {

    private val faux = "12345"
    private val ouverts = ArrayList<AutoCloseable>()
    @After fun ferme() { ouverts.forEach { runCatching { it.close() } } }

    /** A fake server: says hello, answers the login as told, keeps every line it gets. */
    private inner class FauxServeur(val verifie: Boolean = true, val bavard: List<String> = emptyList()) {
        val ss = ServerSocket(0, 5, InetAddress.getLoopbackAddress()).also { ouverts += it }
        val lignes = CopyOnWriteArrayList<String>()
        val connexions = AtomicInteger()
        init {
            Thread {
                while (!ss.isClosed) {
                    val c = runCatching { ss.accept() }.getOrNull() ?: break
                    ouverts += c
                    connexions.incrementAndGet()
                    Thread {
                        runCatching {
                            val o = c.getOutputStream()
                            o.write("# aprsc faux 127.0.0.1\r\n".toByteArray()); o.flush()
                            val r = c.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                            while (true) {
                                val l = r.readLine() ?: break
                                lignes += l
                                if (l.startsWith("user ")) {
                                    o.write(("# logresp F4ABC-10 " + (if (verifie) "verified" else "unverified") + ", server T2FAUX\r\n").toByteArray())
                                    // What APRS-IS sends to an iGate: never to go to the air.
                                    bavard.forEach { o.write((it + "\r\n").toByteArray()) }
                                    o.flush()
                                }
                            }
                        }
                    }.apply { isDaemon = true; start() }
                }
            }.apply { isDaemon = true; start() }
        }
        val adresse get() = "127.0.0.1:" + ss.localPort
    }

    private fun trame(src: String, chemin: String, info: String) =
        Trame(Adresse.de("APRS"), Adresse.de(src), chemin.split(',').filter { it.isNotBlank() }.map { Adresse.de(it) }, info.toByteArray())

    private fun attend(ms: Long = 5000, quoi: () -> Boolean): Boolean {
        val fin = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < fin) { if (quoi()) return true; Thread.sleep(20) }
        return quoi()
    }

    private fun igate(st: StockageMemoire = StockageMemoire(), ouvre: ((String, Int) -> Socket)? = null, horloge: () -> Long = System::currentTimeMillis) =
        (if (ouvre == null) IGatePc(st, { "F4ABC" }, horloge, version = "essai") else IGatePc(st, { "F4ABC" }, horloge, ouvre, "essai")).also { g -> ouverts += AutoCloseable { g.arrete() } }

    @Test
    fun `coupe d'origine, rien n'est ouvert ni compte`() {
        val ouvertures = AtomicInteger()
        val g = igate(ouvre = { _, _ -> ouvertures.incrementAndGet(); throw java.io.IOException("non") })
        assertFalse(g.actif)
        assertEquals(IGate.SERVEUR_DEFAUT, g.serveur)
        g.recoit(trame("F4XYZ", "WIDE1-1*", ">test"))
        Thread.sleep(700)
        assertEquals(0, ouvertures.get())
        assertEquals(0, g.json().getJSONObject("refuses").length())
        assertEquals("coupé", g.etat)
    }

    @Test
    fun `transmet a un faux serveur local, avec qAR, et refuse ce qu'il faut`() {
        val s = FauxServeur(bavard = listOf("DL1XYZ>APRS,TCPIP*,qAC,T2FAUX::F4ABC    :emets ceci{1", "F4ZZZ>APRS,qAS,T2FAUX:>message d'Internet"))
        val g = igate()
        g.action(mapOf("serveur" to s.adresse, "indicatif" to "F4ABC-10", "passcode" to faux, "actif" to "1"))
        assertTrue(attend { g.connecte && g.verifie })
        assertTrue(s.lignes.first().startsWith("user F4ABC-10 pass $faux vers SatMe-PC essai"))
        assertEquals("T2FAUX", g.nomServeur)

        g.recoit(trame("F4XYZ-9", "WIDE1-1*,WIDE2-1", "!4852.00N/00220.00E>"))
        g.recoit(trame("EA1XYZ", "RS0ISS*,ARISS", "=4000.00N/00400.00W-"))
        g.recoit(trame("F4XYZ-9", "WIDE1-1*,WIDE2-2*", "!4852.00N/00220.00E>"))  // duplicate
        g.recoit(trame("F4NOG", "NOGATE", ">pas moi"))
        g.recoit(trame("F4RFO", "RFONLY", ">pas moi"))
        g.recoit(trame("F4GW", "WIDE1-1", "}DL1XYZ>APRS,TCPIP,F4GW*:>d'Internet"))
        assertTrue(attend { s.lignes.size >= 3 })
        Thread.sleep(300)
        assertEquals(listOf("F4XYZ-9>APRS,WIDE1-1*,WIDE2-1,qAR,F4ABC-10:!4852.00N/00220.00E>",
            "EA1XYZ>APRS,RS0ISS*,ARISS,qAR,F4ABC-10:=4000.00N/00400.00W-"), s.lignes.drop(1))
        assertEquals(2, g.transmis)
        val r = g.json().getJSONObject("refuses")
        assertEquals(1, r.getInt("doublon")); assertEquals(2, r.getInt("nogate")); assertEquals(1, r.getInt("internet"))
    }

    @Test
    fun `le passcode n'apparait jamais dans l'etat, la page ou une erreur`() {
        val st = StockageMemoire()
        val g = igate(st, ouvre = { _, _ -> throw java.io.IOException("refus pour $faux") })
        g.action(mapOf("serveur" to "127.0.0.1:9", "indicatif" to "F4ABC-10", "passcode" to faux, "actif" to "1"))
        assertTrue(attend { g.erreur.isNotEmpty() })
        val j = g.json()
        assertEquals(true, j.getBoolean("passcode"))
        assertFalse(j.toString().contains(faux))
        assertFalse(g.erreur.contains(faux)); assertFalse(g.etat.contains(faux))
        g.action(mapOf("passcodeEfface" to "1"))
        assertEquals(false, g.json().getBoolean("passcode"))
        // A secret for the settings' export.
        assertTrue(DeploiementPc.secret(IGatePc.CLE_PASSCODE))
    }

    @Test
    fun `non verifie, rien n'est transmis`() {
        val s = FauxServeur(verifie = false)
        val g = igate()
        g.action(mapOf("serveur" to s.adresse, "indicatif" to "F4ABC-10", "passcode" to faux, "actif" to "1"))
        assertTrue(attend { g.connecte && g.etat.contains("non vérifié") })
        g.recoit(trame("F4XYZ", "WIDE1-1*", ">test"))
        Thread.sleep(400)
        assertEquals(1, s.lignes.size)  // the login only
        assertEquals(1, g.json().getJSONObject("refuses").getInt("non-verifie"))
    }

    /** 5 s, 10 s, 20 s… between two calls (a fake clock: the test does not wait). */
    @Test
    fun `reconnexion avec attente croissante`() {
        val maintenant = AtomicLong(1_000_000L)
        val essais = CopyOnWriteArrayList<Long>()
        val g = igate(ouvre = { _, _ -> essais += maintenant.get(); throw java.io.IOException("injoignable") }, horloge = { maintenant.get() })
        g.action(mapOf("serveur" to "127.0.0.1:9", "indicatif" to "F4ABC-10", "passcode" to faux, "actif" to "1"))
        repeat(4) { n ->
            assertTrue(attend { essais.size >= n + 1 })
            assertTrue(attend { g.prochainEssaiMs > maintenant.get() })
            maintenant.set(g.prochainEssaiMs + 1)
        }
        assertTrue(attend { essais.size >= 4 })
        val ecarts = essais.zipWithNext { a, b -> b - a }.take(3)
        assertEquals(listOf(5_001L, 10_001L, 20_001L), ecarts)
    }

    @Test
    fun `hors ligne, les trames sont comptees, pas gardees pour plus tard`() {
        val g = igate(ouvre = { _, _ -> throw java.io.IOException("injoignable") })
        g.action(mapOf("serveur" to "127.0.0.1:9", "indicatif" to "F4ABC-10", "passcode" to faux, "actif" to "1"))
        g.recoit(trame("F4XYZ", "WIDE1-1*", ">test"))
        assertEquals(1, g.json().getJSONObject("refuses").getInt("hors-ligne"))
        assertEquals(0, g.transmis)
    }
}
