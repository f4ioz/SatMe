/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.cat.CatBench
import fr.f4ioz.satcombo.cat.CatDecode
import fr.f4ioz.satcombo.cat.CatJournal
import fr.f4ioz.satcombo.cat.CivController
import fr.f4ioz.satcombo.cat.Ic9700Sim
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le pilote CI-V, contre un IC-9700 qui n'existe pas.
 *
 * Dix essais, dont trois portent sur des défauts qui ont réellement coûté des
 * passages : un ton d'accès encodé à l'envers, une réponse cherchée dans son
 * propre écho, et une lecture unique qui tronquait tout ce qui suivait un
 * accusé de réception. Aucun des trois ne se voit sur la face avant du poste,
 * et c'est bien le problème.
 */
class CivControllerTest {

    private fun bench(sim: Ic9700Sim = Ic9700Sim()): CivController {
        val cat = CivController()
        cat.pacingMs = 0L        // au banc, il n'y a personne à ménager
        cat.attach(sim)
        return cat
    }

    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun une_sequence_saine_ne_fait_rien_refuser_au_poste() = runBlocking {
        // L'affirmation que l'on n'avait jamais pu faire : le poste a tout
        // compris. Pas « ça n'a pas planté » — tout compris.
        val sim = Ic9700Sim()
        val r = CatBench.runIc9700(sim)
        assertEquals("refus du poste simulé : ${r.steps}", 0, r.refusals)
        assertTrue(r.ok)
        assertTrue(sim.satMode)
    }

    @Test
    fun le_couple_satellite_atterrit_sur_les_bonnes_bandes() = runBlocking {
        val sim = Ic9700Sim()
        val cat = bench(sim)
        cat.setSatelliteMode(true)
        cat.setSatellitePair(435_500_000L, 145_900_000L)
        assertEquals(435_500_000L, sim.mainHz)
        assertEquals(145_900_000L, sim.subHz)
        // Et l'on finit sur la principale, pour que le bouton de l'opérateur
        // reste celui de la réception.
        assertTrue("le poste est resté sur la bande secondaire", !sim.onSub)
        assertEquals(0, sim.refusals)
    }

    @Test
    fun l_echo_du_bus_ne_se_fait_plus_prendre_pour_une_reponse() = runBlocking {
        // « CI-V USB Echo Back » activé : le poste renvoie la question avant la
        // réponse. L'ancien code cherchait un octet 0x03 dans le tampon brut ;
        // il trouvait donc sa propre question, et lisait cinq octets de rien
        // derrière. On croyait relire la fréquence du poste : on relisait la
        // sienne.
        val sim = Ic9700Sim()
        sim.echo = true
        val cat = bench(sim)
        cat.setFrequency(435_123_450L)
        assertEquals(435_123_450L, cat.readFrequency())
    }

    @Test
    fun une_reponse_precedee_d_un_accuse_n_est_plus_tronquee() = runBlocking {
        // Certains postes accusent réception puis répondent. Une lecture unique
        // rendait l'accusé, et la réponse tombait dans le vide.
        val sim = Ic9700Sim()
        sim.ackBeforeReply = true
        sim.echo = true          // les deux à la fois, tant qu'à faire
        // Le simulateur applique désormais la règle du vrai poste : jamais les
        // deux bandes ensemble. La bande principale part sur 435 et la
        // secondaire sur 145 ; écrire 145 sur la principale serait refusé —
        // par le simulateur comme par l'IC-9700. Cet essai-ci porte sur le
        // découpage des trames, pas sur les bandes, alors on écrit là où le
        // poste est déjà.
        val cat = bench(sim)
        cat.setFrequency(435_875_000L)
        assertEquals(435_875_000L, cat.readFrequency())
    }

