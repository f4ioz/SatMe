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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le banc du codec FT8.
 *
 * **Ce qu'il prouve et ce qu'il ne prouve pas.** Un aller-retour vérifie que
 * l'encodeur et le décodeur s'accordent — donc qu'il n'y a pas de faute de
 * frappe entre les deux. Il ne prouve pas la compatibilité avec WSJT-X : si
 * les deux partagent la même erreur d'interprétation de la norme, l'essai
 * passe et rien ne se décodera en l'air.
 *
 * La seule preuve qui vaille est un signal réel décodé. Ces essais évitent les
 * fautes internes ; ils ne remplacent pas la première station entendue.
 */
class Ft8Test {

    // ---------------------------------------------------------- les symboles

    @Test
    fun un_message_fait_soixante_dix_neuf_symboles() {
        val bits = BooleanArray(Ft8.BITS)
        assertEquals(Ft8.SYMBOLES, Ft8.bitsVersSymboles(bits).size)
    }

    @Test
    fun les_reperes_de_costas_sont_a_leur_place() {
        val tons = Ft8.bitsVersSymboles(BooleanArray(Ft8.BITS))
        assertEquals(21, Ft8.scoreCostas(tons))
        for (i in 0 until 7) {
            assertEquals(Ft8.COSTAS[i], tons[i])
            assertEquals(Ft8.COSTAS[i], tons[36 + i])
            assertEquals(Ft8.COSTAS[i], tons[72 + i])
        }
    }

    @Test
    fun les_bits_survivent_a_l_aller_retour_par_les_symboles() {
        val bits = BooleanArray(Ft8.BITS) { (it * 7 + 3) % 5 < 2 }
        val relus = Ft8.symbolesVersBits(Ft8.bitsVersSymboles(bits))
        assertTrue(bits.contentEquals(relus))
    }

    /**
     * Le code de Gray n'est pas une décoration.
     *
     * La propriété porte sur les **tons voisins en fréquence**, et non sur les
     * valeurs voisines : c'est une erreur d'un demi-écartement qui doit ne
     * fausser qu'un seul bit sur trois. Ma première rédaction posait la
     * question à l'envers et échouait sur un code pourtant juste — l'essai
     * avait tort, pas le code.
     */
    @Test
    fun deux_tons_voisins_en_frequence_ne_different_que_d_un_bit() {
        // Le ton d'un symbole de données, pour chacune des huit valeurs.
        val valeurPourTon = IntArray(8)
        val bits = BooleanArray(Ft8.BITS)
        for (v in 0 until 8) {
            Ft8.ecritEntier(bits, 0, 3, v.toLong())
            valeurPourTon[Ft8.bitsVersSymboles(bits)[7]] = v
        }
        for (ton in 0 until 7) {
            val a = valeurPourTon[ton]
            val b = valeurPourTon[ton + 1]
            assertEquals("tons $ton et ${ton + 1}", 1, Integer.bitCount(a xor b))
        }
    }

    @Test
    fun un_score_de_costas_faux_se_voit() {
        val tons = Ft8.bitsVersSymboles(BooleanArray(Ft8.BITS))
        tons[0] = (tons[0] + 1) % 8
        tons[36] = (tons[36] + 1) % 8
        assertEquals(19, Ft8.scoreCostas(tons))
    }

    // --------------------------------------------------------------- le CRC

    @Test
    fun le_controle_valide_un_message_intact() {
        val m = BooleanArray(Ft8.BITS_MESSAGE) { it % 3 == 0 }
        assertTrue(Ft8.controleJuste(Ft8.avecControle(m)))
    }

    /**
     * C'est lui qui remplace le code correcteur : un seul bit faux doit
     * conduire au rejet, jamais à l'affichage d'un message inventé.
     */
    @Test
    fun un_seul_bit_faux_fait_echouer_le_controle() {
        val m = BooleanArray(Ft8.BITS_MESSAGE) { it % 3 == 0 }
        val avec = Ft8.avecControle(m)
        for (i in 0 until Ft8.BITS_MESSAGE) {
            val abime = avec.copyOf()
            abime[i] = !abime[i]
            assertTrue("bit $i non détecté", !Ft8.controleJuste(abime))
        }
    }

    @Test
    fun un_bit_faux_dans_le_controle_lui_meme_est_vu() {
        val m = BooleanArray(Ft8.BITS_MESSAGE) { it % 5 == 0 }
        val avec = Ft8.avecControle(m)
        for (i in Ft8.BITS_MESSAGE until Ft8.BITS_UTILES) {
            val abime = avec.copyOf()
            abime[i] = !abime[i]
            assertTrue("bit $i non détecté", !Ft8.controleJuste(abime))
        }
    }

