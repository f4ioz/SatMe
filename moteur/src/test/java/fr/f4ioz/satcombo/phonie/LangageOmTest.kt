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
import org.junit.Assert.assertTrue
import org.junit.Test

class LangageOmTest {

    private fun fr(t: String) = LangageOm.corrige(t, "fr")
    private fun en(t: String) = LangageOm.corrige(t, "en")
    private fun es(t: String) = LangageOm.corrige(t, "es")

    /** The callsigns [AnalysePhonie] finds in the text written. */
    private fun indicatifs(t: String, langue: String) = AnalysePhonie.indicatifs(AnalysePhonie.analyse(LangageOm.corrige(t, langue), langue)).map { it.valeur }

    // ------------------------------------------------------------------ spellings

    @Test fun `un indicatif epele en anglais`() {
        assertEquals("CQ CQ de F4IOZ, over.", en("CQ CQ de Fox-trot Four India Oscar Zulu, over."))
        assertEquals("This is F4IOZ.", en("This is Foxtrot Four India Oscar Zulu."))
        assertEquals("F4IOZ", en("Foxtrot fower India Oscar Zulu"))
        assertEquals("F5SIM", en("Fox trot five Sierra India Mike"))
    }

    @Test fun `un indicatif epele en francais avec ses variantes`() {
        assertEquals("ici F4IOZ, à vous", fr("ici Foxtrot 4 India Oscar Zoulou, à vous"))
        assertEquals("ici F4IOZ", fr("ici Fox quatre Italie Oscar Zoulou"))
        assertEquals("F0SIM", fr("Fox-trot zéro Sierra India Mike"))
        // Whisper's commas between the words.
        assertEquals("de F5SIM.", fr("de Foxtrot, cinq, Sierra, India, Mike."))
    }

    @Test fun `les chiffres de l'aviation`() {
        assertEquals("F9SIM", en("Foxtrot niner Sierra India Mike"))
        assertEquals("F3SIM", en("Foxtrot tree Sierra India Mike"))
        assertEquals("F5SIM", en("Foxtrot fife Sierra India Mike"))
    }

    @Test fun `les variantes d'orthographe de whisper`() {
        assertEquals("JXWAQZ", en("Juliet X-ray Whisky Alpha Quebec Zulu"))
        assertEquals("JXWAQZ", fr("Juliette Xray Whiskey Alfa Québec Zoulou"))
        assertEquals("XL", en("X ray Lima"))
        assertEquals("ML", en("Mike Lima"))
    }

    @Test fun `en espagnol`() {
        assertEquals("aquí EA5SIM, cambio", es("aquí Eco Alfa cinco Sierra India Mike, cambio"))
        assertEquals("JN", es("Julieta Noviembre"))
        assertEquals("reporte 59", es("reporte cinco nueve"))
    }

    @Test fun `des lettres seules`() {
        assertEquals("CQ", fr("Charlie Québec"))
        assertEquals("ABC", en("Alpha Bravo Charlie"))
        assertEquals("CQ CQ", fr("Charlie Québec Charlie Québec"))
        assertEquals("CQ DX", en("Charlie Quebec Delta X-ray"))
    }

    @Test fun `un carre locator`() {
        assertEquals("locator JN18", en("locator Juliett November one eight"))
        assertEquals("carré JN18", fr("carré Juliette Novembre un huit"))
        assertEquals("JN18FB", en("Juliett November one eight Foxtrot Bravo"))
    }

    @Test fun `un seul element colle a un indicatif`() {
        assertEquals("F5AB", fr("F5A Bravo"))
        assertEquals("F4IOZ", fr("F4IO Zoulou"))
        assertEquals("F4IOZ", en("Fox 4 IOZ"))
        // Not a fifth letter for a callsign said whole.
        assertEquals("F4IOZ Charlie", fr("F4IOZ Charlie"))
        // Written apart by the engine.
        assertEquals("de F4IOZ", fr("de F 4 I O Z"))
        assertEquals("de F4IOZ", fr("de F4 IOZ"))
    }

    @Test fun `les indicatifs portables`() {
        assertEquals("QA1BC/P", en("Quebec Alpha one Bravo Charlie stroke Papa"))
        assertEquals("QA1BC/P", fr("Québec Alpha un Bravo Charlie portable"))
        assertEquals("F/QA1BC", en("Foxtrot stroke Quebec Alpha one Bravo Charlie"))
        assertEquals("F/QA1BC", fr("Fox barre Québec Alpha un Bravo Charlie"))
        assertEquals("F5SIM/P", fr("F5SIM portable"))
        assertEquals("EA8/F5SIM", en("Echo Alfa eight slash Foxtrot five Sierra India Mike"))
    }

