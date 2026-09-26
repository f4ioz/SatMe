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
import fr.f4ioz.satcombo.rotor.RotorPos
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ce qui traîne sur le fil n'est pas la réponse à la question qu'on vient de
 * poser.
 *
 * Un fil série n'a ni longueur annoncée, ni somme de contrôle, ni numéro de
 * question : tout ce qui arrive ressemble à une réponse. Deux choses en
 * profitaient pour se faire passer pour la position du mât — l'écho de la
 * consigne `W` renvoyé par les émulateurs bavards, et le reliquat du tour
 * précédent resté dans le tampon. Vu de l'opérateur, le résultat était le
 * même : « ça suit bien… et puis ça ne suit plus le rotor, on passe en
 * normal », une seconde sur cinq, sans que rien ne l'explique.
 *
 * Ces essais posent la règle : **une ligne n'est une réponse que si elle se
 * relit**. Le reste se jette — mais se garde pour le journal, parce que
 * « quelque chose, mais pas ça » et « rien du tout » n'envoient pas
 * l'opérateur au même endroit.
 */
class Gs232EchoTest {

    @After
    fun apres() { CatJournal.enabled = false; CatJournal.clear() }

    /**
     * Un émulateur bavard : il répète la consigne reçue avant de répondre, et
     * l'on peut lui laisser des octets dans le tampon avant même de parler.
     */
    private class LienBavard(
        private val avantLaQuestion: String = "",
        private val echo: Boolean = true,
        private val reponse: String = "AZ=155EL=016\r"
    ) : SerialLink {
        private val sortie = ArrayDeque<Byte>()
        var questions = 0; private set
        private var derniereConsigne = ""

        init { pousser(avantLaQuestion) }

        private fun pousser(s: String) =
            s.toByteArray(Charsets.US_ASCII).forEach { sortie.addLast(it) }

        override fun write(bytes: ByteArray, timeoutMs: Int): Boolean {
            val s = String(bytes, Charsets.US_ASCII).trim()
            if (s.uppercase().startsWith("C")) {
                questions++
                if (echo && derniereConsigne.isNotEmpty()) pousser("$derniereConsigne\r")
                pousser(reponse)
            } else {
                derniereConsigne = s
            }
            return true
        }

        override fun read(buf: ByteArray, timeoutMs: Int): Int {
            var n = 0
            while (n < buf.size && sortie.isNotEmpty()) buf[n++] = sortie.removeFirst()
            return n
        }

        override fun close() {}
    }

    private fun rotor(l: SerialLink): Gs232Rotor {
        val r = Gs232Rotor()
        r.pacingMs = 0L          // au banc, il n'y a personne à ménager
        r.attach(l)
        return r
    }

    @Test
    fun l_echo_de_la_consigne_n_est_pas_pris_pour_la_position() = runBlocking {
        // La panne du terrain, reproduite : le tour précédent a envoyé
        // `W155 016`, l'émulateur le renvoie, et cette ligne-là arrive
        // terminée par un retour chariot **avant** la position. On rendait
        // donc « W155 016 » comme réponse au `C2`, le décodeur n'y trouvait
        // rien, et la position du mât disparaissait de l'écran le temps d'un
        // battement.
        val l = LienBavard()
        val r = rotor(l)
        assertTrue(r.moveTo(155.0, 16.0))
        assertEquals(RotorPos(155.0, 16.0), r.readPosition())
        assertEquals(1, l.questions)
    }

    @Test
    fun le_reliquat_du_tour_precedent_est_jete_avant_de_questionner() = runBlocking {
        // Une réponse arrivée trop tard, un message d'amorçage, un accusé de
        // réception : ce qui dormait dans le tampon décrit le passé. Le lire
        // comme réponse d'aujourd'hui, c'est afficher une position vieille
        // d'une seconde — ou pire, une position qui ne se relit pas et qui
        // efface tout.
        val l = LienBavard(avantLaQuestion = "AZ=010EL=002\r", echo = false)
        val r = rotor(l)
        assertEquals("le vieux tampon a été pris pour la réponse",
            RotorPos(155.0, 16.0), r.readPosition())
    }

    @Test
    fun le_bruit_d_amorcage_non_termine_ne_masque_pas_la_reponse() = runBlocking {
        // Un Arduino qui vient de redémarrer crache une demi-ligne sans retour
        // chariot. Elle est jetée au vidage, et la vraie réponse passe.
        val l = LienBavard(avantLaQuestion = "Arduino GS-232 v1.2", echo = false)
        assertEquals(RotorPos(155.0, 16.0), rotor(l).readPosition())
    }

    @Test
    fun une_reponse_qui_ne_se_relit_pas_reste_dans_le_journal() = runBlocking {
        // Silence et charabia ne se réparent pas de la même façon : l'un
        // envoie vérifier un câble, l'autre une vitesse de transmission. La
        // trame refusée doit donc survivre à son refus, faute de quoi l'écran
        // dit « pas de réponse » à un contrôleur qui parle.
        val l = LienBavard(echo = false, reponse = "?>\r")
        val r = rotor(l)
        assertNull("une trame illisible a été prise pour une position", r.readPosition())
        assertEquals("?>", r.lastReply)
    }

    @Test
    fun le_charabia_qui_precede_la_position_ne_la_perd_pas() = runBlocking {
        // Plusieurs lignes complètes avant la bonne : on continue de lire
        // jusqu'à celle qui se relit, au lieu de s'arrêter à la première.
        val l = LienBavard(echo = false, reponse = "?>\rERR\rAZ=155EL=016\r")
        val r = rotor(l)
        val p = r.readPosition()
        assertNotNull(p)
        assertEquals(155.0, p!!.azDeg, 1e-9)
        assertEquals(16.0, p.elDeg, 1e-9)
    }
}
