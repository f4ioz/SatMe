/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.sonde

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Les sites de lâcher, la bande, et l'heure à laquelle il faut écouter.
 *
 * L'essai le plus important de ce fichier est celui de la borne haute : la
 * bande d'écoute s'arrête à 405,9 MHz parce qu'au-dessus commencent les balises
 * de détresse. Cette limite n'est pas un réglage confortable, c'est une règle,
 * et elle doit rester vérifiée à chaque compilation.
 */
class SondeSitesTest {

    // Le QTH de référence : Brest-Guipavas.
    private val LAT = 48.44425
    private val LON = -4.41238

    @Test
    fun `la bande s arrete avant les balises de detresse`() {
        assertTrue(SondeSites.inBand(SondeSites.SCAN_FROM_HZ))
        assertTrue(SondeSites.inBand(SondeSites.SCAN_TO_HZ))
        assertTrue(!SondeSites.inBand(SondeSites.SCAN_FROM_HZ - 1))
        assertTrue(!SondeSites.inBand(SondeSites.SCAN_TO_HZ + 1))
        // 406 MHz est la fréquence COSPAS-SARSAT : jamais dans le plan.
        assertTrue(!SondeSites.inBand(406_000_000L))
        assertTrue(!SondeSites.inBand(0L))
        assertEquals(400_150_000L, SondeSites.SCAN_FROM_HZ)
        assertEquals(405_900_000L, SondeSites.SCAN_TO_HZ)
        assertEquals(10_000L, SondeSites.SCAN_STEP_HZ)
    }

    @Test
    fun `le catalogue des sites se tient`() {
        assertEquals(SondeSites.FRANCE.size + SondeSites.FRANCE_OCCASIONAL.size +
            SondeSites.NEIGHBOURS.size, SondeSites.ALL.size)
        assertEquals(SondeSites.ALL.size, SondeSites.ALL.map { it.wmo }.toSet().size)
        for (s in SondeSites.ALL) {
            assertTrue("${s.name} sans fréquence", s.freqKhz.isNotEmpty())
            for (k in s.freqKhz) {
                assertTrue("${s.name} hors bande : $k kHz", SondeSites.inBand(k * 1000L))
            }
            assertTrue("${s.name} latitude", s.lat > -90.0 && s.lat < 90.0)
            assertTrue("${s.name} longitude", s.lon >= -180.0 && s.lon <= 180.0)
            assertEquals(s.freqKhz[0] * 1000L, s.mainHz)
        }
        // Les sites français réguliers ont tous l'horaire Météo-France.
        for (s in SondeSites.FRANCE) {
            assertTrue(!s.occasional)
            assertEquals(2, s.launchesUtc.size)
        }
        for (s in SondeSites.FRANCE_OCCASIONAL) assertTrue(s.occasional)
    }

    @Test
    fun `les sites sortent tries par distance`() {
        val n = SondeSites.nearest(LAT, LON)
        assertEquals(6, n.size)
        assertEquals("07110", n[0].first.wmo)
        assertEquals(0.0, n[0].second, 0.1)
        for (k in 1 until n.size) assertTrue(n[k].second >= n[k - 1].second)
        // Par défaut on ne propose pas les sites occasionnels.
        assertTrue(n.none { it.first.occasional })
        val avec = SondeSites.nearest(LAT, LON, max = 10, includeOccasional = true)
        assertTrue(avec.any { it.first.occasional })
        assertEquals(10, avec.size)
    }

    @Test
    fun `le plan de balayage commence par le site le plus proche`() {
        val p = SondeSites.scanPlan(LAT, LON)
        assertEquals(404_000_000L, p[0])
        assertTrue(p.all { SondeSites.inBand(it) })
        assertEquals(p.size, p.toSet().size)          // aucune fréquence deux fois
        // Le balayage complet de la bande au pas de 10 kHz.
        assertEquals(576, p.size)
        assertTrue(p.contains(SondeSites.SCAN_FROM_HZ))
        assertTrue(p.contains(SondeSites.SCAN_TO_HZ))
        // Les canaux des stations proches passent avant le balayage.
        assertTrue(p.indexOf(404_000_000L) < p.indexOf(SondeSites.SCAN_FROM_HZ))
    }

