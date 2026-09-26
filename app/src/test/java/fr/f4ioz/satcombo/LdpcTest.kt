/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.Ft8
import fr.f4ioz.satcombo.domain.Ft8Signal
import fr.f4ioz.satcombo.domain.Ldpc
import fr.f4ioz.satcombo.domain.LdpcTables
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The LDPC error-correcting code.
 *
 * Two questions: how much can a message be damaged before it is lost? And,
 * just as important: what does the decoder return when fed pure noise?
 */
class LdpcTest {

    private fun message(graine: Long): BooleanArray {
        val alea = java.util.Random(graine)
        return BooleanArray(LdpcTables.K) { alea.nextBoolean() }
    }

    // ---- tables ----

    @Test
    fun les_tables_ont_les_bonnes_dimensions() {
        assertEquals(174, LdpcTables.N)
        assertEquals(91, LdpcTables.K)
        assertEquals(83, LdpcTables.M)
        assertEquals(83, LdpcTables.generateur.size)
        assertEquals(91, LdpcTables.generateur[0].size)
        assertEquals(83, LdpcTables.bitsDuControle.size)
        assertEquals(174, LdpcTables.controlesDuBit.size)
    }

    @Test
    fun chaque_bit_participe_a_exactement_trois_controles() {
        for (n in 0 until LdpcTables.N) {
            assertEquals("bit $n", 3, LdpcTables.controlesDuBit[n].size)
            for (c in LdpcTables.controlesDuBit[n]) {
                assertTrue("contrôle hors bornes pour le bit $n", c in 0 until LdpcTables.M)
            }
        }
    }

    @Test
    fun le_graphe_est_cohérent_dans_les_deux_sens() {
        // If a check claims a bit, that bit must list the check. A mistyped
        // table shows up here and nowhere else before the field.
        for (m in 0 until LdpcTables.M) {
            for (n in LdpcTables.bitsDuControle[m]) {
                assertTrue("le contrôle $m porte sur le bit $n, qui l'ignore",
                    LdpcTables.controlesDuBit[n].contains(m))
            }
        }
        val aretes = (0 until LdpcTables.M).sumOf { LdpcTables.bitsDuControle[it].size }
        assertEquals(174 * 3, aretes)
    }

    // ---- encoding ----

    @Test
    fun un_mot_encodé_satisfait_tous_les_controles() {
        for (g in 1L..20L) {
            val mot = Ldpc.encode(message(g))
            assertEquals("graine $g", 0, Ldpc.controlesViolés(mot))
        }
    }

    @Test
    fun lencodage_est_systematique() {
        // The first 91 bits come out intact, so the message can be read
        // without inverting anything.
        val m = message(3)
        val mot = Ldpc.encode(m)
        assertEquals(m.toList(), mot.copyOf(LdpcTables.K).toList())
    }

    @Test
    fun un_seul_bit_retourné_casse_au_moins_un_controle() {
        val mot = Ldpc.encode(message(5))
        for (i in 0 until LdpcTables.N) {
            val abime = mot.copyOf(); abime[i] = !abime[i]
            assertTrue("bit $i invisible", Ldpc.controlesViolés(abime) > 0)
        }
    }

    // ---- decoding ----

    @Test
    fun un_mot_parfait_se_decode_du_premier_coup() {
        val mot = Ldpc.encode(message(7))
        val r = Ldpc.decode(Ldpc.vraisemblancesParfaites(mot))
        assertNotNull(r.bits)
        assertEquals(mot.toList(), r.bits!!.toList())
        assertEquals(1, r.tours)
    }

    @Test
    fun le_code_tient_un_canal_bruité() {
        // A Gaussian channel, like the real one. Mean amplitude 1, noise
        // sigma 0.7: about 8% wrong bits (13 of 174). The 14-bit CRC alone
        // would have rejected all twenty trials.
        val alea = java.util.Random(11)
        var repares = 0
        var fautesBrutes = 0
        for (essai in 0 until 20) {
            val mot = Ldpc.encode(message(100L + essai))
            val vrais = FloatArray(LdpcTables.N) { i ->
                (if (mot[i]) -1.0 else 1.0).plus(alea.nextGaussian() * 0.7).toFloat()
            }
            for (i in 0 until LdpcTables.N) if ((vrais[i] < 0f) != mot[i]) fautesBrutes++
            val r = Ldpc.decode(vrais)
            if (r.bits != null && r.bits!!.toList() == mot.toList()) repares++
        }
        assertTrue("le canal devait être vraiment abîmé : $fautesBrutes fautes",
            fautesBrutes > 100)
        assertTrue("seulement $repares/20 réparés", repares >= 10)
    }

