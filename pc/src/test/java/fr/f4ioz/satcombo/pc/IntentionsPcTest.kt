/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sentences understood: accents, numbers in words, a letter wrong, and every transmission refused. */
class IntentionsPcTest {

    private val sats = listOf("ISS (ZARYA)" to 25544, "SO-50" to 27607, "AO-91" to 43017, "RS-44" to 44909, "AO-7" to 7530,
        "FO-29" to 24278, "QO-100" to 43700, "IO-117" to 53106, "CAS-4A" to 42761, "PO-101" to 43678)

    private fun i(t: String) = Phrases.analyse(t, sats).intention
    private fun sat(t: String) = Phrases.analyse(t, sats).satellite?.second

    @Test fun normalise() {
        assertEquals("prochain passage de l iss", Phrases.normalise("Prochain passage de l'ISS ?"))
        assertEquals("ou est l elevation", Phrases.normalise("Où est l'élévation"))
        assertEquals("ao 91", Phrases.normalise("AO quatre-vingt-onze"))
        assertEquals("so 50", Phrases.normalise("SO cinquante"))
        assertEquals("71 97 80 21", Phrases.normalise("soixante et onze, quatre-vingt-dix-sept, quatre-vingts, vingt et un"))
        assertEquals("ao 7", Phrases.normalise("AO seven"))
        assertEquals("ao91", Phrases.cle("AO-91"))
        assertEquals("isszarya", Phrases.cle("ISS (ZARYA)"))
        // « une image »: a word, not a digit.
        assertEquals("la derniere image une image", Phrases.normalise("la dernière image, une image"))
    }

    @Test fun les_satellites_nommes() {
        assertEquals(25544, sat("quand passe l'ISS"))
        assertEquals(25544, sat("prochain passage de la station spatiale"))
        assertEquals(27607, sat("prochain passage de SO-50"))
        assertEquals(27607, sat("prochain passage de SO 50"))
        assertEquals(27607, sat("prochain passage de so cinquante"))
        assertEquals(43017, sat("quand passe AO 91"))
        assertEquals(43017, sat("quand passe A O quatre-vingt-onze"))
        assertEquals(43017, sat("next pass of AO-91"))
        assertEquals(44909, sat("prochain passage de RS44"))
        assertEquals(7530, sat("quand passe AO sept"))
        assertEquals(53106, sat("quand passe IO-117"))
        assertEquals(42761, sat("prochain passage de CAS 4A"))
        assertNull(sat("prochain passage"))
        assertEquals(Intention.PROCHAIN_PASSAGE, i("Quand passe l'ISS ?"))
        assertEquals(Intention.PROCHAIN_PASSAGE, i("ISS"))
        assertEquals(Intention.PROCHAIN_PASSAGE, i("est-ce que l'ISS est visible"))
    }

    @Test fun les_questions() {
        assertEquals(Intention.PROCHAIN_PASSAGE, i("prochain passage"))
        assertEquals(Intention.PROCHAIN_PASSAGE, i("prochain pasage de mes favoris"))
        assertEquals(Intention.PROCHAIN_PASSAGE, i("When is the next pass?"))
        assertEquals(Intention.VISIBLES, i("Quels satellites sont visibles ?"))
        assertEquals(Intention.VISIBLES, i("qu'est-ce qui est au-dessus en ce moment"))
        assertEquals(Intention.VISIBLES, i("which satellites are in view"))
        assertEquals(Intention.PROCHAIN_AOS, i("prochain AOS"))
        assertEquals(Intention.PROCHAIN_AOS, i("c'est quand le prochain satellite"))
        assertEquals(Intention.AUTOPILOTE_ETAT, i("où en est l'auto-pilote"))
        assertEquals(Intention.AUTOPILOTE_ETAT, i("état de l'autopilotte"))
        assertEquals(Intention.AUTOPILOTE_ETAT, i("Est-ce que l'autopilote est activé ?"))
        assertEquals(Intention.DERNIERE_SSTV, i("dernière image SSTV"))
        assertEquals(Intention.DERNIERE_SSTV, i("what was the last picture"))
        assertEquals(Intention.DATV_ETAT, i("état de la DATV"))
        assertEquals(Intention.DATV_ETAT, i("est-ce que la DATV est coupée ?"))
        assertEquals(Intention.BALISE, i("comment va la balise QO-100"))
        assertEquals(Intention.BALISE, i("beacon status"))
        assertEquals(Intention.ROTOR, i("où pointe l'antenne"))
        assertEquals(Intention.ROTOR, i("position du rotor"))
        assertEquals(Intention.DERNIER_QSO, i("dernier QSO"))
        assertEquals(Intention.DERNIER_QSO, i("last contact"))
        assertEquals(Intention.AIDE, i("aide"))
        assertEquals(Intention.INCONNU, i("fais-moi un café"))
        assertEquals(Intention.INCONNU, i(""))
    }

