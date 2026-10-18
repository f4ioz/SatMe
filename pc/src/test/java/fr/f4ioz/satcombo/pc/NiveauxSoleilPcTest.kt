/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.plateforme.StockageMemoire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NiveauxSoleilPcTest {

    private val dossier = File(System.getProperty("java.io.tmpdir"), "satme-niveaux-" + System.nanoTime()).apply { mkdirs() }
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun fin() { portee.cancel(); dossier.deleteRecursively() }

    @Test fun `plancher et puissance autour d'un repère, lus dans le spectre`() {
        val n = 4096
        val sp = FloatArray(n) { -90f }
        val parBin = 176_400.0 / n
        // A carrier 10 kHz above the PLL, three bins at -60 dB.
        val j = (10_000.0 / parBin).toInt() + n / 2
        for (k in j - 1..j + 1) sp[k] = -60f
        assertEquals(-90.0, NiveauxPc.plancher(sp)!!, 1e-9)
        val p = NiveauxPc.puissance(sp, 10_000.0, 1_000.0)!!
        assertEquals(-60.0 + 10 * Math.log10(3.0), p, 0.3)
        // Out of view: nothing.
        assertNull(NiveauxPc.puissance(sp, 95_000.0, 1_000.0))
    }

    private fun station(): Pair<StationPc, SdrPc> {
        val stockage = StockageMemoire()
        val st = StationPc(stockage, Catalogue(dossier, enLigne = false), PortsSeriePc(), portee)
        return st to SdrPc(st, SonPc(), stockage, portee)
    }

    @Test fun `les niveaux, vides tant que le récepteur dort, et leur CSV`() {
        val (st, sdr) = station()
        val niv = NiveauxPc(sdr, null, st.stockage)
        niv.tic()
        val j = JSONObject(String(niv.sert("/api/niveaux", "GET") { if (it == "serie") "canal" else "" }.corps))
        assertTrue(j.getBoolean("ok")); assertFalse(j.getBoolean("actif"))
        assertEquals(0, j.getJSONArray("points").length())
        assertEquals(1_000L, j.getLong("pas"))
        niv.sert("/api/niveaux/repere", "POST") { if (it == "hz") "-5000" else if (it == "largeur") "3000" else "" }
        assertEquals(-5_000, niv.repereHz); assertEquals(3_000, niv.repereLargeurHz)
        val csv = niv.sert("/api/niveaux.csv", "GET") { if (it == "fenetre") "24h" else "" }
        assertTrue(csv.type.startsWith("text/csv"))
        assertTrue(String(csv.corps).startsWith("temps_utc,canal_moy_db"))
    }

    @Test fun `le balayage du Soleil ne bouge rien sans confirmation ni rotor`() {
        val (st, sdr) = station()
        val sol = SoleilPc(st, sdr, st.stockage)
        // Not confirmed: refused before anything else.
        val a = JSONObject(String(sol.sert("/api/soleil/balayer", "POST") { "" }.corps))
        assertFalse(a.getBoolean("ok")); assertTrue(a.getString("erreur").contains("confirmer"))
        // Confirmed, but no rotor connected.
        val b = JSONObject(String(sol.sert("/api/soleil/balayer", "POST") { if (it == "confirme") "1" else "" }.corps))
        assertFalse(b.getBoolean("ok")); assertTrue(b.getString("erreur").contains("rotor"))
        assertEquals("", sol.enCours)
        assertFalse(st.stationRotor.etat.value.rotorConnected)
        // A measure needs the receiver.
        assertFalse(JSONObject(String(sol.sert("/api/soleil/mesure", "POST") { if (it == "quoi") "froid" else "" }.corps)).getBoolean("ok"))
        // The settings, and the expected rise once the dish and the index are given.
        val e = JSONObject(String(sol.sert("/api/soleil/reglages", "POST") { mapOf("diametre" to "120", "sfu" to "100")[it].orEmpty() }.corps))
        assertEquals(120, e.getInt("diametre"))
        val y = e.getJSONObject("attendu").getDouble("y")
        assertTrue("$y", y in 6.0..13.0)
        assertFalse(e.getJSONObject("rotor").getBoolean("pret"))
    }

    private val serveur = java.net.ServerSocket(0)
    @Volatile private var fini = false
    @After fun ferme() { fini = true; runCatching { serveur.close() } }

    /** An rtl_tcp of a few lines: noise. */
    private fun fauxRtlTcp() = kotlin.concurrent.thread(isDaemon = true) {
        runCatching {
            serveur.accept().use { c ->
                val o = c.getOutputStream()
                o.write("RTL0".toByteArray() + byteArrayOf(0, 0, 0, 5, 0, 0, 0, 29)); o.flush()
                kotlin.concurrent.thread(isDaemon = true) { runCatching { val i = c.getInputStream(); while (i.read() >= 0) Unit } }
                val r = kotlin.random.Random(3)
                val bloc = ByteArray(16_384) { (127 + r.nextInt(-20, 21)).toByte() }
                while (!fini) { o.write(bloc); Thread.sleep(7) }
            }
        }
    }

    private fun attends(ms: Long, quoi: () -> Boolean) {
        val fin = System.currentTimeMillis() + ms
        while (!quoi()) { check(System.currentTimeMillis() < fin) { "trop long" }; Thread.sleep(50) }
    }

    @Test fun `balayage confirmé avec le rotor simulé, dans ses butées`() {
        val (st, sdr) = station()
        st.settings.manualLocator = "JN18FS"
        st.settings.sdrAudio = false
        st.demarre()
        attends(8_000) { st.etat.value.observateur != null }
        // Paris, 21 June 2026 at 05:00 UTC: the Sun low in the north-east, near 65° az.
        val depart = System.currentTimeMillis(); val base = java.time.Instant.parse("2026-06-21T05:00:00Z").toEpochMilli()
        val horloge = { base + System.currentTimeMillis() - depart }
        val rotor = st.stationRotor
        rotor.setRotorSim(true); rotor.connectRotor()
        attends(5_000) { rotor.etat.value.rotorConnected }
        sdr.lance = false; sdr.port = serveur.localPort
        fauxRtlTcp()
        assertTrue(sdr.demarre())
        attends(8_000) { sdr.spectre != null }
        val sol = SoleilPc(st, sdr, st.stockage, horloge, SoleilPc.Durees(mesureMs = 300, celluleMs = 250, reposMs = 50, lectureMs = 1_100, immobileMs = 1_500, maxMs = 30_000))
        val (az, el) = fr.f4ioz.satcombo.domain.BruitSolaire.soleil(48.77, 2.46, horloge())
        assertTrue("$az", az in 60.0..66.0); assertTrue("$el", el in 4.0..12.0)
        // The rotor's lowest elevation just under the Sun: the row below is refused, never clamped.
        val minEl = Math.floor(el + 0.45).toInt()
        rotor.setRotorMinEl(minEl)
        val j = JSONObject(String(sol.sert("/api/soleil/balayer", "POST") { mapOf("confirme" to "1", "demi" to "0.5", "pas" to "0.5")[it].orEmpty() }.corps))
        assertTrue(j.toString(), j.getBoolean("ok"))
        val prevues = j.getInt("cellules")
        assertTrue("$prevues", prevues in 3..8)
        attends(90_000) { sol.enCours.isEmpty() }
        val e = sol.etat()
        assertEquals("balayage terminé", e.getString("message"))
        val bal = e.getJSONObject("balayage")
        assertTrue(bal.getJSONArray("cellules").length() >= 3)
        assertTrue(bal.has("pic"))
        // Every command within the rotor's range; tracking off.
        val ordres = rotor.simulateur!!.received.filter { it.startsWith("W") }
        assertTrue(ordres.isNotEmpty())
        assertTrue(ordres.toString(), ordres.all { it.trim().split(" ").last().toInt() >= minEl })
        assertFalse(rotor.etat.value.rotorEnabled)
        sdr.arrete()
        rotor.disconnectRotor()
    }
}
