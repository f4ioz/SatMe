/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.datv.BalisesQo100
import fr.f4ioz.satcombo.datv.BandeLarge
import fr.f4ioz.satcombo.datv.Datv
import fr.f4ioz.satcombo.datv.ReglageDatv
import fr.f4ioz.satcombo.datv.SecoursDatv
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The beacons' watch and the automatic scan on SatMe-PC, with a made-up wide band spectrum. */
class BalisesQo100PcTest {

    @get:Rule val tmp = TemporaryFolder()

    private var t = 1_800_000_000_000L
    private val stockage = StockageMemoire()
    private val datv by lazy { DatvPc(stockage, tmp.newFolder(), horloge = { t }) }
    /** The beacon on the ground station's spectrum, or not. */
    private var avecBalise = true

    /** A frame: the noise, the beacon (when [avecBalise]), a 500 kS/s and a 2 MS/s station. */
    private fun trame(): ByteArray {
        val n = 922
        val v = IntArray(n) { 11_000 + (it * 7919 % 400) }
        fun signal(c: Long, sr: Int, h: Int) {
            for (i in 0 until n) if (Math.abs(BandeLarge.khz(i.toDouble(), n) - c) < sr * 0.45) v[i] = 11_000 + h
        }
        if (avecBalise) signal(Datv.BALISE_KHZ, 1500, 27_000)
        signal(10_493_750L, 500, 22_000); signal(10_496_250L, 2000, 26_000)
        return ByteArray(2 * n) { i -> (if (i % 2 == 0) v[i / 2] and 0xFF else v[i / 2] shr 8).toByte() }
    }

    private val large by lazy {
        SpectreLargePc(stockage, datv, { t }, { var reste = 2; SpectreLargePc.Source({ if (reste-- > 0) trame() else null }, {}) }, { "pas ici" })
    }

    /** The spectrum switched on and read (fresh for 5 s). */
    private fun lisSpectre() {
        large.action("/api/datv/large/reglage", mapOf("on" to "1"))
        large.json(-1)
        large.boucle()
    }

    private val demandes = ArrayList<ReglageDatv>()
    private var refus: String? = "LongMynd introuvable"
    private fun balises(f: java.io.File? = tmp.root.resolve("balises.json")) =
        BalisesQo100Pc(stockage, datv, large, f, { t }, { demandes += it; refus }, {})

    @Test fun `off - nothing watched, nothing for the site`() {
        val b = balises()
        b.tic()
        assertNull(b.avisLarge)
        assertNull(b.siteJson())
        assertFalse(b.json().getBoolean("on"))
        assertFalse(large.voulu())
    }

    @Test fun `the beacon gone from the ground station's spectrum - an alert, at the source, back - said too`() {
        val b = balises()
        b.action(mapOf("on" to "1"))
        // The watch keeps the spectrum connected without a tab looking.
        large.garde = { b.on }
        avecBalise = false
        lisSpectre()
        b.tic()
        assertEquals(BalisesQo100.Statut.ABSENTE, b.avisLarge!!.statut)
        assertEquals(BalisesQo100.Cause.SOURCE, b.avisLarge!!.cause)
        val j = b.json(0)
        assertEquals("absente", j.getJSONObject("large").getString("statut"))
        val a = j.getJSONArray("alertes")
        assertEquals(1, a.length())
        assertTrue(a.getJSONObject(0).getString("texte"), a.getJSONObject(0).getString("texte").contains("pas chez vous"))
        assertTrue(a.getJSONObject(0).getBoolean("grave"))
        t += 11_000L
        assertTrue(large.voulu())
        // Back on the spectrum: after 20 s held, « revenue ».
        avecBalise = true
        repeat(25) { t += 1000L; lisSpectre(); b.tic() }
        assertEquals(BalisesQo100.Statut.OK, b.avisLarge!!.statut)
        val a2 = b.json(1).getJSONArray("alertes")
        assertEquals(1, a2.length())
        assertTrue(a2.getJSONObject(0).getString("texte").contains("revenue"))
        assertFalse(a2.getJSONObject(0).getBoolean("grave"))
        // The short view (another tab): no history.
        assertFalse(b.json(0, court = true).has("points"))
    }

    @Test fun `narrow band - a silent beacon while the others come in, a keyed one not taken for silent`() {
        val b = balises()
        b.action(mapOf("on" to "1"))
        var basse: BalisesQo100.Ecoute? = BalisesQo100.Ecoute(20.0, 5.0)
        b.etroites = { mapOf("basse" to basse, "mediane" to BalisesQo100.Ecoute(25.0, 3.0), "haute" to null) }
        b.tic()
        assertEquals(BalisesQo100.Statut.ABSENTE, b.avisEtroites!!.statut)
        assertTrue(b.avisEtroites!!.texte.startsWith("Balise haute muette"))
        // The lower one silent a few seconds (between two letters): still heard.
        basse = null
        repeat(10) { t += 1000L; b.tic() }
        assertTrue(b.avisEtroites!!.texte.startsWith("Balise haute muette"))
        val l = b.json().getJSONObject("etroites").getJSONArray("liste")
        assertEquals(20.0, l.getJSONObject(0).getDouble("db"), 1e-9)
        assertEquals(JSONObject.NULL, l.getJSONObject(2).get("db"))
        // Silent for good.
        repeat(40) { t += 1000L; b.tic() }
        assertTrue(b.avisEtroites!!.texte, b.avisEtroites!!.texte.startsWith("Balise basse (CW), balise haute muettes"))
    }