    @Test fun les_actions_sures() {
        assertEquals(Intention.AUTOPILOTE_ON, i("active l'auto-pilote"))
        assertEquals(Intention.AUTOPILOTE_ON, i("turn on the autopilot"))
        assertEquals(Intention.AUTOPILOTE_OFF, i("coupe l'autopilote"))
        assertEquals(Intention.SSTV_ON, i("démarre la réception SSTV"))
        assertEquals(Intention.SSTV_ON, i("lance la SSTV"))
        assertEquals(Intention.SSTV_OFF, i("arrête la SSTV"))
        assertEquals(Intention.DATV_ON, i("démarre la DATV"))
        assertEquals(Intention.DATV_OFF, i("stop DATV"))
        assertEquals(Intention.SON_MUET, i("coupe le son"))
        assertEquals(Intention.SON_MUET, i("mute"))
        assertEquals(Intention.SON_MUET, i("silence"))
        assertEquals(Intention.SON_REMET, i("remets le son"))
        assertEquals(Intention.SON_REMET, i("unmute"))
        assertEquals(Intention.SON_MUET, i("peux-tu couper le son ?"))
        Intention.ACTIONS_SURES.forEach { assertTrue(it.action) }
        Intention.QUESTIONS.forEach { assertFalse(it.action) }
    }

    @Test fun toute_emission_est_refusee() {
        listOf(
            "passe en émission", "émets sur SO-50", "émettre en DATV", "lance l'émission DATV", "démarre l'émission SSTV",
            "envoie une image SSTV", "appuie sur le PTT", "PTT", "lance un appel CQ", "appel CQ en FT8", "démarre le FT8",
            "réponds en FT8", "transmit on QO-100", "start transmitting", "key the radio", "go on the air", "send a picture on the ISS",
            "allume le Pluto", "monte la puissance", "TX on", "arrête l'émission", "est-ce que la station émet ?",
            "tourne l'antenne vers le sud", "oriente la parabole", "rotate the antenna to 180", "point the antenna at the ISS",
            "tourne le rotor à 90 degrés d'azimut", "parque le rotor", "Satmi, émets", "autopilote émission",
        ).forEach { t ->
            assertEquals(t, Intention.EMISSION, i(t))
            assertTrue(t, Phrases.demandeEmission(t))
        }
        // Receive words are not transmissions.
        listOf("démarre la réception SSTV", "dernier QSO", "où pointe l'antenne", "état de la DATV", "prochain passage de SO-50").forEach { t ->
            assertFalse(t, Phrases.demandeEmission(t))
        }
    }

    @Test fun mots_proches() {
        assertTrue(Phrases.ressemble("autopilotte", "autopilote"))
        assertTrue(Phrases.ressemble("emettre", "emet"))
        assertFalse(Phrases.ressemble("sont", "son"))
        assertFalse(Phrases.ressemble("reception", "emission"))
        assertEquals(1, Phrases.distance("pasage", "passage"))
    }
}
