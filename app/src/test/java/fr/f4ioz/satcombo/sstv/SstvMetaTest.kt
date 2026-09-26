/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.sstv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le nom de fichier et le fichier annexe d'une image SSTV.
 *
 * Ce qui est vérifié ici, c'est qu'une image écrite pendant un passage se
 * relit correctement des mois plus tard, y compris si son fichier annexe a
 * disparu — un dossier copié à la main vers la galerie du téléphone ne garde
 * souvent que les PNG.
 */
class SstvMetaTest {

    // 14 novembre 2023, 22 h 13 min 20 s UTC.
    private val t = 1_700_000_000_000L

    @Test
    fun `le nom porte satellite, heure UTC et mode`() {
        val n = SstvMeta.fileName("ISS", t, "PD120", true)
        assertEquals("SatMe_SSTV_ISS_20231114_221320Z_PD120.png", n)
    }

    @Test
    fun `une image coupee est marquee partielle`() {
        val n = SstvMeta.fileName("ISS", t, "Robot36", false)
        assertTrue(n.endsWith("_partiel.png"))
        assertFalse(SstvMeta.parseName(n).complete)
    }

    @Test
    fun `un nom de satellite exotique ne casse pas la relecture`() {
        // « ISS (ZARYA) » traverse mal un système de fichiers, et un tiret bas
        // dans le nom décalerait la lecture de la date.
        val n = SstvMeta.fileName("ISS (ZARYA)", t, "Martin 1", true)
        val s = SstvMeta.parseName(n)
        assertEquals("ISS--ZARYA", s.satName)
        assertEquals("Martin1", s.mode)
        assertEquals(t, s.timeMs)
    }

    @Test
    fun `aller-retour complet sur un nom de fichier`() {
        val n = SstvMeta.fileName("NOAA-15", t, "PD180", true)
        val s = SstvMeta.parseName(n)
        assertEquals("NOAA-15", s.satName)
        assertEquals("PD180", s.mode)
        assertEquals(t, s.timeMs)
        assertTrue(s.complete)
    }

    @Test
    fun `un nom etranger ne fait pas exploser la relecture`() {
        val s = SstvMeta.parseName("photo_vacances.png")
        assertEquals("photo_vacances.png", s.fileName)
        assertEquals("", s.satName)
        assertEquals(0L, s.timeMs)
    }

    @Test
    fun `une date impossible ne devient pas une fausse date`() {
        val s = SstvMeta.parseName("SatMe_SSTV_ISS_20239999_221320Z_PD120.png")
        assertEquals(0L, s.timeMs)
    }

    @Test
    fun `le fichier annexe fait l-aller-retour`() {
        val shot = SstvMeta.SstvShot(
            fileName = "SatMe_SSTV_ISS_20231114_221320Z_PD120.png",
            satName = "ISS", timeMs = t, mode = "PD120", complete = true,
            locator = "JN18FS", callsign = "F4IOZ", source = "live",
            note = "beau passage")
        val back = SstvMeta.decode(shot.fileName, SstvMeta.encode(shot))
        assertEquals(shot.satName, back.satName)
        assertEquals(shot.timeMs, back.timeMs)
        assertEquals(shot.mode, back.mode)
        assertEquals(shot.locator, back.locator)
        assertEquals(shot.callsign, back.callsign)
        assertEquals(shot.source, back.source)
        assertEquals(shot.note, back.note)
        assertTrue(back.complete)
    }

    @Test
    fun `sans fichier annexe le nom suffit encore`() {
        val n = SstvMeta.fileName("ISS", t, "PD120", true)
        val s = SstvMeta.decode(n, null)
        assertEquals("ISS", s.satName)
        assertEquals(t, s.timeMs)
        assertEquals("", s.locator)
    }

    @Test
    fun `une ligne abimee ne coute que son propre champ`() {
        val n = SstvMeta.fileName("ISS", t, "PD120", true)
        val s = SstvMeta.decode(n, "locator=JN18FS\nn'importe quoi\ncall=F4IOZ\n")
        assertEquals("JN18FS", s.locator)
        assertEquals("F4IOZ", s.callsign)
    }

    @Test
    fun `l-annexe d-une image partielle le dit`() {
        val shot = SstvMeta.SstvShot(fileName = "x.png", satName = "ISS", complete = false)
        assertFalse(SstvMeta.decode("x.png", SstvMeta.encode(shot)).complete)
    }

    @Test
    fun `le fichier annexe se range a cote de l-image`() {
        assertEquals("SatMe_SSTV_ISS_20231114_221320Z_PD120.meta",
            SstvMeta.sidecarName("SatMe_SSTV_ISS_20231114_221320Z_PD120.png"))
    }

    @Test
    fun `une image APT se nomme et se relit comme une image SSTV`() {
        val n = SstvMeta.fileName("NOAA 19", 1_700_000_000_000L, "APT", true, kind = "APT")
        assertEquals("SatMe_APT_NOAA-19_20231114_221320Z_APT.png", n)
        val s = SstvMeta.parseName(n)
        assertEquals("NOAA-19", s.satName)
        assertEquals("APT", s.mode)
        assertEquals(1_700_000_000_000L, s.timeMs)
        assertTrue(s.complete)
    }

    @Test
    fun `une famille d-image inconnue retombe sur SSTV`() {
        val n = SstvMeta.fileName("ISS", 1_700_000_000_000L, "PD120", true, kind = "RTTY")
        assertTrue(n, n.startsWith("SatMe_SSTV_"))
    }
}
