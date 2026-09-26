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
import fr.f4ioz.satcombo.cat.Ft817Cat
import fr.f4ioz.satcombo.cat.Ft817Pair
import fr.f4ioz.satcombo.cat.Ft817Sim
import fr.f4ioz.satcombo.cat.SerialLink
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La paire de FT-817, contre deux postes qui n'existent pas.
 *
 * Douze essais. Le dialecte Yaesu n'a ni adresse ni délimiteur : cinq octets à
 * l'aller, toujours, et une réponse dont la longueur dépend de la question. Il
 * n'y a donc rien à reconnaître, seulement à compter — et un octet manquant
 * décale tout ce qui suit sans que rien ne le signale. D'où l'essai qui fait
 * arriver la réponse octet par octet, et celui qui vérifie le bit d'état, mis
 * en **réception** et non en émission, ce qui est exactement l'inverse de ce
 * que l'on écrit spontanément.
 */
class Ft817Test {

    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun rig(sim: Ft817Sim): Ft817Cat {
        val cat = Ft817Cat()
        cat.pacingMs = 0L        // au banc, il n'y a personne à ménager
        cat.attach(sim)
        return cat
    }

    private fun pair(rxSim: Ft817Sim, txSim: Ft817Sim): Ft817Pair {
        val p = Ft817Pair()
        p.attach(rxSim, txSim)
        p.pacingMs = 0L
        return p
    }

    /**
     * Un fil série qui ne rend qu'un octet à la fois.
     *
     * C'est le comportement réel d'un adaptateur USB à 4800 bauds : cinq octets
     * mettent une dizaine de millisecondes à passer et n'arrivent presque jamais
     * d'un seul coup. Une lecture unique en attrapait un ou deux, et le pilote
     * concluait « pas de réponse ».
     */
    private class DribbleLink(private val inner: SerialLink) : SerialLink {
        override fun write(bytes: ByteArray, timeoutMs: Int): Boolean = inner.write(bytes, timeoutMs)
        override fun read(buf: ByteArray, timeoutMs: Int): Int {
            if (buf.isEmpty()) return 0
            val un = ByteArray(1)
            if (inner.read(un, timeoutMs) <= 0) return 0
            buf[0] = un[0]
            return 1
        }
        override fun close() { inner.close() }
    }

    @Test
    fun une_sequence_saine_ne_fait_rien_refuser_au_couple() = runBlocking {
        val rxSim = Ft817Sim()
        val txSim = Ft817Sim()
        val r = CatBench.runFt817Pair(rxSim, txSim)
        assertEquals("refus des postes simulés : ${r.steps}", 0, r.refusals)
        assertTrue(r.ok)
        assertEquals(145_800_000L, rxSim.hz)
        assertEquals(437_800_000L, txSim.hz)
    }

    @Test
    fun la_frequence_fait_l_aller_retour() = runBlocking {
        val sim = Ft817Sim()
        val cat = rig(sim)
        assertTrue(cat.setFrequency(435_120_000L))
        assertEquals(435_120_000L, sim.hz)
        assertEquals(435_120_000L, cat.readFrequency())
        assertEquals(0, sim.refusals)
    }

    @Test
    fun les_unites_de_hertz_disparaissent_comme_le_veut_le_poste() = runBlocking {
        // Le pas du FT-817 est de dix hertz. Ce n'est pas un défaut à corriger :
        // c'est une limite à connaître, sans quoi la correction Doppler croit
        // écrire une valeur que le poste n'affichera jamais.
        val sim = Ft817Sim()
        val cat = rig(sim)
        cat.setFrequency(145_800_007L)
        assertEquals(145_800_000L, sim.hz)
        cat.setFrequency(145_800_019L)
        assertEquals(145_800_010L, sim.hz)
    }

    @Test
    fun la_lecture_rend_la_frequence_et_le_mode_ensemble() = runBlocking {
        // Une seule question, cinq octets de réponse : quatre de fréquence et un
        // de mode. Les séparer coûterait un aller-retour de plus par seconde.
        val sim = Ft817Sim()
        val cat = rig(sim)
        cat.setFrequency(437_800_000L)
        assertTrue(cat.setMode("FM"))
        assertEquals(0x08, sim.mode)
        val (hz, mode) = cat.readFrequencyAndMode()!!
        assertEquals(437_800_000L, hz)
        assertEquals(0x08, mode)
    }

    @Test
    fun le_bit_d_etat_est_mis_quand_le_poste_recoit() = runBlocking {
        // Le manuel dit bien : bit 7 MIS en réception. L'écrire à l'envers
        // revient à écrire sur le VFO d'un poste en pleine émission.
        val sim = Ft817Sim()
        sim.transmitting = false
        val cat = rig(sim)
        assertEquals(false, cat.isTransmitting())
    }

    @Test
    fun le_bit_d_etat_s_efface_quand_le_poste_emet() = runBlocking {
        val sim = Ft817Sim()
        sim.transmitting = true
        val cat = rig(sim)
        assertEquals(true, cat.isTransmitting())
    }

