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
import fr.f4ioz.satcombo.domain.CampagneSstv.Campagne
import fr.f4ioz.satcombo.domain.CampagneSstv.Statut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * A campaign's period across the end of summer time (25 Oct 2026 in Paris):
 * whole days announced by a news post, said in full, a period over, one pass
 * cancelled by hand; the owner's file read unchanged.
 */
class CampagnePeriodeTest {

    private val paris = ZoneId.of("Europe/Paris")
    private val m = 60_000L
    private fun ici(s: String) = LocalDateTime.parse(s).atZone(paris).toInstant().toEpochMilli()
    private fun utc(s: String) = LocalDateTime.parse(s).toInstant(ZoneOffset.UTC).toEpochMilli()

    private val src = ActivitesAVenir.Source("AMSAT-F", "https://site.amsat-f.org/")
    private fun rss(titre: String, corps: String, pub: String) = """<?xml version="1.0"?><rss><channel>
<item><title>$titre</title><link>https://site.amsat-f.org/2026/10/x/</link><pubDate>$pub</pubDate>
<description><![CDATA[$corps]]></description></item></channel></rss>"""

    @Test fun `des journees annoncees par un flux restent les memes jours ici, la fin pas le lendemain`() {
        // The post the owner's UMKA-1 campaign came from: « du 27 au 29 octobre 2026 », no time — read as UTC days.
        val l = LectureActivites.rss(rss("UMKA-1 :  Emission SSTV du 27 au 29 octobre 2026",
            "Annoncé sur Discord, une émission d’image SSTV est planifiée du 27 au 29 octobre 2026.", "Tue, 27 Oct 2026 09:16:57 +0000"),
            src, utc("2026-10-27T10:00"))
        val a = l.single()
        assertEquals(ActivitesAVenir.Precision.JOURS, a.precision)
        assertEquals(utc("2026-10-27T00:00"), a.debutMs)
        assertEquals(utc("2026-10-29T23:59"), a.finMs)
        // The old conversion (the local day of each instant): 23:59 UTC is 00:59 the next day in Paris — one day too long.
        val ancienne = java.time.Instant.ofEpochMilli(a.finMs).atZone(paris).toLocalDate().atTime(23, 59).atZone(paris).toInstant().toEpochMilli()
        assertEquals(ici("2026-10-30T23:59"), ancienne)
        // Now: the very days, 27/10 00:00 → 29/10 23:59 in Paris.
        val (de, fin) = ActivitesAVenir.periodeCampagne(a, a.debutMs, a.finMs, paris)
        assertEquals(ici("2026-10-27T00:00"), de)
        assertEquals(ici("2026-10-29T23:59"), fin)
        assertEquals("du mar. 27/10 00:00 au jeu. 29/10 23:59 (heure de Paris)", CampagneSstv.periodeTexte(de, fin, paris))
    }

    @Test fun `de part et d'autre du changement d'heure du 25 octobre`() {
        val a = ActivitesAVenir.Activite(id = "rss:x", type = ActivitesAVenir.Type.SSTV, satNom = "X", catnum = 1,
            debutMs = utc("2026-10-24T00:00"), finMs = utc("2026-10-26T23:59"), precision = ActivitesAVenir.Precision.JOURS, titre = "X", sources = listOf(src))
        val (de, fin) = ActivitesAVenir.periodeCampagne(a, a.debutMs, a.finMs, paris)
        // 24/10 00:00 summer time (UTC+2), 26/10 23:59 winter time (UTC+1).
        assertEquals(utc("2026-10-23T22:00"), de)
        assertEquals(utc("2026-10-26T22:59"), fin)
        assertEquals("du sam. 24/10 00:00 au lun. 26/10 23:59 (heure de Paris)", CampagneSstv.periodeTexte(de, fin, paris))
        // Known times are left as they are; unknown dates (a week from the post) are the days here.
        assertEquals(a.debutMs to a.finMs, ActivitesAVenir.periodeCampagne(a.copy(precision = ActivitesAVenir.Precision.EXACTE), a.debutMs, a.finMs, paris))
        val inc = ActivitesAVenir.periodeCampagne(a.copy(precision = ActivitesAVenir.Precision.INCONNUE), utc("2026-10-29T07:08"), utc("2026-11-05T07:08"), paris)
        assertEquals(ici("2026-10-29T00:00") to ici("2026-11-05T23:59"), inc)
        // West of UTC too: the same days.
        val ny = ZoneId.of("America/New_York")
        assertEquals("du sam. 24/10 00:00 au lun. 26/10 23:59 (heure de New York)",
            ActivitesAVenir.periodeCampagne(a, a.debutMs, a.finMs, ny).let { (d, f) -> CampagneSstv.periodeTexte(d, f, ny) })
        assertEquals("UTC", CampagneSstv.nomZone(ZoneOffset.UTC))
    }

