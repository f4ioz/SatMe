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
import java.net.Socket

/**
 * The routes another program on this PC uses to share SatMe-PC's rig and
 * mast: the receive tuning of the IC-9700, the mast pointed with « Suivre »
 * kept, its STOP, the pass flag in `/api/etat`, the sound card's stream. On
 * the simulated IC-9700 and GS-232 only, a fake clock for the passes.
 */
class PartagePcTest {
    private val dossier = File(System.getProperty("java.io.tmpdir"), "satme-partage-" + System.nanoTime()).apply {
        File(this, "emetteurs").mkdirs()
        File(this, "amsat-tle.txt").writeText(TLE)
        File(this, "emetteurs/25544.json").writeText("""[
          {"description":"FM voice repeater (V/U)","mode":"FM","uplink_low":145990000,"downlink_low":437800000,"alive":true,"invert":false,"type":"Transceiver"}]""")
    }
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var serveur: ServeurPc? = null
    @After fun fin() { serveur?.arrete(); portee.cancel(); dossier.deleteRecursively() }

    /** The fake clock: from the elements' epoch (an ISS pass comes within hours). */
    @Volatile private var t = Catalogue.lisTle(TLE).first().epochMs!!

    private fun attends(ms: Long = 10_000, quoi: () -> Boolean) {
        val fin = System.currentTimeMillis() + ms
        while (!quoi()) { check(System.currentTimeMillis() < fin) { "trop long" }; Thread.sleep(20) }
    }

    private fun station() = StationPc(StockageMemoire(), Catalogue(dossier, enLigne = false), PortsSeriePc(), portee, horloge = { t })
        .also { it.settings.manualLocator = "JN18FS"; it.demarre() }

    private fun monte(st: StationPc, diffusion: DiffusionSon? = null): ServeurPc =
        ServeurPc(st, 0, diffusion = diffusion).also { it.demarre(); serveur = it }

    private class Rep(val code: Int, val corps: String) { val json: JSONObject get() = JSONObject(corps) }

    /** One request by hand (HttpURLConnection will not send Origin), the answer read to its end. */
    private fun requete(s: ServeurPc, m: String, chemin: String, entetes: Map<String, String> = emptyMap()): Rep {
        Socket("127.0.0.1", s.portReel).use { c ->
            c.soTimeout = 10_000
            val h = StringBuilder("$m $chemin HTTP/1.1\r\nHost: 127.0.0.1:${s.portReel}\r\nConnection: close\r\n")
            if (m == "POST") h.append("Content-Length: 0\r\n")
            entetes.forEach { (k, v) -> h.append("$k: $v\r\n") }
            c.getOutputStream().write(h.append("\r\n").toString().toByteArray()); c.getOutputStream().flush()
            val tout = c.getInputStream().readBytes().toString(Charsets.UTF_8)
            val code = tout.substringAfter(' ').substringBefore(' ').toInt()
            return Rep(code, tout.substringAfter("\r\n\r\n"))
        }
    }

    private val clic = mapOf("X-SatMe-Geste" to "clic")

    companion object {
        const val TLE = """ISS
1 25544U 98067A   26284.17638745  .00009430  00000-0  18014-3 0  9996
2 25544  51.6312  86.0933 0006713 248.1316 111.8959 15.48821882589734
"""
    }

    // ------------------------------------------------------------------ the pass window

    private fun p(aos: Long, los: Long) = OccupationPc.Passage("ISS", 25544, aos, los)

