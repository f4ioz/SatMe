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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Le réveil de la carte, et le filtre qui décide si l'application existe.
 *
 * « Impossible de connecter. Quand je branche, on me demande d'ouvrir avec une
 * application, SatMe n'est pas dedans. » Deux pannes derrière une seule phrase,
 * et aucune des deux ne se voit dans le code qui commande le mât.
 *
 * La première est un fichier de ressources : Android ne propose une application
 * au branchement que si l'appareil correspond à un filtre déclaré, et
 * l'identifiant du fabricant Arduino n'y était pas. Rien ne plante, rien ne
 * s'affiche — l'application est simplement absente de la liste, et avec elle
 * l'autorisation d'accès que le système accorde en même temps que le choix.
 *
 * La seconde est une convention de câblage vieille de quinze ans : sur une
 * carte Arduino, DTR est relié au RESET. Ouvrir le port redémarre la carte, et
 * la question posée dans la foulée tombe pendant l'amorçage. Le port est bon,
 * le câble est bon, la vitesse est bonne, et l'écran affiche pourtant « ouvert,
 * mais le contrôleur ne répond pas » — le message le plus trompeur de toute
 * l'application.
 */
class RotorReveilTest {

    @After
    fun apres() { CatJournal.enabled = false; CatJournal.clear() }

    /**
     * Une carte qui redémarre : elle avale les premières questions, puis
     * répond normalement.
     */
    private class ArduinoQuiRedemarre(private val avalees: Int) : SerialLink {
        var recues = 0; private set
        private val sortie = ArrayDeque<Byte>()

        override fun write(bytes: ByteArray, timeoutMs: Int): Boolean {
            val s = String(bytes, Charsets.US_ASCII).trim()
            if (!s.uppercase().startsWith("C")) return true
            recues++
            if (recues > avalees) {
                "AZ=123 EL=045\r".toByteArray(Charsets.US_ASCII).forEach { sortie.addLast(it) }
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
        r.pacingMs = 0L
        r.settleMs = 0L
        r.attach(l)
        return r
    }

    // ------------------------------------------------------------------
    // Les relances
    // ------------------------------------------------------------------

    @Test
    fun une_seule_question_ne_suffit_pas_a_declarer_un_controleur_muet() = runBlocking {
        val carte = ArduinoQuiRedemarre(avalees = 2)
        val r = rotor(carte)

        // Ce que faisait la 18.23 : une question, un silence, un verdict.
        assertNull("la carte n'a pas encore fini de redémarrer", r.readPosition())

        // Ce que fait la 18.24 : on redemande.
        val pos = r.probePosition(tries = 3, gapMs = 0L)
        assertNotNull("le contrôleur répond dès qu'il est réveillé", pos)
        assertEquals(123.0, pos!!.azDeg, 1e-9)
        assertEquals(45.0, pos.elDeg, 1e-9)
    }

    @Test
    fun un_controleur_vraiment_muet_reste_muet_et_le_dit() = runBlocking {
        val muet = object : SerialLink {
            var ecrit = 0
            override fun write(bytes: ByteArray, timeoutMs: Int): Boolean { ecrit++; return true }
            override fun read(buf: ByteArray, timeoutMs: Int): Int = 0
            override fun close() {}
        }
        val r = rotor(muet)
        assertNull(r.probePosition(tries = 3, gapMs = 0L))
        // Trois questions posées, pas une de plus : les relances ne doivent pas
        // faire attendre l'opérateur une minute devant un câble débranché.
        assertEquals(3, muet.ecrit)
        assertEquals(3, r.lastTries)
        assertTrue("aucune trame reçue", r.lastReply.isBlank())
    }

    @Test
    fun la_derniere_trame_illisible_est_conservee_pour_l_ecran() = runBlocking {
        // Un appareil qui parle, mais pas le même dialecte : ce n'est pas un
        // silence, et cela ne se répare pas de la même façon.
        val bavard = object : SerialLink {
            private val sortie = ArrayDeque<Byte>()
            override fun write(bytes: ByteArray, timeoutMs: Int): Boolean {
                "?;\r".toByteArray(Charsets.US_ASCII).forEach { sortie.addLast(it) }
                return true
            }
            override fun read(buf: ByteArray, timeoutMs: Int): Int {
                var n = 0
                while (n < buf.size && sortie.isNotEmpty()) buf[n++] = sortie.removeFirst()
                return n
            }
            override fun close() {}
        }
        val r = rotor(bavard)
        assertNull(r.probePosition(tries = 2, gapMs = 0L))
        assertEquals("?;", r.lastReply)
    }

    @Test
    fun un_controleur_qui_repond_du_premier_coup_n_est_pas_relance() = runBlocking {
        val carte = ArduinoQuiRedemarre(avalees = 0)
        val r = rotor(carte)
        assertNotNull(r.probePosition(tries = 3, gapMs = 0L))
        assertEquals("une seule question a suffi", 1, carte.recues)
        assertEquals(1, r.lastTries)
    }

    // ------------------------------------------------------------------
    // Le filtre USB
    // ------------------------------------------------------------------

    private fun filtre(): File? = listOf(
        "src/main/res/xml/usb_device_filter.xml",
        "app/src/main/res/xml/usb_device_filter.xml",
        "../app/src/main/res/xml/usb_device_filter.xml")
        .map { File(it) }.firstOrNull { it.isFile }

    /**
     * Sans cette déclaration, SatMe n'apparaît pas dans « ouvrir avec » au
     * branchement — et rien, nulle part, ne le signale.
     */
    @Test
    fun le_filtre_usb_declare_les_cartes_a_microcontroleur() {
        val f = filtre()
        assertTrue("usb_device_filter.xml introuvable depuis " + File(".").absolutePath,
            f != null)
        val texte = f!!.readText()
        // Les valeurs sont en décimal : la plateforme n'accepte pas 0x2341.
        val attendus = mapOf(
            "Arduino SA (0x2341)" to 9025,
            "Arduino ancien (0x2A03)" to 10755,
            "Atmel (0x03EB)" to 1003,
            "SparkFun (0x1B4F)" to 6991,
            "Adafruit (0x239A)" to 9114,
            "Teensy (0x16C0)" to 5824,
            "STMicroelectronics (0x0483)" to 1155,
            "CH34x (0x1A86)" to 6790,
            "FTDI (0x0403)" to 1027,
            "CP210x (0x10C4)" to 4292)
        val manquants = attendus.filterValues {
            !texte.contains("vendor-id=\"" + it + "\"")
        }.keys
        assertTrue("fabricants absents du filtre USB : " + manquants.joinToString(", "),
            manquants.isEmpty())
    }

    @Test
    fun le_filtre_usb_prend_le_cp210x_en_entier() {
        val f = filtre()
        assertTrue("usb_device_filter.xml introuvable", f != null)
        val texte = f!!.readText()
        // La ligne d'origine ne retenait que le produit 0xEA60 de l'IC-9700 ;
        // un CP2105 ou un CP2108 portait un autre numéro et disparaissait.
        assertTrue("le CP210x doit être déclaré sans numéro de produit",
            Regex("""<usb-device\s+vendor-id="4292"\s*/>""").containsMatchIn(texte))
        // Et le filet de la classe « communication », pour les montages maison.
        assertTrue("la classe CDC doit être déclarée",
            Regex("""<usb-device\s+class="2"\s*/>""").containsMatchIn(texte))
    }
}