    @Test
    fun quelques_bits_faux_mais_assurés_se_reparent_aussi() {
        // The hard case: wrong bits that look confident. A demodulator makes
        // them when a nearby tone clearly wins (interference, spurious
        // carrier). The code absorbs a few, far fewer than merely uncertain
        // bits: misplaced confidence is what costs, not the error itself.
        val alea = java.util.Random(23)
        var repares = 0
        for (essai in 0 until 20) {
            val mot = Ldpc.encode(message(200L + essai))
            val vrais = Ldpc.vraisemblancesParfaites(mot)
            val vus = HashSet<Int>()
            while (vus.size < 4) vus.add(alea.nextInt(LdpcTables.N))
            for (i in vus) vrais[i] = -vrais[i]
            val r = Ldpc.decode(vrais)
            if (r.bits != null && r.bits!!.toList() == mot.toList()) repares++
        }
        assertTrue("seulement $repares/20 réparés", repares >= 12)
    }

    @Test
    fun un_bit_effacé_ne_gene_pas() {
        // A zero likelihood means "don't know". The code must cope with it
        // better than with a confident wrong value.
        val mot = Ldpc.encode(message(13))
        val vrais = Ldpc.vraisemblancesParfaites(mot)
        for (i in 0 until 25) vrais[i] = 0f
        val r = Ldpc.decode(vrais)
        assertNotNull(r.bits)
        assertEquals(mot.toList(), r.bits!!.toList())
    }

    @Test
    fun du_bruit_pur_ne_rend_rien() {
        // The branch that matters. Returning the most likely word despite
        // failed checks would be inventing, and an invented callsign ends up in
        // a log — then in someone else's.
        val alea = java.util.Random(17)
        var inventions = 0
        for (essai in 0 until 30) {
            val vrais = FloatArray(LdpcTables.N) { (alea.nextGaussian() * 0.5).toFloat() }
            val r = Ldpc.decode(vrais)
            if (r.bits != null) inventions++
        }
        // Noise may hit a codeword by chance, but rarely; the 14-bit CRC then
        // removes the rest.
        assertTrue("$inventions inventions sur 30", inventions <= 3)
    }

    @Test
    fun un_echec_se_declare_comme_tel() {
        val alea = java.util.Random(19)
        val vrais = FloatArray(LdpcTables.N) { (alea.nextGaussian() * 0.1).toFloat() }
        val r = Ldpc.decode(vrais, toursMax = 8)
        if (r.bits == null) {
            assertTrue("un échec doit laisser des contrôles violés", r.restants > 0)
            assertEquals(8, r.tours)
        }
    }

    // ---- end to end ----

    @Test
    fun un_signal_faible_se_decode_grace_au_code() {
        // The signal is deliberately placed where hard decisions failed.
        val m = BooleanArray(Ft8.BITS_MESSAGE)
        Ft8.ecritEntier(m, 0, 28, Ft8.indicatifVers28("W9XYZ")!!)
        Ft8.ecritEntier(m, 29, 28, Ft8.indicatifVers28("F4IOZ")!!)
        Ft8.ecritEntier(m, 74, 3, 1L)
        val utiles = Ft8.avecControle(m)
        val mot = Ldpc.encode(utiles)
        assertEquals(0, Ldpc.controlesViolés(mot))

        val mode = Ft8Signal.FT8
        val propre = Ft8Signal.synthetise(
            Ft8.bitsVersSymboles(mot), mode, 1000.0, decalageS = 0.5)
        val bruite = Ft8Signal.avecBruit(propre, mode, rapportDb = -2.0, graine = 7)
        val spec = Ft8Signal.spectrogramme(bruite, mode, 900.0, 1200.0)
        val c = Ft8Signal.candidats(spec, maximum = 4).firstOrNull()
        assertNotNull("le signal n'a pas été repéré", c)

        val douces = Ft8Signal.vraisemblances(spec, c!!)
        val r = Ldpc.decode(douces)
        assertNotNull("le code n'a pas convergé", r.bits)
        assertEquals(mot.toList(), r.bits!!.toList())
        // And the 14-bit CRC passes on top.
        assertTrue(Ft8.controleJuste(r.bits!!.copyOf(Ft8.BITS_UTILES)))
    }

    @Test
    fun le_code_ne_gene_pas_un_signal_fort() {
        val mot = Ldpc.encode(Ft8.avecControle(BooleanArray(Ft8.BITS_MESSAGE) { it % 3 == 0 }))
        val mode = Ft8Signal.FT8
        val audio = Ft8Signal.synthetise(
            Ft8.bitsVersSymboles(mot), mode, 1000.0, decalageS = 0.5)
        val spec = Ft8Signal.spectrogramme(audio, mode, 900.0, 1200.0)
        val c = Ft8Signal.candidats(spec, maximum = 2).first()
        val r = Ldpc.decode(Ft8Signal.vraisemblances(spec, c))
        assertNotNull(r.bits)
        assertEquals(mot.toList(), r.bits!!.toList())
    }
}
