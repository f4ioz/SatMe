/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L'export ADIF, vérifié sur machine.
 *
 * Un fichier ADIF mal formé n'est pas à moitié importé : il est refusé en bloc,
 * souvent sans dire pourquoi. Comme le carnet part vers LoTW ou Club Log, où
 * une erreur se corrige mal, autant que la génération soit tenue par des
 * essais plutôt que par la confiance.
 */
class AdifTest {

    @Test
    fun `la bande se deduit de la frequence descendante`() {
        assertEquals("2m", Adif.band(145.950))
        assertEquals("70cm", Adif.band(435.100))
        assertEquals("10m", Adif.band(29.400))
        assertEquals("23cm", Adif.band(1_265.0))
    }

    @Test
    fun `une frequence hors bande ne rend rien plutot qu-une bande fausse`() {
        assertEquals("", Adif.band(100.0))
        assertEquals("", Adif.band(0.0))
        assertEquals("", Adif.band(-3.0))
    }

    @Test
    fun `le mode satellite s-ecrit avec les lettres de bande`() {
        // RS-44 : montée 145 MHz, descente 435 MHz — donc V/U.
        assertEquals("V/U", Adif.satMode(145.965, 435.640))
        // AO-7 mode B : montée 432, descente 145 — U/V.
        assertEquals("U/V", Adif.satMode(432.150, 145.950))
    }

    @Test
    fun `un mode satellite incomplet reste vide`() {
        assertEquals("", Adif.satMode(0.0, 145.950))
        assertEquals("", Adif.satMode(145.950, 0.0))
    }

    @Test
    fun `la BLU se declare en SSB avec un sous-mode`() {
        assertEquals("SSB" to "USB", Adif.modeOf("USB"))
        assertEquals("SSB" to "LSB", Adif.modeOf("LSB"))
        assertEquals("FM" to "", Adif.modeOf("FM"))
        assertEquals("CW" to "", Adif.modeOf("CW"))
        assertEquals("MFSK" to "FT4", Adif.modeOf("FT4"))
        assertEquals("" to "", Adif.modeOf(null))
    }

    @Test
    fun `la longueur d-un champ est comptee en octets`() {
        // « é » occupe deux octets en UTF-8. Les carnets lisent l'ADIF octet
        // par octet : annoncer 5 pour « café » décalerait tout le fichier.
        assertEquals("<COMMENT:5>café", Adif.field("COMMENT", "café"))
        assertEquals("<CALL:5>F4IOZ", Adif.field("CALL", "F4IOZ"))
        assertEquals("", Adif.field("CALL", "   "))
    }

    @Test
    fun `un champ ne contient jamais de retour a la ligne`() {
        val f = Adif.field("COMMENT", "deux\nlignes")
        assertFalse(f.contains("\n"))
        assertEquals("<COMMENT:11>deux lignes", f)
    }

    @Test
    fun `un contact complet sort avec tous les champs attendus`() {
        val e = LogEntry(
            timeMs = 1_700_000_000_000L, satName = "RS-44",
            catnum = 44909, azimuthDeg = 180.0, elevationDeg = 30.0,
            callsign = "F6ABC", myLocator = "JN18FS", theirLocator = "JN09",
            note = "premier essai", mode = "USB", rstSent = "59", rstRcvd = "57",
            downlinkMhz = 435.640, uplinkMhz = 145.965)
        val out = Adif.export(listOf(e), "F4IOZ")
        assertTrue(out.contains("<EOH>"))
        assertTrue(out.contains("<SAT_NAME:5>RS-44"))
        assertTrue(out.contains("<PROP_MODE:3>SAT"))
        assertTrue(out.contains("<STATION_CALLSIGN:5>F4IOZ"))
        assertTrue(out.contains("<OPERATOR:5>F4IOZ"))
        assertTrue(out.contains("<CALL:5>F6ABC"))
        assertTrue(out.contains("<MODE:3>SSB"))
        assertTrue(out.contains("<SUBMODE:3>USB"))
        assertTrue(out.contains("<RST_SENT:2>59"))
        assertTrue(out.contains("<RST_RCVD:2>57"))
        // On monte sur 145 et on écoute sur 435 : c'est la montée qui va
        // dans BAND, la descente dans BAND_RX. L'essai exigeait l'inverse et
        // consacrait ainsi le défaut.
        assertTrue(out.contains("<BAND:2>2m"))
        assertTrue(out.contains("<BAND_RX:4>70cm"))
        assertTrue(out.contains("<SAT_MODE:3>V/U"))
        assertTrue(out.contains("<MY_GRIDSQUARE:6>JN18FS"))
        assertTrue(out.contains("<GRIDSQUARE:4>JN09"))
        assertTrue(out.endsWith("<EOR>\n"))
    }

    @Test
    fun `l-horodatage est en UTC`() {
        // 1 700 000 000 000 ms = 14 novembre 2023, 22 h 13 min 20 s UTC.
        val e = LogEntry(1_700_000_000_000L, "ISS", 25544, 180.0, 30.0,
            callsign = "F6ABC")
        val out = Adif.export(listOf(e))
        assertTrue(out, out.contains("<QSO_DATE:8>20231114"))
        assertTrue(out, out.contains("<TIME_ON:6>221320"))
    }

