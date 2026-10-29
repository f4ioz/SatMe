/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv

import fr.f4ioz.satcombo.datv.BalisesQo100.Cause
import fr.f4ioz.satcombo.datv.BalisesQo100.Ecoute
import fr.f4ioz.satcombo.datv.BalisesQo100.Flux
import fr.f4ioz.satcombo.datv.BalisesQo100.Large
import fr.f4ioz.satcombo.datv.BalisesQo100.Reception
import fr.f4ioz.satcombo.datv.BalisesQo100.Statut
import fr.f4ioz.satcombo.domain.Qo100
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** QO-100's beacons: the source told apart from here, the history kept, the narrow band beacons found. */
class BalisesQo100Test {

    /** The wide band beacon received for 30 s with MER 8 dB (margin 7 dB in QPSK 4/5), [flux] its stream. */
    private fun recue(flux: Flux, marge: Double? = 7.0, fec: Boolean? = false, ratio: Double? = 1.0, verrou: Boolean = true) =
        Reception(30_000L, verrou, if (verrou) 8.0 else null, if (verrou) marge else null, fec, ratio, flux)

    @Test fun `the case of the day - holes only on video and sound, the tables and FEC clean, MER fine - the source`() {
        // ~2 % continuity errors on the video/sound PIDs over 10 s, none on the tables, TEI 0, FEC clean.
        val f = Flux(paquetsEs = 9_000, ccEs = 180, paquetsAutres = 400, ccAutres = 0, tei = 0, videoMin = 40.0, imagesParS = 25.0)
        val a = BalisesQo100.large(Large(true, 8.5, recue(f), 20.0))
        assertEquals(Statut.FLUX_ABIME, a.statut)
        assertEquals(Cause.SOURCE, a.cause)
        assertTrue(a.texte, a.texte.contains("à la source") && a.texte.contains("pas chez vous") && a.texte.contains("MER 8,0 dB") && a.texte.contains("2,0 %"))
    }

    @Test fun `holes everywhere with a good signal - this PC or the network, not the beacon`() {
        val f = Flux(paquetsEs = 9_000, ccEs = 180, paquetsAutres = 400, ccAutres = 8, tei = 0)
        val a = BalisesQo100.large(Large(true, 8.5, recue(f, ratio = 0.9), null))
        assertEquals(Statut.PERTES_ICI, a.statut)
        assertEquals(Cause.ICI, a.cause)
        assertTrue(a.texte.contains("n’y est pour rien"))
    }

    @Test fun `holes with a thin margin, or the FEC failing - a weak signal here`() {
        val f = Flux(paquetsEs = 9_000, ccEs = 180, paquetsAutres = 400)
        assertEquals(Statut.FAIBLE, BalisesQo100.large(Large(true, 8.5, recue(f, marge = 0.4), null)).statut)
        val b = BalisesQo100.large(Large(true, 8.5, recue(f, fec = true), null))
        assertEquals(Statut.FAIBLE, b.statut); assertEquals(Cause.ICI, b.cause)
        assertEquals(Statut.FAIBLE, BalisesQo100.large(Large(true, 8.5, recue(f.copy(tei = 3)), null)).statut)
        // No holes, but a thin margin: still « faible », here.
        val c = BalisesQo100.large(Large(true, 8.5, recue(Flux(9_000, 0, 400, 0, imagesParS = 25.0), marge = 0.5), null))
        assertEquals(Statut.FAIBLE, c.statut); assertEquals(Cause.ICI, c.cause)
    }

    @Test fun `a whole stream and pictures - ok, a whole stream without a single picture - the encoder`() {
        val ok = BalisesQo100.large(Large(true, 8.5, recue(Flux(9_000, 1, 400, 0, imagesParS = 25.0)), null))
        assertEquals(Statut.OK, ok.statut)
        val muet = BalisesQo100.large(Large(true, 8.5, recue(Flux(9_000, 0, 400, 0, imagesParS = 0.0)), null))
        assertEquals(Statut.FLUX_ABIME, muet.statut); assertEquals(Cause.SOURCE, muet.cause)
        // Too soon to judge the pictures (10 s): fine.
        assertEquals(Statut.OK, BalisesQo100.large(Large(true, 8.5, recue(Flux(9_000, 0, 400, 0, imagesParS = 0.0)).copy(depuisMs = 12_000L), null)).statut)
    }

