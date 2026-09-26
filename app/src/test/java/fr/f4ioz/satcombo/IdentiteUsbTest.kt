/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.cat.IdentiteUsb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Désigner un adaptateur série qui n'a pas de nom.
 *
 * Le défaut d'origine tenait en une ligne : `open` exigeait
 * `deviceSerial != null` et un `serialNumber` correspondant. Le commentaire
 * disait « the adapter whose **FTDI** serial matches » — l'hypothèse de départ
 * était là. Un FTDI porte toujours un numéro de série ; un PL2303TA n'en porte
 * aucun, conformément à sa fiche. Le câble était reconnu par le pilote et
 * restait inutilisable, faute qu'on puisse le désigner.
 */
class IdentiteUsbTest {

    private val FTDI = 0x0403
    private val PROLIFIC = 0x067B

    @Test
    fun un_adaptateur_avec_numero_de_serie_garde_son_numero() {
        // Rien ne doit changer pour un FTDI déjà configuré : les réglages
        // enregistrés avant ce correctif continuent de fonctionner.
        assertEquals("A50285BI",
            IdentiteUsb.cle("A50285BI", FTDI, 0x6001, "/dev/bus/usb/001/004"))
    }

    @Test
    fun un_adaptateur_sans_numero_recoit_une_identite_de_repli() {
        val cle = IdentiteUsb.cle(null, PROLIFIC, 0x2303, "/dev/bus/usb/001/005")
        assertTrue(cle.contains("067B"))
        assertTrue(cle.contains("2303"))
        assertTrue(cle.contains("/dev/bus/usb/001/005"))
        assertTrue(IdentiteUsb.sansNumeroDeSerie(cle))
        assertFalse(IdentiteUsb.sansNumeroDeSerie("A50285BI"))
    }

    @Test
    fun un_numero_vide_ou_blanc_vaut_absence_de_numero() {
        assertTrue(IdentiteUsb.sansNumeroDeSerie(
            IdentiteUsb.cle("", PROLIFIC, 0x2303, "/dev/bus/usb/001/005")))
        assertTrue(IdentiteUsb.sansNumeroDeSerie(
            IdentiteUsb.cle("   ", PROLIFIC, 0x2303, "/dev/bus/usb/001/005")))
    }

    @Test
    fun deux_cables_identiques_a_des_places_differentes_ont_des_cles_differentes() {
        val a = IdentiteUsb.cle(null, PROLIFIC, 0x2303, "/dev/bus/usb/001/004")
        val b = IdentiteUsb.cle(null, PROLIFIC, 0x2303, "/dev/bus/usb/001/005")
        assertFalse(a == b)
    }

    // ------------------------------------------------------------ résolution

    @Test
    fun la_cle_exacte_est_retrouvee_parmi_plusieurs() {
        val cles = listOf("A50285BI", "067B:2303@/dev/bus/usb/001/005")
        assertEquals("A50285BI", IdentiteUsb.resout("A50285BI", cles))
    }

    /**
     * La règle qui règle la majorité des cas sans rien demander. Refuser
     * d'ouvrir le seul câble branché sous prétexte que sa position a changé
     * depuis la veille serait exactement le défaut qu'on corrige.
     */
    @Test
    fun un_seul_candidat_est_pris_sans_discuter() {
        assertEquals("067B:2303@/dev/bus/usb/001/007",
            IdentiteUsb.resout("067B:2303@/dev/bus/usb/001/004",
                listOf("067B:2303@/dev/bus/usb/001/007")))
    }

    @Test
    fun un_seul_candidat_est_pris_meme_sans_memoire() {
        assertEquals("067B:2303@/dev/bus/usb/001/007",
            IdentiteUsb.resout(null, listOf("067B:2303@/dev/bus/usb/001/007")))
    }

    /**
     * Deux candidats et aucune correspondance : on ne devine pas. Ouvrir au
     * hasard piloterait le mauvais poste — en duplex, cela veut dire émettre
     * sur la fréquence d'écoute.
     */
    @Test
    fun deux_candidats_sans_correspondance_ne_se_devinent_pas() {
        assertNull(IdentiteUsb.resout("inconnu", listOf("a", "b")))
        assertNull(IdentiteUsb.resout(null, listOf("a", "b")))
        assertNull(IdentiteUsb.resout("a", emptyList()))
    }

