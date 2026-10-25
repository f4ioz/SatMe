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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

/** The knob relayed to another station: its login, its keys sent, its session ended; Windows' keys; the portable folder. */
class RelaisMolettePcTest {

    /** A station as the relay sees it: its login page, its knob's routes. */
    private class Station(val mdp: String = "abeille-ancre-astre-aube") : AutoCloseable {
        val recus = CopyOnWriteArrayList<String>()
        @Volatile var sessionValide = true
        val serveur: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/connexion") { e ->
                val corps = e.requestBody.readBytes().toString(Charsets.UTF_8)
                if (corps.contains("mdp=$mdp")) { e.responseHeaders.add("Set-Cookie", AccesDistantPc.COOKIE + "=jeton123; Path=/; HttpOnly; SameSite=Strict"); e.responseHeaders.add("Location", "/"); e.sendResponseHeaders(303, -1) }
                else e.sendResponseHeaders(401, -1)
                e.close()
            }
            createContext("/api/molette/touches") { e ->
                val ok = sessionValide && e.requestHeaders.getFirst("Cookie") == AccesDistantPc.COOKIE + "=jeton123"
                if (ok) recus += java.net.URLDecoder.decode(e.requestURI.rawQuery.removePrefix("l="), "UTF-8")
                val b = (if (ok) "{\"ok\":true,\"geste\":\"VFO +100 Hz\"}" else "{\"ok\":false}").toByteArray()
                e.sendResponseHeaders(if (ok) 200 else 401, b.size.toLong()); e.responseBody.write(b); e.close()
            }
            createContext("/api/molette") { e -> val b = "{\"on\":false}".toByteArray(); e.sendResponseHeaders(200, b.size.toLong()); e.responseBody.write(b); e.close() }
            createContext("/deconnexion") { e -> e.sendResponseHeaders(200, -1); e.close() }
            start()
        }
        val adresse get() = "http://127.0.0.1:" + serveur.address.port
        override fun close() = serveur.stop(0)
    }

    private fun attend(cond: () -> Boolean) { val fin = System.currentTimeMillis() + 5000; while (!cond() && System.currentTimeMillis() < fin) Thread.sleep(20) }

    @Test
    fun connexion_envoi_et_fin_de_session() {
        val d = Files.createTempDirectory("relais").toFile()
        Station().use { st ->
            val f = File(d, "relais-molette.properties")
            val r = RelaisMolettePc(f)
            assertFalse(r.actif)
            assertFalse("rien d'envoyé sans station", r.envoie(115, 1))
            assertEquals("mot de passe refusé (ou trop d'essais : attendre un peu)", r.connecte(st.adresse, "faux"))
            assertNull(r.connecte(st.adresse + "/", st.mdp))
            assertTrue(r.actif)
            // Only the session is kept, never the password.
            val garde = f.readText()
            assertTrue(garde.contains("jeton123")); assertFalse(garde.contains(st.mdp))
            assertTrue(r.envoie(115, 1)); r.envoie(115, 0); r.envoie(114, 2)
            attend { st.recus.joinToString(",").split(',').size >= 2 }
            assertEquals(listOf("115:1", "114:2"), st.recus.joinToString(",").split(','))
            attend { r.envoyes == 2L }
            assertEquals("connecte", r.etat)
            // Kept across a restart.
            assertTrue(RelaisMolettePc(f).actif)
            // The session ended on the station: told, waiting for the password again.
            st.sessionValide = false
            r.envoie(115, 1)
            attend { r.etat == "reconnexion" }
            assertEquals("reconnexion", r.etat); assertFalse(r.actif)
            r.deconnecte()
            assertFalse(RelaisMolettePc(f).actif)
        }
        d.deleteRecursively()
    }

    @Test
    fun adresses_et_cookie() {
        assertEquals("https://satme.example.org", RelaisMolettePc.normalise(" https://satme.example.org/ "))
        assertEquals("http://192.168.0.65:8090", RelaisMolettePc.normalise("http://192.168.0.65:8090"))
        assertNull(RelaisMolettePc.normalise("satme.example.org"))
        assertNull(RelaisMolettePc.normalise("ftp://x"))
        assertEquals("abc", RelaisMolettePc.lisCookie(AccesDistantPc.COOKIE + "=abc; Path=/; HttpOnly"))
        assertNull(RelaisMolettePc.lisCookie("autre=abc"))
        assertNull(RelaisMolettePc.lisCookie(AccesDistantPc.COOKIE + "=; Max-Age=0"))
    }

    @Test
    fun la_molette_relaye_au_lieu_d_accorder() {
        val d = Files.createTempDirectory("relais").toFile()
        Station().use { st ->
            val accords = CopyOnWriteArrayList<Long>()
            val monde = object : MoletteUsbPc.Monde {
                override fun contexte(page: String?) = fr.f4ioz.satcombo.domain.MoletteUsb.Contexte(page, catRelie = true, sdrEnMarche = false)
                override fun vfo(dHz: Long) { accords += dHz }
                override fun shiftRx(dHz: Long) {}
                override fun shiftTx(dHz: Long) {}
                override fun zeroRx() {}
                override fun zeroTx() {}
                override fun sdr(dHz: Long) { accords += dHz }
                override fun qo100(dHz: Long) { accords += dHz }
            }
            val st2 = fr.f4ioz.satcombo.data.SettingsStore(StockageFichier(File(d, "r.properties")))
            st2.moletteVfo = true
            val m = MoletteUsbPc(StockageFichier(File(d, "s.properties")), st2, monde, fichierAppareils = File(d, "aucun"))
            val r = RelaisMolettePc(File(d, "relais.properties"))
            m.relais = r
            assertNull(r.connecte(st.adresse, st.mdp))
            m.recoit(115, 1)
            attend { st.recus.isNotEmpty() }
            assertEquals(listOf("115:1"), st.recus.toList())
            assertTrue("rien accordé ici", accords.isEmpty())
            // Here, as the station: a key relayed is applied even with no knob of its own on.
            st2.moletteVfo = false
            m.relais = null
            m.recoit(115, 1, distant = true)
            assertEquals(1, accords.size)
        }
        d.deleteRecursively()
    }

    @Test
    fun windows_ses_touches_et_son_crochet() {
        val s = EntreesWindows.script(true)
        assertTrue(s.contains("case 175: return 115;") && s.contains("case 174: return 114;") && s.contains("case 173: return 113;"))
        assertTrue(s.contains("[SatMeMolette]::Lance(\$true)"))
        assertTrue(EntreesWindows.script(false).contains("Lance(\$false)"))
        assertTrue(EntreesWindows.APPAREIL.molette)
        val c = EntreesWindows.commande(true)
        assertEquals("powershell.exe", c[0]); assertEquals("-EncodedCommand", c[c.size - 2])
        assertEquals(s, String(java.util.Base64.getDecoder().decode(c.last()), Charsets.UTF_16LE))
    }

    @Test
    fun les_cles_windows_lues_comme_sous_linux() {
        // The helper's lines, from any program that prints them (here sh), read as the knob's keys.
        val src = EntreesWindows.Source(true, listOf("sh", "-c", "echo ok 1; echo k 115 1; echo k 115 0; echo bruit; echo k 114 1"))
        assertTrue(src.capture)
        assertEquals(listOf(115 to 1, 115 to 0, 114 to 1), generateSequence { src.lit() }.map { it.code to it.valeur }.toList())
        src.close()
    }

    @Test
    fun le_dossier_portable() {
        val d = Files.createTempDirectory("portable").toFile()
        val lib = File(d, "satme/lib").apply { mkdirs() }
        assertNull(dossierPortable(File(lib, "pc.jar")))
        val donnees = File(d, "satme/donnees").apply { mkdirs() }
        assertEquals(donnees, dossierPortable(File(lib, "pc.jar")))
        // Windows' app image: the jars in app, SatMe.exe beside it.
        val app = File(d, "SatMe/app").apply { mkdirs() }; File(d, "SatMe/donnees").mkdirs()
        assertNotNull(dossierPortable(File(app, "pc.jar")))
        assertNull(dossierPortable(null))
        d.deleteRecursively()
    }
}