    @Test
    fun le_ton_de_so_50_est_enfin_accepte() = runBlocking {
        // 67,0 Hz sur SO-50, et 74,4 Hz pour l'armement : les deux passent.
        val sim = Ic9700Sim()
        val cat = bench(sim)
        assertTrue(cat.setToneFreq(670))
        assertEquals(670, sim.toneTenthHz)
        assertTrue(cat.setToneFreq(744))
        assertEquals(744, sim.toneTenthHz)
        assertEquals(744, cat.readToneFreq())
        assertEquals(0, sim.refusals)
    }

    @Test
    fun l_ancien_encodage_du_ton_est_refuse_par_le_poste() = runBlocking {
        // La preuve par l'octet. On rejoue à la main ce que l'ancien code
        // émettait pour 88,5 Hz — 1B 00 00 88 50 — et le poste le refuse,
        // comme le vrai le faisait. À l'époque, rien ne le disait : l'accusé de
        // réception arrivait quand même, et l'application affichait « ton
        // réglé » pendant que le relais restait muet.
        val sim = Ic9700Sim()
        sim.write(b(0xFE, 0xFE, 0xA2, 0xE0, 0x1B, 0x00, 0x00, 0x88, 0x50, 0xFD), 500)
        assertEquals(1, sim.refusals)
        assertEquals(0, sim.toneTenthHz)

        // Et le bon encodage, lui, passe.
        sim.write(b(0xFE, 0xFE, 0xA2, 0xE0, 0x1B, 0x00, 0x00, 0x08, 0x85, 0xFD), 500)
        assertEquals(1, sim.refusals)
        assertEquals(885, sim.toneTenthHz)
    }

    @Test
    fun un_ton_hors_plage_ne_part_meme_pas() = runBlocking {
        // Deuxième garde-fou, en amont du poste : le pilote refuse d'émettre ce
        // qu'aucune radio n'accepterait.
        val sim = Ic9700Sim()
        val cat = bench(sim)
        val avant = sim.received.size
        assertTrue(!cat.setToneFreq(8850))
        assertTrue(!cat.setToneFreq(0))
        assertEquals("une trame est partie quand même", avant, sim.received.size)
        assertEquals(0, sim.refusals)
    }

    @Test
    fun la_commande_25_est_refusee_sur_la_bande_secondaire_en_mode_satellite() = runBlocking {
        // C'est la raison pour laquelle le couple passe par 0x07 D0/D1 puis
        // 0x05, et non par 0x25. Un poste qui refuse vaut mille fois mieux
        // qu'un poste qui appliquerait la commande à la mauvaise bande.
        val sim = Ic9700Sim()
        val cat = bench(sim)
        cat.setSatelliteMode(true)
        assertTrue("le poste aurait dû refuser", !cat.setVfoFreq(145_900_000L, unselected = true))
        assertEquals(1, sim.refusals)
        assertEquals(145_000_000L, sim.subHz)
        // Hors mode satellite, la même commande est parfaitement légitime.
        cat.setSatelliteMode(false)
        assertTrue(cat.setVfoFreq(145_900_000L, unselected = true))
        assertEquals(145_900_000L, sim.subHz)
        assertEquals(1, sim.refusals)
    }

    @Test
    fun sans_fil_serie_le_pilote_rend_null_plutot_que_d_exploser() = runBlocking {
        val cat = CivController()
        cat.pacingMs = 0L
        assertTrue(!cat.isOpen)
        assertNull(cat.readFrequency())
        assertNull(cat.readVfoFreq(unselected = true))
        assertNull(cat.sendAndRead(0x03))
        assertTrue(!cat.setFrequency(435_000_000L))
        // Et un poste refermé se comporte de même.
        val sim = Ic9700Sim()
        val ouvert = bench(sim)
        ouvert.close()
        assertTrue(!ouvert.isOpen)
        assertTrue(sim.isClosed)
    }