    @Test
    fun `l origine probable reste une presomption`() {
        val o = SondeSites.likelyOrigin(48.5, -4.3, "M20")
        assertNotNull(o)
        assertEquals("07110", o!!.wmo)
        // Au milieu de l'Atlantique, aucun site ne peut être invoqué.
        assertNull(SondeSites.likelyOrigin(30.0, -40.0, "M20"))
        // Un type inconnu ne doit pas faire disparaître la réponse : on retombe
        // sur le site le plus proche, tous types confondus.
        val q = SondeSites.likelyOrigin(48.5, -4.3, "XYZ")
        assertNotNull(q)
        assertEquals("07110", q!!.wmo)
        // Sans type demandé, la réponse est la même.
        assertEquals("07110", SondeSites.likelyOrigin(48.5, -4.3)!!.wmo)
    }

    @Test
    fun `largeur de filtre et debit selon le type`() {
        assertEquals(Meteomodem.BANDWIDTH_HZ, SondeSites.bandwidthFor("M20"))
        assertEquals(Meteomodem.BANDWIDTH_HZ, SondeSites.bandwidthFor("M10"))
        assertEquals(Rs41.BANDWIDTH_HZ, SondeSites.bandwidthFor("RS41"))
        assertEquals(Rs41.BANDWIDTH_HZ, SondeSites.bandwidthFor("DFM"))
        assertEquals(Meteomodem.M20_BAUD, SondeSites.baudFor("M20"), 1e-9)
        assertEquals(Meteomodem.M10_BAUD, SondeSites.baudFor("M10"), 1e-9)
        assertEquals(Rs41.BAUD, SondeSites.baudFor("RS41"), 1e-9)
        assertEquals(Rs41.BAUD, SondeSites.baudFor(""), 1e-9)
    }

    @Test
    fun `les lachers Meteo-France sont a onze minutes de l heure`() {
        assertEquals(671, Math.round(SondeSites.MF_DAY * 60.0).toInt())     // 11 h 11
        assertEquals(1391, Math.round(SondeSites.MF_NIGHT * 60.0).toInt())  // 23 h 11
    }

    @Test
    fun `minutes avant le prochain lacher`() {
        val brest = SondeSites.FRANCE.first { it.wmo == "07110" }
        assertEquals(71, SondeSites.minutesToNextLaunch(brest, 600))    // 10 h 00 UTC
        assertEquals(711, SondeSites.minutesToNextLaunch(brest, 1400))  // 23 h 20 UTC
        assertEquals(0, SondeSites.minutesToNextLaunch(brest, 671))     // pile à l'heure
        // Un site occasionnel n'a pas d'horaire : on le dit au lieu d'inventer.
        val ury = SondeSites.FRANCE_OCCASIONAL.first { it.wmo == "URY" }
        assertEquals(-1, SondeSites.minutesToNextLaunch(ury, 600))
        assertTrue(!SondeSites.listeningNow(ury, 600))
        // Le résultat reste toujours dans la journée.
        for (m in 0 until 1440 step 17) {
            val d = SondeSites.minutesToNextLaunch(brest, m)
            assertTrue("à $m minutes : $d", d in 0..1439)
        }
    }

    @Test
    fun `la fenetre d ecoute couvre le vol entier`() {
        val brest = SondeSites.FRANCE.first { it.wmo == "07110" }
        // Une demi-heure après le lâcher de nuit : la sonde monte encore.
        assertTrue(SondeSites.listeningNow(brest, 1421))
        // Cinq minutes avant le lâcher de jour : on ouvre en avance.
        assertTrue(SondeSites.listeningNow(brest, 666))
        // Vingt minutes avant : trop tôt, et le vol du matin est fini depuis
        // longtemps.
        assertTrue(!SondeSites.listeningNow(brest, 651))
        // Trois heures après le lâcher de jour, la sonde est au sol.
        assertTrue(SondeSites.listeningNow(brest, 671 + 180))
        assertTrue(!SondeSites.listeningNow(brest, 671 + 181))
    }
}
