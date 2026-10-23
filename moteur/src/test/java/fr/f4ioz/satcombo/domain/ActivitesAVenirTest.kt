/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import fr.f4ioz.satcombo.data.SatPass
import fr.f4ioz.satcombo.domain.ActivitesAVenir.Activite
import fr.f4ioz.satcombo.domain.ActivitesAVenir.Confiance
import fr.f4ioz.satcombo.domain.ActivitesAVenir.Precision
import fr.f4ioz.satcombo.domain.ActivitesAVenir.Source
import fr.f4ioz.satcombo.domain.ActivitesAVenir.Type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** « Activités à venir »: the sources read on trimmed real samples (October 2026), merged, kept, signalled. */
class ActivitesAVenirTest {

    private fun ressource(n: String) = javaClass.getResource("/activites/$n")!!.readText()
    private fun t(s: String) = LectureActivites.iso(s)!!
    private val MAINTENANT = t("2026-10-23T20:00:00Z")

    private fun pass(cat: Int, aos: String, minutes: Int, el: Double) =
        SatPass("ISS", cat, t(aos), t(aos) + minutes * 60_000L, el, 200.0, 80.0, false, false)

    // ------------------------------------------------------------------ the ARISS calendar

    @Test fun le_calendrier_ariss_donne_la_sstv_et_les_contacts_a_venir() {
        val l = LectureActivites.ariss(ressource("ariss-extrait.ics"), MAINTENANT - 2 * 86_400_000L)
        // 2019, HamTV, the October 2 series and the MarconISSta day: left out (over, or not decoded).
        assertEquals(listOf(Type.SSTV, Type.CONTACT), l.map { it.type })
        val s = l[0]
        assertEquals(t("2026-10-22T15:10:00Z"), s.debutMs)
        assertEquals(t("2026-10-26T17:45:00Z"), s.finMs)
        assertEquals(437_550_000L, s.freqHz)   // not 437.825 (APRS afterwards), not 145.800
        assertEquals("Robot 36", s.mode)
        assertEquals("36 s on / 2 min off", s.horaire)
        assertEquals("ARISS SSTV Series 34", s.titre)
        assertEquals(25544, s.catnum)
        assertTrue(s.liens.any { it.startsWith("https://ariss-usa.org/ARISS_SSTV") })
        assertEquals(Precision.EXACTE, s.precision)
        val c = l[1]
        assertEquals("NA1SS (Luke Delaney KJ5PPF) - 24 deg - W4SMA ( Sarasota, FL)", c.titre)
        assertEquals(145_800_000L, c.freqHz)
        assertEquals("", c.mode)
        assertTrue(c.details, c.details.startsWith("RX:") && c.details.contains("FM over USA"))
    }

    @Test fun un_contact_francais_passe_et_les_journees_entieres() {
        val l = LectureActivites.ariss(ressource("ariss-extrait.ics"), t("2019-01-01T00:00:00Z"))
        val f = l.single { it.titre.contains("F4KKM") }
        assertEquals(Type.CONTACT, f.type)
        assertEquals(t("2026-10-16T11:29:00Z"), f.debutMs)
        // « MarconISSta »: neither SSTV nor a school contact.
        assertTrue(l.none { it.titre.contains("MarconISSta") })
        assertTrue(l.none { it.titre.contains("HamTV") })
        assertEquals(2, l.count { it.type == Type.SSTV })
    }

    // ------------------------------------------------------------------ AMSAT, SatNOGS

    @Test fun les_ecoutes_amsat_confirment_la_sstv() {
        val e = LectureActivites.amsat(ressource("amsat-iss-sstv.json"))
        assertEquals(6, e.size)
        assertEquals("JF6BCC", e[2].indicatif)   // « JF6BCC/437550 »
        assertEquals(t("2026-10-23T19:30:00Z"), e[0].quandMs)
        assertFalse(e.last().entendu)
        val s = LectureActivites.ariss(ressource("ariss-extrait.ics"), MAINTENANT - 86_400_000L).first { it.type == Type.SSTV }
        assertEquals("ISS_[SSTV]", ActivitesAVenir.nomAmsat(s))
        val c = ActivitesAVenir.confirme(listOf(s), mapOf("ISS_[SSTV]" to e)).single()
        assertEquals(Confiance.CONFIRME, c.confiance)
        assertEquals(5, c.ecoutes)
        assertEquals(t("2026-10-23T19:30:00Z"), c.derniereEcouteMs)
        assertTrue(c.sources.any { it.nom.startsWith("AMSAT") })
        assertTrue(ActivitesAVenir.aEcouter(s, MAINTENANT))
        assertFalse(ActivitesAVenir.aEcouter(s, t("2026-10-28T12:00:00Z")))
        assertEquals(emptyList<ActivitesAVenir.Ecoute>(), LectureActivites.amsat("[]"))
        assertEquals(emptyList<ActivitesAVenir.Ecoute>(), LectureActivites.amsat("{\"mode\":[\"x\"]}"))
    }

