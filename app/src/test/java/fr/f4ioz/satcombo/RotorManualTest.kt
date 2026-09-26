/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.cat.CatJournal
import fr.f4ioz.satcombo.cat.SerialLink
import fr.f4ioz.satcombo.rotor.Gs232Rotor
import fr.f4ioz.satcombo.rotor.Gs232Simulator
import fr.f4ioz.satcombo.rotor.RotorMath
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L'essai à la main : forcer le mât vers un angle, et voir ce qui part et ce
 * qui revient.
 *
 * Un passage arrive quand il veut. Celui qui vient de brancher un câble, lui,
 * veut savoir tout de suite si quelque chose sort du téléphone — et il n'a
 * aucun moyen de le savoir tant que la seule chose qui commande le mât est un
 * satellite qui ne passera que dans deux heures. Ces essais couvrent les deux
 * moitiés de la réponse : l'angle tapé doit être ramené dans la course du mât
 * plutôt que refusé pour une question d'écriture, et la trame envoyée comme la
 * trame reçue doivent être conservées, y compris quand il n'y en a pas.
 */
class RotorManualTest {

    @After
    fun apres() { CatJournal.enabled = false; CatJournal.clear() }

    private fun rotor(l: SerialLink): Gs232Rotor {
        val r = Gs232Rotor()
        r.pacingMs = 0L
        r.attach(l)
        return r
    }

    /** Un contrôleur branché qui ne répond jamais : le cas le plus fréquent. */
    private class MuetLink : SerialLink {
        var ecrit = 0; private set
        override fun write(bytes: ByteArray, timeoutMs: Int): Boolean { ecrit++; return true }
        override fun read(buf: ByteArray, timeoutMs: Int): Int = 0
        override fun close() {}
    }

    // ------------------------------------------------------------------
    // L'angle saisi
    // ------------------------------------------------------------------

    @Test
    fun sur_un_mat_a_butee_nord_l_angle_tape_ne_bouge_pas() {
        val l = RotorMath.Limits(azMaxDeg = 450.0, elMaxDeg = 90.0, azStopDeg = 0.0)
        val a = RotorMath.manual(90.0, 45.0, l)
        assertNotNull(a)
        assertEquals(90.0, a!!.azDeg, 1e-9)
        assertEquals(45.0, a.elDeg, 1e-9)
        // Le nord aussi, qui est déjà dans la course : aucun tour n'est ajouté.
        assertEquals(0.0, RotorMath.manual(0.0, 0.0, l)!!.azDeg, 1e-9)
    }

    @Test
    fun sur_un_mat_a_butee_sud_le_nord_se_trouve_a_360() {
        // Un mât à butée sud couvre les azimuts dépliés de 180 à 630. Taper
        // « 0 » n'y est pas une erreur : c'est le nord, et le nord est
        // atteignable — il s'appelle 360 chez lui. `park` refusait, parce que
        // garer ailleurs qu'à l'endroit demandé est une faute ; ici l'endroit
        // demandé est le même point du ciel, écrit autrement.
        val l = RotorMath.Limits(azMaxDeg = 450.0, elMaxDeg = 90.0, azStopDeg = 180.0)
        assertNull("park n'a pas à deviner un tour", RotorMath.park(0.0, 30.0, l))
        val a = RotorMath.manual(0.0, 30.0, l)
        assertNotNull(a)
        assertEquals(360.0, a!!.azDeg, 1e-9)
        // Et ce qui passe déjà passe tel quel.
        assertEquals(200.0, RotorMath.manual(200.0, 30.0, l)!!.azDeg, 1e-9)
        // 90 vrai est atteint deux fois, à 450 comme à 90 — mais 90 est sous la
        // butée : c'est 450 qui sort, le seul des deux qui existe.
        assertEquals(450.0, RotorMath.manual(90.0, 0.0, l)!!.azDeg, 1e-9)
    }

    @Test
    fun un_angle_qui_n_existe_nulle_part_reste_refuse() {
        // Une demi-course, et un azimut qui n'est atteignable à aucun tour.
        val court = RotorMath.Limits(azMaxDeg = 180.0, elMaxDeg = 90.0, azStopDeg = 0.0)
        assertNull(RotorMath.manual(270.0, 0.0, court))
        // L'élévation, elle, n'a pas de tours : on la refuse quand elle sort.
        val l = RotorMath.Limits(azMaxDeg = 450.0, elMaxDeg = 90.0, azStopDeg = 0.0)
        assertNull(RotorMath.manual(90.0, 120.0, l))
        assertNull(RotorMath.manual(90.0, -5.0, l))
    }

    // ------------------------------------------------------------------
    // Ce qui part, ce qui revient
    // ------------------------------------------------------------------

    @Test
    fun la_trame_envoyee_et_la_trame_recue_sont_gardees_en_clair() = runBlocking {
        val sim = Gs232Simulator()
        val r = rotor(sim)
        assertTrue(r.moveTo(90.0, 45.0))
        assertEquals("W090 045", r.lastSent)
        sim.advance(60_000)
        assertNotNull(r.readPosition())
        assertEquals("C2", r.lastSent)
        assertEquals("AZ=090 EL=045", r.lastReply.trim())
        assertTrue(r.stop())
        assertEquals("S", r.lastSent)
    }

    @Test
    fun un_controleur_muet_laisse_la_trame_recue_vide() = runBlocking {
        // Le silence est une information, et c'est même la seule qui compte
        // quand rien ne marche : un port ouvert sans personne au bout ressemble
        // en tout point à un mât qui n'a pas fini de tourner. La distinction ne
        // tient qu'à cette chaîne vide, affichée telle quelle.
        val muet = MuetLink()
        val r = rotor(muet)
        assertTrue(r.moveTo(90.0, 45.0))
        assertEquals("W090 045", r.lastSent)
        assertNull(r.readPosition())
        assertEquals("", r.lastReply)
        assertTrue("la consigne n'est même pas partie", muet.ecrit >= 2)
    }

    @Test
    fun un_pilote_non_branche_ne_pretend_pas_avoir_parle() = runBlocking {
        val r = Gs232Rotor()
        r.pacingMs = 0L
        assertTrue(!r.moveTo(90.0, 45.0))
        assertEquals("", r.lastSent)
        assertNull(r.readPosition())
        assertEquals("", r.lastReply)
        assertEquals("", r.lastError)
    }
}
