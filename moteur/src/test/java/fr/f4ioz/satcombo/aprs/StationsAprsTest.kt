/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.aprs

import fr.f4ioz.satcombo.data.Observer
import fr.f4ioz.satcombo.demo.ApiRadio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The map's stations, from fictitious frames (Q-series callsigns, never on
 * the air): how each was heard, its trail, its kind, the passes, the API.
 */
class StationsAprsTest {

    private val t0 = 1_793_000_000_000L
    private val min = 60_000L

    private fun p(de: String, info: String, quand: Long, chemin: List<String> = emptyList(), dest: String = "APRS", sat: String = ""): Paquet =
        Aprs.lit(Trame(Adresse.de(dest), Adresse.de(de), chemin.map { Adresse.de(it) }, info.toByteArray(Charsets.ISO_8859_1)), quand).copy(satellite = sat)

    // ------------------------------------------------------------ parsing what the map needs

    @Test
    fun cap_et_vitesse_en_clair() {
        val q = p("QF1ABC-9", "!4903.50N/07201.75W>088/036 En route", t0)
        assertEquals(88, q.cap)
        assertEquals((36 * 1.852).toInt(), q.vitesseKmh)
        assertEquals("En route", q.commentaire)
        // A weather station's "ddd/sss" is its wind, not a course.
        val m = p("QF4WX", "=4903.50N/07201.75W_220/004g005t077", t0)
        assertNull(m.cap)
        assertEquals(220, m.meteo!!.ventDeg)
    }

    @Test
    fun cap_vitesse_et_altitude_compresses() {
        // APRS 1.01 chapter 9's examples: "7P" = 88°, 36.2 knots; "S]" with a GGA type byte = 10 004 ft.
        val q = p("QF1ABC", "=/5L!!<*e7>7P[", t0)
        assertEquals(88, q.cap)
        assertEquals((36.2 * 1.852).toInt().toDouble(), q.vitesseKmh!!.toDouble(), 1.0)
        val a = p("QF1ABC", "=/5L!!<*e7OS]S", t0)
        assertEquals((10004 * 0.3048), a.altitudeM!!.toDouble(), 5.0)
        assertNull(a.cap)
    }

    @Test
    fun le_nom_des_symboles() {
        assertEquals("voiture", SymbolesAprs.nom("/>"))
        assertEquals("passerelle (iGate), surcharge I", SymbolesAprs.nom("I&"))
        assertEquals("digipeater", SymbolesAprs.nom("\\#"))
        assertEquals("station météo", SymbolesAprs.nom("/_"))
        assertEquals("véhicule, surcharge 3", SymbolesAprs.nom("d>"))
        assertEquals("", SymbolesAprs.nom(null))
        assertEquals("", SymbolesAprs.nom("/J"))
    }

    // ------------------------------------------------------------ how a frame came

    @Test
    fun la_voie_de_chaque_trame() {
        val pos = "=4546.00N/00303.00E-"
        assertEquals(Voie.DIRECT, StationsAprs.voie(p("QF1ABC", pos, t0, listOf("WIDE1-1", "WIDE2-1")).trame))
        assertEquals(Voie.DIGI, StationsAprs.voie(p("QF1ABC", pos, t0, listOf("QF6DIG-2*", "WIDE2-1")).trame))
        assertEquals(Voie.DIGI, StationsAprs.voie(p("QF1ABC", pos, t0, listOf("WIDE1*", "WIDE2-1")).trame))
        assertEquals(Voie.SATELLITE, StationsAprs.voie(p("QE4ABC", pos, t0, listOf("RS0ISS*", "WIDE2-1")).trame))
        // Not yet repeated by the ISS: heard direct (on the uplink).
        assertEquals(Voie.DIRECT, StationsAprs.voie(p("QE4ABC", pos, t0, listOf("ARISS")).trame))
        assertEquals(Voie.SATELLITE, StationsAprs.voie(p("RS0ISS", ">ARISS-International Space Station", t0).trame))
        assertEquals(Voie.INTERNET, StationsAprs.voie(p("QF1ABC", pos, t0, listOf("TCPIP*", "qAC", "QF9GW")).trame))
        assertEquals(listOf("QF6DIG-2", "WIDE1"), StationsAprs.digis(p("QF1ABC", pos, t0, listOf("QF6DIG-2*", "WIDE1*", "WIDE2-1")).trame))
    }

    @Test
    fun une_trame_de_tiers_est_la_station_d_internet() {
        val q = p("QF5IGT-10", "}QF3NET-5>APRS,TCPIP,qAC,QF9GW:=4601.00N/00420.00E-Vue par Internet", t0)
        assertEquals(TypeAprs.TIERS, q.type)
        val v = StationsAprs.vu(q)
        assertEquals("QF3NET-5", v.p.source)
        assertEquals(Voie.INTERNET, v.voie)
        assertEquals(46.0167, v.p.lat!!, 1e-3)
        assertTrue(v.brut.startsWith("QF5IGT-10>APRS:}QF3NET-5"))
        assertNull(StationsAprs.tnc2("pas une trame"))
    }

