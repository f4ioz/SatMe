/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.cat.IdentiteUsb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Identifying a serial adapter with no serial number. `open` once required
 * one, assuming FTDI; a PL2303TA has none, so the cable was recognised yet
 * impossible to select.
 */
class IdentiteUsbTest {

    private val FTDI = 0x0403
    private val PROLIFIC = 0x067B

    @Test
    fun un_adaptateur_avec_numero_de_serie_garde_son_numero() {
        // Nothing changes for an already configured FTDI: existing settings
        // keep working.
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

    // ------------------------------------------------------------ resolution

    @Test
    fun la_cle_exacte_est_retrouvee_parmi_plusieurs() {
        val cles = listOf("A50285BI", "067B:2303@/dev/bus/usb/001/005")
        assertEquals("A50285BI", IdentiteUsb.resout("A50285BI", cles))
    }

    /**
     * The rule that settles most cases without asking. Refusing the only
     * plugged-in cable because its port changed since yesterday would be the
     * very bug being fixed.
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
     * Two candidates and no match: do not guess. Picking at random would drive
     * the wrong radio — in duplex, that means transmitting on the receive
     * frequency.
     */
    @Test
    fun deux_candidats_sans_correspondance_ne_se_devinent_pas() {
        assertNull(IdentiteUsb.resout("inconnu", listOf("a", "b")))
        assertNull(IdentiteUsb.resout(null, listOf("a", "b")))
        assertNull(IdentiteUsb.resout("a", emptyList()))
    }

    // ------------------------------------------------------------ assignment

    private fun sonde(cle: String, hz: Long?) = IdentiteUsb.Sonde(cle, hz)

    /**
     * The core of the duplex answer. Two identical cables without serial
     * numbers look the same, but the two radios are on different bands, and
     * their own replies tell which is which.
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
        // The radio is never exactly on the computed frequency: Doppler,
        // calibration, and the operator moving the VFO.
        val a = IdentiteUsb.attribue(
            listOf(sonde("rx", 145_871_500L), sonde("tx", 435_103_000L)),
            descenteHz = 145_866_000L, monteeHz = 435_108_000L)
        assertEquals("rx", a.rx)
        assertEquals("tx", a.tx)
        assertTrue(a.certaine)
    }

    /**
     * Two radios on the same band: suggest, do not impose. The operator decides
     * from the frequencies read — something they understand, unlike
     * "USB serial (1)" and "USB serial (2)".
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

    /** Without a known transponder there is no band to compare. */
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
     * Tell a real reply from noise bytes read as a frequency: the FT-817
     * covers 100 kHz to 470 MHz; beyond that it was not the radio talking.
     */
    @Test
    fun une_frequence_hors_des_bornes_du_poste_est_ecartee() {
        assertTrue(IdentiteUsb.freqPlausible(145_866_000L))
        assertTrue(IdentiteUsb.freqPlausible(435_100_000L))
        assertFalse(IdentiteUsb.freqPlausible(0L))
        assertFalse(IdentiteUsb.freqPlausible(9_999_999_999L))
    }
}