    @Test
    fun on_n_ecrit_pas_sur_un_poste_qui_emet() = runBlocking {
        // Sécurité semi-duplex, celle de SatPC32 : pendant que l'opérateur
        // parle, la montée ne bouge pas. La descente, elle, continue d'être
        // corrigée — c'est tout l'intérêt d'avoir deux postes.
        val rxSim = Ft817Sim()
        val txSim = Ft817Sim()
        txSim.transmitting = true
        val p = pair(rxSim, txSim)
        val avant = txSim.hz
        p.setPair(145_805_000L, 437_805_000L)
        assertEquals("la montée a bougé pendant l'émission", avant, txSim.hz)
        assertEquals(145_805_000L, rxSim.hz)
        // Et dès que le PTT retombe, la montée repart.
        txSim.transmitting = false
        p.setUplink(437_805_000L)
        assertEquals(437_805_000L, txSim.hz)
    }

    @Test
    fun le_ton_d_acces_part_en_gros_boutien() = runBlocking {
        // Ici l'encodage était déjà juste, et c'est précisément ce qui rend la
        // comparaison utile : le même ton, deux dialectes, une seule des deux
        // implémentations était fausse.
        val sim = Ft817Sim()
        val cat = rig(sim)
        assertTrue(cat.setCtcss(885))
        assertEquals(885, sim.toneTenthHz)
        assertEquals(0x4A, sim.toneMode)
        // La trame 0x0B, relue octet par octet : 08 85, et non 88 50.
        val ton = sim.received.last()
        assertEquals(0x0B, ton[4].toInt() and 0xFF)
        assertEquals(0x08, ton[0].toInt() and 0xFF)
        assertEquals(0x85, ton[1].toInt() and 0xFF)
        // Et zéro coupe le ton, sans rien envoyer de plus.
        assertTrue(cat.setCtcss(0))
        assertEquals(0x8A, sim.toneMode)
        assertEquals(0, sim.refusals)
    }

    @Test
    fun un_ton_hors_plage_ne_part_meme_pas() = runBlocking {
        val sim = Ft817Sim()
        val cat = rig(sim)
        val avant = sim.received.size
        assertTrue(!cat.setCtcss(8850))
        assertTrue(!cat.setCtcss(CatDecode.TONE_MAX_TENTH + 1))
        assertEquals("une trame est partie quand même", avant, sim.received.size)
        assertEquals(0, sim.refusals)
        // La borne, elle, passe.
        assertTrue(cat.setCtcss(CatDecode.TONE_MAX_TENTH))
        assertEquals(CatDecode.TONE_MAX_TENTH, sim.toneTenthHz)
    }

    @Test
    fun la_reponse_qui_arrive_octet_par_octet_est_rassemblee() = runBlocking {
        // Le défaut le plus discret des deux pilotes : une lecture unique. Sur
        // un vrai câble, la réponse arrive en morceaux, et le pilote concluait
        // « pas de réponse » une fois sur deux.
        val sim = Ft817Sim()
        val cat = Ft817Cat()
        cat.pacingMs = 0L
        cat.attach(DribbleLink(sim))
        cat.setFrequency(435_500_000L)
        assertEquals(435_500_000L, cat.readFrequency())
        assertEquals(false, cat.isTransmitting())
    }

    @Test
    fun le_dialecte_yaesu_se_relit_octet_par_octet() {
        // Sans radio ni simulateur : les octets à la main, contre le manuel.
        assertArrayEqualsInt(intArrayOf(0x43, 0x78, 0x00, 0x00), CatDecode.yaesuFreq(437_800_000L))
        assertEquals(437_800_000L, CatDecode.yaesuFreqOf(b(0x43, 0x78, 0x00, 0x00)))
        // Une réponse d'état, dans les deux sens.
        assertTrue(CatDecode.describeYaesu(b(0x00), fromRig = true, lastOp = 0xF7).contains("émet"))
        assertTrue(CatDecode.describeYaesu(b(0x08, 0x00, 0x00, 0x00, 0x07), fromRig = false)
            .contains("FM"))
        assertNotNull(CatDecode.yaesuFreqOf(CatDecode.yaesuFreq(29_600_000L)))
    }

    @Test
    fun un_couple_non_branche_rend_null_plutot_que_d_exploser() = runBlocking {
        val p = Ft817Pair()
        assertTrue(!p.isOpen)
        assertTrue(!p.bothOpen)
        assertNull(p.readDownlink())
        p.setPair(145_800_000L, 437_800_000L)   // ne doit rien faire, et surtout pas planter
        p.setCtcss(670)
        val cat = Ft817Cat()
        cat.pacingMs = 0L
        assertTrue(!cat.isOpen)
        assertNull(cat.readFrequency())
        assertNull(cat.readFrequencyAndMode())
        assertNull(cat.isTransmitting())
        assertTrue(!cat.setFrequency(435_000_000L))
        // Un poste refermé se comporte de même.
        val sim = Ft817Sim()
        val ouvert = rig(sim)
        ouvert.close()
        assertTrue(!ouvert.isOpen)
        assertTrue(sim.isClosed)
    }

    private fun assertArrayEqualsInt(expected: IntArray, actual: ByteArray) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) assertEquals("octet $i", expected[i], actual[i].toInt() and 0xFF)
    }
}