    @Test fun `no lock - the ground station's spectrum says where`() {
        val r = recue(Flux(), verrou = false)
        val absente = BalisesQo100.large(Large(false, null, r, 20.0))
        assertEquals(Statut.ABSENTE, absente.statut); assertEquals(Cause.SOURCE, absente.cause)
        assertTrue(absente.texte.contains("pas chez vous"))
        val ici = BalisesQo100.large(Large(true, 8.0, r, 20.0))
        assertEquals(Statut.FAIBLE, ici.statut); assertEquals(Cause.ICI, ici.cause)
        assertTrue(ici.texte.contains("chez vous"))
        // Without the spectrum, the narrow band heard well: probably the beacon, not sure.
        val p = BalisesQo100.large(Large(null, null, r, 20.0))
        assertEquals(Statut.ABSENTE, p.statut); assertEquals(Cause.INCONNUE, p.cause)
        assertEquals(Cause.INCONNUE, BalisesQo100.large(Large(null, null, r, null)).cause)
        // The first seconds on it: not judged.
        assertEquals(Statut.OK, BalisesQo100.large(Large(true, 8.0, r.copy(depuisMs = 4_000L), null)).statut)
    }

    @Test fun `not receiving it - only the spectrum, else nothing known`() {
        assertEquals(Statut.ABSENTE, BalisesQo100.large(Large(false, null, null, null)).statut)
        val ok = BalisesQo100.large(Large(true, 8.3, null, null))
        assertEquals(Statut.OK, ok.statut); assertTrue(ok.texte.contains("8,3 dB"))
        assertEquals(Statut.INCONNU, BalisesQo100.large(Large(null, null, null, null)).statut)
    }

    @Test fun `narrow band - one silent while the others come in is the source, all silent is here`() {
        val bonne = Ecoute(25.0, 12.0)
        assertEquals(Statut.INCONNU, BalisesQo100.etroites(false, emptyMap()).statut)
        assertEquals(Statut.INCONNU, BalisesQo100.etroites(true, emptyMap()).statut)
        assertEquals(Statut.OK, BalisesQo100.etroites(true, mapOf("basse" to bonne, "mediane" to bonne, "haute" to bonne)).statut)
        val une = BalisesQo100.etroites(true, mapOf("basse" to null, "mediane" to bonne, "haute" to bonne))
        assertEquals(Statut.ABSENTE, une.statut); assertEquals(Cause.SOURCE, une.cause)
        assertTrue(une.texte, une.texte.startsWith("Balise basse (CW) muette alors que les autres arrivent"))
        val toutes = BalisesQo100.etroites(true, mapOf("basse" to null, "mediane" to null))
        assertEquals(Statut.ABSENTE, toutes.statut); assertEquals(Cause.ICI, toutes.cause)
        assertEquals(Statut.FAIBLE, BalisesQo100.etroites(true, mapOf("mediane" to Ecoute(8.0, 0.0))).statut)
        // Only those in the SDR's view count: the upper one out of view is not « silent ».
        assertEquals(Statut.OK, BalisesQo100.etroites(true, mapOf("basse" to bonne, "mediane" to bonne)).statut)
    }

    @Test fun `narrow band beacons found in a panorama, out of view left out`() {
        val n = 8192
        val largeur = 2_400_000.0
        val centre = 10_489_750_000.0 + 300_000.0   // the upper beacon in view, the lower one too
        val db = FloatArray(n) { -90f + (it * 7 % 5) * 0.2f }
        fun raie(hz: Double, h: Float) { val i = Math.round(n / 2.0 + (hz - centre) / (largeur / n)).toInt(); for (k in i - 1..i + 1) db[k] = -90f + h * (if (k == i) 1f else 0.6f) }
        val lnb = 700.0   // the whole transponder 700 Hz high
        raie(Qo100.BALISE_BASSE_HZ + lnb, 30f); raie(Qo100.BALISE_MEDIANE_HZ + lnb, 35f)   // the upper one silent
        val l = BalisesQo100.ecoutes(db, centre, largeur)
        assertEquals(setOf("basse", "mediane", "haute"), l.keys)
        assertNull(l["haute"])
        assertEquals(700.0, l.getValue("mediane")!!.ecartHz, 150.0)
        assertEquals(700.0, l.getValue("basse")!!.ecartHz, 150.0)
        assertTrue(l.getValue("basse")!!.db > 20)
        // A narrow view around the middle beacon: the others out of it, no key.
        val hzBin = largeur / n
        val im = Math.round(n / 2.0 + (Qo100.BALISE_MEDIANE_HZ - centre) / hzBin).toInt()
        val etroit = BalisesQo100.ecoutes(db.copyOfRange(im - 200, im + 200), centre + (im - n / 2) * hzBin, hzBin * 400)
        assertEquals(setOf("mediane"), etroit.keys)
        assertEquals(700.0, etroit.getValue("mediane")!!.ecartHz, 150.0)
    }