    @Test fun `the history kept on disk and taken back`() {
        val f = tmp.root.resolve("h/balises.json")
        val b = balises(f)
        b.action(mapOf("on" to "1"))
        avecBalise = false
        lisSpectre(); b.tic()
        assertTrue(f.isFile)
        val b2 = balises(f)
        assertEquals(1, b2.historique.evenements().size)
        assertEquals(BalisesQo100.Statut.ABSENTE, b2.historique.evenements()[0].statut)
        b2.action(mapOf("oublie" to "1"))
        assertTrue(b2.historique.evenements().isEmpty())
    }

    @Test fun `the site gets the verdicts only, within its keys`() {
        val b = balises()
        b.action(mapOf("on" to "1"))
        b.etroites = { mapOf("mediane" to BalisesQo100.Ecoute(25.0, 3.0)) }
        lisSpectre(); b.tic()
        val o = b.siteJson()!!
        assertEquals(BalisesQo100Pc.CLES_SITE.getValue(""), o.keySet())
        for (k in listOf("large", "etroites")) assertTrue(k + " " + o.getJSONObject(k).keySet(), BalisesQo100Pc.CLES_SITE.getValue(k).containsAll(o.getJSONObject(k).keySet()))
        assertEquals("ok", o.getJSONObject("large").getString("statut"))
        assertTrue(o.getJSONObject("large").has("niveauDb"))
        // Unknown narrow band: left out.
        assertFalse(BalisesQo100Pc.siteJson(BalisesQo100.Avis(BalisesQo100.Statut.OK, BalisesQo100.Cause.AUCUNE, "x"), null, null, null,
            BalisesQo100.Avis(BalisesQo100.Statut.INCONNU, BalisesQo100.Cause.AUCUNE, "y"), null).has("etroites"))
    }

    @Test fun `the check with the free MiniTiouner - only when chosen, not at once, its settings put back when refused`() {
        val b = balises()
        b.action(mapOf("on" to "1", "periode" to "30"))
        repeat(200) { t += 1000L; b.tic() }
        assertTrue(demandes.isEmpty())
        b.action(mapOf("controle" to "1"))
        val avant = datv.reglage
        repeat(100) { t += 1000L; b.tic() }
        assertTrue(demandes.isEmpty())                 // two minutes after it is chosen
        repeat(30) { t += 1000L; b.tic() }
        assertEquals(1, demandes.size)
        assertEquals(Datv.BALISE_KHZ, demandes[0].cielKhz)
        assertEquals(BalisesQo100.SR_KS, demandes[0].srKs)
        assertEquals(avant, datv.reglage)
        assertEquals("LongMynd introuvable", b.json().getJSONObject("sonde").getString("erreur"))
        // Not again before the period.
        repeat(600) { t += 1000L; b.tic() }
        assertEquals(1, demandes.size)
        // Not with a software receiver (it would take the SDR tab's).
        datv.recepteur = Datv.PLUTO
        repeat(1900) { t += 1000L; b.tic() }
        assertEquals(1, demandes.size)
    }

    // ------------------------------------------------------------------ the scan's PC side

    private fun secours() = SecoursDatvPc(stockage, datv, large, { t }, { demandes += it; refus })

    @Test fun `scan - the candidates from the spectrum, the favourites first, the beacon after`() {
        val s = secours()
        lisSpectre()
        s.action(mapOf("favori" to "1", "khz" to "10496250", "sr" to "2000", "nom" to "G0BBB"))
        val l = s.candidats(t, true)
        assertEquals(SecoursDatv.Origine.FAVORI, l[0].origine)
        assertEquals("G0BBB", l[0].cible.nom)
        assertEquals(true, l[0].present)
        assertEquals(SecoursDatv.Origine.BALISE, l[1].origine)
        assertEquals(true, l[1].present)
        assertEquals(setOf(500, 2000), l.drop(2).map { it.cible.srKs }.toSet())
        // Without a spectrum: the favourites and the beacon, presence unknown.
        val l2 = s.candidats(t, false)
        assertEquals(2, l2.size)
        assertNull(l2[1].present)
        // ★ off; out of the band refused.
        s.action(mapOf("favori" to "0", "khz" to "10496250", "sr" to "2000"))
        assertTrue(s.favoris.isEmpty())
        assertFalse(s.action(mapOf("favori" to "1", "khz" to "10400000", "sr" to "2000")).getBoolean("ok"))
    }

    @Test fun `scan - settings kept and bounded, off by default, a stopped receiver never started`() {
        val s = secours()
        assertFalse(s.on)
        s.action(mapOf("on" to "1", "mode" to "duree", "duree" to "5", "attente" to "20", "pause" to "15", "retour" to "0", "balise" to "0"))
        val s2 = secours()
        val r = s2.machine.reglages
        assertTrue(r.actif); assertEquals(SecoursDatv.Mode.DUREE, r.mode); assertEquals(10, r.dureeS); assertEquals(20, r.attenteS)
        assertEquals(15, r.pauseMin); assertFalse(r.retour); assertFalse(r.balise)
        repeat(120) { t += 1000L; s2.tic() }
        assertTrue(demandes.isEmpty())
        assertEquals("attente", s2.json().getString("phase"))
        // A tuning by hand: followed, the scan paused.
        s2.manuel(datv.reglage.copy(cielKhz = 10_494_750L, srKs = 1500))
        assertEquals(10_494_750L, s2.machine.prefere!!.khz)
        assertTrue(s2.machine.pauseJusquA > t)
    }
}
