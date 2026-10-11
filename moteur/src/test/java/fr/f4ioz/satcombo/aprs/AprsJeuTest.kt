/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.aprs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The game side of APRS: weather, our frame back from the ISS, contacts, trophies. */
class AprsJeuTest {

    private val issRepete = listOf(Adresse("RS0ISS", repete = true), Adresse("WIDE2", 1))

    private fun p(info: String, de: String, quand: Long, iss: Boolean = false, emis: Boolean = false,
                  chemin: List<Adresse> = if (iss) issRepete else emptyList()) =
        Aprs.lit(Trame(Adresse("APRS"), Adresse.de(de), chemin, info.toByteArray(Charsets.ISO_8859_1)), quand)
            .copy(emis = emis)

    private val pays: (String) -> String? = { c ->
        when { c.startsWith("F") -> "France"; c.startsWith("G") -> "England"; c.startsWith("EA") -> "Spain"; else -> null }
    }

    // ------------------------------------------------------------ weather

    @Test
    fun meteo_apres_une_position() {
        // APRS 1.01 example: wind 220° at 4 mph, gusts 5 mph, 77 °F, rain, 50 % humidity, 990.1 hPa.
        val m = p("!4903.50N/07201.75W_220/004g005t077r000p000P000h50b09900wRSW", "F4ABC", 0)
        assertEquals(TypeAprs.METEO, m.type)
        val w = m.meteo!!
        assertEquals(220, w.ventDeg)
        assertEquals(6, w.ventKmh)
        assertEquals(8, w.rafaleKmh)
        assertEquals(25.0, w.temperatureC!!, 0.05)
        assertEquals(0.0, w.pluie1hMm!!, 1e-9)
        assertEquals(50, w.humidite)
        assertEquals(990.0, w.pressionHpa!!, 1e-9)
        assertEquals("wRSW", m.commentaire)
    }

    @Test
    fun meteo_sans_position_et_champs_absents() {
        val m = p("_10090556c220s004g...t-05h00b10132", "F4ABC", 0)
        assertEquals(TypeAprs.METEO, m.type)
        val w = m.meteo!!
        assertEquals(220, w.ventDeg)
        assertNull(w.rafaleKmh)
        assertEquals(-20.6, w.temperatureC!!, 0.05)
        assertEquals(100, w.humidite)  // "h00" means 100 %
        assertEquals(1013.2, w.pressionHpa!!, 1e-9)
    }

    @Test
    fun une_position_ordinaire_n_a_pas_de_meteo() {
        assertNull(p("!4903.50N/07201.75W-Test", "F4ABC", 0).meteo)
    }

    // ------------------------------------------------- our frame back from the ISS

    @Test
    fun notre_trame_repetee_par_l_iss_et_qui_l_a_partagee() {
        val t0 = 1_000_000L
        val liste = listOf(
            p("=4852.55N/00219.00E-SatMe", "F4IOZ-7", t0, emis = true, chemin = listOf(Adresse("ARISS"))),
            p("=4852.55N/00219.00E-SatMe", "F4IOZ-7", t0 + 1_000, iss = true),
            p("!5130.00N/00007.00W-", "G0ABC", t0 + 60_000, iss = true),
            p("!4030.00N/00342.00W-", "EA4XYZ", t0 + 3_600_000, iss = true),   // another pass
            p("!4500.00N/00100.00E-", "F1AAA", t0 + 30_000),                     // direct, not via ISS
        )
        val repetes = AprsJeu.repetesParIss(liste, "F4IOZ")
        assertEquals(1, repetes.size)
        assertEquals(listOf("G0ABC"), AprsJeu.entendusAutour(liste, repetes[0].quand, "F4IOZ"))
        // Without a callsign, nothing is ours.
        assertTrue(AprsJeu.repetesParIss(liste, "").isEmpty())
    }

    // ------------------------------------------------------- contacts

    @Test
    fun un_message_accuse_par_son_destinataire_est_un_contact() {
        val t0 = 5_000_000L
        val liste = listOf(
            p("!5130.00N/00007.00W-", "G0ABC", t0, iss = true),
            p(":G0ABC    :Hi from JN18{12", "F4IOZ-7", t0 + 10_000, emis = true, chemin = listOf(Adresse("ARISS"))),
            p(":F4IOZ-7  :ack12", "G0ABC", t0 + 40_000, iss = true),
        )
        assertTrue(AprsJeu.estAccuse(liste[1], liste))
        val c = AprsJeu.contacts(liste, "F4IOZ")
        assertEquals(1, c.size)
        assertEquals("G0ABC", c[0].indicatif)
        assertEquals("IO91", c[0].carre)
        assertTrue(c[0].viaIss)
    }

    @Test
    fun un_message_recu_puis_accuse_par_nous_est_un_contact() {
        val liste = listOf(
            p(":F4IOZ    :CQ via ISS{7", "EA4XYZ", 0, iss = true),
            p(":EA4XYZ   :ack7", "F4IOZ", 5_000, emis = true),
        )
        assertEquals(listOf("EA4XYZ"), AprsJeu.contacts(liste, "F4IOZ").map { it.indicatif })
    }