    // ------------------------------------------------------------ the stations

    @Test
    fun une_station_avec_sa_trace_et_ses_voies() {
        val l = listOf(
            p("QF8MOB-9", "=4540.00N/00300.00E>045/040", t0, listOf("QF6DIG-2*", "WIDE2-1")),
            p("QF8MOB-9", "=4541.00N/00301.00E>045/040", t0 + 3 * min, listOf("QF6DIG-2*", "WIDE2-1")),
            p("QF8MOB-9", "=4542.00N/00302.00E>050/042 Vers Lyon", t0 + 6 * min),
            p("QF8MOB-9", ">En mobile", t0 + 7 * min, listOf("QF6DIG-2*")),
            p("QF6DIG-2", "=4546.00N/00255.00E#Digi du sommet", t0 + 1 * min),
            p("QF6DIG-2", "T#005,145,012,000,056,200,00000000", t0 + 2 * min),
            p("QF1ABC", ":QF8MOB-9 :Bonne route{12", t0 + 4 * min),
            p("QF1ABC", "=4547.00N/00305.00E-", t0 + 4 * min),
            // A GPS without a fix: 0 N 0 E is no position.
            p("QF1ABC", "=0000.00N/00000.00E-", t0 + 5 * min),
            p("QF4WX-13", "=4550.20N/00305.10E_220/004g010t050r000h85b10132 Club", t0 + 2 * min),
            p("QF1ABC", ";SALON-RA *111111z4547.50N\\00305.50Ei Salon", t0 + 5 * min),
        )
        val b = StationsAprs.bilan(l, "QF4DMO", 45.7708, 3.0417, t0 + 10 * min, debutJour = t0 - 60 * min)
        val f = b.fiches.associateBy { it.id }
        val car = f.getValue("QF8MOB-9")
        assertEquals(3, car.trace.size)
        assertEquals(StationsAprs.Genre.MOBILE, car.genre)
        assertEquals(50, car.cap)
        assertEquals("Vers Lyon", car.commentaire)
        assertEquals("En mobile", car.statut)
        assertEquals(setOf(Voie.DIGI, Voie.DIRECT), car.vus.keys)
        assertEquals(t0 + 7 * min, car.vus[Voie.DIGI])
        assertEquals(t0 + 6 * min, car.vus[Voie.DIRECT])
        assertEquals(listOf("QF6DIG-2"), car.digis)
        assertEquals(4, car.nombre)
        assertTrue(car.brut.startsWith("QF8MOB-9>APRS,QF6DIG-2*:>En mobile"))
        // Its name in others' paths, its symbol: a digipeater; its telemetry kept.
        assertEquals(StationsAprs.Genre.DIGI, f.getValue("QF6DIG-2").genre)
        assertEquals("T#005,145,012,000,056,200,00000000", f.getValue("QF6DIG-2").telemetrie!!.texte)
        val abc = f.getValue("QF1ABC")
        assertEquals(47 + 0.0, abc.lat!!.minus(45) * 60, 1e-6)   // the 0 N 0 E frame left out of the position
        assertEquals("Bonne route", abc.message!!.texte)
        assertEquals("QF8MOB-9", abc.message!!.a)
        assertEquals(StationsAprs.Genre.FIXE, abc.genre)
        assertEquals(StationsAprs.Genre.METEO, f.getValue("QF4WX-13").genre)
        assertEquals(85, f.getValue("QF4WX-13").meteo!!.humidite)
        val o = f.getValue("SALON-RA")
        assertTrue(o.objet); assertEquals("QF1ABC", o.indicatif); assertEquals(StationsAprs.Genre.OBJET, o.genre)
        assertTrue(abc.km!! in 3.0..5.0)
        assertEquals("JN15", abc.locator!!.take(4))
        assertEquals(5, b.jour)
        assertEquals(0, b.viaSatellite)
        // The farthest heard direct (no digipeater): the hilltop, 10 km west.
        assertEquals("QF6DIG-2", b.loinDirect!!.first)
        assertEquals(l.size, b.paquets)
        assertEquals(l.size, b.parHeure.sum())
        assertEquals(l.size, b.parHeure[23])
    }