    private fun campagne(de: Long, a: Long, id: String = "sakha") = Campagne(id = id, nom = "Emission SSTV – SAKHACUBE-CHOLBON (RS18S)",
        catnum = 67290, satNom = "SAKHACUBE-CHOLBON", emetteur = "SSTV", debutMs = de, finMs = a, aConfirmer = true, elMin = 10.0)

    private fun passe(aos: Long, el: Double = 28.0) = SatPass("SAKHACUBE-CHOLBON", 67290, aos, aos + 10 * m, el, 0.0, 0.0, false, false)

    @Test fun `une campagne terminee ne prevoit plus rien`() {
        // The owner's: 29/10 00:00 → 01/11 23:59 Paris; the 01/11 23:25 pass, then 02/11.
        val c = campagne(ici("2026-10-29T00:00"), ici("2026-11-01T23:59"))
        val l = listOf(passe(ici("2026-11-01T23:25")), passe(ici("2026-11-02T01:00")))
        val avant = CampagneSstv.plan(listOf(c), { l }, emptyList(), ici("2026-11-01T22:00"))
        assertEquals(listOf(Statut.PREVU), avant.map { it.statut })
        assertFalse(c.terminee(ici("2026-11-01T23:58")))
        assertTrue(c.terminee(ici("2026-11-01T23:59")))
        // Its period over: nothing more, not even a pass that would start after.
        assertTrue(CampagneSstv.plan(listOf(c), { l + passe(ici("2026-11-01T23:58")) }, emptyList(), ici("2026-11-02T00:00")).isEmpty())
        // A pass across the end (23:55 → 00:05): planned before, and once under way it goes on to its LOS.
        val tard = listOf(passe(ici("2026-11-01T23:55")))
        assertEquals(listOf(Statut.PREVU), CampagneSstv.plan(listOf(c), { tard }, emptyList(), ici("2026-11-01T23:56")).map { it.statut })
        assertTrue(CampagneSstv.plan(listOf(c), { tard }, emptyList(), ici("2026-11-02T00:01")).isEmpty())
        val fini = CampagneSstv.plan(listOf(c), { tard }, emptyList(), ici("2026-11-02T00:01"), c.id to tard[0].aosEpochMs)
        assertEquals(listOf(Statut.EN_COURS), fini.map { it.statut })
    }

