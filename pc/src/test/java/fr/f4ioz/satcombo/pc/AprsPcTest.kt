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
import fr.f4ioz.satcombo.aprs.Afsk
import fr.f4ioz.satcombo.aprs.Ax25
import fr.f4ioz.satcombo.aprs.Trame
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** APRS heard through the PC's sound: decoded, kept as the phone keeps it, read back. */
class AprsPcTest {

    private val dossier = File(System.getProperty("java.io.tmpdir"), "satme-aprs-" + System.nanoTime())
    @After fun fin() { dossier.deleteRecursively() }

    private var t = 1_791_800_000_000L

    @Test
    fun hors_antenne_une_trame_est_comptee_jamais_gardee() {
        val trame = Trame(Adresse.de("APRS"), Adresse.de("F4IOZ-7"),
            listOf(Adresse("RS0ISS", repete = true), Adresse.de("WIDE2-1")), "=4852.55N/00219.00E-SatMe".toByteArray())
        val a = AprsPc(dossier, { "ISS" }, { t })
        a.horsAntenne = { true }
        val son = Afsk.module(listOf(Ax25.encode(trame)), 48_000)
        var i = 0
        while (i < son.size) { val n = minOf(2048, son.size - i); a.ecoute(son.copyOfRange(i, i + n), n, 48_000); i += n }
        assertEquals(1, a.essais)
        assertTrue(a.paquets.isEmpty())
        assertTrue(!dossier.exists() || dossier.listFiles().isNullOrEmpty())
    }

    @Test
    fun une_trame_par_l_iss_est_decodee_gardee_et_relue() {
        val trame = Trame(Adresse.de("APRS"), Adresse.de("F4IOZ-7"),
            listOf(Adresse("RS0ISS", repete = true), Adresse.de("WIDE2-1")), "=4852.55N/00219.00E-SatMe".toByteArray())
        val a = AprsPc(dossier, { "ISS" }, { t })
        val son = Afsk.module(listOf(Ax25.encode(trame)), 48_000)
        // In blocks of 2048, as the card hands them out.
        var i = 0
        while (i < son.size) { val n = minOf(2048, son.size - i); a.ecoute(son.copyOfRange(i, i + n), n, 48_000); i += n }
        assertEquals(1, a.paquets.size)
        val p = a.paquets.single()
        assertEquals("F4IOZ-7", p.source)
        assertTrue(p.viaIss)
        assertEquals(48.875, p.lat!!, 0.01)
        // Heard again 2 s later: kept once; read back by a new start, as the phone would.
        t += 2_000
        assertTrue(!a.ajoute(trame, t))
        val relu = AprsPc(dossier, { "ISS" }, { t })
        assertEquals(1, relu.paquets.size)
        assertEquals("F4IOZ-7", relu.paquets.single().source)
        assertTrue(File(dossier, "2026-10-12.txt").readText().contains("\tISS\t"))
    }

    @Test
    fun les_trophees_du_telephone_se_gagnent_et_se_gardent() {
        val d = File(dossier, "aprs")
        // Me in Paris (JN18); a station heard through the ISS from Madrid, 1 050 km away.
        val joueur = { Triple("F4IOZ", 48.8, 2.4) }
        val a = AprsPc(d, { "ISS" }, { t }, joueur)
        val loin = Trame(Adresse.de("APRS"), Adresse.de("EA4ABC"),
            listOf(Adresse("RS0ISS", repete = true)), "=4024.00N/00342.00W-hola".toByteArray())
        assertTrue(a.ajoute(loin, t))
        val e = a.trophees
        assertTrue(fr.f4ioz.satcombo.aprs.Trophees.Badge.PREMIER_PAQUET in e.badges)
        assertTrue(fr.f4ioz.satcombo.aprs.Trophees.Badge.PREMIER_ISS in e.badges)
        assertTrue(fr.f4ioz.satcombo.aprs.Trophees.Badge.KM_1000 in e.badges)
        assertEquals("EA4ABC", e.recordIndicatif)
        assertTrue(a.evenements.isNotEmpty())
        // Kept apart from the history, next to it: read back by a new start.
        assertTrue(File(dossier, "aprs-trophees.txt").isFile)
        val relu = AprsPc(d, { "ISS" }, { t }, joueur)
        assertEquals("EA4ABC", relu.trophees.recordIndicatif)
    }

