/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.Ft4
import fr.f4ioz.satcombo.domain.Ft8
import fr.f4ioz.satcombo.domain.Ft8Decodeur
import fr.f4ioz.satcombo.domain.Ft8Signal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le banc de FT4.
 *
 * Tout y est repris de la description publiée par K9AN, G4WJS et K1JT — placée
 * par eux dans le domaine public — et rien du code de WSJT-X. Ces essais
 * vérifient d'abord que les valeurs recopiées sont cohérentes entre elles,
 * puis qu'un message fait l'aller-retour par l'air.
 */
class Ft4Test {

    private val MODE = Ft8Signal.FT4

    // ---- la trame ----

    @Test
    fun la_trame_compte_bien_cent_cinq_symboles() {
        // R + S1 + 29 + S2 + 29 + S3 + 29 + S4 + R
        assertEquals(105, Ft4.SYMBOLES)
        assertEquals(1 + 4 + 29 + 4 + 29 + 4 + 29 + 4 + 1, Ft4.SYMBOLES)
        // 174 bits à deux bits par symbole : 87 symboles utiles.
        assertEquals(87, Ft4.SYMBOLES_DONNEES)
        assertEquals(Ft4.BITS / 2, Ft4.SYMBOLES_DONNEES)
    }

    @Test
    fun les_reperes_et_les_rampes_sont_aux_bonnes_places() {
        assertTrue(Ft4.estRepere(0))            // rampe de montée
        assertTrue(Ft4.estRepere(104))          // rampe de descente
        for (d in listOf(1, 34, 67, 100)) {
            for (i in 0 until 4) assertTrue("repère ${d + i}", Ft4.estRepere(d + i))
        }
        for (p in listOf(5, 33, 38, 66, 71, 99)) {
            assertFalse("utile $p", Ft4.estRepere(p))
        }
        assertEquals(87, (0 until 105).count { !Ft4.estRepere(it) })
    }

    @Test
    fun les_quatre_reseaux_sont_des_permutations_distinctes() {
        val reseaux = listOf(Ft4.COSTAS_1, Ft4.COSTAS_2, Ft4.COSTAS_3, Ft4.COSTAS_4)
        for (r in reseaux) {
            assertEquals("chaque ton une fois", listOf(0, 1, 2, 3), r.sorted())
        }
        // Quatre motifs **différents** : c'est ce qui dit au récepteur où il
        // est tombé dans la trame, et non seulement qu'il a trouvé un repère.
        for (i in reseaux.indices) for (j in i + 1 until reseaux.size) {
            assertNotEquals(reseaux[i].toList(), reseaux[j].toList())
        }
    }

    @Test
    fun la_synchro_donne_seize_reperes() {
        val s = Ft4.synchro()
        assertEquals(16, s.size)
        assertEquals(1 to 0, s[0])
        assertEquals(4 to 2, s[3])
        assertEquals(100 to 3, s[12])
    }

    // ---- le brouillage ----

    @Test
    fun le_brouillage_est_son_propre_inverse() {
        val alea = java.util.Random(4)
        val m = BooleanArray(Ft4.BITS_MESSAGE) { alea.nextBoolean() }
        assertEquals(m.toList(), Ft4.brouille(Ft4.brouille(m)).toList())
    }

    @Test
    fun le_brouillage_casse_la_longue_suite_de_zeros_dun_appel() {
        // Sans lui, un message d'appel émettrait une porteuse sur le ton 0.
        val zeros = BooleanArray(Ft4.BITS_MESSAGE)
        val brouille = Ft4.brouille(zeros)
        val uns = brouille.count { it }
        assertTrue("brouillage trop pauvre : $uns", uns in 25..52)
        // Et aucune suite de zéros interminable ne subsiste.
        var pire = 0; var courant = 0
        for (b in brouille) { if (!b) { courant++; pire = maxOf(pire, courant) } else courant = 0 }
        assertTrue("suite de zéros trop longue : $pire", pire <= 8)
    }

    @Test
    fun le_controle_porte_sur_le_message_brouille() {
        val alea = java.util.Random(6)
        val m = BooleanArray(Ft4.BITS_MESSAGE) { alea.nextBoolean() }
        val utiles = Ft4.avecControle(m)
        assertTrue(Ft4.controleJuste(utiles))
        assertEquals(m.toList(), Ft4.message(utiles).toList())
    }

    // ---- symboles et bits ----

    @Test
    fun les_bits_font_laller_retour_par_les_symboles() {
        val alea = java.util.Random(8)
        val bits = BooleanArray(Ft4.BITS) { alea.nextBoolean() }
        val tons = Ft4.bitsVersSymboles(bits)
        assertEquals(105, tons.size)
        assertTrue("les tons doivent tenir dans 0..3", tons.all { it in 0..3 })
        assertEquals(bits.toList(), Ft4.symbolesVersBits(tons).toList())
    }

    @Test
    fun les_reperes_sortent_intacts_de_lencodage() {
        val bits = BooleanArray(Ft4.BITS)
        val tons = Ft4.bitsVersSymboles(bits)
        assertEquals(16, Ft4.scoreCostas(tons))
        assertEquals(0, tons[0])
        assertEquals(0, tons[104])
    }

