/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.Ft8
import fr.f4ioz.satcombo.domain.Ft8Decodeur
import fr.f4ioz.satcombo.domain.Ft8Signal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le banc du décodage complet.
 *
 * On fabrique un vrai message — indicatifs, carré —, on l'émet en audio, on le
 * réécoute, et l'on vérifie qu'il ressort mot pour mot. Et surtout : on vérifie
 * qu'**un signal abîmé ne ressort pas du tout**, plutôt que de ressortir faux.
 */
class Ft8DecodeurTest {

    private val MODE = Ft8Signal.FT8

    /**
     * Fabrique l'audio d'un message type 1.
     *
     * Les 83 bits de parité sont tirés au hasard : ce décodeur les ignore, et
     * c'est justement ce que l'essai doit refléter. Le jour où le code
     * correcteur existera, il faudra les calculer — et cet essai échouera, ce
     * qui sera le bon signal.
     */
    private fun audioDeMessage(
        appele: String, appelant: String, carre: String,
        frequenceHz: Double = 1000.0, decalageS: Double = 0.5,
        graine: Long = 1
    ): FloatArray {
        val c1 = if (appele == "CQ") 2L else Ft8.indicatifVers28(appele)!!
        val c2 = Ft8.indicatifVers28(appelant)!!
        val g15 = (carre[0] - 'A') * 1800 + (carre[1] - 'A') * 100 +
            (carre[2] - '0') * 10 + (carre[3] - '0')

        val message = BooleanArray(Ft8.BITS_MESSAGE)
        Ft8.ecritEntier(message, 0, 28, c1)
        Ft8.ecritEntier(message, 29, 28, c2)
        // Bit 59, pas 58. Ces essais écrivaient au même mauvais endroit que
        // le décodeur les relisait : l'aller-retour tombait juste sur une
        // erreur partagée, et le défaut n'est sorti qu'en écoutant de vraies
        // stations. Un banc qui pose lui-même la convention qu'il vérifie ne
        // vérifie rien.
        Ft8.ecritEntier(message, 59, 15, g15.toLong())
        Ft8.ecritEntier(message, 74, 3, 1L)

        val utiles = Ft8.avecControle(message)
        val bits = BooleanArray(Ft8.BITS)
        System.arraycopy(utiles, 0, bits, 0, Ft8.BITS_UTILES)
        val alea = java.util.Random(graine)
        for (i in Ft8.BITS_UTILES until Ft8.BITS) bits[i] = alea.nextBoolean()

        return Ft8Signal.synthetise(
            Ft8.bitsVersSymboles(bits), MODE, frequenceHz, decalageS)
    }

    // ------------------------------------------------------- le cas nominal

    @Test
    fun un_appel_general_est_relu_mot_pour_mot() {
        val audio = audioDeMessage("CQ", "F4IOZ", "IN77")
        val r = Ft8Decodeur.decode(audio, MODE.cadenceHz, MODE, 900.0, 1200.0)
        assertEquals(1, r.size)
        assertEquals("CQ F4IOZ IN77", r[0].message.brut)
        assertEquals("F4IOZ", r[0].message.appelant)
        assertEquals("IN77", r[0].message.locator)
    }

    @Test
    fun un_contact_entre_deux_stations_passe_aussi() {
        val audio = audioDeMessage("F5RRO", "F4IOZ", "JN18")
        val r = Ft8Decodeur.decode(audio, MODE.cadenceHz, MODE, 900.0, 1200.0)
        assertEquals(1, r.size)
        assertEquals("F5RRO F4IOZ JN18", r[0].message.brut)
        assertEquals("F5RRO", r[0].message.appele)
        assertEquals("F4IOZ", r[0].message.appelant)
    }

    @Test
    fun la_frequence_et_linstant_sont_rendus() {
        val audio = audioDeMessage("CQ", "F4IOZ", "IN77",
            frequenceHz = 1500.0, decalageS = 0.72)
        val r = Ft8Decodeur.decode(audio, MODE.cadenceHz, MODE, 1400.0, 1700.0)
        assertEquals(1, r.size)
        assertEquals(1500.0, r[0].frequenceHz, MODE.ecartHz / 2)
        assertEquals(0.72, r[0].instantS, MODE.dureeSymbole)
    }

    @Test
    fun la_cadence_de_capture_na_pas_dimportance() {
        // Ce que le micro fournit varie d'un téléphone à l'autre ; on ramène.
        val a12800 = audioDeMessage("CQ", "F4IOZ", "IN77")
        for (cadence in listOf(48000.0, 44100.0, 16000.0)) {
            val audio = Ft8Signal.reechantillonne(a12800, MODE.cadenceHz, cadence)
            val r = Ft8Decodeur.decode(audio, cadence, MODE, 900.0, 1200.0)
            assertEquals("à $cadence Hz", 1, r.size)
            assertEquals("CQ F4IOZ IN77", r[0].message.brut)
        }
    }