    @Test fun la_fenetre_du_passage_sur_une_horloge() {
        val aos = 10_000_000L; val los = aos + 600_000L
        fun oc(h: Long, avant: Int = 3) = OccupationPc.calcule(OccupationPc.Entree(h, p(aos, los), avant, nomSuivi = "ISS", rotorSuit = true))
        // Before the pre-AOS (3 min + 1): free, the window announced.
        val libre = oc(aos - 4 * 60_000L - 1)
        assertFalse(libre.passageEnCours); assertFalse(libre.occupe)
        assertEquals(aos - 4 * 60_000L, libre.debutMs); assertEquals(los + 60_000L, libre.finMs)
        // Pre-AOS: taken, said so.
        val avant = oc(aos - 2 * 60_000L)
        assertTrue(avant.passageEnCours); assertTrue(avant.occupe)
        assertTrue(avant.rotor!!, avant.rotor!!.contains("se prépare au passage de ISS"))
        assertTrue(avant.poste!!.contains("se prépare"))
        // Under way, up to a minute after LOS.
        assertTrue(oc(aos + 60_000L).rotor!!.contains("en cours"))
        assertTrue(oc(los + 59_000L).passageEnCours)
        val apres = oc(los + 61_000L)
        assertFalse(apres.passageEnCours); assertFalse(apres.occupe)
        // A longer « avant AOS » widens it.
        assertTrue(oc(aos - 9 * 60_000L, avant = 10).passageEnCours)
        // The JSON: the flag, the bounds, the satellite, both parts.
        val j = OccupationPc.json(avant)
        assertTrue(j.getBoolean("passageEnCours")); assertEquals(aos - 4 * 60_000L, j.getLong("debut"))
        assertEquals("ISS", j.getJSONObject("satellite").getString("nom"))
        assertTrue(j.getJSONObject("rotor").getBoolean("occupe")); assertTrue(j.getJSONObject("poste").getBoolean("occupe"))
    }

    @Test fun le_mode_automatique_l_autopilote_l_agenda_le_doppler() {
        val aos = 10_000_000L; val los = aos + 600_000L
        // The automatic mode takes its pass 5 min before AOS, whoever is followed now.
        val v = OccupationPc.calcule(OccupationPc.Entree(aos - 4 * 60_000L - 30_000L, null, 0, veille = p(aos, los)))
        assertTrue(v.passageEnCours)
        assertFalse(OccupationPc.calcule(OccupationPc.Entree(aos - 6 * 60_000L, null, 0, veille = p(aos, los))).passageEnCours)
        // The SSTV autopilot, an agenda entry: both parts taken, outside any pass.
        val a = OccupationPc.calcule(OccupationPc.Entree(0L, null, 3, autopilote = "ISS"))
        assertFalse(a.passageEnCours); assertTrue(a.rotor!!.contains("autopilote")); assertTrue(a.poste!!.contains("autopilote"))
        val g = OccupationPc.calcule(OccupationPc.Entree(0L, null, 3, agenda = "Sked F4XYZ"))
        assertTrue(g.rotor!!.contains("agenda")); assertTrue(g.poste!!.contains("agenda"))
        // The Doppler loop writing the rig (« sous l'horizon »): the rig only.
        val d = OccupationPc.calcule(OccupationPc.Entree(0L, null, 3, pilotageDoppler = true, nomSuivi = "AO-91"))
        assertNull(d.rotor); assertTrue(d.poste!!.contains("Doppler"))
        // The mast waiting at the rise point: the mast only.
        val w = OccupationPc.calcule(OccupationPc.Entree(0L, null, 3, rotorAttente = true))
        assertTrue(w.rotor!!.contains("attend l'AOS")); assertNull(w.poste)
        assertFalse(OccupationPc.calcule(OccupationPc.Entree(0L, null, 3)).occupe)
    }

    // ------------------------------------------------------------------ the tuning request read

    @Test fun la_demande_d_accord_est_reception_seule() {
        val d = AccordPostePc.lit(mapOf("hz" to "145825000", "mode" to "usb-d", "bande" to "sub")).getOrThrow()
        assertEquals(145_825_000L, d.hz); assertTrue(d.sub); assertEquals(0x01, d.mode!!.civ); assertTrue(d.mode!!.donnees)
        assertEquals(AccordPostePc.MODES["FM-D"], AccordPostePc.lit(mapOf("mode" to "PKTFM")).getOrThrow().mode)
        assertFalse(AccordPostePc.lit(mapOf("hz" to "145825000")).getOrThrow().sub)
        for (k in listOf("ptt", "tx", "tone", "puissance", "split", "satellite", "montee"))
            assertTrue(k, AccordPostePc.lit(mapOf("hz" to "145825000", k to "1")).exceptionOrNull()!!.message!!.contains("réception seule"))
        assertTrue(AccordPostePc.lit(mapOf("hz" to "28074000")).isFailure)
        assertTrue(AccordPostePc.lit(mapOf("mode" to "DV")).isFailure)
        assertTrue(AccordPostePc.lit(mapOf("bande" to "sub")).isFailure)
    }