    // ---- la couche physique ----

    @Test
    fun les_parametres_physiques_sont_ceux_de_larticle() {
        assertEquals(0.048, Ft4.DUREE_SYMBOLE_S, 1e-9)
        assertEquals(20.8333, MODE.ecartHz, 1e-4)
        assertEquals(83.33, 4 * MODE.ecartHz, 0.01)
        // Plus fortement lissé que FT8 : BT = 1 contre 2.
        assertEquals(1.0, MODE.lissageBT, 1e-9)
        assertEquals(2.0, Ft8Signal.FT8.lissageBT, 1e-9)
        // 105 × 0,048 = 5,04 s, dans une tranche de 7,5 s.
        assertEquals(5.04, MODE.dureeS, 1e-6)
    }

    @Test
    fun un_signal_ft4_propre_rend_ses_symboles() {
        val alea = java.util.Random(10)
        val bits = BooleanArray(Ft4.BITS) { alea.nextBoolean() }
        val attendus = Ft4.bitsVersSymboles(bits)
        val audio = Ft8Signal.synthetise(attendus, MODE, 1000.0, decalageS = 0.5)
        val spec = Ft8Signal.spectrogramme(audio, MODE, 900.0, 1200.0)
        val c = Ft8Signal.candidats(spec, maximum = 4).firstOrNull()
            ?: error("aucun candidat")
        assertEquals(1000.0, c.frequenceHz(spec), MODE.ecartHz / 2)
        val lus = Ft8Signal.tons(spec, c)
        // Les rampes ne portent rien et leur amplitude varie : on ne juge que
        // les symboles utiles et les repères.
        for (i in 1 until 104) assertEquals("symbole $i", attendus[i], lus[i])
    }

    @Test
    fun un_message_ft4_fait_laller_retour_par_lair() {
        // Convention FT8 et FT4 : le **premier** indicatif est celui qu'on
        // appelle, le second celui qui émet. « W9XYZ F4IOZ » se lit donc
        // « F4IOZ appelle W9XYZ ».
        val m = BooleanArray(Ft4.BITS_MESSAGE)
        Ft8.ecritEntier(m, 0, 28, Ft8.indicatifVers28("W9XYZ")!!)
        Ft8.ecritEntier(m, 29, 28, Ft8.indicatifVers28("F4IOZ")!!)
        Ft8.ecritEntier(m, 74, 3, 1L)          // type 1
        val utiles = Ft4.avecControle(m)
        val tous = BooleanArray(Ft4.BITS)
        utiles.copyInto(tous, 0, 0, Ft4.BITS_UTILES)

        val audio = Ft8Signal.synthetise(
            Ft4.bitsVersSymboles(tous), MODE, 1000.0, decalageS = 0.5)
        val spec = Ft8Signal.spectrogramme(audio, MODE, 900.0, 1200.0)
        val c = Ft8Signal.candidats(spec, maximum = 4).first()
        val relus = Ft4.symbolesVersBits(Ft8Signal.tons(spec, c))
        val utilesRelus = relus.copyOf(Ft4.BITS_UTILES)
        assertTrue("le contrôle doit tomber juste", Ft4.controleJuste(utilesRelus))
        assertEquals(m.toList(), Ft4.message(utilesRelus).toList())
    }

    @Test
    fun le_decodeur_rend_un_message_ft4_complet() {
        val m = BooleanArray(Ft4.BITS_MESSAGE)
        Ft8.ecritEntier(m, 0, 28, Ft8.indicatifVers28("W9XYZ")!!)
        Ft8.ecritEntier(m, 29, 28, Ft8.indicatifVers28("F4IOZ")!!)
        Ft8.ecritEntier(m, 74, 3, 1L)
        val utiles = Ft4.avecControle(m)
        val tous = BooleanArray(Ft4.BITS)
        utiles.copyInto(tous, 0, 0, Ft4.BITS_UTILES)
        val audio = Ft8Signal.synthetise(
            Ft4.bitsVersSymboles(tous), MODE, 1200.0, decalageS = 0.5)

        val entendus = Ft8Decodeur.decode(audio, MODE.cadenceHz, MODE, 1000.0, 1500.0)
        assertTrue("rien décodé", entendus.isNotEmpty())
        assertEquals("F4IOZ", entendus[0].message.appelant)
        assertEquals("W9XYZ", entendus[0].message.appele)
        assertTrue(entendus[0].message.brut.contains("F4IOZ"))
    }

    @Test
    fun un_ft4_decode_en_ft8_ne_donne_rien() {
        // Les deux modes ne doivent pas se prendre l'un pour l'autre : ce serait
        // la porte ouverte à des messages inventés.
        val alea = java.util.Random(12)
        val bits = BooleanArray(Ft4.BITS) { alea.nextBoolean() }
        val audio = Ft8Signal.synthetise(
            Ft4.bitsVersSymboles(bits), MODE, 1000.0, decalageS = 0.5)
        val entendus = Ft8Decodeur.decode(
            audio, MODE.cadenceHz, Ft8Signal.FT8, 900.0, 1200.0)
        assertTrue("un FT8 est sorti d'un FT4", entendus.isEmpty())
    }
}
