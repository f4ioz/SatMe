/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.Doppler
import fr.f4ioz.satcombo.domain.Ft8Montee
import fr.f4ioz.satcombo.domain.Ft8Pliage
import fr.f4ioz.satcombo.domain.Ft8Qso
import fr.f4ioz.satcombo.domain.Ft8Qso.Etape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The FT8/FT4 contact: Tx1–Tx6, the sequence (answering a CQ, being
 * answered, RR73 or 73), the attendance watchdog, the slots' parity, the
 * clock's refusal, a clear frequency, and where the signal lands through
 * a linear transponder.
 */
class Ft8QsoTest {

    private val T0 = 1_700_000_010_000L        // a slot boundary (15 s and 7.5 s)
    private fun slot(n: Int) = T0 + n * 15_000L

    private fun recu(texte: String, de: String?, a: String?, n: Int, snr: Int = -10, loc: String? = null) =
        Ft8Qso.Recu(texte, de, a, loc, snr, 1200, slot(n), Ft8Qso.estPaire(Ft8Qso.tranche(slot(n), false)))

    @Test fun les_six_messages_sont_ceux_de_wsjt() {
        val q = Ft8Qso("F4IOZ", "JN18fs")
        assertEquals(listOf("", "", "", "", "", "CQ F4IOZ JN18"), q.messages())
        q.repond(recu("CQ K1ABC FN42", "K1ABC", null, 0, -7, "FN42"), slot(1))
        assertEquals(listOf("K1ABC F4IOZ JN18", "K1ABC F4IOZ -07", "K1ABC F4IOZ R-07", "K1ABC F4IOZ RR73", "K1ABC F4IOZ 73", "CQ F4IOZ JN18"), q.messages())
        q.rrr = true
        assertEquals("K1ABC F4IOZ RRR", q.messages()[3])
        // Every one of them packs.
        q.messages().forEach { assertNotNull(it, Ft8Pliage.plie(it)) }
    }

    @Test fun avec_un_indicatif_compose_les_messages_se_codent_quand_meme() {
        val q = Ft8Qso("F4IOZ", "JN18")
        q.repond(recu("CQ PJ4/K1ABC", "PJ4/K1ABC", null, 0, -3), slot(1))
        assertEquals("<PJ4/K1ABC> F4IOZ JN18", q.messages()[0])
        assertEquals("PJ4/K1ABC <F4IOZ> RR73", q.messages()[3])
        q.messages().forEach { assertNotNull(it, Ft8Pliage.plie(it)) }
        val moi = Ft8Qso("EA8/F4IOZ", "IL18")
        assertEquals("CQ EA8/F4IOZ", moi.messages()[5])
        moi.repond(recu("CQ K1ABC FN42", "K1ABC", null, 0), slot(1))
        moi.messages().forEach { assertNotNull(it, Ft8Pliage.plie(it)) }
    }

    @Test fun repondre_a_un_cq_puis_toute_la_sequence() {
        val q = Ft8Qso("F4IOZ", "JN18")
        // K1ABC calls CQ in an odd slot: we answer in the even ones.
        q.repond(recu("CQ K1ABC FN42", "K1ABC", null, 1, -7, "FN42"), slot(2))
        assertEquals(1, q.prochain); assertEquals(Etape.APPEL, q.etape)
        assertEquals(!Ft8Qso.estPaire(Ft8Qso.tranche(slot(1), false)), q.pair)
        q.envoye(1, slot(2) + 13_000)
        // He sends me a report: R and mine.
        assertTrue(q.recoit(listOf(recu("F4IOZ K1ABC -12", "K1ABC", "F4IOZ", 3)), true))
        assertEquals(3, q.prochain); assertEquals("K1ABC F4IOZ R-07", q.aEnvoyer())
        q.envoye(3, slot(4) + 13_000)
        // RR73: our 73, then over and offered to the log.
        q.recoit(listOf(recu("F4IOZ K1ABC RR73", "K1ABC", "F4IOZ", 5)), true)
        assertEquals(5, q.prochain); assertEquals("K1ABC F4IOZ 73", q.aEnvoyer())
        q.envoye(5, slot(6) + 13_000)
        assertEquals(0, q.prochain); assertEquals(Etape.FINI, q.etape); assertNull(q.aEnvoyer())
        val f = q.aNoter!!
        assertEquals("K1ABC", f.indicatif); assertEquals("FN42", f.carre)
        assertEquals("-07", f.rapportEnvoye); assertEquals("-12", f.rapportRecu)
    }

