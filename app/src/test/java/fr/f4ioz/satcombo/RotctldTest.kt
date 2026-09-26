/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.rotor.RotctldCodec
import fr.f4ioz.satcombo.rotor.RotctldRotor
import fr.f4ioz.satcombo.rotor.RotorPos
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.Collections
import java.util.Locale

/**
 * Le client `rotctld`, contre un faux Hamlib de trente lignes.
 *
 * Sept essais, et trois d'entre eux existent parce que le protocole a trois
 * pièges qui ne se voient pas : le point décimal, le `RPRT -1` rendu à la place
 * des deux lignes de position, et l'écho du mode étendu. Les deux derniers ont
 * la même conséquence, et c'est la pire qui soit sur un protocole en texte
 * sans délimiteur : une ligne de trop dans le tuyau, et tout ce qui suit est
 * décalé d'un cran — indéfiniment. Le mât répond alors une élévation quand on
 * lui demande un azimut, et rien ne le dit.
 *
 * Le serveur écoute sur un port choisi par le système : deux essais qui
 * tournent en même temps ne doivent pas se disputer un numéro écrit en dur.
 */
class RotctldTest {

    /**
     * Un `rotctld` qui n'existe pas.
     *
     * [extended] rejoue le mode étendu (`rotctld -vv` et ses réponses nommées),
     * [failing] un contrôleur muet qui rend `RPRT -1` au lieu d'une position.
     */
    private class FauxHamlib(
        private val extended: Boolean = false,
        private val failing: Boolean = false
    ) {
        private val server = ServerSocket(0)
        val port: Int get() = server.localPort
        val received: MutableList<String> = Collections.synchronizedList(ArrayList())
        @Volatile var az: Double = 180.0
        @Volatile var el: Double = 45.0

        private val fil = Thread { servir() }

        fun start() { fil.isDaemon = true; fil.start() }
        fun stop() { runCatching { server.close() } }

        private fun servir() {
            runCatching {
                val s = server.accept()
                val r = s.getInputStream().bufferedReader(Charsets.US_ASCII)
                val w = s.getOutputStream().writer(Charsets.US_ASCII)
                while (true) {
                    val ligne = r.readLine() ?: break
                    received += ligne
                    val t = ligne.trim()
                    when {
                        t.startsWith("P ") -> {
                            // Hamlib lit ses nombres en C. Une virgule décimale
                            // n'est pas « presque bon » : c'est un refus net.
                            val p = t.split(Regex("\\s+"))
                            val a = p.getOrNull(1)?.toDoubleOrNull()
                            val e = p.getOrNull(2)?.toDoubleOrNull()
                            if (t.contains(',') || a == null || e == null) w.write("RPRT -1\n")
                            else {
                                az = a; el = e
                                if (extended) w.write("set_pos: ${p[1]} ${p[2]}\n")
                                w.write("RPRT 0\n")
                            }
                        }
                        t == "p" -> when {
                            failing -> w.write("RPRT -1\n")
                            extended -> {
                                w.write("get_pos:\n")
                                w.write(String.format(Locale.US, "Azimuth: %.6f\n", az))
                                w.write(String.format(Locale.US, "Elevation: %.6f\n", el))
                                w.write("RPRT 0\n")
                            }
                            else -> {
                                w.write(String.format(Locale.US, "%.6f\n", az))
                                w.write(String.format(Locale.US, "%.6f\n", el))
                            }
                        }
                        t == "S" -> w.write("RPRT 0\n")
                        else -> w.write("RPRT -1\n")
                    }
                    w.flush()
                }
            }
        }
    }

    private fun <T> avecServeur(h: FauxHamlib, corps: suspend (RotctldRotor) -> T): T {
        h.start()
        val r = RotctldRotor()
        try {
            return runBlocking {
                assertTrue("la prise ne s'ouvre pas", r.open("127.0.0.1", h.port))
                corps(r)
            }
        } finally { r.close(); h.stop() }
    }