    @Test fun satnogs_dit_meteor_vu_recemment_sur_137_9() {
        val a = LectureActivites.satnogs(ressource("satnogs-57166.json"), 57166, "METEOR-M2-3", MAINTENANT)
        assertNotNull(a); a!!
        assertEquals(Type.LRPT, a.type)
        assertEquals(137_900_000L, a.freqHz)
        assertEquals(Confiance.VU_RECEMMENT, a.confiance)
        assertEquals(6, a.ecoutes)
        assertEquals(t("2026-10-23T18:07:30Z"), a.derniereEcouteMs)
        // A week later: no longer seen.
        assertNull(LectureActivites.satnogs(ressource("satnogs-57166.json"), 57166, "METEOR-M2-3", MAINTENANT + 7 * 86_400_000L))
        // Another satellite's observations are not this one's.
        assertNull(LectureActivites.satnogs(ressource("satnogs-57166.json"), 59051, "METEOR-M2-4", MAINTENANT))
    }

    // ------------------------------------------------------------------ news feeds

    @Test fun amsat_uk_annonce_la_meme_sstv_vers_15_10() {
        val l = LectureActivites.rss(ressource("amsat-uk.rss"), Source("AMSAT-UK", "https://amsat-uk.org/"), MAINTENANT)
        val s = l.single()   // the colloquium's post is not about pictures
        assertEquals(Type.SSTV, s.type)
        assertEquals(25544, s.catnum)
        assertEquals(t("2026-10-22T15:10:00Z"), s.debutMs)   // « around 15:10 UTC »
        assertEquals(t("2026-10-26T17:45:00Z"), s.finMs)
        assertEquals(Precision.EXACTE, s.precision)
        assertEquals(437_550_000L, s.freqHz)
        assertEquals("Robot 36", s.mode)
        assertEquals("https://amsat-uk.org/2026/10/20/iss-slow-scan-tv-oct-22-26/", s.liens.single())
    }

    @Test fun amsat_f_en_francais_avec_ou_sans_horaire() {
        val maintenant = t("2026-10-01T19:00:00Z")
        val l = LectureActivites.rss(ressource("amsat-f.rss"), Source("AMSAT-F", "https://site.amsat-f.org/"), maintenant)
        assertEquals(2, l.size)   // the Fête de la Science is not about pictures
        val u = l.first { it.titre.contains("UMKA") }
        assertEquals("UMKA-1", u.satNom)
        assertEquals("RS40S_[SSTV]", ActivitesAVenir.nomAmsat(u))
        assertEquals(t("2026-10-02T00:00:00Z"), u.debutMs)   // the day, its times not given
        assertEquals(Precision.JOURS, u.precision)
        val k = l.first { it.titre.contains("SAKHACUBE") }
        assertEquals(Precision.JOURS, k.precision)
        assertEquals(t("2026-09-02T00:00:00Z"), k.debutMs)
        assertTrue(k.finMs >= t("2026-09-04T23:00:00Z"))
        assertTrue(k.liens.single().startsWith("https://site.amsat-f.org/2026/09/01/"))
    }

    // ------------------------------------------------------------------ merging

    @Test fun deux_sources_une_seule_activite_au_plus_tot_et_au_plus_tard() {
        val ics = LectureActivites.ariss(ressource("ariss-extrait.ics"), MAINTENANT - 2 * 86_400_000L)
        val rss = LectureActivites.rss(ressource("amsat-uk.rss"), Source("AMSAT-UK", "https://amsat-uk.org/"), MAINTENANT)
        // A third source saying 15:30 → 18:00.
        val autre = rss.single().copy(id = "x", debutMs = t("2026-10-22T15:30:00Z"), finMs = t("2026-10-26T18:00:00Z"), sources = listOf(Source("Autre")))
        val l = ActivitesAVenir.fusionne(ics + rss + autre)
        val s = l.single { it.type == Type.SSTV }
        assertEquals(t("2026-10-22T15:10:00Z"), s.debutMs)
        assertEquals(t("2026-10-26T18:00:00Z"), s.finMs)
        assertEquals(listOf("ARISS (agenda)", "AMSAT-UK", "Autre"), s.sources.map { it.nom })
        assertEquals("ARISS SSTV Series 34", s.titre)
        assertEquals(2, l.size)   // and the contact
    }