    @Test fun etre_appele_apres_mon_cq_et_finir_par_rr73() {
        val q = Ft8Qso("F4IOZ", "JN18")
        q.appelleCq(true, slot(0))
        assertEquals("CQ F4IOZ JN18", q.aEnvoyer())
        q.envoye(6, slot(0) + 13_000)
        assertEquals(6, q.prochain)    // CQ repeats (the watchdog bounds it)
        // Two stations answer: the first one (strongest, listed first) becomes the partner.
        q.recoit(listOf(recu("F4IOZ G4ABC IO91", "G4ABC", "F4IOZ", 1, -3, "IO91"), recu("F4IOZ EA4XYZ IN80", "EA4XYZ", "F4IOZ", 1, -15, "IN80")), true)
        assertEquals("G4ABC", q.partenaire); assertEquals(2, q.prochain)
        assertEquals("G4ABC F4IOZ -03", q.aEnvoyer())
        q.envoye(2, slot(2) + 13_000)
        // The other one insists: ignored.
        assertFalse(q.recoit(listOf(recu("F4IOZ EA4XYZ IN80", "EA4XYZ", "F4IOZ", 3)), true))
        q.recoit(listOf(recu("F4IOZ G4ABC R-09", "G4ABC", "F4IOZ", 5)), true)
        assertEquals("G4ABC F4IOZ RR73", q.aEnvoyer())
        q.envoye(4, slot(6) + 13_000)
        assertEquals(0, q.prochain)
        assertEquals("-09", q.aNoter!!.rapportRecu)
        assertEquals("IO91", q.aNoter!!.carre)
    }

    @Test fun un_73_recu_apres_mon_rr73_termine() {
        val q = Ft8Qso("F4IOZ", "JN18")
        q.rrr = true
        q.appelleCq(false, slot(1))
        q.recoit(listOf(recu("F4IOZ G4ABC IO91", "G4ABC", "F4IOZ", 2)), true)
        q.recoit(listOf(recu("F4IOZ G4ABC R-09", "G4ABC", "F4IOZ", 4)), true)
        assertEquals("G4ABC F4IOZ RRR", q.aEnvoyer())
        q.envoye(4, slot(5) + 13_000)
        assertEquals(Etape.RR73, q.etape)       // RRR: the 73 is still awaited
        q.recoit(listOf(recu("F4IOZ G4ABC 73", "G4ABC", "F4IOZ", 6)), true)
        assertEquals(Etape.FINI, q.etape)
        assertNotNull(q.aNoter)
    }

    @Test fun etre_appele_sans_rien_avoir_commence_ne_fait_rien() {
        val q = Ft8Qso("F4IOZ", "JN18")
        assertFalse(q.recoit(listOf(recu("F4IOZ G4ABC IO91", "G4ABC", "F4IOZ", 1)), true))
        assertEquals(0, q.prochain); assertNull(q.partenaire); assertNull(q.aEnvoyer())
    }

    @Test fun sans_sequence_automatique_rien_n_avance() {
        val q = Ft8Qso("F4IOZ", "JN18")
        q.repond(recu("CQ K1ABC FN42", "K1ABC", null, 1), slot(2))
        q.recoit(listOf(recu("F4IOZ K1ABC -12", "K1ABC", "F4IOZ", 3)), false)
        assertEquals(1, q.prochain)
        assertEquals(-12, q.rapportRecu)
        q.choisit(3, slot(4))
        assertEquals("K1ABC F4IOZ R-10", q.aEnvoyer())
    }

    @Test fun double_clic_sur_une_station_qui_m_appelle_saute_a_la_bonne_etape() {
        val q = Ft8Qso("F4IOZ", "JN18")
        q.repond(recu("F4IOZ K1ABC -05", "K1ABC", "F4IOZ", 1, -14), slot(2))
        assertEquals(3, q.prochain); assertEquals("K1ABC F4IOZ R-14", q.aEnvoyer())
        val r = Ft8Qso("F4IOZ", "JN18")
        r.repond(recu("F4IOZ K1ABC R-05", "K1ABC", "F4IOZ", 1, -14), slot(2))
        assertEquals(4, r.prochain)
    }

    @Test fun ma_propre_ligne_et_les_vieilles_lignes_sont_ignorees() {
        val q = Ft8Qso("F4IOZ", "JN18")
        assertNotNull(q.repond(recu("CQ F4IOZ JN18", "F4IOZ", null, 1), slot(2)))
        q.repond(recu("CQ K1ABC FN42", "K1ABC", null, 3), slot(4))
        // A report from K1ABC heard before this contact began: not this contact's.
        assertFalse(q.recoit(listOf(recu("F4IOZ K1ABC -12", "K1ABC", "F4IOZ", 1)), true))
        // Our own slot (our signal back from the satellite): not read either.
        assertFalse(q.recoit(listOf(recu("F4IOZ K1ABC -12", "K1ABC", "F4IOZ", 4)), true))
        // The same slot twice: once.
        assertTrue(q.recoit(listOf(recu("F4IOZ K1ABC -12", "K1ABC", "F4IOZ", 5)), true))
        assertFalse(q.recoit(listOf(recu("F4IOZ K1ABC -12", "K1ABC", "F4IOZ", 5)), true))
    }

    @Test fun le_chien_de_garde_de_presence() {
        val q = Ft8Qso("F4IOZ", "JN18")
        q.appelleCq(true, T0)
        assertEquals(3 * 60_000L, q.chienRestantMs(T0))
        assertFalse(q.chienExpire(T0 + 179_000))
        assertTrue(q.chienExpire(T0 + 180_000))
        // Received messages do not count as presence; an operator gesture does.
        q.recoit(listOf(recu("F4IOZ G4ABC IO91", "G4ABC", "F4IOZ", 1)), true)
        assertTrue(q.chienExpire(T0 + 180_000))
        q.edite(2, "G4ABC F4IOZ -01", T0 + 170_000)
        assertFalse(q.chienExpire(T0 + 180_000))
        q.chienMin = 1
        assertTrue(q.chienExpire(T0 + 231_000))
        q.chienMin = 99
        assertEquals(10, q.chienMin)
    }