    @Test fun `un passage annule a la main, la campagne continue`() {
        val c0 = campagne(ici("2026-10-31T00:00"), ici("2026-11-02T23:59"))
        val l = listOf(passe(ici("2026-11-01T23:25")), passe(ici("2026-11-02T01:00")))
        val c = c0.copy(annules = listOf(ici("2026-11-01T23:25") + 2 * m))
        val p = CampagneSstv.plan(listOf(c), { l }, emptyList(), ici("2026-11-01T22:00"))
        assertEquals(listOf(Statut.ANNULE, Statut.PREVU), p.map { it.statut })
        assertEquals("passage annulé à la main", p[0].raison)
        // Another campaign at the same time is not lost to a cancelled pass.
        val autre = campagne(ici("2026-10-31T00:00"), ici("2026-11-02T23:59"), id = "autre").copy(catnum = 2, annules = emptyList())
        val p2 = CampagneSstv.plan(listOf(c, autre), { k -> if (k.id == "autre") listOf(passe(ici("2026-11-01T23:28")).copy(catalogNumber = 2)) else l }, emptyList(), ici("2026-11-01T22:00"))
        assertEquals(Statut.PREVU, p2.first { it.campagne.id == "autre" }.statut)
        // The auto-pilot does not take it.
        val faits = ArrayList<String>()
        val station = object : PiloteSstv.Station {
            override fun emission(): String? = null
            override fun conflit(c: Campagne): String? = null
            override fun capture() = emptyMap<String, String>()
            override fun prepare(c: Campagne, p: SatPass) = PiloteSstv.Preparation(emptyList()).also { faits += "prepare" }
            override fun enregistre(c: Campagne): String? = null
            override fun arreteEnregistrement(): String = ""
            override fun images(deMs: Long, aMs: Long) = emptyList<String>()
            override fun parque(): String? = null
            override fun restaure(avant: Map<String, String>): List<String> = emptyList()
            override fun annonce(texte: String) {}
        }
        var t = ici("2026-11-01T23:24")
        val pilote = PiloteSstv(station, { t })
        pilote.tic(CampagneSstv.plan(listOf(c), { l }, emptyList(), t), true)
        assertTrue(faits.isEmpty())
        t = ici("2026-11-02T00:59")
        pilote.tic(CampagneSstv.plan(listOf(c), { l }, emptyList(), t), true)
        assertEquals(listOf("prepare"), faits)
        // Kept in its file, read back.
        val k = CampagneSstv.lisCampagne(CampagneSstv.json(c))!!
        assertEquals(c.annules, k.annules)
    }

    @Test fun `le fichier du proprietaire se lit tel quel`() {
        // As written before this version: no « annules », no « supprimees ».
        val texte = """{"v":1,"maitre":true,"campagnes":[{"id":"emission-sstv-sakhacube-cholbo","nom":"Emission SSTV – SAKHACUBE-CHOLBON (RS18S) a partir du du 29",
            "catnum":67290,"sat":"SAKHACUBE-CHOLBON","emetteur":"SSTV","freqHz":0,"modulation":"FM","largeurHz":15000,"mode":"Auto","de":1793228400000,"a":1793573940000,
            "aConfirmer":true,"elMin":10,"horaire":"","avantMin":2,"notes":"","lien":"","active":false,"cree":1793260000000,
            "activite":"rss:https://site.amsat-f.org/2026/10/29/emission-sstv-sakhacube-cholbon-rs18s-a-partir-du-du-29-octobre-2026/","activiteSuit":false,
            "chaine":{"profil":"","poste":true,"connecterPoste":false,"source":"poste","rotor":true,"connecterRotor":false,"enregistrer":true,"sstv":true,"annonce":true,"parquer":false,"ssdv":false}}],
            "historique":[{"campagne":"emission-sstv-sakhacube-cholbo","nom":"x","catnum":67290,"sat":"S","aos":1793564000000,"los":1793564600000,"el":20,"statut":"FAIT","raison":"","quand":1,"enregistrement":"a.wav","images":["i.png"],"faits":[]}],
            "enCours":null}"""
        val f = CampagneSstv.lit(texte)
        val c = f.campagnes.single()
        assertEquals(1793228400000L, c.debutMs)
        assertEquals(1793573940000L, c.finMs)
        assertEquals("du jeu. 29/10 00:00 au dim. 01/11 23:59 (heure de Paris)", CampagneSstv.periodeTexte(c.debutMs, c.finMs, paris))
        assertFalse(c.active); assertFalse(c.activiteSuit)
        assertTrue(c.annules.isEmpty()); assertTrue(f.supprimees.isEmpty())
        assertEquals(1, f.historique.size)
        // Written again and read back: the same, with the new fields.
        val g = CampagneSstv.lit(CampagneSstv.ecrit(f.copy(supprimees = listOf("rss:a"))).toString())
        assertEquals(f.campagnes, g.campagnes)
        assertEquals(listOf("rss:a"), g.supprimees)
    }
}