    @Test fun un_billet_sans_date_ne_rallonge_pas_l_annonce() {
        val a = Activite("a", Type.SSTV, "ISS", 25544, t("2026-10-22T15:10:00Z"), t("2026-10-26T17:45:00Z"), titre = "SSTV", sources = listOf(Source("A")))
        val b = a.copy(id = "b", debutMs = t("2026-10-20T00:00:00Z"), finMs = t("2026-10-27T00:00:00Z"), precision = Precision.INCONNUE, sources = listOf(Source("B")), liens = listOf("https://b.example/"))
        val m = ActivitesAVenir.fusionne(listOf(b, a)).single()
        assertEquals(a.debutMs, m.debutMs); assertEquals(a.finMs, m.finMs)
        assertEquals(Precision.EXACTE, m.precision)
        assertEquals(listOf("https://b.example/"), m.liens)
        // Two contacts the same day, three hours apart: two.
        val c1 = a.copy(id = "c1", type = Type.CONTACT, debutMs = t("2026-10-22T10:00:00Z"), finMs = t("2026-10-22T10:10:00Z"))
        val c2 = c1.copy(id = "c2", debutMs = t("2026-10-22T11:40:00Z"), finMs = t("2026-10-22T11:50:00Z"))
        assertEquals(2, ActivitesAVenir.fusionne(listOf(c1, c2)).size)
    }

    @Test fun ce_qui_est_garde() {
        val a = Activite("a", Type.SSTV, "ISS", 25544, MAINTENANT - 5 * 86_400_000L, MAINTENANT - 86_400_000L, titre = "fini", sources = emptyList())
        val noaa = a.copy(id = "n", satNom = "NOAA 19", debutMs = MAINTENANT, finMs = MAINTENANT + 3_600_000L)
        val lrpt = a.copy(id = "l", type = Type.LRPT, satNom = "METEOR-M2-4", catnum = 59051, finMs = MAINTENANT - 86_400_000L)
        val vieux = lrpt.copy(id = "v", finMs = MAINTENANT - 5 * 86_400_000L)
        val ok = a.copy(id = "ok", finMs = MAINTENANT + 3_600_000L)
        assertEquals(listOf("l", "ok"), ActivitesAVenir.aGarder(listOf(a, noaa, lrpt, vieux, ok), MAINTENANT).map { it.id })
    }

    // ------------------------------------------------------------------ passes and drafts

    @Test fun les_passages_pendant_l_activite_et_la_campagne_proposee() {
        val s = LectureActivites.ariss(ressource("ariss-extrait.ics"), MAINTENANT - 86_400_000L).first { it.type == Type.SSTV }
        val p = listOf(pass(25544, "2026-10-23T21:00:00Z", 10, 45.0), pass(25544, "2026-10-23T22:40:00Z", 8, 4.0),
            pass(25544, "2026-10-27T10:00:00Z", 10, 60.0), pass(43017, "2026-10-23T21:30:00Z", 10, 50.0))
        val u = ActivitesAVenir.passages(s, p, 10.0, MAINTENANT)
        assertEquals(listOf(t("2026-10-23T21:00:00Z")), u.map { it.aosEpochMs })
        val c = ActivitesAVenir.campagne(s, MAINTENANT)
        assertEquals(25544, c.catnum); assertEquals(437_550_000L, c.freqHz); assertEquals("Robot 36", c.modeSstv)
        assertEquals(s.debutMs, c.debutMs); assertEquals(s.finMs, c.finMs); assertFalse(c.aConfirmer)
        assertEquals("36 s on / 2 min off", c.horaire)
        assertEquals("https://ariss-usa.org/ARISS_SSTV/", c.lien)
        assertTrue(CampagneSstv.valide(c).isSuccess)
        assertEquals("", c.id)   // a draft: the owner saves it
        val r = ActivitesAVenir.rendezVous(s, MAINTENANT)
        assertEquals("SSTV", r.kind); assertEquals(437_550_000L, r.freqHz); assertTrue(r.isWindow)
        assertTrue(r.note.contains("Robot 36"))
        // A pass alert 10 min before AOS.
        val al = ActivitesAVenir.alertesPassages(listOf(s), { ActivitesAVenir.passages(it, p, 10.0, MAINTENANT) }, MAINTENANT, 10)
        assertEquals(t("2026-10-23T20:50:00Z"), al.single().quandMs)
    }