    @Test
    fun le_changement_de_v_sur_u_a_u_sur_v_ne_fait_rien_refuser() = runBlocking {
        // Le défaut d'Olivier, joué en entier : RS-44 (descente 435, montée 145)
        // puis AO-91 (descente 145, montée 435). Le poste simulé refuse
        // désormais ce que le vrai refuse — les deux bandes ensemble — donc un
        // seul refus suffirait à faire échouer cet essai. En 18.18 il y en avait
        // un, et c'est pour cela que le panneau POSTE montrait deux fois 435.
        val sim = Ic9700Sim()
        val cat = bench(sim)
        cat.setSatelliteMode(true)

        cat.setSatellitePair(435_660_000L, 145_940_000L)
        assertEquals(435_660_000L, sim.mainHz)
        assertEquals(145_940_000L, sim.subHz)

        cat.setSatellitePair(145_960_000L, 435_250_000L)
        assertEquals("la descente n'a pas changé de bande", 145_960_000L, sim.mainHz)
        assertEquals("la montée n'a pas changé de bande", 435_250_000L, sim.subHz)

        // Et l'on repasse dans l'autre sens, parce qu'un passage en suit un autre.
        cat.setSatellitePair(435_660_000L, 145_940_000L)
        assertEquals(435_660_000L, sim.mainHz)
        assertEquals(145_940_000L, sim.subHz)

        assertEquals("le poste a refusé quelque chose", 0, sim.refusals)
        assertTrue("le poste est resté sur la bande secondaire", !sim.onSub)
    }

    @Test
    fun le_doppler_ne_relit_le_poste_qu_une_fois_par_passage() = runBlocking {
        // La lecture des deux bandes coûte quatre trames ; à dix tours par
        // seconde elle noierait le bus. Elle ne doit avoir lieu qu'au premier
        // couple, puis plus jamais tant qu'on suit le même satellite.
        val sim = Ic9700Sim()
        val cat = bench(sim)
        cat.setSatelliteMode(true)
        cat.setSatellitePair(435_660_000L, 145_940_000L)
        val apresLePremier = sim.received.size
        repeat(20) { i ->
            cat.setSatellitePair(435_660_000L - i * 100L, 145_940_000L + i * 30L)
        }
        val parTour = (sim.received.size - apresLePremier) / 20
        assertTrue("trop de trames par tour de Doppler : $parTour", parTour <= 4)
        assertEquals(0, sim.refusals)
    }

    @Test
    fun changer_de_satellite_fait_oublier_ce_qu_on_croyait_savoir() = runBlocking {
        // Entre deux passages, l'opérateur touche au poste. Ce qu'on croyait
        // savoir ne vaut plus rien, et forgetBands() est ce qui l'admet.
        val sim = Ic9700Sim()
        val cat = bench(sim)
        cat.setSatelliteMode(true)
        cat.setSatellitePair(435_660_000L, 145_940_000L)
        val avant = sim.received.size
        cat.forgetBands()
        cat.setSatellitePair(435_660_000L, 145_940_000L)
        assertTrue("le poste n'a pas été relu après l'oubli",
            sim.received.size - avant > 4)
        assertEquals(0, sim.refusals)
    }

    @Test
    fun le_journal_enregistre_les_deux_sens_en_clair() = runBlocking {
        CatJournal.clear()
        CatJournal.enabled = true
        try {
            val sim = Ic9700Sim()
            val cat = bench(sim)
            cat.selectMainSub(true)
            cat.setFrequency(145_900_000L)
            cat.readFrequency()
            val e = CatJournal.entries.value
            assertTrue("journal vide", e.size >= 4)
            assertTrue(e.any { it.out && it.text.contains("secondaire") })
            assertTrue(e.any { it.out && it.text.contains("145.90000 MHz") })
            assertTrue(e.any { !it.out && it.text.contains("accusé") })
            assertTrue(e.any { !it.out && it.text.contains("fréquence : 145.90000 MHz") })
            // L'hexadécimal reste là pour qui veut vérifier octet par octet.
            assertTrue(e.first().hex.startsWith("FE FE A2 E0"))
            // Et le journal ne grossit pas indéfiniment.
            assertTrue(e.size <= CatJournal.DEPTH)
        } finally {
            CatJournal.enabled = false
            CatJournal.clear()
        }
    }
}
