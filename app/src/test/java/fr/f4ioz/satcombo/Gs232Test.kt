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
import fr.f4ioz.satcombo.rotor.Gs232Codec
import fr.f4ioz.satcombo.rotor.Gs232Rotor
import fr.f4ioz.satcombo.rotor.Gs232Simulator
import fr.f4ioz.satcombo.rotor.RotorPos
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le pilote GS-232, contre un contrôleur qui n'existe pas et un mât qui met du
 * temps à tourner.
 *
 * Onze essais. Le dialecte est du texte, ce qui le rend trompeusement facile :
 * il n'y a ni somme de contrôle, ni accusé de réception, ni longueur annoncée.
 * Le contrôleur ne dit jamais « je n'ai pas compris » — il ne fait rien. Un mât
 * qui n'a pas reçu l'ordre et un mât qui n'a pas fini de tourner se ressemblent
 * exactement, vus de l'application, et c'est pour cela que le contrôleur simulé
 * compte ses refus : c'est la seule façon d'affirmer qu'une séquence saine a
 * été comprise, et pas seulement qu'elle n'a rien fait planter.
 */
class Gs232Test {

    @After
    fun apres() { CatJournal.enabled = false; CatJournal.clear() }

    private fun rotor(sim: SerialLink): Gs232Rotor {
        val r = Gs232Rotor()
        r.pacingMs = 0L          // au banc, il n'y a personne à ménager
        r.attach(sim)
        return r
    }

    /**
     * Un fil série qui ne rend qu'un caractère à la fois.
     *
     * C'est le comportement réel d'un adaptateur USB : à 9600 bauds,
     * `AZ=180 EL=045` met une douzaine de millisecondes à passer et n'arrive
     * presque jamais d'un seul coup.
     */
    private class DribbleLink(private val inner: SerialLink) : SerialLink {
        override fun write(bytes: ByteArray, timeoutMs: Int) = inner.write(bytes, timeoutMs)
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
    fun la_consigne_tient_toujours_en_trois_chiffres() {
        // Le contrôleur lit des colonnes fixes. `W180 45` n'est pas « presque
        // bon » : c'est une élévation de 450 degrés lue sur un champ décalé.
        assertEquals("W180 045\r", Gs232Codec.moveCommand(180.0, 45.0))
        assertEquals("W005 000\r", Gs232Codec.moveCommand(5.4, 0.0))
        assertEquals("W450 180\r", Gs232Codec.moveCommand(450.0, 179.6))
        assertEquals("C2\r", Gs232Codec.QUERY)
        assertEquals("S\r", Gs232Codec.STOP)
    }

    @Test
    fun une_consigne_qui_ne_tient_pas_dans_le_format_ne_part_pas() {
        assertNull(Gs232Codec.moveCommand(-1.0, 0.0))
        assertNull(Gs232Codec.moveCommand(1000.0, 0.0))
        assertNull(Gs232Codec.moveCommand(0.0, -0.6))
        assertNull(Gs232Codec.moveCommand(Double.NaN, 0.0))
    }

    @Test
    fun les_deux_dialectes_de_position_se_relisent() {
        // Un GS-232B répond `AZ=180 EL=045`, un GS-232A `+0180+0045`, et le même
        // boîtier passe de l'un à l'autre selon un cavalier interne que personne
        // ne se souvient d'avoir déplacé.
        assertEquals(RotorPos(180.0, 45.0), Gs232Codec.parsePosition("AZ=180 EL=045\r"))
        assertEquals(RotorPos(180.0, 45.0), Gs232Codec.parsePosition("+0180+0045"))
        assertEquals(RotorPos(12.0, 3.0), Gs232Codec.parsePosition("az=012 el=003"))
        assertEquals(RotorPos(359.0, 0.0), Gs232Codec.parsePosition("AZ=359  EL=000"))
    }

    @Test
    fun une_reponse_incomprehensible_rend_null_plutot_que_zero() {
        // Rendre « azimut zéro » sur une réponse qu'on n'a pas comprise ferait
        // partir le mât vers le nord au premier parasite du câble.
        assertNull(Gs232Codec.parsePosition("?>"))
        assertNull(Gs232Codec.parsePosition("AZ=1"))
        assertNull(Gs232Codec.parsePosition(""))
        assertNull(Gs232Codec.parsePosition("W180 045"))
    }

    @Test
    fun le_mat_simule_ne_tourne_pas_plus_vite_que_sa_mecanique() = runBlocking {
        // Six degrés par seconde, c'est la vitesse d'un G-5500 : un demi-tour
        // prend une minute, pendant laquelle le satellite a continué son chemin.
        // Toute la valeur du recouvrement tient dans ces secondes-là.
        val sim = Gs232Simulator()
        val r = rotor(sim)
        assertTrue(r.moveTo(180.0, 0.0))
        sim.advance(1000)
        assertEquals(6.0, sim.azDeg, 1e-9)
        sim.advance(10_000)
        assertEquals(66.0, sim.azDeg, 1e-9)
        assertTrue(sim.isMoving)
        sim.advance(60_000)
        assertEquals(180.0, sim.azDeg, 1e-9)
        assertTrue(!sim.isMoving)
        // Et le compteur ne compte que ce que le mât a réellement parcouru.
        assertEquals(180.0, sim.azTravelDeg, 1e-9)
        assertEquals(0.0, sim.elTravelDeg, 1e-9)
        assertEquals(0, sim.refusals)
    }