    // ------------------------------------------------------------------ what is signalled

    @Test fun signale_une_fois_et_la_premiere_fois_seulement_la_sstv() {
        val l = ActivitesAVenir.fusionne(LectureActivites.ariss(ressource("ariss-extrait.ics"), MAINTENANT - 2 * 86_400_000L))
        val pasDePassage: (Activite) -> List<SatPass> = { emptyList() }
        var m = ActivitesAVenir.Memoire()
        val n1 = ActivitesAVenir.aSignaler(m, l, MAINTENANT - 86_400_000L * 2, pasDePassage)
        assertEquals(listOf(Type.SSTV), n1.map { it.type })
        m = ActivitesAVenir.retiens(m, l, MAINTENANT)
        assertEquals(emptyList<Activite>(), ActivitesAVenir.aSignaler(m, l, MAINTENANT, pasDePassage))
        // Dates moved by 20 min: still the same.
        val bouge = l.map { it.copy(debutMs = it.debutMs + 20 * 60_000L) }
        assertEquals(emptyList<Activite>(), ActivitesAVenir.aSignaler(m, bouge, MAINTENANT, pasDePassage))
        // A new contact: only when it can be heard from here.
        val c = l.first { it.type == Type.CONTACT }.copy(debutMs = MAINTENANT + 86_400_000L, finMs = MAINTENANT + 86_400_000L + 600_000L)
        assertEquals(emptyList<Activite>(), ActivitesAVenir.aSignaler(m, listOf(c), MAINTENANT, pasDePassage))
        assertEquals(1, ActivitesAVenir.aSignaler(m, listOf(c), MAINTENANT) { listOf(pass(25544, "2026-10-24T20:01:00Z", 8, 30.0)) }.size)
        // The memory read back.
        val m2 = ActivitesAVenir.litMemoire(ActivitesAVenir.ecrit(m.copy(emises = setOf("x"))).toString())
        assertEquals(m.connues, m2.connues); assertTrue(m2.initialisee); assertEquals(setOf("x"), m2.emises)
    }

    @Test fun lrpt_signale_quand_sa_frequence_change() {
        val a = Activite("s", Type.LRPT, "METEOR-M2-4", 59051, MAINTENANT - 86_400_000L, MAINTENANT - 3_600_000L, freqHz = 137_900_000L,
            titre = "METEOR-M2-4 LRPT", sources = emptyList(), confiance = Confiance.VU_RECEMMENT)
        var m = ActivitesAVenir.retiens(ActivitesAVenir.Memoire(), listOf(a), MAINTENANT)
        val rien: (Activite) -> List<SatPass> = { emptyList() }
        assertEquals(emptyList<Activite>(), ActivitesAVenir.aSignaler(m, listOf(a), MAINTENANT, rien))
        val b = a.copy(freqHz = 137_100_000L)
        assertEquals(listOf(b), ActivitesAVenir.aSignaler(m, listOf(b), MAINTENANT, rien))
    }

    @Test fun ca_emet_maintenant_une_seule_fois() {
        val s = LectureActivites.ariss(ressource("ariss-extrait.ics"), MAINTENANT - 86_400_000L).first { it.type == Type.SSTV }
        val c = ActivitesAVenir.confirme(listOf(s), mapOf("ISS_[SSTV]" to LectureActivites.amsat(ressource("amsat-iss-sstv.json"))))
        val m = ActivitesAVenir.Memoire()
        assertEquals(1, ActivitesAVenir.emetMaintenant(m, c, MAINTENANT).size)
        assertEquals(0, ActivitesAVenir.emetMaintenant(m.copy(emises = setOf(ActivitesAVenir.cle(c[0]))), c, MAINTENANT).size)
        // Reports too old: not « now ».
        assertEquals(0, ActivitesAVenir.emetMaintenant(m, c, MAINTENANT + 5 * 3_600_000L).size)
        // Announced only: nothing.
        assertEquals(0, ActivitesAVenir.emetMaintenant(m, listOf(s), MAINTENANT).size)
    }

    @Test fun le_texte_html() {
        assertEquals("AOS: 10:39 UTC\nRX: 145.800 MHz - FM", LectureActivites.texte("AOS: 10:39 UTC&nbsp; <br>RX: <b>145.800</b> MHz - FM"))
        assertEquals("36 s on / 2 min off", LectureActivites.horaire("36 seconds on with 2 minutes off gap"))
        assertEquals("l'été", LectureActivites.texte("l&#8217;&eacute;t&#xe9;").replace('’', '\''))
    }
}