    @Test
    fun `deux carres passent en VUCC, trois vont au commentaire`() {
        val two = LogEntry(1L, "ISS", 25544, 0.0, 0.0,
            callsign = "F6ABC", myGrids = "JN18,JN19")
        assertTrue(Adif.export(listOf(two)).contains("<MY_VUCC_GRIDS:9>JN18,JN19"))

        // La norme n'admet que deux ou quatre carrés adjacents : trois ferait
        // rejeter le contact, donc on le range dans le commentaire.
        val three = LogEntry(1L, "ISS", 25544, 0.0, 0.0,
            callsign = "F6ABC", myGrids = "JN18,JN19,JN28")
        val out = Adif.export(listOf(three))
        assertFalse(out.contains("MY_VUCC_GRIDS"))
        assertTrue(out.contains("JN18/JN19/JN28"))
    }

    /**
     * Le défaut du 25 août : quatre relevés anonymes — un appui sur la
     * boussole, sans indicatif — étaient partis dans le fichier d'export.
     */
    @Test
    fun `une entree sans indicatif ne sort pas`() {
        val anonyme = LogEntry(1L, "ISS", 25544, 0.0, 0.0)
        val vrai = LogEntry(2L, "ISS", 25544, 0.0, 0.0, callsign = "F6ABC")
        val out = Adif.export(listOf(anonyme, vrai))
        assertEquals(1, out.split("<EOR>").size - 1)
        assertTrue(out.contains("<CALL:5>F6ABC"))
    }

    /**
     * Le défaut relevé le 26 août : FREQ portait la descente.
     *
     * La norme définit FREQ et BAND du point de vue de la station qui
     * journalise — donc son émission. Un contact V/U déclarait 435 MHz comme
     * bande d'émission alors que le poste montait sur 145.
     */
    @Test
    fun `FREQ porte la montee et FREQ_RX la descente`() {
        val e = LogEntry(1L, "FO-29", 24278, 0.0, 0.0, callsign = "F6ABC",
            uplinkMhz = 145.9500, downlinkMhz = 435.8500)
        val out = Adif.export(listOf(e))
        assertTrue(out.contains("<FREQ:10>145.950000"))
        assertTrue(out.contains("<FREQ_RX:10>435.850000"))
        assertTrue(out.contains("<BAND:2>2m"))
        assertTrue(out.contains("<BAND_RX:4>70cm"))
    }

    @Test
    fun `l azimut et l elevation sortent quand le satellite est leve`() {
        val e = LogEntry(1L, "FO-29", 24278, 191.4, 37.2, callsign = "F6ABC")
        val out = Adif.export(listOf(e))
        assertTrue(out.contains("<ANT_AZ:5>191.4"))
        assertTrue(out.contains("<ANT_EL:4>37.2"))
    }

    /** Sous l'horizon, la visée ne décrit rien : on n'invente pas un pointage. */
    @Test
    fun `sous l horizon l azimut ne sort pas`() {
        val e = LogEntry(1L, "FO-29", 24278, 191.4, -12.0, callsign = "F6ABC")
        val out = Adif.export(listOf(e))
        assertFalse(out.contains("ANT_AZ"))
        assertFalse(out.contains("ANT_EL"))
    }

    /**
     * QO-100 descend sur 10 489 MHz. La table des bandes s'arrêtait à 13 cm :
     * tous les contacts géostationnaires partaient sans BAND_RX.
     */
    @Test
    fun `les bandes hautes de QO-100 sont connues`() {
        val e = LogEntry(1L, "QO-100", 43700, 0.0, 0.0, callsign = "F5RRO",
            uplinkMhz = 2400.0050, downlinkMhz = 10489.5225)
        val out = Adif.export(listOf(e))
        assertTrue(out.contains("<BAND:4>13cm"))
        assertTrue(out.contains("<BAND_RX:3>3cm"))
    }

    /**
     * Ce que l'annuaire apprend doit arriver jusqu'au carnet d'en face.
     *
     * Wavelog montre Nom, QTH et E-mail dans sa fiche de QSO ; sans ces
     * champs il faut les y remplir un par un, alors que QRZ les avait déjà
     * donnés au moment du dépôt.
     */
    @Test
    fun `le nom la ville et le courriel sortent`() {
        val e = LogEntry(1L, "FO-29", 24278, 0.0, 0.0, callsign = "DO1BEN",
            nom = "Clubstation", qth = "Leverkusen", courriel = "do1ben@darc.de")
        val out = Adif.export(listOf(e))
        assertTrue(out.contains("<NAME:11>Clubstation"))
        assertTrue(out.contains("<QTH:10>Leverkusen"))
        assertTrue(out.contains("<EMAIL:14>do1ben@darc.de"))
    }

    @Test
    fun `ces champs ne sortent pas quand ils sont vides`() {
        val out = Adif.export(listOf(
            LogEntry(1L, "FO-29", 24278, 0.0, 0.0, callsign = "F6ABC")))
        assertFalse(out.contains("<NAME:"))
        assertFalse(out.contains("<QTH:"))
        assertFalse(out.contains("<EMAIL:"))
    }

    @Test
    fun `sans indicatif de station le fichier reste valide`() {
        val out = Adif.export(listOf(LogEntry(1L, "ISS", 25544, 0.0, 0.0,
            callsign = "F6ABC")))
        assertFalse(out.contains("STATION_CALLSIGN"))
        assertTrue(out.contains("<EOR>"))
    }
}
