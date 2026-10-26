/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.cat.PuissanceRf
import fr.f4ioz.satcombo.demo.SonDistant
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

/**
 * The IC-9700's TX from SatMe-PC on the simulated rig: nothing keyed by
 * itself, the PTT held and released every way, the microphone to a fake
 * sink only for the holder, the power under its cap, the CAT closing
 * after the unkey, the knob tuning while keyed. No port, no card opened.
 */
class EmissionPostePcTest {

    private val dossier = File(System.getProperty("java.io.tmpdir"), "satme-txposte-" + System.nanoTime()).apply { mkdirs() }
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun fin() { portee.cancel(); dossier.deleteRecursively() }

    private fun attends(ms: Long = 5_000, quoi: () -> Boolean) {
        val fin = System.currentTimeMillis() + ms
        while (!quoi()) { check(System.currentTimeMillis() < fin) { "trop long" }; Thread.sleep(20) }
    }

    /** A fake way to the rig: the samples counted. */
    private class Fausse : EmissionPostePc.Sortie {
        val recus = AtomicLong(); @Volatile var fermee = false
        override val nom = "fausse"
        override fun pousse(pcm: ShortArray, n: Int, taux: Int): String? { recus.addAndGet(n.toLong()); return null }
        override fun ferme() { fermee = true }
    }

    private var t = 10_000_000L
    private val fausse = Fausse()

    private fun station(): StationPc {
        val st = StationPc(StockageMemoire(), Catalogue(dossier, enLigne = false), PortsSeriePc(), portee)
        st.settings.manualLocator = "JN18FS"
        st.connecteCat("SIM", 0, 0)
        attends { st.etat.value.catConnecte && st.posteSimule != null }
        return st
    }

    private fun tx(st: StationPc, horloge: () -> Long = { t }) = EmissionPostePc(st, { null }, horloge, { _, _ -> fausse })

    private fun trame(n: Int = 960) = SonDistant.litAmont(SonDistant.trameAmont(48_000, 1, 0.0, ShortArray(n) { (it % 100 * 50).toShort() }))!!

    @Test fun rien_n_emet_au_demarrage() {
        val st = station()
        val p = tx(st)
        repeat(5) { p.tic(); Thread.sleep(50) }
        assertTrue(st.posteSimule!!.commandesPtt.isEmpty())
        assertFalse(st.posteSimule!!.transmitting)
        val j = p.json("x")
        assertFalse(j.getBoolean("emet"))
        assertTrue(j.getBoolean("pret"))
    }

    @Test fun appui_micro_du_seul_tenant_puis_relache() {
        val st = station()
        val p = tx(st)
        assertNull(p.appuie("a", false, ""))
        val sim = st.posteSimule!!
        assertTrue(sim.transmitting)
        p.micro("a", trame())
        p.micro("b", trame())
        assertEquals(960L, fausse.recus.get())
        assertEquals(1L, p.json("a").getLong("ignorees"))
        assertTrue(p.json("a").getBoolean("moi"))
        assertTrue(p.appuie("b", true, "192.168.1.9")!!.contains("tenue"))
        p.relache("relâché")
        assertFalse(sim.transmitting)
        p.tic()
        assertTrue(fausse.fermee)
        // Released: the microphone goes nowhere.
        p.micro("a", trame())
        assertEquals(960L, fausse.recus.get())
    }

    @Test fun qo100_jamais_sur_la_balise_et_LEILA_coupe() {
        val st = station()
        val p = tx(st)
        st.qo100.auPoste(true)
        // The cockpit on the middle beacon: listening only, the PTT refused.
        st.qo100.balise()
        assertTrue(p.appuie("a", false, "")!!.contains("balise"))
        assertFalse(st.posteSimule!!.transmitting)
        // In the upper SSB segment: keyed; LEILA heard, released at once.
        st.qo100.descente(10_489_800_000L)
        var leila = false
        p.leila = { leila }
        assertNull(p.appuie("a", false, ""))
        assertTrue(st.posteSimule!!.transmitting)
        leila = true
        p.tic()
        assertFalse(st.posteSimule!!.transmitting)
        assertTrue(p.json("a").getString("raison").contains("LEILA"))
        // Another transmitter (the Pluto's narrow band) excludes the rig.
        leila = false
        p.autreEmission = { "une émission BLU par le Pluto est en cours" }
        assertTrue(p.appuie("a", false, "")!!.contains("Pluto"))
    }

    @Test fun chien_et_minuterie() {
        val st = station()
        val p = tx(st)
        assertNull(p.appuie("a", true, "192.168.1.9"))
        t += 1_500; assertTrue(p.vie("a")); p.tic()
        assertTrue(st.posteSimule!!.transmitting)
        t += 2_100; p.tic()
        assertFalse(st.posteSimule!!.transmitting)
        assertTrue(p.json(null).getString("raison").contains("nouvelles"))
        p.totMin = 1
        assertNull(p.appuie("a", false, ""))
        repeat(60) { t += 1_000; p.vie("a"); p.tic() }
        assertFalse(st.posteSimule!!.transmitting)
        assertTrue(p.json(null).getString("raison").contains("minuterie"))
    }

