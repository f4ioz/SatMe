/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.phonie

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FiltrePhonieTest {

    private fun m(t: String, lp: Double = -0.3, ns: Double = 0.05, c: Double = 1.3) = ProtocolePhonie.Morceau(0.0, 2.0, t, lp, ns, c)

    @Test fun `les phrases inventees sur le bruit`() {
        for (t in listOf("Merci d'avoir regardé cette vidéo !", "Sous-titres réalisés par la communauté d'Amara.org",
                "Thank you for watching.", "Untertitel im Auftrag des ZDF, 2021", "Subtítulos realizados por la comunidad de Amara.org",
                "Sottotitoli creati dalla comunità Amara.org", "Napisy stworzone przez społeczność Amara.org"))
            assertFalse(t, FiltrePhonie.garde(m(t)))
    }

    @Test fun `une vraie phrase reste`() {
        assertTrue(FiltrePhonie.garde(m("CQ CQ CQ de F4IOZ, Fox quatre India Oscar Zoulou, JN18")))
        assertTrue(FiltrePhonie.garde(m("Merci pour le contact, cinq neuf ici à Paris")))
        assertTrue(FiltrePhonie.garde(m("Merci", lp = -0.2, ns = 0.01)))
    }

    @Test fun `un mot seul peu sur est jete`() {
        assertFalse(FiltrePhonie.garde(m("Merci.", lp = -0.8, ns = 0.4)))
        assertFalse(FiltrePhonie.garde(m("Thank you.", ns = 0.3)))
    }

    @Test fun `les boucles`() {
        assertTrue(FiltrePhonie.boucle("Je vais. Je vais. Je vais. Je vais."))
        assertTrue(FiltrePhonie.boucle("ok ok ok"))
        assertFalse(FiltrePhonie.boucle("CQ CQ CQ"))
        assertFalse(FiltrePhonie.boucle("cinq neuf cinq neuf"))
        assertFalse(FiltrePhonie.garde(m("Oui, oui, oui, oui, oui.")))
    }

    @Test fun `confiance et silence`() {
        assertFalse(FiltrePhonie.garde(m("Bonjour à tous", ns = 0.9)))
        assertFalse(FiltrePhonie.garde(m("Bonjour à tous", lp = -1.5)))
        assertFalse(FiltrePhonie.garde(m("Bonjour à tous", lp = -0.9, ns = 0.7)))
        assertFalse(FiltrePhonie.garde(m("la la la la la la la la la", c = 3.0)))
        assertEquals(1, FiltrePhonie.garde(listOf(m("Bonjour"), m("Thanks for watching!"))).size)
    }

    @Test fun `normalise`() {
        assertEquals("sous titres realises par", FiltrePhonie.normalise("Sous-titres réalisés par…"))
        assertEquals("dziekuje", FiltrePhonie.normalise("Dziękuję!"))
        assertEquals("funf", FiltrePhonie.normalise("fünf"))
    }

    @Test fun `le protocole aller et retour`() {
        val pcm = FloatArray(1600) { (it % 100) / 100f - 0.5f }
        val t = ProtocolePhonie.trame(42, pcm, "fr", traduire = true)
        val (h, p) = ProtocolePhonie.litTrame(java.io.DataInputStream(t.inputStream()))!!
        assertEquals(42L, h.getLong("id")); assertEquals("fr", h.getString("langue")); assertTrue(h.getBoolean("traduire"))
        assertEquals(1600, p.size); assertEquals(pcm[150], p[150], 1e-4f)
        assertNull(ProtocolePhonie.litTrame(java.io.DataInputStream(ByteArray(0).inputStream())))
    }

    @Test fun `le protocole la traduction`() {
        val pcm = FloatArray(160)
        // No language asked: the header as before (Whisper's English).
        val (h0, _) = ProtocolePhonie.litTrame(java.io.DataInputStream(ProtocolePhonie.trame(1, pcm, "", traduire = true).inputStream()))!!
        assertFalse(h0.has("cible"))
        val (h, _) = ProtocolePhonie.litTrame(java.io.DataInputStream(ProtocolePhonie.trame(2, pcm, "", traduire = true, cible = "fr").inputStream()))!!
        assertEquals("fr", h.getString("cible"))
        val r = ProtocolePhonie.lit("""{"id":2,"langue":"pl","proba":0.9,"calcul_s":0.5,"segments":[{"debut":0,"fin":2,"texte":"Dzień dobry","avg_logprob":-0.2,"no_speech_prob":0.01,"traduction":"Bonjour"}],"traduction":"Bonjour","langue_cible":"fr","traduction_s":0.05}""")
            as ProtocolePhonie.Message.Reponse
        assertEquals("fr", r.langueCible); assertEquals("Bonjour", r.morceaux.single().traduction); assertEquals(0.05, r.traductionS, 1e-9)
        val m = ProtocolePhonie.lit("""{"id":3,"langue":"pl","proba":0.9,"calcul_s":0.5,"segments":[],"traduction":"","langue_cible":"","traduction_erreur":"traduction pl → fr non installée"}""")
            as ProtocolePhonie.Message.Reponse
        assertEquals("", m.langueCible); assertTrue(m.traductionErreur.contains("non installée"))
        // An older helper: its translation was English.
        val v = ProtocolePhonie.lit("""{"id":4,"langue":"pl","proba":0.9,"calcul_s":0.5,"segments":[],"traduction":"Good morning"}""") as ProtocolePhonie.Message.Reponse
        assertEquals("en", v.langueCible)
        // A bubble kept before: the same.
        val vieille = org.json.JSONObject("""{"id":1,"t":1,"duree":2,"src":"carte","freq":null,"sat":"","langue":"pl","proba":0.9,"texte":"Dzień dobry","traduction":"Good morning","conf":0.8,"calcul":0.3,"elements":[]}""")
        assertEquals("en", BullePhonie.depuis(vieille).langueCible)
        val b = BullePhonie.depuis(vieille).copy(traduction = "Bonjour", langueCible = "fr")
        assertEquals(b, BullePhonie.depuis(b.json()))
    }

    @Test fun `le protocole les messages`() {
        val r = ProtocolePhonie.lit("""{"id":7,"langue":"pl","proba":0.97,"calcul_s":0.4,"segments":[{"debut":0,"fin":2.5,"texte":" Dzień dobry","avg_logprob":-0.2,"no_speech_prob":0.01}],"traduction":"Good morning"}""")
        r as ProtocolePhonie.Message.Reponse
        assertEquals(7L, r.id); assertEquals("pl", r.langue); assertEquals("Dzień dobry", r.texte); assertEquals("Good morning", r.traduction)
        val p = ProtocolePhonie.lit("""{"pret":true,"modele":"turbo","calcul":"cpu","type":"int8","charge_s":2.0}""")
        assertEquals("cpu", (p as ProtocolePhonie.Message.Pret).calcul)
        val e = ProtocolePhonie.lit("""{"erreur":"modèle absent","fatal":true}""")
        assertTrue((e as ProtocolePhonie.Message.Erreur).fatale)
        assertTrue(ProtocolePhonie.lit("bavardage d'une bibliothèque") is ProtocolePhonie.Message.Autre)
    }

    @Test fun `la bulle en json et retour`() {
        val el = AnalysePhonie.analyse("de F4IOZ cinq neuf JN18", "fr")
        val b = BullePhonie(3, 1_700_000_000_000, 4.2, "sdr", 10_489_750_000, "QO-100", "fr", 0.99, "de F4IOZ cinq neuf JN18", "", 0.8, 0.3, el)
        val c = BullePhonie.depuis(b.json())
        assertEquals(b.copy(dureeS = 4.2), c)
    }

    @Test fun `la file jette le plus ancien`() {
        val f = FilePhonie<Int>(3)
        (1..5).forEach { f.ajoute(it) }
        assertEquals(2L, f.perdus)
        assertEquals(3, f.prend(10)); assertEquals(listOf(4, 5), f.tous())
        f.vide(); assertNull(f.prend(5))
    }
}