    @Test fun `tel que whisper l'a ecrit`() {
        // turbo on a synthetic French voice, without and with the prompt.
        assertEquals("CQ, CQ de F4IOZ. Report 59. QSL, 73 à vous.", fr("CQ, CQ de Fox Toro 4 India Oscar Zoulou. Report 5-9. Kiselle, 73 à vous."))
        assertEquals("CQ, CQ de F4IOZ. Report 59. QSL 73, à vous.", fr("CQ, CQ de Foxtrot quatre India Oscar Zulu. Report 59. QSL 73, à vous."))
        assertEquals("Je vous passe 59, mon locator JN18.", fr("Je vous passe 5-9, mon locator Juliette, novembre 1-8."))
        // Garbled (« cinq Sierra » heard « Saint-Syrin »): no callsign made up, two letters stay two letters.
        assertEquals("ici Foxtrot, Saint-Syrin IM Portable.", fr("ici Foxtrot, Saint-Syrin India Mic Portable."))
    }

    // ------------------------------------------------------------------ what stays a word

    @Test fun `pas un mot seul dans une phrase`() {
        for (t in listOf("Je suis à l'hôtel ce soir.", "un hôtel", "Charlie est arrivé hier.", "Victor Hugo est né à Besançon.",
            "J'ai acheté un kilo de pommes.", "Tango argentin.", "Il habite à Lima.", "Mon papa, mon oscar.", "Bravo, Charlie !",
            "Roméo et Juliette", "Bravo bravo !", "une Alfa Romeo rouge", "le 3 novembre 2026", "Delta 2026", "deux trois personnes"))
            assertEquals(t, fr(t))
        for (t in listOf("Thank you, Victor.", "Golf is fun.", "I am at the hotel.", "so 50 people came", "one hotel"))
            assertEquals(t, en(t))
    }

    @Test fun `aucun faux indicatif`() {
        assertEquals(emptyList<String>(), indicatifs("Victor Hugo a écrit les Misérables, un hôtel à Lima", "fr"))
        // A made-up prefix (Q is no country): written, but not a callsign.
        assertEquals("QA1BC", en("Quebec Alpha one Bravo Charlie"))
        assertEquals(emptyList<String>(), indicatifs("Quebec Alpha one Bravo Charlie stroke Papa", "en"))
        // With a country's prefix before it: a callsign.
        assertEquals(listOf("F/QA1BC"), indicatifs("Foxtrot stroke Quebec Alpha one Bravo Charlie", "en"))
    }

    @Test fun `les indicatifs trouves`() {
        assertEquals(listOf("F4IOZ"), indicatifs("CQ de Fox-trot four India Oscar Zulu", "en"))
        assertEquals(setOf("F5SIM", "EA5SIM"), indicatifs("Eco Alfa cinco Sierra India Mike de Foxtrot cinco Sierra India Mike", "es").toSet())
        assertEquals(listOf("F0SIM/P"), indicatifs("ici F0SIM portable", "fr"))
    }

    // ------------------------------------------------------------------ reports

    @Test fun `les reports en chiffres`() {
        assertEquals("report 59", fr("report cinq neuf"))
        assertEquals("vous êtes 59 ici", fr("vous êtes cinq neuf ici"))
        assertEquals("59", fr("cinq sur neuf"))
        assertEquals("you are 59", en("you are five nine"))
        assertEquals("you are 59", en("you are five by nine"))
        assertEquals("599", en("five nine nine"))
        // Written apart by the engine.
        assertEquals("59", en("5 9"))
        // After « report », any: 57, 44.
        assertEquals("rapport 44", fr("rapport quatre quatre"))
        // Not a report: two figures in a sentence.
        assertEquals("à quatre quatre", fr("à quatre quatre"))
    }

    // ------------------------------------------------------------------ the Q code and the usual words

    @Test fun `le code Q`() {
        assertEquals("QRZ ?", fr("Q.R.Z. ?"))
        assertEquals("QRZ", en("Q R Z"))
        assertEquals("QSL", fr("cul est sel"))
        assertEquals("QSL", fr("cul est elle"))
        assertEquals("QSL", en("cue ess ell"))
        assertEquals("QSO", fr("Q-S-O"))
        assertEquals("QTH", en("q t h"))
        assertEquals("QSY sur 145.500", fr("qsy sur 145.500"))
        assertEquals("QRZ, QSL, QRM", en("qrz, Qsl, QRM"))
        // A Q and a word: nothing.
        assertEquals("Q de quoi", fr("Q de quoi"))
        assertEquals("qui est là", fr("qui est là"))
    }