    @Test fun reseau_autorise_seulement_depuis_ce_pc() {
        val st = station()
        val p = tx(st)
        assertFalse(p.reglage(mapOf("reseau" to "1"), local = false).getBoolean("ok"))
        assertFalse(st.txReseauAutorise)
        // The other guards too; the power, from anyone.
        assertFalse(p.reglage(mapOf("plafond" to "100"), local = false).getBoolean("ok"))
        assertFalse(p.reglage(mapOf("tot" to "10"), local = false).getBoolean("ok"))
        assertTrue(p.reglage(mapOf("pct" to "10"), local = false).getBoolean("ok"))
        assertTrue(p.reglage(mapOf("reseau" to "1"), local = true).getBoolean("ok"))
        assertTrue(st.txReseauAutorise)
        // Never from a phone's backup.
        st.txReseauAutorise = false
        val sauvegarde = JSONObject().put("app", "SatMe").put("stores", JSONObject().put("satcombo_settings",
            JSONObject().put("pc_tx_poste_reseau", JSONObject().put("t", "b").put("v", true))))
        ImportTelephone.importe(sauvegarde.toString(), st.stockage)
        assertFalse(st.txReseauAutorise)
    }

    @Test fun puissance_sous_le_plafond_avant_le_ptt() {
        val st = station()
        val p = tx(st)
        val sim = st.posteSimule!!
        assertEquals(255, sim.puissanceMain)
        p.reglage(mapOf("plafond" to "25"), local = true)
        assertNull(p.appuie("a", false, ""))
        assertTrue(sim.puissanceMain <= PuissanceRf.niveau(25))
        assertTrue(sim.transmitting)
        // A preset above the cap: held at the cap.
        assertEquals(PuissanceRf.niveau(25), p.reglePuissance(100))
        p.reglePuissance(10)
        attends { sim.puissanceMain == PuissanceRf.niveau(10) }
        p.relache("fin")
    }

    @Test fun deconnecter_le_poste_relache_d_abord() {
        val st = station()
        val p = tx(st)
        assertNull(p.appuie("a", false, ""))
        val sim = st.posteSimule!!
        st.deconnecteCat()
        attends { !st.etat.value.catConnecte }
        assertFalse(sim.transmitting)
        assertEquals(listOf(true, false), sim.commandesPtt.toList())
        assertFalse(p.poste.etat.emet)
    }

    @Test fun la_molette_accorde_le_poste_pendant_l_emission() {
        val st = station()
        val p = tx(st)
        val sim = st.posteSimule!!
        assertNull(p.appuie("a", false, ""))
        val avant = sim.mainHz
        // No satellite driving the rig: the knob is on its dial (terrestrial SSB).
        assertFalse(st.pilotageDoppler())
        assertTrue(st.accordeDirect(500))
        assertTrue(st.accordeDirect(-100))
        attends { sim.mainHz == avant + 400 }
        assertTrue(sim.transmitting)
        p.relache("fin")
    }

    // ------------------------------------------------------------ through the server

    private fun post(base: String, chemin: String): JSONObject {
        val c = URL(base + chemin).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = 3000; c.readTimeout = 5000
        return JSONObject(c.inputStream.bufferedReader().readText())
    }

    @Test fun par_le_serveur_le_chien_coupe_le_ptt_du_client_muet() {
        val st = station()
        val p = EmissionPostePc(st, { null }, fabriqueSortie = { _, _ -> fausse })
        p.demarre()
        val s = ServeurPc(st, 0, diffusion = DiffusionSon())
        s.emissionPoste = p
        s.demarre()
        try {
            val base = "http://127.0.0.1:${s.portReel}"
            val r = post(base, "/api/poste/emission/demarre?client=page1")
            assertTrue(r.toString(), r.getBoolean("emet"))
            assertTrue(st.posteSimule!!.transmitting)
            // Another client: refused, but its STOP works.
            assertFalse(post(base, "/api/poste/emission/demarre?client=page2").optBoolean("ok", true))
            // page1 goes silent: released within the watchdog.
            attends(4_000) { !st.posteSimule!!.transmitting }
            assertTrue(p.json(null).getString("raison").contains("nouvelles"))
            assertTrue(post(base, "/api/poste/emission/demarre?client=page2").getBoolean("emet"))
            post(base, "/api/poste/emission/arrete?client=page1")
            assertFalse(st.posteSimule!!.transmitting)
        } finally { s.arrete() }
    }

    @Test fun par_le_serveur_le_micro_ferme_relache() {
        val st = station()
        val p = EmissionPostePc(st, { null }, fabriqueSortie = { _, _ -> fausse })
        val s = ServeurPc(st, 0, diffusion = DiffusionSon())
        s.emissionPoste = p
        s.demarre()
        try {
            val base = "http://127.0.0.1:${s.portReel}"
            assertTrue(post(base, "/api/poste/emission/demarre?client=m1").getBoolean("emet"))
            val so = Socket("127.0.0.1", s.portReel)
            so.getOutputStream().write(("GET /api/poste/micro/ws?client=m1 HTTP/1.1\r\nHost: 127.0.0.1:${s.portReel}\r\nUpgrade: websocket\r\n" +
                "Connection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n").toByteArray())
            val e = so.getInputStream()
            val l = StringBuilder(); while (!l.endsWith("\r\n\r\n")) l.append(e.read().toChar())
            assertTrue(l.startsWith("HTTP/1.1 101"))
            assertTrue(String(WebSocketPc.litTrameServeur(e, 4096)!!.donnees).contains("bonjour"))
            repeat(5) { so.getOutputStream().write(WebSocketPc.trameClient(WebSocketPc.BINAIRE, SonDistant.trameAmont(48_000, it.toLong(), 0.0, ShortArray(960)))) }
            attends { fausse.recus.get() == 4800L }
            so.close()
            attends { !st.posteSimule!!.transmitting }
            assertTrue(p.json(null).getString("raison").contains("micro"))
        } finally { s.arrete() }
    }
}