    // ------------------------------------------------------------------ the routes

    @Test fun accorde_l_ic9700_hors_passage_sans_jamais_emettre() {
        val st = station()
        st.connecteCat("SIM", 0, 0)
        attends { st.etat.value.catConnecte && st.posteSimule != null }
        val sim = st.posteSimule!!
        val s = monte(st)
        val r = requete(s, "POST", "/api/cat/accord?hz=437800000&mode=USB-D").json
        assertTrue(r.toString(), r.getBoolean("ok"))
        assertEquals("MAIN", r.getString("bande")); assertEquals(437_800_000L, r.getLong("hz")); assertEquals("USB-D", r.getString("mode"))
        assertEquals(437_800_000L, sim.mainHz); assertEquals(0x01, sim.mainMode); assertTrue(sim.mainData)
        // SUB, FM (no data): tuned, the selection back on MAIN.
        val r2 = requete(s, "POST", "/api/cat/accord?hz=145825000&mode=FM&bande=sub").json
        assertTrue(r2.toString(), r2.getBoolean("ok"))
        assertEquals(145_825_000L, sim.subHz); assertEquals(0x05, sim.subMode); assertFalse(sim.subData)
        attends { !sim.onSub }
        // A transmit parameter: refused by name, nothing written.
        val avant = sim.received.size
        val r3 = requete(s, "POST", "/api/cat/accord?hz=437900000&ptt=1").json
        assertFalse(r3.getBoolean("ok")); assertTrue(r3.getString("erreur").contains("réception seule"))
        assertEquals(437_800_000L, sim.mainHz)
        // Keyed (by hand): nothing tuned.
        sim.transmitting = true
        val r4 = requete(s, "POST", "/api/cat/accord?hz=437900000").json
        assertFalse(r4.getBoolean("ok")); assertTrue(r4.getString("erreur"), r4.getString("erreur").contains("émet"))
        assertEquals(437_800_000L, sim.mainHz)
        sim.transmitting = false
        // Satellite mode on (left by the last pass): MAIN tunes, SUB (the uplink there) never.
        sim.satMode = true
        val r5 = requete(s, "POST", "/api/cat/accord?hz=145900000&bande=sub").json
        assertFalse(r5.getBoolean("ok")); assertTrue(r5.getString("erreur").contains("mode satellite"))
        assertEquals(145_825_000L, sim.subHz)
        assertTrue(requete(s, "POST", "/api/cat/accord?hz=437850000").json.getBoolean("ok"))
        assertEquals(437_850_000L, sim.mainHz)
        assertTrue("satellite mode never switched by the route", sim.satMode)
        // Never keyed, never a PTT command, no power nor tone written (the CAT stopped first: its frames read still).
        assertEquals(404, requete(s, "GET", "/api/cat/accord?hz=437900000").code)
        st.deconnecteCat(); attends { !st.etat.value.catConnecte }
        assertTrue(sim.commandesPtt.isEmpty())
        assertTrue(sim.received.none { it.size > 4 && ((it[4].toInt() and 0xFF) == 0x1C && it.size > 7 || (it[4].toInt() and 0xFF) == 0x14 && it.size > 7 || (it[4].toInt() and 0xFF) == 0x1B) })
        assertTrue(avant > 0)
        // The GET above did nothing.
        assertEquals(437_850_000L, sim.mainHz)
    }