    @Test
    fun le_pilote_lit_la_position_du_mat_simule() = runBlocking {
        val sim = Gs232Simulator()
        val r = rotor(sim)
        assertTrue(r.moveTo(180.0, 45.0))
        sim.advance(60_000)
        assertEquals(RotorPos(180.0, 45.0), r.readPosition())
        assertEquals(0, sim.refusals)
    }

    @Test
    fun la_reponse_qui_arrive_caractere_par_caractere_est_rassemblee() = runBlocking {
        // Le défaut le plus discret d'un pilote série : une lecture unique. Sur
        // un vrai câble la réponse arrive en morceaux, on attrape `AZ=1`, et le
        // pilote conclut « pas de réponse » une fois sur deux.
        val sim = Gs232Simulator()
        val r = Gs232Rotor()
        r.pacingMs = 0L
        r.attach(DribbleLink(sim))
        assertTrue(r.moveTo(90.0, 30.0))
        sim.advance(60_000)
        assertEquals(RotorPos(90.0, 30.0), r.readPosition())
    }

    @Test
    fun une_consigne_hors_course_est_refusee_et_comptee() = runBlocking {
        // 500° tient dans le format mais pas dans la mécanique. Le vrai
        // contrôleur l'ignore en silence ; celui-ci le compte, ce qui permet
        // enfin de l'affirmer dans un essai.
        val sim = Gs232Simulator(azMaxDeg = 450.0, elMaxDeg = 180.0)
        val r = rotor(sim)
        assertTrue(r.moveTo(500.0, 0.0))      // la trame part, bien formée
        sim.advance(60_000)
        assertEquals("le mât a bougé sur une consigne refusée", 0.0, sim.azDeg, 1e-9)
        assertEquals(1, sim.refusals)
        // Et ce qui n'est pas du GS-232 du tout est refusé aussi.
        sim.write("Z\r".toByteArray(), 500)
        assertEquals(2, sim.refusals)
        // La borne, elle, passe.
        assertTrue(r.moveTo(450.0, 180.0))
        sim.advance(120_000)
        assertEquals(450.0, sim.azDeg, 1e-9)
        assertEquals(2, sim.refusals)
    }

    @Test
    fun l_arret_immediat_fige_le_mat_ou_il_est() = runBlocking {
        val sim = Gs232Simulator()
        val r = rotor(sim)
        r.moveTo(180.0, 0.0)
        sim.advance(5_000)
        assertEquals(30.0, sim.azDeg, 1e-9)
        assertTrue(r.stop())
        sim.advance(60_000)
        assertEquals("le mât a continué après l'arrêt", 30.0, sim.azDeg, 1e-9)
        assertTrue(!sim.isMoving)
    }

    @Test
    fun un_pilote_non_branche_rend_null_plutot_que_d_exploser() = runBlocking {
        val r = Gs232Rotor()
        r.pacingMs = 0L
        assertTrue(!r.isOpen)
        assertNull(r.readPosition())
        assertTrue(!r.moveTo(180.0, 45.0))
        assertTrue(!r.stop())
        assertTrue(r.listDevices().isEmpty())
        // Et un contrôleur refermé se comporte de même.
        val sim = Gs232Simulator()
        val ouvert = rotor(sim)
        ouvert.close()
        assertTrue(!ouvert.isOpen)
        assertTrue(sim.isClosed)
        assertNull(ouvert.readPosition())
    }

    @Test
    fun le_journal_garde_les_trames_du_rotor_en_clair() = runBlocking {
        // Le même journal que le CAT, et pour la même raison : quand le mât ne
        // bouge pas, la seule question utile est « la trame est-elle partie, et
        // qu'a répondu le contrôleur ? »
        CatJournal.clear()
        CatJournal.enabled = true
        val sim = Gs232Simulator()
        val r = rotor(sim)
        r.moveTo(180.0, 45.0)
        sim.advance(60_000)
        r.readPosition()
        val e = CatJournal.entries.value
        assertTrue("journal vide", e.size >= 3)
        assertTrue(e.any { it.out && it.text == "consigne → azimut 180°, élévation 45°" })
        assertTrue(e.any { it.out && it.text == "demande de position" })
        assertTrue(e.any { !it.out && it.text.contains("position : azimut 180°") })
        assertTrue(e.first().hex.startsWith("57"))     // 'W'
    }
}
