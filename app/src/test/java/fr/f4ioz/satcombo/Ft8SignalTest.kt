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
import fr.f4ioz.satcombo.domain.Ft8Signal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Le banc de la démodulation.
 *
 * On fabrique le signal soi-même à partir de symboles connus, on le maltraite —
 * décalé dans le temps, décentré en fréquence, noyé dans le bruit — et l'on
 * regarde si les mêmes symboles ressortent. Pour cette partie-là le banc est
 * plus sévère que le terrain, parce qu'on connaît la vérité.
 */
class Ft8SignalTest {

    private val MODE = Ft8Signal.FT8

    /** Une suite de 79 symboles licite : Costas aux bons endroits. */
    private fun symbolesExemple(graine: Long = 7): IntArray {
        val alea = java.util.Random(graine)
        val bits = BooleanArray(Ft8.BITS) { alea.nextBoolean() }
        return Ft8.bitsVersSymboles(bits)
    }

    /** La chaîne complète : audio → spectrogramme → meilleur candidat → tons. */
    private fun demodule(
        audio: FloatArray, basseHz: Double = 900.0, hauteHz: Double = 1200.0
    ): Pair<Ft8Signal.Candidat, IntArray>? {
        val spec = Ft8Signal.spectrogramme(audio, MODE, basseHz, hauteHz)
        val c = Ft8Signal.candidats(spec, maximum = 4).firstOrNull() ?: return null
        return c to Ft8Signal.tons(spec, c)
    }

    // ------------------------------------------------------------- fondations

    @Test
    fun la_fft_place_un_ton_sur_sa_raie() {
        val n = 1024
        val re = FloatArray(n) { sin(2.0 * PI * 64 * it / n).toFloat() }
        val im = FloatArray(n)
        Ft8Signal.fft(re, im)
        var meilleure = 0
        var max = -1.0
        for (b in 1 until n / 2) {
            val p = re[b] * re[b] + im[b] * im[b].toDouble()
            if (p > max) { max = p; meilleure = b }
        }
        assertEquals(64, meilleure)
    }

    @Test
    fun le_reechantillonnage_garde_la_frequence() {
        val de = 48000.0
        val vers = MODE.cadenceHz
        val entree = FloatArray(48000) { sin(2.0 * PI * 1000.0 * it / de).toFloat() }
        val sortie = Ft8Signal.reechantillonne(entree, de, vers)
        // Une seconde à la nouvelle cadence, à l'arrondi près.
        assertTrue(abs(vers.toInt() - sortie.size) <= 4)
        // Le ton doit toujours tomber sur la raie des 1000 Hz.
        val n = 2048
        val re = FloatArray(n) { sortie[4096 + it] }
        val im = FloatArray(n)
        Ft8Signal.fft(re, im)
        var meilleure = 0; var max = -1.0
        for (b in 1 until n / 2) {
            val p = re[b] * re[b] + im[b] * im[b].toDouble()
            if (p > max) { max = p; meilleure = b }
        }
        assertEquals(1000.0, meilleure * vers / n, vers / n)
    }

    @Test
    fun la_cadence_interne_donne_un_symbole_entier() {
        assertEquals(12800.0, MODE.cadenceHz, 1e-6)
        assertEquals(6.25, MODE.ecartHz, 1e-9)
        // Une puissance de deux, sans quoi la transformée refuserait.
        assertTrue(MODE.parSymbole and (MODE.parSymbole - 1) == 0)
    }

    // ------------------------------------------------------ le cas nominal

    @Test
    fun un_signal_propre_rend_exactement_ses_symboles() {
        val attendus = symbolesExemple()
        val audio = Ft8Signal.synthetise(attendus, MODE, 1000.0, decalageS = 0.5)
        val r = demodule(audio)
        assertNotNull(r)
        val (c, lus) = r!!
        assertEquals(1000.0, c.frequenceHz(Ft8Signal.spectrogramme(audio, MODE, 900.0, 1200.0)),
            MODE.ecartHz / 2)
        assertEquals(0.5, c.instantS(Ft8Signal.spectrogramme(audio, MODE, 900.0, 1200.0)),
            MODE.dureeSymbole)
        assertEquals(attendus.toList(), lus.toList())
    }

    @Test
    fun les_reperes_de_costas_tombent_tous_juste() {
        val attendus = symbolesExemple(11)
        val audio = Ft8Signal.synthetise(attendus, MODE, 1050.0, decalageS = 0.32)
        val (_, lus) = demodule(audio)!!
        assertEquals(21, Ft8.scoreCostas(lus))
    }