    @Test fun `les mots du trafic`() {
        assertEquals("73 et 88", fr("soixante-treize et quatre-vingt-huit"))
        assertEquals("73", en("seventy-three"))
        assertEquals("73", es("setenta y tres"))
        assertEquals("un pile-up en DX", fr("un pile up en dx"))
        assertEquals("roger", en("rodger"))
        assertEquals("sur QO-100", fr("sur QO 100"))
        assertEquals("sur QO-100", fr("sur Q.O. 100"))
        assertEquals("AO-91 et RS-44 puis SO-50", fr("AO 91 et RS 44 puis SO-50"))
        assertEquals("en SSTV et APRS", fr("en sstv et aprs"))
        assertEquals("de F5SIM", fr("de f5sim"))
        assertEquals("en FT8", fr("en ft8"))
    }

    // ------------------------------------------------------------------ the personal vocabulary

    @Test fun `le vocabulaire personnel`() {
        val v = LangageOm.litVocabulaire("# un commentaire\n\nRadio-Club de la Sarthe\nQA5SIM\n  aude cent => QO-100\nseulement =>\n")
        assertEquals(listOf("Radio-Club de la Sarthe", "QA5SIM"), v.mots)
        assertEquals(listOf("aude cent" to "QO-100", "seulement" to ""), v.corrections)
        assertEquals("le Radio-Club de la Sarthe sur QO-100", LangageOm.corrige("le radio club de la sarthe sur Aude cent", "fr", v))
        assertEquals("de QA5SIM", LangageOm.corrige("de qa5sim", "fr", v))
        // The template: nothing but comments.
        assertEquals(LangageOm.Vocabulaire.VIDE, LangageOm.litVocabulaire(LangageOm.MODELE))
        // A correction with regex characters is taken literally.
        assertEquals("c'est ok", LangageOm.corrige("c'est (x)+", "", LangageOm.litVocabulaire("(x)+ => ok")))
    }

    // ------------------------------------------------------------------ before listening

    @Test fun `le prompt selon la langue`() {
        val p = LangageOm.prompt("fr", "F4IOZ")
        assertTrue(p, p.contains("F4IOZ") && p.contains("Foxtrot quatre India Oscar Zulu") && p.contains("QSL") && p.contains("à vous"))
        assertTrue(LangageOm.prompt("en", "F4IOZ").contains("Foxtrot four India Oscar Zulu"))
        assertTrue(LangageOm.prompt("es", "").contains("cambio"))
        // Language unknown: the codes alone, which every language has.
        val n = LangageOm.prompt("", "F4IOZ")
        assertTrue(n, n.startsWith("CQ CQ de F4IOZ"))
        assertFalse(n.contains("à vous") || n.contains("over"))
        // The user's words, and never too long.
        assertTrue(LangageOm.prompt("fr", "", LangageOm.Vocabulaire(listOf("Radio-Club de la Sarthe"))).contains("Radio-Club de la Sarthe"))
        assertTrue(LangageOm.prompt("fr", "F4IOZ", LangageOm.Vocabulaire(List(100) { "mot$it" })).length <= LangageOm.MAX_PROMPT)
        assertEquals("CQ QRZ QSL QSO QTH QSY 73 59 QO-100 F4IOZ", LangageOm.motsCles("f4ioz"))
    }

    @Test fun `le prompt repete sur un silence est jete`() {
        val p = LangageOm.prompt("fr", "F4IOZ")
        assertTrue(LangageOm.recopieDuPrompt("QRZ ? Bien reçu, QSL.", p))
        assertFalse(LangageOm.recopieDuPrompt("QSL", p))
        assertFalse(LangageOm.recopieDuPrompt("CQ de F5SIM, report 59", p))
    }

    @Test fun `epeler un indicatif`() {
        assertEquals("Foxtrot four India Oscar Zulu stroke Papa", LangageOm.epelle("F4IOZ/P"))
        assertEquals("Foxtrot zéro Sierra India Mike", LangageOm.epelle("F0SIM", "fr"))
        // Spelt then written again: the same.
        assertEquals("F4IOZ/P", en(LangageOm.epelle("F4IOZ/P")))
        assertEquals("F5SIM", fr(LangageOm.epelle("F5SIM", "fr")))
    }
}