    @Test
    fun la_fenetre_de_la_carte_survit_au_redemarrage_et_s_arrete_a_48_h() {
        fun trame(de: String, info: String, chemin: List<Adresse> = emptyList()) =
            Trame(Adresse.de("APRS"), Adresse.de(de), chemin, info.toByteArray())
        var maintenant = t
        val a = AprsPc(dossier, { "ISS" }, { maintenant })
        // Three days ago, yesterday, now; one through the ISS.
        assertTrue(a.ajoute(trame("QF1ABC", "=4547.00N/00305.00E-vieux"), t - 72 * 3_600_000L))
        assertTrue(a.ajoute(trame("QF2HOT", "=4548.00N/00306.00E-hier"), t - 20 * 3_600_000L))
        assertTrue(a.ajoute(trame("QE4ABC", "=4024.00N/00342.00W-hola", listOf(Adresse("RS0ISS", repete = true))), t - 60_000L))
        assertEquals(3, a.paquets.size)
        assertEquals(listOf("QE4ABC", "QF2HOT"), a.fenetre.map { it.source })
        assertEquals("ISS", a.fenetre.first().satellite)
        // Read back by a new start: the same window, the satellite column with it.
        val relu = AprsPc(dossier, { "" }, { maintenant })
        assertEquals(listOf("QE4ABC", "QF2HOT"), relu.fenetre.map { it.source })
        assertEquals("ISS", relu.fenetre.first().satellite)
        // A day later, yesterday's frame has left the window.
        maintenant = t + 30 * 3_600_000L
        assertTrue(relu.ajoute(trame("QF5ZZA", "=4549.00N/00307.00E-"), maintenant))
        assertEquals(listOf("QF5ZZA", "QE4ABC"), relu.fenetre.map { it.source })
    }

    @Test
    fun l_api_des_stations_de_satme_pc() {
        val st = StationPc(fr.f4ioz.satcombo.plateforme.StockageMemoire(), Catalogue(File(dossier, "cache"), enLigne = false), PortsSeriePc(),
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default))
        st.settings.callsign = "QF4DMO"
        val son = SonStation(SonPc(), st, File(dossier, "enregistrements"), File(dossier, "sstv"))
        son.aprs.ajoute(Trame(Adresse.de("APRS"), Adresse.de("QF8MOB-9"), listOf(Adresse.de("QF6DIG-2*")),
            "=4540.00N/00300.00E>045/040 En route".toByteArray()), System.currentTimeMillis() - 60_000L)
        val s = ServeurPc(st, 0, son = son)
        s.demarre()
        try {
            val base = "http://127.0.0.1:${s.portReel}"
            val o = org.json.JSONObject(java.net.URL("$base/api/aprs/stations?heures=6").readText())
            assertTrue(o.getBoolean("present"))
            assertEquals(6, o.getInt("heures"))
            val q = o.getJSONArray("stations").getJSONObject(0)
            assertEquals("QF8MOB-9", q.getString("id"))
            assertEquals("digi", q.getString("voie"))
            assertEquals("mobile", q.getString("genre"))
            assertEquals("voiture", q.getString("symNom"))
            assertEquals(45, q.getInt("cap"))
            assertEquals("QF6DIG-2", q.getJSONArray("digis").getString(0))
        } finally { s.arrete() }
    }

    @Test
    fun la_demonstration_a_son_aprs() {
        val maintenant = 1_793_000_000_000L
        val iss = fr.f4ioz.satcombo.data.SatPass("ISS", 25544, maintenant - 5 * 3_600_000L, maintenant - 5 * 3_600_000L + 600_000L, 60.0, 200.0, 40.0, false, false)
        val n = AprsDemo(maintenant, 45.77, 3.04).genere(dossier, listOf(iss))
        assertTrue(n > 300)
        val a = AprsPc(File(dossier, "aprs"), { "" }, { maintenant })
        val b = fr.f4ioz.satcombo.aprs.StationsAprs.bilan(a.fenetre, "QF4DMO", 45.77, 3.04, maintenant)
        val f = b.fiches.associateBy { it.id }
        // Every callsign in the Q series (never on the air), every kind of station there.
        assertTrue(b.fiches.all { it.indicatif.startsWith("Q") || it.indicatif == "RS0ISS" })
        val genres = b.fiches.map { it.genre }.toSet()
        listOf("MOBILE", "FIXE", "METEO", "DIGI", "IGATE", "OBJET", "SATELLITE").forEach { g -> assertTrue(g, genres.any { it.name == g }) }
        assertTrue(f.getValue("QF8MOB-9").trace.size > 20)
        assertTrue(f.getValue("QF2CMP-7").cap != null)
        assertEquals(fr.f4ioz.satcombo.aprs.Voie.INTERNET, f.getValue("QF3NET-5").voie)
        assertEquals(1, b.passages.size)
        assertTrue(b.passages.single().stations.size >= 3)
        assertTrue(File(dossier, "aprs").listFiles()!!.isNotEmpty())
    }
}