    @Test
    fun les_passages_de_l_iss_et_moi_entendu_en_retour() {
        val l = listOf(
            p("RS0ISS", ">ARISS-International Space Station", t0, sat = "ISS"),
            p("RS0ISS", "=0000.00N/00000.00E`", t0 + 30_000L, listOf("APRSAT")),
            p("QE4ABC", "=4024.00N/00342.00W-hola", t0 + 2 * min, listOf("RS0ISS*", "WIDE2-1"), sat = "ISS"),
            p("QI1XYZ", "=4154.00N/01230.00E-ciao", t0 + 4 * min, listOf("RS0ISS*"), sat = "ISS"),
            p("QF4DMO-7", "=4546.00N/00302.00E-moi", t0 + 5 * min, listOf("RS0ISS*"), sat = "ISS"),
            // The next pass, 95 minutes later.
            p("QE4ABC", "=4024.00N/00342.00W-hola", t0 + 100 * min, listOf("RS0ISS*"), sat = ""),
        )
        val b = StationsAprs.bilan(l, "QF4DMO", 45.7708, 3.0417, t0 + 120 * min)
        assertEquals(2, b.passages.size)
        val premier = b.passages.last()
        assertEquals(t0, premier.debut)
        assertEquals("ISS", premier.satellite)
        assertEquals(listOf("QE4ABC", "QI1XYZ", "QF4DMO-7"), premier.stations)
        assertEquals(listOf("QE4ABC"), b.passages.first().stations)
        val f = b.fiches.associateBy { it.id }
        assertEquals(StationsAprs.Genre.SATELLITE, f.getValue("RS0ISS").genre)
        assertNull(f.getValue("RS0ISS").lat)
        assertTrue(f.getValue("QF4DMO-7").moi)
        assertEquals("ISS", f.getValue("QE4ABC").satellite)
        // Stations through a satellite: neither the satellite itself nor me.
        assertEquals(2, b.viaSatellite)
        assertNull(b.loinDirect)
    }

    @Test
    fun les_trames_emises_et_trop_vieilles_ne_comptent_pas() {
        val l = listOf(
            p("QF4DMO", "=4546.00N/00302.00E-", t0).copy(emis = true),
            p("QF1ABC", "=4547.00N/00305.00E-", t0 - 49 * 60 * min),
            p("QF2HOT", "=4547.00N/00305.00E-", t0 - 1 * min),
        )
        val b = StationsAprs.bilan(l, "QF4DMO", null, null, t0)
        assertEquals(listOf("QF2HOT"), b.fiches.map { it.id })
        assertNull(b.fiches.single().km)
    }

    @Test
    fun l_api_des_stations() {
        val l = listOf(
            p("QF8MOB-9", "=4540.00N/00300.00E>045/040", t0 - 10 * min, listOf("QF6DIG-2*")),
            p("QF4WX-13", "=4550.20N/00305.10E_220/004g010t050h85b10132", t0 - 5 * min),
            p("QE4ABC", "=4024.00N/00342.00W-hola", t0 - 3 * min, listOf("RS0ISS*"), sat = "ISS"),
        )
        val o = ApiRadio.stationsAprs(l, "QF4DMO", Observer(45.7708, 3.0417), t0, 24)
        assertTrue(o.getBoolean("present"))
        assertEquals(24, o.getInt("heures"))
        assertEquals("QF4DMO", o.getJSONObject("moi").getString("call"))
        assertEquals("JN15MS", o.getJSONObject("moi").getString("loc"))
        val st = o.getJSONArray("stations")
        assertEquals(3, st.length())
        val iss = st.getJSONObject(0)
        assertEquals("QE4ABC", iss.getString("id"))
        assertEquals("satellite", iss.getString("voie"))
        assertEquals("ISS", iss.getString("sat"))
        assertTrue(iss.getJSONObject("vus").has("satellite"))
        assertEquals("maison (QTH)", iss.getString("symNom"))
        assertTrue(iss.getDouble("km") > 800)
        val wx = st.getJSONObject(1)
        assertEquals("meteo", wx.getString("genre"))
        assertEquals(10.0, wx.getJSONObject("meteo").getDouble("tempC"), 0.1)
        assertFalse(wx.getJSONObject("meteo").has("pluie1h"))
        val car = st.getJSONObject(2)
        assertEquals(45, car.getInt("cap"))
        assertEquals("digi", car.getString("voie"))
        assertEquals(1, car.getJSONArray("trace").length())
        assertEquals(3, car.getJSONArray("trace").getJSONArray(0).length())
        assertEquals(1, o.getJSONArray("passages").length())
        assertEquals("ISS", o.getJSONArray("passages").getJSONObject(0).getString("sat"))
        val s = o.getJSONObject("stats")
        assertEquals(24, s.getJSONArray("parHeure").length())
        assertEquals(1, s.getInt("viaSat"))
        assertTrue(s.isNull("loinDirect").not())
        assertEquals("QF4WX-13", s.getJSONObject("loinDirect").getString("call"))
    }
}