    @Test fun `the stream window from two quality seconds, video and sound apart`() {
        fun pids(vararg l: Triple<Int, String, Pair<Long, Long>>) = JSONObject().put("pids", JSONArray(l.map {
            JSONObject().put("pid", it.first).put("role", it.second).put("paquets", it.third.first).put("cc", it.third.second) }))
        val avant = pids(Triple(0, "PAT", 10L to 0L), Triple(256, "H.265", 1000L to 2L), Triple(257, "AAC", 100L to 0L), Triple(0x1FFF, "vide", 500L to 0L)).put("totalTei", 1)
        val apres = pids(Triple(0, "PAT", 20L to 0L), Triple(256, "H.265", 9000L to 160L), Triple(257, "AAC", 900L to 20L), Triple(0x1FFF, "vide", 5000L to 0L))
            .put("totalTei", 1).put("videoMin", 12.5)
        val f = BalisesQo100.flux(avant, apres, 24.0)
        assertEquals(8800L, f.paquetsEs); assertEquals(178L, f.ccEs)
        assertEquals(10L, f.paquetsAutres); assertEquals(0L, f.ccAutres)
        assertEquals(0L, f.tei); assertEquals(12.5, f.videoMin, 1e-9); assertEquals(24.0, f.imagesParS!!, 1e-9)
        // A new reception (counts went down): from zero.
        val g = BalisesQo100.flux(apres, avant, null)
        assertEquals(1100L, g.paquetsEs)
        assertEquals(0L, BalisesQo100.flux(null, JSONObject(), null).paquetsEs)
    }

    @Test fun `a verdict counts once held 20 s, the first at once, the history kept and taken back`() {
        val h = HistoriqueBalises(confirmeMs = 20_000L, pasMs = 300_000L, jours = 7)
        var t = 1_800_000_000_000L
        val ok = BalisesQo100.Avis(Statut.OK, Cause.AUCUNE, "ok")
        val abime = BalisesQo100.Avis(Statut.FLUX_ABIME, Cause.SOURCE, "abîmé")
        assertNotNull(h.avis(t, "large", ok))
        // A flicker of 10 s: nothing.
        repeat(10) { t += 1000; assertNull(h.avis(t, "large", abime)) }
        t += 1000; assertNull(h.avis(t, "large", ok))
        // Held: once, after 20 s.
        var e: HistoriqueBalises.Evenement? = null
        repeat(25) { t += 1000; h.avis(t, "large", abime)?.let { e = it } }
        assertEquals(Statut.FLUX_ABIME, e!!.statut)
        assertEquals(Statut.FLUX_ABIME, h.courant("large")!!.first.statut)
        assertEquals(2, h.evenements().size)
        // Points every 5 min, the worst of each.
        repeat(700) { t += 1000; h.mesure(t, 8.0, 9.0, 21.0) }
        assertTrue(h.points().isNotEmpty())
        assertTrue(h.points().any { it.large == Statut.FLUX_ABIME && it.mer == 8.0 && it.sol == 9.0 && it.etroite == 21.0 })
        val h2 = HistoriqueBalises()
        h2.reprends(h.texte(), t)
        assertEquals(h.evenements(), h2.evenements())
        assertEquals(h.points(), h2.points())
        // Forgotten after the days kept.
        h2.reprends(h.texte(), t + 8 * 86_400_000L)
        assertTrue(h2.evenements().isEmpty() && h2.points().isEmpty())
        h2.reprends("pas du json", t)
    }
}