    // ------------------------------------------------------- le refus de mentir

    @Test
    fun un_signal_abime_ne_rend_rien_plutot_quun_faux() {
        val propre = audioDeMessage("CQ", "F4IOZ", "IN77")
        // Assez de bruit pour que des symboles tombent faux.
        val abime = Ft8Signal.avecBruit(propre, MODE, rapportDb = -8.0, graine = 3)
        val r = Ft8Decodeur.decode(abime, MODE.cadenceHz, MODE, 900.0, 1200.0)
        // Soit le message exact, soit rien — jamais autre chose.
        for (d in r) assertEquals("CQ F4IOZ IN77", d.message.brut)
    }

    @Test
    fun le_bruit_seul_ne_rend_jamais_de_message() {
        val longueur = (MODE.dureeS * MODE.cadenceHz).toInt() + MODE.parSymbole * 8
        for (graine in 1L..12L) {
            val bruit = Ft8Signal.avecBruit(
                FloatArray(longueur) { 0.001f }, MODE, -40.0, graine)
            val r = Ft8Decodeur.decode(bruit, MODE.cadenceHz, MODE, 300.0, 2800.0)
            assertTrue("un message est né du bruit, graine $graine : " +
                r.joinToString { it.message.brut }, r.isEmpty())
        }
    }

    @Test
    fun un_audio_trop_court_ne_fait_pas_tomber_le_decodeur() {
        assertTrue(Ft8Decodeur.decode(FloatArray(1000), 12000.0).isEmpty())
        assertTrue(Ft8Decodeur.decode(FloatArray(0), 12000.0).isEmpty())
    }

    @Test
    fun ft4_sans_ses_reperes_rend_une_liste_vide_sans_se_plaindre() {
        val audio = FloatArray(200_000)
        assertTrue(Ft8Decodeur.decode(audio, 12000.0, Ft8Signal.FT4).isEmpty())
    }

    // ------------------------------------------------------- plusieurs stations

    @Test
    fun deux_stations_sont_decodees_toutes_les_deux() {
        val a = audioDeMessage("CQ", "F4IOZ", "IN77", frequenceHz = 1000.0)
        val b = audioDeMessage("F4IOZ", "F5RRO", "JN18", frequenceHz = 1300.0, graine = 9)
        val melange = FloatArray(a.size) { a[it] + b[it] }
        val r = Ft8Decodeur.decode(melange, MODE.cadenceHz, MODE, 900.0, 1500.0)
        val lus = r.map { it.message.brut }
        assertTrue("la première manque : $lus", lus.contains("CQ F4IOZ IN77"))
        assertTrue("la seconde manque : $lus", lus.contains("F4IOZ F5RRO JN18"))
    }

    @Test
    fun un_meme_message_nest_pas_liste_deux_fois() {
        val audio = audioDeMessage("CQ", "F4IOZ", "IN77", frequenceHz = 1000.0)
        val r = Ft8Decodeur.decode(audio, MODE.cadenceHz, MODE, 900.0, 1200.0)
        assertEquals(1, r.count { it.message.brut == "CQ F4IOZ IN77" })
    }

    // --------------------------------------------------------- le rapport

    @Test
    fun un_signal_fort_annonce_un_rapport_fort() {
        val audio = audioDeMessage("CQ", "F4IOZ", "IN77")
        val r = Ft8Decodeur.decode(audio, MODE.cadenceHz, MODE, 900.0, 1200.0)
        assertEquals(1, r.size)
        // L'estimation sature : la jupe spectrale du signal est indiscernable
        // d'un bruit. On vérifie donc le bon côté de zéro, pas une valeur.
        assertTrue("rapport trop bas : ${r[0].rapportDb}", r[0].rapportDb >= 0)
    }

    @Test
    fun plus_de_bruit_donne_un_rapport_plus_bas() {
        val propre = audioDeMessage("CQ", "F4IOZ", "IN77")
        val fort = Ft8Decodeur.decode(
            Ft8Signal.avecBruit(propre, MODE, 15.0, 2), MODE.cadenceHz, MODE, 900.0, 1200.0)
        val faible = Ft8Decodeur.decode(
            Ft8Signal.avecBruit(propre, MODE, 3.0, 2), MODE.cadenceHz, MODE, 900.0, 1200.0)
        assertEquals(1, fort.size)
        assertEquals(1, faible.size)
        assertTrue("${fort[0].rapportDb} devrait dépasser ${faible[0].rapportDb}",
            fort[0].rapportDb > faible[0].rapportDb)
    }

    @Test
    fun le_rapport_reste_dans_les_bornes_dun_report() {
        val audio = audioDeMessage("CQ", "F4IOZ", "IN77")
        val r = Ft8Decodeur.decode(audio, MODE.cadenceHz, MODE, 900.0, 1200.0)
        assertNotNull(r.firstOrNull())
        assertTrue(r[0].rapportDb in -30..30)
    }
}
