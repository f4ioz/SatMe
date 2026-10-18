/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.annonces

import fr.f4ioz.satcombo.annonces.Annonces.Evenement
import fr.f4ioz.satcombo.annonces.Annonces.Sorte
import fr.f4ioz.satcombo.annonces.DetecteurAnnonces.Instantane
import fr.f4ioz.satcombo.annonces.DetecteurAnnonces.Passage
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnoncesTest {

    // ------------------------------------------------------------ reading

    @Test fun nombresEnFrancais() {
        val L = LectureAnnonce
        assertEquals("zéro", L.nombre(0))
        assertEquals("seize", L.nombre(16))
        assertEquals("dix-sept", L.nombre(17))
        assertEquals("vingt et un", L.nombre(21))
        assertEquals("quarante-cinq", L.nombre(45))
        assertEquals("soixante et onze", L.nombre(71))
        assertEquals("soixante-dix-neuf", L.nombre(79))
        assertEquals("quatre-vingts", L.nombre(80))
        assertEquals("quatre-vingt-un", L.nombre(81))
        assertEquals("quatre-vingt-onze", L.nombre(91))
        assertEquals("cent", L.nombre(100))
        assertEquals("cent un", L.nombre(101))
        assertEquals("deux cents", L.nombre(200))
        assertEquals("deux cent trente", L.nombre(230))
        assertEquals("mille", L.nombre(1000))
        assertEquals("deux mille quatre cent quatre-vingt-neuf", L.nombre(2489))
        assertEquals("quatre-vingt mille", L.nombre(80_000))
        assertEquals("deux cent mille", L.nombre(200_000))
        assertEquals("un million", L.nombre(1_000_000))
        assertEquals("moins douze", L.nombre(-12))
    }

    @Test fun indicatifsEpeles() {
        assertEquals("Fox-trot quatre India Oscar Zoulou", LectureAnnonce.indicatif("f4ioz"))
        assertEquals("Delta Lima un Alfa Bravo Charlie barre Papa", LectureAnnonce.indicatif("DL1ABC/P"))
        assertEquals("F quatre I O Z", LectureAnnonce.indicatif("F4IOZ", phonetique = false))
        assertEquals(26, LectureAnnonce.OACI.size)
    }

    @Test fun frequencesChiffreParChiffre() {
        assertEquals("dix quatre huit neuf, virgule, sept cinq", LectureAnnonce.frequence(10_489_750_000L))
        assertEquals("un quatre cinq, virgule, huit", LectureAnnonce.frequence(145_800_000L))
        assertEquals("quatre trois cinq", LectureAnnonce.frequence(435_000_000L))
        // 2 400,100 MHz: the GHz as a number, then the MHz digit by digit.
        assertEquals("deux quatre zéro zéro, virgule, un", LectureAnnonce.frequence(2_400_100_000L))
    }

    @Test fun pasEtSatellites() {
        assertEquals("cent hertz", LectureAnnonce.pasHz(100))
        assertEquals("un kilohertz", LectureAnnonce.pasHz(1000))
        assertEquals("A O quatre-vingt-onze", LectureAnnonce.satellite("AO-91"))
        assertEquals("ISS", LectureAnnonce.satellite("ISS"))
    }

    // ------------------------------------------------------------ sentences

    @Test fun phrasesSelonLeDetail() {
        val r = Annonces.Reglages(on = true)
        val e = Evenement(Sorte.AOS_PROCHE, "ISS", nombre = 2, elevation = 45)
        assertEquals("ISS dans deux minutes, élévation maximale quarante-cinq degrés", Annonces.texte(e, r))
        assertEquals("ISS dans deux minutes", Annonces.texte(e, r.copy(detail = Annonces.Detail.COURT)))
        assertEquals("LEILA : trop de puissance", Annonces.texte(Evenement(Sorte.LEILA), r))
        assertEquals("Fox-trot quatre India Oscar Zoulou appelé par Delta Lima un Alfa Bravo Charlie",
            Annonces.texte(Evenement(Sorte.APPEL, "DL1ABC", mode = "FT8", nombre = -12), r, "F4IOZ"))
        assertEquals("Fox-trot quatre India Oscar Zoulou appelé par Delta Lima un Alfa Bravo Charlie, en FT8, moins douze décibels",
            Annonces.texte(Evenement(Sorte.APPEL, "DL1ABC", mode = "FT8", nombre = -12), r.copy(detail = Annonces.Detail.DETAILLE), "F4IOZ"))
        assertEquals("pas cent hertz", Annonces.texte(Evenement(Sorte.MOLETTE, hz = 100), r))
        assertEquals("Émission DATV en cours, dix quatre huit neuf, virgule, sept cinq mégahertz",
            Annonces.texte(Evenement(Sorte.EMISSION, "DATV", hz = 10_489_750_000L), r.copy(detail = Annonces.Detail.DETAILLE)))
        assertEquals("Fin d'émission SSTV", Annonces.texte(Evenement(Sorte.EMISSION, "SSTV", debut = false), r))
    }

    @Test fun reglagesGardes() {
        val s = StockageMemoire()
        assertFalse(Annonces.lit(s).on)
        assertTrue(Sorte.AOS in Annonces.lit(s).sortes)
        assertFalse(Sorte.MOLETTE in Annonces.lit(s).sortes)
        val r = Annonces.Reglages(on = true, sortes = setOf(Sorte.LEILA), detail = Annonces.Detail.COURT, sortie = Annonces.Sortie.LES_DEUX, vitesse = 1.3, baisse = 50)
        Annonces.ecrit(s, r)
        val l = Annonces.lit(s)
        assertEquals(r.copy(vitesse = l.vitesse), l)
        assertEquals(1.3, l.vitesse, 1e-6)
        assertTrue(l.veut(Sorte.LEILA)); assertFalse(l.veut(Sorte.AOS))
        assertFalse(l.copy(muet = true).veut(Sorte.LEILA))
    }

    // ------------------------------------------------------------ rate limit

    @Test fun jamaisDeuxFoisEn30sEtTroisAuPlus() {
        var t = 0L
        val f = FileAnnonces { t }
        assertTrue(f.propose(Sorte.LEILA, "LEILA") != null)
        assertNull(f.propose(Sorte.LEILA, "LEILA"))
        t = 29_000; assertNull(f.propose(Sorte.LEILA, "LEILA"))
        t = 30_000; assertTrue(f.propose(Sorte.LEILA, "LEILA") != null)
        // The essay goes through regardless.
        assertTrue(f.propose(Sorte.ESSAI, "LEILA") != null)
        f.vide()
        listOf("a", "b", "c", "d", "e").forEach { f.propose(Sorte.SSTV, it) }
        assertEquals(3, f.taille())
        assertEquals("c", f.prochaine()!!.texte)
        assertEquals(2, f.debordees)
    }

    // ------------------------------------------------------------ detection

    private val min = 60_000L

    @Test fun passageAnnonceAvantPendantApres() {
        val d = DetecteurAnnonces()
        val p = Passage("ISS", 10 * min, 20 * min, 45)
        fun a(t: Long) = d.compare(Instantane(t, passage = p), 2, 5).map { it.sorte }
        assertTrue(a(0).isEmpty())
        assertTrue(a(7 * min).isEmpty())
        assertEquals(listOf(Sorte.AOS_PROCHE), a(8 * min))
        assertTrue(a(8 * min + 1000).isEmpty())
        assertEquals(listOf(Sorte.AOS), a(10 * min + 500))
        assertTrue(a(15 * min).isEmpty())
        // Passes computed again: the AOS moved a few seconds, nothing said twice.
        assertTrue(d.compare(Instantane(16 * min, passage = p.copy(aosMs = p.aosMs + 4000)), 2, 5).isEmpty())
        assertEquals(listOf(Sorte.LOS), a(20 * min + 200))
        assertTrue(a(21 * min).isEmpty())
        val e = d.compare(Instantane(28 * min, passage = Passage("ISS", 30 * min, 40 * min, 12)), 2, 5)
        assertEquals(Sorte.AOS_PROCHE, e.single().sorte); assertEquals(2, e.single().nombre); assertEquals(12, e.single().elevation)
    }

    @Test fun demarreEnPleinPassageSansRienDire() {
        val d = DetecteurAnnonces()
        val p = Passage("SO-50", 10 * min, 20 * min, 30)
        assertTrue(d.compare(Instantane(12 * min, passage = p, emissions = mapOf("DATV" to null)), 2, 5).isEmpty())
        // The LOS of the pass under way is still said.
        assertEquals(listOf(Sorte.LOS), d.compare(Instantane(20 * min + 1, passage = p, emissions = mapOf("DATV" to null)), 2, 60).map { it.sorte })
    }

    @Test fun emissionEtSonRappel() {
        val d = DetecteurAnnonces()
        fun a(t: Long, e: Map<String, Long?>) = d.compare(Instantane(t, emissions = e), 2, 5)
        a(0, emptyMap())
        val debut = a(1000, mapOf("DATV" to 10_499_250_000L)).single()
        assertEquals(Sorte.EMISSION, debut.sorte); assertTrue(debut.debut); assertEquals(10_499_250_000L, debut.hz)
        assertTrue(a(4 * min, mapOf("DATV" to null)).isEmpty())
        val r = a(5 * min + 1000, mapOf("DATV" to null)).single()
        assertEquals(Sorte.RAPPEL_EMISSION, r.sorte); assertEquals(5, r.nombre)
        assertTrue(a(6 * min, mapOf("DATV" to null)).isEmpty())
        val fin = a(7 * min, emptyMap()).single()
        assertEquals(Sorte.EMISSION, fin.sorte); assertFalse(fin.debut)
    }

    @Test fun etatsQuiBasculent() {
        val d = DetecteurAnnonces()
        var i = Instantane(0, moi = "F4IOZ", datvActif = true, rotorConnecte = true, sdrActif = true, molettePasHz = 100,
            decodes = listOf(DetecteurAnnonces.Decode("a", "DL1ABC", "F4IOZ", "FT8")))
        assertTrue(d.compare(i, 2, 5).isEmpty())   // heard before: not said
        i = i.copy(ms = 1000, leila = true, sstvGardees = 1, datvVerrou = true, datvIndicatif = "G4XYZ", molettePasHz = 1000,
            avions = mapOf("AFR123" to 3), rotorButee = true,
            decodes = i.decodes + DetecteurAnnonces.Decode("b", "EA4ABC", "F4IOZ/P", "FT4", -5) + DetecteurAnnonces.Decode("c", "EA4ABC", "DL1ABC", "FT8"))
        val s = d.compare(i, 2, 5)
        assertEquals(setOf(Sorte.LEILA, Sorte.SSTV, Sorte.DATV_VERROU, Sorte.DATV_STATION, Sorte.MOLETTE, Sorte.AVION, Sorte.ROTOR, Sorte.APPEL),
            s.map { it.sorte }.toSet())
        assertEquals("EA4ABC", s.single { it.sorte == Sorte.APPEL }.sujet)
        // Nothing changed: nothing said; the DATV station not again within 10 minutes.
        assertTrue(d.compare(i.copy(ms = 2000), 2, 5).isEmpty())
        val fin = d.compare(i.copy(ms = 3000, sdrActif = false, sdrErreur = "rtl_tcp a coupé la liaison", rotorConnecte = false, datvVerrou = false), 2, 5)
        assertEquals(listOf(Sorte.DATV_VERROU, Sorte.ROTOR, Sorte.SDR), fin.map { it.sorte })
        // The SDR stopped by the operator (no error): not announced.
        val d2 = DetecteurAnnonces()
        d2.compare(Instantane(0, sdrActif = true), 2, 5)
        assertTrue(d2.compare(Instantane(1000, sdrActif = false), 2, 5).isEmpty())
    }

    @Test fun appelEnCw() {
        assertEquals("DL1ABC", DetecteurAnnonces.appelCw("CQ CQ F4IOZ DE DL1ABC K", "F4IOZ"))
        assertEquals("DL1ABC", DetecteurAnnonces.appelCw("F4IOZ F4IOZ DE DL1ABC DL1ABC", "F4IOZ"))
        assertNull(DetecteurAnnonces.appelCw("CQ CQ DE F4IOZ K", "F4IOZ"))
        assertNull(DetecteurAnnonces.appelCw("XF4IOZ DE DL1ABC", "F4IOZ"))
        assertEquals("F4IOZ", DetecteurAnnonces.base("EA8/F4IOZ/P"))
    }
}