    // -------------------------------------------------------- les indicatifs

    @Test
    fun un_indicatif_standard_fait_l_aller_retour() {
        listOf("F4IOZ", "G0ABI", "PA3GAN", "DL0IL", "F5RRO").forEach { ind ->
            val n = Ft8.indicatifVers28(ind)
            assertNotNull("codage de $ind", n)
            assertEquals(ind, Ft8.indicatifDepuis28(n!!))
        }
    }

    /**
     * Un indicatif composé ne tient pas dans le moule : il voyage haché, et
     * l'on ne peut pas le retrouver sans l'avoir déjà entendu en clair. On
     * rend donc rien, plutôt qu'une approximation qui finirait au carnet.
     */
    @Test
    fun un_indicatif_compose_est_refuse_plutot_que_devine() {
        assertNull(Ft8.indicatifVers28("DL/PA3GAN"))
        assertNull(Ft8.indicatifVers28("F4IOZ/P"))
    }

    @Test
    fun les_jetons_ne_sont_pas_des_indicatifs() {
        assertNull(Ft8.indicatifDepuis28(0L))
        assertNull(Ft8.indicatifDepuis28(2L))
    }

    // ---------------------------------------------------------- les messages

    private fun messageType1(appele: String, appelant: String, carre: String): BooleanArray {
        val m = BooleanArray(Ft8.BITS_MESSAGE)
        Ft8.ecritEntier(m, 0, 28, Ft8.indicatifVers28(appele)!!)
        Ft8.ecritEntier(m, 29, 28, Ft8.indicatifVers28(appelant)!!)
        val j = (carre[0] - 'A') * 18 * 10 * 10 + (carre[1] - 'A') * 10 * 10 +
            (carre[2] - '0') * 10 + (carre[3] - '0')
        // Bit 59 : la spécification range c28 r1 c28 r1 R1 g15 i3, et le bit
        // 58 est celui du « roger », pas le premier du carré.
        Ft8.ecritEntier(m, 59, 15, j.toLong())
        Ft8.ecritEntier(m, 74, 3, 1L)
        return m
    }

    @Test
    fun un_message_ordinaire_se_deplie() {
        val d = Ft8.deplie(messageType1("F5RRO", "F4IOZ", "JN18"))
        assertNotNull(d)
        assertEquals("F5RRO", d!!.appele)
        assertEquals("F4IOZ", d.appelant)
        assertEquals("JN18", d.locator)
        assertEquals("F5RRO F4IOZ JN18", d.brut)
    }

    @Test
    fun un_appel_general_se_reconnait() {
        val m = BooleanArray(Ft8.BITS_MESSAGE)
        Ft8.ecritEntier(m, 0, 28, 2L)          // CQ
        Ft8.ecritEntier(m, 29, 28, Ft8.indicatifVers28("F4IOZ")!!)
        Ft8.ecritEntier(m, 59, 15, 0L)         // AA00
        Ft8.ecritEntier(m, 74, 3, 1L)
        val d = Ft8.deplie(m)
        assertNotNull(d)
        assertNull("CQ n'est pas un indicatif", d!!.appele)
        assertEquals("F4IOZ", d.appelant)
        assertTrue(d.brut.startsWith("CQ F4IOZ"))
    }

    /** Les autres types de message sont écartés, non devinés. */
    @Test
    fun un_type_inconnu_est_ecarte() {
        val m = messageType1("F5RRO", "F4IOZ", "JN18")
        Ft8.ecritEntier(m, 74, 3, 4L)
        assertNull(Ft8.deplie(m))
    }

    @Test
    fun la_chaine_entiere_tient_debout() {
        val m = messageType1("G0ABI", "F4IOZ", "IN77")
        val utiles = Ft8.avecControle(m)
        val bits = BooleanArray(Ft8.BITS)
        utiles.copyInto(bits, 0, 0, Ft8.BITS_UTILES)
        val tons = Ft8.bitsVersSymboles(bits)
        assertEquals(21, Ft8.scoreCostas(tons))
        val relus = Ft8.symbolesVersBits(tons)
        assertTrue(Ft8.controleJuste(relus.copyOf(Ft8.BITS_UTILES)))
        assertEquals("G0ABI F4IOZ IN77", Ft8.deplie(relus)!!.brut)
    }
}