    @Test
    fun ni_un_message_sans_accuse_ni_un_serveur_ne_font_un_contact() {
        val liste = listOf(
            p(":G0ABC    :Hi{12", "F4IOZ", 0, emis = true),
            p(":ANSRVR   :CQ HOTG{3", "F4IOZ", 1_000, emis = true),
            p(":F4IOZ    :ack3", "ANSRVR", 2_000),
        )
        assertTrue(AprsJeu.contacts(liste, "F4IOZ").isEmpty())
        // The ack to ANSRVR counts as acknowledged, but is not a person.
        assertTrue(AprsJeu.estAccuse(liste[1], liste))
    }

    @Test
    fun les_fils_de_conversation() {
        val liste = listOf(
            p(":G0ABC    :Hi{1", "F4IOZ", 0, emis = true),
            p(":F4IOZ-7  :Hello{9", "G0ABC", 10_000),
            p(":EA4XYZ   :Hola{2", "F4IOZ", 20_000, emis = true),
            p(":ANSRVR   :CQ HOTG{3", "F4IOZ", 30_000, emis = true),
            p(":F1AAA    :not for us", "G0ABC", 40_000),
        )
        val f = AprsJeu.fils(liste, "F4IOZ")
        assertEquals(listOf("EA4XYZ", "G0ABC"), f.map { it.correspondant })
        assertEquals(2, f[1].messages.size)
    }

    @Test
    fun participants_de_l_aprs_thursday() {
        val liste = listOf(
            p(":ANSRVR   :CQ HOTG hello{1", "G0ABC", 1_000),
            p(":ANSRVR   :CQ HOTG 73{2", "F4IOZ", 2_000, emis = true),
            p(":ANSRVR   :CQ HOTG{5", "EA4XYZ", 3_000),
            p(":ANSRVR   :CQ HOTG{6", "F1OLD", -5_000),   // before the evening
        )
        assertEquals(listOf("G0ABC", "EA4XYZ"), AprsJeu.participantsHotg(liste, 0, "F4IOZ"))
    }

    @Test
    fun le_bilan_de_la_soiree() {
        val liste = listOf(
            p("!5130.00N/00007.00W-", "G0ABC", 0, iss = true),
            p("!4030.00N/00342.00W-", "EA4XYZ", 1_000, iss = true),
            p("!4500.00N/00100.00E-", "F1AAA", 2_000),
            p("=4852.55N/00219.00E-", "F4IOZ", 3_000, emis = true),
        )
        val b = AprsJeu.bilan(liste, 0, "F4IOZ", 48.876, 2.317, pays)
        assertEquals(3, b.stations)
        assertEquals(setOf("France", "England", "Spain"), b.pays)
        assertEquals(3, b.carres.size)
        assertEquals("EA4XYZ", b.plusLoin!!.indicatif)
        assertEquals(2, b.viaIss)
        assertEquals(1, b.envoyes)
    }

    // ------------------------------------------------------------ trophies

    @Test
    fun les_trophees_se_gagnent_et_se_fetent_une_fois() {
        var e = Trophees.Etat()
        val tous = ArrayList<Paquet>()
        val ev = ArrayList<Trophees.Evenement>()
        fun recoit(x: Paquet) {
            tous += x
            val (n, l) = Trophees.avec(e, x, tous, "F4IOZ", 48.876, 2.317, pays)
            e = n; ev += l
        }
        recoit(p("!5130.00N/00007.00W-", "G0ABC", 0, iss = true))
        assertTrue(Trophees.Badge.PREMIER_PAQUET in e.badges)
        assertTrue(Trophees.Badge.PREMIER_ISS in e.badges)
        assertTrue(ev.any { it is Trophees.Evenement.NouveauCarre && it.carre == "IO91" })
        assertTrue(ev.any { it is Trophees.Evenement.Record })
        ev.clear()
        // Same square, same country again: nothing to cheer.
        recoit(p("!5131.00N/00008.00W-", "G0XYZ", 1_000))
        assertFalse(ev.any { it is Trophees.Evenement.NouveauCarre || it is Trophees.Evenement.NouveauPays })
        // Our frame back through the ISS.
        recoit(p("=4852.55N/00219.00E-", "F4IOZ-7", 2_000, iss = true))
        assertEquals(1, e.repetesIss)
        assertTrue(ev.any { it is Trophees.Evenement.RepeteIss && "G0ABC" in it.autres })
        assertTrue(Trophees.Badge.REPETE_ISS in e.badges)
        // A contact, cheered once.
        recoit(p(":G0ABC    :Hi{4", "F4IOZ-7", 3_000, emis = true))
        recoit(p(":F4IOZ-7  :ack4", "G0ABC", 4_000, iss = true))
        assertEquals(1, ev.count { it is Trophees.Evenement.Contact })
        assertTrue(Trophees.Badge.CONTACT_ISS in e.badges)
        recoit(p(":F4IOZ-7  :ack4", "G0ABC", 5_000, iss = true))
        assertEquals(1, ev.count { it is Trophees.Evenement.Contact })
        // Saved and read back the same.
        assertEquals(e, Trophees.lit(Trophees.ecrit(e)))
    }

    @Test
    fun une_station_proche_n_est_pas_fetee_comme_record() {
        val x = p("!4853.00N/00220.00E-", "F1AAA", 0)
        val (e, ev) = Trophees.avec(Trophees.Etat(), x, listOf(x), "F4IOZ", 48.876, 2.317, pays)
        assertTrue(e.recordKm > 0)
        assertFalse(ev.any { it is Trophees.Evenement.Record })
    }
}