    @Test
    fun laller_retour_complet_retrouve_les_bits() {
        val alea = java.util.Random(3)
        val bits = BooleanArray(Ft8.BITS) { alea.nextBoolean() }
        val audio = Ft8Signal.synthetise(Ft8.bitsVersSymboles(bits), MODE, 1000.0, 0.48)
        val (_, lus) = demodule(audio)!!
        assertEquals(bits.toList(), Ft8.symbolesVersBits(lus).toList())
    }

    // ------------------------------------------ décalages en temps et fréquence

    @Test
    fun un_decalage_de_temps_quelconque_passe() {
        val attendus = symbolesExemple(5)
        // Un dixième de symbole : rien d'aligné sur la grille d'analyse.
        for (d in listOf(0.30, 0.416, 0.55, 0.704)) {
            val audio = Ft8Signal.synthetise(attendus, MODE, 1000.0, decalageS = d)
            val (_, lus) = demodule(audio) ?: error("rien trouvé à $d s")
            assertEquals("décalage $d s", attendus.toList(), lus.toList())
        }
    }

    @Test
    fun un_signal_entre_deux_raies_passe_aussi() {
        val attendus = symbolesExemple(9)
        // Pile entre deux raies : le pire cas pour une analyse par transformée.
        val audio = Ft8Signal.synthetise(attendus, MODE, 1003.125, decalageS = 0.5)
        val (_, lus) = demodule(audio) ?: error("rien trouvé")
        assertEquals(attendus.toList(), lus.toList())
    }

    @Test
    fun le_decalage_doppler_dun_passage_ne_gene_pas() {
        // Un satellite bas décale de plusieurs kilohertz ; ce qui compte est que
        // la recherche balaie large, pas que la fréquence soit celle prévue.
        val attendus = symbolesExemple(13)
        val audio = Ft8Signal.synthetise(attendus, MODE, 2400.0, decalageS = 0.5)
        val spec = Ft8Signal.spectrogramme(audio, MODE, 300.0, 2800.0)
        val c = Ft8Signal.candidats(spec, maximum = 4).firstOrNull()
        assertNotNull(c)
        assertEquals(2400.0, c!!.frequenceHz(spec), MODE.ecartHz / 2)
        assertEquals(attendus.toList(), Ft8Signal.tons(spec, c).toList())
    }

    // -------------------------------------------------------------- le bruit

    @Test
    fun un_signal_fort_se_decode_dans_le_bruit() {
        val attendus = symbolesExemple(17)
        val propre = Ft8Signal.synthetise(attendus, MODE, 1000.0, decalageS = 0.5)
        val bruite = Ft8Signal.avecBruit(propre, MODE, rapportDb = 10.0, graine = 42)
        val (_, lus) = demodule(bruite) ?: error("rien trouvé")
        assertEquals(attendus.toList(), lus.toList())
    }

    @Test
    fun la_synchronisation_tient_plus_bas_que_le_decodage() {
        // Le repérage survit là où la lecture dure des symboles échoue déjà.
        // C'est exactement la marge qu'un décodeur LDPC viendrait exploiter.
        val attendus = symbolesExemple(23)
        val propre = Ft8Signal.synthetise(attendus, MODE, 1000.0, decalageS = 0.5)
        val bruite = Ft8Signal.avecBruit(propre, MODE, rapportDb = -3.0, graine = 5)
        val spec = Ft8Signal.spectrogramme(bruite, MODE, 900.0, 1200.0)
        val c = Ft8Signal.candidats(spec, maximum = 8).firstOrNull()
        assertNotNull("le signal n'a pas été repéré", c)
        assertEquals(1000.0, c!!.frequenceHz(spec), MODE.ecartHz)
    }

    @Test
    fun le_bruit_seul_ne_fabrique_pas_de_message() {
        // Un décodeur qui invente est pire qu'un décodeur qui rate : on vérifie
        // qu'aucun candidat tiré du bruit ne franchit le contrôle.
        val bruit = Ft8Signal.avecBruit(
            FloatArray((MODE.dureeS * MODE.cadenceHz).toInt() + 12800) { 0.01f },
            MODE, rapportDb = -30.0, graine = 99)
        val spec = Ft8Signal.spectrogramme(bruit, MODE, 900.0, 1200.0)
        for (c in Ft8Signal.candidats(spec, maximum = 8)) {
            val bits = Ft8.symbolesVersBits(Ft8Signal.tons(spec, c))
            assertTrue("un message est sorti du bruit",
                !Ft8.controleJuste(bits.copyOf(Ft8.BITS_UTILES)))
        }
    }