    @Test fun le_message_edite_remplace_le_genere() {
        val q = Ft8Qso("F4IOZ", "JN18")
        q.repond(recu("CQ K1ABC FN42", "K1ABC", null, 1), slot(2))
        q.edite(1, "k1abc f4ioz jn18 ", slot(2))
        assertEquals("K1ABC F4IOZ JN18", q.messages()[0])
        q.edite(1, "", slot(2))
        assertEquals("K1ABC F4IOZ JN18", q.messages()[0])
        q.edite(6, "CQ DX F4IOZ JN18", slot(2))
        assertEquals("CQ DX F4IOZ JN18", q.messages()[5])
    }

    @Test fun parite_des_tranches() {
        assertEquals(15_000L, Ft8Qso.dureeTrancheMs(false)); assertEquals(7_500L, Ft8Qso.dureeTrancheMs(true))
        val t = 1_700_000_010_000L
        assertTrue(Ft8Qso.estPaire(Ft8Qso.tranche(t, false)) != Ft8Qso.estPaire(Ft8Qso.tranche(t + 15_000, false)))
        assertTrue(Ft8Qso.estPaire(Ft8Qso.tranche(t, true)) != Ft8Qso.estPaire(Ft8Qso.tranche(t + 7_500, true)))
        // A minute of FT8: even at :00 and :30.
        assertTrue(Ft8Qso.estPaire(Ft8Qso.tranche(60_000L * 1000, false)))
        assertFalse(Ft8Qso.estPaire(Ft8Qso.tranche(60_000L * 1000 + 15_000, false)))
        assertTrue(Ft8Qso.debutMaxMs(true) < Ft8Qso.debutMaxMs(false))
    }

    @Test fun l_horloge_trop_decalee_refuse_l_emission() {
        assertNull(Ft8Qso.refusHorloge(null, true))
        assertNull(Ft8Qso.refusHorloge(0.3, true))
        assertNotNull(Ft8Qso.refusHorloge(0.6, true))
        assertNotNull(Ft8Qso.refusHorloge(-0.7, true))
        assertNull(Ft8Qso.refusHorloge(0.8, false))
        assertNotNull(Ft8Qso.refusHorloge(1.4, false))
    }

    @Test fun une_frequence_libre_loin_des_stations() {
        assertEquals(1500, Ft8Qso.frequenceLibre(listOf(800, 2000), 1500, false))
        val f = Ft8Qso.frequenceLibre(listOf(1480, 1530), 1500, false)!!
        assertTrue(listOf(1480, 1530).none { o -> f < o + 60 && o < f + 60 })
        assertNull(Ft8Qso.frequenceLibre((300..2700 step 40).toList(), 1500, false))
        // FT4 is wider.
        assertEquals(1500, Ft8Qso.frequenceLibre(listOf(1400), 1500, false))
        assertTrue(Ft8Qso.frequenceLibre(listOf(1420), 1500, true) != 1500)
    }

    @Test fun ou_tombe_le_signal_par_un_transpondeur_inverse() {
        // RS-44-like: uplink 145.935–145.995, downlink 435.610–435.670, inverting.
        val dlBas = 435_610_000L; val dlHaut = 435_670_000L; val ulBas = 145_935_000L; val ulHaut = 145_995_000L
        val repos = 435_640_000L
        val ulRepos = Doppler.transponderUplinkRest(repos, dlBas, dlHaut, ulBas, ulHaut, true)
        assertEquals(145_965_000L, ulRepos)
        val rr = -5.2   // approaching, km/s
        val ul = Doppler.uplink(ulRepos, rr); val dl = Doppler.downlink(repos, rr)
        // Our tones at 1500 Hz: below the uplink dial (LSB), above the downlink dial (USB).
        assertEquals(ul - 1500, Ft8Montee.montee(ul, 1500, true))
        assertEquals(dl + 1500, Ft8Montee.descente(dl, 1500))
        // Through the transponder, 1500 Hz lower on the uplink is 1500 Hz higher on the downlink, at rest.
        val ulSat = Math.round(Ft8Montee.montee(ul, 1500, true) / (1 + rr / Doppler.C_KM_S))
        assertEquals(ulRepos - 1500.0, ulSat.toDouble(), 2.0)
        assertNull(Ft8Montee.horsBande(ul, 1500, 50, true, rr, ulBas, ulHaut))
        // Tuned at the passband's edge: the signal would fall out.
        val ulBord = Doppler.uplink(ulBas + 500, rr)
        assertNotNull(Ft8Montee.horsBande(ulBord, 1500, 50, true, rr, ulBas, ulHaut))
        // Not inverting: added.
        assertEquals(1_000_001_500L, Ft8Montee.montee(1_000_000_000L, 1500, false))
    }
}