    @Test fun refuse_d_une_autre_origine_et_d_une_autre_machine_sans_session() {
        val st = station()
        st.connecteCat("SIM", 0, 0)
        attends { st.etat.value.catConnecte && st.posteSimule != null }
        val s = monte(st)
        val ailleurs = mapOf("Origin" to "http://exemple.invalid")
        for (ch in listOf("/api/cat/accord?hz=436000000", "/api/rotor/pointer?az=90", "/api/rotor/pointer/stop")) {
            val r = requete(s, "POST", ch, ailleurs + clic)
            assertEquals(ch, 403, r.code); assertEquals("origine", r.json.getString("refus"))
        }
        assertEquals(435_000_000L, st.posteSimule!!.mainHz)
        // This page's own origin: taken.
        assertTrue(requete(s, "POST", "/api/cat/accord?hz=436000000", mapOf("Origin" to "http://127.0.0.1:${s.portReel}")).json.getBoolean("ok"))
        // Another machine without a session: the usual guard, nothing done.
        s.estLocal = { false }
        for (ch in listOf("/api/cat/accord?hz=436500000", "/api/rotor/pointer?az=90", "/api/rotor/pointer/stop"))
            assertEquals(ch, 401, requete(s, "POST", ch, clic).code)
        assertEquals(436_000_000L, st.posteSimule!!.mainHz)
    }

    @Test fun le_rotor_pointe_sur_un_geste_et_garde_suivre() {
        val st = station()
        val r = st.stationRotor
        r.setRotorSim(true); r.setRotorEnabled(true); r.setRotorParkAz(180); r.connectRotor()
        attends { r.etat.value.rotorConnected }
        val s = monte(st)
        // Without the gesture: refused, nothing moved.
        val sans = requete(s, "POST", "/api/rotor/pointer?az=90&el=10")
        assertEquals(403, sans.code); assertEquals("geste", sans.json.getString("refus"))
        assertFalse(r.etat.value.rotorPauseManuelle)
        // Out of the travel: refused, never clamped.
        assertEquals("parametre", requete(s, "POST", "/api/rotor/pointer?az=999", clic).json.getString("refus"))
        // With it: pointed, « Suivre » kept.
        val o = requete(s, "POST", "/api/rotor/pointer?az=90&el=10", clic).json
        assertTrue(o.toString(), o.getBoolean("ok")); assertTrue(o.getBoolean("suit"))
        attends { r.etat.value.rotorTargetAz == 90.0 }
        assertTrue(r.etat.value.rotorEnabled); assertTrue(r.etat.value.rotorPauseManuelle)
        attends(40_000) { Math.abs((r.simulateur?.azDeg ?: 0.0) - 90.0) < 1.0 }
        // The state says it.
        val e = requete(s, "GET", "/api/etat").json
        assertTrue(e.getJSONObject("rotor").getBoolean("suit")); assertTrue(e.getJSONObject("rotor").getBoolean("pauseManuelle"))
        // STOP, no gesture needed: « Suivre » still kept outside a pass.
        val stop = requete(s, "POST", "/api/rotor/pointer/stop").json
        assertTrue(stop.getBoolean("ok")); assertFalse(stop.getBoolean("suiviCoupe"))
        assertTrue(r.etat.value.rotorEnabled)
        // A GET never moves anything.
        assertEquals(404, requete(s, "GET", "/api/rotor/pointer?az=200", clic).code)
        Thread.sleep(300)
        assertEquals(90.0, r.etat.value.rotorTargetAz!!, 0.01)
    }