    @Test
    fun la_consigne_part_avec_un_point_decimal_meme_en_francais() {
        // Le piège le plus bête et le plus coûteux : un téléphone réglé en
        // français écrit « 180,00 », et Hamlib répond `RPRT -1` sans autre
        // explication. Le mât ne bouge pas, l'application n'affiche rien
        // d'anormal, et l'on cherche pendant un passage entier.
        val defaut = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)
            assertEquals("P 180.00 45.50\n", RotctldCodec.moveCommand(180.0, 45.5))
            val h = FauxHamlib()
            avecServeur(h) { r ->
                assertTrue("la consigne a été refusée", r.moveTo(180.0, 45.5))
            }
            assertTrue("consigne absente : ${h.received}",
                h.received.any { it.trim() == "P 180.00 45.50" })
        } finally { Locale.setDefault(defaut) }
    }

    @Test
    fun la_position_se_lit_sur_deux_lignes() {
        val h = FauxHamlib()
        avecServeur(h) { r ->
            assertEquals(RotorPos(180.0, 45.0), r.readPosition())
            // Et l'aller-retour tient : ce qu'on écrit, on le relit.
            assertTrue(r.moveTo(12.0, 3.5))
            assertEquals(RotorPos(12.0, 3.5), r.readPosition())
        }
        assertTrue(h.received.any { it.trim() == "p" })
    }

    @Test
    fun un_rprt_negatif_rend_null_au_lieu_de_decaler_tout_le_reste() {
        // Quand le contrôleur ne répond pas, `p` ne rend pas deux nombres : il
        // rend un code d'erreur, sur une seule ligne. Un lecteur qui attend
        // aveuglément deux lignes consomme la réponse de la commande suivante,
        // et à partir de là tout est décalé.
        val h = FauxHamlib(failing = true)
        avecServeur(h) { r ->
            assertNull(r.readPosition())
            // La preuve que rien n'a été décalé : la commande suivante est
            // comprise, et sa réponse arrive bien à elle.
            assertTrue(r.moveTo(90.0, 10.0))
            assertNull(r.readPosition())
            assertTrue(r.stop())
        }
    }

    @Test
    fun l_echo_du_mode_etendu_ne_se_fait_plus_prendre_pour_une_position() {
        // En mode étendu le serveur nomme ses champs et termine par `RPRT 0`.
        // Ce `RPRT 0` final n'existe pas en mode simple ; l'oublier laisse une
        // ligne en trop, et l'on retombe sur le décalage précédent — d'où la
        // seconde lecture, qui est le véritable objet de l'essai.
        val h = FauxHamlib(extended = true)
        avecServeur(h) { r ->
            assertEquals(RotorPos(180.0, 45.0), r.readPosition())
            assertEquals(RotorPos(180.0, 45.0), r.readPosition())
            assertTrue(r.moveTo(300.0, 12.0))
            assertEquals(RotorPos(300.0, 12.0), r.readPosition())
        }
    }

    @Test
    fun l_arret_passe_par_une_ligne_a_lui_seul() {
        val h = FauxHamlib()
        avecServeur(h) { r -> assertTrue(r.stop()) }
        assertTrue("l'arrêt n'est pas parti : ${h.received}",
            h.received.any { it.trim() == "S" })
        assertEquals("S\n", RotctldCodec.STOP)
        assertEquals("p\n", RotctldCodec.QUERY)
    }

    @Test
    fun un_client_non_connecte_rend_null_plutot_que_d_exploser() = runBlocking {
        val r = RotctldRotor()
        assertTrue(!r.isOpen)
        assertNull(r.readPosition())
        assertTrue(!r.moveTo(180.0, 45.0))
        assertTrue(!r.stop())
        // Un port fermé ne s'ouvre pas, et ne fait pas tomber l'application.
        val libre = ServerSocket(0)
        val port = libre.localPort
        libre.close()
        assertTrue(!r.open("127.0.0.1", port, timeoutMs = 300))
        r.close()
        assertTrue(!r.isOpen)
    }

    @Test
    fun une_consigne_impossible_a_formater_ne_part_pas() {
        assertNull(RotctldCodec.moveCommand(Double.NaN, 0.0))
        assertNull(RotctldCodec.moveCommand(0.0, Double.POSITIVE_INFINITY))
        val h = FauxHamlib()
        avecServeur(h) { r ->
            assertTrue(!r.moveTo(Double.NaN, 0.0))
            assertTrue(r.moveTo(45.0, 5.0))
        }
        assertEquals("une trame est partie quand même", 1, h.received.size)
    }
}