    // ------------------------------------------------------ plusieurs stations

    @Test
    fun deux_stations_cote_a_cote_sont_vues_toutes_les_deux() {
        val a = symbolesExemple(31)
        val b = symbolesExemple(37)
        val sa = Ft8Signal.synthetise(a, MODE, 1000.0, decalageS = 0.5)
        val sb = Ft8Signal.synthetise(b, MODE, 1100.0, decalageS = 0.5)
        val melange = FloatArray(sa.size) { sa[it] + sb[it] }
        val spec = Ft8Signal.spectrogramme(melange, MODE, 900.0, 1200.0)
        val trouves = Ft8Signal.candidats(spec, maximum = 8)
        assertTrue("il en faut au moins deux", trouves.size >= 2)
        val lus = trouves.map { Ft8Signal.tons(spec, it).toList() }
        assertTrue("la première manque", lus.contains(a.toList()))
        assertTrue("la seconde manque", lus.contains(b.toList()))
    }

    @Test
    fun un_meme_signal_ne_prend_pas_toutes_les_places() {
        val a = symbolesExemple(41)
        val audio = Ft8Signal.synthetise(a, MODE, 1000.0, decalageS = 0.5, amplitude = 0.9f)
        val spec = Ft8Signal.spectrogramme(audio, MODE, 900.0, 1200.0)
        val trouves = Ft8Signal.candidats(spec, maximum = 16)
        // Sans le tri des voisins, les seize places seraient seize vues du même.
        assertTrue("trop de doublons : ${trouves.size}", trouves.size <= 2)
    }

    // ------------------------------------------------------- les vraisemblances

    @Test
    fun les_vraisemblances_saccordent_avec_les_decisions_dures() {
        val attendus = symbolesExemple(43)
        val audio = Ft8Signal.synthetise(attendus, MODE, 1000.0, decalageS = 0.5)
        val spec = Ft8Signal.spectrogramme(audio, MODE, 900.0, 1200.0)
        val c = Ft8Signal.candidats(spec, maximum = 2).first()
        val douces = Ft8Signal.vraisemblances(spec, c)
        val dures = Ft8.symbolesVersBits(Ft8Signal.tons(spec, c))
        assertEquals(Ft8.BITS, douces.size)
        for (i in 0 until Ft8.BITS) {
            val depuisDouce = douces[i] < 0f      // négatif = un
            assertEquals("bit $i", dures[i], depuisDouce)
        }
    }

    @Test
    fun un_signal_franc_donne_des_vraisemblances_franches() {
        val attendus = symbolesExemple(47)
        val audio = Ft8Signal.synthetise(attendus, MODE, 1000.0, decalageS = 0.5)
        val spec = Ft8Signal.spectrogramme(audio, MODE, 900.0, 1200.0)
        val c = Ft8Signal.candidats(spec, maximum = 2).first()
        val douces = Ft8Signal.vraisemblances(spec, c)
        val moyenne = douces.map { abs(it) }.average()
        assertTrue("vraisemblances trop molles : $moyenne", moyenne > 1.0)
    }

    // ------------------------------------------------------------------ FT4

    @Test
    fun ft4_a_ses_parametres_verifies_mais_pas_ses_reperes() {
        val m = Ft8Signal.FT4
        assertEquals(4, m.tons)
        assertEquals(105, m.symboles)
        assertEquals(12000.0 / 576.0, m.ecartHz, 1e-6)
        assertEquals(0.048, m.dureeSymbole, 1e-6)
        // 4 × 20,8333 = 83,3 Hz de largeur.
        assertEquals(83.33, 4 * m.ecartHz, 0.01)
        assertTrue(m.parSymbole and (m.parSymbole - 1) == 0)
        // Quatre réseaux de quatre symboles : seize repères.
        assertEquals(16, m.synchro.size)
        // Les deux modes ne se lissent pas pareil : BT = 2 pour FT8, BT = 1
        // pour FT4, dont l'impulsion est plus fortement adoucie.
        assertEquals(2.0, Ft8Signal.FT8.lissageBT, 1e-9)
        assertEquals(1.0, m.lissageBT, 1e-9)
        // Du silence ne donne aucun candidat : pas de plancher de bruit, donc
        // rien à comparer.
        val spec = Ft8Signal.spectrogramme(FloatArray(200_000), m, 900.0, 1200.0)
        assertTrue(Ft8Signal.candidats(spec).isEmpty())
    }
}