    // ------------------------------------------------------------ attribution

    private fun sonde(cle: String, hz: Long?) = IdentiteUsb.Sonde(cle, hz)

    /**
     * Le cœur de la réponse au duplex. Deux câbles identiques sans numéro de
     * série sont indiscernables par leur étiquette — mais les deux postes ne
     * sont pas sur la même bande, et leur propre réponse dit lequel est lequel.
     */
    @Test
    fun les_postes_se_designent_eux_memes_par_leur_bande() {
        val a = IdentiteUsb.attribue(
            listOf(sonde("cable_a", 435_100_000L), sonde("cable_b", 145_866_000L)),
            descenteHz = 145_866_000L, monteeHz = 435_108_000L)
        assertEquals("cable_b", a.rx)
        assertEquals("cable_a", a.tx)
        assertTrue(a.certaine)
    }

    @Test
    fun l_attribution_tient_malgre_un_decalage_de_quelques_kilohertz() {
        // Le poste n'est jamais exactement sur la fréquence calculée : Doppler,
        // calibration, et l'opérateur qui a bougé son VFO.
        val a = IdentiteUsb.attribue(
            listOf(sonde("rx", 145_871_500L), sonde("tx", 435_103_000L)),
            descenteHz = 145_866_000L, monteeHz = 435_108_000L)
        assertEquals("rx", a.rx)
        assertEquals("tx", a.tx)
        assertTrue(a.certaine)
    }

    /**
     * Deux postes dans la même bande : on propose, on n'impose pas. L'opérateur
     * tranchera en voyant les fréquences lues — un discriminant qu'il comprend,
     * contrairement à « USB serial (1) » et « USB serial (2) ».
     */
    @Test
    fun deux_postes_dans_la_meme_bande_ne_donnent_pas_de_certitude() {
        val a = IdentiteUsb.attribue(
            listOf(sonde("un", 145_860_000L), sonde("deux", 145_870_000L)),
            descenteHz = 145_866_000L, monteeHz = 435_108_000L)
        assertFalse(a.certaine)
    }

    @Test
    fun un_seul_poste_qui_repond_ne_donne_pas_de_certitude() {
        val a = IdentiteUsb.attribue(
            listOf(sonde("un", 145_866_000L), sonde("muet", null)),
            descenteHz = 145_866_000L, monteeHz = 435_108_000L)
        assertEquals("un", a.rx)
        assertFalse(a.certaine)
    }

    @Test
    fun aucun_poste_qui_repond_ne_donne_rien() {
        val a = IdentiteUsb.attribue(
            listOf(sonde("un", null), sonde("deux", null)),
            descenteHz = 145_866_000L, monteeHz = 435_108_000L)
        assertNull(a.rx)
        assertNull(a.tx)
        assertFalse(a.certaine)
    }

    /** Sans transpondeur connu, il n'y a aucune bande à comparer. */
    @Test
    fun sans_bandes_connues_on_propose_dans_l_ordre_sans_certitude() {
        val a = IdentiteUsb.attribue(
            listOf(sonde("un", 145_866_000L), sonde("deux", 435_100_000L)),
            descenteHz = null, monteeHz = null)
        assertEquals("un", a.rx)
        assertEquals("deux", a.tx)
        assertFalse(a.certaine)
    }

    /**
     * Distinguer une vraie réponse d'octets de bruit interprétés comme une
     * fréquence : le FT-817 couvre de 100 kHz à 470 MHz, au-delà ce n'est pas
     * le poste qui a parlé.
     */
    @Test
    fun une_frequence_hors_des_bornes_du_poste_est_ecartee() {
        assertTrue(IdentiteUsb.freqPlausible(145_866_000L))
        assertTrue(IdentiteUsb.freqPlausible(435_100_000L))
        assertFalse(IdentiteUsb.freqPlausible(0L))
        assertFalse(IdentiteUsb.freqPlausible(9_999_999_999L))
    }
}