    @Test fun pendant_le_pre_aos_et_le_passage_tout_est_refuse_sauf_stop() {
        val st = station()
        attends(20_000) { st.suis(25544) }
        attends(30_000) { st.etat.value.passage != null }
        val pass = st.etat.value.passage!!
        st.connecteCat("SIM", 0, 0)
        attends { st.etat.value.catConnecte && st.posteSimule != null }
        val r = st.stationRotor
        r.setRotorPreAos(3); r.setRotorSim(true); r.setRotorEnabled(true); r.connectRotor()
        attends { r.etat.value.rotorConnected }
        val s = monte(st)
        // Two minutes before AOS (within « avant AOS » 3 min + 1).
        t = pass.aosEpochMs - 2 * 60_000L
        attends { st.etat.value.maintenant == t }
        val e = requete(s, "GET", "/api/etat").json
        assertTrue(e.toString(), e.getBoolean("passageEnCours"))
        val oc = e.getJSONObject("occupation")
        assertTrue(oc.getBoolean("occupe")); assertEquals(pass.aosEpochMs - 4 * 60_000L, oc.getLong("debut"))
        assertEquals(pass.losEpochMs + 60_000L, oc.getLong("fin")); assertEquals(25544, oc.getJSONObject("satellite").getInt("catnum"))
        val mainAvant = st.posteSimule!!.mainHz
        val a = requete(s, "POST", "/api/cat/accord?hz=437900000").json
        assertFalse(a.getBoolean("ok")); assertEquals("occupe", a.getString("refus")); assertTrue(a.getString("erreur").contains("se prépare"))
        val p = requete(s, "POST", "/api/rotor/pointer?az=90", clic).json
        assertFalse(p.getBoolean("ok")); assertEquals("occupe", p.getString("refus"))
        assertFalse(r.etat.value.rotorPauseManuelle)
        // STOP: always — during a pass it is a real stop (« Suivre » off), said so.
        val stop = requete(s, "POST", "/api/rotor/pointer/stop").json
        assertTrue(stop.getBoolean("ok")); assertTrue(stop.getBoolean("suiviCoupe"))
        attends { !r.etat.value.rotorEnabled }
        // Under way too.
        r.setRotorEnabled(true)
        t = pass.aosEpochMs + 60_000L
        attends { st.etat.value.maintenant == t }
        assertTrue(requete(s, "POST", "/api/cat/accord?hz=437900000").json.getString("erreur").let { it.contains("en cours") || it.contains("Doppler") })
        assertFalse(requete(s, "POST", "/api/rotor/pointer?az=90", clic).json.getBoolean("ok"))
        // After LOS and its margin: free again.
        t = pass.losEpochMs + 2 * 60_000L
        attends { st.etat.value.maintenant == t && st.etat.value.passage?.aosEpochMs != pass.aosEpochMs }
        val apres = requete(s, "GET", "/api/etat").json
        assertFalse(apres.toString(), apres.getBoolean("passageEnCours"))
        assertTrue(st.posteSimule!!.commandesPtt.isEmpty())
        assertTrue(mainAvant > 0)
    }

    @Test fun le_son_de_la_carte_en_flux_reception_seule() {
        val st = station()
        val d = DiffusionSon()
        val s = monte(st, d)
        Socket("127.0.0.1", s.portReel).use { c ->
            c.soTimeout = 5_000
            c.getOutputStream().write("GET /api/son/flux?source=carte HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray()); c.getOutputStream().flush()
            val e = c.getInputStream()
            val tete = StringBuilder()
            while (!tete.endsWith("\r\n\r\n")) tete.append(e.read().toChar())
            assertTrue(tete.toString(), tete.startsWith("HTTP/1.1 200"))
            assertTrue(tete.contains("application/octet-stream"))
            attends { d.ecoute("carte") }
            // The card at 48 kHz: 24 kHz out, a 4-byte rate first, then 16-bit little-endian mono (two samples averaged).
            val pcm = ShortArray(480) { if (it % 2 == 0) 1000 else 3000 }
            d.ajoute("carte", pcm, pcm.size, 48_000)
            val b = ByteArray(4 + 240 * 2)
            var n = 0
            while (n < b.size) { val k = e.read(b, n, b.size - n); check(k > 0); n += k }
            assertEquals(24_000, (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) or ((b[2].toInt() and 0xFF) shl 16) or ((b[3].toInt() and 0xFF) shl 24))
            for (i in 0 until 240) assertEquals(2000, ((b[4 + 2 * i].toInt() and 0xFF) or (b[5 + 2 * i].toInt() shl 8)).toShort().toInt())
        }
        // Nothing of it goes to a transmitter: no rig keyed, nothing transmitting.
        assertFalse(st.etat.value.catConnecte)
    }
}
